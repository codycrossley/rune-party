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
 * client-side. Submits via RunePartyPlugin#submitItemShopChoice the instant an item (or "No") is
 * clicked, then closes itself immediately rather than waiting for the server's own confirming
 * ITEM_SHOP_DISMISSED -- same "set optimistically on submit" latitude WiseOldManDialogueOverlay's
 * own doc describes. A failed purchase does NOT submit-and-close -- the dialogue stays open on
 * ITEM_LIST, still showing the same item, so the player can hit "Next" and try something else. */
public final class ItemShopDialogueOverlay extends ChatboxDialogueOverlay
{
    private enum Screen { GREETING, ITEM_LIST }

    private volatile Screen screen = Screen.GREETING;
    private volatile int itemIndex = 0; // index into RunePartyPlugin.ITEM_SHOP_CATALOG, wraps via Math.floorMod

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
        callbacks.add(() -> { screen = Screen.ITEM_LIST; itemIndex = 0; });

        labels.add("No");
        callbacks.add(() -> submitFinalChoice("decline", null));

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

        // One blank line's own worth of breathing room below the header, matching the mock's own
        // blank line between "Choose One" and the item block.
        int itemTop = bounds.y + headerBottomOffset + lineHeight;
        Rectangle itemRow = new Rectangle(bounds.x + TEXT_LEFT, itemTop - ascent, textWidth, lineHeight * 2);
        boolean itemHovered = mouse != null && itemRow.contains(mouse.getX(), mouse.getY());
        Color itemColor = itemHovered ? OPTION_HOVER_COLOR : OPTION_COLOR;
        drawCentered(g, nameLine, bounds.x + TEXT_LEFT, textWidth, itemTop, itemColor);
        drawCentered(g, descriptionLine, bounds.x + TEXT_LEFT, textWidth, itemTop + lineHeight, itemColor);

        // Another blank line before "Next", same spacing the header-to-item gap above uses.
        int nextTop = itemTop + lineHeight * 2 + lineHeight;
        Rectangle nextRow = new Rectangle(bounds.x + TEXT_LEFT, nextTop - ascent, textWidth, OPTION_ROW_HEIGHT);
        boolean nextHovered = mouse != null && nextRow.contains(mouse.getX(), mouse.getY());
        drawCentered(g, "Next", bounds.x + TEXT_LEFT, textWidth, nextTop, nextHovered ? OPTION_HOVER_COLOR : OPTION_COLOR);

        optionBounds = new Rectangle[] {itemRow, nextRow};
        optionCallbacks = new Runnable[] {
            () -> submitFinalChoice("buy_item", entry.itemKey),
            () -> itemIndex = Math.floorMod(itemIndex + 1, catalog.size()),
        };
    }

    private void submitFinalChoice(String action, String itemKey)
    {
        submitted = true;
        plugin.submitItemShopChoice(action, itemKey);
    }
}
