package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

/** One currently-revealed Rune Match card -- point plus which rune item to show there. A
 * top-level class so models/RuneMatchRuneModel, in a different package, can consume it, same
 * "top-level type shared across a package boundary" shape SandwichSpawn already uses.
 * <p>
 * itemId is a real OSRS item catalog id (e.g. 554 for a Fire rune), not a raw scenery/object model
 * id -- RuneMatchRuneModel resolves the actual model to load via
 * {@code client.getItemDefinition(itemId).getInventoryModel()} (see ItemComposition's own
 * getInventoryModel()), the same item-id-to-model-id indirection every item in the game goes
 * through, rather than a hand-verified raw model id the way SandwichSpawn's own ingredient models
 * use (those needed one because their in-world appearance is a distinct dropped-food model, not
 * their plain inventory icon). */
public final class RuneMatchSpawn
{
    public final WorldPoint point;
    public final int itemId;

    public RuneMatchSpawn(WorldPoint point, int itemId)
    {
        this.point = point;
        this.itemId = itemId;
    }
}
