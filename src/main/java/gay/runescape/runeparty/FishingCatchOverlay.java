package gay.runescape.runeparty;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/** Shows the local player's own Fishing Contest catch tally -- a titled panel with a shrimp/
 * anchovy icon and a big bold running count each, side by side -- while a Fishing Contest round is
 * active AND actually playable. The second check matters: without it this would render the instant
 * the mini-game starts, spoiling the "MINIGAME!" banner/selection wheel's own reveal. Every
 * completed Headbang emote near the Pond rolls one catch -- there's no separate "started fishing"
 * state to gate on beyond that.
 * <p>
 * Deliberately front-and-center (OverlayPosition.TOP_CENTER) with large icons/numbers -- a live
 * count the player is meant to actually watch mid-round belongs somewhere obvious. Still
 * deliberately self-only, not a shared leaderboard: catches are entirely client-local until the
 * one final submission, so no client ever learns another player's running count mid-round.
 * <p>
 * Drawn directly rather than via PanelComponent/LineComponent (the convention StatsOverlay uses)
 * -- those stack one full-width component per row, with no built-in way to put an icon and text
 * side by side on the same row, so this overlay just owns its own small fixed layout instead. */
public class FishingCatchOverlay extends Overlay
{
    private static final int ICON_SIZE = 34;
    private static final int ICON_TEXT_GAP = 6;
    private static final int COUNT_GAP = 18;
    private static final int PADDING_X = 16;
    private static final int PADDING_Y = 10;
    private static final int TITLE_ROW_GAP = 4;
    private static final int CORNER_RADIUS = 12;

    private static final Color BACKGROUND = new Color(8, 28, 52, 195);
    private static final Color BORDER = new Color(90, 170, 230, 220);
    private static final Color TITLE_COLOR = new Color(190, 220, 255);
    private static final Color TEXT_COLOR = Color.WHITE;

    private static final float TITLE_SIZE = 13f;
    private static final float COUNT_SIZE = 22f;

    private static final String TITLE = "YOUR CATCH";

    private final RunePartyPlugin plugin;
    private final BufferedImage shrimpIcon;
    private final BufferedImage anchovyIcon;

    public FishingCatchOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.shrimpIcon = ImageUtil.loadImageResource(getClass(), "minigame_resources/raw_shrimps.png");
        this.anchovyIcon = ImageUtil.loadImageResource(getClass(), "minigame_resources/raw_anchovies.png");

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isFishingContestActive() || !plugin.isMinigamePlayable()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font countFont = FontManager.getRunescapeBoldFont().deriveFont(COUNT_SIZE);

        String shrimpText = String.valueOf(plugin.getShrimpCount());
        String anchovyText = String.valueOf(plugin.getAnchovyCount());

        g.setFont(countFont);
        FontMetrics countFm = g.getFontMetrics();
        int rowWidth = ICON_SIZE + ICON_TEXT_GAP + countFm.stringWidth(shrimpText)
            + COUNT_GAP
            + ICON_SIZE + ICON_TEXT_GAP + countFm.stringWidth(anchovyText);
        int rowHeight = Math.max(ICON_SIZE, countFm.getHeight());

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(TITLE);

        int width = PADDING_X * 2 + Math.max(rowWidth, titleWidth);
        int height = PADDING_Y * 2 + titleFm.getHeight() + TITLE_ROW_GAP + rowHeight;

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(titleFont);
        g.setColor(TITLE_COLOR);
        int titleY = PADDING_Y + titleFm.getAscent();
        g.drawString(TITLE, (width - titleWidth) / 2, titleY);

        int rowTop = titleY + titleFm.getDescent() + TITLE_ROW_GAP;
        int x = (width - rowWidth) / 2;
        x = drawCount(g, shrimpIcon, shrimpText, x, rowTop, rowHeight, countFont, countFm);
        drawCount(g, anchovyIcon, anchovyText, x + COUNT_GAP, rowTop, rowHeight, countFont, countFm);

        return new Dimension(width, height);
    }

    /** Draws one icon+count pair starting at {@code x}, returns the x coordinate immediately past
     * its text (i.e. where the next pair, plus its own gap, should start). Big bold shadowed text
     * keeps the count legible over whatever's on screen behind it. icon may be null (a
     * missing/failed-to-load resource) -- skips drawing it rather than throwing. */
    private int drawCount(Graphics2D g, BufferedImage icon, String text, int x, int rowTop, int rowHeight, Font font, FontMetrics fm)
    {
        if (icon != null)
        {
            int iconY = rowTop + (rowHeight - ICON_SIZE) / 2;
            g.drawImage(icon, x, iconY, ICON_SIZE, ICON_SIZE, null);
        }

        int textX = x + ICON_SIZE + ICON_TEXT_GAP;
        int textY = rowTop + rowHeight / 2 + fm.getAscent() / 2 - 2;
        g.setFont(font);
        g.setColor(Color.BLACK);
        g.drawString(text, textX + 1, textY + 1);
        g.setColor(TEXT_COLOR);
        g.drawString(text, textX, textY);

        return textX + fm.stringWidth(text);
    }
}
