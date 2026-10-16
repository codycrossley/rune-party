package gay.runescape.runeparty.courses;

import java.util.ArrayList;
import java.util.List;

/** On-disk shape of a locally-saved course (see CourseBuilder#saveCurrentCourseAs/
 * reloadLocalPresets). Deliberately a separate, flat, Gson-friendly mirror of
 * CoursePreset/RelativeTile rather than serializing those classes directly -- RelativeTile's own
 * invariant (a decorative tile must be listed after every non-decorative one) is a call-site
 * contract, not something its constructor enforces, and Gson's reflective deserialization bypasses
 * constructors entirely, so round-tripping it directly would silently accept a corrupt/hand-edited
 * file that violates that contract. */
public final class SavedCourseFile
{
    public String name;
    public List<TileDto> tiles;

    public static final class TileDto
    {
        public int dx;
        public int dy;
        public String tileType;
        public String color;
        public int[] nextIndices;
        public boolean decorative;
    }

    public static SavedCourseFile from(CoursePreset preset)
    {
        SavedCourseFile file = new SavedCourseFile();
        file.name = preset.name;
        file.tiles = new ArrayList<>();
        for (CoursePreset.RelativeTile rt : preset.tiles)
        {
            TileDto dto = new TileDto();
            dto.dx = rt.dx;
            dto.dy = rt.dy;
            dto.tileType = rt.tileType;
            dto.color = rt.color;
            dto.nextIndices = rt.nextIndices;
            dto.decorative = rt.decorative;
            file.tiles.add(dto);
        }
        return file;
    }

    public CoursePreset toCoursePreset()
    {
        List<CoursePreset.RelativeTile> relTiles = new ArrayList<>();
        if (tiles != null)
        {
            for (TileDto dto : tiles)
            {
                relTiles.add(new CoursePreset.RelativeTile(dto.dx, dto.dy, dto.tileType, dto.color, dto.decorative, dto.nextIndices));
            }
        }
        return new CoursePreset(name, relTiles);
    }
}
