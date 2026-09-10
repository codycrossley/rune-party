package gay.runescape.runeparty.items;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Placed on a tile rather than resolved instantly (see requiresPlacement). The in-world model
 * once placed is rendered separately by TileOverlay -- drawIcon below is only the wheel/inventory
 * glyph. */
@Slf4j
public class CoinTrapItem implements Item
{
    private static final BufferedImage ICON = loadIcon();

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
        return "Place it on a tile to steal coins from anyone but " + (isLocalPlayer ? "you" : "them") + " who lands on it.";
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
        if (ICON == null) return;

        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g.drawImage(ICON, x - size / 2, y - size / 2, size, size, null);
        g.setComposite(original);
    }

    private static BufferedImage loadIcon()
    {
        try (InputStream is = CoinTrapItem.class.getResourceAsStream("/gay/runescape/runeparty/item_icons/coin-trap-icon.png"))
        {
            if (is == null) throw new IOException("coin-trap-icon.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Coin Trap icon", e);
            return null;
        }
    }
}
