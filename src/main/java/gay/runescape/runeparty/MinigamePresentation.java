package gay.runescape.runeparty;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Mini-game (selection/ready-check/countdown), Coin Rush, and True or False state and event
 * handling -- extracted from RunePartyPlugin per ARCHITECTURE_REVIEW.md's C1 finding, step 2. Owns
 * its own fields, folds most of its own event types via apply(), and exposes handleMinigameEnded()
 * for RunePartyPlugin's hybrid MINIGAME_ENDED case to call (completedRounds/maxRounds themselves
 * stay core -- they represent whole-game progress, not one mini-game instance's own state, see
 * RunePartyPlugin#getCurrentRound). Clears itself via reset(); RunePartyPlugin still exposes every
 * getter under its original name, just delegating here, so no external caller needs to change. */
final class MinigamePresentation
{
    private final RunePartyPlugin plugin;

    private volatile ScheduledFuture<?> minigameSpinnerTask;
    private volatile boolean minigameActive = false;
    private volatile String minigameInstructions = null;
    // Which minigames/ registry entry is active -- see the server's own minigames package, whose
    // Minigame.key values these match, and RunePartyPanel/gay.runescape.runeparty.minigames.
    // Minigames#get, which looks up this key's own control-panel UI.
    private volatile String minigameKey = null;
    // Announced by AnnouncementOverlay's selection spinner once it settles -- see
    // renderMinigameSpinner/renderMinigameReadyCheck, the only consumers.
    private volatile String minigameDisplayName = null;
    // ---- mini-game selection spinner (cosmetic-only timing, chained behind the "MINIGAME!"
    // banner -- see scheduleMinigameSpinner) ----
    private volatile long minigameSpinnerStart = 0;
    private volatile long minigameSpinnerUntil = 0;
    // Real state: true iff this MINIGAME_STARTED was applied during catch-up, meaning the spinner
    // never plays for this client this round (see scheduleMinigameSpinner's !catchingUp guard) --
    // so minigameSpinnerUntil staying 0 means "already resolved, skip straight to the ready-check"
    // rather than "hasn't started yet." Set unconditionally in the MINIGAME_STARTED handler,
    // exactly like minigameCountdownStarted's split for the same reason -- see
    // AnnouncementOverlay#renderMinigameReadyCheck, the only consumer.
    private volatile boolean minigameSpinnerSkippedForClient = false;
    // ---- mini-game ready-check (server-driven, everyone sees it -- see MINIGAME_PLAYER_READY/
    // MINIGAME_COUNTDOWN_STARTED handling). minigameReadyRsns is real state (who's actually
    // YES-emoted so far), applied unconditionally catch-up or not -- same reasoning as
    // RunePartyPlugin's own playerPositions. minigameCountdownStarted is likewise real state; only
    // minigameCountdownBannerUntil (the visual "3...2...1... BEGIN!") is cosmetic-only, deliberately
    // armed MINIGAME_COUNTDOWN_START_DELAY_MS after minigameCountdownStarted flips, so the
    // ready-check screen gets to actually show everyone marked "Ready!" for a beat first (see
    // renderMinigameReadyCheck/isMinigamePlayable, both of which need to tell "hasn't been armed
    // yet, still in that pause" apart from "already resolved, this client only caught up" -- that's
    // what minigameCountdownSkippedForClient is for, same idiom as minigameSpinnerSkippedForClient
    // above). ----
    private final Set<String> minigameReadyRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    private volatile boolean minigameCountdownStarted = false;
    private volatile boolean minigameCountdownSkippedForClient = false;
    private volatile long minigameCountdownBannerUntil = 0;
    // Real state, applied catch-up or not: true once MINIGAME_ROUND_BEGIN has genuinely landed for
    // the current mini-game -- unlike minigameCountdownBannerUntil (a fixed local timer that always
    // resolves MINIGAME_COUNTDOWN_DURATION_MS after arming, regardless of what the server's
    // actually doing), this reflects the real server-side moment, whatever it turns out to be. The
    // Arena mini-game's own round-begin timing depends on when everyone's actually walked onto its
    // grid (see the server's minigames/arena.py), which isn't tied to the generic countdown's fixed
    // schedule at all -- see AnnouncementOverlay#renderArenaGatherMessage, the reader this exists
    // for.
    private volatile boolean minigameRoundBegun = false;
    // Same idea as JadPresentation's own awaitingBowFinish, one per response to a
    // pending mini-game ready-check/True-or-False round -- see RunePartyPlugin#onAnimationChanged,
    // which consults these via the arm/isAwaiting/clear methods below as part of the same
    // priority-ordered "which gesture am I waiting for" chain every other feature's own awaiting
    // flags participate in.
    private volatile boolean awaitingMinigameReadyFinish = false;
    private volatile boolean awaitingTrueOrFalseYesFinish = false;
    private volatile boolean awaitingTrueOrFalseNoFinish = false;
    // ---- minigame banner (server-driven, everyone sees it -- see MINIGAME_STARTED handling) ----
    private final TimedBanner<Void> minigameBanner = new TimedBanner<>();
    // ---- minigame-over banner (server-driven, everyone sees it -- see MINIGAME_ENDED handling and
    // triggerMinigameOverBanner). Fires for every mini-game, no payload of its own -- just a beat
    // between the round actually ending and the rewards recap taking over, see
    // triggerMinigameRewardsBanner's own doc for how the two chain. ----
    private final TimedBanner<Void> minigameOverBanner = new TimedBanner<>();
    // ---- round-complete banner (server-driven, everyone sees it -- see MINIGAME_ENDED handling
    // and scheduleRoundCompleteBanner). Its payload is the *upcoming* round -- the one about to
    // start, same number getCurrentRound() would return live -- snapshotted at trigger time so it
    // stays stable through the banner's own display window regardless of whatever completedRounds
    // does afterward. ----
    private final TimedBanner<Integer> roundCompleteBanner = new TimedBanner<>(); // payload: upcoming round number
    // ---- mini-game rewards recap (server-driven, everyone sees it -- see MINIGAME_ENDED handling
    // and triggerMinigameRewardsBanner). Shown *before* the round-complete recap above, via
    // scheduleRoundCompleteBanner deferring that one behind this banner's own gate extension. ----
    private final TimedBanner<List<MinigameReward>> minigameRewardsBanner = new TimedBanner<>();

