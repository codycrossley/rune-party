package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyColor;
import gay.runescape.runeparty.RunePartyFonts;
import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Coin Rush's own live scoreboard -- one row per seated player, this round's own coin tally,
 * highest first, plus a countdown -- while a Coin Rush round is active AND actually playable.
 * Replaces StatsOverlay's own former Coin Rush takeover (that persistent roster HUD is hidden
 * outright for every mini-game now, see StatsOverlay#render): this owns its own dedicated card
 * instead, same "one self-contained overlay per mini-game" shape every newer mini-game here
 * already follows (TurfWarsScoreOverlay, HotPotatoOverlay, SandwichRushHudOverlay). Also folds in
 * what used to be the standalone CoinRushTimerOverlay's own countdown -- one card, not two
 * adjacent ones, matching how TurfWarsScoreOverlay/HotPotatoOverlay both bundle their own score
 * and timer together already rather than splitting them across overlays. */
public class CoinRushScoreboardOverlay extends Overlay
{
    private static final int PADDING_X = 20;
    private static final int PADDING_Y = 12;
    private static final int TITLE_ROW_GAP = 6;
    private static final int ROW_HEIGHT = 20;
    private static final int ROW_NAME_SCORE_GAP = 24; // minimum gap between a row's own name and score text
    private static final int CORNER_RADIUS = 14;

    private static final Color BACKGROUND = new Color(20, 14, 8, 200);
    private static final Color BORDER = new Color(230, 170, 60, 220);
    private static final Color TITLE_COLOR = new Color(255, 225, 180);
    private static final Color SCORE_COLOR = Color.WHITE;
    private static final Color NAME_FALLBACK_COLOR = Color.LIGHT_GRAY;
    private static final Color TIMER_SAFE_COLOR = new Color(255, 215, 0);
    private static final Color TIMER_URGENT_COLOR = new Color(235, 60, 60); // last few seconds
    private static final long URGENT_THRESHOLD_MS = 5000;

    private static final float TITLE_SIZE = 13f;
    private static final float TIMER_SIZE = 20f;
    private static final float ROW_SIZE = 14f;

    private static final String TITLE = "COIN RUSH";

    private final RunePartyPlugin plugin;

    public CoinRushScoreboardOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isCoinRushActive() || !plugin.isMinigamePlayable()) return null;

        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        if (players.isEmpty()) return null;

        Map<String, Integer> scores = plugin.getCoinRushScores();
        // Sorted by this round's own score descending (ties broken by turn order) -- unlike a
        // persistent roster HUD's deliberately-stable sort, a leaderboard is exactly the one place
        // re-sorting by whoever's currently ahead is the whole point.
        players.sort(Comparator
            .comparing((RosterReducer.RosterEntry e) -> scores.getOrDefault(e.rsn.toLowerCase(Locale.ROOT), 0))
            .reversed()
            .thenComparing(e -> e.number));

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font timerFont = RunePartyFonts.MARIO_PARTY.deriveFont(TIMER_SIZE);
        Font rowFont = FontManager.getRunescapeBoldFont().deriveFont(ROW_SIZE);

        long remainingMs = plugin.getCoinRushEndsAt() - System.currentTimeMillis();
        long remainingSec = Math.max(0, (remainingMs + 999) / 1000);
        String timerText = String.valueOf(remainingSec);
        Color timerColor = remainingMs <= URGENT_THRESHOLD_MS ? TIMER_URGENT_COLOR : TIMER_SAFE_COLOR;

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(TITLE);

        g.setFont(timerFont);
        FontMetrics timerFm = g.getFontMetrics();
        int timerWidth = timerFm.stringWidth(timerText);

        g.setFont(rowFont);
        FontMetrics rowFm = g.getFontMetrics();
        int rowsWidth = 0;
        for (RosterReducer.RosterEntry entry : players)
        {
            String scoreText = scoreText(scores, entry);
            rowsWidth = Math.max(rowsWidth, rowFm.stringWidth(entry.rsn) + ROW_NAME_SCORE_GAP + rowFm.stringWidth(scoreText));
        }

        int contentWidth = Math.max(Math.max(titleWidth, timerWidth), rowsWidth);
        int width = PADDING_X * 2 + contentWidth;
        int height = PADDING_Y * 2 + titleFm.getHeight() + TITLE_ROW_GAP + timerFm.getHeight() + TITLE_ROW_GAP + players.size() * ROW_HEIGHT;

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(titleFont);
        g.setColor(TITLE_COLOR);
        int titleY = PADDING_Y + titleFm.getAscent();
        g.drawString(TITLE, (width - titleWidth) / 2, titleY);

        g.setFont(timerFont);
        int timerY = titleY + titleFm.getDescent() + TITLE_ROW_GAP + timerFm.getAscent();
        drawShadowedText(g, timerText, (width - timerWidth) / 2, timerY, timerColor);

        int rowTop = timerY + timerFm.getDescent() + TITLE_ROW_GAP;
        g.setFont(rowFont);
        for (int i = 0; i < players.size(); i++)
        {
            RosterReducer.RosterEntry entry = players.get(i);
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : NAME_FALLBACK_COLOR;
            String scoreText = scoreText(scores, entry);

            int rowY = rowTop + i * ROW_HEIGHT + rowFm.getAscent();
            drawShadowedText(g, entry.rsn, PADDING_X, rowY, nameColor);
            drawShadowedText(g, scoreText, width - PADDING_X - rowFm.stringWidth(scoreText), rowY, SCORE_COLOR);
        }

        return new Dimension(width, height);
    }

    private static String scoreText(Map<String, Integer> scores, RosterReducer.RosterEntry entry)
    {
        int score = scores.getOrDefault(entry.rsn.toLowerCase(Locale.ROOT), 0);
        return score + (score == 1 ? " coin" : " coins");
    }

    private static void drawShadowedText(Graphics2D g, String text, int x, int y, Color color)
    {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 2, y + 2);
        g.setColor(color);
        g.drawString(text, x, y);
    }
}
