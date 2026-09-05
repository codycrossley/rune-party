package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

/** Fishing Contest's own client-side state -- entirely client-local catches (see
 * RunePartyPlugin#onGameTick's own fishing section), so this only ever needs to track the round's
 * own start, same "wall-clock moment the round actually began, stamped from MINIGAME_ROUND_BEGIN"
 * shape every other minigame's own round-start timestamp uses. Catch counts themselves are never
 * reported to the server mid-round, so they have no counterpart here -- only RunePartyPlugin's own
 * local fields track those. */
public final class FishingContestPresentation implements MinigamePresentationFeature
{
    private volatile long roundStartAt = 0;

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Deliberately not set here -- MINIGAME_STARTED lands before the round is actually playable
        // (the ready-check still has to run first), so stamping "now" at this point would
        // undercount the round's remaining time; see onRoundBegin, the only writer.
        roundStartAt = 0;
    }

    @Override
    public void onRoundBegin()
    {
        roundStartAt = System.currentTimeMillis();
    }

    @Override
    public void reset()
    {
        roundStartAt = 0;
    }

    /** When the current Fishing Contest round's own local catch-timer should stop -- 0 if no round
     * is active yet or the round hasn't actually become playable. RunePartyPlugin#onGameTick's own
     * fishing section compares against this to decide when to submit the local player's final
     * tally. */
    public long getEndsAt()
    {
        return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.FISHING_CONTEST_DURATION_MS : 0;
    }
}
