package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** A Turf-Wars-shaped arena in which up to 4 floating ingredients (see models/SandwichItemModel)
 * spawn/respawn continuously; walking onto one collects it, and holding one of each completes a
 * sandwich. Entirely screen/world-driven -- instructions and arrival-gather messages render
 * center-screen, and the local player's held-ingredients/sandwich-count status renders via a
 * dedicated corner overlay (see SandwichRushHudOverlay). */
@Slf4j
public class SandwichRushMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "sandwich-rush";
    }

    @Override
    public String getDisplayName()
    {
        return "Sandwich Rush";
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
        try (InputStream is = SandwichRushMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/sandwich-rush.png"))
        {
            if (is == null) throw new IOException("sandwich-rush.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Sandwich Rush wheel icon", e);
            return null;
        }
    }
}
