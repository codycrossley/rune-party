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
    static final String BASE_URL = "http://localhost:8000/runeparty";
    // static final String BASE_URL = "https://runeparty.shrunk.studio/runeparty";

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

    /** Host-only kick -- same PLAYER_LEFT outcome as leaveGame, just authorized via the host's own
     * write key instead of the target's session token (which the host doesn't have). See
     * RunePartyPlugin#removePlayer, the only caller. */
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
     * only ever grants SPECTATOR (see /v1/join/{code}) -- this is how the host opts someone into
     * the turn order, same assign-role contract as Gnomeball's ApiClient. colorNumber is the
     * host's own explicit seat-color choice (see RunePartyColor#seatNumber, and RunePartyPlugin#
     * addToGameMenuEntry/RunePartyPanel#buildAddToGamePopup, which offer one submenu entry per
     * currently-available color) -- null for a SPECTATOR demotion, or to let the server auto-pick
     * the lowest available color instead. */
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Confirm arrival failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports that the local player has finished walking to the Start tile after using a Home
     * Teleport -- see the server's own confirm-home-teleport-arrival endpoint, which is what
     * actually pays out the reward (never at use time, see items/home_teleport.py's own doc). Same
     * "client reports a position claim, server decides" trust boundary confirmArrival already
     * uses, just not tied to a pending roll -- Home Teleport is a free action, so this can be
     * called well after (even a turn or more after) the item was actually used. */
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
     * playable (see RunePartyPlugin#onGameTick) -- unlike confirmArrival/confirmHomeTeleportArrival,
     * this isn't a one-shot report of reaching a specific destination, it's a live heartbeat a
     * mini-game's own server-side round (e.g. the Arena's hazard tiles) reads back to know who's
     * standing where, moment to moment. Generic, not Arena-specific -- any future mini-game that
     * needs live positions reuses this same call. */
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

    /** Submits the local player's final Fishing Contest catch tally -- see the server's own
     * submit-fishing-catch endpoint. Fired exactly once per round, from RunePartyPlugin#
     * submitFishingCatch the moment that client's own local 30-second timer elapses -- unlike
     * reportMinigamePosition's own per-tick heartbeat, this is a one-shot report, not a
     * continuously-repeated one. */
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

    /** Buys the Golden Gnome currently standing at (x, y, plane) -- see the server's own
     * purchase-golden-gnome endpoint. A free side-action during the local player's own pending
     * roll, triggered by a right-click "Purchase Golden Gnome" menu entry rather than an emote --
     * doesn't touch pendingRoll or advance the turn, so confirmArrival is still a separate call
     * afterward. 409s if it isn't genuinely the local player's turn, no roll is pending, the tile
     * isn't reachable within the current roll, they've already bought one this turn, or they can't
     * afford it -- the server, not this call's caller, checks and debits the coin balance. */
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

    /** Reports the local player's BOW emote during a pending Jad encounter -- same
     * report-then-server-decides shape as answerTrueOrFalse. 409s if the encounter isn't
     * pending for this player, or if it already smashed (the bow window expired first). */
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

    /** Spends one of the local player's held requires_target items (currently only Tele Block) on
     * {@code targetRsn} -- see the server's own use-item-on-player endpoint, a separate call from
     * useItem the same way placeCoinTrap is (see that method's own doc), since a requires_target
     * item has no meaningful use without a target to go with it. 409s if they don't hold it, it
     * isn't genuinely their turn to act, or targetRsn isn't an active PLAYER in this game. */
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

    /** Spends a held Coin Trap by placing it at (x, y, plane) -- see the server's own
     * place-coin-trap endpoint, which both consumes the item and marks the tile in one call (there's
     * no separate "use" step for a requires_placement item, see Item#requiresPlacement). 409s if
     * the tile isn't one of the two directly adjacent to the player's own current position, or if
     * it isn't genuinely their turn to act. */
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

    /** Reports the local player reaching a still-live Coin Rush spawn tile (see the server's own
     * collect-coin-rush-coin endpoint), keyed by {@code spawnId} rather than just the tile's own
     * coordinates -- more than one racer can report the exact same spawn within the same tick, and
     * the server needs to tell "two separate reports for the same still-live spawn, first one
     * wins" apart from "this spawn already got claimed and a new one just happens to share the
     * tile." (x, y, plane) are carried alongside purely so the server can sanity-check the report
     * against where it actually placed that spawn, the same trust-boundary shape confirmArrival's
     * own (x, y, plane) already has for a rolled destination -- the payout itself (+2 coins,
     * COIN_RUSH_COLLECTED naming the actual winner) is always server-decided, never this call's own
     * response. */
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

    /** Reports the local player's YES ("True")/NO ("False") emote answering the current True or
     * False round -- see the server's own true-or-false-answer endpoint, which 409s a second
     * attempt for the same round (an answer, once landed, is final) or if no question is
     * currently open. Never echoes back correctness -- that's only ever revealed once the round's
     * own clock runs out, via TRUE_OR_FALSE_ROUND_ENDED. */
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
    // Course/board building -- same tile-marking shape as Gnomeball's field
    // builder, extended with a path index so a course is an ordered sequence
    // rather than an unordered zone/field set.
    // -------------------------------------------------------------------------

    /** One request for a whole course's worth of tiles instead of one request per tile, same
     * reasoning as Gnomeball's markTiles/unmarkTiles. */
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

    /** Static, server-wide catalog of tile-type colors/labels/descriptions
     * -- not game-scoped, so unlike fetchRoster this only needs fetching once per
     * plugin session, not per game. See RunePartyPlugin#loadTileTypeCatalog. */
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

    /** Thrown when the server actually responded, but with a non-2xx status -- carries the real
     * HTTP status code so a caller can tell a definitive rejection (4xx: the server has already
     * looked at this exact request and said no -- retrying the identical request will only get
     * the identical answer again) apart from something worth retrying (a genuine network-level
     * IOException with no response at all, or a 5xx server error). Not yet thrown by every
     * endpoint here -- see confirmArrival, the first caller that actually needed the distinction
     * (RunePartyPlugin's own confirmArrival was retrying a 409 "No roll is pending" forever, once
     * a tick, since nothing told it the claim itself -- not just the request -- was invalid). */
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
        public String status; // LOBBY/ACTIVE/ENDED -- matches GamePhase's own names exactly, see RunePartyPlugin#syncRosterSnapshot
        public String currentTurnRsn; // whose turn it currently is, null outside ACTIVE -- reconciled into RunePartyPlugin#currentTurnRsn on reconnect, see syncRosterSnapshot
        public Integer lastDiceRoll; // most recent server-resolved roll -- reconciled into RunePartyPlugin#lastDiceRoll on reconnect, see syncRosterSnapshot
        public List<RosterPlayerOut> players;
    }

    public static class RosterPlayerOut
    {
        public String rsn;
        public String role; // PLAYER or SPECTATOR
        public boolean joined;
        public boolean online;
        public String number; // turn-order position ("1", "2", ...), same field shape RosterReducer already expects
        public String colorNumber; // host-chosen seat color while a PLAYER, "" once removed -- see RunePartyColor#forNumber
        public int coins;
        public int goldenGnomeCount;
        public Map<String, Integer> items = new HashMap<>(); // itemKey -> count held, defaulted so an older server response without this field doesn't NPE
    }

    public static class TileTypesResponse
    {
        public List<TileTypeOut> tileTypes;
    }

    public static class TileTypeOut
    {
        public String key;
        public String displayName;
        public String colorHex;
        public String description;
        public boolean isModifier;
        public boolean isMinigameTile; // only ever spawned by a mini-game's own board swap -- see addSetTileSubmenu
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
