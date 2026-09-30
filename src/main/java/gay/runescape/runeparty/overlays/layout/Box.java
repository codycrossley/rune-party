package gay.runescape.runeparty.overlays.layout;

import gay.runescape.runeparty.overlays.RunePartyRender;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.util.ArrayList;
import java.util.List;

/** A container node that stacks its children along one axis (a plain box-model, the same shape
 * every hand-rolled "card" overlay in this codebase already reimplements per-call-site -- see
 * this package's own top-level doc). {@code measure}/{@code paint} both walk {@code children} in
 * order; each is deliberately re-measured in both passes rather than cached between them (cheap
 * here -- text/box measurement is a few {@code FontMetrics} calls, not a real layout pass -- and
 * it avoids a measure/paint result ever silently drifting out of sync). */
public final class Box extends Node
{
    public enum Direction { ROW, COLUMN }

    /** Cross-axis alignment -- perpendicular to whichever way this Box stacks. */
    public enum Align { START, CENTER, END }

    private final Direction direction;
    private final List<Node> children = new ArrayList<>();
    private int gap = 0;
    private int paddingTop, paddingRight, paddingBottom, paddingLeft;
    private Align align = Align.CENTER;
    private Color background;
    private int backgroundCornerRadius;
    private Color borderColor;
    private float borderWidth = 1f;
    private boolean wrap = false;

    private Box(Direction direction, Node... initial)
    {
        this.direction = direction;
        for (Node n : initial)
        {
            if (n != null) children.add(n);
        }
    }

    public static Box row(Node... children)
    {
        return new Box(Direction.ROW, children);
    }

    public static Box column(Node... children)
    {
        return new Box(Direction.COLUMN, children);
    }

    public Box add(Node child)
    {
        children.add(child);
        return this;
    }

    public Box gap(int px)
    {
        this.gap = px;
        return this;
    }

    public Box padding(int vertical, int horizontal)
    {
        this.paddingTop = vertical;
        this.paddingBottom = vertical;
        this.paddingLeft = horizontal;
        this.paddingRight = horizontal;
        return this;
    }

    public Box padding(int top, int right, int bottom, int left)
    {
        this.paddingTop = top;
        this.paddingRight = right;
        this.paddingBottom = bottom;
        this.paddingLeft = left;
        return this;
    }

    /** Default CENTER. */
    public Box align(Align a)
    {
        this.align = a;
        return this;
    }

    /** Paints a filled rounded-rect behind the children, sized to this Box's own measured bounds
     * -- the "card" background every hand-rolled overlay (CoinRushScoreboardOverlay,
     * TurfWarsScoreOverlay, HotPotatoOverlay) currently re-implements with its own local padding
     * constants and a bare {@code fillRoundRect} call. */
    public Box background(Color fill, int cornerRadius)
    {
        this.background = fill;
        this.backgroundCornerRadius = cornerRadius;
        return this;
    }

    public Box border(Color color, float strokeWidth)
    {
        this.borderColor = color;
        this.borderWidth = strokeWidth;
        return this;
    }

    /** ROW-only: once children's combined width would exceed the available room, wraps overflow
     * into additional rows, each independently centered (or start/end-aligned, per {@link
     * #align}) -- the declarative equivalent of TurfWarsScoreOverlay's own hand-rolled
     * chunk-into-rows flow layout. Not exercised by the first migration batch; included so a
     * later pass can fold that overlay's own duplicate logic into this engine. Ignored for a
     * COLUMN box. */
    public Box wrap(boolean on)
    {
        this.wrap = on;
        return this;
    }

    @Override
    public Dimension measure(Graphics2D g, int maxWidth)
    {
        int innerMax = Math.max(0, maxWidth - paddingLeft - paddingRight);

        if (direction == Direction.COLUMN)
        {
            int width = 0, height = 0;
            for (int i = 0; i < children.size(); i++)
            {
                Dimension d = children.get(i).measure(g, innerMax);
                width = Math.max(width, d.width);
                height += d.height;
                if (i < children.size() - 1) height += gap;
            }
            return new Dimension(width + paddingLeft + paddingRight, height + paddingTop + paddingBottom);
        }

        if (wrap)
        {
            return measureWrappedRow(g, innerMax);
        }

        int width = 0, height = 0;
        for (int i = 0; i < children.size(); i++)
        {
            Dimension d = children.get(i).measure(g, innerMax);
            width += d.width;
            height = Math.max(height, d.height);
            if (i < children.size() - 1) width += gap;
        }
        return new Dimension(width + paddingLeft + paddingRight, height + paddingTop + paddingBottom);
    }

