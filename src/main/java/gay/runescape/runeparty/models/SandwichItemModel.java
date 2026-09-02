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
 * Sandwich Rush's floating ingredients -- keyed by spawn id and carrying a per-spawn model
 * (tomato/cheese/cabbage/bread each load a different item model), with a per-frame vertical bob on
 * top of SceneObjectSet's own placement.
 * <p>
 * A RuneLiteObject's Z field is not a ground-relative offset -- it's an absolute height in the
 * same coordinate space Perspective#getTileHeight returns, and never touching it is what makes
 * every other spawned model in this codebase sit naturally on the ground. So hovering above the
 * ground means reading the tile's real height via Perspective#getTileHeight first, then offsetting
 * from that, never from a bare 0. */
public final class SandwichItemModel
{
    // Base height items float above the ground, plus how far the sine wave swings around that
    // base. Height increases downward in this coordinate space, so subtracting raises the item
    // above the ground.
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

    /** Despawns and forgets every Sandwich Rush ingredient RuneLiteObject. */
    public void clear()
    {
        objects.clear();
    }
}
