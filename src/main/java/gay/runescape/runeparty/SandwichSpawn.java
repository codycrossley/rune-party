package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

/** One currently-live Sandwich Rush ingredient spawn -- point plus which ingredient it is. A
 * top-level class so models/SandwichItemModel, in a different package, can consume it. */
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
