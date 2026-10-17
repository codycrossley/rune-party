package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** Shows the local player's own live Dance, Dance, RuneScape score -- a small titled panel, same
 * shape/positioning as FishingCatchOverlay/SandwichRushHudOverlay. Deliberately self-only, not a
 * shared leaderboard: scores are entirely client-local until the one final submission, so no
 * client ever learns another player's running tally mid-round. */
public class DanceDanceRuneScapeHudOverlay extends Overlay
{
    private static final int PADDING_X = 16;
    private static final int PADDING_Y = 10;
    private static final int TITLE_ROW_GAP = 4;
    private static final int CORNER_RADIUS = 12;

    private static final Color BACKGROUND = new Color(0, 0, 0, 103);
    private static final Color BORDER = new Color(255, 215, 0, 255);
    private static final Color TITLE_COLOR = new Color(255, 215, 0, 255);
    private static final Color TEXT_COLOR = Color.WHITE;

    private static final float TITLE_SIZE = 20f;
    private static final float SCORE_SIZE = 32f;

    private static final String TITLE = "YOUR SCORE";

    private final RunePartyPlugin plugin;

    public DanceDanceRuneScapeHudOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isDanceDanceRuneScapeActive() || !plugin.isMinigamePlayable()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        return RunePartyRender.drawTitledStatPanel(g, PADDING_X, PADDING_Y, TITLE_ROW_GAP, CORNER_RADIUS,
            BACKGROUND, BORDER, FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE), TITLE, TITLE_COLOR,
            FontManager.getRunescapeBoldFont().deriveFont(SCORE_SIZE), String.valueOf(plugin.getDanceDanceRuneScapeScore()), TEXT_COLOR);
    }
}
