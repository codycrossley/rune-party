package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

/** One entry in the client-side item roster -- see Items for the registry that looks these up by
 * key, matching the server's own item keys so the client can tell which item ITEM_GRANTED's
 * payload carried. Extends WheelEntry so AnnouncementOverlay's selection spinner can draw an item
 * the same way it draws a Minigame. Unlike Minigame, there's no createControlPanel here: an item
 * has no play UI, just an effect that fires the instant it's used. */
public interface Item extends WheelEntry
{
    /** Short description of what this item does, shown right after it's granted from an Item
     * Space (see AnnouncementOverlay#renderItemGrantDescription) -- phrased for the given viewer
     * ("your"/"their"). Null skips the subtitle entirely. */
    default String getEffectDescription(boolean isLocalPlayer)
    {
        return null;
    }

    /** Whether AnnouncementOverlay should show a "You used/&lt;rsn&gt; used &lt;name&gt;!" banner
     * the moment this item's ITEM_USED lands. False by default -- most items already get feedback
     * from the coin popup, so most don't need a second banner. */
    default boolean hasUseAnnouncement()
    {
        return false;
    }

    /** The banner's subtitle, phrased for the given viewer ("your"/"their") -- only read when
     * hasUseAnnouncement() is true. */
    default String getUseAnnouncementSubtitle(boolean isLocalPlayer)
    {
        return null;
    }

    /** The banner's verb -- "You &lt;verb&gt; &lt;name&gt;!" -- only read when
     * hasUseAnnouncement() is true. "Used" fits an instant-effect item; a placement item like
     * CoinTrapItem overrides this to "placed". */
    default String getUseAnnounceVerb()
    {
        return "used";
    }

    /** True for an item spent by placing it on a tile (see CoinTrapItem) rather than by an instant
     * effect -- RunePartyPanel's "Use"/"Place" button routes one of these to beginItemPlacement
     * instead of useItem. False by default. */
    default boolean requiresPlacement()
    {
        return false;
    }

    /** True for an item spent on another player rather than the user themselves (see
     * TeleBlockItem) -- RunePartyPanel's "Use" button routes one of these to beginItemTargeting
     * instead of useItem. Targeting is confirmed by right-clicking another seated player's
     * in-world model. False by default. */
    default boolean requiresTarget()
    {
        return false;
    }
}
