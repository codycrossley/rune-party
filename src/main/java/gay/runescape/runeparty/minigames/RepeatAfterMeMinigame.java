package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** A 4x4 grid of tiles briefly reveals a memorized-then-hidden pattern each round -- entirely
 * screen/in-world-driven (see RepeatAfterMePresentation and TileOverlay#renderRepeatAfterMeTile),
 * the icon here just mirrors that same grid with a couple of cells lit, matching how the real
 * sneak peek looks. Screen-driven, no side-panel control (see hasSidePanelPresence), same shape
 * DanceDanceRuneScapeMinigame's own icon-only registration uses. */
public class RepeatAfterMeMinigame implements Minigame
{
    private static final Color CELL_COLOR = new Color(255, 215, 0); // matches TileOverlay's own REPEAT_AFTER_ME_PEEK_FILL_COLOR
    private static final Color OUTLINE = new Color(255, 255, 255);
    // Which of the 16 cells (row-major, matching the real grid's own indexing) render lit on the
    // icon -- purely illustrative, doesn't need to match any real round's own gridIndices.
    private static final boolean[] LIT_CELLS = new boolean[16];
    static
    {
        LIT_CELLS[5] = true;
        LIT_CELLS[10] = true;
    }

    @Override
    public String getKey()
    {
        return RunePartyPlugin.REPEAT_AFTER_ME_KEY;
    }

    @Override
    public String getDisplayName()
    {
        return "Repeat After Me";
    }

    /** A 4x4 grid of small squares, two of them lit gold -- reads as "memorize the grid" at
     * wheel-icon size, purely programmatic, no image asset. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int cell = Math.max(2, size / 4);
        int gridOrigin = -2 * cell; // top-left corner of the 4x4 block, centered on (x, y)

        for (int row = 0; row < 4; row++)
        {
            for (int col = 0; col < 4; col++)
            {
                int index = row * 4 + col;
                Color fill = LIT_CELLS[index] ? withAlpha(CELL_COLOR, a) : new Color(0, 0, 0, 0);
                drawCell(g, x + gridOrigin + col * cell, y + gridOrigin + row * cell, cell, fill, a);
            }
        }
    }

    private void drawCell(Graphics2D g, int left, int top, int cell, Color fill, int outlineAlpha)
    {
        Rectangle2D square = new Rectangle2D.Float(left, top, cell, cell);
        if (fill.getAlpha() > 0)
        {
            g.setColor(fill);
            g.fill(square);
        }
        g.setColor(withAlpha(OUTLINE, outlineAlpha));
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
