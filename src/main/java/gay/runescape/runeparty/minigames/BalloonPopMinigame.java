package gay.runescape.runeparty.minigames;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;

/** A single round balloon with a small knot at its base, reading as "balloon" at wheel-icon size --
 * the real balloons (one per seated player, recolored to that player's own seat color, floating
 * above their head and growing with every 10 clicks) render in-world via models/BalloonModel
 * instead -- see BalloonPopPresentation's own doc. */
public class BalloonPopMinigame implements Minigame
{
    private static final Color BALLOON_COLOR = new Color(255, 111, 168); // matches tiles/balloon_pop.py's own color_hex
    private static final Color OUTLINE_COLOR = new Color(255, 255, 255);

    @Override
    public String getKey()
    {
        return "balloon-pop";
    }

    @Override
    public String getDisplayName()
    {
        return "Hot Click Balloon";
    }

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        Color fill = new Color(BALLOON_COLOR.getRed(), BALLOON_COLOR.getGreen(), BALLOON_COLOR.getBlue(), a);
        Color outline = new Color(OUTLINE_COLOR.getRed(), OUTLINE_COLOR.getGreen(), OUTLINE_COLOR.getBlue(), a);

        int bodyDiameter = Math.round(size * 0.75f);
        int bodyTop = y - size / 2;
        int bodyLeft = x - bodyDiameter / 2;

        g.setColor(fill);
        g.fillOval(bodyLeft, bodyTop, bodyDiameter, bodyDiameter);
        g.setColor(outline);
        g.drawOval(bodyLeft, bodyTop, bodyDiameter, bodyDiameter);

        int knotSize = Math.max(2, size / 10);
        int knotTop = bodyTop + bodyDiameter;
        Polygon knot = new Polygon();
        knot.addPoint(x - knotSize, knotTop);
        knot.addPoint(x + knotSize, knotTop);
        knot.addPoint(x, knotTop + knotSize);
        g.setColor(fill);
        g.fillPolygon(knot);
        g.setColor(outline);
        g.drawPolygon(knot);
    }
}
