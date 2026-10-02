package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** The real board (a 7x7 arena, 16 of those 49 tiles hiding a rune pair each) renders in-world via
 * TileOverlay/RuneMatchRuneModel instead -- see RuneMatchPresentation's own doc. */
@Slf4j
public class RuneMatchMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "rune-match";
    }

    @Override
    public String getDisplayName()
    {
        return "Rune Match";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }

    private static BufferedImage loadIcon()
    {
        try (InputStream is = RuneMatchMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/rune-match.png"))
        {
            if (is == null) throw new IOException("rune-match.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Rune Match wheel icon", e);
            return null;
        }
    }
}
