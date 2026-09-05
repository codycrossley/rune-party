package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

/** Click, Click, Click's own client-side state -- entirely client-local tile clicks (see
 * RunePartyPlugin#onMenuEntryAdded's own click-interception), so this only ever needs to track the
 * round's own start, same shape FishingContestPresentation's own roundStartAt uses. Click tallies
 * themselves are never reported to the server mid-round, so they have no counterpart here -- only
 * RunePartyPlugin's own local field tracks that. */
public final class ClickClickClickPresentation implements MinigamePresentationFeature
{
    private volatile long roundStartAt = 0;

    @Override
    public void onStarted(boolean catchingUp)
    {
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

    /** When the current Click, Click, Click round's own local click-timer should stop -- 0 if no
     * round is active yet or the round hasn't actually become playable. RunePartyPlugin#
     * onGameTick's own click-click-click section compares against this to decide when to submit
     * the local player's final tally. */
    public long getEndsAt()
    {
        return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.CLICK_CLICK_CLICK_DURATION_MS : 0;
    }
}
