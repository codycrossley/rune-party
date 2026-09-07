package gay.runescape.runeparty.session;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.RunePartyRole;
import gay.runescape.runeparty.TimedBanner;
import gay.runescape.runeparty.courses.HardcodedCourse;
import gay.runescape.runeparty.net.ApiClient;
import lombok.extern.slf4j.Slf4j;

/** Game session lifecycle, extracted out of RunePartyPlugin: create/join/start/end/leave a game,
 * host-only roster gating (isGameFull/assignRole/removePlayer), and session persistence/
 * reconnection (persistSession/attemptSessionResume, plus the backlog-then-live-socket handoff in
 * connectEventStream). Not a "Presentation" -- owns real request/response behavior and disk I/O,
 * not cosmetic banner/timing state (the one exception, welcomeBanner, is small enough to just come
 * along for the ride -- its only two writers, createGame/joinGame, both live here). Two sub-
 * concerns share this one class for now (create/join/start/end/leave vs. persistence/reconnect) --
 * they're bridged by connectEventStream, called from both sides, which is why splitting them
 * further wasn't worth the awkward cross-calling it'd take; a future second split remains a
 * natural, low-risk follow-up if this keeps growing.
 * <p>
 * gameId/writeKey/playerToken/phase/currentTurnRsn/lastDiceRoll/standardCourseKey all stay on
 * RunePartyPlugin itself -- too heavily shared (turn engine, minigames, course building, isHost(),
 * dozens of gating checks elsewhere) to move -- and are read/written here as plain public fields,
 * same convention RunePartyPlugin already uses for them. RunePartyPlugin still exposes every
 * getter/action under its original name, just delegating here. */
@Slf4j
public final class SessionManager
{
    private final RunePartyPlugin plugin;

    private volatile String joinCode = null;
    private volatile String hostRsn = null;
    private static final String SESSION_CONFIG_GROUP = "runeparty-session";
    // Attempted at most once per plugin lifetime (see attemptSessionResume, the only writer) --
    // guards RunePartyPlugin#onGameTick's own call site against re-attempting every tick while
    // waiting for the local player's own rsn to become available after login.
    private volatile boolean sessionResumeAttempted = false;
    // ---- welcome title card (client-side, local-player-only -- see triggerWelcomeBanner) ----
    private final TimedBanner<Void> welcomeBanner = new TimedBanner<>();

