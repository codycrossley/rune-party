package gay.runescape.runeparty;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
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
    private volatile Integer pendingTargetIndex = null;
    private volatile boolean arrivalSubmitted = false; // guards confirm-arrival from firing every tick while the echo is in flight
    private volatile boolean minigameActive = false;
    private volatile String minigameInstructions = null;

    // ---- pre-game gathering (GAME_STARTED fired, but currentTurnRsn still null -- see confirmStart) ----
    private volatile boolean startConfirmSubmitted = false; // guards confirm-start firing every tick while the echo is in flight

    // ---- instructional overlays (client-side timers, not server state -- see AnnouncementOverlay) ----
    private volatile String turnAnnounceRsn = null;
    private volatile long turnAnnounceUntil = 0;

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
        if (!self.equalsIgnoreCase(currentTurnRsn) || pendingRoll) return;

        executor.submit(() ->
        {
            try { apiClient.rollDice(gid, self, token); }
            catch (Exception e)
            {
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
            tileSpecs.add(new ApiClient.TileSpec(pt.point.getX(), pt.point.getY(), pt.point.getPlane(), pt.tileType, pt.color, null, i));
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
        if (indexHere == null || pendingTargetIndex == null || !indexHere.equals(pendingTargetIndex)) return;

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
    // field builder), a host-only "Add to Game" on other players' Follow option
    // (mirrors Gnomeball's Follow -> Enlist), and a "Roll Dice" trigger on your
    // own tile during ACTIVE. There's no dedicated in-world button for course
    // building/rolling, so "Walk here" on the relevant tile is the entry point
    // for both, same as Gnomeball's approach.
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
            return;
        }

        if (phase != GamePhase.ACTIVE) return;

        String self = localRsn();
        if (self == null || !self.equalsIgnoreCase(currentTurnRsn) || pendingRoll) return;

        Player localPlayer = client.getLocalPlayer();
        WorldPoint localPos = localPlayer != null ? localPlayer.getWorldLocation() : null;
        if (localPos == null) return;
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null || !localPos.equals(tile.getWorldLocation())) return;

        client.createMenuEntry(-1)
            .setOption("Roll Dice")
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE)
            .onClick(me -> rollDice());
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
                lastDiceRoll = null;
                pendingTargetIndex = null;
                arrivalSubmitted = false;
                turnAnnounceRsn = currentTurnRsn;
                turnAnnounceUntil = System.currentTimeMillis() + TURN_ANNOUNCE_DURATION_MS;
                String self = localRsn();
                if (self != null && self.equalsIgnoreCase(currentTurnRsn))
                {
                    addChatMessage("It's your turn! Right-click your own tile to Roll Dice.");
                }
                break;
            }

            case "DICE_ROLLED":
            {
                lastDiceRoll = safeInt(e.payload, "value");
                pendingTargetIndex = safeInt(e.payload, "targetIndex");
                pendingRoll = true;
                arrivalSubmitted = false;
                addChatMessage(safeStr(e.payload, "player") + " rolled a " + lastDiceRoll + "!");
                break;
            }

            case "TILE_EFFECT":
            {
                // V1 tile effects are all a no-op -- see the plan's build order. This still
                // surfaces the event to chat so the plumbing is visibly real end-to-end.
                addChatMessage(safeStr(e.payload, "player") + " landed on a " + safeStr(e.payload, "tileType") + " tile.");
                break;
            }

            case "MINIGAME_STARTED":
                minigameActive = true;
                minigameInstructions = safeStr(e.payload, "instructions");
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

    private void resetState()
    {
        if (eventSocket != null) eventSocket.stop();
        gameId = null; writeKey = null; playerToken = null; joinCode = null; hostRsn = null;
        phase = GamePhase.DISCONNECTED;
        coursePlacementMode = false; selectedPreset = null; presetRotationSteps = 0;
        currentTurnRsn = null; lastDiceRoll = null; pendingRoll = false; pendingTargetIndex = null;
        arrivalSubmitted = false; minigameActive = false; minigameInstructions = null;
        turnAnnounceRsn = null; turnAnnounceUntil = 0; startConfirmSubmitted = false;
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
    public Integer getPendingTargetIndex() { return pendingTargetIndex; }
    public boolean isMinigameActive() { return minigameActive; }
    public String getMinigameInstructions() { return minigameInstructions; }
    public String getTurnAnnounceRsn() { return turnAnnounceRsn; }
    public long getTurnAnnounceUntil() { return turnAnnounceUntil; }
}
