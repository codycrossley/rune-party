package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Banks a +3 bonus for the holder's own next roll rather than resolving immediately, so unlike
 * PlaceholderItem this has no coin-popup feedback of its own -- see hasUseAnnouncement/
 * getUseAnnouncementSubtitle for the banner that stands in for that. */
public class EnergyPotionItem implements Item
{
    private static final int ROLL_BONUS = 3;
    private static final BufferedImage ICON = WheelEntry.loadIcon(EnergyPotionItem.class, "/gay/runescape/runeparty/item_icons/energy-potion-icon.png", "Energy Potion icon");

    private final String key;
    private final String displayName;

    public EnergyPotionItem(String key, String displayName)
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
            ? "Adds +" + ROLL_BONUS + " to your next roll."
            : "Adds +" + ROLL_BONUS + " to their next roll.";
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
            ? "This adds +" + ROLL_BONUS + " to your next roll."
            : "This adds +" + ROLL_BONUS + " to their next roll.";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
