package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.minigames.DanceDanceRuneScapePresentation.Direction;

import net.runelite.api.Perspective;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

/** Renders Dance, Dance, RuneScape's own dance floor: the anchor tile (always a fixed landmark
 * while the mini-game's active) plus the four tiles adjacent to it, any number of which can be
 * lit at once -- a chord, or one tile's tail still lit as the next begins (see
 * DanceDanceRuneScapePresentation, the state this reads, for why). Deliberately a separate
 * overlay from TileOverlay rather than folded into it -- TileOverlay's whole design assumes every
 * tile it draws traces back to a real TileReducer.TileEntry the server pushed, whereas which tile
 * is lit here is purely local, randomized, client-only state, never sent to the server (see
 * ConfettiOverlay for the closer precedent: a separate overlay for a purely client-local,
 * locally-timed visual effect). Uses the same low-level Perspective.getCanvasTilePoly primitive
 * TileOverlay itself uses, just not routed through TileReducer. */
public class DanceDanceRuneScapeOverlay extends Overlay
{
    private static final Color CENTER_COLOR = new Color(0xFF, 0xEE, 0x00);
    private static final Color NORTH_SOUTH_COLOR = new Color(0xFF, 0x00, 0x99);
    private static final Color EAST_WEST_COLOR = new Color(0x00, 0xCC, 0xFF);
    private static final Color FLASH_COLOR = Color.WHITE;
    private static final Color UNLIT_OUTLINE = new Color(255, 255, 255, 140);
    private static final int FILL_ALPHA = 160;
    private static final int FLASH_ALPHA = 220;
    /** How long a capture's flash lasts, in wall-clock milliseconds, timed off
     * DanceDanceRuneScapePresentation#getFlashStartTimes() rather than the tick clock -- render()
     * runs every frame, not once per 600ms game tick, so this can (and should) be much shorter
     * than a tick and still look smooth. Fades from FLASH_ALPHA to fully transparent over this
     * window rather than cutting off abruptly, so it reads as a quick pop rather than a blink. */
    private static final int FLASH_DURATION_MS = 150;

    private final RunePartyPlugin plugin;

    public DanceDanceRuneScapeOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isDanceDanceRuneScapeActive()) return null;

        WorldPoint anchor = plugin.findDanceDanceRuneScapeTilePoint();
        if (anchor == null) return null;

        fillTile(g, anchor, CENTER_COLOR, FILL_ALPHA);

        Set<Direction> highlighted = plugin.getDanceDanceRuneScapeHighlightedDirections();
        Map<Direction, Long> flashStarts = plugin.getDanceDanceRuneScapeFlashStartTimes();
        long now = System.currentTimeMillis();
        for (Direction direction : Direction.values())
        {
            WorldPoint point = tileFor(anchor, direction);
            Long flashStart = flashStarts.get(direction);
            long flashAge = flashStart == null ? -1 : now - flashStart;
            if (flashAge >= 0 && flashAge < FLASH_DURATION_MS)
            {
                // Just captured -- a quick bright pop that fades to nothing over
                // FLASH_DURATION_MS, then it reverts to the plain unlit outline below. Takes
                // priority over `highlighted`, though in practice a tile never carries both at
                // once -- capturing a note drops it out of `highlighted` the same tick its flash
                // starts.
                float fade = 1f - (flashAge / (float) FLASH_DURATION_MS);
                fillTile(g, point, FLASH_COLOR, Math.round(FLASH_ALPHA * fade));
            }
            else if (highlighted.contains(direction))
            {
                fillTile(g, point, colorFor(direction), FILL_ALPHA);
            }
            else
            {
                outlineTile(g, point, UNLIT_OUTLINE);
            }
        }

        return null;
    }

    private static Color colorFor(Direction direction)
    {
        switch (direction)
        {
            case NORTH:
            case SOUTH:
                return NORTH_SOUTH_COLOR;
            case EAST:
            case WEST:
            default:
                return EAST_WEST_COLOR;
        }
    }

    /** Same north/south/east/west WorldPoint offset math as DanceDanceRuneScapePresentation's own
     * tileFor -- duplicated rather than shared since one lives in the minigames package's own
     * client-local game logic and this one's purely a rendering concern, and the two have no other
     * reason to share a dependency. */
    private static WorldPoint tileFor(WorldPoint anchor, Direction direction)
    {
        switch (direction)
        {
            case NORTH: return new WorldPoint(anchor.getX(), anchor.getY() + 1, anchor.getPlane());
            case SOUTH: return new WorldPoint(anchor.getX(), anchor.getY() - 1, anchor.getPlane());
            case EAST: return new WorldPoint(anchor.getX() + 1, anchor.getY(), anchor.getPlane());
            case WEST: return new WorldPoint(anchor.getX() - 1, anchor.getY(), anchor.getPlane());
            default: return anchor;
        }
    }

    private void fillTile(Graphics2D g, WorldPoint wp, Color color, int alpha)
    {
        Polygon poly = canvasTilePoly(wp);
        if (poly == null) return;

        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
        g.fill(poly);
        g.setColor(color);
        g.draw(poly);
    }

    private void outlineTile(Graphics2D g, WorldPoint wp, Color color)
    {
        Polygon poly = canvasTilePoly(wp);
        if (poly == null) return;

        g.setColor(color);
        g.draw(poly);
    }

    private Polygon canvasTilePoly(WorldPoint wp)
    {
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(plugin.client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(plugin.client.getTopLevelWorldView(), local);
            if (lp == null) continue;
            return Perspective.getCanvasTilePoly(plugin.client, lp);
        }
        return null;
    }
}
