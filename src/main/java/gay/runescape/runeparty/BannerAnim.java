package gay.runescape.runeparty;

/** Animation-timing math shared by every timed banner/popup across the overlays package (see
 * AnnouncementOverlay and PlayerOverlay in particular) -- public since its own callers live in
 * that separate subpackage, not this one. */
public final class BannerAnim
{
    private BannerAnim() {}

    /** Eased fade alpha for a countdown ending at `until`, or null once it's expired (including
     * "never armed", until == 0). */
    public static Float fadeAlpha(long until, long fadeMs)
    {
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) return null;
        return remaining < fadeMs ? remaining / (float) fadeMs : 1f;
    }

    /** A [0,1] breathing oscillation with the given period, evaluated at `now` -- callers map it
     * into whatever range they need. */
    public static float pulse(long now, long periodMs)
    {
        long phaseMs = now % periodMs;
        return (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / periodMs));
    }
}
