package gay.runescape.runeparty;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import gay.runescape.runeparty.items.Items;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.gameval.AnimationID;
import net.runelite.api.gameval.SpotanimID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;

/** Entry point and state hub for Rune Party -- mirrors GnomeballPlugin's role in the sibling
 * gnomeball repo (see that repo's README for the architecture this is modeled on). The turn
 * engine talks to a real server (../rune-party server skeleton) over the same
 * report-then-wait-for-the-echo pattern Gnomeball's client uses: action methods below only ever
 * *request* something (roll, arrival, purchase, minigame result); the authoritative outcome
 * always comes back through handleEvent(). */
@Slf4j
@PluginDescriptor(name = "Rune Party")
public class RunePartyPlugin extends Plugin
{
    /** Caps the turn order at the size of RunePartyColor's palette, so every PLAYER always gets a
     * distinct color and the host never has to decide who shares one. */
    public static final int MAX_PLAYERS = 8;

    /** How long AnnouncementOverlay's "<player>'s Turn" banner stays up after TURN_STARTED -- a
     * purely client-side timer (like Gnomeball's goal-flash duration), not anything the server
     * tracks. */
    public static final long TURN_ANNOUNCE_DURATION_MS = 2500;

    /** How long AnnouncementOverlay's "Welcome to Rune Party Showdown" title card stays up after
     * successfully creating/joining a game -- see triggerWelcomeBanner. Shown once, client-side
     * only, to whoever just created/joined; nobody else sees it. */
    public static final long WELCOME_BANNER_DURATION_MS = 4000;

    /** How long AnnouncementOverlay's "MINIGAME!" banner stays up once it actually appears --
     * server-driven (unlike the welcome banner), so every client shows it at the same moment. The
     * appearance itself is delayed until the last roller's own turn effects settle; see
     * scheduleMinigameBanner. */
    public static final long MINIGAME_BANNER_DURATION_MS = 2800;

    /** How long AnnouncementOverlay's mini-game selection spinner spins before settling on the
     * mini-game the server already picked (see MINIGAME_STARTED's own "key") -- the reveal itself
     * is instant once this phase ends, held for MINIGAME_SPINNER_HOLD_MS afterward. Chained behind
     * the "MINIGAME!" banner via scheduleMinigameSpinner/scheduleAfterTurnEffects, same pattern as
     * every other turn-effect visual. */
    public static final long MINIGAME_SPINNER_SPIN_PHASE_MS = 4000;
    /** How long the spinner then holds on the settled mini-game (icon + name) before the
     * ready-check screen takes over. */
    public static final long MINIGAME_SPINNER_HOLD_MS = 4000;
    /** Total time the spinner occupies the screen, derived from the two phases above -- this is
     * what actually gets stamped as minigameSpinnerUntil. */
    public static final long MINIGAME_SPINNER_DURATION_MS = MINIGAME_SPINNER_SPIN_PHASE_MS + MINIGAME_SPINNER_HOLD_MS;

    /** Same shape as MINIGAME_SPINNER_SPIN_PHASE_MS/HOLD_MS/DURATION_MS above, but for the Item
     * Space wheel (see ITEM_GRANTED handling/scheduleItemSpinner) -- kept as its own set of
     * constants rather than reusing the mini-game ones so each wheel's pacing can be tuned
     * independently, even though AnnouncementOverlay's actual wheel-drawing code (drawWheel) is
     * fully shared between the two. */
    public static final long ITEM_SPINNER_SPIN_PHASE_MS = 4000;
    public static final long ITEM_SPINNER_HOLD_MS = 3000;
    public static final long ITEM_SPINNER_DURATION_MS = ITEM_SPINNER_SPIN_PHASE_MS + ITEM_SPINNER_HOLD_MS;

    /** How long AnnouncementOverlay's "already have N items" announcement stays up -- fired on
     * ITEM_CAP_BLOCKED instead of the item wheel (see scheduleItemCapBlockedAnnouncement), when a
     * player lands on an Item Space already holding the server's ITEM_CAP. No wheel spin, so this
     * is a plain fixed-duration banner rather than a spin+hold pair like ITEM_SPINNER_DURATION_MS. */
    public static final long ITEM_CAP_BLOCKED_DURATION_MS = 4000;

    /** How long AnnouncementOverlay's "You used/&lt;rsn&gt; used &lt;item&gt;!" banner stays up --
     * fired on ITEM_USED for whichever items opt in via Item#hasUseAnnouncement (see
     * scheduleItemUsedAnnouncement), same plain fixed-duration shape as ITEM_CAP_BLOCKED_DURATION_MS. */
    public static final long ITEM_USED_ANNOUNCE_DURATION_MS = 3000;

    /** How long AnnouncementOverlay's "You/&lt;rsn&gt; landed on a coin trap!" banner stays up --
     * fired on COIN_TRAP_TRIGGERED, same fixed-duration shape as ITEM_USED_ANNOUNCE_DURATION_MS. */
    public static final long COIN_TRAP_ANNOUNCE_DURATION_MS = 3000;
    /** How long TileOverlay#updateCoinTrapModels keeps a triggered Coin Trap's model spawned (and
     * its spring animation playing) after COIN_TRAP_TRIGGERED, even though the server's paired
     * TILE_UNMARKED has already dropped it from TileReducer's live set -- long enough for
     * COIN_TRAP_SPRING_ANIMATION_ID to actually finish playing before the model just vanishes. */
    public static final long COIN_TRAP_TRIGGER_PERSIST_MS = 1500;

    /** How long AnnouncementOverlay's "3... 2... 1... BEGIN!" countdown plays once every seated
     * PLAYER has YES-emoted ready (see MINIGAME_COUNTDOWN_STARTED) -- one second per tick: 3, 2,
     * 1, then BEGIN!. Only the client watching it happen live schedules this (see the
     * MINIGAME_COUNTDOWN_STARTED handler); a client that only catches up on the fact that it
     * already happened skips straight to playable, see isMinigamePlayable. */
    public static final long MINIGAME_COUNTDOWN_DURATION_MS = 4000;

    /** How long the ready-check screen lingers -- showing every player marked "Ready!" -- after
     * MINIGAME_COUNTDOWN_STARTED lands before the "3... 2... 1..." countdown actually replaces it,
     * so players get a moment to actually see everyone confirmed ready instead of the screen
     * changing the instant the last person emotes. See the MINIGAME_COUNTDOWN_STARTED handler,
     * which delays arming minigameCountdownBannerUntil by this long. */
    public static final long MINIGAME_COUNTDOWN_START_DELAY_MS = 1500;

    /** How long AnnouncementOverlay's "HERE WE GO!" banner stays up after GAME_STARTED -- fires
     * the instant the host's Start Game click lands, no turnEffectGateUntil delay needed since
     * nothing can be mid-effect before the game has even started. Server-driven, so every client
     * (host and joiners alike) sees it at the same moment. */
    public static final long GAME_START_BANNER_DURATION_MS = 3200;

    /** How long AnnouncementOverlay's post-round "ROUND x" / "Current Standings" recap stays up --
     * triggered on MINIGAME_ENDED (see triggerRoundCompleteBanner), which also extends
     * turnEffectGateUntil so the new round's first TURN_STARTED banner waits behind this one
     * instead of overlapping it. */
    public static final long ROUND_COMPLETE_BANNER_DURATION_MS = 10000;

    /** How long AnnouncementOverlay's mini-game rewards recap ("who got what") stays up -- also
     * triggered on MINIGAME_ENDED (see triggerMinigameRewardsBanner), but shown *before* the round
     * recap: scheduleRoundCompleteBanner defers triggerRoundCompleteBanner via
     * scheduleAfterTurnEffects, which waits on turnEffectGateUntil -- extended by this banner --
     * so the two never overlap. */
    public static final long MINIGAME_REWARDS_BANNER_DURATION_MS = 7500;

    /** How long AnnouncementOverlay's Golden Gnome outcome banner ("You got a Golden Gnome!" or
     * "You can't afford this!") stays up -- fires immediately on GOLDEN_GNOME_OFFER_RESOLVED, same
     * as the coin/Golden-Gnome-count popups it can appear alongside, rather than waiting on
     * scheduleAfterTurnEffects itself; like those popups it calls extendTurnEffectGate instead, so
     * it's the *next* turn's announcement (TURN_STARTED/MINIGAME!) that waits for this one, the
     * count popup, and the underlying tile's own coin popup to all finish settling. */
    public static final long GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS = 2600;

    /** How long AnnouncementOverlay's "GAME OVER!" title card stays up -- the first step of the
     * end-game ceremony (see triggerGameOverSequence), gated behind scheduleAfterTurnEffects so it
     * waits out whatever round-complete/rewards recap the final MINIGAME_ENDED just queued --
     * GAME_ENDED can land in the very same event batch when the last round hits maxRounds -- rather
     * than stomping over it. */
    public static final long GAME_OVER_TITLE_DURATION_MS = 3000;

    /** How long "Now it's time to see the winner..." holds before the reveal countdown begins. */
    public static final long WINNER_INTRO_DURATION_MS = 3000;

    /** How long each "In Nth place..." reveal holds before advancing to the next -- see
     * schedulePlaceReveal, which walks the final standings worst-to-best, stopping once only the
     * top two players remain (they get the "And the winner is..." showdown instead of an
     * individual reveal). */
    public static final long PLACE_REVEAL_DURATION_MS = 2800;

    /** How long "And the winner is..." holds before the winner's own name actually appears. */
    public static final long WINNER_SUSPENSE_DURATION_MS = 2500;

    /** How long the winner's name (plus ConfettiOverlay's burst) stays on screen -- the last step
     * of the ceremony, so this is also how long it lingers before the table just sits on
     * GamePhase.ENDED with nothing further scheduled. */
    public static final long WINNER_REVEAL_DURATION_MS = 9000;

    /** How long ConfettiOverlay's burst actually rains for, kicked off the instant the winner
     * reveal fires -- shorter than WINNER_REVEAL_DURATION_MS so the confetti finishes settling
     * while the winner's name is still up, rather than both cutting off at the same instant. */
    public static final long CONFETTI_DURATION_MS = 6000;

    /** Default lifetime for a spotanim spawned via triggerSpotAnimAtWorldPoint, in ~20ms client
     * cycles (not the 600ms game tick) -- long enough for most one-shot effects to finish playing
     * without a fast one lingering awkwardly afterward. Callers with an unusually long or short
     * effect can pass their own duration to the other overload instead. */
    public static final int SPOTANIM_DEFAULT_DURATION_CYCLES = 60;

    /** Spotanim played at both ends of a Golden Gnome relocating after a purchase (see
     * GOLDEN_GNOME_MOVED handling) -- the same swirling rune-teleport effect standard spellbook
     * teleports use, gold-toned enough to fit the theme. */
    public static final int GOLDEN_GNOME_MOVE_SPOTANIM_ID = SpotanimID.TELEPORT_RUNE;

    /** Gap between the "vanish" spotanim at a Golden Gnome's old spot and the "arrive" spotanim at
     * its new one -- both events fire back to back in the same GOLDEN_GNOME_MOVED payload, but
     * playing both at literally the same instant reads as one confusing double-flash rather than
     * "gone, then reappeared elsewhere." */
    public static final long GOLDEN_GNOME_MOVE_SPOTANIM_GAP_MS = 600;

    /** How long after the "vanish" spotanim starts before the model actually disappears from its
     * old spot -- see TileOverlay#updateGoldenGnomeModels, which force-persists the old point past
     * when TileReducer already removed it (that removal is real state, applied the instant the
     * TILE_UNMARKED event lands, well before this delay) so the spotanim visually "covers" the
     * disappearance instead of the model just vanishing on its own first. */
    public static final long GOLDEN_GNOME_MOVE_VANISH_DELAY_MS = 200;

    /** Same idea as GOLDEN_GNOME_MOVE_VANISH_DELAY_MS, mirrored for the arrival: how long after the
     * "arrive" spotanim starts before the model actually appears at its new spot -- TileReducer
     * already has the new tile the instant TILE_MARKED lands, so TileOverlay force-suppresses it
     * until this delay elapses instead. */
    public static final long GOLDEN_GNOME_MOVE_APPEAR_DELAY_MS = 200;

    /** How long PlayerOverlay's coin popup shows "+3" (or "-3") before switching to the player's
     * new running total -- see PlayerOverlay#drawCoinPopup, which is the only other place these
     * three get read from (kept here rather than duplicated as private constants there, so
     * lengthening one phase can't silently eat into another's screen time the way a
     * separately-hardcoded total once did). Purely a client-side timer, not anything the server
     * tracks. */
    public static final long COIN_POPUP_DELTA_PHASE_MS = 2000;
    /** How long the popup then holds on the running total (its last COIN_POPUP_FADE_MS of this
     * spent fading out) before disappearing. */
    public static final long COIN_POPUP_TOTAL_PHASE_MS = 1800;
    /** Tail-end fade shared by both phases' transition out -- carved out of COIN_POPUP_TOTAL_PHASE_MS
     * above, not additional time. */
    public static final long COIN_POPUP_FADE_MS = 400;
    /** Total popup lifetime, derived from the two phases above -- this is what actually gets
     * stamped as coinPopupUntil; nothing should hardcode this independently again. */
    public static final long COIN_POPUP_DURATION_MS = COIN_POPUP_DELTA_PHASE_MS + COIN_POPUP_TOTAL_PHASE_MS;

    /** Extra breathing room after a turn's in-flight visual effects (currently just the coin
     * popup; see extendTurnEffectGate) finish before whatever announcement comes next -- the next
     * "<player>'s Turn" banner, or "MINIGAME!" -- is allowed to appear, so e.g. "+3 coins" -> new
     * total never gets stepped on by something popping up over top of it. Only actually adds delay
     * when a turn effect is still in flight; see scheduleAfterTurnEffects. */
    public static final long POST_TURN_EFFECT_GRACE_MS = 500;

    /** How long AnnouncementOverlay's screen-centered retro dice cycles through random faces once
     * the roll actually starts -- see onAnimationChanged, which delays calling rollDice() until
     * the local player's Spin emote animation finishes, so this cosmetic re-cycling never overlaps
     * the emote itself. Kept here (not a private constant on AnnouncementOverlay) for the same
     * reason as the coin popup phase constants above: so DICE_ROLL_DURATION_MS below can be
     * derived from it instead of drifting out of sync. */
    public static final long DICE_ROLL_SPIN_PHASE_MS = 900;
    /** How long the die then holds on the real rolled value before fading (its last
     * DICE_ROLL_FADE_MS spent fading out). */
    public static final long DICE_ROLL_HOLD_MS = 2000;
    /** Tail-end fade, carved out of DICE_ROLL_HOLD_MS above, not additional time. */
    public static final long DICE_ROLL_FADE_MS = 350;
    /** Total time AnnouncementOverlay's die stays visible after a DICE_ROLLED event, derived from
     * the two phases above -- this is what actually gets stamped as diceRollUntil; nothing should
     * hardcode this independently again. */
    public static final long DICE_ROLL_DURATION_MS = DICE_ROLL_SPIN_PHASE_MS + DICE_ROLL_HOLD_MS;

    /** Extra reveal phases spliced in after the die settles, only for a roll that carried an item
     * bonus (see DICE_ROLLED's "bonus" field/getDiceRollBonus) -- the die first settles on the
     * bare base roll same as always, holds for BADGE_MS while AnnouncementOverlay#renderDiceRoll
     * pops a "+N" label in beside it, then FLIP_MS flips the die itself over to the bonus-inclusive
     * total (label becomes "+N = total"), which then itself holds for RESULT_HOLD_MS so that merged
     * label actually has time to be read before it disappears and the timeline joins the normal
     * hold/fade with the die alone. A plain roll (bonus == 0) skips all three phases entirely, so
     * its timeline is unchanged. TileOverlay's renderTargetArrow also waits out this whole window
     * (on top of DICE_ROLL_SPIN_PHASE_MS) so the "where to walk" arrow never appears before the
     * bonus reveal has actually finished. */
    public static final long DICE_ROLL_BONUS_BADGE_MS = 1000;
    public static final long DICE_ROLL_BONUS_FLIP_MS = 550;
    public static final long DICE_ROLL_BONUS_RESULT_HOLD_MS = 1100;
    public static final long DICE_ROLL_BONUS_REVEAL_MS =
        DICE_ROLL_BONUS_BADGE_MS + DICE_ROLL_BONUS_FLIP_MS + DICE_ROLL_BONUS_RESULT_HOLD_MS;
    /** Total time the die stays visible after a bonus-carrying DICE_ROLLED -- used in place of
     * DICE_ROLL_DURATION_MS above whenever this roll's bonus is nonzero. */
    public static final long DICE_ROLL_BONUS_DURATION_MS = DICE_ROLL_SPIN_PHASE_MS + DICE_ROLL_BONUS_REVEAL_MS + DICE_ROLL_HOLD_MS;

    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ConfigManager configManager;
    @Inject private RunePartyConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private ModelOutlineRenderer modelOutlineRenderer;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;

