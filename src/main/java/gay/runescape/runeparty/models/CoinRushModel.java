package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/** Same "diff the live set against currently-spawned RuneLiteObjects" shape as GoldenGnomeModel/
 * CoinTrapModel, for Coin Rush spawns -- keyed by the server's own spawn id rather than WorldPoint,
 * with no lingering visual once a spawn is collected. A Coin Rush spawn can land anywhere, not
 * just on the walked course, so {@link #update} reads straight from the plugin rather than a
 * tile-entries list. */
public final class CoinRushModel
{
    private static final int COIN_RUSH_MODEL_ID = 32153;

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<Integer> objects;

    public CoinRushModel(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        this.objects = new SceneObjectSet<>(client);
    }

    public void update()
    {
        Map<Integer, WorldPoint> current = plugin.getCoinRushSpawns();
        objects.sync(current.keySet(), current::get, id -> client.loadModel(COIN_RUSH_MODEL_ID));
    }

    /** Despawns and forgets every Coin Rush RuneLiteObject -- same reasoning/call sites as
     * GoldenGnomeModel#clear/CoinTrapModel#clear. */
    public void clear()
    {
        objects.clear();
    }
}
