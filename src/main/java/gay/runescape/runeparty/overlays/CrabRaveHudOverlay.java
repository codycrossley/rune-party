package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Shows the local player's own live Crab Rave dance count -- a small titled panel, same
 * shape/positioning as DanceDanceRuneScapeHudOverlay/FishingCatchOverlay/SandwichRushHudOverlay.
 * Deliberately self-only, not a shared leaderboard: dance counts are entirely client-local until
 * the one final submission, so no client ever learns another player's running tally mid-round.
 * Stands in for a persistent on-screen reminder to dance -- the mini-game's own instructions
 * banner already covers that once, at MINIGAME_STARTED (see AnnouncementOverlay#
 * renderCrabRaveCountdown's own doc on why that isn't repeated here too). */
public class CrabRaveHudOverlay extends Overlay
{
    private static final int PADDING_X = 16;
    private static final int PADDING_Y = 10;
    private static final int TITLE_ROW_GAP = 4;
    private static final int CORNER_RADIUS = 12;

    private static final Color BACKGROUND = new Color(0, 0, 0, 103);
    // Hot pink, matching CrabRaveTile/TileOverlay's own CRAB_RAVE_ARENA_OUTLINE_COLOR theme.
    private static final Color BORDER = new Color(233, 30, 140, 255);
    private static final Color TITLE_COLOR = new Color(233, 30, 140, 255);
    private static final Color TEXT_COLOR = Color.WHITE;

    private static final float TITLE_SIZE = 20f;
    private static final float COUNT_SIZE = 32f;

    private static final String TITLE = "YOUR DANCES";

    private final RunePartyPlugin plugin;

    public CrabRaveHudOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isCrabRaveActive() || !plugin.isMinigamePlayable()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font countFont = FontManager.getRunescapeBoldFont().deriveFont(COUNT_SIZE);

        String countText = String.valueOf(plugin.getCrabRaveDanceCount());

        g.setFont(countFont);
        FontMetrics countFm = g.getFontMetrics();
        int countWidth = countFm.stringWidth(countText);

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(TITLE);

        int width = PADDING_X * 2 + Math.max(countWidth, titleWidth);
        int height = PADDING_Y * 2 + titleFm.getHeight() + TITLE_ROW_GAP + countFm.getHeight();

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(titleFont);
        g.setColor(TITLE_COLOR);
        int titleY = PADDING_Y + titleFm.getAscent();
        g.drawString(TITLE, (width - titleWidth) / 2, titleY);

        g.setFont(countFont);
        int countY = titleY + titleFm.getDescent() + TITLE_ROW_GAP + countFm.getAscent();
        int countX = (width - countWidth) / 2;
        g.setColor(Color.BLACK);
        g.drawString(countText, countX + 1, countY + 1);
        g.setColor(TEXT_COLOR);
        g.drawString(countText, countX, countY);

        return new Dimension(width, height);
    }
}
