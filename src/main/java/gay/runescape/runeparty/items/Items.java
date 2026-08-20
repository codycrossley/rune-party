package gay.runescape.runeparty.items;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Client-side item registry -- keyed the same way the server's own registry is (see
 * app.py/items' REGISTRY), so RunePartyPanel/AnnouncementOverlay can look up the right Item for
 * whatever key an ITEM_GRANTED payload carried, or the player's own inventory holds. Add a new
 * item by dropping an Item implementation in this package and registering it here.
 */
public final class Items
{
    // Registered 4 times under different keys, matching the server's own REGISTRY (see
    // items/__init__.py), purely so the wheel/inventory/use-flow have more than one option to
    // actually cycle through while real items are still being designed.
    private static final String FALLBACK_KEY = "placeholder-item-1";

    private static final Map<String, Item> REGISTRY = new LinkedHashMap<>();

    static
    {
        register(new EnergyPotionItem("energy-potion", "Energy Potion"));
        register(new CoinTrapItem("coin-trap", "Coin Trap"));
        register(new PlaceholderItem("placeholder-item-1", "Placeholder Item #1"));
        register(new PlaceholderItem("placeholder-item-2", "Placeholder Item #2"));
        register(new PlaceholderItem("placeholder-item-3", "Placeholder Item #3"));
        register(new PlaceholderItem("placeholder-item-4", "Placeholder Item #4"));
    }

    private static void register(Item item)
    {
        REGISTRY.put(item.getKey(), item);
    }

    /** Falls back to FALLBACK_KEY for an unrecognized or missing key -- e.g. a server holding an
     * item this client build was never updated to know about -- rather than leaving the wheel or
     * panel with nothing to show at all. */
    public static Item get(String key)
    {
        Item item = key != null ? REGISTRY.get(key) : null;
        return item != null ? item : REGISTRY.get(FALLBACK_KEY);
    }

    /** Every registered item, in registration order -- stable across calls, so the selection
     * wheel (see AnnouncementOverlay#renderItemSpinner) can assign each one a fixed wheel segment
     * index. */
    public static List<Item> all()
    {
        return Collections.unmodifiableList(new ArrayList<>(REGISTRY.values()));
    }

    private Items()
    {
    }
}
