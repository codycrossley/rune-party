package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import javax.swing.JComponent;
import javax.swing.JPanel;
import net.runelite.client.ui.FontManager;

/** Client-side counterpart to the server's TrueOrFalseMinigame (see minigames/true_or_false.py)
 * -- 5 rounds, 5 seconds each, one OSRS trivia question per round. An answer is a YES
 * ("True")/NO ("False") emote (see RunePartyPlugin#isLocalPlayerAwaitingTrueOrFalseAnswer/
 * answerTrueOrFalse), and the question, countdown, live "who's answered" tally, and per-round
 * reveal all render screen-centered in AnnouncementOverlay (see renderTrueOrFalseQuestion/
 * renderTrueOrFalseReveal) -- the same "Ready screen"-style treatment the mini-game ready-check
 * already uses -- so this mini-game has zero side-panel presence at all (see
 * hasSidePanelPresence), unlike Coin Rush's own plain "here's what to do" reminder there. Key
 * must match the server's own TrueOrFalseMinigame registration exactly, see that file. */
public class TrueOrFalseMinigame implements Minigame
{
    private static final Color CARD_COLOR = new Color(230, 230, 230);
    private static final Color CARD_OUTLINE = new Color(60, 60, 60);
    private static final Color TRUE_COLOR = new Color(80, 220, 80);
    private static final Color FALSE_COLOR = new Color(220, 70, 70);

    @Override
    public String getKey()
    {
        return "true-or-false";
    }

    @Override
    public String getDisplayName()
    {
        return "True or False";
    }

    /** A small card with a green "T" and a red "F" -- reads as "true/false quiz" at wheel-icon
     * size without needing a real model/image asset, same procedural-icon approach
     * CoinTrapItem/PlaceholderMinigame already use. */
    @Override
    public void drawIcon(Graphics2D g, int x, int y, int size, float alpha)
    {
        int half = size / 2;
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255)));

        g.setColor(new Color(CARD_COLOR.getRed(), CARD_COLOR.getGreen(), CARD_COLOR.getBlue(), a));
        g.fillRoundRect(x - half, y - half, size, size, size / 5, size / 5);
        g.setColor(new Color(CARD_OUTLINE.getRed(), CARD_OUTLINE.getGreen(), CARD_OUTLINE.getBlue(), a));
        g.drawRoundRect(x - half, y - half, size, size, size / 5, size / 5);

        Font font = FontManager.getRunescapeBoldFont().deriveFont((float) (size * 0.5));
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();

        int glyphY = y + fm.getAscent() / 2 - 2;

        String t = "T";
        int tX = x - half + size / 6;
        g.setColor(new Color(0, 0, 0, a));
        g.drawString(t, tX + 1, glyphY + 1);
        g.setColor(new Color(TRUE_COLOR.getRed(), TRUE_COLOR.getGreen(), TRUE_COLOR.getBlue(), a));
        g.drawString(t, tX, glyphY);

        String f = "F";
        int fX = x + half - size / 6 - fm.stringWidth(f);
        g.setColor(new Color(0, 0, 0, a));
        g.drawString(f, fX + 1, glyphY + 1);
        g.setColor(new Color(FALSE_COLOR.getRed(), FALSE_COLOR.getGreen(), FALSE_COLOR.getBlue(), a));
        g.drawString(f, fX, glyphY);
    }

    /** Entirely screen-driven -- the question, countdown, and "who's answered" tally all render
     * center-screen in AnnouncementOverlay (see renderTrueOrFalseQuestion/renderTrueOrFalseReveal),
     * so this mini-game has no side-panel presence at all (see hasSidePanelPresence) -- unlike
     * Coin Rush, which still shows a plain "here's what to do" reminder there. This is never
     * actually called as a result; kept minimal rather than unreachable purely to satisfy the
     * interface. */
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
