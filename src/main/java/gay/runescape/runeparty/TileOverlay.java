package gay.runescape.runeparty;

import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.Text;

import java.awt.*;
import java.util.Collection;
import java.util.List;

/** Renders the course: committed tiles from TileReducer, plus a live placement/removal preview
 * while the host is building. Unlike Gnomeball's TileOverlay -- whose FIELD/ZONE tiles are large
 * connected regions rendered as an outline -- a course is a one-tile-wide walked path, so every
 * tile renders individually filled/bordered (Gnomeball's non-outline-type styling) with a
 * connecting line drawn between consecutive path indices to make the route itself legible. */
public class TileOverlay extends Overlay
{
    private static final Color COLOR_UNKNOWN_TYPE = new Color(255, 255, 0); // fallback for any tile type not explicitly recognized below
    private static final Color COLOR_PATH          = new Color(40, 130, 230); // the "standard" tile -- plain, always worth 3 coins on landing
    private static final Color COLOR_START         = new Color(60, 179, 74);
    private static final Color COLOR_GNOMEBALL_TILE = new Color(255, 210, 0);
    private static final Color COLOR_EVENT_TILE    = new Color(170, 80, 220);
    private static final Color COLOR_ROUTE_LINE    = new Color(255, 255, 255, 140);
    private static final Color COLOR_TARGET_ARROW  = new Color(255, 215, 0);

    private static final int FILL_ALPHA   = 128; // 50% opacity fill
    private static final int BORDER_ALPHA = 255; // solid border

