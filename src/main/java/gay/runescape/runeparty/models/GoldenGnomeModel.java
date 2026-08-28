package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;
import gay.runescape.runeparty.TileReducer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.coords.WorldPoint;

/** Keeps one {@link net.runelite.api.RuneLiteObject} (model {@link #GOLDEN_GNOME_MODEL_ID})
 * spawned in the scene for every currently-marked Golden Gnome tile, diffed each frame against
 * TileReducer's live snapshot -- same "the reducer is the one source of truth" pattern every other
 * tile visual follows. Split out of TileOverlay (see ARCHITECTURE_REVIEW.md's C5) alongside
 * CoinTrapModel/CoinRushModel, its two siblings under this package -- all three just hand a
 * different key/model-loader shape to the shared {@link SceneObjectSet} diff engine.
 * <p>
 * TileReducer is real state, updated the instant TILE_UNMARKED/TILE_MARKED land regardless of how
 * this presents it -- but a Golden Gnome relocating is choreographed against two spotanims (see
 * RunePartyPlugin's GOLDEN_GNOME_MOVED handling), so the model shouldn't just teleport the moment
 * those events arrive. RunePartyPlugin#getGoldenGnomeMoveOldPoint/getGoldenGnomeMoveNewPoint (with
 * their matching hide/show timestamps) are what let {@link #update} override the raw diff for
 * exactly as long as that choreography needs: force-persisting the old spot a beat after
 * TileReducer already dropped it, and force-suppressing the new spot a beat before TileReducer's
 * already-added entry actually shows. */
public final class GoldenGnomeModel
{
    // A committed Golden Gnome tile renders as this model, spawned as a RuneLiteObject in the
    // scene, instead of a color fill -- see update().
    private static final int GOLDEN_GNOME_MODEL_ID = 32303;

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<WorldPoint> objects;

    public GoldenGnomeModel(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
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
            if ("GOLDEN_GNOME_TILE".equals(entry.tileType)) current.add(entry.point);
        }

        long now = System.currentTimeMillis();
        WorldPoint moveOld = plugin.getGoldenGnomeMoveOldPoint();
        if (moveOld != null && now < plugin.getGoldenGnomeMoveHideOldAt())
        {
            current.add(moveOld);
        }
        WorldPoint moveNew = plugin.getGoldenGnomeMoveNewPoint();
        if (moveNew != null && now < plugin.getGoldenGnomeMoveShowNewAt())
        {
            current.remove(moveNew);
        }

        objects.sync(current, Function.identity(), point -> client.loadModel(GOLDEN_GNOME_MODEL_ID));
    }

    /** Despawns and forgets every Golden Gnome RuneLiteObject -- called whenever TileOverlay stops
     * actively rendering the course and from RunePartyPlugin#shutDown, since a RuneLiteObject
     * otherwise stays registered with the client independently of this overlay or even the plugin
     * being active. */
    public void clear()
    {
        objects.clear();
    }
}
