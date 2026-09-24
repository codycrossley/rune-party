package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** The real balloons (one per seated player, recolored to that player's own seat color, floating
 * above their head and growing with every 10 clicks) render in-world via models/BalloonModel
 * instead -- see BalloonPopPresentation's own doc. */
@Slf4j
public class BalloonPopMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "balloon-pop";
    }

    @Override
    public String getDisplayName()
    {
        return "Hot Click Balloon";
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
        try (InputStream is = BalloonPopMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/hot-click-balloon.png"))
        {
            if (is == null) throw new IOException("hot-click-balloon.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Hot Click Balloon wheel icon", e);
            return null;
        }
    }
}
