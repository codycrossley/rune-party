package gay.runescape.runeparty.overlays.layout;

import java.awt.Color;
import java.awt.Font;
import java.util.Objects;

/** One styled run of text within a {@link Line}/{@link Table} row -- either a solid {@link #color}
 * or a {@link #rainbowPalette} cycling per non-space character, never both. {@code font} is the
 * base, unscaled size; {@link SegmentRow} derives a smaller one when a {@link Line}/{@link Table}
 * needs to jointly shrink every segment together. */
public final class Segment
{
    final String text;
    final Font font;
    final Color color;
    final Color[] rainbowPalette;

    private Segment(String text, Font font, Color color, Color[] rainbowPalette)
    {
        this.text = text;
        this.font = font;
        this.color = color;
        this.rainbowPalette = rainbowPalette;
    }

    public static Segment plain(String text, Font font, Color color)
    {
        return new Segment(text, font, Objects.requireNonNull(color, "color"), null);
    }

    public static Segment rainbow(String text, Font font, Color[] palette)
    {
        return new Segment(text, font, null, Objects.requireNonNull(palette, "palette"));
    }
}
