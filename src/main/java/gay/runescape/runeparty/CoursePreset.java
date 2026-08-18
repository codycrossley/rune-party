package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A course layout: an <b>ordered</b> list of tiles relative to a center anchor, with rotation
 * support -- the Rune Party analog of Gnomeball's FieldPreset. {@code tiles}' list order is still
 * the tile's stable identity (tile 0 is the start, and a fresh commit assigns pathIndex == list
 * position, see RunePartyPlugin#commitPreset) -- but it's no longer the *only* way tiles connect.
 * By default each tile leads to "list position + 1" (wrapping at the end, closing the loop), same
 * as every course before forks existed; a tile can override that default via
 * {@link RelativeTile#nextIndices} to point at one or more *other* list positions instead --
 * that's a fork (more than one) or a merge redirect (exactly one, pointing somewhere other than
 * "+1"). See {@link #buildForkedLoop} for a worked example of both. Built-in courses and
 * host-saved custom courses share this one representation, same as FieldPreset does for Gnomeball
 * fields. */
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
     * rotation exactly as before it -- and since nextIndices are list-position references rather
     * than coordinates, they carry over unchanged by rotation. This is the single source of truth
     * for course geometry, used identically by the live placement preview and the actual commit so
     * they can never disagree (same guarantee FieldPreset.layout() makes for Gnomeball fields).
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
     * center, in path order. Unlike FieldPreset.fromTiles (which doesn't care about order), this
     * sorts the snapshot by {@link TileReducer.TileEntry#pathIndex} first -- the reducer's own
     * storage is an unordered map, so path order only survives via that field, not iteration order.
     * Entries with no path index (a stray non-course marker <i>or</i> a decorative Golden Gnome
     * modifier, see RelativeTile#decorative) are dropped rather than guessed at -- this function
     * predates decorative tiles and doesn't yet know how to place one back at the right dx/dy
     * relative to whatever real tile it was sitting on, same "not yet wired up" limitation
     * {@link #fromTiles} already had before this feature existed (there's still no UI calling it).
     * Each tile's nextIndices carries over unchanged, which only stays correct if pathIndex values
     * are contiguous from 0 (true for any course committed via the normal placement flow) --
     * removing individual tiles by hand first could leave stale references, same pre-existing
     * caveat this function already had for path order in general.
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
            relTiles.add(new RelativeTile(e.point.getX() - anchorX, e.point.getY() - anchorY, e.tileType, e.color, e.nextIndices));
        }
        return new CoursePreset(name, relTiles);
    }

    public static final class RelativeTile
    {
        public final int dx, dy;
        public final String tileType;
        public final String color;
        /** Explicit outgoing edges, as *list positions* in this preset's own {@code tiles} (which
         * become pathIndex values 1:1 on commit -- see RunePartyPlugin#commitPreset). Empty means
         * "no override": the default single edge to list position {@code (thisIndex + 1) %
         * tiles.size()} applies, same linear-with-wraparound behavior every course had before
         * forks existed. Two or more entries is a fork; exactly one entry that isn't "+1" is a
         * merge redirect (e.g. a branch's last tile rejoining the trunk somewhere else in the
         * list). Always empty for a decorative tile -- it has no course position of its own to
         * route from. */
        public final int[] nextIndices;
        /** True for a modifier tile that sits on top of another (non-decorative) tile at the same
         * dx/dy rather than being a course stop of its own -- a Golden Gnome tile, currently the
         * only example (see app.py's GOLDEN_GNOME_TILE handling). Committed with pathIndex omitted
         * (see RunePartyPlugin#commitPreset) instead of the usual "list position becomes
         * pathIndex," the same "decorative marker" shape TileReducer already supports for a stray
         * non-course tile. <b>Must be listed after every non-decorative tile in the preset</b>: the
         * commit step still uses raw list position as pathIndex for non-decorative entries, so a
         * decorative entry earlier in the list would shift every pathIndex after it, and would also
         * throw off nextIndices values (which are list-position references computed before
         * decorative entries are known to skip numbering). */
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

    /**
     * A generated placeholder loop (a plain rectangular ring of PATH tiles, START at index 0) so
     * there's at least one non-empty, testable built-in course out of the box. Actual course
     * <i>design</i> is explicitly out of scope for this pass -- real courses are expected to be
     * host-authored via sequential freehand placement (each click appends the next path index) and
     * saved as custom slots through {@link #fromTiles}, the same way Gnomeball hosts build/save
     * custom fields. Also exercises a Golden Gnome modifier end-to-end (see RelativeTile#decorative)
     * two steps out from START, so it's reachable by almost any first roll.
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

        // Item Space, 4 steps out from START (distinct from the Golden Gnome tile below, 2 steps
        // out) -- swapped in place the same way the very first tile above became START, so the
        // default course is immediately testable without hand-building a custom one.
        int itemTileIndex = 4;
        if (tiles.size() > itemTileIndex)
        {
            RelativeTile itemTile = tiles.get(itemTileIndex);
            tiles.set(itemTileIndex, new RelativeTile(itemTile.dx, itemTile.dy, "ITEM_TILE", null, itemTile.nextIndices));
        }

        // Decorative Golden Gnome modifier, stacked on the PATH tile two steps out from START (see
        // RelativeTile#decorative's own doc for why this has to be appended *after* the real path
        // rather than spliced in at its logical dx/dy).
        int goldenGnomeDx = startX + 2, goldenGnomeDy = startY;
        tiles.add(new RelativeTile(goldenGnomeDx, goldenGnomeDy, "GOLDEN_GNOME_TILE", null, true));

        return new CoursePreset("Standard Loop", tiles);
    }

    /**
     * A test course exercising forks end-to-end: the trunk splits in two three tiles out from
     * START, an "upper" and a "lower" branch each run three tiles, and both rejoin a single merge
     * tile before the trunk continues and loops back to START. Only the fork tile and each
     * branch's last tile need an explicit nextIndices override -- everything else (including the
     * loop closing back to index 0) is the default "+1" edge, same as Standard Loop. Rolling a
     * value that lands exactly on the fork tile offers both branches as candidates (see
     * TileOverlay#renderTargetArrow, which draws one arrow per candidate); rolling past it without
     * landing there resolves normally along whichever branch that specific roll's step count
     * reaches.
     */
    public static CoursePreset buildForkedLoop()
    {
        List<RelativeTile> tiles = new ArrayList<>();

        tiles.add(new RelativeTile(-3, 0, "START", null));       // 0
        tiles.add(new RelativeTile(-2, 0, "PATH", null));        // 1
        tiles.add(new RelativeTile(-1, 0, "PENALTY_TILE", null));        // 2
        tiles.add(new RelativeTile(0, 0, "PENALTY_TILE", null, 4, 7));   // 3 -- FORK: upper branch (4) or lower branch (7)

        // Branch A ("upper route"), arcing north of the trunk.
        tiles.add(new RelativeTile(1, -1, "PATH", null));        // 4
        tiles.add(new RelativeTile(2, -1, "PENALTY_TILE", null));        // 5
        tiles.add(new RelativeTile(3, -1, "PATH", null, 10));    // 6 -- rejoins at the merge tile (10)

        // Branch B ("lower route"), arcing south of the trunk.
        tiles.add(new RelativeTile(1, 1, "PATH", null));         // 7
        tiles.add(new RelativeTile(2, 1, "PENALTY_TILE", null));         // 8
        tiles.add(new RelativeTile(3, 1, "PENALTY_TILE", null, 10));     // 9 -- rejoins at the merge tile (10)

        tiles.add(new RelativeTile(4, 0, "PATH", null));         // 10 -- MERGE: both branches lead here, trunk continues

        // Trunk continues, then loops back around to START (default "+1" wraparound closes it).
        tiles.add(new RelativeTile(5, 0, "PATH", null));         // 11
        tiles.add(new RelativeTile(5, 3, "PENALTY_TILE", null));         // 12
        tiles.add(new RelativeTile(0, 3, "PENALTY_TILE", null));         // 13
        tiles.add(new RelativeTile(-3, 3, "PENALTY_TILE", null));        // 14
        tiles.add(new RelativeTile(-3, 1, "PATH", null));        // 15 -- default next wraps to 0 (START)

        return new CoursePreset("Forked Loop", tiles);
    }

    public static final CoursePreset STANDARD_LOOP = buildStandardLoop();
    public static final CoursePreset FORKED_LOOP = buildForkedLoop();

    public static final List<CoursePreset> ALL = List.of(STANDARD_LOOP, FORKED_LOOP);
}
