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

    /**
     * Captured from a real, host-built course (12x5 = 30 real tiles, replacing the previous
     * generated 12x8 rectangular-ring placeholder), same "captured verbatim" approach
     * HardcodedCourse.buildFallyParkCourse's own doc describes. Reconstructed from that course's
     * own TILES_MARKED/TILES_UNMARKED event log (id 4127-4162, game 20260916-053836-1C1D) -- a few
     * tiles were placed, removed, and replaced with something else while it was being built (the
     * original JAD_TILE at what's now index 3 became an ITEM_TILE; the original START at what's
     * now index 28 became an ITEM_TILE once index 0 was chosen as the real START instead; the PATH
     * originally at what's now index 16 became a WISE_OLD_MAN_TILE) -- this reflects the FINAL
     * state only, replays already folded in.
     * <p>
     * The connect events that would carry each tile's own real nextIndices weren't captured
     * alongside this -- per the user's own explicit call, this bakes in a plain "+1, wrapping at
     * the end" edge for every tile instead (same idiom the previous generated loop used), not
     * whatever bespoke connections (if any) the original course actually had.
     */
    public static CoursePreset buildStandardLoop()
    {
        List<RelativeTile> tiles = new ArrayList<>();
        tiles.add(new RelativeTile(-5, -2, "START", null));
        tiles.add(new RelativeTile(-4, -2, "PATH", null));
        tiles.add(new RelativeTile(-3, -2, "PATH", null));
        tiles.add(new RelativeTile(-2, -2, "ITEM_TILE", null));
        tiles.add(new RelativeTile(-1, -2, "PENALTY_TILE", null));
        tiles.add(new RelativeTile(0, -2, "PATH", null));
        tiles.add(new RelativeTile(1, -2, "PENALTY_TILE", null));
        tiles.add(new RelativeTile(2, -2, "PATH", null));
        tiles.add(new RelativeTile(3, -2, "ITEM_TILE", null));
        tiles.add(new RelativeTile(4, -2, "CHANCE_TILE", null));
        tiles.add(new RelativeTile(5, -2, "PATH", null));
        tiles.add(new RelativeTile(6, -2, "PENALTY_TILE", null));
        tiles.add(new RelativeTile(6, -1, "CHANCE_TILE", null));
        tiles.add(new RelativeTile(6, 0, "PATH", null));
        tiles.add(new RelativeTile(6, 1, "PATH", null));
        tiles.add(new RelativeTile(6, 2, "ITEM_TILE", null));
        tiles.add(new RelativeTile(5, 2, "WISE_OLD_MAN_TILE", null));
        tiles.add(new RelativeTile(4, 2, "PATH", null));
        tiles.add(new RelativeTile(3, 2, "PENALTY_TILE", null));
        tiles.add(new RelativeTile(2, 2, "ITEM_TILE", null));
        tiles.add(new RelativeTile(1, 2, "JAD_TILE", null));
        tiles.add(new RelativeTile(0, 2, "PENALTY_TILE", null));
        tiles.add(new RelativeTile(-1, 2, "PENALTY_TILE", null));
        tiles.add(new RelativeTile(-2, 2, "PATH", null));
        tiles.add(new RelativeTile(-3, 2, "CHANCE_TILE", null));
        tiles.add(new RelativeTile(-4, 2, "PATH", null));
        tiles.add(new RelativeTile(-5, 2, "PATH", null));
        tiles.add(new RelativeTile(-5, 1, "ITEM_SHOP_TILE", null));
        tiles.add(new RelativeTile(-5, 0, "ITEM_TILE", null));
        tiles.add(new RelativeTile(-5, -1, "PENALTY_TILE", null));

        // Bake in every tile's own explicit "+1, wrapping at the end" edge -- must happen before
        // the decorative Golden Gnome tile is appended below, since courseLen has to be exactly
        // the real course's own tile count, not real-tiles-plus-decorative (a decorative tile
        // never gets a pathIndex of its own).
        int courseLen = tiles.size();
        for (int i = 0; i < tiles.size(); i++)
        {
            RelativeTile t = tiles.get(i);
            tiles.set(i, new RelativeTile(t.dx, t.dy, t.tileType, t.color, (i + 1) % courseLen));
        }

        // Decorative Golden Gnome modifier, stacked exactly on START -- same placement
        // HardcodedCourse's own presets already use (see RelativeTile#decorative's own doc for why
        // this has to be appended *after* the real path rather than spliced in at its logical
        // dx/dy). start_game's own doc: a Golden Gnome sitting exactly on START the instant the
        // game begins gets relocated to a random PATH tile automatically, so placing it here (a
        // clean, deterministic spot for a freshly-placed course to have it sit at) plus that
        // existing relocation is all that's needed for it to land somewhere random at kickoff,
        // same as every hard-coded preset already does -- no separate mechanism needed for the
        // Standard Loop specifically.
        tiles.add(new RelativeTile(-5, -2, "GOLDEN_GNOME_TILE", null, true));

        return new CoursePreset("Standard Loop", tiles);
    }

    public static final CoursePreset STANDARD_LOOP = buildStandardLoop();

    public static final List<CoursePreset> ALL = List.of(STANDARD_LOOP);
}
