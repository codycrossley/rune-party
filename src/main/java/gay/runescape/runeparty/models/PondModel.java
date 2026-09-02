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

/** Keeps one {@link net.runelite.api.RuneLiteObject} (model {@link #FISH_BOWL_MODEL_ID}) spawned
 * in the scene for every currently-marked Pond tile, diffed each frame against TileReducer's live
 * snapshot -- same "the reducer is the one source of truth" pattern GoldenGnomeModel/CoinTrapModel/
 * CoinRushModel already use, sharing the same {@link SceneObjectSet} diff engine. Stationary,
 * unlike GoldenGnomeModel -- a Pond just appears/disappears with the tile itself.
 * <p>
 * Renders the Fish bowl scenery object rather than an item's inventory appearance -- an item's
 * inventory model is a flat "icon" representation, not built to drape over a curved/sloped tile the
 * way a genuine scenery object model is.
 * <p>
 * Deliberately spawned at its plain, untranslated height -- the mesh is authored assuming it sits
 * atop a table, so on its own it floats at roughly tabletop height above bare ground. Rather than
 * snapping it down to the tile, TileOverlay also spawns a real Table (see TableModel) at the same
 * point underneath it, on the theory that the two are an authored pair meant to be placed together
 * at matching default heights with no manual offset at all. */
@Slf4j
public final class PondModel
{
    private static final int FISH_BOWL_MODEL_ID = 13284;

    private final Client client;
    private final SceneObjectSet<WorldPoint> objects;
    private boolean loggedBottomY = false;

    public PondModel(Client client)
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

        objects.sync(current, Function.identity(), point -> loadFishBowlModel());
    }

    /** Logs this model's lowest vertex once per client session the first time it loads -- a
     * diagnostic to compare against TableModel's matching log, to check the Table and Fish bowl
     * line up as an authored pair. */
    private Model loadFishBowlModel()
    {
        Model model = client.loadModel(FISH_BOWL_MODEL_ID);
        if (model != null && !loggedBottomY)
        {
            loggedBottomY = true;
            log.debug("Fish bowl (id 15471) bottomY = {} -- compare against TableModel's logged topmost vertex", model.getBottomY());
        }
        return model;
    }

    /** Despawns and forgets every Pond RuneLiteObject. */
    public void clear()
    {
        objects.clear();
    }
}
