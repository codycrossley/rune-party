package gay.runescape.runeparty;

import java.util.Locale;

/** Jad encounter state and event handling -- structured the same way GoldenGnomePresentation is
 * (extracted client-side state, folds its own event types via apply(), clears itself via reset()).
 * The real difference from a Golden Gnome offer: this is timer-driven (the server force-resolves
 * it if nobody bows in time -- see app.py's _run_jad_encounter) rather than waiting on a response
 * indefinitely, so this also tracks the cosmetic countdown's own start time and whether the smash
 * has already been triggered (once it has, bowing is pointless -- see isSmashTriggered, which
 * AnnouncementOverlay uses to stop showing the BOW instruction). */
final class JadPresentation
{
    private final RunePartyPlugin plugin;

    // Real state, applied catch-up or not: non-null exactly while an encounter is outstanding --
    // gates whether a BOW emote does anything (see RunePartyPlugin#isLocalPlayerAwaitingJadBow) and
    // the encounter banner/countdown, same role offerRsn plays for GoldenGnomePresentation.
    private volatile String encounterRsn = null;
    // Cosmetic-only (see the JAD_AWAKENED handler below): when the local client's own countdown
    // should count down from. Deliberately always equal to revealAt (see that field's own doc) --
    // the countdown always starts fresh at "5" the very first frame it's actually drawn, rather
    // than however much of the real window has already elapsed by then. A client that only catches
    // up on an already-open encounter has no live "awakened" moment of its own to hang this off of,
    // so it just doesn't render the countdown for whatever's left of it -- same shortcut every
    // other short-lived cosmetic reveal in this codebase already takes on reconnect.
    private volatile long awakenedAt = 0;
    // When the encounter reveal (banner + Jad's own 3D model, see JadEncounter) is actually allowed
    // to start showing -- the later of "when the server's real bow window itself starts" (now plus
    // settleMs, see below) or plugin.getTurnEffectGateUntil(), computed ONCE right here when
    // JAD_AWAKENED lands, not re-checked every frame. Computing it once and storing a fixed
    // timestamp is deliberate, not a style choice: an earlier version had
    // AnnouncementOverlay#renderJadEncounter re-check the live gate every frame *and* extend it
    // forward by a rolling window while showing, which created a self-inflicted flashing loop --
    // draw a frame, extend the gate past "now", the very next frame reads that same extension as
    // "something else is still blocking me" and bails without drawing (and without re-extending),
    // the gate then lapses, so it draws one frame and hides for several, repeating forever. A fixed
    // reveal timestamp, decided once, has no such feedback path.
    //
    // awakenedAt is set equal to this, not to the moment the event actually landed -- a deliberate
    // trade, not an oversight. settleMs (see below) covers the *known* obligations that can precede
    // a Jad Tile landing (a Golden Gnome offer resolving, a Coin Trap springing), and the server's
    // own real bow window is delayed to match, so in the common case revealAt already lands at (or
    // before) "now + settleMs" and this changes nothing. But turnEffectGateUntil can also still be
    // extended by something the server has no way to know about in advance (e.g. a still-finishing
    // welcome/game-start ceremony right after the game began) -- in that rarer case, stamping
    // awakenedAt at the earlier "now + settleMs" instead would make the countdown pop in already
    // partway elapsed (reported: showing "2" on the very first frame it was ever visible), which
    // reads as broken. Starting the countdown fresh here instead means it can very occasionally run
    // a little past the server's real deadline before jad_bow's own 409 or JAD_SMASH_TRIGGERED
    // catches up with it -- but AnnouncementOverlay already handles that gracefully today (a late
    // smashTriggered simply swaps the countdown for "didn't bow in time...", see
    // renderJadEncounter), so that's a strictly smaller, less jarring failure mode than a countdown
    // that visibly starts mid-count.
    private volatile long revealAt = 0;
    // Real state, applied catch-up or not: once true, the bow window has closed server-side (see
    // app.py's jadEncounterPending.smashTriggered) -- a late bow would just 409, so the banner stops
    // offering the instruction once this flips.
    private volatile boolean smashTriggered = false;

    private volatile boolean awaitingBowFinish = false;

    // ---- outcome banner ("You chose to bow to Jad!" / "You chose not to bow to Jad!") -- fired
    // once the whole encounter's settled, either way, on JAD_DISMISSED. Armed via plugin.armBanner
    // (see GoldenGnomePresentation's own outcome banner for the identical shape/reasoning), so it
    // queues behind whatever's still showing rather than colliding with it, and reserves its own
    // window so the *next* turn/mini-game announcement waits behind this in turn. ----
    private final TimedBanner<OutcomePayload> outcome = new TimedBanner<>();

    JadPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    void apply(ApiClient.EventOut e, boolean catchingUp)
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
                    // settleMs (0 for a Jad Tile landed on directly) is how long the *real*
                    // server-side bow window waits before it actually starts counting down -- see
                    // app.py's _resolve_tile_effect_and_advance/_run_jad_encounter -- folded in here
                    // alongside the local turnEffectGateUntil so revealAt reflects whichever one
                    // pushes the reveal out further. See revealAt's own doc for why awakenedAt is
                    // set equal to it rather than to "now" or "now + settleMs".
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
                // client-side animation trigger itself (jadOverlay.playSmash()) is handled directly
                // in RunePartyPlugin's own switch, gated on !catchingUp there, same as any other
                // cosmetic-only reveal -- but only *after* the outcome banner below is armed, so the
                // "chose not to bow" decision is announced before the stomp plays or any penalty
                // lands, not after (this is the moment the decision is actually made -- the bow
                // window just closed -- even though the penalty itself is still ~JAD_SMASH_ANIMATION_
                // SECONDS away server-side).
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
                    // to "bowed" only, same "the event carrying the actual outcome is what
                    // announces it" split GoldenGnomePresentation's own "declined" (silent) vs
                    // "purchased" (announced by GOLDEN_GNOME_PURCHASED, not
                    // GOLDEN_GNOME_OFFER_RESOLVED) already follows.
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

    void reset()
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
    void armAwaitingBowFinish() { awaitingBowFinish = true; }
    boolean isAwaitingBowFinish() { return awaitingBowFinish; }
    void clearAwaitingBowFinish() { awaitingBowFinish = false; }

    String getEncounterRsn() { return encounterRsn; }
    long getAwakenedAt() { return awakenedAt; }
    long getRevealAt() { return revealAt; }
    boolean isSmashTriggered() { return smashTriggered; }

    String getOutcome() { return outcome.payload != null ? outcome.payload.outcome : null; }
    String getOutcomeRsn() { return outcome.payload != null ? outcome.payload.rsn : null; }
    long getOutcomeBannerUntil() { return outcome.until; }

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
