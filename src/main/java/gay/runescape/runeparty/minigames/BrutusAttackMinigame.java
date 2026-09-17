package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import javax.swing.JPanel;

/** Entirely screen-driven -- transformed-Brutus rendering lives in overlays/PlayerTransformOverlay,
 * the gather message/dash countdown in AnnouncementOverlay, the eliminated-target skull in
 * PlayerOverlay -- no side-panel control at all, same reasoning HotPotatoMinigame/WhosYourJaddyMinigame
 * already give (see hasSidePanelPresence). A real, randomly-reachable mini-game, so it needs a real
 * wheel icon. */
public class BrutusAttackMinigame implements Minigame
{
    // Matches RunePartyPlugin.TEAM_A_COLOR/TEAM_B_COLOR (and the server's own brutus_attack.py
    // BRUTUS_ZONE_COLOR/TARGET_ZONE_COLOR) -- the same pink/teal pairing PlayerOverlay's own Brutus
    // Attack player-token coloring and the arena's own ground tiles both use now, so this wheel
    // icon agrees with what the round actually looks like.
    private static final Color BRUTUS_ZONE_COLOR = new Color(0xE6, 0x1E, 0x96);
    private static final Color TARGET_ZONE_COLOR = new Color(0x00, 0xAA, 0xAA);

    @Override
    public String getKey()
    {
        return "brutus-attack";
    }

    @Override
    public String getDisplayName()
    {
        return "Brutus Bullet";
    }

    /** Two small colored squares facing each other -- Brutus's own zone (pink) and the targets'
     * zone (teal), the exact same two hex colors the server's own brutus_attack.py colors the
     * arena's two ends with -- purely programmatic, same "no bundled raster asset needed for a
     * wheel icon" convention ClickClickClickMinigame/HotPotatoMinigame's own docs give. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int squareSize = Math.max(2, size / 3);
        int gap = Math.max(1, size / 8);

        g.setColor(new Color(BRUTUS_ZONE_COLOR.getRed(), BRUTUS_ZONE_COLOR.getGreen(), BRUTUS_ZONE_COLOR.getBlue(), a));
        g.fillRect(x - gap / 2 - squareSize, y - squareSize / 2, squareSize, squareSize);

        g.setColor(new Color(TARGET_ZONE_COLOR.getRed(), TARGET_ZONE_COLOR.getGreen(), TARGET_ZONE_COLOR.getBlue(), a));
        g.fillRect(x + gap / 2, y - squareSize / 2, squareSize, squareSize);
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
