package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Balloon Pop's own client-side state -- purely the *broadcast* half of the round (every seated
 * player's own latest known balloon growth level, and who -- if anyone -- has already won).
 * Deliberately does NOT own the local player's own click-counting/menu-hijack/report-submission
 * logic at all -- that lives directly in RunePartyPlugin (balloonPopLocalClicks and friends), same
 * placement ClickClickClickPresentation's own doc explains for clickClickClickTiles: the menu-entry
 * click callback needs direct access to increment a counter on every single click, and
 * RunePartyPlugin already owns all menu-injection code, so keeping that hot path in one file avoids
 * a cross-class call for every click. This class only ever reads the *other* players' own reported
 * progress (BALLOON_POP_GROWTH) plus the round's own resolution (BALLOON_POP_POPPED) -- both real
 * state, folded regardless of catch-up, so a reconnecting client rebuilds every visible balloon's
 * current size instead of everyone resetting to freshly-spawned. See models/BalloonModel, the
 * reader that actually spawns/scales/pops each player's own balloon from this state. */
public final class BalloonPopPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;
    private final ArrivalGate arrivalGate;

    // player rsn (real, display-cased, as broadcast -- see events.py's own "always a real RSN"
    // convention) -> that player's own latest known growth level (1-9). Never shrinks -- see
    // apply()'s own Math.max merge, matching the server's own report_balloon_pop_growth guard
    // against a stale/out-of-order report.
    private final Map<String, Integer> growthLevelByPlayer = new ConcurrentHashMap<>();
    // The real RSN of whoever's own BALLOON_POP_POPPED landed first this round -- null until then.
    // First-write-wins, same as the server's own apply_event (minigames/balloon_pop.py); redundant
    // with the server's own resolution, but this client still needs its own copy to know which
    // balloon to render as "the winner" versus every other popper's own balloon just vanishing.
    private volatile String winnerRsn;

    public BalloonPopPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.arrivalGate = new ArrivalGate(plugin, "Hot Click Balloon", "the Hot Click Balloon arena",
            (self, gid, token) -> plugin.apiClient.confirmBalloonPopArrival(gid, self, token));
    }

    /** BALLOON_POP_GROWTH/BALLOON_POP_POPPED are the only event types this feature needs to react
     * to beyond the generic lifecycle hooks below -- see this class's own doc. Both are real state,
     * applied regardless of catch-up. */
    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.BALLOON_POP_GROWTH:
            {
                String player = Json.requiredStr(e.payload, type, "player");
                Integer level = Json.requiredInt(e.payload, type, "level");
                if (player != null && level != null)
                {
                    growthLevelByPlayer.merge(player, level, Math::max);
                }
                break;
            }

            case Events.BALLOON_POP_POPPED:
            {
                String player = Json.requiredStr(e.payload, type, "player");
                if (player != null)
                {
                    growthLevelByPlayer.remove(player);
                    if (winnerRsn == null) winnerRsn = player;
                }
                break;
            }

            default:
                break;
        }
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while Balloon Pop is active
     * -- fires the one-shot confirm-balloon-pop-arrival report the instant this client's own
     * position first lands on any BALLOON_POP_TILE, same event-driven shape every other
     * arrival-gated mini-game's own onTick already established. */
    public void onTick(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        arrivalGate.confirmIfMatched(pos != null && plugin.findBalloonPopArenaTiles().contains(pos));
    }

    /** Seeds every seated PLAYER's own balloon at level 0 the instant this mini-game is picked --
     * without this, growthLevelByPlayer stays completely empty (and no balloon renders for
     * anyone, see models/BalloonModel's own read of it) until each player's own first real growth
     * report at 10 clicks, well after the "gather in the arena" step this mini-game's own spec
     * calls for a balloon to already be floating above every head. Applied regardless of
     * catchingUp -- a reconnecting client converges to the same real state either way once actual
     * growth/pop events replay on top of these zeros (see apply()'s own Math.max merge). */
    @Override
    public void onStarted(boolean catchingUp)
    {
        reset();
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().seatedPlayers())
        {
            growthLevelByPlayer.put(entry.rsn, 0);
        }
    }

    @Override
    public void reset()
    {
        growthLevelByPlayer.clear();
        winnerRsn = null;
        arrivalGate.reset();
    }

    /** Every player's own latest known balloon growth level (1-9), keyed by real RSN -- a player
     * who's already popped (or never reported a level at all) simply has no entry. See
     * models/BalloonModel, the only reader. */
    public Map<String, Integer> getGrowthLevelByPlayer() { return Collections.unmodifiableMap(growthLevelByPlayer); }

    /** The real RSN of this round's own winner, or null if nobody's popped yet. */
    public String getWinnerRsn() { return winnerRsn; }
}
