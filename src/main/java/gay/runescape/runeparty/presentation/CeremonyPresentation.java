package gay.runescape.runeparty.presentation;

import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;
import gay.runescape.runeparty.minigames.ArrivalGate;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** End-game ceremony state and choreography, extracted out of RunePartyPlugin -- now two chained
 * halves, both server-paced (everyone sees the same thing at the same time):
 * <p>
 * 1. The Golden Gnome Awards: CEREMONY_STARTED (rainbow title, then the persistent "gather in the
 * arena" message) -> the Gnome's own scripted CEREMONY_GNOME_LINE lines -> up to two bonus rounds
 * (CEREMONY_BONUS_OBJECTIVE_ANNOUNCED -> a real GOLDEN_GNOME_WON grant per winner ->
 * CEREMONY_BONUS_WINNER_REVEALED, itself expanded client-side into "The Golden Gnome is awarded
 * to..." suspense -> the actual name(s) -> that round's own flanking Golden Gnome prop vanishing
 * -> the "+1" popup, see handleBonusWinnerRevealed's own doc) -> CEREMONY_TRANSITION_TO_WINNER.
 * <p>
 * 2. The existing standings/winner reveal (unchanged internally, just triggered later than before):
 * "Now it's time to see the winner..." -> one "In Nth place..." reveal per eliminated player (worst
 * to best, stopping once only the top two remain) -> "And the winner is..." -> the winner's name
 * plus ConfettiOverlay's burst -> "GAME OVER!" (moved here, the sequence's own last beat now,
 * instead of its first).
 * <p>
 * Only the very first beat (the rainbow title) needs turnEffectGateUntil chaining
 * (scheduleAfterTurnEffects) -- it can land while the final mini-game's own rewards/round-complete
 * recap is still playing. That same callback is also what flips ceremonyIntroRevealed true --
 * every other purely-cosmetic ceremony-visible element (the gather message, the arena floor
 * outline, the Gnome NPC and its flanking props) waits on THAT instead of the immediate
 * ceremonyStarted, so none of them can render before the recap is actually done either (see
 * ceremonyIntroRevealed's own field doc). Every beat from there on is purely server-paced:
 * ceremony.py (server) sleeps a real, generous duration between each of its own inserts, so
 * there's no risk of two of these overlapping the way independent client-local timers could --
 * each one is just set directly off its own event (the bonus winner reveal's own extra
 * suspense/vanish/popup staging is the one exception, entirely self-contained within the real
 * duration ceremony.py's own BONUS_REVEAL_HOLD_SECONDS already budgets for it). gameOverTask is
 * still the one handle threaded through every step that DOES need it (the title, then the whole
 * standings/winner sequence from triggerWinnerRevealSequence onward).
 * <p>
 * Exposes handleCeremonyStarted/handleGnomeLine/handleBonusObjectiveAnnounced/
 * handleBonusWinnerRevealed/handleCeremonyTransitionToWinner for RunePartyPlugin's own event
 * handling to call, and clears itself via reset(); RunePartyPlugin still exposes every getter
 * under its original name, just delegating here. */
public final class CeremonyPresentation
{
    private final RunePartyPlugin plugin;

    private volatile ScheduledFuture<?> gameOverTask; // one handle threaded through every step below that needs turnEffectGate chaining
    private volatile List<RosterReducer.RosterEntry> gameOverStandings = Collections.emptyList();

    // ---- Golden Gnome Awards (server-paced, everyone sees it) ----
    private volatile boolean ceremonyStarted = false; // real state, set immediately regardless of catch-up
    // Flips true once the cosmetic title banner above has actually been armed (i.e. once
    // scheduleAfterTurnEffects's own callback fires, the same moment the final mini-game's own
    // rewards/round-complete recap has genuinely finished reserving the gate) -- every other
    // ceremony-visible cosmetic element (the gather message, the arena floor outline, the Gnome
    // NPC/flanking props) waits on THIS instead of the immediate ceremonyStarted, so none of them
    // can render before that recap is actually done. Same "real state is immediate, the visual
    // reveal is deliberately held back" split TileOverlay's own doc describes for Rainbow Rush's
    // isMinigameSelectionRevealed().
    private volatile boolean ceremonyIntroRevealed = false;
    // Reset on reset() below -- see ArrivalGate's own doc. No onStarted() equivalent to also reset
    // from -- unlike a mini-game's own arrival gate, the ceremony only ever runs once per game.
    private final ArrivalGate arrivalGate;
    private final TimedBanner<Void> ceremonyTitleBanner = new TimedBanner<>();
    private final TimedBanner<String> gnomeLineBanner = new TimedBanner<>(); // payload: the current scripted line
    private volatile int bonusRoundIndex = 0; // how many CEREMONY_BONUS_OBJECTIVE_ANNOUNCED have landed this ceremony -- for "first"/"second" wording
    private final TimedBanner<BonusObjectivePayload> bonusObjectiveBanner = new TimedBanner<>();
    // "The Golden Gnome is awarded to..." -- a purely client-local suspense beat inserted right
    // before bonusWinnerBanner below, see handleBonusWinnerRevealed's own doc.
    private final TimedBanner<Void> bonusSuspenseBanner = new TimedBanner<>();
    private final TimedBanner<List<String>> bonusWinnerBanner = new TimedBanner<>();
    // One flanking Golden Gnome prop per possible bonus round (index = round index - 1) -- 0 means
    // that round's own gnome hasn't vanished (or that round never ran); real state, System.
    // currentTimeMillis() the moment its own vanish spotanim actually started. GnomeNpcOverlay
    // reads isFlankingGnomeVisible() every frame to decide whether to keep it spawned -- see that
    // class's own doc.
    private final long[] flankingGnomeVanishAt = new long[RunePartyPlugin.CEREMONY_MAX_BONUS_ROUNDS];

    // ---- existing standings/winner reveal (unchanged shape, see this class's own doc) ----
    private final TimedBanner<Void> winnerIntroBanner = new TimedBanner<>();
    private final TimedBanner<PlaceRevealPayload> placeReveal = new TimedBanner<>();
    private final TimedBanner<Void> winnerSuspenseBanner = new TimedBanner<>();
    private final TimedBanner<String> winnerRevealBanner = new TimedBanner<>(); // payload: winner rsn
    private final TimedBanner<Void> confettiBanner = new TimedBanner<>();
    private final TimedBanner<Void> gameOverBanner = new TimedBanner<>(); // now the sequence's own LAST beat, not its first

    public CeremonyPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.arrivalGate = new ArrivalGate(plugin, "the Golden Gnome Awards", "the ceremony arena",
            (self, gid, token) -> plugin.apiClient.confirmCeremonyArrival(gid, self, token));
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while the ceremony's own
     * arena might exist -- fires the one-shot confirm-ceremony-arrival report the instant this
     * client's own position first lands on any CEREMONY_TILE, same event-driven shape every other
     * arrival-gated mini-game's own onTick already established. No-op before CEREMONY_STARTED
     * lands (there's no arena to stand on yet). */
    public void onTick(Player selfPlayer)
    {
        if (!ceremonyStarted) return;
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        arrivalGate.confirmIfMatched(pos != null && plugin.findCeremonyArenaTiles().contains(pos));
    }

    /** Final standings -- Golden Gnome count descending, coins as tiebreak -- snapshotted once
     * right as the Golden Gnome Awards hands off to this reveal (after every bonus gnome's own
     * grant has already landed, so they count). Spectators and never-joined seats are excluded. */
    private List<RosterReducer.RosterEntry> computeFinalStandings()
    {
        List<RosterReducer.RosterEntry> standings = plugin.getRosterReducer().seatedPlayers();
        standings.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> e.goldenGnomeCount).reversed()
            .thenComparing(Comparator.comparingInt((RosterReducer.RosterEntry e) -> e.coins).reversed()));
        return standings;
    }

    /** CEREMONY_STARTED's own handling -- ceremonyStarted flips immediately, real state regardless
     * of catch-up (same "state vs. cosmetic banner" split MINIGAME_STARTED's own minigameActive/
     * celebratory-banner handling already uses), so a client that only catches up on the fact that
     * the ceremony's already under way skips straight to whatever's actually current instead of
     * replaying a rainbow title for a moment that's long since passed. The rainbow title itself is
     * cosmetic-only, scheduled behind whatever's still reserving the gate (the final mini-game's
     * own rewards/round-complete recap, most likely) -- see this class's own doc. */
    public void handleCeremonyStarted(boolean catchingUp)
    {
        ceremonyStarted = true;
        if (catchingUp) return;

        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.CEREMONY_TITLE_BANNER_DURATION_MS, () ->
        {
            ceremonyTitleBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_TITLE_BANNER_DURATION_MS;
            plugin.extendTurnEffectGate(ceremonyTitleBanner.until);
            ceremonyIntroRevealed = true;
        });
    }

    /** One of the Gnome's own scripted lines -- purely server-paced from here (see this class's
     * own doc), no turnEffectGate chaining needed. */
    public void handleGnomeLine(String line, boolean catchingUp)
    {
        if (catchingUp) return;
        gnomeLineBanner.payload = line;
        gnomeLineBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_GNOME_LINE_DURATION_MS;
    }

    public void handleBonusObjectiveAnnounced(String displayName, String description, boolean catchingUp)
    {
        if (catchingUp) return;
        bonusRoundIndex++;
        bonusObjectiveBanner.payload = new BonusObjectivePayload(bonusRoundIndex, displayName, description);
        bonusObjectiveBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_BONUS_ANNOUNCE_DURATION_MS;
    }

    /** The bonus round's own winner reveal -- deliberately NOT shown the instant this event lands.
     * Mirrors scheduleWinnerSuspense/scheduleWinnerReveal's own shape below (the existing "And the
     * winner is..." -> name beat), which is itself purely a client-local delay chained off one
     * already-landed server event, no dedicated suspense event of its own -- same idea here: the
     * client already knows the winner(s) from this payload, it just sits on that for
     * CEREMONY_BONUS_SUSPENSE_DURATION_MS behind a "The Golden Gnome is awarded to..." cliffhanger
     * first. Once the real name(s) actually appear, this round's own flanking Golden Gnome prop
     * (bonusRoundIndex - 1) plays its vanish spotanim -- see triggerFlankingGnomeVanish's own doc
     * for why the "+1" popup itself waits for that animation to actually finish. Uses
     * plugin.uiTimerExec directly (not scheduleAfterTurnEffects/gameOverTask) since nothing else
     * can be competing for the screen at this specific point in the ceremony -- same tool
     * GOLDEN_GNOME_MOVED's own spotanim-gap choreography already uses for an identical reason. */
    public void handleBonusWinnerRevealed(List<String> winners, boolean catchingUp)
    {
        if (catchingUp) return;

        bonusSuspenseBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_BONUS_SUSPENSE_DURATION_MS;

        plugin.uiTimerExec.schedule(() ->
        {
            bonusWinnerBanner.payload = winners;
            bonusWinnerBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_BONUS_REVEAL_DURATION_MS;
            triggerFlankingGnomeVanish(winners);
        }, RunePartyPlugin.CEREMONY_BONUS_SUSPENSE_DURATION_MS, TimeUnit.MILLISECONDS);
    }

    /** Plays this round's own flanking Golden Gnome prop's vanish spotanim -- the exact same
     * GOLDEN_GNOME_MOVE_SPOTANIM_ID the board's own relocating Golden Gnome already uses (see
     * GoldenGnomePresentation#apply's own GOLDEN_GNOME_MOVED handling) -- but with no arrival half:
     * this prop is gone for good, matching the user's own "it just disappears" framing. Real vanish
     * state (flankingGnomeVanishAt) is set immediately so GnomeNpcOverlay stops rendering it after
     * GOLDEN_GNOME_MOVE_VANISH_DELAY_MS, same delay the board's own gnome uses to let the spotanim
     * visually cover the model actually disappearing. The "+1 Golden Gnome" popup for every winner
     * is deliberately deferred to that same moment (not shown the instant GOLDEN_GNOME_WON already
     * landed server-side -- see GoldenGnomePresentation's own "ceremony_bonus" exclusion) via
     * showGoldenGnomeCountPopup, reading the real, already-folded running total straight off
     * RosterReducer rather than needing to carry it through this event at all. */
    private void triggerFlankingGnomeVanish(List<String> winners)
    {
        int index = bonusRoundIndex - 1;
        if (index < 0 || index >= flankingGnomeVanishAt.length) return;

        WorldPoint point = flankingGnomePoint(index);
        if (point != null)
        {
            plugin.triggerSpotAnimAtWorldPoint(RunePartyPlugin.GOLDEN_GNOME_MOVE_SPOTANIM_ID, point);
        }
        flankingGnomeVanishAt[index] = System.currentTimeMillis();

        plugin.uiTimerExec.schedule(() ->
        {
            for (String rsn : winners)
            {
                plugin.showGoldenGnomeCountPopup(rsn, plugin.getRosterReducer().getGoldenGnomeCount(rsn), 1);
            }
        }, RunePartyPlugin.GOLDEN_GNOME_MOVE_VANISH_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    /** Whether the flanking Golden Gnome prop at {@code index} (0 = round 1's, 1 = round 2's)
     * should still be rendered -- GnomeNpcOverlay's own per-frame read. True until its own vanish
     * spotanim has actually had time to play (or forever, if that round never ran at all). */
    public boolean isFlankingGnomeVisible(int index)
    {
        if (index < 0 || index >= flankingGnomeVanishAt.length) return false;
        long vanishAt = flankingGnomeVanishAt[index];
        return vanishAt == 0 || System.currentTimeMillis() - vanishAt < RunePartyPlugin.GOLDEN_GNOME_MOVE_VANISH_DELAY_MS;
    }

    /** The ceremony arena's own exact middle tile -- duplicated from GnomeNpcOverlay's own
     * identical bounding-box-center math rather than reaching into that (rendering-layer) class
     * from here, same small-deliberate-duplication choice ceremony.py's own primitives already
     * make against MinigameContext. Only ever used here to know where to fire the flanking gnome's
     * own vanish spotanim -- GnomeNpcOverlay independently computes the same points, from the same
     * underlying tiles, for its own rendering, so the two always agree. Null if the arena hasn't
     * swapped in yet, or has already been restored. */
    private WorldPoint arenaCenter()
    {
        List<WorldPoint> tiles = plugin.findCeremonyArenaTiles();
        if (tiles.isEmpty()) return null;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (WorldPoint p : tiles)
        {
            minX = Math.min(minX, p.getX());
            maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY());
            maxY = Math.max(maxY, p.getY());
        }
        return new WorldPoint((minX + maxX) / 2, (minY + maxY) / 2, tiles.get(0).getPlane());
    }

    /** One of the two points flanking the ceremony Gnome NPC's own arena-center tile -- index 0
     * (round 1's prop) one tile west, index 1 (round 2's prop) one tile east. Must match
     * GnomeNpcOverlay's own flanking-point math exactly. Null if the arena isn't up right now. */
    private WorldPoint flankingGnomePoint(int index)
    {
        WorldPoint center = arenaCenter();
        if (center == null) return null;
        return index == 0 ? center.dx(-1) : center.dx(1);
    }

    /** CEREMONY_TRANSITION_TO_WINNER's own handling -- the Golden Gnome Awards' own hand-off into
     * the existing, unchanged standings/winner-reveal sequence below. No-ops if nobody's actually
     * seated. */
    public void handleCeremonyTransitionToWinner(boolean catchingUp)
    {
        if (catchingUp) return;
        triggerWinnerRevealSequence();
    }

    /** The existing standings/winner-reveal sequence, unchanged internally -- see this class's own
     * doc for the one thing that DID change (gameOverBanner moved from this sequence's first beat
     * to its last, scheduled from scheduleWinnerReveal's own completion instead of here). */
    private void triggerWinnerRevealSequence()
    {
        List<RosterReducer.RosterEntry> standings = computeFinalStandings();
        if (standings.isEmpty()) return;
        gameOverStandings = standings;
        scheduleWinnerIntro();
    }

    private void scheduleWinnerIntro()
    {
        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.WINNER_INTRO_DURATION_MS, () ->
        {
            winnerIntroBanner.until = System.currentTimeMillis() + RunePartyPlugin.WINNER_INTRO_DURATION_MS;
            plugin.extendTurnEffectGate(winnerIntroBanner.until);

            // Worst to best, stopping once only the top two remain -- e.g. a 4-player game reveals
            // 4th then 3rd, leaving 1st/2nd for the "And the winner is..." showdown. A 2-player
            // game has nothing to reveal here, so this list ends up empty.
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

    /** The winner's own name, held alongside ConfettiOverlay's burst (confettiBanner runs shorter
     * so the confetti finishes settling while the name's still up) and the real level-99 fireworks
     * spotanim on the winner's own actor (WINNER_FIREWORKS_SPOTANIM_ID), then schedules
     * "GAME OVER!" -- the sequence's own true final beat now, not its first. gameOverStandings is
     * sorted winner-first, so index 0 is always the winner. */
    private void scheduleWinnerReveal()
    {
        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.WINNER_REVEAL_DURATION_MS, () ->
        {
            RosterReducer.RosterEntry winner = gameOverStandings.get(0);
            winnerRevealBanner.payload = winner.rsn;
            long now = System.currentTimeMillis();
            winnerRevealBanner.until = now + RunePartyPlugin.WINNER_REVEAL_DURATION_MS;
            confettiBanner.until = now + RunePartyPlugin.CONFETTI_DURATION_MS;
            plugin.triggerSpotAnimOnPlayer(RunePartyPlugin.WINNER_FIREWORKS_SPOTANIM_ID, winner.rsn, RunePartyPlugin.WINNER_FIREWORKS_SPOTANIM_HEIGHT);
            plugin.addChatMessage(winner.rsn + " won Rune Party Showdown!");
            scheduleGameOverFinale();
        });
    }

    /** "GAME OVER!" -- the whole ceremony's own true last word, scheduled behind the winner
     * reveal/confetti's own reservation instead of preceding everything else the way it used to. */
    private void scheduleGameOverFinale()
    {
        gameOverTask = plugin.scheduleAfterTurnEffects(gameOverTask, RunePartyPlugin.GAME_OVER_TITLE_DURATION_MS, () ->
        {
            gameOverBanner.until = System.currentTimeMillis() + RunePartyPlugin.GAME_OVER_TITLE_DURATION_MS;
            plugin.extendTurnEffectGate(gameOverBanner.until);
        });
    }

    public void reset()
    {
        if (gameOverTask != null) { gameOverTask.cancel(false); gameOverTask = null; }
        gameOverStandings = Collections.emptyList();
        ceremonyStarted = false;
        ceremonyIntroRevealed = false;
        arrivalGate.reset();
        ceremonyTitleBanner.reset();
        gnomeLineBanner.reset();
        bonusRoundIndex = 0;
        bonusObjectiveBanner.reset();
        bonusSuspenseBanner.reset();
        bonusWinnerBanner.reset();
        Arrays.fill(flankingGnomeVanishAt, 0);
        winnerIntroBanner.reset();
        placeReveal.reset();
        winnerSuspenseBanner.reset();
        winnerRevealBanner.reset();
        confettiBanner.reset();
        gameOverBanner.reset();
    }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under their original names ----
    public boolean isCeremonyStarted() { return ceremonyStarted; }
    public boolean isCeremonyIntroRevealed() { return ceremonyIntroRevealed; }
    public long getCeremonyTitleBannerUntil() { return ceremonyTitleBanner.until; }
    public long getGnomeLineUntil() { return gnomeLineBanner.until; }
    public String getGnomeLine() { return gnomeLineBanner.payload; }
    public long getBonusObjectiveBannerUntil() { return bonusObjectiveBanner.until; }
    public long getBonusSuspenseBannerUntil() { return bonusSuspenseBanner.until; }
    public int getBonusObjectiveRoundIndex() { return bonusObjectiveBanner.payload != null ? bonusObjectiveBanner.payload.roundIndex : 0; }
    public String getBonusObjectiveDisplayName() { return bonusObjectiveBanner.payload != null ? bonusObjectiveBanner.payload.displayName : null; }
    public String getBonusObjectiveDescription() { return bonusObjectiveBanner.payload != null ? bonusObjectiveBanner.payload.description : null; }
    public long getBonusWinnerBannerUntil() { return bonusWinnerBanner.until; }
    public List<String> getBonusWinners() { return bonusWinnerBanner.payload != null ? bonusWinnerBanner.payload : Collections.emptyList(); }
    public List<RosterReducer.RosterEntry> getGameOverStandings() { return gameOverStandings; }
    public long getGameOverBannerUntil() { return gameOverBanner.until; }
    public long getWinnerIntroBannerUntil() { return winnerIntroBanner.until; }
    public long getPlaceRevealUntil() { return placeReveal.until; }
    public String getPlaceRevealRsn() { return placeReveal.payload != null ? placeReveal.payload.rsn : null; }
    public int getPlaceRevealRank() { return placeReveal.payload != null ? placeReveal.payload.rank : 0; }
    public int getPlaceRevealCoins() { return placeReveal.payload != null ? placeReveal.payload.coins : 0; }
    public int getPlaceRevealGoldenGnomes() { return placeReveal.payload != null ? placeReveal.payload.goldenGnomes : 0; }
    public long getWinnerSuspenseUntil() { return winnerSuspenseBanner.until; }
    public long getWinnerRevealUntil() { return winnerRevealBanner.until; }
    public String getWinnerRsn() { return winnerRevealBanner.payload; }
    public long getConfettiUntil() { return confettiBanner.until; }

    /** Payload for one bonus Golden Gnome round's own objective announcement -- see
     * handleBonusObjectiveAnnounced. roundIndex is 1/2/... within this ceremony, purely for
     * "first"/"second" wording. */
    private static final class BonusObjectivePayload
    {
        final int roundIndex;
        final String displayName;
        final String description;

        BonusObjectivePayload(int roundIndex, String displayName, String description)
        {
            this.roundIndex = roundIndex;
            this.displayName = displayName;
            this.description = description;
        }
    }

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
