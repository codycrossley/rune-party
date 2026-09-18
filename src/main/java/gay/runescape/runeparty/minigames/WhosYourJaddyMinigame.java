package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Two duelling Jads whose zones/recolor/attack animations/duel-resolved banner all render/animate
 * in-world and center-screen (see models/JaddyDuelModel, TileOverlay's per-zone-color bounding box
 * outlines, and AnnouncementOverlay's duel-resolved banner). */
@Slf4j
public class WhosYourJaddyMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "whos-your-jaddy";
    }

    @Override
    public String getDisplayName()
    {
        return "Who's Your Jaddy?";
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
        try (InputStream is = WhosYourJaddyMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/whos-your-jaddy.png"))
        {
            if (is == null) throw new IOException("whos-your-jaddy.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Who's Your Jaddy? wheel icon", e);
            return null;
        }
    }
}
