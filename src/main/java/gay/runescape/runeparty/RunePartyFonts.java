package gay.runescape.runeparty;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.FontManager;

/** Shared font resources for the whole plugin -- currently just the Mario Party-style display font
 * used by both AnnouncementOverlay and CoinRushTimerOverlay. */
@Slf4j
final class RunePartyFonts
{
    // Loaded once at class-init; falls back to the client's own bold font if the resource is
    // missing, so a packaging mistake degrades gracefully instead of crashing.
    static final Font MARIO_PARTY = load();

    private static Font load()
    {
        try (InputStream is = RunePartyFonts.class.getResourceAsStream("mario-party-hudson.ttf"))
        {
            if (is == null) throw new IOException("mario-party-hudson.ttf resource not found");
            return Font.createFont(Font.TRUETYPE_FONT, is);
        }
        catch (FontFormatException | IOException e)
        {
            log.warn("Failed to load the Mario Party Hudson font, falling back to the default", e);
            return FontManager.getRunescapeBoldFont();
        }
    }

    private RunePartyFonts()
    {
    }
}
