package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;

import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.NPCComposition;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/** Spawns a small, purely-decorative crowd of crab NPC models across the Crab Rave arena for as
 * long as that mini-game is active -- a centerpiece Gemstone Crab plus eight smaller crabs spread
 * around it (see {@link #SPAWNS}). Zero gameplay effect, so unlike JaddyDuelModel (which spawns
 * the same shape of thing for a real duel) there's no recolor, no health bar, no death sequence,
 * and no server-driven spawn event: every crab's own real-world position is derived purely from
 * the currently-marked CRAB_RAVE_TILE tiles' own bounding box (see {@link #spawnPoint}), the same
 * "client derives shared geometry from tiles it already has, no wire data needed" reasoning every
 * other arena mini-game's own client geometry already relies on -- since placement has no gameplay
 * consequence, every client landing on the identical spots is enough, no broadcast required.
 * <p>
 * Each spawn idles on its own {@link Spawn#animationId} in a loop the whole time -- not
 * necessarily that NPC composition's own listed standingAnimation (the Jewelled Crabs are
 * deliberately looped on a different animation than their own default, per the user's own explicit
 * picks) -- applied once per freshly-spawned object (checked via {@code getAnimation() == null},
 * simpler than JaddyDuelModel's own boolean-flag idiom since there's no second, later animation
 * this class ever needs to switch to). */
@Slf4j
public final class CrabRaveNpcOverlay extends Overlay
{
    /** One decorative NPC spawn slot -- which composition, which animation to loop on it, and
     * where relative to the arena's own bounding box (see {@link #spawnPoint}). */
    private static final class Spawn
    {
        final int npcId;
        final int animationId;
        final int dx, dy;

        Spawn(int npcId, int animationId, int dx, int dy)
        {
            this.npcId = npcId;
            this.animationId = animationId;
            this.dx = dx;
            this.dy = dy;
        }
    }

    // Plain "Crab" -- loops its own default standing animation (8461), distinct from the three
    // Jewelled Crabs below, which are each looped on a specific animation instead of their own
    // listed standingAnimation (1310), per the user's own explicit picks.
    private static final int CRAB_NPC_ID = 9201;
    private static final int CRAB_ANIMATION_ID = 8461;
    // Each Jewelled Crab color spawns twice (see SPAWNS below), one copy on each of these two
    // animations rather than both matching -- deliberately "opposite" of each other for visual
    // variety, per the user's own explicit ask. Shared across all three colors rather than each
    // color getting its own pair of constants, since it's the same two animations either way.
    private static final int JEWELLED_CRAB_ANIMATION_ID_1 = 2368;
    private static final int JEWELLED_CRAB_ANIMATION_ID_2 = 1312;
    private static final int JEWELLED_CRAB_BLUE_NPC_ID = 7579;
    private static final int JEWELLED_CRAB_RED_NPC_ID = 7577;
    private static final int JEWELLED_CRAB_GREEN_NPC_ID = 7578;
    private static final int HERMIT_CRAB_NPC_ID = 14852;
    private static final int HERMIT_CRAB_ANIMATION_ID = 12538;

    // GRID_SIZE is 8 (minigames/crab_rave.py) -- (4, 4) is the closest single tile to the block's
    // own true center (3.5, 3.5), where the Gemstone Crab stands. Every other spawn's own (dx, dy)
    // is chosen so no two crabs share a tile and -- per the user's own explicit ask -- neither
    // Jewelled Crab pair sits adjacent to its own other copy (each pair's own two tiles are at
    // least 2 tiles apart, Chebyshev distance): blue (6,1)/(1,3), red (1,6)/(2,4), green
    // (6,3)/(3,6). Different-colored crabs can and do end up near each other (e.g. blue's (1,3)
    // and red's (2,4) are diagonally adjacent) -- only a color sitting next to its own other copy
    // was the thing to avoid.
    private static final List<Spawn> SPAWNS = List.of(
        new Spawn(RunePartyPlugin.GEMSTONE_CRAB_NPC_ID, RunePartyPlugin.GEMSTONE_CRAB_IDLE_ANIMATION_ID, 4, 4),
        new Spawn(CRAB_NPC_ID, CRAB_ANIMATION_ID, 1, 1),
        new Spawn(JEWELLED_CRAB_BLUE_NPC_ID, JEWELLED_CRAB_ANIMATION_ID_1, 6, 1),
        new Spawn(JEWELLED_CRAB_BLUE_NPC_ID, JEWELLED_CRAB_ANIMATION_ID_2, 0, 4),
        new Spawn(JEWELLED_CRAB_RED_NPC_ID, JEWELLED_CRAB_ANIMATION_ID_1, 1, 7),
        new Spawn(JEWELLED_CRAB_RED_NPC_ID, JEWELLED_CRAB_ANIMATION_ID_2, 2, 2),
        new Spawn(JEWELLED_CRAB_GREEN_NPC_ID, JEWELLED_CRAB_ANIMATION_ID_1, 7, 7),
        new Spawn(JEWELLED_CRAB_GREEN_NPC_ID, JEWELLED_CRAB_ANIMATION_ID_2, 8, 1),
        new Spawn(HERMIT_CRAB_NPC_ID, HERMIT_CRAB_ANIMATION_ID, 4, 1)
    );

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<Integer> objects;
    // Which npcId's composition has already been diagnostic-logged once (see loadCrabModel) --
    // per-id, since each of the 5 spawns above resolves an entirely independent composition that
    // could succeed or fail on its own.
    private final Set<Integer> loggedCompositionIds = new HashSet<>();
    // Throttles logDiagnosticIfNeeded to roughly once every 2 seconds per npcId -- render() calls
    // this every frame for any spawn that hasn't successfully appeared yet, and without this a
    // genuinely stuck "never resolves" state would spam the log at ~60Hz instead of giving one
    // readable line per id at a steady cadence, same shape JaddyDuelModel's own
    // logZoneDiagnosticIfNeeded uses.
    private final Map<Integer, Long> lastDiagnosticLogAt = new HashMap<>();

