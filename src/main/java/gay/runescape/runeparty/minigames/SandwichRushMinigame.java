package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** A Turf-Wars-shaped arena in which up to 4 floating ingredients (see models/SandwichItemModel)
 * spawn/respawn continuously; walking onto one collects it, and holding one of each completes a
 * sandwich. Entirely screen/world-driven -- instructions and arrival-gather messages render
 * center-screen, and the local player's held-ingredients/sandwich-count status renders via a
 * dedicated corner overlay (see SandwichRushHudOverlay) -- so this has no side-panel presence. */
public class SandwichRushMinigame implements Minigame
{
    private static final Color BREAD_COLOR = new Color(222, 168, 92);
    private static final Color BREAD_OUTLINE = new Color(140, 95, 40);
    private static final Color CHEESE_COLOR = new Color(255, 210, 60);
    private static final Color TOMATO_COLOR = new Color(210, 50, 40);
    private static final Color LETTUCE_COLOR = new Color(90, 170, 70);

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

    /** A triangle sandwich, sliced diagonally the way the real OSRS item is -- a bread outline
     * with cheese/tomato/lettuce bands layered inside. Purely programmatic -- the raster ingredient
     * icons are reserved for SandwichRushHudOverlay instead. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int half = size / 2;

        Polygon triangle = new Polygon();
        triangle.addPoint(x - half, y + half);
        triangle.addPoint(x + half, y + half);
        triangle.addPoint(x - half, y - half);

        Color old = g.getColor();
        g.setColor(withAlpha(BREAD_COLOR, a));
        g.fillPolygon(triangle);

        // Filling bands, clipped to the triangle so each layer reads as a slice of the sandwich
        // rather than a separate shape overlapping it.
        java.awt.Shape oldClip = g.getClip();
        g.clip(triangle);
        int bandHeight = Math.max(2, size / 4);
        g.setColor(withAlpha(LETTUCE_COLOR, a));
        g.fillRect(x - half, y + half - bandHeight, size, bandHeight);
        g.setColor(withAlpha(TOMATO_COLOR, a));
        g.fillRect(x - half, y + half - bandHeight * 2, size, bandHeight);
        g.setColor(withAlpha(CHEESE_COLOR, a));
        g.fillRect(x - half, y + half - bandHeight * 3, size, bandHeight);
        g.setClip(oldClip);

        g.setColor(withAlpha(BREAD_OUTLINE, a));
        g.drawPolygon(triangle);
        g.setColor(old);
    }

    private static Color withAlpha(Color c, int a)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Never actually called -- see hasSidePanelPresence. */
    @Override
    public JComponent createControlPanel(RunePartyPlugin plugin)
    {
        return new JPanel();
    }

    @Override
    public boolean hasSidePanelPresence()
    {
        return false;
    }
}
