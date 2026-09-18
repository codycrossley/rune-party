package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** An 8x8 arena of randomly-flashing "club lighting" tiles around a single centerpiece Gemstone
 * Crab -- entirely screen/in-world-driven (see CrabRavePresentation, CrabRaveNpcOverlay, and
 * TileOverlay#renderCrabRaveTile). */
@Slf4j
public class CrabRaveMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return RunePartyPlugin.CRAB_RAVE_KEY;
    }

    @Override
    public String getDisplayName()
    {
        return "Crab Rave";
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
        try (InputStream is = CrabRaveMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/crab-rave.png"))
        {
            if (is == null) throw new IOException("crab-rave.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Crab Rave wheel icon", e);
            return null;
        }
    }
}
