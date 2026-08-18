package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import java.awt.Graphics2D;
import javax.swing.JComponent;

/** One entry in the client-side mini-game roster -- see Minigames for the registry that looks
 * these up by key, and RunePartyPanel for where createControlPanel's result actually gets shown.
 * Keyed the same way the server's own minigames package is (see app.py's Minigame ABC and its
 * REGISTRY), so the client can tell which mini-game MINIGAME_STARTED's payload picked (see
 * RunePartyPlugin#getMinigameKey) and show the matching play UI for it. Each implementation owns
 * its own play UI/logic; the wire protocol back to the server stays whatever
 * RunePartyPlugin#submitMinigameResult already sends (a single int score) -- a mini-game decides
 * what that number means and how the player produces it, not how it's transmitted. */
public interface Minigame
{
    /** Must match the "key" MINIGAME_STARTED's payload carries for this mini-game -- the same
     * string the matching server-side Minigame subclass sets as its own `key` class attribute
     * (see app.py/minigames). */
    String getKey();

    /** Shown when AnnouncementOverlay's selection spinner lands on this mini-game -- must match
     * the server-side Minigame subclass's own `display_name` (see app.py/minigames). */
    String getDisplayName();

    /** Draws this mini-game's icon centered at (x, y) at roughly `size` pixels across -- used by
     * the selection spinner's wheel segments. Purely programmatic (no image assets), same as the
     * rest of this codebase's icon-ish drawing (the retro die faces, the Golden Gnome map marker
     * triangle). */
    void drawIcon(Graphics2D g, int x, int y, int size, float alpha);

    /** Builds the interactive control(s) shown in the panel's mini-game section while this
     * mini-game is active -- e.g. the placeholder's score spinner + submit button. Called once
     * each time this mini-game becomes active (see RunePartyPanel#refresh), not on every repaint,
     * so implementations are free to hold their own Swing state across that activation. */
    JComponent createControlPanel(RunePartyPlugin plugin);
}