    // ---- Coin Rush (server-driven spawns/collections -- see COIN_RUSH_SPAWN/COIN_RUSH_COLLECTED
    // handling). coinRushSpawns is real state, applied catch-up or not: every currently-live
    // spawn's WorldPoint keyed by the server's own spawn id, mirrored into a 3D model per spawn by
    // TileOverlay#updateCoinRushModels the same "diff against the live set" pattern
    // updateCoinTrapModels already uses. coinRushScores is this round's own live tally (lowercase
    // rsn -> coins collected so far), reset fresh on every MINIGAME_STARTED for COIN_RUSH_KEY --
    // read by StatsOverlay's live scoreboard, which replaces the normal roster view for exactly as
    // long as a Coin Rush round is playable. coinRushRoundStartAt is the wall-clock moment the
    // round actually began (see COIN_RUSH_DURATION_MS/getCoinRushEndsAt), stamped from the
    // server's own MINIGAME_ROUND_BEGIN -- precise for a live client (this event's own arrival is
    // that moment), best-effort ("now") for a client that only caught up on an already-underway
    // round. ----
    private final Map<Integer, WorldPoint> coinRushSpawns = new ConcurrentHashMap<>();
    private final Map<String, Integer> coinRushScores = new ConcurrentHashMap<>();
    // Guards one spawn's own collect report against firing every tick while it's in flight -- same
    // role RunePartyPlugin's own arrivalSubmitted plays for confirmArrival, just one per spawn id
    // (via a Set) instead of a single flag, since more than one spawn can be live -- and walked
    // onto in quick succession -- at the same time. See checkCoinRushCollection, the only writer
    // besides the COIN_RUSH_COLLECTED handler (which clears an entry once the server's own echo
    // confirms it).
    private final Set<Integer> coinRushCollectSubmitted = ConcurrentHashMap.newKeySet();
    private volatile long coinRushRoundStartAt = 0;
    // ---- Fishing Contest (client-local catches, see RunePartyPlugin#onGameTick's own fishing
    // section -- this class only tracks the round's own start, exactly the same "wall-clock moment
    // the round actually began, stamped from MINIGAME_ROUND_BEGIN" shape coinRushRoundStartAt
    // already uses. Catch counts themselves are never reported to the server mid-round, so they
    // have no counterpart here -- only RunePartyPlugin's own local fields track those. ----
    private volatile long fishingRoundStartAt = 0;

    // ---- True or False (server-driven rounds -- see TRUE_OR_FALSE_ROUND_STARTED/ANSWERED/
    // ROUND_ENDED handling). All real state, applied catch-up or not: trueOrFalseQuestion/
    // RoundNumber are the current round's own question text and 1-indexed round number (null/0
    // once the round ends, until the next one starts or the mini-game itself ends).
    // trueOrFalseAnsweredRsns mirrors minigameReadyRsns's own "who's confirmed" role, just scoped
    // to the current round instead of the whole mini-game -- who's submitted an answer this round,
    // not what they answered (see renderTrueOrFalseQuestion, the "Ready screen"-style tally this
    // was asked for). trueOrFalseMyAnswer is the local player's own answer this round (null until
    // they've answered), read by isLocalPlayerAwaitingTrueOrFalseAnswer to stop a YES/NO emote
    // from resubmitting once they already have. trueOrFalseLastCorrectAnswer/Results are the most
    // recent TRUE_OR_FALSE_ROUND_ENDED's own reveal, held onto (not cleared) through the next
    // round's own trueOrFalseQuestion update, since renderTrueOrFalseReveal gates its own display
    // on trueOrFalseRevealUntil, not on whether a new question already exists. ----
    private volatile String trueOrFalseQuestion = null;
    private volatile int trueOrFalseRoundNumber = 0;
    private final Set<String> trueOrFalseAnsweredRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    private volatile Boolean trueOrFalseMyAnswer = null;
    private volatile long trueOrFalseRoundStartedAt = 0; // wall-clock moment this round's own TRUE_OR_FALSE_ROUND_STARTED landed -- see getTrueOrFalseRoundEndsAt
    private volatile Boolean trueOrFalseLastCorrectAnswer = null;
    private volatile List<TrueOrFalseResult> trueOrFalseLastResults = Collections.emptyList();
    private volatile long trueOrFalseRevealUntil = 0;

    MinigamePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.MINIGAME_STARTED:
                // minigameActive/minigameInstructions/minigameKey/minigameDisplayName take effect
                // immediately, catch-up or not -- a player joining mid-minigame needs the panel to
                // correctly show it's underway. Ready-check state resets fresh for this mini-game
                // instance too, real state regardless of catch-up. Only the celebratory banner and
                // the spinner that follows it are cosmetic-only and skipped during catch-up.
                minigameActive = true;
                minigameInstructions = Json.requiredStr(e.payload, type, "instructions");
                minigameKey = Json.requiredStr(e.payload, type, "key");
                minigameDisplayName = Json.requiredStr(e.payload, type, "displayName");
                minigameReadyRsns.clear();
                minigameCountdownStarted = false;
                minigameCountdownSkippedForClient = false;
                minigameCountdownBannerUntil = 0;
                minigameRoundBegun = false;
                minigameSpinnerStart = 0;
                minigameSpinnerUntil = 0;
                minigameSpinnerSkippedForClient = catchingUp;
                // Real state, applied catch-up or not: a fresh Coin Rush instance starts with no
                // spawns and no tally, regardless of whether this client watched the previous
                // round's own leftovers get cleaned up by its own MINIGAME_ENDED (see that case
                // below -- redundant here for a live client, but a catching-up client that missed
                // the previous round's MINIGAME_ENDED entirely still needs this reset).
                // coinRushRoundStartAt deliberately isn't set here -- MINIGAME_STARTED lands before
                // the round is actually playable (the ready-check still has to run first), so
                // stamping "now" at this point would undercount the round's remaining time; see the
                // MINIGAME_ROUND_BEGIN case below, the only writer.
                if (RunePartyPlugin.COIN_RUSH_KEY.equals(minigameKey))
                {
                    coinRushSpawns.clear();
                    coinRushScores.clear();
                    coinRushCollectSubmitted.clear();
                    coinRushRoundStartAt = 0;
                }
                // Same reasoning as Coin Rush's own reset above -- fishingRoundStartAt deliberately
                // isn't set here either, for the identical reason (the round isn't playable yet,
                // see MINIGAME_ROUND_BEGIN below, the only writer).
                if (RunePartyPlugin.FISHING_CONTEST_KEY.equals(minigameKey))
                {
                    fishingRoundStartAt = 0;
                }
                // Same reasoning as Coin Rush's own reset just above -- a fresh True or False
                // instance starts with no question/answers/reveal, regardless of catch-up.
                if (RunePartyPlugin.TRUE_OR_FALSE_KEY.equals(minigameKey))
                {
                    trueOrFalseQuestion = null;
                    trueOrFalseRoundNumber = 0;
                    trueOrFalseAnsweredRsns.clear();
                    trueOrFalseMyAnswer = null;
                    trueOrFalseRoundStartedAt = 0;
                    trueOrFalseLastCorrectAnswer = null;
                    trueOrFalseLastResults = Collections.emptyList();
                    trueOrFalseRevealUntil = 0;
                }
                if (!catchingUp)
                {
                    scheduleMinigameBanner();
                    scheduleMinigameSpinner();
                    plugin.addChatMessage("Mini-game! " + minigameInstructions);
                }
                break;

            case Events.MINIGAME_PLAYER_READY:
            {
                // Real state, applied catch-up or not -- see isLocalPlayerAwaitingMinigameReady/
                // isMinigamePlayable, which both need an accurate ready set regardless of whether
                // this client watched it happen live.
                String readyRsn = Json.requiredStr(e.payload, type, "player");
                if (readyRsn != null) minigameReadyRsns.add(readyRsn.toLowerCase(Locale.ROOT));
                break;
            }

            case Events.MINIGAME_COUNTDOWN_STARTED:
                // Real state, applied catch-up or not -- see isMinigamePlayable, the reason this
                // is split from the cosmetic-only banner timestamp below.
                minigameCountdownStarted = true;
                minigameCountdownSkippedForClient = catchingUp;
                if (!catchingUp)
                {
                    // Arming minigameCountdownBannerUntil is itself delayed by
                    // MINIGAME_COUNTDOWN_START_DELAY_MS -- see renderMinigameReadyCheck, which
                    // keeps showing every player as "Ready!" until this actually fires, instead of
                    // the screen changing the instant the last person emotes.
                    plugin.uiTimerExec.schedule(() ->
                    {
                        minigameCountdownBannerUntil = System.currentTimeMillis() + RunePartyPlugin.MINIGAME_COUNTDOWN_DURATION_MS;
                        plugin.extendTurnEffectGate(minigameCountdownBannerUntil);
                        // RunePartyPanel only re-checks isMinigamePlayable() reactively, when
                        // refreshPanel() runs -- unlike AnnouncementOverlay's per-frame render(),
                        // the panel has no ordinary reason to refresh again once the countdown
                        // naturally finishes a few seconds from now (no further server event marks
                        // that moment). Without this, the panel's play controls silently never
                        // appeared until some unrelated event happened to trigger a refresh
                        // afterward. Chained here (nested inside this same callback) rather than
                        // scheduled as its own independent MINIGAME_COUNTDOWN_START_DELAY_MS +
                        // MINIGAME_COUNTDOWN_DURATION_MS delay off the original event -- two
                        // separately-scheduled tasks race against each other under normal
                        // scheduler jitter, and if this one's own delay ran even a couple of
                        // milliseconds long, the independently-scheduled refresh could fire before
                        // minigameCountdownBannerUntil above was actually reached, leaving
                        // isMinigamePlayable() still false at the one moment anything checked it.
                        // Nesting the schedule call here instead means its delay is always measured
                        // from this exact point, so it's guaranteed to fire strictly after
                        // minigameCountdownBannerUntil regardless of how long this task itself took
                        // to actually run.
                        plugin.uiTimerExec.schedule(plugin::refreshPanel, RunePartyPlugin.MINIGAME_COUNTDOWN_DURATION_MS, TimeUnit.MILLISECONDS);
                    }, RunePartyPlugin.MINIGAME_COUNTDOWN_START_DELAY_MS, TimeUnit.MILLISECONDS);
                }
                break;

