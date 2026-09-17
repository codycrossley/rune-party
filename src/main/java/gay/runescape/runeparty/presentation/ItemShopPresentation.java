package gay.runescape.runeparty.presentation;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import gay.runescape.runeparty.items.Item;
import gay.runescape.runeparty.items.Items;
import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;

import java.util.Locale;

/** Item Shop encounter state and event handling -- structured the same way WiseOldManPresentation
 * is (extracted client-side state, folds its own event types via apply(), clears itself via
 * reset()). Same "pending decision blocks advancement, timer-driven rather than waiting
 * indefinitely" shape itemShopEncounterPending plays server-side. The actual dialogue (chathead,
 * greeting text, item menu) is ItemShopDialogueOverlay's own concern, not this class's -- this
 * only tracks whether an encounter is open, for whom, when its reveal is allowed to start, and the
 * outcome banner for a resolved purchase (successful or not). */
public final class ItemShopPresentation
{
    private final RunePartyPlugin plugin;

    // Real state, applied catch-up or not: non-null exactly while an encounter is outstanding --
    // gates whether the local dialogue box shows at all (only for the rsn actually mid-encounter).
    private volatile String encounterRsn = null;
    // Cosmetic-only: when the local client's own dialogue box is allowed to actually show -- same
    // "a fixed timestamp decided once, not re-checked every frame" reasoning
    // WiseOldManPresentation's own revealAt doc gives.
    private volatile long awakenedAt = 0;
    private volatile long revealAt = 0;

    // ---- outcome banner ("You/<rsn> purchased <item>!" / "You/<rsn> can't afford <item>!" /
    // "You/<rsn> can't afford any items!") -- fired the instant a purchase attempt actually
    // resolves (ITEM_SHOP_PURCHASED/ITEM_SHOP_PURCHASE_FAILED), or the player says "Yes" with
    // nothing in the catalog they can afford (ITEM_SHOP_NO_AFFORDABLE_ITEMS), shown to every
    // player, not just the one involved. Armed via plugin.armBanner (same queuing-behind-other-
    // reveals shape WiseOldManPresentation's own outcome banner uses). No banner at all for
    // "declined"/"timed_out" -- only a real purchase attempt (successful or not), or that same
    // "nothing to browse for" case, is announced. ----
    private final TimedBanner<OutcomePayload> outcome = new TimedBanner<>();

    public ItemShopPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.ITEM_SHOP_ENCOUNTER_OPENED:
            {
                encounterRsn = Json.requiredStr(e.payload, type, "player");
                if (!catchingUp)
                {
                    long now = System.currentTimeMillis();
                    // settleMs (0 for an Item Shop Tile landed on directly) is how long the real
                    // server-side response window waits before it actually starts counting down --
                    // see the server's own item_shop_encounter_opened doc. Folded in alongside the
                    // local turnEffectGateUntil, same WISE_OLD_MAN_ENCOUNTER_OPENED shape.
                    Integer settleMs = Json.safeInt(e.payload, "settleMs");
                    long serverIntendedStart = now + (settleMs != null ? settleMs : 0);
                    revealAt = Math.max(serverIntendedStart, plugin.getTurnEffectGateUntil());
                    awakenedAt = revealAt;
                }
                break;
            }

            case Events.ITEM_SHOP_PURCHASED:
            {
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    String itemDisplayName = Json.requiredStr(e.payload, type, "itemDisplayName");
                    Integer price = Json.safeInt(e.payload, "price");
                    plugin.armBanner(outcome, RunePartyPlugin.ITEM_SHOP_OUTCOME_BANNER_DURATION_MS,
                        () -> new OutcomePayload("purchased", rsn, itemDisplayName, price), true);
                }
                break;
            }

            case Events.ITEM_SHOP_PURCHASE_FAILED:
            {
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    String itemKey = Json.requiredStr(e.payload, type, "itemKey");
                    Item item = Items.get(itemKey);
                    String itemDisplayName = item != null ? item.getDisplayName() : itemKey;
                    plugin.armBanner(outcome, RunePartyPlugin.ITEM_SHOP_OUTCOME_BANNER_DURATION_MS,
                        () -> new OutcomePayload("failed", rsn, itemDisplayName, null), true);
                }
                break;
            }

            case Events.ITEM_SHOP_NO_AFFORDABLE_ITEMS:
            {
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    plugin.armBanner(outcome, RunePartyPlugin.ITEM_SHOP_OUTCOME_BANNER_DURATION_MS,
                        () -> new OutcomePayload("no_affordable_items", rsn, null, null), true);
                }
                break;
            }

            case Events.ITEM_SHOP_DISMISSED:
            {
                encounterRsn = null; // always clear, catch-up or not -- real state
                awakenedAt = 0;
                revealAt = 0;
                break;
            }

            default:
                break;
        }
    }

    public void reset()
    {
        encounterRsn = null;
        awakenedAt = 0;
        revealAt = 0;
        outcome.reset();
    }

    public String getEncounterRsn() { return encounterRsn; }
    public long getAwakenedAt() { return awakenedAt; }
    public long getRevealAt() { return revealAt; }

    public String getOutcome() { return outcome.payload != null ? outcome.payload.outcome : null; }
    public String getOutcomeRsn() { return outcome.payload != null ? outcome.payload.rsn : null; }
    public String getOutcomeItemDisplayName() { return outcome.payload != null ? outcome.payload.itemDisplayName : null; }
    public Integer getOutcomePrice() { return outcome.payload != null ? outcome.payload.price : null; }
    public long getOutcomeBannerUntil() { return outcome.until; }

    /** Payload for the "You/<rsn> purchased/can't afford <item>/can't afford any items!" outcome
     * banner -- see the ITEM_SHOP_PURCHASED/ITEM_SHOP_PURCHASE_FAILED/ITEM_SHOP_NO_AFFORDABLE_ITEMS
     * handlers above. outcome is "purchased", "failed", or "no_affordable_items"; price is only
     * meaningful for "purchased"; itemDisplayName is null for "no_affordable_items" (there's no
     * single item to name). */
    private static final class OutcomePayload
    {
        final String outcome;
        final String rsn;
        final String itemDisplayName;
        final Integer price;

        OutcomePayload(String outcome, String rsn, String itemDisplayName, Integer price)
        {
            this.outcome = outcome;
            this.rsn = rsn;
            this.itemDisplayName = itemDisplayName;
            this.price = price;
        }
    }
}
