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

    public static final String COIN_RUSH_COLLECTED = "COIN_RUSH_COLLECTED";
    public static final String COIN_RUSH_SPAWN = "COIN_RUSH_SPAWN";
    public static final String COIN_TRAP_TRIGGERED = "COIN_TRAP_TRIGGERED";
    public static final String COINS_CHANGED = "COINS_CHANGED";
    public static final String DICE_ROLLED = "DICE_ROLLED";
    public static final String GAME_ENDED = "GAME_ENDED";
    public static final String GAME_STARTED = "GAME_STARTED";
    public static final String GOLDEN_GNOME_MOVED = "GOLDEN_GNOME_MOVED";
    public static final String GOLDEN_GNOME_OFFERED = "GOLDEN_GNOME_OFFERED";
    public static final String GOLDEN_GNOME_OFFER_RESOLVED = "GOLDEN_GNOME_OFFER_RESOLVED";
    public static final String GOLDEN_GNOME_PURCHASED = "GOLDEN_GNOME_PURCHASED";
    public static final String ITEM_CAP_BLOCKED = "ITEM_CAP_BLOCKED";
    public static final String ITEM_GRANTED = "ITEM_GRANTED";
    public static final String ITEM_USED = "ITEM_USED";
    public static final String MINIGAME_COUNTDOWN_STARTED = "MINIGAME_COUNTDOWN_STARTED";
    public static final String MINIGAME_ENDED = "MINIGAME_ENDED";
    public static final String MINIGAME_PLAYER_READY = "MINIGAME_PLAYER_READY";
    public static final String MINIGAME_RESULT_SUBMITTED = "MINIGAME_RESULT_SUBMITTED";
    public static final String MINIGAME_STARTED = "MINIGAME_STARTED";
    public static final String PLAYER_JOINED = "PLAYER_JOINED";
    public static final String PLAYER_LEFT = "PLAYER_LEFT";
    public static final String PLAYER_MOVED = "PLAYER_MOVED";
    public static final String PLAYER_READY = "PLAYER_READY";
    public static final String ROLE_ASSIGNED = "ROLE_ASSIGNED";
    public static final String ROLL_BONUS_GRANTED = "ROLL_BONUS_GRANTED";
    public static final String TILE_EFFECT = "TILE_EFFECT";
    public static final String TILE_MARKED = "TILE_MARKED";
    public static final String TILE_UNMARKED = "TILE_UNMARKED";
    public static final String TILES_MARKED = "TILES_MARKED";
    public static final String TILES_UNMARKED = "TILES_UNMARKED";
    public static final String TRUE_OR_FALSE_ANSWERED = "TRUE_OR_FALSE_ANSWERED";
    public static final String TRUE_OR_FALSE_ROUND_ENDED = "TRUE_OR_FALSE_ROUND_ENDED";
    public static final String TRUE_OR_FALSE_ROUND_STARTED = "TRUE_OR_FALSE_ROUND_STARTED";
    public static final String TURN_STARTED = "TURN_STARTED";
}
