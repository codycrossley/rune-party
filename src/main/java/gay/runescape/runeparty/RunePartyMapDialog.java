package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.FontManager;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** A non-modal top-down schematic of the current course -- every marked tile in its own type
 * color, a small gold marker over any Golden Gnome modifier, and every seated PLAYER's live
 * position as a dot in their own RunePartyColor (the local player's ring drawn a little heavier so
 * "where am I" is a glance, not a search). Opened via RunePartyPlugin#showMap (the panel's "Show
 * Map" button); non-modal so it doesn't block playing the game while it's open. Purely a Swing
 * repaint of RunePartyPlugin/TileReducer/RosterReducer's already-live state -- like every overlay
 * in this plugin, it never computes or guesses a total or position itself, and unlike an in-world
 * Overlay it isn't bound by camera/viewport, so the whole course is visible at once regardless of
 * where the player's standing. Refreshes on its own light timer rather than being wired into
 * RunePartyPlugin's event-driven refreshPanel() chain, so it can open and close independently
 * without the plugin needing to track a dialog's lifecycle beyond disposing it on shutDown. */
public class RunePartyMapDialog extends JDialog
{
    private static final int CELL_SIZE = 22;
    private static final int PADDING = 20;
    private static final int TILE_GAP = 2; // shrinks each cell's fill/border slightly so adjacent tiles read as distinct squares
    private static final long REFRESH_PERIOD_MS = 400;

    private static final Color BACKGROUND = new Color(24, 24, 24);
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

    private final RunePartyPlugin plugin;
    private final Timer refreshTimer;

    public RunePartyMapDialog(Window owner, RunePartyPlugin plugin)
    {
        super(owner, "Rune Party -- Course Map", ModalityType.MODELESS);
        this.plugin = plugin;

        MapPanel mapPanel = new MapPanel();
        JScrollPane scrollPane = new JScrollPane(mapPanel);
        scrollPane.setPreferredSize(new Dimension(420, 320));

        // Legend fixed at the bottom (always visible, no scrolling needed to see it) -- see
        // buildLegend. Per-tile detail beyond a color/marker's own short label is instead a hover
        // tooltip on the map itself, see MapPanel#getToolTipText.
        JPanel content = new JPanel(new BorderLayout());
        content.add(scrollPane, BorderLayout.CENTER);
        content.add(buildLegend(), BorderLayout.SOUTH);
        setContentPane(content);
        setResizable(true);

        refreshTimer = new Timer((int) REFRESH_PERIOD_MS, e -> mapPanel.repaint());
    }

    @Override
    public void setVisible(boolean visible)
    {
        if (visible)
        {
            pack();
            setLocationRelativeTo(getOwner());
            refreshTimer.start();
        }
        else
        {
            refreshTimer.stop();
        }
        super.setVisible(visible);
    }

    @Override
    public void dispose()
    {
        refreshTimer.stop();
        super.dispose();
    }

    private class MapPanel extends JPanel
    {
        // Cached from the most recent paintComponent -- see getToolTipText, which needs the same
        // bounds/plane paintComponent just used to lay tiles out, to map a mouse point back to the
        // WorldPoint under it. Null (via boundsKnown) whenever there's no course to hover at all.
        private int lastMinX, lastMaxY, lastPlane;
        private boolean boundsKnown = false;

