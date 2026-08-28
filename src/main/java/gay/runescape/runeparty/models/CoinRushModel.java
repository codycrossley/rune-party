package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/** Same "diff the live set against currently-spawned RuneLiteObjects" shape as GoldenGnomeModel/
 * CoinTrapModel, for Coin Rush spawns -- object 29165's own model ("Mounted Coins"), left in its
 * own natural gold palette (unlike CoinTrapModel's recolor, since this object is already gold by
 * design) -- except keyed by the server's own spawn id (RunePartyPlugin#getCoinRushSpawns) rather
 * than WorldPoint, and with no force-persist/animation window of its own: a Coin Rush spawn just
 * disappears the instant COIN_RUSH_COLLECTED removes it from that map, no lingering trigger visual
 * the way a sprung Coin Trap gets. No course-tile dependency either (unlike the other two, which
 * read from TileReducer's own snapshot) -- a Coin Rush spawn can land anywhere the server picks,
 * not just on the walked course -- so {@link #update} reads straight from the plugin rather than
 * taking a tile-entries list. */
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
