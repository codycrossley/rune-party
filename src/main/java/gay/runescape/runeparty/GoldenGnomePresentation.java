package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Golden Gnome offer/outcome/popup/relocation state and event handling -- extracted from
 * RunePartyPlugin per ARCHITECTURE_REVIEW.md's C1 finding, step 2. Owns its own fields, folds its
 * own event types via apply(), and clears itself via reset(); RunePartyPlugin still exposes every
 * getter under its original name, just delegating here, so no external caller needs to change. */
final class GoldenGnomePresentation
{
    private final RunePartyPlugin plugin;

    // ---- offer (server-driven, everyone sees it -- see GOLDEN_GNOME_OFFERED/
    // GOLDEN_GNOME_OFFER_RESOLVED handling below). offerRsn is real state (non-null exactly while
    // a response is outstanding, same role pendingRoll plays for a roll) -- it gates whether a
    // YES/NO emote does anything (see RunePartyPlugin#isLocalPlayerAwaitingGoldenGnomeResponse) as
    // well as the offer banner, and who AnnouncementOverlay#renderGoldenGnomeOffer addresses "You
    // found..." to vs "<rsn> found...". outcome below is purely the follow-up announcement ("You
    // got a Golden Gnome!"/"You can't afford this!"), cosmetic only -- its own rsn payload is what
    // lets that banner address the actual buyer ("You...") differently from everyone else watching
    // ("<rsn>..."), the same split offerRsn already does for the offer itself. ----
    private volatile String offerRsn = null;
    private final TimedBanner<OutcomePayload> outcome = new TimedBanner<>();

    // ---- count popup (client-side timer -- see PlayerOverlay#drawGoldenGnomePopup, same "+1" ->
    // running-total shape and timing as the coin popup) ----
    private final TimedBanner<PopupPayload> popup = new TimedBanner<>();

    // ---- relocation choreography (client-side timers -- see TileOverlay#updateGoldenGnomeModels,
    // the only reader). TileReducer already has the *real* tile state the instant
    // TILE_UNMARKED/TILE_MARKED land (tileReducer.apply runs unconditionally for every event,
    // before RunePartyPlugin's own switch on event type even looks at what kind it is) -- these
    // four fields are purely about *when the model visually catches up to that*, so the sequence
    // reads as spotanim -> vanish -> spotanim -> reappear instead of the model teleporting
    // instantly while the spotanims play catch-up after the fact. ----
    private volatile WorldPoint moveOldPoint = null;
    private volatile long moveHideOldAt = 0; // model still force-shown at moveOldPoint until this passes, even though TileReducer already dropped it
    private volatile WorldPoint moveNewPoint = null;
    private volatile long moveShowNewAt = 0; // model force-hidden at moveNewPoint until this passes, even though TileReducer already has it

    // Same idea as RunePartyPlugin#awaitingSpinFinish, one per response to a pending offer -- see
    // RunePartyPlugin#onAnimationChanged, which consults these via the arm/isAwaiting/clear methods
    // below as part of the same priority-ordered "which gesture am I waiting for" chain every
    // other feature's own awaiting flags participate in.
    private volatile boolean awaitingYesFinish = false;
    private volatile boolean awaitingNoFinish = false;

    GoldenGnomePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.GOLDEN_GNOME_OFFERED:
            {
                // Real state, applied catch-up or not: non-null exactly while a response is
                // outstanding, gating both a YES/NO emote doing anything (see
                // RunePartyPlugin#isLocalPlayerAwaitingGoldenGnomeResponse) and rolling again (see
                // isLocalPlayerReadyToRoll). Pauses TILE_EFFECT/COINS_CHANGED for the underlying
                // tile until GOLDEN_GNOME_OFFER_RESOLVED -- see the server's confirm_arrival/
                // respond_golden_gnome_offer split.
                offerRsn = Json.requiredStr(e.payload, type, "player");
                if (!catchingUp)
                {
                    plugin.addChatMessage(offerRsn + " found a Golden Gnome!");
                }
                break;
            }

