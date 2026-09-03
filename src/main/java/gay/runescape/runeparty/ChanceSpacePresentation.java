package gay.runescape.runeparty;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/** Chance Tile reveal choreography, extracted out of RunePartyPlugin -- same shape as
 * GoldenGnomePresentation/JadPresentation. This class owns the whole three-beat cosmetic reveal --
 * a title card, then a three-icon "who's trading what" tableau, then the announcement text fading
 * in underneath it once every icon's settled -- matching CHANCE_SPACE_TRIGGERED's own server-side
 * doc that it's purely an announcement, never folded into real state anywhere.
 * <p>
 * The roll always applies to two seated PLAYERs picked uniformly at random from the whole board
 * (see tiles/chance.py's own doc) -- `lander` (whoever actually landed on the tile) is carried
 * purely for the chat message's own "X landed on a Chance Tile!" framing, and may or may not be
 * one of the two actual participants the tableau shows. Both outcomes are a strict one-way gift
 * (never a mutual swap, see buildRevealPayload's own doc).
 * <p>
 * The real balance change is carried by the sibling COINS_CHANGED/GOLDEN_GNOME_LOST/
 * GOLDEN_GNOME_WON events, folded into rosterReducer immediately as always (their own generic
 * popup/chat handling is suppressed for reason="chance_space" -- see GoldenGnomePresentation's own
 * GOLDEN_GNOME_LOST/WON cases and the COINS_CHANGED switch case's own exclusion). This class shows
 * its own popup/chat for that already-folded change at applyDeferredDelta, timed to the moment the
 * announcement text is about to appear -- NOTE: known limitation, still being reworked -- the real
 * total is visible on live HUDs (e.g. StatsOverlay) from the moment the event first arrives, ahead
 * of this reveal.
 * <p>
 * The whole sequence's total duration is reserved against the shared turn-effect gate in one atomic
 * plugin.reserveTurnEffectGate call up front, then each stage is scheduled directly against
 * plugin.uiTimerExec at its own offset into that reservation -- see reserveTurnEffectGate's own
 * doc for why a chain of individually-extending scheduleAfterTurnEffects calls (each stage only
 * discovering its own next stage's duration once the previous one's callback fires) left a real
 * gap where the next round's MINIGAME! could land mid-sequence. */
final class ChanceSpacePresentation
{
    private final RunePartyPlugin plugin;

    private volatile ScheduledFuture<?> titleTask;
    private volatile ScheduledFuture<?> revealTask;
    private volatile ScheduledFuture<?> deltaTask;

    private final TimedBanner<Void> title = new TimedBanner<>();
    private final TimedBanner<RevealPayload> reveal = new TimedBanner<>();

    ChanceSpacePresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        if (!Events.CHANCE_SPACE_TRIGGERED.equals(type)) return;

        // Purely a reveal, no real state anywhere -- skipped entirely during catch-up, same as
        // every other one-shot cosmetic trigger in this codebase.
        if (catchingUp) return;

        String lander = Json.requiredStr(e.payload, type, "lander");
        String participantA = Json.requiredStr(e.payload, type, "participantA");
        String participantB = Json.requiredStr(e.payload, type, "participantB");
        String outcome = Json.requiredStr(e.payload, type, "outcome");
        Integer amount = Json.safeInt(e.payload, "amount");
        Boolean gnomeTransferred = safeBool(e.payload, "gnomeTransferred");
        String receiver = Json.safeStr(e.payload, "receiver");
        if (lander == null || participantA == null || participantB == null || outcome == null) return;

        RevealPayload revealPayload = buildRevealPayload(participantA, participantB, outcome, gnomeTransferred, receiver, amount);
        String chatMessage = chatMessageFor(lander, participantA, participantB, outcome, amount, gnomeTransferred, receiver);

        if (titleTask != null) titleTask.cancel(false);
        if (revealTask != null) revealTask.cancel(false);
        if (deltaTask != null) deltaTask.cancel(false);

        long totalDurationMs = RunePartyPlugin.CHANCE_SPACE_TITLE_DURATION_MS + RunePartyPlugin.CHANCE_SPACE_ICON_STAGE_DURATION_MS;
        long delay = plugin.reserveTurnEffectGate(totalDurationMs);

        titleTask = plugin.uiTimerExec.schedule(() ->
        {
            title.until = System.currentTimeMillis() + RunePartyPlugin.CHANCE_SPACE_TITLE_DURATION_MS;
        }, delay, TimeUnit.MILLISECONDS);

        long revealDelay = delay + RunePartyPlugin.CHANCE_SPACE_TITLE_DURATION_MS;
        revealTask = plugin.uiTimerExec.schedule(() ->
        {
            reveal.payload = revealPayload;
            reveal.start = System.currentTimeMillis();
            reveal.until = reveal.start + RunePartyPlugin.CHANCE_SPACE_ICON_STAGE_DURATION_MS;
        }, revealDelay, TimeUnit.MILLISECONDS);

