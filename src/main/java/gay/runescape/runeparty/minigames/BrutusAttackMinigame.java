package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Entirely screen-driven -- transformed-Brutus rendering lives in overlays/PlayerTransformOverlay,
 * the gather message/dash countdown in AnnouncementOverlay, the eliminated-target skull in
 * PlayerOverlay. A real, randomly-reachable mini-game, so it needs a real wheel icon. */
public class BrutusAttackMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(BrutusAttackMinigame.class, "/gay/runescape/runeparty/minigame_icons/brutus-bullet.png", "Brutus Bullet wheel icon");

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
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
