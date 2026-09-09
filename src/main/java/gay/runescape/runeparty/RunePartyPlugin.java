package gay.runescape.runeparty;

import gay.runescape.runeparty.courses.CourseBuilder;
import gay.runescape.runeparty.courses.CoursePreset;
import gay.runescape.runeparty.courses.HardcodedCourse;

import gay.runescape.runeparty.presentation.CeremonyPresentation;
import gay.runescape.runeparty.presentation.ChanceSpacePresentation;
import gay.runescape.runeparty.presentation.GoldenGnomePresentation;
import gay.runescape.runeparty.presentation.ItemPresentation;
import gay.runescape.runeparty.presentation.JadPresentation;
import gay.runescape.runeparty.presentation.MinigamePresentation;
import gay.runescape.runeparty.net.MinigameReward;
import gay.runescape.runeparty.net.MinigameScore;

import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.EventSocket;
import gay.runescape.runeparty.net.EventListener;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;

import gay.runescape.runeparty.session.SessionManager;

import gay.runescape.runeparty.minigames.DanceDanceRuneScapePresentation;

import com.google.gson.Gson;
import com.google.inject.Provides;
import gay.runescape.runeparty.items.Items;
import gay.runescape.runeparty.models.HotPotatoExplosionModel;
import gay.runescape.runeparty.overlays.AnnouncementOverlay;
import gay.runescape.runeparty.overlays.ClickClickClickOverlay;
import gay.runescape.runeparty.overlays.CoinRushScoreboardOverlay;
import gay.runescape.runeparty.overlays.ConfettiOverlay;
import gay.runescape.runeparty.overlays.DanceDanceRuneScapeHudOverlay;
import gay.runescape.runeparty.overlays.DanceDanceRuneScapeOverlay;
import gay.runescape.runeparty.overlays.FishingCatchOverlay;
import gay.runescape.runeparty.overlays.HardcodedCourseLauncherOverlay;
import gay.runescape.runeparty.overlays.HotPotatoOverlay;
import gay.runescape.runeparty.overlays.JadEncounter;
import gay.runescape.runeparty.overlays.JaddyDuelModel;
import gay.runescape.runeparty.overlays.PlayerOverlay;
import gay.runescape.runeparty.overlays.RunePartyMapOverlay;
import gay.runescape.runeparty.overlays.SandwichRushHudOverlay;
import gay.runescape.runeparty.overlays.StatsOverlay;
import gay.runescape.runeparty.overlays.TileOverlay;
import gay.runescape.runeparty.overlays.TurfWarsScoreOverlay;
import java.awt.Color;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.Menu;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.api.Tile;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.events.AnimationChanged;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.api.events.MenuOpened;
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
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;
import lombok.extern.slf4j.Slf4j;

/** Entry point and state hub for Rune Party. The turn engine talks to a real server over a
 * report-then-wait-for-the-echo pattern: action methods below only ever request something (roll,
 * arrival, purchase, minigame result); the authoritative outcome always comes back through
 * handleEvent(). */
@Slf4j
@PluginDescriptor(name = "Rune Party")
public class RunePartyPlugin extends Plugin
{
    /** Caps the turn order at the size of RunePartyColor's palette, so every PLAYER always gets a
     * distinct color and the host never has to decide who shares one. */
    public static final int MAX_PLAYERS = 8;

    /** How long AnnouncementOverlay's "<player>'s Turn" banner stays up after TURN_STARTED --
     * purely a client-side timer, not anything the server tracks. */
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

    /** How long AnnouncementOverlay's "ITEM SPACE!" banner stays up once it actually appears --
     * server-driven, so every client shows it at the same moment. Same "MINIGAME!" shape as
     * MINIGAME_BANNER_DURATION_MS -- see scheduleItemBanner. */
    public static final long ITEM_BANNER_DURATION_MS = 2800;

    /** Same shape as MINIGAME_SPINNER_SPIN_PHASE_MS/HOLD_MS/DURATION_MS above, but for the Item
     * Space wheel (see ITEM_GRANTED handling/scheduleItemSpinner) -- kept as its own set of
     * constants rather than reusing the mini-game ones so each wheel's pacing can be tuned
     * independently, even though AnnouncementOverlay's actual wheel-drawing code (drawWheel) is
     * fully shared between the two. */
    public static final long ITEM_SPINNER_SPIN_PHASE_MS = 4000;
    public static final long ITEM_SPINNER_HOLD_MS = 3000;
    public static final long ITEM_SPINNER_DURATION_MS = ITEM_SPINNER_SPIN_PHASE_MS + ITEM_SPINNER_HOLD_MS;

    /** How long AnnouncementOverlay's "what this item does" announcement stays up once the wheel
     * settles -- see scheduleItemGrantDescription, chained right behind the wheel's own reveal,
     * the item-flow counterpart to the mini-game's own ready-check screen following its spinner. */
    public static final long ITEM_GRANT_DESCRIPTION_DURATION_MS = 3500;

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
     * RunePartyMapOverlay's own north-up 2D map, so the two board-viewing tools agree on
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

    /** Client-side key for the Coin Rush mini-game -- must match the server's own registration,
     * the same "key" MINIGAME_STARTED's payload carries for every mini-game. Compared directly
     * against minigameKey wherever coin-rush-specific behavior (spawn tracking, the live
     * scoreboard, the auto-collect check in onGameTick) needs to gate on "is this round actually
     * Coin Rush," rather than going through Minigames#get. */
    public static final String COIN_RUSH_KEY = "coin-rush";

    /** Wall-clock length of one Coin Rush round, measured from the moment the round actually
     * becomes playable (see coinRushRoundStartAt/getCoinRushEndsAt) -- purely a client-side display
     * value for StatsOverlay's live countdown; the server is the one that actually ends the round
     * via MINIGAME_ENDED regardless of what this says. */
    public static final long COIN_RUSH_DURATION_MS = 30000;

    /** Coins a single Coin Rush pickup is worth -- must match the server's own reward-per-coin.
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

    /** Client-side key for the True or False mini-game -- must match the server's own
     * registration, same role COIN_RUSH_KEY plays for Coin Rush. */
    public static final String TRUE_OR_FALSE_KEY = "true-or-false";

    /** Client-side key for the Arena mini-game -- must match the server's own registration, same
     * role COIN_RUSH_KEY/TRUE_OR_FALSE_KEY play for their own mini-games. Used by
     * AnnouncementOverlay to swap the generic "3...2...1...BEGIN!" countdown for Arena's own "All
     * players must stand within the arena!" gather message. */
    public static final String ARENA_KEY = "arena";

    /** Client-side key for the Fishing Contest mini-game -- must match the server's own
     * registration, same role ARENA_KEY/COIN_RUSH_KEY play for their own mini-games. */
    public static final String FISHING_CONTEST_KEY = "fishing-contest";

    /** How long a Fishing Contest round's own local catch loop runs before the local player's
     * final tally gets submitted -- must match the server's own fishing duration. Measured from
     * MINIGAME_ROUND_BEGIN, not from whenever an individual player happens to right-click the pond
     * -- everyone's local timer runs out at the same moment, matching what the server's own
     * bounded wait expects. */
    public static final long FISHING_CONTEST_DURATION_MS = 30000;

    /** Client-side key for the Click, Click, Click mini-game -- must match the server's own
     * registration, same role ARENA_KEY/FISHING_CONTEST_KEY play for their own mini-games. */
    public static final String CLICK_CLICK_CLICK_KEY = "click-click-click";

    /** How long a Click, Click, Click round's own local click-tallying window runs before the
     * local player's final unique-tile count gets submitted -- must match the server's own
     * duration. Measured from MINIGAME_ROUND_BEGIN, same "everyone's local timer runs out at the
     * same moment" reasoning FISHING_CONTEST_DURATION_MS's own doc gives. */
    public static final long CLICK_CLICK_CLICK_DURATION_MS = 30000;

    /** Client-side key for the Hot Potato mini-game -- must match the server's own registration,
     * same role ARENA_KEY/FISHING_CONTEST_KEY/CLICK_CLICK_CLICK_KEY play for their own mini-games. */
    public static final String HOT_POTATO_KEY = "hot-potato";

    /** How long a whole Hot Potato round lasts -- must match the server's own round length.
     * Measured from MINIGAME_ROUND_BEGIN, same "stamped instant + fixed duration" shape
     * COIN_RUSH_DURATION_MS/CLICK_CLICK_CLICK_DURATION_MS already use. */
    public static final long HOT_POTATO_DURATION_MS = 30000;

    /** Client-side key for the Turf Wars mini-game -- must match the server's own registration,
     * same role ARENA_KEY/FISHING_CONTEST_KEY play for their own mini-games. */
    public static final String TURF_WARS_KEY = "turf-wars";

    /** How long a whole Turf Wars round lasts -- must match the server's own round length.
     * Measured from MINIGAME_ROUND_BEGIN, same "stamped instant + fixed duration" shape
     * COIN_RUSH_DURATION_MS already uses. */
    public static final long TURF_WARS_ROUND_MS = 60000;

    /** Turf Wars' two fixed team colors, used for an even seated-PLAYER count's round (an odd
     * count instead gives every player their own individual, existing RunePartyColor seat color)
     * -- same hex the server pushes as each tile's own color, so a tile's server-driven fill and
     * this client's own scoreboard/banner always agree. Local constants rather than reusing
     * RunePartyColor for the 2-team case -- that enum is a per-seat roster palette, not a fixed
     * team identity -- deliberately chosen to be visually distinct from every RunePartyColor
     * entry too, so a team-recolored player never reads as "that's just their normal seat color". */
    public static final Color TEAM_A_COLOR = new Color(0xE6, 0x1E, 0x96);
    public static final Color TEAM_B_COLOR = new Color(0x00, 0xAA, 0xAA);

    /** Client-side key for the Sandwich Rush mini-game -- must match the server's own
     * registration, same role TURF_WARS_KEY/COIN_RUSH_KEY play for their own mini-games. Used by
     * AnnouncementOverlay to swap the generic "3...2...1...BEGIN!" countdown for the same
     * arrival-gated "All players must stand within the arena!" gather message Arena/Turf Wars
     * already use. */
    public static final String SANDWICH_RUSH_KEY = "sandwich-rush";

    /** How long a whole Sandwich Rush round lasts -- must match the server's own round length.
     * Measured from MINIGAME_ROUND_BEGIN, same "stamped instant + fixed duration" shape
     * COIN_RUSH_DURATION_MS/TURF_WARS_ROUND_MS already use. */
    public static final long SANDWICH_RUSH_DURATION_MS = 60000;

    /** One entry per Sandwich Rush ingredient key (must match the server's own ingredient list) --
     * the real 3D model id models/SandwichItemModel spawns for it, paired with the raster icon
     * SandwichRushHudOverlay draws for it. LinkedHashMap so both consumers iterate in one fixed,
     * deliberate order (tomato, cheese, cabbage, bread) rather than whatever order a plain
     * HashMap happens to produce. */
    public static final Map<String, Integer> SANDWICH_RUSH_ITEM_MODEL_IDS;
    public static final Map<String, String> SANDWICH_RUSH_ITEM_ICON_RESOURCES;
    static
    {
        Map<String, Integer> models = new LinkedHashMap<>();
        models.put("tomato", 2632);
        models.put("cheese", 2365);
        models.put("cabbage", 8195);
        models.put("bread", 2409);
        SANDWICH_RUSH_ITEM_MODEL_IDS = Collections.unmodifiableMap(models);

        Map<String, String> icons = new LinkedHashMap<>();
        icons.put("tomato", "minigame_resources/tomato.png");
        icons.put("cheese", "minigame_resources/cheese.png");
        icons.put("cabbage", "minigame_resources/cabbage.png");
        icons.put("bread", "minigame_resources/bread.png");
        SANDWICH_RUSH_ITEM_ICON_RESOURCES = Collections.unmodifiableMap(icons);
    }

    /** Client-side key for the Who's Your Jaddy? mini-game -- must match the server's own
     * registration, same role ARENA_KEY/TURF_WARS_KEY/SANDWICH_RUSH_KEY play for their own
     * mini-games. Used by AnnouncementOverlay to swap the generic "3...2...1...BEGIN!" countdown
     * for the same arrival-gated gather message Arena/Turf Wars/Sandwich Rush already use, with its
     * own "pick a side" wording. */
    public static final String JADDY_KEY = "whos-your-jaddy";

    /** Client-side key for the Dance, Dance, RuneScape mini-game -- must match the server's own
     * registration, same role ARENA_KEY/FISHING_CONTEST_KEY/CLICK_CLICK_CLICK_KEY play for their
     * own mini-games. Used by AnnouncementOverlay to swap the generic "3...2...1...BEGIN!"
     * countdown for the same arrival-gated gather message Arena/Turf Wars/Sandwich Rush/Jaddy/Hot
     * Potato already use. */
    public static final String DANCE_DANCE_RUNESCAPE_KEY = "dance-dance-runescape";

    /** Client-side key for the Rainbow Rush mini-game -- must match the server's own registration,
     * same role every other {@code *_KEY} plays for its own mini-game. Unlike every board-swapping
     * mini-game above, Rainbow Rush never swaps the board at all -- it temporarily recolors the
     * live main course itself (see TileOverlay#renderRainbowRushTile), and its round has no fixed
     * duration: it ends the instant some player's own RainbowRushPresentation reports having
     * visited every course tile, with RAINBOW_RUSH_MAX_DURATION_MS below as a server-enforced
     * backup ceiling only. */
    public static final String RAINBOW_RUSH_KEY = "rainbow-rush";

    /** Backup ceiling on a Rainbow Rush round -- not a normal win condition (see RAINBOW_RUSH_KEY's
     * own doc), just a failsafe in case nobody ever manages to visit every course tile (e.g. an
     * unreachable fork in a host-built course). Five minutes, deliberately much longer than every
     * other mini-game's own fixed COIN_RUSH_DURATION_MS-shaped timer, since finishing this one
     * means physically walking the *entire* course rather than a compact arena. */
    public static final long RAINBOW_RUSH_MAX_DURATION_MS = 300_000;

    /** How long each light of Rainbow Rush's own "traffic light" get-ready sequence stays lit --
     * red, then orange, then green (see AnnouncementOverlay#renderRainbowRushTrafficLight) --
     * purely a client-local animation, timed off MINIGAME_ROUND_BEGIN's own arrival timestamp
     * (RainbowRushPresentation#getRoundStartAt) rather than any dedicated server event, since every
     * client already receives that one event at essentially the same moment. Doubles as the real
     * gate on RainbowRushPresentation#onTick actually starting to track visited tiles -- nothing
     * should count as "visited" while the light's still red/orange, or a fast player could score a
     * tile before the on-screen "Begin!" even appears. */
    public static final long RAINBOW_RUSH_LIGHT_PHASE_MS = 1000;
    public static final long RAINBOW_RUSH_TRAFFIC_LIGHT_MS = 3 * RAINBOW_RUSH_LIGHT_PHASE_MS;

    /** TzTok-Jad's death animation -- new to this codebase, unlike JAD_SMASH_ANIMATION_ID/
     * JAD_IDLE_ANIMATION_ID/JAD_BOW_ACKNOWLEDGE_ANIMATION_ID (all reused here from the single-Jad
     * Jad Tile encounter, see JadPresentation/JadEncounter) -- played once, by the losing side's
     * Jad, when JADDY_DUEL_RESOLVED lands (see models/JaddyDuelModel). */
    public static final int JAD_DEATH_ANIMATION_ID = 2654;

    /** How long an attack animation (JAD_BOW_ACKNOWLEDGE_ANIMATION_ID/JAD_SMASH_ANIMATION_ID,
     * alternated per beat by the server's own JADDY_ATTACK_TRIGGERED) plays before both Jads return
     * to their idle loop -- a first estimate, not measured, same caveat every other animation-hold
     * constant in this class already carries. */
    public static final long JADDY_ATTACK_ANIMATION_HOLD_MS = 1800;

    /** How long the winning Jad keeps standing (looping JAD_IDLE_ANIMATION_ID) after the loser's
     * own death animation finishes before both despawn -- purely client-timed, there's no separate
     * server event marking "done" the way JAD_DISMISSED does for the single-Jad encounter. Matches
     * JADDY_RESOLVED_BANNER_DURATION_MS so the "&lt;color&gt; wins!" banner and the winning Jad
     * disappear together. */
    public static final long JADDY_DEATH_HOLD_MS = 5000;

    /** How long AnnouncementOverlay's "&lt;color&gt; wins!" duel-resolved banner stays up once
     * JADDY_DUEL_RESOLVED lands -- matches JADDY_DEATH_HOLD_MS, see that field's own doc. */
    public static final long JADDY_RESOLVED_BANNER_DURATION_MS = 5000;

    /** How long AnnouncementOverlay's team-assigned reveal banner stays up -- fired once, right
     * after MINIGAME_TEAMS_ASSIGNED lands for the local player (see MinigamePresentation#
     * triggerTeamAssignedBanner), same duration as MINIGAME_OVER_BANNER_DURATION_MS above (both
     * are a single beat of context, not something a player needs to read at length). */
    public static final long TEAM_ASSIGNED_BANNER_DURATION_MS = 3000;

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

    /** How long AnnouncementOverlay's "MINIGAME OVER!" banner stays up -- fired on every
     * MINIGAME_ENDED, for every mini-game, before the rewards recap even starts (see
     * MinigamePresentation#triggerMinigameOverBanner, called first in handleMinigameEnded, and
     * triggerMinigameRewardsBanner right behind it -- both armBanner calls, so the rewards recap
     * automatically waits behind this one via the shared turnEffectGateUntil, same chaining every
     * other back-to-back banner pair here already uses). */
    public static final long MINIGAME_OVER_BANNER_DURATION_MS = 3000;

    /** How long AnnouncementOverlay's mini-game final-score recap ("how did everyone do") stays up
     * -- triggered on MINIGAME_ENDED (see triggerMinigameScoreBanner), shown *after* the "MINIGAME
     * OVER!" banner above and *before* the rewards recap below: chained via the same armBanner/
     * turnEffectGateUntil sequencing every other back-to-back banner pair here already uses, so
     * players see how they scored before finding out what (if anything) that earned them. */
    public static final long MINIGAME_SCORE_BANNER_DURATION_MS = 6000;

    /** How long AnnouncementOverlay's mini-game rewards recap ("who got what") stays up -- also
     * triggered on MINIGAME_ENDED (see triggerMinigameRewardsBanner), but shown *after* the
     * "MINIGAME OVER!" banner and the final-score recap above, and *before* the round recap: all
     * of scheduleRoundCompleteBanner/triggerMinigameRewardsBanner/triggerMinigameScoreBanner defer
     * via scheduleAfterTurnEffects/armBanner, which wait on turnEffectGateUntil -- extended by
     * whichever banner armed most recently -- so none of them ever overlap. */
    public static final long MINIGAME_REWARDS_BANNER_DURATION_MS = 7500;

    /** How long AnnouncementOverlay's Golden Gnome outcome banner ("You got a Golden Gnome!")
     * stays up -- fires immediately on GOLDEN_GNOME_PURCHASED, same
     * as the coin/Golden-Gnome-count popups it can appear alongside, rather than waiting on
     * scheduleAfterTurnEffects itself; like those popups it calls extendTurnEffectGate instead, so
     * it's the *next* turn's announcement (TURN_STARTED/MINIGAME!) that waits for this one, the
     * count popup, and the underlying tile's own coin popup to all finish settling. */
    public static final long GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS = 2600;

    /** How long AnnouncementOverlay's Jad encounter countdown counts down from -- purely cosmetic,
     * loosely paired with the server's own bow window, not protocol-coupled to it -- the server's
     * own clock is what actually enforces the window, this is just what the countdown number
     * displays. */
    public static final long JAD_BOW_WINDOW_MS = 5000;

    // "lordmagmus_smash" -- played once on the Jad model when JAD_SMASH_TRIGGERED lands (see
    // JadEncounter#playSmash), same one-shot idiom TileOverlay's own COIN_TRAP_SPRING_ANIMATION_ID
    // already uses. JAD_SMASH_ANIMATION_HOLD_MS is a first estimate of how long it plays for (not
    // measured, same caveat JAD_SMASH_ANIMATION_SECONDS's own doc carries), after which Jad
    // returns to JAD_IDLE_ANIMATION_ID for whatever's left of the real encounter window before
    // JAD_DISMISSED despawns it.
    public static final int JAD_SMASH_ANIMATION_ID = 2652;
    public static final long JAD_SMASH_ANIMATION_HOLD_MS = 1800;

    // Looped for as long as Jad's standing there waiting on a response (from the moment the model
    // reveals until playSmash() takes over, see JadEncounter's own render()) -- an idle/taunt stance
    // rather than the smash's own one-shot animation.
    public static final int JAD_IDLE_ANIMATION_ID = 2650;

