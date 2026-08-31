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
 * every currently-marked Pond tile -- the real Table scenery object (id 598) the Fish bowl (see
 * PondModel, spawned at the same point) is meant to sit on, rather than PondModel snapping the
 * bowl's own mesh down to bare ground itself. Same {@link SceneObjectSet} diff engine, same
 * "reducer is the one source of truth" pattern as every other model in this package, keyed off the
 * identical POND_TILE point PondModel already uses so the two always appear/disappear together. */
@Slf4j
public final class TableModel
{
    // Table (object id 598) -- its own single object model, objectModels[0] in its cache
    // definition. Same tile/interactType/decorDisplacement shape as the Fish bowl's own
    // composition (see PondModel), consistent with these being an intentionally-paired prop set.
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

    /** Logs this model's own topmost vertex once per client session the first time it loads
     * (Jagex's model-space Y axis runs negative upward -- see PondModel#loadGroundedFishBowlModel's
     * old doc for the same convention -- so the *top* surface is the *minimum* Y across every
     * vertex) purely as a one-off diagnostic: if the Table and Fish bowl really are an authored
     * pair, this value should land close to PondModel's own logged getBottomY() -- the bowl's
     * lowest point -- with both objects spawned at their plain, untranslated height. If the two
     * numbers are meaningfully apart in practice, that's the signal an explicit offset is needed
     * after all, rather than assuming the pairing is exact. */
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

    /** Despawns and forgets every Table RuneLiteObject -- called whenever TileOverlay stops
     * actively rendering the course and from RunePartyPlugin#shutDown, same lifecycle every other
     * model in this package already follows. */
    public void clear()
    {
        objects.clear();
    }
}
