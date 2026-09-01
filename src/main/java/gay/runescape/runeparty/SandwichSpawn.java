package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

/** One currently-live Sandwich Rush ingredient spawn -- point plus which ingredient it is (see
 * RunePartyPlugin#SANDWICH_RUSH_ITEM_MODEL_IDS/SANDWICH_RUSH_ITEM_ICON_RESOURCES, keyed the same
 * way). A top-level class (not nested in MinigamePresentation, which is package-private) purely
 * so models/SandwichItemModel -- a different package -- can consume it, same reason CoinRushModel
 * gets away with a plain WorldPoint instead: a coin spawn has nothing but a position, this one
 * also needs to say which of the 4 ingredients it is. */
public final class SandwichSpawn
{
    public final WorldPoint point;
    public final String item;

    public SandwichSpawn(WorldPoint point, String item)
    {
        this.point = point;
        this.item = item;
    }
}
