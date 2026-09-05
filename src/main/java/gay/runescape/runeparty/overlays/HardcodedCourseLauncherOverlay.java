package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.HardcodedCourse;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;
import gay.runescape.runeparty.TileReducer;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Spawns the same Golden Gnome model models/GoldenGnomeModel.java uses at every
 * {@link HardcodedCourse#launcherPoint}, entirely independent of TileReducer/course state --
 * unlike GoldenGnomeModel (driven by a live game's own marked tiles), this has to render with no
 * active game at all, since discovering/right-clicking it is how a hard-coded course's game gets
 * created in the first place. TileOverlay can't host this since its own render() hard-bails unless
 * a game is already in LOBBY/ACTIVE.
 * <p>
 * Gated on the whole local client having no active game, not on whether any specific launcher's
 * own game is currently running -- once you're in a game there's nothing left to advertise to you. */
public final class HardcodedCourseLauncherOverlay extends Overlay
{
    private static final int GOLDEN_GNOME_MODEL_ID = 32303;

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<WorldPoint> objects;

    public HardcodedCourseLauncherOverlay(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        this.objects = new SceneObjectSet<>(client);

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (plugin.getGameId() != null)
        {
            objects.clear();
            return null;
        }

        Set<WorldPoint> desired = new HashSet<>();
        for (HardcodedCourse course : HardcodedCourse.ALL) desired.add(course.launcherPoint);

        objects.sync(desired, Function.identity(), point -> client.loadModel(GOLDEN_GNOME_MODEL_ID));
        return null;
    }

    /** The hard-coded course whose launcher Golden Gnome model {@code canvasPoint} is currently
     * over, or null -- real per-model screen-space clickbox hit-testing via
     * {@link Perspective#getClickbox}, not a fixed ground-tile square, so it tracks the model's
     * actual visible silhouette from whatever angle/distance it's being viewed at. A RuneLiteObject
     * has no clickbox of its own otherwise. Height comes from Perspective#getTileHeight, not the
     * object's own getZ() -- that field is never populated by setLocation. */
    public HardcodedCourse hoveredCourse(Point canvasPoint)
    {
        if (canvasPoint == null || plugin.getGameId() != null) return null;

        for (HardcodedCourse course : HardcodedCourse.ALL)
        {
            RuneLiteObject obj = objects.get(course.launcherPoint);
            if (obj == null || !obj.isActive()) continue;

            Model model = obj.getModel();
            LocalPoint lp = obj.getLocation();
            if (model == null || lp == null) continue;

            int height = Perspective.getTileHeight(client, lp, course.launcherPoint.getPlane());
            Shape clickbox = Perspective.getClickbox(client, client.getTopLevelWorldView(), model,
                obj.getOrientation(), lp.getX(), lp.getY(), height);
            if (clickbox != null && clickbox.contains(canvasPoint.getX(), canvasPoint.getY())) return course;
        }
        return null;
    }

    /** Despawns and forgets every launcher RuneLiteObject -- a RuneLiteObject otherwise outlives
     * this overlay unless explicitly cleared. */
    public void clear()
    {
        objects.clear();
    }
}
