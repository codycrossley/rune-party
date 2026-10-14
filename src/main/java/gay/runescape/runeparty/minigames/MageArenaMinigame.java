package gay.runescape.runeparty.minigames;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;

/** A purple arena floor with a bursting fire-spell motif in the center -- reads as "arena plus
 * spell" at wheel-icon size, distinct from Arena's own red/green hazard grid. */
public class MageArenaMinigame implements Minigame
{
    private static final Color FLOOR_PURPLE = new Color(106, 13, 173);
    private static final Color FLAME_ORANGE = new Color(255, 140, 0);
    private static final Color FLAME_RED = new Color(220, 30, 30);

    @Override
    public String getKey()
    {
        return "mage-arena";
    }

    @Override
    public String getDisplayName()
    {
        return "Mage Arena";
    }

    /** A purple floor square with an 8-point orange/red burst star in the center -- the arena
     * floor plus a spell detonation, distinct from ArenaMinigame's own scattered red/green grid. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int half = size / 2;
        int left = x - half;
        int top = y - half;

        g.setColor(new Color(FLOOR_PURPLE.getRed(), FLOOR_PURPLE.getGreen(), FLOOR_PURPLE.getBlue(), a));
        g.fillRect(left, top, size, size);
        g.setColor(new Color(0, 0, 0, a));
        g.drawRect(left, top, size, size);

        int outerR = (int) (size * 0.42);
        int innerR = (int) (size * 0.16);
        Path2D.Double star = new Path2D.Double();
        int points = 8;
        for (int i = 0; i < points * 2; i++)
        {
            double angle = Math.PI * i / points;
            double r = (i % 2 == 0) ? outerR : innerR;
            double px = x + r * Math.sin(angle);
            double py = y - r * Math.cos(angle);
            if (i == 0) star.moveTo(px, py); else star.lineTo(px, py);
        }
        star.closePath();

        g.setColor(new Color(FLAME_RED.getRed(), FLAME_RED.getGreen(), FLAME_RED.getBlue(), a));
        g.fill(star);
        g.setColor(new Color(FLAME_ORANGE.getRed(), FLAME_ORANGE.getGreen(), FLAME_ORANGE.getBlue(), a));
        int coreR = (int) (size * 0.12);
        g.fillOval(x - coreR, y - coreR, coreR * 2, coreR * 2);
    }
}