            case Events.GOLDEN_GNOME_OFFER_RESOLVED:
            {
                offerRsn = null; // always clear, catch-up or not -- real state
                awaitingYesFinish = false;
                awaitingNoFinish = false;
                // "purchased"'s own announcement comes from the GOLDEN_GNOME_PURCHASED case below
                // instead (it carries the new total, which this event doesn't) -- "cant_afford" and
                // "declined" both get their own announcement here, since this is the only event
                // carrying either outcome. Armed via plugin.armBanner (scheduleAfterTurnEffects
                // underneath) rather than set directly -- see that method's own doc: without it,
                // this banner would show immediately even if some earlier effect (a Coin Trap
                // animation, another Golden Gnome outcome, now a Jad encounter reveal) was still
                // playing, stomping over it instead of queuing politely behind it.
                if (!catchingUp)
                {
                    String resolvedOutcome = Json.requiredStr(e.payload, type, "outcome");
                    String resolvedRsn = Json.requiredStr(e.payload, type, "player");
                    if ("cant_afford".equals(resolvedOutcome))
                    {
                        plugin.armBanner(outcome, RunePartyPlugin.GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS,
                            () -> new OutcomePayload("cant_afford", resolvedRsn), true);
                        plugin.addChatMessage("Can't afford the Golden Gnome!");
                    }
                    else if ("declined".equals(resolvedOutcome))
                    {
                        plugin.armBanner(outcome, RunePartyPlugin.GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS,
                            () -> new OutcomePayload("declined", resolvedRsn), true);
                        plugin.addChatMessage(resolvedRsn + " declined the Golden Gnome!");
                    }
                }
                break;
            }

