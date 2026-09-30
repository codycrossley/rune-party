package gay.runescape.runeparty.overlays.layout;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** N rows of {@link Segment}s (e.g. rank + name + stats), jointly fit-scaled from whichever row is
 * WIDEST -- not each row independently -- so a whole standings/roster table shrinks together and
 * stays column-aligned rather than drifting row to row. Replicates AnnouncementOverlay's own {@code
 * drawPlayerRows}/{@code drawStandingsLine} exactly, including a detail that matters for pixel
 * fidelity: {@link #rowHeight} is a fixed, caller-supplied pixel step between row baselines --
 * never derived from font metrics -- that itself scales down proportionally once a shared shrink
 * applies. This is why {@code Table} is not built as "a {@link Box} of {@link Line}s": {@code Box}
 * stacks by real measured height + gap, which would silently break this fixed-step behavior the
 * moment a row's own font metrics disagree with its hand-tuned pixel spacing. */
public final class Table extends Node
{
    private final List<List<Segment>> rows = new ArrayList<>();
    private int rowHeight = -1;
    private boolean fitToWidth = true;
    private float minFitScale = 0.5f;
    private boolean shadow = true;

    private Table()
    {
    }

    public static Table rows()
    {
        return new Table();
    }

    public Table addRow(Segment... segments)
    {
        rows.add(Arrays.asList(segments));
        return this;
    }

    /** REQUIRED -- the fixed pixel step between row baselines (mirrors the original {@code
     * drawPlayerRows}' own {@code lineHeight} parameter). Not derived from {@code FontMetrics};
     * scales down alongside every row's own fonts once {@link #fitToWidth} kicks in. */
    public Table rowHeight(int px)
    {
        this.rowHeight = px;
        return this;
    }

    public Table fitToWidth(boolean on)
    {
        this.fitToWidth = on;
        return this;
    }

    public Table minFitScale(float f)
    {
        this.minFitScale = f;
        return this;
    }

    public Table shadow(boolean on)
    {
        this.shadow = on;
        return this;
    }

    @Override
    public Dimension measure(Graphics2D g, int maxWidth)
    {
        Resolved r = resolve(g, maxWidth);
        int widest = 0;
        for (List<SegmentRow.Resolved> row : r.rows) widest = Math.max(widest, SegmentRow.sumWidth(row));
        return new Dimension(widest, r.scaledRowHeight * rows.size());
    }

    @Override
    public void paint(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        float a = Math.max(0f, Math.min(1f, alpha * ownOpacity()));
        Resolved r = resolve(g, maxWidth);

        // Reconstructs the caller's own original centerX exactly: Layout.renderCentered computed
        // x = centerX0 - measuredWidth/2 before calling paint, so x + measuredWidth/2 == centerX0
        // (the same truncating integer division cancels both ways) -- letting each row re-center
        // itself around that point exactly like drawStandingsLine's own per-row centering did.
        int tableWidth = 0;
        for (List<SegmentRow.Resolved> row : r.rows) tableWidth = Math.max(tableWidth, SegmentRow.sumWidth(row));
        int centerX = x + tableWidth / 2;

        int rowY = y;
        for (List<SegmentRow.Resolved> row : r.rows)
        {
            int rowX = centerX - SegmentRow.sumWidth(row) / 2;
            SegmentRow.paint(g, row, rowX, rowY, a, shadow);
            rowY += r.scaledRowHeight;
        }
    }

    @Override
    int verticalAnchor(Graphics2D g, int maxWidth)
    {
        Resolved r = resolve(g, maxWidth);
        return r.rows.isEmpty() ? 0 : SegmentRow.maxAscent(r.rows.get(0));
    }

    private Resolved resolve(Graphics2D g, int maxWidth)
    {
        if (rowHeight <= 0)
        {
            throw new IllegalStateException("Table has no rowHeight set -- call .rowHeight(px) before measure()/paint()");
        }

        List<List<SegmentRow.Resolved>> natural = new ArrayList<>(rows.size());
        int widest = 0;
        for (List<Segment> row : rows)
        {
            List<SegmentRow.Resolved> resolved = SegmentRow.resolve(g, row, 1f);
            natural.add(resolved);
            widest = Math.max(widest, SegmentRow.sumWidth(resolved));
        }

        if (fitToWidth && maxWidth > 0 && widest > maxWidth)
        {
            float scale = Math.max(minFitScale, maxWidth / (float) widest);
            List<List<SegmentRow.Resolved>> scaled = new ArrayList<>(rows.size());
            for (List<Segment> row : rows) scaled.add(SegmentRow.resolve(g, row, scale));
            return new Resolved(scaled, Math.round(rowHeight * scale));
        }
        return new Resolved(natural, rowHeight);
    }

    private static final class Resolved
    {
        final List<List<SegmentRow.Resolved>> rows;
        final int scaledRowHeight;

        Resolved(List<List<SegmentRow.Resolved>> rows, int scaledRowHeight)
        {
            this.rows = rows;
            this.scaledRowHeight = scaledRowHeight;
        }
    }
}
