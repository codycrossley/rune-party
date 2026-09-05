package gay.runescape.runeparty.net;

import com.google.gson.Gson;
import okhttp3.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * WebSocket client for the Rune Party companion server's live event stream.
 *
 * Reconnects with full-jitter exponential backoff (delay = random(0,
 * min(30s, 500ms * 2^attempt))): full jitter specifically because a server
 * restart drops every connected client's socket at once, and plain
 * exponential backoff would produce a synchronized reconnect wave from
 * every client at the same moment.
 *
 * Every (re)connect opens at {@code afterSeq=lastSeq}, so the server's first flush on that
 * connection is always a replay of whatever committed after that point -- true live events only
 * start once the server's own CAUGHT_UP sentinel arrives. A client that drops for 30 seconds and
 * reconnects would otherwise have that whole missed-history burst delivered indistinguishably from
 * live traffic, firing every banner/dice-reveal/spotanim at once -- {@link #caughtUp} tracks the
 * boundary per connection so {@link EventListener#onEvent} can tell the two apart.
 */
public class EventSocket
{
    private static final long MAX_BACKOFF_MS = 30_000;
    private static final long BASE_BACKOFF_MS = 500;

    private final OkHttpClient http;
    private final Gson gson;
    private final EventListener listener;
    private final String wsBaseUrl;

    private final ScheduledExecutorService reconnectExec =
        Executors.newSingleThreadScheduledExecutor(r ->
        {
            Thread t = new Thread(r, "runeparty-ws-reconnect");
            t.setDaemon(true);
            return t;
        });

    private volatile String gameId;
    private volatile String playerRsn;
    private final AtomicInteger lastSeq = new AtomicInteger(0);
    private final AtomicInteger reconnectAttempt = new AtomicInteger(0);
    private volatile boolean running = false;
    private WebSocket webSocket;
    private ScheduledFuture<?> reconnectTask;
    // False from the moment a new connection opens until its own CAUGHT_UP sentinel arrives -- see
    // the class doc above.
    private volatile boolean caughtUp = false;

    public EventSocket(OkHttpClient okHttpClient, Gson gson, EventListener listener)
    {
        // Derived from ApiClient.BASE_URL rather than a second hardcoded constant, so repointing
        // that one constant at a real deployment can't leave this socket still pointed at
        // localhost by mistake.
        this(okHttpClient, gson, listener, ApiClient.BASE_URL.replaceFirst("^http", "ws"));
    }

    /** Visible for testing -- lets tests point this at a MockWebServer instead of production. */
    public EventSocket(OkHttpClient okHttpClient, Gson gson, EventListener listener, String wsBaseUrl)
    {
        // Pings let OkHttp detect a silently-dead connection (NAT timeout,
        // network partition) faster than plain TCP would on its own.
        this.http = okHttpClient.newBuilder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();
        this.gson = gson;
        this.listener = listener;
        this.wsBaseUrl = wsBaseUrl;
    }

    public void start(String gameId, String playerRsn)
    {
        start(gameId, 0, playerRsn);
    }

    public synchronized void start(String gameId, int initialSeq, String playerRsn)
    {
        stop();
        this.gameId = gameId;
        this.playerRsn = playerRsn;
        this.lastSeq.set(initialSeq);
        this.reconnectAttempt.set(0);
        this.running = true;
        connect();
    }

    public synchronized void stop()
    {
        running = false;
        if (reconnectTask != null)
        {
            reconnectTask.cancel(false);
            reconnectTask = null;
        }
        if (webSocket != null)
        {
            webSocket.close(1000, "client stopping");
            webSocket = null;
        }
    }

    public synchronized void shutdown()
    {
        stop();
        reconnectExec.shutdownNow();
    }

    private synchronized void connect()
    {
        if (!running) return;

        final String gid = this.gameId;
        if (gid == null || gid.isBlank()) return;

        caughtUp = false; // this connection's own first flush is a replay until its CAUGHT_UP sentinel arrives
        String url = wsUrl(gid, lastSeq.get(), playerRsn);
        Request req = new Request.Builder().url(url).build();
        webSocket = http.newWebSocket(req, new Listener());
    }

    private String wsUrl(String gameId, int afterSeq, String playerRsn)
    {
        StringBuilder sb = new StringBuilder(wsBaseUrl)
            .append("/v1/games/").append(gameId).append("/ws")
            .append("?afterSeq=").append(afterSeq);
        if (playerRsn != null && !playerRsn.isBlank())
        {
            sb.append("&player=").append(urlEncode(playerRsn));
        }
        return sb.toString();
    }

    private static String urlEncode(String s)
    {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private void scheduleReconnect()
    {
        synchronized (this)
        {
            if (!running) return;

            int attempt = reconnectAttempt.getAndIncrement();
            long cap = Math.min(MAX_BACKOFF_MS, BASE_BACKOFF_MS * (1L << Math.min(attempt, 20)));
            long delay = ThreadLocalRandom.current().nextLong(0, cap + 1);

            reconnectTask = reconnectExec.schedule(this::connect, delay, TimeUnit.MILLISECONDS);
        }
    }

    private final class Listener extends WebSocketListener
    {
        @Override
        public void onOpen(WebSocket webSocket, Response response)
        {
            reconnectAttempt.set(0);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text)
        {
            if (!running) return;
            try
            {
                ApiClient.EventOut e = gson.fromJson(text, ApiClient.EventOut.class);
                if (e == null) return;

                // Marks the end of this connection's own replay burst -- see the class doc and
                // caughtUp's own field doc. Never itself forwarded to listener.onEvent.
                if (Events.CAUGHT_UP.equals(e.type))
                {
                    caughtUp = true;
                    listener.onCaughtUp();
                    return;
                }

                // catchingUp is read once per message (not per unwrapped event below) since the
                // server never mixes a replay-burst event and a genuinely live one in the same
                // frame -- the CAUGHT_UP sentinel above always arrives as its own message between
                // the two.
                boolean catchingUp = !caughtUp;

                // The server coalesces a short burst of events for the same game into one wrapped
                // message instead of one frame each -- unwrap it back into individual onEvent
                // calls, in order, so nothing downstream needs to know batching exists.
                if (Events.EVENTS_BATCH.equals(e.type))
                {
                    EventsBatch batch = gson.fromJson(text, EventsBatch.class);
                    if (batch.events == null) return;
                    for (ApiClient.EventOut inner : batch.events)
                    {
                        if (inner == null) continue;
                        lastSeq.set(Math.max(lastSeq.get(), inner.seq));
                        listener.onEvent(inner, catchingUp);
                    }
                    return;
                }

                lastSeq.set(Math.max(lastSeq.get(), e.seq));
                listener.onEvent(e, catchingUp);
            }
            catch (Exception ex)
            {
                listener.onError(ex);
            }
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason)
        {
            webSocket.close(code, reason);
        }

        @Override
        public void onClosed(WebSocket webSocket, int code, String reason)
        {
            if (running) scheduleReconnect();
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response)
        {
            listener.onError(t instanceof Exception ? (Exception) t : new RuntimeException(t));
            if (running) scheduleReconnect();
        }
    }

    /** Wire shape for a coalesced burst of events -- only sent when more than one event landed in
     * the server's ~100ms buffering window for this game. */
    private static final class EventsBatch
    {
        String type;
        List<ApiClient.EventOut> events;
    }
}
