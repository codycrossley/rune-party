package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

/** Client-side counterpart to the server's CoinRushMinigame (see minigames/coin_rush.py, once
 * built) -- Rune Party's first real mini-game, replacing one of the four PlaceholderMinigame
 * wheel slots (see Minigames). 2-4 coin spawns (object 29165, "Mounted Coins") appear on random
 * tiles at random points over a 30-second round (RunePartyPlugin#COIN_RUSH_DURATION_MS); whoever
 * physically reaches a spawn's tile first gets +2 coins for it (see RunePartyPlugin#
 * checkCoinRushCollection/ApiClient#collectCoinRushCoin) and it disappears, then another can spawn
 * elsewhere. Unlike PlaceholderMinigame's self-reported score spinner, there's nothing for the
 * player to actually operate here -- collection is automatic the instant they walk onto a live
 * spawn's tile -- so this control panel is purely a "here's what to do" reminder; the actual
 * gameplay renders in-world (TileOverlay#updateCoinRushModels) and the live per-round tally lives
 * in StatsOverlay's scoreboard, not here. Key must match the server's own CoinRushMinigame
 * registration exactly, see that file. */
public class CoinRushMinigame implements Minigame
{
    private static final Color COIN_COLOR = new Color(255, 215, 0);
    private static final Color COIN_OUTLINE = new Color(120, 90, 0);

    @Override
    public String getKey()
    {
        return "coin-rush";
    }

    @Override
    public String getDisplayName()
    {
        return "Coin Rush";
    }

    /** A small pile of overlapping gold coins -- distinct from CoinTrapItem's single trapped coin
     * and PlaceholderMinigame's plain gray glyph, reads as "grab the coins" at wheel-icon size. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));
        int r = Math.max(3, size / 5);

        int[][] offsets = { { -r, r / 2 }, { r, r / 2 }, { 0, -r / 2 } };
        for (int[] offset : offsets)
        {
            int cx = x + offset[0];
            int cy = y + offset[1];
            g.setColor(new Color(COIN_COLOR.getRed(), COIN_COLOR.getGreen(), COIN_COLOR.getBlue(), a));
            g.fillOval(cx - r, cy - r, r * 2, r * 2);
            g.setColor(new Color(COIN_OUTLINE.getRed(), COIN_OUTLINE.getGreen(), COIN_OUTLINE.getBlue(), a));
            g.drawOval(cx - r, cy - r, r * 2, r * 2);
        }

        Font font = FontManager.getRunescapeBoldFont().deriveFont((float) (size * 0.32));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String text = "+2";
        g.setColor(new Color(255, 255, 255, a));
        g.drawString(text, x - fm.stringWidth(text) / 2, y + size / 2 + fm.getAscent());
    }

    @Override
    public JComponent createControlPanel(RunePartyPlugin plugin)
    {
        JLabel hint = new JLabel("<html>Run to the coins as they appear -- first one there gets +2!</html>");
        hint.setForeground(Color.LIGHT_GRAY);
        hint.setFont(FontManager.getRunescapeSmallFont());

        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setOpaque(false);
        panel.add(hint);
        return panel;
    }
}
