package gay.runescape.runeparty.courses;

import gay.runescape.runeparty.net.ApiClient;

import net.runelite.api.coords.WorldPoint;

import java.util.List;

/** A course whose tiles sit at fixed, absolute world coordinates -- committed verbatim the instant
 * a game is created from this course's own launcher, rather than a host placing tiles by hand
 * through build mode. Unlike {@link CoursePreset} (list-position == pathIndex, host picks a center
 * + rotation), a hard-coded course's {@link #tiles} already carry real, possibly non-contiguous
 * pathIndex values and their own explicit nextIndices exactly as captured -- there's no relative
 * placement math here at all, just "commit these exact tiles."
 * <p>
 * {@link #launcherPoint} is this course's own START tile point -- where
 * {@link HardcodedCourseLauncherOverlay} spawns the persistent, always-visible Golden Gnome model
 * that "Create Game" hangs off of. Every course's own {@link #tiles} includes one extra
 * GOLDEN_GNOME_TILE stacked on that same point, the same stacking CoursePreset.buildStandardLoop
 * uses for its own Golden Gnome modifier. Once the game starts, the server relocates that gnome
 * off START to a random PATH tile automatically, using the same relocation event/animation a
 * purchase-triggered move already uses. */
public final class HardcodedCourse
{
    public final String name;
    /** Stable identifier sent to the server via ApiClient#lockStandardCourse -- distinct from
     * {@link #name} (a display string that could change) so a rename here never silently breaks
     * anything keyed off of it server-side. Never changes once a course ships -- treat it the same
     * as a database primary key. */
    public final String key;
    public final WorldPoint launcherPoint;
    public final List<ApiClient.TileSpec> tiles;

    private HardcodedCourse(String name, String key, WorldPoint launcherPoint, List<ApiClient.TileSpec> tiles)
    {
        this.name = name;
        this.key = key;
        this.launcherPoint = launcherPoint;
        this.tiles = tiles;
    }

    /** x/y (plane always 0 for this course), tileType, pathIndex, and this tile's own explicit
     * outgoing edges -- color/orientation are always null here, this course has neither. */
    private static ApiClient.TileSpec t(int x, int y, String tileType, int pathIndex, int... nextIndices)
    {
        return new ApiClient.TileSpec(x, y, 0, tileType, null, null, pathIndex, nextIndices);
    }

