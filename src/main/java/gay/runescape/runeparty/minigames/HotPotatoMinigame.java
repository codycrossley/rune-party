package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Entirely emote-driven (the SPIN emote passes it, see RunePartyPlugin#isLocalPlayerHoldingHotPotato/
 * onAnimationChanged) -- no side-panel control at all, same reasoning WhosYourJaddyMinigame/
 * ClickClickClickMinigame already give (see hasSidePanelPresence). The status overview (who's
 * holding it, the round countdown) renders center-screen via HotPotatoOverlay instead. */
public class HotPotatoMinigame implements Minigame
{
    private static final Color POTATO_COLOR = new Color(168, 120, 74);
    private static final Color SPECKLE_COLOR = new Color(96, 64, 36);

    @Override
    public String getKey()
    {
        return "hot-potato";
    }

    @Override
    public String getDisplayName()
    {
        return "Hot Potato";
    }

    /** A simple potato-brown oval with a couple of darker speckles -- purely programmatic, same
     * "no bundled raster asset needed for a wheel icon" convention ClickClickClickMinigame's own
     * doc gives (the bundled potato.png raster is reserved for HotPotatoOverlay, where it needs to
     * read as a real potato at a larger, steadier size). */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int halfW = size / 2;
        int halfH = Math.round(size * 0.38f);

        g.setColor(new Color(POTATO_COLOR.getRed(), POTATO_COLOR.getGreen(), POTATO_COLOR.getBlue(), a));
        g.fill(new Ellipse2D.Float(x - halfW, y - halfH, halfW * 2, halfH * 2));

        g.setColor(new Color(SPECKLE_COLOR.getRed(), SPECKLE_COLOR.getGreen(), SPECKLE_COLOR.getBlue(), a));
        int speckle = Math.max(2, size / 10);
        g.fillOval(x - halfW / 2, y - speckle / 2, speckle, speckle);
        g.fillOval(x + halfW / 4, y - halfH / 3, speckle, speckle);
        g.fillOval(x - halfW / 4, y + halfH / 3, speckle, speckle);
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
