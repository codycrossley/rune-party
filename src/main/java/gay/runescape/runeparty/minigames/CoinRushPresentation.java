package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Coin Rush's own client-side state (server-driven spawns/collections). spawns is real state,
 * applied catch-up or not: every currently-live spawn's WorldPoint keyed by the server's own spawn
 * id, mirrored into a 3D model per spawn by TileOverlay#updateCoinRushModels. scores is this
 * round's own live tally (lowercase rsn -> coins collected so far), reset fresh on every
 * MINIGAME_STARTED for this key -- read by StatsOverlay's live scoreboard, which replaces the
 * normal roster view for exactly as long as a Coin Rush round is playable. roundStartAt is the
 * wall-clock moment the round actually began, stamped from the server's own MINIGAME_ROUND_BEGIN --
 * precise for a live client, best-effort ("now") for a client that only caught up on an
 * already-underway round. */
public final class CoinRushPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private final Map<Integer, WorldPoint> spawns = new ConcurrentHashMap<>();
    private final Map<String, Integer> scores = new ConcurrentHashMap<>();
    // Guards one spawn's own collect report against firing every tick while it's in flight -- one
    // per spawn id (via a Set) since more than one spawn can be live at the same time. See
    // checkCollection, the only writer besides the COIN_RUSH_COLLECTED handler (which clears an
    // entry once the server's own echo confirms it).
    private final Set<Integer> collectSubmitted = ConcurrentHashMap.newKeySet();
    private volatile long roundStartAt = 0;

    public CoinRushPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.COIN_RUSH_SPAWN:
            {
                // Real state, applied catch-up or not: a coin genuinely exists on the board at
                // this point -- see TileOverlay#updateCoinRushModels, which just mirrors spawns's
                // own current keys every frame.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                WorldPoint point = Json.safeWorldPoint(e.payload, "point");
                if (spawnId != null && point != null) spawns.put(spawnId, point);
                break;
            }

            case Events.COIN_RUSH_COLLECTED:
            {
                // Real state, applied catch-up or not: the spawn is gone (whoever the server
                // credited already claimed it) and the tally reflects it. This event never
                // carries a real coin-total change of its own -- the server doesn't actually
                // credit a Coin Rush pickup to the player's balance until the round ends, one lump
                // sum per player -- so the only thing worth showing live, right now, is a purely
                // cosmetic "+2" flash (see enqueueCoinPopup's totalless=true), never a running total.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                if (spawnId != null)
                {
                    spawns.remove(spawnId);
                    collectSubmitted.remove(spawnId);
                }
                String collector = Json.requiredStr(e.payload, type, "player");
                if (collector != null)
                {
                    scores.merge(collector.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
                if (!catchingUp && collector != null)
                {
                    plugin.enqueueCoinPopup(collector, RunePartyPlugin.COIN_RUSH_REWARD, 0, RunePartyPlugin.COIN_RUSH_BUMP_POPUP_DURATION_MS, true);
                    plugin.addChatMessage(collector + " grabbed a coin!");
                }
                break;
            }

            default:
                break;
        }
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Real state, applied catch-up or not: a fresh Coin Rush instance starts with no spawns
        // and no tally. roundStartAt deliberately isn't set here -- MINIGAME_STARTED lands before
        // the round is actually playable (the ready-check still has to run first), so stamping
        // "now" at this point would undercount the round's remaining time; see onRoundBegin, the
        // only writer.
        spawns.clear();
        scores.clear();
        collectSubmitted.clear();
        roundStartAt = 0;
    }

    @Override
    public void onRoundBegin()
    {
        roundStartAt = System.currentTimeMillis();
    }

    @Override
    public boolean showsFinalScore() { return true; }

    @Override
    public void reset()
    {
        // Unconditional (harmless no-op if this round wasn't Coin Rush) rather than gated on
        // whether this was the active minigame -- any coin still standing when the round ends
        // shouldn't keep rendering (see TileOverlay#updateCoinRushModels, which just mirrors
        // spawns's own keys). scores is deliberately left as-is when called from a per-round clear
        // (StatsOverlay's own gate already stops rendering it once the round's over, and the next
        // MINIGAME_STARTED resets it fresh regardless) -- a whole-game reset clears it too since
        // nothing keeps caring after that.
        spawns.clear();
        collectSubmitted.clear();
        scores.clear();
        roundStartAt = 0;
    }

    /** Checks the local player's current position against every currently-live spawn (see
     * getSpawns) and reports a claim the instant it matches one -- called every tick while a Coin
     * Rush round is playable. collectSubmitted guards each spawn id against being reported more
     * than once while its first report is still in flight: the server's own COIN_RUSH_COLLECTED
     * echo is what actually removes the spawn from spawns (and clears the guard), so standing on a
     * still-live spawn tile for several ticks in a row before the echo lands doesn't fire a fresh
     * request every single tick. */
    public void checkCollection(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        if (pos == null) return;

        for (Map.Entry<Integer, WorldPoint> entry : spawns.entrySet())
        {
            if (!entry.getValue().equals(pos)) continue;
            int spawnId = entry.getKey();
            if (!collectSubmitted.add(spawnId)) continue; // already reported, awaiting the echo
            collectCoin(spawnId, pos);
        }
    }

    /** Reports the local player reaching a still-live spawn tile. Same report-then-wait-for-the-echo
     * shape as RunePartyPlugin#confirmArrival: the server, not this call's caller, decides who
     * actually wins a spawn racing multiple simultaneous reports, so this never assumes success
     * locally. A failed request (network blip, not a "someone else already got it" 409) clears the
     * guard so checkCollection retries it on a later tick. */
    private void collectCoin(int spawnId, WorldPoint pos)
    {
        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) { collectSubmitted.remove(spawnId); return; }

        plugin.submitAction("Collect Coin Rush coin", () -> plugin.apiClient.collectCoinRushCoin(gid, self, token, spawnId, pos.getX(), pos.getY(), pos.getPlane()),
            e -> collectSubmitted.remove(spawnId));
    }

    public Map<Integer, WorldPoint> getSpawns() { return spawns; }
    public Map<String, Integer> getScores() { return scores; }

    /** When the current Coin Rush round's own clock runs out -- 0 if no round is active yet or the
     * round hasn't actually become playable (see roundStartAt's own doc). */
    public long getEndsAt() { return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.COIN_RUSH_DURATION_MS : 0; }
}
