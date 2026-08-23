package gay.runescape.runeparty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;

/** End-game awards ceremony state and choreography -- extracted from RunePartyPlugin per
 * ARCHITECTURE_REVIEW.md's C1 finding, step 2. Owns its own fields, exposes triggerGameOverSequence()
 * for RunePartyPlugin's hybrid GAME_ENDED case to call (GAME_ENDED itself is a core phase
 * transition, not "ceremony state," so it isn't routed through an apply()), and clears itself via
 * reset(); RunePartyPlugin still exposes every getter under its original name, just delegating
 * here, so no external caller needs to change. */
final class CeremonyPresentation
{
    private final RunePartyPlugin plugin;

    // ---- end-game awards ceremony (server-driven, everyone sees it -- see GAME_ENDED handling and
    // triggerGameOverSequence). A chain of banners, each scheduled behind the last via
    // scheduleAfterTurnEffects: "GAME OVER!" -> "Now it's time to see the winner..." -> one
    // "In Nth place..." reveal per eliminated player (worst to best, stopping once only the top two
    // remain) -> "And the winner is..." -> the winner's name plus ConfettiOverlay's burst.
    // gameOverStandings is the final ranking (coins desc, Golden Gnomes tiebreak, same order
    // renderRoundCompleteBanner already uses), snapshotted once at trigger time since no further
    // coin/gnome changes are possible once the server's flipped the game out of ACTIVE. ----
    private volatile ScheduledFuture<?> gameOverTask; // one handle threaded through every step below, not owned by any single one
    private volatile List<RosterReducer.RosterEntry> gameOverStandings = Collections.emptyList();
    private final TimedBanner<Void> gameOverBanner = new TimedBanner<>();
    private final TimedBanner<Void> winnerIntroBanner = new TimedBanner<>();
    private final TimedBanner<PlaceRevealPayload> placeReveal = new TimedBanner<>();
    private final TimedBanner<Void> winnerSuspenseBanner = new TimedBanner<>();
    private final TimedBanner<String> winnerRevealBanner = new TimedBanner<>(); // payload: winner rsn
    private final TimedBanner<Void> confettiBanner = new TimedBanner<>();

    CeremonyPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    /** Final standings, ranked the same way renderRoundCompleteBanner already ranks the live
     * mid-game ones -- Golden Gnome count descending, coins as tiebreak -- snapshotted once on
     * GAME_ENDED rather than read live, since no further coin/gnome changes are possible once the
     * server's flipped the game out of ACTIVE. Spectators and never-joined seats are excluded, same
     * as every other standings view. */
    private List<RosterReducer.RosterEntry> computeFinalStandings()
    {
        List<RosterReducer.RosterEntry> standings = plugin.getRosterReducer().seatedPlayers();
        standings.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> e.goldenGnomeCount).reversed()
            .thenComparing(Comparator.comparingInt((RosterReducer.RosterEntry e) -> e.coins).reversed()));
        return standings;
    }

    /** Kicks off the end-game awards ceremony on GAME_ENDED -- a chain of banners, each scheduled
     * behind the last via scheduleAfterTurnEffects (see the field block's own doc for the full
     * sequence). This first step is itself gated the same way, since GAME_ENDED can land in the
     * very same event batch as the final round's own MINIGAME_ENDED (see app.py's
     * _resolve_minigame_if_complete) -- without waiting behind that banner's own gate reservation,
     * "GAME OVER!" would flash up mid-rewards-recap instead of politely queuing after it. No-ops if
     * nobody's actually seated (shouldn't happen for a game that reached GAME_ENDED, but a lone
     * host force-ending an empty lobby is technically possible). Not an armBanner call, nor are
     * the four scheduleX methods below it that continue this chain (see that method's own doc) --
     * each carries real logic beyond arming one banner (building a reveal order, a chat message,
     * arming two banners in the same callback), not just the arm-and-extend-gate shape armBanner
     * covers. */
    void triggerGameOverSequence()
    {
        List<RosterReducer.RosterEntry> standings = computeFinalStandings();
        if (standings.isEmpty()) return;
        gameOverStandings = standings;

        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.GAME_OVER_TITLE_DURATION_MS, () ->
        {
            gameOverBanner.until = System.currentTimeMillis() + RunePartyPlugin.GAME_OVER_TITLE_DURATION_MS;
            plugin.extendTurnEffectGate(gameOverBanner.until);
            scheduleWinnerIntro();
        });
    }

    private void scheduleWinnerIntro()
    {
        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.WINNER_INTRO_DURATION_MS, () ->
        {
            winnerIntroBanner.until = System.currentTimeMillis() + RunePartyPlugin.WINNER_INTRO_DURATION_MS;
            plugin.extendTurnEffectGate(winnerIntroBanner.until);

            // Worst to best, stopping once only the top two remain -- e.g. a 4-player game reveals
            // 4th then 3rd, leaving 1st/2nd for the "And the winner is..." showdown. A 2-player (or
            // fewer) game has nothing to reveal here at all, so this list ends up empty and
            // schedulePlaceReveal falls straight through to scheduleWinnerSuspense.
            List<RosterReducer.RosterEntry> revealOrder = new ArrayList<>();
            for (int i = gameOverStandings.size() - 1; i >= 2; i--) revealOrder.add(gameOverStandings.get(i));
            schedulePlaceReveal(revealOrder, 0);
        });
    }

    private void schedulePlaceReveal(List<RosterReducer.RosterEntry> revealOrder, int index)
    {
        if (index >= revealOrder.size())
        {
            scheduleWinnerSuspense();
            return;
        }

        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.PLACE_REVEAL_DURATION_MS, () ->
        {
            RosterReducer.RosterEntry entry = revealOrder.get(index);
            placeReveal.payload = new PlaceRevealPayload(entry.rsn, gameOverStandings.indexOf(entry) + 1, entry.coins, entry.goldenGnomeCount);
            placeReveal.until = System.currentTimeMillis() + RunePartyPlugin.PLACE_REVEAL_DURATION_MS;
            plugin.extendTurnEffectGate(placeReveal.until);
            schedulePlaceReveal(revealOrder, index + 1);
        });
    }

    private void scheduleWinnerSuspense()
    {
        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.WINNER_SUSPENSE_DURATION_MS, () ->
        {
            winnerSuspenseBanner.until = System.currentTimeMillis() + RunePartyPlugin.WINNER_SUSPENSE_DURATION_MS;
            plugin.extendTurnEffectGate(winnerSuspenseBanner.until);
            scheduleWinnerReveal();
        });
    }

    /** The ceremony's final step -- the winner's own name, held alongside ConfettiOverlay's burst
     * (confettiBanner, shorter than WINNER_REVEAL_DURATION_MS so the confetti finishes settling
     * while the name's still up rather than both cutting off together). gameOverStandings is sorted
     * winner-first, so index 0 is always the winner. */
    private void scheduleWinnerReveal()
    {
        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.WINNER_REVEAL_DURATION_MS, () ->
        {
            RosterReducer.RosterEntry winner = gameOverStandings.get(0);
            winnerRevealBanner.payload = winner.rsn;
            long now = System.currentTimeMillis();
            winnerRevealBanner.until = now + RunePartyPlugin.WINNER_REVEAL_DURATION_MS;
            confettiBanner.until = now + RunePartyPlugin.CONFETTI_DURATION_MS;
            plugin.addChatMessage(winner.rsn + " wins Rune Party!");
        });
    }

    void reset()
    {
        if (gameOverTask != null) { gameOverTask.cancel(false); gameOverTask = null; }
        gameOverStandings = Collections.emptyList();
        gameOverBanner.reset();
        winnerIntroBanner.reset();
        placeReveal.reset();
        winnerSuspenseBanner.reset();
        winnerRevealBanner.reset();
        confettiBanner.reset();
    }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under their original names ----
    List<RosterReducer.RosterEntry> getGameOverStandings() { return gameOverStandings; }
    long getGameOverBannerUntil() { return gameOverBanner.until; }
    long getWinnerIntroBannerUntil() { return winnerIntroBanner.until; }
    long getPlaceRevealUntil() { return placeReveal.until; }
    String getPlaceRevealRsn() { return placeReveal.payload != null ? placeReveal.payload.rsn : null; }
    int getPlaceRevealRank() { return placeReveal.payload != null ? placeReveal.payload.rank : 0; }
    int getPlaceRevealCoins() { return placeReveal.payload != null ? placeReveal.payload.coins : 0; }
    int getPlaceRevealGoldenGnomes() { return placeReveal.payload != null ? placeReveal.payload.goldenGnomes : 0; }
    long getWinnerSuspenseUntil() { return winnerSuspenseBanner.until; }
    long getWinnerRevealUntil() { return winnerRevealBanner.until; }
    String getWinnerRsn() { return winnerRevealBanner.payload; }
    long getConfettiUntil() { return confettiBanner.until; }

    /** Payload for one place-reveal step in the end-game ceremony -- see schedulePlaceReveal. */
    private static final class PlaceRevealPayload
    {
        final String rsn;
        final int rank; // 1-based rank within gameOverStandings
        final int coins;
        final int goldenGnomes;

        PlaceRevealPayload(String rsn, int rank, int coins, int goldenGnomes)
        {
            this.rsn = rsn;
            this.rank = rank;
            this.coins = coins;
            this.goldenGnomes = goldenGnomes;
        }
    }
}
