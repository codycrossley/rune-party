package gay.runescape.runeparty;

import java.util.concurrent.ScheduledFuture;

/** One cosmetic, client-only announcement banner's timing state -- until/payload/optionally a
 * chained task. Replaces what used to be 2-5 separate parallel fields per banner (an xUntil
 * timestamp, an xRsn/payload, sometimes an xStart, sometimes an xTask) -- see
 * ARCHITECTURE_REVIEW.md's C1 finding. Purely a value holder: this class still owns exactly
 * when/why each one gets armed (via its own scheduleXBanner/triggerX method, on RunePartyPlugin or
 * on whichever presenter owns it) and every public getter still has its own name/signature, just
 * delegating to one of these instead of a raw field -- AnnouncementOverlay and every other
 * consumer needs no changes. Real, server-authoritative state (minigameReadyRsns, coinRushSpawns,
 * trueOrFalseQuestion, etc.) is untouched -- this is purely the cosmetic-timer half. Package-private
 * (not public) -- only RunePartyPlugin and its presenter classes ever need it. */
final class TimedBanner<T>
{
    volatile long start;
    volatile long until;
    volatile T payload;
    volatile ScheduledFuture<?> task;

    void reset()
    {
        if (task != null) { task.cancel(false); task = null; }
        start = 0;
        until = 0;
        payload = null;
    }
}