    public SessionManager(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void createGame()
    {
        String host = plugin.getLocalRsn();
        if (host == null) return;

        plugin.submitAction("Create game", () ->
        {
            ApiClient.CreateGameResult result = plugin.apiClient.createGame(host);
            applyCreateGameResult(result, host);
            plugin.addChatMessage("Created Rune Party game. Join code: " + result.joinCode);
            triggerWelcomeBanner();
        }, e -> plugin.addChatMessage("Failed to create game: " + e.getMessage()), plugin::refreshPanel);
    }

    /** Same as createGame(), but for a hard-coded course's own launcher -- creates the game
     * exactly the same way, then commits that course's whole tile set (including its
     * GOLDEN_GNOME_TILE stacked on START, see HardcodedCourse's own doc) in one extra mark-tiles
     * call before announcing success, so the host lands in LOBBY with the course already built. A
     * third call, lockStandardCourse, then permanently locks this game to that course. */
    public void createGameFromHardcodedCourse(HardcodedCourse course)
    {
        String host = plugin.getLocalRsn();
        if (host == null) return;

        plugin.submitAction("Create game", () ->
        {
            ApiClient.CreateGameResult result = plugin.apiClient.createGame(host);
            applyCreateGameResult(result, host);
            plugin.apiClient.markTiles(plugin.gameId, plugin.writeKey, course.tiles);
            plugin.apiClient.lockStandardCourse(plugin.gameId, plugin.writeKey, course.key);
            plugin.addChatMessage("Created Rune Party game (" + course.name + "). Join code: " + result.joinCode);
            triggerWelcomeBanner();
        }, e -> plugin.addChatMessage("Failed to create game: " + e.getMessage()), plugin::refreshPanel);
    }

    /** The session field-assignment shared by createGame()/createGameFromHardcodedCourse() --
     * factored out purely so the hard-coded-course path can't drift from the ordinary one. */
    private void applyCreateGameResult(ApiClient.CreateGameResult result, String host)
    {
        plugin.gameId = result.gameId;
        joinCode = result.joinCode;
        plugin.writeKey = result.writeKey;
        plugin.playerToken = result.playerToken;
        hostRsn = host;
        plugin.phase = GamePhase.LOBBY;
        persistSession();
        connectEventStream(plugin.gameId, host);
    }

    public void joinGame(String code)
    {
        String self = plugin.getLocalRsn();
        if (self == null) return;

        plugin.submitAction("Join game", () ->
        {
            ApiClient.JoinResult result = plugin.apiClient.joinGame(code, self);
            plugin.gameId = result.gameId;
            hostRsn = result.hostRsn;
            plugin.playerToken = result.playerToken;
            plugin.writeKey = null;
            joinCode = code;
            plugin.phase = GamePhase.LOBBY;
            persistSession();
            connectEventStream(plugin.gameId, self);
            plugin.addChatMessage("Joined Rune Party game hosted by " + result.hostRsn);
            triggerWelcomeBanner();
        }, e -> plugin.addChatMessage("Failed to join game: " + e.getMessage()), plugin::refreshPanel);
    }

    /** {@code maxRounds} is turns-per-player -- the host sets it in the panel right before
     * starting (see RunePartyPanel's spinner). The server won't insert the first TURN_STARTED
     * itself; that only happens once every seated PLAYER reports standing on the START tile (see
     * RunePartyPlugin#confirmStart / onGameTick's gathering check). */
    public void startGame(int maxRounds)
    {
        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        if (gid == null || wk == null || maxRounds <= 1) return;

        plugin.submitAction("Start game", () -> plugin.apiClient.startGame(gid, wk, maxRounds),
            e -> plugin.addChatMessage("Failed to start game: " + e.getMessage()));
    }

    /** Host-only: ends the game for everyone, distinct from leaveGame() which only removes the
     * caller. The resulting GAME_ENDED event (see RunePartyPlugin#handleEvent) is what actually
     * flips phase to ENDED for every connected client, this call and leaveGame() both just request
     * it. */
    public void endGame()
    {
        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        if (gid == null || wk == null) return;

        plugin.submitAction("End game", () -> plugin.apiClient.endGame(gid, wk),
            e -> plugin.addChatMessage("Failed to end game: " + e.getMessage()));
    }

    public void leaveGame()
    {
        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        clearPersistedSession();
        if (self == null || gid == null || token == null) { plugin.resetState(); return; }

        plugin.submitAction("Leave game", () -> plugin.apiClient.leaveGame(gid, self, token));
        plugin.resetState();
    }

    /** Whether the turn order already has MAX_PLAYERS seats filled -- the client-side gate on
     * "Add to Game" (menu entry and roster popup both check this). The server doesn't currently
     * enforce this cap itself, so it's a UI guard rather than a real limit. */
    public boolean isGameFull()
    {
        return plugin.getRosterReducer().countRole(RunePartyRole.PLAYER) >= RunePartyPlugin.MAX_PLAYERS;
    }

    /** Host-only: promotes a spectator into the turn order (or, symmetrically, could demote a
     * player back to spectator). Joining a game only ever grants SPECTATOR -- see ApiClient.assignRole
     * -- so this is the only path onto the roster's turn order. colorNumber is the host's own
     * explicit seat-color choice for a PLAYER promotion (see RunePartyPlugin#addToGameMenuEntry/
     * RunePartyPanel#buildAddToGamePopup, both of which pass one) -- null for a SPECTATOR demotion. */
    public void assignRole(String playerRsn, RunePartyRole role, Integer colorNumber)
    {
        if (!plugin.isHost() || plugin.gameId == null) return;

        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        plugin.submitAction("Assign role", () -> plugin.apiClient.assignRole(gid, wk, playerRsn, role, colorNumber),
            e -> plugin.addChatMessage("Failed to update " + playerRsn + "'s role: " + e.getMessage()));
    }

    /** Host-only kick, wired to the roster panel's "Remove Player" right-click entry -- same
     * PLAYER_LEFT outcome as the target leaving on their own, so they drop out of turn order and
     * free their seat color for a new player. A returning player gets whatever color the host
     * picks for them at that point, rather than automatically reclaiming their old one. */
    public void removePlayer(String playerRsn)
    {
        if (!plugin.isHost() || plugin.gameId == null) return;

        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        plugin.submitAction("Remove player", () -> plugin.apiClient.removePlayer(gid, wk, playerRsn),
            e -> plugin.addChatMessage("Failed to remove " + playerRsn + ": " + e.getMessage()));
    }

    /** Arms AnnouncementOverlay's "Welcome to Rune Party Showdown" title card -- called once, right
     * after createGame/joinGame succeeds, for the local player only (there's no server event for
     * this; it's purely a client-side "you're in!" splash, so it never fires for anyone already in
     * the lobby when someone else joins). Not an armBanner call (see that method's own doc) --
     * arms synchronously with no scheduleAfterTurnEffects wrapper, nothing to collapse. */
    private void triggerWelcomeBanner()
    {
        welcomeBanner.until = System.currentTimeMillis() + RunePartyPlugin.WELCOME_BANNER_DURATION_MS;
    }

    /** Saves the current session (gameId/writeKey/playerToken/joinCode, keyed by the local RSN
     * that owns it) to ConfigManager, so attemptSessionResume can recover it after a plugin
     * restart -- called once right after createGame/joinGame's own field assignments succeed, and
     * again after attemptSessionResume itself succeeds (to hand a fresh playerToken forward to
     * whatever restart comes next, see that method's non-host branch). A no-op if nothing's
     * actually joined yet. */
    private void persistSession()
    {
        String self = plugin.getLocalRsn();
        if (self == null || plugin.gameId == null) return;

        plugin.configManager.setConfiguration(SESSION_CONFIG_GROUP, "rsn", self);
        plugin.configManager.setConfiguration(SESSION_CONFIG_GROUP, "gameId", plugin.gameId);
        plugin.configManager.setConfiguration(SESSION_CONFIG_GROUP, "joinCode", joinCode != null ? joinCode : "");
        if (plugin.writeKey != null) plugin.configManager.setConfiguration(SESSION_CONFIG_GROUP, "writeKey", plugin.writeKey);
        else plugin.configManager.unsetConfiguration(SESSION_CONFIG_GROUP, "writeKey");
        if (plugin.playerToken != null) plugin.configManager.setConfiguration(SESSION_CONFIG_GROUP, "playerToken", plugin.playerToken);
        else plugin.configManager.unsetConfiguration(SESSION_CONFIG_GROUP, "playerToken");
    }

    private void clearPersistedSession()
    {
        plugin.configManager.unsetConfiguration(SESSION_CONFIG_GROUP, "rsn");
        plugin.configManager.unsetConfiguration(SESSION_CONFIG_GROUP, "gameId");
        plugin.configManager.unsetConfiguration(SESSION_CONFIG_GROUP, "joinCode");
        plugin.configManager.unsetConfiguration(SESSION_CONFIG_GROUP, "writeKey");
        plugin.configManager.unsetConfiguration(SESSION_CONFIG_GROUP, "playerToken");
    }

    /** One-shot attempt (see hasAttemptedResume) to recover a session persistSession saved before
     * this plugin instance existed -- called from RunePartyPlugin#onGameTick once the local
     * player's RSN is actually known (a fresh plugin start races the login screen, so this can't
     * just run from startUp()). Two genuinely different recovery paths depending on what was
     * persisted:
     *
     * <p>Host (writeKey present): the persisted writeKey is the only copy that will ever exist --
     * the server never reissues one -- so this either still works right now, or that game can
     * never be hosted again from any client. Confirmed via checkHostSession, a read-only call,
     * before this client resumes acting as host with it.
     *
     * <p>Player (no writeKey): nothing irreplaceable was lost -- rejoining with the same RSN via
     * the ordinary joinGame call transparently reissues a fresh playerToken for the same seat, so
     * there's no dedicated resume endpoint for this case at all.
     *
     * <p>Only clears the persisted session on a definitive server rejection (403/404/409): a plain
     * IOException (server unreachable, no network yet at plugin startup) leaves it alone so the
     * next restart gets another try, rather than a transient hiccup silently costing someone their
     * host status for good. */
    public void attemptSessionResume()
    {
        String self = plugin.getLocalRsn();
        if (self == null) return; // not logged in yet -- retry next tick, don't mark attempted

        sessionResumeAttempted = true;

        String savedRsn = plugin.configManager.getConfiguration(SESSION_CONFIG_GROUP, "rsn");
        String savedGameId = plugin.configManager.getConfiguration(SESSION_CONFIG_GROUP, "gameId");
        if (savedRsn == null || savedGameId == null) return; // nothing to resume

        if (!self.equalsIgnoreCase(savedRsn))
        {
            clearPersistedSession(); // a different account logged in on this machine/profile
            return;
        }

        String savedJoinCode = plugin.configManager.getConfiguration(SESSION_CONFIG_GROUP, "joinCode");
        String savedWriteKey = plugin.configManager.getConfiguration(SESSION_CONFIG_GROUP, "writeKey");
        String savedPlayerToken = plugin.configManager.getConfiguration(SESSION_CONFIG_GROUP, "playerToken");

        plugin.submitAction("Resume session", () ->
        {
            if (savedWriteKey != null && !savedWriteKey.isEmpty())
            {
                ApiClient.HostSessionInfo info = plugin.apiClient.checkHostSession(savedGameId, savedWriteKey);
                if ("ENDED".equals(info.status)) { clearPersistedSession(); return; }

                plugin.gameId = savedGameId;
                plugin.writeKey = savedWriteKey;
                plugin.playerToken = savedPlayerToken;
                joinCode = info.joinCode;
                hostRsn = info.hostRsn;
                plugin.phase = "ACTIVE".equals(info.status) ? GamePhase.ACTIVE : GamePhase.LOBBY;
                persistSession();
                connectEventStream(plugin.gameId, self);
                plugin.addChatMessage("Resumed hosting Rune Party game. Join code: " + info.joinCode);
            }
            else if (savedJoinCode != null && !savedJoinCode.isEmpty())
            {
                ApiClient.JoinResult result = plugin.apiClient.joinGame(savedJoinCode, self);
                plugin.gameId = result.gameId;
                hostRsn = result.hostRsn;
                plugin.playerToken = result.playerToken;
                plugin.writeKey = null;
                joinCode = savedJoinCode;
                plugin.phase = GamePhase.LOBBY; // corrected immediately by GAME_STARTED if the backlog replay below shows it's actually ACTIVE
                persistSession();
                connectEventStream(plugin.gameId, self);
                plugin.addChatMessage("Resumed Rune Party session hosted by " + result.hostRsn);
            }
            else
            {
                clearPersistedSession();
            }
        },
        e ->
        {
            if (e instanceof ApiClient.ApiHttpException && ((ApiClient.ApiHttpException) e).code < 500)
            {
                log.debug("Rune Party session no longer resumable, clearing", e);
                clearPersistedSession();
            }
            else
            {
                log.warn("Could not check for a resumable Rune Party session (will retry next restart)", e);
            }
        }, plugin::refreshPanel);
    }

    /** Silently replays a game's full event history via a one-time REST fetch before opening the
     * live WebSocket -- otherwise, since EventSocket's initial connect always asks for every event
     * from the beginning, a player joining a game already in progress would see every banner,
     * popup, and dice-roll animation from the whole game so far fire in rapid succession as that
     * backlog replayed. Real game state still updates from every historical event exactly as it
     * would live -- see RunePartyPlugin#handleEvent's catchingUp parameter, which decides "state
     * always applies, cosmetic timers/banners/chat only when live" for every event type. Once the
     * backlog is applied, the live socket starts from the backlog's own latestSeq, so nothing
     * replays twice. Falls back to the old full-live-replay behavior if the backlog fetch itself
     * fails. */
    private void connectEventStream(String gameId, String rsn)
    {
        try
        {
            ApiClient.ReadEventsResponse backlog = plugin.apiClient.readEvents(gameId, 0);
            for (ApiClient.EventOut event : backlog.events)
            {
                plugin.handleEvent(event, true);
            }
            syncRosterSnapshot(true); // one fresh roster read covers every PLAYER_JOINED/ROLE_ASSIGNED/PLAYER_LEFT skipped above, instead of one REST call per historical event
            plugin.refreshPanel();
            plugin.eventSocket.start(gameId, backlog.latestSeq, rsn);
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch event backlog before connecting -- falling back to a full live replay", e);
            plugin.eventSocket.start(gameId, rsn);
        }
    }

    /** Pulls a fresh roster snapshot from the server -- called after every historical event a
     * catch-up backlog skipped (see connectEventStream), on every live reconnect (see
     * RunePartyPlugin's own EventSocket#onCaughtUp listener), and on any ordinary
     * PLAYER_JOINED/ROLE_ASSIGNED/PLAYER_LEFT (see RunePartyPlugin#handleEvent), since none of
     * those carry a turn-order "number" of their own -- the server only ever computes it fresh
     * from the whole event log, and it can shift for everyone whenever the PLAYER set changes.
     * {@code reconcileGameState} additionally corrects phase/currentTurnRsn/lastDiceRoll from the
     * snapshot -- only appropriate right after a (re)connect, not for an ordinary mid-game roster
     * change, which already gets those from the live event stream itself. */
    public void syncRosterSnapshot(boolean reconcileGameState)
    {
        final String gid = plugin.gameId;
        if (gid == null) return;

        plugin.executor.submit(() ->
        {
            try
            {
                ApiClient.RosterSnapshot snapshot = plugin.apiClient.fetchRoster(gid);
                plugin.getRosterReducer().syncFromRoster(snapshot.players);
                if (reconcileGameState)
                {
                    try { plugin.phase = GamePhase.valueOf(snapshot.status); }
                    catch (IllegalArgumentException | NullPointerException ignored) { }
                    plugin.currentTurnRsn = snapshot.currentTurnRsn;
                    plugin.lastDiceRoll = snapshot.lastDiceRoll;
                }
            }
            catch (Exception ex)
            {
                log.warn("Fetch roster failed", ex);
            }
            plugin.refreshPanel();
        });
    }

    /** Whether attemptSessionResume has already run once this plugin instance's lifetime -- see
     * RunePartyPlugin#onGameTick, the only reader, which stops retrying once this flips true
     * (successful or not). Deliberately never reset by a whole-game reset() below -- resuming is a
     * once-per-plugin-restart concern, not a once-per-game one. */
    public boolean hasAttemptedResume() { return sessionResumeAttempted; }

    public String getJoinCode() { return joinCode; }
    public String getHostRsn() { return hostRsn; }
    public long getWelcomeBannerUntil() { return welcomeBanner.until; }

    public void reset()
    {
        joinCode = null;
        hostRsn = null;
        welcomeBanner.reset();
    }
}
