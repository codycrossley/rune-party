package gay.runescape.runeparty.overlays.layout;

import java.awt.Dimension;
import java.awt.Graphics2D;

/** Escape hatch for content that doesn't have a clean declarative shape -- the prize wheel, the
 * dice roll, the Chance Space tableau, the Rainbow Rush traffic light, all of which stay
 * hand-rolled {@code Graphics2D} methods (see this package's own top-level doc on why those are
 * explicitly NOT part of the first migration batch). Wrapping one of those methods in a {@code
 * Custom} node lets it sit <em>inside</em> a tree -- alongside a title {@link Text}, say -- and
 * inherit consistent alpha/positioning from a parent {@link Box}, without being force-rewritten
 * into pure declarative form. Not exercised by the first migration batch; built now so the
 * engine's shape (including this deliberate opt-out) is proven complete before that migration
 * lands. */
public final class Custom extends Node
{
    @FunctionalInterface
    public interface Painter
    {
        void paint(Graphics2D g, int x, int y, int width, int height, float alpha);
    }

    private final int width;
    private final int height;
    private final Painter painter;

    private Custom(int width, int height, Painter painter)
    {
        this.width = width;
        this.height = height;
        this.painter = painter;
    }

    /** For content whose own footprint is fixed at design time. */
    public static Custom of(int width, int height, Painter painter)
    {
        return new Custom(width, height, painter);
    }

    @Override
    public Dimension measure(Graphics2D g, int maxWidth)
    {
        return new Dimension(width, height);
    }

    @Override
    public void paint(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        float a = Math.max(0f, Math.min(1f, alpha * ownOpacity()));
        painter.paint(g, x, y, width, height, a);
    }
}
