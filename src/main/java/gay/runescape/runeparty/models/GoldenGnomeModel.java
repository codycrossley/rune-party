package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;
import gay.runescape.runeparty.TileReducer;
import java.awt.Shape;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Keeps one {@link net.runelite.api.RuneLiteObject} (model {@link #GOLDEN_GNOME_MODEL_ID})
 * spawned in the scene for every currently-marked Golden Gnome tile, diffed each frame against
 * TileReducer's live snapshot, via the shared {@link SceneObjectSet} diff engine also used by
 * CoinTrapModel/CoinRushModel.
 * <p>
 * TileReducer is real state, updated the instant TILE_UNMARKED/TILE_MARKED land -- but a Golden
 * Gnome relocating is choreographed against two spotanims, so the model shouldn't just teleport
 * the moment those events arrive. RunePartyPlugin#getGoldenGnomeMoveOldPoint/
 * getGoldenGnomeMoveNewPoint (with their matching hide/show timestamps) are what let
 * {@link #update} override the raw diff for exactly as long as that choreography needs:
 * force-persisting the old spot a beat after TileReducer already dropped it, and
 * force-suppressing the new spot a beat before TileReducer's already-added entry actually shows. */
public final class GoldenGnomeModel
{
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

    /** Whether {@code canvasPoint} is over the real screen-space clickbox of whichever Golden
     * Gnome model is currently spawned at {@code point} -- real per-model hit-testing via
     * Perspective#getClickbox, not a fixed ground-tile square. Height comes from
     * Perspective#getTileHeight, not the object's own getZ() -- that field is never populated by
     * setLocation. False if nothing's actually spawned at point. */
    public boolean isUnderMouse(WorldPoint point, Point canvasPoint)
    {
        if (canvasPoint == null) return false;

        RuneLiteObject obj = objects.get(point);
        if (obj == null || !obj.isActive()) return false;

        Model model = obj.getModel();
        LocalPoint lp = obj.getLocation();
        if (model == null || lp == null) return false;

        int height = Perspective.getTileHeight(client, lp, point.getPlane());
        Shape clickbox = Perspective.getClickbox(client, client.getTopLevelWorldView(), model,
            obj.getOrientation(), lp.getX(), lp.getY(), height);
        return clickbox != null && clickbox.contains(canvasPoint.getX(), canvasPoint.getY());
    }

    /** Despawns and forgets every Golden Gnome RuneLiteObject -- a RuneLiteObject otherwise stays
     * registered with the client independently of this overlay or even the plugin being active. */
    public void clear()
    {
        objects.clear();
    }
}
