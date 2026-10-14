package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Mage Arena's own client-side state. mageRsn is folded from MAGE_ARENA_ROUND_STARTED (real state,
 * applied catch-up or not -- a catching-up client must know who the Mage is just as much as one
 * that was already connected) -- fired immediately when the round starts, same timing
 * PLAYER_TRANSFORMED uses for Brutus Attack's own role pick, so PlayerOverlay's own team-color split
 * reads correctly from the start rather than only once everyone's reached the arena (see
 * minigames/mage_arena.py's own doc). roundStartAt, by contrast, is stamped in onRoundBegin, not
 * folded from that event -- see that method's own doc for why it needs to anchor to arrival actually
 * closing instead. eliminatedRsns is real state too, same shape BrutusAttackPresentation's own
 * eliminatedRsns uses.
 * <p>
 * <b>No continuous position pinging, and a dodger never reports its own position at all</b> -- see
 * onTick, called once per game tick while this mini-game is active (RunePartyPlugin#onGameTick).
 * Arrival (confirmedArrivalThisRound) is one-shot, position-free, and fires for EVERY seated
 * player, the Mage included -- unlike Brutus Attack, there's no separate "Mage's own zone" a client
 * needs to know about before deciding where to walk (everyone just heads to the one shared arena),
 * so onTick's own arrival check deliberately doesn't gate on mageRsn at all, even though (unlike an
 * earlier version of this class) mageRsn is in fact already known by the time arrival starts.
 * <p>
 * The Mage's own cast (confirmCast) is a direct menu-click action, not a tick-driven self-check --
 * see RunePartyPlugin's own Mage Arena cast handling, the only caller. Every OTHER seated client
 * reacts to the resulting MAGE_ARENA_SPELL_CAST broadcast by starting its own local
 * warning-then-danger countdown the instant it's received (activeCastWarningAt/DetonateAt/
 * ResolvedAt, computed off System.currentTimeMillis() on receipt, same "client starts its own clock
 * on receipt" shape BrutusAttackPresentation's own dash countdown already uses -- no server
 * timestamp travels with the event at all, see that event's own doc). A dodger whose own position
 * matches the cast's target tile during that local danger window self-reports via
 * confirmElimination -- castProcessedForElimination guards this to at most once per cast, reset the
 * instant a new MAGE_ARENA_SPELL_CAST lands (not once per round, since a round has many casts -- the
 * one real adaptation needed from Brutus's own "once per round" one-shot shape). */
public final class MageArenaPresentation implements MinigamePresentationFeature
{
    // Matches the server's own ROUND_DURATION_SECONDS (minigames/mage_arena.py) -- both sides need
    // to agree since this is what the client's own round-timer countdown counts down from.
    private static final long ROUND_DURATION_MS = 45_000;

    // Matches the server's own CAST_WARNING_SECONDS/CAST_DANGER_SECONDS (minigames/mage_arena.py) --
    // every client starts this same countdown off the moment it receives MAGE_ARENA_SPELL_CAST, see
    // this class's own doc for why no server timestamp is needed for it to stay in sync.
    private static final long CAST_WARNING_MS = 600;
    private static final long CAST_DANGER_MS = 600;

    // How long the "<rsn> was eliminated!" flash stays up -- see
    // AnnouncementOverlay#renderMageArenaElimination, the only reader.
    private static final long ELIMINATION_BANNER_DURATION_MS = 1800;

    private final RunePartyPlugin plugin;

    private volatile String mageRsn = null;
    private volatile long roundStartAt = 0;
    private final Set<String> eliminatedRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn

    // One-shot guard for onTick's own arrival report -- fires exactly once per player per mini-game
    // instance (there's only ever one round, no re-gathering the way Brutus's own 3 rounds need),
    // for every seated player including the eventual Mage -- see this class's own doc.
    private volatile boolean confirmedArrivalThisRound = false;

    // The most recently broadcast cast's own target tile and this client's own locally-computed
    // warning/detonate/resolved timestamps -- see this class's own doc. activeCastPoint is null
    // until the first MAGE_ARENA_SPELL_CAST of the round lands.
    private volatile WorldPoint activeCastPoint = null;
    private volatile long activeCastWarningAt = 0;
    private volatile long activeCastDetonateAt = 0;
    private volatile long activeCastResolvedAt = 0;
    // One-shot guard for onTick's own elimination self-check against the CURRENT active cast --
    // reset the instant a new cast lands, not once per round (see this class's own doc).
    private volatile boolean castProcessedForElimination = false;

    private volatile long eliminationBannerUntil = 0;
    private volatile String eliminationBannerRsn = null;

    public MageArenaPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.MAGE_ARENA_ROUND_STARTED:
            {
                // Fires immediately, before the arrival gate even opens -- see this class's own
                // doc and minigames/mage_arena.py's own doc for why the Mage is announced up front
                // (so PlayerOverlay's own team-color split reads correctly from the start) rather
                // than only once everyone's reached the arena. roundStartAt is NOT stamped here --
                // see onRoundBegin below, which anchors the round's own duration countdown to the
                // moment arrival actually closes instead, same "the clock starts once the round
                // visually begins" semantics every other arrival-gated mini-game's own round-timer
                // already uses.
                String rsn = Json.requiredStr(e.payload, type, "player");
                if (rsn == null) return;
                mageRsn = rsn;
                break;
            }

            case Events.MAGE_ARENA_SPELL_CAST:
            {
                // Unconditional, unlike the elimination self-check below -- durable round state
                // (an active cast's own target/timing) needed even for a catching-up client so
                // TileOverlay's own warning highlight doesn't render stale data for a cast that's
                // already long resolved.
                Integer x = Json.requiredInt(e.payload, type, "x");
                Integer y = Json.requiredInt(e.payload, type, "y");
                Integer plane = Json.requiredInt(e.payload, type, "plane");
                String caster = Json.requiredStr(e.payload, type, "player");
                if (x == null || y == null || plane == null || caster == null) return;

                long now = System.currentTimeMillis();
                activeCastPoint = new WorldPoint(x, y, plane);
                activeCastWarningAt = now;
                activeCastDetonateAt = now + CAST_WARNING_MS;
                activeCastResolvedAt = activeCastDetonateAt + CAST_DANGER_MS;
                castProcessedForElimination = false;

                // The spotanim itself and the elimination self-check below are NOT applied catch-up
                // -- a reconnecting client has no business replaying a detonation flash (or
                // self-eliminating) for a cast from before it even joined.
                if (catchingUp) return;

                WorldPoint detonationPoint = activeCastPoint;
                plugin.uiTimerExec.schedule(
                    () -> plugin.triggerSpotAnimAtWorldPoint(RunePartyPlugin.MAGE_ARENA_SPELL_SPOTANIM_ID, detonationPoint),
                    CAST_WARNING_MS, TimeUnit.MILLISECONDS);
                break;
            }

            case Events.MAGE_ARENA_PLAYER_ELIMINATED:
            {
                // Real state, applied catch-up or not -- a catching-up client must know who's
                // already eliminated. The "<rsn> was eliminated!" flash below is a separate,
                // one-shot reveal, gated on !catchingUp same as every other cosmetic reveal
                // elsewhere in this codebase.
                String rsn = Json.requiredStr(e.payload, type, "player");
                if (rsn != null) eliminatedRsns.add(rsn.toLowerCase(Locale.ROOT));
                if (!catchingUp && rsn != null)
                {
                    eliminationBannerRsn = rsn;
                    eliminationBannerUntil = System.currentTimeMillis() + ELIMINATION_BANNER_DURATION_MS;
                    plugin.addChatMessage(rsn + " was eliminated!");
                }
                break;
            }

            default:
                break;
        }
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while this mini-game is
     * active. Fires at most one confirmArrival per player for the whole round (every seated player,
     * the eventual Mage included -- see this class's own doc for why this can't be gated on mageRsn
     * being set yet), plus -- only once the Mage is known, only for a non-Mage, non-eliminated
     * local player, and only during the CURRENT active cast's own local danger window -- at most one
     * confirmElimination per cast. */
    public void onTick(Player selfPlayer)
    {
        if (selfPlayer == null) return;
        WorldPoint pos = selfPlayer.getWorldLocation();
        if (pos == null) return;
        String self = plugin.getLocalRsn();
        if (self == null) return;

        if (!confirmedArrivalThisRound && plugin.findMageArenaTiles().contains(pos))
        {
            confirmedArrivalThisRound = true;
            confirmArrival(self);
        }

        if (mageRsn == null || self.equalsIgnoreCase(mageRsn)) return; // nothing further for the Mage, or before one's assigned
        if (activeCastPoint == null || castProcessedForElimination) return;
        if (eliminatedRsns.contains(self.toLowerCase(Locale.ROOT))) return;

        long now = System.currentTimeMillis();
        if (now < activeCastDetonateAt || now >= activeCastResolvedAt) return;
        if (!pos.equals(activeCastPoint)) return;

        castProcessedForElimination = true;
        confirmElimination(self);
    }

    private void confirmArrival(String self)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Mage Arena arrival",
            () -> plugin.apiClient.confirmMageArenaArrival(gid, self, token),
            e -> plugin.addChatMessage("Failed to confirm your Mage Arena arrival: " + e.getMessage()));
    }

    /** Fires the local player's own cast -- called directly from a menu-click action (see
     * RunePartyPlugin's own Mage Arena cast handling, the only caller), not a tick-driven check:
     * casting is a deliberate action, not a position self-report. */
    public void confirmCast(String self, WorldPoint target)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Mage Arena cast",
            () -> plugin.apiClient.confirmMageArenaCast(gid, self, token, target.getX(), target.getY(), target.getPlane()),
            e -> plugin.addChatMessage("Failed to cast: " + e.getMessage()));
    }

    private void confirmElimination(String self)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Mage Arena elimination",
            () -> plugin.apiClient.confirmMageArenaElimination(gid, self, token),
            e -> plugin.addChatMessage("Failed to confirm your Mage Arena elimination: " + e.getMessage()));
    }

    /** Anchors the round's own ROUND_DURATION_MS countdown to the moment MINIGAME_ROUND_BEGIN
     * actually lands (deferred by MinigamePresentation until the "BEGIN!" flash reveals, since Mage
     * Arena is one of ARRIVAL_GATHER_KEYS) -- not MAGE_ARENA_ROUND_STARTED's own earlier arrival, so
     * the on-screen countdown starts exactly when the round visually begins, same semantics every
     * other arrival-gated mini-game's own round-timer already uses (see RuneMatchPresentation#
     * onRoundBegin for the identical one-line shape). */
    @Override
    public void onRoundBegin(boolean catchingUp)
    {
        roundStartAt = System.currentTimeMillis();
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        reset();
    }

    @Override
    public void reset()
    {
        mageRsn = null;
        roundStartAt = 0;
        eliminatedRsns.clear();
        confirmedArrivalThisRound = false;
        activeCastPoint = null;
        activeCastWarningAt = 0;
        activeCastDetonateAt = 0;
        activeCastResolvedAt = 0;
        castProcessedForElimination = false;
        eliminationBannerUntil = 0;
        eliminationBannerRsn = null;
    }

    /** The rsn currently assigned as the Mage -- null before MAGE_ARENA_ROUND_STARTED lands. */
    public String getMageRsn() { return mageRsn; }
    /** When the round's own clock runs out -- 0 if no round is active yet. Purely a client-side
     * cosmetic countdown; the server's own run() loop is the real authority on when the round
     * actually ends (see minigames/mage_arena.py's own doc). */
    public long getRoundEndsAt() { return roundStartAt != 0 ? roundStartAt + ROUND_DURATION_MS : 0; }
    /** Lowercase rsns eliminated so far this mini-game -- see PlayerOverlay's own reuse of Hot
     * Potato/Brutus Attack's own skull indicator, the only reader. */
    public Set<String> getEliminatedRsns() { return eliminatedRsns; }

    /** The most recently broadcast cast's own target tile -- null until the round's first cast
     * lands. See TileOverlay#renderMageArenaCastWarning, the only reader. */
    public WorldPoint getActiveCastPoint() { return activeCastPoint; }
    /** When the current active cast's own warning highlight should start ramping up. */
    public long getActiveCastWarningAt() { return activeCastWarningAt; }
    /** When the current active cast's own danger window begins (the warning highlight's own ramp
     * ends here). */
    public long getActiveCastDetonateAt() { return activeCastDetonateAt; }
    /** When the current active cast's own danger window ends -- past this point the tile is safe
     * again until the next cast. */
    public long getActiveCastResolvedAt() { return activeCastResolvedAt; }

    /** When the current "<rsn> was eliminated!" flash should disappear -- 0 if none is armed. See
     * AnnouncementOverlay#renderMageArenaElimination, the only reader. */
    public long getEliminationBannerUntil() { return eliminationBannerUntil; }
    /** Whoever the most recent elimination flash is for -- only meaningful while
     * getEliminationBannerUntil() hasn't passed yet. */
    public String getEliminationBannerRsn() { return eliminationBannerRsn; }
}
