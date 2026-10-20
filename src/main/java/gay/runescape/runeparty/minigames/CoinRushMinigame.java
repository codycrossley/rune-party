package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** 2-4 coin spawns appear on random tiles over a 30-second round; whoever physically reaches a
 * spawn's tile first gets +2 coins and it disappears, then another can spawn elsewhere. There's
 * nothing for the player to actually operate here -- collection is automatic the instant they walk
 * onto a live spawn's tile -- the actual gameplay renders in-world, the live per-round tally lives
 * in StatsOverlay's scoreboard, and the "here's what to do" reminder is the MINIGAME_STARTED
 * instructions banner AnnouncementOverlay already shows (this used to also repeat as a side-panel
 * control-panel hint -- see docs/ARCHITECTURE_REVIEW.md's S7 for why that was removed). */
public class CoinRushMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(CoinRushMinigame.class, "/gay/runescape/runeparty/minigame_icons/coin-rush.png", "Coin Rush wheel icon");

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
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
