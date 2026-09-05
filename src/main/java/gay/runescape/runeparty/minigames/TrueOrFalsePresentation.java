package gay.runescape.runeparty.minigames;

import com.google.gson.JsonElement;
import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TrueOrFalseResult;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** True or False's own client-side state (server-driven rounds). All real state, applied catch-up
 * or not: question/roundNumber are the current round's own question text and 1-indexed round
 * number (null/0 once the round ends, until the next one starts or the mini-game itself ends).
 * answeredRsns mirrors MinigamePresentation's own minigameReadyRsns "who's confirmed" role, just
 * scoped to the current round instead of the whole mini-game. myAnswer is the local player's own
 * answer this round (null until they've answered), consulted so a YES/NO emote doesn't resubmit
 * once they already have. lastCorrectAnswer/lastResults are the most recent
 * TRUE_OR_FALSE_ROUND_ENDED's own reveal, held onto (not cleared) through the next round's own
 * question update, since renderTrueOrFalseReveal gates its own display on revealUntil, not on
 * whether a new question already exists. */
public final class TrueOrFalsePresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private volatile String question = null;
    private volatile int roundNumber = 0;
    private final Set<String> answeredRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    private volatile Boolean myAnswer = null;
    private volatile long roundStartedAt = 0; // wall-clock moment this round's own TRUE_OR_FALSE_ROUND_STARTED landed -- see getRoundEndsAt
    private volatile Boolean lastCorrectAnswer = null;
    private volatile List<TrueOrFalseResult> lastResults = Collections.emptyList();
    private volatile long revealUntil = 0;
    // Same idea as JadPresentation's own awaitingBowFinish, one per response to a pending
    // True-or-False round -- see RunePartyPlugin#onAnimationChanged, which consults these via the
    // arm/isAwaiting/clear methods below.
    private volatile boolean awaitingYesFinish = false;
    private volatile boolean awaitingNoFinish = false;

    public TrueOrFalsePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.TRUE_OR_FALSE_ROUND_STARTED:
            {
                // Real state, applied catch-up or not: a fresh round genuinely started at this
                // point. Never carries the correct answer, so there's nothing here for a client to
                // read early. Per-round-only state (who's answered, my own answer) resets fresh;
                // the previous round's reveal (lastCorrectAnswer/lastResults) is deliberately left
                // alone here -- renderTrueOrFalseReveal gates its own display on revealUntil, not
                // on whether a new question already exists.
                question = Json.requiredStr(e.payload, type, "question");
                Integer newRoundNumber = Json.requiredInt(e.payload, type, "roundNumber");
                if (newRoundNumber != null) roundNumber = newRoundNumber;
                answeredRsns.clear();
                myAnswer = null;
                // "Now" regardless of catch-up -- a live client gets the genuinely-accurate instant
                // since this event only ever arrives right as the round actually starts either way.
                roundStartedAt = System.currentTimeMillis();
                break;
            }

            case Events.TRUE_OR_FALSE_ANSWERED:
            {
                // Real state, applied catch-up or not -- isLocalPlayerAwaitingTrueOrFalseAnswer/
                // renderTrueOrFalseQuestion both need an accurate answered-set.
                String answeredRsn = Json.requiredStr(e.payload, type, "player");
                if (answeredRsn != null)
                {
                    answeredRsns.add(answeredRsn.toLowerCase(Locale.ROOT));
                    String self = plugin.getLocalRsn();
                    if (self != null && self.equalsIgnoreCase(answeredRsn))
                    {
                        JsonElement answerEl = e.payload.get("answer");
                        if (answerEl != null && !answerEl.isJsonNull()) myAnswer = answerEl.getAsBoolean();
                    }
                }
                break;
            }

            case Events.TRUE_OR_FALSE_ROUND_ENDED:
            {
                // Real state, applied catch-up or not: the correct answer is now public.
                JsonElement correctEl = e.payload.get("correctAnswer");
                lastCorrectAnswer = correctEl != null && !correctEl.isJsonNull() ? correctEl.getAsBoolean() : null;
                lastResults = Json.safeTrueOrFalseResults(e.payload);
                if (!catchingUp)
                {
                    revealUntil = System.currentTimeMillis() + RunePartyPlugin.TRUE_OR_FALSE_REVEAL_DURATION_MS;
                    plugin.extendTurnEffectGate(revealUntil);
                }
                break;
            }

            default:
                break;
        }
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as CoinRushPresentation's own onStarted -- a fresh True or False instance
        // starts with no question/answers/reveal, regardless of catch-up.
        question = null;
        roundNumber = 0;
        answeredRsns.clear();
        myAnswer = null;
        roundStartedAt = 0;
        lastCorrectAnswer = null;
        lastResults = Collections.emptyList();
        revealUntil = 0;
    }

    // No onRoundBegin override -- this mini-game anchors its own round timing off its own
    // TRUE_OR_FALSE_ROUND_STARTED (roundStartedAt above), not off the generic MINIGAME_ROUND_BEGIN.

    @Override
    public boolean showsFinalScore() { return true; }

    @Override
    public void reset()
    {
        awaitingYesFinish = false;
        awaitingNoFinish = false;
        // Same reasoning as onStarted above -- no question/reveal should keep rendering once the
        // mini-game itself has ended (per-round clear) or the whole game resets.
        question = null;
        roundNumber = 0;
        answeredRsns.clear();
        myAnswer = null;
        roundStartedAt = 0;
        lastCorrectAnswer = null;
        lastResults = Collections.emptyList();
        revealUntil = 0;
    }

    // ---- awaiting-emote flags, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    public void armAwaitingYesFinish() { awaitingYesFinish = true; }
    public void armAwaitingNoFinish() { awaitingNoFinish = true; }
    public boolean isAwaitingYesFinish() { return awaitingYesFinish; }
    public boolean isAwaitingNoFinish() { return awaitingNoFinish; }
    public void clearAwaitingYesFinish() { awaitingYesFinish = false; }
    public void clearAwaitingNoFinish() { awaitingNoFinish = false; }

    public String getQuestion() { return question; }
    public int getRoundNumber() { return roundNumber; }
    public Set<String> getAnsweredRsns() { return answeredRsns; }
    public Boolean getMyAnswer() { return myAnswer; }
    /** When the current round's reading period ends and its answer countdown starts ticking -- 0
     * if no round is currently open. renderTrueOrFalseQuestion hides the countdown number until
     * this passes. */
    public long getAnswerWindowStartsAt() { return question != null && roundStartedAt != 0 ? roundStartedAt + RunePartyPlugin.TRUE_OR_FALSE_READING_DURATION_MS : 0; }
    /** When the current round's own clock runs out -- 0 if no round is currently open. */
    public long getRoundEndsAt() { return question != null && roundStartedAt != 0 ? roundStartedAt + RunePartyPlugin.TRUE_OR_FALSE_READING_DURATION_MS + RunePartyPlugin.TRUE_OR_FALSE_ROUND_DURATION_MS : 0; }
    public Boolean getLastCorrectAnswer() { return lastCorrectAnswer; }
    public List<TrueOrFalseResult> getLastResults() { return lastResults; }
    public long getRevealUntil() { return revealUntil; }
}
