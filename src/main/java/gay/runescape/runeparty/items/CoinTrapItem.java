package gay.runescape.runeparty.items;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;

/** Placed on a tile rather than resolved instantly (see requiresPlacement). No bundled icon asset,
 * so the wheel/inventory icon is procedural -- a dark jaw-trap glyph with a gold coin caught in it.
 * The in-world model once placed is rendered separately by TileOverlay. */
public class CoinTrapItem implements Item
{
    private static final Color JAW_COLOR = new Color(90, 60, 40);
    private static final Color COIN_COLOR = new Color(255, 215, 0);

    private final String key;
    private final String displayName;

    public CoinTrapItem(String key, String displayName)
    {
        this.key = key;
        this.displayName = displayName;
    }

    @Override
    public String getKey()
    {
        return key;
    }

    @Override
    public String getDisplayName()
    {
        return displayName;
    }

    @Override
    public String getEffectDescription(boolean isLocalPlayer)
    {
        return "Place it on a tile to steal coins from anyone but " + (isLocalPlayer ? "you" : "them") + " who lands on it.";
    }

    @Override
    public boolean requiresPlacement()
    {
        return true;
    }

    @Override
    public boolean hasUseAnnouncement()
    {
        return true;
    }

    @Override
    public String getUseAnnounceVerb()
    {
        return "placed";
    }

    @Override
    public String getUseAnnouncementSubtitle(boolean isLocalPlayer)
    {
        return "It steals coins from anyone but " + (isLocalPlayer ? "you" : "them") + " who lands on it.";
    }

    /** Two inward-facing triangular jaws (like a bear trap) around a small gold coin -- reads as
     * "a trap baited with coins" at wheel-icon size without needing a real model/image asset. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int half = size / 2;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));

        g.setColor(new Color(JAW_COLOR.getRed(), JAW_COLOR.getGreen(), JAW_COLOR.getBlue(), a));
        Polygon leftJaw = new Polygon();
        leftJaw.addPoint(x - half, y - half);
        leftJaw.addPoint(x - half / 4, y);
        leftJaw.addPoint(x - half, y + half);
        g.fillPolygon(leftJaw);

        Polygon rightJaw = new Polygon();
        rightJaw.addPoint(x + half, y - half);
        rightJaw.addPoint(x + half / 4, y);
        rightJaw.addPoint(x + half, y + half);
        g.fillPolygon(rightJaw);

        int coinR = Math.max(3, size / 6);
        g.setColor(new Color(COIN_COLOR.getRed(), COIN_COLOR.getGreen(), COIN_COLOR.getBlue(), a));
        g.fillOval(x - coinR, y - coinR, coinR * 2, coinR * 2);
        g.setColor(new Color(0, 0, 0, a));
        g.drawOval(x - coinR, y - coinR, coinR * 2, coinR * 2);
    }
}
