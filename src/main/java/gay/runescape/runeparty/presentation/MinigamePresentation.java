package gay.runescape.runeparty.presentation;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.net.MinigameReward;
import gay.runescape.runeparty.net.MinigameScore;

import com.google.gson.JsonObject;
import gay.runescape.runeparty.minigames.ClickClickClickPresentation;
import gay.runescape.runeparty.minigames.CoinRushPresentation;
import gay.runescape.runeparty.minigames.FishingContestPresentation;
import gay.runescape.runeparty.minigames.HotPotatoPresentation;
import gay.runescape.runeparty.minigames.MinigamePresentationFeature;
import gay.runescape.runeparty.minigames.SandwichRushPresentation;
import gay.runescape.runeparty.minigames.TrueOrFalsePresentation;
import gay.runescape.runeparty.minigames.TurfWarsPresentation;
import gay.runescape.runeparty.minigames.WhosYourJaddyPresentation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Mini-game (selection/ready-check/countdown) state and event handling, extracted out of
 * RunePartyPlugin. Owns the generic lifecycle every mini-game shares; each mini-game's own state
 * and event-folding lives in its own {@link MinigamePresentationFeature} implementation instead
 * (see the {@code minigames} package) -- this class holds one instance of each, both under a typed
 * accessor and in {@link #features}, keyed by that mini-game's own {@code RunePartyPlugin.*_KEY},
 * for generic dispatch. Exposes handleMinigameEnded() for RunePartyPlugin's hybrid MINIGAME_ENDED
 * case to call (completedRounds/maxRounds themselves stay core -- they represent whole-game
 * progress, not one mini-game instance's own state). Clears itself via reset(); RunePartyPlugin
 * still exposes every getter under its original name, just delegating here or to a feature. */
public final class MinigamePresentation
{
    private final RunePartyPlugin plugin;

    private volatile ScheduledFuture<?> minigameSpinnerTask;
    private volatile boolean minigameActive = false;
    private volatile String minigameInstructions = null;
    // Which minigames/ registry entry is active -- matches the server's own key, and is what
    // RunePartyPanel/Minigames#get use to look up this key's own control-panel UI.
    private volatile String minigameKey = null;
    // Announced by AnnouncementOverlay's selection spinner once it settles.
    private volatile String minigameDisplayName = null;
    // ---- mini-game selection spinner (cosmetic-only timing, chained behind the "MINIGAME!"
    // banner -- see scheduleMinigameSpinner) ----
    private volatile long minigameSpinnerStart = 0;
    private volatile long minigameSpinnerUntil = 0;
    // Real state: true iff this MINIGAME_STARTED was applied during catch-up, meaning the spinner
    // never plays for this client this round -- so minigameSpinnerUntil staying 0 means "already
    // resolved, skip straight to the ready-check" rather than "hasn't started yet." Set
    // unconditionally in the MINIGAME_STARTED handler, exactly like minigameCountdownStarted's
    // split for the same reason.
    private volatile boolean minigameSpinnerSkippedForClient = false;
    // ---- mini-game ready-check (server-driven, everyone sees it). minigameReadyRsns is real
    // state (who's actually YES-emoted so far), applied unconditionally catch-up or not.
    // minigameCountdownStarted is likewise real state; only minigameCountdownBannerUntil (the
    // visual "3...2...1... BEGIN!") is cosmetic-only, deliberately armed
    // MINIGAME_COUNTDOWN_START_DELAY_MS after minigameCountdownStarted flips, so the ready-check
    // screen gets to actually show everyone marked "Ready!" for a beat first. That's what
    // minigameCountdownSkippedForClient is for, same idiom as minigameSpinnerSkippedForClient
    // above. ----
    private final Set<String> minigameReadyRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    private volatile boolean minigameCountdownStarted = false;
    private volatile boolean minigameCountdownSkippedForClient = false;
    private volatile long minigameCountdownBannerUntil = 0;
    // Real state, applied catch-up or not: true once MINIGAME_ROUND_BEGIN has genuinely landed for
    // the current mini-game -- unlike minigameCountdownBannerUntil (a fixed local timer that always
    // resolves MINIGAME_COUNTDOWN_DURATION_MS after arming), this reflects the real server-side
    // moment. The Arena mini-game's own round-begin timing depends on when everyone's actually
    // walked onto its grid, which isn't tied to the generic countdown's fixed schedule at all --
    // see AnnouncementOverlay#renderArenaGatherMessage, the reader this exists for.
    private volatile boolean minigameRoundBegun = false;
    // Same idea as JadPresentation's own awaitingBowFinish, one per response to a pending
    // mini-game ready-check -- see RunePartyPlugin#onAnimationChanged, which consults this via the
    // arm/isAwaiting/clear methods below. True-or-False's/Hot Potato's own equivalents live on
    // their own feature classes now.
    private volatile boolean awaitingMinigameReadyFinish = false;
    // ---- minigame banner (server-driven, everyone sees it -- see MINIGAME_STARTED handling) ----
    private final TimedBanner<Void> minigameBanner = new TimedBanner<>();
    // ---- minigame-over banner (server-driven, everyone sees it). Fires for every mini-game, no
    // payload of its own -- just a beat between the round actually ending and the rewards recap
    // taking over, see triggerMinigameRewardsBanner for how the two chain. ----
    private final TimedBanner<Void> minigameOverBanner = new TimedBanner<>();
    // ---- mini-game final-score recap (server-driven, everyone sees it). Shown after
    // minigameOverBanner above and before minigameRewardsBanner below -- "how did everyone do"
    // comes before "what did that earn you", see triggerMinigameScoreBanner for how the three
    // chain. Only shown for a mini-game whose own feature's showsFinalScore() is true -- see that
    // method's own doc for which ones qualify and why. ----
    private final TimedBanner<List<MinigameScore>> minigameScoreBanner = new TimedBanner<>();
    // ---- round-complete banner (server-driven, everyone sees it). Its payload is the upcoming
    // round -- the one about to start, same number getCurrentRound() would return live --
    // snapshotted at trigger time so it stays stable through the banner's own display window. ----
    private final TimedBanner<Integer> roundCompleteBanner = new TimedBanner<>(); // payload: upcoming round number
    // ---- mini-game rewards recap (server-driven, everyone sees it). Shown before the
    // round-complete recap above, via scheduleRoundCompleteBanner deferring that one behind this
    // banner's own gate extension. ----
    private final TimedBanner<List<MinigameReward>> minigameRewardsBanner = new TimedBanner<>();

    private final CoinRushPresentation coinRush;
    private final SandwichRushPresentation sandwichRush;
    private final FishingContestPresentation fishingContest;
    private final ClickClickClickPresentation clickClickClick;
    private final TurfWarsPresentation turfWars;
    private final WhosYourJaddyPresentation jaddy;
    private final TrueOrFalsePresentation trueOrFalse;
    private final HotPotatoPresentation hotPotato;
    // Every feature above, keyed by its own RunePartyPlugin.*_KEY, for generic dispatch (apply's
    // default branch, onStarted/onRoundBegin/onEnded/showsFinalScore/reset) -- Arena has no entry
    // at all, since it has no client-tracked state of its own beyond this class's own generic
    // lifecycle (its own gameplay is entirely tile-color-driven).
    private final Map<String, MinigamePresentationFeature> features = new LinkedHashMap<>();

    public MinigamePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.coinRush = new CoinRushPresentation(plugin);
        this.sandwichRush = new SandwichRushPresentation(plugin);
        this.fishingContest = new FishingContestPresentation();
        this.clickClickClick = new ClickClickClickPresentation();
        this.turfWars = new TurfWarsPresentation(plugin);
        this.jaddy = new WhosYourJaddyPresentation(plugin);
        this.trueOrFalse = new TrueOrFalsePresentation(plugin);
        this.hotPotato = new HotPotatoPresentation(plugin);

        features.put(RunePartyPlugin.COIN_RUSH_KEY, coinRush);
        features.put(RunePartyPlugin.SANDWICH_RUSH_KEY, sandwichRush);
        features.put(RunePartyPlugin.FISHING_CONTEST_KEY, fishingContest);
        features.put(RunePartyPlugin.CLICK_CLICK_CLICK_KEY, clickClickClick);
        features.put(RunePartyPlugin.TURF_WARS_KEY, turfWars);
        features.put(RunePartyPlugin.JADDY_KEY, jaddy);
        features.put(RunePartyPlugin.TRUE_OR_FALSE_KEY, trueOrFalse);
        features.put(RunePartyPlugin.HOT_POTATO_KEY, hotPotato);
    }

    public void apply(ApiClient.EventOut e, boolean catchingUp)
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
                // Real state, applied catch-up or not: hands off to whichever feature owns this
                // key to reset its own per-round state fresh -- see MinigamePresentationFeature#
                // onStarted's own doc.
                MinigamePresentationFeature startedFeature = features.get(minigameKey);
                if (startedFeature != null) startedFeature.onStarted(catchingUp);
                if (!catchingUp)
                {
                    scheduleMinigameBanner();
                    scheduleMinigameSpinner();
                    plugin.addChatMessage("Mini-game! " + minigameInstructions);
                }
                break;

            case Events.MINIGAME_PLAYER_READY:
            {
                // Real state, applied catch-up or not -- isLocalPlayerAwaitingMinigameReady/
                // isMinigamePlayable both need an accurate ready set.
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
                        // refreshPanel() runs, so without this the panel's play controls would
                        // never appear until some unrelated event triggered a refresh. Nested here
                        // rather than scheduled as its own independent delay off the original
                        // event, so it's guaranteed to fire strictly after
                        // minigameCountdownBannerUntil regardless of scheduler jitter.
                        plugin.uiTimerExec.schedule(plugin::refreshPanel, RunePartyPlugin.MINIGAME_COUNTDOWN_DURATION_MS, TimeUnit.MILLISECONDS);
                    }, RunePartyPlugin.MINIGAME_COUNTDOWN_START_DELAY_MS, TimeUnit.MILLISECONDS);
                }
                break;

            case Events.MINIGAME_ROUND_BEGIN:
            {
                // Real state, applied catch-up or not: the server's own signal that this round's
                // real content actually started -- hands off to whichever feature owns this key to
                // stamp its own round-start clock (see MinigamePresentationFeature#onRoundBegin's
                // own doc for which mini-games skip this, anchoring off a different event instead).
                MinigamePresentationFeature roundBeginFeature = features.get(minigameKey);
                if (roundBeginFeature != null) roundBeginFeature.onRoundBegin();
                // Unconditional, unlike the per-feature stamp above -- every mini-game fires this
                // event, so this flips true regardless of which one is active. See its own field
                // doc for why this exists separately from the generic countdown's fixed timer.
                minigameRoundBegun = true;
                break;
            }

            default:
                // Every other mini-game-specific event type belongs to whichever feature is
                // currently active -- forwarded here instead of being folded by this class
                // directly, see the minigames package.
                MinigamePresentationFeature feature = features.get(minigameKey);
                if (feature != null) feature.apply(e, catchingUp);
                break;
        }
    }

    /** The non-completedRounds half of MINIGAME_ENDED -- RunePartyPlugin's hybrid case increments
     * completedRounds itself (core whole-game progress) before calling this for the rest: clearing
     * every mini-game field, the rewards recap, and (skipped on the game's final round, since
     * triggerGameOverSequence reveals the same standings itself right after) the round-complete
     * recap. {@code maxRounds}/{@code completedRoundsAfterIncrement} are passed in rather than read
     * off the plugin directly since they're core fields this presenter doesn't otherwise touch. */
    public void handleMinigameEnded(JsonObject payload, boolean catchingUp, int maxRounds, int completedRoundsAfterIncrement)
    {
        // Reads minigameKey before anything below touches it -- see MinigamePresentationFeature#
        // onEnded's own doc for why the ended feature needs this captured first, and
        // showsFinalScore's own check below, the other reader of this same captured value.
        String endedKey = minigameKey;
        MinigamePresentationFeature endedFeature = features.get(endedKey);
        if (!catchingUp && endedFeature != null)
        {
            endedFeature.onEnded();
        }
        minigameActive = false;
        minigameInstructions = null;
        minigameKey = null;
        minigameDisplayName = null;
        minigameReadyRsns.clear();
        minigameCountdownStarted = false;
        minigameCountdownSkippedForClient = false;
        minigameCountdownBannerUntil = 0;
        // Clears the ended mini-game's own per-round state -- harmless no-op if nothing was
        // actually active. Safe to do unconditionally, even ahead of a whole-game reset(): every
        // reader of a feature's own state is itself gated on that mini-game's own isXActive()
        // check, which minigameKey being cleared just above already flips false.
        if (endedFeature != null) endedFeature.reset();
        if (!catchingUp)
        {
            plugin.addChatMessage("Mini-game complete!");
            triggerMinigameOverBanner();
            if (endedFeature != null && endedFeature.showsFinalScore())
            {
                triggerMinigameScoreBanner(payload);
            }
            triggerMinigameRewardsBanner(payload);
            // Skipped on the game's last round -- GAME_ENDED fires right behind this same
            // MINIGAME_ENDED and triggerGameOverSequence reveals the very same standings itself,
            // dramatically, one place at a time. Showing the plain "Current Standings" recap first
            // would spoil that reveal.
            if (maxRounds <= 0 || completedRoundsAfterIncrement < maxRounds)
            {
                scheduleRoundCompleteBanner();
            }
        }
    }

    /** Schedules AnnouncementOverlay's "MINIGAME!" banner via scheduleAfterTurnEffects, so it never
     * appears while the last roller's own turn -- including their coin popup -- is still settling.
     * minigameActive/minigameInstructions are set immediately in the MINIGAME_STARTED handler,
     * unaffected by this delay: this only postpones the celebratory banner, not the mini-game
     * itself. scheduleAfterTurnEffects reserves the gate for this banner's own
     * MINIGAME_BANNER_DURATION_MS synchronously, so scheduleMinigameSpinner (called right behind
     * this one) starts right on schedule once that reservation ends -- but the banner itself keeps
     * rendering well past that (see the callback below), so "MINIGAME!" stays up above the wheel
     * for its own whole spin+reveal instead of disappearing the instant the wheel takes over. Not
     * an armBanner call -- its `until` is deliberately longer than what it reserves on the gate. */
    private void scheduleMinigameBanner()
    {
        minigameBanner.task = plugin.scheduleAfterTurnEffects(minigameBanner.task, RunePartyPlugin.MINIGAME_BANNER_DURATION_MS, () ->
        {
            long now = System.currentTimeMillis();
            // Rendered for MINIGAME_BANNER_DURATION_MS + MINIGAME_SPINNER_DURATION_MS -- longer
            // than what's reserved on the gate below -- so "MINIGAME!" stays visible above the
            // selection wheel for the wheel's own entire spin+reveal instead of vanishing the
            // instant the wheel appears. The gate reservation deliberately stays at the shorter
            // MINIGAME_BANNER_DURATION_MS -- extending it to match would also push back the
            // wheel's own start.
            minigameBanner.until = now + RunePartyPlugin.MINIGAME_BANNER_DURATION_MS + RunePartyPlugin.MINIGAME_SPINNER_DURATION_MS;
            plugin.extendTurnEffectGate(now + RunePartyPlugin.MINIGAME_BANNER_DURATION_MS);
        });
    }

    /** Schedules AnnouncementOverlay's mini-game selection spinner via scheduleAfterTurnEffects,
     * so it waits behind the "MINIGAME!" banner (scheduleMinigameBanner, called right before this)
     * instead of both appearing at once. The gate is reserved for the spin + settle-hold
     * synchronously, so the ready-check screen -- which has no timed trigger of its own -- only
     * starts reading as "the current screen" once this finishes. Not an armBanner call --
     * minigameSpinnerStart/Until are still raw fields, never migrated to a TimedBanner. */
    private void scheduleMinigameSpinner()
    {
        minigameSpinnerTask = plugin.scheduleAfterTurnEffects(minigameSpinnerTask, RunePartyPlugin.MINIGAME_SPINNER_DURATION_MS, () ->
        {
            minigameSpinnerStart = System.currentTimeMillis();
            minigameSpinnerUntil = minigameSpinnerStart + RunePartyPlugin.MINIGAME_SPINNER_DURATION_MS;
            plugin.extendTurnEffectGate(minigameSpinnerUntil);
        });
    }

    /** Arms AnnouncementOverlay's "MINIGAME OVER!" banner -- fired first on every MINIGAME_ENDED,
     * for every mini-game alike, before the rewards recap even starts (see
     * triggerMinigameRewardsBanner, armed right behind this one and chained behind it via the
     * shared turnEffectGateUntil). No payload of its own -- just a beat between the round
     * genuinely ending and the recap taking over. */
    private void triggerMinigameOverBanner()
    {
        plugin.armBanner(minigameOverBanner, RunePartyPlugin.MINIGAME_OVER_BANNER_DURATION_MS, () -> null, true);
    }

    /** Arms AnnouncementOverlay's mini-game final-score recap ("how did everyone do") -- called
     * from handleMinigameEnded, parsing its own "results" list eagerly, same "fixed, already-landed
     * event, nothing to gain from lazy re-parsing" reasoning triggerMinigameRewardsBanner's own doc
     * gives. armBanner chains this behind whatever's already reserved turnEffectGateUntil -- the
     * "MINIGAME OVER!" banner above -- and extends it further so the rewards recap right behind
     * this one waits its turn too. */
    private void triggerMinigameScoreBanner(JsonObject payload)
    {
        List<MinigameScore> scores = Json.safeMinigameScores(payload, "results");
        plugin.armBanner(minigameScoreBanner, RunePartyPlugin.MINIGAME_SCORE_BANNER_DURATION_MS, () -> scores, true);
    }

    /** Arms AnnouncementOverlay's mini-game rewards recap ("who got what") -- called from
     * handleMinigameEnded, parsing its own "payouts" list eagerly (payload is a fixed,
     * already-landed MINIGAME_ENDED event, so there's nothing to gain from re-parsing it lazily)
     * rather than having AnnouncementOverlay re-parse the raw event payload every frame. armBanner
     * chains this behind whatever's already reserved turnEffectGateUntil -- the "MINIGAME OVER!"
     * banner above -- and extends it further so both the round-complete recap and the new round's
     * first TURN_STARTED banner wait behind this one too. */
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

    public void reset()
    {
        minigameBanner.reset();
        minigameOverBanner.reset();
        minigameScoreBanner.reset();
        if (minigameSpinnerTask != null) { minigameSpinnerTask.cancel(false); minigameSpinnerTask = null; }
        roundCompleteBanner.reset();
        minigameRewardsBanner.reset();
        for (MinigamePresentationFeature feature : features.values()) feature.reset();
        awaitingMinigameReadyFinish = false;
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
    }

    // ---- awaiting-emote flag, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain. True-or-False's/Hot Potato's
    // own equivalents now live on trueOrFalse()/hotPotato() directly. ----
    public void armAwaitingMinigameReadyFinish() { awaitingMinigameReadyFinish = true; }
    public boolean isAwaitingMinigameReadyFinish() { return awaitingMinigameReadyFinish; }
    public void clearAwaitingMinigameReadyFinish() { awaitingMinigameReadyFinish = false; }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under their original names ----
    public boolean isActive() { return minigameActive; }
    public String getInstructions() { return minigameInstructions; }
    public String getKey() { return minigameKey; }
    public String getDisplayName() { return minigameDisplayName; }
    public long getMinigameSpinnerStart() { return minigameSpinnerStart; }
    public long getMinigameSpinnerUntil() { return minigameSpinnerUntil; }
    public boolean isMinigameSpinnerSkippedForClient() { return minigameSpinnerSkippedForClient; }
    public Set<String> getMinigameReadyRsns() { return minigameReadyRsns; }
    public boolean isCountdownStarted() { return minigameCountdownStarted; }
    public boolean isCountdownSkippedForClient() { return minigameCountdownSkippedForClient; }
    public long getCountdownBannerUntil() { return minigameCountdownBannerUntil; }
    public boolean isRoundBegun() { return minigameRoundBegun; }
    public long getMinigameBannerUntil() { return minigameBanner.until; }
    public long getMinigameOverBannerUntil() { return minigameOverBanner.until; }
    public long getMinigameScoreBannerUntil() { return minigameScoreBanner.until; }
    public List<MinigameScore> getMinigameScores() { return minigameScoreBanner.payload != null ? minigameScoreBanner.payload : Collections.emptyList(); }
    public long getRoundCompleteBannerUntil() { return roundCompleteBanner.until; }
    public int getRoundCompleteRoundNumber() { return roundCompleteBanner.payload != null ? roundCompleteBanner.payload : 0; }
    public long getMinigameRewardsBannerUntil() { return minigameRewardsBanner.until; }
    public List<MinigameReward> getMinigameRewards() { return minigameRewardsBanner.payload != null ? minigameRewardsBanner.payload : Collections.emptyList(); }

    /** Whether {@code key} (one of RunePartyPlugin's own {@code *_KEY} constants) is the
     * currently-active mini-game -- the one bit of per-mini-game logic every {@code isXActive()}
     * facade method shares, so it lives here once instead of once per feature class. */
    public boolean isKeyActive(String key) { return minigameActive && key.equals(minigameKey); }

    public CoinRushPresentation coinRush() { return coinRush; }
    public SandwichRushPresentation sandwichRush() { return sandwichRush; }
    public FishingContestPresentation fishingContest() { return fishingContest; }
    public ClickClickClickPresentation clickClickClick() { return clickClickClick; }
    public TurfWarsPresentation turfWars() { return turfWars; }
    public WhosYourJaddyPresentation jaddy() { return jaddy; }
    public TrueOrFalsePresentation trueOrFalse() { return trueOrFalse; }
    public HotPotatoPresentation hotPotato() { return hotPotato; }
}
