package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** A pure clicking race -- no dedicated arena, no side-panel controls, players just click bare
 * ground wherever they already are. Local unique-tile-click tally renders via a dedicated
 * corner-of-attention overlay (see ClickClickClickOverlay) rather than any side-panel control (see
 * hasSidePanelPresence). */
public class ClickClickClickMinigame implements Minigame
{
    private static final Color RING_COLOR = new Color(255, 210, 0);

    @Override
    public String getKey()
    {
        return "click-click-click";
    }

    @Override
    public String getDisplayName()
    {
        return "Click, Click, Click";
    }

    /** A simple concentric-ring "target" icon -- purely programmatic, same "no bundled raster
     * asset needed for a wheel icon" convention as FishingContestMinigame's own bowl. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int half = size / 2;

        g.setColor(new Color(RING_COLOR.getRed(), RING_COLOR.getGreen(), RING_COLOR.getBlue(), a));
        g.draw(new Ellipse2D.Float(x - half, y - half, size, size));
        int midInset = Math.round(size * 0.3f);
        g.draw(new Ellipse2D.Float(x - half + midInset / 2, y - half + midInset / 2, size - midInset, size - midInset));
        int dotSize = Math.max(2, size / 6);
        g.fillOval(x - dotSize / 2, y - dotSize / 2, dotSize, dotSize);
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