        MapPanel()
        {
            setBackground(BACKGROUND);
            setPreferredSize(new Dimension(300, 200));
            setToolTipText(""); // registers this component with ToolTipManager -- see getToolTipText for the real per-tile content
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            List<TileReducer.TileEntry> tiles = plugin.getTileReducer().snapshot();
            if (tiles.isEmpty())
            {
                boundsKnown = false;
                g.setColor(EMPTY_TEXT);
                g.setFont(FontManager.getRunescapeFont());
                g.drawString("No course marked yet.", PADDING, PADDING + 12);
                return;
            }

            // Majority plane only, same reasoning as CoursePreset#fromTiles -- a stray off-plane
            // tile shouldn't skew the bounds every other tile gets laid out against.
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

            lastMinX = minX;
            lastMaxY = maxY;
            lastPlane = plane;
            boundsKnown = true;

            int width = (maxX - minX + 1) * CELL_SIZE + PADDING * 2;
            int height = (maxY - minY + 1) * CELL_SIZE + PADDING * 2;
            Dimension size = new Dimension(width, height);
            if (!size.equals(getPreferredSize()))
            {
                setPreferredSize(size);
                revalidate();
            }

            // Route lines first, so tile fills/borders and everything above them draw on top.
            g.setStroke(new BasicStroke(2f));
            g.setColor(ROUTE_LINE);
            for (TileReducer.TileEntry from : tiles)
            {
                if (from.point.getPlane() != plane || from.pathIndex == null) continue;
                Point fromCenter = cellCenter(from.point, minX, maxY);
                for (int nextIndex : plugin.getTileReducer().resolveNextIndices(from))
                {
                    TileReducer.TileEntry to = plugin.getTileReducer().tileAtIndex(nextIndex);
                    if (to == null || to.point.getPlane() != plane) continue;
                    Point toCenter = cellCenter(to.point, minX, maxY);
                    g.drawLine(fromCenter.x, fromCenter.y, toCenter.x, toCenter.y);
                }
            }

            // Tile fills -- Golden Gnome/Coin Trap modifiers are drawn as a marker over their
            // underlying PATH tile afterward (see below), not as a fill of their own, same
            // "decorative overlay, not its own course stop" treatment TileOverlay gives them
            // in-world.
            for (TileReducer.TileEntry t : tiles)
            {
                if (t.point.getPlane() != plane) continue;
                if ("GOLDEN_GNOME_TILE".equals(t.tileType) || "COIN_TRAP_TILE".equals(t.tileType)) continue;
                Point topLeft = cellTopLeft(t.point, minX, maxY);
                int s = CELL_SIZE - TILE_GAP;
                Color color = tileColor(t.tileType, t.color);
                g.setColor(withAlpha(color, 160));
                g.fillRect(topLeft.x, topLeft.y, s, s);
                g.setColor(color);
                g.drawRect(topLeft.x, topLeft.y, s, s);
            }

            // Golden Gnome markers.
            for (TileReducer.TileEntry t : tiles)
            {
                if (t.point.getPlane() != plane || !"GOLDEN_GNOME_TILE".equals(t.tileType)) continue;
                drawGnomeMarker(g, cellCenter(t.point, minX, maxY));
            }

            // Coin Trap markers.
            for (TileReducer.TileEntry t : tiles)
            {
                if (t.point.getPlane() != plane || !"COIN_TRAP_TILE".equals(t.tileType)) continue;
                drawCoinTrapMarker(g, cellCenter(t.point, minX, maxY));
            }

            paintPlayers(g, minX, maxY, plane);
        }

        /** Per-tile detail on hover -- see buildLegend's own doc for how this complements rather
         * than duplicates the fixed legend strip. Maps the mouse point back to whichever WorldPoint
         * cell it's over using the same bounds/plane the most recent paintComponent laid tiles out
         * against (see lastMinX/lastMaxY/lastPlane), then lists every tile stacked there (a
         * modifier and its underlying course tile share a cell) plus who's currently standing on
         * it. Null -- no tooltip at all -- off the course entirely or before a course exists. */
        @Override
        public String getToolTipText(MouseEvent event)
        {
            if (!boundsKnown) return null;
            WorldPoint wp = worldPointAt(event.getPoint(), lastMinX, lastMaxY, lastPlane);
            return wp != null ? tileInfoHtml(wp) : null;
        }

        private WorldPoint worldPointAt(Point p, int minX, int maxY, int plane)
        {
            int col = Math.floorDiv(p.x - PADDING, CELL_SIZE);
            int row = Math.floorDiv(p.y - PADDING, CELL_SIZE);
            if (col < 0 || row < 0) return null;
            return new WorldPoint(minX + col, maxY - row, plane);
        }

        private String tileInfoHtml(WorldPoint wp)
        {
            List<TileReducer.TileEntry> here = new ArrayList<>();
            for (TileReducer.TileEntry t : plugin.getTileReducer().snapshot())
            {
                if (t.point.equals(wp)) here.add(t);
            }

            List<String> standing = new ArrayList<>();
            for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().snapshot())
            {
                if (entry.role != RunePartyRole.PLAYER || !entry.joined) continue;
                TileReducer.TileEntry playerTile = plugin.getTileReducer().tileAtIndex(plugin.getPlayerPosition(entry.rsn));
                if (playerTile != null && playerTile.point.equals(wp)) standing.add(entry.rsn);
            }

