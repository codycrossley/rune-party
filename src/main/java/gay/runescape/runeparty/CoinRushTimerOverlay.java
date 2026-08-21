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

/** A big, standalone Coin Rush countdown -- deliberately its own overlay rather than a line inside
 * StatsOverlay's own Coin Rush scoreboard (see that class's own doc), so a round's remaining time
 * reads as a genuinely prominent, Mario-Party-style on-screen clock instead of one small line
 * buried in a stats panel. Positioned just past the right edge of StatsOverlay's own panel (see
 * StatsOverlay#PANEL_WIDTH) rather than through RuneLite's own corner-stacking layout: two
 * TOP_LEFT overlays would stack vertically, one above the other, not side by side, which isn't
 * what "next to it" means here -- so this uses OverlayPosition.DYNAMIC and draws itself directly
 * at a fixed canvas offset instead, the same raw-canvas-coordinate approach AnnouncementOverlay/
 * PlayerOverlay already use for everything they draw. That offset assumes StatsOverlay is sitting
 * at its own default top-left corner position -- if a player ever drags StatsOverlay elsewhere via
 * RuneLite's overlay editor, this one won't follow it, the same limitation any fixed-offset layout
 * has without a live cross-overlay bounds query. */
public class CoinRushTimerOverlay extends Overlay
{
    private static final Color NUMBER_COLOR = new Color(255, 215, 0); // matches AnnouncementOverlay's own RAINBOW_YELLOW
    private static final Color URGENT_COLOR = new Color(235, 60, 60); // last few seconds -- reads as "hurry up"
    private static final long URGENT_THRESHOLD_MS = 5000;
    private static final float NUMBER_SIZE = 48f;
    private static final float LABEL_SIZE = 14f;

    // Canvas offset this overlay draws itself at -- see this class's own doc on why it's a fixed
    // constant rather than a query against StatsOverlay's actual rendered position. Matches the
    // small margin RuneLite's own OverlayRenderer applies to a TOP_LEFT-anchored overlay, so the
    // two read as vertically aligned; GAP_X is empirically chosen, just enough that this doesn't
    // look glued to StatsOverlay's own right edge.
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
        long remainingSec = Math.max(0, (remainingMs + 999) / 1000); // 0 (not negative) once the round's own clock runs out -- the server's MINIGAME_ENDED is what actually ends it, this is purely the display

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
