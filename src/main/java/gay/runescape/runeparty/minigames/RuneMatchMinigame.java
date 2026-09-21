package gay.runescape.runeparty.minigames;

import java.awt.Color;
import java.awt.Graphics2D;

/** A 4x4 grid of dimmed squares, reading as "sixteen hidden cards" at wheel-icon size -- distinct
 * from Arena's own scattered red/green hazard grid despite the shared 4x4 layout. The real board
 * (a 7x7 arena, 16 of those 49 tiles hiding a rune pair each) renders in-world via TileOverlay/
 * RuneMatchRuneModel instead -- see RuneMatchPresentation's own doc. */
public class RuneMatchMinigame implements Minigame
{
    private static final Color CARD_COLOR = new Color(110, 110, 110);
    private static final Color CARD_OUTLINE = new Color(255, 255, 255);
    private static final int GRID_DIM = 4;
    private static final int GRID_GAP = 1;

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
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));

        Color card = new Color(CARD_COLOR.getRed(), CARD_COLOR.getGreen(), CARD_COLOR.getBlue(), a);
        Color outline = new Color(CARD_OUTLINE.getRed(), CARD_OUTLINE.getGreen(), CARD_OUTLINE.getBlue(), a);

        int cell = (size - GRID_GAP * (GRID_DIM - 1)) / GRID_DIM;
        int used = cell * GRID_DIM + GRID_GAP * (GRID_DIM - 1);
        int left = x - used / 2;
        int top = y - used / 2;

        for (int row = 0; row < GRID_DIM; row++)
        {
            for (int col = 0; col < GRID_DIM; col++)
            {
                int cx = left + col * (cell + GRID_GAP);
                int cy = top + row * (cell + GRID_GAP);
                g.setColor(card);
                g.fillRect(cx, cy, cell, cell);
                g.setColor(outline);
                g.drawRect(cx, cy, cell, cell);
            }
        }
    }
}
