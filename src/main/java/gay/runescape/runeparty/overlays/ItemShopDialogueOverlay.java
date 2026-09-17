package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyPlugin;

import gay.runescape.runeparty.items.Item;
import gay.runescape.runeparty.items.Items;

import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.client.game.SpriteManager;
import net.runelite.client.input.MouseManager;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

/** A conversation with the Item Clerk, drawn over the chatbox in the game's own dialog style --
 * chathead, speaker name, greeting text, and clickable options -- for the mini-encounter opened by
 * landing on his tile (see the server's own item_shop_encounter_opened doc). Only ever shows for
 * the local player actually mid-encounter (see ItemShopPresentation#getEncounterRsn) -- every
 * other seated client sees nothing of this box at all, only the eventual "You/&lt;rsn&gt;
 * purchased/can't afford &lt;item&gt;!" announcement (see AnnouncementOverlay#
 * renderItemShopOutcome). All the chatbox chrome (background, chathead, name, click handling,
 * reset-on-new-encounter bookkeeping) lives in ChatboxDialogueOverlay -- see that class's own doc;
 * this subclass supplies only the two screens below.
 * <p>
 * GREETING ("Would you like to buy an item?" / Yes / No) and ITEM_LIST ("Choose One", then a
 * one-item-at-a-time carousel over RunePartyPlugin#ITEM_SHOP_CATALOG -- every item, always,
 * regardless of whether the local player can currently afford it or has room to hold it, per this
 * feature's own confirmed V1 design -- rather than a scrollable/paginated list of several at once:
 * clicking the shown item's own name/description block buys it; "Next" advances to the next
 * catalog entry, wrapping back to the first past the last. These are deliberately the only two
 * screens (no "Back" out of ITEM_LIST -- declining only happens from GREETING, before committing
 * to browse) -- a real affordability/inventory-room check still happens server-side regardless
 * (see item_shop_choose), surfaced back to the player as the ITEM_SHOP_PURCHASE_FAILED banner
 * rather than being hidden from the menu the way an earlier version of this dialogue filtered it
 * client-side.
 * <p>
 * "Yes" is the one exception to "every item always shows" above: if the local player can't afford
 * a single catalog entry, clicking it doesn't open ITEM_LIST at all -- there'd be nothing there
 * they could actually buy -- it instead reports "cant_afford_any" (see submitCantAffordAny), which
 * announces "You/&lt;rsn&gt; can't afford any items!" to everyone and ends the encounter outright,
 * per this feature's own confirmed design. This check is a client-side nicety, same "server still
 * re-checks for real" latitude every other client-side affordability pre-check in this codebase
 * already takes (see item_shop_choose's own doc for the server-side re-check backing it).
 * <p>
 * "No" closes itself immediately rather than waiting for the server's own confirming
 * ITEM_SHOP_DISMISSED -- same "set optimistically on submit" latitude WiseOldManDialogueOverlay's
 * own doc describes, safe here since declining can't meaningfully fail. Buying an item (or hitting
 * "Yes" with nothing affordable) does NOT close optimistically, unlike an earlier version of this
 * dialogue -- both can genuinely fail/be rejected server-side (see item_shop_choose's own doc)
 * while leaving the server's own encounter open, and closing the box on this client regardless
 * left the player stuck with no visible dialogue at all for the full encounter timeout, unable to
 * tell the encounter was still pending. Instead this box simply mirrors the server's own real
 * encounter state the whole time -- see getEncounterRsn/getRevealAt (ChatboxDialogueOverlay's own
 * render() gate) -- closing itself only once ITEM_SHOP_DISMISSED genuinely lands, and staying open
 * with nothing extra needed on a rejection, since encounterRsn never actually cleared. See
 * submitPurchase/submitCantAffordAny's own docs for requestInFlight, which guards against a rapid
 * double-click firing two requests before the first one's own response lands. */
public final class ItemShopDialogueOverlay extends ChatboxDialogueOverlay
{
    private enum Screen { GREETING, ITEM_LIST }

    private volatile Screen screen = Screen.GREETING;
    private volatile int itemIndex = 0; // index into RunePartyPlugin.ITEM_SHOP_CATALOG, wraps via Math.floorMod
    // True from the instant a "buy_item" or "cant_afford_any" request goes out until its response
    // (success or failure) comes back -- see submitPurchase/submitCantAffordAny's own docs.
    // Cleared from RunePartyPlugin's own background executor thread (submitItemShopChoice's
    // onComplete callback), not the client thread -- fine for a plain volatile flag flip, same
    // cross-thread latitude pendingClick's own doc describes.
    private volatile boolean requestInFlight = false;

