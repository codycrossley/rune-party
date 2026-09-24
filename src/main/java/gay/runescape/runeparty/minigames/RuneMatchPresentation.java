package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.RuneMatchSpawn;
import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** Rune Match's own client-side state: a private memory-matching race, entirely local until the
 * one-shot finish report (see onFlipFinished/onTick/apply, the three places submitMinigameResult
 * can fire from), same "nothing shared with the server mid-round" shape RainbowRushPresentation
 * uses. The server supplies a 7x7 RUNE_MATCH_TILE arena (see
 * buildBoardFromServerTiles) plus one broadcast seed (RUNE_MATCH_BOARD_SEEDED) -- every seated
 * client derives the identical rune-to-tile shuffle from that same seed, so everyone standing in
 * the shared arena sees the same rune at the same tile, but which cards *this* client has already
 * flipped/matched is tracked and rendered purely locally (see TileOverlay's own hidden-vs-revealed
 * rendering), never broadcast.
 * <p>
 * Flipping is Spin-emote-driven (see RunePartyPlugin#isLocalPlayerRuneMatchFlipEligible/
 * onFlipFinished) -- the first flip of a "turn" just reveals a card, the second either matches (both
 * join solvedIndices permanently) or doesn't (both stay revealed for UNMATCHED_REVEAL_MS, purely so
 * the player can actually read them, then hide again -- see onTick). First to match all
 * PAIR_COUNT pairs wins; same Mario-Kart-ranked payout shape as Rainbow Rush needs every seated
 * player's REAL pair count, not just the winner's -- so the instant the server sees any submission
 * reach PAIR_COUNT, it broadcasts RUNE_MATCH_FINISHER_FOUND (see {@link #apply}) telling every
 * other still-racing client to stop and submit its own current count right now, rather than
 * waiting for its own local RunePartyPlugin#RUNE_MATCH_MAX_DURATION_MS timer. */
public final class RuneMatchPresentation implements MinigamePresentationFeature
{
    private static final int ARENA_SIZE = 7;
    private static final int EXPECTED_ARENA_TILES = ARENA_SIZE * ARENA_SIZE;
    private static final int EXPECTED_RUNE_TILES = 16;
    private static final int PAIR_COUNT = 8; // matches the server's own PAIR_COUNT (minigames/rune_match.py)
    // How long an unmatched pair stays revealed before hiding again -- purely cosmetic (there's
    // nothing server-side to time against), just long enough for the player to actually read both
    // cards before they flip back over.
    private static final long UNMATCHED_REVEAL_MS = 900;

    /* Item ids for the eight rune types, each appearing exactly twice across the 16 card tiles --
     * real OSRS item catalog ids, not raw model ids (see RuneMatchRuneModel's own doc for how the
     * two differ and where the item-to-model resolution actually happens). */
    private static final int FIRE_RUNE = 554;
    private static final int WATER_RUNE = 555;
    private static final int AIR_RUNE = 556;
    private static final int EARTH_RUNE = 557;
    private static final int MIND_RUNE = 558;
    private static final int DEATH_RUNE = 560;
    private static final int CHAOS_RUNE = 562;
    private static final int BLOOD_RUNE = 565;

    private final RunePartyPlugin plugin;
    // Reset on onStarted/reset -- see ArrivalGate's own doc.
    private final ArrivalGate arrivalGate;

    private final List<WorldPoint> arenaTiles = new CopyOnWriteArrayList<>();
    private final List<WorldPoint> runeTiles = new CopyOnWriteArrayList<>();
    // runeAssignments.get(i) is the rune item id under runeTiles.get(i) -- see generateRuneAssignments.
    private final List<Integer> runeAssignments = new CopyOnWriteArrayList<>();
    // Null until RUNE_MATCH_BOARD_SEEDED lands -- see apply(). Real state, applied catch-up or not:
    // a catching-up client still needs this to ever build its own board at all.
    private volatile Integer boardSeed;

    // At most 2 entries at once -- the "currently face-up, not yet resolved" cards. Cleared either
    // the instant a match is confirmed (both join solvedIndices instead) or once
    // unmatchedRevealUntil elapses (see onTick).
    private final List<Integer> flippedIndices = new CopyOnWriteArrayList<>();
    private volatile long unmatchedRevealUntil = 0;
    // Real state, applied catch-up or not: permanently matched card indices, never removed for the
    // rest of the round -- see TileOverlay's own reveal rendering, the only other reader.
    private final Set<Integer> solvedIndices = ConcurrentHashMap.newKeySet();

    private volatile long roundStartAt = 0;
    private volatile boolean submitted = false;
    // Same idea as HotPotatoPresentation's own awaitingPassFinish -- see RunePartyPlugin#
    // onAnimationChanged, which consults this via the arm/isAwaiting/clear methods below.
    private volatile boolean awaitingFlipFinish = false;

    public RuneMatchPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.arrivalGate = new ArrivalGate(plugin, "Rune Match", "the Rune Match arena",
            (self, gid, token) -> plugin.apiClient.confirmRuneMatchArrival(gid, self, token));
    }

    /** RUNE_MATCH_BOARD_SEEDED and RUNE_MATCH_FINISHER_FOUND are the only event types this feature
     * needs to react to beyond the generic lifecycle hooks below -- see this class's own doc. The
     * seed is real state (applied catch-up or not: a catching-up client still needs it to build its
     * own board); the finisher signal is purely live (see EPHEMERAL_EVENT_TYPES on the server) and
     * ignored once this client has already submitted, same reasoning RainbowRushPresentation#apply
     * already documents. */
    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.RUNE_MATCH_BOARD_SEEDED:
            {
                Integer seed = Json.requiredInt(e.payload, type, "seed");
                if (seed != null) boardSeed = seed;
                break;
            }

            case Events.RUNE_MATCH_FINISHER_FOUND:
                if (!catchingUp && !submitted)
                {
                    submitted = true;
                    plugin.submitMinigameResult(solvedIndices.size() / 2);
                }
                break;

            default:
                break;
        }
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while Rune Match is active.
     * Fires the one-shot confirm-rune-match-arrival report the instant this client's own position
     * first lands on any RUNE_MATCH_TILE (same event-driven shape ArenaPresentation#onTick already
     * established), lazily builds the board the first tick both the arena tiles and the server's
     * own seed are available (order isn't guaranteed -- either can land first), clears a
     * still-showing unmatched pair once UNMATCHED_REVEAL_MS has elapsed, and self-submits the
     * current pair count once RUNE_MATCH_MAX_DURATION_MS runs out, same "unconditional submission
     * once the local timer runs out" shape RainbowRushPresentation#onTick already uses. */
    public void onTick(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        arrivalGate.confirmIfMatched(pos != null && plugin.findRuneMatchArenaTiles().contains(pos));

        if (!isBoardBuilt() && boardSeed != null)
        {
            buildBoardFromServerTiles(boardSeed);
        }

        if (unmatchedRevealUntil != 0 && System.currentTimeMillis() >= unmatchedRevealUntil)
        {
            flippedIndices.clear();
            unmatchedRevealUntil = 0;
        }

        if (roundStartAt == 0 || submitted) return;
        if (System.currentTimeMillis() >= getEndsAt())
        {
            submitted = true;
            plugin.submitMinigameResult(solvedIndices.size() / 2);
        }
    }

    /** Called from RunePartyPlugin#onAnimationChanged the moment the local player's own Spin emote
     * finishes while {@link RunePartyPlugin#isLocalPlayerRuneMatchFlipEligible} was true. Re-checks
     * that eligibility here too (the round could have ended, or a second card could already be
     * showing, mid-emote) before resolving playerPos against this round's own 16 card tiles (see
     * getRuneIndex) -- landing off a card, on an already-solved one, or on the one card already
     * face-up is simply a no-op. The second flip of a "turn" either matches (both join
     * solvedIndices permanently, and this client's own current pair count is submitted the instant
     * it reaches PAIR_COUNT) or doesn't (both stay revealed for UNMATCHED_REVEAL_MS -- see onTick
     * -- purely so the player can actually read them before they flip back). */
    public void onFlipFinished(WorldPoint playerPos)
    {
        if (roundStartAt == 0 || submitted || playerPos == null || !isBoardBuilt()) return;
        if (flippedIndices.size() >= 2) return; // still showing an unresolved pair -- onTick clears it once its reveal window elapses

        Integer index = getRuneIndex(playerPos);
        if (index == null || solvedIndices.contains(index) || flippedIndices.contains(index)) return;

        flippedIndices.add(index);
        if (flippedIndices.size() != 2) return;

        int first = flippedIndices.get(0);
        int second = flippedIndices.get(1);
        if (Objects.equals(getRuneItemId(first), getRuneItemId(second)))
        {
            solvedIndices.add(first);
            solvedIndices.add(second);
            flippedIndices.clear();
            if (!submitted && solvedIndices.size() / 2 >= PAIR_COUNT)
            {
                submitted = true;
                plugin.submitMinigameResult(solvedIndices.size() / 2);
            }
        }
        else
        {
            unmatchedRevealUntil = System.currentTimeMillis() + UNMATCHED_REVEAL_MS;
        }
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as every other client-local mini-game's own onStarted -- a fresh instance
        // starts with no board, no progress, and hasn't submitted yet, regardless of catch-up.
        reset();
    }

    @Override
    public void onRoundBegin(boolean catchingUp)
    {
        roundStartAt = System.currentTimeMillis();
    }

    @Override
    public boolean showsFinalScore() { return true; }

    @Override
    public void reset()
    {
        boardSeed = null;
        arenaTiles.clear();
        runeTiles.clear();
        runeAssignments.clear();
        flippedIndices.clear();
        unmatchedRevealUntil = 0;
        solvedIndices.clear();
        roundStartAt = 0;
        submitted = false;
        awaitingFlipFinish = false;
        arrivalGate.reset();
    }

    // ---- awaiting-emote flag, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    public void armAwaitingFlipFinish() { awaitingFlipFinish = true; }
    public boolean isAwaitingFlipFinish() { return awaitingFlipFinish; }
    public void clearAwaitingFlipFinish() { awaitingFlipFinish = false; }

    /** Reads and sorts the 49 server-provided RUNE_MATCH_TILEs and derives the 16 rune positions
     * (and which rune sits under each) purely from `seed` -- called lazily from onTick, once both
     * the arena tiles and the seed are available (see that method's own doc). Sorts by Y then X for
     * a stable row-major 7x7 grid regardless of the order the tile reducer's own snapshot happens to
     * return, then verifies the received tiles actually form one complete 7x7 square before
     * accepting the board -- a partial/mid-swap read is simply retried next tick rather than built
     * on incomplete data. Rune cards occupy every other tile (even x, even y within the grid, 16 of
     * the 49): (0,0) (2,0) (4,0) (6,0) / (0,2) (2,2) ... / down to (0,6) (2,6) (4,6) (6,6). */
    private void buildBoardFromServerTiles(int seed)
    {
        List<WorldPoint> serverTiles = plugin.findRuneMatchArenaTiles();
        if (serverTiles.size() != EXPECTED_ARENA_TILES) return;

        List<WorldPoint> sorted = new ArrayList<>(serverTiles);
        sorted.sort(Comparator.comparingInt(WorldPoint::getY).thenComparingInt(WorldPoint::getX));

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (WorldPoint point : sorted)
        {
            minX = Math.min(minX, point.getX());
            maxX = Math.max(maxX, point.getX());
            minY = Math.min(minY, point.getY());
            maxY = Math.max(maxY, point.getY());
        }
        if (maxX - minX + 1 != ARENA_SIZE || maxY - minY + 1 != ARENA_SIZE) return;

        List<WorldPoint> newArenaTiles = new ArrayList<>();
        List<WorldPoint> newRuneTiles = new ArrayList<>();
        for (WorldPoint point : sorted)
        {
            newArenaTiles.add(point);
            int x = point.getX() - minX;
            int y = point.getY() - minY;
            if (x % 2 == 0 && y % 2 == 0) newRuneTiles.add(point);
        }
        if (newRuneTiles.size() != EXPECTED_RUNE_TILES) return;

        arenaTiles.clear();
        arenaTiles.addAll(newArenaTiles);
        runeTiles.clear();
        runeTiles.addAll(newRuneTiles);
        generateRuneAssignments(seed);
    }

    /** Two copies of each of the 8 rune models, shuffled by `seed` -- the one number every seated
     * client derives this identical assignment from (see this class's own doc), so runeAssignments
     * (and thus runeTiles.get(i)'s own rune) agrees exactly across every client's own independent
     * call here. */
    private void generateRuneAssignments(int seed)
    {
        List<Integer> assignments = new ArrayList<>();
        for (int runeId : new int[]{FIRE_RUNE, WATER_RUNE, AIR_RUNE, EARTH_RUNE, MIND_RUNE, DEATH_RUNE, CHAOS_RUNE, BLOOD_RUNE})
        {
            assignments.add(runeId);
            assignments.add(runeId);
        }
        Collections.shuffle(assignments, new Random(seed));
        runeAssignments.clear();
        runeAssignments.addAll(assignments);
    }

    public boolean isBoardBuilt()
    {
        return arenaTiles.size() == EXPECTED_ARENA_TILES && runeTiles.size() == EXPECTED_RUNE_TILES
            && runeAssignments.size() == EXPECTED_RUNE_TILES;
    }

    /** The logical card index (0-15) for `point`, or null if it isn't one of this round's own 16
     * card tiles (including "the board isn't built yet at all"). See TileOverlay's own reveal
     * rendering, the other reader. */
    public Integer getRuneIndex(WorldPoint point)
    {
        if (point == null) return null;
        int index = runeTiles.indexOf(point);
        return index >= 0 ? index : null;
    }

    /** The rune item id under card `index`, or null if the board isn't built or `index` is out of
     * range -- see TileOverlay's own reveal rendering, the other reader. A real OSRS item id, not a
     * raw model id -- see RuneMatchRuneModel's own doc for the resolution from one to the other. */
    public Integer getRuneItemId(int index)
    {
        return index >= 0 && index < runeAssignments.size() ? runeAssignments.get(index) : null;
    }

    public List<WorldPoint> getRuneTiles() { return Collections.unmodifiableList(runeTiles); }

    /** Whether card `index` should currently render its real rune model rather than a hidden
     * placeholder -- true once it's permanently solved, or while it's one of (at most 2) currently
     * face-up cards. See TileOverlay's own reveal rendering, the only reader. */
    public boolean isRevealed(int index) { return solvedIndices.contains(index) || flippedIndices.contains(index); }

    /** This round's own still-hidden card tiles (not yet solved or currently flipped) -- see
     * TileOverlay's own per-tile rendering (a filled square marking where a card can be flipped),
     * the only reader. */
    public List<WorldPoint> getHiddenCardTiles()
    {
        List<WorldPoint> hidden = new ArrayList<>();
        for (int i = 0; i < runeTiles.size(); i++)
        {
            if (!isRevealed(i)) hidden.add(runeTiles.get(i));
        }
        return hidden;
    }

    /** This round's own permanently-solved card tiles -- see TileOverlay's own per-tile rendering
     * (a green highlight for a matched pair), the only reader. */
    public List<WorldPoint> getSolvedCardTiles()
    {
        List<WorldPoint> solved = new ArrayList<>();
        for (int i = 0; i < runeTiles.size(); i++)
        {
            if (solvedIndices.contains(i)) solved.add(runeTiles.get(i));
        }
        return solved;
    }

    /** This round's own currently-revealed cards (solved or momentarily face-up alike), keyed by
     * card index -- see TileOverlay#updateRuneMatchModels, the only reader, which spawns exactly
     * these as real 3D rune models and leaves every other card tile as a hidden placeholder. */
    public Map<Integer, RuneMatchSpawn> getRevealedSpawns()
    {
        Map<Integer, RuneMatchSpawn> spawns = new LinkedHashMap<>();
        for (int i = 0; i < runeTiles.size(); i++)
        {
            if (!isRevealed(i)) continue;
            Integer itemId = getRuneItemId(i);
            if (itemId != null) spawns.put(i, new RuneMatchSpawn(runeTiles.get(i), itemId));
        }
        return spawns;
    }

    /** The local player's own running count of pairs matched so far this round -- see
     * RuneMatchOverlay, the only reader. */
    public int getSolvedPairCount() { return solvedIndices.size() / 2; }

    /** When the server's own backup ceiling kicks in if this client hasn't finished by then -- 0 if
     * no round is active yet. Not a normal win condition, same shape RainbowRushPresentation#
     * getEndsAt already uses. */
    public long getEndsAt() { return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.RUNE_MATCH_MAX_DURATION_MS : 0; }
}