            if (here.isEmpty() && standing.isEmpty()) return null;

            StringBuilder sb = new StringBuilder("<html>");
            for (TileReducer.TileEntry t : here)
            {
                if (sb.length() > 6) sb.append("<br>");
                sb.append(tileDescription(t));
            }
            if (!standing.isEmpty())
            {
                if (sb.length() > 6) sb.append("<br>");
                sb.append("Standing here: ").append(String.join(", ", standing));
            }
            sb.append("</html>");
            return sb.toString();
        }

        /** Plain-English gloss for one tile/modifier -- see buildLegend for the same reward
         * numbers' short-form color-swatch summary; this is the fuller per-tile version, including
         * its pathIndex where that's meaningful. The gloss itself comes from the served catalog
         * (see RunePartyPlugin#getTileTypeCatalog) -- only the "(pathIndex N)" suffix is added
         * here, since that's per-instance data the static catalog doesn't have. */
        private String tileDescription(TileReducer.TileEntry t)
        {
            ApiClient.TileTypeOut type = plugin.getTileTypeCatalog().get(t.tileType);
            if (type == null) return t.tileType;
            return t.pathIndex != null ? type.description + " (pathIndex " + t.pathIndex + ")" : type.description;
        }

        /** One dot per seated, joined PLAYER at their live board position (see
         * RunePartyPlugin#getPlayerPosition), fanned out a little when more than one shares a tile
         * so they don't just stack into an unreadable blob -- gathering at START is exactly when
         * this comes up most. The local player's dot gets a heavier white ring around it, same
         * "make 'you' unmistakable" idea as PlayerOverlay's on-turn glow, just always on here
         * rather than turn-gated -- there's no obvious "your turn" moment on a static map. */
        private void paintPlayers(Graphics2D g, int minX, int maxY, int plane)
        {
            String localRsn = plugin.getLocalRsn();

            Map<Point, List<RosterReducer.RosterEntry>> byCell = new HashMap<>();
            for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().snapshot())
            {
                if (entry.role != RunePartyRole.PLAYER || !entry.joined) continue;
                TileReducer.TileEntry tile = plugin.getTileReducer().tileAtIndex(plugin.getPlayerPosition(entry.rsn));
                if (tile == null || tile.point.getPlane() != plane) continue;
                byCell.computeIfAbsent(cellCenter(tile.point, minX, maxY), c -> new ArrayList<>()).add(entry);
            }

