package gay.runescape.runeparty.items;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Banks a +3 bonus for the holder's own next roll rather than resolving immediately, so unlike
 * PlaceholderItem this has no coin-popup feedback of its own -- see hasUseAnnouncement/
 * getUseAnnouncementSubtitle for the banner that stands in for that. */
@Slf4j
public class EnergyPotionItem implements Item
{
    private static final int ROLL_BONUS = 3;
    private static final BufferedImage ICON = loadIcon();

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
        if (ICON == null) return;

        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g.drawImage(ICON, x - size / 2, y - size / 2, size, size, null);
        g.setComposite(original);
    }

    private static BufferedImage loadIcon()
    {
        try (InputStream is = EnergyPotionItem.class.getResourceAsStream("/gay/runescape/runeparty/item_icons/energy-potion-icon.png"))
        {
            if (is == null) throw new IOException("energy-potion-icon.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Energy Potion icon", e);
            return null;
        }
    }
}
