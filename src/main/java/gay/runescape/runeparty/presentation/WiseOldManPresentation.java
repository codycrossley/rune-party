package gay.runescape.runeparty.presentation;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;

import java.util.Locale;

/** Wise Old Man encounter state and event handling -- structured the same way JadPresentation is
 * (extracted client-side state, folds its own event types via apply(), clears itself via reset()).
 * Same "pending decision blocks advancement, timer-driven rather than waiting indefinitely" shape
 * jadEncounterPending plays server-side, but with no smash/penalty phase -- letting the window
 * lapse just auto-declines (see the server's own wise_old_man_dismissed doc). The actual dialogue
 * (chathead, greeting text, steal-type/target options) is WiseOldManDialogueOverlay's own concern,
 * not this class's -- this only tracks whether an encounter is open, for whom, when its reveal is
 * allowed to start, and the outcome banner for a resolved steal. */
public final class WiseOldManPresentation
{
    private final RunePartyPlugin plugin;

    // Real state, applied catch-up or not: non-null exactly while an encounter is outstanding --
    // gates whether the local dialogue box shows at all (only for the rsn actually mid-encounter).
    private volatile String encounterRsn = null;
    // Cosmetic-only: when the local client's own dialogue box is allowed to actually show. Same
    // "a fixed timestamp decided once, not re-checked every frame" reasoning JadPresentation's own
    // revealAt doc gives -- avoids the same self-inflicted flashing-loop risk. awakenedAt is kept
    // distinct from revealAt (even though nothing here currently reads it separately) purely for
    // parity with JadPresentation's own shape, in case a future countdown ever needs it.
    private volatile long awakenedAt = 0;
    private volatile long revealAt = 0;

    // ---- outcome banner ("<thief> stole N coins from <victim>!" / "... a Golden Gnome from ...")
    // -- fired the instant a steal actually resolves (WISE_OLD_MAN_STOLEN), shown to every player,
    // not just the two involved. Armed via plugin.armBanner (same queuing-behind-other-reveals
    // shape JadPresentation's own outcome banner uses) so it doesn't collide with e.g. a Coin Trap
    // steal's own popup from the same landing. No banner at all for "declined"/"timed_out" -- only
    // a real steal is announced (see this feature's own confirmed design). ----
    private final TimedBanner<StolenPayload> outcome = new TimedBanner<>();

    public WiseOldManPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.WISE_OLD_MAN_ENCOUNTER_OPENED:
            {
                encounterRsn = Json.requiredStr(e.payload, type, "player");
                if (!catchingUp)
                {
                    long now = System.currentTimeMillis();
                    // settleMs (0 for a Wise Old Man Tile landed on directly) is how long the
                    // real server-side response window waits before it actually starts counting
                    // down -- see the server's own wise_old_man_encounter_opened doc. Folded in
                    // alongside the local turnEffectGateUntil, same JAD_AWAKENED shape.
                    Integer settleMs = Json.safeInt(e.payload, "settleMs");
                    long serverIntendedStart = now + (settleMs != null ? settleMs : 0);
                    revealAt = Math.max(serverIntendedStart, plugin.turnEffectGateUntil);
                    awakenedAt = revealAt;
                }
                break;
            }

            case Events.WISE_OLD_MAN_STOLEN:
            {
                if (!catchingUp)
                {
                    String thief = Json.requiredStr(e.payload, type, "thief");
                    String victim = Json.requiredStr(e.payload, type, "victim");
                    String kind = Json.requiredStr(e.payload, type, "kind");
                    Integer amount = Json.safeInt(e.payload, "amount");
                    plugin.armBanner(outcome, RunePartyPlugin.WISE_OLD_MAN_OUTCOME_BANNER_DURATION_MS,
                        () -> new StolenPayload(thief, victim, kind, amount), true);
                }
                break;
            }

            case Events.WISE_OLD_MAN_DISMISSED:
            {
                encounterRsn = null; // always clear, catch-up or not -- real state
                awakenedAt = 0;
                revealAt = 0;
                break;
            }

            default:
                break;
        }
    }

    public void reset()
    {
        encounterRsn = null;
        awakenedAt = 0;
        revealAt = 0;
        outcome.reset();
    }

    public String getEncounterRsn() { return encounterRsn; }
    public long getAwakenedAt() { return awakenedAt; }
    public long getRevealAt() { return revealAt; }

    public String getStolenThief() { return outcome.payload != null ? outcome.payload.thief : null; }
    public String getStolenVictim() { return outcome.payload != null ? outcome.payload.victim : null; }
    public String getStolenKind() { return outcome.payload != null ? outcome.payload.kind : null; }
    public Integer getStolenAmount() { return outcome.payload != null ? outcome.payload.amount : null; }
    public long getStolenBannerUntil() { return outcome.until; }

    /** Payload for the "X stole ... from Y!" outcome banner -- see the WISE_OLD_MAN_STOLEN handler
     * above. kind is "coins" or "golden_gnome"; amount is only meaningful for "coins". */
    private static final class StolenPayload
    {
        final String thief;
        final String victim;
        final String kind;
        final Integer amount;

        StolenPayload(String thief, String victim, String kind, Integer amount)
        {
            this.thief = thief;
            this.victim = victim;
            this.kind = kind;
            this.amount = amount;
        }
    }
}
