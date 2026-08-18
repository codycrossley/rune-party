package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import net.runelite.client.ui.FontManager;

/** Client-side counterpart to the server's PlaceholderMinigame (see minigames/placeholder.py) --
 * a bare number spinner and a submit button, since the placeholder's own "gameplay" is just
 * self-reporting a score. Registered multiple times under different keys (see Minigames) purely
 * so the selection wheel/ready-check/etc. have more than one option to actually cycle through
 * while real mini-games are still being built -- every instance behaves identically, only
 * key/displayName (and the icon's glyph, the key's own last character) differ. Keys and display
 * names must match the server's own PlaceholderMinigame registrations exactly, see that file. */
public class PlaceholderMinigame implements Minigame
{
    private static final Color ICON_COLOR = new Color(120, 120, 120);

    private final String key;
    private final String displayName;

    public PlaceholderMinigame(String key, String displayName)
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

    /** An explicit, obvious placeholder -- a plain gray circle with a glyph (the key's own last
     * character, so the 4 registered instances read as distinct options on the wheel), standing
     * in until real mini-games (and their own real icons) replace these. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int r = size / 2;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));

        g.setColor(new Color(ICON_COLOR.getRed(), ICON_COLOR.getGreen(), ICON_COLOR.getBlue(), a));
        g.fillOval(x - r, y - r, size, size);
        g.setColor(new Color(255, 255, 255, a));
        g.drawOval(x - r, y - r, size, size);

        Font font = FontManager.getRunescapeBoldFont().deriveFont((float) (size * 0.55));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String text = key.substring(key.length() - 1);
        g.drawString(text, x - fm.stringWidth(text) / 2, y + fm.getAscent() / 2 - 2);
    }

    @Override
    public JComponent createControlPanel(RunePartyPlugin plugin)
    {
        JSpinner scoreSpinner = new JSpinner(new SpinnerNumberModel(0, 0, 999, 1));
        scoreSpinner.setMaximumSize(new Dimension(80, 24));

        JButton submitBtn = new JButton("Submit Result");
        submitBtn.addActionListener(e -> plugin.submitMinigameResult((Integer) scoreSpinner.getValue()));

        JPanel row = new JPanel(new BorderLayout(4, 0));
        row.setOpaque(false);
        row.add(scoreSpinner, BorderLayout.WEST);
        row.add(submitBtn, BorderLayout.CENTER);
        return row;
    }
}
