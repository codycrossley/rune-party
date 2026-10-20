package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Every tile on the main course temporarily turns one of six rainbow colors, cycling by its own
 * pathIndex (see TileOverlay#renderRainbowRushTile) -- starting as an outline only, filling in
 * solid the instant a player personally steps on it (see RainbowRushPresentation). First to fill
 * every course tile self-reports their own finish; the server settles who actually won and pays
 * out the reward. */
public class RainbowRushMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(RainbowRushMinigame.class, "/gay/runescape/runeparty/minigame_icons/rainbow-rush.png", "Rainbow Rush wheel icon");

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
}
