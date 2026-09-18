package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** 5 rounds, 5 seconds each, one OSRS trivia question per round. An answer is a YES ("True")/NO
 * ("False") emote, and the question, countdown, live "who's answered" tally, and per-round reveal
 * all render screen-centered in AnnouncementOverlay. */
@Slf4j
public class TrueOrFalseMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "true-or-false";
    }

    @Override
    public String getDisplayName()
    {
        return "True or False";
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
        try (InputStream is = TrueOrFalseMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/true-or-false.png"))
        {
            if (is == null) throw new IOException("true-or-false.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the True or False wheel icon", e);
            return null;
        }
    }
}
