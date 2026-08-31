package gay.runescape.runeparty;

/** One constant per event type string the server ever sends -- mirrors the server's own
 * events.EventType exactly (same names). Never compare against a raw string literal directly;
 * always reference it from here, so a typo or a rename on either side fails to compile instead of
 * silently falling through to a switch's default case. See ARCHITECTURE_REVIEW.md's X1 finding,
 * and check_event_parity.py, which asserts this set matches the server's own one-for-one. */
public final class Events
{
    private Events() {}

    /** Transport-level envelope wrapping a coalesced batch of real events (see EventSocket) --
     * not itself a game event, but the same class of literal-that-should-be-a-constant. */
    public static final String EVENTS_BATCH = "EVENTS_BATCH";

    /** Transport-level sentinel the server sends once it's finished flushing a connection's own
     * backlog burst (see the server's game_events_ws) -- marks the point EventSocket flips from
     * replaying missed history to genuinely live events (see EventSocket#onMessage). Not itself a
     * game event, same as EVENTS_BATCH above. */
    public static final String CAUGHT_UP = "CAUGHT_UP";

    /** Server-only bookkeeping (see the server's minigames/arena.py hazard loop) fired when a
     * player is eliminated -- standing on a tile the instant it reaches red, or stepping off the
     * arena after having been on it. Never dispatched on by any handler here; declared solely so
     * check_event_parity.py's one-for-one assertion holds -- an eliminated player just doesn't get
     * paid at the end, there's no other visible consequence this pass. */
    public static final String ARENA_PLAYER_ELIMINATED = "ARENA_PLAYER_ELIMINATED";
    public static final String COIN_RUSH_COLLECTED = "COIN_RUSH_COLLECTED";
    public static final String COIN_RUSH_SPAWN = "COIN_RUSH_SPAWN";
    public static final String COIN_TRAP_TRIGGERED = "COIN_TRAP_TRIGGERED";
    public static final String COINS_CHANGED = "COINS_CHANGED";
    public static final String DICE_ROLLED = "DICE_ROLLED";
    /** Echo of the client's own submit-fishing-catch call (see ApiClient#submitFishingCatch) --
     * the client already knows its own final tally the instant it sends it, so nothing here needs
     * to react to the echo. Declared solely so check_event_parity.py's one-for-one assertion
     * holds, same treatment as MINIGAME_BOARD_SWAPPED/ARENA_PLAYER_ELIMINATED above. */
    public static final String FISHING_CATCH_SUBMITTED = "FISHING_CATCH_SUBMITTED";
    public static final String GAME_ENDED = "GAME_ENDED";
    public static final String GAME_STARTED = "GAME_STARTED";
    public static final String GOLDEN_GNOME_LOST = "GOLDEN_GNOME_LOST";
    public static final String GOLDEN_GNOME_MOVED = "GOLDEN_GNOME_MOVED";
    public static final String GOLDEN_GNOME_PURCHASED = "GOLDEN_GNOME_PURCHASED";
    public static final String HOME_TELEPORT_ARMED = "HOME_TELEPORT_ARMED";
    public static final String HOME_TELEPORT_ARRIVED = "HOME_TELEPORT_ARRIVED";
    public static final String ITEM_CAP_BLOCKED = "ITEM_CAP_BLOCKED";
    public static final String ITEM_GRANTED = "ITEM_GRANTED";
    public static final String ITEM_USED = "ITEM_USED";
    public static final String JAD_AWAKENED = "JAD_AWAKENED";
    public static final String JAD_DISMISSED = "JAD_DISMISSED";
    public static final String JAD_SMASH_TRIGGERED = "JAD_SMASH_TRIGGERED";
    /** Server-only bookkeeping (see the server's MinigameContext.swap_board) fired alongside the
     * TILES_UNMARKED/TILES_MARKED pair a board swap actually renders as -- purely so the server can
     * restore the real course later. Never dispatched on by any handler here; declared solely so
     * check_event_parity.py's one-for-one assertion holds. */
    public static final String MINIGAME_BOARD_SWAPPED = "MINIGAME_BOARD_SWAPPED";
    public static final String MINIGAME_COUNTDOWN_STARTED = "MINIGAME_COUNTDOWN_STARTED";
    public static final String MINIGAME_ENDED = "MINIGAME_ENDED";
    public static final String MINIGAME_PLAYER_READY = "MINIGAME_PLAYER_READY";
    public static final String MINIGAME_RESULT_SUBMITTED = "MINIGAME_RESULT_SUBMITTED";
    public static final String MINIGAME_ROUND_BEGIN = "MINIGAME_ROUND_BEGIN";
    public static final String MINIGAME_STARTED = "MINIGAME_STARTED";
    public static final String PLAYER_JOINED = "PLAYER_JOINED";
    public static final String PLAYER_LEFT = "PLAYER_LEFT";
    public static final String PLAYER_MOVED = "PLAYER_MOVED";
    public static final String PLAYER_READY = "PLAYER_READY";
    public static final String ROLE_ASSIGNED = "ROLE_ASSIGNED";
    public static final String ROLL_BONUS_GRANTED = "ROLL_BONUS_GRANTED";
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
