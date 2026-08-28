package gay.runescape.runeparty.items;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Client-side counterpart to the server's TeleBlockItem (see items/tele_block.py) -- spent on
 * another player rather than the user themselves, see requiresTarget/RunePartyPlugin#
 * beginItemTargeting. Stacks: a second Tele Block on an already-blocked player queues a second
 * skipped turn rather than overwriting the first -- see TURN_SKIPPED handling in
 * RunePartyPlugin/RosterReducer. Key must match the server's own TeleBlockItem registration
 * exactly, see that file. */
@Slf4j
public class TeleBlockItem implements Item
{
    private static final BufferedImage ICON = loadIcon();

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
    public boolean requiresTarget()
    {
        return true;
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        if (ICON == null) return;

        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g.drawImage(ICON, x - size / 2, y - size / 2, size, size, null);
        g.setComposite(original);
    }

    private static BufferedImage loadIcon()
    {
        try (InputStream is = TeleBlockItem.class.getResourceAsStream("/gay/runescape/runeparty/item_icons/tele-block-icon.png"))
        {
            if (is == null) throw new IOException("tele-block-icon.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Tele Block icon", e);
            return null;
        }
    }
}
