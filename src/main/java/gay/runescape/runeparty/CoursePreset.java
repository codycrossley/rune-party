package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A course layout: an <b>ordered</b> list of tiles relative to a center anchor, with rotation
 * support -- the Rune Party analog of Gnomeball's FieldPreset. The key difference from that
 * unordered zone/field model: {@code tiles}' list order <i>is</i> the walked path order (tile 0 is
 * the start, tile 1 is one roll-of-1 away, etc.) -- no separate index field needed on
 * RelativeTile/PlacedTile, since {@link #layout} preserves list order into its output and callers
 * that need an explicit path index (committing to the server, see TileReducer) derive it from each
 * tile's position in that returned list. Built-in courses and host-saved custom courses share this
 * one representation, same as FieldPreset does for Gnomeball fields. */
public final class CoursePreset
{
    public final String name;
    public final List<RelativeTile> tiles;

    public CoursePreset(String name, List<RelativeTile> tiles)
    {
        this.name = name;
        this.tiles = tiles;
    }

    @Override
    public String toString()
    {
        return name;
    }

    public boolean isEmpty()
    {
        return tiles == null || tiles.isEmpty();
    }

    /**
     * Rotates and translates this course's tiles onto {@code center}, {@code rotationSteps}
     * quarter-turns clockwise (0-3: 0/90/180/270 degrees), via the standard clockwise transform
     * (dx,dy) -> (dy,-dx). List order is preserved (index i of the input maps to index i of the
     * output), so a caller zipping the result against 0..N-1 recovers correct path indices after
     * rotation exactly as before it. This is the single source of truth for course geometry, used
     * identically by the live placement preview and the actual commit so they can never disagree
     * (same guarantee FieldPreset.layout() makes for Gnomeball fields).
     */
    public List<PlacedTile> layout(WorldPoint center, int rotationSteps)
    {
        int steps = ((rotationSteps % 4) + 4) % 4;
        int plane = center.getPlane();

        List<PlacedTile> placed = new ArrayList<>(tiles.size());
        for (RelativeTile rt : tiles)
        {
            int dx = rt.dx, dy = rt.dy;
            for (int i = 0; i < steps; i++)
            {
                int ndx = dy;
                int ndy = -dx;
                dx = ndx;
                dy = ndy;
            }
            placed.add(new PlacedTile(new WorldPoint(center.getX() + dx, center.getY() + dy, plane), rt.tileType, rt.color));
        }
        return placed;
    }

    /**
     * Builds a course from whatever tiles are currently marked, anchored at the bounding-box
     * center, in path order. Unlike FieldPreset.fromTiles (which doesn't care about order), this
     * sorts the snapshot by {@link TileReducer.TileEntry#pathIndex} first -- the reducer's own
     * storage is an unordered map, so path order only survives via that field, not iteration order.
     * Entries with no path index (a stray non-course marker) are dropped rather than guessed at.
     */
    public static CoursePreset fromTiles(String name, List<TileReducer.TileEntry> snapshot)
    {
        if (snapshot == null || snapshot.isEmpty()) return new CoursePreset(name, List.of());

        List<TileReducer.TileEntry> ordered = new ArrayList<>();
        for (TileReducer.TileEntry e : snapshot)
        {
            if (e.pathIndex != null) ordered.add(e);
        }
        if (ordered.isEmpty()) return new CoursePreset(name, List.of());
        ordered.sort((a, b) -> Integer.compare(a.pathIndex, b.pathIndex));

        // Filter to the majority plane first, same reasoning as FieldPreset.fromTiles -- a stray
        // off-plane tile would otherwise skew the bounds used to anchor the relative coordinates.
        Map<Integer, Integer> countByPlane = new LinkedHashMap<>();
        for (TileReducer.TileEntry e : ordered)
        {
            countByPlane.merge(e.point.getPlane(), 1, Integer::sum);
        }
        int majorityPlane = ordered.get(0).point.getPlane();
        int bestCount = -1;
        for (Map.Entry<Integer, Integer> entry : countByPlane.entrySet())
        {
            if (entry.getValue() > bestCount)
            {
                bestCount = entry.getValue();
                majorityPlane = entry.getKey();
            }
        }

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (TileReducer.TileEntry e : ordered)
        {
            if (e.point.getPlane() != majorityPlane) continue;
            minX = Math.min(minX, e.point.getX());
            maxX = Math.max(maxX, e.point.getX());
            minY = Math.min(minY, e.point.getY());
            maxY = Math.max(maxY, e.point.getY());
        }
        int anchorX = Math.floorDiv(minX + maxX, 2);
        int anchorY = Math.floorDiv(minY + maxY, 2);

        List<RelativeTile> relTiles = new ArrayList<>();
        for (TileReducer.TileEntry e : ordered)
        {
            if (e.point.getPlane() != majorityPlane) continue;
            relTiles.add(new RelativeTile(e.point.getX() - anchorX, e.point.getY() - anchorY, e.tileType, e.color));
        }
        return new CoursePreset(name, relTiles);
    }

    public static final class RelativeTile
    {
        public final int dx, dy;
        public final String tileType;
        public final String color;

        public RelativeTile(int dx, int dy, String tileType, String color)
        {
            this.dx = dx;
            this.dy = dy;
            this.tileType = tileType;
            this.color = color;
        }
    }

    public static final class PlacedTile
    {
        public final WorldPoint point;
        public final String tileType;
        public final String color;

        PlacedTile(WorldPoint point, String tileType, String color)
        {
            this.point = point;
            this.tileType = tileType;
            this.color = color;
        }
    }

    /**
     * A generated placeholder loop (a plain rectangular ring of PATH tiles, START at index 0) so
     * there's at least one non-empty, testable built-in course out of the box. Actual course
     * <i>design</i> is explicitly out of scope for this pass -- real courses are expected to be
     * host-authored via sequential freehand placement (each click appends the next path index) and
     * saved as custom slots through {@link #fromTiles}, the same way Gnomeball hosts build/save
     * custom fields.
     */
    public static CoursePreset buildStandardLoop()
    {
        int width = 12, height = 8;
        int startX = -(width / 2), startY = -(height / 2);
        int endX = startX + width - 1, endY = startY + height - 1;

        List<RelativeTile> tiles = new ArrayList<>();
        // Walk the rectangle's perimeter clockwise starting from the top-left corner, so list
        // order traces one continuous loop rather than four disconnected edges.
        for (int x = startX; x <= endX; x++) tiles.add(new RelativeTile(x, startY, "PATH", null));
        for (int y = startY + 1; y <= endY; y++) tiles.add(new RelativeTile(endX, y, "PATH", null));
        for (int x = endX - 1; x >= startX; x--) tiles.add(new RelativeTile(x, endY, "PATH", null));
        for (int y = endY - 1; y > startY; y--) tiles.add(new RelativeTile(startX, y, "PATH", null));

        if (!tiles.isEmpty())
        {
            RelativeTile first = tiles.get(0);
            tiles.set(0, new RelativeTile(first.dx, first.dy, "START", null));
        }

        return new CoursePreset("Standard Loop", tiles);
    }

    public static final CoursePreset STANDARD_LOOP = buildStandardLoop();

    public static final List<CoursePreset> ALL = List.of(STANDARD_LOOP);
}
