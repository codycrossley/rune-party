package gay.runescape.runeparty.items;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Instantly relocates the holder's tracked board position to the course tile immediately before
 * wherever the Golden Gnome currently sits -- so that any roll of 1+ afterward puts the gnome
 * within reach (see purchase-golden-gnome server-side). Unlike HomeTeleportItem there's no reward
 * to pay out and so no confirm-arrival round trip: this is a single instant effect, done the
 * moment it's used. The "go stand on the tile" nudge is still covered for free by TileOverlay's
 * existing return-arrow, which renders off PLAYER_MOVED generically regardless of what caused it. */
@Slf4j
public class GnomeGliderItem implements Item
{
    private static final BufferedImage ICON = loadIcon();

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
            ? "You'll pass the Golden Gnome on your next roll."
            : "They'll pass the Golden Gnome on their next roll.";
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
        try (InputStream is = GnomeGliderItem.class.getResourceAsStream("/gay/runescape/runeparty/item_icons/gnome-glider-icon.png"))
        {
            if (is == null) throw new IOException("gnome-glider-icon.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Gnome Glider icon", e);
            return null;
        }
    }
}
