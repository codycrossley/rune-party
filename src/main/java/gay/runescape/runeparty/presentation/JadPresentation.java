package gay.runescape.runeparty.presentation;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;

import java.util.Locale;

/** Jad encounter state and event handling -- structured the same way GoldenGnomePresentation is
 * (extracted client-side state, folds its own event types via apply(), clears itself via reset()).
 * The real difference from a Golden Gnome offer: this is timer-driven (the server force-resolves
 * it if nobody bows in time) rather than waiting on a response indefinitely, so this also tracks
 * the cosmetic countdown's own start time and whether the smash has already been triggered (once
 * it has, bowing is pointless -- see isSmashTriggered, which AnnouncementOverlay uses to stop
 * showing the BOW instruction). */
public final class JadPresentation
{
    private final RunePartyPlugin plugin;

    // Real state, applied catch-up or not: non-null exactly while an encounter is outstanding --
    // gates whether a BOW emote does anything and the encounter banner/countdown.
    private volatile String encounterRsn = null;
    // Cosmetic-only: when the local client's own countdown should count down from. Deliberately
    // always equal to revealAt (see that field's own doc) -- the countdown always starts fresh at
    // "5" the very first frame it's actually drawn, rather than however much of the real window
    // has already elapsed by then. A client that only catches up on an already-open encounter has
    // no live "awakened" moment of its own to hang this off of, so it just doesn't render the
    // countdown for whatever's left of it.
    private volatile long awakenedAt = 0;
    // When the encounter reveal (banner + Jad's own 3D model, see JadEncounter) is actually allowed
    // to start showing -- the later of "when the server's real bow window itself starts" (now plus
    // settleMs, see below) or plugin.getTurnEffectGateUntil(), computed once right here when
    // JAD_AWAKENED lands, not re-checked every frame. Re-checking the live gate every frame and
    // extending it forward while showing created a self-inflicted flashing loop in an earlier
    // version (draw a frame, extend the gate past "now", the next frame reads that extension as
    // "something else is still blocking me" and bails). A fixed reveal timestamp, decided once, has
    // no such feedback path.
    //
    // awakenedAt is set equal to this, not to the moment the event actually landed. settleMs
    // covers the known obligations that can precede a Jad Tile landing, and the server's real bow
    // window is delayed to match, so in the common case revealAt already lands at (or before)
    // "now + settleMs". But turnEffectGateUntil can also still be extended by something the server
    // has no way to know about in advance -- in that rarer case, stamping awakenedAt at the
    // earlier "now + settleMs" instead would make the countdown pop in already partway elapsed,
    // which reads as broken. Starting the countdown fresh here instead means it can occasionally
    // run a little past the server's real deadline before JAD_SMASH_TRIGGERED catches up with it,
    // but that's a strictly smaller, less jarring failure mode.
    private volatile long revealAt = 0;
    // Real state, applied catch-up or not: once true, the bow window has closed server-side -- a
    // late bow would just 409, so the banner stops offering the instruction once this flips.
    private volatile boolean smashTriggered = false;

    private volatile boolean awaitingBowFinish = false;

    // ---- outcome banner ("Your loyalty will cost you N coins!" / "You chose not to bow to Jad!")
    // -- fired once the whole encounter's settled, either way, on JAD_DISMISSED/
    // JAD_SMASH_TRIGGERED. Armed via plugin.armBanner (same shape as GoldenGnomePresentation's own
    // outcome banner), so it queues behind whatever's still showing rather than colliding with it.
    // The bowed path's own coin toll isn't armed here -- RunePartyPlugin's own COINS_CHANGED
    // handling arms that popup itself, deliberately delayed behind this banner and the
    // bow-acknowledge animation rather than fired the instant this event lands. ----
    private final TimedBanner<OutcomePayload> outcome = new TimedBanner<>();

