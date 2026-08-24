package gay.runescape.runeparty;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Keeps one {@link RuneLiteObject} spawned per key in a caller-supplied "desired" set, diffing it
 * every call against whatever's already spawned -- the sync algorithm TileOverlay's
 * updateGoldenGnomeModels/updateCoinTrapModels/updateCoinRushModels each independently reimplemented
 * (see ARCHITECTURE_REVIEW.md's C5): entrySet().removeIf deactivating anything no longer wanted,
 * computeIfAbsent spawning anything newly wanted, lazily loading the model (retrying every call
 * until it succeeds, since {@code Client#loadModel} can return null for a few frames right after the
 * client starts), bailing (deactivating) on a null {@link LocalPoint} for a point not currently
 * loaded into the scene, then setLocation and setActive(true) once a model's actually attached.
 * <p>
 * Deliberately doesn't try to own the three callers' real differences -- the force-persist/suppress
 * windows (Golden Gnome relocation, Coin Trap's post-trigger linger) are just extra entries the
 * caller adds to/removes from {@code desired} before calling {@link #sync}, and Coin Trap's one-shot
 * spring animation is applied by the caller afterward via {@link #get}, not folded in here. */
final class SceneObjectSet<K>
{
    private final Client client;
    private final Map<K, RuneLiteObject> objects = new HashMap<>();

    SceneObjectSet(Client client)
    {
        this.client = client;
    }

    void sync(Set<K> desired, Function<K, WorldPoint> pointOf, Function<K, Model> modelIfNeeded)
    {
        sync(desired, pointOf, modelIfNeeded, k -> { });
    }

    /** Same as {@link #sync(Set, Function, Function)}, plus {@code onActivated} fires the moment a
     * key's object transitions from inactive to active (not on every call while it stays active) --
     * only updateGoldenGnomeModels needs this, for its "RuneLiteObject activated at {}" debug log. */
    void sync(Set<K> desired, Function<K, WorldPoint> pointOf, Function<K, Model> modelIfNeeded, Consumer<K> onActivated)
    {
        objects.entrySet().removeIf(e ->
        {
            if (desired.contains(e.getKey())) return false;
            e.getValue().setActive(false);
            return true;
        });

        for (K key : desired)
        {
            RuneLiteObject obj = objects.computeIfAbsent(key, k -> client.createRuneLiteObject());

            if (obj.getModel() == null)
            {
                Model model = modelIfNeeded.apply(key);
                if (model != null) obj.setModel(model);
            }

            WorldPoint point = pointOf.apply(key);
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
            if (lp == null)
            {
                obj.setActive(false);
                continue;
            }

            obj.setLocation(lp, point.getPlane());
            if (obj.getModel() != null && !obj.isActive())
            {
                obj.setActive(true);
                onActivated.accept(key);
            }
        }
    }

    RuneLiteObject get(K key)
    {
        return objects.get(key);
    }

    void clear()
    {
        for (RuneLiteObject obj : objects.values())
        {
            obj.setActive(false);
        }
        objects.clear();
    }
}
