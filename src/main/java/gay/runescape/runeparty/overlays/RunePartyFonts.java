package gay.runescape.runeparty.overlays;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.FontManager;

/** Shared font resources for this package -- the Mario Party-style display font used by
 * AnnouncementOverlay and most of the per-mini-game overlays (CoinRushScoreboardOverlay,
 * HotPotatoOverlay, ...), and the two dialogue-box fonts ChatboxDialogueOverlay tries in order for
 * its own NPC conversation boxes (see DIALOGUE_BITMAP/DIALOGUE_PLAIN's own docs). */
@Slf4j
final class RunePartyFonts
{
    // Loaded once at class-init; falls back to the client's own bold font if the resource is
    // missing, so a packaging mistake degrades gracefully instead of crashing.
    static final Font MARIO_PARTY = load("mario-party-hudson.ttf", FontManager.getRunescapeBoldFont());

    // The game's own real dialogue-box font ("Quill 8", cache archive q8_full, widget font id
    // 497) -- not a TrueType font at all, but a dump of the actual glyph bitmaps the client
    // itself blits (see GameFont's own doc). This is Follower Buddy's own PRIMARY path (its
    // GameFontRepository), not merely a closer approximation: DIALOGUE_PLAIN below is what that
    // same plugin falls back to only when this dump isn't loaded, and this project had first
    // shipped with only that fallback, mistaking it for the norm rather than the exception, before
    // realizing the dump itself (already pre-extracted into a portable JSON file, no live
    // cache-reading needed) was small and simple enough to port outright. Null if the resource is
    // missing or fails to parse -- every caller falls back to DIALOGUE_PLAIN in that case, same
    // graceful-degradation shape MARIO_PARTY's own doc describes.
    //
    // Ported from the Follower Buddy plugin (https://github.com/MikeSpatol/follower-buddy,
    // BSD-2-Clause) -- dialogue-font.json is that plugin's own bundled fonts.json, trimmed to just
    // font id 497 (this project draws no overhead chat text, the only consumer of its other
    // bundled font, 496/b12_full). GameFont is a trimmed, verbatim port of that plugin's own class
    // of the same name.
    static final GameFont DIALOGUE_BITMAP = loadDialogueBitmap();

    // RuneStar's pixel-perfect recreation of the game's own "Plain 12" font (a DIFFERENT font from
    // DIALOGUE_BITMAP's own "Quill 8" -- Plain 12 is the general interface typeface, Quill 8 is
    // dialogue-specific) -- see http://runestar.org. Used only when DIALOGUE_BITMAP above is
    // unavailable; see that field's own doc for why this is the fallback, not the norm.
    static final Font DIALOGUE_PLAIN = loadDialoguePlain();

    private static Font load(String resourceName, Font fallback)
    {
        try (InputStream is = RunePartyFonts.class.getResourceAsStream(resourceName))
        {
            if (is == null) throw new IOException(resourceName + " resource not found");
            Font font = Font.createFont(Font.TRUETYPE_FONT, is);
            log.info("Loaded {} -> {}", resourceName, font.getFontName());
            return font;
        }
        catch (FontFormatException | IOException e)
        {
            log.warn("Failed to load the {} font, falling back to the default", resourceName, e);
            return fallback;
        }
    }

    private static Font loadDialoguePlain()
    {
        // 16pt only applies to a successful load of the real TTF (authored so 16pt reproduces its
        // native pixel size, per DIALOGUE_PLAIN's own doc) -- the fallback is already sized
        // appropriately as RuneLite's own default UI font and shouldn't be rescaled to match.
        try (InputStream is = RunePartyFonts.class.getResourceAsStream("RuneScape-Plain-12.ttf"))
        {
            if (is == null) throw new IOException("RuneScape-Plain-12.ttf resource not found");
            Font font = Font.createFont(Font.TRUETYPE_FONT, is).deriveFont(16f);
            log.info("Loaded RuneScape-Plain-12.ttf -> {}", font.getFontName());
            return font;
        }
        catch (FontFormatException | IOException e)
        {
            log.warn("Failed to load the RuneScape-Plain-12.ttf font, falling back to the default", e);
            return FontManager.getRunescapeFont();
        }
    }

    // Gson's own field-reflection binding needs matching field names -- these three nested shapes
    // mirror dialogue-font.json's own structure (itself GameFontRepository's own Dump/FontEntry/
    // GlyphEntry, unchanged) exactly enough to bind, but never escape this one loader method, so
    // there's no reason to give them their own top-level files.
    private static final class GlyphEntry
    {
        int w;
        int h;
        int ox;
        int oy;
        String mask;
    }

    private static final class FontEntry
    {
        int id;
        String name;
        int ascent;
        int[] advances;
        List<GlyphEntry> glyphs;
    }

    private static final class Dump
    {
        int version;
        String cacheRevision;
        List<FontEntry> fonts;
    }

    private static GameFont loadDialogueBitmap()
    {
        try (InputStream is = RunePartyFonts.class.getResourceAsStream("dialogue-font.json"))
        {
            if (is == null) throw new IOException("dialogue-font.json resource not found");
            Dump dump = new Gson().fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Dump.class);
            if (dump == null || dump.fonts == null || dump.fonts.isEmpty())
            {
                throw new IOException("dialogue-font.json has no fonts");
            }

            FontEntry entry = dump.fonts.get(0);
            GameFont.Glyph[] glyphs = new GameFont.Glyph[256];
            for (int i = 0; i < entry.glyphs.size() && i < 256; i++)
            {
                GlyphEntry g = entry.glyphs.get(i);
                glyphs[i] = new GameFont.Glyph(g.w, g.h, g.ox, g.oy,
                    g.mask == null ? new byte[0] : Base64.getDecoder().decode(g.mask));
            }

            log.info("Loaded dialogue-font.json -> font {} '{}' (cache {})", entry.id, entry.name, dump.cacheRevision);
            return new GameFont(entry.ascent, entry.advances, glyphs);
        }
        catch (IOException | JsonSyntaxException e)
        {
            log.warn("Failed to load dialogue-font.json, falling back to the RuneStar TTF", e);
            return null;
        }
    }

    private RunePartyFonts()
    {
    }
}
