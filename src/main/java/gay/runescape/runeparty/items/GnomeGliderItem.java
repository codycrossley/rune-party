package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Instantly relocates the holder's tracked board position to the course tile immediately before
 * wherever the Golden Gnome currently sits -- so that any roll of 1+ afterward puts the gnome
 * within reach (see purchase-golden-gnome server-side). Unlike HomeTeleportItem there's no reward
 * to pay out and so no confirm-arrival round trip: this is a single instant effect, done the
 * moment it's used. The "go stand on the tile" nudge is still covered for free by TileOverlay's
 * existing return-arrow, which renders off PLAYER_MOVED generically regardless of what caused it. */
public class GnomeGliderItem implements Item
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(GnomeGliderItem.class, "/gay/runescape/runeparty/item_icons/gnome-glider-icon.png", "Gnome Glider icon");

    private final String key;
    private final String displayName;

    public GnomeGliderItem(String key, String displayName)
    {
        this.key = key;
        this.displayName = displayName;
    }

    @Override
    public String getKey()
    {
        return key;
    }

    @Override
    public String getDisplayName()
    {
        return displayName;
    }

    @Override
    public String getEffectDescription(boolean isLocalPlayer)
    {
        return isLocalPlayer
            ? "Flies you to one space before the Golden Gnome."
            : "Flies them to one space before the Golden Gnome.";
    }

    @Override
    public boolean hasUseAnnouncement()
    {
        return true;
    }

    @Override
    public String getUseAnnouncementSubtitle(boolean isLocalPlayer)
    {
        return isLocalPlayer
            ? "Flies you to one space before the Golden Gnome."
            : "Flies them to one space before the Golden Gnome.";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
