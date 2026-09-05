package gay.runescape.runeparty;

import java.util.concurrent.ScheduledFuture;

/** One cosmetic, client-only announcement banner's timing state -- until/payload/optionally a
 * chained task. Purely a value holder; each banner's owner still decides when/why it gets armed.
 * Public since some owners (the per-minigame classes in the minigames subpackage) live in a
 * separate package from this one. */
public final class TimedBanner<T>
{
    public volatile long start;
    public volatile long until;
    public volatile T payload;
    public volatile ScheduledFuture<?> task;

    public void reset()
    {
        if (task != null) { task.cancel(false); task = null; }
        start = 0;
        until = 0;
        payload = null;
    }
}
