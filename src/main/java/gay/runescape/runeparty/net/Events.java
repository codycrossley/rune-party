package gay.runescape.runeparty.net;

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

    /** Server-only echo of this client's (or another seated player's) own one-shot
     * confirm-arena-arrival call -- never dispatched on here, the server's own arrival gate is what
     * actually reacts to it (see minigames/arena.py). Same treatment BRUTUS_ARRIVAL_CONFIRMED
     * already gets below, for the same reason: each client tracks its own arrival locally, it never
     * needs to hear the broadcast of anyone else's. */
    public static final String ARENA_ARRIVAL_CONFIRMED = "ARENA_ARRIVAL_CONFIRMED";
    /** Fired when a player is eliminated from the Arena -- either self-detected standing on a tile
     * the instant it turns permanently dead, or self-detected off the grid after genuinely having
     * been on it (see ArenaPresentation#onTick, the client-side check that fires the confirming
     * confirm-arena-elimination call in the first place). Dispatched on directly in
     * RunePartyPlugin's own event switch for a one-shot cosmetic reveal (Voidwaker spec spotanim +
     * chat message) -- no fold into any tracked client state, since ArenaFireModel's already-live
     * tile coloring shows the elimination visually on its own; an eliminated player otherwise just
     * doesn't get paid at the end. */
    public static final String ARENA_PLAYER_ELIMINATED = "ARENA_PLAYER_ELIMINATED";
    /** Server-only echo of this client's (or another seated player's) own one-shot
     * confirm-brutus-arrival call -- never dispatched on here, the server's own arrival gate is
     * what actually reacts to it (see minigames/brutus_attack.py). */
    public static final String BRUTUS_ARRIVAL_CONFIRMED = "BRUTUS_ARRIVAL_CONFIRMED";
    /** Resets BrutusAttackPresentation's own one-shot "have I already confirmed arrival/reported a
     * dash this round" guards -- fires right before each of Brutus Attack's 3 rounds begins
     * gathering (round 1's fires before the arena's own board swap even lands, see
     * minigames/brutus_attack.py's own doc), so a client re-confirms fresh every round even if it
     * never physically moved. */
    public static final String BRUTUS_ARRIVAL_PENDING = "BRUTUS_ARRIVAL_PENDING";
    /** Fires once whenever a Brutus Attack round's own dash window resolves with nobody caught --
     * see BrutusAttackPresentation, which folds this into a "MISS!" flash (the counterpart to
     * BRUTUS_PLAYER_ELIMINATED's own "HIT!" flash). */
    public static final String BRUTUS_DASH_MISSED = "BRUTUS_DASH_MISSED";
    /** Server-only echo of Brutus's own one-shot confirm-brutus-dash call -- never dispatched on
     * here; BRUTUS_PLAYER_ELIMINATED is the one that actually reveals the outcome. */
    public static final String BRUTUS_DASH_REPORTED = "BRUTUS_DASH_REPORTED";
    /** Server-only echo of Brutus's own one-shot confirm-brutus-out-of-bounds call (he stepped
     * completely off the arena before reaching the target zone) -- never dispatched on here; purely
     * lets the server's own _wait_for_dash cut the round short, surfacing as an ordinary
     * BRUTUS_DASH_MISSED "MISS!" flash same as a natural timeout. */
    public static final String BRUTUS_OUT_OF_BOUNDS = "BRUTUS_OUT_OF_BOUNDS";
    /** A surviving target's own frozen round-arrival position matched Brutus's own self-reported
     * dash-landing position -- see BrutusAttackPresentation, which folds this into its own
     * eliminatedRsns set. */
    public static final String BRUTUS_PLAYER_ELIMINATED = "BRUTUS_PLAYER_ELIMINATED";
    /** Fires once per Brutus Attack round, the instant that round's own role-aware arrival gate
     * closes -- gives the client a fresh timestamp to run its own local dash countdown from, same
     * role REPEAT_AFTER_ME_ROUND_STARTED already plays. */
    public static final String BRUTUS_ROUND_STARTED = "BRUTUS_ROUND_STARTED";
    /** A target's own self-report that it walked off its required zone's tiles after already
     * arriving there this round, while the round was actually active -- see
     * BrutusAttackPresentation, which folds this into the same eliminatedRsns set
     * BRUTUS_PLAYER_ELIMINATED does, but deliberately does NOT flash "HIT!" for it -- Brutus didn't
     * actually do anything, so this is silent unless it happened to be the last target standing
     * (see the server's own brutus_target_left_zone doc). */
    public static final String BRUTUS_TARGET_LEFT_ZONE = "BRUTUS_TARGET_LEFT_ZONE";
    public static final String CHANCE_SPACE_TRIGGERED = "CHANCE_SPACE_TRIGGERED";
    /** Echo of the client's own submit-click-click-click-result call -- same "already knows its
     * own final tally" reasoning as FISHING_CATCH_SUBMITTED's own doc just below. */
    public static final String CLICK_CLICK_CLICK_RESULT_SUBMITTED = "CLICK_CLICK_CLICK_RESULT_SUBMITTED";
    public static final String COIN_RUSH_COLLECTED = "COIN_RUSH_COLLECTED";
    public static final String COIN_RUSH_SPAWN = "COIN_RUSH_SPAWN";
    public static final String COIN_TRAP_TRIGGERED = "COIN_TRAP_TRIGGERED";
    public static final String COINS_CHANGED = "COINS_CHANGED";
    public static final String CRAB_RAVE_RESULT_SUBMITTED = "CRAB_RAVE_RESULT_SUBMITTED";
    /** Echo of the client's own submit-ddr-result call -- same "already knows its own final
     * tally" reasoning as FISHING_CATCH_SUBMITTED's own doc just below. */
    public static final String DDR_RESULT_SUBMITTED = "DDR_RESULT_SUBMITTED";
    /** Echo of the client's own report-ddr-round-duration call -- fired once per round, right when
     * DanceDanceRuneScapePresentation#onRoundBegin() picks its sequence, so the server can size its
     * own wait for the round's end around however long that specific sequence actually takes to
     * play through, without ever needing to know the sequence data itself. */
    public static final String DDR_ROUND_DURATION_REPORTED = "DDR_ROUND_DURATION_REPORTED";
    public static final String DICE_ROLLED = "DICE_ROLLED";
    /** Echo of the client's own submit-fishing-catch call -- the client already knows its own
     * final tally the instant it sends it, so nothing here needs to react to the echo. */
    public static final String FISHING_CATCH_SUBMITTED = "FISHING_CATCH_SUBMITTED";
    public static final String GAME_ENDED = "GAME_ENDED";
    public static final String GAME_STARTED = "GAME_STARTED";
    public static final String GOLDEN_GNOME_LOST = "GOLDEN_GNOME_LOST";
    public static final String GOLDEN_GNOME_MOVED = "GOLDEN_GNOME_MOVED";
    /** `player` tried to buy the board's own Golden Gnome but couldn't afford it -- see
     * GoldenGnomePresentation, which folds this into the same outcome banner GOLDEN_GNOME_PURCHASED
     * uses, just with a "can't afford" message instead of "got a Golden Gnome!". */
    public static final String GOLDEN_GNOME_PURCHASE_FAILED = "GOLDEN_GNOME_PURCHASE_FAILED";
    public static final String GOLDEN_GNOME_PURCHASED = "GOLDEN_GNOME_PURCHASED";
    public static final String GOLDEN_GNOME_WON = "GOLDEN_GNOME_WON";
    public static final String HOME_TELEPORT_ARMED = "HOME_TELEPORT_ARMED";
    public static final String HOME_TELEPORT_ARRIVED = "HOME_TELEPORT_ARRIVED";
    public static final String HOT_POTATO_ASSIGNED = "HOT_POTATO_ASSIGNED";
    public static final String HOT_POTATO_EXPLODED = "HOT_POTATO_EXPLODED";
    public static final String ITEM_CAP_BLOCKED = "ITEM_CAP_BLOCKED";
    public static final String ITEM_GRANTED = "ITEM_GRANTED";
    public static final String ITEM_SHOP_DISMISSED = "ITEM_SHOP_DISMISSED";
    public static final String ITEM_SHOP_ENCOUNTER_OPENED = "ITEM_SHOP_ENCOUNTER_OPENED";
    public static final String ITEM_SHOP_NO_AFFORDABLE_ITEMS = "ITEM_SHOP_NO_AFFORDABLE_ITEMS";
    public static final String ITEM_SHOP_PURCHASE_FAILED = "ITEM_SHOP_PURCHASE_FAILED";
    public static final String ITEM_SHOP_PURCHASED = "ITEM_SHOP_PURCHASED";
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
    public static final String PLAYER_TRANSFORMED = "PLAYER_TRANSFORMED";
    public static final String RAINBOW_RUSH_FINISHER_FOUND = "RAINBOW_RUSH_FINISHER_FOUND";
    public static final String REPEAT_AFTER_ME_RESULT_SUBMITTED = "REPEAT_AFTER_ME_RESULT_SUBMITTED";
    public static final String REPEAT_AFTER_ME_ROUND_STARTED = "REPEAT_AFTER_ME_ROUND_STARTED";
    public static final String ROLE_ASSIGNED = "ROLE_ASSIGNED";
    public static final String ROLL_BONUS_GRANTED = "ROLL_BONUS_GRANTED";
    public static final String SANDWICH_RUSH_ITEM_COLLECTED = "SANDWICH_RUSH_ITEM_COLLECTED";
    public static final String SANDWICH_RUSH_ITEM_SPAWNED = "SANDWICH_RUSH_ITEM_SPAWNED";
    public static final String STANDARD_COURSE_LOCKED = "STANDARD_COURSE_LOCKED";
    public static final String TELE_BLOCK_APPLIED = "TELE_BLOCK_APPLIED";
    public static final String TELE_OTHER_USED = "TELE_OTHER_USED";
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
    /** Closes the Wise Old Man encounter, whichever way it went -- outcome is "stole_coins",
     * "stole_golden_gnome", "declined", or "timed_out". See WiseOldManPresentation, which clears
     * its own pending-encounter state on this. */
    public static final String WISE_OLD_MAN_DISMISSED = "WISE_OLD_MAN_DISMISSED";
    /** Opens a Wise Old Man encounter for whoever just landed on the tile -- see
     * WiseOldManPresentation, which folds this into its own encounterRsn/revealAt. */
    public static final String WISE_OLD_MAN_ENCOUNTER_OPENED = "WISE_OLD_MAN_ENCOUNTER_OPENED";
    /** A resolved steal (coins or a Golden Gnome) -- see WiseOldManPresentation, which folds this
     * into the on-screen "X stole ... from Y!" announcement shown to every player. */
    public static final String WISE_OLD_MAN_STOLEN = "WISE_OLD_MAN_STOLEN";
}
