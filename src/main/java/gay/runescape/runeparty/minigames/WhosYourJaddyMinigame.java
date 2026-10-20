package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Two duelling Jads whose zones/recolor/attack animations/duel-resolved banner all render/animate
 * in-world and center-screen (see models/JaddyDuelModel, TileOverlay's per-zone-color bounding box
 * outlines, and AnnouncementOverlay's duel-resolved banner). */
public class WhosYourJaddyMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(WhosYourJaddyMinigame.class, "/gay/runescape/runeparty/minigame_icons/whos-your-jaddy.png", "Who's Your Jaddy? wheel icon");

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

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }
}
