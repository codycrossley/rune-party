package gay.runescape.runeparty.courses;

import gay.runescape.runeparty.TileReducer;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A course layout: an <b>ordered</b> list of tiles relative to a center anchor, with rotation
 * support. {@code tiles}' list order is still the tile's stable identity (tile 0 is the start, and
 * a fresh commit assigns pathIndex == list position), and it's also the only way tiles connect
 * here: every tile's {@link RelativeTile#nextIndices} has to be set explicitly, pointing at one or
 * more other list positions -- there's no implicit "list position + 1" default. {@link
 * #buildStandardLoop} bakes that "+1, wrapping at the end" edge into every one of its own tiles
 * explicitly, for exactly this reason. Built-in courses and host-saved custom courses share this
 * one representation. */
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

    /**
     * Rotates and translates this course's tiles onto {@code center}, {@code rotationSteps}
     * quarter-turns clockwise (0-3: 0/90/180/270 degrees), via the standard clockwise transform
     * (dx,dy) -> (dy,-dx). List order is preserved (index i of the input maps to index i of the
     * output), so a caller zipping the result against 0..N-1 recovers correct path indices after
     * rotation exactly as before it -- and since nextIndices are list-position references rather
     * than coordinates, they carry over unchanged by rotation. This is the single source of truth
     * for course geometry, used identically by the live placement preview and the actual commit so
     * they can never disagree.
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
            placed.add(new PlacedTile(new WorldPoint(center.getX() + dx, center.getY() + dy, plane), rt.tileType, rt.color, rt.nextIndices, rt.decorative));
        }
        return placed;
    }

    /**
     * Builds a course from whatever tiles are currently marked, anchored at the bounding-box
     * center, in path order. Sorts the snapshot by {@link TileReducer.TileEntry#pathIndex} first --
     * the reducer's own storage is an unordered map, so path order only survives via that field,
     * not iteration order. Entries with no path index (a stray non-course marker or a decorative
     * Golden Gnome modifier, see RelativeTile#decorative) are dropped rather than guessed at --
     * this function doesn't yet know how to place one back at the right dx/dy relative to whatever
     * real tile it was sitting on (there's still no UI calling it). Each tile's nextIndices carries
     * over unchanged, which only stays correct if pathIndex values are contiguous from 0.
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

        // Filter to the majority plane first -- a stray off-plane tile would otherwise skew the
        // bounds used to anchor the relative coordinates.
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
            relTiles.add(new RelativeTile(e.point.getX() - anchorX, e.point.getY() - anchorY, e.tileType, e.color, e.nextIndices));
        }
        return new CoursePreset(name, relTiles);
    }

    public static final class RelativeTile
    {
        public final int dx, dy;
        public final String tileType;
        public final String color;
        /** This tile's own outgoing edges, as list positions in this preset's own {@code tiles}
         * (which become pathIndex values 1:1 on commit) -- its only ones, empty means a genuine
         * dead end. No implicit default; a plain "continue to the next tile in the loop" edge has
         * to be listed explicitly, same as a fork (two or more entries) or a merge redirect (one
         * entry pointing somewhere other than "+1"). Always empty for a decorative tile -- it has
         * no course position of its own to route from. */
        public final int[] nextIndices;
        /** True for a modifier tile that sits on top of another (non-decorative) tile at the same
         * dx/dy rather than being a course stop of its own -- a Golden Gnome tile, currently the
         * only example. Committed with pathIndex omitted instead of the usual "list position
         * becomes pathIndex". <b>Must be listed after every non-decorative tile in the preset</b>:
         * the commit step still uses raw list position as pathIndex for non-decorative entries, so
         * a decorative entry earlier in the list would shift every pathIndex after it. */
        public final boolean decorative;

        public RelativeTile(int dx, int dy, String tileType, String color, int... nextIndices)
        {
            this(dx, dy, tileType, color, false, nextIndices);
        }

        public RelativeTile(int dx, int dy, String tileType, String color, boolean decorative, int... nextIndices)
        {
            this.dx = dx;
            this.dy = dy;
            this.tileType = tileType;
            this.color = color;
            this.decorative = decorative;
            this.nextIndices = decorative ? new int[0] : (nextIndices != null ? nextIndices : new int[0]);
        }
    }

    public static final class PlacedTile
    {
        public final WorldPoint point;
        public final String tileType;
        public final String color;
        public final int[] nextIndices;
        public final boolean decorative;

        PlacedTile(WorldPoint point, String tileType, String color, int[] nextIndices, boolean decorative)
        {
            this.point = point;
            this.tileType = tileType;
            this.color = color;
            this.nextIndices = nextIndices != null ? nextIndices : new int[0];
            this.decorative = decorative;
        }
    }

    /** Swaps the tile at {@code index} to {@code tileType} in place, keeping its dx/dy/color/
     * nextIndices -- the same "list position becomes pathIndex, only the type changes" idiom
     * {@link #buildStandardLoop} uses for every one of its non-PATH tiles. */
    private static void swapType(List<RelativeTile> tiles, int index, String tileType)
    {
        if (index >= tiles.size()) return;
        RelativeTile t = tiles.get(index);
        tiles.set(index, new RelativeTile(t.dx, t.dy, tileType, t.color, t.nextIndices));
    }

    /**
     * A generated placeholder loop (a plain rectangular ring of PATH tiles, START at index 0) so
     * there's at least one non-empty, testable built-in course out of the box. Real courses are
     * expected to be host-authored via sequential freehand placement, then saved as custom slots
     * through {@link #fromTiles}. Every tile here gets its own explicit single-edge nextIndices
     * baked in below (the perimeter walk's own "+1, wrapping at the end" order) -- there's no
     * implicit default to lean on. Also exercises a Golden Gnome modifier end-to-end (see
     * RelativeTile#decorative) two steps out from START, so it's reachable by almost any first
     * roll, plus a spread of every other course tile type (ITEM, JAD, PENALTY, CHANCE)
     * swapped in around the loop -- roughly Fally Park's own PATH-heavy proportions, just scaled
     * down to this course's 36 real tiles, so the default course isn't just a bare ring of PATH.
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
            tiles.set(0, new RelativeTile(first.dx, first.dy, "START", null, first.nextIndices));
        }

        // Swapped in place, same idiom as index 0 becoming START above -- spread across all four
        // sides of the perimeter rather than clustered on one, so no single stretch of the loop is
        // all-PATH.
        swapType(tiles, 4, "ITEM_TILE");
        swapType(tiles, 7, "PENALTY_TILE");
        swapType(tiles, 10, "JAD_TILE");
        swapType(tiles, 15, "CHANCE_TILE");
        swapType(tiles, 24, "PENALTY_TILE");
        swapType(tiles, 27, "ITEM_TILE");
        swapType(tiles, 29, "CHANCE_TILE");
        swapType(tiles, 32, "PENALTY_TILE");
        swapType(tiles, 34, "CHANCE_TILE");

        // Bake in every tile's own explicit "+1, wrapping at the end" edge -- must happen after
        // every tileType swap above and before the decorative Golden Gnome tile is appended below,
        // since courseLen has to be exactly the real course's own tile count, not
        // real-tiles-plus-decorative (a decorative tile never gets a pathIndex of its own).
        int courseLen = tiles.size();
        for (int i = 0; i < tiles.size(); i++)
        {
            RelativeTile t = tiles.get(i);
            tiles.set(i, new RelativeTile(t.dx, t.dy, t.tileType, t.color, (i + 1) % courseLen));
        }

        // Decorative Golden Gnome modifier, stacked on the PATH tile two steps out from START (see
        // RelativeTile#decorative's own doc for why this has to be appended *after* the real path
        // rather than spliced in at its logical dx/dy).
        int goldenGnomeDx = startX + 2, goldenGnomeDy = startY;
        tiles.add(new RelativeTile(goldenGnomeDx, goldenGnomeDy, "GOLDEN_GNOME_TILE", null, true));

        return new CoursePreset("Standard Loop", tiles);
    }

    public static final CoursePreset STANDARD_LOOP = buildStandardLoop();

    public static final List<CoursePreset> ALL = List.of(STANDARD_LOOP);
}
