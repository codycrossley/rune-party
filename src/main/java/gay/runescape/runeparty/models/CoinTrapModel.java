package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;
import gay.runescape.runeparty.TileReducer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;

/** Same "diff the live set against currently-spawned RuneLiteObjects" shape as GoldenGnomeModel,
 * for Coin Trap tiles -- fully recolored gold rather than left in its own natural rope/wood
 * palette (see {@link #buildGoldModel}). A Coin Trap never relocates, it just disappears for good
 * once triggered, so there's only one force-persist window to honor here (via
 * RunePartyPlugin#getCoinTrapTriggerPoint/Until), not the two-sided old/new choreography a Golden
 * Gnome relocation needs. That same window is also when the object's own spring animation actually
 * plays -- fired once per trigger, guarded by animatedTriggerPoint so it doesn't re-arm every
 * frame the window stays open. */
public final class CoinTrapModel
{
    private static final int COIN_TRAP_MODEL_ID = 19934;
    // "Gold" web color -- a plain, unambiguous coin-yellow reference. JagexColor#rgbToHSL's own
    // brightnessFactor parameter (1.0 = no adjustment) converts it into the packed-HSL space every
    // model color is actually stored in.
    private static final int COIN_TRAP_GOLD_RGB = 0xFFD700;
    // Played once (not looped) the moment a trap actually triggers, not while it's just sitting
    // armed.
    private static final int COIN_TRAP_SPRING_ANIMATION_ID = 5268;

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<WorldPoint> objects;

    // The one point (if any) currently mid-trigger-animation, so update() only calls setAnimation
    // once per trigger rather than re-arming it every single frame the force-persist window
    // (RunePartyPlugin#getCoinTrapTriggerUntil) stays open.
    private WorldPoint animatedTriggerPoint = null;

    // Built once, lazily, the first time COIN_TRAP_MODEL_ID's raw ModelData successfully loads --
    // see buildGoldModel, the only writer. Every currently-spawned Coin Trap RuneLiteObject shares
    // this exact same recolored Model instance. Null until the load succeeds (and forever, if it
    // never does), in which case update() falls back to the model's own natural palette.
    private Model goldModel;
    private boolean goldModelLoadFailed;

    public CoinTrapModel(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        this.objects = new SceneObjectSet<>(client);
    }

    public void update(List<TileReducer.TileEntry> entries)
    {
        Set<WorldPoint> current = new HashSet<>();
        for (TileReducer.TileEntry entry : entries)
        {
            if ("COIN_TRAP_TILE".equals(entry.tileType)) current.add(entry.point);
        }

        long now = System.currentTimeMillis();
        WorldPoint triggerPoint = plugin.getCoinTrapTriggerPoint();
        boolean triggerActive = triggerPoint != null && now < plugin.getCoinTrapTriggerUntil();
        if (triggerActive)
        {
            current.add(triggerPoint);
        }
        else
        {
            animatedTriggerPoint = null; // window closed -- a future trigger at the same point can animate again
        }

        objects.sync(current, Function.identity(), point ->
        {
            if (goldModel == null) buildGoldModel();
            return goldModel != null ? goldModel : client.loadModel(COIN_TRAP_MODEL_ID);
        });

        if (triggerActive && !triggerPoint.equals(animatedTriggerPoint))
        {
            RuneLiteObject obj = objects.get(triggerPoint);
            if (obj != null)
            {
                Animation anim = client.loadAnimation(COIN_TRAP_SPRING_ANIMATION_ID);
                if (anim != null)
                {
                    obj.setShouldLoop(false);
                    obj.setAnimation(anim);
                    animatedTriggerPoint = triggerPoint;
                }
            }
        }
    }

    /** Builds goldModel the first time it's needed -- a no-op once already built (or once a load
     * attempt has already failed, see goldModelLoadFailed). {@code Client#loadModel(id,
     * recolorFind, recolorReplace)}'s own recolor args only match colors against a swap slot the
     * model's cache definition explicitly declares, and this model declares nowhere near enough of
     * those to recolor the whole thing. Operating on the model's raw, pre-lit {@link ModelData}
     * instead exposes every face color the mesh has, with no swap-slot limit -- {@code
     * ModelData#recolor(short, short)} rewrites any of them directly, and {@code ModelData#light()}
     * bakes the recolored mesh into a final renderable {@link Model}. Every distinct color found
     * gets mapped to a golden HSL sharing that color's own luminance, preserving whatever shading
     * the model's real palette had (a lit top face reads as bright gold, a shadowed underside as
     * darker gold) rather than flattening the whole model into one indistinguishable color.
     * Retried on the next update() call if the raw load itself returns null, but only ever
     * actually builds the model once. */
    private void buildGoldModel()
    {
        if (goldModel != null || goldModelLoadFailed) return;

        ModelData raw = client.loadModelData(COIN_TRAP_MODEL_ID);
        if (raw == null) return; // not loaded yet -- retried next call

        short[] faceColors = raw.getFaceColors();
        if (faceColors == null || faceColors.length == 0)
        {
            goldModelLoadFailed = true;
            return;
        }

        Set<Short> distinct = new HashSet<>();
        for (short c : faceColors) distinct.add(c);

        int goldHue = JagexColor.unpackHue(JagexColor.rgbToHSL(COIN_TRAP_GOLD_RGB, 1.0));
        int goldSaturation = JagexColor.unpackSaturation(JagexColor.rgbToHSL(COIN_TRAP_GOLD_RGB, 1.0));

        ModelData result = raw;
        for (short original : distinct)
        {
            short recolored = JagexColor.packHSL(goldHue, goldSaturation, JagexColor.unpackLuminance(original));
            result = result.recolor(original, recolored);
        }
        goldModel = result.light();
    }

    /** Despawns and forgets every Coin Trap RuneLiteObject. */
    public void clear()
    {
        objects.clear();
    }
}
