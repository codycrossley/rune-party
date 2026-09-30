package gay.runescape.runeparty.overlays.layout;

import java.awt.Dimension;
import java.awt.Graphics2D;

/** Base type for every node in a declarative render tree -- see {@link Box}, {@link Text}, {@link
 * Image}, {@link Custom}. A tree is built fresh each frame from live plugin state (cheap: these
 * are small, short-lived POJOs with no persistent state of their own) and handed to {@link
 * Layout}'s static entry points, rather than one hand-rolled {@code Graphics2D} method per banner
 * -- see AnnouncementOverlay's own migrated methods for the intended calling shape.
 * <p>
 * Two-phase like any other layout system: {@link #measure} first (pure, no drawing) so a {@link
 * Box} can position every child before any of them paints, then {@link #paint} actually draws.
 * Deliberately pure {@code java.awt}/{@code java.util.function} with no RuneLite dependency (no
 * {@code Client}, no {@code FontManager}) -- callers resolve fonts/colors/viewport bounds
 * themselves and hand this package already-resolved values, so the engine itself stays reusable
 * by any future overlay, not just AnnouncementOverlay. */
public abstract class Node
{
    private float opacity = 1f;

    /** Multiplies into whatever alpha this node is painted with -- default 1 (no dimming).
     * Rarely needed on its own since {@code BannerAnim} already produces one alpha per whole
     * banner; exists for the rare "this child drawn slightly dimmer than its sibling" case
     * without inventing a second animation concept inside this package. Returns {@code this} (as
     * {@code T}) so it chains in the same fluent style every node's own builder methods use. */
    @SuppressWarnings("unchecked")
    public final <T extends Node> T opacity(float o)
    {
        this.opacity = o;
        return (T) this;
    }

    final float ownOpacity()
    {
        return opacity;
    }

    /** This node's own footprint given at most {@code maxWidth} px of horizontal room. Must not
     * draw anything -- {@link Box#paint} calls this on every child before positioning any of
     * them, exactly like any other two-phase layout system. */
    public abstract Dimension measure(Graphics2D g, int maxWidth);

    /** Paints with this node's own top-left corner at {@code (x, y)}, constrained to the same
     * {@code maxWidth} the preceding {@link #measure} call used, at {@code alpha} in [0,1]
     * (already multiplied by every ancestor's own {@link #opacity} -- see {@link Layout}/{@link
     * Box}). {@code y} isn't literally "this node's own top pixel" for every node type -- see
     * {@link #verticalAnchor}'s own doc for the one exception ({@link Text}'s baseline). */
    public abstract void paint(Graphics2D g, int x, int y, int maxWidth, float alpha);

    /** How far below the box-model "y" this node's own {@link #paint} actually anchors its
     * content -- 0 for every node type except {@link Text} (which overrides this to return its
     * own font's ascent, so a baseline-oriented {@code drawString} call lands where a column
     * {@link Box}'s top-down height accounting expects). Package-private: only {@link Box}'s own
     * stacking loop needs this; nothing about this package's public API makes a caller reason
     * about baselines directly -- see Text's own doc for why this exists at all. */
    int verticalAnchor(Graphics2D g, int maxWidth)
    {
        return 0;
    }
}
