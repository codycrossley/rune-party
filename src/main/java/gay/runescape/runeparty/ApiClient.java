package gay.runescape.runeparty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ApiClient
{
    // static final String BASE_URL = "http://localhost:8005/runeparty";
    static final String BASE_URL = "https://runeparty.shrunk.studio/runeparty";

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final OkHttpClient http;
    private final Gson gson;

    public ApiClient(OkHttpClient httpClient, Gson gson)
    {
        this.http = httpClient.newBuilder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build();
        this.gson = gson;
    }

    // -------------------------------------------------------------------------
    // Game lifecycle
    // -------------------------------------------------------------------------

    public CreateGameResult createGame(String hostRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("host", hostRsn);

        try (Response resp = post("/v1/games", body, null))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Create game failed (" + resp.code() + "): " + raw);
            CreateGameResponse parsed = gson.fromJson(raw, CreateGameResponse.class);
            return new CreateGameResult(parsed.gameId, parsed.joinCode, parsed.writeKey, parsed.playerToken);
        }
    }

    public JoinResult joinGame(String joinCode, String playerRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/join/" + joinCode, body, null))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Join failed (" + resp.code() + "): " + raw);
            JoinResponse parsed = gson.fromJson(raw, JoinResponse.class);
            return new JoinResult(parsed.gameId, parsed.host, parsed.playerToken);
        }
    }

    /** {@code maxRounds} is "turns per player" -- the host-set limit after which the game ends
     * (following that round's mini-game). The first TURN_STARTED isn't inserted here; see
     * confirmStart. */
    public void startGame(String gameId, String writeKey, int maxRounds) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("maxRounds", maxRounds);

        try (Response resp = post("/v1/games/" + gameId + "/start", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Start game failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player standing on the START tile after GAME_STARTED but before turn
     * order has actually begun -- once every seated PLAYER has called this, the server inserts the
     * first TURN_STARTED itself. */
    public void confirmStart(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/confirm-start", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Confirm start failed (" + resp.code() + "): " + raw);
        }
    }

    public void endGame(String gameId, String writeKey) throws IOException
    {
        try (Response resp = post("/v1/games/" + gameId + "/end", new JsonObject(), writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("End game failed (" + resp.code() + "): " + raw);
        }
    }

    public void leaveGame(String gameId, String playerRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/leave", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Leave failed (" + resp.code() + "): " + raw);
        }
    }

    /** Host-only kick -- same PLAYER_LEFT outcome as leaveGame, just authorized via the host's own
     * write key instead of the target's session token. */
    public void removePlayer(String gameId, String writeKey, String playerRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/remove-player", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Remove player failed (" + resp.code() + "): " + raw);
        }
    }

    /** Host-only: promotes/demotes a roster member between PLAYER and SPECTATOR. Joining a game
     * only ever grants SPECTATOR -- this is how the host opts someone into the turn order.
     * colorNumber is the host's explicit seat-color choice -- null for a SPECTATOR demotion, or to
     * let the server auto-pick the lowest available color. */
    public void assignRole(String gameId, String writeKey, String playerRsn, RunePartyRole role, Integer colorNumber) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("role", role.name());
        if (colorNumber != null) body.addProperty("colorNumber", colorNumber);

        try (Response resp = post("/v1/games/" + gameId + "/assign-role", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Assign role failed (" + resp.code() + "): " + raw);
        }
    }

    // -------------------------------------------------------------------------
    // Turn engine -- dice rolls and coin balances are always server-resolved.
    // These calls report a request/claim; the authoritative outcome comes back
    // as an event (DICE_ROLLED / PLAYER_MOVED / COINS_CHANGED / ...).
    // -------------------------------------------------------------------------

    /** Requests a dice roll for the current player. The server rolls (not the client) and
     * broadcasts the result via a DICE_ROLLED event -- this call's response is not the source of
     * truth for the value, only confirmation the request was accepted. */
    public void rollDice(String gameId, String playerRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/roll-dice", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Roll dice failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports that the local player has finished walking to the tile their roll resolved to. The
     * server validates this against the pending roll before resolving the tile's effect and
     * advancing the turn -- the client only reports a position claim, the server decides. */
    public void confirmArrival(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/confirm-arrival", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Confirm arrival failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports that the local player has finished walking to the Start tile after using a Home
     * Teleport -- this is what actually pays out the reward, never at use time. Not tied to a
     * pending roll like confirmArrival, since Home Teleport is a free action that can be called
     * well after the item was used. */
    public void confirmHomeTeleportArrival(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/confirm-home-teleport-arrival", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Confirm Home Teleport arrival failed (" + resp.code() + "): " + raw);
        }
    }

    /** Continuous "here's where I am right now" ping, fired every game tick while a mini-game is
     * playable -- unlike confirmArrival, this is a live heartbeat rather than a one-shot report of
     * reaching a destination. Generic, shared by any mini-game that needs live positions (e.g. the
     * Arena's hazard tiles). */
    public void reportMinigamePosition(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/minigame-position", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Report minigame position failed (" + resp.code() + "): " + raw);
        }
    }

    /** Submits the local player's final Fishing Contest catch tally. Fired once per round, when
     * the local 30-second timer elapses -- a one-shot report, unlike reportMinigamePosition's
     * per-tick heartbeat. */
    public void submitFishingCatch(String gameId, String playerRsn, String playerToken, int anchovies, int shrimp) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("anchovies", anchovies);
        body.addProperty("shrimp", shrimp);

        try (Response resp = post("/v1/games/" + gameId + "/submit-fishing-catch", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Submit fishing catch failed (" + resp.code() + "): " + raw);
        }
    }

    /** Buys the Golden Gnome standing at (x, y, plane). A free side-action during the local
     * player's pending roll, triggered by a right-click menu entry rather than an emote -- doesn't
     * touch pendingRoll or advance the turn, so confirmArrival is still a separate call afterward.
     * 409s if it isn't the local player's turn, no roll is pending, the tile isn't reachable, one's
     * already been bought this turn, or they can't afford it. */
    public void purchaseGoldenGnome(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/purchase-golden-gnome", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Purchase Golden Gnome failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player's BOW emote during a pending Jad encounter. 409s if the encounter
     * isn't pending for this player, or if it already smashed (the bow window expired first). */
    public void bowToJad(String gameId, String playerRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/jad-bow", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Bow to Jad failed (" + resp.code() + "): " + raw);
        }
    }

    /** Spends one of the local player's held items on their own turn. 409s if they don't hold it or
     * it isn't their turn to act. */
    public void useItem(String gameId, String playerRsn, String playerToken, String itemKey) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("itemKey", itemKey);

        try (Response resp = post("/v1/games/" + gameId + "/use-item", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Use item failed (" + resp.code() + "): " + raw);
        }
    }

    /** Spends one of the local player's held target-requiring items (currently only Tele Block) on
     * {@code targetRsn} -- a separate call from useItem since these items need a target to go with
     * them. 409s if they don't hold it, it isn't their turn, or targetRsn isn't an active PLAYER. */
    public void useItemOnPlayer(String gameId, String playerRsn, String playerToken, String itemKey, String targetRsn) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("itemKey", itemKey);
        body.addProperty("target", targetRsn);

        try (Response resp = post("/v1/games/" + gameId + "/use-item-on-player", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Use item on player failed (" + resp.code() + "): " + raw);
        }
    }

    /** Spends a held Coin Trap by placing it at (x, y, plane) -- consumes the item and marks the
     * tile in one call. 409s if the tile isn't directly adjacent to the player's current position,
     * or if it isn't their turn to act. */
    public void placeCoinTrap(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/place-coin-trap", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Place Coin Trap failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player's YES emote during the ready-check screen -- the server inserts
     * MINIGAME_COUNTDOWN_STARTED once every seated PLAYER's made this same call. */
    public void confirmMinigameReady(String gameId, String playerRsn, String playerToken) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post("/v1/games/" + gameId + "/minigame-ready", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Confirm mini-game ready failed (" + resp.code() + "): " + raw);
        }
    }

    /** Submits this player's raw result for the currently-active mini-game. The server determines
     * the winner and coin payout from all submitted results -- the payout amount is never
     * client-dictated, though the underlying performance number is self-reported. */
    public void submitMinigameResult(String gameId, String playerRsn, String playerToken, int score) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("score", score);

        try (Response resp = post("/v1/games/" + gameId + "/submit-minigame-result", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Submit minigame result failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player reaching a still-live Coin Rush spawn tile, keyed by
     * {@code spawnId} rather than just coordinates -- more than one racer can report the same
     * spawn in the same tick, and the server needs to tell that apart from a new spawn that
     * happens to share the tile. (x, y, plane) let the server sanity-check the report against
     * where it actually placed the spawn. The payout is always server-decided. */
    public void collectCoinRushCoin(String gameId, String playerRsn, String playerToken, int spawnId, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("spawnId", spawnId);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/collect-coin-rush-coin", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Collect Coin Rush coin failed (" + resp.code() + "): " + raw);
        }
    }

    public void collectSandwichItem(String gameId, String playerRsn, String playerToken, int spawnId, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("spawnId", spawnId);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/collect-sandwich-item", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Collect Sandwich Rush item failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player's YES ("True")/NO ("False") emote answering the current True or
     * False round. 409s a second attempt for the same round, or if no question is open. Never
     * echoes back correctness -- that's only revealed once the round ends. */
    public void answerTrueOrFalse(String gameId, String playerRsn, String playerToken, boolean answer) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("answer", answer);

        try (Response resp = post("/v1/games/" + gameId + "/true-or-false-answer", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Answer True or False failed (" + resp.code() + "): " + raw);
        }
    }

    // -------------------------------------------------------------------------
    // Course/board building
    // -------------------------------------------------------------------------

    /** One request for a whole course's worth of tiles instead of one request per tile. */
    public void markTiles(String gameId, String writeKey, List<TileSpec> tiles) throws IOException
    {
        JsonArray arr = new JsonArray();
        for (TileSpec t : tiles)
        {
            JsonObject tileObj = new JsonObject();
            tileObj.addProperty("x", t.x);
            tileObj.addProperty("y", t.y);
            tileObj.addProperty("plane", t.plane);
            tileObj.addProperty("tileType", t.tileType);
            if (t.color != null) tileObj.addProperty("color", t.color);
            if (t.orientation != null) tileObj.addProperty("orientation", t.orientation);
            if (t.pathIndex != null) tileObj.addProperty("pathIndex", t.pathIndex);
            if (t.nextIndices.length > 0)
            {
                JsonArray nextArr = new JsonArray();
                for (int idx : t.nextIndices) nextArr.add(idx);
                tileObj.add("nextIndices", nextArr);
            }
            arr.add(tileObj);
        }
        JsonObject body = new JsonObject();
        body.add("tiles", arr);

        try (Response resp = post("/v1/games/" + gameId + "/mark-tiles", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Mark tiles failed (" + resp.code() + "): " + raw);
        }
    }

    public void unmarkTiles(String gameId, String writeKey, List<PointSpec> points) throws IOException
    {
        JsonArray arr = new JsonArray();
        for (PointSpec p : points)
        {
            JsonObject pointObj = new JsonObject();
            pointObj.addProperty("x", p.x);
            pointObj.addProperty("y", p.y);
            pointObj.addProperty("plane", p.plane);
            if (p.tileType != null) pointObj.addProperty("tileType", p.tileType);
            arr.add(pointObj);
        }
        JsonObject body = new JsonObject();
        body.add("tiles", arr);

        try (Response resp = post("/v1/games/" + gameId + "/unmark-tiles", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Unmark tiles failed (" + resp.code() + "): " + raw);
        }
    }

    /** Locks this game to a Standard Course (see HardcodedCourse.java) -- called once, right after
     * that course's tiles are committed via markTiles above. Once locked, the server refuses
     * further mark-tiles/unmark-tiles calls for this game. */
    public void lockStandardCourse(String gameId, String writeKey, String courseKey) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("courseKey", courseKey);

        try (Response resp = post("/v1/games/" + gameId + "/lock-standard-course", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Lock standard course failed (" + resp.code() + "): " + raw);
        }
    }

    // -------------------------------------------------------------------------
    // Event polling / roster
    // -------------------------------------------------------------------------

    public ReadEventsResponse readEvents(String gameId, int afterSeq) throws IOException
    {
        Request req = new Request.Builder()
            .url(BASE_URL + "/v1/games/" + gameId + "/events?afterSeq=" + afterSeq)
            .get()
            .build();

        try (Response resp = http.newCall(req).execute())
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Read events failed (" + resp.code() + "): " + raw);
            ReadEventsResponse parsed = gson.fromJson(raw, ReadEventsResponse.class);
            if (parsed == null) throw new IOException("Empty response from events endpoint");
            if (parsed.events == null) parsed.events = Collections.emptyList();
            return parsed;
        }
    }

    /** Static, server-wide catalog of tile-type colors/labels/descriptions -- not game-scoped, so
     * unlike fetchRoster this only needs fetching once per plugin session. */
    public TileTypesResponse fetchTileTypes() throws IOException
    {
        Request req = new Request.Builder()
            .url(BASE_URL + "/v1/tile-types")
            .get()
            .build();

        try (Response resp = http.newCall(req).execute())
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Fetch tile types failed (" + resp.code() + "): " + raw);
            TileTypesResponse parsed = gson.fromJson(raw, TileTypesResponse.class);
            if (parsed == null) throw new IOException("Empty response from tile-types endpoint");
            if (parsed.tileTypes == null) parsed.tileTypes = Collections.emptyList();
            return parsed;
        }
    }

    public RosterSnapshot fetchRoster(String gameId) throws IOException
    {
        Request req = new Request.Builder()
            .url(BASE_URL + "/v1/games/" + gameId + "/roster")
            .get()
            .build();

        try (Response resp = http.newCall(req).execute())
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Fetch roster failed (" + resp.code() + "): " + raw);
            RosterSnapshot parsed = gson.fromJson(raw, RosterSnapshot.class);
            if (parsed == null) throw new IOException("Empty response from roster endpoint");
            if (parsed.players == null) parsed.players = Collections.emptyList();
            return parsed;
        }
    }

    /** Read-only check that {@code writeKey} still works for {@code gameId} -- a host's writeKey
     * lives only in client memory, so a plugin restart has nothing to reload it from except its own
     * earlier-persisted copy. Confirms that copy is still good and hands back the game's current
     * joinCode/hostRsn/status. Throws ApiHttpException(403) if the key's wrong, (404) if the game's
     * gone -- both mean this game can never be hosted again from here. */
    public HostSessionInfo checkHostSession(String gameId, String writeKey) throws IOException
    {
        try (Response resp = get("/v1/games/" + gameId + "/host-session", writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Check host session failed (" + resp.code() + "): " + raw);
            HostSessionInfo parsed = gson.fromJson(raw, HostSessionInfo.class);
            if (parsed == null) throw new IOException("Empty response from host-session endpoint");
            return parsed;
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Response get(String path, String bearerToken) throws IOException
    {
        Request.Builder builder = new Request.Builder().url(BASE_URL + path).get();
        if (bearerToken != null && !bearerToken.isEmpty())
        {
            builder.header("Authorization", "Bearer " + bearerToken);
        }
        return http.newCall(builder.build()).execute();
    }

    private Response post(String path, JsonObject body, String writeKey) throws IOException
    {
        Request.Builder builder = new Request.Builder()
            .url(BASE_URL + path)
            .post(RequestBody.create(JSON, gson.toJson(body)));
        if (writeKey != null && !writeKey.isEmpty())
        {
            builder.header("Authorization", "Bearer " + writeKey);
        }
        return http.newCall(builder.build()).execute();
    }

    private static String bodyString(Response resp) throws IOException
    {
        return resp.body() != null ? resp.body().string() : "";
    }

    /** Thrown when the server responded, but with a non-2xx status -- carries the HTTP status code
     * so a caller can distinguish a definitive rejection (4xx) from something worth retrying (a
     * network-level IOException, or a 5xx). Not yet thrown by every endpoint here. */
    public static final class ApiHttpException extends IOException
    {
        public final int code;

        public ApiHttpException(int code, String message)
        {
            super(message);
            this.code = code;
        }
    }

    // -------------------------------------------------------------------------
    // Result / response types
    // -------------------------------------------------------------------------

    public static final class CreateGameResult
    {
        public final String gameId;
        public final String joinCode;
        public final String writeKey;
        public final String playerToken;

        public CreateGameResult(String gameId, String joinCode, String writeKey, String playerToken)
        {
            this.gameId = gameId;
            this.joinCode = joinCode;
            this.writeKey = writeKey;
            this.playerToken = playerToken;
        }
    }

    public static final class TileSpec
    {
        public final int x, y, plane;
        public final String tileType;
        public final String color; // nullable
        public final Integer orientation; // nullable -- reserved for future directional tiles
        public final Integer pathIndex; // nullable -- this tile's position along the walked course
        public final int[] nextIndices; // this tile's outgoing edges -- empty means a genuine dead end

        public TileSpec(int x, int y, int plane, String tileType, String color, Integer orientation, Integer pathIndex, int[] nextIndices)
        {
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.tileType = tileType;
            this.color = color;
            this.orientation = orientation;
            this.pathIndex = pathIndex;
            this.nextIndices = nextIndices != null ? nextIndices : new int[0];
        }
    }

    public static final class PointSpec
    {
        public final int x, y, plane;
        public final String tileType; // nullable -- omitted strips every type at this position

        public PointSpec(int x, int y, int plane, String tileType)
        {
            this.x = x;
            this.y = y;
            this.plane = plane;
            this.tileType = tileType;
        }
    }

    public static final class JoinResult
    {
        public final String gameId;
        public final String hostRsn;
        public final String playerToken;

        public JoinResult(String gameId, String hostRsn, String playerToken)
        {
            this.gameId = gameId;
            this.hostRsn = hostRsn;
            this.playerToken = playerToken;
        }
    }

    public static class ReadEventsResponse
    {
        public String gameId;
        public int latestSeq;
        public List<EventOut> events;
    }

    public static class EventOut
    {
        public int seq;
        public String eventId;
        public String ts;
        public String type;
        public JsonObject payload;
    }

    public static class RosterSnapshot
    {
        public String gameId;
        public int latestSeq;
        public String status; // LOBBY/ACTIVE/ENDED -- matches GamePhase's own names
        public String currentTurnRsn; // whose turn it currently is, null outside ACTIVE
        public Integer lastDiceRoll; // most recent server-resolved roll
        public List<RosterPlayerOut> players;
    }

    public static class RosterPlayerOut
    {
        public String rsn;
        public String role; // PLAYER or SPECTATOR
        public boolean joined;
        public boolean online;
        public String number; // turn-order position ("1", "2", ...)
        public String colorNumber; // host-chosen seat color while a PLAYER, "" once removed
        public int coins;
        public int goldenGnomeCount;
        public Map<String, Integer> items = new HashMap<>(); // itemKey -> count held
    }

    public static class TileTypesResponse
    {
        public List<TileTypeOut> tileTypes;
    }

    public static class HostSessionInfo
    {
        public String gameId;
        public String joinCode;
        public String hostRsn;
        public String status; // LOBBY/ACTIVE/ENDED -- matches GamePhase's own names
    }

    public static class TileTypeOut
    {
        public String key;
        public String displayName;
        public String colorHex;
        public String description;
        public boolean isModifier;
        public boolean isMinigameTile; // only ever spawned by a mini-game's own board swap
    }

    private static class CreateGameResponse
    {
        String gameId;
        String joinCode;
        String writeKey;
        String playerToken;
    }

    private static class JoinResponse
    {
        String gameId;
        String host;
        String playerToken;
    }
}
