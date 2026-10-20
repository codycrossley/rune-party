package gay.runescape.runeparty.courses;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TileReducer;
import gay.runescape.runeparty.net.ApiClient;

import net.runelite.api.MenuAction;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Host-only course building (LOBBY only), extracted out of RunePartyPlugin: pick a preset, enter
 * Place mode, then right-click a ground tile to commit its footprint there -- there's no
 * per-preset removal, clearCourse() below is the host's one "start over" tool.
 * <p>
 * Every action here is a real, synchronous request the server either accepts or rejects, mirrored
 * back into TileReducer's own live board the same way any other tiles_marked update is -- this
 * sets up the REAL board every lobby member sees. */
public final class CourseBuilder
{
    private final RunePartyPlugin plugin;

    private volatile boolean coursePlacementMode = false;
    private volatile CoursePreset selectedPreset = null;
    private volatile int presetRotationSteps = 0; // quarter-turns clockwise: 0/1/2/3 = 0/90/180/270 degrees

    public CourseBuilder(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void selectPreset(CoursePreset preset)
    {
        selectedPreset = preset;
        plugin.refreshPanel();
    }

    public void enterCoursePlacementMode()
    {
        if (plugin.isStandardCourseLocked()) return;
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

    /** Unmarks every currently-committed course tile -- the host's "start over" button. Stays a
     * real server call unconditionally, but ALSO clears every point locally first, synchronously,
     * so the board visibly clears instantly instead of waiting on the server round-trip. */
    public void clearCourse()
    {
        Set<WorldPoint> uniquePoints = new HashSet<>();
        for (TileReducer.TileEntry entry : plugin.tileReducer.snapshot()) uniquePoints.add(entry.point);
        if (uniquePoints.isEmpty()) return;

        for (WorldPoint wp : uniquePoints) plugin.tileReducer.unmarkLocal(wp, null);

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

    public void reset()
    {
        coursePlacementMode = false;
        selectedPreset = null;
        presetRotationSteps = 0;
    }

    public boolean isCoursePlacementMode() { return coursePlacementMode; }
    public CoursePreset getSelectedPreset() { return selectedPreset; }
    public int getPresetRotationSteps() { return presetRotationSteps; }

    /** Built-in presets only -- for RunePartyPanel's presetDropdown. */
    public List<CoursePreset> getAvailablePresets()
    {
        return new ArrayList<>(CoursePreset.ALL);
    }
}
