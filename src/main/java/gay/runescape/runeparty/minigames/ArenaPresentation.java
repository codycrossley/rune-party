package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.models.ArenaFireModel;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Arena ("Flame Field")'s own client-side state -- see minigames/arena.py's own doc on the server
 * for the full reasoning. Deliberately event-driven rather than continuously polled, same shape
 * {@link BrutusAttackPresentation} already established: instead of every seated player pinging
 * their live position every tick for the server to read back (the old reportMinigamePosition/
 * MinigameContext.get_positions mechanism, since removed entirely -- Turf Wars/Who's Your Jaddy
 * were the last two mini-games still using it, before they too moved off it, see DECISIONS.md's
 * project-wide call behind that), each client watches the ARENA_TILE grid it already receives via
 * ordinary tile-color broadcasts (see {@link ArenaFireModel#isDead}) and self-checks its OWN
 * current position against it every real game tick (see {@link #onTick}, called from
 * RunePartyPlugin#onGameTick -- a client-thread cache read, no network cost at all):
 * <p>
 * - confirmArrival -- one-shot, position-free "I'm on the grid" report, fired once the instant this
 *   client's own position first lands on any ARENA_TILE this round. Purely records
 *   ARENA_ARRIVAL_CONFIRMED; the server's own _wait_for_everyone_to_arrive (a plain server-internal
 *   poll, no client cost) is what notices once every seated PLAYER has confirmed and fires
 *   MINIGAME_ROUND_BEGIN.
 * - confirmElimination -- one-shot self-report, fired the instant this client locally detects
 *   either (a) its own current tile has turned the server's own permanently-dead red
 *   ({@link ArenaFireModel#isDead}), or (b) it had already confirmed arrival and is now standing on
 *   no ARENA_TILE at all. Trusted outright by the server, same "the stakes here (a flat coin
 *   reward) don't warrant a server-side re-check" reasoning BrutusAttackPresentation's own doc
 *   already gives for Brutus Attack's own self-reports.
 * <p>
 * Net effect: every seated player sends at most 2 requests total for the whole round (arrival,
 * elimination) rather than one every 600ms for as long as the round runs. No fold of its own --
 * ARENA_PLAYER_ELIMINATED is still handled directly in RunePartyPlugin's own event switch as a
 * one-shot cosmetic reveal (spotanim + chat message), same as before this class existed; this class
 * only ever originates that event's confirming request, never reacts to it. */
public final class ArenaPresentation implements MinigamePresentationFeature
{
    // Same duration MageArenaPresentation's own banner uses -- see EliminationBanner's own doc.
    private static final long ELIMINATION_BANNER_DURATION_MS = 1800;

    private final RunePartyPlugin plugin;

    // Reset on onStarted/reset, same shape BrutusAttackPresentation's own per-round guards use.
    private final ArrivalGate arrivalGate;
    private volatile boolean eliminationReported = false;

    // Persistent for the rest of the round (never cleared until onStarted/reset), unlike the
    // transient banner below -- read by PlayerOverlay's own skull icon, same "Hot Potato" treatment
    // HotPotatoPresentation's own eliminatedRsns already established. Lowercase rsn.
    private final Set<String> eliminatedRsns = ConcurrentHashMap.newKeySet();

    // Armed from RunePartyPlugin's own ARENA_PLAYER_ELIMINATED handling (not folded here via
    // apply() -- see this class' own doc on why that event is still handled directly in the
    // plugin's switch). See EliminationBanner's own doc for the local/others split.
    private final EliminationBanner eliminationBanner;

    public ArenaPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.arrivalGate = new ArrivalGate(plugin, "Arena", "the flame field",
            (self, gid, token) -> plugin.apiClient.confirmArenaArrival(gid, self, token));
        this.eliminationBanner = new EliminationBanner(plugin, ELIMINATION_BANNER_DURATION_MS);
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while Arena is active. Fires
     * at most one confirmArrival (the instant this player's own position first lands on any
     * ARENA_TILE) and, past that, at most one confirmElimination for the round -- either from
     * standing on a tile that's already turned dead, or from being off the grid entirely after
     * having genuinely arrived. Once eliminationReported flips true, this is a no-op for the rest
     * of the round: there's nothing further worth checking or reporting. */
    public void onTick(Player selfPlayer)
    {
        if (selfPlayer == null || eliminationReported) return;
        WorldPoint pos = selfPlayer.getWorldLocation();
        if (pos == null) return;
        String self = plugin.getLocalRsn();
        if (self == null) return;

        List<WorldPoint> grid = plugin.findArenaGridTiles();
        boolean onGrid = grid.contains(pos);
        arrivalGate.confirmIfMatched(onGrid);

        if (onGrid)
        {
            if (plugin.isArenaTileDead(pos))
            {
                eliminationReported = true;
                confirmElimination(self);
            }
        }
        else if (arrivalGate.isConfirmed())
        {
            eliminationReported = true;
            confirmElimination(self);
        }
    }

    private void confirmElimination(String self)
    {
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (gid == null || token == null) return;

        plugin.submitAction("Confirm Arena elimination",
            () -> plugin.apiClient.confirmArenaElimination(gid, self, token),
            e -> plugin.addChatMessage("Failed to confirm your Flame Field elimination: " + e.getMessage()));
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        arrivalGate.reset();
        eliminationReported = false;
        eliminatedRsns.clear();
        eliminationBanner.reset();
    }

    @Override
    public void reset()
    {
        arrivalGate.reset();
        eliminationReported = false;
        eliminatedRsns.clear();
        eliminationBanner.reset();
    }

    /** Lowercase rsns eliminated so far this round -- see PlayerOverlay's own persistent skull icon,
     * the only reader. Unlike the transient banners below, this is real, durable round state: it
     * never clears or gets overwritten mid-round, so an elimination is never "missed" just because
     * the viewer wasn't looking at the right moment or another elimination's own flash stole the
     * single shared banner slot an earlier version of this class used. */
    public Set<String> getEliminatedRsns() { return eliminatedRsns; }

    /** Arms Flame Field's own "&lt;rsn&gt; was eliminated!"/"You were eliminated!" flash -- called
     * from RunePartyPlugin's own ARENA_PLAYER_ELIMINATED handling (not folded here via apply(), see
     * this class' own doc), right alongside the existing spotanim/chat message and the persistent
     * skull icon above, since the chat line alone was easy to miss mid-round. See EliminationBanner
     * for the local/others split's own reasoning. */
    public void armEliminationBanner(String rsn)
    {
        if (rsn == null) return;
        eliminatedRsns.add(rsn.toLowerCase(Locale.ROOT));
        eliminationBanner.arm(rsn);
    }

    public long getLocalEliminationBannerUntil() { return eliminationBanner.getLocalUntil(); }
    public long getOthersEliminationBannerUntil() { return eliminationBanner.getOthersUntil(); }
    public String getOthersEliminationBannerRsn() { return eliminationBanner.getOthersRsn(); }
}