    @Override
    public void paint(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        float a = Math.max(0f, Math.min(1f, alpha * ownOpacity()));
        Dimension size = measure(g, maxWidth);

        if (background != null)
        {
            g.setColor(RunePartyRender.withAlpha(background, a));
            g.fillRoundRect(x, y, size.width, size.height, backgroundCornerRadius, backgroundCornerRadius);
        }
        if (borderColor != null)
        {
            Stroke original = g.getStroke();
            g.setStroke(new BasicStroke(borderWidth));
            g.setColor(RunePartyRender.withAlpha(borderColor, a));
            g.drawRoundRect(x, y, size.width - 1, size.height - 1, backgroundCornerRadius, backgroundCornerRadius);
            g.setStroke(original);
        }

        int innerX = x + paddingLeft;
        int innerY = y + paddingTop;
        int innerWidth = size.width - paddingLeft - paddingRight;
        int innerHeight = size.height - paddingTop - paddingBottom;
        int innerMax = Math.max(0, maxWidth - paddingLeft - paddingRight);

        if (direction == Direction.COLUMN)
        {
            int cursor = innerY;
            for (Node child : children)
            {
                Dimension d = child.measure(g, innerMax);
                int childX = alignOffset(innerX, innerWidth, d.width);
                child.paint(g, childX, cursor + child.verticalAnchor(g, innerMax), innerMax, a);
                cursor += d.height + gap;
            }
            return;
        }

        if (!wrap)
        {
            int cursor = innerX;
            for (Node child : children)
            {
                Dimension d = child.measure(g, innerMax);
                int childY = alignOffset(innerY, innerHeight, d.height);
                child.paint(g, cursor, childY + child.verticalAnchor(g, innerMax), innerMax, a);
                cursor += d.width + gap;
            }
            return;
        }

        int cursorY = innerY;
        for (List<Node> rowChildren : chunkIntoRows(g, innerMax))
        {
            List<Dimension> dims = new ArrayList<>(rowChildren.size());
            int rowWidth = 0, rowHeight = 0;
            for (Node child : rowChildren)
            {
                Dimension d = child.measure(g, innerMax);
                dims.add(d);
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            rowWidth += gap * (rowChildren.size() - 1);

            int cursorX = alignOffset(innerX, innerWidth, rowWidth);
            for (int i = 0; i < rowChildren.size(); i++)
            {
                Node child = rowChildren.get(i);
                Dimension d = dims.get(i);
                int childY = alignOffset(cursorY, rowHeight, d.height);
                child.paint(g, cursorX, childY + child.verticalAnchor(g, innerMax), innerMax, a);
                cursorX += d.width + gap;
            }
            cursorY += rowHeight + gap;
        }
    }

    private Dimension measureWrappedRow(Graphics2D g, int innerMax)
    {
        List<List<Node>> rows = chunkIntoRows(g, innerMax);
        int width = 0, height = 0;
        for (int r = 0; r < rows.size(); r++)
        {
            List<Node> row = rows.get(r);
            int rowWidth = 0, rowHeight = 0;
            for (int i = 0; i < row.size(); i++)
            {
                Dimension d = row.get(i).measure(g, innerMax);
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
                if (i < row.size() - 1) rowWidth += gap;
            }
            width = Math.max(width, rowWidth);
            height += rowHeight;
            if (r < rows.size() - 1) height += gap;
        }
        return new Dimension(width + paddingLeft + paddingRight, height + paddingTop + paddingBottom);
    }

    private List<List<Node>> chunkIntoRows(Graphics2D g, int innerMax)
    {
        List<List<Node>> rows = new ArrayList<>();
        List<Node> current = new ArrayList<>();
        int currentWidth = 0;
        for (Node child : children)
        {
            Dimension d = child.measure(g, innerMax);
            int addWidth = d.width + (current.isEmpty() ? 0 : gap);
            if (!current.isEmpty() && currentWidth + addWidth > innerMax)
            {
                rows.add(current);
                current = new ArrayList<>();
                currentWidth = 0;
                addWidth = d.width;
            }
            current.add(child);
            currentWidth += addWidth;
        }
        if (!current.isEmpty()) rows.add(current);
        return rows;
    }

    private int alignOffset(int start, int available, int size)
    {
        switch (align)
        {
            case START:
                return start;
            case END:
                return start + available - size;
            case CENTER:
            default:
                return start + (available - size) / 2;
        }
    }
}
