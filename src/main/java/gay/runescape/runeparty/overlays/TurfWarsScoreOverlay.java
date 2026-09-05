package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Turf Wars' own live scoreboard -- one colored swatch+count per team currently in play, plus a
 * small "time left" countdown, while a Turf Wars round is active AND actually playable -- without
 * the second check this would render the instant the mini-game starts, spoiling the "MINIGAME!"
 * banner/selection wheel's own reveal. Front-and-center (OverlayPosition.TOP_CENTER) with big bold
 * numbers, since this game needs the score to read at a glance while running across a 64-tile
 * grid. The panel's own border is tinted to the local player's own team color so which side you're
 * on is obvious without reading any number.
 * <p>
 * Unlike a fixed 2-team layout, the number of entries here varies: an even seated-PLAYER count
 * gives exactly 2 (the shared TEAM_A_COLOR/TEAM_B_COLOR), an odd count gives up to 7 (one per
 * solo player, each their own seat color). Entries are sorted by tile count descending and wrap
 * onto additional rows past MAX_PER_ROW, rather than assuming exactly two columns fit.
 * <p>
 * There's no dedicated score event here at all -- a tile claim is just an ordinary tiles_marked
 * update, so every entry is tallied fresh every frame straight from TileReducer's own
 * already-broadcast board. No timer of its own. */
public class TurfWarsScoreOverlay extends Overlay
{
    private static final int PADDING_X = 16;
    private static final int PADDING_Y = 10;
    private static final int ENTRY_GAP = 22;
    private static final int SWATCH_TEXT_GAP = 6;
    private static final int ROW_GAP = 4;
    private static final int ENTRY_ROW_GAP = 6;
    private static final int CORNER_RADIUS = 12;
    private static final int SWATCH_SIZE = 16;
    private static final int MAX_PER_ROW = 4; // wraps to another row past this many distinct colors

    private static final Color BACKGROUND = new Color(10, 10, 16, 195);
    private static final Color NEUTRAL_BORDER = new Color(150, 150, 160, 220);
    private static final Color COUNTDOWN_COLOR = new Color(210, 210, 220);

    private static final float TITLE_SIZE = 13f;
    private static final float SCORE_SIZE = 22f;
    private static final float COUNTDOWN_SIZE = 13f;

    private static final String TITLE = "TURF WARS";

    private final RunePartyPlugin plugin;

    public TurfWarsScoreOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isTurfWarsActive() || !plugin.isMinigamePlayable()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font scoreFont = FontManager.getRunescapeBoldFont().deriveFont(SCORE_SIZE);
        Font countdownFont = FontManager.getRunescapeSmallFont().deriveFont(COUNTDOWN_SIZE);

        List<Map.Entry<String, Integer>> entries = sortedEntries();
        String countdownText = countdownText();

        g.setFont(scoreFont);
        FontMetrics scoreFm = g.getFontMetrics();

