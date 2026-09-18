package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** A Pond players catch fish from by performing the Headbang emote nearby, whose local catch tally
 * renders via a dedicated corner overlay (see FishingCatchOverlay). */
@Slf4j
public class FishingContestMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "fishing-contest";
    }

    @Override
    public String getDisplayName()
    {
        return "Fishing Contest";
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
        try (InputStream is = FishingContestMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/fishing-contest.png"))
        {
            if (is == null) throw new IOException("fishing-contest.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Fishing Contest wheel icon", e);
            return null;
        }
    }
}
