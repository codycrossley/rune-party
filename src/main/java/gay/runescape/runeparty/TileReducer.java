package gay.runescape.runeparty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.runelite.api.coords.WorldPoint;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class TileReducer
{
    public static final class TileEntry
    {
        public final WorldPoint point;
        public final String tileType;
        public final String color;
        public final Integer orientation; // nullable -- reserved for future directional tiles
        public final Integer pathIndex; // nullable -- null only for a non-course decorative marker; every PATH/START/GOLDEN_GNOME_TILE/EVENT_TILE/JAD_TILE has one
        /** This tile's own outgoing edges (pathIndex values) -- its *only* ones. Empty means a
         * dead end, not an implied "next tile in line": there used to be a default (pathIndex + 1)
         * % courseLength fallback here, removed because it made freehand tile placement silently
         * auto-connect tiles the host never actually wired together (see
         * RunePartyPlugin#setCustomTileAt's own doc). Every edge -- including a plain "continue to
         * the next tile" one -- now has to be set explicitly via "Connect From"/"Connect To" (see
         * RunePartyPlugin#connectCustomTiles). See TileReducer#resolveNextIndices, which just
         * returns this as-is now. */
        public final int[] nextIndices;

        public TileEntry(WorldPoint point, String tileType, String color, Integer orientation, Integer pathIndex, int[] nextIndices)
        {
            this.point = point;
            this.tileType = tileType;
            this.color = color;
            this.orientation = orientation;
            this.pathIndex = pathIndex;
            this.nextIndices = nextIndices != null ? nextIndices : new int[0];
        }
    }

    private final ConcurrentHashMap<String, TileEntry> tiles = new ConcurrentHashMap<>();

    public void apply(ApiClient.EventOut e)
    {
        if (e == null || e.type == null) return;
        String type = e.type.toUpperCase(Locale.ROOT);

        if (Events.TILE_MARKED.equals(type))
        {
            applyMark(e.payload);
        }
        else if (Events.TILE_UNMARKED.equals(type))
        {
            applyUnmark(e.payload);
        }
        else if (Events.TILES_MARKED.equals(type))
        {
            // Bulk counterpart to TILE_MARKED -- one event carrying a whole course's worth of
            // tiles (see mark-tiles/commitPreset), so a big course commit is one event to apply
            // here instead of hundreds, even though each individual tile is applied identically.
            for (JsonElement el : Json.safeArray(e.payload, "tiles"))
            {
                if (el.isJsonObject()) applyMark(el.getAsJsonObject());
            }
        }
        else if (Events.TILES_UNMARKED.equals(type))
        {
            for (JsonElement el : Json.safeArray(e.payload, "tiles"))
            {
                if (el.isJsonObject()) applyUnmark(el.getAsJsonObject());
            }
        }
    }

    private void applyMark(JsonObject tile)
    {
        Integer x = Json.requiredInt(tile, Events.TILE_MARKED, "x");
        Integer y = Json.requiredInt(tile, Events.TILE_MARKED, "y");
        Integer plane = Json.requiredInt(tile, Events.TILE_MARKED, "plane");
        if (x == null || y == null || plane == null) return;

        String tileType = Json.requiredStr(tile, Events.TILE_MARKED, "tileType");
        if (tileType == null) return; // server always requires/validates a real tileType
        String color = Json.safeStr(tile, "color");
        Integer orientation = Json.safeInt(tile, "orientation");
        Integer pathIndex = Json.safeInt(tile, "pathIndex");
        int[] nextIndices = Json.safeIntArray(tile, "nextIndices");

        tiles.put(key(x, y, plane, tileType),
            new TileEntry(new WorldPoint(x, y, plane), tileType, color, orientation, pathIndex, nextIndices));
    }

    private void applyUnmark(JsonObject tile)
    {
        Integer x = Json.requiredInt(tile, Events.TILE_UNMARKED, "x");
        Integer y = Json.requiredInt(tile, Events.TILE_UNMARKED, "y");
        Integer plane = Json.requiredInt(tile, Events.TILE_UNMARKED, "plane");
        if (x == null || y == null || plane == null) return;

        String tileType = Json.safeStr(tile, "tileType");
        if (tileType != null)
        {
            tiles.remove(key(x, y, plane, tileType));
        }
        else
        {
            String prefix = x + ":" + y + ":" + plane + ":";
            tiles.keySet().removeIf(k -> k.startsWith(prefix));
        }
    }

    public void reset()
    {
        tiles.clear();
    }

    public List<TileEntry> snapshot()
    {
        return Collections.unmodifiableList(new ArrayList<>(tiles.values()));
    }

    /** The course tile at a given path position, or null if nothing is marked with that index
     * (an incomplete/gappy course -- the turn engine should treat that as "can't resolve this
     * roll" rather than guessing). Course sizes are small (tens to low hundreds of tiles) and this
     * is only ever called once per dice roll, so a linear scan needs no supporting index. */
    public TileEntry tileAtIndex(int pathIndex)
    {
        for (TileEntry e : tiles.values())
        {
            if (e.pathIndex != null && e.pathIndex == pathIndex) return e;
        }
        return null;
    }

    /** The path index of whatever course tile sits at {@code wp}, or null if {@code wp} isn't a
     * course tile at all (e.g. the player wandered off the board). Used to confirm a player
     * actually arrived at their rolled destination. */
    public Integer pathIndexAt(WorldPoint wp)
    {
        if (wp == null) return null;
        for (TileEntry e : tiles.values())
        {
            if (e.pathIndex == null) continue;
            if (e.point.getX() == wp.getX() && e.point.getY() == wp.getY() && e.point.getPlane() == wp.getPlane())
            {
                return e.pathIndex;
            }
        }
        return null;
    }

    /** One past the highest committed path index, i.e. the course's tile count if indices are
     * contiguous from 0 -- used for wrap-around math if a board loops back to its start. Returns 0
     * if no course is marked at all. */
    public int courseLength()
    {
        int max = -1;
        for (TileEntry e : tiles.values())
        {
            if (e.pathIndex != null && e.pathIndex > max) max = e.pathIndex;
        }
        return max + 1;
    }

    /** A tile's outgoing graph edges -- always exactly its own explicit nextIndices, never
     * inferred (see TileEntry#nextIndices's own doc for why there's no default fallback anymore).
     * Kept as its own method, rather than every caller reading entry.nextIndices directly, purely
     * so callers read the same "this is the resolved edge set" vocabulary the server's own
     * _resolve_next_indices uses -- route-line rendering here always agrees with what a roll can
     * actually resolve to server-side (see ApiClient's DICE_ROLLED targetIndices) because both
     * sides now do the exact same nothing-but-what's-explicit lookup. */
    public int[] resolveNextIndices(TileEntry entry)
    {
        return entry.nextIndices;
    }

    /** BFS shortest number of forward steps from fromPathIndex to toPathIndex along this course's
     * own graph (see resolveNextIndices) -- for RunePartyMapOverlay's own "N tiles away" hover
     * detail, the client-side mirror of the same forward-walk server's own _reachable_within does
     * for a dice roll. Null if toPathIndex genuinely isn't reachable at all (a dead end, or a gap
     * in the course) -- every visited pathIndex is remembered so a looping course (the common case)
     * can't spin forever re-treading ground it's already covered. 0 if the two indices are the
     * same tile. */
    public Integer stepsBetween(int fromPathIndex, int toPathIndex)
    {
        if (fromPathIndex == toPathIndex) return 0;

        Set<Integer> visited = new HashSet<>();
        visited.add(fromPathIndex);
        List<Integer> frontier = new ArrayList<>();
        frontier.add(fromPathIndex);

        int steps = 0;
        while (!frontier.isEmpty())
        {
            steps++;
            List<Integer> next = new ArrayList<>();
            for (int idx : frontier)
            {
                TileEntry entry = tileAtIndex(idx);
                if (entry == null) continue;
                for (int nxt : resolveNextIndices(entry))
                {
                    if (nxt == toPathIndex) return steps;
                    if (visited.add(nxt)) next.add(nxt);
                }
            }
            frontier = next;
        }
        return null;
    }

    private static String key(int x, int y, int plane, String tileType)
    {
        return x + ":" + y + ":" + plane + ":" + tileType;
    }
}
