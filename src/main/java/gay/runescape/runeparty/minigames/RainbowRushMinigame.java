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

/** Every tile on the main course temporarily turns one of six rainbow colors, cycling by its own
 * pathIndex (see TileOverlay#renderRainbowRushTile) -- starting as an outline only, filling in
 * solid the instant a player personally steps on it (see RainbowRushPresentation). First to fill
 * every course tile self-reports their own finish; the server settles who actually won and pays
 * out the reward. */
@Slf4j
public class RainbowRushMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return RunePartyPlugin.RAINBOW_RUSH_KEY;
    }

    @Override
    public String getDisplayName()
    {
        return "Rainbow Rush";
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
        try (InputStream is = RainbowRushMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/rainbow-rush.png"))
        {
            if (is == null) throw new IOException("rainbow-rush.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Rainbow Rush wheel icon", e);
            return null;
        }
    }
}
