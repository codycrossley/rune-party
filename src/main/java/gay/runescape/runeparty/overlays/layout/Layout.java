package gay.runescape.runeparty.overlays.layout;

import java.awt.Dimension;
import java.awt.Graphics2D;

/** Top-level entry points that turn a built {@link Node} tree into pixels -- what a migrated
 * AnnouncementOverlay {@code render*} method calls once its tree is built, in place of its own
 * former {@code drawCenteredText}/{@code g.setFont} tail. Returns the occupied {@link Dimension}
 * since RuneLite's {@code Overlay#render} contract requires one, even though AnnouncementOverlay
 * itself (being {@code OverlayPosition.DYNAMIC}) doesn't currently do anything with it beyond
 * discarding it into its own {@code return null;}. */
public final class Layout
{
    private Layout()
    {
    }

    /** Measures {@code root} against {@code maxWidth}, then paints it horizontally centered on
     * {@code centerX} with {@code topY} handed straight through as {@code root}'s own {@code y} --
     * which means "top of the box" for a {@link Box} root, but "text baseline" for a bare {@link
     * Text} root (see that class's own doc on why). This is deliberate: it's what makes every
     * single-{@code Text}-root migration a zero-pixel-drift port of the banner's own pre-existing
     * {@code y} expression, while a multi-line {@link Box#column} root gets real box-model
     * stacking from {@code topY} down. */
    public static Dimension renderCentered(Graphics2D g, Node root, int centerX, int topY, int maxWidth, float alpha)
    {
        Dimension size = root.measure(g, maxWidth);
        root.paint(g, centerX - size.width / 2, topY, maxWidth, alpha);
        return size;
    }

    /** Left-aligned counterpart to {@link #renderCentered} -- for a banner that isn't centered.
     * Not used by the first migration batch (every tier-1 banner is centered); kept for parity and
     * named here for tier-2's chained-segment rows, which anchor a whole row's left edge once the
     * row's own overall width is known. */
    public static Dimension renderAt(Graphics2D g, Node root, int x, int topY, int maxWidth, float alpha)
    {
        Dimension size = root.measure(g, maxWidth);
        root.paint(g, x, topY, maxWidth, alpha);
        return size;
    }
}
