package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Entirely emote-driven (the SPIN emote passes it, see RunePartyPlugin#isLocalPlayerHoldingHotPotato/
 * onAnimationChanged). The status overview (who's holding it, the round countdown) renders
 * center-screen via HotPotatoOverlay instead. */
@Slf4j
public class HotPotatoMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "hot-potato";
    }

    @Override
    public String getDisplayName()
    {
        return "Hot Potato";
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

    /** Distinct from HotPotatoOverlay's own bundled potato.png (a larger, steadier in-world/HUD
     * raster) -- this one's sized and cached purely for the wheel-icon slot. */
    private static BufferedImage loadIcon()
    {
        try (InputStream is = HotPotatoMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/hot-potato.png"))
        {
            if (is == null) throw new IOException("hot-potato.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Hot Potato wheel icon", e);
            return null;
        }
    }
}
