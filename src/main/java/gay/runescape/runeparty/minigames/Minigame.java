package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.WheelEntry;

/** One entry in the client-side mini-game roster -- see Minigames for the registry that looks
 * these up by key. The client uses the key to tell which mini-game the server picked and show the
 * matching play UI. Each implementation owns its own play UI/logic (rendered center-screen via
 * AnnouncementOverlay, not the side panel -- see ARCHITECTURE.md's "Best practices" #10); results
 * are always reported as a single int score. Extends WheelEntry so AnnouncementOverlay's
 * selection spinner can draw a mini-game the same way it draws an Item.
 * <p>
 * Used to also carry createControlPanel()/hasSidePanelPresence(), a side-panel control-panel slot
 * every mini-game could opt into -- removed 2026-09-18 once all 14 registered mini-games had opted
 * out except CoinRushMinigame, whose own panel was a purely static reminder text that
 * AnnouncementOverlay already shows on screen via the MINIGAME_STARTED instructions banner. See
 * docs/ARCHITECTURE_REVIEW.md's S7 for the fuller removal note. */
public interface Minigame extends WheelEntry
{
}
