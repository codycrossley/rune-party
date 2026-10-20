package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Placed on a tile rather than resolved instantly (see requiresPlacement). The in-world model
 * once placed is rendered separately by TileOverlay -- drawIcon below is only the wheel/inventory
 * glyph. */
public class CoinTrapItem implements Item
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(CoinTrapItem.class, "/gay/runescape/runeparty/item_icons/coin-trap-icon.png", "Coin Trap icon");

    private final String key;
    private final String displayName;

    public CoinTrapItem(String key, String displayName)
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
        return "Place it on a tile to steal coins from someone who lands on it.";
    }

    @Override
    public boolean requiresPlacement()
    {
        return true;
    }

    @Override
    public boolean hasUseAnnouncement()
    {
        return true;
    }

    @Override
    public String getUseAnnounceVerb()
    {
        return "placed";
    }

    @Override
    public String getUseAnnouncementSubtitle(boolean isLocalPlayer)
    {
        return "It steals coins from anyone but " + (isLocalPlayer ? "you" : "them") + " who lands on it.";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
