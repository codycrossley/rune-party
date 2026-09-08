package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Dance, Dance, RuneScape's own client-side state (a purely client-local reflex game -- see the
 * class doc on {@link DanceDanceRuneScapeMinigame}). Once every seated player's reached the
 * board-swapped {@code DDR_CENTER_TILE} (server-gated, same "wait for everyone's reported
 * position" mechanic Arena/Turf Wars/Sandwich Rush/Jaddy/Hot Potato already use -- no client code
 * needed for that half), every client plays through the exact same fixed {@link #SEQUENCES
 * sequence} of {@link Note}s exactly once -- the round's own duration is derived directly from
 * that sequence's own total tick length (see {@link #getEndsAt()}), not a separate fixed timer, so
 * the round always runs exactly as long as it takes to play the whole thing through once, no more
 * and no less. The server has no visibility into which sequence was picked or how long it runs
 * (that's all deliberately client-only data -- see this class's own doc further down), so
 * {@link #onRoundBegin()} reports the chosen duration to it once, via
 * ApiClient#reportDanceDanceRuneScapeRoundDuration -- a single one-shot push at round start, same
 * shape as the final tally's own one-shot submit at round end, not an ongoing round-trip. A note
 * is active for a fixed span of game ticks starting at its own {@code startTick} -- two notes can
 * share a tick (a "chord", both tiles lit at once) or simply overlap partway (one note's tail
 * still lit while the next one's already begun), so a player has to notice which tile lit up and
 * step onto it sometime during its own window, not just react to a single spotlight moving one
 * step at a time. Standing on an active note's own tile scores it once. Nothing about *which*
 * tile is highlighted or *when* is shared with the server or any other client -- every client
 * reads the same hardcoded sequence data and picks the same one for this game independently, with
 * no round-trip involved for that part; only the round's overall duration and the final tally are
 * ever sent, once each. */
public final class DanceDanceRuneScapePresentation implements MinigamePresentationFeature
{
    /** Which of the four tiles adjacent to the anchor is currently highlighted. NORTH/SOUTH are
     * +1/-1 on the anchor's own y; EAST/WEST are +1/-1 on its own x -- same axis convention every
     * other WorldPoint offset in this codebase uses. */
    public enum Direction
    {
        NORTH, SOUTH, EAST, WEST
    }

    /** One tile's own lit window within a sequence, in raw game ticks (600ms each) -- active for
     * exactly {@code ticks} ticks starting at {@code startTick}, i.e. the half-open interval
     * {@code [startTick, startTick + ticks)}. Two notes whose intervals overlap (same startTick,
     * or simply staggered) are both lit at once, on purpose (see this class's own doc). Most
     * sequence data is authored via the {@link #beat} factory for readability (a "beat" is just a
     * fixed-size group of ticks, see {@link #TICKS_PER_BEAT}), but a note can also be given an
     * exact tick span directly -- needed for anything shorter or more precisely timed than a
     * whole beat, e.g. a brief four-way "get 'em all" moment. */
    private static final class Note
    {
        final Direction direction;
        final int startTick;
        final int ticks;

        Note(Direction direction, int startTick, int ticks)
        {
            this.direction = direction;
            this.startTick = startTick;
            this.ticks = ticks;
        }

        boolean activeAt(int tickInLoop)
        {
            return tickInLoop >= startTick && tickInLoop < startTick + ticks;
        }
    }

    /** How many game ticks (600ms each) make up one "beat" for the {@link #beat} authoring
     * helper -- the one knob to turn for the overall tempo/pace of anything authored in beats.
     * Notes authored directly in ticks (see {@link Note}) aren't affected by this at all, which is
     * exactly why they exist: a note's minimum possible resolution is one tick regardless of
     * tempo. */
    private static final int TICKS_PER_BEAT = 2;

    private static Note beat(Direction direction, int startBeat, int beats)
    {
        return new Note(direction, startBeat * TICKS_PER_BEAT, beats * TICKS_PER_BEAT);
    }

    /** One real OSRS game tick, in wall-clock milliseconds -- used only to convert a sequence's
     * own tick length into the round's real duration (see onRoundBegin/getEndsAt), not for
     * anything tick-counting related (that's all done in raw ticks already). */
    private static final long TICK_MILLIS = 600;

    // A handful of fixed sequences every client reads verbatim -- see pickSequence for how one
    // gets chosen for a given game, deterministically and identically on every client with no
    // server round-trip. Add more here as they're designed; nothing else needs to change to pick
    // them up, and there's no requirement that they all be the same length (each one's own total
    // tick length becomes that round's real duration -- see onRoundBegin/getEndsAt -- and the
    // server is told that value once, live, rather than needing to know it up front, so sequences
    // can vary freely).
    //
    // SEQUENCE_1 ramps up in three stages: a warm-up of plain single-tile beats (with one
    // overlap and one three-way chord thrown in early), a quickening middle stretch of
    // rapid-fire single beats and alternating N/S <-> E/W chords, then a "lightning round" climax
    // -- all four tiles snap on together for just 3 raw ticks (shorter than a single beat), which
    // only pays off if a player was already standing on one of them the instant it lit, since
    // there isn't time to react and move once it appears -- followed by a longer four-way finale
    // chord as a breather before the loop repeats.
    private static final Note[] SEQUENCE_1 =
    {
        // Warm-up: one tile at a time, slow and readable, with an early overlap and chord so the
        // pattern doesn't feel purely mechanical from the first beat.
        beat(Direction.NORTH, 0, 3),
        beat(Direction.EAST, 3, 3),
        beat(Direction.SOUTH, 6, 3),
        beat(Direction.WEST, 9, 3),
        beat(Direction.NORTH, 12, 3),
        beat(Direction.WEST, 13, 3),           // overlaps NORTH's own tail

        // Picking up the pace: shorter single beats, back to back.
        beat(Direction.SOUTH, 16, 2),
        beat(Direction.EAST, 18, 2),
        beat(Direction.SOUTH, 20, 2),
        beat(Direction.EAST, 21, 2),            // overlaps SOUTH's own tail

        // First real chord -- three tiles at once.
        beat(Direction.SOUTH, 23, 4),
        beat(Direction.NORTH, 23, 4),
        beat(Direction.EAST, 23, 4),

        // A quick rotating sweep, one beat apiece -- visually a single "spotlight" spinning
        // around the four tiles rather than jumping randomly.
        beat(Direction.WEST, 27, 1),
        beat(Direction.NORTH, 28, 1),
        beat(Direction.EAST, 29, 1),
        beat(Direction.SOUTH, 30, 1),
        beat(Direction.WEST, 31, 1),
        beat(Direction.NORTH, 32, 1),

        // Alternating opposite-pair chords, one beat each -- N+S, then E+W, twice through. Fast
        // and symmetric, forces a player to hop between two tiles on the beat rather than one.
        beat(Direction.NORTH, 33, 1), beat(Direction.SOUTH, 33, 1),
        beat(Direction.EAST, 34, 1), beat(Direction.WEST, 34, 1),
        beat(Direction.NORTH, 35, 1), beat(Direction.SOUTH, 35, 1),
        beat(Direction.EAST, 36, 1), beat(Direction.WEST, 36, 1),

        // Lightning round: all four tiles for exactly 3 raw ticks (beat 37 == tick 74) -- shorter
        // than a single beat, so the only way to score it is to already be standing on one of the
        // four tiles the instant it lights, not to react and move once it does.
        new Note(Direction.NORTH, 37 * TICKS_PER_BEAT, 3),
        new Note(Direction.SOUTH, 37 * TICKS_PER_BEAT, 3),
        new Note(Direction.EAST, 37 * TICKS_PER_BEAT, 3),
        new Note(Direction.WEST, 37 * TICKS_PER_BEAT, 3),

        // A longer four-way finale chord as a breather/payoff before the sequence loops.
        beat(Direction.NORTH, 39, 2),
        beat(Direction.SOUTH, 39, 2),
        beat(Direction.EAST, 39, 2),
        beat(Direction.WEST, 39, 2),
    };

    // SEQUENCE_2, "Mirror & Sweep": opens with each direction hit solo, then its own opposite
    // right after (N then S, E then W -- a "mirror"), moves into the same two pairs as actual
    // chords, then a there-and-back rotation (clockwise a full turn, then straight back
    // counter-clockwise -- a figure-eight rather than a one-way spin), a three-way chord that
    // leaves out EAST this time (SEQUENCE_1's own left out WEST), and closes with two all-four
    // flashes separated by a few ticks of total darkness -- the second flash shorter than the
    // first, so the pause itself becomes part of the challenge (don't relax after the first one).
    private static final Note[] SEQUENCE_2 =
    {
        // Solo, then its own mirror.
        beat(Direction.NORTH, 0, 3),
        beat(Direction.SOUTH, 3, 3),
        beat(Direction.EAST, 6, 3),
        beat(Direction.WEST, 9, 3),

        // Same two pairs, now as chords, quicker.
        beat(Direction.NORTH, 12, 2), beat(Direction.SOUTH, 12, 2),
        beat(Direction.EAST, 14, 2), beat(Direction.WEST, 14, 2),
        beat(Direction.NORTH, 16, 2), beat(Direction.SOUTH, 16, 2),
        beat(Direction.EAST, 18, 2), beat(Direction.WEST, 18, 2),

        // Figure-eight: a full clockwise turn, then straight back the way it came.
        beat(Direction.NORTH, 20, 1),
        beat(Direction.EAST, 21, 1),
        beat(Direction.SOUTH, 22, 1),
        beat(Direction.WEST, 23, 1),
        beat(Direction.SOUTH, 24, 1),
        beat(Direction.EAST, 25, 1),
        beat(Direction.NORTH, 26, 1),

        // Three-way chord, EAST left out this time.
        beat(Direction.SOUTH, 28, 3),
        beat(Direction.WEST, 28, 3),
        beat(Direction.NORTH, 28, 3),

        // Two all-four flashes with a few ticks of total darkness between them -- the second
        // flash shorter (2 ticks) than the first (3), so a player who relaxes after surviving the
        // first one gets caught out by the second.
        new Note(Direction.NORTH, 31 * TICKS_PER_BEAT, 3),
        new Note(Direction.SOUTH, 31 * TICKS_PER_BEAT, 3),
        new Note(Direction.EAST, 31 * TICKS_PER_BEAT, 3),
        new Note(Direction.WEST, 31 * TICKS_PER_BEAT, 3),
        new Note(Direction.NORTH, 34 * TICKS_PER_BEAT, 2),
        new Note(Direction.SOUTH, 34 * TICKS_PER_BEAT, 2),
        new Note(Direction.EAST, 34 * TICKS_PER_BEAT, 2),
        new Note(Direction.WEST, 34 * TICKS_PER_BEAT, 2),

        // Finale chord.
        beat(Direction.NORTH, 35, 2),
        beat(Direction.SOUTH, 35, 2),
        beat(Direction.EAST, 35, 2),
        beat(Direction.WEST, 35, 2),
    };

    // SEQUENCE_3, "Stutter": deliberately the shortest and densest of the four -- no slow warm-up
    // at all. Opens with lone single-tick beats separated by full beats of silence (no ramp-up
    // time to read a rhythm, just react), then a chain of four notes each overlapping the next by
    // a full beat (denser than SEQUENCE_1/2's brief one-tick overlaps), a rapid-fire two-chord
    // "call and response" (E+W immediately followed by N+S, no gap), and the single fastest
    // all-four flash across every sequence -- just 2 raw ticks.
    private static final Note[] SEQUENCE_3 =
    {
        // Lone beats with a full beat of silence between each -- nothing to read a rhythm off of.
        beat(Direction.NORTH, 0, 1),
        beat(Direction.EAST, 2, 1),
        beat(Direction.SOUTH, 4, 1),
        beat(Direction.WEST, 6, 1),

        // A chain of heavy overlaps -- each note's own tail overlaps the next note's first half.
        beat(Direction.NORTH, 8, 2),
        beat(Direction.EAST, 9, 2),
        beat(Direction.SOUTH, 10, 2),
        beat(Direction.WEST, 11, 2),

        // Call and response -- one chord immediately followed by the other, no gap.
        beat(Direction.EAST, 13, 1), beat(Direction.WEST, 13, 1),
        beat(Direction.NORTH, 14, 1), beat(Direction.SOUTH, 14, 1),

        // The fastest all-four flash of any sequence -- 2 raw ticks, shorter even than
        // SEQUENCE_1/2's own 3-tick moments.
        new Note(Direction.NORTH, 15 * TICKS_PER_BEAT, 2),
        new Note(Direction.SOUTH, 15 * TICKS_PER_BEAT, 2),
        new Note(Direction.EAST, 15 * TICKS_PER_BEAT, 2),
        new Note(Direction.WEST, 15 * TICKS_PER_BEAT, 2),

        // Finale chord.
        beat(Direction.NORTH, 16, 3),
        beat(Direction.SOUTH, 16, 3),
        beat(Direction.EAST, 16, 3),
        beat(Direction.WEST, 16, 3),
    };

    // SEQUENCE_4, "Marathon": the longest of the four -- two full laps of solo beats before
    // anything harder starts, a double rotation sweep, then TWO separate all-four flashes (3
    // ticks, then a faster 2 ticks later on) with real content in between rather than back to
    // back, a three-way chord leaving out NORTH this time, and the longest finale hold of any
    // sequence as the payoff for making it all the way through.
    private static final Note[] SEQUENCE_4 =
    {
        // Two full laps, solo beats.
        beat(Direction.NORTH, 0, 3),
        beat(Direction.EAST, 3, 3),
        beat(Direction.SOUTH, 6, 3),
        beat(Direction.WEST, 9, 3),
        beat(Direction.NORTH, 12, 3),
        beat(Direction.EAST, 15, 3),
        beat(Direction.SOUTH, 18, 3),
        beat(Direction.WEST, 21, 3),

        // A double rotation sweep, one beat apiece.
        beat(Direction.NORTH, 24, 1),
        beat(Direction.EAST, 25, 1),
        beat(Direction.SOUTH, 26, 1),
        beat(Direction.WEST, 27, 1),
        beat(Direction.NORTH, 28, 1),
        beat(Direction.EAST, 29, 1),
        beat(Direction.SOUTH, 30, 1),
        beat(Direction.WEST, 31, 1),

        // First all-four flash -- 3 ticks, same length as SEQUENCE_1/2's own. Followed by one
        // extra dark tick (a deliberate held breath) to land back on an even beat boundary.
        new Note(Direction.NORTH, 64, 3),
        new Note(Direction.SOUTH, 64, 3),
        new Note(Direction.EAST, 64, 3),
        new Note(Direction.WEST, 64, 3),

        // Alternating pair chords, twice through.
        beat(Direction.NORTH, 34, 1), beat(Direction.SOUTH, 34, 1),
        beat(Direction.EAST, 35, 1), beat(Direction.WEST, 35, 1),
        beat(Direction.NORTH, 36, 1), beat(Direction.SOUTH, 36, 1),
        beat(Direction.EAST, 37, 1), beat(Direction.WEST, 37, 1),

        // Second all-four flash -- faster than the first, just 2 ticks, proving the marathon
        // keeps testing you right up to the end.
        new Note(Direction.NORTH, 76, 2),
        new Note(Direction.SOUTH, 76, 2),
        new Note(Direction.EAST, 76, 2),
        new Note(Direction.WEST, 76, 2),

        // Three-way chord, NORTH left out this time.
        beat(Direction.EAST, 39, 3),
        beat(Direction.WEST, 39, 3),
        beat(Direction.SOUTH, 39, 3),

        // The longest finale hold of any sequence -- 3 beats.
        beat(Direction.NORTH, 42, 3),
        beat(Direction.SOUTH, 42, 3),
        beat(Direction.EAST, 42, 3),
        beat(Direction.WEST, 42, 3),
    };

    private static final Note[][] SEQUENCES = { SEQUENCE_1, SEQUENCE_2, SEQUENCE_3, SEQUENCE_4 };

    private final RunePartyPlugin plugin;

    private volatile long roundStartAt = 0;
    private volatile Note[] sequence = null;
    private volatile int sequenceTicks = 0; // total loop length, in ticks -- max(startTick + ticks) across the sequence
    private volatile long roundDurationMs = 0; // sequenceTicks * TICK_MILLIS -- see getEndsAt()
    private volatile int tickCount = 0; // ticks since this round's own sequence started, wraps via sequenceTicks
    private volatile int lastLoopNumber = -1;
    // One flag per note in `sequence`, parallel by index -- whether that note's already been
    // scored during the sequence's current loop iteration (see onTick). Cleared in bulk every
    // time the loop wraps back to its own start, so a repeating sequence keeps paying out fresh
    // points on every lap, not just the first.
    private volatile boolean[] scoredThisLoop = new boolean[0];
    // When each direction was last captured (System.currentTimeMillis()), for the overlay's own
    // brief flash effect -- see FLASH_DURATION_MS. Rebuilt (copy-on-write, like highlighted below)
    // only on the ticks where a capture actually happens, not every tick.
    private volatile Map<Direction, Long> flashStartAtMillis = Collections.emptyMap();
    private volatile Set<Direction> highlighted = Collections.emptySet();
    private volatile int score = 0;
    private volatile boolean submitted = false;

    public DanceDanceRuneScapePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    /** Which of the fixed {@link #SEQUENCES} this game plays -- derived from this game's own id
     * plus the round number, so every client (all of whom already agree on both) lands on the
     * exact same index with zero server round-trip, and so a game that hits Dance, Dance,
     * RuneScape more than once doesn't necessarily replay the same sequence every time once more
     * than one exists. */
    private Note[] pickSequence()
    {
        int index = Math.floorMod(Objects.hash(plugin.gameId, plugin.getCurrentRound()), SEQUENCES.length);
        return SEQUENCES[index];
    }

    private static int totalTicks(Note[] notes)
    {
        int total = 0;
        for (Note note : notes) total = Math.max(total, note.startTick + note.ticks);
        return total;
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while this mini-game is
     * active. Advances the sequence, checks the local player's own position against every
     * currently-active note's own tile, and -- once the round's own fixed duration has elapsed --
     * fires the one-shot final submission. Everything here is purely local; nothing is read from
     * or written to the server mid-round.
     * <p>
     * A no-op until {@link #onRoundBegin()} has actually stamped {@code roundStartAt} -- this
     * mini-game's own round doesn't begin the instant MINIGAME_STARTED lands, only once every
     * seated player's reported position has satisfied the server's own gather gate (same
     * arrival-gated shape Arena/Turf Wars/Sandwich Rush/Jaddy/Hot Potato already use), so without
     * this guard a player still walking toward the center tile during that gather phase could
     * accidentally rack up points on a tile lighting up before the round's really started. */
    public void onTick(Player selfPlayer)
    {
        if (roundStartAt == 0 || sequence == null || sequenceTicks == 0) return;

        int loopNumber = tickCount / sequenceTicks;
        int tickInLoop = tickCount % sequenceTicks;
        tickCount++;

        if (loopNumber != lastLoopNumber)
        {
            Arrays.fill(scoredThisLoop, false);
            lastLoopNumber = loopNumber;
        }

        // Scoring runs before the highlighted set below is (re)computed, so a note captured this
        // very tick already reads as un-lit in the same frame -- "step on it, it flashes and goes
        // dark" rather than staying lit for the rest of its window after it's already claimed.
        Map<Direction, Long> newFlashes = null;
        if (selfPlayer != null)
        {
            WorldPoint anchor = plugin.findDanceDanceRuneScapeTilePoint();
            WorldPoint playerPos = selfPlayer.getWorldLocation();
            if (anchor != null && playerPos != null)
            {
                for (int i = 0; i < sequence.length; i++)
                {
                    if (scoredThisLoop[i] || !sequence[i].activeAt(tickInLoop)) continue;
                    if (tileFor(anchor, sequence[i].direction).equals(playerPos))
                    {
                        score++;
                        scoredThisLoop[i] = true;
                        if (newFlashes == null)
                        {
                            // Not `new EnumMap<>(flashStartAtMillis)` -- that constructor throws
                            // IllegalArgumentException when given a non-EnumMap that's empty (it
                            // can't infer the key type from zero entries), which flashStartAtMillis
                            // always is at the very first capture (Collections.emptyMap()).
                            newFlashes = new EnumMap<>(Direction.class);
                            newFlashes.putAll(flashStartAtMillis);
                        }
                        newFlashes.put(sequence[i].direction, System.currentTimeMillis());
                    }
                }
            }
        }
        if (newFlashes != null) flashStartAtMillis = Collections.unmodifiableMap(newFlashes);

        // A direction only counts as "highlighted" (scoreable, rendered lit) while it has at
        // least one active note that hasn't already been captured this loop -- see the scoring
        // loop above, which excludes exactly the same scoredThisLoop notes, so a captured tile
        // can never be scored again until its note comes back around on the next loop.
        EnumSet<Direction> active = EnumSet.noneOf(Direction.class);
        for (int i = 0; i < sequence.length; i++)
        {
            if (sequence[i].activeAt(tickInLoop) && !scoredThisLoop[i]) active.add(sequence[i].direction);
        }
        highlighted = Collections.unmodifiableSet(active);

        long endsAt = getEndsAt();
        if (endsAt != 0 && System.currentTimeMillis() >= endsAt && !submitted)
        {
            submitted = true;
            submitResult();
        }
    }

    /** The real WorldPoint for {@code direction} relative to {@code anchor} -- north/south adjust
     * y by +1/-1, east/west adjust x by +1/-1, same plane. */
    private static WorldPoint tileFor(WorldPoint anchor, Direction direction)
    {
        switch (direction)
        {
            case NORTH: return new WorldPoint(anchor.getX(), anchor.getY() + 1, anchor.getPlane());
            case SOUTH: return new WorldPoint(anchor.getX(), anchor.getY() - 1, anchor.getPlane());
            case EAST: return new WorldPoint(anchor.getX() + 1, anchor.getY(), anchor.getPlane());
            default: return new WorldPoint(anchor.getX() - 1, anchor.getY(), anchor.getPlane());
        }
    }

    /** Reports the local player's own final tally -- one-shot, no retry, same shape
     * CoinRushPresentation's own collectCoin/SandwichRushPresentation's own collectItem use for
     * their own server calls. */
    private void submitResult()
    {
        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) return;

        final int finalScore = score;
        plugin.submitAction("Submit Dance, Dance, RuneScape result",
            () -> plugin.apiClient.submitDanceDanceRuneScapeResult(gid, self, token, finalScore),
            e -> plugin.addChatMessage("Failed to submit your Dance, Dance, RuneScape result: " + e.getMessage()));
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as every other client-local mini-game's own onStarted -- a fresh instance
        // starts with no highlight, no score, and hasn't submitted yet, regardless of catch-up.
        // sequence itself isn't picked yet either -- see onRoundBegin, the only writer -- since
        // getCurrentRound() only reflects the round this instance is actually playing once the
        // round has genuinely begun.
        sequence = null;
        sequenceTicks = 0;
        roundDurationMs = 0;
        tickCount = 0;
        lastLoopNumber = -1;
        scoredThisLoop = new boolean[0];
        flashStartAtMillis = Collections.emptyMap();
        highlighted = Collections.emptySet();
        score = 0;
        submitted = false;
        roundStartAt = 0;
    }

    @Override
    public void onRoundBegin()
    {
        sequence = pickSequence();
        sequenceTicks = totalTicks(sequence);
        roundDurationMs = (long) sequenceTicks * TICK_MILLIS;
        tickCount = 0;
        lastLoopNumber = -1;
        scoredThisLoop = new boolean[sequence.length];
        flashStartAtMillis = Collections.emptyMap();
        roundStartAt = System.currentTimeMillis();
        reportRoundDuration();
    }

    /** Tells the server how long this round will actually run, once, right as it begins -- see
     * this class's own doc for why (the server has no other way to know, since which sequence gets
     * picked and how long it runs is deliberately client-only data). One-shot, no retry, same
     * shape submitResult()'s own final-tally call uses -- if it's lost, the server just falls back
     * to its own generous default (see dance_dance_runescape.py), which only risks the round
     * ending a bit later than strictly necessary, not a hang. */
    private void reportRoundDuration()
    {
        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) return;

        final long durationMs = roundDurationMs;
        plugin.submitAction("Report Dance, Dance, RuneScape round duration",
            () -> plugin.apiClient.reportDanceDanceRuneScapeRoundDuration(gid, self, token, durationMs),
            e -> plugin.addChatMessage("Failed to report your Dance, Dance, RuneScape round duration: " + e.getMessage()));
    }

    @Override
    public boolean showsFinalScore() { return true; }

    @Override
    public void reset()
    {
        sequence = null;
        sequenceTicks = 0;
        roundDurationMs = 0;
        tickCount = 0;
        lastLoopNumber = -1;
        scoredThisLoop = new boolean[0];
        flashStartAtMillis = Collections.emptyMap();
        highlighted = Collections.emptySet();
        score = 0;
        submitted = false;
        roundStartAt = 0;
    }

    /** When the current round's own clock runs out -- 0 if no round is active yet or the round
     * hasn't actually become playable. Unlike every other client-local mini-game's own
     * getEndsAt(), this isn't a fixed duration -- it's derived from however long the round's own
     * {@link #sequence} actually takes to play through once (see onRoundBegin), so the round
     * always lasts exactly as long as the sequence itself, never cut short or padded by an
     * unrelated fixed timer. */
    public long getEndsAt() { return roundStartAt != 0 ? roundStartAt + roundDurationMs : 0; }

    /** Every tile currently lit and still scoreable -- empty (never null) before the first beat,
     * one entry for a plain single-tile note, two or more for a chord. A note drops out of this
     * set the instant it's captured (see onTick), even if its own window isn't over yet -- see
     * {@link #getFlashStartTimes()} for the capture feedback that plays instead. */
    public Set<Direction> getHighlightedDirections() { return highlighted; }

    /** When (System.currentTimeMillis()) each direction was last captured, for the overlay's own
     * brief flash -- see DanceDanceRuneScapeOverlay#FLASH_DURATION_MS for how long a flash lasts
     * from that timestamp, entirely the overlay's own call since this class knows nothing about
     * frame timing (onTick only advances once per 600ms game tick; Overlay#render runs every
     * rendered frame, so timing the flash off a millisecond timestamp rather than tick count lets
     * it be far shorter than a whole tick and still render smoothly). Never removed once written
     * (the overlay itself decides when a flash has expired by comparing against "now"), so a
     * direction can be "present but expired" here -- that's expected. */
    public Map<Direction, Long> getFlashStartTimes() { return flashStartAtMillis; }

    /** The local player's own running tally this round. */
    public int getScore() { return score; }
}
