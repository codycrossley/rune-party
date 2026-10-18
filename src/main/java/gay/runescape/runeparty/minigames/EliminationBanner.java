package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;

/** Shared "local vs. others" elimination-flash state -- ArenaPresentation and MageArenaPresentation
 * used to each hand-roll an identical pair of banner slots (localEliminationBannerUntil,
 * othersEliminationBannerUntil/Rsn) plus an identical arm() method. Routes a fresh elimination into
 * one of two completely independent slots depending on whether the eliminated player is the local
 * viewer, so a bystander's own elimination can never stomp the one message that matters most to the
 * player it's actually about -- two LOCAL eliminations in the same window structurally can't
 * collide (a player is only ever eliminated once per round), but two bystanders' eliminations
 * landing close together can still stomp each other in the "others" slot, an accepted, lower-stakes
 * tradeoff given both callers also keep their own persistent skull icon (see each one's own
 * eliminatedRsns, NOT owned here -- Arena/Mage Arena fold that at different times relative to
 * arming this banner, so it stays each caller's own responsibility). Deliberately a small composed
 * helper, not a base class, same "what varies stays with the caller" reasoning ArrivalGate's own
 * doc gives. */
public final class EliminationBanner
{
    private final RunePartyPlugin plugin;
    private final long durationMs;

    private volatile long localUntil = 0;
    private volatile long othersUntil = 0;
    private volatile String othersRsn = null;

    public EliminationBanner(RunePartyPlugin plugin, long durationMs)
    {
        this.plugin = plugin;
        this.durationMs = durationMs;
    }

    /** Arms the flash for {@code rsn}'s own elimination -- routes to the local or others slot
     * depending on whether {@code rsn} is the local viewer. A no-op if {@code rsn} is null. */
    public void arm(String rsn)
    {
        if (rsn == null) return;
        String local = plugin.getLocalRsn();
        long until = System.currentTimeMillis() + durationMs;
        if (local != null && local.equalsIgnoreCase(rsn))
        {
            localUntil = until;
        }
        else
        {
            othersRsn = rsn;
            othersUntil = until;
        }
    }

    /** Clears both slots -- call from both {@code onStarted} and {@code reset()}, same two places
     * every hand-rolled version of this needed clearing in by hand. */
    public void reset()
    {
        localUntil = 0;
        othersUntil = 0;
        othersRsn = null;
    }

    /** When the local viewer's own "You were eliminated!" flash should disappear -- 0 if none is
     * armed. Always about the local viewer by construction, so there's no accompanying rsn getter
     * the way {@link #getOthersUntil()} needs one. */
    public long getLocalUntil() { return localUntil; }

    /** Same idea, for the "&lt;rsn&gt; was eliminated!" flash shown for everyone ELSE'S
     * eliminations -- independent of {@link #getLocalUntil()} so the two can never stomp each
     * other. */
    public long getOthersUntil() { return othersUntil; }

    /** Only meaningful while a caller has already confirmed via {@link #getOthersUntil()} that the
     * banner's own fade hasn't passed yet. */
    public String getOthersRsn() { return othersRsn; }
}
