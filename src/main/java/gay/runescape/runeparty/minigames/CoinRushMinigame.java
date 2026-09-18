package gay.runescape.runeparty.minigames;

import java.awt.AlphaComposite;
import java.awt.Composite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;

/** 2-4 coin spawns appear on random tiles over a 30-second round; whoever physically reaches a
 * spawn's tile first gets +2 coins and it disappears, then another can spawn elsewhere. There's
 * nothing for the player to actually operate here -- collection is automatic the instant they walk
 * onto a live spawn's tile -- the actual gameplay renders in-world, the live per-round tally lives
 * in StatsOverlay's scoreboard, and the "here's what to do" reminder is the MINIGAME_STARTED
 * instructions banner AnnouncementOverlay already shows (this used to also repeat as a side-panel
 * control-panel hint -- see docs/ARCHITECTURE_REVIEW.md's S7 for why that was removed). */
@Slf4j
public class CoinRushMinigame implements Minigame
{
    private static final BufferedImage ICON = loadIcon();

    @Override
    public String getKey()
    {
        return "coin-rush";
    }

    @Override
    public String getDisplayName()
    {
        return "Coin Rush";
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
        try (InputStream is = CoinRushMinigame.class.getResourceAsStream("/gay/runescape/runeparty/minigame_icons/coin-rush.png"))
        {
            if (is == null) throw new IOException("coin-rush.png resource not found");
            return ImageIO.read(is);
        }
        catch (IOException e)
        {
            log.warn("Failed to load the Coin Rush wheel icon", e);
            return null;
        }
    }
}
