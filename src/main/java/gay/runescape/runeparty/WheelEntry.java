package gay.runescape.runeparty;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Anything that can appear as a segment on AnnouncementOverlay's spinner wheel -- a name shown
 * once the wheel settles, and an icon drawn in its own wedge. Extended by Minigame and Item, the
 * two current wheel "menus". */
public interface WheelEntry
{
    /** Must match the key the matching server-side entity registers itself under. */
    String getKey();

    String getDisplayName();

    /** Draws this entry's icon centered at (x, y) at roughly {@code size} pixels across. Most
     * implementations load a PNG and delegate straight to {@link #drawIconImage} below; a few draw
     * programmatically instead. */
    void drawIcon(Graphics2D g, int x, int y, int size, float alpha);

    /** Shared icon-image draw: fits {@code icon} within a {@code size}x{@code size} box centered at
     * (x, y), preserving its own real aspect ratio, rather than stretching it to fill a square --
     * a non-square source image (most of this codebase's own wheel icons are not perfectly square)
     * would otherwise visibly squish/stretch. No-op if {@code icon} is null (still loading, or its
     * own resource failed to load -- see each implementer's own {@code loadIcon}). */
    default void drawIconImage(Graphics2D g, BufferedImage icon, int x, int y, int size, float alpha)
    {
        if (icon == null) return;

        float scale = Math.min(size / (float) icon.getWidth(), size / (float) icon.getHeight());
        int w = Math.round(icon.getWidth() * scale);
        int h = Math.round(icon.getHeight() * scale);

        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g.drawImage(icon, x - w / 2, y - h / 2, w, h, null);
        g.setComposite(original);
    }
}
