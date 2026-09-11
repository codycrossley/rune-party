package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Repeat After Me's own client-side state (server-driven rounds, client-driven timing and
 * judging -- see minigames/repeat_after_me.py's own doc for why). roundNumber/targetIndices are
 * real state, applied catch-up or not, folded straight from REPEAT_AFTER_ME_ROUND_STARTED --
 * targetIndices is which of the 16 grid cells (0-15, row-major) this round's sneak peek revealed,
 * the exact same shared set every seated client sees. roundStartAt anchors this class's own local
 * peek/challenge clock, the same "stamp off my own round-scoped event, not the generic
 * MINIGAME_ROUND_BEGIN" shape TrueOrFalsePresentation's own roundStartedAt uses -- see getEndsAt.
 * attemptedIndices is purely local judging: EVERY cell the local player has stood on and SPUN
 * since this round began (see onSpinFinished), correct or not -- deliberately not filtered down to
 * just the correct ones, since TileOverlay#renderRepeatAfterMeTile needs the full picked set both
 * for the live white-outline feedback during the challenge and for the green/red reveal once it
 * ends (see isRevealActive). Never sent anywhere itself -- only its containsAll(targetIndices)
 * verdict travels, via the one-shot submitResult() call at this round's own deadline. */
public final class RepeatAfterMePresentation implements MinigamePresentationFeature
{
    // Matched exactly by the server's own SNEAK_PEEK_SECONDS/CHALLENGE_SECONDS_PER_TILE
    // (minigames/repeat_after_me.py) -- both sides need to agree since the server's own deadline
    // (when it stops waiting on stragglers) is derived from the identical formula.
    private static final long SNEAK_PEEK_MS = 3_000;
    private static final long CHALLENGE_MS_PER_TILE = 3_000;
    private static final int GRID_SIZE = 4; // matches the server's own GRID_SIZE

    private final RunePartyPlugin plugin;

    private volatile int roundNumber = 0;
    private volatile Set<Integer> targetIndices = new HashSet<>();
    private final Set<Integer> attemptedIndices = ConcurrentHashMap.newKeySet();
    private volatile long roundStartAt = 0;
    private volatile boolean submitted = false;
    // Same idea as HotPotatoPresentation's own awaitingPassFinish -- see RunePartyPlugin#
    // onAnimationChanged, which consults this via the arm/isAwaiting/clear methods below.
    private volatile boolean awaitingSpinFinish = false;

