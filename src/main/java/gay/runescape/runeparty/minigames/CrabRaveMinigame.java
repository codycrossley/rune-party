package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** An 8x8 arena of randomly-flashing "club lighting" tiles around a single centerpiece Gemstone
 * Crab -- entirely screen/in-world-driven (see CrabRavePresentation, CrabRaveNpcOverlay, and
 * TileOverlay#renderCrabRaveTile). */
public class CrabRaveMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(CrabRaveMinigame.class, "/gay/runescape/runeparty/minigame_icons/crab-rave.png", "Crab Rave wheel icon");

    @Override
    public String getKey()
    {
        return RunePartyPlugin.CRAB_RAVE_KEY;
    }

    @Override
    public String getDisplayName()
    {
        return "Crab Rave";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
