package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Spent on another player rather than the user themselves (see requiresTarget). Stacks: a second
 * Tele Block on an already-blocked player queues a second skipped turn rather than overwriting the
 * first. */
public class TeleBlockItem implements Item
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(TeleBlockItem.class, "/gay/runescape/runeparty/item_icons/ice-barrage-icon.png", "Ice Barrage icon");

    private final String key;
    private final String displayName;

    public TeleBlockItem(String key, String displayName)
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
        return "Use it on another player to skip their next turn.";
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
