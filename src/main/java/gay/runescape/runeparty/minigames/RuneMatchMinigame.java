package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** The real board (a 7x7 arena, 16 of those 49 tiles hiding a rune pair each) renders in-world via
 * TileOverlay/RuneMatchRuneModel instead -- see RuneMatchPresentation's own doc. */
public class RuneMatchMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(RuneMatchMinigame.class, "/gay/runescape/runeparty/minigame_icons/rune-match.png", "Rune Match wheel icon");

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
}
