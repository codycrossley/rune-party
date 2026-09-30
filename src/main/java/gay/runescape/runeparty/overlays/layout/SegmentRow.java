package gay.runescape.runeparty.overlays.layout;

import gay.runescape.runeparty.overlays.RunePartyRender;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

/** Shared "measure a chain of {@link Segment}s at a given scale, sum their natural width, paint
 * them chained left-to-right" logic behind both {@link Line} and {@link Table} -- own copy of
 * {@link Text}'s exact shadow idiom (same offset/dimming), not a shared reference, since {@code
 * Text}'s own constants are private and this package already accepts this shape of small
 * duplication elsewhere (see RunePartyRender#drawShadowed's own doc). */
final class SegmentRow
{
    private static final float SHADOW_ALPHA_SCALE = 0.7f;
    private static final int SHADOW_OFFSET = 2;

    private SegmentRow()
    {
    }

    static final class Resolved
    {
        final Segment segment;
        final Font font;
        final FontMetrics metrics;
        final int width;

        Resolved(Segment segment, Font font, FontMetrics metrics, int width)
        {
            this.segment = segment;
            this.font = font;
            this.metrics = metrics;
            this.width = width;
        }
    }

    /** Resolves every segment at its own base font scaled by {@code scale} (1f = unscaled). A
     * rainbow segment's width is the sum of its own per-character advances (matching {@link
     * Text#rainbow}'s own measurement, and the per-character loop {@link #paint} actually draws
     * with) rather than a whole-string measurement. */
    static List<Resolved> resolve(Graphics2D g, List<Segment> segments, float scale)
    {
        List<Resolved> resolved = new ArrayList<>(segments.size());
        for (Segment s : segments)
        {
            Font font = scale < 1f ? s.font.deriveFont(s.font.getSize2D() * scale) : s.font;
            FontMetrics fm = g.getFontMetrics(font);
            int width = s.rainbowPalette != null ? sumCharWidths(fm, s.text) : fm.stringWidth(s.text);
            resolved.add(new Resolved(s, font, fm, width));
        }
        return resolved;
    }

    static int sumWidth(List<Resolved> resolved)
    {
        int total = 0;
        for (Resolved r : resolved) total += r.width;
        return total;
    }

    static int maxAscent(List<Resolved> resolved)
    {
        int max = 0;
        for (Resolved r : resolved) max = Math.max(max, r.metrics.getAscent());
        return max;
    }

    static int maxHeight(List<Resolved> resolved)
    {
        int max = 0;
        for (Resolved r : resolved) max = Math.max(max, r.metrics.getHeight());
        return max;
    }

    /** Paints {@code resolved} chained left-to-right from {@code x}, with {@code y} as the shared
     * text baseline every segment draws against. Returns the x just past the last segment. */
    static int paint(Graphics2D g, List<Resolved> resolved, int x, int y, float alpha, boolean shadow)
    {
        int cx = x;
        for (Resolved r : resolved)
        {
            g.setFont(r.font);
            if (r.segment.rainbowPalette != null)
            {
                cx = paintRainbow(g, r, cx, y, alpha, shadow);
            }
            else
            {
                if (shadow)
                {
                    g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * SHADOW_ALPHA_SCALE));
                    g.drawString(r.segment.text, cx + SHADOW_OFFSET, y + SHADOW_OFFSET);
                }
                g.setColor(RunePartyRender.withAlpha(r.segment.color, alpha));
                g.drawString(r.segment.text, cx, y);
                cx += r.width;
            }
        }
        return cx;
    }

    private static int paintRainbow(Graphics2D g, Resolved r, int x, int y, float alpha, boolean shadow)
    {
        String text = r.segment.text;
        Color[] palette = r.segment.rainbowPalette;
        int cx = x;
        int colorIndex = 0;
        for (int i = 0; i < text.length(); i++)
        {
            char ch = text.charAt(i);
            int charWidth = r.metrics.charWidth(ch);
            if (!Character.isWhitespace(ch))
            {
                String s = String.valueOf(ch);
                Color color = palette[colorIndex % palette.length];
                if (shadow)
                {
                    g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * SHADOW_ALPHA_SCALE));
                    g.drawString(s, cx + SHADOW_OFFSET, y + SHADOW_OFFSET);
                }
                g.setColor(RunePartyRender.withAlpha(color, alpha));
                g.drawString(s, cx, y);
                colorIndex++;
            }
            cx += charWidth;
        }
        return cx;
    }

    private static int sumCharWidths(FontMetrics fm, String text)
    {
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += fm.charWidth(text.charAt(i));
        return total;
    }
}
