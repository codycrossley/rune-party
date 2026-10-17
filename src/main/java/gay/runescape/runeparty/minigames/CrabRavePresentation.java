package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.List;

/** Crab Rave's own client-side state -- entirely client-local Dance-emote counting (no server
 * round-trip per dance, see onDanceFinished), so this only needs to track the round's own start
 * (stamped off the generic MINIGAME_ROUND_BEGIN, same shape ClickClickClickPresentation/
 * FishingContestPresentation's own roundStartAt uses -- unlike RepeatAfterMePresentation, Crab
 * Rave has no round-scoped content event of its own to anchor on instead) and the local player's
 * own running dance tally, never reported until the one-shot submitResult() call at the round's
 * own fixed deadline. */
public final class CrabRavePresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private volatile long roundStartAt = 0;
    private volatile int danceCount = 0;
    private volatile boolean submitted = false;
    // Same idea as HotPotatoPresentation's own awaitingPassFinish -- see RunePartyPlugin#
    // onAnimationChanged, which consults this via the arm/isAwaiting/clear methods below.
    private volatile boolean awaitingDanceFinish = false;

    public CrabRavePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        roundStartAt = 0;
        danceCount = 0;
        submitted = false;
    }

    @Override
    public void onRoundBegin(boolean catchingUp)
    {
        roundStartAt = System.currentTimeMillis();
        // A reconnecting client replaying this same event mid-round should never restart the
        // music from the top -- see MinigamePresentationFeature#onRoundBegin's own doc for why
        // this parameter exists at all.
        if (!catchingUp) plugin.playCrabRaveMusic();
    }

    @Override
    public void reset()
    {
        awaitingDanceFinish = false;
        roundStartAt = 0;
        danceCount = 0;
        submitted = false;
    }

    /** A real, varying per-player round count worth a "FINAL SCORE" recap -- see
     * MinigamePresentationFeature's own doc. */
    @Override
    public boolean showsFinalScore() { return true; }

    /** Called once per real game tick from RunePartyPlugin#onGameTick while this mini-game is
     * active. The only thing this does is notice the round's own fixed deadline has passed and
     * fire the one-shot final submission -- same shape RepeatAfterMePresentation's own onTick
     * uses, minus any per-tick position/scoring check (scoring only ever happens in response to an
     * actual Dance emote finishing, see onDanceFinished). */
    public void onTick(Player selfPlayer)
    {
        if (roundStartAt == 0 || submitted) return;
        if (System.currentTimeMillis() >= getEndsAt())
        {
            submitted = true;
            submitResult();
        }
    }

    /** Called from RunePartyPlugin#onAnimationChanged once the local player's own Dance emote
     * finishes while {@link #isDanceEligible()} was true. Only counts if playerPos falls
     * somewhere inside the current CRAB_RAVE_TILE arena -- dancing anywhere else is simply a
     * no-op, no penalty, matching every other client-local mini-game's own forgiving spirit. */
    public void onDanceFinished(WorldPoint playerPos)
    {
        if (!isDanceEligible() || playerPos == null) return;

        List<WorldPoint> tiles = plugin.findCrabRaveTilePoints();
        if (tiles.isEmpty()) return;

        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (WorldPoint p : tiles)
        {
            if (p.getPlane() != playerPos.getPlane()) continue;
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
        }
        if (minX > maxX) return; // no tiles on the local player's own plane

        if (playerPos.getX() >= minX && playerPos.getX() <= maxX && playerPos.getY() >= minY && playerPos.getY() <= maxY)
        {
            danceCount++;
        }
    }

    /** Reports the local player's own one-shot, unconditional dance tally -- fired once, the
     * instant the round's own fixed deadline passes, whatever danceCount stands at (0 is a real,
     * valid result). Same shape RepeatAfterMePresentation#submitResult/
     * DanceDanceRuneScapePresentation#submitResult use. */
    private void submitResult()
    {
        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) return;

        final int finalDanceCount = danceCount;
        plugin.submitAction("Submit Crab Rave result",
            () -> plugin.apiClient.submitCrabRaveResult(gid, self, token, finalDanceCount),
            e -> plugin.addChatMessage("Failed to submit your Crab Rave result: " + e.getMessage()));
    }

    // ---- awaiting-emote flag, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    public void armAwaitingDanceFinish() { awaitingDanceFinish = true; }
    public boolean isAwaitingDanceFinish() { return awaitingDanceFinish; }
    public void clearAwaitingDanceFinish() { awaitingDanceFinish = false; }

    /** Whether a Dance emote right now could actually score something -- the round's begun,
     * before its own deadline, and not already submitted. Consulted by RunePartyPlugin#
     * isLocalPlayerCrabRaveDanceEligible, which gates whether Dance even arms this class's own
     * awaitingDanceFinish in the first place. */
    public boolean isDanceEligible()
    {
        return roundStartAt != 0 && !submitted && System.currentTimeMillis() < getEndsAt();
    }

    public int getDanceCount() { return danceCount; }

    /** When the current Crab Rave round's own clock runs out -- 0 if no round is active yet. */
    public long getEndsAt()
    {
        return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.CRAB_RAVE_DURATION_MS : 0;
    }
}