    /** Captured from a real, host-built course (56 tiles, non-contiguous pathIndex from in-game
     * tile removals, plus one GOLDEN_GNOME_TILE stacked on START). A few stale, dangling
     * nextIndices entries left over from editing were stripped here rather than baked permanently
     * into a course with no in-game "Remove Connection" of its own to fix them with later. */
    private static HardcodedCourse buildFallyParkCourse()
    {
        WorldPoint start = new WorldPoint(2996, 3374, 0);
        List<ApiClient.TileSpec> tiles = List.of(
            t(2996, 3374, "START", 0, 1),
            t(2996, 3376, "PATH", 1, 58),
            t(3000, 3376, "PATH", 3, 59),
            t(3002, 3379, "WISE_OLD_MAN_TILE", 5, 6),
            t(2999, 3380, "PATH", 6, 7),
            t(2996, 3380, "JAD_TILE", 7, 46),
            t(2993, 3378, "ITEM_TILE", 8, 9),
            t(2991, 3378, "ITEM_SHOP_TILE", 9, 10),
            t(2990, 3381, "PATH", 10, 60),
            t(2990, 3385, "PATH", 12, 45),
            t(2993, 3387, "PATH", 14, 15),
            t(2995, 3386, "PATH", 15, 61),
            t(2999, 3386, "PATH", 17, 18),
            t(3001, 3387, "PENALTY_TILE", 18, 19),
            t(3003, 3387, "PATH", 19, 20),
            t(3006, 3386, "CHANCE_TILE", 20, 21),
            t(3007, 3385, "PATH", 21, 22, 47),
            t(3007, 3383, "ITEM_SHOP_TILE", 22, 23),
            t(3006, 3381, "PATH", 23, 24),
            t(3006, 3378, "ITEM_TILE", 24, 25),
            t(3008, 3377, "PATH", 25, 26),
            t(3010, 3377, "JAD_TILE", 26, 27),
            t(3012, 3376, "PATH", 27, 28),
            t(3014, 3375, "PATH", 28, 29),
            t(3015, 3373, "PATH", 29, 30),
            t(3014, 3371, "PATH", 30, 65),
            t(3010, 3371, "PATH", 32, 33, 40),
            t(3009, 3373, "CHANCE_TILE", 33, 34),
            t(3008, 3375, "PATH", 34, 64),
            t(3004, 3375, "PENALTY_TILE", 36, 37),
            t(3002, 3375, "PATH", 37, 38),
            t(3000, 3374, "PATH", 38, 39),
            t(2998, 3374, "PATH", 39, 0),
            t(3008, 3371, "ITEM_SHOP_TILE", 40, 44),
            t(3003, 3371, "PATH", 42, 43),
            t(3001, 3372, "CHANCE_TILE", 43, 38),
            t(3005, 3371, "PATH", 44, 42),
            t(2991, 3387, "PENALTY_TILE", 45, 14),
            t(2995, 3378, "PATH", 46, 8),
            t(3010, 3385, "PATH", 47, 62),
            t(3019, 3386, "CHANCE_TILE", 50, 51),
            t(3022, 3385, "PATH", 51, 52),
            t(3023, 3382, "PENALTY_TILE", 52, 53),
            t(3024, 3379, "PATH", 53, 54),
            t(3023, 3377, "PENALTY_TILE", 54, 55),
            t(3021, 3375, "PENALTY_TILE", 55, 56),
            t(3019, 3374, "PATH", 56, 57),
            t(3017, 3373, "PATH", 57, 30),
            t(2998, 3377, "PENALTY_TILE", 58, 3),
            t(3002, 3377, "ITEM_TILE", 59, 5),
            t(2991, 3383, "ITEM_TILE", 60, 12),
            t(2997, 3387, "PATH", 61, 17),
            t(3013, 3385, "ITEM_TILE", 62, 63),
            t(3016, 3386, "ITEM_TILE", 63, 50),
            t(3006, 3375, "ITEM_TILE", 64, 36),
            t(3012, 3370, "PENALTY_TILE", 65, 32),
            new ApiClient.TileSpec(start.getX(), start.getY(), start.getPlane(), "GOLDEN_GNOME_TILE", null, null, null, new int[0])
        );
        return new HardcodedCourse("Fally Park", "fally_park", start, tiles);
    }

    public static final HardcodedCourse FALLY_PARK_COURSE = buildFallyParkCourse();

