package gay.runescape.runeparty.items;

import gay.runescape.runeparty.KeyedRegistry;

import java.util.List;

/** Client-side item registry, keyed to match the server's own item keys, so RunePartyPanel/
 * AnnouncementOverlay can look up the right Item for whatever key an event payload carried. Add a
 * new item by dropping an Item implementation in this package and registering it here. */
public final class Items
{
    private static final KeyedRegistry<Item> REGISTRY = new KeyedRegistry<>("tele-block");

    static
    {
        REGISTRY.register(new EnergyPotionItem("energy-potion", "Energy Potion"));
        REGISTRY.register(new CoinTrapItem("coin-trap", "Coin Trap"));
        REGISTRY.register(new TeleBlockItem("tele-block", "Tele Block"));
        REGISTRY.register(new HomeTeleportItem("tele-home", "Home Teleport"));
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
