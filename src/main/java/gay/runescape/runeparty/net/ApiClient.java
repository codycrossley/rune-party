package gay.runescape.runeparty.net;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gay.runescape.runeparty.RunePartyRole;
import okhttp3.*;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

public class ApiClient
{
    static final String BASE_URL = "http://localhost:8005/runeparty";
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Create game failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Start game failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Confirm start failed (" + resp.code() + "): " + raw);
        }
    }

    public void endGame(String gameId, String writeKey) throws IOException
    {
        try (Response resp = post("/v1/games/" + gameId + "/end", new JsonObject(), writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "End game failed (" + resp.code() + "): " + raw);
        }
    }

    public void leaveGame(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/leave", playerRsn, playerToken, "Leave");
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Remove player failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Assign role failed (" + resp.code() + "): " + raw);
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
        postPlayerAction("/v1/games/" + gameId + "/roll-dice", playerRsn, playerToken, "Roll dice");
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

    /** One-shot, position-free "I'm on the grid" report for Arena -- fired once, the instant the
     * local client detects its own position landing on any ARENA_TILE this round, rather than a
     * continuous per-tick heartbeat. See ArenaPresentation#onTick, the only caller. */
    public void confirmArenaArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-arena-arrival", playerRsn, playerToken, "Confirm Arena arrival");
    }

    /** A player's own one-shot self-report that it was just caught by the flame field -- fired only
     * when this client's own local check (ArenaPresentation#onTick) independently found its own
     * real position either standing on a tile it already knows just turned permanently dead, or off
     * the grid entirely after having genuinely arrived. See confirmArenaArrival above and
     * ArenaPresentation's own doc for why no position needs to travel with this call at all. */
    public void confirmArenaElimination(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-arena-elimination", playerRsn, playerToken, "Confirm Arena elimination");
    }

    /** One-shot, position-free "I've reached my own required zone" ready-check for Brutus Attack
     * -- fired once, the instant the local client detects standing on its own required zone's
     * colored tile, rather than a continuous per-tick heartbeat. See BrutusAttackPresentation#onTick,
     * the only caller. */
    public void confirmBrutusArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-brutus-arrival", playerRsn, playerToken, "Confirm Brutus arrival");
    }

    /** One-shot "I just entered the target zone" report for Brutus Attack -- fired once, only by
     * whichever client is currently transformed into Brutus, the instant it detects its own
     * position landing on a target-zone tile. Broadcast back out to every other seated client,
     * which each independently check their own real position against it -- see
     * BrutusAttackPresentation#onTick (the report) and #apply (the check on the receiving end),
     * and confirmBrutusElimination below (what a hit actually reports). */
    public void confirmBrutusDash(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/confirm-brutus-dash", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Confirm Brutus dash failed (" + resp.code() + "): " + raw);
        }
    }

    /** A target's own one-shot self-report that Brutus's dash just landed on it -- fired only when
     * this client's own local check (BrutusAttackPresentation#apply, reacting to
     * confirmBrutusDash's own broadcasted report) found a match against its own real position. */
    public void confirmBrutusElimination(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-brutus-elimination", playerRsn, playerToken, "Confirm Brutus elimination");
    }

    /** A target's own one-shot self-report that it walked off its required zone's tiles after
     * already arriving there this round, while the round was actually active -- fired only by
     * BrutusAttackPresentation#onTick's own local check (dashWindowOpenThisRound gates it, so the
     * pre-round gather/setup phase never triggers this). Deliberately a separate call from
     * confirmBrutusElimination above, even though both settle into the same eliminatedRsns set --
     * Brutus didn't actually do anything here, so this one must never flash "HIT!"; the mini-game
     * just carries on silently unless this emptied out every remaining target. */
    public void confirmBrutusTargetLeftZone(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-brutus-target-left-zone", playerRsn, playerToken, "Confirm Brutus target left zone");
    }

    /** Brutus's own one-shot self-report that he's stepped completely off the arena (any of his
     * own zone, the neutral corridor, or the targets' zone) before reaching the target zone this
     * round -- fired only by whichever client is currently transformed into Brutus, the instant it
     * detects its own position off every BRUTUS_ATTACK_TILE. See BrutusAttackPresentation#onTick,
     * the only caller, and RunePartyPlugin#findBrutusAttackArenaTiles for the combined tile set
     * checked against. Server-side, this cuts the round short as an immediate "MISS!" rather than
     * a bespoke event of its own -- see brutus_out_of_bounds's own doc on the server. */
    public void confirmBrutusOutOfBounds(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-brutus-out-of-bounds", playerRsn, playerToken, "Confirm Brutus out-of-bounds");
    }

    /** Submits the local player's final Fishing Contest catch tally. Fired once per round, when
     * the local 30-second timer elapses -- a one-shot report, not a per-tick heartbeat. */
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

    /** Submits the local player's final Click, Click, Click unique-tile-click tally. Fired once
     * per round, when the local 30-second timer elapses -- same one-shot shape as
     * submitFishingCatch. */
    public void submitClickClickClickResult(String gameId, String playerRsn, String playerToken, int uniqueTiles) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("uniqueTiles", uniqueTiles);

        try (Response resp = post("/v1/games/" + gameId + "/submit-click-click-click-result", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Submit Click, Click, Click result failed (" + resp.code() + "): " + raw);
        }
    }

    /** Submits the local player's final Crab Rave Dance-emote tally. Fired once per round, when
     * the local 30-second timer elapses -- same one-shot shape as submitClickClickClickResult. */
    public void submitCrabRaveResult(String gameId, String playerRsn, String playerToken, int danceCount) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("danceCount", danceCount);

        try (Response resp = post("/v1/games/" + gameId + "/submit-crab-rave-result", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Submit Crab Rave result failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player's final Dance, Dance, RuneScape tally -- called exactly once per
     * round, when the local 30-second timer elapses -- same one-shot shape as
     * submitFishingCatch/submitClickClickClickResult. */
    public void submitDanceDanceRuneScapeResult(String gameId, String playerRsn, String playerToken, int score) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("score", score);

        try (Response resp = post("/v1/games/" + gameId + "/submit-ddr-result", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Submit Dance, Dance, RuneScape result failed (" + resp.code() + "): " + raw);
        }
    }

    /** A player's own one-shot self-report that it just reached the Dance, Dance, RuneScape arena
     * -- fired the instant this client locally detects standing on any DDR_CENTER_TILE this round.
     * No position travels with this call: the server's own arrival gate just needs to know who,
     * not where. See confirmArenaArrival's own doc for the full reasoning behind this shape. */
    public void confirmDdrArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-ddr-arrival", playerRsn, playerToken, "Confirm Dance, Dance, RuneScape arrival");
    }

    /** Reports how long this client's own Dance, Dance, RuneScape round will run for -- fired once
     * at round-begin (see DanceDanceRuneScapePresentation#onRoundBegin), so the server can size its
     * own end-of-round wait to match whichever sequence this game actually picked, without the
     * server ever needing the sequence data itself. Every seated client computes and reports the
     * identical value (same deterministic sequence pick), so which one's report "wins" server-side
     * doesn't matter. */
    public void reportDanceDanceRuneScapeRoundDuration(String gameId, String playerRsn, String playerToken, long durationMs) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("durationMs", durationMs);

        try (Response resp = post("/v1/games/" + gameId + "/report-ddr-round-duration", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Report Dance, Dance, RuneScape round duration failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player's own one-shot, unconditional result for the current Repeat After
     * Me round -- called exactly once per round, when the local challenge-window timer elapses,
     * regardless of whether every target tile was actually hit (completed can be false). Same
     * one-shot shape submitDanceDanceRuneScapeResult uses, except a stale retry for a round that's
     * already moved on gets rejected server-side (see app.py's submit_repeat_after_me_result). */
    public void submitRepeatAfterMeResult(String gameId, String playerRsn, String playerToken, int roundNumber, boolean completed) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("roundNumber", roundNumber);
        body.addProperty("completed", completed);

        try (Response resp = post("/v1/games/" + gameId + "/submit-repeat-after-me-result", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Submit Repeat After Me result failed (" + resp.code() + "): " + raw);
        }
    }

    /** A player's own one-shot self-report that it just reached the current Repeat After Me round's
     * own target cell/arena -- fired the instant this client locally detects standing on any
     * REPEAT_AFTER_ME_TILE. No position travels with this call. See confirmArenaArrival's own doc
     * for the full reasoning behind this shape. */
    public void confirmRepeatAfterMeArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-repeat-after-me-arrival", playerRsn, playerToken, "Confirm Repeat After Me arrival");
    }

    /** A player's own one-shot self-report that it just reached the course's own real START tile
     * for Rainbow Rush -- fired the instant this client locally detects standing there. No position
     * travels with this call. See confirmArenaArrival's own doc for the full reasoning behind this
     * shape. */
    public void confirmRainbowRushArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-rainbow-rush-arrival", playerRsn, playerToken, "Confirm Rainbow Rush arrival");
    }

    /** A player's own one-shot self-report that it just reached the Rune Match arena -- fired the
     * instant this client locally detects standing on any RUNE_MATCH_TILE. No position travels
     * with this call. See confirmArenaArrival's own doc for the full reasoning behind this shape. */
    public void confirmRuneMatchArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-rune-match-arrival", playerRsn, playerToken, "Confirm Rune Match arrival");
    }

    /** A player's own one-shot self-report that it just reached the Golden Gnome Awards ceremony
     * arena -- fired the instant this client locally detects standing on any CEREMONY_TILE. No
     * position travels with this call. See confirmArenaArrival's own doc for the full reasoning
     * behind this shape. */
    public void confirmCeremonyArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-ceremony-arrival", playerRsn, playerToken, "Confirm ceremony arrival");
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Purchase Golden Gnome failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player's BOW emote during a pending Jad encounter. 409s if the encounter
     * isn't pending for this player, or if it already smashed (the bow window expired first). */
    public void bowToJad(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/jad-bow", playerRsn, playerToken, "Bow to Jad");
    }

    /** Resolves a pending Wise Old Man encounter -- action is "steal_coins", "steal_golden_gnome",
     * or "decline"; target is the rsn to steal from (required for either steal action, ignored for
     * decline). 409s if no encounter is pending for this player, or if the server's own
     * affordability/ownership re-check rejects a steal the dialogue shouldn't have offered in the
     * first place. */
    public void wiseOldManChoose(String gameId, String playerRsn, String playerToken, String action, String target) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("action", action);
        if (target != null) body.addProperty("target", target);

        try (Response resp = post("/v1/games/" + gameId + "/wise-old-man-choose", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Wise Old Man choice failed (" + resp.code() + "): " + raw);
        }
    }

    public void itemShopChoose(String gameId, String playerRsn, String playerToken, String action, String itemKey) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("action", action);
        if (itemKey != null) body.addProperty("itemKey", itemKey);

        try (Response resp = post("/v1/games/" + gameId + "/item-shop-choose", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Item Shop choice failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Use item failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Use item on player failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Place Coin Trap failed (" + resp.code() + "): " + raw);
        }
    }

    /** Reports the local player's YES emote during the ready-check screen -- the server inserts
     * MINIGAME_COUNTDOWN_STARTED once every seated PLAYER's made this same call. */
    public void confirmMinigameReady(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/minigame-ready", playerRsn, playerToken, "Confirm mini-game ready");
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Submit minigame result failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Collect Coin Rush coin failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Collect Sandwich Rush item failed (" + resp.code() + "): " + raw);
        }
    }

    /** A player's own one-shot self-report that it just reached the Sandwich Rush arena -- fired
     * the instant this client locally detects standing on any SANDWICH_RUSH_TILE this round. No
     * position travels with this call. See confirmArenaArrival's own doc for the full reasoning
     * behind this shape. */
    public void confirmSandwichRushArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-sandwich-rush-arrival", playerRsn, playerToken, "Confirm Sandwich Rush arrival");
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

    /** Passes the Hot Potato -- only succeeds if the caller is the current holder. */
    public void passHotPotato(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/hot-potato-pass", playerRsn, playerToken, "Pass Hot Potato");
    }

    /** A player's own one-shot self-report that it just reached the Hot Potato arena -- fired the
     * instant this client locally detects standing on any HOT_POTATO_TILE this round. No position
     * travels with this call. See confirmArenaArrival's own doc for the full reasoning behind this
     * shape. */
    public void confirmHotPotatoArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-hot-potato-arrival", playerRsn, playerToken, "Confirm Hot Potato arrival");
    }

    /** A player's own one-shot self-report that it just reached the Turf Wars arena -- fired the
     * instant this client locally detects standing on any TURF_WARS_TILE this round. No position
     * travels with this call. See confirmArenaArrival's own doc for the full reasoning behind this
     * shape. Distinct from claimTurfWarsTile just below -- this is the one-shot arrival gate, that's
     * the ongoing, repeatable claiming action. */
    public void confirmTurfWarsArrival(String gameId, String playerRsn, String playerToken) throws IOException
    {
        postPlayerAction("/v1/games/" + gameId + "/confirm-turf-wars-arrival", playerRsn, playerToken, "Confirm Turf Wars arrival");
    }

    /** Claims a single Turf Wars tile for the local player's own assigned color -- fired only when
     * this client locally detects standing on a tile that isn't already that color (see
     * TurfWarsPresentation#onTick), replacing what used to be a continuous per-tick position
     * heartbeat the server polled for every claim. (x, y, plane) let the server sanity-check the
     * report against the board's own current tiles, same reasoning collectSandwichItem's own
     * (x, y, plane) already has. */
    public void claimTurfWarsTile(String gameId, String playerRsn, String playerToken, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/claim-turf-wars-tile", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Claim Turf Wars tile failed (" + resp.code() + "): " + raw);
        }
    }

    /** A player's own one-shot self-report that its live Who's Your Jaddy? zone membership just
     * changed -- fired only when this client locally detects its current position has moved into a
     * different zone than the one it last reported (see WhosYourJaddyPresentation#onTick),
     * replacing what used to be a continuous per-tick position heartbeat the server polled at the
     * duel's own resolution instant. colorHex is TEAM_A_COLOR, TEAM_B_COLOR, or null for "standing
     * in neither zone right now". */
    public void reportJaddyZone(String gameId, String playerRsn, String playerToken, String colorHex) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);
        body.addProperty("colorHex", colorHex);

        try (Response resp = post("/v1/games/" + gameId + "/report-jaddy-zone", body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Report Jaddy zone failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Mark tiles failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Unmark tiles failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Lock standard course failed (" + resp.code() + "): " + raw);
        }
    }

    /** Pins {@code minigameKey}'s own board-swapped arena to an exact world point for the rest of
     * this game -- see app.py's set_minigame_spawn_point's own doc for why this is allowed
     * regardless of whether the course is a locked Standard Course. */
    public void setMinigameSpawnPoint(String gameId, String writeKey, String minigameKey, int x, int y, int plane) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("minigameKey", minigameKey);
        body.addProperty("x", x);
        body.addProperty("y", y);
        body.addProperty("plane", plane);

        try (Response resp = post("/v1/games/" + gameId + "/set-minigame-spawn-point", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Set mini-game spawn point failed (" + resp.code() + "): " + raw);
        }
    }

    /** Resets {@code minigameKey} back to the default bounding-box-center placement -- see
     * setMinigameSpawnPoint's own doc. */
    public void clearMinigameSpawnPoint(String gameId, String writeKey, String minigameKey) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("minigameKey", minigameKey);

        try (Response resp = post("/v1/games/" + gameId + "/clear-minigame-spawn-point", body, writeKey))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Clear mini-game spawn point failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Read events failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Fetch tile types failed (" + resp.code() + "): " + raw);
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
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), "Fetch roster failed (" + resp.code() + "): " + raw);
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

    /** Shared body for the ~17 one-shot "here's who I am" reports below (arrival/elimination
     * confirmations, ready-checks, and similar) -- each one used to hand-roll the identical
     * {@code {"player": playerRsn}} POST + throw-on-failure shape (see
     * ARCHITECTURE_REVIEW.md's C1). Every caller now gets ApiHttpException uniformly, including the
     * five (leaveGame/rollDice/bowToJad/confirmMinigameReady/passHotPotato) that used to throw plain
     * IOException instead -- a caller catching IOException is unaffected either way, since
     * ApiHttpException already extends it (see that class's own doc, and ARCHITECTURE_REVIEW.md's
     * C2 for why the old split was worth closing). */
    private void postPlayerAction(String path, String playerRsn, String playerToken, String errorLabel) throws IOException
    {
        JsonObject body = new JsonObject();
        body.addProperty("player", playerRsn);

        try (Response resp = post(path, body, playerToken))
        {
            String raw = bodyString(resp);
            if (!resp.isSuccessful()) throw new ApiHttpException(resp.code(), errorLabel + " failed (" + resp.code() + "): " + raw);
        }
    }

    private static String bodyString(Response resp) throws IOException
    {
        return resp.body() != null ? resp.body().string() : "";
    }

    /** Thrown when the server responded, but with a non-2xx status -- carries the HTTP status code
     * so a caller can distinguish a definitive rejection (4xx) from something worth retrying (a
     * network-level IOException, or a 5xx). Thrown uniformly by every non-2xx check in this class
     * now (see ARCHITECTURE_REVIEW.md's C2) -- the only remaining plain IOExceptions left are the
     * handful of "server said 200 but the body was empty/malformed" cases (readEvents/
     * fetchTileTypes/fetchRoster/checkHostSession), which have no real HTTP status to attach. */
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
