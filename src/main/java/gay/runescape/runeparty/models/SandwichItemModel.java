package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SandwichSpawn;
import gay.runescape.runeparty.SceneObjectSet;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

/** Same "diff the live set against currently-spawned RuneLiteObjects" shape as CoinRushModel, for
 * Sandwich Rush's own floating ingredients -- except keyed by spawn id AND carrying a per-spawn
 * model (tomato/cheese/cabbage/bread each load a different real item model, see
 * RunePartyPlugin#SANDWICH_RUSH_ITEM_MODEL_IDS), and with a per-frame vertical bob on top of
 * SceneObjectSet's own placement -- the "float above the ground" this mini-game was asked for.
 * <p>
 * Confirmed live: a RuneLiteObject's own Z field (RuneLiteObjectController#setZ) is NOT a
 * ground-relative offset the way you'd expect (0 meaning "at the ground") -- it's an absolute
 * height in the same coordinate space Perspective#getTileHeight returns for a given tile, and
 * never touching it at all is what makes every other spawned model in this codebase (Golden
 * Gnome, Coin Rush, etc.) render sitting naturally on the ground. Calling setZ(0) placed items far
 * from that tile's real ground height and made them invisible -- confirmed by removing the call
 * entirely and seeing a spawn render correctly. So hovering above the ground means reading this
 * tile's own real height via Perspective#getTileHeight first, then offsetting FROM that, never
 * from a bare 0. */
public final class SandwichItemModel
{
    // Base height items float above the ground, plus how far the sine wave swings around that
    // base, in the same raw height units Perspective#getTileHeight returns. Height increases
    // downward in this coordinate space (matches every other height-offset call in this
    // codebase), so subtracting raises the item above the ground -- flip the sign here if it
    // turns out to look wrong live.
    private static final int HOVER_HEIGHT = 60;
    private static final int HOVER_BOB_AMPLITUDE = 15;
    private static final double HOVER_BOB_PERIOD_MS = 1400.0;

    private final Client client;
    private final SceneObjectSet<Integer> objects;

    public SandwichItemModel(Client client)
    {
        this.client = client;
        this.objects = new SceneObjectSet<>(client);
    }

    public void update(Map<Integer, SandwichSpawn> spawns)
    {
        objects.sync(spawns.keySet(), id -> spawns.get(id).point, id ->
        {
            SandwichSpawn spawn = spawns.get(id);
            Integer modelId = RunePartyPlugin.SANDWICH_RUSH_ITEM_MODEL_IDS.get(spawn.item);
            return modelId != null ? client.loadModel(modelId) : null;
        });

        long now = System.currentTimeMillis();
        for (Integer id : spawns.keySet())
        {
            RuneLiteObject obj = objects.get(id);
            if (obj == null) continue;

            LocalPoint lp = obj.getLocation();
            SandwichSpawn spawn = spawns.get(id);
            if (lp == null || spawn == null) continue;

            int groundHeight = Perspective.getTileHeight(client, lp, spawn.point.getPlane());
            double phase = (now + id * 137L) / HOVER_BOB_PERIOD_MS;
            int bob = (int) Math.round(Math.sin(phase * 2 * Math.PI) * HOVER_BOB_AMPLITUDE);
            obj.setZ(groundHeight - HOVER_HEIGHT - bob);
        }
    }

    /** Despawns and forgets every Sandwich Rush ingredient RuneLiteObject -- same reasoning/call
     * sites as CoinRushModel#clear. */
    public void clear()
    {
        objects.clear();
    }
}
