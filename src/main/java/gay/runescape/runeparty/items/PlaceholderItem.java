package gay.runescape.runeparty.items;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import net.runelite.client.ui.FontManager;

/** A generic item that just changes coins by a fixed amount -- nothing to draw here beyond an
 * obvious placeholder icon. Keys and display names must match the server's own registrations. */
public class PlaceholderItem implements Item
{
    private static final Color ICON_COLOR = new Color(80, 150, 220);

    private final String key;
    private final String displayName;

    public PlaceholderItem(String key, String displayName)
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

    /** An explicit, obvious placeholder -- a plain blue square with a glyph (the key's own last
     * character, so multiple registered instances read as distinct options). */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int half = size / 2;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));

        g.setColor(new Color(ICON_COLOR.getRed(), ICON_COLOR.getGreen(), ICON_COLOR.getBlue(), a));
        g.fillRoundRect(x - half, y - half, size, size, size / 4, size / 4);
        g.setColor(new Color(255, 255, 255, a));
        g.drawRoundRect(x - half, y - half, size, size, size / 4, size / 4);

        Font font = FontManager.getRunescapeBoldFont().deriveFont((float) (size * 0.55));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String text = key.substring(key.length() - 1);
        int textX = x - fm.stringWidth(text) / 2;
        int textY = y + fm.getAscent() / 2 - 2;
        g.setColor(new Color(0, 0, 0, a));
        g.drawString(text, textX + 1, textY + 1);
        g.setColor(new Color(255, 255, 255, a));
        g.drawString(text, textX, textY);
    }
}