    // Played once on the Jad model after the "Your loyalty will cost you N coins!" outcome banner
    // has had JAD_OUTCOME_BANNER_DURATION_MS to be read (see JadPresentation's JAD_DISMISSED
    // "bowed" branch and RunePartyPlugin's own handleEvent, which schedules
    // JadEncounter#playBowThenClear that far out instead of firing it immediately) -- a one-shot
    // reaction to being bowed to, same idiom as JAD_SMASH_ANIMATION_ID but for the opposite
    // outcome. Purely client-side timing throughout (no server timer backs any of this, unlike the
    // smash/penalty sequence -- bowing, and the coin toll it costs, already closed the encounter
    // server-side the instant it landed): JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS is a first estimate
    // of how long 2655 itself plays for (not measured, same caveat JAD_SMASH_ANIMATION_SECONDS's
    // own doc carries), after which Jad returns to JAD_IDLE_ANIMATION_ID for
    // JAD_BOW_ACKNOWLEDGE_IDLE_HOLD_MS before actually despawning.
    public static final int JAD_BOW_ACKNOWLEDGE_ANIMATION_ID = 2655;
    public static final long JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS = 1000;
    public static final long JAD_BOW_ACKNOWLEDGE_IDLE_HOLD_MS = 3000;

    // Flat coin toll bowing costs (see AnnouncementOverlay's "Your loyalty will cost you..." banner
    // and the COINS_CHANGED reason="jad_bow" popup) -- must match the server's own bow coin cost.
    // Only needed client-side as display text: the banner announces the nominal cost before the
    // real COINS_CHANGED (already floored at 0 server-side, same as any other debit) has even
    // arrived, so unlike JAD_SMASH's own chat line -- which just echoes back whatever real delta
    // the event already carried -- there's no event payload to read this from yet at the point the
    // banner needs it. Loosely paired with the server constant, not protocol-coupled.
    public static final int JAD_BOW_COIN_COST = 15;

    /** How long AnnouncementOverlay's Jad outcome banner stays up -- "Your loyalty will cost you N
     * coins!" on the bowed path, "You chose not to bow to Jad!" on the smashed one. Fires on
     * JAD_DISMISSED/JAD_SMASH_TRIGGERED, same duration/queuing shape as
     * GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS (see JadPresentation's own armBanner calls). The
     * bowed path also reuses this same duration as the delay before the bow-acknowledge animation
     * and the eventual coin popup fire (see handleEvent's JAD_DISMISSED/COINS_CHANGED cases), so
     * the toll is never seen being deducted mid-sentence, before the announcement's even done
     * fading. */
    public static final long JAD_OUTCOME_BANNER_DURATION_MS = GOLDEN_GNOME_OUTCOME_BANNER_DURATION_MS;

    /** How long AnnouncementOverlay's "CHANCE TILE!" title card stays up before the three-icon
     * tableau below takes over -- same single-line-title treatment/duration idiom as
     * ITEM_BANNER_DURATION_MS's own "ITEM SPACE!" card. See ChanceSpacePresentation's own
     * scheduleAfterTurnEffects chain for how this leads into CHANCE_SPACE_ICON_STAGE_DURATION_MS. */
    public static final long CHANCE_SPACE_TITLE_DURATION_MS = 1800;

    /** Gap between each of the three Chance Tile tableau icons' own reveal (see
     * ChanceSpacePresentation#buildIconStagePayload's randomized per-slot delay) -- staggered so
     * they pop in one at a time rather than all at once, in a different order every time. Long
     * enough (2s) that each icon actually registers as its own beat rather than a blur. */
    public static final long CHANCE_SPACE_ICON_STAGE_STAGGER_MS = 2000;

    /** How long a single Chance Tile tableau icon takes to pop in from oversized down to its
     * resting scale, once its own staggered delay above has elapsed -- see
     * AnnouncementOverlay#slotAnim's fade-in/scale-in window, shared by all three icons. */
    public static final long CHANCE_SPACE_ICON_POP_MS = 250;

    /** Extra pause after the last icon finishes popping in before the announcement text below the
     * tableau starts fading in -- a short beat so the reveal doesn't feel like it's talking over
     * its own icons. */
    public static final long CHANCE_SPACE_TEXT_REVEAL_DELAY_MS = 300;

    /** How long the announcement text (see ChanceSpacePresentation#buildAnnouncementLines) takes to
     * fade in beneath the icon tableau, once CHANCE_SPACE_TEXT_REVEAL_DELAY_MS above has passed. */
    public static final long CHANCE_SPACE_TEXT_FADE_MS = 400;

    /** Offset from the start of the icon-tableau stage at which the announcement text begins fading
     * in -- every icon's own delay/pop-in window plus the pause above. Single source of truth for
     * AnnouncementOverlay#drawChanceSpaceAnnouncement's own fade-in timing and
     * ChanceSpacePresentation's own deferred coin/gnome popup (see that class's own doc on why the
     * real delta is held back until the sequence plays out) -- both need to agree on exactly when
     * the reveal actually happens, so this is computed once, here, rather than separately by each. */
    public static final long CHANCE_SPACE_TEXT_START_OFFSET_MS =
        2 * CHANCE_SPACE_ICON_STAGE_STAGGER_MS + CHANCE_SPACE_ICON_POP_MS + CHANCE_SPACE_TEXT_REVEAL_DELAY_MS;

    /** How long the icons and swap announcement hold together, fully visible, before the whole
     * tableau fades out -- long enough to actually read both lines of text (a header plus one
     * directional gift line). */
    public static final long CHANCE_SPACE_TEXT_HOLD_MS = 6000;

    /** Total lifetime of the Chance Tile icon-tableau banner: CHANCE_SPACE_TEXT_START_OFFSET_MS
     * (every icon's own delay + pop-in time, plus the pause before text starts), the text's own
     * fade-in, then the hold above. Must cover every icon's own delay + pop-in time and the text's
     * own reveal, or either would get cut off fading out mid-reveal. */
    public static final long CHANCE_SPACE_ICON_STAGE_DURATION_MS =
        CHANCE_SPACE_TEXT_START_OFFSET_MS + CHANCE_SPACE_TEXT_FADE_MS + CHANCE_SPACE_TEXT_HOLD_MS;

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

    /** Spotanim played directly on the target's own actor the instant a Tele Block lands on them
     * (see TELE_BLOCK_APPLIED handling) -- the real spell's own impact graphic (SpotanimID's own
     * doc groups it right next to TELEPORT_OTHER_IMPACT/CASTING, same "cast at another player"
     * family), not a generic placeholder. Played via Actor#createSpotAnim (see
     * triggerSpotAnimOnPlayer), unlike GOLDEN_GNOME_MOVE_SPOTANIM_ID's own fixed-world-point
     * projectile trick, since this needs to follow the target's actor, not sit at one tile. */
    public static final int TELE_BLOCK_IMPACT_SPOTANIM_ID = SpotanimID.TELE_BLOCK_IMPACT;

    /** Height offset (in the same units Actor#createSpotAnim itself takes) for
     * TELE_BLOCK_IMPACT_SPOTANIM_ID -- a first estimate for "roughly chest height," not measured
     * against the real spell in-client, same caveat every other un-measured animation-hold/effect
     * constant in this codebase already carries (see e.g. JAD_SMASH_ANIMATION_HOLD_MS's own doc). */
    public static final int TELE_BLOCK_IMPACT_SPOTANIM_HEIGHT = 100;

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

    @Inject public Client client; // public: CourseBuilder builds its own RuneLite menu entries directly
    @Inject private ClientThread clientThread;
    @Inject public ConfigManager configManager;
    @Inject private RunePartyConfig config;
    @Inject private ClientToolbar clientToolbar;
    @Inject private OverlayManager overlayManager;
    @Inject private ModelOutlineRenderer modelOutlineRenderer;
    @Inject private TooltipManager tooltipManager;
    @Inject private OkHttpClient okHttpClient;
    @Inject private Gson gson;

    private TileReducer tileReducer;
    private TileOverlay tileOverlay;
    private StatsOverlay statsOverlay;
    private CoinRushScoreboardOverlay coinRushScoreboardOverlay;
    private PlayerOverlay playerOverlay;
    private AnnouncementOverlay announcementOverlay;
    private ConfettiOverlay confettiOverlay;
    private JadEncounter jadEncounter;
    private JaddyDuelModel jaddyDuelModel;
    private FishingCatchOverlay fishingCatchOverlay;
    private ClickClickClickOverlay clickClickClickOverlay;
    private HotPotatoOverlay hotPotatoOverlay;
    private HotPotatoExplosionModel hotPotatoExplosionModel;
    private TurfWarsScoreOverlay turfWarsScoreOverlay;
    private SandwichRushHudOverlay sandwichRushHudOverlay;
    private DanceDanceRuneScapeOverlay danceDanceRuneScapeOverlay;
    private DanceDanceRuneScapeHudOverlay danceDanceRuneScapeHudOverlay;
    private HardcodedCourseLauncherOverlay hardcodedCourseLauncherOverlay;
    private RosterReducer rosterReducer;
    public ApiClient apiClient; // public: presenters in the minigames subpackage issue their own requests
    public EventSocket eventSocket;

    // Per-feature presenter objects -- each owns its own fields, folds its own event types via
    // apply()/a dedicated method, and clears itself via reset(). Constructed once in startUp()
    // (same convention as apiClient above), not reconstructed per game -- resetState() calls each
    // one's reset() instead.
    private GoldenGnomePresentation goldenGnomePresentation;
    private CeremonyPresentation ceremonyPresentation;
    private ItemPresentation itemPresentation;
    private MinigamePresentation minigamePresentation;
    private JadPresentation jadPresentation;
    private ChanceSpacePresentation chanceSpacePresentation;
    // Neither of these two is a "Presentation" -- both own real interactive behavior (building
    // RuneLite menu entries, issuing mark/unmark-tiles requests; making create/join/session API
    // calls) rather than cosmetic banner/timing state. Same constructed-once-in-startUp/
    // reset()-via-resetState() convention as the presenters above.
    private CourseBuilder courseBuilder;
    private SessionManager sessionManager;
    private RunePartyPanel panel;
    private NavigationButton navButton;
    private RunePartyMapOverlay mapOverlay;
    // Toggled by the panel's "Show Map"/"Hide Map" button (see toggleMap) -- read by
    // RunePartyMapOverlay#render (whether to draw at all) and AnnouncementOverlay#render (which
    // suppresses every banner/announcement while this is true, see that class's own doc).
    private volatile boolean mapShowing = false;

    // Server-wide, not game-scoped -- fetched once at startup (see loadTileTypeCatalog) rather
    // than per-game like the roster, since the tiles/ registry it mirrors never changes at
    // runtime. Empty until the fetch completes (or forever, if it fails) -- every consumer
    // (TileOverlay, RunePartyMapOverlay) already falls back to a default color/label on a miss.
    private volatile Map<String, ApiClient.TileTypeOut> tileTypeCatalog = new LinkedHashMap<>();

    public final ExecutorService executor = Executors.newSingleThreadExecutor(r ->
    {
        Thread t = new Thread(r, "runeparty-actions");
        t.setDaemon(true);
        return t;
    });

    // Dedicated to delayed, purely-cosmetic UI timers (see scheduleTurnAnnouncement) -- kept
    // separate from `executor` above so a pending delay can never queue behind (or block) a real
    // network call.
    // Public: presenters in the presentation subpackage (GoldenGnomePresentation's
    // GOLDEN_GNOME_MOVED handling, MinigamePresentation's MINIGAME_COUNTDOWN_STARTED handling)
    // schedule their own raw nested delayed callbacks against this, not just through
    // scheduleAfterTurnEffects/armBanner. A caller with no reason to reach it directly (e.g.
    // models/JadEncounter) should still prefer scheduleDelayed instead.
    public final ScheduledExecutorService uiTimerExec = Executors.newSingleThreadScheduledExecutor(r ->
    {
        Thread t = new Thread(r, "runeparty-ui-timer");
        t.setDaemon(true);
        return t;
    });

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

    public volatile GamePhase phase = GamePhase.DISCONNECTED;

    // ---- session ----
    public volatile String gameId = null; // public: CoinRushPresentation/SandwichRushPresentation's own collect methods read this
    public volatile String writeKey = null; // public (like gameId/playerToken): non-null only for the host, read directly by CourseBuilder
    public volatile String playerToken = null; // public: CoinRushPresentation/SandwichRushPresentation's own collect methods read this
    // Non-null only once this game has been permanently locked to a Standard Course (see
    // SessionManager#createGameFromHardcodedCourse/ApiClient#lockStandardCourse and the
    // STANDARD_COURSE_LOCKED handling below) -- HardcodedCourse#key, not its display name. Once
    // set, it never goes back to null for the life of this game. Gates course-building tool
    // visibility in RunePartyPanel.
    private volatile String standardCourseKey = null;

    // ---- turn engine ----
    public volatile String currentTurnRsn = null;
    public volatile Integer lastDiceRoll = null;
    private volatile boolean pendingRoll = false;
    // Guards the Spin-emote roll trigger against double-submitting while a roll request is in
    // flight but the server's DICE_ROLLED echo (which flips pendingRoll) hasn't landed yet -- see
    // onAnimationChanged and rollDice().
    private volatile boolean rollRequestSubmitted = false;
    // True from the moment the local player's Spin emote starts (on their own turn) until it
    // finishes -- onAnimationChanged only actually calls rollDice() on the animation change that
    // clears this, so the roll never fires mid-emote.
    private volatile boolean awaitingSpinFinish = false;
    // True from the moment the local player's Headbang emote starts until it finishes -- same
    // "wait for the animation to actually finish, not just start" idiom as awaitingSpinFinish, see
    // onAnimationChanged's EMOTE_DANCE_HEADBANG branch / performFishingCatchRoll.
    private volatile boolean awaitingHeadbangFinish = false;
    // At most one of these seven "awaiting" flags (this one plus GoldenGnomePresentation's own
    // Golden Gnome pair, plus MinigamePresentation's own mini-game/True-or-False three, plus
    // awaitingHeadbangFinish above) is ever true at once -- see onAnimationChanged. A roll and a
    // Golden Gnome offer are both only possible outside a mini-game (isLocalPlayerReadyToRoll
    // requires !minigameActive), a mini-game ready-check is only possible before its own countdown
    // starts (isLocalPlayerAwaitingMinigameReady requires !minigameCountdownStarted), a True or
    // False answer is only possible once that round's actually playable
    // (isLocalPlayerAwaitingTrueOrFalseAnswer requires isMinigamePlayable(), which itself requires
    // the countdown to have both started and finished), and a Headbang catch roll is only possible
    // while Fishing Contest is the active mini-game (isFishingContestActive) -- since exactly one
    // mini-game (or none) is ever active at a time, no two of these can ever overlap.
    // Candidate destination tiles for the current roll -- more than one when the roll's path
    // crosses a fork (see TileOverlay#renderTargetArrow, which draws one arrow per candidate).
    // Never null, only ever empty.
    private volatile List<Integer> pendingTargetIndices = Collections.emptyList();
    // The wider "everywhere this roll passes within reach of" set (see DICE_ROLLED's own
    // reachableIndices field and _reachable_within's doc) -- at most `value` steps out, not just
    // exactly `value` like pendingTargetIndices. Used to gate hoveredPurchasableGoldenGnomePoint so
    // the menu entry only ever offers a Golden Gnome that's genuinely reachable this roll, rather
    // than relying on the server's own 409 to find that out only after attempting the purchase.
    // Never null, only ever empty.
    private volatile List<Integer> pendingReachableIndices = Collections.emptyList();
    private volatile boolean arrivalSubmitted = false; // guards confirm-arrival from firing every tick while the echo is in flight
    // Guards confirmHomeTeleportArrival the same way arrivalSubmitted guards confirmArrival --
    // see onGameTick's own Home Teleport check, which is independent of pendingRoll (see that
    // field's own doc for why Home Teleport can be pending well outside any roll window).
    private volatile boolean homeTeleportArrivalSubmitted = false;
    // Guards reportMinigamePosition against piling up requests on the single-threaded executor if
    // any one round-trip ever takes longer than a game tick -- unlike arrivalSubmitted/
    // homeTeleportArrivalSubmitted (each cleared only once the specific claim they guard is
    // resolved one way or another), this one is meant to fire again every single tick, so it's
    // cleared unconditionally in submitAction's own finallyAction the instant each call resolves,
    // not selectively on success/failure. See onGameTick's own reportMinigamePosition check.
    private final AtomicBoolean minigamePositionReportInFlight = new AtomicBoolean(false);
    // Which mini-games actually read reportMinigamePosition's own server-side cache
    // (MinigameContext.get_positions), and for how long -- see onGameTick's own position-heartbeat
    // check, the only reader of these two sets. Coin Rush/Click-Click-Click/True or
    // False/Fishing Contest never read positions at all; every other mini-game's own server module
    // was checked directly for every get_positions() call site to build this split, not guessed:
    //   - MINIGAMES_NEEDING_CONTINUOUS_POSITION: reads positions for the round's entire live
    //     duration, not just to detect arrival -- Arena/Turf Wars evaluate hazard tiles/tile
    //     capture every tick for as long as the round runs, and Who's Your Jaddy captures whoever's
    //     standing in the winning zone at the exact (unpredictable -- damage-roll-dependent)
    //     instant the duel resolves, which could be many ticks after round-begin.
    //   - MINIGAMES_NEEDING_PRE_ROUND_POSITION: reads positions only inside their own
    //     _wait_for_everyone_to_arrive gather gate, never again once MINIGAME_ROUND_BEGIN fires --
    //     Sandwich Rush/Rainbow Rush/Dance Dance RuneScape/Hot Potato all fit this shape.
    private static final Set<String> MINIGAMES_NEEDING_CONTINUOUS_POSITION = Set.of(ARENA_KEY, TURF_WARS_KEY, JADDY_KEY);
    private static final Set<String> MINIGAMES_NEEDING_PRE_ROUND_POSITION = Set.of(
        SANDWICH_RUSH_KEY, RAINBOW_RUSH_KEY, DANCE_DANCE_RUNESCAPE_KEY, HOT_POTATO_KEY);
    // ---- Fishing Contest (entirely client-local until the one final submission -- catches are
    // never reported per-catch). Every completed Headbang emote near the Fish bowl rolls one
    // catch (see onAnimationChanged's
    // EMOTE_DANCE_HEADBANG branch / performFishingCatchRoll) -- there's no "start fishing" state,
    // catching is just however many Headbangs land within a round. shrimpCount/anchovyCount are
    // owned solely by the client thread (performFishingCatchRoll, onGameTick's submission check),
    // same single-writer assumption every other per-tick field in this class already relies on, so
    // plain (non-atomic) fields are fine here. fishingCatchSubmitted guards the one-time end-of-
    // round submission (see onGameTick) and is reset on MINIGAME_STARTED, set defensively on
    // MINIGAME_ENDED too in case a round ends abnormally (host force-end) before the local
    // 30-second timer would have fired the submission itself. ----
    private volatile boolean fishingCatchSubmitted = false;
    private int shrimpCount = 0;
    private int anchovyCount = 0;
    // ---- Click, Click, Click (entirely client-local until the one final submission -- clicks are
    // never reported per-click). Every left-click on bare ground while this mini-game is playable
    // records that WorldPoint here (see onMenuEntryAdded's own Click, Click, Click branch /
    // registerClickClickClickTile) -- there's no "start clicking" state beyond the round itself
    // being playable. clickClickClickTiles is owned solely by the client thread
    // (registerClickClickClickTile runs from a menu-entry click callback, same thread as
    // onGameTick's submission check), same single-writer assumption shrimpCount/anchovyCount
    // already rely on, so a plain HashSet is fine here. clickClickClickSubmitted guards the
    // one-time end-of-round submission (see onGameTick) and is reset on MINIGAME_STARTED, set
    // defensively on MINIGAME_ENDED too in case a round ends abnormally (host force-end) before
    // the local 30-second timer would have fired the submission itself. ----
    private volatile boolean clickClickClickSubmitted = false;
    private final Set<WorldPoint> clickClickClickTiles = new HashSet<>();
    // Real state, applied catch-up or not: whether the current turn's player has already spent
    // their one-item-per-turn allowance -- reset on every TURN_STARTED, set by ITEM_USED. Mirrors
    // the server's own itemUsedThisTurn.
    private volatile boolean itemUsedThisTurn = false;
    // Whether the current turn's player has already made a Golden Gnome purchase attempt this
    // turn -- reset on every TURN_STARTED, same shape as itemUsedThisTurn, but set the instant an
    // attempt is *submitted* (see purchaseGoldenGnomeAt), not just on a confirmed
    // GOLDEN_GNOME_PURCHASED (which also sets it, for a reconnecting/catching-up client that missed
    // the local click but still needs the menu entry suppressed for the rest of this turn). The
    // server's own goldenGnomePurchasedThisTurn only ever flips on a *successful* purchase -- a
    // "can't afford this" attempt 409s before anything is inserted, so there's no event to key off
    // for that case at all. Reported behavior: the "Purchase Golden Gnome" menu entry should
    // disappear the moment an attempt is made, afford or not, not just on success -- setting this
    // synchronously on submit (rather than waiting for the response) is what covers that.
    private volatile boolean goldenGnomePurchasedThisTurn = false;
    // Non-null while a requires_placement item (see Item#requiresPlacement) is armed -- set by
    // beginItemPlacement, cleared by cancelItemPlacement or a successful placement. Client-local
    // only: the server never hears about this until the actual place-coin-trap call goes out, so
    // there's no "used but not yet placed" state to reconcile if the player backs out. See
    // getItemPlacementCandidates for the two tiles this arms "Place <item>" on.
    private volatile String itemPlacementKey = null;
    // Non-null while a requires_target item (see Item#requiresTarget) is armed -- set by
    // beginItemTargeting, cleared by cancelItemTargeting or a successful confirmItemTargetOn.
    // Same client-local-only shape as itemPlacementKey (see that field's own doc), just confirmed
    // by right-clicking another player's in-world model instead of a candidate tile -- see
    // addItemTargetMenuEntry.
    private volatile String itemTargetKey = null;
    // Recomputed once per game tick from onGameTick (the client thread) -- see
    // isStandingOnTrackedPosition, whose Player#getWorldLocation() call asserts it's never invoked
    // off the client thread. isLocalPlayerReadyToRoll() reads this cached copy instead of calling
    // that check live, since it's also called from RunePartyPanel (Swing EDT, not the client
    // thread) -- see refreshItemUse. A tick of staleness costs nothing here: OSRS positions only
    // ever change on tick boundaries anyway, so this is exactly as fresh as a live read would be.
    private volatile boolean standingOnTrackedPositionCached = false;
    // Same reasoning/pattern as standingOnTrackedPositionCached immediately above -- Player#
    // getWorldLocation() also asserts it's never invoked off the client thread, and
    // getLocalJaddyZoneColorHex() needs the local player's own current position from handleEvent,
    // which runs on EventSocket's own OkHttp WebSocket callback thread, not the client thread. An
    // uncaught AssertionError thrown there doesn't just fail that one lookup -- it propagates out of
    // OkHttp's own RealWebSocket#loopReader, killing that connection's entire read loop, which is
    // indistinguishable from "the game stalled" until EventSocket's own reconnect eventually kicks
    // in. A tick of staleness costs nothing here either, same reasoning that field's own doc gives.
    private volatile WorldPoint lastKnownLocalPosition = null;
    // ---- board view (client-side camera state -- see toggleBoardView/restoreCameraFromBoardView,
    // the only writers). boardViewSavedPitch/Yaw/Zoom are only ever read/written from inside a
    // clientThread.invoke callback (same thread every writer already runs on), so no volatile
    // needed on those three; boardViewActive itself is read from RunePartyPanel (Swing EDT) via
    // isBoardViewActive(), hence volatile. ----
    private volatile boolean boardViewActive = false;
    private int boardViewSavedPitch;
    private int boardViewSavedYaw;
    private int boardViewSavedZoom;
    // Mini-game (selection/ready-check/countdown), Coin Rush, and True or False state lives in
    // MinigamePresentation -- minigamePresentation field is declared with the other presenters
    // above, constructed in startUp(). Item wheel/cap/used-announcement and Coin Trap trigger
    // state likewise lives in ItemPresentation.
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
    // Stands in for turnAnnounce on a player whose turn was skipped by a Tele Block (see
    // TURN_SKIPPED handling below) -- arms instead of, never alongside, turnAnnounce for that
    // player, since a skipped player never actually gets a turn to announce.
    private final TimedBanner<String> turnSkippedAnnounce = new TimedBanner<>(); // payload: rsn
    // "You/<caster> cast teleblock on <target>!" -- fired on TELE_BLOCK_APPLIED. Not routed through
    // Item#hasUseAnnouncement/ItemPresentation's own itemUsedAnnounce (see TeleBlockItem's own
    // doc for why): that mechanism's payload has no target field, and this banner's title needs one.
    private final TimedBanner<TeleBlockCastPayload> teleBlockCastAnnounce = new TimedBanner<>();

