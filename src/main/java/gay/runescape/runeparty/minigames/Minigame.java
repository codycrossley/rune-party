package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.WheelEntry;
import javax.swing.JComponent;

/** One entry in the client-side mini-game roster -- see Minigames for the registry that looks
 * these up by key, and RunePartyPanel for where createControlPanel's result actually gets shown.
 * Keyed the same way the server's own minigames package is (see app.py's Minigame ABC and its
 * REGISTRY), so the client can tell which mini-game MINIGAME_STARTED's payload picked (see
 * RunePartyPlugin#getMinigameKey) and show the matching play UI for it. Each implementation owns
 * its own play UI/logic; the wire protocol back to the server stays whatever
 * RunePartyPlugin#submitMinigameResult already sends (a single int score) -- a mini-game decides
 * what that number means and how the player produces it, not how it's transmitted. Extends
 * WheelEntry (getDisplayName/drawIcon) so AnnouncementOverlay's selection spinner can draw a
 * mini-game the same shared way it draws an Item -- see WheelEntry's own doc. */
public interface Minigame extends WheelEntry
{
    /** Builds the interactive control(s) shown in the panel's mini-game section while this
     * mini-game is active -- e.g. the placeholder's score spinner + submit button. Called once
     * each time this mini-game becomes active (see RunePartyPanel#refresh), not on every repaint,
     * so implementations are free to hold their own Swing state across that activation. Never
     * actually invoked for a mini-game whose own hasSidePanelPresence() is false -- RunePartyPanel
     * hides the whole instructions/control section before ever calling this, so an
     * implementation with nothing to show is free to leave this trivial (or unreachable) rather
     * than build a "here's what to do" placeholder no one will ever see. */
    JComponent createControlPanel(RunePartyPlugin plugin);

    /** Whether this mini-game's own instructions text and createControlPanel() result should
     * appear in the side panel at all while it's playable -- true by default (every mini-game
     * before this one wanted at least an instructions line, even Coin Rush's own "nothing to
     * click" reminder). A mini-game that's entirely screen-driven -- its own instructions,
     * countdown, and live status already rendered center-screen in AnnouncementOverlay, the same
     * "Ready screen"-style treatment a Golden Gnome offer or the ready-check itself already gets
     * with zero side-panel footprint of its own -- overrides this to false instead of duplicating
     * that same information in two places at once. See RunePartyPanel#refresh, the only reader. */
    default boolean hasSidePanelPresence()
    {
        return true;
    }
}
