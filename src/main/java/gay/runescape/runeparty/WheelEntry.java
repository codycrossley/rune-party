package gay.runescape.runeparty;

import java.awt.Graphics2D;

/** Anything that can appear as a segment on AnnouncementOverlay's spinner wheel -- a name shown
 * once the wheel settles, and an icon drawn in its own wedge. Extended by
 * gay.runescape.runeparty.minigames.Minigame and gay.runescape.runeparty.items.Item, the two
 * current wheel "menus" (which mini-game plays, which item you get) -- see
 * AnnouncementOverlay#drawWheel, the shared drawing routine that only needs to know about this
 * common shape, not either concrete type. */
public interface WheelEntry
{
    /** Must match the key the matching server-side entity registers itself under (see app.py's
     * items/minigames REGISTRY) -- how Minigames/Items (see KeyedRegistry) look an entry back up
     * for whatever key a server event payload carried. */
    String getKey();

    String getDisplayName();

    /** Draws this entry's icon centered at (x, y) at roughly {@code size} pixels across. Purely
     * programmatic (no image assets), same as the rest of this codebase's icon-ish drawing (the
     * retro die faces, the Golden Gnome map marker triangle). */
    void drawIcon(Graphics2D g, int x, int y, int size, float alpha);
}
