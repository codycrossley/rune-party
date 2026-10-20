package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** A Turf-Wars-shaped arena in which up to 4 floating ingredients (see models/SandwichItemModel)
 * spawn/respawn continuously; walking onto one collects it, and holding one of each completes a
 * sandwich. Entirely screen/world-driven -- instructions and arrival-gather messages render
 * center-screen, and the local player's held-ingredients/sandwich-count status renders via a
 * dedicated corner overlay (see SandwichRushHudOverlay). */
public class SandwichRushMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(SandwichRushMinigame.class, "/gay/runescape/runeparty/minigame_icons/sandwich-rush.png", "Sandwich Rush wheel icon");

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
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
