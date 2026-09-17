package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/** Coin Rush's own countdown card -- just the title and the round's remaining seconds, while a
 * Coin Rush round is active AND actually playable. Deliberately no per-player rows here: with up
 * to 8 players a live leaderboard gets busy fast, and scores are visible enough via the coin
 * pickups themselves, so this stays a plain timer. */
public class CoinRushScoreboardOverlay extends Overlay
{
    private static final int PADDING_X = 20;
    private static final int PADDING_Y = 12;
    private static final int TITLE_ROW_GAP = 6;
    private static final int CORNER_RADIUS = 14;

    private static final Color BACKGROUND = new Color(20, 14, 8, 200);
    private static final Color BORDER = new Color(230, 170, 60, 220);
    private static final Color TITLE_COLOR = new Color(255, 225, 180);
    private static final Color TIMER_SAFE_COLOR = new Color(255, 215, 0);
    private static final Color TIMER_URGENT_COLOR = new Color(235, 60, 60); // last few seconds
    private static final long URGENT_THRESHOLD_MS = 5000;

    private static final float TITLE_SIZE = 13f;
    private static final float TIMER_SIZE = 20f;

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

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font timerFont = RunePartyFonts.MARIO_PARTY.deriveFont(TIMER_SIZE);

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

        int contentWidth = Math.max(titleWidth, timerWidth);
        int width = PADDING_X * 2 + contentWidth;
        int height = PADDING_Y * 2 + titleFm.getHeight() + TITLE_ROW_GAP + timerFm.getHeight();

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

        return new Dimension(width, height);
    }

    private static void drawShadowedText(Graphics2D g, String text, int x, int y, Color color)
    {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 2, y + 2);
        g.setColor(color);
        g.drawString(text, x, y);
    }
}
