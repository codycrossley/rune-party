package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RunePartyColor;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.SceneObjectSet;

import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.gameval.ItemID;
import net.runelite.client.util.Text;

import java.awt.Color;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Spawns/clears one floating balloon per seated player with a currently-known Balloon Pop growth
 * level (see RunePartyPlugin#getBalloonPopGrowthLevelByPlayer/BalloonPopPresentation) -- unlike
 * RuneMatchRuneModel's own tile-anchored models, each of these follows a specific *player's* own
 * live position every frame (see update()'s own live-Player-by-RSN resolution, the same technique
 * PlayerOverlay/RunePartyPlugin#triggerSpotAnimOnPlayer already use). Positioned from
 * getLocalLocation(), not getWorldLocation() -- the latter is only that player's own settled tile,
 * updated once per 600ms game tick, which read as the balloon snapping tile-to-tile instead of
 * gliding alongside a walk; getLocalLocation() is the same smoothly-interpolated, per-frame
 * position the player's own model already renders at. Hovers HOVER_HEAD_CLEARANCE above THAT
 * player's own real getLogicalHeight() -- not the local viewer's, since this needs to look right
 * above every player at once, not just whoever's watching -- with a gentle bob and a slow
 * continuous spin (see HOVER_BOB_AMPLITUDE/HOVER_BOB_PERIOD_MS/ROTATION_PERIOD_MS), each given a
 * small per-player phase offset so multiple balloons on screen don't move in lockstep.
 * <p>
 * Base model is a real balloon item (ItemID#ZEP_TEST_BALLOON, the "Origami balloon," resolved via
 * ItemComposition#getInventoryModel() same as RuneMatchRuneModel's own doc explains), recolored to
 * that player's own seat color via the same hue/saturation-preserving-luminance technique
 * JaddyDuelModel#buildRecoloredModel already uses, then scaled up per completed growth level (see
 * BASE_SCALE/GROWTH_PER_LEVEL). A balloon vanishes the instant its own player pops (see
 * BalloonPopPresentation#apply, which removes a popped player from the growth map entirely) --
 * this class has no "popped" state of its own to track, it just stops being asked to render one. */
public final class BalloonModel
{
    // "Origami balloon" -- ItemID.ZEP_TOY_BALLOON_YEL (9935) was tried first but never actually
    // rendered anything; this one's a confirmed-real, plain balloon shape (inventoryModel 19775).
    private static final int BALLOON_ITEM_ID = ItemID.ZEP_TEST_BALLOON;
    // Mesh#scale's own 1/128ths convention (see RuneMatchRuneModel's own doc) -- BASE_SCALE=140 is
    // roughly the balloon's own natural inventory size (~1.1x), growing by GROWTH_PER_LEVEL per
    // completed 10-click level (1 through 9), so a fully-grown balloon (level 9) renders at
    // 140+9*50=590, visibly huge right before popping at 100 clicks.
    private static final int BASE_SCALE = 140;
    private static final int GROWTH_PER_LEVEL = 50;
    // 40 units above the player's own real head height (player.getLogicalHeight()), per the user's
    // own spec -- same shape RuneMatchRuneModel's own HOVER_HEAD_CLEARANCE uses (coincidentally
    // also 40), generalized here to whichever player each balloon actually belongs to.
    private static final int HOVER_HEAD_CLEARANCE = 40;
    // Per completed growth level, the balloon rises this much further above its own base hover
    // height -- by level 9 it's floating a full 9*20=180 units higher than it started, so the
    // whole thing visibly drifts upward as it inflates, not just outward.
    private static final int HOVER_LIFT_PER_LEVEL = 20;
    // Same gentle sinusoidal bob RuneMatchRuneModel's own hover already uses, same amplitude/
    // period -- a balloon that just sat dead still at a fixed height read as noticeably stiffer
    // than that class's own established feel.
    private static final int HOVER_BOB_AMPLITUDE = 15;
    private static final double HOVER_BOB_PERIOD_MS = 1400.0;
    // Roughly one full turn every 5 seconds, per the user's own ask -- slow enough to read as
    // "gently drifting," not spinning. 2048 is OSRS's own orientation scale (a full circle), same
    // unit RunePartyRender#orientationFacing already uses.
    private static final double ROTATION_PERIOD_MS = 5000.0;
    private static final int FULL_ORIENTATION = 2048;

    private final Client client;
    private final RunePartyPlugin plugin;
    private final SceneObjectSet<String> objects;
    // rsn -> the growth level its own currently-spawned model was actually built at. SceneObjectSet
    // #sync's own modelIfNeeded factory only ever fires once per key (the first time an object's
    // spawned, see that class's own doc) -- an existing balloon's own model has to be swapped
    // directly here instead once its level moves on, or it would just keep rendering its very
    // first size forever.
    private final Map<String, Integer> builtLevel = new HashMap<>();

    public BalloonModel(Client client, RunePartyPlugin plugin)
    {
        this.client = client;
        this.plugin = plugin;
        this.objects = new SceneObjectSet<>(client);
    }

    /** Synchronizes every currently-visible balloon against RunePartyPlugin#
     * getBalloonPopGrowthLevelByPlayer, then hovers each one above its own real, live player. */
    public void update()
    {
        Map<String, Integer> growthLevelByPlayer = plugin.getBalloonPopGrowthLevelByPlayer();

        Map<String, Player> liveByRsn = new HashMap<>();
        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;
            String rsn = Text.toJagexName(p.getName());
            if (growthLevelByPlayer.containsKey(rsn)) liveByRsn.put(rsn, p);
        }

        Set<String> desired = liveByRsn.keySet();
        builtLevel.keySet().retainAll(desired);

        for (String rsn : desired)
        {
            int level = growthLevelByPlayer.get(rsn);
            RuneLiteObject existing = objects.get(rsn);
            if (existing != null && !Objects.equals(builtLevel.get(rsn), level))
            {
                Model rebuilt = buildModel(rsn, level);
                if (rebuilt != null)
                {
                    existing.setModel(rebuilt);
                    builtLevel.put(rsn, level);
                }
            }
        }

        objects.sync(desired,
            rsn -> liveByRsn.get(rsn).getWorldLocation(),
            rsn ->
            {
                int level = growthLevelByPlayer.getOrDefault(rsn, 0);
                Model model = buildModel(rsn, level);
                if (model != null) builtLevel.put(rsn, level);
                return model;
            });

        long now = System.currentTimeMillis();
        for (String rsn : desired)
        {
            RuneLiteObject obj = objects.get(rsn);
            Player player = liveByRsn.get(rsn);
            if (obj == null || player == null) continue;

            // sync() above already positioned this object from player.getWorldLocation() -- the
            // player's own settled tile, only updated once per 600ms game tick. Overriding with
            // getLocalLocation() here instead re-anchors it to the same smoothly-interpolated,
            // per-frame position the player's own model renders at, so the balloon actually glides
            // alongside a walk instead of snapping tile-to-tile the instant the tick catches up.
            LocalPoint lp = player.getLocalLocation();
            if (lp == null) continue;
            int plane = player.getWorldLocation().getPlane();
            obj.setLocation(lp, plane);

            // A small per-player phase offset (from the rsn's own hash) so multiple balloons on
            // screen at once don't all bob/spin in perfect, visibly robotic lockstep.
            long phased = now + Math.floorMod(rsn.hashCode(), 5000);

            int level = growthLevelByPlayer.getOrDefault(rsn, 0);
            int groundHeight = Perspective.getTileHeight(client, lp, plane);
            int hoverHeight = hoverHeightFor(player, level);
            double bobPhase = Math.floorMod(phased, (long) HOVER_BOB_PERIOD_MS) / HOVER_BOB_PERIOD_MS;
            int bob = (int) Math.round(Math.sin(bobPhase * 2 * Math.PI) * HOVER_BOB_AMPLITUDE);
            obj.setZ(groundHeight - hoverHeight - bob);

            double rotationPhase = Math.floorMod(phased, (long) ROTATION_PERIOD_MS) / ROTATION_PERIOD_MS;
            obj.setOrientation((int) Math.floorMod(Math.round(rotationPhase * FULL_ORIENTATION), FULL_ORIENTATION));
        }
    }

    /** How far above `player`'s own feet their balloon floats at `level` -- the same real height a
     * one-shot spotanim's own "height offspot" wants too (see RunePartyPlugin's own
     * BALLOON_POP_POPPED handling, which reuses this directly so the pop's own burst lands right
     * where that player's balloon actually was, not a separately-guessed constant). */
    public static int hoverHeightFor(Player player, int level)
    {
        return player.getLogicalHeight() + HOVER_HEAD_CLEARANCE + level * HOVER_LIFT_PER_LEVEL;
    }

    /** Builds `rsn`'s own balloon at `level`'s own scale, recolored to their own seat color --
     * returns null (retried next call) while the item's raw model data isn't cached yet, or if it
     * genuinely has no face colors to recolor. cloneColors()/cloneVertices() before mutating, same
     * "never mutate the shared cached ModelData in place" caution JaddyDuelModel/RuneMatchRuneModel
     * already document for this exact RuneLite API. */
    private Model buildModel(String rsn, int level)
    {
        String colorNumber = plugin.getRosterReducer().getColorNumber(rsn);
        RunePartyColor seatColor = RunePartyColor.forNumber(colorNumber);
        Color targetColor = seatColor != null ? seatColor.awt : Color.WHITE;

        ItemComposition item = client.getItemDefinition(BALLOON_ITEM_ID);
        if (item == null) return null;
        ModelData raw = client.loadModelData(item.getInventoryModel());
        if (raw == null) return null;

        short[] faceColors = raw.getFaceColors();
        if (faceColors == null || faceColors.length == 0) return null;

        Set<Short> distinct = new HashSet<>();
        for (short c : faceColors) distinct.add(c);

        int targetRgb = targetColor.getRGB() & 0xFFFFFF;
        int targetHue = JagexColor.unpackHue(JagexColor.rgbToHSL(targetRgb, 1.0));
        int targetSaturation = JagexColor.unpackSaturation(JagexColor.rgbToHSL(targetRgb, 1.0));

        ModelData result = raw.cloneColors().cloneVertices();
        for (short original : distinct)
        {
            short recolored = JagexColor.packHSL(targetHue, targetSaturation, JagexColor.unpackLuminance(original));
            result = result.recolor(original, recolored);
        }

        int scale = BASE_SCALE + level * GROWTH_PER_LEVEL;
        result = result.scale(scale, scale, scale);
        return result.light();
    }

    public void clear()
    {
        objects.clear();
        builtLevel.clear();
    }
}