            case Events.MINIGAME_ROUND_BEGIN:
                // Real state, applied catch-up or not: the server's own signal that this round's
                // real content actually started (see events.minigame_round_begin's own doc,
                // ARCHITECTURE_REVIEW.md's X2(b)) -- Coin Rush's own round clock (see
                // COIN_RUSH_DURATION_MS/getCoinRushEndsAt) starts ticking from here rather than a
                // locally-guessed offset off MINIGAME_COUNTDOWN_STARTED. "now" is exact for a live
                // client (this event's own arrival *is* the moment) and best-effort for a
                // catching-up client, same as every other real-state field here. True or False
                // doesn't need this -- trueOrFalseRoundStartedAt already anchors off its own
                // TRUE_OR_FALSE_ROUND_STARTED -- so this only acts on Coin Rush (and now Fishing
                // Contest, same reasoning).
                if (RunePartyPlugin.COIN_RUSH_KEY.equals(minigameKey))
                {
                    coinRushRoundStartAt = System.currentTimeMillis();
                }
                if (RunePartyPlugin.FISHING_CONTEST_KEY.equals(minigameKey))
                {
                    fishingRoundStartAt = System.currentTimeMillis();
                }
                // Unconditional, unlike the Coin-Rush-specific stamp above -- every mini-game fires
                // this event (see events.minigame_round_begin's own doc), so this flips true
                // regardless of which one is active. See its own field doc for why this exists
                // separately from the generic countdown's fixed timer.
                minigameRoundBegun = true;
                break;

            case Events.COIN_RUSH_SPAWN:
            {
                // Real state, applied catch-up or not: a coin genuinely exists on the board at
                // this point the instant the server says so, regardless of whether this client
                // watched it appear live -- see TileOverlay#updateCoinRushModels, which just
                // mirrors coinRushSpawns's own current keys every frame.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                WorldPoint point = Json.safeWorldPoint(e.payload, "point");
                if (spawnId != null && point != null) coinRushSpawns.put(spawnId, point);
                break;
            }