    public JadPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.JAD_AWAKENED:
            {
                encounterRsn = Json.requiredStr(e.payload, type, "player");
                smashTriggered = false;
                if (!catchingUp)
                {
                    long now = System.currentTimeMillis();
                    // settleMs (0 for a Jad Tile landed on directly) is how long the real
                    // server-side bow window waits before it actually starts counting down,
                    // folded in here alongside the local turnEffectGateUntil so revealAt reflects
                    // whichever one pushes the reveal out further. See revealAt's own doc for why
                    // awakenedAt is set equal to it rather than to "now" or "now + settleMs".
                    Integer settleMs = Json.safeInt(e.payload, "settleMs");
                    long serverIntendedStart = now + (settleMs != null ? settleMs : 0);
                    revealAt = Math.max(serverIntendedStart, plugin.getTurnEffectGateUntil());
                    awakenedAt = revealAt;
                    plugin.addChatMessage(encounterRsn + " has awakened Jad!");
                }
                break;
            }

            case Events.JAD_SMASH_TRIGGERED:
            {
                // Real state, applied catch-up or not -- see isSmashTriggered's own doc. The
                // client-side animation trigger itself is handled directly in RunePartyPlugin's own
                // switch, but only after the outcome banner below is armed, so the "chose not to
                // bow" decision is announced before the stomp plays or any penalty lands.
                smashTriggered = true;
                if (!catchingUp)
                {
                    String resolvedRsn = Json.requiredStr(e.payload, type, "player");
                    plugin.armBanner(outcome, RunePartyPlugin.JAD_OUTCOME_BANNER_DURATION_MS,
                        () -> new OutcomePayload("smashed", resolvedRsn), true);
                }
                break;
            }

            case Events.JAD_DISMISSED:
            {
                encounterRsn = null; // always clear, catch-up or not -- real state
                smashTriggered = false;
                awaitingBowFinish = false;
                if (!catchingUp)
                {
                    String resolvedOutcome = Json.requiredStr(e.payload, type, "outcome");
                    String resolvedRsn = Json.requiredStr(e.payload, type, "player");

                    // The "smashed" loss itself (COINS_CHANGED reason=jad_smash, or
                    // GOLDEN_GNOME_LOST) already has its own chat announcement -- this one's scoped
                    // to "bowed" only, so each outcome's chat line comes from the event that
                    // actually carries it, not a generic dismissal here.
                    if ("bowed".equals(resolvedOutcome))
                    {
                        plugin.addChatMessage(resolvedRsn + " bowed before Jad!");

                        // Only the "bowed" outcome is armed here -- "smashed" was already armed the
                        // instant the bow window closed (see JAD_SMASH_TRIGGERED above), specifically
                        // so it shows before the stomp/penalty rather than after both are already
                        // done.
                        plugin.armBanner(outcome, RunePartyPlugin.JAD_OUTCOME_BANNER_DURATION_MS,
                            () -> new OutcomePayload(resolvedOutcome, resolvedRsn), true);
                    }
                }
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
        smashTriggered = false;
        awaitingBowFinish = false;
        outcome.reset();
    }

    // ---- awaiting-emote flag, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    public void armAwaitingBowFinish() { awaitingBowFinish = true; }
    public boolean isAwaitingBowFinish() { return awaitingBowFinish; }
    public void clearAwaitingBowFinish() { awaitingBowFinish = false; }

    public String getEncounterRsn() { return encounterRsn; }
    public long getAwakenedAt() { return awakenedAt; }
    public long getRevealAt() { return revealAt; }
    public boolean isSmashTriggered() { return smashTriggered; }

    public String getOutcome() { return outcome.payload != null ? outcome.payload.outcome : null; }
    public String getOutcomeRsn() { return outcome.payload != null ? outcome.payload.rsn : null; }
    public long getOutcomeBannerUntil() { return outcome.until; }

    /** Payload for the "You chose to/not to bow to Jad!" outcome banner -- see the JAD_DISMISSED
     * handler above. */
    private static final class OutcomePayload
    {
        final String outcome; // "bowed" | "smashed"
        final String rsn;

        OutcomePayload(String outcome, String rsn)
        {
            this.outcome = outcome;
            this.rsn = rsn;
        }
    }
}
