package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.net.MinigameReward;
import gay.runescape.runeparty.net.MinigameScore;
import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyColor;
import gay.runescape.runeparty.RunePartyConfig;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TrueOrFalseResult;
import gay.runescape.runeparty.WheelEntry;
import gay.runescape.runeparty.overlays.layout.Box;
import gay.runescape.runeparty.overlays.layout.Layout;
import gay.runescape.runeparty.overlays.layout.Line;
import gay.runescape.runeparty.overlays.layout.Node;
import gay.runescape.runeparty.overlays.layout.Segment;
import gay.runescape.runeparty.overlays.layout.Table;
import gay.runescape.runeparty.overlays.layout.Text;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import gay.runescape.runeparty.items.Item;
import gay.runescape.runeparty.items.Items;
import gay.runescape.runeparty.minigames.Minigame;
import gay.runescape.runeparty.minigames.Minigames;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/** Big, brief, screen-centered instructional banners -- e.g. "&lt;player&gt;'s Turn". */
@Slf4j
public class AnnouncementOverlay extends Overlay
{
    private static final long DEFAULT_FADE_MS = 500;
    private static final long DEFAULT_PULSE_PERIOD_MS = 1400;

    private static final long FADE_DURATION_MS = DEFAULT_FADE_MS;

    private static final Color DIE_BORDER = Color.BLACK;
    private static final Color DIE_PIP = new Color(255, 255, 255, 230);
    private static final int DIE_SIZE = 90;
    private static final int DIE_CORNER = 14;
    private static final float DIE_BORDER_WIDTH = 5f;
    private static final float DIE_NUMBER_SIZE = 48f;
    private static final float DIE_FACE_OPACITY = 0.45f; // face stays translucent so the game behind it is still visible
    private static final long DIE_SPIN_FACE_MS = 70;
    private static final long DIE_SETTLE_POP_MS = 220; // overshoot-then-settle scale pop once the value lands

    private static final Color WELCOME_TITLE_COLOR = new Color(255, 215, 0);
    private static final long WELCOME_FADE_MS = 700;
    private static final float WELCOME_LEAD_SIZE = 20f; // "WELCOME TO"
    private static final float RUNE_PARTY_SIZE = 52f; // "RUNE PARTY"
    private static final float SHOWDOWN_SIZE = 32f; // "SHOWDOWN"

    // Mario-Party-style rainbow-letter treatment shared by "RUNE PARTY", "MINIGAME!", "HERE WE GO!":
    // one color per non-space character, cycling through this sequence (see drawCenteredRainbowText).
    private static final Color RAINBOW_RED = new Color(230, 45, 45);
    private static final Color RAINBOW_GREEN = new Color(60, 190, 80);
    private static final Color RAINBOW_YELLOW = new Color(250, 210, 40);
    private static final Color RAINBOW_BLUE = new Color(60, 130, 230);
    private static final Color[] RAINBOW_LETTER_COLORS = {
        RAINBOW_RED, RAINBOW_GREEN, RAINBOW_YELLOW, RAINBOW_BLUE, RAINBOW_GREEN,
        RAINBOW_RED, RAINBOW_BLUE, RAINBOW_GREEN, RAINBOW_YELLOW,
    };

    private static final long MINIGAME_FADE_MS = DEFAULT_FADE_MS;
    private static final float MINIGAME_TITLE_SIZE = 58f;

    // Shared spinner wheel (see drawWheel), reused by renderMinigameSpinner and renderItemSpinner.
    private static final long WHEEL_FADE_MS = 400;
    private static final int WHEEL_MAX_SEGMENTS = 4; // caps how many options the wheel ever shows
    private static final float WHEEL_RADIUS = 90f;
    private static final float WHEEL_ICON_SIZE = 34f;
    private static final int WHEEL_EXTRA_SPINS = 3; // full rotations before settling, purely visual
    private static final long WHEEL_SETTLE_POP_MS = 220;
    private static final float WHEEL_NAME_SIZE = 30f; // the settled entry's name, revealed once the wheel stops
    private static final Color WHEEL_POINTER_COLOR = new Color(255, 215, 0);

    private static final long ITEM_CAP_BLOCKED_FADE_MS = DEFAULT_FADE_MS;
    private static final float ITEM_CAP_BLOCKED_TITLE_SIZE = 28f;
    private static final float ITEM_CAP_BLOCKED_SUBTITLE_SIZE = 20f;

    private static final long ITEM_USED_ANNOUNCE_FADE_MS = DEFAULT_FADE_MS;
    private static final float ITEM_USED_ANNOUNCE_TITLE_SIZE = 28f;
    private static final float ITEM_USED_ANNOUNCE_SUBTITLE_SIZE = 20f;

    private static final long COIN_TRAP_ANNOUNCE_FADE_MS = DEFAULT_FADE_MS;
    private static final float COIN_TRAP_ANNOUNCE_TITLE_SIZE = 32f;
    private static final float WISE_OLD_MAN_STOLEN_TITLE_SIZE = 32f;

    private static final float DICE_ROLL_BONUS_LABEL_SIZE = 26f;
    private static final Color DICE_ROLL_BONUS_POSITIVE_COLOR = new Color(80, 220, 80);
    private static final Color DICE_ROLL_BONUS_NEGATIVE_COLOR = new Color(230, 70, 70);

    private static final long MINIGAME_READY_CHECK_PULSE_PERIOD_MS = DEFAULT_PULSE_PERIOD_MS;
    private static final float MINIGAME_READY_CHECK_MIN_ALPHA = 0.6f;
    private static final float MINIGAME_READY_CHECK_LINE_SIZE = 22f;
    private static final int MINIGAME_READY_CHECK_LINE_HEIGHT = 28;

    // Rainbow Rush's own traffic-light get-ready beat -- see renderRainbowRushTrafficLight.
    private static final int TRAFFIC_LIGHT_DOT_RADIUS = 22;
    private static final int TRAFFIC_LIGHT_GAP = 14;
    private static final int TRAFFIC_LIGHT_PADDING = 20;
    private static final int TRAFFIC_LIGHT_ARC = 24;
    private static final int TRAFFIC_LIGHT_GLOW_EXTRA_PX = 10;
    private static final int TRAFFIC_LIGHT_GLOW_ALPHA = 90;
    private static final Color TRAFFIC_LIGHT_HOUSING_COLOR = new Color(20, 20, 20, 200);
    private static final Color TRAFFIC_LIGHT_HOUSING_BORDER_COLOR = new Color(255, 255, 255, 160);

    private static final float MINIGAME_COUNTDOWN_SIZE = 90f;
    private static final long MINIGAME_COUNTDOWN_POP_MS = 260;
    private static final Color MINIGAME_COUNTDOWN_NUMBER_COLOR = RAINBOW_YELLOW;

    private static final long GAME_START_FADE_MS = 600;
    private static final float GAME_START_TITLE_SIZE = 58f;

    private static final long ROUND_COMPLETE_FADE_MS = DEFAULT_FADE_MS;
    private static final float ROUND_COMPLETE_TITLE_SIZE = 46f; // "ROUND x"
    private static final float ROUND_COMPLETE_SUBTITLE_SIZE = 20f; // "Current Standings"
    private static final float ROUND_COMPLETE_LINE_SIZE = 18f;
    private static final int ROUND_COMPLETE_LINE_HEIGHT = 24;

    private static final long MINIGAME_REWARDS_FADE_MS = DEFAULT_FADE_MS;
    private static final float MINIGAME_REWARDS_TITLE_SIZE = 46f; // "REWARDS"
    private static final float MINIGAME_REWARDS_LINE_SIZE = 18f;
    private static final int MINIGAME_REWARDS_LINE_HEIGHT = 24;
    private static final Color MINIGAME_REWARDS_COLOR = new Color(80, 220, 120);
    private static final Color MINIGAME_REWARDS_NONE_COLOR = Color.GRAY;

    private static final float TRUE_OR_FALSE_ROUND_LABEL_SIZE = 22f; // "Round 2/5"
    private static final float TRUE_OR_FALSE_QUESTION_SIZE = 26f;
    private static final int TRUE_OR_FALSE_QUESTION_LINE_HEIGHT = 32;
    private static final int TRUE_OR_FALSE_QUESTION_MAX_WIDTH = 620;
    private static final float TRUE_OR_FALSE_COUNTDOWN_SIZE = 34f;
    private static final Color TRUE_OR_FALSE_COUNTDOWN_COLOR = RAINBOW_YELLOW;

    private static final long TRUE_OR_FALSE_REVEAL_FADE_MS = 400;
    private static final float TRUE_OR_FALSE_REVEAL_TITLE_SIZE = 30f;
    private static final float TRUE_OR_FALSE_REVEAL_LINE_SIZE = 18f;
    private static final int TRUE_OR_FALSE_REVEAL_LINE_HEIGHT = 24;
    private static final Color TRUE_OR_FALSE_TRUE_COLOR = new Color(80, 220, 80);
    private static final Color TRUE_OR_FALSE_FALSE_COLOR = new Color(220, 70, 70);

    // End-game awards ceremony: "GAME OVER!" -> intro -> one place reveal per eliminated player ->
    // suspense -> winner. Each phase gets its own fade/size below, in the order it plays.
    private static final long GAME_OVER_TITLE_FADE_MS = 600;
    private static final float GAME_OVER_TITLE_SIZE = 64f;

    private static final long WINNER_INTRO_FADE_MS = 500;
    private static final float WINNER_INTRO_SIZE = 26f;

    private static final long PLACE_REVEAL_FADE_MS = 500;
    private static final float PLACE_REVEAL_RANK_SIZE = 40f; // "In 4th place..."
    private static final float PLACE_REVEAL_LINE_SIZE = 26f; // "<Player> -- N GG, M coins"

    private static final long WINNER_SUSPENSE_FADE_MS = 500;
    private static final float WINNER_SUSPENSE_SIZE = 34f;

    private static final long WINNER_REVEAL_FADE_MS = 700;
    private static final float WINNER_REVEAL_NAME_SIZE = 62f;
    private static final float WINNER_REVEAL_SUBTITLE_SIZE = 22f;

    private static final Color SPIN_HINT_COLOR = new Color(255, 255, 255);
    private static final float SPIN_HINT_SIZE = 22f;
    private static final long SPIN_HINT_PULSE_PERIOD_MS = DEFAULT_PULSE_PERIOD_MS; // breathing alpha since it has no fixed duration
    private static final float SPIN_HINT_MIN_ALPHA = 0.55f;

    private static final float GOLDEN_GNOME_OFFER_TITLE_SIZE = 30f;
    private static final float GOLDEN_GNOME_OFFER_SUBTITLE_SIZE = 20f;
    private static final float GOLDEN_GNOME_OFFER_EMOTE_SIZE = 24f;
    private static final int READY_CHECK_INSTRUCTIONS_LINE_HEIGHT = 24;
    private static final int READY_CHECK_INSTRUCTIONS_MAX_WIDTH = 620;
    private static final long GOLDEN_GNOME_OFFER_PULSE_PERIOD_MS = DEFAULT_PULSE_PERIOD_MS;
    private static final float GOLDEN_GNOME_OFFER_MIN_ALPHA = 0.6f;

    private static final long GOLDEN_GNOME_OUTCOME_FADE_MS = DEFAULT_FADE_MS;
    private static final float GOLDEN_GNOME_OUTCOME_SIZE = 32f;

    private static final long ITEM_SHOP_OUTCOME_FADE_MS = DEFAULT_FADE_MS;
    private static final float ITEM_SHOP_OUTCOME_SIZE = 32f;

    private static final long CHANCE_SPACE_ICON_STAGE_FADE_MS = DEFAULT_FADE_MS;
    private static final int CHANCE_SPACE_ICON_SPACING = 160; // each player token's own x offset from center; the arrow sits at dead center
    private static final int CHANCE_SPACE_TOKEN_RADIUS = 22;
    private static final Stroke CHANCE_SPACE_TOKEN_BORDER = new BasicStroke(2.5f);
    private static final float CHANCE_SPACE_TOKEN_NAME_SIZE = 16f;
    private static final int CHANCE_SPACE_TOKEN_NAME_GAP = 18;
    private static final int CHANCE_SPACE_ARROW_HALF_WIDTH = 12;
    private static final int CHANCE_SPACE_ARROW_LENGTH = 70;
    private static final int CHANCE_SPACE_ARROWHEAD_LENGTH = 16;
    private static final Stroke CHANCE_SPACE_ARROW_SHAFT_STROKE = new BasicStroke(6f);
    private static final int CHANCE_SPACE_ARROW_ITEM_CLEARANCE = 48; // gap between the arrow shaft and the coin/gnome icon above it
    private static final int CHANCE_SPACE_ITEM_ICON_SIZE = 52; // 2x the original placeholder-art size
    private static final int CHANCE_SPACE_ANNOUNCEMENT_TOP_GAP = 30; // gap below the token name labels before the announcement's own header line
    private static final float CHANCE_SPACE_ANNOUNCEMENT_HEADER_SIZE = 22f;
    private static final float CHANCE_SPACE_ANNOUNCEMENT_LINE_SIZE = 18f;
    private static final int CHANCE_SPACE_ANNOUNCEMENT_LINE_HEIGHT = 26;
    private static final float CHANCE_SPACE_BACKDROP_ALPHA = 0.55f; // slightly opaque, not a full screen dim
    private static final int CHANCE_SPACE_BACKDROP_PADDING_X = 32;
    private static final int CHANCE_SPACE_BACKDROP_PADDING_Y = 22;
    private static final int CHANCE_SPACE_BACKDROP_ARC = 24;

    private static final float JAD_TAUNT_SIZE = 26f;
    private static final float JAD_COUNTDOWN_SIZE = 40f;