            case Events.COIN_RUSH_COLLECTED:
            {
                // Real state, applied catch-up or not: the spawn is gone (whoever the server
                // credited already claimed it) and the tally reflects it, regardless of whether
                // this client watched the race happen live -- a catching-up client still needs an
                // accurate live scoreboard the instant it starts rendering one. This event never
                // carries a real coin-total change of its own -- the server doesn't actually credit
                // a Coin Rush pickup to the player's balance until the round ends, one lump sum per
                // player (see COINS_CHANGED's own "coin_rush" case) -- so the only thing worth
                // showing live, right now, is a purely cosmetic "+2" flash (see enqueueCoinPopup's
                // totalless=true), never a running total.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                if (spawnId != null)
                {
                    coinRushSpawns.remove(spawnId);
                    coinRushCollectSubmitted.remove(spawnId);
                }
                String collector = Json.requiredStr(e.payload, type, "player");
                if (collector != null)
                {
                    coinRushScores.merge(collector.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
                if (!catchingUp && collector != null)
                {
                    plugin.enqueueCoinPopup(collector, RunePartyPlugin.COIN_RUSH_REWARD, 0, RunePartyPlugin.COIN_RUSH_BUMP_POPUP_DURATION_MS, true);
                    plugin.addChatMessage(collector + " grabbed a coin!");
                }
                break;
            }

            case Events.TRUE_OR_FALSE_ROUND_STARTED:
            {
                // Real state, applied catch-up or not: a fresh round genuinely started at this
                // point regardless of whether this client watched it happen live -- a catching-up
                // client still needs to know the current question/round number the instant it
                // starts rendering anything. Never carries the correct answer (see the server's own
                // TRUE_OR_FALSE_ROUND_STARTED payload -- only questionIndex, resolved into text by
                // the server itself before broadcast), so there's nothing here for a client to read
                // early. Per-round-only state (who's answered, my own answer) resets fresh; the
                // *previous* round's reveal (trueOrFalseLastCorrectAnswer/Results) is deliberately
                // left alone here, same "let the timer/next reset clear it" idiom coinRushScores
                // already uses -- renderTrueOrFalseReveal gates its own display on
                // trueOrFalseRevealUntil, not on whether a new question already exists.
                trueOrFalseQuestion = Json.requiredStr(e.payload, type, "question");
                Integer roundNumber = Json.requiredInt(e.payload, type, "roundNumber");
                if (roundNumber != null) trueOrFalseRoundNumber = roundNumber;
                trueOrFalseAnsweredRsns.clear();
                trueOrFalseMyAnswer = null;
                // "Now" regardless of catch-up -- a live client gets the genuinely-accurate instant
                // since this event only ever arrives right as the round actually starts either way;
                // a catching-up client gets the same best-effort approximation coinRushRoundStartAt's
                // own catch-up branch uses.
                trueOrFalseRoundStartedAt = System.currentTimeMillis();
                break;
            }

            case Events.TRUE_OR_FALSE_ANSWERED:
            {
                // Real state, applied catch-up or not -- see isLocalPlayerAwaitingTrueOrFalseAnswer/
                // renderTrueOrFalseQuestion, both of which need an accurate answered-set regardless
                // of whether this client watched each answer land live.
                String answeredRsn = Json.requiredStr(e.payload, type, "player");
                if (answeredRsn != null)
                {
                    trueOrFalseAnsweredRsns.add(answeredRsn.toLowerCase(Locale.ROOT));
                    String self = plugin.localRsn();
                    if (self != null && self.equalsIgnoreCase(answeredRsn))
                    {
                        JsonElement answerEl = e.payload.get("answer");
                        if (answerEl != null && !answerEl.isJsonNull()) trueOrFalseMyAnswer = answerEl.getAsBoolean();
                    }
                }
                break;
            }

            case Events.TRUE_OR_FALSE_ROUND_ENDED:
            {
                // Real state, applied catch-up or not: the correct answer is now public regardless
                // of whether this client watched the round happen live.
                JsonElement correctEl = e.payload.get("correctAnswer");
                trueOrFalseLastCorrectAnswer = correctEl != null && !correctEl.isJsonNull() ? correctEl.getAsBoolean() : null;
                trueOrFalseLastResults = Json.safeTrueOrFalseResults(e.payload);
                if (!catchingUp)
                {
                    trueOrFalseRevealUntil = System.currentTimeMillis() + RunePartyPlugin.TRUE_OR_FALSE_REVEAL_DURATION_MS;
                    plugin.extendTurnEffectGate(trueOrFalseRevealUntil);
                }
                break;
            }

            default:
                break;
        }
    }

    /** The non-completedRounds half of MINIGAME_ENDED -- RunePartyPlugin's hybrid case increments
     * completedRounds itself (core whole-game progress, read by getCurrentRound) before calling
     * this for the rest: clearing every mini-game/Coin-Rush/True-or-False field, the rewards recap,
     * and (skipped on the game's final round, since triggerGameOverSequence reveals the same
     * standings itself right after) the round-complete recap. {@code maxRounds}/
     * {@code completedRoundsAfterIncrement} are passed in rather than read off the plugin directly
     * since they're core fields this presenter doesn't otherwise touch. */
    void handleMinigameEnded(JsonObject payload, boolean catchingUp, int maxRounds, int completedRoundsAfterIncrement)
    {
        minigameActive = false;
        minigameInstructions = null;
        minigameKey = null;
        minigameDisplayName = null;
        minigameReadyRsns.clear();
        minigameCountdownStarted = false;
        minigameCountdownSkippedForClient = false;
        minigameCountdownBannerUntil = 0;
        // Unconditional (harmless no-op if this round wasn't Coin Rush) rather than gated on
        // COIN_RUSH_KEY.equals(minigameKey) -- minigameKey is already cleared above by this point.
        // Any coin still standing when the round ends shouldn't keep rendering (see TileOverlay#
        // updateCoinRushModels, which just mirrors this map's own keys) -- real state, applied
        // catch-up or not. The scoreboard tally itself (coinRushScores) is deliberately left as-is
        // rather than cleared here: StatsOverlay's own gate (isCoinRushActive() &&
        // isMinigamePlayable(), both now false) already stops rendering it, and the next
        // MINIGAME_STARTED resets it fresh regardless.
        coinRushSpawns.clear();
        coinRushCollectSubmitted.clear();
        // Same reasoning as the Coin Rush cleanup just above -- no question/reveal should keep
        // rendering once the mini-game itself has ended.
        trueOrFalseQuestion = null;
        trueOrFalseRoundNumber = 0;
        trueOrFalseAnsweredRsns.clear();
        trueOrFalseMyAnswer = null;
        if (!catchingUp)
        {
            plugin.addChatMessage("Mini-game complete!");
            triggerMinigameOverBanner();
            triggerMinigameRewardsBanner(payload);
            // Skipped on the game's last round -- GAME_ENDED fires right behind this same
            // MINIGAME_ENDED (see app.py's _resolve_minigame_if_complete, which checks maxRounds
            // immediately after inserting this event) and triggerGameOverSequence reveals the very
            // same standings itself, dramatically, one place at a time. Showing the plain "Current
            // Standings" recap first would spoil that reveal.
            if (maxRounds <= 0 || completedRoundsAfterIncrement < maxRounds)
            {
                scheduleRoundCompleteBanner();
            }
        }
    }