    public RepeatAfterMePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        if (Events.REPEAT_AFTER_ME_ROUND_STARTED.equals(type))
        {
            Integer round = Json.requiredInt(e.payload, type, "roundNumber");
            List<Integer> indices = Json.safeIntList(e.payload, "gridIndices");
            if (round == null || indices == null) return;

            roundNumber = round;
            targetIndices = new HashSet<>(indices);
            attemptedIndices.clear();
            submitted = false;
            roundStartAt = System.currentTimeMillis();
        }
    }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while this mini-game is
     * active. The only thing this does is notice this round's own deadline has passed and fire the
     * one-shot final submission -- unlike DanceDanceRuneScapePresentation's own onTick, there's no
     * per-tick position/scoring check here, since scoring only ever happens in response to an
     * actual SPIN emote finishing (see onSpinFinished), not every tick. */
    public void onTick(Player selfPlayer)
    {
        if (roundStartAt == 0 || submitted) return;
        if (System.currentTimeMillis() >= getEndsAt())
        {
            submitted = true;
            submitResult();
        }
    }

    /** Called from RunePartyPlugin#onAnimationChanged once the local player's own SPIN emote
     * finishes while {@link #isChallengeActive()} was true. Matches their current position against
     * the swapped-in grid's own 16 tiles (see RunePartyPlugin#findRepeatAfterMeTilePoints) -- any
     * cell landed on is recorded as attempted, correct or not (see TileOverlay#
     * renderRepeatAfterMeTile's own white-outline feedback for a still-open round, and the
     * green/red reveal once it ends) -- whether it was actually one of this round's own target
     * cells is only resolved at the reveal, not shown live, so there's no instant tell either way
     * while the round's still playable. Spinning off the grid entirely is simply a no-op. */
    public void onSpinFinished(WorldPoint playerPos)
    {
        if (!isChallengeActive() || playerPos == null) return;

        List<WorldPoint> tiles = plugin.findRepeatAfterMeTilePoints();
        Integer index = indexForPoint(tiles, playerPos);
        if (index != null)
        {
            attemptedIndices.add(index);
        }
    }

    /** cell (dx, dy)'s own index is dy * GRID_SIZE + dx, the exact same row-major layout the
     * server's own _centered_grid used to place these 16 tiles -- derived purely from the tiles'
     * own real coordinates (relative to their shared minimum corner), so no index ever needs to
     * travel over the wire. Returns null if the board isn't a full 16-tile Repeat After Me grid
     * right now, or point isn't actually one of its tiles (e.g. still walking toward it). */
    public static Integer indexForPoint(List<WorldPoint> tiles, WorldPoint point)
    {
        if (tiles.size() != GRID_SIZE * GRID_SIZE || !tiles.contains(point)) return null;

        int minX = tiles.stream().mapToInt(WorldPoint::getX).min().orElseThrow();
        int minY = tiles.stream().mapToInt(WorldPoint::getY).min().orElseThrow();
        int dx = point.getX() - minX;
        int dy = point.getY() - minY;
        if (dx < 0 || dx >= GRID_SIZE || dy < 0 || dy >= GRID_SIZE) return null;
        return dy * GRID_SIZE + dx;
    }

    /** Reports the local player's own one-shot, unconditional result for this round -- fired once,
     * the instant this round's own local deadline passes, success or not. Same shape
     * DanceDanceRuneScapePresentation#submitResult uses. */
    private void submitResult()
    {
        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) return;

        final int finalRoundNumber = roundNumber;
        final boolean completed = attemptedIndices.containsAll(targetIndices);
        plugin.submitAction("Submit Repeat After Me result",
            () -> plugin.apiClient.submitRepeatAfterMeResult(gid, self, token, finalRoundNumber, completed),
            e -> plugin.addChatMessage("Failed to submit your Repeat After Me result: " + e.getMessage()));
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        roundNumber = 0;
        targetIndices = new HashSet<>();
        attemptedIndices.clear();
        roundStartAt = 0;
        submitted = false;
    }

    @Override
    public void reset()
    {
        awaitingSpinFinish = false;
        roundNumber = 0;
        targetIndices = new HashSet<>();
        attemptedIndices.clear();
        roundStartAt = 0;
        submitted = false;
    }

    /** A real, varying per-player round count worth a "FINAL SCORE" recap -- see
     * MinigamePresentationFeature's own doc. */
    @Override
    public boolean showsFinalScore() { return true; }

    // ---- awaiting-emote flag, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    public void armAwaitingSpinFinish() { awaitingSpinFinish = true; }
    public boolean isAwaitingSpinFinish() { return awaitingSpinFinish; }
    public void clearAwaitingSpinFinish() { awaitingSpinFinish = false; }

    /** Whether a SPIN emote right now could actually score something -- past the sneak peek,
     * before this round's own deadline, and not already submitted. Consulted by
     * RunePartyPlugin#isLocalPlayerInRepeatAfterMeChallenge, which gates whether SPIN even arms
     * this class's own awaitingSpinFinish in the first place. */
    public boolean isChallengeActive()
    {
        if (roundStartAt == 0 || submitted) return false;
        long now = System.currentTimeMillis();
        return now >= roundStartAt + SNEAK_PEEK_MS && now < getEndsAt();
    }

    /** Whether this round's own sneak peek is still showing -- the overlay's own gate on drawing
     * the target tiles in their "memorize me" color versus their in-progress challenge colors. */
    public boolean isPeekActive()
    {
        return roundStartAt != 0 && System.currentTimeMillis() < roundStartAt + SNEAK_PEEK_MS;
    }

    /** Whether this round's own reveal should be showing -- once its own deadline has passed
     * (submitted flips true, see onTick) and until the next round's own REPEAT_AFTER_ME_ROUND_STARTED
     * replaces this state (or the mini-game itself ends). See TileOverlay#renderRepeatAfterMeTile,
     * the only reader: every attempted cell renders green (correct) or red (incorrect) while this
     * is true, instead of the plain white "picked" outline a still-open round shows. */
    public boolean isRevealActive()
    {
        return roundStartAt != 0 && submitted;
    }

    public int getRoundNumber() { return roundNumber; }
    /** This round's own target cells (0-15) -- empty before the first round's own
     * REPEAT_AFTER_ME_ROUND_STARTED lands. */
    public Set<Integer> getTargetIndices() { return targetIndices; }
    /** Every cell the local player has stood-and-spun on so far this round, correct or not. */
    public Set<Integer> getAttemptedIndices() { return attemptedIndices; }

    /** When this round's own clock runs out -- 0 if no round is active yet. Sneak peek plus
     * CHALLENGE_MS_PER_TILE for every target tile this round revealed, same formula the server's
     * own deadline_seconds uses. */
    public long getEndsAt()
    {
        return roundStartAt != 0 ? roundStartAt + SNEAK_PEEK_MS + (long) CHALLENGE_MS_PER_TILE * targetIndices.size() : 0;
    }
}
