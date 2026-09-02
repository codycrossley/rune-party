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

/** Keeps one {@link net.runelite.api.RuneLiteObject} (model {@link #TABLE_MODEL_ID}) spawned at
 * every currently-marked Pond tile -- the Table scenery object the Fish bowl (see PondModel,
 * spawned at the same point) is meant to sit on. Same {@link SceneObjectSet} diff engine as every
 * other model in this package, keyed off the same POND_TILE point PondModel uses so the two always
 * appear/disappear together. */
@Slf4j
public final class TableModel
{
    private static final int TABLE_MODEL_ID = 1157;

    private final Client client;
    private final SceneObjectSet<WorldPoint> objects;
    private boolean loggedTopY = false;

    public TableModel(Client client)
    {
        this.client = client;
        this.objects = new SceneObjectSet<>(client);
    }

    /** loadModel can return null for a couple of frames right after the client starts while the
     * model's still loading from cache -- SceneObjectSet keeps retrying every frame until it
     * succeeds rather than giving up after one null. */
    public void update(List<TileReducer.TileEntry> entries)
    {
        Set<WorldPoint> current = new HashSet<>();
        for (TileReducer.TileEntry entry : entries)
        {
            if ("POND_TILE".equals(entry.tileType)) current.add(entry.point);
        }

        objects.sync(current, Function.identity(), point -> loadTableModel());
    }

    /** Logs this model's topmost vertex once per client session the first time it loads (the
     * model-space Y axis runs negative upward, so the top surface is the minimum Y) -- a
     * diagnostic to check the Table and Fish bowl line up as an authored pair. */
    private Model loadTableModel()
    {
        Model model = client.loadModel(TABLE_MODEL_ID);
        if (model != null && !loggedTopY)
        {
            loggedTopY = true;
            float topY = Float.MAX_VALUE;
            for (float y : model.getVerticesY()) topY = Math.min(topY, y);
            log.debug("Table (id 598) topmost vertex Y = {} -- compare against PondModel's logged bottomY", topY);
        }
        return model;
    }

    /** Despawns and forgets every Table RuneLiteObject. */
    public void clear()
    {
        objects.clear();
    }
}
