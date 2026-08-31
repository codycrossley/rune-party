package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Client-side counterpart to the server's FishingContestMinigame (see
 * minigames/fishing_contest.py) -- a Pond players catch fish from by performing the Headbang emote
 * nearby, whose local catch tally renders via a dedicated corner overlay (see FishingCatchOverlay)
 * rather than any side-panel
 * control, same "fully screen/world-driven" shape ArenaMinigame/TrueOrFalseMinigame already use
 * (see hasSidePanelPresence). Key must match the server's own FishingContestMinigame registration
 * exactly, see that file. Registering this is what keeps RunePartyPanel from falling back to
 * KeyedRegistry's own default entry (a Placeholder mini-game's score-submission spinner, which
 * makes no sense here) whenever this key's active. */
public class FishingContestMinigame implements Minigame
{
    private static final Color WATER_BLUE = new Color(40, 130, 230);
    private static final Color FIN_BLUE = new Color(20, 70, 140);
    private static final Color GLASS_FILL = new Color(220, 240, 255);
    private static final Color GLASS_OUTLINE = new Color(255, 255, 255);

    @Override
    public String getKey()
    {
        return "fishing-contest";
    }

    @Override
    public String getDisplayName()
    {
        return "Fishing Contest";
    }

    /** A round glass fish bowl -- faint glass fill, a water line across the lower portion with a
     * small fin poking out of it, and a flattened rim ellipse at the top marking the bowl's open
     * mouth -- reads as "fishing" (and now matches the in-world Fish bowl prop, see models/
     * PondModel) at wheel-icon size, distinct from Arena's grid and Coin Rush's coins/True or
     * False's T/F card. Purely programmatic per this interface's own contract (see WheelEntry's own
     * doc) -- the raster shrimp/anchovy icons are reserved for FishingCatchOverlay instead. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int half = size / 2;

        // The glass bowl itself -- a faintly-filled circle, so it reads as glass rather than a
        // solid ball.
        Shape bowl = new Ellipse2D.Float(x - half, y - half, size, size);
        g.setColor(new Color(GLASS_FILL.getRed(), GLASS_FILL.getGreen(), GLASS_FILL.getBlue(), a / 3));
        g.fill(bowl);

        // Water fill and fin, clipped to the bowl's own circle so the water line reads as an
        // actual level inside the glass rather than a separate shape overlapping it.
        Shape oldClip = g.getClip();
        g.clip(bowl);

        int waterTop = y - half + Math.round(size * 0.4f);
        g.setColor(new Color(WATER_BLUE.getRed(), WATER_BLUE.getGreen(), WATER_BLUE.getBlue(), a));
        g.fillRect(x - half, waterTop, size, size);

        int finBase = Math.max(2, size / 5);
        Polygon fin = new Polygon();
        fin.addPoint(x - finBase / 2, waterTop + finBase / 3);
        fin.addPoint(x + finBase / 2, waterTop + finBase / 3);
        fin.addPoint(x, waterTop - finBase);
        g.setColor(new Color(FIN_BLUE.getRed(), FIN_BLUE.getGreen(), FIN_BLUE.getBlue(), a));
        g.fillPolygon(fin);

        g.setClip(oldClip);

        // Glass outline, plus a flattened rim ellipse marking the bowl's open top -- what actually
        // sells "bowl" rather than just "ball".
        g.setColor(new Color(GLASS_OUTLINE.getRed(), GLASS_OUTLINE.getGreen(), GLASS_OUTLINE.getBlue(), a));
        g.draw(bowl);
        int rimHeight = Math.max(2, size / 6);
        g.drawOval(x - half, y - half - rimHeight / 2, size, rimHeight);
    }

    /** Never actually called -- see hasSidePanelPresence, same "minimal rather than unreachable"
     * shape TrueOrFalseMinigame/ArenaMinigame's own doc explains. */
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