    /** Checks the local player's current position against every currently-live Coin Rush spawn
     * (see coinRushSpawns) and reports a claim the instant it matches one -- called every tick
     * while a Coin Rush round is playable (see RunePartyPlugin#onGameTick). coinRushCollectSubmitted
     * guards each spawn id against being reported more than once while its first report is still in
     * flight: the server's own COIN_RUSH_COLLECTED echo is what actually removes the spawn from
     * coinRushSpawns (and clears the guard), so standing on a still-live spawn tile for several
     * ticks in a row before the echo lands doesn't fire a fresh request every single tick. */
    void checkCoinRushCollection(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        if (pos == null) return;

        for (Map.Entry<Integer, WorldPoint> entry : coinRushSpawns.entrySet())
        {
            if (!entry.getValue().equals(pos)) continue;
            int spawnId = entry.getKey();
            if (!coinRushCollectSubmitted.add(spawnId)) continue; // already reported, awaiting the echo
            collectCoinRushCoin(spawnId, pos);
        }
    }

    /** Reports the local player reaching a still-live Coin Rush spawn tile. Same
     * report-then-wait-for-the-echo shape as RunePartyPlugin#confirmArrival: the server -- not this
     * call's caller -- decides who actually wins a spawn racing multiple simultaneous reports (see
     * COIN_RUSH_COLLECTED, the only source of truth for who got the coins), so this never assumes
     * success locally. A failed request (network blip, not a "someone else already got it" 409)
     * clears the guard so checkCoinRushCollection retries it on a later tick. */
    private void collectCoinRushCoin(int spawnId, WorldPoint pos)
    {
        String self = plugin.localRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) { coinRushCollectSubmitted.remove(spawnId); return; }

