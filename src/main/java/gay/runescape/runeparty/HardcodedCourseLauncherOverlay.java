package gay.runescape.runeparty;

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

/** Spawns the same Golden Gnome model models/GoldenGnomeModel.java uses (model ID 32303, kept as
 * its own copy of that constant here -- these are two independent concerns, see that class's own
 * doc) at every {@link HardcodedCourse#launcherPoint}, entirely independent of TileReducer/course
 * state -- unlike GoldenGnomeModel (driven by a live game's own marked tiles), this has to render
 * with no active game at all, since discovering/right-clicking it is how a hard-coded course's
 * game gets created in the first place (see RunePartyPlugin#onMenuOpened/#onClientTick, which read
 * {@link #hoveredCourse} to find out what's actually under the cursor). TileOverlay can't host
 * this: its own render() hard-bails unless phase is LOBBY/ACTIVE (see that class's own render()),
 * which is never true for a client with no game at all.
 * <p>
 * Gated on the whole local client having no active game (plugin.getGameId() == null), not on
 * whether any specific launcher's own game is currently running -- once you're in a game (any
 * game) there's nothing left to advertise to you, and this deliberately never tries to answer "is
 * someone else already playing at this exact spot" (see HardcodedCourse's own doc on that accepted
 * tradeoff). */
public final class HardcodedCourseLauncherOverlay extends Overlay
{
    // Same model GoldenGnomeModel spawns for a real in-game Golden Gnome tile -- see that class's
    // own GOLDEN_GNOME_MODEL_ID.
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
     * actual visible silhouette from whatever angle/distance it's being viewed at (see
     * RunePartyPlugin#onClientTick/#onMenuOpened, the only two callers). {@code Perspective.
     * getClickbox} is the same {@code @ApiStatus.Internal} method that backs a real {@code
     * TileObject#getClickbox()} -- a RuneLiteObject has no clickbox of its own at all otherwise,
     * same reasoning the Follower Buddy plugin's own {@code isUnderMouse} gives for using it
     * directly. Height (the projection's own z) isn't the object's own getZ() -- that field is
     * never populated by setLocation (see RuneLiteObjectController's own source), it's a separate
     * offset nothing here ever sets -- so this looks up the real tile height itself, same as that
     * plugin's own equivalent method does. */
    HardcodedCourse hoveredCourse(Point canvasPoint)
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

    /** Despawns and forgets every launcher RuneLiteObject -- called from RunePartyPlugin#shutDown,
     * same "a RuneLiteObject outlives this overlay unless explicitly cleared" reasoning
     * GoldenGnomeModel#clear's own doc gives. */
    public void clear()
    {
        objects.clear();
    }
}
