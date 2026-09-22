package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;

import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Spawns the purely-decorative Gnome NPC (RunePartyPlugin#GNOME_NPC_ID) at the exact center of
 * the Golden Gnome Awards ceremony's own 5x5 CEREMONY_TILE arena, idling on GNOME_IDLE_ANIMATION_ID
 * the whole time -- same "no server-driven spawn event, every client derives an identical spot
 * from tiles it already has" reasoning WiseOldManNpcOverlay's own doc gives, just centered on the
 * arena's own middle tile instead of offset from a single host-placed one (5 being odd, the 5x5
 * block's own bounding-box center is always itself one of the 25 real tiles, never a gap between
 * four of them). Left in the NPC's own natural, declared appearance (see
 * RunePartyRender#loadNpcModel, which already applies an NPC's own composition/recolors
 * automatically) rather than a custom override the way WiseOldManNpcOverlay's own doc explains
 * that class needed for its own thematic reasons -- this Gnome's default look is exactly what the
 * ceremony wants.
 * <p>
 * Also spawns up to two flanking Golden Gnome props (flankingProps, GOLDEN_GNOME_PROP_MODEL_ID --
 * the same model id GoldenGnomeModel uses for the board's own relocating Golden Gnome), one per
 * possible bonus round, one tile west/east of the NPC's own center tile. A separate SceneObjectSet
 * from the NPC's own, since these are plain static props -- no idle animation, no composition
 * lookup -- rather than branching one factory function by key. Each is only included in the synced
 * set while CeremonyPresentation#isFlankingGnomeVisible(index) says so; that class independently
 * computes the exact same flanking points from the same underlying tiles for its own vanish-spotanim
 * targeting, so the two always agree without needing to reach into each other. */
public final class GnomeNpcOverlay extends Overlay
{
    private static final int GOLDEN_GNOME_PROP_MODEL_ID = 32303;

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<WorldPoint> objects;
    private final SceneObjectSet<WorldPoint> flankingProps;

    public GnomeNpcOverlay(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        this.objects = new SceneObjectSet<>(client);
        this.flankingProps = new SceneObjectSet<>(client);

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE || !plugin.isCeremonyIntroRevealed())
        {
            clear();
            return null;
        }

        WorldPoint center = arenaCenter();
        if (center == null)
        {
            clear();
            return null;
        }

        objects.sync(Collections.singleton(center), k -> k,
            k -> RunePartyRender.loadNpcModel(client, RunePartyPlugin.GNOME_NPC_ID));

        RuneLiteObject obj = objects.get(center);
        if (obj != null && obj.getModel() != null && obj.getAnimation() == null)
        {
            Animation anim = client.loadAnimation(RunePartyPlugin.GNOME_IDLE_ANIMATION_ID);
            if (anim != null)
            {
                obj.setShouldLoop(true);
                obj.setAnimation(anim);
            }
        }

        Set<WorldPoint> currentFlankingPoints = new HashSet<>();
        if (plugin.isCeremonyFlankingGnomeVisible(0)) currentFlankingPoints.add(center.dx(-1));
        if (plugin.isCeremonyFlankingGnomeVisible(1)) currentFlankingPoints.add(center.dx(1));
        flankingProps.sync(currentFlankingPoints, k -> k, k -> client.loadModel(GOLDEN_GNOME_PROP_MODEL_ID));

        return null;
    }

    /** The ceremony arena's own exact middle tile -- the 5x5 CEREMONY_TILE block's own
     * bounding-box center. Null if the arena hasn't swapped in yet, or has already been restored
     * (the ceremony's over). */
    private WorldPoint arenaCenter()
    {
        List<WorldPoint> tiles = plugin.findCeremonyArenaTiles();
        if (tiles.isEmpty()) return null;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (WorldPoint p : tiles)
        {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        return new WorldPoint((minX + maxX) / 2, (minY + maxY) / 2, tiles.get(0).getPlane());
    }

    /** Despawns and forgets the Gnome RuneLiteObject and both flanking props -- see
     * CrabRaveNpcOverlay's own clear() doc for why this is needed independently of the overlay/
     * plugin being active. */
    public void clear()
    {
        objects.clear();
        flankingProps.clear();
    }
}
