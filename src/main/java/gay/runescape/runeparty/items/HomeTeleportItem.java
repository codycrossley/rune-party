package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Relocates the holder's tracked board position straight to the Start tile the instant it's used,
 * but doesn't pay out the reward until they've actually walked their character over. The "go stand
 * on the tile" nudge is covered for free by TileOverlay's existing return-arrow, so this item needs
 * no bespoke indicator -- just the standard use-announcement banner. */
public class HomeTeleportItem implements Item
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(HomeTeleportItem.class, "/gay/runescape/runeparty/item_icons/tele-home-icon.png", "Home Teleport icon");

    private final String key;
    private final String displayName;

    public HomeTeleportItem(String key, String displayName)
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
            ? "Teleports you to the Start tile."
            : "Teleports them to the Start tile.";
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
            ? "Walk to the Start Tile to collect your reward."
            : "They'll need to walk to the Start Tile to collect their rewardß.";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