    // welcomeBanner lives on SessionManager, along with the session-lifecycle fields/methods it's
    // armed by (createGame/joinGame).

    // minigameBanner/roundCompleteBanner/minigameRewardsBanner live on MinigamePresentation, along
    // with the mini-game fields/handleEvent cases/getters they back.

    // ---- game-start banner (server-driven, everyone sees it -- see GAME_STARTED handling) ----
    private final TimedBanner<Void> gameStartBanner = new TimedBanner<>();

    // MinigameReward, TrueOrFalseResult, and TimedBanner<T> live in their own top-level files
    // (same package) so the presenter classes can reference them without a qualified
    // RunePartyPlugin.X name.

    // ItemSpinnerPayload/ItemCapBlockedPayload/ItemUsedAnnouncePayload live nested inside
    // ItemPresentation, along with the fields/handleEvent cases/getters they back.

    // GoldenGnomeOutcomePayload/GoldenGnomePopupPayload live nested inside GoldenGnomePresentation,
    // along with the fields/handleEvent cases/getters they back.

    // PlaceRevealPayload lives nested inside CeremonyPresentation, along with the end-game
    // ceremony fields/GAME_ENDED handling/getters it backs.

    // Golden Gnome offer/outcome/popup/relocation state lives in GoldenGnomePresentation --
    // goldenGnomePresentation field is declared with the other presenters below, constructed in
    // startUp().

    // ---- coin popup (client-side timer -- see PlayerOverlay#drawCoinPopup). Keyed per player
    // (player_lower -> an ordered queue of not-yet-expired popups for them) rather than one global
    // slot, so two different players' coins changing out of the same landing can show at once
    // instead of the second clobbering the first before it's even rendered a frame. A queue, not a
    // single latest-value slot, for the same reason within one player: a Coin Trap steal's own
    // COINS_CHANGED is immediately followed by the underlying tile's own standard-tile payout for
    // that same victim -- two popups for one player, back-to-back, both needing their own full
    // display window. getCoinPopup below peeks the head of this queue and only advances past an
    // entry once its own `until` has actually passed, so every queued popup gets its turn. See the
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

    /** Payload for the "You/&lt;caster&gt; cast teleblock on &lt;target&gt;!" banner -- see
     * teleBlockCastAnnounce's own doc for why this isn't just ItemPresentation's own
     * ItemUsedAnnouncePayload (no target field there). */
    private static final class TeleBlockCastPayload
    {
        final String casterRsn;
        final String targetRsn;

