package gay.runescape.runeparty;

import java.util.concurrent.ScheduledFuture;

/** One cosmetic, client-only announcement banner's timing state -- until/payload/optionally a
 * chained task. Purely a value holder; each banner's owner still decides when/why it gets armed. */
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
