package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Two duelling Jads whose zones/recolor/attack animations/duel-resolved banner all render/animate
 * in-world and center-screen (see models/JaddyDuelModel, TileOverlay's per-zone-color bounding box
 * outlines, and AnnouncementOverlay's duel-resolved banner) rather than through any side-panel
 * control (see hasSidePanelPresence). */
public class WhosYourJaddyMinigame implements Minigame
{
    // Matches RunePartyPlugin's own TEAM_A_COLOR/TEAM_B_COLOR so this wheel icon and the two Jads'
    // own zones/recolors always agree.
    private static final Color TEAM_A_PINK = new Color(0xE6, 0x1E, 0x96);
    private static final Color TEAM_B_TEAL = new Color(0x00, 0xAA, 0xAA);

    @Override
    public String getKey()
    {
        return "whos-your-jaddy";
    }

    @Override
    public String getDisplayName()
    {
        return "Who's Your Jaddy?";
    }

    /** Two facing triangles ("Jads") in the two team colors -- reads as "two duelling sides" at
     * wheel-icon size, distinct from every other mini-game's own grid-shaped icon. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int half = size / 2;

        Color pink = new Color(TEAM_A_PINK.getRed(), TEAM_A_PINK.getGreen(), TEAM_A_PINK.getBlue(), a);
        Color teal = new Color(TEAM_B_TEAL.getRed(), TEAM_B_TEAL.getGreen(), TEAM_B_TEAL.getBlue(), a);

        int gap = Math.max(2, size / 8);
        int triWidth = (size - gap) / 2;

        g.setColor(pink);
        g.fillPolygon(
            new int[] { x - half, x - half, x - gap / 2 },
            new int[] { y - half, y + half, y },
            3);

        g.setColor(teal);
        g.fillPolygon(
            new int[] { x + half, x + half, x + gap / 2 },
            new int[] { y - half, y + half, y },
            3);

        g.setColor(new Color(0, 0, 0, a));
        g.drawRect(x - half, y - half, size, size);
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