    // Screen-fit safety net -- every one of this overlay's single-phrase/rainbow text draws funnels
    // through drawCenteredText/drawCenteredRainbowText below, which auto-shrink (see fitFont) rather
    // than let a title, name, or line run past the viewer's own CURRENT drawable edges -- computed
    // fresh from drawableWidth() (see its own doc) each call, not the game window's normal/design
    // size, so a resized/small client is covered, AND so is Fixed mode, where the actual 3D viewport
    // is smaller than the full canvas and the rest is native OSRS widgets (inventory, chat, minimap)
    // that would otherwise sit on top of anything centered on the raw canvas instead. Every
    // centerX/y position formula in this file (not just the wrap/shrink width) is anchored to
    // drawableWidth()/drawableHeight() for the same reason -- see their own doc. A composite line
    // built from several independently pre-measured segments (renderSpinHintSelf/Waiting,
    // an emote instruction, a Table's own rank/name/stats columns) can't shrink itself that way --
    // see the layout package's own Line/Table, which jointly measure every segment/row up front and
    // apply the same shared scale to all of them before painting anything, exactly like fitScale's
    // own doc describes for a single line. Genuine multi-sentence content (mini-game instructions,
    // True or False questions) still wraps onto multiple lines instead of shrinking -- see Text#wrap
    // -- since a whole paragraph squeezed onto one line would turn unreadable long before it ran out
    // of room that way.
    private static final int SCREEN_SAFE_MARGIN_PX = 24; // kept clear on both sides of the canvas edge
    private static final int MIN_SAFE_TEXT_WIDTH_PX = 240; // floor so a tiny/resized window can't force degenerate wrapping/shrinking
    private static final float MIN_FIT_SCALE = 0.5f; // never shrink a line past half its designed size

    private static final Font MARIO_PARTY_FONT = RunePartyFonts.MARIO_PARTY;

    private final Client client;
    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;

    // The item drawn above the Chance Tile arrow -- see drawChanceSpaceArrow. Placeholder art for
    // now (plain gold coin / gold circle-plus-hat "gnome"); swap either PNG in place at this same
    // path/size and no code change is needed.
    private final BufferedImage chanceSpaceCoinIcon;
    private final BufferedImage chanceSpaceGnomeIcon;

    public AnnouncementOverlay(Client client, RunePartyConfig config, RunePartyPlugin plugin)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.chanceSpaceCoinIcon = ImageUtil.loadImageResource(getClass(), "chance_space/coin-icon.png");
        this.chanceSpaceGnomeIcon = ImageUtil.loadImageResource(getClass(), "chance_space/golden-gnome-icon.png");

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        // Hide every banner while the full-screen course map is up -- nothing here has a "missed
        // it" state, so skipping frames is safe.
        if (plugin.isMapShowing()) return null;
        if (!config.showOverlay()) return null;
        GamePhase phase = plugin.getPhase();
        // ENDED is included so the end-game awards ceremony can still render past the instant the
        // phase flips; every other render* call below is still gated on its own until-timestamp.
        if (phase != GamePhase.ACTIVE && phase != GamePhase.LOBBY && phase != GamePhase.ENDED) return null;

        renderWelcomeBanner(g);
        renderGameStartBanner(g);
        renderTurnAnnouncement(g);
        renderTurnSkippedAnnouncement(g);
        renderSpinHint(g);
        renderReturnToPositionHint(g);
        renderStartHereHint(g);
        renderGoldenGnomeOutcome(g);
        renderChanceSpaceTitle(g);
        renderChanceSpaceIcons(g);
        renderJadEncounter(g);
        renderJadOutcome(g);
        renderItemBanner(g);
        renderItemSpinner(g);
        renderItemGrantDescription(g);
        renderItemCapBlocked(g);
        renderItemUsedAnnouncement(g);
        renderTeleBlockCastAnnouncement(g);
        renderTeleOtherUsedAnnouncement(g);
        renderCoinTrapAnnouncement(g);
        renderWiseOldManStolen(g);
        renderItemShopOutcome(g);
        renderMinigameBanner(g);
        renderMinigameSpinner(g);
        renderMinigameReadyCheck(g);
        renderTeamAssignedBanner(g);
        renderJaddyResolvedBanner(g);
        if (RunePartyPlugin.BRUTUS_ATTACK_KEY.equals(plugin.getMinigameKey()))
        {
            // Bespoke, not folded into the generic arrival-gather list below: Brutus Attack needs
            // a fresh gather message before EACH of its own 3 rounds (players have to walk back
            // to their zone every round), not just once before the whole mini-game's first round
            // -- see renderBrutusAttackGatherMessage's own doc for why isMinigameRoundBegun()'s
            // one-shot latch (every other arena mini-game's own hide condition) doesn't fit here.
            renderBrutusAttackGatherMessage(g);
        }
        else if (RunePartyPlugin.GATHER_MESSAGE_KEYS.contains(plugin.getMinigameKey()))
        {
            renderArrivalGatherMessage(g);
        }
        else
        {
            renderMinigameCountdown(g);
        }
        renderRainbowRushTrafficLight(g);
        renderArrivalRoundBeginBanner(g);
        renderTrueOrFalseReveal(g);
        renderTrueOrFalseQuestion(g);
        renderCrabRaveCountdown(g);
        renderBrutusAttackDashCountdown(g);
        renderBrutusAttackDashResult(g);
        renderMinigameOverBanner(g);
        renderMinigameScoreBanner(g);
        renderMinigameRewardsBanner(g);
        renderRoundCompleteBanner(g);
        renderDiceRoll(g);
        renderCeremonyTitleBanner(g);
        renderCeremonyGatherMessage(g);
        renderGnomeLine(g);
        renderBonusObjectiveBanner(g);
        renderBonusSuspenseBanner(g);
        renderBonusWinnerBanner(g);
        renderGameOverBanner(g);
        renderWinnerIntroBanner(g);
        renderPlaceReveal(g);
        renderWinnerSuspenseBanner(g);
        renderWinnerReveal(g);

