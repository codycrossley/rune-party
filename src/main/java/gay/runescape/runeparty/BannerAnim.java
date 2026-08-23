package gay.runescape.runeparty;

/** Pure animation-timing math shared by every timed banner/popup across AnnouncementOverlay and
 * PlayerOverlay -- see ARCHITECTURE_REVIEW.md's C2 finding. Deliberately knows nothing about
 * TimedBanner, payloads, or Graphics2D: every call site still owns its own guard order, payload
 * reads, and drawing -- this only owns the "how faded / how pulsed" arithmetic that was
 * byte-for-byte duplicated at every site. */
final class BannerAnim
{
    private BannerAnim() {}

    /** Eased fade alpha for a countdown ending at `until`, or null once it's expired (including
     * "never armed", until == 0 -- remaining ends up hugely negative either way, no separate
     * check needed). Collapses the `long remaining = ...; if (remaining <= 0) return; float alpha
     * = remaining < fadeMs ? ... : 1f;` block repeated at every timed-banner render site. */
    static Float fadeAlpha(long until, long fadeMs)
    {
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) return null;
        return remaining < fadeMs ? remaining / (float) fadeMs : 1f;
    }

    /** A [0,1] breathing oscillation with the given period, evaluated at `now` -- callers map it
     * into whatever range they need (e.g. {@code minAlpha + (1 - minAlpha) * pulse(...)} for a
     * float alpha, {@code 100 + 155 * pulse(...)} for an int glow alpha) -- this only owns the
     * oscillation itself, not the final range, since call sites disagree on that range. */
    static float pulse(long now, long periodMs)
    {
        long phaseMs = now % periodMs;
        return (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / periodMs));
    }
}