    private TileReducer tileReducer;
    private TileOverlay tileOverlay;
    private StatsOverlay statsOverlay;
    private PlayerOverlay playerOverlay;
    private AnnouncementOverlay announcementOverlay;
    private ConfettiOverlay confettiOverlay;
    private RosterReducer rosterReducer;
    private ApiClient apiClient;
    private EventSocket eventSocket;
    private RunePartyPanel panel;
    private NavigationButton navButton;
    private RunePartyMapDialog mapDialog; // lazily created on the first "Show Map" click, see showMap()

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "runeparty-actions");
        t.setDaemon(true);
        return t;
    });

    // Dedicated to delayed, purely-cosmetic UI timers (see scheduleTurnAnnouncement) -- kept
    // separate from `executor` above so a pending delay can never queue behind (or block) a real
    // network call.
    private final ScheduledExecutorService uiTimerExec = Executors.newSingleThreadScheduledExecutor(r ->
    {
        Thread t = new Thread(r, "runeparty-ui-timer");
        t.setDaemon(true);
        return t;
    });
    private volatile ScheduledFuture<?> turnAnnounceTask;
    private volatile ScheduledFuture<?> minigameBannerTask;
    private volatile ScheduledFuture<?> minigameSpinnerTask;
    private volatile ScheduledFuture<?> itemSpinnerTask;

    /** Rolling "nothing turn-concluding should appear before this" gate. Every turn-effect visual
     * with its own on-screen duration -- currently just the coin popup, but meant to grow as more
     * tile effects gain their own reveal animations -- pushes this forward via
     * extendTurnEffectGate when it starts. Anything that announces "the turn is over, here's
     * what's next" (the next TURN_STARTED banner, the MINIGAME! banner, and any future one)
     * schedules itself against this single shared timestamp via scheduleAfterTurnEffects instead
     * of hardcoding its own "is some specific effect still showing?" check -- so a new effect only
     * ever needs to touch this one field, not every downstream announcement. Never moves backward:
     * an effect that lands while another is still settling extends the gate rather than shortening
     * it. */
    private volatile long turnEffectGateUntil = 0;

    private volatile GamePhase phase = GamePhase.DISCONNECTED;

    // ---- session ----
    private volatile String gameId = null;
    private volatile String writeKey = null; // non-null only for the host
    private volatile String playerToken = null;
    private volatile String joinCode = null;
    private volatile String hostRsn = null;

    // ---- course building (host, LOBBY only) ----
    private volatile boolean coursePlacementMode = false;
    private volatile CoursePreset selectedPreset = null;
    private volatile int presetRotationSteps = 0; // quarter-turns clockwise: 0/1/2/3 = 0/90/180/270 degrees

    // ---- turn engine ----
    private volatile String currentTurnRsn = null;
    private volatile Integer lastDiceRoll = null;
    private volatile boolean pendingRoll = false;
    // Guards the Spin-emote roll trigger against double-submitting while a roll request is in
    // flight but the server's DICE_ROLLED echo (which flips pendingRoll) hasn't landed yet -- see
    // onAnimationChanged and rollDice().
    private volatile boolean rollRequestSubmitted = false;
    // True from the moment the local player's Spin emote starts (on their own turn) until it
    // finishes -- onAnimationChanged only actually calls rollDice() on the animation change that
    // clears this, so the roll never fires mid-emote.
    private volatile boolean awaitingSpinFinish = false;
    // Same idea as awaitingSpinFinish, one per response to a pending Golden Gnome offer -- see
    // onAnimationChanged. At most one of these four "awaiting" flags is ever true at once, since a
    // roll, an offer, and a mini-game ready-check can never all be pending at the same time.
    private volatile boolean awaitingGnomeYesFinish = false;
    private volatile boolean awaitingGnomeNoFinish = false;
    private volatile boolean awaitingMinigameReadyFinish = false;
    // Candidate destination tiles for the current roll -- more than one when the roll's path
    // crosses a fork (see TileOverlay#renderTargetArrow, which draws one arrow per candidate).
    // Never null, only ever empty.
    private volatile List<Integer> pendingTargetIndices = Collections.emptyList();
    private volatile boolean arrivalSubmitted = false; // guards confirm-arrival from firing every tick while the echo is in flight
    // Real state, applied catch-up or not: whether the current turn's player has already spent
    // their one-item-per-turn allowance -- reset on every TURN_STARTED, set by ITEM_USED. Mirrors
    // the server's own itemUsedThisTurn (see app.py's use_item).
    private volatile boolean itemUsedThisTurn = false;
    // Non-null while a requires_placement item (see Item#requiresPlacement) is armed -- set by
    // beginItemPlacement, cleared by cancelItemPlacement or a successful placement. Client-local
    // only: the server never hears about this until the actual place-coin-trap call goes out, so
    // there's no "used but not yet placed" state to reconcile if the player backs out. See
    // getItemPlacementCandidates for the two tiles this arms "Place <item>" on.
    private volatile String itemPlacementKey = null;
    // Recomputed once per game tick from onGameTick (the client thread) -- see
    // isStandingOnTrackedPosition, whose Player#getWorldLocation() call asserts it's never invoked
    // off the client thread. isLocalPlayerReadyToRoll() reads this cached copy instead of calling
    // that check live, since it's also called from RunePartyPanel (Swing EDT, not the client
    // thread) -- see refreshItemUse. A tick of staleness costs nothing here: OSRS positions only
    // ever change on tick boundaries anyway, so this is exactly as fresh as a live read would be.
    private volatile boolean standingOnTrackedPositionCached = false;
    private volatile boolean minigameActive = false;
    private volatile String minigameInstructions = null;
    // Which minigames/ registry entry is active -- see the server's own minigames package, whose
    // Minigame.key values these match, and RunePartyPanel/gay.runescape.runeparty.minigames.
    // Minigames#get, which looks up this key's own control-panel UI.
    private volatile String minigameKey = null;
    // Announced by AnnouncementOverlay's selection spinner once it settles -- see
    // renderMinigameSpinner/renderMinigameReadyCheck, the only consumers.
    private volatile String minigameDisplayName = null;
    // ---- mini-game selection spinner (cosmetic-only timing, chained behind the "MINIGAME!"
    // banner -- see scheduleMinigameSpinner) ----
    private volatile long minigameSpinnerStart = 0;
    private volatile long minigameSpinnerUntil = 0;
    // Real state: true iff this MINIGAME_STARTED was applied during catch-up, meaning the spinner
    // never plays for this client this round (see scheduleMinigameSpinner's !catchingUp guard) --
    // so minigameSpinnerUntil staying 0 means "already resolved, skip straight to the ready-check"
    // rather than "hasn't started yet." Set unconditionally in the MINIGAME_STARTED handler,
    // exactly like minigameCountdownStarted's split for the same reason -- see
    // AnnouncementOverlay#renderMinigameReadyCheck, the only consumer.
    private volatile boolean minigameSpinnerSkippedForClient = false;
    // ---- item wheel reveal (cosmetic-only timing, chained behind whatever turn-effect is
    // already showing -- see scheduleItemSpinner). itemGrantRsn/itemGrantKey identify who got
    // what, needed by the reveal text ("You got..."/"<rsn> got...", mirroring
    // renderGoldenGnomeOutcome's own per-viewer split). Unlike the mini-game spinner, nothing
    // else needs to distinguish "hasn't started yet" from "this client only caught up after the
    // fact" -- no other screen chains behind this one the way the ready-check chains behind the
    // mini-game spinner, so there's no *SkippedForClient flag needed here. ----
    private volatile long itemSpinnerStart = 0;
    private volatile long itemSpinnerUntil = 0;
    private volatile String itemGrantRsn = null;
    private volatile String itemGrantKey = null;
    // ---- item cap announcement (cosmetic-only timing, chained the same way as the item spinner
    // above -- see scheduleItemCapBlockedAnnouncement). Fires instead of the item wheel when
    // ITEM_CAP_BLOCKED lands, so at most one of {itemSpinnerUntil, itemCapBlockedUntil} is ever
    // "live" for the same landing. ----
    private volatile ScheduledFuture<?> itemCapBlockedTask;
    private volatile long itemCapBlockedUntil = 0;
    private volatile String itemCapBlockedRsn = null;
    private volatile int itemCapBlockedCap = 0;
    // ---- item-used announcement (cosmetic-only timing, chained the same way as the item cap
    // banner above -- see scheduleItemUsedAnnouncement). Only fired for items that opt in via
    // Item#hasUseAnnouncement -- PlaceholderItem's coin change already has its own feedback. ----
    private volatile ScheduledFuture<?> itemUsedAnnounceTask;
    private volatile long itemUsedAnnounceUntil = 0;
    private volatile String itemUsedAnnounceRsn = null;
    private volatile String itemUsedAnnounceItemKey = null;
    // ---- Coin Trap trigger (cosmetic-only timing, chained the same way as the item-used
    // announcement above -- see scheduleCoinTrapTriggerAnnouncement). coinTrapAnnounceRsn is
    // whoever landed on it (the victim) -- the owner's own feedback is purely their coin popup, no
    // banner of their own. ----
    private volatile ScheduledFuture<?> coinTrapAnnounceTask;
    private volatile long coinTrapAnnounceUntil = 0;
    private volatile String coinTrapAnnounceRsn = null;
    // Real-time (not chained behind scheduleAfterTurnEffects -- see COIN_TRAP_TRIGGERED handling's
    // own doc): where TileOverlay#updateCoinTrapModels should force-persist the model and fire its
    // spring animation for COIN_TRAP_TRIGGER_PERSIST_MS after the server's own TILE_UNMARKED would
    // otherwise have already made it vanish.
    private volatile WorldPoint coinTrapTriggerPoint = null;
    private volatile long coinTrapTriggerUntil = 0;
    // ---- mini-game ready-check (server-driven, everyone sees it -- see MINIGAME_PLAYER_READY/
    // MINIGAME_COUNTDOWN_STARTED handling). minigameReadyRsns is real state (who's actually
    // YES-emoted so far), applied unconditionally catch-up or not -- same reasoning as
    // playerPositions below. minigameCountdownStarted is likewise real state; only
    // minigameCountdownBannerUntil (the visual "3...2...1... BEGIN!") is cosmetic-only, deliberately
    // armed MINIGAME_COUNTDOWN_START_DELAY_MS after minigameCountdownStarted flips, so the
    // ready-check screen gets to actually show everyone marked "Ready!" for a beat first (see
    // renderMinigameReadyCheck/isMinigamePlayable, both of which need to tell "hasn't been armed
    // yet, still in that pause" apart from "already resolved, this client only caught up" -- that's
    // what minigameCountdownSkippedForClient is for, same idiom as minigameSpinnerSkippedForClient
    // above). ----
    private final Set<String> minigameReadyRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    private volatile boolean minigameCountdownStarted = false;
    private volatile boolean minigameCountdownSkippedForClient = false;
    private volatile long minigameCountdownBannerUntil = 0;
    // Host-set at start (see GAME_STARTED's maxRounds) and incremented once per completed round
    // (see MINIGAME_ENDED) -- together these are what StatsOverlay's "ROUND x/y" line reads via
    // getCurrentRound/getMaxRounds. 0 until GAME_STARTED actually lands.
    private volatile int maxRounds = 0;
    private volatile int completedRounds = 0;

    // Every player's current board position (pathIndex), keyed by lowercase rsn -- mirrors the
    // server's own state["positions"], kept in sync purely by replaying PLAYER_MOVED events (see
    // handleEvent). Since EventSocket always connects with afterSeq=0 (see start/createGame/
    // joinGame), a fresh or reconnecting client replays every PLAYER_MOVED since the game began, so
    // this ends up correct even without a dedicated snapshot endpoint. A player with no entry yet
    // is on pathIndex 0 (START), same default the server uses. See TileOverlay#
    // renderReturnToPositionArrow, which is what actually uses this to gate re-rolling.
    private final Map<String, Integer> playerPositions = new ConcurrentHashMap<>();

    // ---- pre-game gathering (GAME_STARTED fired, but currentTurnRsn still null -- see confirmStart) ----
    private volatile boolean startConfirmSubmitted = false; // guards confirm-start firing every tick while the echo is in flight

    // ---- instructional overlays (client-side timers, not server state -- see AnnouncementOverlay) ----
    private volatile String turnAnnounceRsn = null;
    private volatile long turnAnnounceUntil = 0;

    // ---- welcome title card (client-side, local-player-only -- see triggerWelcomeBanner) ----
    private volatile long welcomeBannerUntil = 0;

    // ---- minigame banner (server-driven, everyone sees it -- see MINIGAME_STARTED handling) ----
    private volatile long minigameBannerUntil = 0;

    // ---- game-start banner (server-driven, everyone sees it -- see GAME_STARTED handling) ----
    private volatile long gameStartBannerUntil = 0;

    // ---- round-complete banner (server-driven, everyone sees it -- see MINIGAME_ENDED handling
    // and scheduleRoundCompleteBanner). roundCompleteRoundNumber is the *upcoming* round -- the
    // one about to start, same number getCurrentRound() would return live -- snapshotted at
    // trigger time so it stays stable through the banner's own display window regardless of
    // whatever completedRounds does afterward. ----
    private volatile long roundCompleteBannerUntil = 0;
    private volatile int roundCompleteRoundNumber = 0;
    private volatile ScheduledFuture<?> roundCompleteBannerTask;

    // ---- mini-game rewards recap (server-driven, everyone sees it -- see MINIGAME_ENDED handling
    // and triggerMinigameRewardsBanner). Shown *before* the round-complete recap above, via
    // scheduleRoundCompleteBanner deferring that one behind this banner's own gate extension.
    // minigameRewards is a snapshot of MINIGAME_ENDED's own payouts, parsed once at trigger time. ----
    private volatile long minigameRewardsBannerUntil = 0;
    private volatile List<MinigameReward> minigameRewards = Collections.emptyList();

    /** One entry in a MINIGAME_ENDED payload's "payouts" list -- see safeMinigameRewards. */
    public static class MinigameReward
    {
        public final String rsn;
        public final int coins;

        public MinigameReward(String rsn, int coins)
        {
            this.rsn = rsn;
            this.coins = coins;
        }
    }

    // ---- end-game awards ceremony (server-driven, everyone sees it -- see GAME_ENDED handling and
    // triggerGameOverSequence). A chain of banners, each scheduled behind the last via
    // scheduleAfterTurnEffects: "GAME OVER!" -> "Now it's time to see the winner..." -> one
    // "In Nth place..." reveal per eliminated player (worst to best, stopping once only the top two
    // remain) -> "And the winner is..." -> the winner's name plus ConfettiOverlay's burst.
    // gameOverStandings is the final ranking (coins desc, Golden Gnomes tiebreak, same order
    // renderRoundCompleteBanner already uses), snapshotted once at trigger time since no further
    // coin/gnome changes are possible once the server's flipped the game out of ACTIVE. ----
    private volatile ScheduledFuture<?> gameOverTask;
    private volatile List<RosterReducer.RosterEntry> gameOverStandings = Collections.emptyList();
    private volatile long gameOverBannerUntil = 0;
    private volatile long winnerIntroBannerUntil = 0;
    private volatile long placeRevealUntil = 0;
    private volatile String placeRevealRsn = null;
    private volatile int placeRevealRank = 0; // 1-based rank within gameOverStandings
    private volatile int placeRevealCoins = 0;
    private volatile int placeRevealGoldenGnomes = 0;
    private volatile long winnerSuspenseUntil = 0;
    private volatile long winnerRevealUntil = 0;
    private volatile String winnerRsn = null;
    private volatile long confettiUntil = 0;

    // ---- Golden Gnome offer (server-driven, everyone sees it -- see GOLDEN_GNOME_OFFERED/
    // GOLDEN_GNOME_OFFER_RESOLVED handling). goldenGnomeOfferRsn is real state (non-null exactly
    // while a response is outstanding, same role pendingRoll plays for a roll) -- it gates whether
    // a YES/NO emote does anything (see isLocalPlayerAwaitingGoldenGnomeResponse) as well as the
    // offer banner, and who AnnouncementOverlay#renderGoldenGnomeOffer addresses "You found..." to
    // vs "<rsn> found...". goldenGnomeOutcome/goldenGnomeOutcomeRsn/goldenGnomeOutcomeBannerUntil
    // are purely the follow-up announcement ("You got a Golden Gnome!"/"You can't afford this!"),
    // cosmetic only -- goldenGnomeOutcomeRsn is what lets that banner address the actual buyer
    // ("You...") differently from everyone else watching ("<rsn>..."), the same split
    // goldenGnomeOfferRsn already does for the offer itself. ----
    private volatile String goldenGnomeOfferRsn = null;
    private volatile String goldenGnomeOutcome = null; // "purchased" | "declined" | "cant_afford"
    private volatile String goldenGnomeOutcomeRsn = null;
    private volatile long goldenGnomeOutcomeBannerUntil = 0;

    // ---- Golden Gnome count popup (client-side timer -- see PlayerOverlay#drawGoldenGnomePopup,
    // same "+1" -> running-total shape and timing as the coin popup) ----
    private volatile String goldenGnomePopupRsn = null;
    private volatile int goldenGnomePopupNewTotal = 0;
    private volatile long goldenGnomePopupStart = 0;
    private volatile long goldenGnomePopupUntil = 0;

    // ---- Golden Gnome relocation choreography (client-side timers -- see TileOverlay#
    // updateGoldenGnomeModels, the only reader). TileReducer already has the *real* tile state the
    // instant TILE_UNMARKED/TILE_MARKED land (tileReducer.apply runs unconditionally for every
    // event, before this class's own switch on event type even looks at what kind it is) -- these
    // four fields are purely about *when the model visually catches up to that*, so the sequence
    // reads as spotanim -> vanish -> spotanim -> reappear instead of the model teleporting
    // instantly while the spotanims play catch-up after the fact. ----
    private volatile WorldPoint goldenGnomeMoveOldPoint = null;
    private volatile long goldenGnomeMoveHideOldAt = 0; // model still force-shown at goldenGnomeMoveOldPoint until this passes, even though TileReducer already dropped it
    private volatile WorldPoint goldenGnomeMoveNewPoint = null;
    private volatile long goldenGnomeMoveShowNewAt = 0; // model force-hidden at goldenGnomeMoveNewPoint until this passes, even though TileReducer already has it

    // ---- coin popup (client-side timer -- see PlayerOverlay#drawCoinPopup). Keyed per player
    // (player_lower -> an ordered queue of not-yet-expired popups for them) rather than one global
    // slot, so two different players' coins changing out of the same landing -- see
    // COIN_TRAP_TRIGGERED's paired COINS_CHANGED events, victim and owner both -- can show at once
    // instead of the second clobbering the first before it's even rendered a frame. A *queue*, not
    // a single latest-value slot, for the same reason within one player: a Coin Trap steal's own
    // COINS_CHANGED is immediately followed by the underlying tile's own standard-tile payout for
    // that same victim (see _resolve_tile_effect_and_advance's steal-then-fall-through-to-normal-
    // effect shape) -- two popups for one player, back-to-back, both needing their own full
    // display window. A single-slot design here previously meant the second one's arrival
    // overwrote the first in the map before it had rendered even one frame, silently discarding it
    // -- getCoinPopup below instead peeks the head of this queue and only advances past an entry
    // once its own `until` has actually passed, so every queued popup gets its turn. See the
    // COINS_CHANGED handler for how a new popup's start gets computed against the queue's current
    // tail (queuing behind it) rather than against "now". ----
    private final Map<String, Deque<CoinPopup>> coinPopups = new ConcurrentHashMap<>();

    /** One player's coin popup snapshot -- immutable, appended wholesale to coinPopups's per-player
     * queue rather than mutated in place. See CoinPopup's own doc on RunePartyPlugin's
     * COINS_CHANGED handling for how start/until get computed. */
    public static final class CoinPopup
    {
        public final int delta;
        public final int newTotal;
        public final long start;
        public final long until;

        CoinPopup(int delta, int newTotal, long start, long until)
        {
            this.delta = delta;
            this.newTotal = newTotal;
            this.start = start;
            this.until = until;
        }
    }

    // ---- dice roll popup (client-side timer -- see PlayerOverlay#drawDiceRoll) ----
    private volatile String diceRollRsn = null;
    private volatile int diceRollValue = 0;
    // How much of diceRollValue came from a banked item bonus (see DICE_ROLLED's "bonus" field) --
    // 0 for a plain roll, in which case AnnouncementOverlay#renderDiceRoll's timeline is unchanged.
    private volatile int diceRollBonus = 0;
    private volatile long diceRollStart = 0;
    private volatile long diceRollUntil = 0;

    @Override
    protected void startUp()
    {
        log.debug("Rune Party starting up");

        apiClient = new ApiClient(okHttpClient, gson);
        rosterReducer = new RosterReducer();
        tileReducer = new TileReducer();

        tileOverlay = new TileOverlay(client, config, this, tileReducer);
        overlayManager.add(tileOverlay);

        statsOverlay = new StatsOverlay(config, this);
        overlayManager.add(statsOverlay);

        playerOverlay = new PlayerOverlay(client, config, this, rosterReducer, modelOutlineRenderer);
        overlayManager.add(playerOverlay);

        announcementOverlay = new AnnouncementOverlay(client, config, this);
        overlayManager.add(announcementOverlay);

        confettiOverlay = new ConfettiOverlay(client, this);
        overlayManager.add(confettiOverlay);

        panel = new RunePartyPanel(this);
        navButton = NavigationButton.builder()
            .tooltip("Rune Party")
            .icon(buildPlaceholderIcon())
            .priority(6)
            .panel(panel)
            .build();
        clientToolbar.addNavigation(navButton);

        eventSocket = new EventSocket(okHttpClient, gson, new EventListener()
        {
            @Override public void onEvent(ApiClient.EventOut e) { handleEvent(e, false); }
            @Override public void onError(Exception e) { log.debug("EventSocket error", e); }
        });
    }

    @Override
    protected void shutDown()
    {
        log.debug("Rune Party shutting down");
        if (eventSocket != null) eventSocket.shutdown();
        executor.shutdownNow();
        uiTimerExec.shutdownNow();
        if (tileOverlay != null) { tileOverlay.clearGoldenGnomeModels(); overlayManager.remove(tileOverlay); }
        if (statsOverlay != null) overlayManager.remove(statsOverlay);
        if (playerOverlay != null) overlayManager.remove(playerOverlay);
        if (announcementOverlay != null) overlayManager.remove(announcementOverlay);
        if (confettiOverlay != null) overlayManager.remove(confettiOverlay);
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        if (mapDialog != null) { mapDialog.dispose(); mapDialog = null; }
        resetState();
    }

    /** Opens (or brings to front) the course map dialog -- see RunePartyMapDialog, which is a
     * non-modal Swing window so it doesn't block actually playing the game while it's up. Lazily
     * created once and reused rather than a fresh dialog per click, same as navButton/panel above. */
    public void showMap()
    {
        if (mapDialog == null)
        {
            mapDialog = new RunePartyMapDialog(SwingUtilities.getWindowAncestor(panel), this);
        }
        mapDialog.setVisible(true);
        mapDialog.toFront();
    }

    /** The local player's own RSN, or null if unresolvable -- same lookup localRsn() already does
     * for every action method here, just exposed for RunePartyMapDialog to tell "you" apart from
     * everyone else on the map. */
    public String getLocalRsn()
    {
        return localRsn();
    }

    /** Drawn in code rather than loaded from a resource -- there's no real icon asset yet, and a
     * flat placeholder is enough to give the sidebar a tab until someone draws actual artwork. */
    private static BufferedImage buildPlaceholderIcon()
    {
        BufferedImage icon = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = icon.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(255, 210, 0));
        g.fillRoundRect(1, 1, 14, 14, 4, 4);
        g.setColor(Color.BLACK);
        g.fillOval(4, 4, 3, 3);
        g.fillOval(9, 4, 3, 3);
        g.fillOval(4, 9, 3, 3);
        g.fillOval(9, 9, 3, 3);
        g.dispose();
        return icon;
    }

    @Provides
    RunePartyConfig provideConfig(ConfigManager configManager)
    {
        return configManager.getConfig(RunePartyConfig.class);
    }

    // -------------------------------------------------------------------------
    // Session actions -- each of these only ever requests something; the
    // authoritative result always arrives back through handleEvent().
    // -------------------------------------------------------------------------

    public void createGame()
    {
        String host = localRsn();
        if (host == null) return;

        executor.submit(() ->
        {
            try
            {
                ApiClient.CreateGameResult result = apiClient.createGame(host);
                gameId = result.gameId;
                joinCode = result.joinCode;
                writeKey = result.writeKey;
                playerToken = result.playerToken;
                hostRsn = host;
                phase = GamePhase.LOBBY;
                connectEventStream(gameId, host);
                addChatMessage("Created Rune Party game. Join code: " + result.joinCode);
                triggerWelcomeBanner();
            }
            catch (Exception e)
            {
                log.warn("Create game failed", e);
                addChatMessage("Failed to create game: " + e.getMessage());
            }
            refreshPanel();
        });
    }

    public void joinGame(String code)
    {
        String self = localRsn();
        if (self == null) return;

        executor.submit(() ->
        {
            try
            {
                ApiClient.JoinResult result = apiClient.joinGame(code, self);
                gameId = result.gameId;
                hostRsn = result.hostRsn;
                playerToken = result.playerToken;
                writeKey = null;
                joinCode = code;
                phase = GamePhase.LOBBY;
                connectEventStream(gameId, self);
                addChatMessage("Joined Rune Party game hosted by " + result.hostRsn);
                triggerWelcomeBanner();
            }
            catch (Exception e)
            {
                log.warn("Join game failed", e);
                addChatMessage("Failed to join game: " + e.getMessage());
            }
            refreshPanel();
        });
    }

    /** {@code maxRounds} is turns-per-player -- the host sets it in the panel right before
     * starting (see RunePartyPanel's spinner). The server won't insert the first TURN_STARTED
     * itself; that only happens once every seated PLAYER reports standing on the START tile (see
     * confirmStart / onGameTick's gathering check below). */
    public void startGame(int maxRounds)
    {
        final String gid = gameId;
        final String wk = writeKey;
        if (gid == null || wk == null || maxRounds <= 1) return;

        executor.submit(() ->
        {
            try { apiClient.startGame(gid, wk, maxRounds); }
            catch (Exception e)
            {
                log.warn("Start game failed", e);
                addChatMessage("Failed to start game: " + e.getMessage());
            }
        });
    }

    /** Host-only: ends the game for everyone, distinct from leaveGame() which only removes the
     * caller. The resulting GAME_ENDED event (see handleEvent) is what actually flips phase to
     * ENDED for every connected client, this call and leaveGame() both just request it. */
    public void endGame()
    {
        final String gid = gameId;
        final String wk = writeKey;
        if (gid == null || wk == null) return;

        executor.submit(() ->
        {
            try { apiClient.endGame(gid, wk); }
            catch (Exception e)
            {
                log.warn("End game failed", e);
                addChatMessage("Failed to end game: " + e.getMessage());
            }
        });
    }

    public void rollDice()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;
        if (!self.equalsIgnoreCase(currentTurnRsn) || pendingRoll || rollRequestSubmitted) return;

        rollRequestSubmitted = true;
        executor.submit(() ->
        {
            try { apiClient.rollDice(gid, self, token); }
            catch (Exception e)
            {
                rollRequestSubmitted = false; // let a retry (another Spin) through
                log.warn("Roll dice failed", e);
                addChatMessage("Failed to roll dice: " + e.getMessage());
            }
        });
    }

    /** Reports arrival at the tile a roll resolved to. Called automatically from onGameTick once
     * the local player's position matches the pending destination -- see checkPendingArrival(). */
    private void confirmArrival(WorldPoint pos)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.confirmArrival(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()); }
            catch (Exception e)
            {
                log.warn("Confirm arrival failed", e);
                addChatMessage("Failed to confirm arrival: " + e.getMessage());
                arrivalSubmitted = false; // let the next tick retry
            }
        });
    }

    /** Reports the local player standing on the START tile during the pre-game gathering window
     * (GAME_STARTED fired, currentTurnRsn still null). Called automatically from onGameTick --
     * see the gathering check there -- once every seated PLAYER has confirmed, the server inserts
     * the first TURN_STARTED itself. */
    private void confirmStart(WorldPoint pos)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.confirmStart(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()); }
            catch (Exception e)
            {
                log.warn("Confirm start failed", e);
                addChatMessage("Failed to confirm ready: " + e.getMessage());
                startConfirmSubmitted = false; // let the next tick retry
            }
        });
    }

    /** Accepts or declines a pending Golden Gnome offer -- called from onAnimationChanged once the
     * local player's YES/NO emote finishes, same finish-gated pattern as rollDice/the Spin emote.
     * The server resolves the outcome (purchased/declined/cant_afford) and reports it back via
     * GOLDEN_GNOME_OFFER_RESOLVED, which is what actually drives the announcement/popup -- this
     * call itself is fire-and-forget, same as every other player-action method here. */
    private void respondGoldenGnomeOffer(boolean accept)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.respondGoldenGnomeOffer(gid, self, token, accept); }
            catch (Exception e)
            {
                log.warn("Respond to Golden Gnome offer failed", e);
                addChatMessage("Failed to respond to the Golden Gnome offer: " + e.getMessage());
            }
        });
    }

    /** Reports the local player's YES emote during the mini-game ready-check -- see
     * onAnimationChanged (calls this once isLocalPlayerAwaitingMinigameReady's emote finishes) and
     * the server's own minigame_ready, which inserts MINIGAME_COUNTDOWN_STARTED once every seated
     * PLAYER's made this same call. */
    private void confirmMinigameReady()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.confirmMinigameReady(gid, self, token); }
            catch (Exception e)
            {
                log.warn("Confirm mini-game ready failed", e);
                addChatMessage("Failed to confirm mini-game ready: " + e.getMessage());
            }
        });
    }

    public void submitMinigameResult(int score)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.submitMinigameResult(gid, self, token, score); }
            catch (Exception e)
            {
                log.warn("Submit minigame result failed", e);
                addChatMessage("Failed to submit mini-game result: " + e.getMessage());
            }
        });
    }

    /** Spends one of the local player's held items -- called from RunePartyPanel's item-use
     * buttons. A free action: doesn't touch pendingRoll or the turn, same as the server's own
     * use-item endpoint, so the player can still SPIN normally afterward. */
    public void useItem(String itemKey)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null || itemKey == null) return;

        executor.submit(() ->
        {
            try { apiClient.useItem(gid, self, token, itemKey); }
            catch (Exception e)
            {
                log.warn("Use item failed", e);
                addChatMessage("Failed to use item: " + e.getMessage());
            }
        });
    }

    /** Arms a requires_placement item (see Item#requiresPlacement) -- called from RunePartyPanel's
     * item buttons instead of useItem() for one of these, since there's no server call to make
     * yet: it's client-local until the player actually right-clicks a candidate tile's "Place
     * &lt;item&gt;" entry (see onMenuEntryAdded/placeCoinTrapAt). Refuses silently (same "not
     * actually your turn to act right now" guard useItem itself relies on the server to enforce,
     * just checked locally here first) rather than arming a placement that'd only 409 anyway. */
    public void beginItemPlacement(String itemKey)
    {
        if (itemKey == null || !isLocalPlayerReadyToRoll() || isItemUsedThisTurn()) return;
        if (!Items.get(itemKey).requiresPlacement()) return;
        itemPlacementKey = itemKey;
        refreshPanel();
    }

    /** Backs out of an armed placement -- called from RunePartyPanel's Cancel button and the
     * in-world "Cancel" menu entry (see addItemPlacementMenuEntries). Purely client-local, same as
     * beginItemPlacement: the server never heard about the item being "used" in the first place, so
     * there's nothing to undo server-side. */
    public void cancelItemPlacement()
    {
        itemPlacementKey = null;
        refreshPanel();
    }

    /** The two tiles a placement arms "Place &lt;item&gt;" on -- one step ahead, one step behind
     * the local player's own current course position, by plain pathIndex +-1 (not graph-aware, same
     * V1 simplification the server's own place-coin-trap endpoint uses -- forks aren't this
     * feature's concern). Empty if nothing's armed, the course is empty, or the local player isn't
     * tracked yet. */
    public List<WorldPoint> getItemPlacementCandidates()
    {
        if (itemPlacementKey == null) return Collections.emptyList();
        String self = localRsn();
        if (self == null) return Collections.emptyList();
        int length = tileReducer.courseLength();
        if (length == 0) return Collections.emptyList();

        int pos = getPlayerPosition(self);
        List<WorldPoint> candidates = new ArrayList<>(2);
        TileReducer.TileEntry front = tileReducer.tileAtIndex((pos + 1) % length);
        TileReducer.TileEntry behind = tileReducer.tileAtIndex((pos - 1 + length) % length);
        if (front != null) candidates.add(front.point);
        if (behind != null) candidates.add(behind.point);
        return candidates;
    }

    /** Spends the armed Coin Trap by placing it at {@code point} -- called from the in-world
     * "Place Coin Trap" menu entry (see addItemPlacementMenuEntries), which only ever offers this
     * on one of getItemPlacementCandidates()'s own two tiles. Clears placement mode optimistically
     * before the call even goes out, same as every other fire-and-forget action method here (a 409
     * still surfaces as a chat message, it just doesn't re-arm placement automatically -- the
     * player can start over via the panel if they want to retry). */
    private void placeCoinTrapAt(WorldPoint point)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        itemPlacementKey = null;
        refreshPanel();
        if (self == null || gid == null || token == null) return;

        executor.submit(() ->
        {
            try { apiClient.placeCoinTrap(gid, self, token, point.getX(), point.getY(), point.getPlane()); }
            catch (Exception e)
            {
                log.warn("Place Coin Trap failed", e);
                addChatMessage("Failed to place Coin Trap: " + e.getMessage());
            }
        });
    }

    public void leaveGame()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) { resetState(); return; }

        executor.submit(() ->
        {
            try { apiClient.leaveGame(gid, self, token); }
            catch (Exception e) { log.warn("Leave game failed", e); }
        });
        resetState();
    }

    /** Whether the turn order already has MAX_PLAYERS seats filled -- the client-side gate on
     * "Add to Game" (menu entry and roster popup both check this). The server doesn't currently
     * enforce this cap itself, so it's a UI guard rather than a real limit -- consistent with the
     * rest of this app's "friendly pickup game" trust model (see Gnomeball's LIMITATIONS.md). */
    public boolean isGameFull()
    {
        return rosterReducer.countRole(RunePartyRole.PLAYER) >= MAX_PLAYERS;
    }

    /** Host-only: promotes a spectator into the turn order (or, symmetrically, could demote a
     * player back to spectator). Joining a game only ever grants SPECTATOR -- see ApiClient.assignRole
     * -- so this is the only path onto the roster's turn order. */
    public void assignRole(String playerRsn, RunePartyRole role)
    {
        if (!isHost() || gameId == null) return;

        final String gid = gameId;
        final String wk = writeKey;
        executor.submit(() ->
        {
            try { apiClient.assignRole(gid, wk, playerRsn, role); }
            catch (Exception e)
            {
                log.warn("Assign role failed", e);
                addChatMessage("Failed to update " + playerRsn + "'s role: " + e.getMessage());
            }
        });
    }

    // -------------------------------------------------------------------------
    // Course building (host, LOBBY only) -- same placement flow as Gnomeball's
    // field builder: pick a preset, enter Place mode, then right-click a
    // ground tile to commit its footprint there. There's no per-preset
    // removal -- clearCourse() below is the host's one "start over" tool.
    // -------------------------------------------------------------------------

    public void selectPreset(CoursePreset preset)
    {
        selectedPreset = preset;
        refreshPanel();
    }

    public void enterCoursePlacementMode()
    {
        coursePlacementMode = true;
        refreshPanel();
    }

    public void cancelPresetMode()
    {
        coursePlacementMode = false;
        refreshPanel();
    }

    public void rotatePresetNext()
    {
        presetRotationSteps = (presetRotationSteps + 1) % 4;
    }

    /** Unmarks every currently-committed course tile -- the host's "start over" button. */
    public void clearCourse()
    {
        final String gid = gameId;
        final String wk = writeKey;
        if (gid == null || wk == null) return;

        Set<WorldPoint> uniquePoints = new HashSet<>();
        for (TileReducer.TileEntry entry : tileReducer.snapshot()) uniquePoints.add(entry.point);
        if (uniquePoints.isEmpty()) return;

        List<ApiClient.PointSpec> pointSpecs = new ArrayList<>(uniquePoints.size());
        for (WorldPoint wp : uniquePoints)
        {
            pointSpecs.add(new ApiClient.PointSpec(wp.getX(), wp.getY(), wp.getPlane(), null));
        }

        executor.submit(() ->
        {
            try { apiClient.unmarkTiles(gid, wk, pointSpecs); }
            catch (Exception e) { log.warn("Clear course failed", e); }
        });
    }

    private void addPresetMenuEntries()
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint center = tile.getWorldLocation();
        if (center == null) return;
        CoursePreset preset = selectedPreset;
        if (preset == null) return;

        client.createMenuEntry(-1)
            .setOption("Cancel")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelPresetMode());

        client.createMenuEntry(-1)
            .setOption("Rotate Course")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> rotatePresetNext());

        int degrees = presetRotationSteps * 90;
        String suffix = degrees != 0 ? " (" + degrees + "°)" : "";
        client.createMenuEntry(-1)
            .setOption("<col=00FF00>Place " + preset.name + suffix + "</col>")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> commitPreset(center));
    }

    /** Same "Walk here" -> custom RUNELITE entries idiom as addPresetMenuEntries, for an armed
     * requires_placement item -- only offered on the exact tile the cursor's currently over, and
     * only when that tile is genuinely one of getItemPlacementCandidates()'s own two (the server
     * would 409 on anything else anyway, this just keeps the menu from offering a doomed option).
     * Coin Trap is the only requires_placement item so far, hence the direct placeCoinTrapAt call
     * rather than a more general dispatch -- generalize this once a second one exists. */
    private void addItemPlacementMenuEntries()
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint point = tile.getWorldLocation();
        if (point == null) return;
        String itemKey = itemPlacementKey;
        if (itemKey == null || !getItemPlacementCandidates().contains(point)) return;

        client.createMenuEntry(-1)
            .setOption("Cancel")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelItemPlacement());

        client.createMenuEntry(-1)
            .setOption("<col=00FF00>Place " + Items.get(itemKey).getDisplayName() + "</col>")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> placeCoinTrapAt(point));
    }

    private void commitPreset(WorldPoint center)
    {
        CoursePreset preset = selectedPreset;
        int rotationSteps = presetRotationSteps;
        cancelPresetMode();
        if (!isHost() || gameId == null || preset == null) return;

        List<CoursePreset.PlacedTile> placed = preset.layout(center, rotationSteps);
        List<ApiClient.TileSpec> tileSpecs = new ArrayList<>(placed.size());
        for (int i = 0; i < placed.size(); i++)
        {
            CoursePreset.PlacedTile pt = placed.get(i);
            // List order IS path order (see CoursePreset's own class doc) -- this is the one
            // place that turns "position i in the list" into an explicit pathIndex, since once
            // this leaves as a TileSpec the server/TileReducer only ever see unordered tiles. A
            // decorative tile (see PlacedTile#decorative) gets no pathIndex at all instead -- it's
            // a modifier stacked on another tile's position, not a course stop of its own.
            Integer pathIndex = pt.decorative ? null : i;
            tileSpecs.add(new ApiClient.TileSpec(pt.point.getX(), pt.point.getY(), pt.point.getPlane(), pt.tileType, pt.color, null, pathIndex, pt.nextIndices));
        }

        final String gid = gameId;
        final String wk = writeKey;
        executor.submit(() ->
        {
            try { apiClient.markTiles(gid, wk, tileSpecs); }
            catch (Exception e) { log.warn("Commit course failed", e); }
        });
    }

    // -------------------------------------------------------------------------
    // Movement -- detect arrival at a rolled destination the same way Gnomeball
    // detects zone/out-of-bounds crossings: watch the local player's position
    // every tick rather than relying on a click/animation trigger.
    // -------------------------------------------------------------------------

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (phase != GamePhase.ACTIVE) return;

        // Refreshed unconditionally, ahead of the early returns below -- isLocalPlayerReadyToRoll
        // needs this cache kept current every tick regardless of pendingRoll/arrivalSubmitted/etc,
        // since it's read from contexts (RunePartyPanel) that can't safely compute it live. See the
        // field's own doc.
        String self = localRsn();
        Player selfPlayer = client.getLocalPlayer();
        standingOnTrackedPositionCached = self != null && selfPlayer != null && isStandingOnTrackedPosition(selfPlayer, self);

        // GAME_STARTED fired but turn order hasn't begun yet (currentTurnRsn still null) -- this
        // is the gathering window AnnouncementOverlay/TileOverlay's start-tile arrow cover; watch
        // for the local player reaching the START tile instead of a rolled destination.
        if (currentTurnRsn == null)
        {
            checkGatheringAtStart();
            return;
        }

        if (!pendingRoll || arrivalSubmitted) return;

        if (self == null || !self.equalsIgnoreCase(currentTurnRsn)) return;

        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        if (pos == null) return;

        Integer indexHere = tileReducer.pathIndexAt(pos);
        if (indexHere == null || !pendingTargetIndices.contains(indexHere)) return;

        arrivalSubmitted = true;
        confirmArrival(pos);
    }

    private void checkGatheringAtStart()
    {
        if (startConfirmSubmitted) return;

        String self = localRsn();
        if (self == null) return;
        if (rosterReducer.getRole(self) != RunePartyRole.PLAYER) return; // only seated players need to report in

        Player localPlayer = client.getLocalPlayer();
        WorldPoint pos = localPlayer != null ? localPlayer.getWorldLocation() : null;
        if (pos == null) return;

        // The START tile is always path index 0 by construction (see CoursePreset), so this
        // doesn't need a dedicated TileReducer lookup for tileType==START.
        TileReducer.TileEntry start = tileReducer.tileAtIndex(0);
        if (start == null || !start.point.equals(pos)) return;

        startConfirmSubmitted = true;
        confirmStart(pos);
    }

    // -------------------------------------------------------------------------
    // Menu entries -- course placement/removal during LOBBY (mirrors Gnomeball's
    // field builder) and a host-only "Add to Game" on other players' Follow
    // option (mirrors Gnomeball's Follow -> Enlist). There's no dedicated
    // in-world button for course building, so "Walk here" on the relevant tile
    // is the entry point, same as Gnomeball's approach. Rolling dice is a
    // gesture trigger instead (see onAnimationChanged) rather than a menu entry.
    // -------------------------------------------------------------------------

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if ("Follow".equals(event.getOption()))
        {
            addToGameMenuEntry(event);
            return;
        }

        if (!"Walk here".equals(event.getOption())) return;
        if (phase == GamePhase.LOBBY && isHost() && coursePlacementMode)
        {
            addPresetMenuEntries();
            return;
        }
        if (phase == GamePhase.ACTIVE && itemPlacementKey != null)
        {
            addItemPlacementMenuEntries();
        }
    }

    /** Rolls the dice once the local player's Spin emote finishes on their own turn -- replaces the
     * old "right-click your tile -> Roll Dice" menu entry with a gesture trigger -- and, the same
     * way, responds to a pending Golden Gnome offer once a YES/NO emote finishes. Only reacts to
     * the local player's own animation (every client sees every nearby player's AnimationChanged,
     * so this would otherwise also fire for spectators watching someone else spin/nod/shake for
     * fun). Waits for the *next* animation change away from whichever emote ID matched -- i.e. the
     * emote actually finishing, not just starting -- so the roll (and the screen-centered dice
     * reveal every client sees, see AnnouncementOverlay#renderDiceRoll) or the offer response never
     * fires mid-emote; awaitingSpinFinish/awaitingGnomeYesFinish/awaitingGnomeNoFinish are what
     * carry that wait across the two AnimationChanged firings, exactly one set at a time since a
     * roll and an offer can never both be pending simultaneously (isLocalPlayerReadyToRoll already
     * requires no offer is pending). Gates the actual roll on isLocalPlayerReadyToRoll() -- same
     * check AnnouncementOverlay#renderSpinHint uses to decide whether to show the "Use the SPIN!
     * emote" reminder -- and the offer response on isLocalPlayerAwaitingGoldenGnomeResponse(), same
     * check AnnouncementOverlay#renderGoldenGnomeOffer uses, so neither hint is ever showing when
     * the matching emote wouldn't actually do anything. rollDice()/respondGoldenGnomeOffer() each
     * re-check their own state on top of this, this is just what decides *when* to call them. */
    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (phase != GamePhase.ACTIVE) return;

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || event.getActor() != localPlayer) return;

        int anim = localPlayer.getAnimation();

        if (anim == AnimationID.EMOTE_DANCE_SPIN)
        {
            if (!isLocalPlayerReadyToRoll()) return;
            awaitingSpinFinish = true;
            return;
        }

        if (anim == AnimationID.EMOTE_YES)
        {
            if (isLocalPlayerAwaitingGoldenGnomeResponse())
            {
                awaitingGnomeYesFinish = true;
            }
            else if (isLocalPlayerAwaitingMinigameReady())
            {
                awaitingMinigameReadyFinish = true;
            }
            return;
        }

        if (anim == AnimationID.EMOTE_NO)
        {
            if (!isLocalPlayerAwaitingGoldenGnomeResponse()) return;
            awaitingGnomeNoFinish = true;
            return;
        }

        if (awaitingSpinFinish)
        {
            awaitingSpinFinish = false;
            rollDice();
        }
        else if (awaitingGnomeYesFinish)
        {
            awaitingGnomeYesFinish = false;
            respondGoldenGnomeOffer(true);
        }
        else if (awaitingGnomeNoFinish)
        {
            awaitingGnomeNoFinish = false;
            respondGoldenGnomeOffer(false);
        }
        else if (awaitingMinigameReadyFinish)
        {
            awaitingMinigameReadyFinish = false;
            confirmMinigameReady();
        }
    }

    /** Whether the local player could actually roll the dice right now by performing the Spin
     * emote: it's genuinely their turn, no roll is already pending or in flight, no mini-game is
     * running, no Golden Gnome offer is awaiting their response (see
     * isLocalPlayerAwaitingGoldenGnomeResponse -- resolving that always takes priority over
     * rolling again), they're standing on their own tracked board position (see
     * isStandingOnTrackedPosition -- if they wandered off their last landed tile, spinning in place
     * does nothing until they walk back, see TileOverlay#renderReturnToPositionArrow), and their
     * own "<player>'s Turn"/"Your Turn!" banner has actually had its chance to appear.
     * currentTurnRsn itself is real state, set the instant TURN_STARTED lands -- but the banner
     * announcing it is cosmetic, deliberately delayed behind turnEffectGateUntil (see
     * scheduleTurnAnnouncement) so it doesn't stomp over e.g. the previous mini-game's rewards/
     * round recap still showing. Without the turnEffectGateUntil check below, this would go true
     * the instant currentTurnRsn updates, well before that banner's own delayed slot -- "Use the
     * SPIN! emote" popping up before "Your Turn!" has even shown. Single source of truth for "can
     * I roll right now" -- onAnimationChanged gates the real roll on this, AnnouncementOverlay#
     * renderSpinHint gates the "Use the SPIN! emote" reminder on the exact same thing, so the two
     * can never disagree about whether spinning would do anything. Reads
     * standingOnTrackedPositionCached rather than resolving the local player's position live, since
     * this is also called from RunePartyPanel (Swing EDT) -- see that field's own doc for why a
     * direct Player#getWorldLocation() call here would crash off the client thread. */
    public boolean isLocalPlayerReadyToRoll()
    {
        if (phase != GamePhase.ACTIVE || pendingRoll || rollRequestSubmitted || minigameActive) return false;
        if (goldenGnomeOfferRsn != null) return false;
        if (System.currentTimeMillis() < turnEffectGateUntil) return false;

        String self = localRsn();
        if (self == null || !self.equalsIgnoreCase(currentTurnRsn)) return false;

        return standingOnTrackedPositionCached;
    }

    /** Whether the table is genuinely waiting on *someone's* roll right now -- the same gating
     * isLocalPlayerReadyToRoll uses, minus the two checks that only make sense from the mover's own
     * perspective ("is it me" and "am I standing on my tracked tile", which a bystander has no way
     * to verify for someone else anyway). Used by AnnouncementOverlay#renderSpinHint to show
     * everyone *other* than the mover "Waiting for &lt;player&gt; to roll the dice..." instead of
     * showing them nothing at all while the mover sees "Use the SPIN! emote...". Deliberately
     * doesn't care whether the mover has actually walked back to their tile yet -- from a
     * bystander's vantage point "it's their turn and nobody's rolled" is the whole story either
     * way. */
    public boolean isAwaitingSomeonesRoll()
    {
        if (phase != GamePhase.ACTIVE || pendingRoll || minigameActive) return false;
        if (goldenGnomeOfferRsn != null) return false;
        if (System.currentTimeMillis() < turnEffectGateUntil) return false;
        return currentTurnRsn != null;
    }

    /** Whether the local player has a Golden Gnome offer awaiting their own YES/NO response --
     * single source of truth for "should a YES/NO emote actually do something right now," mirroring
     * isLocalPlayerReadyToRoll's role for the Spin emote. See onAnimationChanged (gates the real
     * response) and AnnouncementOverlay#renderGoldenGnomeOffer (gates the offer banner/instructions
     * on the exact same thing). */
    public boolean isLocalPlayerAwaitingGoldenGnomeResponse()
    {
        if (phase != GamePhase.ACTIVE || goldenGnomeOfferRsn == null) return false;
        String self = localRsn();
        return self != null && self.equalsIgnoreCase(goldenGnomeOfferRsn);
    }

    /** Whether the local player still needs to YES-emote ready for the current mini-game --
     * mirrors isLocalPlayerAwaitingGoldenGnomeResponse's role for that offer's YES/NO emotes. See
     * onAnimationChanged (gates the real confirmMinigameReady call) and
     * AnnouncementOverlay#renderMinigameReadyCheck (gates the "use the YES emote" instruction on
     * the exact same thing, so it stops nagging a player the instant their own ready lands). */
    public boolean isLocalPlayerAwaitingMinigameReady()
    {
        if (phase != GamePhase.ACTIVE || !minigameActive || minigameCountdownStarted) return false;
        String self = localRsn();
        return self != null && !minigameReadyRsns.contains(self.toLowerCase(Locale.ROOT));
    }

    /** Whether the panel should show the current mini-game's real play controls (see
     * RunePartyPanel#refresh, the only caller) rather than the spinner/instructions/ready-check
     * sequence still being in AnnouncementOverlay. minigameCountdownStarted is real state (applied
     * unconditionally, catch-up or not -- see the MINIGAME_COUNTDOWN_STARTED handler), but
     * minigameCountdownBannerUntil is cosmetic-only, and for a *live* client isn't even armed until
     * MINIGAME_COUNTDOWN_START_DELAY_MS after minigameCountdownStarted flips (see that handler) --
     * so during that pause it's legitimately still 0 while very much not yet playable.
     * minigameCountdownSkippedForClient is what tells that pause apart from a client that only
     * ever caught up on the fact that the whole sequence already happened -- only that client
     * should become playable immediately instead of waiting on a "3...2...1... BEGIN!" replay for
     * a moment that's long since passed. */
    public boolean isMinigamePlayable()
    {
        if (!minigameActive || !minigameCountdownStarted) return false;
        if (minigameCountdownSkippedForClient) return true;
        return minigameCountdownBannerUntil != 0 && System.currentTimeMillis() >= minigameCountdownBannerUntil;
    }

    /** Whether {@code localPlayer} is standing on {@code rsn}'s tracked board position -- see
     * getPlayerPosition and isLocalPlayerReadyToRoll, the only caller. False (not just "unknown")
     * if the course isn't marked or the local player's position isn't resolvable, same fail-closed
     * behavior as everywhere else that resolves a WorldPoint against the course. */
    private boolean isStandingOnTrackedPosition(Player localPlayer, String rsn)
    {
        WorldPoint pos = localPlayer.getWorldLocation();
        if (pos == null) return false;
        TileReducer.TileEntry tile = tileReducer.tileAtIndex(getPlayerPosition(rsn));
        return tile != null && tile.point.equals(pos);
    }

    /** Adds an "Add to Game" entry on another player's Follow option, host-only, so the host can
     * pull a spectator into the turn order without them running the join flow themselves --
     * joining only ever grants SPECTATOR (see assignRole's doc). Hidden once the target is already
     * a PLAYER, same as Gnomeball's Enlist submenu skipping the enlisted player's current role. */
    private void addToGameMenuEntry(MenuEntryAdded event)
    {
        if (!isHost() || gameId == null) return;
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return;
        if (!(event.getMenuEntry().getActor() instanceof Player)) return;

        Player target = (Player) event.getMenuEntry().getActor();
        if (target == null || target == client.getLocalPlayer() || target.getName() == null) return;

        String targetRsn = Text.toJagexName(target.getName());
        if (targetRsn == null || targetRsn.isBlank()) return;
        if (rosterReducer.getRole(targetRsn) == RunePartyRole.PLAYER) return;
        if (isGameFull()) return;

        client.createMenuEntry(-1)
            .setOption("Add to Game")
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE_PLAYER)
            .setIdentifier(event.getIdentifier())
            .onClick(me -> assignRole(targetRsn, RunePartyRole.PLAYER));
    }

    /** Pulls a fresh /roster snapshot and merges it into RosterReducer -- the only source for the
     * turn-order "number" every RunePartyColor lookup (roster panel, PlayerOverlay, TileOverlay's
     * target arrow) depends on, since it never travels in the event stream itself. */
    private void syncRosterSnapshot()
    {
        final String gid = gameId;
        if (gid == null) return;

        executor.submit(() ->
        {
            try
            {
                ApiClient.RosterSnapshot snapshot = apiClient.fetchRoster(gid);
                rosterReducer.syncFromRoster(snapshot.players);
            }
            catch (Exception ex)
            {
                log.warn("Fetch roster failed", ex);
            }
            refreshPanel();
        });
    }

    /** Pushes turnEffectGateUntil forward to at least {@code untilTimestamp} -- called by whatever
     * just started a turn-effect visual with its own on-screen duration (currently only the
     * COINS_CHANGED handler, passing coinPopupUntil). A future tile effect with its own timed
     * reveal (a teleport animation, a "steal coins" flourish, whatever comes next) should call this
     * the same way when it starts, and nothing else needs to change -- every "what's next"
     * announcement already waits on this one gate via scheduleAfterTurnEffects. Never moves the
     * gate backward, so two effects landing close together both get their own full window. */
    private void extendTurnEffectGate(long untilTimestamp)
    {
        turnEffectGateUntil = Math.max(turnEffectGateUntil, untilTimestamp);
    }

    /** Schedules {@code action} to run once every in-flight turn-effect visual has cleared (see
     * extendTurnEffectGate) plus a short POST_TURN_EFFECT_GRACE_MS beat, so an outgoing effect and
     * an incoming "turn's over" announcement never visually collide -- runs immediately (still off
     * the caller's thread) if nothing is currently gating. Cancels {@code previousTask} first, since
     * a stray double-fire of the caller (there shouldn't be one, but see EventSocket's
     * reconnect-task pattern for the same defensive cancel-before-reschedule) would otherwise leave
     * two competing delayed writes in flight; returns the new task so the caller can do the same on
     * its next call.
     * <p>
     * Synchronously reserves the gate through this effect's own {@code durationMs} -- via
     * extendTurnEffectGate, called here rather than left for {@code action} to do once it actually
     * fires -- before this method even returns. That matters whenever more than one of these gets
     * scheduled in the same tick from *different* events (e.g. MINIGAME_ENDED's
     * scheduleRoundCompleteBanner immediately followed by the new round's own TURN_STARTED calling
     * scheduleTurnAnnouncement): without reserving synchronously, the second call would compute its
     * own delay against a gate that doesn't know the first effect is coming yet -- its
     * gate-extension is still sitting inside its own not-yet-fired callback -- so both would end up
     * scheduled for the same moment instead of one waiting on the other. Shared by every "the turn
     * is over, here's what's next" announcement -- anything new in that category should go through
     * this too rather than growing its own bespoke delay math. */
    private ScheduledFuture<?> scheduleAfterTurnEffects(ScheduledFuture<?> previousTask, long durationMs, Runnable action)
    {
        if (previousTask != null) previousTask.cancel(false);

        long now = System.currentTimeMillis();
        long delay = turnEffectGateUntil > now ? (turnEffectGateUntil - now) + POST_TURN_EFFECT_GRACE_MS : 0;
        extendTurnEffectGate(now + delay + durationMs);

        // The panel (isLocalPlayerReadyToRoll-gated item/roll UI) only ever refreshes on an
        // explicit refreshPanel() call, unlike AnnouncementOverlay's per-frame render() -- so
        // without this, once turnEffectGateUntil lifts here with no new server event to trigger a
        // refresh (e.g. sitting on a finished "Your Turn!" banner with nothing else happening),
        // the item-use section/SPIN-adjacent panel state can go stale indefinitely. Fire one right
        // as this effect's own reservation of the gate expires so the panel re-checks readiness
        // the moment it's actually true, not just whenever the next unrelated event happens to land.
        uiTimerExec.schedule(this::refreshPanel, delay + durationMs, TimeUnit.MILLISECONDS);

        return uiTimerExec.schedule(action, delay, TimeUnit.MILLISECONDS);
    }

    /** Schedules AnnouncementOverlay's "<player>'s Turn" banner via scheduleAfterTurnEffects, so it
     * never appears while e.g. the previous mover's coin popup is still settling. */
    private void scheduleTurnAnnouncement(String rsn)
    {
        turnAnnounceTask = scheduleAfterTurnEffects(turnAnnounceTask, TURN_ANNOUNCE_DURATION_MS, () ->
        {
            turnAnnounceRsn = rsn;
            turnAnnounceUntil = System.currentTimeMillis() + TURN_ANNOUNCE_DURATION_MS;
        });
    }

    /** Schedules AnnouncementOverlay's "MINIGAME!" banner via scheduleAfterTurnEffects, so it never
     * appears while the last roller's own turn -- including their coin popup -- is still settling.
     * minigameActive/minigameInstructions are set immediately in the MINIGAME_STARTED handler,
     * unaffected by this delay: this only postpones the celebratory banner, not the mini-game
     * itself. scheduleAfterTurnEffects reserves the gate for this banner's own duration
     * synchronously, so scheduleMinigameSpinner (called right behind this one, same
     * MINIGAME_STARTED handler) waits for this banner to actually finish instead of appearing on
     * top of it. */
    private void scheduleMinigameBanner()
    {
        minigameBannerTask = scheduleAfterTurnEffects(minigameBannerTask, MINIGAME_BANNER_DURATION_MS, () ->
        {
            minigameBannerUntil = System.currentTimeMillis() + MINIGAME_BANNER_DURATION_MS;
            extendTurnEffectGate(minigameBannerUntil); // belt-and-suspenders against scheduler jitter -- see scheduleAfterTurnEffects' own synchronous reservation, the primary fix
        });
    }

    /** Schedules AnnouncementOverlay's mini-game selection spinner via scheduleAfterTurnEffects,
     * so it waits behind the "MINIGAME!" banner (scheduleMinigameBanner, called right before this
     * in the MINIGAME_STARTED handler) instead of both appearing at once. The gate is reserved for
     * the spin + settle-hold synchronously (see scheduleAfterTurnEffects), so the ready-check
     * screen -- which has no timed trigger of its own, see
     * AnnouncementOverlay#renderMinigameReadyCheck -- only starts reading as "the current screen"
     * once this finishes. */
    private void scheduleMinigameSpinner()
    {
        minigameSpinnerTask = scheduleAfterTurnEffects(minigameSpinnerTask, MINIGAME_SPINNER_DURATION_MS, () ->
        {
            minigameSpinnerStart = System.currentTimeMillis();
            minigameSpinnerUntil = minigameSpinnerStart + MINIGAME_SPINNER_DURATION_MS;
            extendTurnEffectGate(minigameSpinnerUntil); // belt-and-suspenders, see scheduleMinigameBanner's identical comment
        });
    }

    /** Schedules AnnouncementOverlay's item wheel reveal via scheduleAfterTurnEffects, so it waits
     * behind whatever turn-effect visual is already showing (a coin popup from the same landing,
     * the previous player's own effects still settling, etc.) instead of appearing on top of it.
     * {@code rsn}/{@code itemKey} are captured here rather than read back off some "current grant"
     * plugin field, since -- unlike the mini-game key, which stays put for the whole mini-game --
     * an item grant is a one-off event with nothing else keeping track of it in between. */
    private void scheduleItemSpinner(String rsn, String itemKey)
    {
        itemSpinnerTask = scheduleAfterTurnEffects(itemSpinnerTask, ITEM_SPINNER_DURATION_MS, () ->
        {
            itemGrantRsn = rsn;
            itemGrantKey = itemKey;
            itemSpinnerStart = System.currentTimeMillis();
            itemSpinnerUntil = itemSpinnerStart + ITEM_SPINNER_DURATION_MS;
            extendTurnEffectGate(itemSpinnerUntil);
        });
    }

    /** Schedules AnnouncementOverlay's "already have N items" announcement via
     * scheduleAfterTurnEffects -- fired instead of scheduleItemSpinner when the server's own
     * ITEM_CAP_BLOCKED lands (see app.py's ITEM_TILE handling), so it waits behind whatever
     * turn-effect visual is already showing the same way the item wheel itself would have. */
    private void scheduleItemCapBlockedAnnouncement(String rsn, int cap)
    {
        itemCapBlockedTask = scheduleAfterTurnEffects(itemCapBlockedTask, ITEM_CAP_BLOCKED_DURATION_MS, () ->
        {
            itemCapBlockedRsn = rsn;
            itemCapBlockedCap = cap;
            itemCapBlockedUntil = System.currentTimeMillis() + ITEM_CAP_BLOCKED_DURATION_MS;
            extendTurnEffectGate(itemCapBlockedUntil);
        });
    }

    /** Schedules AnnouncementOverlay's "You used/&lt;rsn&gt; used &lt;item&gt;!" banner via
     * scheduleAfterTurnEffects -- fired on ITEM_USED for whichever item opts in via
     * Item#hasUseAnnouncement (see that handler), so it waits behind whatever turn-effect visual is
     * already showing, same as scheduleItemCapBlockedAnnouncement. */
    private void scheduleItemUsedAnnouncement(String rsn, String itemKey)
    {
        itemUsedAnnounceTask = scheduleAfterTurnEffects(itemUsedAnnounceTask, ITEM_USED_ANNOUNCE_DURATION_MS, () ->
        {
            itemUsedAnnounceRsn = rsn;
            itemUsedAnnounceItemKey = itemKey;
            itemUsedAnnounceUntil = System.currentTimeMillis() + ITEM_USED_ANNOUNCE_DURATION_MS;
            extendTurnEffectGate(itemUsedAnnounceUntil);
        });
    }

    /** Schedules AnnouncementOverlay's "You/&lt;rsn&gt; landed on a Coin Trap!" banner via
     * scheduleAfterTurnEffects -- fired on COIN_TRAP_TRIGGERED, same shape as
     * scheduleItemUsedAnnouncement. {@code victimRsn} is whoever landed on it, not the trap's
     * owner -- the owner's own feedback is purely their +N coin popup (see the COINS_CHANGED
     * handler), no banner of their own. */
    private void scheduleCoinTrapTriggerAnnouncement(String victimRsn)
    {
        coinTrapAnnounceTask = scheduleAfterTurnEffects(coinTrapAnnounceTask, COIN_TRAP_ANNOUNCE_DURATION_MS, () ->
        {
            coinTrapAnnounceRsn = victimRsn;
            coinTrapAnnounceUntil = System.currentTimeMillis() + COIN_TRAP_ANNOUNCE_DURATION_MS;
            extendTurnEffectGate(coinTrapAnnounceUntil);
        });
    }

    /** Arms AnnouncementOverlay's mini-game rewards recap ("who got what") -- called from the
     * MINIGAME_ENDED handler, parsing its own "payouts" list once here rather than having
     * AnnouncementOverlay re-parse the raw event payload every frame. Extends turnEffectGateUntil
     * so both the round-complete recap (see scheduleRoundCompleteBanner) and the new round's first
     * TURN_STARTED banner wait behind this one instead of overlapping it. */
    private void triggerMinigameRewardsBanner(JsonObject payload)
    {
        minigameRewards = safeMinigameRewards(payload, "payouts");
        minigameRewardsBannerUntil = System.currentTimeMillis() + MINIGAME_REWARDS_BANNER_DURATION_MS;
        extendTurnEffectGate(minigameRewardsBannerUntil);
    }

    /** Schedules AnnouncementOverlay's post-round "ROUND x" / "Current Standings" recap via
     * scheduleAfterTurnEffects, so it waits behind the mini-game rewards recap
     * (triggerMinigameRewardsBanner) that always fires first on the same MINIGAME_ENDED event,
     * instead of both appearing at once. Snapshots getCurrentRound() -- the round about to start,
     * not the one that just finished (completedRounds is already incremented by the time this
     * runs, so getCurrentRound() here is the same "upcoming round" number the next TURN_STARTED's
     * own banner and StatsOverlay's live "ROUND x/y" line would show). Not called at all for the
     * game's final round -- see the MINIGAME_ENDED handler -- since triggerGameOverSequence reveals
     * those same standings itself right after, and this plain recap would spoil that. */
    private void scheduleRoundCompleteBanner()
    {
        roundCompleteBannerTask = scheduleAfterTurnEffects(roundCompleteBannerTask, ROUND_COMPLETE_BANNER_DURATION_MS, () ->
        {
            roundCompleteRoundNumber = getCurrentRound();
            roundCompleteBannerUntil = System.currentTimeMillis() + ROUND_COMPLETE_BANNER_DURATION_MS;
            extendTurnEffectGate(roundCompleteBannerUntil); // belt-and-suspenders, see scheduleMinigameBanner's identical comment
        });
    }

    /** Final standings, ranked the same way renderRoundCompleteBanner already ranks the live
     * mid-game ones -- Golden Gnome count descending, coins as tiebreak -- snapshotted once on
     * GAME_ENDED rather than read live, since no further coin/gnome changes are possible once the
     * server's flipped the game out of ACTIVE. Spectators and never-joined seats are excluded, same
     * as every other standings view. */
    private List<RosterReducer.RosterEntry> computeFinalStandings()
    {
        List<RosterReducer.RosterEntry> standings = new ArrayList<>();
        for (RosterReducer.RosterEntry entry : rosterReducer.snapshot())
        {
            if (entry.role == RunePartyRole.PLAYER && entry.joined) standings.add(entry);
        }
        standings.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> e.goldenGnomeCount).reversed()
            .thenComparing(Comparator.comparingInt((RosterReducer.RosterEntry e) -> e.coins).reversed()));
        return standings;
    }

    /** Kicks off the end-game awards ceremony on GAME_ENDED -- a chain of banners, each scheduled
     * behind the last via scheduleAfterTurnEffects (see the field block's own doc for the full
     * sequence). This first step is itself gated the same way, since GAME_ENDED can land in the
     * very same event batch as the final round's own MINIGAME_ENDED (see app.py's
     * _resolve_minigame_if_complete) -- without waiting behind that banner's own gate reservation,
     * "GAME OVER!" would flash up mid-rewards-recap instead of politely queuing after it. No-ops if
     * nobody's actually seated (shouldn't happen for a game that reached GAME_ENDED, but a lone
     * host force-ending an empty lobby is technically possible). */
    private void triggerGameOverSequence()
    {
        List<RosterReducer.RosterEntry> standings = computeFinalStandings();
        if (standings.isEmpty()) return;
        gameOverStandings = standings;

        gameOverTask = scheduleAfterTurnEffects(gameOverTask, GAME_OVER_TITLE_DURATION_MS, () ->
        {
            gameOverBannerUntil = System.currentTimeMillis() + GAME_OVER_TITLE_DURATION_MS;
            extendTurnEffectGate(gameOverBannerUntil);
            scheduleWinnerIntro();
        });
    }

    private void scheduleWinnerIntro()
    {
        gameOverTask = scheduleAfterTurnEffects(gameOverTask, WINNER_INTRO_DURATION_MS, () ->
        {
            winnerIntroBannerUntil = System.currentTimeMillis() + WINNER_INTRO_DURATION_MS;
            extendTurnEffectGate(winnerIntroBannerUntil);

            // Worst to best, stopping once only the top two remain -- e.g. a 4-player game reveals
            // 4th then 3rd, leaving 1st/2nd for the "And the winner is..." showdown. A 2-player (or
            // fewer) game has nothing to reveal here at all, so this list ends up empty and
            // schedulePlaceReveal falls straight through to scheduleWinnerSuspense.
            List<RosterReducer.RosterEntry> revealOrder = new ArrayList<>();
            for (int i = gameOverStandings.size() - 1; i >= 2; i--) revealOrder.add(gameOverStandings.get(i));
            schedulePlaceReveal(revealOrder, 0);
        });
    }

    private void schedulePlaceReveal(List<RosterReducer.RosterEntry> revealOrder, int index)
    {
        if (index >= revealOrder.size())
        {
            scheduleWinnerSuspense();
            return;
        }

        gameOverTask = scheduleAfterTurnEffects(gameOverTask, PLACE_REVEAL_DURATION_MS, () ->
        {
            RosterReducer.RosterEntry entry = revealOrder.get(index);
            placeRevealRsn = entry.rsn;
            placeRevealRank = gameOverStandings.indexOf(entry) + 1;
            placeRevealCoins = entry.coins;
            placeRevealGoldenGnomes = entry.goldenGnomeCount;
            placeRevealUntil = System.currentTimeMillis() + PLACE_REVEAL_DURATION_MS;
            extendTurnEffectGate(placeRevealUntil);
            schedulePlaceReveal(revealOrder, index + 1);
        });
    }

    private void scheduleWinnerSuspense()
    {
        gameOverTask = scheduleAfterTurnEffects(gameOverTask, WINNER_SUSPENSE_DURATION_MS, () ->
        {
            winnerSuspenseUntil = System.currentTimeMillis() + WINNER_SUSPENSE_DURATION_MS;
            extendTurnEffectGate(winnerSuspenseUntil);
            scheduleWinnerReveal();
        });
    }

    /** The ceremony's final step -- the winner's own name, held alongside ConfettiOverlay's burst
     * (confettiUntil, shorter than WINNER_REVEAL_DURATION_MS so the confetti finishes settling
     * while the name's still up rather than both cutting off together). gameOverStandings is sorted
     * winner-first, so index 0 is always the winner. */
    private void scheduleWinnerReveal()
    {
        gameOverTask = scheduleAfterTurnEffects(gameOverTask, WINNER_REVEAL_DURATION_MS, () ->
        {
            RosterReducer.RosterEntry winner = gameOverStandings.get(0);
            winnerRsn = winner.rsn;
            long now = System.currentTimeMillis();
            winnerRevealUntil = now + WINNER_REVEAL_DURATION_MS;
            confettiUntil = now + CONFETTI_DURATION_MS;
            addChatMessage(winner.rsn + " wins Rune Party!");
        });
    }

    /** Arms AnnouncementOverlay's "Welcome to Rune Party Showdown" title card -- called once, right
     * after createGame/joinGame succeeds, for the local player only (there's no server event for
     * this; it's purely a client-side "you're in!" splash, so it never fires for anyone already in
     * the lobby when someone else joins). */
    private void triggerWelcomeBanner()
    {
        welcomeBannerUntil = System.currentTimeMillis() + WELCOME_BANNER_DURATION_MS;
    }

    /** Silently replays a game's full event history via a one-time REST fetch before opening the
     * live WebSocket -- otherwise, since EventSocket's initial connect always asks for every event
     * from the beginning (afterSeq=0), a player joining a game already in progress would see every
     * banner, popup, and dice-roll animation from the whole game so far fire in rapid succession as
     * that backlog replayed. Real game state (whose turn it is, coin totals, board positions, tile
     * markers, the minigame-active flag, roster) still updates from every historical event exactly
     * as it would live -- see handleEvent's catchingUp parameter, which is the one flag that decides
     * "state always applies, cosmetic timers/banners/chat only when live" for every event type, so
     * adding a new tile effect or announcement later only ever needs to sort itself into one of
     * those two buckets, not duplicate this catch-up logic. Once the backlog is applied, the live
     * socket starts from the backlog's own latestSeq, so nothing replays twice and every event from
     * that point on gets full normal (animated) treatment. Falls back to the old full-live-replay
     * behavior if the backlog fetch itself fails, rather than silently connecting from an unknown
     * point and risking missed history. */
    private void connectEventStream(String gameId, String rsn)
    {
        try
        {
            ApiClient.ReadEventsResponse backlog = apiClient.readEvents(gameId, 0);
            for (ApiClient.EventOut event : backlog.events)
            {
                handleEvent(event, true);
            }
            syncRosterSnapshot(); // one fresh roster read covers every PLAYER_JOINED/ROLE_ASSIGNED/PLAYER_LEFT skipped above, instead of one REST call per historical event
            refreshPanel();
            eventSocket.start(gameId, backlog.latestSeq, rsn);
        }
        catch (Exception e)
        {
            log.warn("Failed to fetch event backlog before connecting -- falling back to a full live replay", e);
            eventSocket.start(gameId, rsn);
        }
    }

    // -------------------------------------------------------------------------
    // Server-pushed events
    // -------------------------------------------------------------------------

    /** {@code catchingUp} is true only when this event is being silently replayed from
     * connectEventStream's initial backlog fetch, false for every event that arrives live over the
     * WebSocket. Real game state -- turn order, coins, board positions, tile markers, the
     * minigame-active flag, roster sync -- always applies either way, via rosterReducer/tileReducer
     * above and the unguarded field writes below. Anything purely cosmetic (a banner, a popup timer,
     * a chat line announcing something happened) is gated behind {@code !catchingUp} so a player who
     * joins mid-game only ever sees the game's *current* state, not a replay of how it got there. */
    private void handleEvent(ApiClient.EventOut e, boolean catchingUp)
    {
        if (e == null || e.type == null) return;

        rosterReducer.apply(e);
        tileReducer.apply(e);

        switch (e.type.toUpperCase(Locale.ROOT))
        {
            case "GAME_STARTED":
            {
                phase = GamePhase.ACTIVE;
                // currentTurnRsn stays null here -- see confirmStart/checkGatheringAtStart, turn
                // order doesn't actually begin until every seated PLAYER reports being at START.
                startConfirmSubmitted = false;
                // Real state, applied catch-up or not -- see getCurrentRound/StatsOverlay's
                // "ROUND x/y" line, the only consumer.
                Integer mr = safeInt(e.payload, "maxRounds");
                if (mr != null) maxRounds = mr;
                if (!catchingUp)
                {
                    gameStartBannerUntil = System.currentTimeMillis() + GAME_START_BANNER_DURATION_MS;
                }
                break;
            }

            case "GAME_ENDED":
                phase = GamePhase.ENDED;
                if (!catchingUp)
                {
                    triggerGameOverSequence();
                }
                break;

            case "PLAYER_READY":
                if (!catchingUp)
                {
                    addChatMessage(safeStr(e.payload, "player") + " is ready at the start!");
                }
                break;

            // None of these three carry a turn-order "number" in their payload -- the server only
            // ever computes it fresh from the whole event log on a roster read (see
            // _finalize_roster in app.py), and it can shift for everyone whenever the PLAYER set
            // changes (a join, a promotion, a leave). So on any of them, pull a fresh roster
            // snapshot rather than trying to derive numbers from the event stream itself. Skipped
            // during catch-up -- connectEventStream does one roster sync after the whole backlog
            // instead of one REST call per historical join/promotion/leave.
            case "PLAYER_JOINED":
            case "ROLE_ASSIGNED":
            case "PLAYER_LEFT":
                if (!catchingUp)
                {
                    syncRosterSnapshot();
                }
                break;

            case "TURN_STARTED":
            {
                currentTurnRsn = safeStr(e.payload, "player");
                pendingRoll = false;
                rollRequestSubmitted = false;
                awaitingSpinFinish = false;
                lastDiceRoll = null;
                pendingTargetIndices = Collections.emptyList();
                arrivalSubmitted = false;
                itemUsedThisTurn = false;
                if (!catchingUp)
                {
                    scheduleTurnAnnouncement(currentTurnRsn);
                    String self = localRsn();
                    if (self != null && self.equalsIgnoreCase(currentTurnRsn))
                    {
                        addChatMessage("It's your turn! Use the Spin emote to roll the dice.");
                    }
                }
                break;
            }

            case "DICE_ROLLED":
            {
                lastDiceRoll = safeInt(e.payload, "value");
                pendingTargetIndices = safeIntList(e.payload, "targetIndices");
                pendingRoll = true;
                rollRequestSubmitted = false; // pendingRoll is now the authoritative in-flight guard
                arrivalSubmitted = false;
                if (!catchingUp)
                {
                    String roller = safeStr(e.payload, "player");
                    addChatMessage(roller + " rolled a " + lastDiceRoll + "!");
                    if (lastDiceRoll != null)
                    {
                        Integer bonus = safeInt(e.payload, "bonus");
                        diceRollRsn = roller;
                        diceRollValue = lastDiceRoll;
                        diceRollBonus = bonus != null ? bonus : 0;
                        diceRollStart = System.currentTimeMillis();
                        diceRollUntil = diceRollStart + (diceRollBonus != 0 ? DICE_ROLL_BONUS_DURATION_MS : DICE_ROLL_DURATION_MS);
                    }
                }
                break;
            }

            case "PLAYER_MOVED":
            {
                String mover = safeStr(e.payload, "player");
                Integer toIndex = safeInt(e.payload, "toIndex");
                if (mover != null && toIndex != null)
                {
                    playerPositions.put(mover.toLowerCase(Locale.ROOT), toIndex);
                }
                break;
            }

            case "GOLDEN_GNOME_OFFERED":
            {
                // Real state, applied catch-up or not: non-null exactly while a response is
                // outstanding, gating both a YES/NO emote doing anything (see
                // isLocalPlayerAwaitingGoldenGnomeResponse) and rolling again (see
                // isLocalPlayerReadyToRoll). Pauses TILE_EFFECT/COINS_CHANGED for the underlying
                // tile until GOLDEN_GNOME_OFFER_RESOLVED -- see the server's confirm_arrival/
                // respond_golden_gnome_offer split.
                goldenGnomeOfferRsn = safeStr(e.payload, "player");
                if (!catchingUp)
                {
                    addChatMessage(goldenGnomeOfferRsn + " found a Golden Gnome!");
                }
                break;
            }

            case "GOLDEN_GNOME_OFFER_RESOLVED":
            {
                goldenGnomeOfferRsn = null; // always clear, catch-up or not -- real state
                awaitingGnomeYesFinish = false;
                awaitingGnomeNoFinish = false;
                // "declined" gets no banner/chat of its own -- the offer simply disappears, same
                // silence a declined confirm-start or an un-rolled turn would get. "purchased"'s
                // own announcement comes from the GOLDEN_GNOME_PURCHASED case below instead (it
                // carries the new total, which this event doesn't), so only "cant_afford" actually
                // needs to arm anything here.
                if (!catchingUp && "cant_afford".equals(safeStr(e.payload, "outcome")))
                {
                    goldenGnomeOutcome = "cant_afford";
                    goldenGnomeOutcomeRsn = safeStr(e.payload, "player");
                    goldenGnomeOutcomeBannerUntil = System.currentTimeMillis() + GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS;
                    extendTurnEffectGate(goldenGnomeOutcomeBannerUntil);
                    addChatMessage("Can't afford the Golden Gnome!");
                }
                break;
            }

            case "GOLDEN_GNOME_PURCHASED":
            {
                // The running total itself lives in rosterReducer (updated unconditionally above,
                // catch-up or not) -- everything here is purely cosmetic: the "You got a Golden
                // Gnome!" announcement (goldenGnomeOutcome, reusing the same banner
                // renderGoldenGnomeOutcome uses for "cant_afford") plus the "+1 Golden Gnome"
                // popup, both fired from this one event since it's the only one carrying the new
                // total the popup needs.
                if (!catchingUp)
                {
                    goldenGnomePopupRsn = safeStr(e.payload, "player");
                    Integer total = safeInt(e.payload, "goldenGnomeCount");
                    goldenGnomePopupNewTotal = total != null ? total : 0;
                    goldenGnomePopupStart = System.currentTimeMillis();
                    goldenGnomePopupUntil = goldenGnomePopupStart + COIN_POPUP_DURATION_MS;
                    extendTurnEffectGate(goldenGnomePopupUntil);

                    goldenGnomeOutcome = "purchased";
                    goldenGnomeOutcomeRsn = goldenGnomePopupRsn; // same event, same player
                    goldenGnomeOutcomeBannerUntil = System.currentTimeMillis() + GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS;
                    extendTurnEffectGate(goldenGnomeOutcomeBannerUntil);

                    addChatMessage(goldenGnomePopupRsn + " got a Golden Gnome!");
                }
                break;
            }

            case "ITEM_GRANTED":
            {
                // Inventory itself is updated unconditionally by rosterReducer.apply above --
                // everything here is purely the wheel reveal's own cosmetics.
                if (!catchingUp)
                {
                    String rsn = safeStr(e.payload, "player");
                    String itemKey = safeStr(e.payload, "itemKey");
                    String itemDisplayName = safeStr(e.payload, "itemDisplayName");
                    scheduleItemSpinner(rsn, itemKey);
                    addChatMessage(rsn + " got " + itemDisplayName + "!");
                }
                break;
            }

            case "ITEM_CAP_BLOCKED":
            {
                // No inventory change -- the server refused to grant anything, see app.py's
                // ITEM_TILE handling. Purely cosmetic, same as ITEM_GRANTED's own reveal.
                if (!catchingUp)
                {
                    String rsn = safeStr(e.payload, "player");
                    Integer cap = safeInt(e.payload, "itemCap");
                    scheduleItemCapBlockedAnnouncement(rsn, cap != null ? cap : 0);
                    addChatMessage(rsn + " already has too many items!");
                }
                break;
            }

            case "ITEM_USED":
            {
                // Real state, applied catch-up or not: an ITEM_USED can only ever be inserted for
                // the current turn's player (see the server's _require_ready_to_act), so this is
                // always the same turn TURN_STARTED just reset it for. Inventory itself is already
                // decremented unconditionally by rosterReducer.apply above.
                itemUsedThisTurn = true;
                if (!catchingUp)
                {
                    String rsn = safeStr(e.payload, "player");
                    String itemKey = safeStr(e.payload, "itemKey");
                    if (Items.get(itemKey).hasUseAnnouncement())
                    {
                        scheduleItemUsedAnnouncement(rsn, itemKey);
                    }
                }
                break;
            }

            case "GOLDEN_GNOME_MOVED":
            {
                // The real relocation is carried by the paired TILE_UNMARKED/TILE_MARKED events,
                // already applied unconditionally via tileReducer.apply above (catch-up or not) by
                // the time this case even runs. Everything here is choreographing the *visual*
                // catch-up -- see goldenGnomeMoveOldPoint/goldenGnomeMoveNewPoint's own doc -- so
                // it's entirely skipped during catch-up like every other purely-visual event.
                // Sequence: spotanim at the old spot -> (VANISH_DELAY later) model disappears ->
                // (GAP after the spotanim) spotanim at the new spot -> (APPEAR_DELAY later) model
                // appears, rather than the model instantly teleporting while the spotanims play
                // catch-up after the fact.
                if (!catchingUp)
                {
                    WorldPoint oldPoint = safeWorldPoint(e.payload, "oldPoint");
                    WorldPoint newPoint = safeWorldPoint(e.payload, "newPoint");

                    if (oldPoint != null)
                    {
                        triggerSpotAnimAtWorldPoint(GOLDEN_GNOME_MOVE_SPOTANIM_ID, oldPoint);
                        goldenGnomeMoveOldPoint = oldPoint;
                        goldenGnomeMoveHideOldAt = System.currentTimeMillis() + GOLDEN_GNOME_MOVE_VANISH_DELAY_MS;
                    }
                    if (newPoint != null)
                    {
                        uiTimerExec.schedule(() ->
                        {
                            triggerSpotAnimAtWorldPoint(GOLDEN_GNOME_MOVE_SPOTANIM_ID, newPoint);
                            goldenGnomeMoveNewPoint = newPoint;
                            goldenGnomeMoveShowNewAt = System.currentTimeMillis() + GOLDEN_GNOME_MOVE_APPEAR_DELAY_MS;
                        }, GOLDEN_GNOME_MOVE_SPOTANIM_GAP_MS, TimeUnit.MILLISECONDS);
                    }
                }
                break;
            }

            case "TILE_EFFECT":
            {
                // PATH is the only tile type with a real effect so far (see the COINS_CHANGED
                // case below, which is what actually pays it out) -- every other type is still a
                // no-op, but the event fires for all of them so this chat line is always accurate.
                if (!catchingUp)
                {
                    addChatMessage(safeStr(e.payload, "player") + " landed on a " + safeStr(e.payload, "tileType") + " tile.");
                }
                break;
            }

            case "COIN_TRAP_TRIGGERED":
            {
                // Real-time, not chained behind scheduleAfterTurnEffects -- unlike the announcement
                // banner below, the model/animation choreography is tied to a specific spot on the
                // board (see TileOverlay#updateCoinTrapModels), not the screen-centered UI other
                // "what happened" banners share a queue for, so delaying it to wait its turn would
                // just look like the trap sprang for no visible reason moments after the player
                // actually landed on it.
                if (!catchingUp)
                {
                    String victim = safeStr(e.payload, "player");
                    String owner = safeStr(e.payload, "owner");
                    Integer stolen = safeInt(e.payload, "stolen");
                    Integer x = safeInt(e.payload, "x");
                    Integer y = safeInt(e.payload, "y");
                    Integer plane = safeInt(e.payload, "plane");
                    if (x != null && y != null && plane != null)
                    {
                        coinTrapTriggerPoint = new WorldPoint(x, y, plane);
                        coinTrapTriggerUntil = System.currentTimeMillis() + COIN_TRAP_TRIGGER_PERSIST_MS;
                    }
                    if (victim != null)
                    {
                        scheduleCoinTrapTriggerAnnouncement(victim);
                        addChatMessage(victim + " landed on a Coin Trap" + (owner != null ? " set by " + owner : "")
                            + "! " + (stolen != null ? stolen : 0) + " coins stolen.");
                    }
                }
                break;
            }

            case "COINS_CHANGED":
            {
                // The standard-tile reward, an item's own coin effect, and a Coin Trap steal all
                // get the popup treatment -- a Golden Gnome purchase or mini-game payout already
                // has its own feedback (the roster/stats panels update, and submitMinigameResult's
                // caller sees the MINIGAME_ENDED chat line), so this stays scoped to the cases that
                // otherwise had no visible feedback at all. The real coin total itself lives in
                // rosterReducer (updated unconditionally above, catch-up or not) -- everything in
                // this block is purely the popup's own cosmetics.
                String coinsChangedReason = safeStr(e.payload, "reason");
                if (!catchingUp && ("standard_tile".equals(coinsChangedReason) || "item".equals(coinsChangedReason) || "coin_trap".equals(coinsChangedReason)))
                {
                    String coinsChangedRsn = safeStr(e.payload, "player");
                    Integer delta = safeInt(e.payload, "delta");
                    Integer total = safeInt(e.payload, "coins");

                    if (coinsChangedRsn != null)
                    {
                        String key = coinsChangedRsn.toLowerCase(Locale.ROOT);
                        long now = System.currentTimeMillis();

                        // A Golden Gnome purchase always resolves before the underlying tile's own
                        // effect (see the server's confirm_arrival/respond_golden_gnome_offer
                        // split), and a Coin Trap steal always resolves before it too (see
                        // _resolve_tile_effect_and_advance), so two or even three popups can land
                        // back-to-back for the same player within milliseconds of each other.
                        // Rather than show them at once, each new one queues behind whichever's
                        // already showing *for this same player* -- the Golden Gnome popup
                        // specifically (it's tracked separately, not in this queue) or the tail of
                        // this player's own coin-popup queue (see coinPopups's own doc for why this
                        // has to be a real queue, appended to, rather than a single slot overwritten
                        // each time). Doesn't apply to a different player, who gets their own popup
                        // immediately regardless of what's showing for anyone else (see the Coin
                        // Trap owner's simultaneous +N popup).
                        Deque<CoinPopup> queue = coinPopups.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
                        CoinPopup tailPopup = queue.peekLast();
                        boolean samePlayerGnomePopupShowing = coinsChangedRsn.equalsIgnoreCase(goldenGnomePopupRsn) && goldenGnomePopupUntil > now;
                        long start = samePlayerGnomePopupShowing ? goldenGnomePopupUntil
                            : (tailPopup != null && tailPopup.until > now) ? tailPopup.until
                            : now;
                        long until = start + COIN_POPUP_DURATION_MS;

                        queue.addLast(new CoinPopup(delta != null ? delta : 0, total != null ? total : 0, start, until));
                        extendTurnEffectGate(until);
                    }
                }
                break;
            }

            case "MINIGAME_STARTED":
                // minigameActive/minigameInstructions/minigameKey/minigameDisplayName take effect
                // immediately, catch-up or not -- a player joining mid-minigame needs the panel to
                // correctly show it's underway. Ready-check state resets fresh for this mini-game
                // instance too, real state regardless of catch-up. Only the celebratory banner and
                // the spinner that follows it are cosmetic-only and skipped during catch-up.
                minigameActive = true;
                minigameInstructions = safeStr(e.payload, "instructions");
                minigameKey = safeStr(e.payload, "key");
                minigameDisplayName = safeStr(e.payload, "displayName");
                minigameReadyRsns.clear();
                minigameCountdownStarted = false;
                minigameCountdownSkippedForClient = false;
                minigameCountdownBannerUntil = 0;
                minigameSpinnerStart = 0;
                minigameSpinnerUntil = 0;
                minigameSpinnerSkippedForClient = catchingUp;
                if (!catchingUp)
                {
                    scheduleMinigameBanner();
                    scheduleMinigameSpinner();
                    addChatMessage("Mini-game! " + minigameInstructions);
                }
                break;

            case "MINIGAME_PLAYER_READY":
            {
                // Real state, applied catch-up or not -- see isLocalPlayerAwaitingMinigameReady/
                // isMinigamePlayable, which both need an accurate ready set regardless of whether
                // this client watched it happen live.
                String readyRsn = safeStr(e.payload, "player");
                if (readyRsn != null) minigameReadyRsns.add(readyRsn.toLowerCase(Locale.ROOT));
                break;
            }

            case "MINIGAME_COUNTDOWN_STARTED":
                // Real state, applied catch-up or not -- see isMinigamePlayable, the reason this
                // is split from the cosmetic-only banner timestamp below.
                minigameCountdownStarted = true;
                minigameCountdownSkippedForClient = catchingUp;
                if (!catchingUp)
                {
                    // Arming minigameCountdownBannerUntil is itself delayed by
                    // MINIGAME_COUNTDOWN_START_DELAY_MS -- see renderMinigameReadyCheck, which
                    // keeps showing every player as "Ready!" until this actually fires, instead of
                    // the screen changing the instant the last person emotes.
                    uiTimerExec.schedule(() ->
                    {
                        minigameCountdownBannerUntil = System.currentTimeMillis() + MINIGAME_COUNTDOWN_DURATION_MS;
                        extendTurnEffectGate(minigameCountdownBannerUntil);
                        // RunePartyPanel only re-checks isMinigamePlayable() reactively, when
                        // refreshPanel() runs -- unlike AnnouncementOverlay's per-frame render(),
                        // the panel has no ordinary reason to refresh again once the countdown
                        // naturally finishes a few seconds from now (no further server event marks
                        // that moment). Without this, the panel's play controls silently never
                        // appeared until some unrelated event happened to trigger a refresh
                        // afterward. Chained here (nested inside this same callback) rather than
                        // scheduled as its own independent MINIGAME_COUNTDOWN_START_DELAY_MS +
                        // MINIGAME_COUNTDOWN_DURATION_MS delay off the original event -- two
                        // separately-scheduled tasks race against each other under normal
                        // scheduler jitter, and if this one's own delay ran even a couple of
                        // milliseconds long, the independently-scheduled refresh could fire before
                        // minigameCountdownBannerUntil above was actually reached, leaving
                        // isMinigamePlayable() still false at the one moment anything checked it.
                        // Nesting the schedule call here instead means its delay is always measured
                        // from this exact point, so it's guaranteed to fire strictly after
                        // minigameCountdownBannerUntil regardless of how long this task itself took
                        // to actually run.
                        uiTimerExec.schedule(this::refreshPanel, MINIGAME_COUNTDOWN_DURATION_MS, TimeUnit.MILLISECONDS);
                    }, MINIGAME_COUNTDOWN_START_DELAY_MS, TimeUnit.MILLISECONDS);
                }
                break;

            case "MINIGAME_ENDED":
                minigameActive = false;
                minigameInstructions = null;
                minigameKey = null;
                minigameDisplayName = null;
                minigameReadyRsns.clear();
                minigameCountdownStarted = false;
                minigameCountdownSkippedForClient = false;
                minigameCountdownBannerUntil = 0;
                // Real state, applied catch-up or not -- one MINIGAME_ENDED is exactly one
                // completed round (see the server's own _resolve_minigame_if_complete, which
                // counts these events the same way to decide when maxRounds is reached). See
                // getCurrentRound/StatsOverlay's "ROUND x/y" line, the only consumer.
                completedRounds++;
                if (!catchingUp)
                {
                    addChatMessage("Mini-game complete!");
                    triggerMinigameRewardsBanner(e.payload);
                    // Skipped on the game's last round -- GAME_ENDED fires right behind this same
                    // MINIGAME_ENDED (see app.py's _resolve_minigame_if_complete, which checks
                    // maxRounds immediately after inserting this event) and triggerGameOverSequence
                    // reveals the very same standings itself, dramatically, one place at a time.
                    // Showing the plain "Current Standings" recap first would spoil that reveal.
                    if (maxRounds <= 0 || completedRounds < maxRounds)
                    {
                        scheduleRoundCompleteBanner();
                    }
                }
                break;

            default:
                break;
        }

        if (!catchingUp)
        {
            refreshPanel();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void addChatMessage(String message)
    {
        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
    }

    /** Plays {@code spotAnimId} (see net.runelite.api.gameval.SpotanimID) once at a fixed world
     * point -- no travel, no actor attached. The client API has no direct "spawn a stationary
     * graphic" call (a real GraphicsObject is otherwise only ever created by the game engine
     * itself, in response to an actual server packet); the standard RuneLite-plugin trick for this
     * is a projectile whose source and target are the same point, which is exactly what this does.
     * Always hops onto the client thread, so any caller (an event handler off the client thread,
     * same as everything in handleEvent) can call this directly. */
    public void triggerSpotAnimAtWorldPoint(int spotAnimId, WorldPoint point, int durationCycles)
    {
        if (point == null) return;
        clientThread.invoke(() ->
        {
            int startCycle = client.getGameCycle();
            client.createProjectile(spotAnimId, point, 0, null, point, 0, null, startCycle, startCycle + durationCycles, 0, 0);
        });
    }

    public void triggerSpotAnimAtWorldPoint(int spotAnimId, WorldPoint point)
    {
        triggerSpotAnimAtWorldPoint(spotAnimId, point, SPOTANIM_DEFAULT_DURATION_CYCLES);
    }

    private void refreshPanel()
    {
        if (panel != null) SwingUtilities.invokeLater(panel::refresh);
    }

    private String localRsn()
    {
        if (client.getLocalPlayer() == null) return null;
        String name = client.getLocalPlayer().getName();
        return name != null ? Text.toJagexName(name) : null;
    }

    private static String safeStr(JsonObject o, String key)
    {
        return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsString() : null;
    }

    private static Integer safeInt(JsonObject o, String key)
    {
        try { return (o != null && o.has(key) && !o.get(key).isJsonNull()) ? o.get(key).getAsInt() : null; }
        catch (Exception ignored) { return null; }
    }

    /** Reads a nested {@code {x, y, plane}} object -- see GOLDEN_GNOME_MOVED's oldPoint/newPoint,
     * the only current callers. Null if the key's missing or any of the three fields is. */
    private static WorldPoint safeWorldPoint(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) return null;
        JsonObject p = o.getAsJsonObject(key);
        Integer x = safeInt(p, "x");
        Integer y = safeInt(p, "y");
        Integer plane = safeInt(p, "plane");
        if (x == null || y == null || plane == null) return null;
        return new WorldPoint(x, y, plane);
    }

    /** Reads DICE_ROLLED's targetIndices -- plural since a fork can offer more than one candidate
     * destination for a single roll (see TileOverlay#renderTargetArrow). Never null, only empty. */
    private static List<Integer> safeIntList(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.get(key).getAsJsonArray();
        List<Integer> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            try { out.add(arr.get(i).getAsInt()); }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        return out;
    }

    /** Reads MINIGAME_ENDED's "payouts" list -- {@code [{"player": rsn, "coins": int}, ...]} --
     * one entry per player a mini-game's own Minigame.resolve_rewards() decided to reward (see
     * app.py); players who got nothing simply aren't in the list. Never null, only empty. */
    private static List<MinigameReward> safeMinigameRewards(JsonObject o, String key)
    {
        if (o == null || !o.has(key) || o.get(key).isJsonNull() || !o.get(key).isJsonArray()) return Collections.emptyList();
        JsonArray arr = o.get(key).getAsJsonArray();
        List<MinigameReward> out = new ArrayList<>(arr.size());
        for (int i = 0; i < arr.size(); i++)
        {
            try
            {
                JsonObject entry = arr.get(i).getAsJsonObject();
                String rsn = safeStr(entry, "player");
                Integer coins = safeInt(entry, "coins");
                if (rsn != null && coins != null) out.add(new MinigameReward(rsn, coins));
            }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        return out;
    }

    private void resetState()
    {
        if (eventSocket != null) eventSocket.stop();
        if (turnAnnounceTask != null) { turnAnnounceTask.cancel(false); turnAnnounceTask = null; }
        if (minigameBannerTask != null) { minigameBannerTask.cancel(false); minigameBannerTask = null; }
        if (minigameSpinnerTask != null) { minigameSpinnerTask.cancel(false); minigameSpinnerTask = null; }
        if (itemSpinnerTask != null) { itemSpinnerTask.cancel(false); itemSpinnerTask = null; }
        if (itemCapBlockedTask != null) { itemCapBlockedTask.cancel(false); itemCapBlockedTask = null; }
        if (itemUsedAnnounceTask != null) { itemUsedAnnounceTask.cancel(false); itemUsedAnnounceTask = null; }
        if (coinTrapAnnounceTask != null) { coinTrapAnnounceTask.cancel(false); coinTrapAnnounceTask = null; }
        if (roundCompleteBannerTask != null) { roundCompleteBannerTask.cancel(false); roundCompleteBannerTask = null; }
        if (gameOverTask != null) { gameOverTask.cancel(false); gameOverTask = null; }
        turnEffectGateUntil = 0;
        gameId = null; writeKey = null; playerToken = null; joinCode = null; hostRsn = null;
        phase = GamePhase.DISCONNECTED;
        coursePlacementMode = false; selectedPreset = null; presetRotationSteps = 0;
        currentTurnRsn = null; lastDiceRoll = null; pendingRoll = false; rollRequestSubmitted = false;
        awaitingSpinFinish = false;
        awaitingGnomeYesFinish = false; awaitingGnomeNoFinish = false; awaitingMinigameReadyFinish = false;
        pendingTargetIndices = Collections.emptyList();
        arrivalSubmitted = false; itemUsedThisTurn = false; standingOnTrackedPositionCached = false;
        itemPlacementKey = null;
        minigameActive = false; minigameInstructions = null; minigameKey = null;
        minigameDisplayName = null;
        minigameSpinnerStart = 0; minigameSpinnerUntil = 0; minigameSpinnerSkippedForClient = false;
        itemSpinnerStart = 0; itemSpinnerUntil = 0; itemGrantRsn = null; itemGrantKey = null;
        itemCapBlockedUntil = 0; itemCapBlockedRsn = null; itemCapBlockedCap = 0;
        itemUsedAnnounceUntil = 0; itemUsedAnnounceRsn = null; itemUsedAnnounceItemKey = null;
        coinTrapAnnounceUntil = 0; coinTrapAnnounceRsn = null;
        coinTrapTriggerPoint = null; coinTrapTriggerUntil = 0;
        minigameReadyRsns.clear();
        minigameCountdownStarted = false; minigameCountdownSkippedForClient = false; minigameCountdownBannerUntil = 0;
        maxRounds = 0; completedRounds = 0;
        playerPositions.clear();
        turnAnnounceRsn = null; turnAnnounceUntil = 0; startConfirmSubmitted = false;
        welcomeBannerUntil = 0;
        minigameBannerUntil = 0;
        gameStartBannerUntil = 0;
        roundCompleteBannerUntil = 0; roundCompleteRoundNumber = 0;
        minigameRewardsBannerUntil = 0; minigameRewards = Collections.emptyList();
        gameOverStandings = Collections.emptyList(); gameOverBannerUntil = 0; winnerIntroBannerUntil = 0;
        placeRevealUntil = 0; placeRevealRsn = null; placeRevealRank = 0; placeRevealCoins = 0; placeRevealGoldenGnomes = 0;
        winnerSuspenseUntil = 0; winnerRevealUntil = 0; winnerRsn = null; confettiUntil = 0;
        goldenGnomeOfferRsn = null; goldenGnomeOutcome = null; goldenGnomeOutcomeRsn = null; goldenGnomeOutcomeBannerUntil = 0;
        goldenGnomePopupRsn = null; goldenGnomePopupNewTotal = 0; goldenGnomePopupStart = 0; goldenGnomePopupUntil = 0;
        goldenGnomeMoveOldPoint = null; goldenGnomeMoveHideOldAt = 0;
        goldenGnomeMoveNewPoint = null; goldenGnomeMoveShowNewAt = 0;
        coinPopups.clear();
        diceRollRsn = null; diceRollValue = 0; diceRollBonus = 0; diceRollStart = 0; diceRollUntil = 0;
        if (rosterReducer != null) rosterReducer.reset();
        if (tileReducer != null) tileReducer.reset();
        refreshPanel();
    }

    // -------------------------------------------------------------------------
    // Getters (consumed by TileOverlay now, PlayerOverlay/stats overlay/panel later)
    // -------------------------------------------------------------------------

    public GamePhase getPhase() { return phase; }
    public TileReducer getTileReducer() { return tileReducer; }
    public RosterReducer getRosterReducer() { return rosterReducer; }
    public String getGameId() { return gameId; }
    public String getJoinCode() { return joinCode; }
    public String getHostRsn() { return hostRsn; }
    public boolean isHost() { return writeKey != null; }
    public boolean isCoursePlacementMode() { return coursePlacementMode; }
    public CoursePreset getSelectedPreset() { return selectedPreset; }
    public int getPresetRotationSteps() { return presetRotationSteps; }
    public String getCurrentTurnRsn() { return currentTurnRsn; }
    public Integer getLastDiceRoll() { return lastDiceRoll; }
    public boolean isPendingRoll() { return pendingRoll; }
    public boolean isItemUsedThisTurn() { return itemUsedThisTurn; }
    public List<Integer> getPendingTargetIndices() { return pendingTargetIndices; }
    public boolean isMinigameActive() { return minigameActive; }
    /** The board tile (pathIndex) {@code rsn} is currently standing at, per the last PLAYER_MOVED
     * seen for them -- 0 (START) if they haven't moved yet this game. See TileOverlay#
     * renderReturnToPositionArrow, the only consumer. */
    public int getPlayerPosition(String rsn)
    {
        if (rsn == null) return 0;
        Integer idx = playerPositions.get(rsn.toLowerCase(Locale.ROOT));
        return idx != null ? idx : 0;
    }
    public String getMinigameInstructions() { return minigameInstructions; }
    public String getMinigameKey() { return minigameKey; }
    public String getMinigameDisplayName() { return minigameDisplayName; }
    public long getMinigameSpinnerStart() { return minigameSpinnerStart; }
    public long getMinigameSpinnerUntil() { return minigameSpinnerUntil; }
    public boolean isMinigameSpinnerSkippedForClient() { return minigameSpinnerSkippedForClient; }
    public long getItemSpinnerStart() { return itemSpinnerStart; }
    public long getItemSpinnerUntil() { return itemSpinnerUntil; }
    public String getItemGrantRsn() { return itemGrantRsn; }
    public String getItemGrantKey() { return itemGrantKey; }
    public long getItemCapBlockedUntil() { return itemCapBlockedUntil; }
    public String getItemCapBlockedRsn() { return itemCapBlockedRsn; }
    public int getItemCapBlockedCap() { return itemCapBlockedCap; }
    public long getItemUsedAnnounceUntil() { return itemUsedAnnounceUntil; }
    public String getItemUsedAnnounceRsn() { return itemUsedAnnounceRsn; }
    public String getItemUsedAnnounceItemKey() { return itemUsedAnnounceItemKey; }
    public long getCoinTrapAnnounceUntil() { return coinTrapAnnounceUntil; }
    public String getCoinTrapAnnounceRsn() { return coinTrapAnnounceRsn; }
    public WorldPoint getCoinTrapTriggerPoint() { return coinTrapTriggerPoint; }
    public long getCoinTrapTriggerUntil() { return coinTrapTriggerUntil; }
    public String getItemPlacementKey() { return itemPlacementKey; }
    public Set<String> getMinigameReadyRsns() { return minigameReadyRsns; }
    public boolean isMinigameCountdownStarted() { return minigameCountdownStarted; }
    public boolean isMinigameCountdownSkippedForClient() { return minigameCountdownSkippedForClient; }
    public long getMinigameCountdownBannerUntil() { return minigameCountdownBannerUntil; }
    public int getMaxRounds() { return maxRounds; }
    /** 1-indexed round currently in progress, capped at maxRounds so the round the final
     * MINIGAME_ENDED just completed doesn't briefly read as "one past the end" before GAME_ENDED
     * lands -- 0 before GAME_STARTED has set maxRounds at all. See StatsOverlay's "ROUND x/y" line,
     * the only consumer. */
    public int getCurrentRound()
    {
        if (maxRounds <= 0) return 0;
        return Math.min(completedRounds + 1, maxRounds);
    }
    public String getTurnAnnounceRsn() { return turnAnnounceRsn; }
    public long getTurnAnnounceUntil() { return turnAnnounceUntil; }
    public long getWelcomeBannerUntil() { return welcomeBannerUntil; }
    public long getMinigameBannerUntil() { return minigameBannerUntil; }
    public long getGameStartBannerUntil() { return gameStartBannerUntil; }
    public long getRoundCompleteBannerUntil() { return roundCompleteBannerUntil; }
    public int getRoundCompleteRoundNumber() { return roundCompleteRoundNumber; }
    public long getMinigameRewardsBannerUntil() { return minigameRewardsBannerUntil; }
    public List<MinigameReward> getMinigameRewards() { return minigameRewards; }
    public List<RosterReducer.RosterEntry> getGameOverStandings() { return gameOverStandings; }
    public long getGameOverBannerUntil() { return gameOverBannerUntil; }
    public long getWinnerIntroBannerUntil() { return winnerIntroBannerUntil; }
    public long getPlaceRevealUntil() { return placeRevealUntil; }
    public String getPlaceRevealRsn() { return placeRevealRsn; }
    public int getPlaceRevealRank() { return placeRevealRank; }
    public int getPlaceRevealCoins() { return placeRevealCoins; }
    public int getPlaceRevealGoldenGnomes() { return placeRevealGoldenGnomes; }
    public long getWinnerSuspenseUntil() { return winnerSuspenseUntil; }
    public long getWinnerRevealUntil() { return winnerRevealUntil; }
    public String getWinnerRsn() { return winnerRsn; }
    public long getConfettiUntil() { return confettiUntil; }
    /** {@code rsn}'s currently-showing coin popup, or null if none -- see PlayerOverlay#
     * drawCoinPopup, the only consumer. Drops expired entries off the front of this player's queue
     * first (see coinPopups's own doc for why it's a queue, not a single slot) so an old, already-
     * finished popup can never mask the one that's actually due to be showing right now; called
     * every render frame, so there's no need to prune anywhere else. */
    public CoinPopup getCoinPopup(String rsn)
    {
        if (rsn == null) return null;
        Deque<CoinPopup> queue = coinPopups.get(rsn.toLowerCase(Locale.ROOT));
        if (queue == null) return null;

        long now = System.currentTimeMillis();
        CoinPopup head;
        while ((head = queue.peekFirst()) != null && head.until <= now)
        {
            queue.pollFirst();
        }
        return head;
    }
    public String getDiceRollRsn() { return diceRollRsn; }
    public int getDiceRollValue() { return diceRollValue; }
    public int getDiceRollBonus() { return diceRollBonus; }
    public long getDiceRollStart() { return diceRollStart; }
    public long getDiceRollUntil() { return diceRollUntil; }
    public String getGoldenGnomeOfferRsn() { return goldenGnomeOfferRsn; }
    public String getGoldenGnomeOutcome() { return goldenGnomeOutcome; }
    public String getGoldenGnomeOutcomeRsn() { return goldenGnomeOutcomeRsn; }
    public long getGoldenGnomeOutcomeBannerUntil() { return goldenGnomeOutcomeBannerUntil; }
    public String getGoldenGnomePopupRsn() { return goldenGnomePopupRsn; }
    public int getGoldenGnomePopupNewTotal() { return goldenGnomePopupNewTotal; }
    public long getGoldenGnomePopupStart() { return goldenGnomePopupStart; }
    public long getGoldenGnomePopupUntil() { return goldenGnomePopupUntil; }
    public WorldPoint getGoldenGnomeMoveOldPoint() { return goldenGnomeMoveOldPoint; }
    public long getGoldenGnomeMoveHideOldAt() { return goldenGnomeMoveHideOldAt; }
    public WorldPoint getGoldenGnomeMoveNewPoint() { return goldenGnomeMoveNewPoint; }
    public long getGoldenGnomeMoveShowNewAt() { return goldenGnomeMoveShowNewAt; }
}