            g.setFont(FontManager.getRunescapeSmallFont());
            for (Map.Entry<Point, List<RosterReducer.RosterEntry>> cell : byCell.entrySet())
            {
                List<RosterReducer.RosterEntry> here = cell.getValue();
                for (int i = 0; i < here.size(); i++)
                {
                    RosterReducer.RosterEntry entry = here.get(i);
                    // Fans the group out symmetrically around the tile's true center, e.g. 3
                    // players land at offsets -SPREAD, 0, +SPREAD regardless of which index in
                    // `here` each happens to be.
                    int offset = here.size() == 1 ? 0 : Math.round((i - (here.size() - 1) / 2f) * PLAYER_DOT_SPREAD);
                    int cx = cell.getKey().x + offset;
                    int cy = cell.getKey().y;

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
                    g.setColor(Color.BLACK);
                    g.drawString(label, labelX + 1, labelY + 1);
                    g.setColor(PLAYER_LABEL);
                    g.drawString(label, labelX, labelY);
                }
            }
        }

        /** A simple isosceles triangle -- taller than it is wide, but not by much -- instead of a
         * plain filled circle, so it can't be mistaken for a player's dot at a glance (including,
         * ironically, a literally yellow-seated player's). */
        private void drawGnomeMarker(Graphics2D g, Point center)
        {
            int width = CELL_SIZE * 7 / 10;
            int height = width * 5 / 4;

            Polygon triangle = new Polygon();
            triangle.addPoint(center.x, center.y - height / 2);
            triangle.addPoint(center.x - width / 2, center.y + height / 2);
            triangle.addPoint(center.x + width / 2, center.y + height / 2);

            g.setColor(GOLDEN_GNOME_MARKER);
            g.fillPolygon(triangle);

            g.setStroke(new BasicStroke(1.3f));
            g.setColor(GOLDEN_GNOME_MARKER_BORDER);
            g.drawPolygon(triangle);
        }

        /** A diamond -- distinct from the Golden Gnome's triangle and every player's circular dot
         * -- for a Coin Trap modifier. */
        private void drawCoinTrapMarker(Graphics2D g, Point center)
        {
            int r = CELL_SIZE * 7 / 20;

            Polygon diamond = new Polygon();
            diamond.addPoint(center.x, center.y - r);
            diamond.addPoint(center.x + r, center.y);
            diamond.addPoint(center.x, center.y + r);
            diamond.addPoint(center.x - r, center.y);

            g.setColor(COIN_TRAP_MARKER);
            g.fillPolygon(diamond);

            g.setStroke(new BasicStroke(1.3f));
            g.setColor(COIN_TRAP_MARKER_BORDER);
            g.drawPolygon(diamond);
        }

        private Point cellTopLeft(WorldPoint wp, int minX, int maxY)
        {
            int x = PADDING + (wp.getX() - minX) * CELL_SIZE;
            int y = PADDING + (maxY - wp.getY()) * CELL_SIZE; // north-up: world Y grows north, screen Y grows down
            return new Point(x, y);
        }

        private Point cellCenter(WorldPoint wp, int minX, int maxY)
        {
            Point topLeft = cellTopLeft(wp, minX, maxY);
            return new Point(topLeft.x + CELL_SIZE / 2, topLeft.y + CELL_SIZE / 2);
        }
    }

    /** Fixed reference strip along the bottom of the dialog, always visible without scrolling --
     * one swatch/marker-color per tile type, so a color you can't place from memory (there are a
     * lot of them once Golden Gnome/Coin Trap markers are added on top of the plain fills) is one
     * glance away instead of a guess. Complements rather than duplicates MapPanel's own hover
     * tooltip (see getToolTipText) -- this is the quick "what does orange mean" reference, the
     * tooltip is the per-tile detail (exact reward numbers, a Golden Gnome/Coin Trap's own state,
     * who's standing there). */
    private JPanel buildLegend()
    {
        JPanel legend = new JPanel();
        legend.setLayout(new BoxLayout(legend, BoxLayout.Y_AXIS));
        legend.setBackground(BACKGROUND);
        legend.setBorder(BorderFactory.createEmptyBorder(6, PADDING, 8, PADDING));

        // Non-modifier types come from the served catalog (see RunePartyPlugin#getTileTypeCatalog),
        // in the server's own REGISTRY order -- the 2 modifiers stay hardcoded below since they
        // render with their own bespoke marker glyph/color, not a tile-outline color.
        for (ApiClient.TileTypeOut t : plugin.getTileTypeCatalog().values())
        {
            if (t.isModifier) continue;
            legend.add(legendRow(tileColor(t.key, null), t.description));
        }
        legend.add(legendRow(GOLDEN_GNOME_MARKER, "Golden Gnome (▲ marker -- buy for 20 coins)"));
        legend.add(legendRow(COIN_TRAP_MARKER, "Coin Trap (◆ marker -- steals up to 20 coins)"));

        JLabel hint = new JLabel("Hover a tile on the map above for details");
        hint.setForeground(EMPTY_TEXT);
        hint.setFont(FontManager.getRunescapeSmallFont());
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        legend.add(Box.createVerticalStrut(4));
        legend.add(hint);

        return legend;
    }

    private static JPanel legendRow(Color swatchColor, String label)
    {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
        row.setBackground(BACKGROUND);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);

        JPanel swatch = new JPanel();
        swatch.setPreferredSize(new Dimension(12, 12));
        swatch.setBackground(swatchColor);
        swatch.setBorder(BorderFactory.createLineBorder(Color.BLACK));
        row.add(swatch);

        JLabel text = new JLabel(label);
        text.setForeground(EMPTY_TEXT);
        text.setFont(FontManager.getRunescapeSmallFont());
        row.add(text);

        return row;
    }

    /** Same served catalog TileOverlay#defaultColorFor reads (see RunePartyPlugin#
     * getTileTypeCatalog, populated once at startup from GET /v1/tile-types) rather than its own
     * hardcoded table -- falls back to plain yellow for a type the catalog doesn't have. */
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

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
