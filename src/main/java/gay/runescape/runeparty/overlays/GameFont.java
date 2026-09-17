package gay.runescape.runeparty.overlays;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/** One of the game's own bitmap fonts, rendered by blitting the cache's actual glyph masks -- the
 * same pixels the real client puts on screen. A TTF through a system rasterizer never matches
 * exactly (hinting, baselines and advances all differ); these are the real glyphs with the real
 * per-character advances and bearings. Ported (verbatim rendering logic, trimmed to the methods
 * ChatboxDialogueOverlay actually calls -- no overhead-chat-text support, this project has no
 * overlay that draws that) from the Follower Buddy plugin's own GameFont
 * (https://github.com/MikeSpatol/follower-buddy, BSD-2-Clause) -- see RunePartyFonts#DIALOGUE_BITMAP
 * for where the glyph data itself (dialogue-font.json) comes from.
 *
 * <p>Draw semantics ported from the client's own font renderer (PixFont): glyphs blit at
 * {@code (x + offsetX, top + offsetY)}, the pen advances by the font's own per-character advance.
 * Glyphs are indexed DIRECTLY by character code (the dumped archive carries 256 glyphs and 256
 * advances). */
final class GameFont
{
    static final class Glyph
    {
        final int width;
        final int height;
        final int offsetX;
        final int offsetY;
        final byte[] mask;

        Glyph(int width, int height, int offsetX, int offsetY, byte[] mask)
        {
            this.width = width;
            this.height = height;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.mask = mask;
        }
    }

    private final int ascent;
    private final int[] advances;
    private final Glyph[] glyphs;

    /** The client's own PixFont.height: the tallest glyph mask, this font's own line-stack step. */
    private final int lineHeight;

    /** Tinted glyph images, cached per (character, colour) -- see tint's own doc. */
    private final Map<Long, BufferedImage> tinted = new HashMap<>();

    GameFont(int ascent, int[] advances, Glyph[] glyphs)
    {
        this.ascent = ascent;
        this.advances = advances;
        this.glyphs = glyphs;

        int tallest = 0;
        for (Glyph glyph : glyphs)
        {
            if (glyph != null && glyph.height > tallest)
            {
                tallest = glyph.height;
            }
        }
        this.lineHeight = tallest;
    }

    int getAscent()
    {
        return ascent;
    }

    int getLineHeight()
    {
        return lineHeight;
    }

    /** The exact pixel width the real client would measure for this string. */
    int stringWidth(String text)
    {
        if (text == null) return 0;
        int width = 0;
        for (int i = 0; i < text.length(); i++)
        {
            width += advances[text.charAt(i) & 0xFF];
        }
        return width;
    }

    /** Draws with {@code y} as the BASELINE, the widget text convention: a text line's baseline
     * sits at its top plus the font's own ascent. No shadow -- see ChatboxDialogueOverlay's own
     * doc for why real dialogue text never gets one. */
    void drawBaseline(Graphics2D g, String text, int x, int y, int rgb)
    {
        drawTop(g, text, x, y - ascent, rgb);
    }

    /** Draws with {@code y} as the TOP of the glyph cell. */
    private void drawTop(Graphics2D g, String text, int x, int y, int rgb)
    {
        if (text == null) return;
        int pen = x;
        for (int i = 0; i < text.length(); i++)
        {
            int c = text.charAt(i) & 0xFF;
            Glyph glyph = glyphs[c];
            if (glyph != null && glyph.width > 0 && glyph.height > 0)
            {
                g.drawImage(tint(c, rgb), pen + glyph.offsetX, y + glyph.offsetY, null);
            }
            pen += advances[c];
        }
    }

    /** A single character's own glyph mask, tinted to {@code rgb} and cached -- every dialogue box
     * redraws the same handful of characters every frame, so paying the per-pixel tint cost once
     * per (character, color) pair actually seen, rather than every frame, is what keeps this cheap
     * enough to call from render(). */
    private BufferedImage tint(int character, int rgb)
    {
        long key = ((long) character << 32) | (rgb & 0xFFFFFFFFL);
        BufferedImage cached = tinted.get(key);
        if (cached != null) return cached;

        Glyph glyph = glyphs[character];
        BufferedImage image = new BufferedImage(glyph.width, glyph.height, BufferedImage.TYPE_INT_ARGB);
        int argb = 0xFF000000 | rgb;
        for (int i = 0; i < glyph.mask.length; i++)
        {
            if (glyph.mask[i] != 0)
            {
                image.setRGB(i % glyph.width, i / glyph.width, argb);
            }
        }
        tinted.put(key, image);
        return image;
    }
}
