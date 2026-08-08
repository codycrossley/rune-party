package gay.runescape.runeparty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.AnimationID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;

/** Entry point and state hub for Rune Party -- mirrors GnomeballPlugin's role in the sibling
 * gnomeball repo (see that repo's README for the architecture this is modeled on). The turn
 * engine talks to a real server (../rune-party server skeleton) over the same
 * report-then-wait-for-the-echo pattern Gnomeball's client uses: action methods below only ever
 * *request* something (roll, arrival, purchase, minigame result); the authoritative outcome
 * always comes back through handleEvent(). */
@Slf4j
@PluginDescriptor(name = "Rune Party")
public class RunePartyPlugin extends Plugin
{
    /** Caps the turn order at the size of RunePartyColor's palette, so every PLAYER always gets a
     * distinct color and the host never has to decide who shares one. */
    public static final int MAX_PLAYERS = 8;

    /** How long AnnouncementOverlay's "<player>'s Turn" banner stays up after TURN_STARTED -- a
     * purely client-side timer (like Gnomeball's goal-flash duration), not anything the server
     * tracks. */
    public static final long TURN_ANNOUNCE_DURATION_MS = 2500;

    /** How long AnnouncementOverlay's "Welcome to Rune Party Showdown" title card stays up after
     * successfully creating/joining a game -- see triggerWelcomeBanner. Shown once, client-side
     * only, to whoever just created/joined; nobody else sees it. */
    public static final long WELCOME_BANNER_DURATION_MS = 4000;

    /** How long AnnouncementOverlay's "MINIGAME!" banner stays up once it actually appears --
     * server-driven (unlike the welcome banner), so every client shows it at the same moment. The
     * appearance itself is delayed until the last roller's own turn effects settle; see
     * scheduleMinigameBanner. */
    public static final long MINIGAME_BANNER_DURATION_MS = 2800;

    /** How long AnnouncementOverlay's "HERE WE GO!" banner stays up after GAME_STARTED -- fires
     * the instant the host's Start Game click lands, no turnEffectGateUntil delay needed since
     * nothing can be mid-effect before the game has even started. Server-driven, so every client
     * (host and joiners alike) sees it at the same moment. */
    public static final long GAME_START_BANNER_DURATION_MS = 3200;

    /** How long PlayerOverlay's coin popup shows "+3" (or "-3") before switching to the player's
     * new running total -- see PlayerOverlay#drawCoinPopup, which is the only other place these
     * three get read from (kept here rather than duplicated as private constants there, so
     * lengthening one phase can't silently eat into another's screen time the way a
     * separately-hardcoded total once did). Purely a client-side timer, not anything the server
     * tracks. */
    public static final long COIN_POPUP_DELTA_PHASE_MS = 2000;
    /** How long the popup then holds on the running total (its last COIN_POPUP_FADE_MS of this
     * spent fading out) before disappearing. */
    public static final long COIN_POPUP_TOTAL_PHASE_MS = 1800;
    /** Tail-end fade shared by both phases' transition out -- carved out of COIN_POPUP_TOTAL_PHASE_MS
     * above, not additional time. */
    public static final long COIN_POPUP_FADE_MS = 400;
    /** Total popup lifetime, derived from the two phases above -- this is what actually gets
     * stamped as coinPopupUntil; nothing should hardcode this independently again. */
    public static final long COIN_POPUP_DURATION_MS = COIN_POPUP_DELTA_PHASE_MS + COIN_POPUP_TOTAL_PHASE_MS;

    /** Extra breathing room after a turn's in-flight visual effects (currently just the coin
     * popup; see extendTurnEffectGate) finish before whatever announcement comes next -- the next
     * "<player>'s Turn" banner, or "MINIGAME!" -- is allowed to appear, so e.g. "+3 coins" -> new
     * total never gets stepped on by something popping up over top of it. Only actually adds delay
     * when a turn effect is still in flight; see scheduleAfterTurnEffects. */
    public static final long POST_TURN_EFFECT_GRACE_MS = 500;

    /** How long AnnouncementOverlay's screen-centered retro dice cycles through random faces once
     * the roll actually starts -- see onAnimationChanged, which delays calling rollDice() until
     * the local player's Spin emote animation finishes, so this cosmetic re-cycling never overlaps
     * the emote itself. Kept here (not a private constant on AnnouncementOverlay) for the same
     * reason as the coin popup phase constants above: so DICE_ROLL_DURATION_MS below can be
     * derived from it instead of drifting out of sync. */
    public static final long DICE_ROLL_SPIN_PHASE_MS = 900;
    /** How long the die then holds on the real rolled value before fading (its last
     * DICE_ROLL_FADE_MS spent fading out). */
    public static final long DICE_ROLL_HOLD_MS = 2000;
    /** Tail-end fade, carved out of DICE_ROLL_HOLD_MS above, not additional time. */
    public static final long DICE_ROLL_FADE_MS = 350;
    /** Total time AnnouncementOverlay's die stays visible after a DICE_ROLLED event, derived from
     * the two phases above -- this is what actually gets stamped as diceRollUntil; nothing should
     * hardcode this independently again. */
    public static final long DICE_ROLL_DURATION_MS = DICE_ROLL_SPIN_PHASE_MS + DICE_ROLL_HOLD_MS;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private RunePartyConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private ModelOutlineRenderer modelOutlineRenderer;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;

