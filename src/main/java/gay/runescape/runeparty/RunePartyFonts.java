package gay.runescape.runeparty;

import java.awt.Font;
import java.awt.FontFormatException;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.ui.FontManager;

/** Shared font resources for the whole plugin -- currently just the one Mario Party-style display
 * font, split out of AnnouncementOverlay (its original sole consumer) once CoinRushTimerOverlay
 * needed the exact same font too, so both load it through one place instead of duplicating the
 * loader. */
@Slf4j
final class RunePartyFonts
{
    // Bundled at src/main/resources/gay/runescape/runeparty/mario-party-hudson.ttf -- loaded once
    // at class-init, deriveFont(size) per use same as FontManager's own fonts. Falls back to the
    // client's own bold font if the resource is ever missing, so a packaging mistake degrades
    // gracefully instead of crashing whichever overlay asked for it.
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