    /** Captured from a real, host-built course (43 tiles, non-contiguous pathIndex from in-game
     * tile removals) -- an outward spiral from START at the center, winding clockwise and closing
     * back on itself at index 49 -> 0. Reconstructed from that course's own TILES_MARKED/
     * TILES_UNMARKED event log (game 20260918-062850-C528 on prod, still mid-build in LOBBY when
     * captured), same "captured verbatim, strip stale editing leftovers" approach
     * buildFallyParkCourse's own doc describes -- but this one needed real logical fixes, not just
     * stripping, found by checking every tile's (dx, dy) relative to START against its own place in
     * the intended winding order (radius should grow and angle should sweep clockwise,
     * monotonically, tile to tile):
     * <ul>
     * <li>Six tiles (27, 34, 38, 42, 52, 55) each had two outgoing edges -- one real, one a stale
     * leftover pointing at a pathIndex the host had since deleted (28, 40, 41, 43, 44, 30
     * respectively, none of which exist in the final tile set at all). Dropped the dangling one,
     * same as Fally Park's own precedent.</li>
     * <li>Tiles 9, 10, 53, and 11 formed a real logical error, not just a stray edge: 9 was a dead
     * end (no outgoing edge at all), while 11 -&gt; 53 -&gt; 10 -&gt; 9 wired that whole short
     * segment backward -- into the dead end instead of out of it -- leaving 11 itself
     * unreachable (nothing pointed to it) and the spiral broken in two unconnected pieces at
     * exactly the point they should have met. Geometrically, 10/53/11 sit precisely between 9 and
     * 14 in the winding order (radius 4.12 -&gt; 4.47 -&gt; 5.00, angle 256&deg; -&gt; 243&deg; -&gt;
     * 217&deg;, continuing the same clockwise sweep 9 -&gt; 14 already have on either side) --
     * reversed the segment to 9 -&gt; 10 -&gt; 53 -&gt; 11 -&gt; 14, which turns the whole course
     * into one single 43-tile cycle with no dead ends and no unreachable tiles.</li>
     * </ul>
     * Verified by simulating the full forward walk from index 0: visits all 43 tiles exactly once
     * and returns to 0, with every tile's own (radius, angle) from START increasing/sweeping
     * clockwise in step with its place in that walk. */
    private static HardcodedCourse buildVarrockSquareCourse()
    {
        WorldPoint start = new WorldPoint(3213, 3429, 0);
        List<ApiClient.TileSpec> tiles = List.of(
            t(3213, 3429, "START", 0, 1),
            t(3213, 3430, "PATH", 1, 2),
            t(3213, 3431, "PATH", 2, 3),
            t(3213, 3432, "PENALTY_TILE", 3, 4),
            t(3214, 3432, "ITEM_TILE", 4, 5),
            t(3216, 3431, "PATH", 5, 12),
            t(3216, 3426, "PATH", 7, 8),
            t(3214, 3425, "ITEM_TILE", 8, 9),
            t(3213, 3425, "PATH", 9, 10),
            t(3212, 3425, "ITEM_SHOP_TILE", 10, 53),
            t(3209, 3426, "PENALTY_TILE", 11, 14),
            t(3217, 3430, "ITEM_TILE", 12, 13),
            t(3217, 3427, "PATH", 13, 7),
            t(3208, 3427, "PENALTY_TILE", 14, 15),
            t(3208, 3430, "ITEM_TILE", 15, 16),
            t(3209, 3432, "PENALTY_TILE", 16, 17),
            t(3210, 3433, "CHANCE_TILE", 17, 19),
            t(3212, 3434, "JAD_TILE", 19, 20),
            t(3213, 3434, "ITEM_TILE", 20, 54),
            t(3218, 3432, "PATH", 22, 24),
            t(3217, 3433, "PATH", 23, 22),
            t(3219, 3430, "WISE_OLD_MAN_TILE", 24, 25),
            t(3219, 3427, "PATH", 25, 27),
            t(3218, 3425, "PATH", 27, 55),
            t(3212, 3422, "JAD_TILE", 34, 63),
            t(3206, 3427, "CHANCE_TILE", 38, 64),
            t(3207, 3433, "PENALTY_TILE", 42, 52),
            t(3215, 3436, "PATH", 45, 46),
            t(3218, 3435, "PATH", 46, 47),
            t(3220, 3433, "ITEM_TILE", 47, 48),
            t(3222, 3430, "PATH", 48, 49),
            t(3222, 3429, "PATH", 49, 0),
            t(3209, 3435, "PENALTY_TILE", 52, 59),
            t(3211, 3425, "PENALTY_TILE", 53, 11),
            t(3214, 3434, "PATH", 54, 23),
            t(3216, 3423, "PATH", 55, 58),
            t(3213, 3422, "CHANCE_TILE", 57, 34),
            t(3214, 3422, "ITEM_SHOP_TILE", 58, 57),
            t(3211, 3436, "PENALTY_TILE", 59, 60),
            t(3213, 3436, "ITEM_SHOP_TILE", 60, 45),
            t(3207, 3425, "PENALTY_TILE", 62, 38),
            t(3209, 3423, "PENALTY_TILE", 63, 62),
            t(3206, 3430, "CHANCE_TILE", 64, 42),
            new ApiClient.TileSpec(start.getX(), start.getY(), start.getPlane(), "GOLDEN_GNOME_TILE", null, null, null, new int[0])
        );
        return new HardcodedCourse("Varrock Square", "varrock_square", start, tiles);
    }

    public static final HardcodedCourse VARROCK_SQUARE_COURSE = buildVarrockSquareCourse();

