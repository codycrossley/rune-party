package gay.runescape.runeparty.items;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Spent on another player rather than the user themselves (see requiresTarget). Sends the target
 * backward BACKWARD_OFFSET tiles along the course path -- see the server's own items/tele_other.py
 * for the exact mechanic and why it fires a bare PLAYER_MOVED rather than routing through a real
 * tile landing. */
@Slf4j
public class TeleOtherItem implements Item
{
    /** Matches the server's own items/tele_other.py#BACKWARD_OFFSET -- purely cosmetic here (the
     * server computes the real destination itself), just so this item's own description reads the
     * true number rather than a guess. */
    private static final int BACKWARD_OFFSET = 5;

    private static final BufferedImage ICON = loadIcon();

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
        if (ICON == null) return;

        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g.drawImage(ICON, x - size / 2, y - size / 2, size, size, null);
        g.setComposite(original);
    }

    private static BufferedImage loadIcon()
    {
        try (InputStream is = TeleOtherItem.class.getResourceAsStream("/gay/runescape/runeparty/item_icons/tele-other-icon.png"))
        {
            if (is == null) throw new IOException("tele-other-icon.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Tele Other icon", e);
            return null;
        }
    }
}
