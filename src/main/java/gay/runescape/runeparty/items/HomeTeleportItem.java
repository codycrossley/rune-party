package gay.runescape.runeparty.items;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Relocates the holder's tracked board position straight to the Start tile the instant it's used,
 * but doesn't pay out the reward until they've actually walked their character over. The "go stand
 * on the tile" nudge is covered for free by TileOverlay's existing return-arrow, so this item needs
 * no bespoke indicator -- just the standard use-announcement banner. */
@Slf4j
public class HomeTeleportItem implements Item
{
    private static final BufferedImage ICON = loadIcon();

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
            ? "Teleports you to the Start tile -- walk over to collect the reward."
            : "Teleports them to the Start tile -- they'll need to walk over to collect the reward.";
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
            ? "Walk to the Start Tile to collect 20 coins."
            : "They'll need to walk to the Start Tile to collect 20 coins.";
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
        try (InputStream is = HomeTeleportItem.class.getResourceAsStream("/gay/runescape/runeparty/item_icons/tele-home-icon.png"))
        {
            if (is == null) throw new IOException("tele-home-icon.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Home Teleport icon", e);
            return null;
        }
    }
}