    public ItemShopDialogueOverlay(Client client, RunePartyPlugin plugin, MouseManager mouseManager, SpriteManager spriteManager, RosterReducer roster)
    {
        super(client, plugin, mouseManager, spriteManager, roster);
    }

    @Override
    protected String getEncounterRsn() { return plugin.getItemShopEncounterRsn(); }

    @Override
    protected long getRevealAt() { return plugin.getItemShopRevealAt(); }

    @Override
    protected int getNpcId() { return RunePartyPlugin.ITEM_SHOP_NPC_ID; }

    @Override
    protected String getSpeakerName() { return "Item Clerk"; }

    /** The Item Clerk's own in-world model is recolored by hand (see ItemShopNpcOverlay's own
     * RECOLOR_FIND/RECOLOR_REPLACE doc) -- his chathead portrait needs the exact same recolor
     * applied via that class's shared applyRecolor helper, or the dialogue box would show him in
     * his stock colors while the model standing on the tile shows the recolored ones. */
    @Override
    protected Model loadChatheadModel()
    {
        ModelData raw = RunePartyRender.loadNpcChatheadModelData(client, getNpcId());
        if (raw == null) return null;
        return ItemShopNpcOverlay.applyRecolor(raw).light();
    }

    @Override
    protected void resetForNextEncounter()
    {
        super.resetForNextEncounter();
        screen = Screen.GREETING;
        itemIndex = 0;
        requestInFlight = false;
    }

    @Override
    protected void drawBody(Graphics2D g, String self)
    {
        if (screen == Screen.GREETING)
        {
            drawGreeting(g);
        }
        else
        {
            drawItemCarousel(g);
        }
    }

    private void drawGreeting(Graphics2D g)
    {
        int textWidth = bounds.width - TEXT_LEFT - TEXT_RIGHT_MARGIN;
        int optionsTopOffset = Math.max(OPTIONS_TOP_OFFSET,
            drawWrappedText(g, "Would you like to buy an item?", bounds.x + TEXT_LEFT, textWidth));

        List<String> labels = new ArrayList<>();
        List<Runnable> callbacks = new ArrayList<>();

        labels.add("Yes");
        callbacks.add(this::handleYes);

        labels.add("No");
        callbacks.add(this::submitDecline);

        drawOptionRows(g, labels, callbacks, bounds.y + optionsTopOffset);
    }

