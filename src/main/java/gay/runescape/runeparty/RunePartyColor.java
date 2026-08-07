package gay.runescape.runeparty;

import java.awt.Color;

/** The fixed 8-color palette assigned to PLAYER roster slots by turn order -- one color per seat,
 * matching MAX_PLAYERS (see RunePartyPlugin) so every player always gets a distinct color and
 * nobody ever has to share one. Purely a client-side rendering concern: the server only ever
 * assigns turn-order numbers (see RosterReducer#getNumber), this enum is what turns "1".."8" into
 * something visible on-screen. */
public enum RunePartyColor
{
    RED(new Color(220, 50, 50)),
    ORANGE(new Color(255, 140, 0)),
    YELLOW(new Color(255, 215, 0)),
    GREEN(new Color(60, 179, 74)),
    BLUE(new Color(40, 130, 230)),
    PURPLE(new Color(170, 80, 220)),
    BLACK(new Color(35, 35, 35)),
    WHITE(new Color(245, 245, 245));

    private static final RunePartyColor[] BY_SEAT = values();

    public final Color awt;

    RunePartyColor(Color awt)
    {
        this.awt = awt;
    }

    /** Maps a roster "number" (1-indexed turn order, see RosterReducer#getNumber) onto this
     * palette. Returns null for a blank/unparseable number -- e.g. a spectator, who has none. */
    public static RunePartyColor forNumber(String number)
    {
        if (number == null || number.isBlank()) return null;
        int n;
        try { n = Integer.parseInt(number.trim()); }
        catch (NumberFormatException e) { return null; }
        if (n < 1) return null;
        return BY_SEAT[(n - 1) % BY_SEAT.length];
    }
}
