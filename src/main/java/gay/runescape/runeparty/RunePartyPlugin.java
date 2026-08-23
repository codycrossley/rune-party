package gay.runescape.runeparty;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.inject.Provides;
import gay.runescape.runeparty.items.Items;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
import java.util.function.Consumer;
import java.util.function.Supplier;
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
import net.runelite.client.util.ImageUtil;
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

    /** "Looking straight down" in the client's own internal camera angle units, used by
     * toggleBoardView -- live-tested and confirmed by eye (the initial guess of 500, based on a
     * wrong assumption that the full pitch range was 0-2048, rendered close to head-on instead;
     * the real range runs considerably higher than that). Not derived from any formula, just the
     * value that was actually confirmed to look correctly top-down. */
    public static final int BOARD_VIEW_PITCH = 4160;
    /** Game-north "up" on screen once board view snaps to BOARD_VIEW_PITCH -- matches
     * RunePartyMapDialog's own north-up 2D map, so the two board-viewing tools agree on
     * orientation. */
    public static final int BOARD_VIEW_YAW = 0;
    /** Live-tested, confirmed-by-eye zoom-out level for board view -- see VARC_CAMERA_ZOOM. */
    public static final int BOARD_VIEW_ZOOM = 128;

    // Varc (varclient) id toggleBoardView's zoom-out writes to -- confirmed against RuneLite's own
    // bundled Camera plugin's decompiled source, whose makeSliderTooltip reads this exact varc for
    // the live zoom level.
    private static final int VARC_CAMERA_ZOOM = 74;

    // Client#setCameraMode/setCameraFocalPointX/Y/Z were tried here once, to pin board view over
    // the board's own center rather than wherever the local player stands -- live-tested and
    // confirmed BROKEN: setCameraMode(1) produced a solid black screen, not merely "didn't
    // detach." No plugin bundled with RuneLite itself ever calls either method (checked directly
    // against the client jar), and this result confirms why -- reverted, not attempted again with
    // a different guessed mode value. Board view stays centered on the player, same as it already
    // correctly does with just pitch/yaw/zoom below.

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

    /** Client-side key for the Coin Rush mini-game -- must match the server's own registration
     * (see minigames/coin_rush.py), the same "key" MINIGAME_STARTED's payload carries for every
     * mini-game. Compared directly against minigameKey wherever coin-rush-specific behavior
     * (spawn tracking, the live scoreboard, the auto-collect check in onGameTick) needs to gate on
     * "is this round actually Coin Rush," rather than going through Minigames#get. */
    public static final String COIN_RUSH_KEY = "coin-rush";

    /** Wall-clock length of one Coin Rush round, measured from the moment the round actually
     * becomes playable (see coinRushRoundStartAt/getCoinRushEndsAt) -- purely a client-side display
     * value for StatsOverlay's live countdown; the server is the one that actually ends the round
     * via MINIGAME_ENDED regardless of what this says. */
    public static final long COIN_RUSH_DURATION_MS = 30000;

    /** Coins a single Coin Rush pickup is worth -- must match the server's own COIN_RUSH_REWARD.
     * The server never actually credits this to the player's real balance until the round ends (a
     * single lump sum, see the "coin_rush" COINS_CHANGED case) -- this constant only exists so the
     * mid-round "+2" flash (see COIN_RUSH_COLLECTED handling) has a number to show immediately,
     * without waiting on that real payout. */
    public static final int COIN_RUSH_REWARD = 2;

    /** How long Coin Rush's mid-round "+2" flash stays up above a player's head after they collect
     * a coin -- see COIN_RUSH_COLLECTED's own enqueueCoinPopup call. Deliberately shorter than
     * COIN_POPUP_DURATION_MS: unlike a real coin popup, this one has no second "show the new total"
     * phase to hold for (see CoinPopup's own totalless doc), so it only ever needs to be on screen
     * long enough to read "+2" before fading. */
    public static final long COIN_RUSH_BUMP_POPUP_DURATION_MS = 1300;

    /** Client-side key for the True or False mini-game -- must match the server's own registration
     * (see minigames/true_or_false.py), same role COIN_RUSH_KEY plays for Coin Rush. */
    public static final String TRUE_OR_FALSE_KEY = "true-or-false";

    /** How long the question sits on screen before the answer countdown starts ticking, measured
     * from the moment TRUE_OR_FALSE_ROUND_STARTED lands (see trueOrFalseRoundStartedAt/
     * getTrueOrFalseAnswerWindowStartsAt) -- purely a client-side display value for
     * AnnouncementOverlay's renderTrueOrFalseQuestion (it hides the countdown number until this
     * elapses, so the question gets a beat to actually be read instead of the 5-second answer
     * clock ticking down from the instant it appears); the server is the one that actually decides
     * when answers stop counting regardless of what this says. Must match the server's own
     * TRUE_OR_FALSE_READING_SECONDS. */
    public static final long TRUE_OR_FALSE_READING_DURATION_MS = 3000;

    /** Wall-clock length of the actual answer countdown, once TRUE_OR_FALSE_READING_DURATION_MS
     * has elapsed (see getTrueOrFalseRoundEndsAt) -- purely a client-side display value for the
     * countdown in AnnouncementOverlay's renderTrueOrFalseQuestion; the server is the one that
     * actually ends the round via TRUE_OR_FALSE_ROUND_ENDED regardless of what this says. Must
     * match the server's own TRUE_OR_FALSE_ROUND_SECONDS. */
    public static final long TRUE_OR_FALSE_ROUND_DURATION_MS = 5000;

    /** How long AnnouncementOverlay's per-round reveal ("Correct!"/"Incorrect" + the right answer)
     * stays up after TRUE_OR_FALSE_ROUND_ENDED before the next question (or, on the final round,
     * the mini-game's own rewards recap) takes over. Purely a client-side timer, but matched
     * exactly to the server's own TRUE_OR_FALSE_INTERMISSION_SECONDS -- the real gap the server
     * now holds open before the next TRUE_OR_FALSE_ROUND_STARTED (or MINIGAME_ENDED) lands -- so
     * the reveal fills that whole gap rather than getting cut off early or sitting idle after the
     * next round's already live; see renderTrueOrFalseReveal's own gating. */
    public static final long TRUE_OR_FALSE_REVEAL_DURATION_MS = 2000;

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
    private CoinRushTimerOverlay coinRushTimerOverlay;
    private PlayerOverlay playerOverlay;
    private AnnouncementOverlay announcementOverlay;
    private ConfettiOverlay confettiOverlay;
    private RosterReducer rosterReducer;
    private ApiClient apiClient;
    private EventSocket eventSocket;
    private RunePartyPanel panel;
    private NavigationButton navButton;
    private RunePartyMapDialog mapDialog; // lazily created on the first "Show Map" click, see showMap()

    // Server-wide, not game-scoped -- fetched once at startup (see loadTileTypeCatalog) rather
    // than per-game like the roster, since the tiles/ registry it mirrors never changes at
    // runtime. Empty until the fetch completes (or forever, if it fails) -- every consumer
    // (TileOverlay, RunePartyMapDialog) already falls back to a default color/label on a miss.
    private volatile Map<String, ApiClient.TileTypeOut> tileTypeCatalog = new LinkedHashMap<>();

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
    private volatile ScheduledFuture<?> minigameSpinnerTask;

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
    // onAnimationChanged. At most one of these six "awaiting" flags is ever true at once: a roll
    // and a Golden Gnome offer are both only possible outside a mini-game (isLocalPlayerReadyToRoll
    // requires !minigameActive), a mini-game ready-check is only possible before its own countdown
    // starts (isLocalPlayerAwaitingMinigameReady requires !minigameCountdownStarted), and a True or
    // False answer is only possible once the round's actually playable (isLocalPlayerAwaitingTrueOrFalseAnswer
    // requires isMinigamePlayable(), which itself requires the countdown to have both started and
    // finished) -- so no two of these can ever overlap.
    private volatile boolean awaitingGnomeYesFinish = false;
    private volatile boolean awaitingGnomeNoFinish = false;
    private volatile boolean awaitingMinigameReadyFinish = false;
    private volatile boolean awaitingTrueOrFalseYesFinish = false;
    private volatile boolean awaitingTrueOrFalseNoFinish = false;
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
    // ---- board view (client-side camera state -- see toggleBoardView/restoreCameraFromBoardView,
    // the only writers). boardViewSavedPitch/Yaw/Zoom are only ever read/written from inside a
    // clientThread.invoke callback (same thread every writer already runs on), so no volatile
    // needed on those three; boardViewActive itself is read from RunePartyPanel (Swing EDT) via
    // isBoardViewActive(), hence volatile. ----
    private volatile boolean boardViewActive = false;
    private int boardViewSavedPitch;
    private int boardViewSavedYaw;
    private int boardViewSavedZoom;
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
    // already showing -- see scheduleItemSpinner). Payload identifies who got what, needed by the
    // reveal text ("You got..."/"<rsn> got...", mirroring renderGoldenGnomeOutcome's own
    // per-viewer split). Unlike the mini-game spinner, nothing else needs to distinguish "hasn't
    // started yet" from "this client only caught up after the fact" -- no other screen chains
    // behind this one the way the ready-check chains behind the mini-game spinner, so there's no
    // *SkippedForClient flag needed here. ----
    private final TimedBanner<ItemSpinnerPayload> itemSpinner = new TimedBanner<>();
    // ---- item cap announcement (cosmetic-only timing, chained the same way as the item spinner
    // above -- see scheduleItemCapBlockedAnnouncement). Fires instead of the item wheel when
    // ITEM_CAP_BLOCKED lands, so at most one of {itemSpinner.until, itemCapBlocked.until} is ever
    // "live" for the same landing. ----
    private final TimedBanner<ItemCapBlockedPayload> itemCapBlocked = new TimedBanner<>();
    // ---- item-used announcement (cosmetic-only timing, chained the same way as the item cap
    // banner above -- see scheduleItemUsedAnnouncement). Only fired for items that opt in via
    // Item#hasUseAnnouncement -- PlaceholderItem's coin change already has its own feedback. ----
    private final TimedBanner<ItemUsedAnnouncePayload> itemUsedAnnounce = new TimedBanner<>();
    // ---- Coin Trap trigger (cosmetic-only timing, chained the same way as the item-used
    // announcement above -- see scheduleCoinTrapTriggerAnnouncement). Payload is whoever landed on
    // it (the victim) -- the owner's own feedback is purely their coin popup, no banner of their
    // own. ----
    private final TimedBanner<String> coinTrapAnnounce = new TimedBanner<>(); // payload: victim rsn
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
    // ---- Coin Rush (server-driven spawns/collections -- see COIN_RUSH_SPAWN/COIN_RUSH_COLLECTED
    // handling). coinRushSpawns is real state, applied catch-up or not: every currently-live
    // spawn's WorldPoint keyed by the server's own spawn id, mirrored into a 3D model per spawn by
    // TileOverlay#updateCoinRushModels the same "diff against the live set" pattern
    // updateCoinTrapModels already uses. coinRushScores is this round's own live tally (lowercase
    // rsn -> coins collected so far), reset fresh on every MINIGAME_STARTED for COIN_RUSH_KEY --
    // read by StatsOverlay's live scoreboard, which replaces the normal roster view for exactly as
    // long as a Coin Rush round is playable. coinRushRoundStartAt is the wall-clock moment the
    // round actually became playable (see COIN_RUSH_DURATION_MS/getCoinRushEndsAt), set once per
    // round -- precisely for a live client (the same "BEGIN!" instant minigameCountdownBannerUntil
    // gets armed at), best-effort ("now") for a client that only caught up on an already-underway
    // round. ----
    private final Map<Integer, WorldPoint> coinRushSpawns = new ConcurrentHashMap<>();
    private final Map<String, Integer> coinRushScores = new ConcurrentHashMap<>();
    // Guards one spawn's own collect report against firing every tick while it's in flight -- same
    // role arrivalSubmitted plays for confirmArrival, just one per spawn id (via a Set) instead of
    // a single flag, since more than one spawn can be live -- and walked onto in quick succession
    // -- at the same time. See checkCoinRushCollection, the only writer besides the
    // COIN_RUSH_COLLECTED handler (which clears an entry once the server's own echo confirms it).
    private final Set<Integer> coinRushCollectSubmitted = ConcurrentHashMap.newKeySet();
    private volatile long coinRushRoundStartAt = 0;
    // ---- True or False (server-driven rounds -- see TRUE_OR_FALSE_ROUND_STARTED/ANSWERED/
    // ROUND_ENDED handling). All real state, applied catch-up or not: trueOrFalseQuestion/
    // RoundNumber are the current round's own question text and 1-indexed round number (null/0
    // once the round ends, until the next one starts or the mini-game itself ends).
    // trueOrFalseAnsweredRsns mirrors minigameReadyRsns's own "who's confirmed" role, just scoped
    // to the current round instead of the whole mini-game -- who's submitted an answer this round,
    // not what they answered (see renderTrueOrFalseQuestion, the "Ready screen"-style tally this
    // was asked for). trueOrFalseMyAnswer is the local player's own answer this round (null until
    // they've answered), read by isLocalPlayerAwaitingTrueOrFalseAnswer to stop a YES/NO emote
    // from resubmitting once they already have. trueOrFalseLastCorrectAnswer/Results are the most
    // recent TRUE_OR_FALSE_ROUND_ENDED's own reveal, held onto (not cleared) through the next
    // round's own trueOrFalseQuestion update, since renderTrueOrFalseReveal gates its own display
    // on trueOrFalseRevealUntil, not on whether a new question already exists. ----
    private volatile String trueOrFalseQuestion = null;
    private volatile int trueOrFalseRoundNumber = 0;
    private final Set<String> trueOrFalseAnsweredRsns = ConcurrentHashMap.newKeySet(); // lowercase rsn
    private volatile Boolean trueOrFalseMyAnswer = null;
    private volatile long trueOrFalseRoundStartedAt = 0; // wall-clock moment this round's own TRUE_OR_FALSE_ROUND_STARTED landed -- see getTrueOrFalseRoundEndsAt
    private volatile Boolean trueOrFalseLastCorrectAnswer = null;
    private volatile List<TrueOrFalseResult> trueOrFalseLastResults = Collections.emptyList();
    private volatile long trueOrFalseRevealUntil = 0;
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
    private final TimedBanner<String> turnAnnounce = new TimedBanner<>(); // payload: rsn

    // ---- welcome title card (client-side, local-player-only -- see triggerWelcomeBanner) ----
    private final TimedBanner<Void> welcomeBanner = new TimedBanner<>();

    // ---- minigame banner (server-driven, everyone sees it -- see MINIGAME_STARTED handling) ----
    private final TimedBanner<Void> minigameBanner = new TimedBanner<>();

    // ---- game-start banner (server-driven, everyone sees it -- see GAME_STARTED handling) ----
    private final TimedBanner<Void> gameStartBanner = new TimedBanner<>();

    // ---- round-complete banner (server-driven, everyone sees it -- see MINIGAME_ENDED handling
    // and scheduleRoundCompleteBanner). Its payload is the *upcoming* round -- the one about to
    // start, same number getCurrentRound() would return live -- snapshotted at trigger time so it
    // stays stable through the banner's own display window regardless of whatever completedRounds
    // does afterward. ----
    private final TimedBanner<Integer> roundCompleteBanner = new TimedBanner<>(); // payload: upcoming round number

    // ---- mini-game rewards recap (server-driven, everyone sees it -- see MINIGAME_ENDED handling
    // and triggerMinigameRewardsBanner). Shown *before* the round-complete recap above, via
    // scheduleRoundCompleteBanner deferring that one behind this banner's own gate extension. ----
    private final TimedBanner<List<MinigameReward>> minigameRewardsBanner = new TimedBanner<>();

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

    /** One player's outcome on a single True or False round -- see TRUE_OR_FALSE_ROUND_ENDED's own
     * "results" list and renderTrueOrFalseReveal, the only consumer. {@code answer} is null if
     * they never answered in time (always incorrect in that case). */
    public static class TrueOrFalseResult
    {
        public final String rsn;
        public final Boolean answer;
        public final boolean correct;

        public TrueOrFalseResult(String rsn, Boolean answer, boolean correct)
        {
            this.rsn = rsn;
            this.answer = answer;
            this.correct = correct;
        }
    }

    /** One cosmetic, client-only announcement banner's timing state -- until/payload/optionally a
     * chained task. Replaces what used to be 2-5 separate parallel fields per banner (an xUntil
     * timestamp, an xRsn/payload, sometimes an xStart, sometimes an xTask) -- see
     * ARCHITECTURE_REVIEW.md's C1 finding. Purely a value holder: this class still owns exactly
     * when/why each one gets armed (via its own scheduleXBanner/triggerX method) and every public
     * getter still has its own name/signature, just delegating to one of these instead of a raw
     * field -- AnnouncementOverlay and every other consumer needs no changes. Real,
     * server-authoritative state (minigameReadyRsns, coinRushSpawns, trueOrFalseQuestion, etc.) is
     * untouched -- this is purely the cosmetic-timer half. */
    private static final class TimedBanner<T>
    {
        volatile long start;
        volatile long until;
        volatile T payload;
        volatile ScheduledFuture<?> task;

        void reset()
        {
            if (task != null) { task.cancel(false); task = null; }
            start = 0;
            until = 0;
            payload = null;
        }
    }

    /** Payload for the item wheel reveal -- see scheduleItemSpinner. */
    private static final class ItemSpinnerPayload
    {
        final String rsn;
        final String itemKey;

        ItemSpinnerPayload(String rsn, String itemKey)
        {
            this.rsn = rsn;
            this.itemKey = itemKey;
        }
    }

    /** Payload for the "already have N items" announcement -- see scheduleItemCapBlockedAnnouncement. */
    private static final class ItemCapBlockedPayload
    {
        final String rsn;
        final int cap;

        ItemCapBlockedPayload(String rsn, int cap)
        {
            this.rsn = rsn;
            this.cap = cap;
        }
    }

    /** Payload for the "You/&lt;rsn&gt; used &lt;item&gt;!" banner -- see scheduleItemUsedAnnouncement. */
    private static final class ItemUsedAnnouncePayload
    {
        final String rsn;
        final String itemKey;

        ItemUsedAnnouncePayload(String rsn, String itemKey)
        {
            this.rsn = rsn;
            this.itemKey = itemKey;
        }
    }

    /** Payload for the Golden Gnome purchase outcome banner ("You got a Golden Gnome!"/"You can't
     * afford this!") -- see the GOLDEN_GNOME_OFFER_RESOLVED handler. */
    private static final class GoldenGnomeOutcomePayload
    {
        final String outcome; // "purchased" | "declined" | "cant_afford"
        final String rsn;

        GoldenGnomeOutcomePayload(String outcome, String rsn)
        {
            this.outcome = outcome;
            this.rsn = rsn;
        }
    }

    /** Payload for the Golden Gnome count "+1" popup -- see the GOLDEN_GNOME_OFFER_RESOLVED handler. */
    private static final class GoldenGnomePopupPayload
    {
        final String rsn;
        final int newTotal;

        GoldenGnomePopupPayload(String rsn, int newTotal)
        {
            this.rsn = rsn;
            this.newTotal = newTotal;
        }
    }

    /** Payload for one place-reveal step in the end-game ceremony -- see schedulePlaceReveal. */
    private static final class PlaceRevealPayload
    {
        final String rsn;
        final int rank; // 1-based rank within gameOverStandings
        final int coins;
        final int goldenGnomes;

        PlaceRevealPayload(String rsn, int rank, int coins, int goldenGnomes)
        {
            this.rsn = rsn;
            this.rank = rank;
            this.coins = coins;
            this.goldenGnomes = goldenGnomes;
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
    private volatile ScheduledFuture<?> gameOverTask; // one handle threaded through every step below, not owned by any single one
    private volatile List<RosterReducer.RosterEntry> gameOverStandings = Collections.emptyList();
    private final TimedBanner<Void> gameOverBanner = new TimedBanner<>();
    private final TimedBanner<Void> winnerIntroBanner = new TimedBanner<>();
    private final TimedBanner<PlaceRevealPayload> placeReveal = new TimedBanner<>();
    private final TimedBanner<Void> winnerSuspenseBanner = new TimedBanner<>();
    private final TimedBanner<String> winnerRevealBanner = new TimedBanner<>(); // payload: winner rsn
    private final TimedBanner<Void> confettiBanner = new TimedBanner<>();

    // ---- Golden Gnome offer (server-driven, everyone sees it -- see GOLDEN_GNOME_OFFERED/
    // GOLDEN_GNOME_OFFER_RESOLVED handling). goldenGnomeOfferRsn is real state (non-null exactly
    // while a response is outstanding, same role pendingRoll plays for a roll) -- it gates whether
    // a YES/NO emote does anything (see isLocalPlayerAwaitingGoldenGnomeResponse) as well as the
    // offer banner, and who AnnouncementOverlay#renderGoldenGnomeOffer addresses "You found..." to
    // vs "<rsn> found...". goldenGnomeOutcome below is purely the follow-up announcement ("You got
    // a Golden Gnome!"/"You can't afford this!"), cosmetic only -- its own rsn payload is what lets
    // that banner address the actual buyer ("You...") differently from everyone else watching
    // ("<rsn>..."), the same split goldenGnomeOfferRsn already does for the offer itself. ----
    private volatile String goldenGnomeOfferRsn = null;
    private final TimedBanner<GoldenGnomeOutcomePayload> goldenGnomeOutcome = new TimedBanner<>();

    // ---- Golden Gnome count popup (client-side timer -- see PlayerOverlay#drawGoldenGnomePopup,
    // same "+1" -> running-total shape and timing as the coin popup) ----
    private final TimedBanner<GoldenGnomePopupPayload> goldenGnomePopup = new TimedBanner<>();

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
     * queue rather than mutated in place. See enqueueCoinPopup for how start/until get computed.
     * {@code totalless} is true only for Coin Rush's mid-round "+2" flash (see COIN_RUSH_COLLECTED
     * handling) -- unlike every other coin popup, that one never actually changed the player's real
     * balance (Coin Rush pays out in one lump sum at the round's end instead, see COINS_CHANGED's
     * own "coin_rush" case), so PlayerOverlay#drawCoinPopup never advances a totalless popup past
     * its delta phase into showing {@code newTotal} -- there is no accurate total to show yet. */
    public static final class CoinPopup
    {
        public final int delta;
        public final int newTotal;
        public final long start;
        public final long until;
        public final boolean totalless;

        CoinPopup(int delta, int newTotal, long start, long until, boolean totalless)
        {
            this.delta = delta;
            this.newTotal = newTotal;
            this.start = start;
            this.until = until;
            this.totalless = totalless;
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
        loadTileTypeCatalog();
        rosterReducer = new RosterReducer();
        tileReducer = new TileReducer();

        tileOverlay = new TileOverlay(client, config, this, tileReducer);
        overlayManager.add(tileOverlay);

        statsOverlay = new StatsOverlay(config, this);
        overlayManager.add(statsOverlay);

        coinRushTimerOverlay = new CoinRushTimerOverlay(config, this);
        overlayManager.add(coinRushTimerOverlay);

        playerOverlay = new PlayerOverlay(client, config, this, rosterReducer, modelOutlineRenderer);
        overlayManager.add(playerOverlay);

        announcementOverlay = new AnnouncementOverlay(client, config, this);
        overlayManager.add(announcementOverlay);

        confettiOverlay = new ConfettiOverlay(client, this);
        overlayManager.add(confettiOverlay);

        panel = new RunePartyPanel(this);
        navButton = NavigationButton.builder()
            .tooltip("Rune Party")
            .icon(ImageUtil.loadImageResource(getClass(), "panel_icon.png"))
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
        if (tileOverlay != null) { tileOverlay.clearGoldenGnomeModels(); tileOverlay.clearCoinRushModels(); overlayManager.remove(tileOverlay); }
        if (statsOverlay != null) overlayManager.remove(statsOverlay);
        if (coinRushTimerOverlay != null) overlayManager.remove(coinRushTimerOverlay);
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

    /** Toggles between the normal player-driven camera and a steep, near-straight-down "board
     * view" -- see RunePartyPanel's "View Board" button, the only caller. Only ever adjusts
     * pitch/yaw *target* (Client#setCameraPitchTarget/setCameraYawTarget) plus zoom
     * (Client#setVarcIntValue) and the pitch relaxer that lifts the vanilla ~383-unit pitch cap
     * (Client#setCameraPitchRelaxerEnabled -- the exact mechanism RuneLite's own bundled Camera
     * plugin uses to let a player manually drag past that same cap) -- all three live-tested and
     * confirmed, see BOARD_VIEW_PITCH/YAW/ZOOM's own docs. Deliberately does NOT touch
     * Client#setCameraMode or Client#setCameraFocalPointX/Y/Z -- an earlier attempt at using them
     * to pin the view over the board's own center (rather than wherever the local player stands)
     * was live-tested and produced a solid black screen, not merely "didn't detach" -- see
     * VARC_CAMERA_ZOOM's neighboring comment. Reverted, not retried with a different guessed mode
     * value. That means this pans/tilts to look straight down from wherever the local player
     * already stands (the camera's normal focal point, left untouched) rather than pinning over
     * the board's own true center regardless of player position -- close enough for a course
     * that's normally small and directly underfoot during a turn, but not literally "locked to the
     * board" if the player wanders off it.
     * <p>
     * The pitch/yaw *target* setters are believed (from their own naming/getter pairing, not
     * independently verified) to write the same internal value the game's native mouse-drag
     * control already targets and eases toward every frame on its own, so this only ever needs to
     * set that target once per toggle rather than animate anything itself -- and the player's own
     * mouse can still freely drag away from it at any time even while "board view" is nominally
     * on, since nothing here disables normal input. */
    public void toggleBoardView()
    {
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return;

        // refreshPanel() is deliberately called from *inside* each clientThread.invoke callback,
        // not right after queuing it -- ClientThread#invoke runs its callback on a future client
        // tick, not synchronously, so calling refreshPanel() immediately after queuing (as this
        // used to) would refresh the panel's "View Board"/"Return to Normal View" label against
        // boardViewActive's *old* value, one tick before restoreCameraFromBoardView() actually
        // flips it -- the exact bug that left the button stuck reading "Return to Normal View"
        // after turning board view back off. refreshPanel() itself is safe to call from any thread
        // (it just posts to the Swing EDT via SwingUtilities.invokeLater), so there's no harm
        // moving it inside.
        if (boardViewActive)
        {
            clientThread.invoke(() ->
            {
                restoreCameraFromBoardView();
                refreshPanel();
            });
        }
        else
        {
            boardViewActive = true;
            refreshPanel(); // safe here -- this branch sets boardViewActive synchronously, above, before any client-thread hop
            clientThread.invoke(() ->
            {
                boardViewSavedPitch = client.getCameraPitchTarget();
                boardViewSavedYaw = client.getCameraYawTarget();
                boardViewSavedZoom = client.getVarcIntValue(VARC_CAMERA_ZOOM);
                client.setCameraPitchRelaxerEnabled(true);
                client.setCameraPitchTarget(BOARD_VIEW_PITCH);
                client.setCameraYawTarget(BOARD_VIEW_YAW);
                client.setVarcIntValue(VARC_CAMERA_ZOOM, BOARD_VIEW_ZOOM);
            });
        }
    }

    /** Writes the pitch/yaw/zoom captured just before toggleBoardView last turned board view on
     * back onto the camera -- shared by toggleBoardView's own off-branch and resetState (leaving/
     * disconnecting from a game while board view is active shouldn't strand the player's camera
     * pointing straight down and zoomed out once they're back to whatever they were doing before).
     * Deliberately leaves the pitch relaxer enabled rather than disabling it -- there's no
     * matching getter to know whether it was already on before this feature touched it (e.g. from
     * the player's own separately-installed Camera plugin), so turning it back off risks
     * clobbering a setting this feature was never the sole owner of. No-op if board view isn't
     * actually active. */
    private void restoreCameraFromBoardView()
    {
        if (!boardViewActive) return;
        boardViewActive = false;
        client.setCameraPitchTarget(boardViewSavedPitch);
        client.setCameraYawTarget(boardViewSavedYaw);
        client.setVarcIntValue(VARC_CAMERA_ZOOM, boardViewSavedZoom);
    }

    public boolean isBoardViewActive() { return boardViewActive; }

    /** The local player's own RSN, or null if unresolvable -- same lookup localRsn() already does
     * for every action method here, just exposed for RunePartyMapDialog to tell "you" apart from
     * everyone else on the map. */
    public String getLocalRsn()
    {
        return localRsn();
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

    @FunctionalInterface
    private interface ApiCall
    {
        void run() throws Exception;
    }

    /** Shared shape for a fire-and-forget request method: submit {@code call} to the executor; on
     * any exception, log {@code logLabel + " failed"} and, if {@code onFailure} is non-null, run
     * it with the exception -- a chat message, resetting a "let the next tick retry" flag, or
     * both (see confirmArrival/rollDice below for callers that need the latter). Every action
     * method in this section used to hand-write this exact executor.submit/try/catch/log wrapper
     * around its own apiClient call. */
    private void submitAction(String logLabel, ApiCall call, Consumer<Exception> onFailure)
    {
        executor.submit(() ->
        {
            try { call.run(); }
            catch (Exception e)
            {
                log.warn(logLabel + " failed", e);
                if (onFailure != null) onFailure.accept(e);
            }
        });
    }

    private void submitAction(String logLabel, ApiCall call)
    {
        submitAction(logLabel, call, null);
    }

    /** Same as {@link #submitAction(String, ApiCall, Consumer)}, plus {@code finallyAction}, run
     * once {@code call} has resolved either way (success or failure) -- createGame/joinGame's own
     * "refresh the panel regardless of outcome" epilogue, the only two callers that need one. */
    private void submitAction(String logLabel, ApiCall call, Consumer<Exception> onFailure, Runnable finallyAction)
    {
        executor.submit(() ->
        {
            try { call.run(); }
            catch (Exception e)
            {
                log.warn(logLabel + " failed", e);
                if (onFailure != null) onFailure.accept(e);
            }
            if (finallyAction != null) finallyAction.run();
        });
    }

    public void createGame()
    {
        String host = localRsn();
        if (host == null) return;

        submitAction("Create game", () ->
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
        }, e -> addChatMessage("Failed to create game: " + e.getMessage()), this::refreshPanel);
    }

    public void joinGame(String code)
    {
        String self = localRsn();
        if (self == null) return;

        submitAction("Join game", () ->
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
        }, e -> addChatMessage("Failed to join game: " + e.getMessage()), this::refreshPanel);
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

        submitAction("Start game", () -> apiClient.startGame(gid, wk, maxRounds),
            e -> addChatMessage("Failed to start game: " + e.getMessage()));
    }

    /** Host-only: ends the game for everyone, distinct from leaveGame() which only removes the
     * caller. The resulting GAME_ENDED event (see handleEvent) is what actually flips phase to
     * ENDED for every connected client, this call and leaveGame() both just request it. */
    public void endGame()
    {
        final String gid = gameId;
        final String wk = writeKey;
        if (gid == null || wk == null) return;

        submitAction("End game", () -> apiClient.endGame(gid, wk),
            e -> addChatMessage("Failed to end game: " + e.getMessage()));
    }

    public void rollDice()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;
        if (!self.equalsIgnoreCase(currentTurnRsn) || pendingRoll || rollRequestSubmitted) return;

        rollRequestSubmitted = true;
        submitAction("Roll dice", () -> apiClient.rollDice(gid, self, token), e ->
        {
            rollRequestSubmitted = false; // let a retry (another Spin) through
            addChatMessage("Failed to roll dice: " + e.getMessage());
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

        submitAction("Confirm arrival", () -> apiClient.confirmArrival(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()), e ->
        {
            addChatMessage("Failed to confirm arrival: " + e.getMessage());
            // A definitive 4xx (e.g. 409 "No roll is pending") means the server has already
            // looked at this exact claim and rejected it -- arrivalSubmitted staying true
            // deliberately blocks checkPendingArrival from resubmitting the identical claim
            // every tick forever, which is what used to happen here (this exact call, this
            // exact 409, once a game tick, spamming the log until something else eventually
            // changed pendingRoll). The client's own pendingRoll is corrected the normal way
            // instead -- by the next real TURN_STARTED or DICE_ROLLED event (see handleEvent),
            // both of which also reset arrivalSubmitted, resyncing to the server's actual
            // state rather than blindly retrying against it. A genuine transient failure
            // (a real network-level IOException, or a 5xx server error) is different -- the
            // claim itself was never actually rejected, so retrying it is still worth doing.
            if (!(e instanceof ApiClient.ApiHttpException) || ((ApiClient.ApiHttpException) e).code >= 500)
            {
                arrivalSubmitted = false; // let the next tick retry
            }
        });
    }

    /** Checks the local player's current position against every currently-live Coin Rush spawn
     * (see coinRushSpawns) and reports a claim the instant it matches one -- called every tick
     * while a Coin Rush round is playable (see onGameTick). coinRushCollectSubmitted guards each
     * spawn id against being reported more than once while its first report is still in flight: the
     * server's own COIN_RUSH_COLLECTED echo is what actually removes the spawn from coinRushSpawns
     * (and clears the guard), so standing on a still-live spawn tile for several ticks in a row
     * before the echo lands doesn't fire a fresh request every single tick. */
    private void checkCoinRushCollection(Player selfPlayer)
    {
        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        if (pos == null) return;

        for (Map.Entry<Integer, WorldPoint> entry : coinRushSpawns.entrySet())
        {
            if (!entry.getValue().equals(pos)) continue;
            int spawnId = entry.getKey();
            if (!coinRushCollectSubmitted.add(spawnId)) continue; // already reported, awaiting the echo
            collectCoinRushCoin(spawnId, pos);
        }
    }

    /** Reports the local player reaching a still-live Coin Rush spawn tile. Same
     * report-then-wait-for-the-echo shape as confirmArrival: the server -- not this call's caller
     * -- decides who actually wins a spawn racing multiple simultaneous reports (see
     * COIN_RUSH_COLLECTED, the only source of truth for who got the coins), so this never assumes
     * success locally. A failed request (network blip, not a "someone else already got it" 409)
     * clears the guard so checkCoinRushCollection retries it on a later tick. */
    private void collectCoinRushCoin(int spawnId, WorldPoint pos)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) { coinRushCollectSubmitted.remove(spawnId); return; }

        submitAction("Collect Coin Rush coin", () -> apiClient.collectCoinRushCoin(gid, self, token, spawnId, pos.getX(), pos.getY(), pos.getPlane()),
            e -> coinRushCollectSubmitted.remove(spawnId));
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

        submitAction("Confirm start", () -> apiClient.confirmStart(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()), e ->
        {
            addChatMessage("Failed to confirm ready: " + e.getMessage());
            startConfirmSubmitted = false; // let the next tick retry
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

        submitAction("Respond to Golden Gnome offer", () -> apiClient.respondGoldenGnomeOffer(gid, self, token, accept),
            e -> addChatMessage("Failed to respond to the Golden Gnome offer: " + e.getMessage()));
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

        submitAction("Confirm mini-game ready", () -> apiClient.confirmMinigameReady(gid, self, token),
            e -> addChatMessage("Failed to confirm mini-game ready: " + e.getMessage()));
    }

    /** Answers the current True or False round -- called from onAnimationChanged once the local
     * player's YES ("True")/NO ("False") emote finishes, same finish-gated pattern as
     * respondGoldenGnomeOffer. The server never echoes back correctness -- see
     * TRUE_OR_FALSE_ROUND_ENDED, the only thing that actually reveals it, once every player's had
     * their full 5 seconds. A 409 here (already answered, or the round already ended before this
     * landed) is a definitive rejection, not a network hiccup -- see ApiClient#ApiHttpException,
     * same reasoning confirmArrival's own catch block follows -- so this doesn't retry either way;
     * the player just missed this one, same as if they'd never emoted at all. */
    private void answerTrueOrFalse(boolean answer)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        submitAction("Answer True or False", () -> apiClient.answerTrueOrFalse(gid, self, token, answer));
    }

    public void submitMinigameResult(int score)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        submitAction("Submit minigame result", () -> apiClient.submitMinigameResult(gid, self, token, score),
            e -> addChatMessage("Failed to submit mini-game result: " + e.getMessage()));
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

        submitAction("Use item", () -> apiClient.useItem(gid, self, token, itemKey),
            e -> addChatMessage("Failed to use item: " + e.getMessage()));
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

        submitAction("Place Coin Trap", () -> apiClient.placeCoinTrap(gid, self, token, point.getX(), point.getY(), point.getPlane()),
            e -> addChatMessage("Failed to place Coin Trap: " + e.getMessage()));
    }

    public void leaveGame()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) { resetState(); return; }

        submitAction("Leave game", () -> apiClient.leaveGame(gid, self, token));
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
        submitAction("Assign role", () -> apiClient.assignRole(gid, wk, playerRsn, role),
            e -> addChatMessage("Failed to update " + playerRsn + "'s role: " + e.getMessage()));
    }

    /** Host-only kick, wired to the roster panel's "Remove Player" right-click entry
     * (RunePartyPanel#buildRemovePlayerPopup) -- same PLAYER_LEFT outcome as the target leaving
     * on their own (see ApiClient#removePlayer), so they drop out of turn order but keep their
     * colorNumber/seat if the host re-adds them later via assignRole. */
    public void removePlayer(String playerRsn)
    {
        if (!isHost() || gameId == null) return;

        final String gid = gameId;
        final String wk = writeKey;
        submitAction("Remove player", () -> apiClient.removePlayer(gid, wk, playerRsn),
            e -> addChatMessage("Failed to remove " + playerRsn + ": " + e.getMessage()));
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

        submitAction("Clear course", () -> apiClient.unmarkTiles(gid, wk, pointSpecs));
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
        submitAction("Commit course", () -> apiClient.markTiles(gid, wk, tileSpecs));
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

        // Runs independently of the turn engine below -- a Coin Rush round has no "whose turn is
        // it" at all, every seated player can be racing for a spawn at once, so this can't share
        // the pendingRoll-gated checks the rest of onGameTick uses.
        if (isCoinRushActive() && isMinigamePlayable())
        {
            checkCoinRushCollection(selfPlayer);
        }

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
     * way, responds to a pending Golden Gnome offer, a mini-game ready-check, or the current True
     * or False round once the matching YES/NO emote finishes. Only reacts to the local player's own
     * animation (every client sees every nearby player's AnimationChanged, so this would otherwise
     * also fire for spectators watching someone else spin/nod/shake for fun). Waits for the *next*
     * animation change away from whichever emote ID matched -- i.e. the emote actually finishing,
     * not just starting -- so the roll (and the screen-centered dice reveal every client sees, see
     * AnnouncementOverlay#renderDiceRoll) or whichever response fires never happens mid-emote;
     * awaitingSpinFinish/awaitingGnomeYesFinish/awaitingGnomeNoFinish/awaitingMinigameReadyFinish/
     * awaitingTrueOrFalseYesFinish/awaitingTrueOrFalseNoFinish are what carry that wait across the
     * two AnimationChanged firings, exactly one set at a time (see those six fields' own doc for
     * why none of the underlying situations can ever overlap). Gates the actual roll on
     * isLocalPlayerReadyToRoll() -- same check AnnouncementOverlay#renderSpinHint uses to decide
     * whether to show the "Use the SPIN! emote" reminder -- and each response on its own matching
     * isLocalPlayerAwaiting*() check, so no hint is ever showing when the matching emote wouldn't
     * actually do anything. rollDice()/respondGoldenGnomeOffer()/confirmMinigameReady()/
     * answerTrueOrFalse() each re-check their own state on top of this, this is just what decides
     * *when* to call them. */
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
            else if (isLocalPlayerAwaitingTrueOrFalseAnswer())
            {
                awaitingTrueOrFalseYesFinish = true;
            }
            return;
        }

        if (anim == AnimationID.EMOTE_NO)
        {
            if (isLocalPlayerAwaitingGoldenGnomeResponse())
            {
                awaitingGnomeNoFinish = true;
            }
            else if (isLocalPlayerAwaitingTrueOrFalseAnswer())
            {
                awaitingTrueOrFalseNoFinish = true;
            }
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
        else if (awaitingTrueOrFalseYesFinish)
        {
            awaitingTrueOrFalseYesFinish = false;
            answerTrueOrFalse(true);
        }
        else if (awaitingTrueOrFalseNoFinish)
        {
            awaitingTrueOrFalseNoFinish = false;
            answerTrueOrFalse(false);
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

    /** Whether the local player still needs to answer the current True or False round -- mirrors
     * isLocalPlayerAwaitingGoldenGnomeResponse's role for that offer's YES/NO emotes. Requires
     * isMinigamePlayable() (not just minigameActive), same "the ready-check has to actually
     * finish first" gate every other in-round action here respects. See onAnimationChanged (gates
     * the real answerTrueOrFalse call) and AnnouncementOverlay#renderTrueOrFalseQuestion (gates
     * the "use YES/NO" instruction on the exact same thing, so it stops prompting a player the
     * instant their own answer lands). */
    public boolean isLocalPlayerAwaitingTrueOrFalseAnswer()
    {
        if (!TRUE_OR_FALSE_KEY.equals(minigameKey) || !isMinigamePlayable() || trueOrFalseQuestion == null) return false;
        String self = localRsn();
        return self != null && !trueOrFalseAnsweredRsns.contains(self.toLowerCase(Locale.ROOT));
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

    public Map<String, ApiClient.TileTypeOut> getTileTypeCatalog()
    {
        return tileTypeCatalog;
    }

    /** Fetches the tile-type color/label/description catalog once, at startup -- see
     * ApiClient#fetchTileTypes's own doc for why this doesn't need re-fetching per game the way
     * syncRosterSnapshot does. Leaves tileTypeCatalog empty on failure rather than retrying; every
     * consumer already degrades gracefully on a missing key. */
    private void loadTileTypeCatalog()
    {
        executor.submit(() ->
        {
            try
            {
                ApiClient.TileTypesResponse resp = apiClient.fetchTileTypes();
                Map<String, ApiClient.TileTypeOut> byKey = new LinkedHashMap<>();
                for (ApiClient.TileTypeOut t : resp.tileTypes)
                {
                    byKey.put(t.key, t);
                }
                tileTypeCatalog = byKey;
            }
            catch (Exception ex)
            {
                log.warn("Fetch tile types failed", ex);
            }
        });
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

    /** Appends a new CoinPopup to {@code rsn}'s own queue (see coinPopups's own doc for why this
     * is a queue, not a single slot) and extends the turn-effect gate to match. Shared by
     * COINS_CHANGED's real coin-total popups and COIN_RUSH_COLLECTED's own totalless "+2" flash
     * (see CoinPopup's own doc), so a Coin Rush round's mid-round flashes and its own end-of-round
     * lump-sum popup still queue behind each other and behind a Golden Gnome popup correctly,
     * exactly like every other coin popup already does, rather than needing a second queue of
     * their own.
     * <p>
     * start is computed by queuing behind whichever's already showing for this same player -- their
     * Golden Gnome popup (tracked separately, not in this queue) if that's still up, else the tail
     * of their own coin-popup queue, else "now" if nothing's currently showing. A different player
     * always gets their own popup immediately regardless of what's showing for anyone else (see the
     * Coin Trap owner's simultaneous +N popup). */
    private void enqueueCoinPopup(String rsn, int delta, int newTotal, long durationMs, boolean totalless)
    {
        String key = rsn.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        Deque<CoinPopup> queue = coinPopups.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        CoinPopup tailPopup = queue.peekLast();
        String samePlayerGnomePopupRsn = goldenGnomePopup.payload != null ? goldenGnomePopup.payload.rsn : null;
        boolean samePlayerGnomePopupShowing = rsn.equalsIgnoreCase(samePlayerGnomePopupRsn) && goldenGnomePopup.until > now;
        long start = samePlayerGnomePopupShowing ? goldenGnomePopup.until
            : (tailPopup != null && tailPopup.until > now) ? tailPopup.until
            : now;
        long until = start + durationMs;

        queue.addLast(new CoinPopup(delta, newTotal, start, until, totalless));
        extendTurnEffectGate(until);
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

    /** Arms `banner` behind whatever turn-effect visual is already showing (see
     * scheduleAfterTurnEffects) -- collapses the `<field>.task = scheduleAfterTurnEffects(...)  {
     * <field>.payload = ...; <field>.until = now + duration; extendTurnEffectGate(...); }` shape
     * repeated across ~6 of the scheduleXBanner methods below (see ARCHITECTURE_REVIEW.md's C6
     * finding). Not applied to every scheduleXBanner method -- several have real behavior beyond
     * "arm one banner" (bespoke until/gate math, chaining to a follow-up step, arming two banners
     * at once) that this deliberately doesn't try to generalize; each of those keeps a one-line
     * comment pointing back here instead of silently diverging from a pattern it never fit. */
    private <T> void armBanner(TimedBanner<T> banner, long durationMs, Supplier<T> payload, boolean extendGate)
    {
        banner.task = scheduleAfterTurnEffects(banner.task, durationMs, () ->
        {
            banner.payload = payload.get();
            banner.start = System.currentTimeMillis();
            banner.until = banner.start + durationMs;
            if (extendGate) extendTurnEffectGate(banner.until);
        });
    }

    /** Schedules AnnouncementOverlay's "<player>'s Turn" banner via scheduleAfterTurnEffects, so it
     * never appears while e.g. the previous mover's coin popup is still settling. Deliberately the
     * one armBanner call with extendGate=false -- unlike every other banner here, this one has
     * never reserved the turn-effect gate for itself (matches its pre-C6 behavior exactly). */
    private void scheduleTurnAnnouncement(String rsn)
    {
        armBanner(turnAnnounce, TURN_ANNOUNCE_DURATION_MS, () -> rsn, false);
    }

    /** Schedules AnnouncementOverlay's "MINIGAME!" banner via scheduleAfterTurnEffects, so it never
     * appears while the last roller's own turn -- including their coin popup -- is still settling.
     * minigameActive/minigameInstructions are set immediately in the MINIGAME_STARTED handler,
     * unaffected by this delay: this only postpones the celebratory banner, not the mini-game
     * itself. scheduleAfterTurnEffects reserves the gate for this banner's own
     * MINIGAME_BANNER_DURATION_MS synchronously, so scheduleMinigameSpinner (called right behind
     * this one, same MINIGAME_STARTED handler) starts right on schedule once that reservation
     * ends -- but the banner itself keeps rendering well past that (see the callback below), so
     * "MINIGAME!" stays up above the wheel for its own whole spin+reveal instead of disappearing
     * the instant the wheel takes over. Not an armBanner call (see that method's own doc) -- its
     * `until` is deliberately longer than what it reserves on the gate, which armBanner's uniform
     * "until == gate reservation" shape can't express. */
    private void scheduleMinigameBanner()
    {
        minigameBanner.task = scheduleAfterTurnEffects(minigameBanner.task, MINIGAME_BANNER_DURATION_MS, () ->
        {
            long now = System.currentTimeMillis();
            // Rendered for MINIGAME_BANNER_DURATION_MS + MINIGAME_SPINNER_DURATION_MS -- longer
            // than what's reserved on the gate below -- so "MINIGAME!" stays visible above the
            // selection wheel for the wheel's own entire spin+reveal (scheduleMinigameSpinner,
            // called right behind this one in the same MINIGAME_STARTED handler) instead of
            // vanishing the instant the wheel appears. The gate reservation deliberately stays at
            // the shorter MINIGAME_BANNER_DURATION_MS (belt-and-suspenders against scheduler
            // jitter, same as ever) -- extending it to match would also push back the wheel's own
            // start, which isn't the goal here.
            minigameBanner.until = now + MINIGAME_BANNER_DURATION_MS + MINIGAME_SPINNER_DURATION_MS;
            extendTurnEffectGate(now + MINIGAME_BANNER_DURATION_MS);
        });
    }

    /** Schedules AnnouncementOverlay's mini-game selection spinner via scheduleAfterTurnEffects,
     * so it waits behind the "MINIGAME!" banner (scheduleMinigameBanner, called right before this
     * in the MINIGAME_STARTED handler) instead of both appearing at once. The gate is reserved for
     * the spin + settle-hold synchronously (see scheduleAfterTurnEffects), so the ready-check
     * screen -- which has no timed trigger of its own, see
     * AnnouncementOverlay#renderMinigameReadyCheck -- only starts reading as "the current screen"
     * once this finishes. Not an armBanner call (see that method's own doc) -- minigameSpinnerStart/
     * Until are still raw fields, never migrated to a TimedBanner, out of scope for this pass. */
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
        armBanner(itemSpinner, ITEM_SPINNER_DURATION_MS, () -> new ItemSpinnerPayload(rsn, itemKey), true);
    }

    /** Schedules AnnouncementOverlay's "already have N items" announcement via
     * scheduleAfterTurnEffects -- fired instead of scheduleItemSpinner when the server's own
     * ITEM_CAP_BLOCKED lands (see app.py's ITEM_TILE handling), so it waits behind whatever
     * turn-effect visual is already showing the same way the item wheel itself would have. */
    private void scheduleItemCapBlockedAnnouncement(String rsn, int cap)
    {
        armBanner(itemCapBlocked, ITEM_CAP_BLOCKED_DURATION_MS, () -> new ItemCapBlockedPayload(rsn, cap), true);
    }

    /** Schedules AnnouncementOverlay's "You used/&lt;rsn&gt; used &lt;item&gt;!" banner via
     * scheduleAfterTurnEffects -- fired on ITEM_USED for whichever item opts in via
     * Item#hasUseAnnouncement (see that handler), so it waits behind whatever turn-effect visual is
     * already showing, same as scheduleItemCapBlockedAnnouncement. */
    private void scheduleItemUsedAnnouncement(String rsn, String itemKey)
    {
        armBanner(itemUsedAnnounce, ITEM_USED_ANNOUNCE_DURATION_MS, () -> new ItemUsedAnnouncePayload(rsn, itemKey), true);
    }

    /** Schedules AnnouncementOverlay's "You/&lt;rsn&gt; landed on a Coin Trap!" banner via
     * scheduleAfterTurnEffects -- fired on COIN_TRAP_TRIGGERED, same shape as
     * scheduleItemUsedAnnouncement. {@code victimRsn} is whoever landed on it, not the trap's
     * owner -- the owner's own feedback is purely their +N coin popup (see the COINS_CHANGED
     * handler), no banner of their own. */
    private void scheduleCoinTrapTriggerAnnouncement(String victimRsn)
    {
        armBanner(coinTrapAnnounce, COIN_TRAP_ANNOUNCE_DURATION_MS, () -> victimRsn, true);
    }

    /** Arms AnnouncementOverlay's mini-game rewards recap ("who got what") -- called from the
     * MINIGAME_ENDED handler, parsing its own "payouts" list once here rather than having
     * AnnouncementOverlay re-parse the raw event payload every frame. Extends turnEffectGateUntil
     * so both the round-complete recap (see scheduleRoundCompleteBanner) and the new round's first
     * TURN_STARTED banner wait behind this one instead of overlapping it. Not an armBanner call
     * (see that method's own doc) -- this arms synchronously, with no scheduleAfterTurnEffects
     * wrapper to collapse. */
    private void triggerMinigameRewardsBanner(JsonObject payload)
    {
        minigameRewardsBanner.payload = Json.safeMinigameRewards(payload, "payouts");
        minigameRewardsBanner.until = System.currentTimeMillis() + MINIGAME_REWARDS_BANNER_DURATION_MS;
        extendTurnEffectGate(minigameRewardsBanner.until);
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
        armBanner(roundCompleteBanner, ROUND_COMPLETE_BANNER_DURATION_MS, this::getCurrentRound, true);
    }

    /** Final standings, ranked the same way renderRoundCompleteBanner already ranks the live
     * mid-game ones -- Golden Gnome count descending, coins as tiebreak -- snapshotted once on
     * GAME_ENDED rather than read live, since no further coin/gnome changes are possible once the
     * server's flipped the game out of ACTIVE. Spectators and never-joined seats are excluded, same
     * as every other standings view. */
    private List<RosterReducer.RosterEntry> computeFinalStandings()
    {
        List<RosterReducer.RosterEntry> standings = rosterReducer.seatedPlayers();
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
     * host force-ending an empty lobby is technically possible). Not an armBanner call, nor are
     * the four scheduleX methods below it that continue this chain (see that method's own doc) --
     * each carries real logic beyond arming one banner (building a reveal order, a chat message,
     * arming two banners in the same callback), not just the arm-and-extend-gate shape armBanner
     * covers. */
    private void triggerGameOverSequence()
    {
        List<RosterReducer.RosterEntry> standings = computeFinalStandings();
        if (standings.isEmpty()) return;
        gameOverStandings = standings;

        gameOverTask = scheduleAfterTurnEffects(gameOverTask, GAME_OVER_TITLE_DURATION_MS, () ->
        {
            gameOverBanner.until = System.currentTimeMillis() + GAME_OVER_TITLE_DURATION_MS;
            extendTurnEffectGate(gameOverBanner.until);
            scheduleWinnerIntro();
        });
    }

    private void scheduleWinnerIntro()
    {
        gameOverTask = scheduleAfterTurnEffects(gameOverTask, WINNER_INTRO_DURATION_MS, () ->
        {
            winnerIntroBanner.until = System.currentTimeMillis() + WINNER_INTRO_DURATION_MS;
            extendTurnEffectGate(winnerIntroBanner.until);

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
            placeReveal.payload = new PlaceRevealPayload(entry.rsn, gameOverStandings.indexOf(entry) + 1, entry.coins, entry.goldenGnomeCount);
            placeReveal.until = System.currentTimeMillis() + PLACE_REVEAL_DURATION_MS;
            extendTurnEffectGate(placeReveal.until);
            schedulePlaceReveal(revealOrder, index + 1);
        });
    }

    private void scheduleWinnerSuspense()
    {
        gameOverTask = scheduleAfterTurnEffects(gameOverTask, WINNER_SUSPENSE_DURATION_MS, () ->
        {
            winnerSuspenseBanner.until = System.currentTimeMillis() + WINNER_SUSPENSE_DURATION_MS;
            extendTurnEffectGate(winnerSuspenseBanner.until);
            scheduleWinnerReveal();
        });
    }

    /** The ceremony's final step -- the winner's own name, held alongside ConfettiOverlay's burst
     * (confettiBanner, shorter than WINNER_REVEAL_DURATION_MS so the confetti finishes settling
     * while the name's still up rather than both cutting off together). gameOverStandings is sorted
     * winner-first, so index 0 is always the winner. */
    private void scheduleWinnerReveal()
    {
        gameOverTask = scheduleAfterTurnEffects(gameOverTask, WINNER_REVEAL_DURATION_MS, () ->
        {
            RosterReducer.RosterEntry winner = gameOverStandings.get(0);
            winnerRevealBanner.payload = winner.rsn;
            long now = System.currentTimeMillis();
            winnerRevealBanner.until = now + WINNER_REVEAL_DURATION_MS;
            confettiBanner.until = now + CONFETTI_DURATION_MS;
            addChatMessage(winner.rsn + " wins Rune Party!");
        });
    }

    /** Arms AnnouncementOverlay's "Welcome to Rune Party Showdown" title card -- called once, right
     * after createGame/joinGame succeeds, for the local player only (there's no server event for
     * this; it's purely a client-side "you're in!" splash, so it never fires for anyone already in
     * the lobby when someone else joins). Not an armBanner call (see that method's own doc) --
     * arms synchronously with no scheduleAfterTurnEffects wrapper, nothing to collapse. */
    private void triggerWelcomeBanner()
    {
        welcomeBanner.until = System.currentTimeMillis() + WELCOME_BANNER_DURATION_MS;
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

        String type = e.type.toUpperCase(Locale.ROOT);
        switch (type)
        {
            case Events.GAME_STARTED:
            {
                phase = GamePhase.ACTIVE;
                // currentTurnRsn stays null here -- see confirmStart/checkGatheringAtStart, turn
                // order doesn't actually begin until every seated PLAYER reports being at START.
                startConfirmSubmitted = false;
                // Real state, applied catch-up or not -- see getCurrentRound/StatsOverlay's
                // "ROUND x/y" line, the only consumer.
                Integer mr = Json.requiredInt(e.payload, type, "maxRounds");
                if (mr != null) maxRounds = mr;
                if (!catchingUp)
                {
                    gameStartBanner.until = System.currentTimeMillis() + GAME_START_BANNER_DURATION_MS;
                }
                break;
            }

            case Events.GAME_ENDED:
                phase = GamePhase.ENDED;
                if (!catchingUp)
                {
                    triggerGameOverSequence();
                }
                break;

            case Events.PLAYER_READY:
                if (!catchingUp)
                {
                    addChatMessage(Json.requiredStr(e.payload, type, "player") + " is ready at the start!");
                }
                break;

            // None of these three carry a turn-order "number" in their payload -- the server only
            // ever computes it fresh from the whole event log on a roster read (see
            // _finalize_roster in app.py), and it can shift for everyone whenever the PLAYER set
            // changes (a join, a promotion, a leave). So on any of them, pull a fresh roster
            // snapshot rather than trying to derive numbers from the event stream itself. Skipped
            // during catch-up -- connectEventStream does one roster sync after the whole backlog
            // instead of one REST call per historical join/promotion/leave.
            case Events.PLAYER_JOINED:
            case Events.ROLE_ASSIGNED:
            case Events.PLAYER_LEFT:
                if (!catchingUp)
                {
                    syncRosterSnapshot();
                }
                break;

            case Events.TURN_STARTED:
            {
                currentTurnRsn = Json.requiredStr(e.payload, type, "player");
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

            case Events.DICE_ROLLED:
            {
                lastDiceRoll = Json.requiredInt(e.payload, type, "value");
                pendingTargetIndices = Json.safeIntList(e.payload, "targetIndices");
                pendingRoll = true;
                rollRequestSubmitted = false; // pendingRoll is now the authoritative in-flight guard
                arrivalSubmitted = false;
                if (!catchingUp)
                {
                    String roller = Json.requiredStr(e.payload, type, "player");
                    addChatMessage(roller + " rolled a " + lastDiceRoll + "!");
                    if (lastDiceRoll != null)
                    {
                        Integer bonus = Json.safeInt(e.payload, "bonus");
                        diceRollRsn = roller;
                        diceRollValue = lastDiceRoll;
                        diceRollBonus = bonus != null ? bonus : 0;
                        diceRollStart = System.currentTimeMillis();
                        diceRollUntil = diceRollStart + (diceRollBonus != 0 ? DICE_ROLL_BONUS_DURATION_MS : DICE_ROLL_DURATION_MS);
                    }
                }
                break;
            }

            case Events.PLAYER_MOVED:
            {
                String mover = Json.requiredStr(e.payload, type, "player");
                Integer toIndex = Json.requiredInt(e.payload, type, "toIndex");
                if (mover != null && toIndex != null)
                {
                    playerPositions.put(mover.toLowerCase(Locale.ROOT), toIndex);
                }
                break;
            }

            case Events.GOLDEN_GNOME_OFFERED:
            {
                // Real state, applied catch-up or not: non-null exactly while a response is
                // outstanding, gating both a YES/NO emote doing anything (see
                // isLocalPlayerAwaitingGoldenGnomeResponse) and rolling again (see
                // isLocalPlayerReadyToRoll). Pauses TILE_EFFECT/COINS_CHANGED for the underlying
                // tile until GOLDEN_GNOME_OFFER_RESOLVED -- see the server's confirm_arrival/
                // respond_golden_gnome_offer split.
                goldenGnomeOfferRsn = Json.requiredStr(e.payload, type, "player");
                if (!catchingUp)
                {
                    addChatMessage(goldenGnomeOfferRsn + " found a Golden Gnome!");
                }
                break;
            }

            case Events.GOLDEN_GNOME_OFFER_RESOLVED:
            {
                goldenGnomeOfferRsn = null; // always clear, catch-up or not -- real state
                awaitingGnomeYesFinish = false;
                awaitingGnomeNoFinish = false;
                // "declined" gets no banner/chat of its own -- the offer simply disappears, same
                // silence a declined confirm-start or an un-rolled turn would get. "purchased"'s
                // own announcement comes from the GOLDEN_GNOME_PURCHASED case below instead (it
                // carries the new total, which this event doesn't), so only "cant_afford" actually
                // needs to arm anything here.
                if (!catchingUp && "cant_afford".equals(Json.requiredStr(e.payload, type, "outcome")))
                {
                    goldenGnomeOutcome.payload = new GoldenGnomeOutcomePayload("cant_afford", Json.requiredStr(e.payload, type, "player"));
                    goldenGnomeOutcome.until = System.currentTimeMillis() + GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS;
                    extendTurnEffectGate(goldenGnomeOutcome.until);
                    addChatMessage("Can't afford the Golden Gnome!");
                }
                break;
            }

            case Events.GOLDEN_GNOME_PURCHASED:
            {
                // The running total itself lives in rosterReducer (updated unconditionally above,
                // catch-up or not) -- everything here is purely cosmetic: the "You got a Golden
                // Gnome!" announcement (goldenGnomeOutcome, reusing the same banner
                // renderGoldenGnomeOutcome uses for "cant_afford") plus the "+1 Golden Gnome"
                // popup, both fired from this one event since it's the only one carrying the new
                // total the popup needs.
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    Integer total = Json.requiredInt(e.payload, type, "goldenGnomeCount");
                    goldenGnomePopup.payload = new GoldenGnomePopupPayload(rsn, total != null ? total : 0);
                    goldenGnomePopup.start = System.currentTimeMillis();
                    goldenGnomePopup.until = goldenGnomePopup.start + COIN_POPUP_DURATION_MS;
                    extendTurnEffectGate(goldenGnomePopup.until);

                    goldenGnomeOutcome.payload = new GoldenGnomeOutcomePayload("purchased", rsn); // same event, same player
                    goldenGnomeOutcome.until = System.currentTimeMillis() + GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS;
                    extendTurnEffectGate(goldenGnomeOutcome.until);

                    addChatMessage(rsn + " got a Golden Gnome!");
                }
                break;
            }

            case Events.ITEM_GRANTED:
            {
                // Inventory itself is updated unconditionally by rosterReducer.apply above --
                // everything here is purely the wheel reveal's own cosmetics.
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    String itemKey = Json.requiredStr(e.payload, type, "itemKey");
                    String itemDisplayName = Json.requiredStr(e.payload, type, "itemDisplayName");
                    scheduleItemSpinner(rsn, itemKey);
                    addChatMessage(rsn + " got " + itemDisplayName + "!");
                }
                break;
            }

            case Events.ITEM_CAP_BLOCKED:
            {
                // No inventory change -- the server refused to grant anything, see app.py's
                // ITEM_TILE handling. Purely cosmetic, same as ITEM_GRANTED's own reveal.
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    Integer cap = Json.requiredInt(e.payload, type, "itemCap");
                    scheduleItemCapBlockedAnnouncement(rsn, cap != null ? cap : 0);
                    addChatMessage(rsn + " already has too many items!");
                }
                break;
            }

            case Events.ITEM_USED:
            {
                // Real state, applied catch-up or not: an ITEM_USED can only ever be inserted for
                // the current turn's player (see the server's _require_ready_to_act), so this is
                // always the same turn TURN_STARTED just reset it for. Inventory itself is already
                // decremented unconditionally by rosterReducer.apply above.
                itemUsedThisTurn = true;
                if (!catchingUp)
                {
                    String rsn = Json.requiredStr(e.payload, type, "player");
                    String itemKey = Json.requiredStr(e.payload, type, "itemKey");
                    if (Items.get(itemKey).hasUseAnnouncement())
                    {
                        scheduleItemUsedAnnouncement(rsn, itemKey);
                    }
                }
                break;
            }

            case Events.GOLDEN_GNOME_MOVED:
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
                    WorldPoint oldPoint = Json.safeWorldPoint(e.payload, "oldPoint");
                    WorldPoint newPoint = Json.safeWorldPoint(e.payload, "newPoint");

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

            case Events.TILE_EFFECT:
            {
                // PATH/PENALTY_TILE/ITEM_TILE are the tile types with a real effect so far (see
                // the COINS_CHANGED/ITEM_GRANTED cases below, which actually pay/grant it) --
                // START/EVENT_TILE are still no-ops, but this event fires for every type so this
                // chat line is always accurate regardless.
                if (!catchingUp)
                {
                    addChatMessage(Json.requiredStr(e.payload, type, "player") + " landed on a " + Json.requiredStr(e.payload, type, "tileType") + " tile.");
                }
                break;
            }

            case Events.COIN_TRAP_TRIGGERED:
            {
                // Real-time, not chained behind scheduleAfterTurnEffects -- unlike the announcement
                // banner below, the model/animation choreography is tied to a specific spot on the
                // board (see TileOverlay#updateCoinTrapModels), not the screen-centered UI other
                // "what happened" banners share a queue for, so delaying it to wait its turn would
                // just look like the trap sprang for no visible reason moments after the player
                // actually landed on it.
                if (!catchingUp)
                {
                    String victim = Json.requiredStr(e.payload, type, "player");
                    String owner = Json.requiredStr(e.payload, type, "owner");
                    Integer stolen = Json.requiredInt(e.payload, type, "stolen");
                    Integer x = Json.requiredInt(e.payload, type, "x");
                    Integer y = Json.requiredInt(e.payload, type, "y");
                    Integer plane = Json.requiredInt(e.payload, type, "plane");
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

            case Events.COINS_CHANGED:
            {
                // The standard-tile reward, an item's own coin effect, and a Coin Trap steal all
                // get the popup treatment -- a Golden Gnome purchase or a mini-game's own
                // end-of-round payout already has its own feedback (the roster/stats panels
                // update, and submitMinigameResult's caller sees the MINIGAME_ENDED chat line), so
                // this stays scoped to the cases that otherwise had no visible feedback at all.
                // Coin Rush's "coin_rush" and True or False's "true_or_false" reasons are the two
                // exceptions worth calling out: unlike the other three, neither fires per landing --
                // the server bundles every coin/correct-answer from the whole round/mini-game into
                // one lump-sum COINS_CHANGED right before MINIGAME_ENDED (see app.py's
                // _coin_rush_end_round/_true_or_false_end), so each case only ever fires once per
                // player per mini-game, showing their own round total ("+6 coins" / "+10 coins")
                // then their real new balance -- never per-pickup or per-round. Coin Rush's own
                // individual pickups get a separate, purely cosmetic "+2" flash instead (see
                // COIN_RUSH_COLLECTED handling), which never touches this case at all since it
                // carries no COINS_CHANGED of its own; True or False has no equivalent mid-round
                // flash since a round's own correctness isn't revealed until it ends anyway (see
                // TRUE_OR_FALSE_ROUND_ENDED). The real coin total itself lives in rosterReducer
                // (updated unconditionally above, catch-up or not) -- everything in this block is
                // purely the popup's own cosmetics.
                String coinsChangedReason = Json.requiredStr(e.payload, type, "reason");
                if (!catchingUp && ("standard_tile".equals(coinsChangedReason) || "item".equals(coinsChangedReason)
                    || "coin_trap".equals(coinsChangedReason) || "coin_rush".equals(coinsChangedReason)
                    || "true_or_false".equals(coinsChangedReason)))
                {
                    String coinsChangedRsn = Json.requiredStr(e.payload, type, "player");
                    Integer delta = Json.requiredInt(e.payload, type, "delta");
                    Integer total = Json.requiredInt(e.payload, type, "coins");

                    if (coinsChangedRsn != null)
                    {
                        enqueueCoinPopup(coinsChangedRsn, delta != null ? delta : 0, total != null ? total : 0,
                            COIN_POPUP_DURATION_MS, false);
                    }
                }
                break;
            }

            case Events.MINIGAME_STARTED:
                // minigameActive/minigameInstructions/minigameKey/minigameDisplayName take effect
                // immediately, catch-up or not -- a player joining mid-minigame needs the panel to
                // correctly show it's underway. Ready-check state resets fresh for this mini-game
                // instance too, real state regardless of catch-up. Only the celebratory banner and
                // the spinner that follows it are cosmetic-only and skipped during catch-up.
                minigameActive = true;
                minigameInstructions = Json.requiredStr(e.payload, type, "instructions");
                minigameKey = Json.requiredStr(e.payload, type, "key");
                minigameDisplayName = Json.requiredStr(e.payload, type, "displayName");
                minigameReadyRsns.clear();
                minigameCountdownStarted = false;
                minigameCountdownSkippedForClient = false;
                minigameCountdownBannerUntil = 0;
                minigameSpinnerStart = 0;
                minigameSpinnerUntil = 0;
                minigameSpinnerSkippedForClient = catchingUp;
                // Real state, applied catch-up or not: a fresh Coin Rush instance starts with no
                // spawns and no tally, regardless of whether this client watched the previous
                // round's own leftovers get cleaned up by its own MINIGAME_ENDED (see that case
                // below -- redundant here for a live client, but a catching-up client that missed
                // the previous round's MINIGAME_ENDED entirely still needs this reset).
                // coinRushRoundStartAt deliberately isn't set here -- MINIGAME_STARTED lands before
                // the round is actually playable (the ready-check still has to run first), so
                // stamping "now" at this point would undercount the round's remaining time; see the
                // MINIGAME_COUNTDOWN_STARTED case below, the only writer.
                if (COIN_RUSH_KEY.equals(minigameKey))
                {
                    coinRushSpawns.clear();
                    coinRushScores.clear();
                    coinRushCollectSubmitted.clear();
                    coinRushRoundStartAt = 0;
                }
                // Same reasoning as Coin Rush's own reset just above -- a fresh True or False
                // instance starts with no question/answers/reveal, regardless of catch-up.
                if (TRUE_OR_FALSE_KEY.equals(minigameKey))
                {
                    trueOrFalseQuestion = null;
                    trueOrFalseRoundNumber = 0;
                    trueOrFalseAnsweredRsns.clear();
                    trueOrFalseMyAnswer = null;
                    trueOrFalseRoundStartedAt = 0;
                    trueOrFalseLastCorrectAnswer = null;
                    trueOrFalseLastResults = Collections.emptyList();
                    trueOrFalseRevealUntil = 0;
                }
                if (!catchingUp)
                {
                    scheduleMinigameBanner();
                    scheduleMinigameSpinner();
                    addChatMessage("Mini-game! " + minigameInstructions);
                }
                break;

            case Events.MINIGAME_PLAYER_READY:
            {
                // Real state, applied catch-up or not -- see isLocalPlayerAwaitingMinigameReady/
                // isMinigamePlayable, which both need an accurate ready set regardless of whether
                // this client watched it happen live.
                String readyRsn = Json.requiredStr(e.payload, type, "player");
                if (readyRsn != null) minigameReadyRsns.add(readyRsn.toLowerCase(Locale.ROOT));
                break;
            }

            case Events.MINIGAME_COUNTDOWN_STARTED:
                // Real state, applied catch-up or not -- see isMinigamePlayable, the reason this
                // is split from the cosmetic-only banner timestamp below.
                minigameCountdownStarted = true;
                minigameCountdownSkippedForClient = catchingUp;
                // Coin Rush's own round clock (see COIN_RUSH_DURATION_MS/getCoinRushEndsAt) starts
                // ticking from here, not MINIGAME_STARTED -- this is the event that actually marks
                // the round playable (once the countdown itself finishes, see isMinigamePlayable).
                // A catching-up client has no live "BEGIN!" moment of its own to hang this off of
                // (see minigameCountdownBannerUntil's own catch-up doc), so "now" is the closest
                // available approximation; a live client gets the precise instant instead, stamped
                // below once the countdown's own delay actually elapses.
                if (catchingUp && COIN_RUSH_KEY.equals(minigameKey))
                {
                    coinRushRoundStartAt = System.currentTimeMillis();
                }
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
                        if (COIN_RUSH_KEY.equals(minigameKey)) coinRushRoundStartAt = minigameCountdownBannerUntil;
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

            case Events.MINIGAME_ENDED:
                minigameActive = false;
                minigameInstructions = null;
                minigameKey = null;
                minigameDisplayName = null;
                minigameReadyRsns.clear();
                minigameCountdownStarted = false;
                minigameCountdownSkippedForClient = false;
                minigameCountdownBannerUntil = 0;
                // Unconditional (harmless no-op if this round wasn't Coin Rush) rather than gated
                // on COIN_RUSH_KEY.equals(minigameKey) -- minigameKey is already cleared above by
                // this point. Any coin still standing when the round ends shouldn't keep rendering
                // (see TileOverlay#updateCoinRushModels, which just mirrors this map's own keys) --
                // real state, applied catch-up or not. The scoreboard tally itself (coinRushScores)
                // is deliberately left as-is rather than cleared here: StatsOverlay's own gate
                // (isCoinRushActive() && isMinigamePlayable(), both now false) already stops
                // rendering it, and the next MINIGAME_STARTED resets it fresh regardless.
                coinRushSpawns.clear();
                coinRushCollectSubmitted.clear();
                // Same reasoning as the Coin Rush cleanup just above -- no question/reveal should
                // keep rendering once the mini-game itself has ended.
                trueOrFalseQuestion = null;
                trueOrFalseRoundNumber = 0;
                trueOrFalseAnsweredRsns.clear();
                trueOrFalseMyAnswer = null;
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

            case Events.COIN_RUSH_SPAWN:
            {
                // Real state, applied catch-up or not: a coin genuinely exists on the board at
                // this point the instant the server says so, regardless of whether this client
                // watched it appear live -- see TileOverlay#updateCoinRushModels, which just
                // mirrors coinRushSpawns's own current keys every frame.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                WorldPoint point = Json.safeWorldPoint(e.payload, "point");
                if (spawnId != null && point != null) coinRushSpawns.put(spawnId, point);
                break;
            }

            case Events.COIN_RUSH_COLLECTED:
            {
                // Real state, applied catch-up or not: the spawn is gone (whoever the server
                // credited already claimed it) and the tally reflects it, regardless of whether
                // this client watched the race happen live -- a catching-up client still needs an
                // accurate live scoreboard the instant it starts rendering one. This event never
                // carries a real coin-total change of its own -- the server doesn't actually credit
                // a Coin Rush pickup to the player's balance until the round ends, one lump sum per
                // player (see COINS_CHANGED's own "coin_rush" case) -- so the only thing worth
                // showing live, right now, is a purely cosmetic "+2" flash (see enqueueCoinPopup's
                // totalless=true), never a running total.
                Integer spawnId = Json.requiredInt(e.payload, type, "id");
                if (spawnId != null)
                {
                    coinRushSpawns.remove(spawnId);
                    coinRushCollectSubmitted.remove(spawnId);
                }
                String collector = Json.requiredStr(e.payload, type, "player");
                if (collector != null)
                {
                    coinRushScores.merge(collector.toLowerCase(Locale.ROOT), 1, Integer::sum);
                }
                if (!catchingUp && collector != null)
                {
                    enqueueCoinPopup(collector, COIN_RUSH_REWARD, 0, COIN_RUSH_BUMP_POPUP_DURATION_MS, true);
                    addChatMessage(collector + " grabbed a coin!");
                }
                break;
            }

            case Events.TRUE_OR_FALSE_ROUND_STARTED:
            {
                // Real state, applied catch-up or not: a fresh round genuinely started at this
                // point regardless of whether this client watched it happen live -- a catching-up
                // client still needs to know the current question/round number the instant it
                // starts rendering anything. Never carries the correct answer (see the server's own
                // TRUE_OR_FALSE_ROUND_STARTED payload -- only questionIndex, resolved into text by
                // the server itself before broadcast), so there's nothing here for a client to read
                // early. Per-round-only state (who's answered, my own answer) resets fresh; the
                // *previous* round's reveal (trueOrFalseLastCorrectAnswer/Results) is deliberately
                // left alone here, same "let the timer/next reset clear it" idiom coinRushScores
                // already uses -- renderTrueOrFalseReveal gates its own display on
                // trueOrFalseRevealUntil, not on whether a new question already exists.
                trueOrFalseQuestion = Json.requiredStr(e.payload, type, "question");
                Integer roundNumber = Json.requiredInt(e.payload, type, "roundNumber");
                if (roundNumber != null) trueOrFalseRoundNumber = roundNumber;
                trueOrFalseAnsweredRsns.clear();
                trueOrFalseMyAnswer = null;
                // "Now" regardless of catch-up -- a live client gets the genuinely-accurate instant
                // since this event only ever arrives right as the round actually starts either way;
                // a catching-up client gets the same best-effort approximation coinRushRoundStartAt's
                // own catch-up branch uses.
                trueOrFalseRoundStartedAt = System.currentTimeMillis();
                break;
            }

            case Events.TRUE_OR_FALSE_ANSWERED:
            {
                // Real state, applied catch-up or not -- see isLocalPlayerAwaitingTrueOrFalseAnswer/
                // renderTrueOrFalseQuestion, both of which need an accurate answered-set regardless
                // of whether this client watched each answer land live.
                String answeredRsn = Json.requiredStr(e.payload, type, "player");
                if (answeredRsn != null)
                {
                    trueOrFalseAnsweredRsns.add(answeredRsn.toLowerCase(Locale.ROOT));
                    String self = localRsn();
                    if (self != null && self.equalsIgnoreCase(answeredRsn))
                    {
                        JsonElement answerEl = e.payload.get("answer");
                        if (answerEl != null && !answerEl.isJsonNull()) trueOrFalseMyAnswer = answerEl.getAsBoolean();
                    }
                }
                break;
            }

            case Events.TRUE_OR_FALSE_ROUND_ENDED:
            {
                // Real state, applied catch-up or not: the correct answer is now public regardless
                // of whether this client watched the round happen live.
                JsonElement correctEl = e.payload.get("correctAnswer");
                trueOrFalseLastCorrectAnswer = correctEl != null && !correctEl.isJsonNull() ? correctEl.getAsBoolean() : null;
                trueOrFalseLastResults = Json.safeTrueOrFalseResults(e.payload);
                if (!catchingUp)
                {
                    trueOrFalseRevealUntil = System.currentTimeMillis() + TRUE_OR_FALSE_REVEAL_DURATION_MS;
                    extendTurnEffectGate(trueOrFalseRevealUntil);
                }
                break;
            }

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

    private void resetState()
    {
        // Leaving/disconnecting while board view is active shouldn't strand the player's camera
        // pointing straight down once they're back to whatever they were doing before -- restore
        // it the same way toggling the button off would. clientThread.invoke rather than a direct
        // call since resetState can run from a Swing button handler (leaveGame) as well as a
        // client-thread event subscriber, and camera setters are believed to require the client
        // thread the same way RuneLiteObject#setActive does (see CheerleaderRenderer#clear's
        // identical reasoning in the sibling Gnomeball repo).
        if (boardViewActive) clientThread.invoke(this::restoreCameraFromBoardView);
        if (eventSocket != null) eventSocket.stop();
        turnAnnounce.reset();
        minigameBanner.reset();
        if (minigameSpinnerTask != null) { minigameSpinnerTask.cancel(false); minigameSpinnerTask = null; }
        itemSpinner.reset();
        itemCapBlocked.reset();
        itemUsedAnnounce.reset();
        coinTrapAnnounce.reset();
        roundCompleteBanner.reset();
        if (gameOverTask != null) { gameOverTask.cancel(false); gameOverTask = null; }
        turnEffectGateUntil = 0;
        gameId = null; writeKey = null; playerToken = null; joinCode = null; hostRsn = null;
        phase = GamePhase.DISCONNECTED;
        coursePlacementMode = false; selectedPreset = null; presetRotationSteps = 0;
        currentTurnRsn = null; lastDiceRoll = null; pendingRoll = false; rollRequestSubmitted = false;
        awaitingSpinFinish = false;
        awaitingGnomeYesFinish = false; awaitingGnomeNoFinish = false; awaitingMinigameReadyFinish = false;
        awaitingTrueOrFalseYesFinish = false; awaitingTrueOrFalseNoFinish = false;
        pendingTargetIndices = Collections.emptyList();
        arrivalSubmitted = false; itemUsedThisTurn = false; standingOnTrackedPositionCached = false;
        itemPlacementKey = null;
        minigameActive = false; minigameInstructions = null; minigameKey = null;
        minigameDisplayName = null;
        minigameSpinnerStart = 0; minigameSpinnerUntil = 0; minigameSpinnerSkippedForClient = false;
        coinTrapTriggerPoint = null; coinTrapTriggerUntil = 0;
        minigameReadyRsns.clear();
        minigameCountdownStarted = false; minigameCountdownSkippedForClient = false; minigameCountdownBannerUntil = 0;
        coinRushSpawns.clear(); coinRushScores.clear(); coinRushCollectSubmitted.clear(); coinRushRoundStartAt = 0;
        trueOrFalseQuestion = null; trueOrFalseRoundNumber = 0; trueOrFalseAnsweredRsns.clear();
        trueOrFalseMyAnswer = null; trueOrFalseRoundStartedAt = 0;
        trueOrFalseLastCorrectAnswer = null; trueOrFalseLastResults = Collections.emptyList(); trueOrFalseRevealUntil = 0;
        maxRounds = 0; completedRounds = 0;
        playerPositions.clear();
        startConfirmSubmitted = false;
        welcomeBanner.reset();
        gameStartBanner.reset();
        minigameRewardsBanner.reset();
        gameOverStandings = Collections.emptyList(); gameOverBanner.reset(); winnerIntroBanner.reset();
        placeReveal.reset();
        winnerSuspenseBanner.reset(); winnerRevealBanner.reset(); confettiBanner.reset();
        goldenGnomeOfferRsn = null; goldenGnomeOutcome.reset();
        goldenGnomePopup.reset();
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
    public long getItemSpinnerStart() { return itemSpinner.start; }
    public long getItemSpinnerUntil() { return itemSpinner.until; }
    public String getItemGrantRsn() { return itemSpinner.payload != null ? itemSpinner.payload.rsn : null; }
    public String getItemGrantKey() { return itemSpinner.payload != null ? itemSpinner.payload.itemKey : null; }
    public long getItemCapBlockedUntil() { return itemCapBlocked.until; }
    public String getItemCapBlockedRsn() { return itemCapBlocked.payload != null ? itemCapBlocked.payload.rsn : null; }
    public int getItemCapBlockedCap() { return itemCapBlocked.payload != null ? itemCapBlocked.payload.cap : 0; }
    public long getItemUsedAnnounceUntil() { return itemUsedAnnounce.until; }
    public String getItemUsedAnnounceRsn() { return itemUsedAnnounce.payload != null ? itemUsedAnnounce.payload.rsn : null; }
    public String getItemUsedAnnounceItemKey() { return itemUsedAnnounce.payload != null ? itemUsedAnnounce.payload.itemKey : null; }
    public long getCoinTrapAnnounceUntil() { return coinTrapAnnounce.until; }
    public String getCoinTrapAnnounceRsn() { return coinTrapAnnounce.payload; }
    public WorldPoint getCoinTrapTriggerPoint() { return coinTrapTriggerPoint; }
    public long getCoinTrapTriggerUntil() { return coinTrapTriggerUntil; }

    /** Every currently-live Coin Rush spawn, keyed by the server's own spawn id -- see
     * TileOverlay#updateCoinRushModels, the only consumer. */
    public Map<Integer, WorldPoint> getCoinRushSpawns() { return Collections.unmodifiableMap(coinRushSpawns); }
    /** This round's live Coin Rush tally, lowercase rsn -> coins collected so far -- see
     * StatsOverlay's live scoreboard, the only consumer. */
    public Map<String, Integer> getCoinRushScores() { return Collections.unmodifiableMap(coinRushScores); }
    public boolean isCoinRushActive() { return minigameActive && COIN_RUSH_KEY.equals(minigameKey); }
    /** When the current Coin Rush round's own clock (see COIN_RUSH_DURATION_MS) runs out -- 0 if
     * no round is active yet or the round hasn't actually become playable (see
     * coinRushRoundStartAt's own doc on when that gets stamped). */
    public long getCoinRushEndsAt() { return coinRushRoundStartAt != 0 ? coinRushRoundStartAt + COIN_RUSH_DURATION_MS : 0; }

    public String getTrueOrFalseQuestion() { return trueOrFalseQuestion; }
    public int getTrueOrFalseRoundNumber() { return trueOrFalseRoundNumber; }
    /** Who's answered the *current* round so far -- see renderTrueOrFalseQuestion's own
     * "Ready screen"-style tally, the only consumer. */
    public Set<String> getTrueOrFalseAnsweredRsns() { return Collections.unmodifiableSet(trueOrFalseAnsweredRsns); }
    public Boolean getTrueOrFalseMyAnswer() { return trueOrFalseMyAnswer; }
    /** When the current True or False round's reading period ends and its answer countdown starts
     * ticking (see TRUE_OR_FALSE_READING_DURATION_MS) -- 0 if no round is currently open, same
     * gating as getTrueOrFalseRoundEndsAt. renderTrueOrFalseQuestion hides the countdown number
     * until this passes. */
    public long getTrueOrFalseAnswerWindowStartsAt() { return trueOrFalseQuestion != null && trueOrFalseRoundStartedAt != 0 ? trueOrFalseRoundStartedAt + TRUE_OR_FALSE_READING_DURATION_MS : 0; }
    /** When the current True or False round's own clock (see TRUE_OR_FALSE_READING_DURATION_MS +
     * TRUE_OR_FALSE_ROUND_DURATION_MS) runs out -- 0 if no round is currently open (see
     * trueOrFalseRoundStartedAt's own doc on when that gets stamped, and trueOrFalseQuestion,
     * cleared the instant the round ends). */
    public long getTrueOrFalseRoundEndsAt() { return trueOrFalseQuestion != null && trueOrFalseRoundStartedAt != 0 ? trueOrFalseRoundStartedAt + TRUE_OR_FALSE_READING_DURATION_MS + TRUE_OR_FALSE_ROUND_DURATION_MS : 0; }
    public Boolean getTrueOrFalseLastCorrectAnswer() { return trueOrFalseLastCorrectAnswer; }
    public List<TrueOrFalseResult> getTrueOrFalseLastResults() { return trueOrFalseLastResults; }
    public long getTrueOrFalseRevealUntil() { return trueOrFalseRevealUntil; }

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
    public String getTurnAnnounceRsn() { return turnAnnounce.payload; }
    public long getTurnAnnounceUntil() { return turnAnnounce.until; }
    public long getWelcomeBannerUntil() { return welcomeBanner.until; }
    public long getMinigameBannerUntil() { return minigameBanner.until; }
    public long getGameStartBannerUntil() { return gameStartBanner.until; }
    public long getRoundCompleteBannerUntil() { return roundCompleteBanner.until; }
    public int getRoundCompleteRoundNumber() { return roundCompleteBanner.payload != null ? roundCompleteBanner.payload : 0; }
    public long getMinigameRewardsBannerUntil() { return minigameRewardsBanner.until; }
    public List<MinigameReward> getMinigameRewards() { return minigameRewardsBanner.payload != null ? minigameRewardsBanner.payload : Collections.emptyList(); }
    public List<RosterReducer.RosterEntry> getGameOverStandings() { return gameOverStandings; }
    public long getGameOverBannerUntil() { return gameOverBanner.until; }
    public long getWinnerIntroBannerUntil() { return winnerIntroBanner.until; }
    public long getPlaceRevealUntil() { return placeReveal.until; }
    public String getPlaceRevealRsn() { return placeReveal.payload != null ? placeReveal.payload.rsn : null; }
    public int getPlaceRevealRank() { return placeReveal.payload != null ? placeReveal.payload.rank : 0; }
    public int getPlaceRevealCoins() { return placeReveal.payload != null ? placeReveal.payload.coins : 0; }
    public int getPlaceRevealGoldenGnomes() { return placeReveal.payload != null ? placeReveal.payload.goldenGnomes : 0; }
    public long getWinnerSuspenseUntil() { return winnerSuspenseBanner.until; }
    public long getWinnerRevealUntil() { return winnerRevealBanner.until; }
    public String getWinnerRsn() { return winnerRevealBanner.payload; }
    public long getConfettiUntil() { return confettiBanner.until; }
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
    public String getGoldenGnomeOutcome() { return goldenGnomeOutcome.payload != null ? goldenGnomeOutcome.payload.outcome : null; }
    public String getGoldenGnomeOutcomeRsn() { return goldenGnomeOutcome.payload != null ? goldenGnomeOutcome.payload.rsn : null; }
    public long getGoldenGnomeOutcomeBannerUntil() { return goldenGnomeOutcome.until; }
    public String getGoldenGnomePopupRsn() { return goldenGnomePopup.payload != null ? goldenGnomePopup.payload.rsn : null; }
    public int getGoldenGnomePopupNewTotal() { return goldenGnomePopup.payload != null ? goldenGnomePopup.payload.newTotal : 0; }
    public long getGoldenGnomePopupStart() { return goldenGnomePopup.start; }
    public long getGoldenGnomePopupUntil() { return goldenGnomePopup.until; }
    public WorldPoint getGoldenGnomeMoveOldPoint() { return goldenGnomeMoveOldPoint; }
    public long getGoldenGnomeMoveHideOldAt() { return goldenGnomeMoveHideOldAt; }
    public WorldPoint getGoldenGnomeMoveNewPoint() { return goldenGnomeMoveNewPoint; }
    public long getGoldenGnomeMoveShowNewAt() { return goldenGnomeMoveShowNewAt; }
}