    private static final Stroke SOLID_STROKE   = new BasicStroke(2f);
    private static final Stroke PREVIEW_STROKE = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f);
    private static final Stroke ROUTE_STROKE   = new BasicStroke(2f);

    private final Client client;
    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;
    private final TileReducer tileReducer;

    public TileOverlay(Client client, RunePartyConfig config, RunePartyPlugin plugin, TileReducer tileReducer)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.tileReducer = tileReducer;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showTileOverlay()) return null;
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        renderCommittedCourse(g);

        if (plugin.isCoursePlacementMode())
        {
            renderPresetPreview(g);
        }

        renderTargetArrow(g);
        renderStartArrow(g);

        return null;
    }

    private void renderCommittedCourse(Graphics2D g)
    {
        List<TileReducer.TileEntry> entries = tileReducer.snapshot();

        for (TileReducer.TileEntry entry : entries)
        {
            Color base = resolveColor(entry.color, entry.tileType);
            renderFilledTile(g, entry.point, withAlpha(base, FILL_ALPHA), withAlpha(base, BORDER_ALPHA), SOLID_STROKE);
        }

        renderRouteLines(g, entries);
    }

    /** Draws a bouncing, pulsing arrow -- in the mover's own RunePartyColor (see
     * RunePartyPlugin#getRosterReducer, falls back to gold if their seat color can't be resolved)
     * -- labeled with their name centered above it, over the tile the current roll resolved to.
     * Visible to every client watching, not just the mover, the same way the rest of the board
     * state is shared. Disappears as soon as that player is actually standing on the tile (checked
     * directly against their on-screen position every frame, not just on the next TURN_STARTED
     * echo) or, failing that, once TURN_STARTED clears pendingRoll/pendingTargetIndex (see
     * RunePartyPlugin#handleEvent) -- e.g. if the mover isn't currently rendered for this client. */
    private void renderTargetArrow(Graphics2D g)
    {
        if (!plugin.isPendingRoll()) return;
        Integer targetIndex = plugin.getPendingTargetIndex();
        if (targetIndex == null) return;
        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null) return;

        TileReducer.TileEntry target = tileReducer.tileAtIndex(targetIndex);
        if (target == null) return;

        Player mover = findPlayerByRsn(moverRsn);
        if (mover != null && target.point.equals(mover.getWorldLocation())) return;

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(moverRsn));
        Color arrowColor = seatColor != null ? seatColor.awt : COLOR_TARGET_ARROW;

        drawBouncingArrowWithLabel(g, target.point, moverRsn, arrowColor);
    }

    /** Draws a bouncing, pulsing arrow over {@code tilePoint} with {@code label} centered above
     * it, in {@code color}. Shared by renderTargetArrow (whose-turn-is-it) and renderStartArrow
     * (the pre-game gathering instruction) -- same animation, different trigger condition, color,
     * and label. */
    private void drawBouncingArrowWithLabel(Graphics2D g, WorldPoint tilePoint, String label, Color color)
    {
        Point center = tileCenterOnCanvas(tilePoint);
        if (center == null) return;

        long now = System.currentTimeMillis();
        int bounce = (int) Math.round(Math.sin(now / 180.0) * 6);
        float pulse = (float) (0.55 + 0.45 * Math.sin(now / 260.0));

        int baseY = center.y - 38 + bounce;
        int tipY = baseY + 16;
        int halfWidth = 9;

        Polygon arrow = new Polygon();
        arrow.addPoint(center.x, tipY);
        arrow.addPoint(center.x - halfWidth, baseY);
        arrow.addPoint(center.x + halfWidth, baseY);

        int alpha = (int) (pulse * 255);
        g.setColor(withAlpha(color, alpha));
        g.fillPolygon(arrow);
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.setStroke(SOLID_STROKE);
        g.drawPolygon(arrow);

        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        int textY = baseY - 6;
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.drawString(label, center.x - textWidth / 2 + 1, textY + 1);
        g.setColor(withAlpha(color, alpha));
        g.drawString(label, center.x - textWidth / 2, textY);
    }

    /** Draws the pre-game "gather here" arrow over the START tile (always path index 0, see
     * CoursePreset) during the window between GAME_STARTED and the first TURN_STARTED -- i.e.
     * while RunePartyPlugin#getCurrentTurnRsn is still null, see RunePartyPlugin#
     * checkGatheringAtStart for the matching per-player confirm-start reporting. Uses the START
     * tile's own green so the arrow visually ties back to the tile it's pointing at. */
    private void renderStartArrow(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE) return;
        if (plugin.getCurrentTurnRsn() != null) return; // turn order has begun -- gathering is over

        TileReducer.TileEntry start = tileReducer.tileAtIndex(0);
        if (start == null) return;

        drawBouncingArrowWithLabel(g, start.point, "Start Here!", COLOR_START);
    }

    private Player findPlayerByRsn(String rsn)
    {
        if (rsn == null) return null;
        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;
            if (rsn.equalsIgnoreCase(Text.toJagexName(p.getName()))) return p;
        }
        return null;
    }

    /** Draws a line from each path tile to the next (index i -> i+1), so the walked route reads
     * clearly even though every tile renders as an independent filled square. Only draws between
     * indices that are both actually present -- a gap in the course just leaves that one segment
     * undrawn rather than guessing a connection across it. */
    private void renderRouteLines(Graphics2D g, List<TileReducer.TileEntry> entries)
    {
        int length = tileReducer.courseLength();
        if (length <= 1) return;

        g.setStroke(ROUTE_STROKE);
        g.setColor(COLOR_ROUTE_LINE);

        for (int i = 0; i < length - 1; i++)
        {
            TileReducer.TileEntry from = tileReducer.tileAtIndex(i);
            TileReducer.TileEntry to = tileReducer.tileAtIndex(i + 1);
            if (from == null || to == null) continue;

            Point fromCanvas = tileCenterOnCanvas(from.point);
            Point toCanvas = tileCenterOnCanvas(to.point);
            if (fromCanvas == null || toCanvas == null) continue;

            g.drawLine(fromCanvas.x, fromCanvas.y, toCanvas.x, toCanvas.y);
        }
    }

    private Point tileCenterOnCanvas(WorldPoint wp)
    {
        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), wp);
        if (lp == null) return null;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return null;
        Rectangle bounds = poly.getBounds();
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private void renderPresetPreview(Graphics2D g)
    {
        CoursePreset preset = plugin.getSelectedPreset();
        if (preset == null) return;

        Tile hovered = client.getTopLevelWorldView().getSelectedSceneTile();
        if (hovered == null) return;
        WorldPoint center = hovered.getWorldLocation();
        if (center == null) return;

        List<CoursePreset.PlacedTile> placed = preset.layout(center, plugin.getPresetRotationSteps());

        for (CoursePreset.PlacedTile pt : placed)
        {
            Color base = resolveColor(pt.color, pt.tileType);
            renderFilledTile(g, pt.point, withAlpha(base, FILL_ALPHA), withAlpha(base, BORDER_ALPHA), PREVIEW_STROKE);
        }

        g.setStroke(PREVIEW_STROKE);
        g.setColor(COLOR_ROUTE_LINE);
        for (int i = 0; i < placed.size() - 1; i++)
        {
            Point fromCanvas = tileCenterOnCanvas(placed.get(i).point);
            Point toCanvas = tileCenterOnCanvas(placed.get(i + 1).point);
            if (fromCanvas == null || toCanvas == null) continue;
            g.drawLine(fromCanvas.x, fromCanvas.y, toCanvas.x, toCanvas.y);
        }
    }

    private void renderFilledTile(Graphics2D g, WorldPoint wp, Color fill, Color border, Stroke stroke)
    {
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            g.setColor(fill);
            g.fillPolygon(poly);
            g.setColor(border);
            g.setStroke(stroke);
            g.drawPolygon(poly);
        }
    }

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static Color defaultColorFor(String tileType)
    {
        if ("PATH".equals(tileType)) return COLOR_PATH;
        if ("START".equals(tileType)) return COLOR_START;
        if ("GNOMEBALL_TILE".equals(tileType)) return COLOR_GNOMEBALL_TILE;
        if ("EVENT_TILE".equals(tileType)) return COLOR_EVENT_TILE;
        return COLOR_UNKNOWN_TYPE;
    }

    private static Color resolveColor(String hex, String tileType)
    {
        if (hex == null || hex.isBlank()) return defaultColorFor(tileType);
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return defaultColorFor(tileType); }
    }
}