            case Events.GOLDEN_GNOME_PURCHASED:
            {
                // The running total itself lives in rosterReducer (updated unconditionally by
                // RunePartyPlugin.handleEvent's preamble, catch-up or not) -- everything here is
                // purely cosmetic: the "You got a Golden Gnome!" announcement (outcome, reusing the
                // same banner renderGoldenGnomeOutcome uses for "cant_afford"/"declined") plus the
                // "+1 Golden Gnome" popup, both fired from this one event since it's the only one
                // carrying the new total the popup needs. The popup keeps its own existing
                // immediate/self-queuing behavior (see enqueueCoinPopup's own queue-chaining
                // reasoning) -- only the outcome banner needs plugin.armBanner's queuing, since
                // that's the one drawn in the same big-banner screen position everything else
                // gated behind scheduleAfterTurnEffects uses.
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    Integer total = Json.requiredInt(e.payload, type, "goldenGnomeCount");
                    popup.payload = new PopupPayload(rsn, total != null ? total : 0, 1);
                    popup.start = System.currentTimeMillis();
                    popup.until = popup.start + RunePartyPlugin.COIN_POPUP_DURATION_MS;
                    plugin.extendTurnEffectGate(popup.until);

                    plugin.armBanner(outcome, RunePartyPlugin.GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS,
                        () -> new OutcomePayload("purchased", rsn), true); // same event, same player

                    plugin.addChatMessage(rsn + " got a Golden Gnome!");
                }
                break;
            }

            case Events.GOLDEN_GNOME_LOST:
            {
                // Jad's smash penalty, taken instead of coins when the player holds one (see
                // app.py's _run_jad_encounter) -- same "+1"/running-total popup shape as a purchase,
                // just a -1 delta and no outcome banner of its own (JadOverlay/JadPresentation's own
                // JAD_DISMISSED handling closes out the encounter; this is scoped to the popup and
                // chat message only, matching Coin Trap's own restraint for its coin-loss branch).
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    Integer total = Json.requiredInt(e.payload, type, "goldenGnomeCount");
                    popup.payload = new PopupPayload(rsn, total != null ? total : 0, -1);
                    popup.start = System.currentTimeMillis();
                    popup.until = popup.start + RunePartyPlugin.COIN_POPUP_DURATION_MS;
                    plugin.extendTurnEffectGate(popup.until);

                    plugin.addChatMessage("Jad smashes " + rsn + "! They lost their Golden Gnome!");
                }
                break;
            }

            case Events.GOLDEN_GNOME_MOVED:
            {
                // The real relocation is carried by the paired TILE_UNMARKED/TILE_MARKED events,
                // already applied unconditionally via tileReducer.apply above (catch-up or not) by
                // the time this case even runs. Everything here is choreographing the *visual*
                // catch-up -- see moveOldPoint/moveNewPoint's own doc -- so it's entirely skipped
                // during catch-up like every other purely-visual event. Sequence: spotanim at the
                // old spot -> (VANISH_DELAY later) model disappears -> (GAP after the spotanim)
                // spotanim at the new spot -> (APPEAR_DELAY later) model appears, rather than the
                // model instantly teleporting while the spotanims play catch-up after the fact.
                if (!catchingUp)
                {
                    WorldPoint oldPoint = Json.safeWorldPoint(e.payload, "oldPoint");
                    WorldPoint newPoint = Json.safeWorldPoint(e.payload, "newPoint");

                    if (oldPoint != null)
                    {
                        plugin.triggerSpotAnimAtWorldPoint(RunePartyPlugin.GOLDEN_GNOME_MOVE_SPOTANIM_ID, oldPoint);
                        moveOldPoint = oldPoint;
                        moveHideOldAt = System.currentTimeMillis() + RunePartyPlugin.GOLDEN_GNOME_MOVE_VANISH_DELAY_MS;
                    }
                    if (newPoint != null)
                    {
                        plugin.uiTimerExec.schedule(() ->
                        {
                            plugin.triggerSpotAnimAtWorldPoint(RunePartyPlugin.GOLDEN_GNOME_MOVE_SPOTANIM_ID, newPoint);
                            moveNewPoint = newPoint;
                            moveShowNewAt = System.currentTimeMillis() + RunePartyPlugin.GOLDEN_GNOME_MOVE_APPEAR_DELAY_MS;
                        }, RunePartyPlugin.GOLDEN_GNOME_MOVE_SPOTANIM_GAP_MS, TimeUnit.MILLISECONDS);
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
        offerRsn = null;
        outcome.reset();
        popup.reset();
        moveOldPoint = null;
        moveHideOldAt = 0;
        moveNewPoint = null;
        moveShowNewAt = 0;
        awaitingYesFinish = false;
        awaitingNoFinish = false;
    }

    // ---- awaiting-emote flags, consulted by RunePartyPlugin#onAnimationChanged as part of its
    // single priority-ordered "which gesture am I waiting for" chain ----
    void armAwaitingYesFinish() { awaitingYesFinish = true; }
    void armAwaitingNoFinish() { awaitingNoFinish = true; }
    boolean isAwaitingYesFinish() { return awaitingYesFinish; }
    boolean isAwaitingNoFinish() { return awaitingNoFinish; }
    void clearAwaitingYesFinish() { awaitingYesFinish = false; }
    void clearAwaitingNoFinish() { awaitingNoFinish = false; }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under their original names ----
    String getOfferRsn() { return offerRsn; }
    String getOutcome() { return outcome.payload != null ? outcome.payload.outcome : null; }
    String getOutcomeRsn() { return outcome.payload != null ? outcome.payload.rsn : null; }
    long getOutcomeBannerUntil() { return outcome.until; }
    String getPopupRsn() { return popup.payload != null ? popup.payload.rsn : null; }
    int getPopupNewTotal() { return popup.payload != null ? popup.payload.newTotal : 0; }
    int getPopupDelta() { return popup.payload != null ? popup.payload.delta : 1; }
    long getPopupStart() { return popup.start; }
    long getPopupUntil() { return popup.until; }
    WorldPoint getMoveOldPoint() { return moveOldPoint; }
    long getMoveHideOldAt() { return moveHideOldAt; }
    WorldPoint getMoveNewPoint() { return moveNewPoint; }
    long getMoveShowNewAt() { return moveShowNewAt; }

    /** Payload for the Golden Gnome purchase outcome banner ("You got a Golden Gnome!"/"You can't
     * afford this!") -- see the GOLDEN_GNOME_OFFER_RESOLVED handler above. */
    private static final class OutcomePayload
    {
        final String outcome; // "purchased" | "declined" | "cant_afford"
        final String rsn;

        OutcomePayload(String outcome, String rsn)
        {
            this.outcome = outcome;
            this.rsn = rsn;
        }
    }

    /** Payload for the Golden Gnome count popup -- "+1" on a purchase (see the
     * GOLDEN_GNOME_PURCHASED handler above) or "-1" on a Jad smash (see the GOLDEN_GNOME_LOST
     * handler above); delta is always exactly +-1 in practice (a purchase/loss is always exactly
     * one gnome), but carried as a real signed value rather than hardcoded so PlayerOverlay's
     * rendering doesn't need to guess which case it's in. */
    private static final class PopupPayload
    {
        final String rsn;
        final int newTotal;
        final int delta;

        PopupPayload(String rsn, int newTotal, int delta)
        {
            this.rsn = rsn;
            this.newTotal = newTotal;
            this.delta = delta;
        }
    }
}
