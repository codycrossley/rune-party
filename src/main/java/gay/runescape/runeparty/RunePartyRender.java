package gay.runescape.runeparty;

import java.awt.Color;
import java.awt.Graphics2D;

/** Small rendering helpers every overlay/dialog previously kept its own copy of (see
 * ARCHITECTURE_REVIEW.md's C3) -- {@code withAlpha} existed 5 times, and the "black shadow offset
 * one pixel, then the real color on top" idiom 3 of those same times. Two copies of a 3-line color
 * helper would be a fine judgment call (see RunePartyMapDialog's own small independent copies of
 * this kind of thing elsewhere); five was worth collapsing. */
final class RunePartyRender
{
    private RunePartyRender()
    {
    }

    static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** Clamped to [0, 255] before handing java.awt.Color a component value -- one of the five
     * previous copies of this (ConfettiOverlay's) didn't clamp, which would have thrown if a
     * caller's own alpha arithmetic ever drifted fractionally outside [0f, 1f]. */
    static Color withAlpha(Color c, float alpha)
    {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Draws {@code text} once in black at (x+1, y+1), then again in {@code color} at (x, y) --
     * the shadow-then-draw idiom PlayerOverlay's coin/Golden-Gnome popups and TileOverlay's
     * return-arrow label all independently repeated, each already scaling both draws to the same
     * {@code alpha}. Not a fit for every "draw text with a shadow" site in this codebase --
     * CoinRushTimerOverlay's own drawShadowedText is a permanently-opaque HUD label with a
     * different (+2, +2) offset, and AnnouncementOverlay's drawLeftAlignedText dims its shadow to
     * 0.7x the main alpha and returns the x position past the text for layout chaining -- both
     * real behavioral differences, not just duplicate names for this same thing, so both stay
     * separate rather than being forced through this one signature. */
    static void drawShadowed(Graphics2D g, String text, int x, int y, Color color, int alpha)
    {
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.drawString(text, x + 1, y + 1);
        g.setColor(withAlpha(color, alpha));
        g.drawString(text, x, y);
    }
}
