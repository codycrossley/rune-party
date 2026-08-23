package gay.runescape.runeparty.items;

import gay.runescape.runeparty.KeyedRegistry;

import java.util.List;

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
    private static final KeyedRegistry<Item> REGISTRY = new KeyedRegistry<>("placeholder-item-1");

    static
    {
        REGISTRY.register(new EnergyPotionItem("energy-potion", "Energy Potion"));
        REGISTRY.register(new CoinTrapItem("coin-trap", "Coin Trap"));
        REGISTRY.register(new PlaceholderItem("placeholder-item-1", "Placeholder Item #1"));
        REGISTRY.register(new PlaceholderItem("placeholder-item-2", "Placeholder Item #2"));
        REGISTRY.register(new PlaceholderItem("placeholder-item-3", "Placeholder Item #3"));
        REGISTRY.register(new PlaceholderItem("placeholder-item-4", "Placeholder Item #4"));
    }

    public static Item get(String key)
    {
        return REGISTRY.get(key);
    }

    public static List<Item> all()
    {
        return REGISTRY.all();
    }

    private Items()
    {
    }
}
