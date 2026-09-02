package gay.runescape.runeparty;

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
import net.runelite.client.ui.overlay.OverlayPriority;

/** A big, standalone Coin Rush countdown, positioned just past the right edge of StatsOverlay's
 * panel. Uses OverlayPosition.DYNAMIC and draws itself at a fixed canvas offset rather than
 * RuneLite's corner-stacking layout, since two TOP_LEFT overlays would stack vertically instead of
 * side by side. That offset assumes StatsOverlay is still at its default position -- it won't
 * follow if a player drags StatsOverlay elsewhere. */
public class CoinRushTimerOverlay extends Overlay
{
    private static final Color NUMBER_COLOR = new Color(255, 215, 0);
    private static final Color URGENT_COLOR = new Color(235, 60, 60); // last few seconds
    private static final long URGENT_THRESHOLD_MS = 5000;
    private static final float NUMBER_SIZE = 48f;
    private static final float LABEL_SIZE = 14f;

    // Canvas offset this overlay draws itself at (see class doc). MARGIN matches RuneLite's own
    // default TOP_LEFT margin so the two read as vertically aligned.
    private static final int MARGIN_X = 4;
    private static final int MARGIN_Y = 4;
    private static final int GAP_X = 14;

    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;

    public CoinRushTimerOverlay(RunePartyConfig config, RunePartyPlugin plugin)
    {
        this.config = config;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showStatsOverlay()) return null;
        if (!plugin.isCoinRushActive() || !plugin.isMinigamePlayable()) return null;

        long remainingMs = plugin.getCoinRushEndsAt() - System.currentTimeMillis();
        long remainingSec = Math.max(0, (remainingMs + 999) / 1000); // never negative once the round ends

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Color color = remainingMs <= URGENT_THRESHOLD_MS ? URGENT_COLOR : NUMBER_COLOR;
        int x = StatsOverlay.PANEL_WIDTH + GAP_X + MARGIN_X;

        Font labelFont = FontManager.getRunescapeSmallFont().deriveFont(LABEL_SIZE);
        g.setFont(labelFont);
        FontMetrics labelFm = g.getFontMetrics();
        int labelY = MARGIN_Y + labelFm.getAscent();
        drawShadowedText(g, "TIME LEFT", x, labelY, Color.WHITE);

        Font numberFont = RunePartyFonts.MARIO_PARTY.deriveFont(NUMBER_SIZE);
        g.setFont(numberFont);
        FontMetrics numberFm = g.getFontMetrics();
        int numberY = labelY + numberFm.getAscent() + 2;
        String text = String.valueOf(remainingSec);
        drawShadowedText(g, text, x, numberY, color);

        int width = Math.max(labelFm.stringWidth("TIME LEFT"), numberFm.stringWidth(text));
        int height = numberY + numberFm.getDescent();
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
