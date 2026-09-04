package gay.runescape.runeparty.models;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.AnimationController;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;

/** Plays the Hot Potato's own detonation effect at a fixed world point -- ported from the
 * skwid-games plugin's own spawnDetonationSpotanim (same model/animation ids: a landmine
 * detonating there uses this exact effect), which solves "spawn a one-shot world-space explosion"
 * by spawning a real {@link RuneLiteObject} carrying the detonation model, driving it through the
 * detonation animation via {@link AnimationController}, and deactivating itself once that
 * animation finishes. Deliberately not the {@code triggerSpotAnimAtWorldPoint}/{@code
 * triggerSpotAnimOnPlayer} approach RunePartyPlugin already uses elsewhere (Tele Block's impact,
 * Golden Gnome's relocation) -- those only play a cache-declared spotanim graphic (or fake one via
 * a stationary projectile); this is a full custom model+animation pair, which needs its own
 * RuneLiteObject the way every other 3D decoration in this package already gets one. */
public final class HotPotatoExplosionModel
{
    private static final int DETONATION_MODEL_ID = 3960;
    private static final int DETONATION_ANIM_ID = 1230;

    private final Client client;
    private final List<RuneLiteObject> active = new ArrayList<>();

    public HotPotatoExplosionModel(Client client)
    {
        this.client = client;
    }

    /** Spawns a one-shot explosion at {@code point} -- must be called on the client thread. A
     * no-op if the detonation model isn't cached yet: unlike the recolored decorations elsewhere in
     * this package (which retry every frame until their own raw ModelData load succeeds, since
     * they're needed for as long as their tile/round is live), a missed explosion here is a single
     * cosmetic beat with nothing left to retry against once the moment's passed. */
    public void spawn(WorldPoint point)
    {
        Model model = client.loadModel(DETONATION_MODEL_ID);
        if (model == null) return;

        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
        if (lp == null) return;

        RuneLiteObject obj = client.createRuneLiteObject();
        obj.setModel(model);
        AnimationController controller = new AnimationController(client, DETONATION_ANIM_ID);
        controller.setOnFinished(c -> obj.setActive(false));
        obj.setAnimationController(controller);
        obj.setLocation(lp, point.getPlane());
        obj.setActive(true);
        active.add(obj);
    }

    /** Despawns and forgets every still-active explosion object -- a RuneLiteObject otherwise
     * stays registered with the client independently of this overlay or even the plugin being
     * active, same reasoning every other model in this package's own clear() gives. */
    public void clear()
    {
        for (RuneLiteObject obj : active) obj.setActive(false);
        active.clear();
    }
}
