package gay.runescape.runeparty;

public interface EventListener
{
    /** {@code catchingUp} is true while EventSocket is still replaying a connection's backlog
     * burst -- true on both the first connect and any reconnect, false once events are arriving
     * live. */
    void onEvent(ApiClient.EventOut e, boolean catchingUp);
    void onError(Exception e);

    /** Called once per connection, right after its replay burst finishes. A roster resync is
     * skipped for individual roster events seen during catch-up, so this is what catches the
     * roster back up to current afterward. */
    void onCaughtUp();
}
