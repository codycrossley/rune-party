package gay.runescape.runeparty;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Full-screen, in-world top-down schematic of the current course -- every marked tile in its own
 * type color, a small gold marker over any Golden Gnome modifier, a diamond over any Coin Trap,
 * and every seated PLAYER's live position as a dot in their own RunePartyColor (the local player's
 * ring drawn a little heavier so "where am I" is a glance, not a search). Toggled via
 * RunePartyPlugin#toggleMap -- dims the whole game view and draws directly on the canvas rather
 * than opening a separate window, and (see AnnouncementOverlay's own isMapShowing() gate)
 * suppresses every banner/announcement while up so nothing draws over it. Purely a repaint of
 * RunePartyPlugin/TileReducer/RosterReducer's already-live state every frame.
 * <p>
 * Per-tile hover detail (see maybeShowTooltip) is computed fresh each render() from this exact
 * frame's own on-screen layout (see lastMinX/lastMaxY/lastPlane/lastOriginX/lastOriginY) rather
 * than a cached one -- an Overlay gets no dedicated per-pixel mouse hook of its own, so this just
 * does the same hit-test inline every frame it's showing. */
public class RunePartyMapOverlay extends Overlay
{
    private static final int CELL_SIZE = 22;
    private static final int PADDING = 20;
    private static final int TILE_GAP = 2; // shrinks each cell's fill/border slightly so adjacent tiles read as distinct squares
    private static final int TITLE_GAP = 10; // between the title and the grid below it

    // Footer legend layout -- see drawLegendFooter's own doc.
    private static final int FOOTER_PADDING_X = 24;
    private static final int FOOTER_PADDING_Y = 10;
    private static final int FOOTER_ROW_HEIGHT = 22;
    private static final int FOOTER_ICON_SIZE = 13;
    private static final int FOOTER_ICON_TEXT_GAP = 6;
    private static final int FOOTER_ITEM_GAP = 22; // between one item's text and the next item's icon

    private static final Color BACKDROP = new Color(0, 0, 0, 165); // dims the whole game view behind the panel
    private static final Color PANEL_BACKGROUND = new Color(24, 24, 24, 230);
    private static final Color PANEL_BORDER = new Color(120, 120, 120, 220);
    private static final Color FOOTER_BACKGROUND = new Color(16, 16, 16, 225);
    private static final Color FOOTER_BORDER = new Color(120, 120, 120, 200);
    private static final Color TITLE_COLOR = new Color(190, 220, 255);
    private static final Color EMPTY_TEXT = new Color(200, 200, 200);
    private static final Color ROUTE_LINE = new Color(255, 255, 255, 90);
    private static final Color GOLDEN_GNOME_MARKER = new Color(255, 215, 0);
    private static final Color GOLDEN_GNOME_MARKER_BORDER = Color.BLACK;
    private static final Color COIN_TRAP_MARKER = new Color(150, 80, 30);
    private static final Color COIN_TRAP_MARKER_BORDER = Color.BLACK;
    private static final Color PLAYER_LABEL = Color.WHITE;
    private static final Color PLAYER_DOT_BORDER = Color.BLACK;
    private static final Color LOCAL_PLAYER_RING = Color.WHITE;

    private static final int PLAYER_DOT_RADIUS = 6;
    private static final int PLAYER_DOT_SPREAD = 10; // how far apart dots fan out when more than one player shares a tile

    private static final String TITLE = "BOARD MAP";

    private final Client client;
    private final RunePartyPlugin plugin;
    private final TooltipManager tooltipManager;

    // This frame's own on-screen layout, needed to invert a mouse canvas position back to a
    // WorldPoint for maybeShowTooltip -- see this class's own doc for why there's no cached
    // dialog-style version of this.
    private int lastMinX, lastMaxY, lastPlane, lastOriginX, lastOriginY;
    private boolean boundsKnown = false;

    public RunePartyMapOverlay(Client client, RunePartyPlugin plugin, TooltipManager tooltipManager)
    {
        this.client = client;
        this.plugin = plugin;
        this.tooltipManager = tooltipManager;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isMapShowing())
        {
            boundsKnown = false;
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        int viewportWidth = client.getCanvasWidth();
        int viewportHeight = client.getCanvasHeight();
        g.setColor(BACKDROP);
        g.fillRect(0, 0, viewportWidth, viewportHeight);

        List<TileReducer.TileEntry> tiles = plugin.getTileReducer().snapshot();
        if (tiles.isEmpty())
        {
            boundsKnown = false;
            renderEmptyPanel(g, viewportWidth, viewportHeight);
            drawLegendFooter(g, viewportWidth, viewportHeight);
            return null;
        }

        // Majority plane only, same reasoning as CoursePreset#fromTiles -- a stray off-plane tile
        // shouldn't skew the bounds every other tile gets laid out against.
        Map<Integer, Integer> countByPlane = new HashMap<>();
        for (TileReducer.TileEntry t : tiles) countByPlane.merge(t.point.getPlane(), 1, Integer::sum);
        int plane = tiles.get(0).point.getPlane();
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> e : countByPlane.entrySet())
        {
            if (e.getValue() > bestCount) { bestCount = e.getValue(); plane = e.getKey(); }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (TileReducer.TileEntry t : tiles)
        {
            if (t.point.getPlane() != plane) continue;
            minX = Math.min(minX, t.point.getX());
            maxX = Math.max(maxX, t.point.getX());
            minY = Math.min(minY, t.point.getY());
            maxY = Math.max(maxY, t.point.getY());
        }

        int gridWidth = (maxX - minX + 1) * CELL_SIZE;
        int gridHeight = (maxY - minY + 1) * CELL_SIZE;

        g.setFont(FontManager.getRunescapeBoldFont());
        int titleWidth = g.getFontMetrics().stringWidth(TITLE);

        int contentWidth = Math.max(gridWidth, titleWidth);
        int panelWidth = contentWidth + PADDING * 2;
        int panelHeight = PADDING + g.getFontMetrics().getHeight() + TITLE_GAP + gridHeight + PADDING;

        int originX = (viewportWidth - panelWidth) / 2;
        int originY = (viewportHeight - panelHeight) / 2;

        drawPanelBackground(g, originX, originY, panelWidth, panelHeight);

        g.setFont(FontManager.getRunescapeBoldFont());
        FontMetrics titleFm = g.getFontMetrics();
        RunePartyRender.drawShadowed(g, TITLE, originX + (panelWidth - titleWidth) / 2, originY + PADDING + titleFm.getAscent(), TITLE_COLOR, 255);

        int gridOriginX = originX + (panelWidth - gridWidth) / 2;
        int gridOriginY = originY + PADDING + titleFm.getHeight() + TITLE_GAP;

        lastMinX = minX;
        lastMaxY = maxY;
        lastPlane = plane;
        lastOriginX = gridOriginX;
        lastOriginY = gridOriginY;
        boundsKnown = true;

        // Route lines first, so tile fills/borders and everything above them draw on top.
        g.setStroke(new BasicStroke(2f));
        g.setColor(ROUTE_LINE);
        for (TileReducer.TileEntry from : tiles)
        {
            if (from.point.getPlane() != plane || from.pathIndex == null) continue;
            Point fromCenter = cellCenter(from.point, minX, maxY, gridOriginX, gridOriginY);
            for (int nextIndex : plugin.getTileReducer().resolveNextIndices(from))
            {
                TileReducer.TileEntry to = plugin.getTileReducer().tileAtIndex(nextIndex);
                if (to == null || to.point.getPlane() != plane) continue;
                Point toCenter = cellCenter(to.point, minX, maxY, gridOriginX, gridOriginY);
                g.drawLine(fromCenter.getX(), fromCenter.getY(), toCenter.getX(), toCenter.getY());
            }
        }

        // Tile fills -- Golden Gnome/Coin Trap modifiers are drawn as a marker over their
        // underlying PATH tile afterward (see below), not as a fill of their own, same
        // "decorative overlay, not its own course stop" treatment TileOverlay gives them in-world.
        for (TileReducer.TileEntry t : tiles)
        {
            if (t.point.getPlane() != plane) continue;
            if ("GOLDEN_GNOME_TILE".equals(t.tileType) || "COIN_TRAP_TILE".equals(t.tileType)) continue;
            Point topLeft = cellTopLeft(t.point, minX, maxY, gridOriginX, gridOriginY);
            int s = CELL_SIZE - TILE_GAP;
            Color color = tileColor(t.tileType, t.color);
            g.setColor(RunePartyRender.withAlpha(color, 160));
            g.fillRect(topLeft.getX(), topLeft.getY(), s, s);
            g.setColor(color);
            g.drawRect(topLeft.getX(), topLeft.getY(), s, s);
        }

        for (TileReducer.TileEntry t : tiles)
        {
            if (t.point.getPlane() != plane || !"GOLDEN_GNOME_TILE".equals(t.tileType)) continue;
            drawGnomeMarker(g, cellCenter(t.point, minX, maxY, gridOriginX, gridOriginY));
        }

        for (TileReducer.TileEntry t : tiles)
        {
            if (t.point.getPlane() != plane || !"COIN_TRAP_TILE".equals(t.tileType)) continue;
            drawCoinTrapMarker(g, cellCenter(t.point, minX, maxY, gridOriginX, gridOriginY));
        }

        paintPlayers(g, minX, maxY, plane, gridOriginX, gridOriginY);

        drawLegendFooter(g, viewportWidth, viewportHeight);
        maybeShowTooltip();

        return null;
    }

    private void renderEmptyPanel(Graphics2D g, int viewportWidth, int viewportHeight)
    {
        g.setFont(FontManager.getRunescapeFont());
        FontMetrics fm = g.getFontMetrics();
        String text = "No course marked yet.";
        int width = fm.stringWidth(text) + PADDING * 2;
        int height = fm.getHeight() + PADDING * 2;
        int originX = (viewportWidth - width) / 2;
        int originY = (viewportHeight - height) / 2;

        drawPanelBackground(g, originX, originY, width, height);
        g.setColor(EMPTY_TEXT);
        g.drawString(text, originX + PADDING, originY + PADDING + fm.getAscent());
    }

    private void drawPanelBackground(Graphics2D g, int x, int y, int width, int height)
    {
        g.setColor(PANEL_BACKGROUND);
        g.fillRoundRect(x, y, width, height, 14, 14);
        g.setStroke(new BasicStroke(2f));
        g.setColor(PANEL_BORDER);
        g.drawRoundRect(x, y, width, height, 14, 14);
    }

    /** Hit-tests the mouse's current canvas position against this exact frame's own grid layout
     * (see lastMinX/lastMaxY/lastPlane/lastOriginX/lastOriginY) and, if it lands on a cell with
     * anything to say, queues up its detail via TooltipManager -- one Tooltip per line. */
    private void maybeShowTooltip()
    {
        if (!boundsKnown) return;
        Point mouse = client.getMouseCanvasPosition();
        if (mouse == null) return;

        WorldPoint wp = worldPointAt(mouse, lastMinX, lastMaxY, lastPlane, lastOriginX, lastOriginY);
        if (wp == null) return;

        List<TileReducer.TileEntry> here = new ArrayList<>();
        for (TileReducer.TileEntry t : plugin.getTileReducer().snapshot())
        {
            if (t.point.equals(wp)) here.add(t);
        }

        List<String> standing = new ArrayList<>();
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().seatedPlayers())
        {
            TileReducer.TileEntry playerTile = plugin.getTileReducer().tileAtIndex(plugin.getPlayerPosition(entry.rsn));
            if (playerTile != null && playerTile.point.equals(wp)) standing.add(entry.rsn);
        }

        if (here.isEmpty() && standing.isEmpty()) return;

        for (TileReducer.TileEntry t : here)
        {
            tooltipManager.add(new Tooltip(tileDescription(t)));
        }
        if (!standing.isEmpty())
        {
            tooltipManager.add(new Tooltip("Standing here: " + String.join(", ", standing)));
        }
    }

    private WorldPoint worldPointAt(Point p, int minX, int maxY, int plane, int originX, int originY)
    {
        int col = Math.floorDiv(p.getX() - originX - PADDING, CELL_SIZE);
        int row = Math.floorDiv(p.getY() - originY, CELL_SIZE);
        if (col < 0 || row < 0) return null;
        return new WorldPoint(minX + col, maxY - row, plane);
    }

    /** Plain-English gloss for one tile/modifier -- see buildLegendRows for the short-form
     * color-swatch summary; this is the fuller per-tile version, including how far it is from the
     * local viewer's own current position where that's meaningful (see stepsAwaySuffix). */
    private String tileDescription(TileReducer.TileEntry t)
    {
        ApiClient.TileTypeOut type = plugin.getTileTypeCatalog().get(t.tileType);
        String base = type != null ? type.description : t.tileType;
        if (t.pathIndex == null) return base;

        String suffix = stepsAwaySuffix(t.pathIndex);
        return suffix != null ? base + suffix : base;
    }

    /** " -- N tiles away" from the local viewer's own current board position, or null if there's
     * nothing to say -- an observer (not a seated PLAYER) has no position of their own to measure
     * from, and a target genuinely unreachable from here at all (a dead end, or a gap in the
     * course) has no meaningful distance either. */
    private String stepsAwaySuffix(int targetPathIndex)
    {
        String localRsn = plugin.getLocalRsn();
        if (localRsn == null) return null;

        RosterReducer.RosterEntry self = null;
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().seatedPlayers())
        {
            if (entry.rsn.equalsIgnoreCase(localRsn)) { self = entry; break; }
        }
        if (self == null) return null;

        Integer steps = plugin.getTileReducer().stepsBetween(plugin.getPlayerPosition(self.rsn), targetPathIndex);
        if (steps == null) return null;
        return " -- " + steps + (steps == 1 ? " tile away" : " tiles away");
    }

    /** One dot per seated, joined PLAYER at their live board position, fanned out a little when
     * more than one shares a tile so they don't just stack into an unreadable blob -- gathering at
     * START is exactly when this comes up most. The local player's dot gets a heavier white ring
     * around it, always on here rather than turn-gated, since there's no obvious "your turn"
     * moment on a static map. */
    private void paintPlayers(Graphics2D g, int minX, int maxY, int plane, int originX, int originY)
    {
        String localRsn = plugin.getLocalRsn();

        Map<Point, List<RosterReducer.RosterEntry>> byCell = new HashMap<>();
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().seatedPlayers())
        {
            TileReducer.TileEntry tile = plugin.getTileReducer().tileAtIndex(plugin.getPlayerPosition(entry.rsn));
            if (tile == null || tile.point.getPlane() != plane) continue;
            byCell.computeIfAbsent(cellCenter(tile.point, minX, maxY, originX, originY), c -> new ArrayList<>()).add(entry);
        }

        g.setFont(FontManager.getRunescapeSmallFont());
        for (Map.Entry<Point, List<RosterReducer.RosterEntry>> cell : byCell.entrySet())
        {
            List<RosterReducer.RosterEntry> here = cell.getValue();
            for (int i = 0; i < here.size(); i++)
            {
                RosterReducer.RosterEntry entry = here.get(i);
                // Fans the group out symmetrically around the tile's true center, e.g. 3 players
                // land at offsets -SPREAD, 0, +SPREAD regardless of which index in `here` each
                // happens to be.
                int offset = here.size() == 1 ? 0 : Math.round((i - (here.size() - 1) / 2f) * PLAYER_DOT_SPREAD);
                int cx = cell.getKey().getX() + offset;
                int cy = cell.getKey().getY();

                RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
                Color dotColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
                boolean isLocal = entry.rsn.equalsIgnoreCase(localRsn);

                if (isLocal)
                {
                    g.setColor(LOCAL_PLAYER_RING);
                    g.setStroke(new BasicStroke(3f));
                    g.drawOval(cx - PLAYER_DOT_RADIUS - 3, cy - PLAYER_DOT_RADIUS - 3, (PLAYER_DOT_RADIUS + 3) * 2, (PLAYER_DOT_RADIUS + 3) * 2);
                }

                g.setColor(dotColor);
                g.fillOval(cx - PLAYER_DOT_RADIUS, cy - PLAYER_DOT_RADIUS, PLAYER_DOT_RADIUS * 2, PLAYER_DOT_RADIUS * 2);
                g.setColor(PLAYER_DOT_BORDER);
                g.setStroke(new BasicStroke(1.5f));
                g.drawOval(cx - PLAYER_DOT_RADIUS, cy - PLAYER_DOT_RADIUS, PLAYER_DOT_RADIUS * 2, PLAYER_DOT_RADIUS * 2);

                String label = isLocal ? "You" : entry.rsn;
                FontMetrics fm = g.getFontMetrics();
                int labelX = cx - fm.stringWidth(label) / 2;
                int labelY = cy - PLAYER_DOT_RADIUS - 6;
                RunePartyRender.drawShadowed(g, label, labelX, labelY, PLAYER_LABEL, 255);
            }
        }
    }

    /** A plain tile-color swatch (SQUARE), the Golden Gnome's own isosceles triangle, or the Coin
     * Trap's own diamond -- one shared shape/size-parameterized drawer, used both for the grid's
     * own in-world markers (drawGnomeMarker/drawCoinTrapMarker below) and the footer legend's own
     * small icons (see drawLegendFooter), so the legend key always matches the marker it's keying.
     * {@code size} is the shape's own width; TRIANGLE's height follows the same 5:4 ratio the
     * in-world marker uses. */
    private void drawShapeIcon(Graphics2D g, LegendShape shape, Color fill, Color border, int centerX, int centerY, int size)
    {
        switch (shape)
        {
            case TRIANGLE:
            {
                int height = size * 5 / 4;
                Polygon triangle = new Polygon();
                triangle.addPoint(centerX, centerY - height / 2);
                triangle.addPoint(centerX - size / 2, centerY + height / 2);
                triangle.addPoint(centerX + size / 2, centerY + height / 2);
                g.setColor(fill);
                g.fillPolygon(triangle);
                g.setStroke(new BasicStroke(1.3f));
                g.setColor(border);
                g.drawPolygon(triangle);
                break;
            }
            case DIAMOND:
            {
                int r = size / 2;
                Polygon diamond = new Polygon();
                diamond.addPoint(centerX, centerY - r);
                diamond.addPoint(centerX + r, centerY);
                diamond.addPoint(centerX, centerY + r);
                diamond.addPoint(centerX - r, centerY);
                g.setColor(fill);
                g.fillPolygon(diamond);
                g.setStroke(new BasicStroke(1.3f));
                g.setColor(border);
                g.drawPolygon(diamond);
                break;
            }
            case SQUARE:
            default:
            {
                int half = size / 2;
                g.setColor(fill);
                g.fillRect(centerX - half, centerY - half, size, size);
                g.setColor(border);
                g.drawRect(centerX - half, centerY - half, size, size);
                break;
            }
        }
    }

    private void drawGnomeMarker(Graphics2D g, Point center)
    {
        drawShapeIcon(g, LegendShape.TRIANGLE, GOLDEN_GNOME_MARKER, GOLDEN_GNOME_MARKER_BORDER, center.getX(), center.getY(), CELL_SIZE * 7 / 10);
    }

    private void drawCoinTrapMarker(Graphics2D g, Point center)
    {
        drawShapeIcon(g, LegendShape.DIAMOND, COIN_TRAP_MARKER, COIN_TRAP_MARKER_BORDER, center.getX(), center.getY(), CELL_SIZE * 7 / 10);
    }

    private Point cellTopLeft(WorldPoint wp, int minX, int maxY, int originX, int originY)
    {
        int x = originX + PADDING + (wp.getX() - minX) * CELL_SIZE;
        int y = originY + (maxY - wp.getY()) * CELL_SIZE; // north-up: world Y grows north, screen Y grows down
        return new Point(x, y);
    }

    private Point cellCenter(WorldPoint wp, int minX, int maxY, int originX, int originY)
    {
        Point topLeft = cellTopLeft(wp, minX, maxY, originX, originY);
        return new Point(topLeft.getX() + CELL_SIZE / 2, topLeft.getY() + CELL_SIZE / 2);
    }

    private enum LegendShape { SQUARE, TRIANGLE, DIAMOND }

    private static final class LegendRow
    {
        final LegendShape shape;
        final Color color;
        final String label;

        LegendRow(LegendShape shape, Color color, String label)
        {
            this.shape = shape;
            this.color = color;
            this.label = label;
        }
    }

    /** Fixed reference list, one icon/color + short name per tile type -- just enough to place a
     * color or marker shape, not the fuller reward-number description maybeShowTooltip's own
     * per-tile hover already gives. Non-modifier types come from the served catalog; the 2
     * modifiers get their own bespoke marker shape/color below rather than a tile-outline square,
     * but still pull their own short name from the same served catalog rather than hardcoding it a
     * second time. */
    private List<LegendRow> buildLegendRows()
    {
        List<LegendRow> rows = new ArrayList<>();
        for (ApiClient.TileTypeOut t : plugin.getTileTypeCatalog().values())
        {
            if (t.isModifier) continue;
            rows.add(new LegendRow(LegendShape.SQUARE, tileColor(t.key, null), t.displayName != null ? t.displayName : t.key));
        }
        rows.add(new LegendRow(LegendShape.TRIANGLE, GOLDEN_GNOME_MARKER, modifierName("GOLDEN_GNOME_TILE", "Golden Gnome")));
        rows.add(new LegendRow(LegendShape.DIAMOND, COIN_TRAP_MARKER, modifierName("COIN_TRAP_TILE", "Coin Trap")));
        return rows;
    }

    /** A full-width footer bar, flush with the bottom of the game view, listing every legend entry
     * left-to-right and wrapping onto another row once a line would run past the viewport's own
     * width. Two passes: the first only measures how many rows the wrap actually needs (so the
     * background bar's own height is known up front and every row draws top-down inside it, rather
     * than guessing tall enough and drawing bottom-up); the second actually draws. */
    private void drawLegendFooter(Graphics2D g, int viewportWidth, int viewportHeight)
    {
        List<LegendRow> rows = buildLegendRows();
        g.setFont(FontManager.getRunescapeSmallFont());
        FontMetrics fm = g.getFontMetrics();

        int maxLineWidth = viewportWidth - FOOTER_PADDING_X * 2;
        int rowCount = 1;
        int lineWidth = 0;
        for (LegendRow row : rows)
        {
            int itemWidth = FOOTER_ICON_SIZE + FOOTER_ICON_TEXT_GAP + fm.stringWidth(row.label);
            int addedWidth = lineWidth == 0 ? itemWidth : FOOTER_ITEM_GAP + itemWidth;
            if (lineWidth != 0 && lineWidth + addedWidth > maxLineWidth)
            {
                rowCount++;
                lineWidth = itemWidth;
            }
            else
            {
                lineWidth += addedWidth;
            }
        }

        int footerHeight = FOOTER_PADDING_Y * 2 + rowCount * FOOTER_ROW_HEIGHT;
        int footerY = viewportHeight - footerHeight;

        g.setColor(FOOTER_BACKGROUND);
        g.fillRect(0, footerY, viewportWidth, footerHeight);
        g.setStroke(new BasicStroke(1.5f));
        g.setColor(FOOTER_BORDER);
        g.drawLine(0, footerY, viewportWidth, footerY);

        int x = FOOTER_PADDING_X;
        int y = footerY + FOOTER_PADDING_Y;
        for (LegendRow row : rows)
        {
            int itemWidth = FOOTER_ICON_SIZE + FOOTER_ICON_TEXT_GAP + fm.stringWidth(row.label);
            if (x != FOOTER_PADDING_X && x + itemWidth > viewportWidth - FOOTER_PADDING_X)
            {
                x = FOOTER_PADDING_X;
                y += FOOTER_ROW_HEIGHT;
            }

            int iconCenterY = y + FOOTER_ROW_HEIGHT / 2;
            drawShapeIcon(g, row.shape, row.color, Color.BLACK, x + FOOTER_ICON_SIZE / 2, iconCenterY, FOOTER_ICON_SIZE);

            g.setColor(EMPTY_TEXT);
            g.drawString(row.label, x + FOOTER_ICON_SIZE + FOOTER_ICON_TEXT_GAP, iconCenterY + fm.getAscent() / 2 - 2);

            x += itemWidth + FOOTER_ITEM_GAP;
        }
    }

    /** Pulls a modifier tile type's own served display name -- falls back to {@code fallback} if
     * the catalog fetch failed or hasn't landed yet, same defensive shape tileColor already uses
     * for a missing catalog entry. */
    private String modifierName(String tileType, String fallback)
    {
        ApiClient.TileTypeOut t = plugin.getTileTypeCatalog().get(tileType);
        return t != null && t.displayName != null ? t.displayName : fallback;
    }

    /** Same served catalog TileOverlay#defaultColorFor reads, rather than its own hardcoded table
     * -- falls back to plain yellow for a type the catalog doesn't have. */
    private Color tileColor(String tileType, String hex)
    {
        if (hex != null && !hex.isBlank())
        {
            try { return Color.decode(hex); }
            catch (NumberFormatException ignored) { /* fall through to the type default below */ }
        }
        ApiClient.TileTypeOut t = plugin.getTileTypeCatalog().get(tileType);
        if (t == null || t.colorHex == null) return new Color(255, 255, 0);
        try { return Color.decode(t.colorHex); }
        catch (NumberFormatException e) { return new Color(255, 255, 0); }
    }
}
