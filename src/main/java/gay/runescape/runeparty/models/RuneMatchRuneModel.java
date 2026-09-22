package gay.runescape.runeparty.models;

import gay.runescape.runeparty.RuneMatchSpawn;
import gay.runescape.runeparty.SceneObjectSet;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.ModelData;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;

import java.util.Map;

/** Spawns/clears the 3D rune models for Rune Match's own currently-revealed cards -- same
 * SceneObjectSet-backed diff/spawn shape PondModel/TableModel already use for their own
 * always-visible modifiers. Unlike those, which mirror whatever the server broadcasts, which cards
 * are revealed here is decided entirely client-side (see minigames.RuneMatchPresentation#
 * isRevealed) -- TileOverlay only ever passes this the subset of the 16 card tiles the LOCAL
 * player has actually flipped or matched, never all 16; everything else renders as a hidden
 * placeholder instead (see TileOverlay#updateRuneMatchModels, the only caller).
 * <p>
 * RuneMatchSpawn.itemId is a real item id (e.g. 554 for a Fire rune), not a raw model id --
 * client.loadModel(int) needs the latter, so this resolves item -> model via
 * ItemComposition#getInventoryModel() first, the same indirection every item in the game goes
 * through to get from "which item is this" to "which model renders it" (see RuneMatchSpawn's own
 * doc for why this differs from SandwichItemModel's own hand-verified raw model ids).
 * <p>
 * Scaled up (SCALE, via the raw ModelData's own Mesh#scale -- see that method's own doc: the
 * argument is 1/128ths, so SCALE=250 is ~2x the item's own natural inventory size) and hovered
 * HOVER_HEAD_CLEARANCE above the local player's own getLogicalHeight() -- the same real height
 * PlayerOverlay's own token/skull/coin-popup positioning already reads for "how far above this
 * exact player's own head" (see that class's own doc), rather than a flat guessed constant, so a
 * card flipped underfoot always clears whichever player happens to be standing on it instead of
 * hidden behind/under them, per this minigame's own doc. DEFAULT_HOVER_HEIGHT is only a fallback
 * for the rare tick getLocalPlayer() itself returns null. cloneVertices() before scaling, same
 * "never mutate the shared cached ModelData in place" caution CoinTrapModel/JaddyDuelModel's own
 * recolor calls already document for that same class of RuneLite API. */
public final class RuneMatchRuneModel
{
    private static final int SCALE = 250;
    private static final int HOVER_HEAD_CLEARANCE = 40;
    private static final int DEFAULT_HOVER_HEIGHT = 190;
    private static final int HOVER_BOB_AMPLITUDE = 15;
    private static final double HOVER_BOB_PERIOD_MS = 1400.0;

    private final Client client;
    private final SceneObjectSet<Integer> objects;

    public RuneMatchRuneModel(Client client)
    {
        this.client = client;
        this.objects = new SceneObjectSet<>(client);
    }

    /** Synchronizes the visible Rune Match rune models -- keyed by this round's own 0-15 card
     * index, one entry per currently-revealed card only (see this class's own doc) -- then hovers
     * each one HOVER_HEAD_CLEARANCE above the local player's own current head height, with a
     * gentle per-index-offset bob, same "read the tile's real height via
     * Perspective#getTileHeight, then offset from that" shape SandwichItemModel's own doc requires
     * (a RuneLiteObject's Z is an absolute height, not a ground-relative one). */
    public void update(Map<Integer, RuneMatchSpawn> revealed)
    {
        Player localPlayer = client.getLocalPlayer();
        int hoverHeight = (localPlayer != null ? localPlayer.getLogicalHeight() : DEFAULT_HOVER_HEIGHT) + HOVER_HEAD_CLEARANCE;

        objects.sync(
            revealed.keySet(),
            index ->
            {
                RuneMatchSpawn rune = revealed.get(index);
                return rune != null ? rune.point : null;
            },
            index ->
            {
                RuneMatchSpawn rune = revealed.get(index);
                if (rune == null) return null;
                ItemComposition item = client.getItemDefinition(rune.itemId);
                if (item == null) return null;
                ModelData raw = client.loadModelData(item.getInventoryModel());
                if (raw == null) return null;
                return raw.cloneVertices().scale(SCALE, SCALE, SCALE).light();
            }
        );

        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, RuneMatchSpawn> entry : revealed.entrySet())
        {
            RuneLiteObject obj = objects.get(entry.getKey());
            if (obj == null) continue;

            LocalPoint lp = obj.getLocation();
            if (lp == null) continue;

            int groundHeight = Perspective.getTileHeight(client, lp, entry.getValue().point.getPlane());
            double phase = (now + entry.getKey() * 137L) / HOVER_BOB_PERIOD_MS;
            int bob = (int) Math.round(Math.sin(phase * 2 * Math.PI) * HOVER_BOB_AMPLITUDE);
            obj.setZ(groundHeight - hoverHeight - bob);
        }
    }

    public void clear()
    {
        objects.clear();
    }
}
