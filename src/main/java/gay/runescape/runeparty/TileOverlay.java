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
    private static final Color COLOR_PENALTY_TILE  = new Color(220, 50, 50); // functions like PATH, but -3 coins on landing (floored at 0)
    private static final Color COLOR_START         = new Color(60, 179, 74);
    private static final Color COLOR_GNOMEBALL_TILE = new Color(255, 210, 0);
    private static final Color COLOR_EVENT_TILE    = new Color(170, 80, 220);
    private static final Color COLOR_ROUTE_LINE    = new Color(255, 255, 255, 100);
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
     * -- labeled with their name centered above it, over <i>every</i> candidate tile the current
     * roll could resolve to. Usually that's one tile, but a roll whose path crosses a fork can
     * offer more than one (see RunePartyPlugin#getPendingTargetIndices and the server's
     * _reachable_targets) -- the player picks which one to walk to, so every candidate gets its
     * own arrow. Visible to every client watching, not just the mover, the same way the rest of
     * the board state is shared. All of them disappear together as soon as the mover is actually
     * standing on any one candidate (checked directly against their on-screen position every
     * frame, not just on the next TURN_STARTED echo) or, failing that, once TURN_STARTED clears
     * pendingRoll/pendingTargetIndices (see RunePartyPlugin#handleEvent) -- e.g. if the mover
     * isn't currently rendered for this client. */
    private void renderTargetArrow(Graphics2D g)
    {
        if (!plugin.isPendingRoll()) return;
        List<Integer> targetIndices = plugin.getPendingTargetIndices();
        if (targetIndices.isEmpty()) return;
        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null) return;

        Player mover = findPlayerByRsn(moverRsn);
        WorldPoint moverPos = mover != null ? mover.getWorldLocation() : null;

        if (moverPos != null)
        {
            for (int targetIndex : targetIndices)
            {
                TileReducer.TileEntry target = tileReducer.tileAtIndex(targetIndex);
                if (target != null && target.point.equals(moverPos)) return; // already chose one -- hide every candidate
            }
        }

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(moverRsn));
        Color arrowColor = seatColor != null ? seatColor.awt : COLOR_TARGET_ARROW;

        for (int targetIndex : targetIndices)
        {
            TileReducer.TileEntry target = tileReducer.tileAtIndex(targetIndex);
            if (target == null) continue;
            drawBouncingArrowWithLabel(g, target.point, moverRsn, arrowColor);
        }
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

    /** Draws a line from each path tile to every tile it actually leads to (see
     * TileReducer#resolveNextIndices), so the walked route reads clearly even though every tile
     * renders as an independent filled square -- a fork fans out into more than one line, and a
     * branch's tiles converge back onto whatever merge tile they were pointed at. Only draws
     * between tiles that both actually exist -- a gap in the course just leaves that one segment
     * undrawn rather than guessing a connection across it. */
    private void renderRouteLines(Graphics2D g, List<TileReducer.TileEntry> entries)
    {
        if (entries.isEmpty()) return;

        g.setStroke(ROUTE_STROKE);
        g.setColor(COLOR_ROUTE_LINE);

        for (TileReducer.TileEntry from : entries)
        {
            if (from.pathIndex == null) continue;

            for (int nextIndex : tileReducer.resolveNextIndices(from))
            {
                TileReducer.TileEntry to = tileReducer.tileAtIndex(nextIndex);
                if (to == null) continue;

                Point fromCanvas = tileCenterOnCanvas(from.point);
                Point toCanvas = tileCenterOnCanvas(to.point);
                if (fromCanvas == null || toCanvas == null) continue;

                g.drawLine(fromCanvas.x, fromCanvas.y, toCanvas.x, toCanvas.y);
            }
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
        int length = placed.size();
        for (int i = 0; i < length; i++)
        {
            CoursePreset.PlacedTile pt = placed.get(i);
            for (int nextIndex : resolvePreviewNextIndices(pt, i, length))
            {
                if (nextIndex < 0 || nextIndex >= length) continue;
                Point fromCanvas = tileCenterOnCanvas(pt.point);
                Point toCanvas = tileCenterOnCanvas(placed.get(nextIndex).point);
                if (fromCanvas == null || toCanvas == null) continue;
                g.drawLine(fromCanvas.x, fromCanvas.y, toCanvas.x, toCanvas.y);
            }
        }
    }

    /** Same default-or-explicit resolution as TileReducer#resolveNextIndices, but for a live
     * placement preview's in-memory PlacedTile list (not yet committed, so there's no TileReducer
     * entry -- or courseLength -- to resolve against yet). */
    private static int[] resolvePreviewNextIndices(CoursePreset.PlacedTile pt, int index, int length)
    {
        if (pt.nextIndices.length > 0) return pt.nextIndices;
        if (length == 0) return new int[0];
        return new int[] { (index + 1) % length };
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
        if ("PENALTY_TILE".equals(tileType)) return COLOR_PENALTY_TILE;
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
