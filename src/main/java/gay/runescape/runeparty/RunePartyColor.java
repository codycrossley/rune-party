package gay.runescape.runeparty;

import java.awt.Color;
import java.util.Locale;

/** The fixed 8-color palette assigned to PLAYER roster slots -- one color per seat, matching
 * MAX_PLAYERS (see RunePartyPlugin) so every player always gets a distinct color and nobody ever
 * has to share one. Purely a client-side rendering concern: the server records whichever
 * "colorNumber" the host explicitly picked for a player when promoting them (see
 * RunePartyPlugin#addToGameMenuEntry/RunePartyPanel#buildAddToGamePopup, both of which offer one
 * submenu entry per currently-available color here), freed again the instant that player is
 * removed (see RosterReducer.RosterEntry#colorNumber) -- this enum is what turns that "1".."8"
 * into something visible on-screen, and its own name() into the label each submenu entry shows.
 * Deliberately not the same field as the roster's turn-order "number" (see
 * RosterReducer#getNumber), which does shift as players come and go. */
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
    /** Title-cased menu label ("Red", not "RED") -- see addToGameMenuEntry/buildAddToGamePopup,
     * the only consumers. */
    public final String displayName;

    RunePartyColor(Color awt)
    {
        this.awt = awt;
        this.displayName = name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }

    /** This color's own 1-indexed seat number (the inverse of forNumber) -- see
     * addToGameMenuEntry/buildAddToGamePopup, which both need to send the actual colorNumber back
     * to the server for whichever palette entry the host clicked. */
    public int seatNumber()
    {
        return ordinal() + 1;
    }

    /** Wraps {@code text} in this color's own "<col=RRGGBB>...</col>" tag -- the same manual
     * color-tag convention every other colored menu option in RunePartyPlugin already uses (e.g.
     * "<col=00FF00>Place ...</col>"). */
    public String menuTag(String text)
    {
        return String.format("<col=%02X%02X%02X>%s</col>", awt.getRed(), awt.getGreen(), awt.getBlue(), text);
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
