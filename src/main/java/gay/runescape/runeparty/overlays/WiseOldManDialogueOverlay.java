package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Client;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.MouseManager;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** A conversation with the Wise Old Man, drawn over the chatbox in the game's own dialog style --
 * chathead, speaker name, greeting text, and clickable options -- for the mini-encounter opened by
 * landing on his tile (see the server's own wise_old_man_encounter_opened doc). Only ever shows
 * for the local player actually mid-encounter (see WiseOldManPresentation#getEncounterRsn) -- every
 * other seated client sees nothing of this box at all, only the eventual "X stole ... from Y!"
 * announcement once (if) it resolves as a steal (see AnnouncementOverlay#renderWiseOldManStolen).
 * All the chatbox chrome (background, chathead, name, click handling, reset-on-new-encounter
 * bookkeeping) lives in ChatboxDialogueOverlay -- see that class's own doc for the "drawn rather
 * than driven through the game's real dialog interface" reasoning and the ChatheadRenderer/
 * Follower Buddy provenance; this subclass supplies only the two cards below -- rebuilt to match
 * ItemShopDialogueOverlay's own simplified shape (a short greeting/choice card, then a
 * one-thing-at-a-time carousel for anything that's a genuine list, rather than a scrollable/
 * paginated menu of several rows at once).
 * <p>
 * ACTION_CHOICE: a fixed "What would you like to do?" prompt plus a plain row per action --
 * "Steal coins (free)", "Steal Golden Gnome (N coins)", "Never mind" -- always all three,
 * regardless of the local player's own affordability or whether anyone else currently holds a
 * gnome (see drawActionChoice's own doc: ineligibility is handled downstream instead, once a
 * target's actually being picked). Kept as plain rows rather than a carousel -- unlike a target
 * list or the Item Shop's own catalog, this is a small, fixed set of 3 where showing every option
 * at once is simpler than making the player click through them one at a time.
 * <p>
 * TARGET_SELECT: a carousel over every eligible other seated player, one name at a time --
 * "Steal coins from who?"/"Steal a Golden Gnome from who?" header, a blank line, the current
 * candidate's own name (click it to commit), a blank line, then "Next" (only shown when there's
 * more than one candidate to cycle to, wrapping back to the first past the last) and "Back" (to
 * ACTION_CHOICE, in case the player wants to reconsider between coins/gnome, or bail entirely --
 * kept here even though ItemShopDialogueOverlay's own carousel dropped its own "Back", since this
 * screen is reached via a real two-step action+target commitment rather than a flat item pick, and
 * the eligible candidate set can be empty for the coins action on a small roster). No pagination
 * math needed here at all (unlike this dialogue's own pre-rebuild shape, which could genuinely
 * overflow a page) -- exactly one candidate's worth of content is ever on screen. Submits via
 * RunePartyPlugin#submitWiseOldManChoice the instant a target (or "Never mind") is clicked, then
 * closes itself immediately rather than waiting for the server's own confirming
 * WISE_OLD_MAN_DISMISSED -- same "set optimistically on submit" latitude
 * goldenGnomePurchasedThisTurn's own doc describes. */
public final class WiseOldManDialogueOverlay extends ChatboxDialogueOverlay
{
    private enum Screen { ACTION_CHOICE, TARGET_SELECT }

    private volatile Screen screen = Screen.ACTION_CHOICE;
    private volatile String pendingActionChoice = null; // "steal_coins" | "steal_golden_gnome", set once picked on screen 1
    private volatile int targetIndex = 0; // index into this screen's own candidates list, wraps via Math.floorMod

    public WiseOldManDialogueOverlay(Client client, RunePartyPlugin plugin, MouseManager mouseManager, SpriteManager spriteManager, RosterReducer roster)
    {
        super(client, plugin, mouseManager, spriteManager, roster);
    }

    @Override
    protected String getEncounterRsn() { return plugin.getWiseOldManEncounterRsn(); }

    @Override
    protected long getRevealAt() { return plugin.getWiseOldManRevealAt(); }

    @Override
    protected int getNpcId() { return RunePartyPlugin.WISE_OLD_MAN_NPC_ID; }

    @Override
    protected String getSpeakerName() { return "Wise Old Man"; }

    @Override
    protected void resetForNextEncounter()
    {
        super.resetForNextEncounter();
        screen = Screen.ACTION_CHOICE;
        pendingActionChoice = null;
        targetIndex = 0;
    }

    @Override
    protected void drawBody(Graphics2D g, String self)
    {
        if (screen == Screen.ACTION_CHOICE)
        {
            drawActionChoice(g, self);
        }
        else
        {
            drawTargetCarousel(g);
        }
    }

    /** Always the same fixed prompt and both steal options -- unlike the pre-rebuild shape, this no
     * longer varies the greeting text or hides the Golden Gnome option based on the local player's
     * own affordability/whether anyone else currently holds a gnome (see this feature's own
     * confirmed design). Ineligibility is still handled downstream instead: an unaffordable/
     * nobody-eligible pick still reaches drawTargetCarousel, whose own "(nobody eligible)"
     * placeholder already covers the no-candidates case, and the server itself remains the real
     * authority on affordability (same "client offers it, server still re-checks for real"
     * reasoning WISE_OLD_MAN_GNOME_STEAL_COST's own doc already gives for the purchase flow). {@code
     * self} is unused now that this screen no longer reads the local player's own coin total, but
     * kept to match drawBody's own fixed override signature. */
    private void drawActionChoice(Graphics2D g, String self)
    {
        String greeting = "What would you like to do?";

        int textWidth = bounds.width - TEXT_LEFT - TEXT_RIGHT_MARGIN;
        int optionsTopOffset = Math.max(OPTIONS_TOP_OFFSET, drawWrappedText(g, greeting, bounds.x + TEXT_LEFT, textWidth));

        List<String> labels = new ArrayList<>();
        List<Runnable> callbacks = new ArrayList<>();

        labels.add("Steal coins (free)");
        callbacks.add(() -> beginTargetSelect("steal_coins"));

        labels.add("Steal Golden Gnome (" + RunePartyPlugin.WISE_OLD_MAN_GNOME_STEAL_COST + " coins)");
        callbacks.add(() -> beginTargetSelect("steal_golden_gnome"));

        labels.add("Never mind");
        callbacks.add(() -> submitFinalChoice("decline", null));

        drawOptionRows(g, labels, callbacks, bounds.y + optionsTopOffset);
    }

    private void beginTargetSelect(String action)
    {
        pendingActionChoice = action;
        screen = Screen.TARGET_SELECT;
        targetIndex = 0;
    }

    /** One eligible candidate at a time -- header, a blank line, the current candidate's own name
     * (click it to steal from them), a blank line, then "Next"/"Back" -- see this class's own doc
     * for why "Back" (unlike ItemShopDialogueOverlay's own carousel) is kept here. */
    private void drawTargetCarousel(Graphics2D g)
    {
        String self = plugin.getLocalRsn();
        boolean gnomeSteal = "steal_golden_gnome".equals(pendingActionChoice);

        List<String> candidates = new ArrayList<>();
        for (RosterReducer.RosterEntry p : roster.seatedPlayers())
        {
            if (self != null && p.rsn.equalsIgnoreCase(self)) continue;
            if (gnomeSteal && p.goldenGnomeCount <= 0) continue;
            candidates.add(p.rsn);
        }

        String header = gnomeSteal ? "Steal a Golden Gnome from who?" : "Steal coins from who?";
        int textWidth = bounds.width - TEXT_LEFT - TEXT_RIGHT_MARGIN;
        int headerBottomOffset = Math.max(OPTIONS_TOP_OFFSET, drawWrappedText(g, header, bounds.x + TEXT_LEFT, textWidth));

        int lineHeight = lineHeight(g);
        int ascent = ascent(g);
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();

        boolean hasCandidates = !candidates.isEmpty();
        String targetRsn = hasCandidates ? candidates.get(Math.floorMod(targetIndex, candidates.size())) : null;
        String nameLine = hasCandidates ? targetRsn : "(nobody eligible)";

        // One blank line's own worth of breathing room below the header, matching
        // ItemShopDialogueOverlay's own carousel spacing.
        int nameTop = bounds.y + headerBottomOffset + lineHeight;
        Rectangle nameRow = new Rectangle(bounds.x + TEXT_LEFT, nameTop - ascent, textWidth, lineHeight);
        boolean nameHovered = hasCandidates && mouse != null && nameRow.contains(mouse.getX(), mouse.getY());
        drawCentered(g, nameLine, bounds.x + TEXT_LEFT, textWidth, nameTop, nameHovered ? OPTION_HOVER_COLOR : OPTION_COLOR);

        List<Rectangle> rects = new ArrayList<>();
        List<Runnable> callbacks = new ArrayList<>();
        if (hasCandidates)
        {
            rects.add(nameRow);
            callbacks.add(() -> submitFinalChoice(pendingActionChoice, targetRsn));
        }

        // Another blank line before the trailer row(s), same spacing the header-to-name gap above uses.
        int trailerTop = nameTop + lineHeight * 2;

        if (candidates.size() > 1)
        {
            Rectangle nextRow = new Rectangle(bounds.x + TEXT_LEFT, trailerTop - ascent, textWidth, OPTION_ROW_HEIGHT);
            boolean nextHovered = mouse != null && nextRow.contains(mouse.getX(), mouse.getY());
            drawCentered(g, "Next", bounds.x + TEXT_LEFT, textWidth, trailerTop, nextHovered ? OPTION_HOVER_COLOR : OPTION_COLOR);
            rects.add(nextRow);
            callbacks.add(() -> targetIndex = Math.floorMod(targetIndex + 1, candidates.size()));
            trailerTop += OPTION_ROW_HEIGHT;
        }

        Rectangle backRow = new Rectangle(bounds.x + TEXT_LEFT, trailerTop - ascent, textWidth, OPTION_ROW_HEIGHT);
        boolean backHovered = mouse != null && backRow.contains(mouse.getX(), mouse.getY());
        drawCentered(g, "Back", bounds.x + TEXT_LEFT, textWidth, trailerTop, backHovered ? OPTION_HOVER_COLOR : OPTION_COLOR);
        rects.add(backRow);
        callbacks.add(() -> { screen = Screen.ACTION_CHOICE; pendingActionChoice = null; });

        optionBounds = rects.toArray(new Rectangle[0]);
        optionCallbacks = callbacks.toArray(new Runnable[0]);
    }

    private void submitFinalChoice(String action, String target)
    {
        submitted = true;
        plugin.submitWiseOldManChoice(action, target);
    }
}