    /** Captured from a real, host-built course (45 tiles, one gap at pathIndex 25 from an in-game
     * tile removal) -- a clockwise loop, no spiral this time (radius from START stays roughly
     * within one band rather than growing outward), same "captured verbatim" approach
     * buildFallyParkCourse/buildVarrockSquareCourse's own docs describe. Unlike those two, this
     * course had never been wired at all: every tile's own nextIndices was still empty
     * (host had placed all 45 in order but hadn't gotten to "Connect From"/"Connect To" yet), so
     * there was no existing graph to strip stale edges from or repair a broken segment in -- the
     * connections below are entirely inferred from geometry, by checking each tile's own (radius,
     * angle) from START against its place in ascending pathIndex order (the same check
     * buildVarrockSquareCourse's own doc describes). That check found the whole 0-45 sequence
     * already swept clockwise cleanly with exactly one exception: pathIndex 26 and 27 sit swapped
     * relative to the real clockwise order at that point (26 at angle 59.5&deg;/radius 19.72
     * comes geometrically *after* 27 at 63.4&deg;/22.36, not before) -- wired as 24 -&gt; 27 -&gt;
     * 26 -&gt; 28 rather than the naive ascending-pathIndex 24 -&gt; 26 -&gt; 27 -&gt; 28, which
     * would have doubled back counter-clockwise for one step. Verified the same way as Varrock
     * Square: simulating the full forward walk from index 0 visits all 45 tiles exactly once and
     * returns to 0, angle sweeping clockwise in step with the walk the entire way around. */
    private static HardcodedCourse buildCowTownCourse()
    {
        WorldPoint start = new WorldPoint(3254, 3267, 0);
        List<ApiClient.TileSpec> tiles = List.of(
            t(3254, 3267, "START", 0, 1),
            t(3254, 3269, "PATH", 1, 2),
            t(3254, 3271, "PATH", 2, 3),
            t(3254, 3273, "PENALTY_TILE", 3, 4),
            t(3254, 3275, "ITEM_TILE", 4, 5),
            t(3254, 3277, "PATH", 5, 6),
            t(3253, 3279, "ITEM_TILE", 6, 7),
            t(3252, 3280, "CHANCE_TILE", 7, 8),
            t(3251, 3281, "PENALTY_TILE", 8, 9),
            t(3249, 3282, "PENALTY_TILE", 9, 10),
            t(3247, 3282, "PATH", 10, 11),
            t(3244, 3283, "ITEM_SHOP_TILE", 11, 12),
            t(3243, 3285, "ITEM_TILE", 12, 13),
            t(3243, 3287, "CHANCE_TILE", 13, 14),
            t(3245, 3289, "PENALTY_TILE", 14, 15),
            t(3245, 3291, "PATH", 15, 16),
            t(3245, 3293, "PATH", 16, 17),
            t(3246, 3295, "ITEM_TILE", 17, 18),
            t(3249, 3295, "PATH", 18, 19),
            t(3252, 3293, "PENALTY_TILE", 19, 20),
            t(3255, 3291, "JAD_TILE", 20, 21),
            t(3258, 3292, "PATH", 21, 22),
            t(3260, 3292, "ITEM_SHOP_TILE", 22, 23),
            t(3262, 3291, "PATH", 23, 24),
            t(3263, 3289, "CHANCE_TILE", 24, 27),
            t(3264, 3284, "WISE_OLD_MAN_TILE", 26, 28),
            t(3264, 3287, "PENALTY_TILE", 27, 26),
            t(3264, 3281, "ITEM_TILE", 28, 29),
            t(3264, 3279, "PATH", 29, 30),
            t(3264, 3277, "PATH", 30, 31),
            t(3264, 3275, "PENALTY_TILE", 31, 32),
            t(3264, 3273, "PENALTY_TILE", 32, 33),
            t(3264, 3271, "PATH", 33, 34),
            t(3264, 3269, "ITEM_TILE", 34, 35),
            t(3264, 3267, "CHANCE_TILE", 35, 36),
            t(3264, 3265, "PATH", 36, 37),
            t(3264, 3263, "PENALTY_TILE", 37, 38),
            t(3264, 3261, "PENALTY_TILE", 38, 39),
            t(3263, 3259, "PATH", 39, 40),
            t(3261, 3258, "ITEM_TILE", 40, 41),
            t(3259, 3257, "JAD_TILE", 41, 42),
            t(3257, 3258, "ITEM_SHOP_TILE", 42, 43),
            t(3255, 3260, "PATH", 43, 44),
            t(3254, 3262, "PENALTY_TILE", 44, 45),
            t(3254, 3265, "PATH", 45, 0),
            new ApiClient.TileSpec(start.getX(), start.getY(), start.getPlane(), "GOLDEN_GNOME_TILE", null, null, null, new int[0])
        );
        return new HardcodedCourse("Cow Town", "cow_town", start, tiles);
    }

    public static final HardcodedCourse COW_TOWN_COURSE = buildCowTownCourse();

    public static final List<HardcodedCourse> ALL = List.of(FALLY_PARK_COURSE, VARROCK_SQUARE_COURSE, COW_TOWN_COURSE);
}
