package gay.runescape.runeparty;

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

/** Shows the local player's own running Click, Click, Click unique-tile-click count -- a big,
 * unmissable number front and center, while a round is active AND actually playable (same
 * "playable, not just started" gate FishingCatchOverlay uses, so this doesn't spoil the
 * "MINIGAME!" banner/selection wheel's own reveal). Every left-click on bare ground adds at most
 * one to this (see RunePartyPlugin#registerClickClickClickTile) -- there's no separate "started
 * clicking" state beyond the round itself being playable.
 * <p>
 * Deliberately front-and-center (OverlayPosition.TOP_CENTER, a genuinely large number) -- unlike
 * Fishing Contest's own two-icon tally, this mini-game's entire point IS the number, so it gets
 * the biggest treatment any per-round HUD in this codebase uses. Still deliberately self-only, not
 * a shared leaderboard: clicks are entirely client-local until the one final submission, so no
 * client ever learns another player's running count mid-round. */
public class ClickClickClickOverlay extends Overlay
{
    private static final int PADDING_X = 24;
    private static final int PADDING_Y = 14;
    private static final int LABEL_GAP = 2;
    private static final int CORNER_RADIUS = 14;

    private static final Color BACKGROUND = new Color(0, 0, 0, 84);
    private static final Color BORDER = new Color(255, 215, 0, 255);
    private static final Color LABEL_COLOR = new Color(255, 215, 0);
    // Fallback only -- render() prefers the local player's own seat color (see RunePartyColor),
    // same "a player's own name/number renders in their own seat color" convention every roster
    // listing in this codebase already follows (see e.g. AnnouncementOverlay#drawPlayerRows).
    // Falls back to this plain gold whenever a seat color can't be resolved (no roster entry yet,
    // e.g. briefly during catch-up).
    private static final Color NUMBER_COLOR_FALLBACK = new Color(255, 215, 0);

    private static final float LABEL_SIZE = 16f;
    private static final float NUMBER_SIZE = 64f;

    private static final String LABEL = "UNIQUE TILES CLICKED";

    private final RunePartyPlugin plugin;

    public ClickClickClickOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isClickClickClickActive() || !plugin.isMinigamePlayable()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font labelFont = FontManager.getRunescapeSmallFont().deriveFont(LABEL_SIZE);
        Font numberFont = RunePartyFonts.MARIO_PARTY.deriveFont(NUMBER_SIZE);
        String numberText = String.valueOf(plugin.getClickClickClickUniqueTileCount());

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(plugin.localRsn()));
        Color numberColor = seatColor != null ? seatColor.awt : NUMBER_COLOR_FALLBACK;

        g.setFont(labelFont);
        FontMetrics labelFm = g.getFontMetrics();
        int labelWidth = labelFm.stringWidth(LABEL);

        g.setFont(numberFont);
        FontMetrics numberFm = g.getFontMetrics();
        int numberWidth = numberFm.stringWidth(numberText);

        int width = PADDING_X * 2 + Math.max(labelWidth, numberWidth);
        int height = PADDING_Y * 2 + labelFm.getHeight() + LABEL_GAP + numberFm.getHeight();

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(labelFont);
        int labelY = PADDING_Y + labelFm.getAscent();
        drawShadowedText(g, LABEL, (width - labelWidth) / 2, labelY, LABEL_COLOR);

        g.setFont(numberFont);
        int numberY = labelY + labelFm.getDescent() + LABEL_GAP + numberFm.getAscent();
        drawShadowedText(g, numberText, (width - numberWidth) / 2, numberY, numberColor);

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
