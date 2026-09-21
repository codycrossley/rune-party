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

/** Shows the local player's own Rune Match pair count -- a titled panel with a big bold "X / 8"
 * running total -- while a Rune Match round is active AND actually playable. The second check
 * matters: without it this would render the instant the mini-game starts, spoiling the
 * "MINIGAME!" banner/selection wheel's own reveal, same reasoning FishingCatchOverlay's own doc
 * gives. Deliberately self-only, not a shared leaderboard: which pairs a player's found is entirely
 * client-local until the one final submission (see minigames/RuneMatchPresentation's own doc), so
 * no client ever learns another player's running count mid-round -- same "private race" shape
 * FishingCatchOverlay's own tally already established.
 * <p>
 * Drawn directly rather than via PanelComponent/LineComponent, same reasoning FishingCatchOverlay's
 * own doc gives -- a fixed one-line layout doesn't need that machinery. */
public class RuneMatchOverlay extends Overlay
{
    private static final int PADDING_X = 16;
    private static final int PADDING_Y = 10;
    private static final int TITLE_ROW_GAP = 4;
    private static final int CORNER_RADIUS = 12;

    private static final Color BACKGROUND = new Color(30, 16, 48, 195);
    private static final Color BORDER = new Color(150, 110, 220, 220);
    private static final Color TITLE_COLOR = new Color(220, 200, 255);
    private static final Color TEXT_COLOR = Color.WHITE;

    private static final float TITLE_SIZE = 13f;
    private static final float COUNT_SIZE = 22f;

    private static final String TITLE = "PAIRS FOUND";
    private static final int PAIR_COUNT = 8; // matches the server's own PAIR_COUNT (minigames/rune_match.py)

    private final RunePartyPlugin plugin;

    public RuneMatchOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isRuneMatchActive() || !plugin.isMinigamePlayable()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font countFont = FontManager.getRunescapeBoldFont().deriveFont(COUNT_SIZE);

        String countText = plugin.getRuneMatchSolvedPairCount() + " / " + PAIR_COUNT;

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

        int countY = titleY + titleFm.getDescent() + TITLE_ROW_GAP + countFm.getAscent();
        g.setFont(countFont);
        int countX = (width - countWidth) / 2;
        g.setColor(Color.BLACK);
        g.drawString(countText, countX + 1, countY + 1);
        g.setColor(TEXT_COLOR);
        g.drawString(countText, countX, countY);

        return new Dimension(width, height);
    }
}
