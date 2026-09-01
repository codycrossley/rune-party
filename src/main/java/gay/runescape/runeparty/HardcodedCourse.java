package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

import java.util.List;

/** A course whose tiles sit at fixed, absolute world coordinates -- committed verbatim via the
 * ordinary mark-tiles endpoint (see RunePartyPlugin#createGameFromHardcodedCourse) the instant a
 * game is created from this course's own launcher, rather than a host placing tiles by hand
 * through build mode. Unlike {@link CoursePreset} (list-position == pathIndex, host picks a center
 * + rotation), a hard-coded course's {@link #tiles} already carry real, possibly non-contiguous
 * pathIndex values and their own explicit nextIndices exactly as captured -- there's no relative
 * placement math here at all, just "commit these exact tiles."
 * <p>
 * {@link #launcherPoint} is this course's own START tile point -- where
 * {@link HardcodedCourseLauncherOverlay} spawns the persistent, always-visible Golden Gnome model
 * that "Create Game" hangs off of (see RunePartyPlugin#addHardcodedCourseLauncherMenuEntry). Every
 * course's own {@link #tiles} includes one extra GOLDEN_GNOME_TILE stacked on that same point --
 * tiles are keyed by (x, y, plane, tileType), same stacking CoursePreset.buildStandardLoop already
 * uses for its own Golden Gnome modifier, so this needs no special-casing at commit time. Once the
 * game actually starts, app.py's own start_game relocates that gnome off START to a random PATH
 * tile automatically (a general rule, not specific to hard-coded courses -- see that endpoint's own
 * doc), using the exact same relocation event/animation a purchase-triggered move already uses. */
public final class HardcodedCourse
{
    public final String name;
    /** Stable identifier sent to the server via ApiClient#lockStandardCourse -- distinct from
     * {@link #name} (a display string that could change) so a rename here never silently breaks
     * anything keyed off of it server-side (see minigames/course_offset.py's own ARENA_OFFSETS
     * table). Never changes once a course ships -- treat it the same as a database primary key. */
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

    /** Captured read-only from a real, currently-active LOBBY game (host "Mario Twunk", game_id
     * 20260901-033101-41DB) via a direct query against the local docker DB -- no write made, that
     * live game is untouched. This is the same real-world spot the original hard-coded capture
     * used (START still at (2996, 3374, 0), unchanged -- see this class's own doc on why
     * {@link #launcherPoint} always just follows wherever a course's own START tile is), evolved
     * since via the host's own "Connect From"/"Connect To" edits -- new ITEM_TILE/EVENT_TILE
     * forks, more branches. 56 real tiles, non-contiguous pathIndex (this course has real gaps
     * from in-game tile removals) preserved exactly as captured, plus one GOLDEN_GNOME_TILE
     * already stacked on START in the live data itself. 8 tiles' own nextIndices had a second,
     * genuinely dangling entry left over from that editing (an old "+1" edge to a since-removed
     * tile, sitting alongside a real new fork edge the host had added without ever cleaning up the
     * stale one via "Remove Connection") -- harmless in play (a nonexistent target is just never
     * followed, see app.py's _resolve_next_indices/tile_at_index) but stripped here rather than
     * baked permanently into a hard-coded course that has no in-game "Remove Connection" of its
     * own to fix it with later. */
    private static HardcodedCourse buildFallyParkCourse()
    {
        WorldPoint start = new WorldPoint(2996, 3374, 0);
        List<ApiClient.TileSpec> tiles = List.of(
            t(2996, 3374, "START", 0, 1),
            t(2996, 3376, "PATH", 1, 58),
            t(3000, 3376, "PATH", 3, 59),
            t(3002, 3379, "PATH", 5, 6),
            t(2999, 3380, "PATH", 6, 7),
            t(2996, 3380, "JAD_TILE", 7, 46),
            t(2993, 3378, "ITEM_TILE", 8, 9),
            t(2991, 3378, "PATH", 9, 10),
            t(2990, 3381, "PATH", 10, 60),
            t(2990, 3385, "PATH", 12, 45),
            t(2993, 3387, "PATH", 14, 15),
            t(2995, 3386, "PATH", 15, 61),
            t(2999, 3386, "PATH", 17, 18),
            t(3001, 3387, "PENALTY_TILE", 18, 19),
            t(3003, 3387, "PATH", 19, 20),
            t(3006, 3386, "PATH", 20, 21),
            t(3007, 3385, "PATH", 21, 22, 47),
            t(3007, 3383, "PATH", 22, 23),
            t(3006, 3381, "PATH", 23, 24),
            t(3006, 3378, "ITEM_TILE", 24, 25),
            t(3008, 3377, "PATH", 25, 26),
            t(3010, 3377, "JAD_TILE", 26, 27),
            t(3012, 3376, "PATH", 27, 28),
            t(3014, 3375, "PATH", 28, 29),
            t(3015, 3373, "PATH", 29, 30),
            t(3014, 3371, "PATH", 30, 65),
            t(3010, 3371, "PATH", 32, 33, 40),
            t(3009, 3373, "PATH", 33, 34),
            t(3008, 3375, "PATH", 34, 64),
            t(3004, 3375, "PENALTY_TILE", 36, 37),
            t(3002, 3375, "PATH", 37, 38),
            t(3000, 3374, "PATH", 38, 39),
            t(2998, 3374, "PATH", 39, 0),
            t(3008, 3371, "PATH", 40, 44),
            t(3003, 3371, "PATH", 42, 43),
            t(3001, 3372, "PATH", 43, 38),
            t(3005, 3371, "PATH", 44, 42),
            t(2991, 3387, "PENALTY_TILE", 45, 14),
            t(2995, 3378, "PATH", 46, 8),
            t(3010, 3385, "PATH", 47, 62),
            t(3019, 3386, "PATH", 50, 51),
            t(3022, 3385, "PATH", 51, 52),
            t(3023, 3382, "PENALTY_TILE", 52, 53),
            t(3024, 3379, "PATH", 53, 54),
            t(3023, 3377, "PENALTY_TILE", 54, 55),
            t(3021, 3375, "PENALTY_TILE", 55, 56),
            t(3019, 3374, "EVENT_TILE", 56, 57),
            t(3017, 3373, "PATH", 57, 30),
            t(2998, 3377, "PENALTY_TILE", 58, 3),
            t(3002, 3377, "ITEM_TILE", 59, 5),
            t(2991, 3383, "ITEM_TILE", 60, 12),
            t(2997, 3387, "EVENT_TILE", 61, 17),
            t(3013, 3385, "ITEM_TILE", 62, 63),
            t(3016, 3386, "ITEM_TILE", 63, 50),
            t(3006, 3375, "ITEM_TILE", 64, 36),
            t(3012, 3370, "PENALTY_TILE", 65, 32),
            new ApiClient.TileSpec(start.getX(), start.getY(), start.getPlane(), "GOLDEN_GNOME_TILE", null, null, null, new int[0])
        );
        return new HardcodedCourse("Fally Park", "fally_park", start, tiles);
    }

    public static final HardcodedCourse FALLY_PARK_COURSE = buildFallyParkCourse();

    public static final List<HardcodedCourse> ALL = List.of(FALLY_PARK_COURSE);
}
