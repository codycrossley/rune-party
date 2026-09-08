package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** A 3x3 grid of tiles players gather on, then step onto whichever of the four outer tiles lights
 * up next -- entirely client-local (see DanceDanceRuneScapePresentation), the icon here just
 * mirrors that same cross-shaped layout. Screen-driven, no side-panel control (see
 * hasSidePanelPresence). */
public class DanceDanceRuneScapeMinigame implements Minigame
{
    private static final Color CENTER_COLOR = new Color(0xFF, 0xEE, 0x00);
    private static final Color NORTH_SOUTH_COLOR = new Color(0xFF, 0x00, 0x99);
    private static final Color EAST_WEST_COLOR = new Color(0x00, 0xCC, 0xFF);
    private static final Color OUTLINE = new Color(255, 255, 255);

    @Override
    public String getKey()
    {
        return RunePartyPlugin.DANCE_DANCE_RUNESCAPE_KEY;
    }

    @Override
    public String getDisplayName()
    {
        return "Dance, Dance, RuneScape";
    }

    /** A plus-shaped cross of 5 squares (center + N/S/E/W), each tinted the same color its real
     * in-world tile uses -- purely programmatic, no image asset. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int cell = Math.max(2, size / 3);

        drawCell(g, x, y, cell, withAlpha(CENTER_COLOR, a));
        drawCell(g, x, y - cell, cell, withAlpha(NORTH_SOUTH_COLOR, a));
        drawCell(g, x, y + cell, cell, withAlpha(NORTH_SOUTH_COLOR, a));
        drawCell(g, x - cell, y, cell, withAlpha(EAST_WEST_COLOR, a));
        drawCell(g, x + cell, y, cell, withAlpha(EAST_WEST_COLOR, a));
    }

    private void drawCell(Graphics2D g, int centerX, int centerY, int cell, Color fill)
    {
        Rectangle2D square = new Rectangle2D.Float(centerX - cell / 2f, centerY - cell / 2f, cell, cell);
        g.setColor(fill);
        g.fill(square);
        g.setColor(OUTLINE);
        g.draw(square);
    }

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
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
