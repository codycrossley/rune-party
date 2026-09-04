package gay.runescape.runeparty;

/** One constant per event type string the server ever sends. Never compare against a raw string
 * literal directly; always reference it from here, so a typo or rename fails to compile instead
 * of silently falling through to a switch's default case. */
public final class Events
{
    private Events() {}

    /** Transport-level envelope wrapping a coalesced batch of real events (see EventSocket) --
     * not itself a game event, but the same class of literal-that-should-be-a-constant. */
    public static final String EVENTS_BATCH = "EVENTS_BATCH";

    /** Transport-level sentinel the server sends once it's finished flushing a connection's
     * backlog burst -- marks the point EventSocket flips from replaying missed history to
     * genuinely live events. Not itself a game event, same as EVENTS_BATCH above. */
    public static final String CAUGHT_UP = "CAUGHT_UP";

    /** Server-only bookkeeping fired when a player is eliminated from the Arena. Never dispatched
     * on by any handler here -- an eliminated player just doesn't get paid at the end. */
    public static final String ARENA_PLAYER_ELIMINATED = "ARENA_PLAYER_ELIMINATED";
    public static final String CHANCE_SPACE_TRIGGERED = "CHANCE_SPACE_TRIGGERED";
    /** Echo of the client's own submit-click-click-click-result call -- same "already knows its
     * own final tally" reasoning as FISHING_CATCH_SUBMITTED's own doc just below. */
    public static final String CLICK_CLICK_CLICK_RESULT_SUBMITTED = "CLICK_CLICK_CLICK_RESULT_SUBMITTED";
    public static final String COIN_RUSH_COLLECTED = "COIN_RUSH_COLLECTED";
    public static final String COIN_RUSH_SPAWN = "COIN_RUSH_SPAWN";
    public static final String COIN_TRAP_TRIGGERED = "COIN_TRAP_TRIGGERED";
    public static final String COINS_CHANGED = "COINS_CHANGED";
    public static final String DICE_ROLLED = "DICE_ROLLED";
    /** Echo of the client's own submit-fishing-catch call -- the client already knows its own
     * final tally the instant it sends it, so nothing here needs to react to the echo. */
    public static final String FISHING_CATCH_SUBMITTED = "FISHING_CATCH_SUBMITTED";
    public static final String GAME_ENDED = "GAME_ENDED";
    public static final String GAME_STARTED = "GAME_STARTED";
    public static final String GOLDEN_GNOME_LOST = "GOLDEN_GNOME_LOST";
    public static final String GOLDEN_GNOME_MOVED = "GOLDEN_GNOME_MOVED";
    public static final String GOLDEN_GNOME_PURCHASED = "GOLDEN_GNOME_PURCHASED";
    public static final String GOLDEN_GNOME_WON = "GOLDEN_GNOME_WON";
    public static final String HOME_TELEPORT_ARMED = "HOME_TELEPORT_ARMED";
    public static final String HOME_TELEPORT_ARRIVED = "HOME_TELEPORT_ARRIVED";
    public static final String ITEM_CAP_BLOCKED = "ITEM_CAP_BLOCKED";
    public static final String ITEM_GRANTED = "ITEM_GRANTED";
    public static final String ITEM_USED = "ITEM_USED";
    public static final String JAD_AWAKENED = "JAD_AWAKENED";
    public static final String JAD_DISMISSED = "JAD_DISMISSED";
    public static final String JAD_SMASH_TRIGGERED = "JAD_SMASH_TRIGGERED";
    public static final String JADDY_ATTACK_TRIGGERED = "JADDY_ATTACK_TRIGGERED";
    public static final String JADDY_DUEL_RESOLVED = "JADDY_DUEL_RESOLVED";
    /** Server-only bookkeeping fired alongside the TILES_UNMARKED/TILES_MARKED pair a board swap
     * actually renders as, so the server can restore the real course later. Never dispatched on by
     * any handler here. */
    public static final String MINIGAME_BOARD_SWAPPED = "MINIGAME_BOARD_SWAPPED";
    public static final String MINIGAME_COUNTDOWN_STARTED = "MINIGAME_COUNTDOWN_STARTED";
    public static final String MINIGAME_ENDED = "MINIGAME_ENDED";
    public static final String MINIGAME_PLAYER_READY = "MINIGAME_PLAYER_READY";
    public static final String MINIGAME_RESULT_SUBMITTED = "MINIGAME_RESULT_SUBMITTED";
    public static final String MINIGAME_ROUND_BEGIN = "MINIGAME_ROUND_BEGIN";
    public static final String MINIGAME_STARTED = "MINIGAME_STARTED";
    public static final String MINIGAME_TEAMS_ASSIGNED = "MINIGAME_TEAMS_ASSIGNED";
    public static final String PLAYER_JOINED = "PLAYER_JOINED";
    public static final String PLAYER_LEFT = "PLAYER_LEFT";
    public static final String PLAYER_MOVED = "PLAYER_MOVED";
    public static final String PLAYER_READY = "PLAYER_READY";
    public static final String ROLE_ASSIGNED = "ROLE_ASSIGNED";
    public static final String ROLL_BONUS_GRANTED = "ROLL_BONUS_GRANTED";
    public static final String SANDWICH_RUSH_ITEM_COLLECTED = "SANDWICH_RUSH_ITEM_COLLECTED";
    public static final String SANDWICH_RUSH_ITEM_SPAWNED = "SANDWICH_RUSH_ITEM_SPAWNED";
    public static final String STANDARD_COURSE_LOCKED = "STANDARD_COURSE_LOCKED";
    public static final String TELE_BLOCK_APPLIED = "TELE_BLOCK_APPLIED";
    public static final String TILE_EFFECT = "TILE_EFFECT";
    public static final String TILE_MARKED = "TILE_MARKED";
    public static final String TILE_UNMARKED = "TILE_UNMARKED";
    public static final String TILES_MARKED = "TILES_MARKED";
    public static final String TILES_UNMARKED = "TILES_UNMARKED";
    public static final String TRUE_OR_FALSE_ANSWERED = "TRUE_OR_FALSE_ANSWERED";
    public static final String TRUE_OR_FALSE_ROUND_ENDED = "TRUE_OR_FALSE_ROUND_ENDED";
    public static final String TRUE_OR_FALSE_ROUND_STARTED = "TRUE_OR_FALSE_ROUND_STARTED";
    public static final String TURN_SKIPPED = "TURN_SKIPPED";
    public static final String TURN_STARTED = "TURN_STARTED";
}