        // Break entries into rows of at most MAX_PER_ROW, each row's own width the sum of its
        // entries' swatch+gap+text widths plus ENTRY_GAP between them.
        List<List<Map.Entry<String, Integer>>> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i += MAX_PER_ROW)
        {
            rows.add(entries.subList(i, Math.min(i + MAX_PER_ROW, entries.size())));
        }
        List<Integer> rowWidths = new ArrayList<>();
        int widestRow = 0;
        for (List<Map.Entry<String, Integer>> row : rows)
        {
            int rowWidth = 0;
            for (int i = 0; i < row.size(); i++)
            {
                if (i > 0) rowWidth += ENTRY_GAP;
                rowWidth += SWATCH_SIZE + SWATCH_TEXT_GAP + scoreFm.stringWidth(String.valueOf(row.get(i).getValue()));
            }
            rowWidths.add(rowWidth);
            widestRow = Math.max(widestRow, rowWidth);
        }

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(TITLE);

        g.setFont(countdownFont);
        FontMetrics countdownFm = g.getFontMetrics();
        int countdownWidth = countdownFm.stringWidth(countdownText);

        int width = PADDING_X * 2 + Math.max(widestRow, Math.max(titleWidth, countdownWidth));
        int rowHeight = Math.max(SWATCH_SIZE, scoreFm.getHeight());
        int scoresHeight = rows.isEmpty() ? 0 : rows.size() * rowHeight + (rows.size() - 1) * ENTRY_ROW_GAP;
        int height = PADDING_Y * 2 + titleFm.getHeight() + ROW_GAP + scoresHeight + ROW_GAP + countdownFm.getHeight();

        Color border = plugin.getPlayerTeamColor(plugin.getLocalRsn());
        if (border == null) border = NEUTRAL_BORDER;

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(border);
        g.drawRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(titleFont);
        g.setColor(COUNTDOWN_COLOR);
        int titleY = PADDING_Y + titleFm.getAscent();
        g.drawString(TITLE, (width - titleWidth) / 2, titleY);

        int rowTop = titleY + titleFm.getDescent() + ROW_GAP;
        for (int r = 0; r < rows.size(); r++)
        {
            int x = (width - rowWidths.get(r)) / 2;
            for (Map.Entry<String, Integer> entry : rows.get(r))
            {
                x = drawEntry(g, entry.getKey(), entry.getValue(), x, rowTop, rowHeight, scoreFont, scoreFm) + ENTRY_GAP;
            }
            rowTop += rowHeight + ENTRY_ROW_GAP;
        }

        int countdownY = rowTop - ENTRY_ROW_GAP + ROW_GAP + countdownFm.getAscent();
        g.setFont(countdownFont);
        g.setColor(COUNTDOWN_COLOR);
        g.drawString(countdownText, (width - countdownWidth) / 2, countdownY);

        return new Dimension(width, height);
    }

    /** getTurfWarsTileCounts()'s own map, sorted by tile count descending -- so the current leader
     * (or leaders, tied) always reads first/leftmost. Ties broken by hex string, purely so the
     * ordering is stable frame to frame rather than flickering between equal-valued entries in
     * whatever arbitrary order the underlying HashMap happens to iterate. */
    private List<Map.Entry<String, Integer>> sortedEntries()
    {
        List<Map.Entry<String, Integer>> entries = new ArrayList<>(plugin.getTurfWarsTileCounts().entrySet());
        entries.sort((a, b) ->
        {
            int byCount = b.getValue() - a.getValue();
            return byCount != 0 ? byCount : a.getKey().compareTo(b.getKey());
        });
        return entries;
    }

    /** Draws one swatch+count pair starting at {@code x}, returns the x coordinate immediately
     * past its text (i.e. where the next entry, plus ENTRY_GAP, should start). */
    private int drawEntry(Graphics2D g, String colorHex, int count, int x, int rowTop, int rowHeight, Font font, FontMetrics fm)
    {
        Color color;
        try { color = Color.decode(colorHex); }
        catch (NumberFormatException e) { color = Color.GRAY; }

        int swatchY = rowTop + (rowHeight - SWATCH_SIZE) / 2;
        g.setColor(color);
        g.fillOval(x, swatchY, SWATCH_SIZE, SWATCH_SIZE);
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(1f));
        g.drawOval(x, swatchY, SWATCH_SIZE, SWATCH_SIZE);

        String text = String.valueOf(count);
        int textX = x + SWATCH_SIZE + SWATCH_TEXT_GAP;
        int textY = rowTop + rowHeight / 2 + fm.getAscent() / 2 - 2;
        g.setFont(font);
        g.setColor(Color.BLACK);
        g.drawString(text, textX + 1, textY + 1);
        g.setColor(color);
        g.drawString(text, textX, textY);

        return textX + fm.stringWidth(text);
    }

    /** "Time left: Ns", rounding up to the nearest whole second so this never shows "0s" for a
     * moment while the round is still technically active. Falls back to a blank countdown (not
     * "0s") if getTurfWarsEndsAt() hasn't been stamped yet. */
    private String countdownText()
    {
        long endsAt = plugin.getTurfWarsEndsAt();
        if (endsAt == 0) return "";
        long remainingMs = endsAt - System.currentTimeMillis();
        long remainingSec = Math.max(0, (remainingMs + 999) / 1000);
        return "Time left: " + remainingSec + "s";
    }
}
