package gay.runescape.runeparty.presentation;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;

import gay.runescape.runeparty.items.Items;
import net.runelite.api.coords.WorldPoint;

import java.util.Locale;

/** Item wheel/cap/used-announcement and Coin Trap trigger state and event handling, extracted out
 * of RunePartyPlugin. Owns its own fields, folds ITEM_GRANTED/ITEM_CAP_BLOCKED/
 * COIN_TRAP_TRIGGERED via apply(), and exposes handleItemUsed() for RunePartyPlugin's hybrid
 * ITEM_USED case to call (itemUsedThisTurn itself stays core, read directly by RunePartyPlugin).
 * Clears itself via reset(); RunePartyPlugin still exposes every getter under its original name,
 * just delegating here. */
public final class ItemPresentation
{
    private final RunePartyPlugin plugin;

    // ---- "ITEM SPACE!" banner (server-driven, everyone sees it -- see scheduleItemBanner), same
    // shape as MinigamePresentation's own minigameBanner/scheduleMinigameBanner: a pure title card
    // chained just ahead of the wheel below, staying up past its own reservation so it's still
    // visible above the wheel for the wheel's whole spin+reveal. ----
    private final TimedBanner<Void> itemBanner = new TimedBanner<>();
    // ---- item wheel reveal (cosmetic-only timing, chained behind whatever turn-effect is already
    // showing -- see scheduleItemSpinner). Payload identifies who got what, needed by the reveal
    // text ("You got..."/"<rsn> got..."). ----
    private final TimedBanner<ItemSpinnerPayload> itemSpinner = new TimedBanner<>();
    // ---- item effect description, chained right behind the wheel above -- see
    // scheduleItemGrantDescription. Same payload shape as itemSpinner (who got what), reused here
    // since AnnouncementOverlay needs the exact same rsn/itemKey to know whose item it's
    // describing and what to say about it. ----
    private final TimedBanner<ItemSpinnerPayload> itemGrantDescription = new TimedBanner<>();
    // ---- item cap announcement, chained the same way as the item spinner above. Fires instead of
    // the item wheel when ITEM_CAP_BLOCKED lands, so at most one of {itemSpinner.until,
    // itemCapBlocked.until} is ever "live" for the same landing. ----
    private final TimedBanner<ItemCapBlockedPayload> itemCapBlocked = new TimedBanner<>();
    // ---- item-used announcement, chained the same way as the item cap banner above. Only fired
    // for items that opt in via Item#hasUseAnnouncement -- PlaceholderItem's coin change already
    // has its own feedback. ----
    private final TimedBanner<ItemUsedAnnouncePayload> itemUsedAnnounce = new TimedBanner<>();
    // ---- Coin Trap trigger, chained the same way as the item-used announcement above. Payload is
    // whoever landed on it (the victim) -- the owner's own feedback is purely their coin popup. ----
    private final TimedBanner<String> coinTrapAnnounce = new TimedBanner<>(); // payload: victim rsn
    // Real-time (not chained behind scheduleAfterTurnEffects -- see COIN_TRAP_TRIGGERED handling's
    // own doc): where TileOverlay#updateCoinTrapModels should force-persist the model and fire its
    // spring animation for COIN_TRAP_TRIGGER_PERSIST_MS after the server's own TILE_UNMARKED would
    // otherwise have already made it vanish.
    private volatile WorldPoint coinTrapTriggerPoint = null;
    private volatile long coinTrapTriggerUntil = 0;

