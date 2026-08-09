package gay.runescape.runeparty;

import com.google.gson.JsonArray;
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
        public final Integer pathIndex; // nullable -- null only for a non-course decorative marker; every PATH/START/GOLDEN_GNOME_TILE/EVENT_TILE has one
        /** Explicit outgoing edges (pathIndex values), for board forks/merges -- empty means "use
         * the default (pathIndex + 1) % courseLength" edge every tile had before forks existed.
         * Only ever non-empty at a fork tile or a branch's tile just before a merge point; see
         * TileReducer#resolveNextIndices, which is what actually applies the default. */
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

        if ("TILE_MARKED".equals(type))
        {
            applyMark(e.payload);
        }
        else if ("TILE_UNMARKED".equals(type))
        {
            applyUnmark(e.payload);
        }
        else if ("TILES_MARKED".equals(type))
        {
            // Bulk counterpart to TILE_MARKED -- one event carrying a whole course's worth of
            // tiles (see mark-tiles/commitPreset), so a big course commit is one event to apply
            // here instead of hundreds, even though each individual tile is applied identically.
            for (JsonElement el : asArray(e.payload, "tiles"))
            {
                if (el.isJsonObject()) applyMark(el.getAsJsonObject());
            }
        }
        else if ("TILES_UNMARKED".equals(type))
        {
            for (JsonElement el : asArray(e.payload, "tiles"))
            {
                if (el.isJsonObject()) applyUnmark(el.getAsJsonObject());
            }
        }
    }

    private void applyMark(JsonObject tile)
    {
        Integer x = safeInt(tile, "x");
        Integer y = safeInt(tile, "y");
        Integer plane = safeInt(tile, "plane");
        if (x == null || y == null || plane == null) return;

        String tileType = safeStr(tile, "tileType");
        if (tileType == null) return; // server always requires/validates a real tileType
        String color = safeStr(tile, "color");
        Integer orientation = safeInt(tile, "orientation");
        Integer pathIndex = safeInt(tile, "pathIndex");
        int[] nextIndices = safeIntArray(tile, "nextIndices");

        tiles.put(key(x, y, plane, tileType),
            new TileEntry(new WorldPoint(x, y, plane), tileType, color, orientation, pathIndex, nextIndices));
    }

    private void applyUnmark(JsonObject tile)
    {
        Integer x = safeInt(tile, "x");
        Integer y = safeInt(tile, "y");
        Integer plane = safeInt(tile, "plane");
        if (x == null || y == null || plane == null) return;

        String tileType = safeStr(tile, "tileType");
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

    private static JsonArray asArray(JsonObject o, String k)
    {
        return (o != null && o.has(k) && o.get(k).isJsonArray()) ? o.get(k).getAsJsonArray() : new JsonArray();
    }

    public void loadAll(List<ApiClient.TileOut> tileList)
    {
        tiles.clear();
        if (tileList == null) return;
        for (ApiClient.TileOut t : tileList)
        {
            if (t == null || t.tileType == null) continue; // server always requires/validates a real tileType
            tiles.put(key(t.x, t.y, t.plane, t.tileType),
                new TileEntry(new WorldPoint(t.x, t.y, t.plane), t.tileType, t.color, t.orientation, t.pathIndex, t.nextIndices));
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

    public boolean hasMarker(WorldPoint wp, String tileType)
    {
        if (wp == null) return false;
        return tiles.containsKey(key(wp.getX(), wp.getY(), wp.getPlane(), tileType));
    }

    public boolean hasAnyMarker(WorldPoint wp)
    {
        if (wp == null) return false;
        String prefix = wp.getX() + ":" + wp.getY() + ":" + wp.getPlane() + ":";
        for (String k : tiles.keySet())
        {
            if (k.startsWith(prefix)) return true;
        }
        return false;
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

    /** Resolves a tile's outgoing graph edges: its own explicit nextIndices if it set any (a fork,
     * or a merge redirect), else the default single edge to (pathIndex + 1) % courseLength -- same
     * linear-with-wraparound behavior every course had before forks existed. Mirrors the server's
     * own _resolve_next_indices exactly, so route-line rendering always agrees with what a roll
     * can actually resolve to (see ApiClient's DICE_ROLLED targetIndices). */
    public int[] resolveNextIndices(TileEntry entry)
    {
        if (entry.nextIndices.length > 0) return entry.nextIndices;
        if (entry.pathIndex == null) return new int[0];
        int length = courseLength();
        if (length == 0) return new int[0];
        return new int[] { (entry.pathIndex + 1) % length };
    }

    /** Whether the host has marked out any course tiles at all -- mirrors Gnomeball's
     * hasFieldBoundary() guard: without this, "no course marked" and "board complete" are both
     * indistinguishable zero states to the turn engine. */
    public boolean hasCourse()
    {
        return courseLength() > 0;
    }

    private static String key(int x, int y, int plane, String tileType)
    {
        return x + ":" + y + ":" + plane + ":" + tileType;
    }

    private static String safeStr(JsonObject o, String k)
    {
        return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsString() : null;
    }

    private static Integer safeInt(JsonObject o, String k)
    {
        try { return (o != null && o.has(k) && !o.get(k).isJsonNull()) ? o.get(k).getAsInt() : null; }
        catch (Exception ignored) { return null; }
    }

    private static int[] safeIntArray(JsonObject o, String k)
    {
        if (o == null || !o.has(k) || o.get(k).isJsonNull() || !o.get(k).isJsonArray()) return new int[0];
        JsonArray arr = o.get(k).getAsJsonArray();
        int[] out = new int[arr.size()];
        for (int i = 0; i < arr.size(); i++)
        {
            try { out[i] = arr.get(i).getAsInt(); }
            catch (Exception ignored) { return new int[0]; }
        }
        return out;
    }
}