    // The arena's own bounding-box origin, resolved once from the first non-empty
    // findCrabRaveTilePoints() read and reused for the rest of the round -- see render()'s own doc
    // for why re-deriving it (a tile-type scan + two stream().min() reductions) on every one of the
    // ~50 frames/sec this overlay runs is unnecessary: the arena is fixed the instant it swaps in
    // and never moves again until clear() (round end) wipes this back to null. Null means "not
    // resolved yet" -- distinct from a legitimately negative coordinate.
    private Integer cachedMinX;
    private Integer cachedMinY;
    private int cachedPlane;

    public CrabRaveNpcOverlay(Client client, RunePartyPlugin plugin)
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
        if (plugin.getPhase() != GamePhase.ACTIVE || !plugin.isCrabRaveActive())
        {
            clear();
            return null;
        }

        if (cachedMinX == null)
        {
            List<WorldPoint> tiles = plugin.findCrabRaveTilePoints();
            if (tiles.isEmpty())
            {
                clear();
                return null;
            }

            cachedMinX = tiles.stream().mapToInt(WorldPoint::getX).min().orElseThrow();
            cachedMinY = tiles.stream().mapToInt(WorldPoint::getY).min().orElseThrow();
            cachedPlane = tiles.get(0).getPlane();
        }

        int minX = cachedMinX;
        int minY = cachedMinY;
        int plane = cachedPlane;

        Set<Integer> desired = new HashSet<>();
        for (int i = 0; i < SPAWNS.size(); i++) desired.add(i);

        objects.sync(desired, i -> spawnPoint(SPAWNS.get(i), minX, minY, plane), i -> loadCrabModel(SPAWNS.get(i).npcId));

        for (Integer i : desired)
        {
            Spawn spawn = SPAWNS.get(i);
            RuneLiteObject obj = objects.get(i);
            if (obj == null || obj.getModel() == null)
            {
                logDiagnosticIfNeeded(spawn.npcId);
                continue;
            }
            if (obj.getAnimation() == null)
            {
                Animation anim = client.loadAnimation(spawn.animationId);
                if (anim != null)
                {
                    obj.setShouldLoop(true);
                    obj.setAnimation(anim);
                }
            }
        }

        return null;
    }

    private static WorldPoint spawnPoint(Spawn spawn, int minX, int minY, int plane)
    {
        return new WorldPoint(minX + spawn.dx, minY + spawn.dy, plane);
    }

    /** Thin wrapper over RunePartyRender.loadNpcModel that also logs (once per npcId) exactly what
     * that composition actually resolved to, the instant it first becomes available --
     * loadNpcModel/loadNpcModelData return null identically for "not cached yet, keep retrying"
     * and "this id doesn't resolve to a real, model-bearing composition," which are otherwise
     * indistinguishable from the outside (see JaddyDuelModel's own "Jads invisible, nothing else
     * wrong" diagnostic doc for the exact same class of silent-failure report this is written to
     * head off). Logs the composition's own real name and model id array so a wrong or stale npcId
     * is immediately visible rather than just "that crab never appears." */
    private Model loadCrabModel(int npcId)
    {
        NPCComposition comp = client.getNpcDefinition(npcId);
        if (comp != null && loggedCompositionIds.add(npcId))
        {
            int[] modelIds = comp.getModels();
            log.warn("CrabRaveNpcOverlay: NPC {} resolved -- name='{}' models={}",
                npcId, comp.getName(), modelIds == null ? "null" : Arrays.toString(modelIds));
        }
        return RunePartyRender.loadNpcModel(client, npcId);
    }

    private void logDiagnosticIfNeeded(int npcId)
    {
        long now = System.currentTimeMillis();
        Long last = lastDiagnosticLogAt.get(npcId);
        if (last != null && now - last < 2000) return;
        lastDiagnosticLogAt.put(npcId, now);

        NPCComposition comp = client.getNpcDefinition(npcId);
        if (comp == null)
        {
            log.warn("CrabRaveNpcOverlay: NPC {} still hasn't resolved a composition at all -- either not cached yet, or this id doesn't exist in the current game data", npcId);
        }
        else
        {
            log.warn("CrabRaveNpcOverlay: NPC {} composition resolved (name='{}') but no Model has spawned yet -- either its model parts aren't cached yet, or getModels() is empty",
                npcId, comp.getName());
        }
    }

    /** Despawns and forgets every Crab Rave RuneLiteObject -- a RuneLiteObject otherwise stays
     * registered with the client independently of this overlay or even the plugin being active. */
    public void clear()
    {
        objects.clear();
        cachedMinX = null; // see that field's own doc -- forces a fresh resolve for the next round
    }
}
