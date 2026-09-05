package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SandwichSpawn;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Sandwich Rush's own client-side state (server-driven spawns/collections). spawns is real
 * state, applied catch-up or not: every currently-live spawn's point+ingredient keyed by the
 * server's own spawn id, mirrored into a 3D model per spawn by models/SandwichItemModel.
 * held/count are the LOCAL player's own held ingredients/completed-sandwich count this round --
 * unlike Coin Rush's shared scoreboard, this is deliberately self-only (see
 * SandwichRushHudOverlay). roundStartAt is the wall-clock moment the round actually began, same
 * single-stamp-off-MINIGAME_ROUND_BEGIN shape CoinRushPresentation's own roundStartAt uses. */
public final class SandwichRushPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private final Map<Integer, SandwichSpawn> spawns = new ConcurrentHashMap<>();
    private final Set<String> held = ConcurrentHashMap.newKeySet(); // lowercase ingredient keys
    private volatile int count = 0;
    // Guards one spawn's own collect report against firing every tick while it's in flight -- same
    // role CoinRushPresentation's own collectSubmitted plays.
    private final Set<Integer> collectSubmitted = ConcurrentHashMap.newKeySet();
    private volatile long roundStartAt = 0;

    public SandwichRushPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.SANDWICH_RUSH_ITEM_SPAWNED:
            {
                // Real state, applied catch-up or not: an ingredient genuinely exists on the board
                // at this point -- see models/SandwichItemModel, which just mirrors spawns's own
                // current keys every frame.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                WorldPoint point = Json.safeWorldPoint(e.payload, "point");
                String item = Json.requiredStr(e.payload, type, "item");
                if (spawnId != null && point != null && item != null)
                {
                    spawns.put(spawnId, new SandwichSpawn(point, item));
                }
                break;
            }

            case Events.SANDWICH_RUSH_ITEM_COLLECTED:
            {
                // The spawn itself is gone regardless of who collected it -- real state, applied
                // catch-up or not. held/count only ever track the LOCAL player's own progress
                // (deliberately self-only, see this class's own field doc), so nothing past this
                // point touches them unless the collector is the local player.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                if (spawnId != null)
                {
                    spawns.remove(spawnId);
                    collectSubmitted.remove(spawnId);
                }
                String collector = Json.requiredStr(e.payload, type, "player");
                String item = Json.requiredStr(e.payload, type, "item");
                Integer newCount = Json.requiredInt(e.payload, type, "sandwichCount");
                String self = plugin.getLocalRsn();
                if (collector != null && item != null && self != null && collector.equalsIgnoreCase(self))
                {
                    boolean completedSandwich = newCount != null && newCount > count;
                    if (completedSandwich) held.clear();
                    else held.add(item);
                    if (newCount != null) count = newCount;

                    if (!catchingUp)
                    {
                        plugin.addChatMessage(completedSandwich
                            ? "Sandwich complete! (" + count + " total)"
                            : "Picked up a " + item + "!");
                    }
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
        // Same reasoning as CoinRushPresentation's own onStarted -- held/count reset too: a fresh
        // round means nobody's holding anything and nobody's made a sandwich yet, regardless of
        // catch-up.
        spawns.clear();
        held.clear();
        count = 0;
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
        // Same reasoning as CoinRushPresentation's own reset -- any ingredient still floating when
        // the round ends shouldn't keep rendering (see models/SandwichItemModel). held/count are
        // deliberately left as-is when called from a per-round clear, same reasoning
        // CoinRushPresentation's own scores comment gives -- a whole-game reset clears them too.
        spawns.clear();
        collectSubmitted.clear();
        held.clear();
        count = 0;
        roundStartAt = 0;
    }

    /** Checks the local player's current position against every currently-live ingredient spawn
     * (see getSpawns) and reports a claim the instant it matches one -- called every tick while a
     * Sandwich Rush round is playable. Same guard shape CoinRushPresentation#checkCollection uses
     * -- collectSubmitted stops a spawn id from being reported more than once while its first
     * report is still in flight. */
    public void checkCollection(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        if (pos == null) return;

        for (Map.Entry<Integer, SandwichSpawn> entry : spawns.entrySet())
        {
            if (!entry.getValue().point.equals(pos)) continue;
            int spawnId = entry.getKey();
            if (!collectSubmitted.add(spawnId)) continue; // already reported, awaiting the echo
            collectItem(spawnId, pos);
        }
    }

    /** Reports the local player reaching a still-live ingredient's tile -- same
     * report-then-wait-for-the-echo shape CoinRushPresentation's own collectCoin uses. A failed
     * request (network blip, not a "someone else already got it"/"already holding that ingredient"
     * 409) clears the guard so checkCollection retries it on a later tick. */
    private void collectItem(int spawnId, WorldPoint pos)
    {
        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) { collectSubmitted.remove(spawnId); return; }

        plugin.submitAction("Collect Sandwich Rush item", () -> plugin.apiClient.collectSandwichItem(gid, self, token, spawnId, pos.getX(), pos.getY(), pos.getPlane()),
            e -> collectSubmitted.remove(spawnId));
    }

    public Map<Integer, SandwichSpawn> getSpawns() { return spawns; }
    /** The LOCAL player's own currently-held ingredient keys this round -- deliberately self-only,
     * see this class's own field doc. */
    public Set<String> getHeld() { return held; }
    public int getCount() { return count; }

    /** When the current Sandwich Rush round's own clock runs out -- 0 if no round is active yet or
     * the round hasn't actually become playable, same "stamped instant + fixed duration" shape
     * CoinRushPresentation#getEndsAt already uses. */
    public long getEndsAt() { return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.SANDWICH_RUSH_DURATION_MS : 0; }
}