    public ItemPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.ITEM_GRANTED:
            {
                // Inventory itself is updated unconditionally by rosterReducer.apply above --
                // everything here is purely the wheel reveal's own cosmetics.
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    String itemKey = Json.requiredStr(e.payload, type, "itemKey");
                    String itemDisplayName = Json.requiredStr(e.payload, type, "itemDisplayName");
                    scheduleItemBanner(RunePartyPlugin.ITEM_SPINNER_DURATION_MS);
                    scheduleItemSpinner(rsn, itemKey);
                    scheduleItemGrantDescription(rsn, itemKey);
                    plugin.addChatMessage(rsn + " got " + itemDisplayName + "!");
                }
                break;
            }

            case Events.ITEM_CAP_BLOCKED:
            {
                // No inventory change -- the server refused to grant anything. Purely cosmetic,
                // same as ITEM_GRANTED's own reveal.
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    Integer cap = Json.requiredInt(e.payload, type, "itemCap");
                    scheduleItemBanner(0);
                    scheduleItemCapBlockedAnnouncement(rsn, cap != null ? cap : 0);
                    plugin.addChatMessage(rsn + " already has too many items!");
                }
                break;
            }

            case Events.COIN_TRAP_TRIGGERED:
            {
                // Real-time, not chained behind scheduleAfterTurnEffects -- unlike the announcement
                // banner below, the model/animation choreography is tied to a specific spot on the
                // board (see TileOverlay#updateCoinTrapModels), not the screen-centered UI other
                // "what happened" banners share a queue for, so delaying it to wait its turn would
                // just look like the trap sprang for no visible reason moments after the player
                // actually landed on it.
                if (!catchingUp)
                {
                    String victim = Json.requiredStr(e.payload, type, "player");
                    String owner = Json.requiredStr(e.payload, type, "owner");
                    Integer stolen = Json.requiredInt(e.payload, type, "stolen");
                    Integer x = Json.requiredInt(e.payload, type, "x");
                    Integer y = Json.requiredInt(e.payload, type, "y");
                    Integer plane = Json.requiredInt(e.payload, type, "plane");
                    if (x != null && y != null && plane != null)
                    {
                        coinTrapTriggerPoint = new WorldPoint(x, y, plane);
                        coinTrapTriggerUntil = System.currentTimeMillis() + RunePartyPlugin.COIN_TRAP_TRIGGER_PERSIST_MS;
                    }
                    if (victim != null)
                    {
                        scheduleCoinTrapTriggerAnnouncement(victim);
                        plugin.addChatMessage(victim + " landed on a Coin Trap" + (owner != null ? " set by " + owner : "")
                            + "! " + (stolen != null ? stolen : 0) + " coins stolen.");
                    }
                }
                break;
            }

            default:
                break;
        }
    }

    /** The cosmetic half of ITEM_USED -- RunePartyPlugin's hybrid case sets itemUsedThisTurn = true
     * itself (core turn state, read by isLocalPlayerReadyToRoll/isAwaitingSomeonesRoll) before
     * calling this for the rest: the "You/&lt;rsn&gt; used &lt;item&gt;!" banner, only fired for
     * items that opt in via Item#hasUseAnnouncement -- PlaceholderItem's coin change already has
     * its own feedback. */
    public void handleItemUsed(ApiClient.EventOut e, boolean catchingUp)
    {
        if (catchingUp) return;
        String type = e.type.toUpperCase(Locale.ROOT);
        String rsn = Json.requiredStr(e.payload, type, "player");
        String itemKey = Json.requiredStr(e.payload, type, "itemKey");
        if (Items.get(itemKey).hasUseAnnouncement())
        {
            scheduleItemUsedAnnouncement(rsn, itemKey);
        }
    }

    /** Schedules AnnouncementOverlay's "ITEM SPACE!" banner via scheduleAfterTurnEffects, same
     * "MINIGAME!" shape scheduleMinigameBanner uses -- see that method's own doc for why this
     * isn't an armBanner call. {@code lingerMs} is how much longer past its own
     * ITEM_BANNER_DURATION_MS gate reservation the banner keeps rendering, so it stays visible
     * over whatever comes next instead of vanishing the instant that reservation ends -- the item
     * wheel's own full ITEM_SPINNER_DURATION_MS spin+reveal on the ITEM_GRANTED path (called right
     * before scheduleItemSpinner, chaining the two through scheduleAfterTurnEffects' own
     * synchronous gate reservation), or 0 on the ITEM_CAP_BLOCKED path, which has no wheel to
     * linger over -- lingering there would just overlap the cap-blocked message that follows. */
    private void scheduleItemBanner(long lingerMs)
    {
        itemBanner.task = plugin.scheduleAfterTurnEffects(itemBanner.task, RunePartyPlugin.ITEM_BANNER_DURATION_MS, () ->
        {
            long now = System.currentTimeMillis();
            itemBanner.until = now + RunePartyPlugin.ITEM_BANNER_DURATION_MS + lingerMs;
            plugin.extendTurnEffectGate(now + RunePartyPlugin.ITEM_BANNER_DURATION_MS);
        });
    }

    /** Schedules AnnouncementOverlay's item wheel reveal via scheduleAfterTurnEffects, so it waits
     * behind the "ITEM SPACE!" banner (scheduleItemBanner, called right before this) instead of
     * both appearing at once. {@code rsn}/{@code itemKey} are captured here rather than read back
     * off some "current grant" field, since -- unlike the mini-game key, which stays put for the
     * whole mini-game -- an item grant is a one-off event with nothing else keeping track of it in
     * between. */
    private void scheduleItemSpinner(String rsn, String itemKey)
    {
        plugin.armBanner(itemSpinner, RunePartyPlugin.ITEM_SPINNER_DURATION_MS, () -> new ItemSpinnerPayload(rsn, itemKey), true);
    }

    /** Schedules AnnouncementOverlay's "what this item does" announcement via
     * scheduleAfterTurnEffects, so it waits behind the wheel (scheduleItemSpinner, called right
     * before this) instead of appearing before the wheel's even settled -- the item-flow
     * equivalent of the mini-game's own ready-check screen following its own selection spinner. */
    private void scheduleItemGrantDescription(String rsn, String itemKey)
    {
        plugin.armBanner(itemGrantDescription, RunePartyPlugin.ITEM_GRANT_DESCRIPTION_DURATION_MS, () -> new ItemSpinnerPayload(rsn, itemKey), true);
    }

    /** Schedules AnnouncementOverlay's "already have N items" announcement via
     * scheduleAfterTurnEffects -- fired instead of scheduleItemSpinner when ITEM_CAP_BLOCKED
     * lands, so it waits behind whatever turn-effect visual is already showing the same way the
     * item wheel itself would have. */
    private void scheduleItemCapBlockedAnnouncement(String rsn, int cap)
    {
        plugin.armBanner(itemCapBlocked, RunePartyPlugin.ITEM_CAP_BLOCKED_DURATION_MS, () -> new ItemCapBlockedPayload(rsn, cap), true);
    }

    /** Schedules AnnouncementOverlay's "You used/&lt;rsn&gt; used &lt;item&gt;!" banner via
     * scheduleAfterTurnEffects -- fired on ITEM_USED for whichever item opts in via
     * Item#hasUseAnnouncement (see handleItemUsed), so it waits behind whatever turn-effect visual
     * is already showing, same as scheduleItemCapBlockedAnnouncement. */
    private void scheduleItemUsedAnnouncement(String rsn, String itemKey)
    {
        plugin.armBanner(itemUsedAnnounce, RunePartyPlugin.ITEM_USED_ANNOUNCE_DURATION_MS, () -> new ItemUsedAnnouncePayload(rsn, itemKey), true);
    }

    /** Schedules AnnouncementOverlay's "You/&lt;rsn&gt; landed on a Coin Trap!" banner via
     * scheduleAfterTurnEffects -- fired on COIN_TRAP_TRIGGERED, same shape as
     * scheduleItemUsedAnnouncement. {@code victimRsn} is whoever landed on it, not the trap's
     * owner -- the owner's own feedback is purely their +N coin popup (see the COINS_CHANGED
     * handler), no banner of their own. */
    private void scheduleCoinTrapTriggerAnnouncement(String victimRsn)
    {
        plugin.armBanner(coinTrapAnnounce, RunePartyPlugin.COIN_TRAP_ANNOUNCE_DURATION_MS, () -> victimRsn, true);
    }

    public void reset()
    {
        itemBanner.reset();
        itemSpinner.reset();
        itemGrantDescription.reset();
        itemCapBlocked.reset();
        itemUsedAnnounce.reset();
        coinTrapAnnounce.reset();
        coinTrapTriggerPoint = null;
        coinTrapTriggerUntil = 0;
    }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under their original names ----
    public long getItemBannerUntil() { return itemBanner.until; }
    public long getItemSpinnerStart() { return itemSpinner.start; }
    public long getItemSpinnerUntil() { return itemSpinner.until; }
    public String getItemGrantRsn() { return itemSpinner.payload != null ? itemSpinner.payload.rsn : null; }
    public String getItemGrantKey() { return itemSpinner.payload != null ? itemSpinner.payload.itemKey : null; }
    public long getItemGrantDescriptionUntil() { return itemGrantDescription.until; }
    public String getItemGrantDescriptionRsn() { return itemGrantDescription.payload != null ? itemGrantDescription.payload.rsn : null; }
    public String getItemGrantDescriptionKey() { return itemGrantDescription.payload != null ? itemGrantDescription.payload.itemKey : null; }
    public long getItemCapBlockedUntil() { return itemCapBlocked.until; }
    public String getItemCapBlockedRsn() { return itemCapBlocked.payload != null ? itemCapBlocked.payload.rsn : null; }
    public int getItemCapBlockedCap() { return itemCapBlocked.payload != null ? itemCapBlocked.payload.cap : 0; }
    public long getItemUsedAnnounceUntil() { return itemUsedAnnounce.until; }
    public String getItemUsedAnnounceRsn() { return itemUsedAnnounce.payload != null ? itemUsedAnnounce.payload.rsn : null; }
    public String getItemUsedAnnounceItemKey() { return itemUsedAnnounce.payload != null ? itemUsedAnnounce.payload.itemKey : null; }
    public long getCoinTrapAnnounceUntil() { return coinTrapAnnounce.until; }
    public String getCoinTrapAnnounceRsn() { return coinTrapAnnounce.payload; }
    public WorldPoint getCoinTrapTriggerPoint() { return coinTrapTriggerPoint; }
    public long getCoinTrapTriggerUntil() { return coinTrapTriggerUntil; }

    /** Payload for the item wheel reveal and the grant-description banner right behind it -- see
     * scheduleItemSpinner/scheduleItemGrantDescription. */
    private static final class ItemSpinnerPayload
    {
        final String rsn;
        final String itemKey;

        ItemSpinnerPayload(String rsn, String itemKey)
        {
            this.rsn = rsn;
            this.itemKey = itemKey;
        }
    }

    /** Payload for the "already have N items" announcement -- see scheduleItemCapBlockedAnnouncement. */
    private static final class ItemCapBlockedPayload
    {
        final String rsn;
        final int cap;

        ItemCapBlockedPayload(String rsn, int cap)
        {
            this.rsn = rsn;
            this.cap = cap;
        }
    }

    /** Payload for the "You/&lt;rsn&gt; used &lt;item&gt;!" banner -- see scheduleItemUsedAnnouncement. */
    private static final class ItemUsedAnnouncePayload
    {
        final String rsn;
        final String itemKey;

        ItemUsedAnnouncePayload(String rsn, String itemKey)
        {
            this.rsn = rsn;
            this.itemKey = itemKey;
        }
    }
}