        // Held back until the same moment the announcement text itself starts fading in (not the
        // instant the tableau appears, and not the instant the underlying events first arrived) --
        // see this class's own doc on the current known limitation around live HUDs.
        long deltaDelay = revealDelay + RunePartyPlugin.CHANCE_SPACE_TEXT_START_OFFSET_MS;
        deltaTask = plugin.uiTimerExec.schedule(() ->
        {
            plugin.addChatMessage(chatMessage);
            applyDeferredDelta(participantA, participantB, outcome, amount, gnomeTransferred, receiver);
        }, deltaDelay, TimeUnit.MILLISECONDS);
    }

    /** Shows the coin/Golden-Gnome-count popup this outcome's own sibling COINS_CHANGED/
     * GOLDEN_GNOME_LOST/GOLDEN_GNOME_WON event would normally have shown immediately -- the real
     * totals are already folded into rosterReducer by now (it folds unconditionally, before this
     * whole reveal was even scheduled), so this just reads them back out rather than needing the
     * sibling events' own payload data threaded through. A fizzle (receiver null) shows nothing --
     * there's nobody to point at. */
    private void applyDeferredDelta(String participantA, String participantB, String outcome,
                                     Integer amount, Boolean gnomeTransferred, String receiver)
    {
        if (receiver == null) return;
        String giver = receiver.equalsIgnoreCase(participantA) ? participantB : participantA;

        if ("coins".equals(outcome))
        {
            int coins = amount != null ? amount : 0;
            if (coins > 0)
            {
                plugin.enqueueCoinPopup(receiver, coins, plugin.getRosterReducer().getCoins(receiver), RunePartyPlugin.COIN_POPUP_DURATION_MS, false);
                plugin.enqueueCoinPopup(giver, -coins, plugin.getRosterReducer().getCoins(giver), RunePartyPlugin.COIN_POPUP_DURATION_MS, false);
            }
        }
        else if (Boolean.TRUE.equals(gnomeTransferred))
        {
            plugin.showGoldenGnomeCountPopup(giver, plugin.getRosterReducer().getGoldenGnomeCount(giver), -1);
            plugin.showGoldenGnomeCountPopup(receiver, plugin.getRosterReducer().getGoldenGnomeCount(receiver), 1);
        }
    }

    /** Picks which side of the tableau each participant lands on (a coin flip -- purely cosmetic,
     * has no bearing on the gift itself) and derives the arrow's direction. Both outcomes are a
     * strict one-way gift: never a mutual swap, so the arrow always points one way, never both.
     * `receiver` (whichever of participantA/participantB actually received the gift) is null only
     * for a fizzled gnome gift, in which case the arrow points toward a random one of the two --
     * neither is the "intended" recipient since a fizzle means neither participant held one to
     * begin with -- dimmed by the fizzle rendering itself. */
    private static RevealPayload buildRevealPayload(String participantA, String participantB, String outcome,
                                                      Boolean gnomeTransferred, String receiver, Integer amount)
    {
        boolean aOnLeft = ThreadLocalRandom.current().nextBoolean();
        String leftRsn = aOnLeft ? participantA : participantB;
        String rightRsn = aOnLeft ? participantB : participantA;

        String pointsAt = receiver != null ? receiver : (ThreadLocalRandom.current().nextBoolean() ? participantA : participantB);
        String direction = pointsAt.equalsIgnoreCase(leftRsn) ? "LEFT" : "RIGHT";

        // Slot 0 = left participant token, 1 = arrow, 2 = right participant token -- shuffled into
        // a random temporal reveal order so it's not always "left, then arrow, then right" every
        // single time, while the three screen positions themselves stay fixed.
        List<Integer> order = new ArrayList<>(List.of(0, 1, 2));
        Collections.shuffle(order, ThreadLocalRandom.current());
        long[] slotDelayMs = new long[3];
        for (int position = 0; position < order.size(); position++)
        {
            slotDelayMs[order.get(position)] = position * RunePartyPlugin.CHANCE_SPACE_ICON_STAGE_STAGGER_MS;
        }

        String[] lines = buildAnnouncementLines(participantA, participantB, outcome, amount, gnomeTransferred, receiver);

        return new RevealPayload(leftRsn, rightRsn, outcome, receiver != null, direction, slotDelayMs, lines);
    }

    /** The text that fades in underneath the icon tableau once every icon's settled -- a header
     * naming the outcome, then one line stating exactly who gives whom what. */
    private static String[] buildAnnouncementLines(String participantA, String participantB, String outcome,
                                                     Integer amount, Boolean gnomeTransferred, String receiver)
    {
        if ("coins".equals(outcome))
        {
            int coins = amount != null ? amount : 0;
            String giver = receiver != null && receiver.equalsIgnoreCase(participantA) ? participantB : participantA;
            String toRsn = receiver != null ? receiver : participantB;
            return new String[]{"Chance Tile: Coins!", giver + " gives " + toRsn + " " + coins + " coins!"};
        }
        if (Boolean.TRUE.equals(gnomeTransferred) && receiver != null)
        {
            String giver = receiver.equalsIgnoreCase(participantA) ? participantB : participantA;
            return new String[]{"Chance Tile: Golden Gnome!", giver + " gives " + receiver + " their Golden Gnome!"};
        }
        return new String[]{"Chance Tile: Golden Gnome!", participantA + " and " + participantB + " didn't have one to give!"};
    }

    private static String chatMessageFor(String lander, String participantA, String participantB, String outcome,
                                          Integer amount, Boolean gnomeTransferred, String receiver)
    {
        if ("coins".equals(outcome))
        {
            int coins = amount != null ? amount : 0;
            String giver = receiver != null && receiver.equalsIgnoreCase(participantA) ? participantB : participantA;
            String toRsn = receiver != null ? receiver : participantB;
            return lander + " landed on a Chance Tile! " + giver + " gives " + toRsn + " " + coins + " coins!";
        }
        if (Boolean.TRUE.equals(gnomeTransferred) && receiver != null)
        {
            String giver = receiver.equalsIgnoreCase(participantA) ? participantB : participantA;
            return lander + " landed on a Chance Tile! " + giver + " gives " + receiver + " a Golden Gnome!";
        }
        return lander + " landed on a Chance Tile, but neither " + participantA + " nor " + participantB + " had a Golden Gnome to give!";
    }

    // Boolean field with no plain accessor on Json -- same inline null-safe read Json's own
    // safeTrueOrFalseResults uses for TRUE_OR_FALSE_ROUND_ENDED's "answer" field.
    private static Boolean safeBool(com.google.gson.JsonObject o, String key)
    {
        com.google.gson.JsonElement el = o != null ? o.get(key) : null;
        return el != null && !el.isJsonNull() ? el.getAsBoolean() : null;
    }

    void reset()
    {
        if (titleTask != null) { titleTask.cancel(false); titleTask = null; }
        if (revealTask != null) { revealTask.cancel(false); revealTask = null; }
        if (deltaTask != null) { deltaTask.cancel(false); deltaTask = null; }
        title.reset();
        reveal.reset();
    }

    // ---- getters, mirrored 1:1 by RunePartyPlugin's own facade under prefixed names ----
    long getTitleUntil() { return title.until; }

    long getIconsStart() { return reveal.start; }
    long getIconsUntil() { return reveal.until; }
    String getIconsLeftRsn() { return reveal.payload != null ? reveal.payload.leftRsn : null; }
    String getIconsRightRsn() { return reveal.payload != null ? reveal.payload.rightRsn : null; }
    String getIconsOutcomeType() { return reveal.payload != null ? reveal.payload.outcome : null; }
    boolean isIconsGnomeTransferred() { return reveal.payload != null && reveal.payload.gnomeTransferred; }
    String getIconsArrowDirection() { return reveal.payload != null ? reveal.payload.arrowDirection : null; }
    long[] getIconsSlotDelayMs() { return reveal.payload != null ? reveal.payload.slotDelayMs : null; }
    String[] getAnnouncementLines() { return reveal.payload != null ? reveal.payload.announcementLines : null; }

    /** Payload for the reveal banner: which rsn sits on which side of the tableau, the arrow's
     * direction (always one way, see buildRevealPayload's own doc), which item icon (coins vs
     * Golden Gnome) sits above it, each slot's own reveal delay, and the announcement lines shown
     * once the tableau settles (see buildAnnouncementLines). */
    private static final class RevealPayload
    {
        final String leftRsn;
        final String rightRsn;
        final String outcome; // "coins" or "golden_gnome"
        final boolean gnomeTransferred; // only meaningful when outcome == "golden_gnome"
        final String arrowDirection; // "LEFT" or "RIGHT" -- never "BOTH", see this class's own doc
        final long[] slotDelayMs; // length 3, indexed 0=left participant, 1=arrow, 2=right participant
        final String[] announcementLines;

        RevealPayload(String leftRsn, String rightRsn, String outcome, boolean gnomeTransferred,
                      String arrowDirection, long[] slotDelayMs, String[] announcementLines)
        {
            this.leftRsn = leftRsn;
            this.rightRsn = rightRsn;
            this.outcome = outcome;
            this.gnomeTransferred = gnomeTransferred;
            this.arrowDirection = arrowDirection;
            this.slotDelayMs = slotDelayMs;
            this.announcementLines = announcementLines;
        }
    }
}
