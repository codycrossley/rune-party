package gay.runescape.runeparty.overlays.layout;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Arrays;
import java.util.List;

/** One line built from multiple chained {@link Segment}s -- e.g. plain text, a highlighted rainbow
 * word, more plain text -- jointly fit-scaled as a single unit rather than each segment shrinking
 * independently. Replicates AnnouncementOverlay's own {@code drawEmoteInstruction}/{@code
 * renderSpinHintSelf} idiom: measure every segment's own natural width, and if their sum overflows
 * the room available, shrink every segment's font by the SAME factor before drawing, so a rainbow
 * word and the plain text around it never end up at mismatched sizes.
 * <p>
 * {@code y} in {@link #paint} is this node's own text baseline -- same deliberate convention {@link
 * Text} uses (see {@link Node#paint}'s own doc) -- so a bare {@code Line} handed straight to {@link
 * Layout#renderCentered} is a drop-in replacement for the old chained-{@code drawLeftAlignedText}
 * call sites, no y-math changes needed. */
public final class Line extends Node
{
    private final List<Segment> segments;
    private boolean fitToWidth = true;
    private float minFitScale = 0.5f;
    private boolean shadow = true;

    private Line(List<Segment> segments)
    {
        this.segments = segments;
    }

    public static Line of(Segment... segments)
    {
        return new Line(Arrays.asList(segments));
    }

    /** Default true -- shrinks every segment together toward {@link #minFitScale} rather than
     * overflow the room a parent hands this node. */
    public Line fitToWidth(boolean on)
    {
        this.fitToWidth = on;
        return this;
    }

    public Line minFitScale(float f)
    {
        this.minFitScale = f;
        return this;
    }

    public Line shadow(boolean on)
    {
        this.shadow = on;
        return this;
    }

    @Override
    public Dimension measure(Graphics2D g, int maxWidth)
    {
        List<SegmentRow.Resolved> resolved = resolve(g, maxWidth);
        return new Dimension(SegmentRow.sumWidth(resolved), SegmentRow.maxHeight(resolved));
    }

    @Override
    public void paint(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        float a = Math.max(0f, Math.min(1f, alpha * ownOpacity()));
        SegmentRow.paint(g, resolve(g, maxWidth), x, y, a, shadow);
    }

    @Override
    int verticalAnchor(Graphics2D g, int maxWidth)
    {
        return SegmentRow.maxAscent(resolve(g, maxWidth));
    }

    /** Same "measure, maybe shrink, re-measure at the derived font" pattern {@link
     * Text#measure}/{@code resolve} already uses -- one joint scale from the summed natural width,
     * not a per-segment fit. */
    private List<SegmentRow.Resolved> resolve(Graphics2D g, int maxWidth)
    {
        List<SegmentRow.Resolved> natural = SegmentRow.resolve(g, segments, 1f);
        int naturalWidth = SegmentRow.sumWidth(natural);
        if (fitToWidth && maxWidth > 0 && naturalWidth > maxWidth)
        {
            float scale = Math.max(minFitScale, maxWidth / (float) naturalWidth);
            return SegmentRow.resolve(g, segments, scale);
        }
        return natural;
    }
}
