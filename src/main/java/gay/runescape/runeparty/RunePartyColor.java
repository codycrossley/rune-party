package gay.runescape.runeparty;

import java.awt.Color;

/** The fixed 8-color palette assigned to PLAYER roster slots -- one color per seat, matching
 * MAX_PLAYERS (see RunePartyPlugin) so every player always gets a distinct color and nobody ever
 * has to share one. Purely a client-side rendering concern: the server assigns each player a
 * stable "colorNumber" the first time they become a PLAYER (see RosterReducer.RosterEntry#
 * colorNumber) that never changes again even if they leave and are later re-added -- this enum is
 * what turns that "1".."8" into something visible on-screen. Deliberately not the same field as
 * the roster's turn-order "number" (see RosterReducer#getNumber), which does shift as players
 * come and go. */
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

    /** Maps a roster "colorNumber" (1-indexed, stable per-player -- see RosterReducer.RosterEntry#
     * colorNumber) onto this palette. Returns null for a blank/unparseable number -- e.g. a
     * spectator who's never been a PLAYER, who has none. */
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
