package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;

import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Spawns a purely-decorative Item Shop shopkeeper NPC model standing one tile south of every
 * currently-marked ITEM_SHOP_TILE, for as long as the game itself is active -- verbatim same
 * "no server-driven spawn event, every client derives an identical spot from tiles it already
 * has" shape WiseOldManNpcOverlay's own doc gives. Zero gameplay effect -- the actual encounter is
 * driven entirely by landing on the tile itself (see ItemShopDialogueOverlay); this is just who's
 * standing there. Idles on ITEM_SHOP_NPC_IDLE_ANIMATION_ID the whole time, facing the tile itself.
 * Recolored by hand to a custom palette (see RECOLOR_FIND/RECOLOR_REPLACE) rather than left in
 * ITEM_SHOP_NPC_ID's own natural colors -- same "the real spawn pipeline applies an NPC's declared
 * recolors automatically, a raw loadNpcModelData composition merge doesn't, so they have to be
 * reapplied by hand here" reasoning ArenaFireModel's own RECOLOR_FIND/RECOLOR_REPLACE doc gives.
 * applyRecolor is also called by ItemShopDialogueOverlay for this same NPC's own chathead portrait
 * -- see that class's own loadChatheadModel override -- so the two never drift apart into showing
 * different colors for what's meant to be the same shopkeeper. */
public final class ItemShopNpcOverlay extends Overlay
{
    // The shopkeeper's own custom recolor find/replace pairs (packed-HSL swap slots), paired by
    // index -- values above Short.MAX_VALUE are genuine packed-HSL bit patterns, not out of range;
    // Java just has no unsigned short literal, so they need an explicit narrowing cast the same way
    // any color above 0x7FFF always would.
    private static final short[] RECOLOR_FIND =
    {
        (short) 8741, (short) 25238, (short) 8078, (short) 908, (short) 4626, (short) 6798,
    };
    private static final short[] RECOLOR_REPLACE =
    {
        (short) 47502, (short) 47502, (short) 47382, (short) 47382, (short) 12, (short) 6057,
    };

    /** Applies the shopkeeper's own RECOLOR_FIND/RECOLOR_REPLACE pairs to raw ModelData -- shared
     * by this class's own buildRecoloredModel (the in-world model) and
     * ItemShopDialogueOverlay#loadChatheadModel (the dialogue portrait), so both always show the
     * exact same palette off one single source of truth. Caller still needs to call
     * {@code ModelData#light()} on the result themselves. */
    static ModelData applyRecolor(ModelData raw)
    {
        ModelData result = raw;
        for (int i = 0; i < RECOLOR_FIND.length; i++)
        {
            result = result.recolor(RECOLOR_FIND[i], RECOLOR_REPLACE[i]);
        }
        return result;
    }

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<WorldPoint> objects;

    // Built once, lazily, the first time ITEM_SHOP_NPC_ID's raw ModelData successfully merges/loads
    // -- see buildRecoloredModel, the only writer. Every spawned Item Shop NPC shares this exact
    // same recolored Model instance. Null until the load succeeds (and forever, if it never does),
    // in which case render() falls back to the model's own untouched palette rather than never
    // spawning at all.
    private Model recoloredModel;

    public ItemShopNpcOverlay(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        this.objects = new SceneObjectSet<>(client);

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE)
        {
            clear();
            return null;
        }

        List<WorldPoint> tiles = plugin.findItemShopTilePoints();
        if (tiles.isEmpty())
        {
            clear();
            return null;
        }

        Set<WorldPoint> desired = new HashSet<>(tiles);
        objects.sync(desired, ItemShopNpcOverlay::spawnPoint, k ->
        {
            if (recoloredModel == null) buildRecoloredModel();
            return recoloredModel != null ? recoloredModel : RunePartyRender.loadNpcModel(client, RunePartyPlugin.ITEM_SHOP_NPC_ID);
        });

        for (WorldPoint tilePoint : desired)
        {
            RuneLiteObject obj = objects.get(tilePoint);
            if (obj == null || obj.getModel() == null) continue;

            obj.setOrientation(RunePartyRender.orientationFacing(spawnPoint(tilePoint), tilePoint));

            if (obj.getAnimation() == null)
            {
                Animation anim = client.loadAnimation(RunePartyPlugin.ITEM_SHOP_NPC_IDLE_ANIMATION_ID);
                if (anim != null)
                {
                    obj.setShouldLoop(true);
                    obj.setAnimation(anim);
                }
            }
        }

        return null;
    }

    private static WorldPoint spawnPoint(WorldPoint tilePoint)
    {
        return tilePoint.dy(-1);
    }

    /** Builds recoloredModel the first time it's needed -- a no-op once already built. Retried on
     * the next render() call if the raw merge returns null, but only ever actually builds once.
     * Same recolor technique ArenaFireModel#buildRecoloredModel uses, just sourced from
     * RunePartyRender#loadNpcModelData's own NPC composition merge (ITEM_SHOP_NPC_ID has more than
     * one model part) rather than a single-part client#loadModelData(objectId) load. */
    private void buildRecoloredModel()
    {
        if (recoloredModel != null) return;

        ModelData raw = RunePartyRender.loadNpcModelData(client, RunePartyPlugin.ITEM_SHOP_NPC_ID);
        if (raw == null) return; // not cached yet -- retried next call

        recoloredModel = applyRecolor(raw).light();
    }

    /** Despawns and forgets every Item Shop RuneLiteObject -- see WiseOldManNpcOverlay's own
     * clear() doc for why this is needed independently of the overlay/plugin being active. */
    public void clear()
    {
        objects.clear();
    }
}