        return null;
    }

    private void renderTurnAnnouncement(Graphics2D g)
    {
        String rsn = plugin.getTurnAnnounceRsn();
        if (rsn == null) return;

        Float alpha = BannerAnim.fadeAlpha(plugin.getTurnAnnounceUntil(), FADE_DURATION_MS);
        if (alpha == null) return;

        String text = isLocal(rsn) ? "Your Turn!" : rsn + "'s Turn";

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color color = seatColor != null ? seatColor.awt : Color.WHITE;

        Node title = Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(36f)).color(color);
        Layout.renderCentered(g, title, drawableWidth() / 2, drawableHeight() / 4, safeTextWidth(), alpha);
    }

    /** Stands in for renderTurnAnnouncement when a player's turn was skipped by a Tele Block. */
    private void renderTurnSkippedAnnouncement(Graphics2D g)
    {
        String rsn = plugin.getTurnSkippedRsn();
        if (rsn == null) return;

        Float alpha = BannerAnim.fadeAlpha(plugin.getTurnSkippedUntil(), FADE_DURATION_MS);
        if (alpha == null) return;

        String text = isLocal(rsn) ? "Your Turn Was Skipped!" : rsn + "'s Turn Was Skipped!";

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color color = seatColor != null ? seatColor.awt : Color.WHITE;

        Node banner = Box.column(
                Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(36f)).color(color),
                Text.of("Ice Barraged").font(FontManager.getRunescapeBoldFont().deriveFont(16f)).color(color))
            .gap(10);
        Layout.renderCentered(g, banner, drawableWidth() / 2, drawableHeight() / 4, safeTextWidth(), alpha);
    }

    /** Dispatches to whichever half of the Spin hint applies to the local viewer: the "it's your
     * turn" reminder for whoever's up, or "waiting for them to roll" for everyone else. */
    private void renderSpinHint(Graphics2D g)
    {
        if (plugin.isLocalPlayerReadyToRoll())
        {
            renderSpinHintSelf(g);
            return;
        }

        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null) return;
        if (isLocal(moverRsn)) return;
        if (!plugin.isAwaitingSomeonesRoll()) return;

        renderSpinHintWaiting(g, moverRsn);
    }

    /** Reminds the local player to use the SPIN emote to roll -- or use an item instead, if they're
     * holding one and haven't already used one this turn. Duration-less, so it pulses to stay
     * noticeable. */
    private void renderSpinHintSelf(Graphics2D g)
    {
        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * BannerAnim.pulse(System.currentTimeMillis(), SPIN_HINT_PULSE_PERIOD_MS);

        String self = plugin.getLocalRsn();
        boolean hasItems = self != null && !plugin.isItemUsedThisTurn()
            && !plugin.getRosterReducer().getItems(self).isEmpty();

        String suffix = hasItems ? " emote to roll the dice, or use an item in the panel." : " emote to roll the dice.";

        Font normalFont = FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE);
        Font spinFont = MARIO_PARTY_FONT.deriveFont(SPIN_HINT_SIZE);

        // Shrunk together rather than left to overflow -- the "or use an item..." suffix is the
        // longest text on screen during a normal turn, and the first to run off a small/resized
        // client if it isn't checked.
        Node line = Line.of(
            Segment.plain("Use the ", normalFont, SPIN_HINT_COLOR),
            Segment.rainbow("SPIN", spinFont, RAINBOW_LETTER_COLORS),
            Segment.plain(suffix, normalFont, SPIN_HINT_COLOR));

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 4 + 40;
        Layout.renderCentered(g, line, centerX, y, safeTextWidth(), alpha);
    }

    /** The bystander half of the Spin hint -- "Waiting for &lt;player&gt; to roll the dice...". */
    private void renderSpinHintWaiting(Graphics2D g, String rsn)
    {
        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * BannerAnim.pulse(System.currentTimeMillis(), SPIN_HINT_PULSE_PERIOD_MS);

        Font font = FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE);
        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color nameColor = seatColor != null ? seatColor.awt : SPIN_HINT_COLOR;

        Node line = Line.of(
            Segment.plain("Waiting for ", font, SPIN_HINT_COLOR),
            Segment.plain(rsn, font, nameColor),
            Segment.plain(" to roll the dice...", font, SPIN_HINT_COLOR));

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 4 + 40;
        Layout.renderCentered(g, line, centerX, y, safeTextWidth(), alpha);
    }

    /** The on-screen text half of TileOverlay's own "Return Here!" arrow -- shown under the exact
     * same condition (see RunePartyPlugin#isLocalPlayerAwaitingReturnToPosition), in the same
     * pulsing, duration-less style/position as the Spin hint above. The two never show at once --
     * this requires the local player NOT standing on their tracked tile, the Spin hint requires the
     * opposite -- so sharing the same screen slot never conflicts. */
    private void renderReturnToPositionHint(Graphics2D g)
    {
        if (!plugin.isLocalPlayerAwaitingReturnToPosition()) return;

        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * BannerAnim.pulse(System.currentTimeMillis(), SPIN_HINT_PULSE_PERIOD_MS);
        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 4 + 40;

        Node message = Text.of("Return to your tile to roll the dice!").font(FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE)).color(SPIN_HINT_COLOR);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** The on-screen text half of TileOverlay's own "Start Here!" arrow -- shown under the exact
     * same condition (turn order hasn't begun yet), broadcast to everyone the same way the arrow
     * itself is, not personalized to whether this particular viewer has already reached the tile.
     * Same pulsing style/position as the Spin hint/return-to-position hint above -- mutually
     * exclusive with both, since neither of those can fire before turn order has begun. */
    private void renderStartHereHint(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE || plugin.getCurrentTurnRsn() != null) return;

        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * BannerAnim.pulse(System.currentTimeMillis(), SPIN_HINT_PULSE_PERIOD_MS);
        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 4 + 40;

        Node message = Text.of("Head to the Start Tile to begin!").font(FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE)).color(SPIN_HINT_COLOR);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the Jad encounter -- the awakening announcement, Jad's taunt, a cosmetic countdown to
     * the bow window closing, then either the BOW instruction (for the target) or a "waiting on
     * them" line (for everyone else). Broadcast to everyone, duration-less so it pulses. Stops
     * rendering once the bow window closes, handing off to renderJadOutcome. */
    private void renderJadEncounter(Graphics2D g)
    {
        String encounterRsn = plugin.getJadEncounterRsn();
        if (encounterRsn == null) return;
        if (plugin.isJadSmashTriggered()) return;
        if (System.currentTimeMillis() < plugin.getJadRevealAt()) return;

        float alpha = GOLDEN_GNOME_OFFER_MIN_ALPHA + (1f - GOLDEN_GNOME_OFFER_MIN_ALPHA) * BannerAnim.pulse(System.currentTimeMillis(), GOLDEN_GNOME_OFFER_PULSE_PERIOD_MS);

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        boolean isLocal = isLocal(encounterRsn);

        String title = isLocal ? "You have awakened Jad!" : encounterRsn + " has awakened Jad!";
        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, Color.WHITE, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(JAD_TAUNT_SIZE));
        drawCenteredText(g, "Bow down to me!! Or else!!", centerX, y + 32, new Color(214, 9, 65), alpha);

        // Skipped (shows nothing) for a client that only caught up on an already-open encounter.
        long awakenedAt = plugin.getJadAwakenedAt();
        if (awakenedAt != 0)
        {
            long remainingMs = RunePartyPlugin.JAD_BOW_WINDOW_MS - (System.currentTimeMillis() - awakenedAt);
            int secondsLeft = (int) Math.max(0, Math.ceil(remainingMs / 1000.0));
            g.setFont(MARIO_PARTY_FONT.deriveFont(JAD_COUNTDOWN_SIZE));
            drawCenteredText(g, String.valueOf(secondsLeft), centerX, y + 68, Color.WHITE, alpha);
        }

        if (isLocal)
        {
            Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
            Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
            Node emoteLine = Line.of(
                Segment.plain("'", emoteFont, Color.LIGHT_GRAY),
                Segment.rainbow("BOW", emoteWordFont, RAINBOW_LETTER_COLORS),
                Segment.plain("' emote: bow to Jad!", emoteFont, Color.LIGHT_GRAY));
            Layout.renderCentered(g, emoteLine, centerX, y + 96, safeTextWidth(), alpha);
        }
        else
        {
            g.setFont(FontManager.getRunescapeSmallFont());
            drawCenteredText(g, "Waiting for " + encounterRsn + " to bow...", centerX, y + 96, Color.LIGHT_GRAY, alpha);
        }
    }

    /** Draws the Golden Gnome purchase follow-up -- "You got a Golden Gnome!" -- addressed to
     * whoever the outcome belongs to. */
    private void renderGoldenGnomeOutcome(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getGoldenGnomeOutcomeBannerUntil(), GOLDEN_GNOME_OUTCOME_FADE_MS);
        if (alpha == null) return;

        String outcome = plugin.getGoldenGnomeOutcome();
        String rsn = plugin.getGoldenGnomeOutcomeRsn();
        boolean isLocal = isLocal(rsn);

        String text;
        Color color;
        if ("purchased".equals(outcome))
        {
            text = isLocal ? "You got a Golden Gnome!" : rsn != null ? rsn + " got a Golden Gnome!" : null;
            color = WELCOME_TITLE_COLOR;
        }
        else if ("failed".equals(outcome))
        {
            text = isLocal ? "You can't afford a Golden Gnome!" : rsn != null ? rsn + " can't afford a Golden Gnome!" : null;
            color = DICE_ROLL_BONUS_NEGATIVE_COLOR;
        }
        else
        {
            text = null;
            color = Color.WHITE;
        }
        if (text == null) return;

        Node message = Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OUTCOME_SIZE)).color(color);
        Layout.renderCentered(g, message, drawableWidth() / 2, drawableHeight() / 3, safeTextWidth(), alpha);
    }

    /** Draws the "CHANCE TILE!" title card -- same rainbow single-line treatment as "ITEM SPACE!"
     * (renderItemBanner), the first of three Chance Tile reveal stages (see
     * ChanceSpacePresentation's own scheduleAfterTurnEffects chain). */
    private void renderChanceSpaceTitle(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getChanceSpaceTitleUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        Node title = Text.rainbow("CHANCE TILE!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the second Chance Tile reveal stage: a player token on each side of the screen and,
     * between them, an arrow carrying whichever item (coins or a Golden Gnome) actually moved, plus
     * (once every icon's settled) the announcement text fading in underneath. Each of the three
     * icons pops in independently once its own randomized delay
     * (ChanceSpacePresentation#buildRevealPayload's slotDelayMs) has elapsed, via {@link
     * #slotAnim} -- so the reveal order varies, even though the three screen positions never
     * move. */
    private void renderChanceSpaceIcons(Graphics2D g)
    {
        Float stageAlpha = BannerAnim.fadeAlpha(plugin.getChanceSpaceIconsUntil(), CHANCE_SPACE_ICON_STAGE_FADE_MS);
        if (stageAlpha == null) return;

        String leftRsn = plugin.getChanceSpaceIconsLeftRsn();
        String rightRsn = plugin.getChanceSpaceIconsRightRsn();
        String outcomeType = plugin.getChanceSpaceIconsOutcomeType();
        String direction = plugin.getChanceSpaceIconsArrowDirection();
        long[] slotDelayMs = plugin.getChanceSpaceIconsSlotDelayMs();
        String[] announcementLines = plugin.getChanceSpaceAnnouncementLines();
        if (leftRsn == null || rightRsn == null || outcomeType == null || direction == null || slotDelayMs == null) return;

        long elapsed = System.currentTimeMillis() - plugin.getChanceSpaceIconsStart();
        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        drawChanceSpaceBackdrop(g, announcementLines, centerX, y, stageAlpha);

        float[] anim = slotAnim(elapsed, slotDelayMs[0], stageAlpha);
        if (anim != null) drawChanceSpaceToken(g, leftRsn, centerX - CHANCE_SPACE_ICON_SPACING, y, anim[0], anim[1]);

        anim = slotAnim(elapsed, slotDelayMs[1], stageAlpha);
        if (anim != null) drawChanceSpaceArrow(g, direction, outcomeType, centerX, y, anim[0], anim[1]);

        anim = slotAnim(elapsed, slotDelayMs[2], stageAlpha);
        if (anim != null) drawChanceSpaceToken(g, rightRsn, centerX + CHANCE_SPACE_ICON_SPACING, y, anim[0], anim[1]);

        if (announcementLines != null) drawChanceSpaceAnnouncement(g, announcementLines, elapsed, centerX, y, stageAlpha);
    }

    /** A slightly opaque backdrop panel behind the whole tableau (tokens, arrow, and the
     * announcement text once it appears) so the reveal stays readable over a busy game-world
     * background. Sized wide enough for either the token span or the widest announcement line,
     * whichever is bigger, and tall enough to run from just above the item icon at its very top
     * down through the last announcement line -- both measured against their own rest-state
     * (unscaled) sizes, not the brief pop-in overshoot, which is a fine amount of edge bleed for a
     * backdrop. Fades in/out with the same stageAlpha as everything else it sits behind. */
    private void drawChanceSpaceBackdrop(Graphics2D g, String[] lines, int centerX, int iconY, float stageAlpha)
    {
        int halfWidth = CHANCE_SPACE_ICON_SPACING + CHANCE_SPACE_TOKEN_RADIUS + CHANCE_SPACE_BACKDROP_PADDING_X;
        if (lines != null && lines.length > 0)
        {
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(CHANCE_SPACE_ANNOUNCEMENT_HEADER_SIZE));
            int widest = g.getFontMetrics().stringWidth(lines[0]);
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(CHANCE_SPACE_ANNOUNCEMENT_LINE_SIZE));
            for (int i = 1; i < lines.length; i++) widest = Math.max(widest, g.getFontMetrics().stringWidth(lines[i]));
            halfWidth = Math.max(halfWidth, widest / 2 + CHANCE_SPACE_BACKDROP_PADDING_X);
        }

        int top = iconY - CHANCE_SPACE_ARROW_ITEM_CLEARANCE - CHANCE_SPACE_ITEM_ICON_SIZE / 2 - CHANCE_SPACE_BACKDROP_PADDING_Y;
        int bottom = iconY + CHANCE_SPACE_TOKEN_RADIUS + CHANCE_SPACE_TOKEN_NAME_GAP + CHANCE_SPACE_ANNOUNCEMENT_TOP_GAP
            + (lines != null ? lines.length : 0) * CHANCE_SPACE_ANNOUNCEMENT_LINE_HEIGHT + CHANCE_SPACE_BACKDROP_PADDING_Y;

        g.setColor(RunePartyRender.withAlpha(Color.BLACK, stageAlpha * CHANCE_SPACE_BACKDROP_ALPHA));
        g.fillRoundRect(centerX - halfWidth, top, halfWidth * 2, bottom - top, CHANCE_SPACE_BACKDROP_ARC, CHANCE_SPACE_BACKDROP_ARC);
    }

    /** The header + directional line(s) that fade in beneath the settled tableau -- see
     * ChanceSpacePresentation#buildAnnouncementLines for their content. Starts fading in only after
     * every icon has finished its own pop-in (two stagger gaps + one pop-in window) plus a short
     * extra pause, so it never talks over the icons still animating in. */
    private void drawChanceSpaceAnnouncement(Graphics2D g, String[] lines, long elapsed, int centerX, int iconY, float stageAlpha)
    {
        long local = elapsed - RunePartyPlugin.CHANCE_SPACE_TEXT_START_OFFSET_MS;
        if (local < 0 || lines.length == 0) return;
        float textAlpha = stageAlpha * Math.min(1f, local / (float) RunePartyPlugin.CHANCE_SPACE_TEXT_FADE_MS);

        int y = iconY + CHANCE_SPACE_TOKEN_RADIUS + CHANCE_SPACE_TOKEN_NAME_GAP + CHANCE_SPACE_ANNOUNCEMENT_TOP_GAP;
        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(CHANCE_SPACE_ANNOUNCEMENT_HEADER_SIZE));
        drawCenteredText(g, lines[0], centerX, y, WELCOME_TITLE_COLOR, textAlpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(CHANCE_SPACE_ANNOUNCEMENT_LINE_SIZE));
        for (int i = 1; i < lines.length; i++)
        {
            y += CHANCE_SPACE_ANNOUNCEMENT_LINE_HEIGHT;
            drawCenteredText(g, lines[i], centerX, y, Color.WHITE, textAlpha);
        }
    }

    /** [scale, alpha] for one Chance Tile tableau icon at {@code elapsed} ms into the icon stage,
     * given its own reveal {@code delayMs} -- null before that delay has passed. Pops in oversized
     * then settles to scale 1 over CHANCE_SPACE_ICON_POP_MS, fading in over that same window --
     * same "pop in big, settle down" idiom as the minigame wheel's own settle-scale animation. */
    private static float[] slotAnim(long elapsed, long delayMs, float stageAlpha)
    {
        long local = elapsed - delayMs;
        if (local < 0) return null;
        float t = Math.min(1f, local / (float) RunePartyPlugin.CHANCE_SPACE_ICON_POP_MS);
        return new float[]{1.3f - 0.3f * t, stageAlpha * t};
    }

    /** A player's seat-colored token plus their name underneath -- the screen-space, larger cousin
     * of PlayerOverlay's own in-world overhead token, addressed purely by seat color rather than a
     * real {@code Player} handle. */
    private void drawChanceSpaceToken(Graphics2D g, String rsn, int cx, int cy, float scale, float alpha)
    {
        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color color = seatColor != null ? seatColor.awt : Color.WHITE;
        int radius = Math.round(CHANCE_SPACE_TOKEN_RADIUS * scale);

        g.setColor(RunePartyRender.withAlpha(color, alpha));
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);
        g.setStroke(CHANCE_SPACE_TOKEN_BORDER);
        g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha));
        g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(CHANCE_SPACE_TOKEN_NAME_SIZE));
        drawCenteredText(g, rsn, cx, cy + radius + CHANCE_SPACE_TOKEN_NAME_GAP, color, alpha);
    }

    /** The arrow between the two tokens, always pointing one way -- toward whichever side actually
     * received the gift (see ChanceSpacePresentation#buildRevealPayload's own doc: both outcomes
     * are a strict one-way gift now, never a mutual swap) -- with the item that moved -- coins or a
     * Golden Gnome, see {@link #drawChanceSpaceCoinIcon}/{@link #drawChanceSpaceGnomeIcon} -- drawn
     * above its center. */
    private void drawChanceSpaceArrow(Graphics2D g, String direction, String outcomeType,
                                       int cx, int cy, float scale, float alpha)
    {
        int halfWidth = Math.round(CHANCE_SPACE_ARROW_HALF_WIDTH * scale);
        int length = Math.round(CHANCE_SPACE_ARROW_LENGTH * scale);
        int headLength = Math.round(CHANCE_SPACE_ARROWHEAD_LENGTH * scale);
        int dir = "LEFT".equals(direction) ? -1 : 1;

        int tipX = cx + dir * length / 2;
        int tailX = cx - dir * length / 2;
        int headBaseX = tipX - dir * headLength;

        g.setColor(RunePartyRender.withAlpha(Color.WHITE, alpha));
        g.setStroke(CHANCE_SPACE_ARROW_SHAFT_STROKE);
        // Stops at the arrowhead's own base, not its tip -- the shaft's stroke cap would otherwise
        // poke past the triangle's sharp point instead of stopping cleanly where the head begins.
        g.drawLine(tailX, cy, headBaseX, cy);
        drawChanceSpaceArrowHead(g, tipX, cy, dir, halfWidth, headLength);

        int itemY = cy - CHANCE_SPACE_ARROW_ITEM_CLEARANCE;
        int itemSize = Math.round(CHANCE_SPACE_ITEM_ICON_SIZE * scale);
        if ("coins".equals(outcomeType)) drawChanceSpaceCoinIcon(g, cx, itemY, itemSize, alpha);
        else drawChanceSpaceGnomeIcon(g, cx, itemY, itemSize, alpha);
    }

    private void drawChanceSpaceArrowHead(Graphics2D g, int tipX, int y, int dir, int halfWidth, int headLength)
    {
        int baseX = tipX - dir * headLength;
        Polygon head = new Polygon();
        head.addPoint(tipX, y);
        head.addPoint(baseX, y - halfWidth);
        head.addPoint(baseX, y + halfWidth);
        g.fillPolygon(head);
    }

    /** Draws chanceSpaceCoinIcon centered on (cx, cy) at size x size -- placeholder art, see that
     * field's own doc. RuneLite's Graphics2D already has bilinear interpolation on, so scaling this
     * small a source image up/down for the tableau's pop-in animation stays smooth. */
    private void drawChanceSpaceCoinIcon(Graphics2D g, int cx, int cy, int size, float alpha)
    {
        drawIconImage(g, chanceSpaceCoinIcon, cx, cy, size, alpha);
    }

    /** Draws chanceSpaceGnomeIcon the same way as the coin icon above -- including on a fizzled
     * gnome swap (neither player held one to give): the announcement text underneath already says
     * so plainly, so dimming the icon and slashing a red line through it on top of that just read as
     * a rendering glitch (a barely-visible Golden Gnome half-hidden behind the tableau's own
     * backdrop) rather than an intentional "nothing happened" cue. */
    private void drawChanceSpaceGnomeIcon(Graphics2D g, int cx, int cy, int size, float alpha)
    {
        drawIconImage(g, chanceSpaceGnomeIcon, cx, cy, size, alpha);
    }

    private void drawIconImage(Graphics2D g, BufferedImage icon, int cx, int cy, int size, float alpha)
    {
        if (icon == null) return;
        Composite previous = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, Math.max(0f, Math.min(1f, alpha))));
        g.drawImage(icon, cx - size / 2, cy - size / 2, size, size, null);
        g.setComposite(previous);
    }

    /** Draws the Jad encounter follow-up -- either the coin toll for bowing, or the "chose not to
     * bow" outcome. */
    private void renderJadOutcome(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getJadOutcomeBannerUntil(), GOLDEN_GNOME_OUTCOME_FADE_MS);
        if (alpha == null) return;

        String outcome = plugin.getJadOutcome();
        String rsn = plugin.getJadOutcomeRsn();
        boolean isLocal = isLocal(rsn);

        String text;
        if ("bowed".equals(outcome))
        {
            text = isLocal ? "Your loyalty will cost you " + RunePartyPlugin.JAD_BOW_COIN_COST + " coins!"
                : rsn != null ? rsn + "'s loyalty will cost them " + RunePartyPlugin.JAD_BOW_COIN_COST + " coins!" : null;
        }
        else if ("smashed".equals(outcome))
        {
            text = isLocal ? "You chose not to bow to Jad!" : rsn != null ? rsn + " chose not to bow to Jad!" : null;
        }
        else
        {
            text = null;
        }
        if (text == null) return;

        Node message = Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OUTCOME_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, drawableWidth() / 2, drawableHeight() / 3, safeTextWidth(), alpha);
    }

    /** Draws the "MINIGAME!" title card. A pure title card -- instructions are shown by the
     * ready-check screen instead. */
    private void renderMinigameBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        Node title = Text.rainbow("MINIGAME!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, safeTextWidth(), alpha);
    }

    /** The closing bookend to renderMinigameBanner's "MINIGAME!". */
    private void renderMinigameOverBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameOverBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        Node title = Text.rainbow("MINIGAME OVER!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, safeTextWidth(), alpha);
    }

    /** The "you're off" moment an arrival-gated mini-game's own gather message
     * (renderArrivalGatherMessage) never had -- see RunePartyPlugin#ARRIVAL_GATHER_KEYS's own doc
     * for exactly which mini-games this fires for. Reuses the same rainbow "BEGIN!" the
     * countdown-driven path's own renderMinigameCountdown pops in with, so every mini-game's round
     * genuinely starting reads the same way regardless of which path got it there. */
    private void renderArrivalRoundBeginBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getArrivalRoundBeginBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node title = Text.rainbow("BEGIN!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_COUNTDOWN_SIZE));
        Layout.renderCentered(g, title, centerX, y, safeTextWidth(), alpha);
    }

    /** Turf Wars' once-per-round reveal -- "This is your team color!" drawn in that player's own
     * assigned color. Deliberately doesn't name the color, since the text works the same for both
     * shared team colors and an odd round's solo seat colors. */
    private void renderTeamAssignedBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getTeamAssignedBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        String colorHex = plugin.getTeamAssignedBannerTeam();
        if (colorHex == null) return;
        Color color;
        try { color = Color.decode(colorHex); }
        catch (NumberFormatException e) { return; }

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = Text.of("This is your team color!").font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE)).color(color);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the mini-game selection spinner -- a rainbow prize wheel, one segment per registered
     * mini-game. The mini-game is already picked server-side; this only animates the reveal, always
     * landing on the correct segment. Shared wheel-drawing with renderItemSpinner via drawWheel. */
    private void renderMinigameSpinner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameSpinnerUntil(), WHEEL_FADE_MS);
        if (alpha == null) return;
        long now = System.currentTimeMillis();

        List<Minigame> all = Minigames.all();
        if (all.isEmpty()) return;

        Minigame selected = Minigames.get(plugin.getMinigameKey());
        List<Minigame> wheelEntries = selectWheelEntries(all, selected, plugin.getMinigameSpinnerStart());
        int targetIndex = Math.max(0, wheelEntries.indexOf(selected));

        long elapsed = now - plugin.getMinigameSpinnerStart();
        boolean spinning = elapsed < RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS;
        float rotationDeg = wheelRotationDeg(wheelEntries.size(), targetIndex, elapsed, RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS, spinning);
        float scale = wheelSettleScale(elapsed, RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS, spinning);

        drawWheel(g, wheelEntries, targetIndex, rotationDeg, scale, alpha, spinning, selected.getDisplayName());
    }

    /** Draws the "ITEM SPACE!" title card on ITEM_GRANTED -- same shape/treatment as
     * renderMinigameBanner's own "MINIGAME!", chained just ahead of the item wheel below the
     * identical way "MINIGAME!" leads into renderMinigameSpinner. */
    private void renderItemBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getItemBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        Node title = Text.rainbow("ITEM SPACE!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the Item Space wheel -- same shared drawWheel routine as renderMinigameSpinner, one
     * segment per registered item. Reveals "You got &lt;item&gt;!" once settled. */
    private void renderItemSpinner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getItemSpinnerUntil(), WHEEL_FADE_MS);
        if (alpha == null) return;
        long now = System.currentTimeMillis();

        List<Item> all = Items.all();
        if (all.isEmpty()) return;

        Item selected = Items.get(plugin.getItemGrantKey());
        List<Item> wheelEntries = selectWheelEntries(all, selected, plugin.getItemSpinnerStart());
        int targetIndex = Math.max(0, wheelEntries.indexOf(selected));

        long elapsed = now - plugin.getItemSpinnerStart();
        boolean spinning = elapsed < RunePartyPlugin.ITEM_SPINNER_SPIN_PHASE_MS;
        float rotationDeg = wheelRotationDeg(wheelEntries.size(), targetIndex, elapsed, RunePartyPlugin.ITEM_SPINNER_SPIN_PHASE_MS, spinning);
        float scale = wheelSettleScale(elapsed, RunePartyPlugin.ITEM_SPINNER_SPIN_PHASE_MS, spinning);

        String grantRsn = plugin.getItemGrantRsn();
        boolean isLocal = isLocal(grantRsn);
        String revealText = grantRsn == null ? null
            : isLocal ? "You got " + selected.getDisplayName() + "!"
            : grantRsn + " got " + selected.getDisplayName() + "!";

        drawWheel(g, wheelEntries, targetIndex, rotationDeg, scale, alpha, spinning, revealText);
    }

    /** Draws the item's own name plus a short line describing what it does, once the wheel above
     * has settled -- the item-flow counterpart to the mini-game's own ready-check screen following
     * its spinner, just a fixed-duration announcement instead of a persistent one (an item has no
     * "ready" step to wait on). Skips the subtitle entirely for an item with no description. */
    private void renderItemGrantDescription(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getItemGrantDescriptionUntil(), ITEM_USED_ANNOUNCE_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getItemGrantDescriptionRsn();
        Item item = Items.get(plugin.getItemGrantDescriptionKey());
        if (rsn == null || item == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        boolean isLocal = isLocal(rsn);
        String subtitle = item.getEffectDescription(isLocal);

        Text title = Text.of(item.getDisplayName()).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE)).color(WELCOME_TITLE_COLOR);
        Node banner = subtitle == null ? title
            : Box.column(title, Text.of(subtitle).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_SUBTITLE_SIZE)).color(Color.LIGHT_GRAY)).gap(10);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** Fires instead of renderItemSpinner when the mover is already at the item cap -- no wheel,
     * just a two-line explainer. */
    private void renderItemCapBlocked(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getItemCapBlockedUntil(), ITEM_CAP_BLOCKED_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getItemCapBlockedRsn();
        if (rsn == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;
        int cap = plugin.getItemCapBlockedCap();

        boolean isLocal = isLocal(rsn);

        String title = isLocal ? "You already have " + cap + " items!" : rsn + " already has " + cap + " items!";
        String subtitle = isLocal
            ? "You must use an item before you can receive any more."
            : "They must use an item before they can receive any more.";

        Node banner = Box.column(
                Text.of(title).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_CAP_BLOCKED_TITLE_SIZE)).color(Color.WHITE),
                Text.of(subtitle).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_CAP_BLOCKED_SUBTITLE_SIZE)).color(Color.LIGHT_GRAY))
            .gap(10);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws "You used/&lt;rsn&gt; used &lt;item&gt;!" plus that item's own subtitle. Only shown
     * for items that opt into a use announcement. */
    private void renderItemUsedAnnouncement(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getItemUsedAnnounceUntil(), ITEM_USED_ANNOUNCE_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getItemUsedAnnounceRsn();
        Item item = Items.get(plugin.getItemUsedAnnounceItemKey());
        if (rsn == null || item == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        boolean isLocal = isLocal(rsn);

        String verb = item.getUseAnnounceVerb();
        String titleText = (isLocal ? "You " + verb + " " : rsn + " " + verb + " ") + item.getDisplayName() + "!";
        String subtitle = item.getUseAnnouncementSubtitle(isLocal);

        Text title = Text.of(titleText).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE)).color(Color.WHITE);
        Node banner = subtitle == null ? title
            : Box.column(title, Text.of(subtitle).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_SUBTITLE_SIZE)).color(Color.LIGHT_GRAY)).gap(10);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws "You/&lt;caster&gt; cast Ice Barrage on &lt;target&gt;/you!" plus a matching subtitle.
     * Personalized for whichever role the local viewer is; a third party sees both names. */
    private void renderTeleBlockCastAnnouncement(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getTeleBlockCastUntil(), ITEM_USED_ANNOUNCE_FADE_MS);
        if (alpha == null) return;
        String caster = plugin.getTeleBlockCastCasterRsn();
        String target = plugin.getTeleBlockCastTargetRsn();
        if (caster == null || target == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        boolean isCaster = isLocal(caster);
        boolean isTarget = isLocal(target);

        String casterPart = isCaster ? "You" : caster;
        String targetPart = isTarget ? "you" : target;
        String title = casterPart + " cast Ice Barrage on " + targetPart + "!";
        String subtitle = isTarget ? "You will lose your next turn." : target + " will lose their next turn.";

        Node banner = Box.column(
                Text.of(title).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE)).color(Color.WHITE),
                Text.of(subtitle).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_SUBTITLE_SIZE)).color(Color.LIGHT_GRAY))
            .gap(10);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws "You/&lt;caster&gt; used Tele Other on &lt;target&gt;!" -- single line, no subtitle,
     * per this item's own confirmed design (see RunePartyPlugin#scheduleTeleOtherUsedAnnouncement's
     * own doc for why this differs from renderTeleBlockCastAnnouncement's two-line shape). */
    private void renderTeleOtherUsedAnnouncement(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getTeleOtherUsedUntil(), ITEM_USED_ANNOUNCE_FADE_MS);
        if (alpha == null) return;
        String caster = plugin.getTeleOtherUsedCasterRsn();
        String target = plugin.getTeleOtherUsedTargetRsn();
        if (caster == null || target == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        String casterPart = isLocal(caster) ? "You" : caster;
        String targetPart = isLocal(target) ? "you" : target;
        String title = casterPart + " used Tele Other on " + targetPart + "!";

        Node message = Text.of(title).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws "You/&lt;rsn&gt; landed on a Coin Trap!" -- no subtitle, since the actual coin numbers
     * show up in each player's own coin popup instead. */
    private void renderCoinTrapAnnouncement(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getCoinTrapAnnounceUntil(), COIN_TRAP_ANNOUNCE_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getCoinTrapAnnounceRsn();
        if (rsn == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        String title = (isLocal(rsn) ? "You" : rsn) + " landed on a Coin Trap!";

        Node message = Text.of(title).font(FontManager.getRunescapeBoldFont().deriveFont(COIN_TRAP_ANNOUNCE_TITLE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws "&lt;thief&gt; stole N coins from &lt;victim&gt;!" or "...a Golden Gnome from
     * &lt;victim&gt;!" -- shown to every player, not just the two involved, the instant a Wise Old
     * Man steal resolves (see WiseOldManPresentation#apply's own WISE_OLD_MAN_STOLEN handling). No
     * banner at all for a decline/timeout -- only a real steal is announced. */
    private void renderWiseOldManStolen(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWiseOldManStolenBannerUntil(), DEFAULT_FADE_MS);
        if (alpha == null) return;
        String thief = plugin.getWiseOldManStolenThief();
        String victim = plugin.getWiseOldManStolenVictim();
        String kind = plugin.getWiseOldManStolenKind();
        if (thief == null || victim == null || kind == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        String thiefLabel = isLocal(thief) ? "You" : thief;
        String victimLabel = isLocal(victim) ? "you" : victim;
        String what = "golden_gnome".equals(kind) ? "a Golden Gnome" : (plugin.getWiseOldManStolenAmount() + " coins");
        String title = thiefLabel + " stole " + what + " from " + victimLabel + "!";

        Node message = Text.of(title).font(FontManager.getRunescapeBoldFont().deriveFont(WISE_OLD_MAN_STOLEN_TITLE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the Item Shop purchase follow-up -- "You/&lt;rsn&gt; purchased &lt;item&gt;!",
     * "You/&lt;rsn&gt; can't afford &lt;item&gt;!", or "You/&lt;rsn&gt; can't afford any items!" --
     * addressed to whoever the outcome belongs to, shown to every player same as
     * renderGoldenGnomeOutcome's own doc describes for its own purchased/failed pair. No banner at
     * all for a decline/timeout -- only a real purchase attempt (successful or not), or saying
     * "Yes" to browsing with nothing actually affordable, is announced. */
    private void renderItemShopOutcome(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getItemShopOutcomeBannerUntil(), ITEM_SHOP_OUTCOME_FADE_MS);
        if (alpha == null) return;

        String outcome = plugin.getItemShopOutcome();
        String rsn = plugin.getItemShopOutcomeRsn();
        String itemDisplayName = plugin.getItemShopOutcomeItemDisplayName();
        boolean isLocal = isLocal(rsn);

        String text;
        Color color;
        if ("purchased".equals(outcome))
        {
            text = itemDisplayName == null ? null
                : isLocal ? "You purchased " + itemDisplayName + "!"
                : rsn != null ? rsn + " purchased " + itemDisplayName + "!" : null;
            color = WELCOME_TITLE_COLOR;
        }
        else if ("failed".equals(outcome))
        {
            text = itemDisplayName == null ? null
                : isLocal ? "You can't afford " + itemDisplayName + "!"
                : rsn != null ? rsn + " can't afford " + itemDisplayName + "!" : null;
            color = DICE_ROLL_BONUS_NEGATIVE_COLOR;
        }
        else if ("no_affordable_items".equals(outcome))
        {
            text = isLocal ? "You can't afford any items!"
                : rsn != null ? rsn + " can't afford any items!" : null;
            color = DICE_ROLL_BONUS_NEGATIVE_COLOR;
        }
        else
        {
            text = null;
            color = Color.WHITE;
        }
        if (text == null) return;

        Node message = Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(ITEM_SHOP_OUTCOME_SIZE)).color(color);
        Layout.renderCentered(g, message, drawableWidth() / 2, drawableHeight() / 3, safeTextWidth(), alpha);
    }

    /** How far the wheel has rotated at {@code elapsed} into its spin -- eased to a stop, then held
     * fixed on the target once settled. Shared by renderMinigameSpinner and renderItemSpinner. */
    private static float wheelRotationDeg(int entryCount, int targetIndex, long elapsed, long spinPhaseMs, boolean spinning)
    {
        float segmentDeg = 360f / entryCount;
        float targetCenterAngle = targetIndex * segmentDeg + segmentDeg / 2f;
        float totalRotationDeg = (360f - targetCenterAngle) + WHEEL_EXTRA_SPINS * 360f;

        if (!spinning) return totalRotationDeg;

        float t = elapsed / (float) spinPhaseMs;
        float eased = 1f - (float) Math.pow(1f - t, 3); // ease-out cubic, slows into the landing
        return eased * totalRotationDeg;
    }

    /** The brief overshoot-then-settle scale pop right after the wheel stops. 1f while still
     * spinning or once the pop's finished. */
    private static float wheelSettleScale(long elapsed, long spinPhaseMs, boolean spinning)
    {
        if (spinning) return 1f;
        long sinceSettle = elapsed - spinPhaseMs;
        if (sinceSettle >= WHEEL_SETTLE_POP_MS) return 1f;
        float t = sinceSettle / (float) WHEEL_SETTLE_POP_MS;
        return 1.25f - 0.25f * t;
    }

    /** Draws one frame of a spinner wheel -- wedges, border, each entry's icon, a fixed pointer,
     * and (once settled) {@code revealText} underneath in plain yellow. Shared by
     * renderMinigameSpinner and renderItemSpinner. */
    private <T extends WheelEntry> void drawWheel(Graphics2D g, List<T> wheelEntries, int targetIndex, float rotationDeg, float scale, float alpha, boolean spinning, String revealText)
    {
        int n = wheelEntries.size();
        float segmentDeg = 360f / n;

        int cx = drawableWidth() / 2;
        int cy = drawableHeight() / 2;
        float radius = WHEEL_RADIUS * scale;

        Graphics2D wheel = (Graphics2D) g.create();
        wheel.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        wheel.rotate(Math.toRadians(rotationDeg), cx, cy);

        for (int i = 0; i < n; i++)
        {
            Color base = RAINBOW_LETTER_COLORS[i % RAINBOW_LETTER_COLORS.length];
            drawWheelWedge(wheel, cx, cy, radius, i * segmentDeg, (i + 1) * segmentDeg, RunePartyRender.withAlpha(base, alpha * 0.85f));
        }
        wheel.setStroke(new BasicStroke(2f));
        wheel.setColor(RunePartyRender.withAlpha(Color.WHITE, alpha));
        wheel.drawOval(Math.round(cx - radius), Math.round(cy - radius), Math.round(radius * 2), Math.round(radius * 2));
        for (int i = 0; i < n; i++)
        {
            float center = i * segmentDeg + segmentDeg / 2f;
            Point2D.Float p = pointOnCircle(cx, cy, radius * 0.62f, center);
            // Counter-rotate each icon so it stays upright while orbiting the spinning wheel,
            // instead of tumbling with its wedge (the standard prize-wheel look).
            Graphics2D icon = (Graphics2D) wheel.create();
            icon.rotate(Math.toRadians(-rotationDeg), p.x, p.y);
            wheelEntries.get(i).drawIcon(icon, Math.round(p.x), Math.round(p.y), Math.round(WHEEL_ICON_SIZE), alpha);
            icon.dispose();
        }
        wheel.dispose();

        // Fixed pointer above the wheel -- doesn't rotate, the wheel spins under it.
        int pointerTip = Math.round(cy - radius - 6);
        Polygon pointer = new Polygon();
        pointer.addPoint(cx - 10, pointerTip - 16);
        pointer.addPoint(cx + 10, pointerTip - 16);
        pointer.addPoint(cx, pointerTip);
        g.setColor(RunePartyRender.withAlpha(WHEEL_POINTER_COLOR, alpha));
        g.fillPolygon(pointer);

        if (!spinning && revealText != null)
        {
            g.setFont(MARIO_PARTY_FONT.deriveFont(WHEEL_NAME_SIZE));
            drawCenteredText(g, revealText, cx, Math.round(cy + WHEEL_RADIUS + 50), RAINBOW_YELLOW, alpha);
        }
    }

    /** Picks which entries appear on the wheel -- every one if there are WHEEL_MAX_SEGMENTS or
     * fewer, otherwise {@code selected} plus a random sample of the rest, seeded by {@code seed} so
     * the sample stays stable across frames of the same spin. */
    private <T extends WheelEntry> List<T> selectWheelEntries(List<T> all, T selected, long seed)
    {
        if (all.size() <= WHEEL_MAX_SEGMENTS) return all;

        List<T> others = new ArrayList<>(all);
        others.remove(selected);
        Collections.shuffle(others, new Random(seed));

        List<T> entries = new ArrayList<>();
        entries.add(selected);
        entries.addAll(others.subList(0, WHEEL_MAX_SEGMENTS - 1));
        return entries;
    }

    /** Fills one wedge of the spinner wheel -- a triangle fan approximated with short line segments
     * between {@code startAngleDeg} and {@code endAngleDeg} (clockwise from straight up, see
     * pointOnCircle). */
    private void drawWheelWedge(Graphics2D g, int cx, int cy, float radius, float startAngleDeg, float endAngleDeg, Color fill)
    {
        Path2D.Float path = new Path2D.Float();
        path.moveTo(cx, cy);
        int steps = Math.max(2, Math.round((endAngleDeg - startAngleDeg) / 6f));
        for (int i = 0; i <= steps; i++)
        {
            float a = startAngleDeg + (endAngleDeg - startAngleDeg) * i / steps;
            Point2D.Float p = pointOnCircle(cx, cy, radius, a);
            path.lineTo(p.x, p.y);
        }
        path.closePath();
        g.setColor(fill);
        g.fill(path);
    }

    /** A point on a circle of {@code radius} centered at (cx, cy), at {@code angleDeg} measured
     * clockwise from straight up (0 = top, 90 = right, 180 = bottom, 270 = left). */
    private static Point2D.Float pointOnCircle(int cx, int cy, float radius, float angleDeg)
    {
        double rad = Math.toRadians(angleDeg);
        return new Point2D.Float((float) (cx + radius * Math.sin(rad)), (float) (cy - radius * Math.cos(rad)));
    }

    /** Draws the mini-game ready-check screen -- name, instructions, a "YES emote when ready"
     * instruction, then every seated player with a Ready/Waiting status. Kept visible briefly after
     * the last player readies up, so everyone gets a beat to see the full "Ready!" list before the
     * countdown replaces it. */
    private void renderMinigameReadyCheck(Graphics2D g)
    {
        if (!plugin.isMinigameActive()) return;

        boolean countdownRevealed = plugin.isMinigameCountdownStarted()
            && (plugin.isMinigameCountdownSkippedForClient() || plugin.getMinigameCountdownBannerUntil() != 0);
        if (countdownRevealed) return;

        long now = System.currentTimeMillis();
        long spinnerUntil = plugin.getMinigameSpinnerUntil();
        boolean spinnerDone = plugin.isMinigameSpinnerSkippedForClient() || (spinnerUntil != 0 && now >= spinnerUntil);
        if (!spinnerDone) return;

        float alpha = MINIGAME_READY_CHECK_MIN_ALPHA + (1f - MINIGAME_READY_CHECK_MIN_ALPHA) * BannerAnim.pulse(now, MINIGAME_READY_CHECK_PULSE_PERIOD_MS);

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        int maxWidth = safeTextWidth();

        String displayName = plugin.getMinigameDisplayName();
        if (displayName != null)
        {
            Node title = Text.of(displayName).font(MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE)).color(WELCOME_TITLE_COLOR);
            Layout.renderCentered(g, title, centerX, y, maxWidth, alpha);
        }

        // Wrapped since a mini-game's instructions can run long; everything below is laid out
        // relative to afterInstructionsY so it shifts down instead of overlapping.
        int afterInstructionsY = y + 30;
        String instructions = plugin.getMinigameInstructions();
        if (instructions != null)
        {
            Text instructionsText = Text.of(instructions)
                .font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE))
                .color(Color.WHITE)
                .wrap(Math.min(READY_CHECK_INSTRUCTIONS_MAX_WIDTH, maxWidth))
                .lineHeight(READY_CHECK_INSTRUCTIONS_LINE_HEIGHT);
            Dimension size = Layout.renderCentered(g, instructionsText, centerX, y + 30, maxWidth, alpha);
            afterInstructionsY = y + 30 + size.height - READY_CHECK_INSTRUCTIONS_LINE_HEIGHT;
        }

        Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        Node emoteLine = Line.of(
            Segment.plain("'", emoteFont, Color.LIGHT_GRAY),
            Segment.rainbow("YES", emoteWordFont, RAINBOW_LETTER_COLORS),
            Segment.plain("' emote when you're ready!", emoteFont, Color.LIGHT_GRAY));
        Layout.renderCentered(g, emoteLine, centerX, afterInstructionsY + 36, maxWidth, alpha);

        Set<String> ready = plugin.getMinigameReadyRsns();
        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator.comparing((RosterReducer.RosterEntry e) -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(MINIGAME_READY_CHECK_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(MINIGAME_READY_CHECK_LINE_SIZE);
        Table table = Table.rows().rowHeight(MINIGAME_READY_CHECK_LINE_HEIGHT);
        for (RosterReducer.RosterEntry entry : players)
        {
            boolean isReady = ready.contains(entry.rsn.toLowerCase());
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            table.addRow(
                Segment.plain("", nameFont, Color.LIGHT_GRAY),
                Segment.plain(entry.rsn, nameFont, nameColor),
                Segment.plain(isReady ? "   Ready!" : "   Waiting...", statsFont,
                    isReady ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR));
        }
        Layout.renderCentered(g, table, centerX, afterInstructionsY + 70, maxWidth, alpha);
    }

    /** Draws the "3... 2... 1... BEGIN!" countdown once everyone's ready. Only a client watching
     * live sees it -- a reconnecting client skips straight to playable. */
    private void renderMinigameCountdown(Graphics2D g)
    {
        long until = plugin.getMinigameCountdownBannerUntil();
        if (until == 0) return;
        long remaining = until - System.currentTimeMillis();
        if (remaining <= 0) return;

        long elapsed = RunePartyPlugin.MINIGAME_COUNTDOWN_DURATION_MS - remaining;
        int tick = (int) (elapsed / 1000); // 0, 1, 2 -> 3, 2, 1 ; 3 -> BEGIN!
        int number = 3 - tick;

        long withinTick = elapsed % 1000;
        float scale = withinTick < MINIGAME_COUNTDOWN_POP_MS
            ? 1.4f - 0.4f * (withinTick / (float) MINIGAME_COUNTDOWN_POP_MS)
            : 1f;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = number >= 1
            ? Text.of(String.valueOf(number)).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_COUNTDOWN_SIZE * scale)).color(MINIGAME_COUNTDOWN_NUMBER_COLOR)
            : Text.rainbow("BEGIN!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_COUNTDOWN_SIZE * scale));
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), 1f);
    }

    /** Replacement for renderMinigameCountdown for mini-games whose round starts once every player
     * has walked into the arena, rather than on a fixed clock -- shows a persistent instruction
     * instead of a countdown that could tick down before everyone's actually in position. */
    private void renderArrivalGatherMessage(Graphics2D g)
    {
        boolean countdownRevealed = plugin.isMinigameCountdownStarted()
            && (plugin.isMinigameCountdownSkippedForClient() || plugin.getMinigameCountdownBannerUntil() != 0);
        if (!countdownRevealed) return;
        if (plugin.isMinigameRoundBegun()) return;

        long now = System.currentTimeMillis();
        float alpha = MINIGAME_READY_CHECK_MIN_ALPHA + (1f - MINIGAME_READY_CHECK_MIN_ALPHA) * BannerAnim.pulse(now, MINIGAME_READY_CHECK_PULSE_PERIOD_MS);

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        String text;
        if (RunePartyPlugin.JADDY_KEY.equals(plugin.getMinigameKey()))
        {
            text = "Choose a side -- stand in the pink or teal zone!";
        }
        else if (RunePartyPlugin.DANCE_DANCE_RUNESCAPE_KEY.equals(plugin.getMinigameKey()))
        {
            text = "Everyone must stand on the dance floor!";
        }
        else if (RunePartyPlugin.RAINBOW_RUSH_KEY.equals(plugin.getMinigameKey()))
        {
            text = "Everyone must stand on the Start tile!";
        }
        else
        {
            text = "All players must stand within the arena!";
        }

        Node message = Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Rainbow Rush's own "get ready" beat, shown center-screen right where renderArrivalGatherMessage
     * just was -- the two never overlap (that one hides the instant MINIGAME_ROUND_BEGIN lands, this
     * one only ever shows after). Three dots in a housing, lighting up red -> orange -> green in
     * turn, RunePartyPlugin#RAINBOW_RUSH_LIGHT_PHASE_MS apiece, "Begin!" popping in alongside green.
     * Timed purely off RainbowRushPresentation#getRoundStartAt (every client receives
     * MINIGAME_ROUND_BEGIN at essentially the same moment, so no dedicated server event is needed
     * just to synchronize a 3-second cosmetic). Disappears once the sequence finishes --
     * RainbowRushPresentation#onTick's own guard is timed off this exact same window, so actual
     * play is already live underneath by the time this vanishes. */
    private void renderRainbowRushTrafficLight(Graphics2D g)
    {
        if (!plugin.isRainbowRushActive() || !plugin.isMinigameRoundBegun()) return;
        long startAt = plugin.getRainbowRushRoundStartAt();
        if (startAt == 0) return;
        long elapsed = System.currentTimeMillis() - startAt;
        if (elapsed >= RunePartyPlugin.RAINBOW_RUSH_TRAFFIC_LIGHT_MS) return;

        int phase = (int) Math.min(2, elapsed / RunePartyPlugin.RAINBOW_RUSH_LIGHT_PHASE_MS); // 0=red, 1=orange, 2=green

        int centerX = drawableWidth() / 2;
        int centerY = drawableHeight() / 2;

        int boxWidth = TRAFFIC_LIGHT_DOT_RADIUS * 2 + TRAFFIC_LIGHT_PADDING * 2;
        int boxHeight = TRAFFIC_LIGHT_DOT_RADIUS * 6 + TRAFFIC_LIGHT_GAP * 2 + TRAFFIC_LIGHT_PADDING * 2;
        int boxLeft = centerX - boxWidth / 2;
        int boxTop = centerY - boxHeight / 2 - 30;

        g.setColor(TRAFFIC_LIGHT_HOUSING_COLOR);
        g.fillRoundRect(boxLeft, boxTop, boxWidth, boxHeight, TRAFFIC_LIGHT_ARC, TRAFFIC_LIGHT_ARC);
        g.setColor(TRAFFIC_LIGHT_HOUSING_BORDER_COLOR);
        g.setStroke(new BasicStroke(2f));
        g.drawRoundRect(boxLeft, boxTop, boxWidth, boxHeight, TRAFFIC_LIGHT_ARC, TRAFFIC_LIGHT_ARC);

        Color[] litColors = { RunePartyColor.RED.awt, RunePartyColor.ORANGE.awt, RunePartyColor.GREEN.awt };
        for (int i = 0; i < litColors.length; i++)
        {
            int dotCenterY = boxTop + TRAFFIC_LIGHT_PADDING + TRAFFIC_LIGHT_DOT_RADIUS
                + i * (TRAFFIC_LIGHT_DOT_RADIUS * 2 + TRAFFIC_LIGHT_GAP);
            drawTrafficLightDot(g, centerX, dotCenterY, litColors[i], i == phase);
        }

        if (phase == 2)
        {
            long withinPhase = elapsed - 2L * RunePartyPlugin.RAINBOW_RUSH_LIGHT_PHASE_MS;
            float scale = withinPhase < MINIGAME_COUNTDOWN_POP_MS
                ? 1.4f - 0.4f * (withinPhase / (float) MINIGAME_COUNTDOWN_POP_MS)
                : 1f;
            g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_COUNTDOWN_SIZE * 0.55f * scale));
            drawCenteredRainbowText(g, "Begin!", RAINBOW_LETTER_COLORS, centerX, boxTop + boxHeight + 50, 1f);
        }
    }

    /** One traffic-light dot -- a bright glow halo plus full-brightness fill while lit, a plain
     * dark/desaturated fill while not, same "lit vs unclaimed" contrast TileOverlay's own claimed-
     * tile fills already lean on. */
    private void drawTrafficLightDot(Graphics2D g, int centerX, int centerY, Color color, boolean lit)
    {
        if (lit)
        {
            g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), TRAFFIC_LIGHT_GLOW_ALPHA));
            int glowRadius = TRAFFIC_LIGHT_DOT_RADIUS + TRAFFIC_LIGHT_GLOW_EXTRA_PX;
            g.fillOval(centerX - glowRadius, centerY - glowRadius, glowRadius * 2, glowRadius * 2);
            g.setColor(color);
        }
        else
        {
            g.setColor(new Color(color.getRed() / 4, color.getGreen() / 4, color.getBlue() / 4));
        }
        g.fillOval(centerX - TRAFFIC_LIGHT_DOT_RADIUS, centerY - TRAFFIC_LIGHT_DOT_RADIUS,
            TRAFFIC_LIGHT_DOT_RADIUS * 2, TRAFFIC_LIGHT_DOT_RADIUS * 2);
        g.setColor(TRAFFIC_LIGHT_HOUSING_BORDER_COLOR);
        g.setStroke(new BasicStroke(2f));
        g.drawOval(centerX - TRAFFIC_LIGHT_DOT_RADIUS, centerY - TRAFFIC_LIGHT_DOT_RADIUS,
            TRAFFIC_LIGHT_DOT_RADIUS * 2, TRAFFIC_LIGHT_DOT_RADIUS * 2);
    }

    /** Draws the Who's Your Jaddy? duel-resolved reveal, up for JADDY_RESOLVED_BANNER_DURATION_MS
     * once JADDY_DUEL_RESOLVED lands (matched to JADDY_DEATH_HOLD_MS, see that field's own doc, so
     * this and the winning Jad's own standing idle loop disappear together). Personalized for
     * whoever picked a side -- "Your team's Jad won!"/"The other team's Jad won!" -- based on which
     * zone the local player was standing in at the exact resolution instant (see
     * getJaddyResolvedLocalZoneColor's own doc), falling back to the plain "&lt;color&gt; Won!" for
     * everyone else (spectators, or anyone who wasn't standing in either zone then). Individual coin
     * gains still come from the ordinary COINS_CHANGED popup, same as every other flat-payout
     * mini-game -- this banner is just the "who won" announcement. */
    private void renderJaddyResolvedBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getJaddyResolvedBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        String colorHex = plugin.getJaddyResolvedWinningColor();
        if (colorHex == null) return;
        Color color;
        try { color = Color.decode(colorHex); }
        catch (NumberFormatException e) { return; }

        String localZoneHex = plugin.getJaddyResolvedLocalZoneColor();
        String text;
        if (localZoneHex == null)
        {
            String label = color.getRGB() == RunePartyPlugin.TEAM_A_COLOR.getRGB() ? "Pink"
                : color.getRGB() == RunePartyPlugin.TEAM_B_COLOR.getRGB() ? "Teal" : "That Jad";
            text = label + " Won!";
        }
        else
        {
            boolean localWon;
            try { localWon = Color.decode(localZoneHex).getRGB() == color.getRGB(); }
            catch (NumberFormatException e) { localWon = false; }
            text = localWon ? "Your team's Jad won!" : "The other team's Jad won!";
        }

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE)).color(color);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the current True or False round's question, a live countdown to its answer deadline,
     * and a "who's answered" tally. The countdown number stays hidden ("Get ready...") for a brief
     * reading period before the real answer clock starts. */
    private void renderTrueOrFalseQuestion(Graphics2D g)
    {
        if (!plugin.isMinigamePlayable()) return;
        if (System.currentTimeMillis() < plugin.getTrueOrFalseRevealUntil()) return;
        String question = plugin.getTrueOrFalseQuestion();
        if (question == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3 - 20;
        int maxWidth = safeTextWidth();

        Node roundLabel = Text.of("Round " + plugin.getTrueOrFalseRoundNumber() + "/5")
            .font(FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_ROUND_LABEL_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, roundLabel, centerX, y, maxWidth, 1f);

        Text questionText = Text.of(question)
            .font(FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_QUESTION_SIZE))
            .color(Color.WHITE)
            .wrap(Math.min(TRUE_OR_FALSE_QUESTION_MAX_WIDTH, maxWidth))
            .lineHeight(TRUE_OR_FALSE_QUESTION_LINE_HEIGHT);
        Dimension questionSize = Layout.renderCentered(g, questionText, centerX, y + 34, maxWidth, 1f);
        int afterQuestionY = y + 34 + questionSize.height;

        long now = System.currentTimeMillis();
        if (now < plugin.getTrueOrFalseAnswerWindowStartsAt())
        {
            Node getReady = Text.of("Get ready...").font(FontManager.getRunescapeSmallFont()).color(Color.LIGHT_GRAY);
            Layout.renderCentered(g, getReady, centerX, afterQuestionY + 34, maxWidth, 1f);
        }
        else
        {
            long remainingMs = plugin.getTrueOrFalseRoundEndsAt() - now;
            int secondsLeft = (int) Math.max(0, Math.ceil(remainingMs / 1000.0));
            Node countdown = Text.of(String.valueOf(secondsLeft)).font(MARIO_PARTY_FONT.deriveFont(TRUE_OR_FALSE_COUNTDOWN_SIZE)).color(TRUE_OR_FALSE_COUNTDOWN_COLOR);
            Layout.renderCentered(g, countdown, centerX, afterQuestionY + 40, maxWidth, 1f);
        }

        Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        int emoteY = afterQuestionY + 72;
        Node yesLine = Line.of(
            Segment.plain("'", emoteFont, Color.LIGHT_GRAY),
            Segment.rainbow("YES", emoteWordFont, RAINBOW_LETTER_COLORS),
            Segment.plain("' = True", emoteFont, Color.LIGHT_GRAY));
        Layout.renderCentered(g, yesLine, centerX - 100, emoteY, maxWidth, 1f);
        Node noLine = Line.of(
            Segment.plain("'", emoteFont, Color.LIGHT_GRAY),
            Segment.rainbow("NO", emoteWordFont, RAINBOW_LETTER_COLORS),
            Segment.plain("' = False", emoteFont, Color.LIGHT_GRAY));
        Layout.renderCentered(g, noLine, centerX + 100, emoteY, maxWidth, 1f);

        Set<String> answered = plugin.getTrueOrFalseAnsweredRsns();
        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator.comparing((RosterReducer.RosterEntry e) -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Table table = Table.rows().rowHeight(ROUND_COMPLETE_LINE_HEIGHT);
        for (RosterReducer.RosterEntry entry : players)
        {
            boolean isAnswered = answered.contains(entry.rsn.toLowerCase());
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            table.addRow(
                Segment.plain("", nameFont, Color.LIGHT_GRAY),
                Segment.plain(entry.rsn, nameFont, nameColor),
                Segment.plain(isAnswered ? "   Answered!" : "   Waiting...", statsFont,
                    isAnswered ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR));
        }
        Layout.renderCentered(g, table, centerX, emoteY + 36, maxWidth, 1f);
    }

    /** Crab Rave's own big centered countdown, ticking down from getCrabRaveEndsAt() -- same
     * secondsLeft math renderTrueOrFalseQuestion's own countdown number already uses. No
     * instructional subtitle here -- the mini-game's own instructions banner (shown once, at
     * MINIGAME_STARTED) already told players to dance, so a second persistent reminder for the
     * whole round would just be clutter (see CrabRaveHudOverlay for the local dance counter that
     * replaces it as the round's own persistent UI). Shown for the whole round (no separate "get
     * ready" phase -- the round begins on the standard "3...2...1...BEGIN!" spinner, not an arrival
     * gate, see minigames/crab_rave.py's own doc), and stops the instant the clock actually runs
     * out rather than lingering into the reveal. */
    private void renderCrabRaveCountdown(Graphics2D g)
    {
        if (!plugin.isCrabRaveActive()) return;
        long endsAt = plugin.getCrabRaveEndsAt();
        if (endsAt == 0) return;

        long now = System.currentTimeMillis();
        if (now >= endsAt) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        int secondsLeft = (int) Math.max(0, Math.ceil((endsAt - now) / 1000.0));
        Node message = Text.of(String.valueOf(secondsLeft)).font(MARIO_PARTY_FONT.deriveFont(TRUE_OR_FALSE_COUNTDOWN_SIZE)).color(TRUE_OR_FALSE_COUNTDOWN_COLOR);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), 1f);
    }

    /** Brutus Attack's own role-aware replacement for renderArrivalGatherMessage -- shown before
     * EACH of its 3 rounds, not just the mini-game's first, since every round needs Brutus and
     * every surviving target to walk back to their own zone (see brutus_attack.py's own doc).
     * isMinigameRoundBegun() (every other arena mini-game's own hide condition) is a one-shot
     * latch for the whole mini-game instance, so it can't tell rounds 2/3 apart from round 1 --
     * this instead hides only while a dash is actually live right now (getBrutusAttackDashEndsAt
     * in the future), reappearing the instant that window closes, whether by a catch, a miss, or
     * the clock running out. A caught-early round (Brutus lands a hit well before the full 10
     * seconds elapse) can leave this hidden a few seconds longer than ideal, since dashEndsAt
     * itself doesn't move up early -- a minor cosmetic gap, not worth a dedicated "round resolved"
     * server signal just for this banner's own timing. */
    private void renderBrutusAttackGatherMessage(Graphics2D g)
    {
        boolean countdownRevealed = plugin.isMinigameCountdownStarted()
            && (plugin.isMinigameCountdownSkippedForClient() || plugin.getMinigameCountdownBannerUntil() != 0);
        if (!countdownRevealed) return;

        long now = System.currentTimeMillis();
        long dashEndsAt = plugin.getBrutusAttackDashEndsAt();
        if (dashEndsAt != 0 && now < dashEndsAt) return;

        float alpha = MINIGAME_READY_CHECK_MIN_ALPHA + (1f - MINIGAME_READY_CHECK_MIN_ALPHA) * BannerAnim.pulse(now, MINIGAME_READY_CHECK_PULSE_PERIOD_MS);
        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        String text = plugin.isLocalPlayerAssignedBrutus()
            ? "Head to your own zone (pink)!"
            : "Head to the target zone (teal)!";

        Node message = Text.of(text).font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Brutus Attack's own big centered countdown for the current round's 10-second dash window,
     * same secondsLeft math renderCrabRaveCountdown's own countdown number already uses. Hidden as
     * soon as either becomes true: isBrutusAttackDashLandedThisRound (Brutus's own dash has
     * landed -- the moment that actually mattered has already happened, even though the server
     * itself still waits out a brief grace period afterward for a target's own independent
     * elimination self-report before the round's outcome is actually known) or
     * isBrutusAttackDashResolvedThisRound (the round's outcome landed even without a dash ever
     * being reported -- Brutus simply never entered the zone before the clock ran out). Reappears
     * once the next round's own arrival gate re-stamps getBrutusAttackDashEndsAt. */
    private void renderBrutusAttackDashCountdown(Graphics2D g)
    {
        if (!plugin.isBrutusAttackActive()) return;
        if (plugin.isBrutusAttackDashLandedThisRound() || plugin.isBrutusAttackDashResolvedThisRound()) return;
        long endsAt = plugin.getBrutusAttackDashEndsAt();
        if (endsAt == 0) return;

        long now = System.currentTimeMillis();
        if (now >= endsAt) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        int secondsLeft = (int) Math.max(0, Math.ceil((endsAt - now) / 1000.0));
        Node message = Text.of(String.valueOf(secondsLeft)).font(MARIO_PARTY_FONT.deriveFont(TRUE_OR_FALSE_COUNTDOWN_SIZE)).color(TRUE_OR_FALSE_COUNTDOWN_COLOR);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), 1f);
    }

    /** Brutus Attack's own "HIT!"/"MISS!" flash -- fires once per resolved dash (a catch or a
     * miss), same Mario Party rainbow-letter treatment "MINIGAME!"/"GAME OVER!" already use (see
     * renderMinigameBanner). Shown at the same spot the dash countdown (renderBrutusAttackDashCountdown)
     * just was -- the two never overlap, since the countdown hides the instant the dash resolves,
     * right when this one arms. */
    private void renderBrutusAttackDashResult(Graphics2D g)
    {
        if (!plugin.isBrutusAttackActive()) return;
        Float alpha = BannerAnim.fadeAlpha(plugin.getBrutusAttackDashResultBannerUntil(), DEFAULT_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        String text = plugin.isBrutusAttackDashResultHit() ? "HIT!" : "MISS!";
        Node message = Text.rainbow(text, RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the previous True or False round's reveal -- the correct answer, plus every player's
     * own answer and whether it was correct. Fixed-duration, since the answer doesn't change once
     * revealed. */
    private void renderTrueOrFalseReveal(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getTrueOrFalseRevealUntil(), TRUE_OR_FALSE_REVEAL_FADE_MS);
        if (alpha == null) return;
        Boolean correctAnswer = plugin.getTrueOrFalseLastCorrectAnswer();
        if (correctAnswer == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;
        int maxWidth = safeTextWidth();

        String title = "The answer was " + (correctAnswer ? "TRUE" : "FALSE") + "!";
        Color titleColor = correctAnswer ? TRUE_OR_FALSE_TRUE_COLOR : TRUE_OR_FALSE_FALSE_COLOR;
        Node titleNode = Text.of(title).font(FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_REVEAL_TITLE_SIZE)).color(titleColor);
        Layout.renderCentered(g, titleNode, centerX, y, maxWidth, alpha);

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_REVEAL_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(TRUE_OR_FALSE_REVEAL_LINE_SIZE);

        List<TrueOrFalseResult> results = plugin.getTrueOrFalseLastResults();
        Table table = Table.rows().rowHeight(TRUE_OR_FALSE_REVEAL_LINE_HEIGHT);
        for (TrueOrFalseResult result : results)
        {
            String answerText = result.answer == null ? "no answer" : (result.answer ? "True" : "False");
            String status = "   " + answerText + (result.correct ? " -- correct!" : " -- wrong");
            Color statusColor = result.correct ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR;
            table.addRow(
                Segment.plain(result.rsn, nameFont, Color.LIGHT_GRAY),
                Segment.plain(status, statsFont, statusColor));
        }
        Layout.renderCentered(g, table, centerX, y + 36, maxWidth, alpha);
    }

    /** Draws the mini-game final-score recap -- "FINAL SCORE", then every seated player with the
     * score they earned this round, highest first, each name in its own seat color so it reads
     * like a personal standings list rather than a flat report.
     * Score's own meaning varies per mini-game (unique tiles clicked, anchovies caught, correct
     * answers, ...) -- this banner doesn't need to know which, it just shows the raw number every
     * mini-game's own pay_out/pay_out_flat/pay_out_top already produces in MINIGAME_ENDED's
     * "results" list. Shown after renderMinigameOverBanner and before renderMinigameRewardsBanner,
     * so players see how they did before finding out what (if anything) that earned them. */
    private void renderMinigameScoreBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameScoreBannerUntil(), MINIGAME_REWARDS_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;
        int maxWidth = safeTextWidth();

        Node title = Text.rainbow("FINAL SCORE", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_REWARDS_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, maxWidth, alpha);

        Map<String, Integer> scoreByRsn = new HashMap<>();
        for (MinigameScore score : plugin.getMinigameScores())
        {
            scoreByRsn.put(score.rsn.toLowerCase(), score.score);
        }

        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> scoreByRsn.getOrDefault(e.rsn.toLowerCase(), 0))
            .reversed()
            .thenComparing(e -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(MINIGAME_REWARDS_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(MINIGAME_REWARDS_LINE_SIZE);
        Table table = Table.rows().rowHeight(MINIGAME_REWARDS_LINE_HEIGHT);
        for (RosterReducer.RosterEntry entry : players)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            table.addRow(
                Segment.plain("", nameFont, Color.LIGHT_GRAY),
                Segment.plain(entry.rsn, nameFont, nameColor),
                Segment.plain("   " + scoreByRsn.getOrDefault(entry.rsn.toLowerCase(), 0) + " pts", statsFont, Color.LIGHT_GRAY));
        }
        Layout.renderCentered(g, table, centerX, y + 40, maxWidth, alpha);
    }

    /** Draws the mini-game rewards recap -- "REWARDS", then every seated player with the coins they
     * received, highest reward first ("no reward" in gray otherwise). Shown before
     * renderRoundCompleteBanner. */
    private void renderMinigameRewardsBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameRewardsBannerUntil(), MINIGAME_REWARDS_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;
        int maxWidth = safeTextWidth();

        Node title = Text.rainbow("REWARDS", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(MINIGAME_REWARDS_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, maxWidth, alpha);

        Map<String, Integer> rewardByRsn = new HashMap<>();
        for (MinigameReward reward : plugin.getMinigameRewards())
        {
            rewardByRsn.put(reward.rsn.toLowerCase(), reward.coins);
        }

        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> rewardByRsn.getOrDefault(e.rsn.toLowerCase(), 0))
            .reversed()
            .thenComparing(e -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(MINIGAME_REWARDS_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(MINIGAME_REWARDS_LINE_SIZE);
        Table table = Table.rows().rowHeight(MINIGAME_REWARDS_LINE_HEIGHT);
        for (RosterReducer.RosterEntry entry : players)
        {
            Integer reward = rewardByRsn.get(entry.rsn.toLowerCase());
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            table.addRow(
                Segment.plain("", nameFont, Color.LIGHT_GRAY),
                Segment.plain(entry.rsn, nameFont, nameColor),
                Segment.plain(reward != null ? "   +" + reward + " coins" : "   no reward", statsFont,
                    reward != null ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR));
        }
        Layout.renderCentered(g, table, centerX, y + 40, maxWidth, alpha);
    }

    /** Draws the post-round recap -- "ROUND x" (the upcoming round), "Current Standings", then every
     * seated player ranked by Golden Gnomes (coins as tiebreak), each in their own seat color. */
    private void renderRoundCompleteBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getRoundCompleteBannerUntil(), ROUND_COMPLETE_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;
        int maxWidth = safeTextWidth();

        Node title = Text.rainbow("ROUND " + plugin.getRoundCompleteRoundNumber(), RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(ROUND_COMPLETE_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, maxWidth, alpha);

        Node subtitle = Text.of("Current Standings").font(FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_SUBTITLE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, subtitle, centerX, y + 34, maxWidth, alpha);

        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> e.goldenGnomeCount).reversed()
            .thenComparing(Comparator.comparingInt((RosterReducer.RosterEntry e) -> e.coins).reversed()));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Table table = Table.rows().rowHeight(ROUND_COMPLETE_LINE_HEIGHT);
        int rank = 1;
        for (RosterReducer.RosterEntry entry : players)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            table.addRow(
                Segment.plain("#" + rank + "  ", nameFont, Color.LIGHT_GRAY),
                Segment.plain(entry.rsn, nameFont, nameColor),
                Segment.plain("   " + entry.goldenGnomeCount + " GG, " + entry.coins + " coins", statsFont, Color.LIGHT_GRAY));
            rank++;
        }
        Layout.renderCentered(g, table, centerX, y + 66, maxWidth, alpha);
    }

    /** The rainbow "GOLDEN GNOME AWARDS!" title -- the true first beat of the end-game ceremony
     * now, gated behind whatever the final mini-game's own round-complete/rewards recap was still
     * reserving (see CeremonyPresentation#handleCeremonyStarted). Same treatment
     * renderMinigameBanner's own "MINIGAME!" already gets. */
    private void renderCeremonyTitleBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getCeremonyTitleBannerUntil(), GAME_OVER_TITLE_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2 - 20;

        Node title = Text.rainbow("GOLDEN GNOME AWARDS!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(GAME_OVER_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, safeTextWidth(), alpha);
    }

    /** Persistent "gather in the arena" message for the Golden Gnome Awards -- same
     * pulsing-alpha, no-fixed-duration treatment renderArrivalGatherMessage already uses for every
     * arrival-gated mini-game, just gated on the ceremony's own isCeremonyGatherMessageRevealed()
     * instead of a minigame key -- NOT isCeremonyIntroRevealed()/isCeremonyStarted(), both too
     * early: this shares the rainbow title's own screen-center slot, so it has to wait for the
     * title to actually finish, not just start (see CeremonyPresentation's own doc on why those two
     * flags are split). Hides the instant the Gnome's own first line lands (getGnomeLine() != null). */
    private void renderCeremonyGatherMessage(Graphics2D g)
    {
        if (!plugin.isCeremonyGatherMessageRevealed()) return;
        if (plugin.getGnomeLine() != null) return;

        long now = System.currentTimeMillis();
        float alpha = MINIGAME_READY_CHECK_MIN_ALPHA + (1f - MINIGAME_READY_CHECK_MIN_ALPHA) * BannerAnim.pulse(now, MINIGAME_READY_CHECK_PULSE_PERIOD_MS);

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = Text.of("Everyone must gather in the arena!").font(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** One of the Gnome's own scripted lines, center-screen -- same plain white text-banner
     * treatment renderWinnerIntroBanner already uses. */
    private void renderGnomeLine(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getGnomeLineUntil(), WINNER_INTRO_FADE_MS);
        if (alpha == null) return;
        String line = plugin.getGnomeLine();
        if (line == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = Text.of(line).font(FontManager.getRunescapeBoldFont().deriveFont(WINNER_INTRO_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** "The &lt;Nth&gt; Golden Gnome is awarded to the player who &lt;description&gt;..." -- one
     * bonus Golden Gnome round's own objective, see CeremonyPresentation#handleBonusObjectiveAnnounced. */
    private void renderBonusObjectiveBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getBonusObjectiveBannerUntil(), WINNER_INTRO_FADE_MS);
        if (alpha == null) return;
        String description = plugin.getBonusObjectiveDescription();
        if (description == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        String ordinal = plugin.getBonusObjectiveRoundIndex() == 1 ? "first" : plugin.getBonusObjectiveRoundIndex() == 2 ? "second" : (plugin.getBonusObjectiveRoundIndex() + "th");
        Node message = Text.of("The " + ordinal + " Golden Gnome is awarded to the player who " + description + "...")
            .font(FontManager.getRunescapeBoldFont().deriveFont(WINNER_INTRO_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** "The Golden Gnome is awarded to..." -- the suspense beat CeremonyPresentation#
     * handleBonusWinnerRevealed now inserts before the actual reveal below, same plain
     * white/no-name-yet cliffhanger treatment renderWinnerSuspenseBanner's own "And the winner
     * is..." beat already uses for the main end-game reveal. */
    private void renderBonusSuspenseBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getBonusSuspenseBannerUntil(), WINNER_INTRO_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = Text.of("The Golden Gnome is awarded to...").font(FontManager.getRunescapeBoldFont().deriveFont(WINNER_INTRO_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** "That player is... &lt;name(s)&gt;!" -- the reveal half of a bonus Golden Gnome round, see
     * CeremonyPresentation#handleBonusWinnerRevealed. More than one name means a tie -- everyone
     * tied got their own gnome. */
    private void renderBonusWinnerBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getBonusWinnerBannerUntil(), WINNER_INTRO_FADE_MS);
        if (alpha == null) return;
        List<String> winners = plugin.getBonusWinners();
        if (winners.isEmpty()) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        String names = String.join(" and ", winners);
        Node message = Text.of(names + "!").font(FontManager.getRunescapeBoldFont().deriveFont(WINNER_INTRO_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    private void renderGameOverBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getGameOverBannerUntil(), GAME_OVER_TITLE_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2 - 20;

        Node title = Text.rainbow("GAME OVER!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(GAME_OVER_TITLE_SIZE));
        Layout.renderCentered(g, title, centerX, y, safeTextWidth(), alpha);
    }

    /** Second beat -- "Now it's time to see the winner...", bridging into the standings countdown. */
    private void renderWinnerIntroBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWinnerIntroBannerUntil(), WINNER_INTRO_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = Text.of("Now it's time to see the winner...").font(FontManager.getRunescapeBoldFont().deriveFont(WINNER_INTRO_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** The dramatic countdown itself -- one "In &lt;Nth&gt; place... &lt;Player&gt; -- N coins"
     * reveal per call, worst-place first, stopping once only the top two players remain. */
    private void renderPlaceReveal(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getPlaceRevealUntil(), PLACE_REVEAL_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getPlaceRevealRsn();
        if (rsn == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2 - 20;

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color nameColor = seatColor != null ? seatColor.awt : Color.WHITE;
        String stats = rsn + " -- " + plugin.getPlaceRevealGoldenGnomes() + " GG, " + plugin.getPlaceRevealCoins() + " coins";

        Node banner = Box.column(
                Text.of("In " + ordinal(plugin.getPlaceRevealRank()) + " place...")
                    .font(FontManager.getRunescapeBoldFont().deriveFont(PLACE_REVEAL_RANK_SIZE)).color(Color.LIGHT_GRAY),
                Text.of(stats)
                    .font(FontManager.getRunescapeBoldFont().deriveFont(PLACE_REVEAL_LINE_SIZE)).color(nameColor))
            .gap(14);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** Penultimate beat -- "And the winner is...", the last breath before renderWinnerReveal. */
    private void renderWinnerSuspenseBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWinnerSuspenseUntil(), WINNER_SUSPENSE_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2;

        Node message = Text.of("And the winner is...").font(FontManager.getRunescapeBoldFont().deriveFont(WINNER_SUSPENSE_SIZE)).color(Color.WHITE);
        Layout.renderCentered(g, message, centerX, y, safeTextWidth(), alpha);
    }

    /** The payoff -- the winner's name in the rainbow treatment, plus their final coins/Golden
     * Gnome tally. Played alongside ConfettiOverlay's burst, which renders separately. */
    private void renderWinnerReveal(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWinnerRevealUntil(), WINNER_REVEAL_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getWinnerRsn();
        if (rsn == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 2 - 10;

        Text name = Text.rainbow(rsn, RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(WINNER_REVEAL_NAME_SIZE));

        List<RosterReducer.RosterEntry> standings = plugin.getGameOverStandings();
        RosterReducer.RosterEntry winner = standings.isEmpty() ? null : standings.get(0);
        Node banner = winner == null ? name
            : Box.column(name, Text.of(winner.goldenGnomeCount + " Golden Gnomes, " + winner.coins + " coins")
                .font(FontManager.getRunescapeBoldFont().deriveFont(WINNER_REVEAL_SUBTITLE_SIZE)).color(Color.LIGHT_GRAY)).gap(14);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** "1st"/"2nd"/"3rd"/"4th"... with the 11-13 exception. */
    private static String ordinal(int n)
    {
        if (n % 100 >= 11 && n % 100 <= 13) return n + "th";
        switch (n % 10)
        {
            case 1: return n + "st";
            case 2: return n + "nd";
            case 3: return n + "rd";
            default: return n + "th";
        }
    }

    /** Draws the one-shot "WELCOME TO / RUNE PARTY / SHOWDOWN" title card shown right after
     * creating or joining a game. Local-player-only. */
    private void renderWelcomeBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWelcomeBannerUntil(), WELCOME_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        Node banner = Box.column(
                Text.of("WELCOME TO").font(FontManager.getRunescapeBoldFont().deriveFont(WELCOME_LEAD_SIZE)).color(Color.WHITE),
                Text.rainbow("RUNE PARTY", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(RUNE_PARTY_SIZE)),
                Text.of("SHOWDOWN").font(MARIO_PARTY_FONT.deriveFont(SHOWDOWN_SIZE)).color(WELCOME_TITLE_COLOR))
            .gap(14);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** Draws the "HERE WE GO!" banner when the game starts, plus an instruction to gather on the
     * Start tile. */
    private void renderGameStartBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getGameStartBannerUntil(), GAME_START_FADE_MS);
        if (alpha == null) return;

        int centerX = drawableWidth() / 2;
        int y = drawableHeight() / 3;

        Node banner = Box.column(
                Text.rainbow("HERE WE GO!", RAINBOW_LETTER_COLORS).font(MARIO_PARTY_FONT.deriveFont(GAME_START_TITLE_SIZE)),
                Text.of("Please stand on the Start Tile to begin.").font(FontManager.getRunescapeSmallFont()).color(Color.LIGHT_GRAY))
            .gap(10);
        Layout.renderCentered(g, banner, centerX, y, safeTextWidth(), alpha);
    }

    /** The width every "centerX = drawableWidth() / N" position formula in this file is actually
     * measured against -- the real 3D game viewport, not the full RuneLite canvas. In OSRS's Fixed
     * layouts (and some Resizable ones), the viewport is a sub-rectangle of the canvas: the
     * inventory/minimap/chat widgets occupy the rest of it. Centering on the raw canvas -- as this
     * whole file used to -- puts text partly or entirely behind those widgets whenever the viewport
     * doesn't fill the canvas.
     * <p>
     * {@code client.getViewportXOffset()} is normally just a handful of px -- the viewport sits
     * flush against the canvas's own top-left corner, the widgets eat into its right/bottom edges
     * instead, not its left/top -- so doubling that offset here builds a "virtual canvas" exactly
     * the size the real viewport would need to be centered within. That makes drawableWidth() / 2
     * (by far the most common divisor below, i.e. every plain centerX) land EXACTLY on the
     * viewport's own true center, and leaves only a negligible (offset/3-ish, a couple px at most)
     * error for the handful of non-center vertical fractions (/3, /4) this file also uses --
     * dramatically simpler than threading an explicit origin offset through every one of this file's
     * position formulas individually. */
    private int drawableWidth()
    {
        return client.getViewportWidth() + 2 * client.getViewportXOffset();
    }

    /** Vertical counterpart to drawableWidth() -- see its own doc. */
    private int drawableHeight()
    {
        return client.getViewportHeight() + 2 * client.getViewportYOffset();
    }

    /** The widest a horizontally-centered line/row should ever be drawn, given the viewer's own
     * CURRENT viewport size (see drawableWidth()) -- not a fixed design-time constant -- so every
     * banner authored against a normal game window still fits entirely within the visible 3D
     * viewport, not behind Fixed mode's own widgets and not off a resized/small client's own edge.
     * Cheap enough (one subtraction) to recompute fresh every frame; no resize/layout-change
     * listener needed since the very next frame just sees the new viewport size. */
    private int safeTextWidth()
    {
        return Math.max(MIN_SAFE_TEXT_WIDTH_PX, drawableWidth() - 2 * SCREEN_SAFE_MARGIN_PX);
    }

    /** Scale factor to shrink a composite line/row's own already-measured "design" width by so it
     * fits within safeTextWidth() -- 1f (unchanged) if it already fits. A composite line built from
     * several separately-measured segments can't shrink itself the way a single drawCenteredText
     * call can: by the time any one segment is actually drawn, its caller has already measured every
     * segment at the font size it intends to use and laid out x offsets from that. So those callers
     * measure the WHOLE line/row at its normal design size first, get this shared scale, then
     * re-derive every segment's font by the same factor before laying anything out for real --
     * keeping proportions between segments consistent instead of each drifting independently. */
    private float fitScale(int designWidth)
    {
        int maxWidth = safeTextWidth();
        if (designWidth <= maxWidth) return 1f;
        return Math.max(MIN_FIT_SCALE, maxWidth / (float) designWidth);
    }

    /** Shared centered/shadowed string draw -- caller sets the font first. Auto-shrinks (see
     * fitScale) rather than let {@code text} run past the viewer's own current screen edges, then
     * restores the caller's original font before returning so anything drawn right after (e.g. a
     * backdrop sized off that same font's metrics) is unaffected.
     * <p>
     * Measures {@code text} exactly once for the common case (no shrink needed) -- the same
     * measurement this method always had to do for centering doubles as the fit check, rather than
     * a separate pass measuring the same string twice every frame. A second measurement only happens
     * on the rare frame where a shrink actually applies, since {@code fitScale}'s ratio is a linear
     * approximation of the smaller font's real width. */
    private void drawCenteredText(Graphics2D g, String text, int centerX, int y, Color color, float alpha)
    {
        Font original = g.getFont();
        int width = g.getFontMetrics(original).stringWidth(text);
        float scale = fitScale(width);

        Font font = original;
        if (scale < 1f)
        {
            font = original.deriveFont(original.getSize2D() * scale);
            g.setFont(font);
            width = g.getFontMetrics().stringWidth(text);
        }

        drawLeftAlignedText(g, text, centerX - width / 2, y, color, alpha);
        if (font != original) g.setFont(original);
    }

    /** Same as drawCenteredText, but colors each non-space character from {@code letterColors} in
     * order instead of one solid color. Same auto-shrink/restore treatment, and same
     * measure-once-in-the-common-case reasoning -- the per-character width loop below is already
     * required (rainbow text needs per-character positions regardless of fit), so it doubles as the
     * fit check instead of adding a separate measurement pass. */
    private void drawCenteredRainbowText(Graphics2D g, String text, Color[] letterColors, int centerX, int y, float alpha)
    {
        Font original = g.getFont();
        FontMetrics fm = g.getFontMetrics(original);
        int totalWidth = 0;
        for (int i = 0; i < text.length(); i++) totalWidth += fm.charWidth(text.charAt(i));
        float scale = fitScale(totalWidth);

        Font font = original;
        if (scale < 1f)
        {
            font = original.deriveFont(original.getSize2D() * scale);
            g.setFont(font);
            fm = g.getFontMetrics();
            totalWidth = 0;
            for (int i = 0; i < text.length(); i++) totalWidth += fm.charWidth(text.charAt(i));
        }

        drawLeftAlignedRainbowText(g, text, letterColors, centerX - totalWidth / 2, y, alpha);
        if (font != original) g.setFont(original);
    }

    /** Draws {@code text} left-aligned from canvas x {@code x}, and returns the x just past what it
     * drew, so a composite line built from multiple styled segments can keep chaining. */
    private int drawLeftAlignedText(Graphics2D g, String text, int x, int y, Color color, float alpha)
    {
        g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * 0.7f));
        g.drawString(text, x + 2, y + 2);
        g.setColor(RunePartyRender.withAlpha(color, alpha));
        g.drawString(text, x, y);
        return x + g.getFontMetrics().stringWidth(text);
    }

    /** Left-aligned counterpart to drawCenteredRainbowText. */
    private int drawLeftAlignedRainbowText(Graphics2D g, String text, Color[] letterColors, int x, int y, float alpha)
    {
        FontMetrics fm = g.getFontMetrics();
        int colorIndex = 0;
        for (int i = 0; i < text.length(); i++)
        {
            char ch = text.charAt(i);
            int charWidth = fm.charWidth(ch);
            if (!Character.isWhitespace(ch))
            {
                String s = String.valueOf(ch);
                Color color = letterColors[colorIndex % letterColors.length];
                g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * 0.7f));
                g.drawString(s, x + 2, y + 2);
                g.setColor(RunePartyRender.withAlpha(color, alpha));
                g.drawString(s, x, y);
                colorIndex++;
            }
            x += charWidth;
        }
        return x;
    }

    /** Draws a big, toy-like die dead center of the screen after a dice roll, filled in the
     * roller's own seat color, faces 1-10. Cycles random faces while "spinning", then snaps to the
     * real value with an overshoot pop, holds, and fades.
     * <p>
     * When the roll carried an item bonus, three extra beats splice in after the initial settle:
     * the die holds on the bare base roll, a "+N" label pops in, then the die pops again to the
     * bonus-inclusive total while the label becomes "+N = total". A plain roll (bonus == 0) never
     * enters these branches. */
    private void renderDiceRoll(Graphics2D g)
    {
        String rsn = plugin.getDiceRollRsn();
        if (rsn == null) return;

        Float alpha = BannerAnim.fadeAlpha(plugin.getDiceRollUntil(), RunePartyPlugin.DICE_ROLL_FADE_MS);
        if (alpha == null) return;
        long now = System.currentTimeMillis();

        long elapsed = now - plugin.getDiceRollStart();
        boolean spinning = elapsed < RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS;

        int bonus = plugin.getDiceRollBonus();
        int total = plugin.getDiceRollValue();
        int base = total - bonus;

        // Sub-phase boundaries within the bonus reveal, relative to the moment the spin ends. All
        // three collapse to the same point when bonus == 0, so those branches are simply unreached.
        long sinceSpinEnd = elapsed - RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS;
        long badgeStart = DIE_SETTLE_POP_MS;
        long flipStart = badgeStart + (bonus != 0 ? RunePartyPlugin.DICE_ROLL_BONUS_BADGE_MS : 0);
        long flipEnd = flipStart + (bonus != 0 ? RunePartyPlugin.DICE_ROLL_BONUS_FLIP_MS : 0);
        long resultHoldEnd = flipEnd + (bonus != 0 ? RunePartyPlugin.DICE_ROLL_BONUS_RESULT_HOLD_MS : 0);

        int shown;
        float scale = 1f;
        if (spinning)
        {
            shown = 1 + (int) ((now / DIE_SPIN_FACE_MS) % 10);
        }
        else if (bonus != 0 && sinceSpinEnd < flipStart)
        {
            // Settled on the bare base roll, holding before the bonus flips it to the total.
            shown = base;
            if (sinceSpinEnd < DIE_SETTLE_POP_MS)
            {
                float t = sinceSpinEnd / (float) DIE_SETTLE_POP_MS;
                scale = 1.35f - 0.35f * t;
            }
        }
        else if (bonus != 0 && sinceSpinEnd < flipEnd)
        {
            // Flipping from the base roll to the bonus-inclusive total.
            shown = total;
            float t = (sinceSpinEnd - flipStart) / (float) RunePartyPlugin.DICE_ROLL_BONUS_FLIP_MS;
            scale = 1.35f - 0.35f * t;
        }
        else
        {
            shown = total;
            if (sinceSpinEnd < DIE_SETTLE_POP_MS)
            {
                float t = sinceSpinEnd / (float) DIE_SETTLE_POP_MS;
                scale = 1.35f - 0.35f * t;
            }
        }

        int jitterX = spinning ? (int) Math.round(Math.sin(now / 35.0) * 4) : 0;
        int jitterY = spinning ? (int) Math.round(Math.cos(now / 47.0) * 4) : 0;

        int size = Math.round(DIE_SIZE * scale);
        int half = size / 2;
        int cx = drawableWidth() / 2 + jitterX;
        int cy = drawableHeight() / 2 + jitterY;

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color color = seatColor != null ? seatColor.awt : Color.WHITE;

        g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha * 0.3f));
        g.fillRoundRect(cx - half + 4, cy - half + 4, size, size, DIE_CORNER, DIE_CORNER);

        g.setColor(RunePartyRender.withAlpha(color, alpha * DIE_FACE_OPACITY));
        g.fillRoundRect(cx - half, cy - half, size, size, DIE_CORNER, DIE_CORNER);
        g.setStroke(new BasicStroke(DIE_BORDER_WIDTH));
        g.setColor(RunePartyRender.withAlpha(DIE_BORDER, alpha));
        g.drawRoundRect(cx - half, cy - half, size, size, DIE_CORNER, DIE_CORNER);

        int pipPad = Math.max(10, size / 6);
        int pipR = 5;
        g.setColor(RunePartyRender.withAlpha(DIE_PIP, alpha));
        g.fillOval(cx - half + pipPad - pipR, cy - half + pipPad - pipR, pipR * 2, pipR * 2);
        g.fillOval(cx + half - pipPad - pipR, cy - half + pipPad - pipR, pipR * 2, pipR * 2);
        g.fillOval(cx - half + pipPad - pipR, cy + half - pipPad - pipR, pipR * 2, pipR * 2);
        g.fillOval(cx + half - pipPad - pipR, cy + half - pipPad - pipR, pipR * 2, pipR * 2);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(DIE_NUMBER_SIZE));
        String text = String.valueOf(shown);
        FontMetrics fm = g.getFontMetrics();
        int tx = cx - fm.stringWidth(text) / 2;
        int ty = cy + fm.getAscent() / 2 - 4;
        g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha));
        g.drawString(text, tx + 2, ty + 2);
        g.setColor(RunePartyRender.withAlpha(Color.WHITE, alpha));
        g.drawString(text, tx, ty);

        // "+N"/"-N" (then "+N = total") underneath the die, only during the bonus reveal itself.
        if (bonus != 0 && !spinning && sinceSpinEnd >= badgeStart && sinceSpinEnd < resultHoldEnd)
        {
            String signedBonus = (bonus > 0 ? "+" : "") + bonus;
            String label = sinceSpinEnd < flipStart ? signedBonus : (signedBonus + " = " + total);
            Color labelColor = bonus > 0 ? DICE_ROLL_BONUS_POSITIVE_COLOR : DICE_ROLL_BONUS_NEGATIVE_COLOR;
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(DICE_ROLL_BONUS_LABEL_SIZE));
            drawCenteredText(g, label, cx, cy + half + 30, labelColor, alpha);
        }
    }

    /** Whether {@code rsn} is the local viewer. */
    private boolean isLocal(String rsn)
    {
        String local = plugin.getLocalRsn();
        return rsn != null && local != null && local.equalsIgnoreCase(rsn);
    }
}
