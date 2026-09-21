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

/** Hot Potato's own client-side state (server-driven holder assignment). holder is real state,
 * applied catch-up or not: a catching-up client must know who's holding it right now, same as any
 * other real per-round state elsewhere in this package -- null before the round's own initial
 * random assignment lands. roundStartAt is the wall-clock moment the round actually began, same
 * single-stamp-off-MINIGAME_ROUND_BEGIN shape CoinRushPresentation's own roundStartAt uses.
 * eliminatedRsns is real state too, applied catch-up or not: once someone's caught holding it when
 * the server's own random explosion timer goes off (HOT_POTATO_EXPLODED), they're out for the rest
 * of the round -- see PlayerOverlay#drawToken's own skull indicator, the only reader. */
public final class HotPotatoPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private volatile String holder = null;
    private volatile long roundStartAt = 0;
    private final Set<String> eliminatedRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    // Same idea as JadPresentation's own awaitingBowFinish -- see RunePartyPlugin#
    // onAnimationChanged, which consults this via the arm/isAwaiting/clear methods below.
    private volatile boolean awaitingPassFinish = false;
    // Reset on onStarted/reset -- see ArrivalGate's own doc.
    private final ArrivalGate arrivalGate;

    public HotPotatoPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.arrivalGate = new ArrivalGate(plugin, "Hot Potato", "the Hot Potato arena",
            (self, gid, token) -> plugin.apiClient.confirmHotPotatoArrival(gid, self, token));
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.HOT_POTATO_ASSIGNED:
            {
                // Real state, applied catch-up or not -- a catching-up client must know who's
                // holding it right now, same as any other real per-round state elsewhere in this
                // class. Fires for the round's own initial random holder, every player-initiated
                // pass, and the server's own random-explosion force-reassignment alike (see
                // hot_potato.py's own doc -- one event type covers all three).
                String rsn = Json.requiredStr(e.payload, type, "player");
                if (rsn != null)
                {
                    holder = rsn;
                    if (!catchingUp)
                    {
                        plugin.addChatMessage(rsn + " is holding the hot potato!");
                    }
                }
                break;
            }

            case Events.HOT_POTATO_EXPLODED:
            {
                // Real state, applied catch-up or not -- a catching-up client must know who's
                // already eliminated so PlayerOverlay's own flame indicator is correct immediately,
                // not just once a fresh explosion happens to land while they're connected. The
                // explosion spotanim/chat message are a separate, one-shot reveal handled by
                // RunePartyPlugin's own dedicated case for this event, gated on !catchingUp there
                // -- this fold is unconditional on purpose.
                String rsn = Json.requiredStr(e.payload, type, "player");
                if (rsn != null) eliminatedRsns.add(rsn.toLowerCase(Locale.ROOT));
                break;
            }

            default:
                break;
        }
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while Hot Potato is active.
     * Fires, at most once per round, a one-shot confirm-hot-potato-arrival report the instant this
     * client's own position first lands on any HOT_POTATO_TILE -- the server's own
     * _wait_for_everyone_to_arrive_and_begin (a plain server-internal poll, no client cost) is what
     * notices once every seated PLAYER has confirmed and fires the round's own initial random
     * holder assignment + MINIGAME_ROUND_BEGIN together. Same event-driven, position-free shape
     * ArenaPresentation#onTick already established, replacing what a continuous position-ping
     * mini-game would otherwise need from onGameTick's own position heartbeat. No-op past the
     * instant arrival's already been confirmed -- there's nothing further this class needs to check
     * every tick (holder/elimination are both purely server-driven, folded in apply() above). */
    public void onTick(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        arrivalGate.confirmIfMatched(pos != null && plugin.findHotPotatoArenaTiles().contains(pos));
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as every other mini-game's own reset above -- a fresh Hot Potato instance
        // starts with no holder assigned yet (see HOT_POTATO_ASSIGNED, the only other writer) and
        // hasn't become playable yet either.
        holder = null;
        roundStartAt = 0;
        eliminatedRsns.clear();
        arrivalGate.reset();
    }

    @Override
    public void onRoundBegin()
    {
        roundStartAt = System.currentTimeMillis();
    }

    @Override
    public void reset()
    {
        awaitingPassFinish = false;
        holder = null;
        roundStartAt = 0;
        eliminatedRsns.clear();
        arrivalGate.reset();
    }

    // ---- awaiting-emote flag, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    public void armAwaitingPassFinish() { awaitingPassFinish = true; }
    public boolean isAwaitingPassFinish() { return awaitingPassFinish; }
    public void clearAwaitingPassFinish() { awaitingPassFinish = false; }

    /** The current holder's rsn, or null before the round's own initial random assignment lands. */
    public String getHolder() { return holder; }
    /** Lowercase rsns eliminated for the rest of this Hot Potato round -- see PlayerOverlay#
     * drawToken, the only reader. */
    public Set<String> getEliminatedRsns() { return eliminatedRsns; }
    /** When the current Hot Potato round's own clock runs out -- 0 if no round is active yet or the
     * round hasn't actually become playable, same "stamped instant + fixed duration" shape
     * CoinRushPresentation#getEndsAt already uses. */
    public long getEndsAt() { return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.HOT_POTATO_DURATION_MS : 0; }
}
