package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyColor;
import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.Color;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Every tile on the main course temporarily turns one of six rainbow colors, cycling by its own
 * pathIndex (see TileOverlay#renderRainbowRushTile) -- starting as an outline only, filling in
 * solid the instant a player personally steps on it (see RainbowRushPresentation). First to fill
 * every course tile self-reports their own finish; the server settles who actually won and pays
 * out the reward. Entirely board/overlay-driven, no side-panel control (see
 * hasSidePanelPresence), same as Turf Wars/Coin Rush. */
public class RainbowRushMinigame implements Minigame
{
    private static final RunePartyColor[] PALETTE =
    {
        RunePartyColor.RED, RunePartyColor.ORANGE, RunePartyColor.YELLOW,
        RunePartyColor.GREEN, RunePartyColor.BLUE, RunePartyColor.PURPLE
    };

    @Override
    public String getKey()
    {
        return RunePartyPlugin.RAINBOW_RUSH_KEY;
    }

    @Override
    public String getDisplayName()
    {
        return "Rainbow Rush";
    }

    /** A small ROYGBP strip of squares -- reads as "rainbow board" at wheel-icon size. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int cell = Math.max(2, size / PALETTE.length);
        int totalWidth = cell * PALETTE.length;
        int left = x - totalWidth / 2;
        int top = y - cell / 2;

        for (int i = 0; i < PALETTE.length; i++)
        {
            Color c = PALETTE[i].awt;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), a));
            g.fillRect(left + i * cell, top, cell, cell);
        }
        g.setColor(new Color(0, 0, 0, a));
        g.drawRect(left, top, totalWidth, cell);
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
