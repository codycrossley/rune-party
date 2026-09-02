package gay.runescape.runeparty;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Keeps one {@link RuneLiteObject} spawned per key in a caller-supplied "desired" set, diffing it
 * every call against whatever's already spawned -- the shared sync algorithm behind
 * GoldenGnomeModel/CoinTrapModel/CoinRushModel (see the models/ package): entrySet().removeIf
 * deactivates anything no longer wanted, computeIfAbsent spawns anything newly wanted (lazily
 * loading the model, retrying every call until it succeeds since {@code Client#loadModel} can
 * return null for a few frames right after the client starts), bails (deactivates) on a null
 * {@link LocalPoint} for a point not currently loaded into the scene, then sets location and
 * activates once a model's actually attached.
 * <p>
 * Deliberately doesn't try to own its callers' real differences -- any force-persist/suppress
 * windows are just extra entries the caller adds to/removes from {@code desired} before calling
 * {@link #sync}, and a one-shot animation is applied by the caller afterward via {@link #get}. */
public final class SceneObjectSet<K>
{
    private final Client client;
    private final Map<K, RuneLiteObject> objects = new HashMap<>();

    public SceneObjectSet(Client client)
    {
        this.client = client;
    }

    public void sync(Set<K> desired, Function<K, WorldPoint> pointOf, Function<K, Model> modelIfNeeded)
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
            if (obj.getModel() != null && !obj.isActive()) obj.setActive(true);
        }
    }

    public RuneLiteObject get(K key)
    {
        return objects.get(key);
    }

    public void clear()
    {
        for (RuneLiteObject obj : objects.values())
        {
            obj.setActive(false);
        }
        objects.clear();
    }
}
