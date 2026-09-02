package gay.runescape.runeparty.models;

import gay.runescape.runeparty.SceneObjectSet;
import gay.runescape.runeparty.TileReducer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.WorldPoint;

/** Same "diff the live set against currently-spawned RuneLiteObjects" shape as GoldenGnomeModel/
 * CoinTrapModel, for an Arena tile that's finished heating -- a looping Fire model, replacing the
 * tile's own red outline once it's actually dead rather than layering the fire on top of it.
 * Unlike Coin Trap's single one-shot spring animation on trigger, every spawned Fire loops for as
 * long as its tile stays dead, which is the rest of the round. Keyed by WorldPoint same as
 * GoldenGnomeModel/CoinTrapModel, since a dead tile never relocates or un-dies. */
public final class ArenaFireModel
{
    private static final int ARENA_FIRE_MODEL_ID = 26582;
    private static final int ARENA_FIRE_ANIMATION_ID = 6645;

    // The Fire object's declared recolor find/replace pairs (packed-HSL swap slots) -- the game
    // only actually applies these when an object is spawned through the real object-definition
    // pipeline, never for a raw Client#loadModel(modelId) load, so they're reapplied by hand here
    // or the fire renders in whatever untouched palette the base mesh happens to have.
    private static final short[] RECOLOR_FIND = { 10471, 5056, 7104, 8146 };
    private static final short[] RECOLOR_REPLACE = { 978, 836, 704, 825 };

    // The hex color the server's heat gradient settles into once a tile is permanently dead. Every
    // earlier gradient step (green/yellow/orange) still renders as a plain colored tile outline;
    // only this exact, final red swaps in the Fire model instead.
    public static final String ARENA_DEAD_COLOR_HEX = "#DC1E1E";

    private final Client client;
    private final SceneObjectSet<WorldPoint> objects;

    // Tiles whose Fire object has already had its looping animation armed, so update() doesn't
    // re-call setAnimation every single frame a dead tile stays dead.
    private final Set<WorldPoint> animated = new HashSet<>();

    // Built once, lazily, the first time ARENA_FIRE_MODEL_ID's raw ModelData successfully loads --
    // see buildRecoloredModel, the only writer. Every currently-spawned Fire RuneLiteObject shares
    // this exact same recolored Model instance. Null until the load succeeds (and forever, if it
    // never does), in which case update() falls back to the model's own untouched palette.
    private Model recoloredModel;

    public ArenaFireModel(Client client)
    {
        this.client = client;
        this.objects = new SceneObjectSet<>(client);
    }

    /** Whether {@code colorHex} is the server's own "permanently dead" red, i.e. the tile should
     * render as a Fire model instead of a colored outline. Shared with TileOverlay so the outline
     * exclusion and this class's own spawn set can never drift apart. */
    public static boolean isDead(String colorHex)
    {
        return ARENA_DEAD_COLOR_HEX.equalsIgnoreCase(colorHex);
    }

    public void update(List<TileReducer.TileEntry> entries)
    {
        Set<WorldPoint> current = new HashSet<>();
        for (TileReducer.TileEntry entry : entries)
        {
            if ("ARENA_TILE".equals(entry.tileType) && isDead(entry.color)) current.add(entry.point);
        }

        objects.sync(current, Function.identity(), point ->
        {
            if (recoloredModel == null) buildRecoloredModel();
            return recoloredModel != null ? recoloredModel : client.loadModel(ARENA_FIRE_MODEL_ID);
        });

        animated.retainAll(current);
        for (WorldPoint point : current)
        {
            if (animated.contains(point)) continue;

            RuneLiteObject obj = objects.get(point);
            if (obj == null || obj.getModel() == null) continue; // model not loaded yet -- retried next call

            Animation anim = client.loadAnimation(ARENA_FIRE_ANIMATION_ID);
            if (anim == null) continue; // not loaded yet -- retried next call

            obj.setShouldLoop(true);
            obj.setAnimation(anim);
            animated.add(point);
        }
    }

    /** Builds recoloredModel the first time it's needed -- a no-op once already built. Retried on
     * the next update() call if the raw load returns null, but only ever actually builds once. */
    private void buildRecoloredModel()
    {
        if (recoloredModel != null) return;

        ModelData raw = client.loadModelData(ARENA_FIRE_MODEL_ID);
        if (raw == null) return; // not loaded yet -- retried next call

        ModelData result = raw;
        for (int i = 0; i < RECOLOR_FIND.length; i++)
        {
            result = result.recolor(RECOLOR_FIND[i], RECOLOR_REPLACE[i]);
        }
        recoloredModel = result.light();
    }

    /** Despawns and forgets every Arena Fire RuneLiteObject. */
    public void clear()
    {
        objects.clear();
        animated.clear();
    }
}
