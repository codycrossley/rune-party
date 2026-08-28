package gay.runescape.runeparty;

import net.runelite.api.coords.WorldPoint;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Golden Gnome outcome/popup/relocation state and event handling -- extracted from
 * RunePartyPlugin per ARCHITECTURE_REVIEW.md's C1 finding, step 2. Owns its own fields, folds its
 * own event types via apply(), and clears itself via reset(); RunePartyPlugin still exposes every
 * getter under its original name, just delegating here, so no external caller needs to change.
 * There's no more offer/response state here -- purchase_golden_gnome (see the server's own doc) is
 * a direct request/response now, triggered by a right-click menu entry rather than an emote, so
 * this class only ever reacts to what already happened (purchased/lost/moved), never gates a
 * pending decision. */
final class GoldenGnomePresentation
{
    private final RunePartyPlugin plugin;

    // ---- outcome banner ("You got a Golden Gnome!") -- fired on GOLDEN_GNOME_PURCHASED, its own
    // rsn payload letting the banner address the actual buyer ("You...") differently from everyone
    // else watching ("<rsn>...") ----
    private final TimedBanner<OutcomePayload> outcome = new TimedBanner<>();

    // ---- count popup (client-side timer -- see PlayerOverlay#drawGoldenGnomePopup, same "+1" ->
    // running-total shape and timing as the coin popup) ----
    private final TimedBanner<PopupPayload> popup = new TimedBanner<>();

    // ---- relocation choreography (client-side timers -- see models/GoldenGnomeModel#update, the
    // only reader). TileReducer already has the *real* tile state the instant
    // TILE_UNMARKED/TILE_MARKED land (tileReducer.apply runs unconditionally for every event,
    // before RunePartyPlugin's own switch on event type even looks at what kind it is) -- these
    // four fields are purely about *when the model visually catches up to that*, so the sequence
    // reads as spotanim -> vanish -> spotanim -> reappear instead of the model teleporting
    // instantly while the spotanims play catch-up after the fact. ----
    private volatile WorldPoint moveOldPoint = null;
    private volatile long moveHideOldAt = 0; // model still force-shown at moveOldPoint until this passes, even though TileReducer already dropped it
    private volatile WorldPoint moveNewPoint = null;
    private volatile long moveShowNewAt = 0; // model force-hidden at moveNewPoint until this passes, even though TileReducer already has it

    GoldenGnomePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.GOLDEN_GNOME_PURCHASED:
            {
                // The running total itself lives in rosterReducer (updated unconditionally by
                // RunePartyPlugin.handleEvent's preamble, catch-up or not) -- everything here is
                // purely cosmetic: the "You got a Golden Gnome!" announcement (outcome) plus the
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
                // just a -1 delta and no outcome banner of its own (JadEncounter/JadPresentation's own
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
        outcome.reset();
        popup.reset();
        moveOldPoint = null;
        moveHideOldAt = 0;
        moveNewPoint = null;
        moveShowNewAt = 0;
    }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under their original names ----
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

    /** Payload for the Golden Gnome purchase outcome banner ("You got a Golden Gnome!") -- see the
     * GOLDEN_GNOME_PURCHASED handler above, the only writer. outcome is always "purchased" in
     * practice -- carried as a real field rather than a hardcoded string only because
     * renderGoldenGnomeOutcome (see its own doc) still checks it explicitly. */
    private static final class OutcomePayload
    {
        final String outcome; // "purchased", always -- see this class's own doc
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
