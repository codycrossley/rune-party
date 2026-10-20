package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Spent on another player rather than the user themselves (see requiresTarget). Sends the target
 * backward BACKWARD_OFFSET tiles along the course path -- see the server's own items/tele_other.py
 * for the exact mechanic and why it fires a bare PLAYER_MOVED rather than routing through a real
 * tile landing. */
public class TeleOtherItem implements Item
{
    /** Matches the server's own items/tele_other.py#BACKWARD_OFFSET -- purely cosmetic here (the
     * server computes the real destination itself), just so this item's own description reads the
     * true number rather than a guess. */
    private static final int BACKWARD_OFFSET = 5;

    private static final BufferedImage ICON = WheelEntry.loadIcon(TeleOtherItem.class, "/gay/runescape/runeparty/item_icons/tele-other-icon.png", "Tele Other icon");

    private final String key;
    private final String displayName;

    public TeleOtherItem(String key, String displayName)
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
        return "Use it on another player to send them " + BACKWARD_OFFSET + " tiles backward.";
    }

    @Override
    public boolean requiresTarget()
    {
        return true;
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
