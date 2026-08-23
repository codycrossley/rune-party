package gay.runescape.runeparty;

public interface EventListener
{
    /** {@code catchingUp} is true while EventSocket is still replaying a connection's own backlog
     * burst (the events it fetched for {@code afterSeq}, sent before its CAUGHT_UP sentinel) --
     * true on both the very first connect and any later reconnect, false once that burst is done
     * and events are arriving genuinely live. See EventSocket's own class doc and
     * RunePartyPlugin#handleEvent, the only implementation, for how this is used. */
    void onEvent(ApiClient.EventOut e, boolean catchingUp);
    void onError(Exception e);

    /** Called once per connection, right after its own replay burst finishes (see EventSocket's
     * CAUGHT_UP handling) -- including the very first connect, where the burst is normally empty
     * since connectEventStream's own REST backlog fetch already covers everything up to the seq
     * the socket opens at. Mirrors what connectEventStream does by hand after that REST fetch: a
     * PLAYER_JOINED/ROLE_ASSIGNED/PLAYER_LEFT seen during catch-up deliberately skips its own
     * per-event roster resync (see RunePartyPlugin#handleEvent), so this is what actually catches
     * the roster back up to current, once, rather than the connection silently going stale
     * whenever any such event fell inside a reconnect's own replay burst. */
    void onCaughtUp();
}
