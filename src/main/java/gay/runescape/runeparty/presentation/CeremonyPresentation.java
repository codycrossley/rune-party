package gay.runescape.runeparty.presentation;

import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;
import gay.runescape.runeparty.minigames.ArrivalGate;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** End-game ceremony state and choreography, extracted out of RunePartyPlugin -- two chained
 * halves, both server-paced (everyone sees the same thing at the same time):
 * <p>
 * 1. The Golden Gnome Awards: CEREMONY_STARTED (rainbow title, then the persistent "gather in the
 * arena" message) -> the Gnome's own scripted CEREMONY_GNOME_LINE lines -> up to two bonus rounds
 * (CEREMONY_BONUS_OBJECTIVE_ANNOUNCED -> a real GOLDEN_GNOME_WON grant per winner ->
 * CEREMONY_BONUS_WINNER_REVEALED, itself expanded client-side into "The Golden Gnome is awarded
 * to..." suspense -> the actual name(s) -> that round's own flanking Golden Gnome prop vanishing
 * -> the "+1" popup) -> CEREMONY_TRANSITION_TO_WINNER.
 * <p>
 * 2. The existing standings/winner reveal: "Now it's time to see the winner..." -> one "In Nth
 * place..." reveal per eliminated player (worst to best, stopping once only the top two remain) ->
 * "And the winner is..." -> the winner's name plus ConfettiOverlay's burst -> "GAME OVER!" (this
 * sequence's own last beat, not its first).
 * <p>
 * <b>Every one of the beats above is enqueued onto a single ordered sequence (see {@link #enqueue})
 * instead of each one scheduling its own follow-up by hand.</b> This class used to mix three
 * different scheduling idioms -- a chained {@code scheduleAfterTurnEffects} handle for the title and
 * the whole winner-reveal half, a bespoke "next reveal at" timestamp for the Gnome's own lines, and
 * no gating at all for the bonus rounds -- on the assumption that ceremony.py's own real, generous
 * sleep between server-side inserts would always be enough margin. Two real playtests proved that
 * wrong from two different angles: the final mini-game's own score/rewards/round-complete recap can
 * run long enough to eat into the fixed gap before the Gnome's first line (which used no gating at
 * all, back then), and -- the harder bug to see -- once that gap-eating delay pushes ANY beat later
 * than the server assumed, every later beat with no gating of its own (the bonus rounds) would still
 * apply immediately the instant its own event landed, overlapping whatever earlier, now-delayed beat
 * was still on screen. Patching each symptom as it was found (three different one-off fixes for
 * three different beats) was never going to end, because the actual bug was structural: nothing
 * tracked "is the PREVIOUS beat, whatever it was, actually done yet" as one single fact every beat
 * could depend on.
 * <p>
 * A single FIFO sequence fixes the whole class of bug at once, by construction: every beat computes
 * its own reveal time as strictly after both (a) the shared turnEffectGateUntil this class doesn't
 * own (the just-ended mini-game's own recap, relevant only to the very first beat) and (b) whatever
 * THIS class's own last-enqueued beat is still occupying. Two beats can never show at once, no beat
 * can ever be skipped or overwritten by a later one arriving early, and every "handleX" method
 * becomes a flat, linear list of `enqueue(duration, revealSomething)` calls in the exact order the
 * ceremony is supposed to play -- the code now reads as the sequence itself, not as scheduling
 * machinery you have to trace to find the sequence. See enqueue's own doc for the mechanism, and
 * ceremonyGeneration's own doc for how a mid-ceremony reset (a force-ended game) invalidates
 * whatever's still pending without needing to track/cancel every individual scheduled task.
 * <p>
 * Real state that a catching-up (reconnecting) client needs regardless of any cosmetic reveal --
 * ceremonyStarted, flankingGnomeVanishAt, bonusRoundIndex's own snapshot inside each banner payload
 * -- is still applied immediately and unconditionally, exactly where it always was; only the
 * cosmetic reveal itself (arming a TimedBanner, playing a spotanim) goes through enqueue, and every
 * handler still short-circuits entirely on catchingUp==true, same as before (a reconnecting client
 * has already missed every one-shot cosmetic beat, there's nothing left to catch it up on).
 * <p>
 * Exposes handleCeremonyStarted/handleGnomeLine/handleBonusObjectiveAnnounced/
 * handleBonusWinnerRevealed/handleCeremonyTransitionToWinner for RunePartyPlugin's own event
 * handling to call, and clears itself via reset(); RunePartyPlugin still exposes every getter under
 * its original name, just delegating here. */
public final class CeremonyPresentation
{
    private final RunePartyPlugin plugin;

    // ---- the single sequence every ceremony beat is enqueued onto -- see enqueue's own doc ----
    // The "not before this" timestamp for the NEXT enqueued beat -- 0 means the sequence hasn't
    // started yet (nothing's been enqueued this ceremony). Only ever moves forward.
    private volatile long nextBeatAt = 0;
    // Bumped by reset() -- every enqueue() closure captures the generation it was scheduled under
    // and checks it's still current before actually applying its own effect, so a mid-ceremony
    // reset (the host force-ending the game) can't have a stale, already-scheduled beat from the
    // OLD ceremony mutate banner state after the fact. Simpler and more robust than trying to track
    // and cancel every individual ScheduledFuture the way a single gameOverTask handle used to
    // (and could only ever ward off ONE pending task at a time, which stopped being enough the
    // moment more than one beat could be in flight at once).
    private volatile int ceremonyGeneration = 0;

    private volatile List<RosterReducer.RosterEntry> gameOverStandings = Collections.emptyList();

    // ---- Golden Gnome Awards (server-paced, everyone sees it) ----
    private volatile boolean ceremonyStarted = false; // real state, set immediately regardless of catch-up
    // Flips true once the cosmetic title banner has actually been enqueued AND its own turn in the
    // sequence has arrived (i.e. once the just-ended mini-game's own recap has genuinely finished)
    // -- the arena floor outline and the Gnome NPC/flanking props wait on THIS instead of the
    // immediate ceremonyStarted, so none of them can render before that recap is actually done.
    // Same "real state is immediate, the visual reveal is deliberately held back" split TileOverlay's
    // own doc describes for Rainbow Rush's isMinigameSelectionRevealed(). Those three are all
    // world-space elements with no shared-screen-slot conflict against the title banner, so they're
    // fine revealing the moment it starts -- unlike the gather message below, which shares the
    // title's own screen-center position and needs to wait for it to actually finish instead.
    private volatile boolean ceremonyIntroRevealed = false;
    // Flips true once the title's own reveal has both started AND held the screen for its own full
    // CEREMONY_TITLE_BANNER_DURATION_MS (a second, immediately-following, zero-duration beat --
    // see handleCeremonyStarted) -- renderCeremonyGatherMessage waits on THIS, not
    // ceremonyIntroRevealed, since the persistent "gather in the arena" message renders in the
    // exact same screen-center slot the rainbow title just occupied. Without this split, a real
    // playtest found the two rendering at once.
    private volatile boolean ceremonyGatherMessageRevealed = false;
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
    private final TimedBanner<Void> gameOverBanner = new TimedBanner<>(); // this sequence's own LAST beat, not its first

    public CeremonyPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.arrivalGate = new ArrivalGate(plugin, "the Golden Gnome Awards", "the ceremony arena",
            (self, gid, token) -> plugin.apiClient.confirmCeremonyArrival(gid, self, token));
    }

    /** Appends one ceremony "beat" to the single sequence every beat in this class goes through --
     * {@code reveal} runs the instant it's this beat's own turn (arming whatever banner/spotanim/
     * flag it owns), {@code durationMs} is how long it then holds the screen before the NEXT
     * enqueued beat gets its own turn. Every call computes its own slot as strictly after both (a)
     * the shared turnEffectGateUntil (RunePartyPlugin) this class doesn't own -- only ever relevant
     * to the very first beat of a ceremony, since this method itself keeps that same gate extended
     * to cover every beat after that -- and (b) nextBeatAt, this class's own record of when its
     * last-enqueued beat's own slot ends. Because nextBeatAt only ever moves forward and every
     * caller just appends, this is a plain FIFO queue: a beat can never be skipped (unlike the old
     * per-beat "cancel whatever was previously scheduled" chaining, correct only when a single
     * producer schedules each stage from inside the previous stage's own already-fired callback,
     * wrong the instant more than one server event can land before an earlier one's own reveal has
     * fired) and two beats can never show at once (unlike the old "no gating, apply immediately"
     * shape the bonus rounds used to use, correct only as long as nothing upstream was ever
     * delayed -- which stopped being true the moment the Gnome's own lines needed real queueing).
     * <p>
     * Captures the current {@link #ceremonyGeneration} so a reset() that runs after this was
     * enqueued but before it fires (the host force-ending the game mid-ceremony) makes it a no-op
     * instead of letting a stale beat from an old ceremony mutate banner state after the fact. */
    private void enqueue(long durationMs, Runnable reveal)
    {
        int generation = ceremonyGeneration;
        long now = System.currentTimeMillis();
        long gateUntil = plugin.getTurnEffectGateUntil();
        long earliestFromSharedGate = gateUntil > now ? gateUntil + RunePartyPlugin.POST_TURN_EFFECT_GRACE_MS : now;
        long revealAt = Math.max(earliestFromSharedGate, nextBeatAt);
        nextBeatAt = revealAt + durationMs;
        plugin.extendTurnEffectGate(nextBeatAt);

        plugin.uiTimerExec.schedule(() ->
        {
            if (generation == ceremonyGeneration) reveal.run();
        }, Math.max(0, revealAt - now), TimeUnit.MILLISECONDS);
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
     * cosmetic-only, enqueued as this ceremony's very first beat -- see enqueue's own doc for how
     * that ends up waiting behind the final mini-game's own still-playing recap. The gather
     * message's own reveal is a second, zero-duration beat enqueued right behind it, so
     * ceremonyGatherMessageRevealed only flips once the title has actually finished holding the
     * screen, not the instant it appears. */
    public void handleCeremonyStarted(boolean catchingUp)
    {
        ceremonyStarted = true;
        if (catchingUp) return;

        enqueue(RunePartyPlugin.CEREMONY_TITLE_BANNER_DURATION_MS, () ->
        {
            ceremonyTitleBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_TITLE_BANNER_DURATION_MS;
            ceremonyIntroRevealed = true;
        });
        enqueue(0, () -> ceremonyGatherMessageRevealed = true);
    }

    /** One of the Gnome's own scripted lines -- enqueued behind whatever's already queued (the
     * title/gather beats above, most likely, for this ceremony's very first line) exactly like
     * every other beat in this class. */
    public void handleGnomeLine(String line, boolean catchingUp)
    {
        if (catchingUp) return;
        enqueue(RunePartyPlugin.CEREMONY_GNOME_LINE_DURATION_MS, () ->
        {
            gnomeLineBanner.payload = line;
            gnomeLineBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_GNOME_LINE_DURATION_MS;
        });
    }

    /** One bonus round's own objective announcement -- bonusRoundIndex is incremented and the
     * payload (which snapshots that round index for "first"/"second" wording) built immediately,
     * synchronously, the instant this event lands -- only the actual banner reveal is deferred via
     * enqueue. This matters: by the time this beat's own delayed turn finally arrives, a LATER
     * round's own CEREMONY_BONUS_OBJECTIVE_ANNOUNCED could already have landed and incremented
     * bonusRoundIndex again, so reading it fresh inside the enqueued reveal itself would risk
     * showing the wrong round's own ordinal. Capturing it into the payload up front avoids that
     * regardless of how delayed this beat's own reveal ends up being. */
    public void handleBonusObjectiveAnnounced(String displayName, String description, boolean catchingUp)
    {
        if (catchingUp) return;
        bonusRoundIndex++;
        BonusObjectivePayload payload = new BonusObjectivePayload(bonusRoundIndex, displayName, description);

        enqueue(RunePartyPlugin.CEREMONY_BONUS_ANNOUNCE_DURATION_MS, () ->
        {
            bonusObjectiveBanner.payload = payload;
            bonusObjectiveBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_BONUS_ANNOUNCE_DURATION_MS;
        });
    }

    /** The bonus round's own winner reveal -- expanded into two enqueued beats rather than shown
     * the instant this event lands: a "The Golden Gnome is awarded to..." suspense cliffhanger,
     * then (once THAT beat's own turn ends) the actual name(s) -- which is also where this round's
     * own flanking Golden Gnome prop plays its vanish spotanim, see triggerFlankingGnomeVanish's
     * own doc for why the "+1" popup itself waits for that animation to actually finish rather than
     * being a third top-level beat of its own. roundIndex is captured here, synchronously, for the
     * identical reason handleBonusObjectiveAnnounced's own payload capture is -- by the time the
     * second beat's own reveal actually fires, a later round's own bonusRoundIndex increment could
     * already have landed. */
    public void handleBonusWinnerRevealed(List<String> winners, boolean catchingUp)
    {
        if (catchingUp) return;
        int roundIndex = bonusRoundIndex;

        enqueue(RunePartyPlugin.CEREMONY_BONUS_SUSPENSE_DURATION_MS, () ->
            bonusSuspenseBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_BONUS_SUSPENSE_DURATION_MS);

        enqueue(RunePartyPlugin.CEREMONY_BONUS_REVEAL_DURATION_MS, () ->
        {
            bonusWinnerBanner.payload = winners;
            bonusWinnerBanner.until = System.currentTimeMillis() + RunePartyPlugin.CEREMONY_BONUS_REVEAL_DURATION_MS;
            triggerFlankingGnomeVanish(roundIndex, winners);
        });
    }

    /** Plays this round's own flanking Golden Gnome prop's vanish spotanim -- the exact same
     * GOLDEN_GNOME_MOVE_SPOTANIM_ID the board's own relocating Golden Gnome already uses (see
     * GoldenGnomePresentation#apply's own GOLDEN_GNOME_MOVED handling) -- but with no arrival half:
     * this prop is gone for good, matching the user's own "it just disappears" framing. Real vanish
     * state (flankingGnomeVanishAt) is set immediately so GnomeNpcOverlay stops rendering it after
     * GOLDEN_GNOME_MOVE_VANISH_DELAY_MS, same delay the board's own gnome uses to let the spotanim
     * visually cover the model actually disappearing.
     * <p>
     * The "+1 Golden Gnome" popup is deliberately NOT its own top-level enqueue()'d beat -- unlike
     * every other multi-part beat in this class, it isn't the ceremony's own NEXT thing to show,
     * it's a delayed side effect happening WHILE the "names" beat above is still holding the
     * screen (the popup floats over the still-visible winner names, it doesn't replace them). A
     * plain nested plugin.uiTimerExec call, scoped to this specific choreography and re-checked
     * against ceremonyGeneration the same way enqueue's own closures are, is the right tool here --
     * same reasoning this used before, just now guarded against a mid-choreography reset too. */
    private void triggerFlankingGnomeVanish(int roundIndex, List<String> winners)
    {
        int index = roundIndex - 1;
        if (index < 0 || index >= flankingGnomeVanishAt.length) return;

        WorldPoint point = flankingGnomePoint(index);
        if (point != null)
        {
            plugin.triggerSpotAnimAtWorldPoint(RunePartyPlugin.GOLDEN_GNOME_MOVE_SPOTANIM_ID, point);
        }
        flankingGnomeVanishAt[index] = System.currentTimeMillis();

        int generation = ceremonyGeneration;
        plugin.uiTimerExec.schedule(() ->
        {
            if (generation != ceremonyGeneration) return;
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
     * the existing standings/winner-reveal sequence, now just more beats enqueued onto the same
     * single sequence everything else in this class uses (see enqueue's own doc) instead of its
     * own separate gameOverTask-chained recursion. No-ops if nobody's actually seated. Each
     * `entry`/`payload` below is a fresh binding per loop iteration (Java's enhanced for-loop, not
     * a mutated shared index), so every place-reveal's own lambda safely captures its own entry
     * regardless of how many beats end up queued ahead of it. */
    public void handleCeremonyTransitionToWinner(boolean catchingUp)
    {
        if (catchingUp) return;

        List<RosterReducer.RosterEntry> standings = computeFinalStandings();
        if (standings.isEmpty()) return;
        gameOverStandings = standings;

        enqueue(RunePartyPlugin.WINNER_INTRO_DURATION_MS, () ->
            winnerIntroBanner.until = System.currentTimeMillis() + RunePartyPlugin.WINNER_INTRO_DURATION_MS);

        // Worst to best, stopping once only the top two remain -- e.g. a 4-player game reveals 4th
        // then 3rd, leaving 1st/2nd for the "And the winner is..." showdown. A 2-player game has
        // nothing to reveal here, so this loop simply enqueues nothing.
        for (int i = standings.size() - 1; i >= 2; i--)
        {
            RosterReducer.RosterEntry entry = standings.get(i);
            PlaceRevealPayload payload = new PlaceRevealPayload(entry.rsn, i + 1, entry.coins, entry.goldenGnomeCount);
            enqueue(RunePartyPlugin.PLACE_REVEAL_DURATION_MS, () ->
            {
                placeReveal.payload = payload;
                placeReveal.until = System.currentTimeMillis() + RunePartyPlugin.PLACE_REVEAL_DURATION_MS;
            });
        }

        enqueue(RunePartyPlugin.WINNER_SUSPENSE_DURATION_MS, () ->
            winnerSuspenseBanner.until = System.currentTimeMillis() + RunePartyPlugin.WINNER_SUSPENSE_DURATION_MS);

        // gameOverStandings is sorted winner-first, so index 0 is always the winner. Held alongside
        // ConfettiOverlay's burst (confettiBanner runs shorter so the confetti finishes settling
        // while the name's still up) and the real level-99 fireworks spotanim on the winner's own
        // actor.
        RosterReducer.RosterEntry winner = standings.get(0);
        enqueue(RunePartyPlugin.WINNER_REVEAL_DURATION_MS, () ->
        {
            long now = System.currentTimeMillis();
            winnerRevealBanner.payload = winner.rsn;
            winnerRevealBanner.until = now + RunePartyPlugin.WINNER_REVEAL_DURATION_MS;
            confettiBanner.until = now + RunePartyPlugin.CONFETTI_DURATION_MS;
            plugin.triggerSpotAnimOnPlayer(RunePartyPlugin.WINNER_FIREWORKS_SPOTANIM_ID, winner.rsn, RunePartyPlugin.WINNER_FIREWORKS_SPOTANIM_HEIGHT);
            plugin.addChatMessage(winner.rsn + " won Rune Party Showdown!");
        });

        // "GAME OVER!" -- the whole ceremony's own true last word.
        enqueue(RunePartyPlugin.GAME_OVER_TITLE_DURATION_MS, () ->
            gameOverBanner.until = System.currentTimeMillis() + RunePartyPlugin.GAME_OVER_TITLE_DURATION_MS);
    }

    public void reset()
    {
        ceremonyGeneration++; // invalidate every still-pending enqueue()'d/nested closure -- see that field's own doc
        nextBeatAt = 0;
        gameOverStandings = Collections.emptyList();
        ceremonyStarted = false;
        ceremonyIntroRevealed = false;
        ceremonyGatherMessageRevealed = false;
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
    public boolean isCeremonyGatherMessageRevealed() { return ceremonyGatherMessageRevealed; }
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

    /** Payload for one place-reveal step in the end-game ceremony -- see
     * handleCeremonyTransitionToWinner. */
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
