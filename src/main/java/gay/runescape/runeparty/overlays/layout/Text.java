package gay.runescape.runeparty.overlays.layout;

import gay.runescape.runeparty.RunePartyColor;
import gay.runescape.runeparty.overlays.RunePartyRender;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** A single styled line (or, with {@link #wrap}, a word-wrapped paragraph) of text -- the leaf
 * node every migrated AnnouncementOverlay banner is ultimately built from. Replicates that file's
 * own {@code drawCenteredText}/{@code drawCenteredRainbowText}/{@code drawWrappedCenteredText}
 * behavior (shadow offset, auto-shrink-to-fit, rainbow per-letter cycling) as one reusable node
 * instead of four private methods plus their own {@code fitScale}/{@code wrapCenteredLines}
 * helpers -- see this package's own doc on why the algorithm is replicated here rather than
 * shared by reference (the viewport-reading half of the original stays in AnnouncementOverlay,
 * which has the RuneLite {@code Client} this package deliberately doesn't depend on).
 * <p>
 * {@code y} in {@link #paint} is this node's own text <b>baseline</b>, not its top edge -- the
 * one deliberate exception {@link Node#paint}'s own doc calls out. Every existing
 * AnnouncementOverlay call site already computes "y" as a baseline fed straight into {@code
 * g.drawString}; keeping that meaning here means a banner that's just one {@code Text} handed
 * straight to {@link Layout#renderCentered} needs no y-math changes at all during migration. A
 * {@link Box} reconciles this with its own top-down box model via {@link #verticalAnchor}. */
public final class Text extends Node
{
    private static final float SHADOW_ALPHA_SCALE = 0.7f;
    private static final int SHADOW_OFFSET = 2;

    private final String text;
    private final Color[] rainbowPalette;
    private Font font;
    private Color color = Color.WHITE;
    private boolean shadow = true;
    private boolean fitToWidth = true;
    private float minFitScale = 0.5f;
    private int wrapWidth = -1;
    private int lineHeight = -1;

    private Text(String text, Color[] rainbowPalette)
    {
        this.text = text;
        this.rainbowPalette = rainbowPalette;
    }

    public static Text of(String text)
    {
        return new Text(text, null);
    }

    /** Colors each non-space character from {@code palette} in order, cycling -- replicates
     * drawCenteredRainbowText/drawLeftAlignedRainbowText. */
    public static Text rainbow(String text, Color[] palette)
    {
        return new Text(text, Objects.requireNonNull(palette, "palette"));
    }

    public Text font(Font font)
    {
        this.font = font;
        return this;
    }

    public Text color(Color c)
    {
        this.color = c;
        return this;
    }

    /** Convenience overload so a seat color can be handed straight over without every call site
     * unwrapping {@code .awt} itself. */
    public Text color(RunePartyColor c)
    {
        return color(c.awt);
    }

    /** Default true, matching every existing banner's own shadow. */
    public Text shadow(boolean on)
    {
        this.shadow = on;
        return this;
    }

    /** Default true -- shrinks toward {@link #minFitScale} rather than overflow the room a parent
     * hands this node, replicating {@code fitScale}/{@code MIN_FIT_SCALE}. Ignored once {@link
     * #wrap} is set (wrapping into multiple lines takes priority over shrinking a single line). */
    public Text fitToWidth(boolean on)
    {
        this.fitToWidth = on;
        return this;
    }

    public Text minFitScale(float f)
    {
        this.minFitScale = f;
        return this;
    }

    /** Greedy word-wraps into multiple lines at most {@code maxWidthPx} wide instead of shrinking
     * -- replicates {@code wrapCenteredLines}/{@code drawWrappedCenteredText}. Not exercised by
     * the first migration batch (every tier-1 banner is a single line); included now so a tier-2
     * migration doesn't need a redesign. Takes priority over {@link #fitToWidth} once set. */
    public Text wrap(int maxWidthPx)
    {
        this.wrapWidth = maxWidthPx;
        return this;
    }

    /** Only meaningful with {@link #wrap} -- defaults to the resolved font's own {@code
     * FontMetrics#getHeight()} if never called. */
    public Text lineHeight(int px)
    {
        this.lineHeight = px;
        return this;
    }

    @Override
    public Dimension measure(Graphics2D g, int maxWidth)
    {
        if (wrapWidth > 0) return measureWrapped(g);
        Resolved r = resolve(g, maxWidth);
        return new Dimension(r.width, r.metrics.getHeight());
    }

    @Override
    public void paint(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        float a = Math.max(0f, Math.min(1f, alpha * ownOpacity()));
        if (wrapWidth > 0)
        {
            paintWrapped(g, x, y, a);
        }
        else if (rainbowPalette != null)
        {
            paintRainbowLine(g, x, y, maxWidth, a);
        }
        else
        {
            paintPlainLine(g, x, y, maxWidth, a);
        }
    }

    @Override
    int verticalAnchor(Graphics2D g, int maxWidth)
    {
        if (wrapWidth > 0) return g.getFontMetrics(requireFont()).getAscent();
        return resolve(g, maxWidth).metrics.getAscent();
    }

    private void paintPlainLine(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        Resolved r = resolve(g, maxWidth);
        g.setFont(r.font);
        if (shadow)
        {
            g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * SHADOW_ALPHA_SCALE));
            g.drawString(text, x + SHADOW_OFFSET, y + SHADOW_OFFSET);
        }
        g.setColor(RunePartyRender.withAlpha(color, alpha));
        g.drawString(text, x, y);
    }

    private void paintRainbowLine(Graphics2D g, int x, int y, int maxWidth, float alpha)
    {
        Resolved r = resolve(g, maxWidth);
        g.setFont(r.font);
        FontMetrics fm = r.metrics;
        int cx = x;
        int colorIndex = 0;
        for (int i = 0; i < text.length(); i++)
        {
            char ch = text.charAt(i);
            int charWidth = fm.charWidth(ch);
            if (!Character.isWhitespace(ch))
            {
                String s = String.valueOf(ch);
                Color c = rainbowPalette[colorIndex % rainbowPalette.length];
                if (shadow)
                {
                    g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * SHADOW_ALPHA_SCALE));
                    g.drawString(s, cx + SHADOW_OFFSET, y + SHADOW_OFFSET);
                }
                g.setColor(RunePartyRender.withAlpha(c, alpha));
                g.drawString(s, cx, y);
                colorIndex++;
            }
            cx += charWidth;
        }
    }

    private Dimension measureWrapped(Graphics2D g)
    {
        Font f = requireFont();
        FontMetrics fm = g.getFontMetrics(f);
        List<String> lines = wrapLines(fm);
        int width = 0;
        for (String line : lines) width = Math.max(width, fm.stringWidth(line));
        int lh = lineHeight > 0 ? lineHeight : fm.getHeight();
        return new Dimension(width, lh * lines.size());
    }

    private void paintWrapped(Graphics2D g, int x, int y, float alpha)
    {
        Font f = requireFont();
        g.setFont(f);
        FontMetrics fm = g.getFontMetrics(f);
        List<String> lines = wrapLines(fm);

        int blockWidth = 0;
        for (String line : lines) blockWidth = Math.max(blockWidth, fm.stringWidth(line));
        int lh = lineHeight > 0 ? lineHeight : fm.getHeight();

        int cy = y;
        for (String line : lines)
        {
            int lineWidth = fm.stringWidth(line);
            int lx = x + (blockWidth - lineWidth) / 2;
            if (shadow)
            {
                g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * SHADOW_ALPHA_SCALE));
                g.drawString(line, lx + SHADOW_OFFSET, cy + SHADOW_OFFSET);
            }
            g.setColor(RunePartyRender.withAlpha(color, alpha));
            g.drawString(line, lx, cy);
            cy += lh;
        }
    }

    /** Greedy word-wrap, same shape as the original {@code wrapCenteredLines} minus its manual
     * memoization cache -- see this package's own top-level doc on why that cache isn't ported
     * (a Text instance is rebuilt fresh every frame, so there's no long-lived instance to hang a
     * cross-frame cache off of the way AnnouncementOverlay's own private fields could). */
    private List<String> wrapLines(FontMetrics fm)
    {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String word : text.split(" "))
        {
            String candidate = current.length() == 0 ? word : current + " " + word;
            if (current.length() > 0 && fm.stringWidth(candidate) > wrapWidth)
            {
                lines.add(current.toString());
                current = new StringBuilder(word);
            }
            else
            {
                current = new StringBuilder(candidate);
            }
        }
        if (current.length() > 0) lines.add(current.toString());
        return lines;
    }

    private Resolved resolve(Graphics2D g, int maxWidth)
    {
        Font base = requireFont();
        FontMetrics fm = g.getFontMetrics(base);
        int width = rainbowPalette != null ? sumCharWidths(fm) : fm.stringWidth(text);

        if (fitToWidth && maxWidth > 0 && width > maxWidth)
        {
            float scale = Math.max(minFitScale, maxWidth / (float) width);
            base = base.deriveFont(base.getSize2D() * scale);
            fm = g.getFontMetrics(base);
            width = rainbowPalette != null ? sumCharWidths(fm) : fm.stringWidth(text);
        }

        return new Resolved(base, fm, width);
    }

    private int sumCharWidths(FontMetrics fm)
    {
        int total = 0;
        for (int i = 0; i < text.length(); i++) total += fm.charWidth(text.charAt(i));
        return total;
    }

    private Font requireFont()
    {
        if (font == null)
        {
            throw new IllegalStateException("Text node has no font set -- call .font(...) before measure()/paint()");
        }
        return font;
    }

    private static final class Resolved
    {
        final Font font;
        final FontMetrics metrics;
        final int width;

        Resolved(Font font, FontMetrics metrics, int width)
        {
            this.font = font;
            this.metrics = metrics;
            this.width = width;
        }
    }
}
