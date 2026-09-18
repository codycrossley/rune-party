package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** Entirely screen-driven -- transformed-Brutus rendering lives in overlays/PlayerTransformOverlay,
 * the gather message/dash countdown in AnnouncementOverlay, the eliminated-target skull in
 * PlayerOverlay. A real, randomly-reachable mini-game, so it needs a real wheel icon. */
@Slf4j
public class BrutusAttackMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "brutus-attack";
    }

    @Override
    public String getDisplayName()
    {
        return "Brutus Bullet";
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
        try (InputStream is = BrutusAttackMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/brutus-bullet.png"))
        {
            if (is == null) throw new IOException("brutus-bullet.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Brutus Bullet wheel icon", e);
            return null;
        }
    }
}