    /** One catalog entry at a time -- "Choose One" header, a blank line, the current item's own
     * name/price line and effect-description line right below it (together one clickable block --
     * click either line to buy), another blank line, then "Next" to cycle forward (wrapping back
     * to the first entry past the last). No pagination math needed here at all (unlike
     * WiseOldManDialogueOverlay's own target list, which can genuinely overflow a page) -- exactly
     * one item's worth of content is ever on screen, comfortably inside the box regardless of the
     * real widget's own height. */
    private void drawItemCarousel(Graphics2D g)
    {
        int textWidth = bounds.width - TEXT_LEFT - TEXT_RIGHT_MARGIN;
        int headerBottomOffset = Math.max(OPTIONS_TOP_OFFSET, drawWrappedText(g, "Choose One", bounds.x + TEXT_LEFT, textWidth));

        List<RunePartyPlugin.ItemShopEntry> catalog = RunePartyPlugin.ITEM_SHOP_CATALOG;
        RunePartyPlugin.ItemShopEntry entry = catalog.get(Math.floorMod(itemIndex, catalog.size()));
        Item item = Items.get(entry.itemKey);
        String nameLine = (item != null ? item.getDisplayName() : entry.itemKey) + ": " + entry.price + " coins";
        String descriptionLine = item != null ? item.getEffectDescription(true) : "";

        int lineHeight = lineHeight(g);
        int ascent = ascent(g);
        net.runelite.api.Point mouse = client.getMouseCanvasPosition();

        // A visually distinct break below the header, matching the mock's own blank line between
        // "Choose One" and the item block -- see CAROUSEL_BLANK_LINE_GAP's own doc for why this
        // isn't a full lineHeight: headerBottomOffset already carries drawWrappedText's own
        // ascent(g)+BODY_OPTIONS_GAP clearance, so stacking a second full line on top of that
        // double-counts it.
        int itemTop = bounds.y + headerBottomOffset + CAROUSEL_BLANK_LINE_GAP;
        Rectangle itemRow = new Rectangle(bounds.x + TEXT_LEFT, itemTop - ascent, textWidth, lineHeight * 2);
        boolean itemHovered = mouse != null && itemRow.contains(mouse.getX(), mouse.getY());
        Color itemColor = itemHovered ? OPTION_HOVER_COLOR : OPTION_COLOR;
        drawCentered(g, nameLine, bounds.x + TEXT_LEFT, textWidth, itemTop, itemColor);
        drawCentered(g, descriptionLine, bounds.x + TEXT_LEFT, textWidth, itemTop + lineHeight, itemColor);

        // Same break before "Next" -- lineHeight * 2 here is the item block's own real height (two
        // drawn lines), not a spacing gap, so it stays as-is; only the gap itself uses the smaller
        // constant.
        int nextTop = itemTop + lineHeight * 2 + CAROUSEL_BLANK_LINE_GAP;
        Rectangle nextRow = new Rectangle(bounds.x + TEXT_LEFT, nextTop - ascent, textWidth, OPTION_ROW_HEIGHT);
        boolean nextHovered = mouse != null && nextRow.contains(mouse.getX(), mouse.getY());
        drawCentered(g, "Next", bounds.x + TEXT_LEFT, textWidth, nextTop, nextHovered ? OPTION_HOVER_COLOR : OPTION_COLOR);

        optionBounds = new Rectangle[] {itemRow, nextRow};
        optionCallbacks = new Runnable[] {
            () -> submitPurchase(entry.itemKey),
            () -> itemIndex = Math.floorMod(itemIndex + 1, catalog.size()),
        };
    }

    /** Closes optimistically, same latitude WiseOldManDialogueOverlay's own submitFinalChoice
     * takes -- declining can't meaningfully fail server-side, so there's nothing to stay open
     * for. */
    private void submitDecline()
    {
        submitted = true;
        plugin.submitItemShopChoice("decline", null, null);
    }

    /** Deliberately does NOT set {@code submitted} -- see this class's own doc for why closing
     * optimistically here was the actual bug: a failed purchase leaves the server's own encounter
     * genuinely open, and this box now simply mirrors that real state instead of assuming success.
     * requestInFlight guards the one real risk that opens up by not closing immediately -- a rapid
     * double-click landing before the first request's own response comes back, which could
     * otherwise fire two separate purchases (and pay for the item twice) instead of one. Cleared
     * via submitItemShopChoice's own onComplete callback regardless of how the request resolves. */
    private void submitPurchase(String itemKey)
    {
        if (requestInFlight) return;
        requestInFlight = true;
        plugin.submitItemShopChoice("buy_item", itemKey, () -> requestInFlight = false);
    }

    /** "Yes" itself never leaves the client -- this is a plain local pre-check (does the local
     * player's own coin total, per RosterReducer, cover ANY catalog entry's own price) deciding
     * whether to advance to ITEM_LIST at all, same "server still re-checks for real" latitude this
     * class's own doc describes. Affordable: just flips the screen, no server round-trip needed,
     * exactly as before. Not affordable: reports "cant_afford_any" instead (see
     * submitCantAffordAny) rather than opening a screen that would only ever show items the
     * player can't buy. */
    private void handleYes()
    {
        int coins = roster.getCoins(plugin.getLocalRsn());
        boolean canAffordAnything = RunePartyPlugin.ITEM_SHOP_CATALOG.stream().anyMatch(entry -> entry.price <= coins);
        if (canAffordAnything)
        {
            screen = Screen.ITEM_LIST;
            itemIndex = 0;
        }
        else
        {
            submitCantAffordAny();
        }
    }

    /** Same "does NOT set submitted, requestInFlight guards a double-fire" shape submitPurchase
     * uses -- see that method's own doc and this class's own doc for why. Ends the encounter
     * outright on success (see item_shop_choose's own "cant_afford_any" doc), so this box closes
     * itself the instant the real ITEM_SHOP_DISMISSED lands, same as any other outcome. */
    private void submitCantAffordAny()
    {
        if (requestInFlight) return;
        requestInFlight = true;
        plugin.submitItemShopChoice("cant_afford_any", null, () -> requestInFlight = false);
    }
}
