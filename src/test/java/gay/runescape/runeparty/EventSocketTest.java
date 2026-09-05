package gay.runescape.runeparty;

import com.google.gson.Gson;
import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.EventListener;
import gay.runescape.runeparty.net.EventSocket;
import okhttp3.OkHttpClient;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Locks in EventSocket's CAUGHT_UP handling: every (re)connect opens at {@code afterSeq=lastSeq},
 * so the server's own replay burst for that connection -- everything sent before its CAUGHT_UP
 * sentinel -- must reach the listener with {@code catchingUp=true}, and only events after that
 * sentinel should read as genuinely live.
 */
public class EventSocketTest
{
    private MockWebServer server;
    private OkHttpClient client;
    private EventSocket socket;
    // Counted down by the server-side WebSocketListener's own onClosed below, once the close
    // handshake EventSocket#stop() kicks off (see tearDown) actually finishes -- server.shutdown()
    // otherwise races that handshake and can time out waiting for its own executor with a
    // connection still open (a real MockWebServer quirk, not evidence of anything wrong with
    // EventSocket's own close() call).
    private CountDownLatch serverSocketClosed;

    @Before
    public void setUp() throws Exception
    {
        server = new MockWebServer();
        server.start();
        client = new OkHttpClient();
        serverSocketClosed = new CountDownLatch(1);
    }

    @After
    public void tearDown() throws Exception
    {
        if (socket != null) socket.shutdown();
        assertTrue("server-side socket must finish closing before MockWebServer#shutdown",
            serverSocketClosed.await(5, TimeUnit.SECONDS));
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
        server.shutdown();
    }

    private static final class Received
    {
        final String type;
        final int seq;
        final boolean catchingUp;

        Received(String type, int seq, boolean catchingUp)
        {
            this.type = type;
            this.seq = seq;
            this.catchingUp = catchingUp;
        }
    }

    @Test
    public void replayBurstIsCatchingUpAndLiveEventsAfterCaughtUpAreNot() throws Exception
    {
        List<Received> received = new ArrayList<>();
        List<String> caughtUpCalls = new ArrayList<>();
        CountDownLatch gotThreeEvents = new CountDownLatch(3);

        EventListener listener = new EventListener()
        {
            @Override
            public synchronized void onEvent(ApiClient.EventOut e, boolean catchingUp)
            {
                received.add(new Received(e.type, e.seq, catchingUp));
                gotThreeEvents.countDown();
            }

            @Override
            public void onError(Exception e)
            {
                // Not asserted against -- teardown's own socket.close(1000) can itself surface as
                // an onFailure/onError race on OkHttp's background dispatcher thread once the
                // assertions below have already run, which would otherwise crash that thread with
                // nothing left to catch it.
            }

            @Override
            public synchronized void onCaughtUp()
            {
                caughtUpCalls.add("caught-up");
            }
        };

        // The server sends two backlog events, then its CAUGHT_UP sentinel, then one genuinely
        // live event -- one WebSocket frame per message, matching how the server actually writes
        // them.
        server.enqueue(new MockResponse().withWebSocketUpgrade(new WebSocketListener()
        {
            @Override
            public void onOpen(WebSocket webSocket, okhttp3.Response response)
            {
                webSocket.send("{\"seq\":1,\"eventId\":\"a\",\"ts\":\"t\",\"type\":\"TURN_STARTED\",\"payload\":{}}");
                webSocket.send("{\"seq\":2,\"eventId\":\"b\",\"ts\":\"t\",\"type\":\"TURN_STARTED\",\"payload\":{}}");
                webSocket.send("{\"type\":\"CAUGHT_UP\"}");
                webSocket.send("{\"seq\":3,\"eventId\":\"c\",\"ts\":\"t\",\"type\":\"TURN_STARTED\",\"payload\":{}}");
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason)
            {
                webSocket.close(code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason)
            {
                serverSocketClosed.countDown();
            }
        }));

        String wsBaseUrl = "ws://" + server.getHostName() + ":" + server.getPort();
        socket = new EventSocket(client, new Gson(), listener, wsBaseUrl);
        socket.start("test-game", 0, "Zezima");

        assertTrue("expected 3 onEvent calls within the timeout", gotThreeEvents.await(5, TimeUnit.SECONDS));

        synchronized (listener)
        {
            assertEquals(3, received.size());
            assertEquals(1, received.get(0).seq);
            assertTrue("backlog event 1 must be catchingUp", received.get(0).catchingUp);
            assertEquals(2, received.get(1).seq);
            assertTrue("backlog event 2 must be catchingUp", received.get(1).catchingUp);
            assertEquals(3, received.get(2).seq);
            assertTrue("event after CAUGHT_UP must NOT be catchingUp", !received.get(2).catchingUp);

            assertEquals("onCaughtUp must fire exactly once", 1, caughtUpCalls.size());
        }
    }
}
