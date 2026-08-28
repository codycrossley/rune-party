package gay.runescape.runeparty.items;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Client-side counterpart to the server's HomeTeleportItem (see items/home_teleport.py) --
 * relocates the holder's own tracked board position straight to the Start tile the instant it's
 * used, but doesn't pay out the reward until they've actually walked their own character over
 * (COINS_CHANGED reason="start_tile" fires later, on RunePartyPlugin's own
 * confirmHomeTeleportArrival -- already recognized by the existing coin-popup handling, no new
 * client wiring needed for that half once it does fire). The "go stand on the physical tile" nudge
 * is covered for free: PLAYER_MOVED already drives TileOverlay#renderReturnToPositionArrow
 * generically regardless of what caused the move, so this item needs no bespoke indicator of its
 * own -- just the standard "You/&lt;rsn&gt; used &lt;item&gt;!" banner (see hasUseAnnouncement).
 * Key must match the server's own HomeTeleportItem registration exactly, see that file. */
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
