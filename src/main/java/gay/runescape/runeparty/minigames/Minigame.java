package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.WheelEntry;
import javax.swing.JComponent;

/** One entry in the client-side mini-game roster -- see Minigames for the registry that looks
 * these up by key, and RunePartyPanel for where createControlPanel's result gets shown. The client
 * uses the key to tell which mini-game the server picked and show the matching play UI. Each
 * implementation owns its own play UI/logic; results are always reported as a single int score.
 * Extends WheelEntry so AnnouncementOverlay's selection spinner can draw a mini-game the same way
 * it draws an Item. */
public interface Minigame extends WheelEntry
{
    /** Builds the interactive control(s) shown in the panel's mini-game section while this
     * mini-game is active. Called once each time this mini-game becomes active, not on every
     * repaint. Never invoked for a mini-game whose hasSidePanelPresence() is false. */
    JComponent createControlPanel(RunePartyPlugin plugin);

    /** Whether this mini-game's instructions and createControlPanel() result should appear in the
     * side panel while it's playable -- true by default. A mini-game that's entirely
     * screen-driven, rendered center-screen in AnnouncementOverlay instead, overrides this to
     * false rather than duplicating that same information in two places. */
    default boolean hasSidePanelPresence()
    {
        return true;
    }
}
