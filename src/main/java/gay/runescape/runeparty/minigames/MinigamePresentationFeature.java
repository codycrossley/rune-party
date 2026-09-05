package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;

/** One minigame's own client-side state and event-folding, extracted out of the monolithic
 * MinigamePresentation that used to hold all of these side by side (see e.g. HotPotatoPresentation,
 * CoinRushPresentation) -- the same "extract a feature's own state into its own class" pattern
 * ChanceSpacePresentation/GoldenGnomePresentation/ItemPresentation/JadPresentation/
 * CeremonyPresentation already established, just not previously applied per-minigame.
 * MinigamePresentation holds one instance of each implementation, keyed by that minigame's own
 * RunePartyPlugin.*_KEY, and dispatches to whichever one matches the currently-active minigameKey.
 * Not every minigame needs one: Arena has no client-tracked state of its own beyond the generic
 * lifecycle (its own gameplay is entirely tile-color-driven), so it has no implementation at all. */
public interface MinigamePresentationFeature
{
    /** Folds one event type this minigame owns -- called only while this minigame is the currently
     * active one, for any event type MinigamePresentation's own generic switch doesn't recognize.
     * Default no-op, for a minigame with no events of its own to fold (Fishing Contest/Click,
     * Click, Click, whose tallies are entirely client-local and never reported mid-round). */
    default void apply(ApiClient.EventOut e, boolean catchingUp) {}

    /** Resets this minigame's own per-round state fresh -- called once, right as MINIGAME_STARTED
     * lands for this minigame's own key, replacing what used to be one more branch in a long
     * if-chain keyed on minigameKey. Default no-op, for a minigame with nothing to reset before its
     * first real event (e.g. Hot Potato, whose own HOT_POTATO_ASSIGNED is what actually seeds its
     * state, not this hook). */
    default void onStarted(boolean catchingUp) {}

    /** Stamps this minigame's own round-start wall-clock timestamp -- called once, right as
     * MINIGAME_ROUND_BEGIN lands, for whichever minigame is currently active. Default no-op, for a
     * minigame that anchors its own timing off a different event instead (True or False anchors off
     * its own TRUE_OR_FALSE_ROUND_STARTED). */
    default void onRoundBegin() {}

    /** A pre-clear hook run from handleMinigameEnded, before minigameKey (and everything else
     * generic) is reset -- for a one-shot side effect that needs to know which minigame just ended
     * (e.g. Turf Wars' own end-of-round confetti burst). Default no-op. */
    default void onEnded() {}

    /** Whether this minigame's own MINIGAME_ENDED "results" carry a real, varying per-player count
     * worth showing as a "FINAL SCORE" recap -- coins collected, correct answers, catches, tiles
     * claimed, sandwiches made, unique tiles clicked. False by default: a minigame whose own
     * server-side payout is a flat reward (Arena's survive/eliminated, Hot Potato's who-was-
     * holding-it, Who's Your Jaddy?'s which-side-won) only ever produces a binary "did you get the
     * flat reward or not" result, not a real score, so a recap there would just show everyone tied
     * at the same number (or 0) -- not information worth a whole extra banner. */
    default boolean showsFinalScore() { return false; }

    /** Clears this minigame's own state back to its own initial values -- called both per-round
     * (folded into MinigamePresentation's own handleMinigameEnded cleanup) and on a whole-game
     * reset (MinigamePresentation's own reset()). */
    void reset();
}