    private TileReducer tileReducer;
    private TileOverlay tileOverlay;
    private StatsOverlay statsOverlay;
    private PlayerOverlay playerOverlay;
    private AnnouncementOverlay announcementOverlay;
    private RosterReducer rosterReducer;
    private ApiClient apiClient;
    private EventSocket eventSocket;
    private RunePartyPanel panel;
    private NavigationButton navButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "runeparty-actions");
        t.setDaemon(true);
        return t;
    });

    // Dedicated to delayed, purely-cosmetic UI timers (see scheduleTurnAnnouncement) -- kept
    // separate from `executor` above so a pending delay can never queue behind (or block) a real
    // network call.
    private final ScheduledExecutorService uiTimerExec = Executors.newSingleThreadScheduledExecutor(r ->
    {
        Thread t = new Thread(r, "runeparty-ui-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> turnAnnounceTask;
    private volatile ScheduledFuture<?> minigameBannerTask;

    /** Rolling "nothing turn-concluding should appear before this" gate. Every turn-effect visual
     * with its own on-screen duration -- currently just the coin popup, but meant to grow as more
     * tile effects gain their own reveal animations -- pushes this forward via
     * extendTurnEffectGate when it starts. Anything that announces "the turn is over, here's
     * what's next" (the next TURN_STARTED banner, the MINIGAME! banner, and any future one)
     * schedules itself against this single shared timestamp via scheduleAfterTurnEffects instead
     * of hardcoding its own "is some specific effect still showing?" check -- so a new effect only
     * ever needs to touch this one field, not every downstream announcement. Never moves backward:
     * an effect that lands while another is still settling extends the gate rather than shortening
     * it. */
    private volatile long turnEffectGateUntil = 0;

    private volatile GamePhase phase = GamePhase.DISCONNECTED;

    // ---- session ----
    private volatile String gameId = null;
    private volatile String writeKey = null; // non-null only for the host
    private volatile String playerToken = null;
    private volatile String joinCode = null;
    private volatile String hostRsn = null;

    // ---- course building (host, LOBBY only) ----
    private volatile boolean coursePlacementMode = false;
    private volatile CoursePreset selectedPreset = null;
    private volatile int presetRotationSteps = 0; // quarter-turns clockwise: 0/1/2/3 = 0/90/180/270 degrees

    // ---- turn engine ----
    private volatile String currentTurnRsn = null;
    private volatile Integer lastDiceRoll = null;
    private volatile boolean pendingRoll = false;
    // Guards the Spin-emote roll trigger against double-submitting while a roll request is in
    // flight but the server's DICE_ROLLED echo (which flips pendingRoll) hasn't landed yet -- see
    // onAnimationChanged and rollDice().
    private volatile boolean rollRequestSubmitted = false;
    // True from the moment the local player's Spin emote starts (on their own turn) until it
    // finishes -- onAnimationChanged only actually calls rollDice() on the animation change that
    // clears this, so the roll never fires mid-emote.
    private volatile boolean awaitingSpinFinish = false;
    // Candidate destination tiles for the current roll -- more than one when the roll's path
    // crosses a fork (see TileOverlay#renderTargetArrow, which draws one arrow per candidate).
    // Never null, only ever empty.
    private volatile List<Integer> pendingTargetIndices = Collections.emptyList();
    private volatile boolean arrivalSubmitted = false; // guards confirm-arrival from firing every tick while the echo is in flight
    private volatile boolean minigameActive = false;
    private volatile String minigameInstructions = null;

    // Every player's current board position (pathIndex), keyed by lowercase rsn -- mirrors the
    // server's own state["positions"], kept in sync purely by replaying PLAYER_MOVED events (see
    // handleEvent). Since EventSocket always connects with afterSeq=0 (see start/createGame/
    // joinGame), a fresh or reconnecting client replays every PLAYER_MOVED since the game began, so
    // this ends up correct even without a dedicated snapshot endpoint. A player with no entry yet
    // is on pathIndex 0 (START), same default the server uses. See TileOverlay#
    // renderReturnToPositionArrow, which is what actually uses this to gate re-rolling.
    private final Map<String, Integer> playerPositions = new ConcurrentHashMap<>();

    // ---- pre-game gathering (GAME_STARTED fired, but currentTurnRsn still null -- see confirmStart) ----
    private volatile boolean startConfirmSubmitted = false; // guards confirm-start firing every tick while the echo is in flight

    // ---- instructional overlays (client-side timers, not server state -- see AnnouncementOverlay) ----
    private volatile String turnAnnounceRsn = null;
    private volatile long turnAnnounceUntil = 0;

    // ---- welcome title card (client-side, local-player-only -- see triggerWelcomeBanner) ----
    private volatile long welcomeBannerUntil = 0;

    // ---- minigame banner (server-driven, everyone sees it -- see MINIGAME_STARTED handling) ----
    private volatile long minigameBannerUntil = 0;

    // ---- game-start banner (server-driven, everyone sees it -- see GAME_STARTED handling) ----
    private volatile long gameStartBannerUntil = 0;

    // ---- coin popup (client-side timer -- see PlayerOverlay#drawCoinPopup) ----
    private volatile String coinPopupRsn = null;
    private volatile int coinPopupDelta = 0;
    private volatile int coinPopupNewTotal = 0;
    private volatile long coinPopupStart = 0;
    private volatile long coinPopupUntil = 0;

    // ---- dice roll popup (client-side timer -- see PlayerOverlay#drawDiceRoll) ----
    private volatile String diceRollRsn = null;
    private volatile int diceRollValue = 0;
    private volatile long diceRollStart = 0;
    private volatile long diceRollUntil = 0;

    @Override
    protected void startUp()
    {
        log.debug("Rune Party starting up");

        apiClient = new ApiClient(okHttpClient, gson);
        rosterReducer = new RosterReducer();
        tileReducer = new TileReducer();

        tileOverlay = new TileOverlay(client, config, this, tileReducer);
        overlayManager.add(tileOverlay);

        statsOverlay = new StatsOverlay(config, this);
        overlayManager.add(statsOverlay);

        playerOverlay = new PlayerOverlay(client, config, this, rosterReducer, modelOutlineRenderer);
        overlayManager.add(playerOverlay);

        announcementOverlay = new AnnouncementOverlay(client, config, this);
        overlayManager.add(announcementOverlay);

        panel = new RunePartyPanel(this);
        navButton = NavigationButton.builder()
            .tooltip("Rune Party")
            .icon(buildPlaceholderIcon())
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        eventSocket = new EventSocket(okHttpClient, gson, new EventListener()
        {
            @Override public void onEvent(ApiClient.EventOut e) { handleEvent(e); }
            @Override public void onError(Exception e) { log.debug("EventSocket error", e); }
        });
    }

    @Override
    protected void shutDown()
    {
        log.debug("Rune Party shutting down");
        if (eventSocket != null) eventSocket.shutdown();
        executor.shutdownNow();
        uiTimerExec.shutdownNow();
        if (tileOverlay != null) overlayManager.remove(tileOverlay);
        if (statsOverlay != null) overlayManager.remove(statsOverlay);
        if (playerOverlay != null) overlayManager.remove(playerOverlay);
        if (announcementOverlay != null) overlayManager.remove(announcementOverlay);
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        resetState();
    }

    /** Drawn in code rather than loaded from a resource -- there's no real icon asset yet, and a
     * flat placeholder is enough to give the sidebar a tab until someone draws actual artwork. */
    private static BufferedImage buildPlaceholderIcon()
    {
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 210, 0));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(Color.BLACK);
        g.fillOval(4, 4, 3, 3);
        g.fillOval(9, 4, 3, 3);
        g.fillOval(4, 9, 3, 3);
        g.fillOval(9, 9, 3, 3);
        g.dispose();
        return icon;
    }

    @Provides
    RunePartyConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RunePartyConfig.class);
    }

    // -------------------------------------------------------------------------
    // Session actions -- each of these only ever requests something; the
    // authoritative result always arrives back through handleEvent().
    // -------------------------------------------------------------------------

    public void createGame()
    {
        String host = localRsn();
        if (host == null) return;

        executor.submit(() ->
        {
            try
            {
                ApiClient.CreateGameResult result = apiClient.createGame(host);
                gameId = result.gameId;
                joinCode = result.joinCode;
                writeKey = result.writeKey;
                playerToken = result.playerToken;
                hostRsn = host;
                phase = GamePhase.LOBBY;
                eventSocket.start(gameId, host);
                addChatMessage("Created Rune Party game. Join code: " + result.joinCode);
                triggerWelcomeBanner();
            }
            catch (Exception e)
            {
                log.warn("Create game failed", e);
                addChatMessage("Failed to create game: " + e.getMessage());
            }
            refreshPanel();
        });
    }

    public void joinGame(String code)
    {
        String self = localRsn();
        if (self == null) return;

        executor.submit(() ->
        {
            try
            {
                ApiClient.JoinResult result = apiClient.joinGame(code, self);
                gameId = result.gameId;
                hostRsn = result.hostRsn;
                playerToken = result.playerToken;
                writeKey = null;
                joinCode = code;
                phase = GamePhase.LOBBY;
                eventSocket.start(gameId, self);
                addChatMessage("Joined Rune Party game hosted by " + result.hostRsn);
                triggerWelcomeBanner();
            }
            catch (Exception e)
            {
                log.warn("Join game failed", e);
                addChatMessage("Failed to join game: " + e.getMessage());
            }
            refreshPanel();
        });
    }

    /** {@code maxRounds} is turns-per-player -- the host sets it in the panel right before
     * starting (see RunePartyPanel's spinner). The server won't insert the first TURN_STARTED
     * itself; that only happens once every seated PLAYER reports standing on the START tile (see
     * confirmStart / onGameTick's gathering check below). */
    public void startGame(int maxRounds)
    {
        final String gid = gameId;
        final String wk = writeKey;
        if (gid == null || wk == null || maxRounds <= 1) return;

        executor.submit(() ->
        {
            try { apiClient.startGame(gid, wk, maxRounds); }
            catch (Exception e)
            {
                log.warn("Start game failed", e);
                addChatMessage("Failed to start game: " + e.getMessage());
            }
        });
    }

    /** Host-only: ends the game for everyone, distinct from leaveGame() which only removes the
     * caller. The resulting GAME_ENDED event (see handleEvent) is what actually flips phase to
     * ENDED for every connected client, this call and leaveGame() both just request it. */
    public void endGame()
    {
        final String gid = gameId;
        final String wk = writeKey;
        if (gid == null || wk == null) return;

        executor.submit(() ->
        {
            try { apiClient.endGame(gid, wk); }
            catch (Exception e)
            {
                log.warn("End game failed", e);
                addChatMessage("Failed to end game: " + e.getMessage());
            }
        });
    }

    public void rollDice()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;
        if (!self.equalsIgnoreCase(currentTurnRsn) || pendingRoll || rollRequestSubmitted) return;

        rollRequestSubmitted = true;
        executor.submit(() ->
        {
            try { apiClient.rollDice(gid, self, token); }
            catch (Exception e)
            {
                rollRequestSubmitted = false; // let a retry (another Spin) through
                log.warn("Roll dice failed", e);
                addChatMessage("Failed to roll dice: " + e.getMessage());
            }
        });
    }

    /** Reports arrival at the tile a roll resolved to. Called automatically from onGameTick once
     * the local player's position matches the pending destination -- see checkPendingArrival(). */
    private void confirmArrival(WorldPoint pos)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.confirmArrival(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()); }
            catch (Exception e)
            {
                log.warn("Confirm arrival failed", e);
                addChatMessage("Failed to confirm arrival: " + e.getMessage());
                arrivalSubmitted = false; // let the next tick retry
            }
        });
    }

    /** Reports the local player standing on the START tile during the pre-game gathering window
     * (GAME_STARTED fired, currentTurnRsn still null). Called automatically from onGameTick --
     * see the gathering check there -- once every seated PLAYER has confirmed, the server inserts
     * the first TURN_STARTED itself. */
    private void confirmStart(WorldPoint pos)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.confirmStart(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()); }
            catch (Exception e)
            {
                log.warn("Confirm start failed", e);
                addChatMessage("Failed to confirm ready: " + e.getMessage());
                startConfirmSubmitted = false; // let the next tick retry
            }
        });
    }

    public void purchaseGnomeball()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.purchaseGnomeball(gid, self, token); }
            catch (Exception e)
            {
                log.warn("Purchase gnomeball failed", e);
                addChatMessage("Failed to purchase the gilded gnomeball: " + e.getMessage());
            }
        });
    }

    public void submitMinigameResult(int score)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.submitMinigameResult(gid, self, token, score); }
            catch (Exception e)
            {
                log.warn("Submit minigame result failed", e);
                addChatMessage("Failed to submit mini-game result: " + e.getMessage());
            }
        });
    }

    public void leaveGame()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) { resetState(); return; }

        executor.submit(() ->
        {
            try { apiClient.leaveGame(gid, self, token); }
            catch (Exception e) { log.warn("Leave game failed", e); }
        });
        resetState();
    }

    /** Whether the turn order already has MAX_PLAYERS seats filled -- the client-side gate on
     * "Add to Game" (menu entry and roster popup both check this). The server doesn't currently
     * enforce this cap itself, so it's a UI guard rather than a real limit -- consistent with the
     * rest of this app's "friendly pickup game" trust model (see Gnomeball's LIMITATIONS.md). */
    public boolean isGameFull()
    {
        return rosterReducer.countRole(RunePartyRole.PLAYER) >= MAX_PLAYERS;
    }

    /** Host-only: promotes a spectator into the turn order (or, symmetrically, could demote a
     * player back to spectator). Joining a game only ever grants SPECTATOR -- see ApiClient.assignRole
     * -- so this is the only path onto the roster's turn order. */
    public void assignRole(String playerRsn, RunePartyRole role)
    {
        if (!isHost() || gameId == null) return;

        final String gid = gameId;
        final String wk = writeKey;
        executor.submit(() ->
        {
            try { apiClient.assignRole(gid, wk, playerRsn, role); }
            catch (Exception e)
            {
                log.warn("Assign role failed", e);
                addChatMessage("Failed to update " + playerRsn + "'s role: " + e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Course building (host, LOBBY only) -- same placement flow as Gnomeball's
    // field builder: pick a preset, enter Place mode, then right-click a
    // ground tile to commit its footprint there. There's no per-preset
    // removal -- clearCourse() below is the host's one "start over" tool.
    // -------------------------------------------------------------------------

    public void selectPreset(CoursePreset preset)
    {
        selectedPreset = preset;
        refreshPanel();
    }

    public void enterCoursePlacementMode()
    {
        coursePlacementMode = true;
        refreshPanel();
    }

    public void cancelPresetMode()
    {
        coursePlacementMode = false;
        refreshPanel();
    }

    public void rotatePresetNext()
    {
        presetRotationSteps = (presetRotationSteps + 1) % 4;
    }

    /** Unmarks every currently-committed course tile -- the host's "start over" button. */
    public void clearCourse()
    {
        final String gid = gameId;
        final String wk = writeKey;
        if (gid == null || wk == null) return;

        Set<WorldPoint> uniquePoints = new HashSet<>();
        for (TileReducer.TileEntry entry : tileReducer.snapshot()) uniquePoints.add(entry.point);
        if (uniquePoints.isEmpty()) return;

        List<ApiClient.PointSpec> pointSpecs = new ArrayList<>(uniquePoints.size());
        for (WorldPoint wp : uniquePoints)
        {
            pointSpecs.add(new ApiClient.PointSpec(wp.getX(), wp.getY(), wp.getPlane(), null));
        }

        executor.submit(() ->
        {
            try { apiClient.unmarkTiles(gid, wk, pointSpecs); }
            catch (Exception e) { log.warn("Clear course failed", e); }
        });
    }

    private void addPresetMenuEntries()
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint center = tile.getWorldLocation();
        if (center == null) return;
        CoursePreset preset = selectedPreset;
        if (preset == null) return;

        client.createMenuEntry(-1)
            .setOption("Cancel")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelPresetMode());

        client.createMenuEntry(-1)
            .setOption("Rotate Course")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> rotatePresetNext());

        int degrees = presetRotationSteps * 90;
        String suffix = degrees != 0 ? " (" + degrees + "°)" : "";
        client.createMenuEntry(-1)
            .setOption("<col=00FF00>Place " + preset.name + suffix + "</col>")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> commitPreset(center));
    }

    private void commitPreset(WorldPoint center)
    {
        CoursePreset preset = selectedPreset;
        int rotationSteps = presetRotationSteps;
        cancelPresetMode();
        if (!isHost() || gameId == null || preset == null) return;

        List<CoursePreset.PlacedTile> placed = preset.layout(center, rotationSteps);
        List<ApiClient.TileSpec> tileSpecs = new ArrayList<>(placed.size());
        for (int i = 0; i < placed.size(); i++)
        {
            CoursePreset.PlacedTile pt = placed.get(i);
            // List order IS path order (see CoursePreset's own class doc) -- this is the one
            // place that turns "position i in the list" into an explicit pathIndex, since once
            // this leaves as a TileSpec the server/TileReducer only ever see unordered tiles.
            tileSpecs.add(new ApiClient.TileSpec(pt.point.getX(), pt.point.getY(), pt.point.getPlane(), pt.tileType, pt.color, null, i, pt.nextIndices));
        }

        final String gid = gameId;
        final String wk = writeKey;
        executor.submit(() ->
        {
            try { apiClient.markTiles(gid, wk, tileSpecs); }
            catch (Exception e) { log.warn("Commit course failed", e); }
        });
    }

    // -------------------------------------------------------------------------
    // Movement -- detect arrival at a rolled destination the same way Gnomeball
    // detects zone/out-of-bounds crossings: watch the local player's position
    // every tick rather than relying on a click/animation trigger.
    // -------------------------------------------------------------------------

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (phase != GamePhase.ACTIVE) return;

        // GAME_STARTED fired but turn order hasn't begun yet (currentTurnRsn still null) -- this
        // is the gathering window AnnouncementOverlay/TileOverlay's start-tile arrow cover; watch
        // for the local player reaching the START tile instead of a rolled destination.
        if (currentTurnRsn == null)
        {
            checkGatheringAtStart();
            return;
        }

        if (!pendingRoll || arrivalSubmitted) return;

        String self = localRsn();
        if (self == null || !self.equalsIgnoreCase(currentTurnRsn)) return;

        Player localPlayer = client.getLocalPlayer();
        WorldPoint pos = localPlayer != null ? localPlayer.getWorldLocation() : null;
        if (pos == null) return;

        Integer indexHere = tileReducer.pathIndexAt(pos);
        if (indexHere == null || !pendingTargetIndices.contains(indexHere)) return;

        arrivalSubmitted = true;
        confirmArrival(pos);
    }

    private void checkGatheringAtStart()
    {
        if (startConfirmSubmitted) return;

        String self = localRsn();
        if (self == null) return;
        if (rosterReducer.getRole(self) != RunePartyRole.PLAYER) return; // only seated players need to report in

        Player localPlayer = client.getLocalPlayer();
        WorldPoint pos = localPlayer != null ? localPlayer.getWorldLocation() : null;
        if (pos == null) return;

        // The START tile is always path index 0 by construction (see CoursePreset), so this
        // doesn't need a dedicated TileReducer lookup for tileType==START.
        TileReducer.TileEntry start = tileReducer.tileAtIndex(0);
        if (start == null || !start.point.equals(pos)) return;

        startConfirmSubmitted = true;
        confirmStart(pos);
    }

    // -------------------------------------------------------------------------
    // Menu entries -- course placement/removal during LOBBY (mirrors Gnomeball's
    // field builder) and a host-only "Add to Game" on other players' Follow
    // option (mirrors Gnomeball's Follow -> Enlist). There's no dedicated
    // in-world button for course building, so "Walk here" on the relevant tile
    // is the entry point, same as Gnomeball's approach. Rolling dice is a
    // gesture trigger instead (see onAnimationChanged) rather than a menu entry.
    // -------------------------------------------------------------------------

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if ("Follow".equals(event.getOption()))
        {
            addToGameMenuEntry(event);
            return;
        }

        if (!"Walk here".equals(event.getOption())) return;
        if (phase == GamePhase.LOBBY && isHost() && coursePlacementMode)
        {
            addPresetMenuEntries();
        }
    }

    /** Rolls the dice once the local player's Spin emote finishes on their own turn -- replaces the
     * old "right-click your tile -> Roll Dice" menu entry with a gesture trigger. Only reacts to
     * the local player's own animation (every client sees every nearby player's AnimationChanged,
     * so this would otherwise also fire for spectators watching someone else spin for fun). Waits
     * for the *next* animation change away from the Spin ID -- i.e. the emote actually finishing,
     * not just starting -- so the roll (and the screen-centered dice reveal every client sees, see
     * AnnouncementOverlay#renderDiceRoll) never fires mid-emote; awaitingSpinFinish is what carries
     * that wait across the two AnimationChanged firings. Also requires actually standing on the
     * tile tracked in playerPositions -- if a player wandered off their last landed tile before
     * their next turn, spinning in place does nothing until they walk back (see TileOverlay#
     * renderReturnToPositionArrow, which is what tells them to). This gate never applies during a
     * mini-game or any other non-turn state, since currentTurnRsn is null/stale then and this whole
     * method already requires it to match the local player. rollDice() itself re-checks turn/pending
     * state, this is just what decides *when* to call it. */
    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (phase != GamePhase.ACTIVE) return;

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || event.getActor() != localPlayer) return;

        if (localPlayer.getAnimation() == AnimationID.EMOTE_DANCE_SPIN)
        {
            if (pendingRoll || rollRequestSubmitted) return;
            String self = localRsn();
            if (self == null || !self.equalsIgnoreCase(currentTurnRsn)) return;
            if (!isStandingOnTrackedPosition(localPlayer, self)) return;
            awaitingSpinFinish = true;
            return;
        }

        if (!awaitingSpinFinish) return;
        awaitingSpinFinish = false;
        rollDice();
    }

    /** Whether {@code localPlayer} is standing on {@code rsn}'s tracked board position -- see
     * getPlayerPosition and onAnimationChanged, the only caller. False (not just "unknown") if the
     * course isn't marked or the local player's position isn't resolvable, same fail-closed
     * behavior as everywhere else that resolves a WorldPoint against the course. */
    private boolean isStandingOnTrackedPosition(Player localPlayer, String rsn)
    {
        WorldPoint pos = localPlayer.getWorldLocation();
        if (pos == null) return false;
        TileReducer.TileEntry tile = tileReducer.tileAtIndex(getPlayerPosition(rsn));
        return tile != null && tile.point.equals(pos);
    }

    /** Adds an "Add to Game" entry on another player's Follow option, host-only, so the host can
     * pull a spectator into the turn order without them running the join flow themselves --
     * joining only ever grants SPECTATOR (see assignRole's doc). Hidden once the target is already
     * a PLAYER, same as Gnomeball's Enlist submenu skipping the enlisted player's current role. */
    private void addToGameMenuEntry(MenuEntryAdded event)
    {
        if (!isHost() || gameId == null) return;
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return;
        if (!(event.getMenuEntry().getActor() instanceof Player)) return;

        Player target = (Player) event.getMenuEntry().getActor();
        if (target == null || target == client.getLocalPlayer() || target.getName() == null) return;

        String targetRsn = Text.toJagexName(target.getName());
        if (targetRsn == null || targetRsn.isBlank()) return;
        if (rosterReducer.getRole(targetRsn) == RunePartyRole.PLAYER) return;
        if (isGameFull()) return;

        client.createMenuEntry(-1)
            .setOption("Add to Game")
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE_PLAYER)
            .setIdentifier(event.getIdentifier())
            .onClick(me -> assignRole(targetRsn, RunePartyRole.PLAYER));
    }

    /** Pulls a fresh /roster snapshot and merges it into RosterReducer -- the only source for the
     * turn-order "number" every RunePartyColor lookup (roster panel, PlayerOverlay, TileOverlay's
     * target arrow) depends on, since it never travels in the event stream itself. */
    private void syncRosterSnapshot()
    {
        final String gid = gameId;
        if (gid == null) return;

        executor.submit(() ->
        {
            try
            {
                ApiClient.RosterSnapshot snapshot = apiClient.fetchRoster(gid);
                rosterReducer.syncFromRoster(snapshot.players);
            }
            catch (Exception ex)
            {
                log.warn("Fetch roster failed", ex);
            }
            refreshPanel();
        });
    }

    /** Pushes turnEffectGateUntil forward to at least {@code untilTimestamp} -- called by whatever
     * just started a turn-effect visual with its own on-screen duration (currently only the
     * COINS_CHANGED handler, passing coinPopupUntil). A future tile effect with its own timed
     * reveal (a teleport animation, a "steal coins" flourish, whatever comes next) should call this
     * the same way when it starts, and nothing else needs to change -- every "what's next"
     * announcement already waits on this one gate via scheduleAfterTurnEffects. Never moves the
     * gate backward, so two effects landing close together both get their own full window. */
    private void extendTurnEffectGate(long untilTimestamp)
    {
        turnEffectGateUntil = Math.max(turnEffectGateUntil, untilTimestamp);
    }

    /** Schedules {@code action} to run once every in-flight turn-effect visual has cleared (see
     * extendTurnEffectGate) plus a short POST_TURN_EFFECT_GRACE_MS beat, so an outgoing effect and
     * an incoming "turn's over" announcement never visually collide -- runs immediately (still off
     * the caller's thread) if nothing is currently gating. Cancels {@code previousTask} first, since
     * a stray double-fire of the caller (there shouldn't be one, but see EventSocket's
     * reconnect-task pattern for the same defensive cancel-before-reschedule) would otherwise leave
     * two competing delayed writes in flight; returns the new task so the caller can do the same on
     * its next call. Shared by scheduleTurnAnnouncement and the MINIGAME_STARTED handler -- anything
     * else that announces "the turn is over" should go through this too rather than growing its own
     * bespoke delay math. */
    private ScheduledFuture<?> scheduleAfterTurnEffects(ScheduledFuture<?> previousTask, Runnable action)
    {
        if (previousTask != null) previousTask.cancel(false);

        long now = System.currentTimeMillis();
        long delay = turnEffectGateUntil > now ? (turnEffectGateUntil - now) + POST_TURN_EFFECT_GRACE_MS : 0;

        return uiTimerExec.schedule(action, delay, TimeUnit.MILLISECONDS);
    }

    /** Schedules AnnouncementOverlay's "<player>'s Turn" banner via scheduleAfterTurnEffects, so it
     * never appears while e.g. the previous mover's coin popup is still settling. */
    private void scheduleTurnAnnouncement(String rsn)
    {
        turnAnnounceTask = scheduleAfterTurnEffects(turnAnnounceTask, () ->
        {
            turnAnnounceRsn = rsn;
            turnAnnounceUntil = System.currentTimeMillis() + TURN_ANNOUNCE_DURATION_MS;
        });
    }

    /** Schedules AnnouncementOverlay's "MINIGAME!" banner via scheduleAfterTurnEffects, so it never
     * appears while the last roller's own turn -- including their coin popup -- is still settling.
     * minigameActive/minigameInstructions are set immediately in the MINIGAME_STARTED handler,
     * unaffected by this delay: this only postpones the celebratory banner, not the mini-game
     * itself. */
    private void scheduleMinigameBanner()
    {
        minigameBannerTask = scheduleAfterTurnEffects(minigameBannerTask, () ->
            minigameBannerUntil = System.currentTimeMillis() + MINIGAME_BANNER_DURATION_MS);
    }

    /** Arms AnnouncementOverlay's "Welcome to Rune Party Showdown" title card -- called once, right
     * after createGame/joinGame succeeds, for the local player only (there's no server event for
     * this; it's purely a client-side "you're in!" splash, so it never fires for anyone already in
     * the lobby when someone else joins). */
    private void triggerWelcomeBanner()
    {
        welcomeBannerUntil = System.currentTimeMillis() + WELCOME_BANNER_DURATION_MS;
    }

    // -------------------------------------------------------------------------
    // Server-pushed events
    // -------------------------------------------------------------------------

    private void handleEvent(ApiClient.EventOut e)
    {
        if (e == null || e.type == null) return;

        rosterReducer.apply(e);
        tileReducer.apply(e);

        switch (e.type.toUpperCase(Locale.ROOT))
        {
            case "GAME_STARTED":
                phase = GamePhase.ACTIVE;
                // currentTurnRsn stays null here -- see confirmStart/checkGatheringAtStart, turn
                // order doesn't actually begin until every seated PLAYER reports being at START.
                startConfirmSubmitted = false;
                gameStartBannerUntil = System.currentTimeMillis() + GAME_START_BANNER_DURATION_MS;
                break;

            case "GAME_ENDED":
                phase = GamePhase.ENDED;
                break;

            case "PLAYER_READY":
                addChatMessage(safeStr(e.payload, "player") + " is ready at the start!");
                break;

            // None of these three carry a turn-order "number" in their payload -- the server only
            // ever computes it fresh from the whole event log on a roster read (see
            // _finalize_roster in app.py), and it can shift for everyone whenever the PLAYER set
            // changes (a join, a promotion, a leave). So on any of them, pull a fresh roster
            // snapshot rather than trying to derive numbers from the event stream itself.
            case "PLAYER_JOINED":
            case "ROLE_ASSIGNED":
            case "PLAYER_LEFT":
                syncRosterSnapshot();
                break;

            case "TURN_STARTED":
            {
                currentTurnRsn = safeStr(e.payload, "player");
                pendingRoll = false;
                rollRequestSubmitted = false;
                awaitingSpinFinish = false;
                lastDiceRoll = null;
                pendingTargetIndices = Collections.emptyList();
                arrivalSubmitted = false;
                scheduleTurnAnnouncement(currentTurnRsn);
                String self = localRsn();
                if (self != null && self.equalsIgnoreCase(currentTurnRsn))
                {
                    addChatMessage("It's your turn! Use the Spin emote to roll the dice.");
                }
                break;
            }

            case "DICE_ROLLED":
            {
                lastDiceRoll = safeInt(e.payload, "value");
                pendingTargetIndices = safeIntList(e.payload, "targetIndices");
                pendingRoll = true;
                rollRequestSubmitted = false; // pendingRoll is now the authoritative in-flight guard
                arrivalSubmitted = false;
                String roller = safeStr(e.payload, "player");
                addChatMessage(roller + " rolled a " + lastDiceRoll + "!");
                if (lastDiceRoll != null)
                {
                    diceRollRsn = roller;
                    diceRollValue = lastDiceRoll;
                    diceRollStart = System.currentTimeMillis();
                    diceRollUntil = diceRollStart + DICE_ROLL_DURATION_MS;
                }
                break;
            }

            case "PLAYER_MOVED":
            {
                String mover = safeStr(e.payload, "player");
                Integer toIndex = safeInt(e.payload, "toIndex");
                if (mover != null && toIndex != null)
                {
                    playerPositions.put(mover.toLowerCase(Locale.ROOT), toIndex);
                }
                break;
            }

            case "TILE_EFFECT":
            {
                // PATH is the only tile type with a real effect so far (see the COINS_CHANGED
                // case below, which is what actually pays it out) -- every other type is still a
                // no-op, but the event fires for all of them so this chat line is always accurate.
                addChatMessage(safeStr(e.payload, "player") + " landed on a " + safeStr(e.payload, "tileType") + " tile.");
                break;
            }

            case "COINS_CHANGED":
            {
                // Only the standard-tile reward gets the popup treatment for now -- a purchase or
                // mini-game payout already has its own feedback (the roster/stats panels update,
                // and submitMinigameResult's caller sees the MINIGAME_ENDED chat line), so this
                // stays scoped to the one case that otherwise had no visible feedback at all.
                if ("standard_tile".equals(safeStr(e.payload, "reason")))
                {
                    coinPopupRsn = safeStr(e.payload, "player");
                    Integer delta = safeInt(e.payload, "delta");
                    Integer total = safeInt(e.payload, "coins");
                    coinPopupDelta = delta != null ? delta : 0;
                    coinPopupNewTotal = total != null ? total : 0;
                    coinPopupStart = System.currentTimeMillis();
                    coinPopupUntil = coinPopupStart + COIN_POPUP_DURATION_MS;
                    extendTurnEffectGate(coinPopupUntil);
                }
                break;
            }

            case "MINIGAME_STARTED":
                // minigameActive/minigameInstructions take effect immediately -- only the
                // celebratory banner waits (see scheduleMinigameBanner) for this turn's own
                // effects (the coin popup, and whatever else lands here in the future) to settle.
                minigameActive = true;
                minigameInstructions = safeStr(e.payload, "instructions");
                scheduleMinigameBanner();
                addChatMessage("Mini-game! " + minigameInstructions);
                break;

            case "MINIGAME_ENDED":
                minigameActive = false;
                minigameInstructions = null;
                addChatMessage(safeStr(e.payload, "winner") + " won the mini-game!");
                break;

            default:
                break;
        }

        refreshPanel();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void addChatMessage(String message)
    {
        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
    }

    private void refreshPanel()
    {
        if (panel != null) SwingUtilities.invokeLater(panel::refresh);
    }

    private String localRsn()
    {
        if (client.getLocalPlayer() == null) return null;
        String name = client.getLocalPlayer().getName();
        return name != null ? Text.toJagexName(name) : null;
    }

    private static String safeStr(JsonObject o, String key)
    {
        return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    private static Integer safeInt(JsonObject o, String key)
    {
        try { return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : null; }
        catch (Exception ignored) { return null; }
    }

    /** Reads DICE_ROLLED's targetIndices -- plural since a fork can offer more than one candidate
     * destination for a single roll (see TileOverlay#renderTargetArrow). Never null, only empty. */
    private static List<Integer> safeIntList(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.get(key).getAsJsonArray();
        List<Integer> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            try { out.add(arr.get(i).getAsInt()); }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        return out;
    }

    private void resetState()
    {
        if (eventSocket != null) eventSocket.stop();
        if (turnAnnounceTask != null) { turnAnnounceTask.cancel(false); turnAnnounceTask = null; }
        if (minigameBannerTask != null) { minigameBannerTask.cancel(false); minigameBannerTask = null; }
        turnEffectGateUntil = 0;
        gameId = null; writeKey = null; playerToken = null; joinCode = null; hostRsn = null;
        phase = GamePhase.DISCONNECTED;
        coursePlacementMode = false; selectedPreset = null; presetRotationSteps = 0;
        currentTurnRsn = null; lastDiceRoll = null; pendingRoll = false; rollRequestSubmitted = false;
        awaitingSpinFinish = false;
        pendingTargetIndices = Collections.emptyList();
        arrivalSubmitted = false; minigameActive = false; minigameInstructions = null;
        playerPositions.clear();
        turnAnnounceRsn = null; turnAnnounceUntil = 0; startConfirmSubmitted = false;
        welcomeBannerUntil = 0;
        minigameBannerUntil = 0;
        gameStartBannerUntil = 0;
        coinPopupRsn = null; coinPopupDelta = 0; coinPopupNewTotal = 0; coinPopupStart = 0; coinPopupUntil = 0;
        diceRollRsn = null; diceRollValue = 0; diceRollStart = 0; diceRollUntil = 0;
        if (rosterReducer != null) rosterReducer.reset();
        if (tileReducer != null) tileReducer.reset();
        refreshPanel();
    }

    // -------------------------------------------------------------------------
    // Getters (consumed by TileOverlay now, PlayerOverlay/stats overlay/panel later)
    // -------------------------------------------------------------------------

    public GamePhase getPhase() { return phase; }
    public TileReducer getTileReducer() { return tileReducer; }
    public RosterReducer getRosterReducer() { return rosterReducer; }
    public String getGameId() { return gameId; }
    public String getJoinCode() { return joinCode; }
    public String getHostRsn() { return hostRsn; }
    public boolean isHost() { return writeKey != null; }
    public boolean isCoursePlacementMode() { return coursePlacementMode; }
    public CoursePreset getSelectedPreset() { return selectedPreset; }
    public int getPresetRotationSteps() { return presetRotationSteps; }
    public String getCurrentTurnRsn() { return currentTurnRsn; }
    public Integer getLastDiceRoll() { return lastDiceRoll; }
    public boolean isPendingRoll() { return pendingRoll; }
    public List<Integer> getPendingTargetIndices() { return pendingTargetIndices; }
    public boolean isMinigameActive() { return minigameActive; }
    /** The board tile (pathIndex) {@code rsn} is currently standing at, per the last PLAYER_MOVED
     * seen for them -- 0 (START) if they haven't moved yet this game. See TileOverlay#
     * renderReturnToPositionArrow, the only consumer. */
    public int getPlayerPosition(String rsn)
    {
        if (rsn == null) return 0;
        Integer idx = playerPositions.get(rsn.toLowerCase(Locale.ROOT));
        return idx != null ? idx : 0;
    }
    public String getMinigameInstructions() { return minigameInstructions; }
    public String getTurnAnnounceRsn() { return turnAnnounceRsn; }
    public long getTurnAnnounceUntil() { return turnAnnounceUntil; }
    public long getWelcomeBannerUntil() { return welcomeBannerUntil; }
    public long getMinigameBannerUntil() { return minigameBannerUntil; }
    public long getGameStartBannerUntil() { return gameStartBannerUntil; }
    public String getCoinPopupRsn() { return coinPopupRsn; }
    public int getCoinPopupDelta() { return coinPopupDelta; }
    public int getCoinPopupNewTotal() { return coinPopupNewTotal; }
    public long getCoinPopupStart() { return coinPopupStart; }
    public long getCoinPopupUntil() { return coinPopupUntil; }
    public String getDiceRollRsn() { return diceRollRsn; }
    public int getDiceRollValue() { return diceRollValue; }
    public long getDiceRollStart() { return diceRollStart; }
    public long getDiceRollUntil() { return diceRollUntil; }
}
