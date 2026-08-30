package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Random;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Client-side counterpart to the server's ArenaMinigame (see minigames/arena.py) -- a 4x4 hazard
 * grid whose gathering message, tile colors, and elimination all render/animate in-world and
 * center-screen (see TileOverlay's generic tile rendering and AnnouncementOverlay#
 * renderArenaGatherMessage) rather than through any side-panel control, same "fully screen-driven"
 * shape TrueOrFalseMinigame already uses (see hasSidePanelPresence). Key must match the server's
 * own ArenaMinigame registration exactly, see that file. Registering this is what keeps
 * RunePartyPanel from falling back to KeyedRegistry's own default entry (a Placeholder mini-game's
 * score-submission spinner, which makes no sense for the Arena) whenever this key's active. */
public class ArenaMinigame implements Minigame
{
    private static final Color ARENA_RED = new Color(220, 30, 30);
    private static final Color ARENA_GREEN = new Color(46, 204, 64);
    private static final int GRID_DIM = 4;
    private static final int GRID_GAP = 1;

    /** Fixed at class-load so the icon doesn't re-shuffle on every repaint (drawIcon is called
     * once per frame while the minigame wheel spins). */
    private static final boolean[][] ICON_PATTERN = new boolean[GRID_DIM][GRID_DIM];

    static
    {
        Random rng = new Random(12345);
        for (int row = 0; row < GRID_DIM; row++)
        {
            for (int col = 0; col < GRID_DIM; col++)
            {
                ICON_PATTERN[row][col] = rng.nextBoolean();
            }
        }
    }

    @Override
    public String getKey()
    {
        return "arena";
    }

    @Override
    public String getDisplayName()
    {
        return "The Arena";
    }

    /** A 4x4 grid of scattered red/green squares -- reads as "the grid, safe tiles and hazard
     * tiles" at wheel-icon size, distinct from Coin Rush's coins and True or False's T/F card. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int half = size / 2;
        int left = x - half;
        int top = y - half;

        Color green = new Color(ARENA_GREEN.getRed(), ARENA_GREEN.getGreen(), ARENA_GREEN.getBlue(), a);
        Color red = new Color(ARENA_RED.getRed(), ARENA_RED.getGreen(), ARENA_RED.getBlue(), a);

        int cell = (size - GRID_GAP * (GRID_DIM - 1)) / GRID_DIM;
        int used = cell * GRID_DIM + GRID_GAP * (GRID_DIM - 1);
        int pad = (size - used) / 2;

        for (int row = 0; row < GRID_DIM; row++)
        {
            for (int col = 0; col < GRID_DIM; col++)
            {
                g.setColor(ICON_PATTERN[row][col] ? green : red);
                int cx = left + pad + col * (cell + GRID_GAP);
                int cy = top + pad + row * (cell + GRID_GAP);
                g.fillRect(cx, cy, cell, cell);
            }
        }

        g.setColor(new Color(0, 0, 0, a));
        g.drawRect(left, top, size, size);
    }

    /** Never actually called -- see hasSidePanelPresence, same "minimal rather than unreachable"
     * shape TrueOrFalseMinigame's own doc explains. */
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
