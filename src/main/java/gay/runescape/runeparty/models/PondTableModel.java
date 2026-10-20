package gay.runescape.runeparty.models;

import gay.runescape.runeparty.SceneObjectSet;
import gay.runescape.runeparty.TileReducer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.coords.WorldPoint;

/** Keeps one Fish bowl ({@link #FISH_BOWL_MODEL_ID}) and one Table ({@link #TABLE_MODEL_ID})
 * RuneLiteObject spawned, co-located, at every currently-marked Pond tile -- the two are an
 * authored pair meant to be placed together at matching default heights with no manual offset (see
 * loadFishBowlModel/loadTableModel's own one-shot debug logs, which compare their logged vertices
 * to confirm they actually line up). Same {@link SceneObjectSet} diff engine every other model in
 * this package uses, one instance per model since each needs its own independent spawn/despawn
 * lifecycle even though both key off the exact same POND_TILE point set -- formerly two separate,
 * near-identical classes (PondModel/TableModel), merged since neither is ever used without the
 * other. */
@Slf4j
public final class PondTableModel
{
    private static final int FISH_BOWL_MODEL_ID = 13284;
    private static final int TABLE_MODEL_ID = 1157;

    private final Client client;
    private final SceneObjectSet<WorldPoint> bowlObjects;
    private final SceneObjectSet<WorldPoint> tableObjects;
    private boolean loggedBottomY = false;
    private boolean loggedTopY = false;

    public PondTableModel(Client client)
    {
        this.client = client;
        this.bowlObjects = new SceneObjectSet<>(client);
        this.tableObjects = new SceneObjectSet<>(client);
    }

    /** loadModel can return null for a couple of frames right after the client starts while a
     * model's still loading from cache -- SceneObjectSet keeps retrying every frame until it
     * succeeds rather than giving up after one null. */
    public void update(List<TileReducer.TileEntry> entries)
    {
        Set<WorldPoint> current = new HashSet<>();
        for (TileReducer.TileEntry entry : entries)
        {
            if ("POND_TILE".equals(entry.tileType)) current.add(entry.point);
        }

        bowlObjects.sync(current, Function.identity(), point -> loadFishBowlModel());
        tableObjects.sync(current, Function.identity(), point -> loadTableModel());
    }

    private Model loadFishBowlModel()
    {
        Model model = client.loadModel(FISH_BOWL_MODEL_ID);
        if (model != null && !loggedBottomY)
        {
            loggedBottomY = true;
            log.debug("Fish bowl (id 15471) bottomY = {} -- compare against the Table's logged topmost vertex", model.getBottomY());
        }
        return model;
    }

    private Model loadTableModel()
    {
        Model model = client.loadModel(TABLE_MODEL_ID);
        if (model != null && !loggedTopY)
        {
            loggedTopY = true;
            float topY = Float.MAX_VALUE;
            for (float y : model.getVerticesY()) topY = Math.min(topY, y);
            log.debug("Table (id 598) topmost vertex Y = {} -- compare against the Fish bowl's logged bottomY", topY);
        }
        return model;
    }

    /** Despawns and forgets every Fish bowl and Table RuneLiteObject. */
    public void clear()
    {
        bowlObjects.clear();
        tableObjects.clear();
    }
}
