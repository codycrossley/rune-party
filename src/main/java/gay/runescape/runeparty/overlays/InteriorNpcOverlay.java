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
import java.util.function.Supplier;

/** Spawns a purely-decorative NPC model standing just inside the course loop, beside every
 * currently-marked tile a given {@code tileFinder} reports (see
 * RunePartyPlugin#findInteriorNpcSpawnPoint for exactly how that spot is chosen), for as long as
 * the game itself is active -- the "no server-driven spawn event, every client derives an
 * identical spot from tiles it already has" shape every other decorative spawner in this package
 * shares. Zero gameplay effect -- the actual encounter is driven entirely by landing on the tile
 * itself; this is just who's standing there. Idles on {@code idleAnimationId} the whole time,
 * facing the tile itself.
 * <p>
 * Was two near-identical classes (ItemShopNpcOverlay, WiseOldManNpcOverlay) before being
 * parameterized into one -- the Item Clerk and the Wise Old Man are both plain "stand beside your
 * tile, idle, maybe recolored" NPCs with no spawn/behavior differences beyond which NPC, which
 * tiles, and which recolor palette. {@code recolorFind}/{@code recolorReplace} may be empty arrays
 * for an NPC left in its own natural colors (see constructor doc) -- same "the real spawn pipeline
 * applies an NPC's declared recolors automatically, a raw loadNpcModelData composition merge
 * doesn't, so they have to be reapplied by hand here" reasoning ArenaFireModel's own
 * RECOLOR_FIND/RECOLOR_REPLACE doc gives. {@link #applyRecolor} is also called by this NPC's own
 * ChatboxDialogueOverlay subclass for its chathead portrait, so the in-world model and the
 * dialogue box never drift apart into showing different colors for what's meant to be the same
 * person. */
public final class InteriorNpcOverlay extends Overlay
{
    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<WorldPoint> objects;
    private final Supplier<List<WorldPoint>> tileFinder;
    private final int npcId;
    private final int idleAnimationId;
    private final short[] recolorFind;
    private final short[] recolorReplace;

    // Built once, lazily, the first time npcId's raw ModelData successfully merges/loads -- see
    // buildRecoloredModel, the only writer. Every spawned copy of this NPC shares this exact same
    // recolored Model instance. Null until the load succeeds (and forever, if it never does -- or
    // if recolorFind is empty, in which case this stays null forever by design and render() falls
    // back to the NPC's own natural palette via RunePartyRender#loadNpcModel).
    private Model recoloredModel;

    /** {@code recolorFind}/{@code recolorReplace} may be empty (not null) for an NPC that should
     * keep its own natural, declared appearance -- e.g. GnomeNpcOverlay's own Gnome needed no
     * override, unlike this class's two current users. */
    public InteriorNpcOverlay(Client client, RunePartyPlugin plugin, Supplier<List<WorldPoint>> tileFinder,
                               int npcId, int idleAnimationId, short[] recolorFind, short[] recolorReplace)
    {
        this.client = client;
        this.plugin = plugin;
        this.tileFinder = tileFinder;
        this.npcId = npcId;
        this.idleAnimationId = idleAnimationId;
        this.recolorFind = recolorFind;
        this.recolorReplace = recolorReplace;
        this.objects = new SceneObjectSet<>(client);

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    /** Applies {@code find}/{@code replace}'s recolor pairs (packed-HSL swap slots, paired by
     * index) to raw ModelData -- shared by this class's own buildRecoloredModel (the in-world
     * model) and whichever ChatboxDialogueOverlay subclass owns this same NPC's chathead portrait,
     * so both always show the exact same palette off one single source of truth. Caller still
     * needs to call {@code ModelData#light()} on the result themselves. */
    public static ModelData applyRecolor(ModelData raw, short[] find, short[] replace)
    {
        ModelData result = raw;
        for (int i = 0; i < find.length; i++)
        {
            result = result.recolor(find[i], replace[i]);
        }
        return result;
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE)
        {
            clear();
            return null;
        }

        List<WorldPoint> tiles = tileFinder.get();
        if (tiles.isEmpty())
        {
            clear();
            return null;
        }

        Set<WorldPoint> desired = new HashSet<>(tiles);
        objects.sync(desired, plugin::findInteriorNpcSpawnPoint, k ->
        {
            if (recoloredModel == null) buildRecoloredModel();
            return recoloredModel != null ? recoloredModel : RunePartyRender.loadNpcModel(client, npcId);
        });

        for (WorldPoint tilePoint : desired)
        {
            RuneLiteObject obj = objects.get(tilePoint);
            if (obj == null || obj.getModel() == null) continue;

            obj.setOrientation(RunePartyRender.orientationFacing(plugin.findInteriorNpcSpawnPoint(tilePoint), tilePoint));

            if (obj.getAnimation() == null)
            {
                Animation anim = client.loadAnimation(idleAnimationId);
                if (anim != null)
                {
                    obj.setShouldLoop(true);
                    obj.setAnimation(anim);
                }
            }
        }

        return null;
    }

    /** Builds recoloredModel the first time it's needed -- a no-op once already built (or once
     * recolorFind is confirmed empty). Retried on the next render() call if the raw merge returns
     * null, but only ever actually builds once. Same recolor technique ArenaFireModel's own
     * buildRecoloredModel uses, sourced from RunePartyRender#loadNpcModelData's own NPC composition
     * merge rather than a single-part client#loadModelData(objectId) load. */
    private void buildRecoloredModel()
    {
        if (recoloredModel != null || recolorFind.length == 0) return;

        ModelData raw = RunePartyRender.loadNpcModelData(client, npcId);
        if (raw == null) return; // not cached yet -- retried next call

        recoloredModel = applyRecolor(raw, recolorFind, recolorReplace).light();
    }

    /** Despawns and forgets every RuneLiteObject this instance spawned -- a RuneLiteObject
     * otherwise stays registered with the client independently of this overlay or even the plugin
     * being active. */
    public void clear()
    {
        objects.clear();
    }
}
