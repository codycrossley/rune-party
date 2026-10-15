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
    // Same duration MageArenaPresentation's own eliminationBannerUntil uses for its "<rsn> was
    // eliminated!" flash -- see armEliminationBanner's own doc.
    private static final long ELIMINATION_BANNER_DURATION_MS = 1800;

    private final RunePartyPlugin plugin;

    // Reset on onStarted/reset, same shape BrutusAttackPresentation's own per-round guards use.
    private final ArrivalGate arrivalGate;
    private volatile boolean eliminationReported = false;

    // Persistent for the rest of the round (never cleared until onStarted/reset), unlike the
    // transient banners below -- read by PlayerOverlay's own skull icon, same "Hot Potato" treatment
    // HotPotatoPresentation's own eliminatedRsns already established. Lowercase rsn.
    private final Set<String> eliminatedRsns = ConcurrentHashMap.newKeySet();

    // Armed from RunePartyPlugin's own ARENA_PLAYER_ELIMINATED handling (not folded here via
    // apply() -- see this class' own doc on why that event is still handled directly in the
    // plugin's switch). Split into two independent slots -- see armEliminationBanner's own doc on
    // why -- rather than one shared pair the way an earlier version of this had it.
    private volatile long localEliminationBannerUntil = 0;
    private volatile long othersEliminationBannerUntil = 0;
    private volatile String othersEliminationBannerRsn = null;

    public ArenaPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.arrivalGate = new ArrivalGate(plugin, "Arena", "the flame field",
            (self, gid, token) -> plugin.apiClient.confirmArenaArrival(gid, self, token));
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
        localEliminationBannerUntil = 0;
        othersEliminationBannerUntil = 0;
        othersEliminationBannerRsn = null;
    }

    @Override
    public void reset()
    {
        arrivalGate.reset();
        eliminationReported = false;
        eliminatedRsns.clear();
        localEliminationBannerUntil = 0;
        othersEliminationBannerUntil = 0;
        othersEliminationBannerRsn = null;
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
     * skull icon above, since the chat line alone was easy to miss mid-round.
     * <p>
     * Deliberately routed into one of two completely independent slots depending on whether {@code
     * rsn} is the local viewer, rather than one shared pair: an earlier version of this used a single
     * slot for every elimination, which meant two eliminations landing within the same ~1.8s window
     * -- plausible now that Mage Arena's own cast can catch several dodgers in one "X" blast, and
     * always possible here if Flame Field's heat gradient kills more than one player on the same
     * tick -- would silently stomp each other, with no guarantee the locally-eliminated player's own
     * "You were eliminated!" ever actually stayed on screen long enough to be read. Splitting the
     * local slot out means a bystander's own elimination flash can never steal the one message that
     * matters most to the player it's actually about -- only two LOCAL eliminations in the same
     * window could still collide, which structurally can't happen (a player can only be eliminated
     * once per round). The "others" slot can still be stomped by a second bystander's elimination
     * arriving within the window, same as before, but that's a much lower-stakes case now that the
     * persistent skull icon (see getEliminatedRsns above) means a missed flash is never the only way
     * to learn someone's out. */
    public void armEliminationBanner(String rsn)
    {
        if (rsn == null) return;
        eliminatedRsns.add(rsn.toLowerCase(Locale.ROOT));

        String local = plugin.getLocalRsn();
        long until = System.currentTimeMillis() + ELIMINATION_BANNER_DURATION_MS;
        if (local != null && local.equalsIgnoreCase(rsn))
        {
            localEliminationBannerUntil = until;
        }
        else
        {
            othersEliminationBannerRsn = rsn;
            othersEliminationBannerUntil = until;
        }
    }

    /** When the local viewer's own "You were eliminated!" flash should disappear -- 0 if none is
     * armed. Always about the local viewer by construction, so there's no accompanying rsn getter
     * the way {@link #getOthersEliminationBannerRsn()} needs one. */
    public long getLocalEliminationBannerUntil() { return localEliminationBannerUntil; }

    /** Same idea, for the "&lt;rsn&gt; was eliminated!" flash shown for everyone ELSE'S eliminations
     * -- independent of {@link #getLocalEliminationBannerUntil()} so the two can never stomp each
     * other (see armEliminationBanner's own doc). */
    public long getOthersEliminationBannerUntil() { return othersEliminationBannerUntil; }

    /** Only meaningful while a caller has already confirmed via
     * {@link #getOthersEliminationBannerUntil()} that the banner's own fade hasn't passed yet. */
    public String getOthersEliminationBannerRsn() { return othersEliminationBannerRsn; }
}
