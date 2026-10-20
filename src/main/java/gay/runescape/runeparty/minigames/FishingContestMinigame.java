package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** A Pond players catch fish from by performing the Headbang emote nearby, whose local catch tally
 * renders via a dedicated corner overlay (see FishingCatchOverlay). */
public class FishingContestMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(FishingContestMinigame.class, "/gay/runescape/runeparty/minigame_icons/fishing-contest.png", "Fishing Contest wheel icon");

    @Override
    public String getKey()
    {
        return "fishing-contest";
    }

    @Override
    public String getDisplayName()
    {
        return "Fishing Contest";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
