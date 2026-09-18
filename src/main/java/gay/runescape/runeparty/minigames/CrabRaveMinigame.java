package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Rectangle2D;

/** An 8x8 arena of randomly-flashing "club lighting" tiles around a single centerpiece Gemstone
 * Crab -- entirely screen/in-world-driven (see CrabRavePresentation, CrabRaveNpcOverlay, and
 * TileOverlay#renderCrabRaveTile), the icon here just mirrors that same dance-floor-lights look at
 * wheel-icon size rather than the crab itself (a recognizable crab silhouette doesn't read at this
 * size; the colored floor does). */
public class CrabRaveMinigame implements Minigame
{
    // Same neon "club lighting" palette as TileOverlay's own CRAB_RAVE_LIGHT_COLORS, just a
    // smaller sample -- kept as its own copy rather than a shared reference, since neither side has
    // any reason to stay byte-for-byte identical (this is a fixed illustrative icon, not a live
    // readout of the real randomly-changing pattern).
    private static final Color[] LIGHT_COLORS =
    {
        new Color(255, 20, 147), // hot pink
        new Color(0, 220, 255),  // cyan
        new Color(190, 0, 255),  // purple
        new Color(255, 230, 0),  // electric yellow
    };
    private static final Color FLOOR_COLOR = new Color(40, 20, 50);
    private static final Color OUTLINE = new Color(255, 255, 255);
    // Which of the 3x3 icon cells get a colored light -- the four corners, leaving the center free
    // for visual balance (purely illustrative, doesn't need to match any real round's own pattern).
    private static final int[] LIT_CELLS = {0, 2, 6, 8};

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

    /** A 3x3 dance floor, four corner cells lit in rotating neon colors -- reads as "colorful
     * dance floor" at wheel-icon size, purely programmatic, no image asset. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int cell = Math.max(2, size / 3);
        int gridOrigin = -cell - cell / 2; // top-left corner of the 3x3 block, centered on (x, y)

        for (int row = 0; row < 3; row++)
        {
            for (int col = 0; col < 3; col++)
            {
                int index = row * 3 + col;
                Color fill = FLOOR_COLOR;
                for (int i = 0; i < LIT_CELLS.length; i++)
                {
                    if (LIT_CELLS[i] == index)
                    {
                        fill = LIGHT_COLORS[i % LIGHT_COLORS.length];
                        break;
                    }
                }
                drawCell(g, x + gridOrigin + col * cell, y + gridOrigin + row * cell, cell, withAlpha(fill, a), a);
            }
        }
    }

    private void drawCell(Graphics2D g, int left, int top, int cell, Color fill, int outlineAlpha)
    {
        Rectangle2D square = new Rectangle2D.Float(left, top, cell, cell);
        g.setColor(fill);
        g.fill(square);
        g.setColor(withAlpha(OUTLINE, outlineAlpha));
        g.draw(square);
    }

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
