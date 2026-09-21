package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

/** Shared one-shot "I arrived" self-report guard -- every board-swapping mini-game's own arrival
 * gate used to hand-roll an identical {@code arrivalConfirmed} field, a {@code confirmArrival()}
 * method differing only in the mini-game's own name/ApiClient call/chat text, and resetting that
 * field in both {@code onStarted} AND {@code reset()} by hand (see ARCHITECTURE_REVIEW.md's P1 --
 * that last part is exactly the shape that produced the Turf Wars round-begin bug: a flag kept in
 * sync across two lifecycle methods by hand). Deliberately a small composed helper, not a base
 * class every Presentation would need to extend -- what actually counts as "arrived" (a single
 * tile, a whole grid, either of two zones, ...) genuinely differs per mini-game and stays entirely
 * the caller's own {@code onTick} logic; this only owns the "fire once, then never again until
 * reset" latch and the actual network call.
 * <p>
 * Not used by {@link BrutusAttackPresentation} (role-branching and multiple guards beyond a plain
 * latch) or {@link WhosYourJaddyPresentation} (its own zone report is genuinely repeatable, not
 * one-shot) -- forcing either into this shape would fight how they actually work. */
public final class ArrivalGate
{
    /** The actual confirm-arrival network call for one mini-game, e.g.
     * {@code (self, gid, token) -> plugin.apiClient.confirmArenaArrival(gid, self, token)}. */
    public interface Report
    {
        void confirm(String self, String gid, String token) throws Exception;
    }

    private final RunePartyPlugin plugin;
    private final String displayName; // e.g. "Arena" -- feeds the submitAction log label ("Confirm Arena arrival")
    private final String placeNoun;   // e.g. "the flame field" -- feeds the chat-failure text
    private final Report report;
    private volatile boolean confirmed = false;

    public ArrivalGate(RunePartyPlugin plugin, String displayName, String placeNoun, Report report)
    {
        this.plugin = plugin;
        this.displayName = displayName;
        this.placeNoun = placeNoun;
        this.report = report;
    }

    /** Fires the one-shot confirm-arrival report the instant {@code onTarget} is true for the
     * first time this round -- a no-op on every call after that (including if {@code onTarget}
     * later goes back to false, e.g. the player walks off and back onto the arena). Callers decide
     * what "on target" means for their own mini-game (a tile-contains check, a grid membership
     * check, ...) and call this once per {@code onTick} with that boolean -- safe to call every
     * tick regardless of state, same as the hand-rolled {@code if (!arrivalConfirmed) {...}} guards
     * this replaced. */
    public void confirmIfMatched(boolean onTarget)
    {
        if (!onTarget || confirmed) return;
        confirmed = true;

        String self = plugin.getLocalRsn();
        final String gid = plugin.gameId;
        final String token = plugin.playerToken;
        if (self == null || gid == null || token == null) return;

        plugin.submitAction("Confirm " + displayName + " arrival",
            () -> report.confirm(self, gid, token),
            e -> plugin.addChatMessage("Failed to confirm you reached " + placeNoun + ": " + e.getMessage()));
    }

    /** Whether this round's own arrival has already been confirmed -- for the rare caller (see
     * {@link ArenaPresentation#onTick}) that needs to branch on it directly, not just fire the
     * report. */
    public boolean isConfirmed() { return confirmed; }

    /** Clears the one-shot latch -- call from both {@code onStarted} and {@code reset()}, the same
     * two places every hand-rolled {@code arrivalConfirmed} field used to need clearing in by
     * hand. */
    public void reset()
    {
        confirmed = false;
    }
}
