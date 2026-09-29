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

/** Spawns a purely-decorative Wise Old Man NPC model standing just inside the course loop, beside
 * every currently-marked WISE_OLD_MAN_TILE (see RunePartyPlugin#findInteriorNpcSpawnPoint for
 * exactly how that spot is chosen), for as long as the game itself is active -- unlike
 * CrabRaveNpcOverlay's own spawns (only up for that one mini-game's own duration), this tracks the
 * tile's own real, host-placed presence on the board directly, the same "no server-driven spawn
 * event, every client derives an identical spot from tiles it already has" reasoning that class's
 * own doc gives. Zero gameplay effect -- the actual encounter is driven entirely by landing on the
 * tile itself (see WiseOldManDialogueOverlay); this is just who's standing there. Idles on
 * WISE_OLD_MAN_IDLE_ANIMATION_ID the whole time, facing the tile itself. Recolored by hand to a
 * custom palette (see RECOLOR_FIND/RECOLOR_REPLACE) rather than left in WISE_OLD_MAN_NPC_ID's own
 * natural colors -- same "the real spawn pipeline applies an NPC's declared recolors automatically,
 * a raw loadNpcModelData composition merge doesn't, so they have to be reapplied by hand here"
 * reasoning ArenaFireModel's own RECOLOR_FIND/RECOLOR_REPLACE doc gives (and ItemShopNpcOverlay's
 * own copy of the same technique already applies for its own NPC). */
public final class WiseOldManNpcOverlay extends Overlay
{
    // This NPC's own custom recolor find/replace pairs (packed-HSL swap slots), paired by index --
    // values above Short.MAX_VALUE are genuine packed-HSL bit patterns, not out of range; Java just
    // has no unsigned short literal, so they need an explicit narrowing cast the same way any color
    // above 0x7FFF always would.
    private static final short[] RECOLOR_FIND =
    {
        (short) 8741, (short) 7700, (short) 926, (short) 11200, (short) 25238,
    };
    private static final short[] RECOLOR_REPLACE =
    {
        (short) 8856, (short) 42787, (short) 43963, (short) 8617, (short) 8856,
    };

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<WorldPoint> objects;

    // Built once, lazily, the first time WISE_OLD_MAN_NPC_ID's raw ModelData successfully
    // merges/loads -- see buildRecoloredModel, the only writer. Every spawned Wise Old Man shares
    // this exact same recolored Model instance. Null until the load succeeds (and forever, if it
    // never does), in which case render() falls back to the model's own untouched palette rather
    // than never spawning at all.
    private Model recoloredModel;

    public WiseOldManNpcOverlay(Client client, RunePartyPlugin plugin)
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

        List<WorldPoint> tiles = plugin.findWiseOldManTilePoints();
        if (tiles.isEmpty())
        {
            clear();
            return null;
        }

        Set<WorldPoint> desired = new HashSet<>(tiles);
        objects.sync(desired, plugin::findInteriorNpcSpawnPoint, k ->
        {
            if (recoloredModel == null) buildRecoloredModel();
            return recoloredModel != null ? recoloredModel : RunePartyRender.loadNpcModel(client, RunePartyPlugin.WISE_OLD_MAN_NPC_ID);
        });

        for (WorldPoint tilePoint : desired)
        {
            RuneLiteObject obj = objects.get(tilePoint);
            if (obj == null || obj.getModel() == null) continue;

            obj.setOrientation(RunePartyRender.orientationFacing(plugin.findInteriorNpcSpawnPoint(tilePoint), tilePoint));

            if (obj.getAnimation() == null)
            {
                Animation anim = client.loadAnimation(RunePartyPlugin.WISE_OLD_MAN_IDLE_ANIMATION_ID);
                if (anim != null)
                {
                    obj.setShouldLoop(true);
                    obj.setAnimation(anim);
                }
            }
        }

        return null;
    }

    /** Builds recoloredModel the first time it's needed -- a no-op once already built. Retried on
     * the next render() call if the raw merge returns null, but only ever actually builds once.
     * Same recolor technique ArenaFireModel#buildRecoloredModel/ItemShopNpcOverlay#
     * buildRecoloredModel use, sourced from RunePartyRender#loadNpcModelData's own NPC composition
     * merge (WISE_OLD_MAN_NPC_ID has more than one model part) rather than a single-part
     * client#loadModelData(objectId) load. */
    private void buildRecoloredModel()
    {
        if (recoloredModel != null) return;

        ModelData raw = RunePartyRender.loadNpcModelData(client, RunePartyPlugin.WISE_OLD_MAN_NPC_ID);
        if (raw == null) return; // not cached yet -- retried next call

        ModelData result = raw;
        for (int i = 0; i < RECOLOR_FIND.length; i++)
        {
            result = result.recolor(RECOLOR_FIND[i], RECOLOR_REPLACE[i]);
        }
        recoloredModel = result.light();
    }

    /** Despawns and forgets every Wise Old Man RuneLiteObject -- see CrabRaveNpcOverlay's own
     * clear() doc for why this is needed independently of the overlay/plugin being active. */
    public void clear()
    {
        objects.clear();
    }
}