        TeleBlockCastPayload(String casterRsn, String targetRsn)
        {
            this.casterRsn = casterRsn;
            this.targetRsn = targetRsn;
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
        goldenGnomePresentation = new GoldenGnomePresentation(this);
        ceremonyPresentation = new CeremonyPresentation(this);
        itemPresentation = new ItemPresentation(this);
        minigamePresentation = new MinigamePresentation(this);
        jadPresentation = new JadPresentation(this);
        chanceSpacePresentation = new ChanceSpacePresentation(this);
        courseBuilder = new CourseBuilder(this);
        sessionManager = new SessionManager(this);

        tileOverlay = new TileOverlay(client, config, this, tileReducer);
        overlayManager.add(tileOverlay);

        statsOverlay = new StatsOverlay(config, this);
        overlayManager.add(statsOverlay);

        coinRushScoreboardOverlay = new CoinRushScoreboardOverlay(this);
        overlayManager.add(coinRushScoreboardOverlay);

        playerOverlay = new PlayerOverlay(client, config, this, rosterReducer, modelOutlineRenderer);
        overlayManager.add(playerOverlay);

        announcementOverlay = new AnnouncementOverlay(client, config, this);
        overlayManager.add(announcementOverlay);

        confettiOverlay = new ConfettiOverlay(client, this);
        overlayManager.add(confettiOverlay);

        jadEncounter = new JadEncounter(client, clientThread, this);
        overlayManager.add(jadEncounter);

        jaddyDuelModel = new JaddyDuelModel(client, clientThread, this, tileReducer);
        overlayManager.add(jaddyDuelModel);

        fishingCatchOverlay = new FishingCatchOverlay(this);
        overlayManager.add(fishingCatchOverlay);

        clickClickClickOverlay = new ClickClickClickOverlay(this);
        overlayManager.add(clickClickClickOverlay);

        hotPotatoOverlay = new HotPotatoOverlay(this);
        overlayManager.add(hotPotatoOverlay);

        // Not an Overlay itself -- see its own doc -- so no overlayManager registration, just a
        // plain field spawn()ed directly from the HOT_POTATO_EXPLODED handler.
        hotPotatoExplosionModel = new HotPotatoExplosionModel(client);

        turfWarsScoreOverlay = new TurfWarsScoreOverlay(this);
        overlayManager.add(turfWarsScoreOverlay);

        sandwichRushHudOverlay = new SandwichRushHudOverlay(this);
        overlayManager.add(sandwichRushHudOverlay);

        danceDanceRuneScapeOverlay = new DanceDanceRuneScapeOverlay(this);
        overlayManager.add(danceDanceRuneScapeOverlay);

        danceDanceRuneScapeHudOverlay = new DanceDanceRuneScapeHudOverlay(this);
        overlayManager.add(danceDanceRuneScapeHudOverlay);

        hardcodedCourseLauncherOverlay = new HardcodedCourseLauncherOverlay(client, this);
        overlayManager.add(hardcodedCourseLauncherOverlay);

        mapOverlay = new RunePartyMapOverlay(client, this, tooltipManager);
        overlayManager.add(mapOverlay);

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
            @Override public void onEvent(ApiClient.EventOut e, boolean catchingUp) { handleEvent(e, catchingUp); }
            @Override public void onError(Exception e) { log.debug("EventSocket error", e); }
            @Override public void onCaughtUp() { sessionManager.syncRosterSnapshot(true); refreshPanel(); }
        });
    }

    @Override
    protected void shutDown()
    {
        log.debug("Rune Party shutting down");
        if (eventSocket != null) eventSocket.shutdown();
        executor.shutdownNow();
        uiTimerExec.shutdownNow();
        if (tileOverlay != null) { tileOverlay.clearGoldenGnomeModels(); tileOverlay.clearCoinRushModels(); tileOverlay.clearSandwichItemModels(); tileOverlay.clearPondModels(); tileOverlay.clearTableModels(); overlayManager.remove(tileOverlay); }
        if (statsOverlay != null) overlayManager.remove(statsOverlay);
        if (coinRushScoreboardOverlay != null) overlayManager.remove(coinRushScoreboardOverlay);
        if (playerOverlay != null) overlayManager.remove(playerOverlay);
        if (announcementOverlay != null) overlayManager.remove(announcementOverlay);
        if (confettiOverlay != null) overlayManager.remove(confettiOverlay);
        if (jadEncounter != null) { jadEncounter.clear(); overlayManager.remove(jadEncounter); }
        if (jaddyDuelModel != null) { jaddyDuelModel.clear(); overlayManager.remove(jaddyDuelModel); }
        if (fishingCatchOverlay != null) overlayManager.remove(fishingCatchOverlay);
        if (clickClickClickOverlay != null) overlayManager.remove(clickClickClickOverlay);
        if (hotPotatoOverlay != null) overlayManager.remove(hotPotatoOverlay);
        if (hotPotatoExplosionModel != null) hotPotatoExplosionModel.clear();
        if (turfWarsScoreOverlay != null) overlayManager.remove(turfWarsScoreOverlay);
        if (sandwichRushHudOverlay != null) overlayManager.remove(sandwichRushHudOverlay);
        if (danceDanceRuneScapeOverlay != null) overlayManager.remove(danceDanceRuneScapeOverlay);
        if (danceDanceRuneScapeHudOverlay != null) overlayManager.remove(danceDanceRuneScapeHudOverlay);
        if (hardcodedCourseLauncherOverlay != null) { hardcodedCourseLauncherOverlay.clear(); overlayManager.remove(hardcodedCourseLauncherOverlay); }
        if (mapOverlay != null) overlayManager.remove(mapOverlay);
        if (navButton != null) clientToolbar.removeNavigation(navButton);
        resetState();
    }

    /** Flips whether RunePartyMapOverlay draws its full-screen course map -- see RunePartyPanel's
     * "Show Map"/"Hide Map" button (that label swap is refresh()'s own job, reading isMapShowing()
     * back), the only caller. Nothing to lazily create/dispose of anymore -- unlike the Swing
     * dialog this replaced, the overlay's already registered for the plugin's whole lifetime (see
     * startUp()) and simply skips drawing while mapShowing is false, same as every other overlay's
     * own early-return convention. */
    public void toggleMap()
    {
        mapShowing = !mapShowing;
        refreshPanel();
    }

    public boolean isMapShowing()
    {
        return mapShowing;
    }

    /** Toggles between the normal player-driven camera and a steep, near-straight-down "board
     * view" -- see RunePartyPanel's "View Board" button, the only caller. Only ever adjusts
     * pitch/yaw target plus zoom and the pitch relaxer that lifts the vanilla pitch cap -- all
     * live-tested and confirmed, see BOARD_VIEW_PITCH/YAW/ZOOM's own docs. Deliberately does not
     * touch the camera mode or focal point, so this pans/tilts to look straight down from wherever
     * the local player already stands rather than pinning over the board's own true center --
     * close enough for a course that's normally small and directly underfoot during a turn.
     * <p>
     * The pitch/yaw target setters write the same internal value the game's native mouse-drag
     * control already targets and eases toward every frame on its own, so this only ever needs to
     * set that target once per toggle -- and the player's own mouse can still freely drag away
     * from it at any time, since nothing here disables normal input. */
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
     * for every action method here, just exposed for RunePartyMapOverlay to tell "you" apart from
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

    // Package-private: MinigamePresentation's collectCoinRushCoin passes a lambda to submitAction.
    @FunctionalInterface
    public interface ApiCall
    {
        void run() throws Exception;
    }

    /** Shared shape for a fire-and-forget request method: submit {@code call} to the executor; on
     * any exception, log {@code logLabel + " failed"} and, if {@code onFailure} is non-null, run
     * it with the exception -- a chat message, resetting a "let the next tick retry" flag, or
     * both (see confirmArrival/rollDice below for callers that need the latter). Every action
     * method in this section used to hand-write this exact executor.submit/try/catch/log wrapper
     * around its own apiClient call. */
    public void submitAction(String logLabel, ApiCall call, Consumer<Exception> onFailure)
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

    public void submitAction(String logLabel, ApiCall call)
    {
        submitAction(logLabel, call, null);
    }

    /** Same as {@link #submitAction(String, ApiCall, Consumer)}, plus {@code finallyAction}, run
     * once {@code call} has resolved either way (success or failure) -- createGame/joinGame's own
     * "refresh the panel regardless of outcome" epilogue, the only two callers that need one. */
    public void submitAction(String logLabel, ApiCall call, Consumer<Exception> onFailure, Runnable finallyAction)
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

    // Delegating facade -- SessionManager owns the actual state/logic. Every name/signature below
    // is unchanged, so RunePartyPanel doesn't need to change.
    public void createGame() { sessionManager.createGame(); }
    public void createGameFromHardcodedCourse(HardcodedCourse course) { sessionManager.createGameFromHardcodedCourse(course); }
    public void joinGame(String code) { sessionManager.joinGame(code); }
    public void startGame(int maxRounds) { sessionManager.startGame(maxRounds); }
    /** Host-only: ends the game for everyone, distinct from leaveGame() which only removes the
     * caller. The resulting GAME_ENDED event (see handleEvent) is what actually flips phase to
     * ENDED for every connected client, this call and leaveGame() both just request it. */
    public void endGame() { sessionManager.endGame(); }

    public void rollDice()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;
        if (!self.equalsIgnoreCase(currentTurnRsn) || pendingRoll || rollRequestSubmitted) return;

        // Rolling abandons any still-armed item placement/targeting (see beginItemPlacement/
        // cancelItemPlacement and beginItemTargeting/cancelItemTargeting) -- the player chose to
        // move instead of finishing it, so it goes back to unused (the server never heard about it
        // either, see cancelItemPlacement's own doc) rather than staying stuck armed past the turn
        // it was armed on. TURN_STARTED clears both too, as a backstop for any other path off this
        // turn that isn't a roll.
        if (itemPlacementKey != null || itemTargetKey != null)
        {
            itemPlacementKey = null;
            itemTargetKey = null;
            refreshPanel();
        }

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
            // rejected this exact claim -- arrivalSubmitted staying true blocks
            // checkPendingArrival from resubmitting it every tick forever. The client's own
            // pendingRoll is corrected the normal way instead -- by the next real TURN_STARTED
            // or DICE_ROLLED event, which also resets arrivalSubmitted. A genuine transient
            // failure (a real network-level IOException, or a 5xx) is different -- the claim
            // itself was never rejected, so retrying it is still worth doing.
            if (!(e instanceof ApiClient.ApiHttpException) || ((ApiClient.ApiHttpException) e).code >= 500)
            {
                arrivalSubmitted = false; // let the next tick retry
            }
        });
    }

    /** Fires the local player's own current position off to the server -- called every tick a
     * mini-game is playable (see onGameTick), not once per claim like confirmArrival. No retry
     * logic on failure: a dropped or failed report is superseded by the next tick's own report
     * 600ms later, so there's nothing worth resubmitting. minigamePositionReportInFlight is cleared
     * in finallyAction regardless of outcome, so a failure doesn't leave future ticks permanently
     * blocked from trying again. */
    private void reportMinigamePosition(WorldPoint pos)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null)
        {
            minigamePositionReportInFlight.set(false);
            return;
        }

        submitAction("Report minigame position",
            () -> apiClient.reportMinigamePosition(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()),
            null,
            () -> minigamePositionReportInFlight.set(false));
    }

    /** Fires the local player's final Fishing Contest tally off to the server -- called exactly
     * once per round, from onGameTick the moment its own local 30-second timer elapses. Snapshots
     * shrimpCount/anchovyCount at call time rather than reading them again inside the lambda, so
     * the submitted numbers stay tied to the exact instant the round ended. No retry on failure --
     * unlike reportMinigamePosition's own every-tick heartbeat, there's no next tick to supersede a
     * dropped one-shot submission with, but a missed submission just means this player shows as 0
     * anchovies, not a hung round. */
    private void submitFishingCatch()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        final int shrimp = shrimpCount;
        final int anchovies = anchovyCount;
        if (self == null || gid == null || token == null) return;

        submitAction("Submit fishing catch",
            () -> apiClient.submitFishingCatch(gid, self, token, anchovies, shrimp),
            e -> addChatMessage("Failed to submit your fishing catch: " + e.getMessage()));
    }

    /** Reports arrival at the Start tile after using a Home Teleport -- called automatically from
     * onGameTick once the local player has a pending arrival and is standing on their own tracked
     * position (which a Home Teleport's own PLAYER_MOVED already set to Start the instant it was
     * used). Same retry-on-transient-failure-only shape confirmArrival uses -- a definitive 4xx
     * means the server's already rejected this exact claim, so homeTeleportArrivalSubmitted
     * staying true blocks a tick-every-retry spam; only a genuine transient failure resets it. */
    private void confirmHomeTeleportArrival(WorldPoint pos)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        submitAction("Confirm Home Teleport arrival",
            () -> apiClient.confirmHomeTeleportArrival(gid, self, token, pos.getX(), pos.getY(), pos.getPlane()), e ->
        {
            addChatMessage("Failed to confirm Home Teleport arrival: " + e.getMessage());
            if (!(e instanceof ApiClient.ApiHttpException) || ((ApiClient.ApiHttpException) e).code >= 500)
            {
                homeTeleportArrivalSubmitted = false; // let the next tick retry
            }
        });
    }

    // checkCoinRushCollection/collectCoinRushCoin live on MinigamePresentation, along with the
    // Coin Rush fields/handleEvent cases/getters they back.

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

    /** Reports the local player's BOW emote during a pending Jad encounter -- called from
     * onAnimationChanged once the emote finishes, same finish-gated pattern as rollDice/the Spin
     * emote. The server resolves the outcome and reports it back via
     * JAD_DISMISSED -- this call itself is fire-and-forget, same as every other player-action
     * method here. A 409 here (the bow window already closed, see JadPresentation#isSmashTriggered)
     * just means this lost the race against the server's own timeout -- the smash/penalty plays out
     * regardless. */
    private void bowToJad()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        submitAction("Bow to Jad", () -> apiClient.bowToJad(gid, self, token),
            e -> addChatMessage("Failed to bow to Jad: " + e.getMessage()));
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
     * player's YES ("True")/NO ("False") emote finishes, same finish-gated pattern as bowToJad.
     * The server never echoes back correctness -- only TRUE_OR_FALSE_ROUND_ENDED reveals it, once
     * every player's had their full 5 seconds. A 409 here (already answered, or the round already
     * ended) is a definitive rejection, not a network hiccup, so this doesn't retry either way. */
    private void answerTrueOrFalse(boolean answer)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        submitAction("Answer True or False", () -> apiClient.answerTrueOrFalse(gid, self, token, answer));
    }

    /** Passes the Hot Potato -- called from onAnimationChanged once the local player's own Spin
     * emote finishes while isLocalPlayerHoldingHotPotato() was true. A 409 here (the round already
     * ended, or someone else's own pass beat this one to the server first, which shouldn't be
     * possible for the same holder but costs nothing to tolerate) is a definitive rejection, not a
     * network hiccup, same as answerTrueOrFalse's own reasoning. */
    private void passHotPotato()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        submitAction("Pass Hot Potato", () -> apiClient.passHotPotato(gid, self, token));
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
     * item buttons instead of useItem() for one of these, since there's no server call to make yet:
     * it's client-local until the player actually right-clicks a candidate tile's "Place
     * &lt;item&gt;" entry. Refuses silently rather than arming a placement that'd only 409 anyway. */
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
     * the local player's own current course position, by plain pathIndex +-1 (not graph-aware --
     * forks aren't this feature's concern). Empty if nothing's armed, the course is empty, or the
     * local player isn't tracked yet. */
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

    /** Arms a requires_target item (see Item#requiresTarget) -- called from RunePartyPanel's item
     * buttons instead of useItem() for one of these, mirroring beginItemPlacement exactly (see
     * that method's own doc) just for the right-click-a-player confirm step instead of
     * right-click-a-tile (see addItemTargetMenuEntry/confirmItemTargetOn). */
    public void beginItemTargeting(String itemKey)
    {
        if (itemKey == null || !isLocalPlayerReadyToRoll() || isItemUsedThisTurn()) return;
        if (!Items.get(itemKey).requiresTarget()) return;
        itemTargetKey = itemKey;
        refreshPanel();
    }

    /** Backs out of an armed targeting -- called from RunePartyPanel's Cancel button and the
     * in-world "Cancel" menu entry (see addItemTargetMenuEntry). Purely client-local, same as
     * cancelItemPlacement: the server never heard about the item being "used" in the first place,
     * so there's nothing to undo server-side. */
    public void cancelItemTargeting()
    {
        itemTargetKey = null;
        refreshPanel();
    }

    /** Spends the armed requires_target item on {@code targetRsn} -- called from the in-world
     * "Tele Block &lt;name&gt;"-style menu entry (see addItemTargetMenuEntry), which only ever
     * offers this on an actual seated PLAYER other than the local player themselves. Clears
     * targeting mode optimistically before the call even goes out, same restraint every other
     * fire-and-forget action method here already takes (see placeCoinTrapAt's own doc). */
    private void confirmItemTargetOn(String targetRsn)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        final String itemKey = itemTargetKey;
        itemTargetKey = null;
        refreshPanel();
        if (self == null || gid == null || token == null || itemKey == null) return;

        submitAction("Use item on player", () -> apiClient.useItemOnPlayer(gid, self, token, itemKey, targetRsn),
            e -> addChatMessage("Failed to use " + Items.get(itemKey).getDisplayName() + " on " + targetRsn + ": " + e.getMessage()));
    }

    public void leaveGame() { sessionManager.leaveGame(); }
    /** Whether the turn order already has MAX_PLAYERS seats filled -- the client-side gate on
     * "Add to Game" (menu entry and roster popup both check this). The server doesn't currently
     * enforce this cap itself, so it's a UI guard rather than a real limit. */
    public boolean isGameFull() { return sessionManager.isGameFull(); }
    public void assignRole(String playerRsn, RunePartyRole role, Integer colorNumber) { sessionManager.assignRole(playerRsn, role, colorNumber); }
    /** Host-only kick, wired to the roster panel's "Remove Player" right-click entry. */
    public void removePlayer(String playerRsn) { sessionManager.removePlayer(playerRsn); }

    // -------------------------------------------------------------------------
    // Course building (host, LOBBY only) -- delegating facade, CourseBuilder owns the actual
    // state/logic. Every name/signature below is unchanged, so RunePartyPanel/TileOverlay don't
    // need to change.
    // -------------------------------------------------------------------------

    public void selectPreset(CoursePreset preset) { courseBuilder.selectPreset(preset); }
    public void enterCoursePlacementMode() { courseBuilder.enterCoursePlacementMode(); }
    /** Unmarks every currently-committed course tile -- the host's "start over" button. */
    public void clearCourse() { courseBuilder.clearCourse(); }
    public boolean isCustomCourseBuildMode() { return courseBuilder.isCustomCourseBuildMode(); }
    public void enterCustomCourseBuildMode() { courseBuilder.enterCustomCourseBuildMode(); }
    public void exitCustomCourseBuildMode() { courseBuilder.exitCustomCourseBuildMode(); }
    /** The world point of the tile currently armed via "Connect From" -- see
     * TileOverlay#renderConnectFromIndicator, the only reader. */
    public WorldPoint getCourseConnectFromPoint() { return courseBuilder.getCourseConnectFromPoint(); }

    // -------------------------------------------------------------------------
    // Board interaction helpers -- Golden Gnome purchase, Click Click Click tile clicks, Fishing
    // Contest catches, and the generic menu-hover plumbing behind all of them plus the hard-coded
    // course launcher. Physically lived alongside course building before that moved out to its own
    // CourseBuilder class above -- none of this is course-building itself.
    // -------------------------------------------------------------------------

    /** Same "Walk here" -> custom RUNELITE entries idiom as CourseBuilder#addPresetMenuEntries, for an armed
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

    /** Same "Walk here" -> custom RUNELITE entry idiom as addPresetMenuEntries/
     * addItemPlacementMenuEntries, but here the whole point is that the player never actually
     * walks: appending this entry after "Walk here" makes it the new default left-click action
     * (see onMenuEntryAdded's own gating), so a rapid-fire clicking spree just records tiles in
     * place instead of sending the local player running across the map to the last one clicked.
     * "Walk here" itself is left untouched -- a player who genuinely wants to walk can still
     * right-click and pick it explicitly. */
    private void addClickClickClickMenuEntry()
    {
        Tile tile = client.getTopLevelWorldView().getSelectedSceneTile();
        if (tile == null) return;
        WorldPoint point = tile.getWorldLocation();
        if (point == null) return;

        client.createMenuEntry(-1)
            .setOption("<col=00FF00>Click!</col>")
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> registerClickClickClickTile(point));
    }

    /** Records one Click, Click, Click tile click -- called from the synthetic "Click!" menu entry
     * (see addClickClickClickMenuEntry). Re-checks isClickClickClickActive()/
     * clickClickClickSubmitted here on top of that method's own gate, since the round could have
     * ended in the moment between the menu opening and this entry actually being clicked. A
     * repeat click on an already-recorded tile is a harmless no-op -- clickClickClickTiles is a
     * Set, so the running unique count (read live by ClickClickClickOverlay) never double-counts
     * one. */
    private void registerClickClickClickTile(WorldPoint point)
    {
        if (!isClickClickClickActive() || clickClickClickSubmitted) return;
        clickClickClickTiles.add(point);
    }

    /** Fires the local player's final Click, Click, Click unique-tile tally off to the server --
     * called exactly once per round, from onGameTick the moment its own local 30-second timer
     * elapses. Same "snapshot at call time, no retry on failure" shape submitFishingCatch already
     * uses. */
    private void submitClickClickClickResult()
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        final int uniqueTiles = clickClickClickTiles.size();
        if (self == null || gid == null || token == null) return;

        submitAction("Submit Click, Click, Click result",
            () -> apiClient.submitClickClickClickResult(gid, self, token, uniqueTiles),
            e -> addChatMessage("Failed to submit your Click, Click, Click result: " + e.getMessage()));
    }

    private static final String GOLDEN_GNOME_PURCHASE_OPTION = "<col=00FF00>Purchase Golden Gnome</col>";

    /** The Golden Gnome's own point if the mouse is genuinely over its model's real clickbox (see
     * TileOverlay#isGoldenGnomeUnderMouse), only for the local player's own turn while a roll is
     * pending, only when it's genuinely reachable this roll (see pendingReachableIndices), and
     * only once per turn (see goldenGnomePurchasedThisTurn's own doc for why that's set
     * optimistically on submit rather than waiting for a confirmed purchase). Still doesn't
     * re-check affordability client-side -- the server already 409s on that; this guard only keeps
     * the menu from offering an option the server would reject anyway. Called from
     * onClientTick/onMenuOpened, the only two callers. */
    private WorldPoint hoveredPurchasableGoldenGnomePoint(Point canvasPoint)
    {
        String self = localRsn();
        if (self == null || currentTurnRsn == null || !self.equalsIgnoreCase(currentTurnRsn) || !pendingRoll) return null;
        if (goldenGnomePurchasedThisTurn) return null;

        WorldPoint goldenGnomePoint = findGoldenGnomeTilePoint();
        if (goldenGnomePoint == null || !tileOverlay.isGoldenGnomeUnderMouse(goldenGnomePoint, canvasPoint)) return null;

        Integer goldenGnomePathIndex = tileReducer.pathIndexAt(goldenGnomePoint);
        if (goldenGnomePathIndex == null || !pendingReachableIndices.contains(goldenGnomePathIndex)) return null;

        return goldenGnomePoint;
    }

    private void addGoldenGnomePurchaseMenuEntry(WorldPoint point)
    {
        client.createMenuEntry(-1)
            .setOption(GOLDEN_GNOME_PURCHASE_OPTION)
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> purchaseGoldenGnomeAt(point));
    }

    private static final String HARDCODED_COURSE_LAUNCHER_OPTION = "<col=00FF00>Create Game</col>";

    /** Every client tick the mouse rests on a clickbox-hit-tested RuneLiteObject this plugin cares
     * about -- a hard-coded course's own launcher Golden Gnome (see
     * HardcodedCourseLauncherOverlay#hoveredCourse) or the real in-game Golden Gnome mid-purchase
     * (see hoveredPurchasableGoldenGnomePoint) -- speculatively injects whichever entry
     * onMenuOpened would commit for real, purely so the client's own native top-left hover hint
     * shows it before the player's even right-clicked. Skipped while a menu's already open --
     * nothing to speculatively add once a real menu build is already underway. */
    @Subscribe
    public void onClientTick(ClientTick event)
    {
        if (client.isMenuOpen()) return;
        addHoveredClickboxMenuEntry(client.getMouseCanvasPosition());
    }

    /** The definitive, click-time version of onClientTick's own speculative injection -- a
     * RuneLiteObject has no native menu at all, so this is the only way right-clicking one can
     * actually work. Strips whatever onClientTick's own speculative entry left in this exact menu
     * snapshot first so hovering never shows either entry twice. Reads/writes via
     * client.getMenu(), not the event object's own same-named setter -- that one only mutates the
     * event instance's own field, never the actual live menu client.createMenuEntry appends to. */
    @Subscribe
    public void onMenuOpened(MenuOpened event)
    {
        List<MenuEntry> kept = new ArrayList<>();
        for (MenuEntry entry : client.getMenu().getMenuEntries())
        {
            if (entry.getType() != MenuAction.RUNELITE
                || (!HARDCODED_COURSE_LAUNCHER_OPTION.equals(entry.getOption()) && !GOLDEN_GNOME_PURCHASE_OPTION.equals(entry.getOption())))
            {
                kept.add(entry);
            }
        }
        client.getMenu().setMenuEntries(kept.toArray(new MenuEntry[0]));

        addHoveredClickboxMenuEntry(client.getMouseCanvasPosition());
    }

    /** Shared by onClientTick/onMenuOpened so the two clickbox-hit-tested candidates -- a
     * hard-coded course's launcher, the in-game Golden Gnome -- never drift out of sync between
     * the speculative and definitive injection sites. At most one entry per call: a launcher and a
     * purchasable Golden Gnome can never both apply at once (the launcher only shows with no
     * active game, the purchase option only during one). */
    private void addHoveredClickboxMenuEntry(Point canvasPoint)
    {
        HardcodedCourse course = hardcodedCourseLauncherOverlay.hoveredCourse(canvasPoint);
        if (course != null)
        {
            addHardcodedCourseLauncherMenuEntry(course);
            return;
        }

        WorldPoint goldenGnomePoint = hoveredPurchasableGoldenGnomePoint(canvasPoint);
        if (goldenGnomePoint != null)
        {
            addGoldenGnomePurchaseMenuEntry(goldenGnomePoint);
        }
    }

    /** Clicking it both creates a game and commits {@code course}'s whole tile set in one go --
     * see createGameFromHardcodedCourse. */
    private void addHardcodedCourseLauncherMenuEntry(HardcodedCourse course)
    {
        client.createMenuEntry(-1)
            .setOption(HARDCODED_COURSE_LAUNCHER_OPTION)
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> createGameFromHardcodedCourse(course));
    }

    /** The Golden Gnome's own current tile, if one is currently marked -- see
     * addGoldenGnomePurchaseMenuEntry and TileOverlay's own arrow, the only two readers. Scans
     * tileReducer's live snapshot directly rather than caching, same "the reducer is the one
     * source of truth" reasoning models/GoldenGnomeModel's own update() already follows. */
    public WorldPoint findGoldenGnomeTilePoint()
    {
        for (TileReducer.TileEntry entry : tileReducer.snapshot())
        {
            if ("GOLDEN_GNOME_TILE".equals(entry.tileType)) return entry.point;
        }
        return null;
    }

    /** The Pond's own current tile, if one is currently marked -- see performFishingCatchRoll, the
     * only reader. Scans tileReducer's live snapshot directly rather than caching, same "the
     * reducer is the one source of truth" reasoning findGoldenGnomeTilePoint already follows. */
    WorldPoint findPondTilePoint()
    {
        for (TileReducer.TileEntry entry : tileReducer.snapshot())
        {
            if ("POND_TILE".equals(entry.tileType)) return entry.point;
        }
        return null;
    }

    /** Dance, Dance, RuneScape's own anchor tile, if one is currently marked -- see
     * DanceDanceRuneScapePresentation, the only reader, which derives the four highlightable
     * tiles adjacent to this one purely by point arithmetic. Board-swapped by the server once the
     * round starts (see that class's own doc), not host-placed -- but the lookup itself is
     * identical either way, same "the reducer is the one source of truth" reasoning
     * findGoldenGnomeTilePoint/findPondTilePoint already follow. */
    public WorldPoint findDanceDanceRuneScapeTilePoint()
    {
        for (TileReducer.TileEntry entry : tileReducer.snapshot())
        {
            if ("DDR_CENTER_TILE".equals(entry.tileType)) return entry.point;
        }
        return null;
    }

    /** Rolls one Fishing Contest catch -- called from onAnimationChanged the moment the local
     * player's own Headbang emote finishes (see awaitingHeadbangFinish). Re-checks
     * isFishingContestActive()/fishingCatchSubmitted here on top of onAnimationChanged's own gate
     * before arming -- the round could have ended mid-emote. Requires the local player to be
     * within one tile of the Pond (Chebyshev distance <= 1) and on the same plane, otherwise a
     * player could stand anywhere on the board and Headbang for free catches. No cooldown beyond
     * the emote's own animation length, since each catch costs one full Headbang. */
    private void performFishingCatchRoll()
    {
        if (!isFishingContestActive() || fishingCatchSubmitted) return;

        Player selfPlayer = client.getLocalPlayer();
        WorldPoint pos = selfPlayer == null ? null : selfPlayer.getWorldLocation();
        WorldPoint anchor = findPondTilePoint();
        if (pos == null || anchor == null || pos.getPlane() != anchor.getPlane()) return;
        if (Math.max(Math.abs(pos.getX() - anchor.getX()), Math.abs(pos.getY() - anchor.getY())) > 1) return;

        if (ThreadLocalRandom.current().nextInt(100) < 67) shrimpCount++; else anchovyCount++;
    }

    /** Buys the Golden Gnome currently standing at {@code point} -- called from the in-world
     * "Purchase Golden Gnome" menu entry (see addGoldenGnomePurchaseMenuEntry). A free side-action
     * during the local player's own pending roll, same as useItem: doesn't touch pendingRoll or
     * advance the turn, so the player still needs to separately walk to and confirm arrival at
     * their real destination afterward. */
    private void purchaseGoldenGnomeAt(WorldPoint point)
    {
        String self = localRsn();
        final String gid = gameId;
        final String token = playerToken;
        if (self == null || gid == null || token == null) return;

        // Set the instant a genuine attempt goes out, not on the response -- see
        // goldenGnomePurchasedThisTurn's own doc. A "can't afford this" 409 never reaches the
        // client as an event, so waiting for GOLDEN_GNOME_PURCHASED alone would leave the menu
        // entry offered again on the very next right-click after a failed attempt.
        goldenGnomePurchasedThisTurn = true;

        submitAction("Purchase Golden Gnome", () -> apiClient.purchaseGoldenGnome(gid, self, token, point.getX(), point.getY(), point.getPlane()),
            e -> addChatMessage("Failed to purchase the Golden Gnome: " + e.getMessage()));
    }

    // -------------------------------------------------------------------------
    // Movement -- detect arrival at a rolled destination by watching the local
    // player's position every tick rather than relying on a click/animation
    // trigger.
    // -------------------------------------------------------------------------

    @Subscribe
    public void onGameTick(GameTick event)
    {
        if (phase == GamePhase.DISCONNECTED && !sessionManager.hasAttemptedResume())
        {
            sessionManager.attemptSessionResume();
        }

        if (phase != GamePhase.ACTIVE) return;

        // Refreshed unconditionally, ahead of the early returns below -- isLocalPlayerReadyToRoll
        // needs this cache kept current every tick regardless of pendingRoll/arrivalSubmitted/etc,
        // since it's read from contexts (RunePartyPanel) that can't safely compute it live. See the
        // field's own doc.
        String self = localRsn();
        Player selfPlayer = client.getLocalPlayer();
        standingOnTrackedPositionCached = self != null && selfPlayer != null && isStandingOnTrackedPosition(selfPlayer, self);
        lastKnownLocalPosition = selfPlayer != null ? selfPlayer.getWorldLocation() : null;

        // Runs independently of the turn engine below -- a Coin Rush round has no "whose turn is
        // it" at all, every seated player can be racing for a spawn at once, so this can't share
        // the pendingRoll-gated checks the rest of onGameTick uses.
        if (isCoinRushActive() && isMinigamePlayable())
        {
            minigamePresentation.coinRush().checkCollection(selfPlayer);
        }

        // Same reasoning as the Coin Rush check just above -- Sandwich Rush also has no "whose
        // turn is it," every seated player can be racing for the same ingredient at once.
        if (isSandwichRushActive() && isMinigamePlayable())
        {
            minigamePresentation.sandwichRush().checkCollection(selfPlayer);
        }

        // Generic (not Coin-Rush/Arena-specific) live position heartbeat -- any mini-game whose
        // own server-side round wants to know where seated players actually are reads this back.
        // Scoped to exactly the mini-games/phases that can ever actually consume it (see
        // MINIGAMES_NEEDING_CONTINUOUS_POSITION/MINIGAMES_NEEDING_PRE_ROUND_POSITION's own doc) --
        // every other mini-game, and every other phase (selection spinner, ready-check, countdown,
        // and post-round-begin for the pre-round-only set), never had a server-side reader at all,
        // so pinging there was pure request volume for nothing. Checked on isMinigameActive() (not
        // isMinigamePlayable()) for the continuous set specifically -- Arena/Turf Wars/Jaddy's own
        // round-begin fires the instant everyone's reported position lands inside their own grid,
        // which can happen well before the generic countdown's own fixed isMinigamePlayable()
        // moment; gating reporting on that fixed moment would silently put a floor under how fast
        // those rounds could ever begin. minigamePositionReportInFlight only guards against piling
        // up requests if a round-trip is unusually slow.
        // minigamePresentation.getKey() is null whenever no mini-game is active at all (the
        // overwhelming majority of ticks, during ordinary turn-based play). Set.of(...)'s own
        // contains(null) throws NullPointerException rather than just returning false (unlike
        // HashSet) -- this was crashing onGameTick's entire subscriber every single tick any time
        // no mini-game was running, taking down everything below this point in the method
        // (confirm-start/confirm-arrival included) along with it. The explicit null check below is
        // required, not cosmetic -- it must short-circuit before either set's own contains() ever
        // runs.
        String activeMinigameKey = minigamePresentation.getKey();
        boolean needsPositionPing = activeMinigameKey != null && (
            MINIGAMES_NEEDING_CONTINUOUS_POSITION.contains(activeMinigameKey)
            || (MINIGAMES_NEEDING_PRE_ROUND_POSITION.contains(activeMinigameKey) && !isMinigameRoundBegun())
        );
        if (self != null && selfPlayer != null && isMinigameActive() && needsPositionPing
            && rosterReducer.getRole(self) == RunePartyRole.PLAYER
            && minigamePositionReportInFlight.compareAndSet(false, true))
        {
            reportMinigamePosition(selfPlayer.getWorldLocation());
        }

        // Also independent of the turn engine below -- the one-time end-of-round submission for
        // the local player's own entirely client-local Fishing Contest tally. Individual catches
        // are rolled from onAnimationChanged instead, one per completed Headbang emote -- this
        // just watches the local 30-second timer (anchored to MINIGAME_ROUND_BEGIN) and fires the
        // single submission once it elapses, guarded by fishingCatchSubmitted so it can only ever
        // fire once per round.
        if (isFishingContestActive() && !fishingCatchSubmitted)
        {
            long endsAt = getFishingContestEndsAt();
            if (endsAt != 0 && System.currentTimeMillis() >= endsAt)
            {
                fishingCatchSubmitted = true;
                submitFishingCatch();
            }
        }

        // Same shape as the Fishing Contest check just above: this only watches the local
        // 30-second timer (anchored to MINIGAME_ROUND_BEGIN) and fires the single submission once
        // it elapses -- individual clicks are recorded from the synthetic "Click!" menu entry
        // instead (see registerClickClickClickTile).
        if (isClickClickClickActive() && !clickClickClickSubmitted)
        {
            long endsAt = getClickClickClickEndsAt();
            if (endsAt != 0 && System.currentTimeMillis() >= endsAt)
            {
                clickClickClickSubmitted = true;
                submitClickClickClickResult();
            }
        }

        // Also independent of the turn engine below -- Dance, Dance, RuneScape's own highlight
        // cycling, local scoring, and one-shot end-of-round submission all live inside this one
        // call now (see DanceDanceRuneScapePresentation#onTick), not scattered inline the way the
        // two client-local mini-games above still are.
        if (isDanceDanceRuneScapeActive())
        {
            minigamePresentation.danceDanceRuneScape().onTick(selfPlayer);
        }

        // Also independent of the turn engine below -- Rainbow Rush's own local visited-tile
        // tracking and one-shot finish report both live inside this one call (see
        // RainbowRushPresentation#onTick), same shape as Dance, Dance, RuneScape's own onTick just
        // above.
        if (isRainbowRushActive())
        {
            minigamePresentation.rainbowRush().onTick(selfPlayer);
        }

        // Also independent of the turn engine below -- unlike a rolled destination (pendingRoll,
        // only ever true on the local player's own turn), a Home Teleport arrival can still be
        // owed well after the turn it was armed on, whosever turn it currently is (see
        // homeTeleportArrivalSubmitted's own doc). standingOnTrackedPositionCached already covers
        // "have they actually walked over" -- a Home Teleport's own PLAYER_MOVED set the tracked
        // position to Start the instant it was used, so this is the same "back on your own tracked
        // tile" check confirmArrival's caller relies on, just not gated on pendingRoll/whose turn
        // it is.
        if (self != null && !homeTeleportArrivalSubmitted && standingOnTrackedPositionCached
            && rosterReducer.isHomeTeleportPending(self))
        {
            homeTeleportArrivalSubmitted = true;
            confirmHomeTeleportArrival(selfPlayer.getWorldLocation());
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
    // Menu entries -- course placement/removal during LOBBY, and a host-only
    // "Add to Game" on other players' Follow option. There's no dedicated
    // in-world button for course building, so "Walk here" on the relevant
    // tile is the entry point. Rolling dice is a gesture trigger instead (see
    // onAnimationChanged) rather than a menu entry.
    // -------------------------------------------------------------------------

    @Subscribe
    public void onMenuEntryAdded(MenuEntryAdded event)
    {
        if ("Follow".equals(event.getOption()))
        {
            addToGameMenuEntry(event);
            addItemTargetMenuEntry(event);
            return;
        }

        if (!"Walk here".equals(event.getOption())) return;
        if (phase == GamePhase.LOBBY && isHost() && courseBuilder.isCoursePlacementMode())
        {
            courseBuilder.addPresetMenuEntries();
            return;
        }
        if (phase == GamePhase.LOBBY && isHost() && courseBuilder.isCustomCourseBuildMode())
        {
            courseBuilder.addCustomCourseBuildMenuEntries();
            return;
        }
        if (phase == GamePhase.ACTIVE && itemPlacementKey != null)
        {
            addItemPlacementMenuEntries();
            return;
        }
        if (phase == GamePhase.ACTIVE && isClickClickClickActive() && isMinigamePlayable())
        {
            addClickClickClickMenuEntry();
        }
    }

    /** Rolls the dice once the local player's Spin emote finishes on their own turn, and, the same
     * way, responds to a pending Jad bow, a mini-game ready-check, the current True or False round
     * once the matching YES/NO emote finishes, a Fishing Contest catch roll once a Headbang emote
     * finishes, or a Hot Potato pass once a Spin emote finishes while holding it (Spin's other
     * meaning -- see isLocalPlayerReadyToRoll's own doc for why the two never overlap). Only reacts
     * to the local player's own animation, since every client sees
     * every nearby player's AnimationChanged. Waits for the next animation change away from
     * whichever emote ID matched -- i.e. the emote actually finishing, not just starting -- so the
     * response never fires mid-emote; awaitingSpinFinish and awaitingHeadbangFinish here, plus
     * JadPresentation's own awaitingBowFinish and MinigamePresentation's own three awaiting flags,
     * carry that wait across the two AnimationChanged firings, exactly one set at a time. Gates the
     * actual roll on isLocalPlayerReadyToRoll() -- same check AnnouncementOverlay#renderSpinHint
     * uses -- and each other response on its own matching isLocalPlayerAwaiting*() check, so no
     * hint is ever showing when the matching emote wouldn't actually do anything. */
    @Subscribe
    public void onAnimationChanged(AnimationChanged event)
    {
        if (phase != GamePhase.ACTIVE) return;

        Player localPlayer = client.getLocalPlayer();
        if (localPlayer == null || event.getActor() != localPlayer) return;

        int anim = localPlayer.getAnimation();

        if (anim == AnimationID.EMOTE_DANCE_SPIN)
        {
            // Spin is already the dice-roll gesture on a normal turn -- isLocalPlayerReadyToRoll()
            // is always false while a mini-game is active (see its own doc), so there's no overlap
            // between the two: Hot Potato only ever gets a look-in once a real turn couldn't be
            // the reason for this Spin.
            if (isLocalPlayerReadyToRoll())
            {
                awaitingSpinFinish = true;
                return;
            }
            if (isLocalPlayerHoldingHotPotato())
            {
                minigamePresentation.hotPotato().armAwaitingPassFinish();
                return;
            }
            return;
        }

        if (anim == AnimationID.EMOTE_BOW)
        {
            if (!isLocalPlayerAwaitingJadBow()) return;
            jadPresentation.armAwaitingBowFinish();
            return;
        }

        if (anim == AnimationID.EMOTE_YES)
        {
            if (isLocalPlayerAwaitingMinigameReady())
            {
                minigamePresentation.armAwaitingMinigameReadyFinish();
            }
            else if (isLocalPlayerAwaitingTrueOrFalseAnswer())
            {
                minigamePresentation.trueOrFalse().armAwaitingYesFinish();
            }
            return;
        }

        if (anim == AnimationID.EMOTE_NO)
        {
            if (isLocalPlayerAwaitingTrueOrFalseAnswer())
            {
                minigamePresentation.trueOrFalse().armAwaitingNoFinish();
            }
            return;
        }

        if (anim == AnimationID.EMOTE_DANCE_HEADBANG)
        {
            if (!isFishingContestActive() || fishingCatchSubmitted) return;
            awaitingHeadbangFinish = true;
            return;
        }

        if (awaitingSpinFinish)
        {
            awaitingSpinFinish = false;
            rollDice();
        }
        else if (jadPresentation.isAwaitingBowFinish())
        {
            jadPresentation.clearAwaitingBowFinish();
            bowToJad();
        }
        else if (minigamePresentation.isAwaitingMinigameReadyFinish())
        {
            minigamePresentation.clearAwaitingMinigameReadyFinish();
            confirmMinigameReady();
        }
        else if (minigamePresentation.trueOrFalse().isAwaitingYesFinish())
        {
            minigamePresentation.trueOrFalse().clearAwaitingYesFinish();
            answerTrueOrFalse(true);
        }
        else if (minigamePresentation.trueOrFalse().isAwaitingNoFinish())
        {
            minigamePresentation.trueOrFalse().clearAwaitingNoFinish();
            answerTrueOrFalse(false);
        }
        else if (awaitingHeadbangFinish)
        {
            awaitingHeadbangFinish = false;
            performFishingCatchRoll();
        }
        else if (minigamePresentation.hotPotato().isAwaitingPassFinish())
        {
            minigamePresentation.hotPotato().clearAwaitingPassFinish();
            passHotPotato();
        }
    }

    /** Whether the local player could actually roll the dice right now by performing the Spin
     * emote: it's genuinely their turn, no roll is already pending or in flight, no mini-game is
     * running, they're standing on their own tracked board position (see
     * isStandingOnTrackedPosition), and their own "<player>'s Turn"/"Your Turn!" banner has
     * actually had its chance to appear. currentTurnRsn itself is real state, set the instant
     * TURN_STARTED lands -- but the banner announcing it is cosmetic, deliberately delayed behind
     * turnEffectGateUntil so it doesn't stomp over e.g. the previous mini-game's rewards/round
     * recap still showing. Single source of truth for "can I roll right now" -- onAnimationChanged
     * gates the real roll on this, AnnouncementOverlay#renderSpinHint gates the reminder on the
     * exact same thing. Reads standingOnTrackedPositionCached rather than resolving the local
     * player's position live, since this is also called from RunePartyPanel (Swing EDT), and a
     * direct Player#getWorldLocation() call here would crash off the client thread. */
    public boolean isLocalPlayerReadyToRoll()
    {
        if (phase != GamePhase.ACTIVE || pendingRoll || rollRequestSubmitted || minigamePresentation.isActive()) return false;
        if (System.currentTimeMillis() < turnEffectGateUntil) return false;

        String self = localRsn();
        if (self == null || !self.equalsIgnoreCase(currentTurnRsn)) return false;

        return standingOnTrackedPositionCached;
    }

    /** Whether the local player needs to walk back to their own tracked board position before they
     * can roll again -- it's their turn, no roll is pending, no mini-game is running, and they're
     * not currently standing where TURN_STARTED left them (e.g. they wandered off toward the
     * Golden Gnome, or just walked off after landing last round). Mirrors TileOverlay#
     * renderReturnToPositionArrow's own gating (the in-world "Return Here!" arrow) for
     * AnnouncementOverlay#renderReturnToPositionHint's own on-screen text reminder -- that arrow
     * itself still resolves the local player's position live every frame rather than reading
     * standingOnTrackedPositionCached the way this does, so the two can differ by up to one tick
     * in edge cases, same tolerance every other cached-vs-live check in this file already accepts. */
    public boolean isLocalPlayerAwaitingReturnToPosition()
    {
        if (phase != GamePhase.ACTIVE || pendingRoll || minigamePresentation.isActive()) return false;
        String self = localRsn();
        if (self == null || !self.equalsIgnoreCase(currentTurnRsn)) return false;
        return !standingOnTrackedPositionCached;
    }

    /** Whether the table is genuinely waiting on someone's roll right now -- the same gating
     * isLocalPlayerReadyToRoll uses, minus the two checks that only make sense from the mover's
     * own perspective ("is it me" and "am I standing on my tracked tile"). Used by
     * AnnouncementOverlay#renderSpinHint to show everyone other than the mover "Waiting for
     * &lt;player&gt; to roll the dice...". Deliberately doesn't care whether the mover has
     * actually walked back to their tile yet -- from a bystander's vantage point "it's their turn
     * and nobody's rolled" is the whole story either way. */
    public boolean isAwaitingSomeonesRoll()
    {
        if (phase != GamePhase.ACTIVE || pendingRoll || minigamePresentation.isActive()) return false;
        if (System.currentTimeMillis() < turnEffectGateUntil) return false;
        return currentTurnRsn != null;
    }

    /** Whether the local player has a Jad encounter awaiting their own BOW response -- single
     * source of truth for "should a BOW emote actually do something right now," mirroring
     * isLocalPlayerReadyToRoll's role for the Spin emote. False once
     * the bow window has already closed server-side (see JadPresentation#isSmashTriggered) --
     * bowing at that point would just 409, same as the server's own guard in jad_bow. See
     * onAnimationChanged (gates the real response) and AnnouncementOverlay#renderJadEncounter
     * (gates the BOW instruction on the exact same thing). */
    public boolean isLocalPlayerAwaitingJadBow()
    {
        String encounterRsn = jadPresentation.getEncounterRsn();
        if (phase != GamePhase.ACTIVE || encounterRsn == null || jadPresentation.isSmashTriggered()) return false;
        String self = localRsn();
        return self != null && self.equalsIgnoreCase(encounterRsn);
    }

    /** Whether the local player still needs to YES-emote ready for the current mini-game --
     * mirrors isLocalPlayerAwaitingJadBow's role for that encounter's own BOW emote. See
     * onAnimationChanged (gates the real confirmMinigameReady call) and
     * AnnouncementOverlay#renderMinigameReadyCheck (gates the "use the YES emote" instruction on
     * the exact same thing, so it stops nagging a player the instant their own ready lands). */
    public boolean isLocalPlayerAwaitingMinigameReady()
    {
        if (phase != GamePhase.ACTIVE || !minigamePresentation.isActive() || minigamePresentation.isCountdownStarted()) return false;
        String self = localRsn();
        return self != null && !minigamePresentation.getMinigameReadyRsns().contains(self.toLowerCase(Locale.ROOT));
    }

    /** Whether the panel should show the current mini-game's real play controls (see
     * RunePartyPanel#refresh, the only caller) rather than the spinner/instructions/ready-check
     * sequence still being in AnnouncementOverlay. minigameCountdownStarted is real state, but
     * minigameCountdownBannerUntil is cosmetic-only, and for a live client isn't even armed until
     * MINIGAME_COUNTDOWN_START_DELAY_MS after minigameCountdownStarted flips -- so during that
     * pause it's legitimately still 0 while very much not yet playable.
     * minigameCountdownSkippedForClient is what tells that pause apart from a client that only
     * caught up on the fact that the whole sequence already happened -- only that client should
     * become playable immediately instead of waiting on a "3...2...1... BEGIN!" replay for a
     * moment that's long since passed. */
    public boolean isMinigamePlayable()
    {
        if (!minigamePresentation.isActive() || !minigamePresentation.isCountdownStarted()) return false;
        if (minigamePresentation.isCountdownSkippedForClient()) return true;
        long countdownBannerUntil = minigamePresentation.getCountdownBannerUntil();
        return countdownBannerUntil != 0 && System.currentTimeMillis() >= countdownBannerUntil;
    }

    /** Whether the local player still needs to answer the current True or False round -- mirrors
     * isLocalPlayerAwaitingJadBow's role for that encounter's own BOW emote. Requires
     * isMinigamePlayable() (not just minigameActive), same "the ready-check has to actually
     * finish first" gate every other in-round action here respects. See onAnimationChanged (gates
     * the real answerTrueOrFalse call) and AnnouncementOverlay#renderTrueOrFalseQuestion (gates
     * the "use YES/NO" instruction on the exact same thing, so it stops prompting a player the
     * instant their own answer lands). */
    public boolean isLocalPlayerAwaitingTrueOrFalseAnswer()
    {
        if (!TRUE_OR_FALSE_KEY.equals(minigamePresentation.getKey()) || !isMinigamePlayable() || minigamePresentation.trueOrFalse().getQuestion() == null) return false;
        String self = localRsn();
        return self != null && !minigamePresentation.trueOrFalse().getAnsweredRsns().contains(self.toLowerCase(Locale.ROOT));
    }

    /** Whether the local player is the current Hot Potato holder -- unlike every other
     * isLocalPlayerAwaiting*() check here, this isn't a one-shot "answer once" gate: a holder can
     * Spin-emote to pass at any moment right up until the round's own clock runs out from under
     * them, as many times as this ever comes back true again (it never will for the same round,
     * since passing hands it to someone else, but nothing here assumes that). See
     * onAnimationChanged's own EMOTE_DANCE_SPIN case, the only caller. */
    public boolean isLocalPlayerHoldingHotPotato()
    {
        if (!HOT_POTATO_KEY.equals(minigamePresentation.getKey()) || !isMinigamePlayable()) return false;
        String self = localRsn();
        String holder = minigamePresentation.hotPotato().getHolder();
        return self != null && holder != null && self.equalsIgnoreCase(holder);
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

    /** Every RunePartyColor not currently held by a seated PLAYER -- shared by addToGameMenuEntry
     * (world right-click submenu) and RunePartyPanel#buildAddToGamePopup (roster panel submenu),
     * the two "Add to Game" surfaces, so the host is never offered a color that would immediately
     * 409 as taken. Recomputed fresh on every menu build rather than cached, same "always read the
     * live roster" approach every other menu-availability check here already takes. */
    List<RunePartyColor> availableSeatColors()
    {
        Set<RunePartyColor> taken = EnumSet.noneOf(RunePartyColor.class);
        for (RosterReducer.RosterEntry entry : rosterReducer.seatedPlayers())
        {
            RunePartyColor color = RunePartyColor.forNumber(entry.colorNumber);
            if (color != null) taken.add(color);
        }
        List<RunePartyColor> available = new ArrayList<>();
        for (RunePartyColor color : RunePartyColor.values())
        {
            if (!taken.contains(color)) available.add(color);
        }
        return available;
    }

    /** Adds an "Add to Game" submenu -- one entry per currently-available seat color (see
     * availableSeatColors) -- on another player's Follow option, host-only, so the host can pull a
     * spectator into the turn order with a specific color rather than always following whatever
     * order players happened to be added in. Joining a game only ever grants SPECTATOR (see
     * assignRole's doc). Hidden once the target is already a PLAYER. */
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

        MenuEntry parent = client.createMenuEntry(-1)
            .setOption("Add to Game")
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE_PLAYER)
            .setIdentifier(event.getIdentifier());

        Menu submenu = parent.createSubMenu();
        for (RunePartyColor color : availableSeatColors())
        {
            submenu.createMenuEntry(-1)
                .setOption(color.menuTag(color.displayName))
                .setTarget("")
                .setType(MenuAction.RUNELITE)
                .onClick(me -> assignRole(targetRsn, RunePartyRole.PLAYER, color.seatNumber()));
        }
    }

    /** Adds a "Tele Block &lt;name&gt;"-style entry on another seated PLAYER's own Follow option
     * while a requires_target item is armed (see beginItemTargeting) -- the same "inject onto the
     * actor's own context menu" idiom addToGameMenuEntry uses, just gated on itemTargetKey instead
     * of isHost(). Offered on any active PLAYER other than the local player, including one who's
     * already Tele Blocked -- stacking is intentional, see TeleBlockItem's own doc. Tele Block is
     * the only requires_target item so far, hence the direct confirmItemTargetOn call rather than
     * a more general dispatch. */
    private void addItemTargetMenuEntry(MenuEntryAdded event)
    {
        String itemKey = itemTargetKey;
        if (itemKey == null || phase != GamePhase.ACTIVE) return;
        if (!(event.getMenuEntry().getActor() instanceof Player)) return;

        Player target = (Player) event.getMenuEntry().getActor();
        if (target == null || target == client.getLocalPlayer() || target.getName() == null) return;

        String targetRsn = Text.toJagexName(target.getName());
        if (targetRsn == null || targetRsn.isBlank()) return;
        if (rosterReducer.getRole(targetRsn) != RunePartyRole.PLAYER) return;

        client.createMenuEntry(-1)
            .setOption("Cancel " + Items.get(itemKey).getDisplayName())
            .setTarget("")
            .setType(MenuAction.RUNELITE)
            .onClick(me -> cancelItemTargeting());

        client.createMenuEntry(-1)
            .setOption("<col=00FF00>Use " + Items.get(itemKey).getDisplayName() + "</col>")
            .setTarget(event.getTarget())
            .setType(MenuAction.RUNELITE_PLAYER)
            .setIdentifier(event.getIdentifier())
            .onClick(me -> confirmItemTargetOn(targetRsn));
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


    /** Pushes turnEffectGateUntil forward to at least {@code untilTimestamp} -- called by whatever
     * just started a turn-effect visual with its own on-screen duration (currently only the
     * COINS_CHANGED handler, passing coinPopupUntil). A future tile effect with its own timed
     * reveal (a teleport animation, a "steal coins" flourish, whatever comes next) should call this
     * the same way when it starts, and nothing else needs to change -- every "what's next"
     * announcement already waits on this one gate via scheduleAfterTurnEffects. Never moves the
     * gate backward, so two effects landing close together both get their own full window. */
    public void extendTurnEffectGate(long untilTimestamp)
    {
        turnEffectGateUntil = Math.max(turnEffectGateUntil, untilTimestamp);
    }

    /** Read by AnnouncementOverlay#renderJadEncounter, which -- unlike every other banner here --
     * doesn't fit the fixed-duration TimedBanner/armBanner shape (it needs to keep rendering for as
     * long as a Jad encounter's genuinely pending, not one preset window), so it checks this
     * directly every frame instead of going through scheduleAfterTurnEffects. Also read by
     * models/JadEncounter#spawn for the same reason, one package over. */
    public long getTurnEffectGateUntil()
    {
        return turnEffectGateUntil;
    }

    /** Appends a new CoinPopup to {@code rsn}'s own queue (see coinPopups's own doc for why this
     * is a queue, not a single slot) and extends the turn-effect gate to match. Shared by
     * COINS_CHANGED's real coin-total popups and COIN_RUSH_COLLECTED's own totalless "+2" flash,
     * so a Coin Rush round's mid-round flashes and its own end-of-round lump-sum popup still queue
     * behind each other and behind a Golden Gnome popup correctly.
     * <p>
     * start is computed by queuing behind whichever's already showing for this same player -- their
     * Golden Gnome popup (tracked separately, not in this queue) if that's still up, else the tail
     * of their own coin-popup queue, else "now" if nothing's currently showing. A different player
     * always gets their own popup immediately regardless of what's showing for anyone else. */
    public void enqueueCoinPopup(String rsn, int delta, int newTotal, long durationMs, boolean totalless)
    {
        String key = rsn.toLowerCase(Locale.ROOT);
        long now = System.currentTimeMillis();

        Deque<CoinPopup> queue = coinPopups.computeIfAbsent(key, k -> new ConcurrentLinkedDeque<>());
        CoinPopup tailPopup = queue.peekLast();
        String samePlayerGnomePopupRsn = goldenGnomePresentation.getPopupRsn();
        boolean samePlayerGnomePopupShowing = rsn.equalsIgnoreCase(samePlayerGnomePopupRsn) && goldenGnomePresentation.getPopupUntil() > now;
        long start = samePlayerGnomePopupShowing ? goldenGnomePresentation.getPopupUntil()
            : (tailPopup != null && tailPopup.until > now) ? tailPopup.until
            : now;
        long until = start + durationMs;

        queue.addLast(new CoinPopup(delta, newTotal, start, until, totalless));
        extendTurnEffectGate(until);
    }

    /** Schedules {@code action} to run once every in-flight turn-effect visual has cleared (see
     * extendTurnEffectGate) plus a short POST_TURN_EFFECT_GRACE_MS beat, so an outgoing effect and
     * an incoming "turn's over" announcement never visually collide -- runs immediately if nothing
     * is currently gating. Cancels {@code previousTask} first so a stray double-fire of the caller
     * can't leave two competing delayed writes in flight; returns the new task so the caller can do
     * the same on its next call.
     * <p>
     * Synchronously reserves the gate through this effect's own {@code durationMs} before this
     * method even returns. That matters whenever more than one of these gets scheduled in the same
     * tick from different events: without reserving synchronously, the second call would compute
     * its own delay against a gate that doesn't know the first effect is coming yet, so both would
     * end up scheduled for the same moment instead of one waiting on the other. Shared by every
     * "the turn is over, here's what's next" announcement. */
    public ScheduledFuture<?> scheduleAfterTurnEffects(ScheduledFuture<?> previousTask, long durationMs, Runnable action)
    {
        if (previousTask != null) previousTask.cancel(false);

        long now = System.currentTimeMillis();
        long delay = turnEffectGateUntil > now ? (turnEffectGateUntil - now) + POST_TURN_EFFECT_GRACE_MS : 0;
        extendTurnEffectGate(now + delay + durationMs);

        // The panel (isLocalPlayerReadyToRoll-gated item/roll UI) only ever refreshes on an
        // explicit refreshPanel() call, unlike AnnouncementOverlay's per-frame render() -- so
        // without this, once turnEffectGateUntil lifts here with no new server event to trigger a
        // refresh, the item-use section/SPIN-adjacent panel state can go stale indefinitely. Fire
        // one right as this effect's own reservation of the gate expires so the panel re-checks
        // readiness the moment it's actually true.
        uiTimerExec.schedule(this::refreshPanel, delay + durationMs, TimeUnit.MILLISECONDS);

        return uiTimerExec.schedule(action, delay, TimeUnit.MILLISECONDS);
    }

    /** Reserves the turn-effect gate for a whole multi-stage sequence's {@code totalDurationMs} in
     * one atomic step, and returns the delay (ms from now) before that reserved window begins --
     * for a caller about to run several timed stages back-to-back (a title card, then a tableau,
     * then...) that all need to land as one uninterrupted block.
     * <p>
     * A chain of individual scheduleAfterTurnEffects calls -- each stage only calling the next one
     * from inside its own callback -- reserves the gate one stage at a time, discovering each later
     * stage's own duration only once the previous stage's callback actually fires. That leaves a
     * window, for as long as only the first stage's duration is reserved, where an unrelated event
     * arriving mid-chain (e.g. the next round's MINIGAME_STARTED) reads a gate that doesn't know the
     * later stages are coming yet, and schedules itself to land on top of them. Calling this once,
     * synchronously, before scheduling any of the chain's own stages closes that window: every
     * stage's own duration is already accounted for in the gate from the very first moment.
     * <p>
     * Callers should schedule each of their own stages via {@code uiTimerExec.schedule} directly, at
     * this returned delay plus that stage's own cumulative offset into the sequence -- not via
     * another scheduleAfterTurnEffects call per stage, which would read and extend the gate a second
     * time on top of this reservation. */
    public long reserveTurnEffectGate(long totalDurationMs)
    {
        long now = System.currentTimeMillis();
        long delay = turnEffectGateUntil > now ? (turnEffectGateUntil - now) + POST_TURN_EFFECT_GRACE_MS : 0;
        extendTurnEffectGate(now + delay + totalDurationMs);
        uiTimerExec.schedule(this::refreshPanel, delay + totalDurationMs, TimeUnit.MILLISECONDS);
        return delay;
    }

    /** Plain fixed-delay scheduling against uiTimerExec, with no turn-effect gating of its own --
     * unlike scheduleAfterTurnEffects, {@code action} just runs {@code delayMs} from now. Public
     * (unlike uiTimerExec itself, deliberately package-private) so a caller outside this package,
     * e.g. models/JadEncounter's own bow-acknowledge/idle-reapply timers, can still schedule a
     * plain callback without reaching into the raw executor. */
    public ScheduledFuture<?> scheduleDelayed(Runnable action, long delayMs)
    {
        return uiTimerExec.schedule(action, delayMs, TimeUnit.MILLISECONDS);
    }

    /** Arms `banner` behind whatever turn-effect visual is already showing (see
     * scheduleAfterTurnEffects) -- collapses the `<field>.task = scheduleAfterTurnEffects(...) {
     * <field>.payload = ...; <field>.until = now + duration; extendTurnEffectGate(...); }` shape
     * repeated across several scheduleXBanner methods below. Not applied to every one of those --
     * several have real behavior beyond "arm one banner" (bespoke until/gate math, chaining to a
     * follow-up step, arming two banners at once) that this deliberately doesn't try to
     * generalize. */
    public <T> void armBanner(TimedBanner<T> banner, long durationMs, Supplier<T> payload, boolean extendGate)
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

    /** Same shape as scheduleTurnAnnouncement, for turnSkippedAnnounce instead -- fired on
     * TURN_SKIPPED in place of the "It's &lt;rsn&gt;'s Turn" banner that player never actually
     * gets this time (see that field's own doc). */
    private void scheduleTurnSkippedAnnouncement(String rsn)
    {
        armBanner(turnSkippedAnnounce, TURN_ANNOUNCE_DURATION_MS, () -> rsn, false);
    }

    /** Arms teleBlockCastAnnounce -- same duration/queuing shape ItemPresentation's own
     * scheduleItemUsedAnnouncement uses for every other item's "You/&lt;rsn&gt; used &lt;item&gt;!"
     * banner (see ITEM_USED_ANNOUNCE_DURATION_MS), reused here rather than a bespoke duration since
     * this is visually the same two-line title/subtitle shape, just with a target woven into the
     * title (see renderTeleBlockCastAnnouncement). */
    private void scheduleTeleBlockCastAnnouncement(String casterRsn, String targetRsn)
    {
        armBanner(teleBlockCastAnnounce, ITEM_USED_ANNOUNCE_DURATION_MS,
            () -> new TeleBlockCastPayload(casterRsn, targetRsn), true);
    }

    // scheduleMinigameBanner/scheduleMinigameSpinner/triggerMinigameRewardsBanner/
    // scheduleRoundCompleteBanner, and scheduleItemSpinner/scheduleItemCapBlockedAnnouncement/
    // scheduleItemUsedAnnouncement/scheduleCoinTrapTriggerAnnouncement, live on
    // MinigamePresentation/ItemPresentation respectively, along with the fields/handleEvent
    // cases/getters they back.

    // triggerWelcomeBanner/persistSession/clearPersistedSession/attemptSessionResume/
    // connectEventStream/syncRosterSnapshot all live on SessionManager now, along with the
    // session fields they back.

    // -------------------------------------------------------------------------
    // Server-pushed events
    // -------------------------------------------------------------------------

    /** {@code catchingUp} is true both for connectEventStream's initial backlog fetch and for
     * EventSocket's own replay burst after a reconnect -- false only for an event that arrives
     * genuinely live over the WebSocket. Real game state -- turn order, coins, board positions,
     * tile markers, the minigame-active flag, roster sync -- always applies either way, via
     * rosterReducer/tileReducer above and the unguarded field writes below. Anything purely
     * cosmetic (a banner, a popup timer, a chat line) is gated behind {@code !catchingUp} so a
     * player who joins mid-game, or whose connection drops and reconnects mid-game, only ever
     * sees the game's current state, not a replay of how it got there. */
    public void handleEvent(ApiClient.EventOut e, boolean catchingUp)
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

            case Events.STANDARD_COURSE_LOCKED:
                // Real state, applied catch-up or not -- a client that only caught up on an
                // already-locked game still needs its course-building tools hidden, same as one
                // that watched the lock happen live.
                standardCourseKey = Json.requiredStr(e.payload, type, "courseKey");
                break;

            case Events.GAME_ENDED:
                phase = GamePhase.ENDED;
                if (!catchingUp)
                {
                    ceremonyPresentation.triggerGameOverSequence();
                }
                break;

            case Events.PLAYER_READY:
                if (!catchingUp)
                {
                    addChatMessage(Json.requiredStr(e.payload, type, "player") + " is ready at the start!");
                }
                break;

            // None of these three carry a turn-order "number" in their payload -- the server only
            // ever computes it fresh from the whole event log on a roster read, and it can shift
            // for everyone whenever the PLAYER set changes (a join, a promotion, a leave). So on
            // any of them, pull a fresh roster snapshot rather than trying to derive numbers from
            // the event stream itself. Skipped during catch-up -- connectEventStream does one
            // roster sync after the whole backlog instead of one REST call per historical
            // join/promotion/leave.
            case Events.PLAYER_JOINED:
            case Events.ROLE_ASSIGNED:
            case Events.PLAYER_LEFT:
                if (!catchingUp)
                {
                    sessionManager.syncRosterSnapshot(false);
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
                pendingReachableIndices = Collections.emptyList();
                arrivalSubmitted = false;
                // NOT a resync of whether a Home Teleport arrival is actually still owed (that's
                // rosterReducer.isHomeTeleportPending, driven by HOME_TELEPORT_ARMED/ARRIVED, and
                // deliberately survives a turn change -- see homeTeleportPendingByPlayer's own
                // doc) -- just the same "let the next tick retry" backstop arrivalSubmitted itself
                // gets here, in case a stray in-flight submission never got its own retry reset.
                homeTeleportArrivalSubmitted = false;
                itemUsedThisTurn = false;
                goldenGnomePurchasedThisTurn = false;
                // Backstop for the same invariant rollDice() enforces on its own path (see that
                // method's own doc) -- an armed-but-never-placed/targeted item must never survive
                // into a turn other than the one it was armed on, regardless of how this turn
                // actually ended.
                itemPlacementKey = null;
                itemTargetKey = null;
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

            case Events.TURN_SKIPPED:
            {
                // currentTurnPlayer never becomes the skipped player at all -- this fires in place
                // of TURN_STARTED for them, not before it -- so unlike TURN_STARTED there's no
                // turn-state field here to update. This is purely a cosmetic "here's why you
                // didn't just see a TURN_STARTED for them" announcement.
                if (!catchingUp)
                {
                    String skippedRsn = Json.requiredStr(e.payload, type, "player");
                    if (skippedRsn != null)
                    {
                        scheduleTurnSkippedAnnouncement(skippedRsn);
                        String self = localRsn();
                        addChatMessage((self != null && self.equalsIgnoreCase(skippedRsn) ? "Your" : skippedRsn + "'s")
                            + " turn was skipped -- Tele Blocked!");
                    }
                }
                break;
            }

            case Events.DICE_ROLLED:
            {
                lastDiceRoll = Json.requiredInt(e.payload, type, "value");
                pendingTargetIndices = Json.safeIntList(e.payload, "targetIndices");
                pendingReachableIndices = Json.safeIntList(e.payload, "reachableIndices");
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

            case Events.GOLDEN_GNOME_PURCHASED:
            {
                // Real state, applied catch-up or not -- see goldenGnomePurchasedThisTurn's own
                // doc. A GOLDEN_GNOME_PURCHASED can only ever be inserted for the current turn's
                // player (see the server's own goldenGnomePurchasedThisTurn gate), so this is
                // always the same turn TURN_STARTED just reset it for.
                goldenGnomePurchasedThisTurn = true;
                goldenGnomePresentation.apply(e, catchingUp);
                break;
            }

            case Events.GOLDEN_GNOME_LOST:
            case Events.GOLDEN_GNOME_WON:
            {
                goldenGnomePresentation.apply(e, catchingUp);
                break;
            }

            case Events.CHANCE_SPACE_TRIGGERED:
            {
                chanceSpacePresentation.apply(e, catchingUp);
                break;
            }

            case Events.JAD_AWAKENED:
            {
                jadPresentation.apply(e, catchingUp);
                break;
            }

            case Events.JAD_SMASH_TRIGGERED:
            {
                jadPresentation.apply(e, catchingUp);
                // Cosmetic-only trigger for the actual animation playback -- a catching-up client
                // has already missed the moment this would have looked right, same gate every
                // other "reveal a moment that's either happening live or already resolved" cosmetic
                // in this file uses.
                if (!catchingUp)
                {
                    jadEncounter.playSmash();
                }
                break;
            }

            case Events.JAD_DISMISSED:
            {
                jadPresentation.apply(e, catchingUp);
                // "bowed" gets its own one-shot reaction (JAD_BOW_ACKNOWLEDGE_ANIMATION_ID, then
                // back to JAD_IDLE_ANIMATION_ID -- see JadEncounter#playBowThenClear), held back
                // JAD_OUTCOME_BANNER_DURATION_MS from this same moment rather than fired
                // immediately, so the "Your loyalty will cost you N coins!" outcome banner has had
                // its full duration to be read before Jad actually reacts. Every other case
                // (smashed, or a catching-up client with nothing to animate) despawns immediately
                // instead -- by the time this fires on the smashed path, the whole encounter has
                // already played out on its own server-timed schedule.
                if (!catchingUp && "bowed".equals(Json.safeStr(e.payload, "outcome")))
                {
                    scheduleDelayed(jadEncounter::playBowThenClear, JAD_OUTCOME_BANNER_DURATION_MS);

                    // Reserve the turn-effect gate for the whole client-timed bowed sequence up
                    // front, not just the outcome banner's own duration -- jadPresentation.apply
                    // above already extended it that far, which is only enough to stop the next
                    // turn/mini-game announcement from colliding with the banner itself. Without
                    // this, that announcement could still fire the moment the banner fades while
                    // Jad's model is still mid-animation, or before the delayed "jad_bow" coin
                    // popup has even appeared. Whichever finishes later -- Jad's own despawn or
                    // the coin popup's own on-screen window -- wins; Math.max inside
                    // extendTurnEffectGate makes the earlier extension a harmless no-op.
                    long now = System.currentTimeMillis();
                    long jadClearAt = now + JAD_OUTCOME_BANNER_DURATION_MS + JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS
                        + JAD_BOW_ACKNOWLEDGE_IDLE_HOLD_MS;
                    long coinPopupEndsAt = now + JAD_OUTCOME_BANNER_DURATION_MS + JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS
                        + COIN_POPUP_DURATION_MS;
                    extendTurnEffectGate(Math.max(jadClearAt, coinPopupEndsAt));
                }
                else
                {
                    jadEncounter.clear();
                }
                break;
            }

            case Events.JADDY_ATTACK_TRIGGERED:
            {
                // Purely cosmetic, no real state to fold -- this never touches a presentation class
                // at all. Deliberately NOT gated on !catchingUp, unlike every other one-shot
                // animation trigger in this switch: a client that drops connection (EventSocket
                // auto-reconnects with full-jitter backoff, see that class's own doc) mid-fight and
                // reconnects replays its whole missed backlog as catchingUp=true, and this event is
                // the ONLY thing that ever updates a Jad's own health bar -- skipping it here left
                // both Jads frozen at 100/100, idling forever with no explanation, for the rest of
                // that round, on every client that so much as blipped its connection during a duel.
                // Replaying every missed beat back-to-back like this can flicker through more than
                // one attack animation/hitsplat in a single frame, but each Slot's own fields just
                // get overwritten by the next update in the burst, so whatever's actually visible
                // once the burst finishes still reflects the truth -- a strictly better outcome than
                // freezing. See JADDY_DUEL_RESOLVED below for the same reasoning applied to the
                // duel's own final outcome.
                String attackingColor = Json.requiredStr(e.payload, type, "attackingColor");
                String defendingColor = Json.requiredStr(e.payload, type, "defendingColor");
                Integer animationId = Json.requiredInt(e.payload, type, "animationId");
                Integer damage = Json.requiredInt(e.payload, type, "damage");
                Integer defenderHp = Json.requiredInt(e.payload, type, "defenderHp");
                Integer defenderMaxHp = Json.requiredInt(e.payload, type, "defenderMaxHp");
                if (attackingColor != null && defendingColor != null && animationId != null
                    && damage != null && defenderHp != null && defenderMaxHp != null)
                {
                    jaddyDuelModel.playAttack(attackingColor, defendingColor, animationId, damage, defenderHp, defenderMaxHp);
                }
                break;
            }

            case Events.JADDY_DUEL_RESOLVED:
            {
                minigamePresentation.apply(e, catchingUp);
                // Deliberately NOT gated on !catchingUp -- see JADDY_ATTACK_TRIGGERED's own doc for
                // the bug this fixes. This is the one animation trigger in this whole switch that's
                // safe (and important) to always apply regardless of catch-up: it's a one-time
                // terminal transition, not a replayable-many-times reveal, so there's no "flickering
                // through history" risk the way JADDY_ATTACK_TRIGGERED's own burst-replay has -- a
                // client that reconnects after the duel already resolved gets the correct final
                // state (the right side dead, the other still standing) immediately, instead of
                // never resolving at all. Only the celebratory "<color> Won!" banner itself
                // (minigamePresentation.apply above) stays catch-up-suppressed, same convention
                // every other banner in this file follows.
                String winningColor = Json.requiredStr(e.payload, type, "winningColor");
                if (winningColor != null) jaddyDuelModel.resolve(winningColor);
                break;
            }

            case Events.ITEM_GRANTED:
            case Events.ITEM_CAP_BLOCKED:
            {
                itemPresentation.apply(e, catchingUp);
                break;
            }

            case Events.ITEM_USED:
            {
                // Real state, applied catch-up or not: an ITEM_USED can only ever be inserted for
                // the current turn's player (see the server's _require_ready_to_act), so this is
                // always the same turn TURN_STARTED just reset it for. Inventory itself is already
                // decremented unconditionally by rosterReducer.apply above.
                itemUsedThisTurn = true;
                itemPresentation.handleItemUsed(e, catchingUp);
                break;
            }

            case Events.TELE_BLOCK_APPLIED:
            {
                // TeleBlockItem leaves hasUseAnnouncement() at its default false -- the generic
                // "You used/<rsn> used <item>!" banner ITEM_USED already fires above has no
                // target field to phrase around, so this fires its own dedicated "You/<caster>
                // cast teleblock on <target>!" banner instead, alongside the impact spotanim on
                // the target's own actor -- both fire together, right here, since there's no
                // earlier "reveal" step for this to wait behind. teleblockedByPlayer itself is
                // already updated unconditionally by rosterReducer.apply above, catch-up or not.
                if (!catchingUp)
                {
                    String blockedRsn = Json.requiredStr(e.payload, type, "player");
                    String byRsn = Json.requiredStr(e.payload, type, "appliedBy");
                    if (blockedRsn != null && byRsn != null)
                    {
                        scheduleTeleBlockCastAnnouncement(byRsn, blockedRsn);
                        triggerSpotAnimOnPlayer(TELE_BLOCK_IMPACT_SPOTANIM_ID, blockedRsn, TELE_BLOCK_IMPACT_SPOTANIM_HEIGHT);
                        addChatMessage(byRsn + " cast teleblock on " + blockedRsn + "! " + blockedRsn + " will lose their next turn.");
                    }
                }
                break;
            }

            case Events.HOT_POTATO_EXPLODED:
            {
                // minigamePresentation.apply folds the elimination itself (hotPotatoEliminatedRsns)
                // unconditionally -- real state, needed immediately even for a catching-up client
                // so PlayerOverlay's own skull indicator is correct from the first frame. The
                // explosion effect/chat message below are a separate, one-shot reveal, gated on
                // !catchingUp same as TELE_BLOCK_APPLIED's own impact spotanim above.
                minigamePresentation.apply(e, catchingUp);
                if (!catchingUp)
                {
                    String explodedRsn = Json.requiredStr(e.payload, type, "player");
                    if (explodedRsn != null)
                    {
                        triggerHotPotatoExplosion(explodedRsn);
                        addChatMessage(explodedRsn + "'s potato exploded! They're eliminated from this round.");
                    }
                }
                break;
            }

            case Events.GOLDEN_GNOME_MOVED:
            {
                goldenGnomePresentation.apply(e, catchingUp);
                break;
            }

            case Events.TILE_EFFECT:
            {
                // PATH/PENALTY_TILE/ITEM_TILE are the tile types with a real (coins/item) effect so
                // far (see the COINS_CHANGED/ITEM_GRANTED cases below, which actually pay/grant it)
                // -- START/EVENT_TILE are still no-ops, but this event fires for every type so this
                // chat line is always accurate regardless. JAD_TILE has no coins/item effect of its
                // own either, but does trigger a purely client-side cosmetic reaction below --
                // spawning Jad's own model.
                String tileEffectPlayer = Json.requiredStr(e.payload, type, "player");
                String tileEffectType = Json.requiredStr(e.payload, type, "tileType");
                if (!catchingUp)
                {
                    addChatMessage(tileEffectPlayer + " landed on a " + tileEffectType + " tile.");
                }
                // Gated on !catchingUp same as every other cosmetic-reveal elsewhere in this file
                // -- a reconnecting client simply doesn't see a replay of a Jad appearance that's
                // already come and gone.
                if ("JAD_TILE".equals(tileEffectType) && !catchingUp && tileEffectPlayer != null)
                {
                    TileReducer.TileEntry landed = tileReducer.tileAtIndex(getPlayerPosition(tileEffectPlayer));
                    if (landed != null)
                    {
                        jadEncounter.spawn(landed.point.dy(3), landed.point);
                    }
                }
                break;
            }

            case Events.COIN_TRAP_TRIGGERED:
            {
                itemPresentation.apply(e, catchingUp);
                break;
            }

            case Events.COINS_CHANGED:
            {
                // The standard-tile reward, the Start tile's own reward, an item's own coin
                // effect, and a Coin Trap steal all get the popup treatment -- a Golden Gnome
                // purchase or a mini-game's own end-of-round payout already has its own feedback,
                // so this stays scoped to the cases that otherwise had no visible feedback at all.
                // Coin Rush's "coin_rush" and True or False's "true_or_false" reasons are worth
                // calling out: unlike the others, neither fires per landing -- the server bundles
                // every coin/correct-answer from the whole round/mini-game into one lump-sum
                // COINS_CHANGED right before MINIGAME_ENDED, so each case only ever fires once per
                // player per mini-game, showing their own round total then their real new balance.
                // Coin Rush's own individual pickups get a separate, purely cosmetic "+2" flash
                // instead (see COIN_RUSH_COLLECTED handling); True or False has no equivalent
                // mid-round flash since a round's own correctness isn't revealed until it ends
                // anyway. The real coin total itself lives in rosterReducer (updated
                // unconditionally above, catch-up or not) -- everything in this block is purely
                // the popup's own cosmetics. "dev_adjust" gets the same treatment as any other
                // unattended coin change.
                // "chance_space" is deliberately excluded here -- ChanceSpacePresentation triggers
                // its own coin popup manually (see its own applyDeferredDelta), at its own reveal's
                // chosen moment rather than the instant this event arrives.
                String coinsChangedReason = Json.requiredStr(e.payload, type, "reason");
                if (!catchingUp && ("standard_tile".equals(coinsChangedReason) || "start_tile".equals(coinsChangedReason)
                    || "item".equals(coinsChangedReason) || "coin_trap".equals(coinsChangedReason)
                    || "coin_rush".equals(coinsChangedReason) || "true_or_false".equals(coinsChangedReason)
                    || "jad_smash".equals(coinsChangedReason) || "jad_bow".equals(coinsChangedReason)
                    || "dev_adjust".equals(coinsChangedReason)))
                {
                    String coinsChangedRsn = Json.requiredStr(e.payload, type, "player");
                    Integer delta = Json.requiredInt(e.payload, type, "delta");
                    Integer total = Json.requiredInt(e.payload, type, "coins");

                    if (coinsChangedRsn != null)
                    {
                        // "jad_bow" is the one reason here that doesn't reflect its popup/chat the
                        // instant the event lands -- the toll has to visibly land only after the
                        // "Your loyalty will cost you N coins!" banner has been read and the
                        // bow-acknowledge animation has played. Every other reason here has no
                        // such staged reveal to wait on, so they still fire immediately.
                        if ("jad_bow".equals(coinsChangedReason))
                        {
                            int jadBowDelta = delta != null ? delta : 0;
                            int jadBowTotal = total != null ? total : 0;
                            scheduleDelayed(() ->
                            {
                                enqueueCoinPopup(coinsChangedRsn, jadBowDelta, jadBowTotal, COIN_POPUP_DURATION_MS, false);
                                addChatMessage(coinsChangedRsn + "'s loyalty cost them " + Math.abs(jadBowDelta) + " coins!");
                            }, JAD_OUTCOME_BANNER_DURATION_MS + JAD_BOW_ACKNOWLEDGE_ANIMATION_HOLD_MS);
                        }
                        else
                        {
                            enqueueCoinPopup(coinsChangedRsn, delta != null ? delta : 0, total != null ? total : 0,
                                COIN_POPUP_DURATION_MS, false);
                            // Jad has no Golden Gnome to take here -- this is the only feedback
                            // the coin-loss branch gets, matching Coin Trap's own restraint
                            // (popup + chat, no dedicated banner).
                            if ("jad_smash".equals(coinsChangedReason))
                            {
                                addChatMessage("Jad smashes " + coinsChangedRsn + "! They lost " + Math.abs(delta != null ? delta : 0) + " coins!");
                            }
                        }
                    }
                }
                break;
            }

            case Events.MINIGAME_STARTED:
            case Events.MINIGAME_PLAYER_READY:
            case Events.MINIGAME_COUNTDOWN_STARTED:
            case Events.MINIGAME_ROUND_BEGIN:
            case Events.COIN_RUSH_SPAWN:
            case Events.COIN_RUSH_COLLECTED:
            case Events.SANDWICH_RUSH_ITEM_SPAWNED:
            case Events.SANDWICH_RUSH_ITEM_COLLECTED:
            case Events.TRUE_OR_FALSE_ROUND_STARTED:
            case Events.TRUE_OR_FALSE_ANSWERED:
            case Events.TRUE_OR_FALSE_ROUND_ENDED:
            case Events.MINIGAME_TEAMS_ASSIGNED:
            case Events.HOT_POTATO_ASSIGNED:
            {
                if (Events.MINIGAME_STARTED.equals(type))
                {
                    // The round's last roller transitions straight from their own confirm-arrival
                    // into MINIGAME_STARTED, not TURN_STARTED -- without this, pendingRoll would
                    // stay stuck true for the entire mini-game, since only TURN_STARTED's own case
                    // resets it otherwise. That left the "Purchase Golden Gnome" menu entry
                    // offering itself to that player well after their turn -- and the round --
                    // was actually over.
                    pendingRoll = false;
                    // Real state regardless of catch-up: a fresh mini-game instance never inherits
                    // a previous round's own Fishing Contest tally/submission-guard/emote-wait, even
                    // if that previous round somehow ended abnormally (host force-end) before its
                    // own local 30-second timer got the chance to submit and clear this itself.
                    shrimpCount = 0;
                    anchovyCount = 0;
                    fishingCatchSubmitted = false;
                    awaitingHeadbangFinish = false;
                    // Same reasoning as Fishing Contest's own reset just above -- a fresh Click,
                    // Click, Click instance starts with no clicks and no submission.
                    clickClickClickTiles.clear();
                    clickClickClickSubmitted = false;
                }
                minigamePresentation.apply(e, catchingUp);
                break;
            }

            case Events.MINIGAME_ENDED:
                // Real state, applied catch-up or not -- one MINIGAME_ENDED is exactly one
                // completed round. This is core whole-game progress, not one mini-game instance's
                // own state -- see getCurrentRound/StatsOverlay's "ROUND x/y" line, the only
                // consumer -- so it stays inline rather than moving into MinigamePresentation
                // with the rest of this case.
                completedRounds++;
                // Defensive guard against a stray late catch/submission -- covers a round ending
                // (host force-end, or the server's own bounded wait simply timing out server-side)
                // before this client's own local timer ever got the chance to submit this itself.
                fishingCatchSubmitted = true;
                awaitingHeadbangFinish = false;
                clickClickClickSubmitted = true;
                minigamePresentation.handleMinigameEnded(e.payload, catchingUp, maxRounds, completedRounds);
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

    public void addChatMessage(String message)
    {
        clientThread.invokeLater(() -> client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
    }

    /** Plays {@code spotAnimId} (see net.runelite.api.gameval.SpotanimID) once at a fixed world
     * point -- no travel, no actor attached. The client API has no direct "spawn a stationary
     * graphic" call, so this uses the standard trick of a projectile whose source and target are
     * the same point. Always hops onto the client thread, so any caller off it (an event handler,
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

    /** Plays {@code spotAnimId} directly on {@code rsn}'s own in-game actor -- follows them if they
     * move, unlike triggerSpotAnimAtWorldPoint's fixed-point projectile trick: an Actor can just be
     * told to show a spotanim directly via Actor#createSpotAnim, no faked projectile needed.
     * {@code id} is just spotAnimId itself; nothing here needs more than one spotanim live on the
     * same actor at once. A no-op if {@code rsn} isn't currently a loaded/visible nearby actor.
     * Always hops onto the client thread, so any caller off it can call this directly. */
    public void triggerSpotAnimOnPlayer(int spotAnimId, String rsn, int height, int delayTicks)
    {
        if (rsn == null) return;
        clientThread.invoke(() ->
        {
            for (Player p : client.getPlayers())
            {
                if (p == null || p.getName() == null) continue;
                if (rsn.equalsIgnoreCase(Text.toJagexName(p.getName())))
                {
                    p.createSpotAnim(spotAnimId, spotAnimId, height, delayTicks);
                    return;
                }
            }
        });
    }

    public void triggerSpotAnimOnPlayer(int spotAnimId, String rsn, int height)
    {
        triggerSpotAnimOnPlayer(spotAnimId, rsn, height, 0);
    }

    /** Plays the Hot Potato explosion effect (see models/HotPotatoExplosionModel) at {@code rsn}'s
     * current location -- a snapshot at the moment it fires, not an actor-attached effect that
     * follows them the way triggerSpotAnimOnPlayer's own spotanim does, matching "the spot of the
     * explosion" rather than "on them" (they're eliminated either way, so there's nothing left to
     * keep following). A no-op if {@code rsn} isn't currently a loaded/visible nearby actor. Always
     * hops onto the client thread, so any caller off it can call this directly. */
    public void triggerHotPotatoExplosion(String rsn)
    {
        if (rsn == null) return;
        clientThread.invoke(() ->
        {
            for (Player p : client.getPlayers())
            {
                if (p == null || p.getName() == null) continue;
                if (rsn.equalsIgnoreCase(Text.toJagexName(p.getName())))
                {
                    hotPotatoExplosionModel.spawn(p.getWorldLocation());
                    return;
                }
            }
        });
    }

    public void refreshPanel()
    {
        if (panel != null) SwingUtilities.invokeLater(panel::refresh);
    }

    String localRsn()
    {
        if (client.getLocalPlayer() == null) return null;
        String name = client.getLocalPlayer().getName();
        return name != null ? Text.toJagexName(name) : null;
    }

    public void resetState()
    {
        // Leaving/disconnecting while board view is active shouldn't strand the player's camera
        // pointing straight down once they're back to whatever they were doing before -- restore
        // it the same way toggling the button off would. clientThread.invoke rather than a direct
        // call since resetState can run from a Swing button handler (leaveGame) as well as a
        // client-thread event subscriber, and camera setters require the client thread the same
        // way RuneLiteObject#setActive does.
        if (boardViewActive) clientThread.invoke(this::restoreCameraFromBoardView);
        if (eventSocket != null) eventSocket.stop();
        turnAnnounce.reset();
        turnSkippedAnnounce.reset();
        teleBlockCastAnnounce.reset();
        itemPresentation.reset();
        turnEffectGateUntil = 0;
        gameId = null; writeKey = null; playerToken = null;
        sessionManager.reset();
        standardCourseKey = null;
        phase = GamePhase.DISCONNECTED;
        courseBuilder.reset();
        mapShowing = false;
        currentTurnRsn = null; lastDiceRoll = null; pendingRoll = false; rollRequestSubmitted = false;
        awaitingSpinFinish = false;
        pendingTargetIndices = Collections.emptyList();
        pendingReachableIndices = Collections.emptyList();
        arrivalSubmitted = false; itemUsedThisTurn = false; goldenGnomePurchasedThisTurn = false; standingOnTrackedPositionCached = false;
        homeTeleportArrivalSubmitted = false;
        itemPlacementKey = null;
        itemTargetKey = null;
        minigamePresentation.reset();
        maxRounds = 0; completedRounds = 0;
        playerPositions.clear();
        startConfirmSubmitted = false;
        gameStartBanner.reset();
        ceremonyPresentation.reset();
        goldenGnomePresentation.reset();
        jadPresentation.reset();
        chanceSpacePresentation.reset();
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
    public String getJoinCode() { return sessionManager.getJoinCode(); }
    public String getHostRsn() { return sessionManager.getHostRsn(); }
    public boolean isHost() { return writeKey != null; }
    /** True once this game has been permanently locked to a Standard Course -- see
     * standardCourseKey's own field doc. Course-building tools (RunePartyPanel's own
     * courseToolsPanel) stay hidden for the rest of this game's life once this flips true. */
    public boolean isStandardCourseLocked() { return standardCourseKey != null; }
    public boolean isCoursePlacementMode() { return courseBuilder.isCoursePlacementMode(); }
    public CoursePreset getSelectedPreset() { return courseBuilder.getSelectedPreset(); }
    public int getPresetRotationSteps() { return courseBuilder.getPresetRotationSteps(); }
    public String getCurrentTurnRsn() { return currentTurnRsn; }
    public Integer getLastDiceRoll() { return lastDiceRoll; }
    public boolean isPendingRoll() { return pendingRoll; }
    public boolean isItemUsedThisTurn() { return itemUsedThisTurn; }
    public boolean isGoldenGnomePurchasedThisTurn() { return goldenGnomePurchasedThisTurn; }
    public List<Integer> getPendingTargetIndices() { return pendingTargetIndices; }
    public List<Integer> getPendingReachableIndices() { return pendingReachableIndices; }
    // Delegating facade -- MinigamePresentation owns the actual state. Every name/signature below
    // is unchanged, so no external caller (AnnouncementOverlay, RunePartyPanel, StatsOverlay)
    // needs to change.
    public boolean isMinigameActive() { return minigamePresentation.isActive(); }
    /** The board tile (pathIndex) {@code rsn} is currently standing at, per the last PLAYER_MOVED
     * seen for them -- 0 (START) if they haven't moved yet this game. See TileOverlay#
     * renderReturnToPositionArrow, the only consumer. */
    public int getPlayerPosition(String rsn)
    {
        if (rsn == null) return 0;
        Integer idx = playerPositions.get(rsn.toLowerCase(Locale.ROOT));
        return idx != null ? idx : 0;
    }
    public String getMinigameInstructions() { return minigamePresentation.getInstructions(); }
    public String getMinigameKey() { return minigamePresentation.getKey(); }
    public String getMinigameDisplayName() { return minigamePresentation.getDisplayName(); }
    public long getMinigameSpinnerStart() { return minigamePresentation.getMinigameSpinnerStart(); }
    public long getMinigameSpinnerUntil() { return minigamePresentation.getMinigameSpinnerUntil(); }
    public boolean isMinigameSpinnerSkippedForClient() { return minigamePresentation.isMinigameSpinnerSkippedForClient(); }

    /** Whether this client has actually seen the mini-game selection wheel settle on its result
     * yet (or skipped straight to "already known," via catch-up) -- the exact same instant
     * scheduleMinigameSpinner's own nested chat line already waits for, so the chosen mini-game's
     * name is never spoiled ahead of the wheel's own reveal. Any board-state change that's specific
     * to which mini-game got picked (e.g. Rainbow Rush recoloring the course, see TileOverlay)
     * should gate on this rather than isMinigameActive()/isRainbowRushActive() alone -- those flip
     * true the instant MINIGAME_STARTED lands, well before the wheel has actually spun to a stop. */
    public boolean isMinigameSelectionRevealed()
    {
        if (minigamePresentation.isMinigameSpinnerSkippedForClient()) return true;
        long start = minigamePresentation.getMinigameSpinnerStart();
        if (start == 0) return false;
        return System.currentTimeMillis() - start >= MINIGAME_SPINNER_SPIN_PHASE_MS;
    }

    // Delegating facade -- ItemPresentation owns the actual state. Every name/signature below is
    // unchanged, so no external caller (AnnouncementOverlay, TileOverlay) needs to change.
    public long getItemBannerUntil() { return itemPresentation.getItemBannerUntil(); }
    public long getItemSpinnerStart() { return itemPresentation.getItemSpinnerStart(); }
    public long getItemSpinnerUntil() { return itemPresentation.getItemSpinnerUntil(); }
    public String getItemGrantRsn() { return itemPresentation.getItemGrantRsn(); }
    public String getItemGrantKey() { return itemPresentation.getItemGrantKey(); }
    public long getItemGrantDescriptionUntil() { return itemPresentation.getItemGrantDescriptionUntil(); }
    public String getItemGrantDescriptionRsn() { return itemPresentation.getItemGrantDescriptionRsn(); }
    public String getItemGrantDescriptionKey() { return itemPresentation.getItemGrantDescriptionKey(); }
    public long getItemCapBlockedUntil() { return itemPresentation.getItemCapBlockedUntil(); }
    public String getItemCapBlockedRsn() { return itemPresentation.getItemCapBlockedRsn(); }
    public int getItemCapBlockedCap() { return itemPresentation.getItemCapBlockedCap(); }
    public long getItemUsedAnnounceUntil() { return itemPresentation.getItemUsedAnnounceUntil(); }
    public String getItemUsedAnnounceRsn() { return itemPresentation.getItemUsedAnnounceRsn(); }
    public String getItemUsedAnnounceItemKey() { return itemPresentation.getItemUsedAnnounceItemKey(); }
    public long getCoinTrapAnnounceUntil() { return itemPresentation.getCoinTrapAnnounceUntil(); }
    public String getCoinTrapAnnounceRsn() { return itemPresentation.getCoinTrapAnnounceRsn(); }
    public WorldPoint getCoinTrapTriggerPoint() { return itemPresentation.getCoinTrapTriggerPoint(); }
    public long getCoinTrapTriggerUntil() { return itemPresentation.getCoinTrapTriggerUntil(); }

    /** Every currently-live Coin Rush spawn, keyed by the server's own spawn id -- see
     * TileOverlay#updateCoinRushModels, the only consumer. */
    public Map<Integer, WorldPoint> getCoinRushSpawns() { return Collections.unmodifiableMap(minigamePresentation.coinRush().getSpawns()); }
    /** This round's live Coin Rush tally, lowercase rsn -> coins collected so far -- see
     * StatsOverlay's live scoreboard, the only consumer. */
    public Map<String, Integer> getCoinRushScores() { return Collections.unmodifiableMap(minigamePresentation.coinRush().getScores()); }
    public boolean isCoinRushActive() { return minigamePresentation.isKeyActive(COIN_RUSH_KEY); }
    /** When the current Coin Rush round's own clock runs out -- 0 if no round is active yet or the
     * round hasn't actually become playable. */
    public long getCoinRushEndsAt() { return minigamePresentation.coinRush().getEndsAt(); }

    /** Every currently-live Sandwich Rush ingredient spawn, keyed by the server's own spawn id --
     * see models/SandwichItemModel, the only consumer. */
    public Map<Integer, SandwichSpawn> getSandwichRushSpawns() { return Collections.unmodifiableMap(minigamePresentation.sandwichRush().getSpawns()); }
    /** The LOCAL player's own currently-held ingredient keys this round -- deliberately self-only
     * (see MinigamePresentation's own field doc) -- see SandwichRushHudOverlay, the only
     * consumer. */
    public Set<String> getSandwichHeld() { return Collections.unmodifiableSet(minigamePresentation.sandwichRush().getHeld()); }
    public int getSandwichCount() { return minigamePresentation.sandwichRush().getCount(); }
    public boolean isSandwichRushActive() { return minigamePresentation.isKeyActive(SANDWICH_RUSH_KEY); }
    /** When the current Sandwich Rush round's own clock runs out -- 0 if no round is active yet
     * or the round hasn't actually become playable. */
    public long getSandwichRushEndsAt() { return minigamePresentation.sandwichRush().getEndsAt(); }

    public boolean isFishingContestActive() { return minigamePresentation.isKeyActive(FISHING_CONTEST_KEY); }
    /** When the current Fishing Contest round's own local catch-timer should stop -- 0 if no
     * round is active yet or the round hasn't actually become playable. */
    public long getFishingContestEndsAt() { return minigamePresentation.fishingContest().getEndsAt(); }
    /** This round's own local catch counts so far -- see FishingCatchOverlay, the only consumer.
     * Client-local only -- nobody but the local player ever sees these. */
    public int getShrimpCount() { return shrimpCount; }
    public int getAnchovyCount() { return anchovyCount; }

    public boolean isClickClickClickActive() { return minigamePresentation.isKeyActive(CLICK_CLICK_CLICK_KEY); }
    /** When the current Click, Click, Click round's own local click-timer should stop -- 0 if no
     * round is active yet or the round hasn't actually become playable. */
    public long getClickClickClickEndsAt() { return minigamePresentation.clickClickClick().getEndsAt(); }
    /** This round's own unique-tile-click count so far -- see ClickClickClickOverlay, the only
     * consumer. Client-local only -- nobody but the local player ever sees this. */
    public int getClickClickClickUniqueTileCount() { return clickClickClickTiles.size(); }

    public boolean isHotPotatoActive() { return minigamePresentation.isKeyActive(HOT_POTATO_KEY); }
    /** The current holder's rsn, or null before the round's own initial random assignment lands --
     * see HotPotatoOverlay and PlayerOverlay#drawToken, the two consumers. */
    public String getHotPotatoHolder() { return minigamePresentation.hotPotato().getHolder(); }
    /** When the current Hot Potato round's own clock runs out -- 0 if no round is active yet or the
     * round hasn't actually become playable. */
    public long getHotPotatoEndsAt() { return minigamePresentation.hotPotato().getEndsAt(); }
    /** Lowercase rsns eliminated for the rest of this Hot Potato round -- see PlayerOverlay#
     * drawToken, the only consumer. */
    public Set<String> getHotPotatoEliminatedRsns() { return minigamePresentation.hotPotato().getEliminatedRsns(); }

    public boolean isDanceDanceRuneScapeActive() { return minigamePresentation.isKeyActive(DANCE_DANCE_RUNESCAPE_KEY); }
    /** When the current Dance, Dance, RuneScape round's own clock runs out -- 0 if no round is
     * active yet or the round hasn't actually begun (see DanceDanceRuneScapePresentation's own
     * onTick doc for why "begun," not just "playable," matters here). */
    public long getDanceDanceRuneScapeEndsAt() { return minigamePresentation.danceDanceRuneScape().getEndsAt(); }
    /** Every tile adjacent to the dance floor's anchor currently lit -- empty before the first
     * beat, more than one entry for a chord -- see DanceDanceRuneScapeOverlay, the only consumer. */
    public Set<DanceDanceRuneScapePresentation.Direction> getDanceDanceRuneScapeHighlightedDirections() { return minigamePresentation.danceDanceRuneScape().getHighlightedDirections(); }
    /** When each direction was last captured (System.currentTimeMillis()), for
     * DanceDanceRuneScapeOverlay's own brief flash -- see that class's FLASH_DURATION_MS. */
    public Map<DanceDanceRuneScapePresentation.Direction, Long> getDanceDanceRuneScapeFlashStartTimes() { return minigamePresentation.danceDanceRuneScape().getFlashStartTimes(); }
    /** The local player's own running tally this round -- see DanceDanceRuneScapeHudOverlay, the
     * only consumer. Client-local only -- nobody but the local player ever sees this. */
    public int getDanceDanceRuneScapeScore() { return minigamePresentation.danceDanceRuneScape().getScore(); }

    public boolean isRainbowRushActive() { return minigamePresentation.isKeyActive(RAINBOW_RUSH_KEY); }
    /** Whether the local player has personally stood on the course tile at {@code pathIndex} yet
     * this round -- see TileOverlay#renderRainbowRushTile, the only consumer: outline-only until
     * this flips true, filled solid after. */
    public boolean isRainbowRushTileVisited(int pathIndex) { return minigamePresentation.rainbowRush().isVisited(pathIndex); }
    /** The local player's own running count of distinct course tiles visited this round. */
    public int getRainbowRushVisitedCount() { return minigamePresentation.rainbowRush().getVisitedCount(); }
    /** When Rainbow Rush's own backup ceiling kicks in if nobody's finished by then -- 0 if no
     * round is active yet. Not a normal win condition, see RAINBOW_RUSH_KEY's own doc. */
    public long getRainbowRushEndsAt() { return minigamePresentation.rainbowRush().getEndsAt(); }
    /** When MINIGAME_ROUND_BEGIN actually landed for this round -- 0 if the round hasn't begun yet.
     * See AnnouncementOverlay#renderRainbowRushTrafficLight, the only consumer: the traffic light
     * sequence is timed purely off this one client-local timestamp. */
    public long getRainbowRushRoundStartAt() { return minigamePresentation.rainbowRush().getRoundStartAt(); }

    public boolean isTurfWarsActive() { return minigamePresentation.isKeyActive(TURF_WARS_KEY); }
    /** This round's own live tile tally, keyed by whatever color hex each tile is currently
     * claimed in (2 keys for an even-count 2-team round, up to 8 for an odd-count free-for-all),
     * tallied fresh from TileReducer's own already-broadcast TURF_WARS_TILE snapshot -- see
     * TurfWarsScoreOverlay (the live scoreboard) and MinigamePresentation#triggerTurfWarsConfetti
     * (the end-of-round winner), the two consumers. There's no dedicated score event at all -- a
     * claim is just an ordinary tiles_marked update, so the board's own current colors already
     * are the score. */
    public Map<String, Integer> getTurfWarsTileCounts()
    {
        Map<String, Integer> counts = new HashMap<>();
        for (TileReducer.TileEntry entry : tileReducer.snapshot())
        {
            if (!"TURF_WARS_TILE".equals(entry.tileType) || entry.color == null) continue;
            counts.merge(entry.color.toUpperCase(Locale.ROOT), 1, Integer::sum);
        }
        return counts;
    }
    /** The color hex `rsn` is currently assigned for Turf Wars, or null if they're not on a team
     * right now (no Turf Wars round active, or the assignment hasn't landed yet this round) -- see
     * PlayerOverlay, which recolors every seated player's own outline/token this way, not just the
     * local player's own. */
    public String getTurfWarsColorHex(String rsn) { return minigamePresentation.turfWars().getPlayerColor(rsn); }
    /** {@link #getTurfWarsColorHex(String)} decoded to an AWT {@link Color}, or null under the
     * same conditions that returns null. */
    public Color getPlayerTeamColor(String rsn)
    {
        String hex = getTurfWarsColorHex(rsn);
        if (hex == null) return null;
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return null; }
    }
    /** When the round's own fixed-duration clock runs out -- 0 if no round is active yet or the
     * round hasn't actually become playable. */
    public long getTurfWarsEndsAt() { return minigamePresentation.turfWars().getEndsAt(); }

    public boolean isJaddyActive() { return minigamePresentation.isKeyActive(JADDY_KEY); }
    /** The color hex of whichever Who's Your Jaddy? zone tile {@code pos} currently sits on, or
     * null if {@code pos} isn't on either zone right now (no Jaddy round active, or the position is
     * off both zones) -- a live board lookup, same "the board's own current state already is the
     * answer" shape getTurfWarsTileCounts already uses, just per-point instead of tallied. Purely
     * position-driven, not locked in at arrival -- a player can walk from one zone to the other at
     * any point before the duel resolves and this (and the eventual payout, see
     * minigames/whos_your_jaddy.py) both follow wherever they're actually standing. See
     * PlayerOverlay, which recolors a seated player's own outline/token this way the instant their
     * real WorldLocation lands inside either zone. */
    public String getJaddyZoneColorHex(WorldPoint pos)
    {
        if (pos == null) return null;
        for (TileReducer.TileEntry entry : tileReducer.snapshot())
        {
            if ("JADDY_TILE".equals(entry.tileType) && entry.color != null && pos.equals(entry.point))
            {
                return entry.color;
            }
        }
        return null;
    }
    /** {@link #getJaddyZoneColorHex(WorldPoint)} decoded to an AWT {@link Color}, or null under the
     * same conditions that returns null. */
    public Color getJaddyZoneColor(WorldPoint pos)
    {
        String hex = getJaddyZoneColorHex(pos);
        if (hex == null) return null;
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return null; }
    }
    /** {@link #getJaddyZoneColorHex(WorldPoint)} for the LOCAL player's own current position --
     * used only to snapshot which side (if any) they'd picked at the exact instant a duel resolves
     * (see MinigamePresentation#triggerJaddyResolvedBanner), so the "Your team's Jad won!" reveal
     * reflects where they actually were then, not wherever they've wandered to by the time the
     * banner itself renders. Reads lastKnownLocalPosition (cached once per tick from onGameTick,
     * the client thread) rather than calling Player#getWorldLocation() directly -- this is called
     * from handleEvent, which runs on EventSocket's own WebSocket callback thread, and that call
     * asserts it's never invoked off the client thread. See lastKnownLocalPosition's own field doc
     * -- this exact call, from this exact call site, is the bug that field was added to fix. */
    public String getLocalJaddyZoneColorHex()
    {
        return getJaddyZoneColorHex(lastKnownLocalPosition);
    }

    public String getTrueOrFalseQuestion() { return minigamePresentation.trueOrFalse().getQuestion(); }
    public int getTrueOrFalseRoundNumber() { return minigamePresentation.trueOrFalse().getRoundNumber(); }
    /** Who's answered the *current* round so far -- see renderTrueOrFalseQuestion's own
     * "Ready screen"-style tally, the only consumer. */
    public Set<String> getTrueOrFalseAnsweredRsns() { return Collections.unmodifiableSet(minigamePresentation.trueOrFalse().getAnsweredRsns()); }
    public Boolean getTrueOrFalseMyAnswer() { return minigamePresentation.trueOrFalse().getMyAnswer(); }
    /** When the current True or False round's reading period ends and its answer countdown starts
     * ticking -- 0 if no round is currently open. renderTrueOrFalseQuestion hides the countdown
     * number until this passes. */
    public long getTrueOrFalseAnswerWindowStartsAt() { return minigamePresentation.trueOrFalse().getAnswerWindowStartsAt(); }
    /** When the current True or False round's own clock runs out -- 0 if no round is currently
     * open. */
    public long getTrueOrFalseRoundEndsAt() { return minigamePresentation.trueOrFalse().getRoundEndsAt(); }
    public Boolean getTrueOrFalseLastCorrectAnswer() { return minigamePresentation.trueOrFalse().getLastCorrectAnswer(); }
    public List<TrueOrFalseResult> getTrueOrFalseLastResults() { return minigamePresentation.trueOrFalse().getLastResults(); }
    public long getTrueOrFalseRevealUntil() { return minigamePresentation.trueOrFalse().getRevealUntil(); }

    public String getItemPlacementKey() { return itemPlacementKey; }
    public String getItemTargetKey() { return itemTargetKey; }
    public Set<String> getMinigameReadyRsns() { return minigamePresentation.getMinigameReadyRsns(); }
    public boolean isMinigameCountdownStarted() { return minigamePresentation.isCountdownStarted(); }
    public boolean isMinigameCountdownSkippedForClient() { return minigamePresentation.isCountdownSkippedForClient(); }
    public long getMinigameCountdownBannerUntil() { return minigamePresentation.getCountdownBannerUntil(); }
    /** Whether MINIGAME_ROUND_BEGIN has genuinely landed for the current mini-game -- unlike
     * isMinigamePlayable() (a fixed local timer off MINIGAME_COUNTDOWN_STARTED), this is a real
     * server signal, true for however long or short the round actually took to begin. See
     * AnnouncementOverlay#renderArenaGatherMessage, the only current reader. */
    public boolean isMinigameRoundBegun() { return minigamePresentation.isRoundBegun(); }
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
    public String getTurnSkippedRsn() { return turnSkippedAnnounce.payload; }
    public long getTurnSkippedUntil() { return turnSkippedAnnounce.until; }
    public String getTeleBlockCastCasterRsn() { return teleBlockCastAnnounce.payload != null ? teleBlockCastAnnounce.payload.casterRsn : null; }
    public String getTeleBlockCastTargetRsn() { return teleBlockCastAnnounce.payload != null ? teleBlockCastAnnounce.payload.targetRsn : null; }
    public long getTeleBlockCastUntil() { return teleBlockCastAnnounce.until; }
    public long getWelcomeBannerUntil() { return sessionManager.getWelcomeBannerUntil(); }
    public long getMinigameBannerUntil() { return minigamePresentation.getMinigameBannerUntil(); }
    public long getMinigameOverBannerUntil() { return minigamePresentation.getMinigameOverBannerUntil(); }
    public long getMinigameScoreBannerUntil() { return minigamePresentation.getMinigameScoreBannerUntil(); }
    public List<MinigameScore> getMinigameScores() { return minigamePresentation.getMinigameScores(); }
    public long getGameStartBannerUntil() { return gameStartBanner.until; }
    public long getRoundCompleteBannerUntil() { return minigamePresentation.getRoundCompleteBannerUntil(); }
    public int getRoundCompleteRoundNumber() { return minigamePresentation.getRoundCompleteRoundNumber(); }
    public long getMinigameRewardsBannerUntil() { return minigamePresentation.getMinigameRewardsBannerUntil(); }
    public List<MinigameReward> getMinigameRewards() { return minigamePresentation.getMinigameRewards(); }
    public long getTeamAssignedBannerUntil() { return minigamePresentation.turfWars().getTeamAssignedBannerUntil(); }
    public String getTeamAssignedBannerTeam() { return minigamePresentation.turfWars().getTeamAssignedBannerTeam(); }
    public long getTurfWarsConfettiUntil() { return minigamePresentation.turfWars().getConfettiUntil(); }
    public Color getTurfWarsConfettiColor() { return minigamePresentation.turfWars().getConfettiColor(); }
    public long getJaddyResolvedBannerUntil() { return minigamePresentation.jaddy().getResolvedBannerUntil(); }
    public String getJaddyResolvedWinningColor() { return minigamePresentation.jaddy().getResolvedWinningColor(); }
    public String getJaddyResolvedLocalZoneColor() { return minigamePresentation.jaddy().getResolvedLocalZoneColor(); }
    // Delegating facade -- CeremonyPresentation owns the actual state. Every name/signature below
    // is unchanged, so no external caller (AnnouncementOverlay, ConfettiOverlay) needs to change.
    public List<RosterReducer.RosterEntry> getGameOverStandings() { return ceremonyPresentation.getGameOverStandings(); }
    public long getGameOverBannerUntil() { return ceremonyPresentation.getGameOverBannerUntil(); }
    public long getWinnerIntroBannerUntil() { return ceremonyPresentation.getWinnerIntroBannerUntil(); }
    public long getPlaceRevealUntil() { return ceremonyPresentation.getPlaceRevealUntil(); }
    public String getPlaceRevealRsn() { return ceremonyPresentation.getPlaceRevealRsn(); }
    public int getPlaceRevealRank() { return ceremonyPresentation.getPlaceRevealRank(); }
    public int getPlaceRevealCoins() { return ceremonyPresentation.getPlaceRevealCoins(); }
    public int getPlaceRevealGoldenGnomes() { return ceremonyPresentation.getPlaceRevealGoldenGnomes(); }
    public long getWinnerSuspenseUntil() { return ceremonyPresentation.getWinnerSuspenseUntil(); }
    public long getWinnerRevealUntil() { return ceremonyPresentation.getWinnerRevealUntil(); }
    public String getWinnerRsn() { return ceremonyPresentation.getWinnerRsn(); }
    public long getConfettiUntil() { return ceremonyPresentation.getConfettiUntil(); }
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
    // Delegating facade -- GoldenGnomePresentation owns the actual state. Every name/signature
    // below is unchanged, so no external caller (AnnouncementOverlay, PlayerOverlay, TileOverlay)
    // needs to change.
    public String getGoldenGnomeOutcome() { return goldenGnomePresentation.getOutcome(); }
    public String getGoldenGnomeOutcomeRsn() { return goldenGnomePresentation.getOutcomeRsn(); }
    public long getGoldenGnomeOutcomeBannerUntil() { return goldenGnomePresentation.getOutcomeBannerUntil(); }
    public String getGoldenGnomePopupRsn() { return goldenGnomePresentation.getPopupRsn(); }
    public int getGoldenGnomePopupNewTotal() { return goldenGnomePresentation.getPopupNewTotal(); }
    public int getGoldenGnomePopupDelta() { return goldenGnomePresentation.getPopupDelta(); }
    public long getGoldenGnomePopupStart() { return goldenGnomePresentation.getPopupStart(); }
    public long getGoldenGnomePopupUntil() { return goldenGnomePresentation.getPopupUntil(); }
    public WorldPoint getGoldenGnomeMoveOldPoint() { return goldenGnomePresentation.getMoveOldPoint(); }
    public long getGoldenGnomeMoveHideOldAt() { return goldenGnomePresentation.getMoveHideOldAt(); }
    public WorldPoint getGoldenGnomeMoveNewPoint() { return goldenGnomePresentation.getMoveNewPoint(); }
    public long getGoldenGnomeMoveShowNewAt() { return goldenGnomePresentation.getMoveShowNewAt(); }

    /** Lets ChanceSpacePresentation's own deferred reveal show the Golden Gnome count popup at a
     * time of its own choosing -- see GoldenGnomePresentation#showCountPopup's own doc. */
    public void showGoldenGnomeCountPopup(String rsn, int newTotal, int delta)
    {
        goldenGnomePresentation.showCountPopup(rsn, newTotal, delta);
    }

    public long getChanceSpaceTitleUntil() { return chanceSpacePresentation.getTitleUntil(); }
    public long getChanceSpaceIconsStart() { return chanceSpacePresentation.getIconsStart(); }
    public long getChanceSpaceIconsUntil() { return chanceSpacePresentation.getIconsUntil(); }
    public String getChanceSpaceIconsLeftRsn() { return chanceSpacePresentation.getIconsLeftRsn(); }
    public String getChanceSpaceIconsRightRsn() { return chanceSpacePresentation.getIconsRightRsn(); }
    public String getChanceSpaceIconsOutcomeType() { return chanceSpacePresentation.getIconsOutcomeType(); }
    public boolean isChanceSpaceIconsGnomeTransferred() { return chanceSpacePresentation.isIconsGnomeTransferred(); }
    public String getChanceSpaceIconsArrowDirection() { return chanceSpacePresentation.getIconsArrowDirection(); }
    public long[] getChanceSpaceIconsSlotDelayMs() { return chanceSpacePresentation.getIconsSlotDelayMs(); }
    public String[] getChanceSpaceAnnouncementLines() { return chanceSpacePresentation.getAnnouncementLines(); }

    public String getJadEncounterRsn() { return jadPresentation.getEncounterRsn(); }
    public long getJadAwakenedAt() { return jadPresentation.getAwakenedAt(); }
    public long getJadRevealAt() { return jadPresentation.getRevealAt(); }
    public boolean isJadSmashTriggered() { return jadPresentation.isSmashTriggered(); }
    public String getJadOutcome() { return jadPresentation.getOutcome(); }
    public String getJadOutcomeRsn() { return jadPresentation.getOutcomeRsn(); }
    public long getJadOutcomeBannerUntil() { return jadPresentation.getOutcomeBannerUntil(); }
}
