package gay.runescape.runeparty;

import java.awt.Graphics2D;

/** Anything that can appear as a segment on AnnouncementOverlay's spinner wheel -- a name shown
 * once the wheel settles, and an icon drawn in its own wedge. Extended by Minigame and Item, the
 * two current wheel "menus". */
public interface WheelEntry
{
    /** Must match the key the matching server-side entity registers itself under. */
    String getKey();

    String getDisplayName();

    /** Draws this entry's icon centered at (x, y) at roughly {@code size} pixels across. Purely
     * programmatic, no image assets. */
    void drawIcon(Graphics2D g, int x, int y, int size, float alpha);
}
