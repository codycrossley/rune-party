package gay.runescape.runeparty.courses;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TileReducer;
import gay.runescape.runeparty.minigames.Minigame;
import gay.runescape.runeparty.minigames.Minigames;
import gay.runescape.runeparty.net.ApiClient;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import lombok.extern.slf4j.Slf4j;

import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.RuneLite;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Host-only course building (LOBBY only), extracted out of RunePartyPlugin: pick a preset,
 * enter Place mode, then right-click a ground tile to commit its footprint there (there's no
 * per-preset removal -- clearCourse() below is the host's one "start over" tool); or, as a
 * free-form alternative, place/retype/remove one custom tile at a time and wire up its
 * connect-graph edges by hand.
 * <p>
 * Placement (commitPreset)/clearCourse/mini-game spawn placement are real, synchronous requests
 * the server either accepts or rejects, mirrored back into TileReducer's own live board the same
 * way any other tiles_marked update is -- these set up the REAL board every lobby member sees, so
 * they stay server-backed regardless of Dev Mode. The free-form tile-by-tile editing methods
 * (setCustomTileAt/removeCustomTileAt/connectCustomTiles/removeCustomConnection/
 * removeAllConnectionsAt), by contrast, are Dev-Mode-only (see enterCustomCourseBuildMode) and
 * write directly into TileReducer via its own markLocal/unmarkLocal -- no server round-trip, no
 * event of any kind, invisible to every other lobby member -- so a host can privately iterate on a
 * new course design without mutating the shared board live in front of everyone, then capture the
 * result to a local JSON file via saveCurrentCourseAs ("Save Current Course" in the panel) once
 * it's done. Owns its own fields, folds no server events of its own, and clears itself via
 * reset(); RunePartyPlugin still exposes every getter/action under its original name, just
 * delegating here. */
@Slf4j
public final class CourseBuilder
{
    // Pretty-printed on purpose -- a saved course file is meant to be human-readable/diffable
    // (e.g. to hand-port into a new HardcodedCourse entry later), unlike the plugin's own injected
    // Gson, which is tuned for compact wire payloads. A dedicated instance keeps that choice local
    // to the one class that owns the file format.
    private static final Gson FILE_GSON = new GsonBuilder().setPrettyPrinting().create();

    private final RunePartyPlugin plugin;

    // Built-in CoursePreset.ALL plus every course loaded from coursesDir() -- see
    // getAvailablePresets/reloadLocalPresets. Reloaded once at construction and again after every
    // successful saveCurrentCourseAs.
    private volatile List<CoursePreset> localPresets = List.of();

    private volatile boolean coursePlacementMode = false;
    private volatile CoursePreset selectedPreset = null;
    private volatile int presetRotationSteps = 0; // quarter-turns clockwise: 0/1/2/3 = 0/90/180/270 degrees
    // Free-form, one-tile-at-a-time alternative to stamping down a whole CoursePreset -- see
    // enterCustomCourseBuildMode/addCustomCourseBuildMenuEntries. Mutually exclusive with
    // coursePlacementMode: entering either one cancels the other, same "only one placement mode
    // armed at a time" invariant RunePartyPlugin's own item-use flow keeps.
    private volatile boolean customCourseBuildMode = false;
    // Armed by "Connect From" -- the source pathIndex a subsequent "Connect To"/"Remove
    // Connection" click targets. Null when not mid-connect. Client-local only -- the server never
    // hears about this until an actual mark-tiles call goes out, so there's nothing to undo
    // server-side just by backing out of it. See TileOverlay#renderConnectFromIndicator, which
    // reads this (via getCourseConnectFromPoint) to show which tile is actually armed -- there's
    // otherwise nothing on screen distinguishing it from any other course tile.
    private volatile Integer courseConnectFromIndex = null;
    // Which mini-game key (see RunePartyPlugin#BOARD_SWAPPING_MINIGAME_KEYS) is currently being
    // placed via "Place Minigame" -> right-click "Place <Name> Here", or null when not in this
    // mode -- see enterMinigameSpawnPlacementMode/addMinigameSpawnMenuEntries. Mutually exclusive
    // with coursePlacementMode/customCourseBuildMode, same "only one placement mode armed at a
    // time" invariant those two already keep between each other. Unlike course tile placement,
    // this is allowed regardless of whether the course is a locked Standard Course -- see
    // enterMinigameSpawnPlacementMode's own doc.
    private volatile String minigameSpawnPlacementKey = null;

    public CourseBuilder(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        reloadLocalPresets();
    }

    public void selectPreset(CoursePreset preset)
    {
        selectedPreset = preset;
        plugin.refreshPanel();
    }

    public void enterCoursePlacementMode()
    {
        if (plugin.isStandardCourseLocked()) return;
        customCourseBuildMode = false; // mutually exclusive -- see that field's own doc
        minigameSpawnPlacementKey = null;
        courseConnectFromIndex = null;
        coursePlacementMode = true;
        plugin.refreshPanel();
    }

    public void cancelPresetMode()
    {
        coursePlacementMode = false;
        plugin.refreshPanel();
    }

    public void rotatePresetNext()
    {
        presetRotationSteps = (presetRotationSteps + 1) % 4;
    }

    public boolean isCustomCourseBuildMode()
    {
        return customCourseBuildMode;
    }

    public void enterCustomCourseBuildMode()
    {
        if (!plugin.isHost() || plugin.isStandardCourseLocked() || !plugin.isDevModeEnabled()) return;
        coursePlacementMode = false; // mutually exclusive -- see customCourseBuildMode's own doc
        minigameSpawnPlacementKey = null;
        customCourseBuildMode = true;
        courseConnectFromIndex = null;
        plugin.refreshPanel();
    }

    public void exitCustomCourseBuildMode()
    {
        customCourseBuildMode = false;
        courseConnectFromIndex = null;
        plugin.refreshPanel();
    }

    public boolean isMinigameSpawnPlacementMode()
    {
        return minigameSpawnPlacementKey != null;
    }

    public String getMinigameSpawnPlacementKey()
    {
        return minigameSpawnPlacementKey;
    }

    /** Arms "Place Minigame" -- unlike enterCoursePlacementMode, NOT refused once the course is a
     * locked Standard Course: this isn't editing the course itself, just configuring where one
     * mini-game's own arena goes for this specific game, which is just as meaningful on a locked
     * Fally Park/Varrock Square as on a freeform course (see the server's own
     * set_minigame_spawn_point doc). IS Dev-Mode-gated, though, same reasoning
     * enterCustomCourseBuildMode's own guard has: this whole panel is an authoring tool. */
    public void enterMinigameSpawnPlacementMode(String minigameKey)
    {
        if (!plugin.isHost() || !plugin.isDevModeEnabled() || !RunePartyPlugin.BOARD_SWAPPING_MINIGAME_KEYS.contains(minigameKey)) return;
        coursePlacementMode = false; // mutually exclusive -- see minigameSpawnPlacementKey's own doc
        customCourseBuildMode = false;
        courseConnectFromIndex = null;
        minigameSpawnPlacementKey = minigameKey;
        plugin.refreshPanel();
    }

    public void cancelMinigameSpawnPlacementMode()
    {
        minigameSpawnPlacementKey = null;
        plugin.refreshPanel();
    }

    /** Same "Walk here" -> custom RUNELITE entries idiom as addPresetMenuEntries, for a single
     * point instead of a whole tile footprint -- offered on whatever ground tile is currently
     * hovered, same as course placement (not restricted to an existing course tile at all). */
    public void addMinigameSpawnMenuEntries()
    {
        Tile tile = plugin.client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint point = tile.getWorldLocation();
        if (point == null) return;
        String minigameKey = minigameSpawnPlacementKey;
        if (minigameKey == null) return;
        Minigame minigame = Minigames.get(minigameKey);

        plugin.client.createMenuEntry(-1)
            .setOption("Cancel")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelMinigameSpawnPlacementMode());

        plugin.client.createMenuEntry(-1)
            .setOption("<col=00FF00>Place " + minigame.getDisplayName() + " Here</col>")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> commitMinigameSpawnPoint(minigameKey, point));
    }

    private void commitMinigameSpawnPoint(String minigameKey, WorldPoint point)
    {
        cancelMinigameSpawnPlacementMode();
        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        if (gid == null || wk == null) return;

        plugin.submitAction("Place mini-game spawn point",
            () -> plugin.apiClient.setMinigameSpawnPoint(gid, wk, minigameKey, point.getX(), point.getY(), point.getPlane()));
    }

    /** Resets a mini-game back to the default bounding-box-center placement -- called from
     * RunePartyPanel's own "Clear" button, only ever enabled once getMinigameSpawnPoint(key)
     * already returns non-null for the selected mini-game. */
    public void clearMinigameSpawnPoint(String minigameKey)
    {
        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        if (gid == null || wk == null || minigameKey == null) return;

        plugin.submitAction("Clear mini-game spawn point",
            () -> plugin.apiClient.clearMinigameSpawnPoint(gid, wk, minigameKey));
    }

    /** Unmarks every currently-committed course tile -- the host's "start over" button. Stays a
     * real server call unconditionally (Clear Course is always visible, Dev Mode or not -- see
     * this class's own doc), but ALSO clears every point locally first, synchronously: if any
     * Dev-Mode-only local tiles (see setCustomTileAt et al.) are staged alongside/on top of the
     * real board, the server was never told about them, so its own unmark response for them is a
     * harmless no-op that would otherwise leave them stuck on screen forever (nothing else ever
     * touches them again). Clearing locally too guarantees this button always fully clears
     * whatever's on screen, real or local, and happens instantly rather than waiting on the
     * server round-trip even for the ordinary (no local tiles) case. */
    public void clearCourse()
    {
        Set<WorldPoint> uniquePoints = new HashSet<>();
        for (TileReducer.TileEntry entry : plugin.getTileReducer().snapshot()) uniquePoints.add(entry.point);
        if (uniquePoints.isEmpty()) return;

        for (WorldPoint wp : uniquePoints) plugin.getTileReducer().unmarkLocal(wp, null);

        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        if (gid == null || wk == null) return;

        List<ApiClient.PointSpec> pointSpecs = new ArrayList<>(uniquePoints.size());
        for (WorldPoint wp : uniquePoints)
        {
            pointSpecs.add(new ApiClient.PointSpec(wp.getX(), wp.getY(), wp.getPlane(), null));
        }

        plugin.submitAction("Clear course", () -> plugin.apiClient.unmarkTiles(gid, wk, pointSpecs));
    }

    public void addPresetMenuEntries()
    {
        Tile tile = plugin.client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint center = tile.getWorldLocation();
        if (center == null) return;
        CoursePreset preset = selectedPreset;
        if (preset == null) return;

        plugin.client.createMenuEntry(-1)
            .setOption("Cancel")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelPresetMode());

        plugin.client.createMenuEntry(-1)
            .setOption("Rotate Course")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> rotatePresetNext());

        int degrees = presetRotationSteps * 90;
        String suffix = degrees != 0 ? " (" + degrees + "°)" : "";
        plugin.client.createMenuEntry(-1)
            .setOption("<col=00FF00>Place " + preset.name + suffix + "</col>")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> commitPreset(center));
    }

    private void commitPreset(WorldPoint center)
    {
        CoursePreset preset = selectedPreset;
        int rotationSteps = presetRotationSteps;
        cancelPresetMode();
        if (!plugin.isHost() || plugin.gameId == null || preset == null) return;

        List<CoursePreset.PlacedTile> placed = preset.layout(center, rotationSteps);
        List<ApiClient.TileSpec> tileSpecs = new ArrayList<>(placed.size());
        for (int i = 0; i < placed.size(); i++)
        {
            CoursePreset.PlacedTile pt = placed.get(i);
            // List order is path order (see CoursePreset's own class doc) -- this is the one
            // place that turns "position i in the list" into an explicit pathIndex, since once
            // this leaves as a TileSpec the server/TileReducer only ever see unordered tiles. A
            // decorative tile (see PlacedTile#decorative) gets no pathIndex at all instead -- it's
            // a modifier stacked on another tile's position, not a course stop of its own.
            Integer pathIndex = pt.decorative ? null : i;
            tileSpecs.add(new ApiClient.TileSpec(pt.point.getX(), pt.point.getY(), pt.point.getPlane(), pt.tileType, pt.color, null, pathIndex, pt.nextIndices));
        }

        final String gid = plugin.gameId;
        final String wk = plugin.writeKey;
        plugin.submitAction("Commit course", () -> plugin.apiClient.markTiles(gid, wk, tileSpecs));
    }

    /** Same "Walk here" -> custom RUNELITE entries idiom as addPresetMenuEntries, for free-form
     * course building -- one tile at a time instead of a whole preset stamped down atomically.
     * Two mutually exclusive sub-modes, switched on courseConnectFromIndex:
     * <p>
     * Not connecting (courseConnectFromIndex == null): a "Set Tile" submenu (see addSetTileSubmenu
     * -- places a new tile here, or retypes the one already here in place, preserving its
     * pathIndex/nextIndices), plus "Connect From"/"Remove All Connections" (only once nextIndices
     * is actually non-empty)/"Remove Tile" once the hovered spot already holds a course tile.
     * <p>
     * Connecting (courseConnectFromIndex != null): delegates to addCourseConnectMenuEntries for
     * "Connect To"/"Remove Connection" against whichever other tile is hovered, plus "Cancel
     * Connecting". */
    public void addCustomCourseBuildMenuEntries()
    {
        Tile tile = plugin.client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint point = tile.getWorldLocation();
        if (point == null) return;

        Integer connectFrom = courseConnectFromIndex;
        if (connectFrom != null)
        {
            addCourseConnectMenuEntries(point, connectFrom);
            return;
        }

        plugin.client.createMenuEntry(-1)
            .setOption("Cancel Building")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> exitCustomCourseBuildMode());

        TileReducer.TileEntry existing = courseTileAt(point);
        if (existing != null)
        {
            plugin.client.createMenuEntry(-1)
                .setOption("Connect From")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> { courseConnectFromIndex = existing.pathIndex; plugin.refreshPanel(); });

            if (existing.nextIndices.length > 0)
            {
                plugin.client.createMenuEntry(-1)
                    .setOption("<col=FF0000>Remove All Connections</col>")
                    .setTarget("")
                    .setType(MenuAction.RUNELITE)
                    .onClick(me -> removeAllConnectionsAt(point));
            }

            plugin.client.createMenuEntry(-1)
                .setOption("<col=FF0000>Remove Tile</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> removeCustomTileAt(point, existing.tileType));
        }

        addSetTileSubmenu(point);
    }

    /** "Set Tile" -> one entry per host-placeable tile type (see MenuEntry#createSubMenu),
     * populated from the already-fetched catalog (getTileTypeCatalog) rather than a hardcoded
     * copy. Two kinds of catalog entry are filtered out: Golden Gnome/Coin Trap (isModifier) are
     * never host-authored directly, both are modifiers a separate dedicated flow places
     * dynamically during real play; Flame Field Boundary and any future mini-game-only type
     * (isMinigameTile) are never host-authored either, only ever spawned in bulk by a mini-game's
     * own board swap -- placing one here would just get swept away the next time a board swap
     * runs. */
    private void addSetTileSubmenu(WorldPoint point)
    {
        MenuEntry parent = plugin.client.createMenuEntry(-1)
            .setOption("Set Tile")
            .setTarget("")
            .setType(MenuAction.RUNELITE);

        Menu submenu = parent.createSubMenu();
        List<ApiClient.TileTypeOut> types = new ArrayList<>(plugin.getTileTypeCatalog().values());
        types.sort(Comparator.comparing(t -> t.displayName));
        for (ApiClient.TileTypeOut type : types)
        {
            if (type.isModifier || type.isMinigameTile) continue;
            submenu.createMenuEntry(-1)
                .setOption(type.displayName)
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> setCustomTileAt(point, type.key));
        }
    }

    /** Connecting half of addCustomCourseBuildMenuEntries, armed by "Connect From" -- offers
     * "Connect To" (add {@code point}'s own pathIndex to {@code fromIndex}'s outgoing edges) or
     * "Remove Connection" (remove it) depending on whether it's already there, plus "Cancel
     * Connecting". A no-op (beyond "Cancel Connecting") if {@code point} isn't itself a course
     * tile, is the armed source tile itself, or the armed source has since been removed out from
     * under this -- same "doesn't offer an option the action method would just no-op/reject
     * anyway" restraint every sibling menu-entry method here already takes. */
    private void addCourseConnectMenuEntries(WorldPoint point, int fromIndex)
    {
        plugin.client.createMenuEntry(-1)
            .setOption("Cancel Connecting")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> { courseConnectFromIndex = null; plugin.refreshPanel(); });

        TileReducer.TileEntry target = courseTileAt(point);
        if (target == null || target.pathIndex == null || target.pathIndex.equals(fromIndex)) return;

        TileReducer.TileEntry source = plugin.getTileReducer().tileAtIndex(fromIndex);
        if (source == null) return; // armed source was removed out from under this -- nothing left to connect from

        boolean alreadyConnected = false;
        for (int idx : plugin.getTileReducer().resolveNextIndices(source))
        {
            if (idx == target.pathIndex) { alreadyConnected = true; break; }
        }

        if (alreadyConnected)
        {
            plugin.client.createMenuEntry(-1)
                .setOption("<col=FF0000>Remove Connection</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> removeCustomConnection(source, target.pathIndex));
        }
        else
        {
            plugin.client.createMenuEntry(-1)
                .setOption("<col=00FF00>Connect To</col>")
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> connectCustomTiles(source, target.pathIndex));
        }
    }

    /** The course tile (has its own pathIndex) at {@code point}, or null -- see TileEntry#pathIndex's
     * own doc for why a null pathIndex is exactly "not a course stop of its own" (a modifier).
     * Scans TileReducer's live snapshot directly, since it's the one source of truth -- course sizes
     * are small and this is only ever called from a menu-build callback, never a hot path. */
    private TileReducer.TileEntry courseTileAt(WorldPoint point)
    {
        for (TileReducer.TileEntry entry : plugin.getTileReducer().snapshot())
        {
            if (entry.pathIndex != null && entry.point.equals(point)) return entry;
        }
        return null;
    }

    /** The world point of the tile currently armed via "Connect From" (courseConnectFromIndex), or
     * null if nothing's armed -- see TileOverlay#renderConnectFromIndicator, the only reader,
     * which shows a player which tile that is. Resolves the armed pathIndex back through
     * TileReducer's own live snapshot, returning null rather than a stale point if that tile's
     * since been removed out from under the armed state. */
    public WorldPoint getCourseConnectFromPoint()
    {
        Integer fromIndex = courseConnectFromIndex;
        if (fromIndex == null) return null;
        TileReducer.TileEntry entry = plugin.getTileReducer().tileAtIndex(fromIndex);
        return entry != null ? entry.point : null;
    }

    /** Places (or retypes in place) a course tile at {@code point} -- called from the "Set Tile"
     * submenu (see addSetTileSubmenu). If {@code point} already holds a course tile, this keeps
     * its existing pathIndex/nextIndices and just swaps tileType -- fixing a mistake without
     * breaking whatever already links to/from it. Otherwise it's a brand new tile, appended at
     * tileReducer.courseLength() (one past the current highest pathIndex) with no nextIndices at
     * all -- placement alone never implies a connection to anything, so a freshly placed tile sits
     * disconnected until the host explicitly wires it up via "Connect From"/"Connect To" (see
     * connectCustomTiles). Deliberately doesn't reassign a fresh index after a mid-course removal
     * left a gap -- courseLength() naturally reuses the freed slot on its own. */
    private void setCustomTileAt(WorldPoint point, String tileTypeKey)
    {
        TileReducer.TileEntry existing = courseTileAt(point);
        Integer pathIndex = existing != null ? existing.pathIndex : plugin.getTileReducer().courseLength();
        int[] nextIndices = existing != null ? existing.nextIndices : new int[0];

        plugin.getTileReducer().markLocal(point, tileTypeKey, null, null, pathIndex, nextIndices);
    }

    /** Unmarks a single course tile -- called from the "Remove Tile" menu entry. Deliberately
     * doesn't rewrite anyone else's nextIndices to route around the gap it leaves behind: a
     * dangling edge (explicit or default) is left for the host to notice -- RunePartyMapOverlay's
     * own route lines make this visible immediately -- and fix by hand, rather than this guessing
     * at which rewrite is "correct" when the removed tile was itself a fork point or a merge
     * target. */
    private void removeCustomTileAt(WorldPoint point, String tileTypeKey)
    {
        plugin.getTileReducer().unmarkLocal(point, tileTypeKey);
    }

    /** Bulk version of removeCustomConnection -- clears {@code point}'s own nextIndices back to
     * empty in one call (a genuine dead end, no implicit fallback), rather than needing "Remove
     * Connection" once per existing target -- called from "Remove All Connections" (only offered
     * once nextIndices is actually non-empty). */
    private void removeAllConnectionsAt(WorldPoint point)
    {
        TileReducer.TileEntry existing = courseTileAt(point);
        if (existing == null || existing.pathIndex == null) return;

        plugin.getTileReducer().markLocal(existing.point, existing.tileType, existing.color, existing.orientation, existing.pathIndex, new int[0]);
    }

    /** Adds {@code targetIndex} to {@code source}'s own outgoing edges -- called from "Connect To"
     * (see addCourseConnectMenuEntries). Additive, not replacing: keeps whatever edges source
     * already had and just appends the new one, so connecting a second target turns a straight
     * edge into a fork rather than silently dropping the first. Clears courseConnectFromIndex
     * optimistically on submit, same client-local-mode reasoning RunePartyPlugin's own
     * cancelItemPlacement follows. */
    private void connectCustomTiles(TileReducer.TileEntry source, int targetIndex)
    {
        courseConnectFromIndex = null;
        plugin.refreshPanel();
        if (source.pathIndex == null) return;

        List<Integer> edges = new ArrayList<>();
        for (int idx : plugin.getTileReducer().resolveNextIndices(source)) edges.add(idx);
        if (!edges.contains(targetIndex)) edges.add(targetIndex);

        plugin.getTileReducer().markLocal(source.point, source.tileType, source.color, source.orientation, source.pathIndex, toIntArray(edges));
    }

    /** Removes {@code targetIndex} from {@code source}'s own outgoing edges -- called from
     * "Remove Connection" (see addCourseConnectMenuEntries). If that empties the list entirely,
     * this sends an empty nextIndices array rather than omitting the field -- the server treats
     * the two identically, and either way source is now a genuine dead end, same as
     * removeAllConnectionsAt's bulk version of this. */
    private void removeCustomConnection(TileReducer.TileEntry source, int targetIndex)
    {
        courseConnectFromIndex = null;
        plugin.refreshPanel();
        if (source.pathIndex == null) return;

        List<Integer> edges = new ArrayList<>();
        for (int idx : plugin.getTileReducer().resolveNextIndices(source)) if (idx != targetIndex) edges.add(idx);

        plugin.getTileReducer().markLocal(source.point, source.tileType, source.color, source.orientation, source.pathIndex, toIntArray(edges));
    }

    private static int[] toIntArray(List<Integer> list)
    {
        int[] arr = new int[list.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = list.get(i);
        return arr;
    }

    public void reset()
    {
        coursePlacementMode = false;
        selectedPreset = null;
        presetRotationSteps = 0;
        customCourseBuildMode = false;
        courseConnectFromIndex = null;
        minigameSpawnPlacementKey = null;
    }

    public boolean isCoursePlacementMode() { return coursePlacementMode; }
    public CoursePreset getSelectedPreset() { return selectedPreset; }
    public int getPresetRotationSteps() { return presetRotationSteps; }

    /** Built-in CoursePreset.ALL plus every course previously saved via saveCurrentCourseAs --
     * for RunePartyPanel's presetDropdown. Available regardless of Dev Mode: placing an
     * already-saved course is an everyday "Place" action for any host, not a dev-only one -- only
     * the authoring tool that PRODUCES a saved course is Dev-Mode-gated (see this class's own
     * doc). */
    public List<CoursePreset> getAvailablePresets()
    {
        List<CoursePreset> all = new ArrayList<>(CoursePreset.ALL);
        all.addAll(localPresets);
        return all;
    }

    private File coursesDir()
    {
        return new File(new File(RuneLite.RUNELITE_DIR, "rune-party"), "courses");
    }

    /** (Re)scans coursesDir() for every *.json file and deserializes it into a CoursePreset,
     * populating localPresets -- called once at construction and again after every successful
     * saveCurrentCourseAs so a freshly-saved course shows up in the dropdown immediately, no
     * restart needed. A file that fails to parse is logged and skipped rather than aborting the
     * whole load -- one corrupt/hand-edited file shouldn't hide every other saved course. */
    private void reloadLocalPresets()
    {
        File[] files = coursesDir().listFiles((dir, name) -> name.endsWith(".json"));
        List<CoursePreset> loaded = new ArrayList<>();
        if (files != null)
        {
            Arrays.sort(files, Comparator.comparing(File::getName));
            for (File f : files)
            {
                try (var reader = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
                {
                    SavedCourseFile dto = FILE_GSON.fromJson(reader, SavedCourseFile.class);
                    if (dto != null) loaded.add(dto.toCoursePreset());
                }
                catch (IOException | JsonParseException e)
                {
                    log.warn("Failed to load saved course file {}", f.getName(), e);
                }
            }
        }
        localPresets = loaded;
    }

    /** "Save Current Course" button's action -- captures whatever's currently on the board (local
     * edits and/or previously-placed real tiles, merged, since both live in the same TileReducer)
     * via the existing CoursePreset.fromTiles, and writes it to coursesDir() as a local JSON file
     * (see SavedCourseFile). Dev-Mode-gated, same as every other authoring entry point in this
     * class, even though the only caller (the panel button) is already hidden otherwise. Returns
     * the file written, or null on a blank name, an empty board (nothing worth saving), or a write
     * failure. Overwrites any existing file with the same sanitized name -- an ordinary, expected
     * "Save" overwrite, not a bug (see sanitizeFileName's own doc on same-sanitized-name
     * collisions between differently-named courses). */
    public File saveCurrentCourseAs(String rawName)
    {
        if (!plugin.isDevModeEnabled()) return null;
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) return null;

        CoursePreset preset = CoursePreset.fromTiles(name, plugin.getTileReducer().snapshot());
        if (preset.tiles.isEmpty()) return null;

        File dir = coursesDir();
        if (!dir.exists() && !dir.mkdirs()) return null;
        File file = new File(dir, sanitizeFileName(name) + ".json");

        try (var writer = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8))
        {
            FILE_GSON.toJson(SavedCourseFile.from(preset), writer);
        }
        catch (IOException e)
        {
            log.warn("Failed to save course {}", name, e);
            return null;
        }

        reloadLocalPresets();
        return file;
    }

    /** Strips a course's display name down to a safe filename -- note that two different display
     * names can sanitize to the same result (e.g. "My Course!" and "My Course?" both become
     * "My_Course"), in which case saving the second silently overwrites the first's file, same as
     * saving under the exact same name does. An accepted tradeoff, not something this needs to
     * guard against. */
    private static String sanitizeFileName(String name)
    {
        String sanitized = name.replaceAll("[^a-zA-Z0-9 _-]", "").trim().replaceAll("\\s+", "_");
        return sanitized.isEmpty() ? "course" : sanitized;
    }
}
