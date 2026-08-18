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
    // No production server exists yet -- the companion server is still to be built (see the plan's
    // "Server skeleton" step). Point this at wherever that ends up deployed once it does; until
    // then this is the local-dev default.
    static final String BASE_URL = "http://localhost:8000/runeparty";

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
            if (!resp.isSuccessful()) throw new IOException("Join failed (" + resp.code() + "): " + raw);
            JoinResponse parsed = gson.fromJson(raw, JoinResponse.class);
            return new JoinResult(parsed.gameId, parsed.host, parsed.playerToken);
        }
    }

    /** {@code maxRounds} is "turns per player" -- the host-set limit after which the game ends
     * (following that round's mini-game). The server deliberately doesn't insert the first
     * TURN_STARTED here; see confirmStart. */
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
     * order has actually begun (currentTurnRsn still null) -- once every seated PLAYER has called
     * this, the server inserts the first TURN_STARTED itself. Same report-then-server-decides
     * shape as confirmArrival, just for the pre-game gathering window instead of a rolled move. */
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

    /** Host-only: promotes/demotes a roster member between PLAYER and SPECTATOR. Joining a game
     * only ever grants SPECTATOR (see /v1/join/{code}) -- this is how the host opts someone into
     * the turn order, same assign-role contract as Gnomeball's ApiClient. */
    public void assignRole(String gameId, String writeKey, String playerRsn, RunePartyRole role) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("role", role.name());

        try (Response resp = post("/v1/games/" + gameId + "/assign-role", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Assign role failed (" + resp.code() + "): " + raw);
        }
    }

    // -------------------------------------------------------------------------
    // Turn engine -- dice rolls and coin balances are always server-resolved.
    // These calls report a request/claim; the authoritative outcome comes back
    // as an event (DICE_ROLLED / PLAYER_MOVED / COINS_CHANGED / ...), same
    // report-then-wait-for-echo pattern Gnomeball's README documents.
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

    /** Reports that the local player has finished walking to the tile their roll resolved to.
     * The server validates this against the pending roll before resolving the tile's effect and
     * advancing the turn -- same "client reports a position claim, server decides" trust boundary
     * Gnomeball's zoneGoal/outOfBounds use (see gnomeball/LIMITATIONS.md Section 1). */
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
            if (!resp.isSuccessful()) throw new IOException("Confirm arrival failed (" + resp.code() + "): " + raw);
        }
    }

    /** Accepts or declines a pending Golden Gnome offer (see RunePartyPlugin's GOLDEN_GNOME_OFFERED
     * handling) -- triggered by the player's own YES/NO emote, not a panel button; there's no
     * standalone "walk up and buy one anytime" flow anymore now that a Golden Gnome is a modifier
     * on a PATH tile rather than a course stop of its own. The server -- not this call's caller --
     * checks and debits the coin balance on accept; an unaffordable accept simply resolves to the
     * "cant_afford" outcome rather than ever letting the client decide affordability. */
    public void respondGoldenGnomeOffer(String gameId, String playerRsn, String playerToken, boolean accept) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("accept", accept);

        try (Response resp = post("/v1/games/" + gameId + "/respond-golden-gnome-offer", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Respond to Golden Gnome offer failed (" + resp.code() + "): " + raw);
        }
    }

    /** Spends one of the local player's held items on their own turn -- see the server's own
     * use-item endpoint, which applies the item's effect and 409s if they don't actually hold it
     * or it isn't genuinely their turn to act. */
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

    /** Submits this player's raw result for the currently-active mini-game. The server determines
     * the winner and coin payout from all submitted results -- the payout amount is never
     * client-dictated -- but the underlying performance number is necessarily self-reported by
     * each client, the same trust boundary as every other client-observed claim in this model. */
    /** Reports the local player's YES emote during the ready-check screen -- see the server's own
     * minigame_ready, which inserts MINIGAME_COUNTDOWN_STARTED once every seated PLAYER's made
     * this same call, same "report-then-server-decides" shape as confirmStart. */
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

    // -------------------------------------------------------------------------
    // Course/board building -- same tile-marking shape as Gnomeball's field
    // builder, extended with a path index so a course is an ordered sequence
    // rather than an unordered zone/field set.
    // -------------------------------------------------------------------------

    public void markTile(String gameId, String writeKey, int x, int y, int plane, String tileType, String color, Integer pathIndex) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);
        body.addProperty("tileType", tileType);
        if (color != null) body.addProperty("color", color);
        if (pathIndex != null) body.addProperty("pathIndex", pathIndex);

        try (Response resp = post("/v1/games/" + gameId + "/mark-tile", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Mark tile failed (" + resp.code() + "): " + raw);
        }
    }

    public void unmarkTile(String gameId, String writeKey, int x, int y, int plane, String tileType) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);
        if (tileType != null) body.addProperty("tileType", tileType);

        try (Response resp = post("/v1/games/" + gameId + "/unmark-tile", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Unmark tile failed (" + resp.code() + "): " + raw);
        }
    }

    /** Batch counterpart to markTile/unmarkTile -- one request for a whole course's worth of tiles
     * instead of one request per tile, same reasoning as Gnomeball's markTiles/unmarkTiles. */
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

    public TilesResponse fetchTiles(String gameId) throws IOException
    {
        Request req = new Request.Builder()
            .url(BASE_URL + "/v1/games/" + gameId + "/tiles")
            .get()
            .build();

        try (Response resp = http.newCall(req).execute())
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new IOException("Fetch tiles failed (" + resp.code() + "): " + raw);
            TilesResponse parsed = gson.fromJson(raw, TilesResponse.class);
            if (parsed == null) throw new IOException("Empty response from tiles endpoint");
            if (parsed.tiles == null) parsed.tiles = Collections.emptyList();
            return parsed;
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

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

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
        public final int[] nextIndices; // empty -- the default (pathIndex + 1) % courseLength edge applies server-side; see app.py's _resolve_next_indices

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
        public String status;
        public String currentTurnRsn; // whose turn it currently is, null outside ACTIVE
        public Integer lastDiceRoll; // most recent server-resolved roll, for a client that just (re)connected
        public String courseName;
        public List<RosterPlayerOut> players;
    }

    public static class RosterPlayerOut
    {
        public String rsn;
        public String role; // PLAYER or SPECTATOR
        public boolean joined;
        public boolean online;
        public String number; // turn-order position ("1", "2", ...), same field shape RosterReducer already expects
        public int coins;
        public int goldenGnomeCount;
        public Map<String, Integer> items = new HashMap<>(); // itemKey -> count held, defaulted so an older server response without this field doesn't NPE
    }

    public static class TilesResponse
    {
        public String gameId;
        public List<TileOut> tiles;
    }

    public static class TileOut
    {
        public int x;
        public int y;
        public int plane;
        public String tileType;
        public String color;
        public Integer orientation;
        public Integer pathIndex;
        public int[] nextIndices; // nullable -- absent/empty means the default (pathIndex + 1) % courseLength edge applies
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

    static boolean isBlank(String s)
    {
        return s == null || s.trim().isEmpty();
    }
}
