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
 * CoinRushModel already use (see ARCHITECTURE_REVIEW.md's C5), sharing the same
 * {@link SceneObjectSet} diff engine. Stationary, unlike GoldenGnomeModel -- there's no relocation
 * choreography to override the raw diff with, a Pond just appears/disappears with the tile itself.
 * <p>
 * Renders the Fish bowl scenery object (id 15471, own model {@link #FISH_BOWL_MODEL_ID}) rather
 * than an item's inventory appearance the way an earlier version of this class did (item 8170,
 * "Pond", via its {@code ItemComposition#getInventoryModel()}) -- an item's inventory model is a
 * flat "icon" representation, not built to drape over a curved/sloped tile the way a genuine
 * scenery object model is, and rendered visibly wrong on anything but flat ground. A real object
 * model needs no such indirection: {@code Client#loadModel(int)} with its own model id, the exact
 * same call GoldenGnomeModel/CoinTrapModel/CoinRushModel already make for their own object-based
 * models.
 * <p>
 * Deliberately spawned at its plain, untranslated height -- the mesh is authored assuming it sits
 * atop a table, so on its own it floats at roughly tabletop height above bare ground. Rather than
 * snapping it down to the tile (an earlier version of this class did exactly that via
 * {@code getBottomY()}/{@code translate()}, which looked wrong once actually seen live), TileOverlay
 * also spawns a real Table (see TableModel) at the same point underneath it, on the theory that the
 * two are an authored pair meant to be placed together at matching default heights with no manual
 * offset at all. */
@Slf4j
public final class PondModel
{
    // Fish bowl (object id 15471) -- its own single object model, objectModels[0] in its cache
    // definition, not an item id/inventory model at all.
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

    /** Logs this model's own lowest vertex once per client session the first time it loads -- see
     * TableModel#loadTableModel's matching log, the intended point of comparison: if the Table and
     * Fish bowl really are an authored pair, this value should land close to that one (both spawned
     * at their plain, untranslated height). Purely a one-off diagnostic, not applied as a
     * correction here. */
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

    /** Despawns and forgets every Pond RuneLiteObject -- called whenever TileOverlay stops
     * actively rendering the course and from RunePartyPlugin#shutDown, same lifecycle every other
     * model in this package already follows. */
    public void clear()
    {
        objects.clear();
    }
}