        plugin.submitAction("Collect Coin Rush coin", () -> plugin.apiClient.collectCoinRushCoin(gid, self, token, spawnId, pos.getX(), pos.getY(), pos.getPlane()),
            e -> coinRushCollectSubmitted.remove(spawnId));
    }

    /** Schedules AnnouncementOverlay's "MINIGAME!" banner via scheduleAfterTurnEffects, so it never
     * appears while the last roller's own turn -- including their coin popup -- is still settling.
     * minigameActive/minigameInstructions are set immediately in the MINIGAME_STARTED handler,
     * unaffected by this delay: this only postpones the celebratory banner, not the mini-game
     * itself. scheduleAfterTurnEffects reserves the gate for this banner's own
     * MINIGAME_BANNER_DURATION_MS synchronously, so scheduleMinigameSpinner (called right behind
     * this one, same MINIGAME_STARTED handler) starts right on schedule once that reservation
     * ends -- but the banner itself keeps rendering well past that (see the callback below), so
     * "MINIGAME!" stays up above the wheel for its own whole spin+reveal instead of disappearing
     * the instant the wheel takes over. Not an armBanner call (see that method's own doc) -- its
     * `until` is deliberately longer than what it reserves on the gate, which armBanner's uniform
     * "until == gate reservation" shape can't express. */
    private void scheduleMinigameBanner()
    {
        minigameBanner.task = plugin.scheduleAfterTurnEffects(minigameBanner.task, RunePartyPlugin.MINIGAME_BANNER_DURATION_MS, () ->
        {
            long now = System.currentTimeMillis();
            // Rendered for MINIGAME_BANNER_DURATION_MS + MINIGAME_SPINNER_DURATION_MS -- longer
            // than what's reserved on the gate below -- so "MINIGAME!" stays visible above the
            // selection wheel for the wheel's own entire spin+reveal (scheduleMinigameSpinner,
            // called right behind this one in the same MINIGAME_STARTED handler) instead of
            // vanishing the instant the wheel appears. The gate reservation deliberately stays at
            // the shorter MINIGAME_BANNER_DURATION_MS (belt-and-suspenders against scheduler
            // jitter, same as ever) -- extending it to match would also push back the wheel's own
            // start, which isn't the goal here.
            minigameBanner.until = now + RunePartyPlugin.MINIGAME_BANNER_DURATION_MS + RunePartyPlugin.MINIGAME_SPINNER_DURATION_MS;
            plugin.extendTurnEffectGate(now + RunePartyPlugin.MINIGAME_BANNER_DURATION_MS);
        });
    }

    /** Schedules AnnouncementOverlay's mini-game selection spinner via scheduleAfterTurnEffects,
     * so it waits behind the "MINIGAME!" banner (scheduleMinigameBanner, called right before this
     * in the MINIGAME_STARTED handler) instead of both appearing at once. The gate is reserved for
     * the spin + settle-hold synchronously (see scheduleAfterTurnEffects), so the ready-check
     * screen -- which has no timed trigger of its own, see
     * AnnouncementOverlay#renderMinigameReadyCheck -- only starts reading as "the current screen"
     * once this finishes. Not an armBanner call (see that method's own doc) -- minigameSpinnerStart/
     * Until are still raw fields, never migrated to a TimedBanner, out of scope for this pass. */
    private void scheduleMinigameSpinner()
    {
        minigameSpinnerTask = plugin.scheduleAfterTurnEffects(minigameSpinnerTask, RunePartyPlugin.MINIGAME_SPINNER_DURATION_MS, () ->
        {
            minigameSpinnerStart = System.currentTimeMillis();
            minigameSpinnerUntil = minigameSpinnerStart + RunePartyPlugin.MINIGAME_SPINNER_DURATION_MS;
            plugin.extendTurnEffectGate(minigameSpinnerUntil); // belt-and-suspenders, see scheduleMinigameBanner's identical comment
        });
    }

    /** Arms AnnouncementOverlay's "MINIGAME OVER!" banner -- fired first on every MINIGAME_ENDED,
     * for every mini-game alike, before the rewards recap even starts (see
     * triggerMinigameRewardsBanner, armed right behind this one in handleMinigameEnded and chained
     * behind it via the shared turnEffectGateUntil armBanner reserves here). No payload of its own
     * -- just a beat between the round genuinely ending and the recap taking over. */
    private void triggerMinigameOverBanner()
    {
        plugin.armBanner(minigameOverBanner, RunePartyPlugin.MINIGAME_OVER_BANNER_DURATION_MS, () -> null, true);
    }

    /** Arms AnnouncementOverlay's mini-game rewards recap ("who got what") -- called from
     * handleMinigameEnded, parsing its own "payouts" list eagerly (payload is a fixed, already-
     * landed MINIGAME_ENDED event -- it never changes, so there's nothing to gain from re-parsing
     * it lazily inside armBanner's own callback) rather than having AnnouncementOverlay re-parse
     * the raw event payload every frame. armBanner chains this behind whatever's already reserved
     * turnEffectGateUntil -- the "MINIGAME OVER!" banner above, armed immediately before this call
     * in handleMinigameEnded -- and extends it further so both the round-complete recap (see
     * scheduleRoundCompleteBanner) and the new round's first TURN_STARTED banner wait behind this
     * one too. */
    private void triggerMinigameRewardsBanner(JsonObject payload)
    {
        List<MinigameReward> rewards = Json.safeMinigameRewards(payload, "payouts");
        plugin.armBanner(minigameRewardsBanner, RunePartyPlugin.MINIGAME_REWARDS_BANNER_DURATION_MS, () -> rewards, true);
    }

    /** Schedules AnnouncementOverlay's post-round "ROUND x" / "Current Standings" recap via
     * scheduleAfterTurnEffects, so it waits behind the mini-game rewards recap
     * (triggerMinigameRewardsBanner) that always fires first on the same MINIGAME_ENDED event,
     * instead of both appearing at once. Snapshots getCurrentRound() -- the round about to start,
     * not the one that just finished (completedRounds is already incremented by the time this
     * runs, so getCurrentRound() here is the same "upcoming round" number the next TURN_STARTED's
     * own banner and StatsOverlay's live "ROUND x/y" line would show). Not called at all for the
     * game's final round -- see handleMinigameEnded -- since triggerGameOverSequence reveals those
     * same standings itself right after, and this plain recap would spoil that. */
    private void scheduleRoundCompleteBanner()
    {
        plugin.armBanner(roundCompleteBanner, RunePartyPlugin.ROUND_COMPLETE_BANNER_DURATION_MS, plugin::getCurrentRound, true);
    }

    void reset()
    {
        minigameBanner.reset();
        minigameOverBanner.reset();
        if (minigameSpinnerTask != null) { minigameSpinnerTask.cancel(false); minigameSpinnerTask = null; }
        roundCompleteBanner.reset();
        minigameRewardsBanner.reset();
        awaitingMinigameReadyFinish = false;
        awaitingTrueOrFalseYesFinish = false;
        awaitingTrueOrFalseNoFinish = false;
        minigameActive = false;
        minigameInstructions = null;
        minigameKey = null;
        minigameDisplayName = null;
        minigameSpinnerStart = 0;
        minigameSpinnerUntil = 0;
        minigameSpinnerSkippedForClient = false;
        minigameReadyRsns.clear();
        minigameCountdownStarted = false;
        minigameCountdownSkippedForClient = false;
        minigameCountdownBannerUntil = 0;
        coinRushSpawns.clear();
        coinRushScores.clear();
        coinRushCollectSubmitted.clear();
        coinRushRoundStartAt = 0;
        fishingRoundStartAt = 0;
        trueOrFalseQuestion = null;
        trueOrFalseRoundNumber = 0;
        trueOrFalseAnsweredRsns.clear();
        trueOrFalseMyAnswer = null;
        trueOrFalseRoundStartedAt = 0;
        trueOrFalseLastCorrectAnswer = null;
        trueOrFalseLastResults = Collections.emptyList();
        trueOrFalseRevealUntil = 0;
    }

    // ---- awaiting-emote flags, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    void armAwaitingMinigameReadyFinish() { awaitingMinigameReadyFinish = true; }
    void armAwaitingTrueOrFalseYesFinish() { awaitingTrueOrFalseYesFinish = true; }
    void armAwaitingTrueOrFalseNoFinish() { awaitingTrueOrFalseNoFinish = true; }
    boolean isAwaitingMinigameReadyFinish() { return awaitingMinigameReadyFinish; }
    boolean isAwaitingTrueOrFalseYesFinish() { return awaitingTrueOrFalseYesFinish; }
    boolean isAwaitingTrueOrFalseNoFinish() { return awaitingTrueOrFalseNoFinish; }
    void clearAwaitingMinigameReadyFinish() { awaitingMinigameReadyFinish = false; }
    void clearAwaitingTrueOrFalseYesFinish() { awaitingTrueOrFalseYesFinish = false; }
    void clearAwaitingTrueOrFalseNoFinish() { awaitingTrueOrFalseNoFinish = false; }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under their original names ----
    boolean isActive() { return minigameActive; }
    String getInstructions() { return minigameInstructions; }
    String getKey() { return minigameKey; }
    String getDisplayName() { return minigameDisplayName; }
    long getMinigameSpinnerStart() { return minigameSpinnerStart; }
    long getMinigameSpinnerUntil() { return minigameSpinnerUntil; }
    boolean isMinigameSpinnerSkippedForClient() { return minigameSpinnerSkippedForClient; }
    Set<String> getMinigameReadyRsns() { return minigameReadyRsns; }
    boolean isCountdownStarted() { return minigameCountdownStarted; }
    boolean isCountdownSkippedForClient() { return minigameCountdownSkippedForClient; }
    long getCountdownBannerUntil() { return minigameCountdownBannerUntil; }
    boolean isRoundBegun() { return minigameRoundBegun; }
    long getMinigameBannerUntil() { return minigameBanner.until; }
    long getMinigameOverBannerUntil() { return minigameOverBanner.until; }
    long getRoundCompleteBannerUntil() { return roundCompleteBanner.until; }
    int getRoundCompleteRoundNumber() { return roundCompleteBanner.payload != null ? roundCompleteBanner.payload : 0; }
    long getMinigameRewardsBannerUntil() { return minigameRewardsBanner.until; }
    List<MinigameReward> getMinigameRewards() { return minigameRewardsBanner.payload != null ? minigameRewardsBanner.payload : Collections.emptyList(); }

    Map<Integer, WorldPoint> getCoinRushSpawns() { return coinRushSpawns; }
    Map<String, Integer> getCoinRushScores() { return coinRushScores; }
    boolean isCoinRushActive() { return minigameActive && RunePartyPlugin.COIN_RUSH_KEY.equals(minigameKey); }
    /** When the current Coin Rush round's own clock (see COIN_RUSH_DURATION_MS) runs out -- 0 if
     * no round is active yet or the round hasn't actually become playable (see
     * coinRushRoundStartAt's own doc on when that gets stamped). */
    long getCoinRushEndsAt() { return coinRushRoundStartAt != 0 ? coinRushRoundStartAt + RunePartyPlugin.COIN_RUSH_DURATION_MS : 0; }

    boolean isFishingContestActive() { return minigameActive && RunePartyPlugin.FISHING_CONTEST_KEY.equals(minigameKey); }
    /** When the current Fishing Contest round's own local catch-timer should stop -- 0 if no round
     * is active yet or the round hasn't actually become playable (see fishingRoundStartAt's own
     * doc on when that gets stamped). RunePartyPlugin#onGameTick's own fishing section compares
     * against this to decide when to submit the local player's final tally, same "stamped instant
     * + fixed duration" shape getCoinRushEndsAt already uses. */
    long getFishingContestEndsAt() { return fishingRoundStartAt != 0 ? fishingRoundStartAt + RunePartyPlugin.FISHING_CONTEST_DURATION_MS : 0; }

    String getTrueOrFalseQuestion() { return trueOrFalseQuestion; }
    int getTrueOrFalseRoundNumber() { return trueOrFalseRoundNumber; }
    Set<String> getTrueOrFalseAnsweredRsns() { return trueOrFalseAnsweredRsns; }
    Boolean getTrueOrFalseMyAnswer() { return trueOrFalseMyAnswer; }
    /** When the current True or False round's reading period ends and its answer countdown starts
     * ticking (see TRUE_OR_FALSE_READING_DURATION_MS) -- 0 if no round is currently open, same
     * gating as getTrueOrFalseRoundEndsAt. renderTrueOrFalseQuestion hides the countdown number
     * until this passes. */
    long getTrueOrFalseAnswerWindowStartsAt() { return trueOrFalseQuestion != null && trueOrFalseRoundStartedAt != 0 ? trueOrFalseRoundStartedAt + RunePartyPlugin.TRUE_OR_FALSE_READING_DURATION_MS : 0; }
    /** When the current True or False round's own clock (see TRUE_OR_FALSE_READING_DURATION_MS +
     * TRUE_OR_FALSE_ROUND_DURATION_MS) runs out -- 0 if no round is currently open (see
     * trueOrFalseRoundStartedAt's own doc on when that gets stamped, and trueOrFalseQuestion,
     * cleared the instant the round ends). */
    long getTrueOrFalseRoundEndsAt() { return trueOrFalseQuestion != null && trueOrFalseRoundStartedAt != 0 ? trueOrFalseRoundStartedAt + RunePartyPlugin.TRUE_OR_FALSE_READING_DURATION_MS + RunePartyPlugin.TRUE_OR_FALSE_ROUND_DURATION_MS : 0; }
    Boolean getTrueOrFalseLastCorrectAnswer() { return trueOrFalseLastCorrectAnswer; }
    List<TrueOrFalseResult> getTrueOrFalseLastResults() { return trueOrFalseLastResults; }
    long getTrueOrFalseRevealUntil() { return trueOrFalseRevealUntil; }
}
