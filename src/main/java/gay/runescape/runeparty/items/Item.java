package gay.runescape.runeparty.items;

import gay.runescape.runeparty.WheelEntry;

/** One entry in the client-side item roster -- see Items for the registry that looks these up by
 * key. Keyed the same way the server's own items package is (see app.py's Item ABC and its
 * REGISTRY), so the client can tell which item ITEM_GRANTED's payload carried. Extends WheelEntry
 * (getDisplayName/drawIcon) so AnnouncementOverlay's selection spinner can draw an item the same
 * shared way it draws a Minigame -- see WheelEntry's own doc. Unlike Minigame, there's no
 * createControlPanel here: an item has no play UI of its own, just an effect that fires the
 * instant it's used (see app.py's use-item endpoint and RunePartyPlugin#useItem). */
public interface Item extends WheelEntry
{
    /** Must match the "itemKey"/"key" ITEM_GRANTED's payload carries for this item -- the same
     * string the matching server-side Item subclass sets as its own `key` attribute (see
     * app.py/items). */
    String getKey();

    /** Whether AnnouncementOverlay should show a "You used/&lt;rsn&gt; used &lt;name&gt;!" banner
     * (see renderItemUsedAnnouncement) the moment this item's ITEM_USED lands. False by default --
     * PlaceholderItem's coin change already gets its own feedback from the coin popup, so most
     * items don't need a second banner on top of that. */
    default boolean hasUseAnnouncement()
    {
        return false;
    }

    /** The banner's subtitle, phrased for the given viewer ("your"/"their") -- only ever read when
     * hasUseAnnouncement() is true. */
    default String getUseAnnouncementSubtitle(boolean isLocalPlayer)
    {
        return null;
    }
}
