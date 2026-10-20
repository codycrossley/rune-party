package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/** Entirely emote-driven (the SPIN emote passes it, see RunePartyPlugin#isLocalPlayerHoldingHotPotato/
 * onAnimationChanged). The status overview (who's holding it, the round countdown) renders
 * center-screen via HotPotatoOverlay instead. */
public class HotPotatoMinigame implements Minigame
{
    private static final BufferedImage ICON = WheelEntry.loadIcon(HotPotatoMinigame.class, "/gay/runescape/runeparty/minigame_icons/hot-potato.png", "Hot Potato wheel icon");

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

    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        drawIconImage(g, ICON, x, y, size, alpha);
    }

    /** Distinct from HotPotatoOverlay's own bundled potato.png (a larger, steadier in-world/HUD
     * raster) -- this one's sized and cached purely for the wheel-icon slot. */
}
