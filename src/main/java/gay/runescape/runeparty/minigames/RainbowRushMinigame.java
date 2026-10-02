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
        drawIconImage(g, ICON, x, y, size, alpha);
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
