package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import java.util.Random;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** An 8x8 grid whose tiles/scoreboard/team-assignment banner all render/animate in-world and
 * center-screen (see TileOverlay's generic tile rendering, TurfWarsScoreOverlay, and
 * AnnouncementOverlay's team-assigned banner) rather than through any side-panel control (see
 * hasSidePanelPresence). */
public class TurfWarsMinigame implements Minigame
{
    // Matches RunePartyPlugin's own TEAM_A_COLOR/TEAM_B_COLOR so this wheel icon, the tiles
    // themselves, and the scoreboard/banner all agree. Always shows the fixed 2-team pairing even
    // though a live round might instead be the odd-count free-for-all variant (each player in
    // their own seat color).
    private static final Color TEAM_A_PINK = new Color(0xE6, 0x1E, 0x96);
    private static final Color TEAM_B_TEAL = new Color(0x00, 0xAA, 0xAA);
    private static final int GRID_DIM = 8;
    private static final int GRID_GAP = 1;

    /** Fixed at class-load so the icon doesn't re-shuffle on every repaint (drawIcon is called
     * once per frame while the minigame wheel spins). */
    private static final boolean[][] ICON_PATTERN = new boolean[GRID_DIM][GRID_DIM];

    static
    {
        Random rng = new Random(24601);
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
        return "turf-wars";
    }

    @Override
    public String getDisplayName()
    {
        return "Turf Wars";
    }

    /** An 8x8 grid of scattered pink/teal squares -- reads as "two teams claiming tiles" at
     * wheel-icon size, distinct from Arena's own 4x4 red/green hazard grid. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int half = size / 2;
        int left = x - half;
        int top = y - half;

        Color pink = new Color(TEAM_A_PINK.getRed(), TEAM_A_PINK.getGreen(), TEAM_A_PINK.getBlue(), a);
        Color teal = new Color(TEAM_B_TEAL.getRed(), TEAM_B_TEAL.getGreen(), TEAM_B_TEAL.getBlue(), a);

        int cell = (size - GRID_GAP * (GRID_DIM - 1)) / GRID_DIM;
        int used = cell * GRID_DIM + GRID_GAP * (GRID_DIM - 1);
        int pad = (size - used) / 2;

        for (int row = 0; row < GRID_DIM; row++)
        {
            for (int col = 0; col < GRID_DIM; col++)
            {
                g.setColor(ICON_PATTERN[row][col] ? pink : teal);
                int cx = left + pad + col * (cell + GRID_GAP);
                int cy = top + pad + row * (cell + GRID_GAP);
                g.fillRect(cx, cy, cell, cell);
            }
        }

        g.setColor(new Color(0, 0, 0, a));
        g.drawRect(left, top, size, size);
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
