package gay.runescape.runeparty;

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
import java.util.function.BiFunction;
import java.util.function.Function;
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

    private static final float DICE_ROLL_BONUS_LABEL_SIZE = 26f;
    private static final Color DICE_ROLL_BONUS_POSITIVE_COLOR = new Color(80, 220, 80);
    private static final Color DICE_ROLL_BONUS_NEGATIVE_COLOR = new Color(230, 70, 70);

    private static final long MINIGAME_READY_CHECK_PULSE_PERIOD_MS = DEFAULT_PULSE_PERIOD_MS;
    private static final float MINIGAME_READY_CHECK_MIN_ALPHA = 0.6f;
    private static final float MINIGAME_READY_CHECK_LINE_SIZE = 22f;
    private static final int MINIGAME_READY_CHECK_LINE_HEIGHT = 28;

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
    private static final Stroke CHANCE_SPACE_FIZZLE_STROKE = new BasicStroke(3f);
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
        renderCoinTrapAnnouncement(g);
        renderMinigameBanner(g);
        renderMinigameSpinner(g);
        renderMinigameReadyCheck(g);
        renderTeamAssignedBanner(g);
        renderJaddyResolvedBanner(g);
        if (RunePartyPlugin.ARENA_KEY.equals(plugin.getMinigameKey()) || RunePartyPlugin.TURF_WARS_KEY.equals(plugin.getMinigameKey())
            || RunePartyPlugin.SANDWICH_RUSH_KEY.equals(plugin.getMinigameKey()) || RunePartyPlugin.JADDY_KEY.equals(plugin.getMinigameKey())
            || RunePartyPlugin.HOT_POTATO_KEY.equals(plugin.getMinigameKey()))
        {
            renderArrivalGatherMessage(g);
        }
        else
        {
            renderMinigameCountdown(g);
        }
        renderTrueOrFalseReveal(g);
        renderTrueOrFalseQuestion(g);
        renderMinigameOverBanner(g);
        renderMinigameScoreBanner(g);
        renderMinigameRewardsBanner(g);
        renderRoundCompleteBanner(g);
        renderDiceRoll(g);
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

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(36f));
        drawCenteredText(g, text, client.getCanvasWidth() / 2, client.getCanvasHeight() / 4, color, alpha);
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

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(36f));
        drawCenteredText(g, text, client.getCanvasWidth() / 2, client.getCanvasHeight() / 4, color, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(16f));
        drawCenteredText(g, "Tele Blocked", client.getCanvasWidth() / 2, client.getCanvasHeight() / 4 + 26, color, alpha);
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

        String prefix = "Use the ";
        String spinWord = "SPIN";
        String suffix = hasItems ? " emote to roll the dice, or use an item in the panel." : " emote to roll the dice.";

        Font normalFont = FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE);
        Font spinFont = MARIO_PARTY_FONT.deriveFont(SPIN_HINT_SIZE);

        g.setFont(normalFont);
        int prefixWidth = g.getFontMetrics().stringWidth(prefix);
        int suffixWidth = g.getFontMetrics().stringWidth(suffix);
        g.setFont(spinFont);
        int spinWidth = g.getFontMetrics().stringWidth(spinWord);

        int y = client.getCanvasHeight() / 4 + 40;
        int x = client.getCanvasWidth() / 2 - (prefixWidth + spinWidth + suffixWidth) / 2;

        g.setFont(normalFont);
        x = drawLeftAlignedText(g, prefix, x, y, SPIN_HINT_COLOR, alpha);

        g.setFont(spinFont);
        x = drawLeftAlignedRainbowText(g, spinWord, RAINBOW_LETTER_COLORS, x, y, alpha);

        g.setFont(normalFont);
        drawLeftAlignedText(g, suffix, x, y, SPIN_HINT_COLOR, alpha);
    }

    /** The bystander half of the Spin hint -- "Waiting for &lt;player&gt; to roll the dice...". */
    private void renderSpinHintWaiting(Graphics2D g, String rsn)
    {
        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * BannerAnim.pulse(System.currentTimeMillis(), SPIN_HINT_PULSE_PERIOD_MS);

        String prefix = "Waiting for ";
        String suffix = " to roll the dice...";

        Font font = FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE);
        g.setFont(font);
        int prefixWidth = g.getFontMetrics().stringWidth(prefix);
        int nameWidth = g.getFontMetrics().stringWidth(rsn);
        int suffixWidth = g.getFontMetrics().stringWidth(suffix);

        int y = client.getCanvasHeight() / 4 + 40;
        int x = client.getCanvasWidth() / 2 - (prefixWidth + nameWidth + suffixWidth) / 2;

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color nameColor = seatColor != null ? seatColor.awt : SPIN_HINT_COLOR;

        x = drawLeftAlignedText(g, prefix, x, y, SPIN_HINT_COLOR, alpha);
        x = drawLeftAlignedText(g, rsn, x, y, nameColor, alpha);
        drawLeftAlignedText(g, suffix, x, y, SPIN_HINT_COLOR, alpha);
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
        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE));
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 4 + 40;
        drawCenteredText(g, "Return to your tile to roll the dice!", centerX, y, SPIN_HINT_COLOR, alpha);
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
        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE));
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 4 + 40;
        drawCenteredText(g, "Head to the Start Tile to begin!", centerX, y, SPIN_HINT_COLOR, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

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
            drawEmoteInstruction(g, "BOW", " emote: bow to Jad!", emoteFont, emoteWordFont, centerX, y + 96, alpha);
        }
        else
        {
            g.setFont(FontManager.getRunescapeSmallFont());
            drawCenteredText(g, "Waiting for " + encounterRsn + " to bow...", centerX, y + 96, Color.LIGHT_GRAY, alpha);
        }
    }

    /** Draws one emote instruction line -- {@code '<word>' emote: <suffix>} -- with the quoted
     * emote name in the Mario Party rainbow font and the rest in plain bold. */
    private void drawEmoteInstruction(Graphics2D g, String word, String suffix, Font plainFont, Font wordFont, int centerX, int y, float alpha)
    {
        String prefix = "'";
        String quoteSuffix = "'" + suffix;

        g.setFont(plainFont);
        int prefixWidth = g.getFontMetrics().stringWidth(prefix);
        int suffixWidth = g.getFontMetrics().stringWidth(quoteSuffix);
        g.setFont(wordFont);
        int wordWidth = g.getFontMetrics().stringWidth(word);

        int x = centerX - (prefixWidth + wordWidth + suffixWidth) / 2;

        g.setFont(plainFont);
        x = drawLeftAlignedText(g, prefix, x, y, Color.LIGHT_GRAY, alpha);
        g.setFont(wordFont);
        x = drawLeftAlignedRainbowText(g, word, RAINBOW_LETTER_COLORS, x, y, alpha);
        g.setFont(plainFont);
        drawLeftAlignedText(g, quoteSuffix, x, y, Color.LIGHT_GRAY, alpha);
    }

    /** Draws one standings row -- {@code <rank>  <name>   <stats>} -- as one centered line, name in
     * the player's own seat color. */
    private void drawStandingsLine(Graphics2D g, Font nameFont, Font statsFont, String rank, String name, Color nameColor, String stats, Color statsColor, int centerX, int y, float alpha)
    {
        g.setFont(nameFont);
        int rankWidth = g.getFontMetrics().stringWidth(rank);
        int nameWidth = g.getFontMetrics().stringWidth(name);
        g.setFont(statsFont);
        int statsWidth = g.getFontMetrics().stringWidth(stats);

        int x = centerX - (rankWidth + nameWidth + statsWidth) / 2;

        g.setFont(nameFont);
        x = drawLeftAlignedText(g, rank, x, y, Color.LIGHT_GRAY, alpha);
        x = drawLeftAlignedText(g, name, x, y, nameColor, alpha);
        g.setFont(statsFont);
        drawLeftAlignedText(g, stats, x, y, statsColor, alpha);
    }

    /** One drawStandingsLine per player, in list order. Shared by renderMinigameReadyCheck,
     * renderTrueOrFalseQuestion, renderMinigameRewardsBanner, and renderRoundCompleteBanner, which
     * differ only in sort order, rank prefix, and status text/color. {@code rankFn} receives the
     * 1-based row index. */
    private void drawPlayerRows(Graphics2D g, List<RosterReducer.RosterEntry> players,
        Font nameFont, Font statsFont, BiFunction<RosterReducer.RosterEntry, Integer, String> rankFn,
        Function<RosterReducer.RosterEntry, String> statsFn,
        Function<RosterReducer.RosterEntry, Color> statsColorFn,
        int centerX, int startY, int lineHeight, float alpha)
    {
        int y = startY;
        int i = 1;
        for (RosterReducer.RosterEntry entry : players)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            drawStandingsLine(g, nameFont, statsFont, rankFn.apply(entry, i), entry.rsn, nameColor,
                statsFn.apply(entry), statsColorFn.apply(entry), centerX, y, alpha);
            y += lineHeight;
            i++;
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
        if ("purchased".equals(outcome))
        {
            text = isLocal ? "You got a Golden Gnome!" : rsn != null ? rsn + " got a Golden Gnome!" : null;
        }
        else
        {
            text = null;
        }
        if (text == null) return;

        Color color = "purchased".equals(outcome) ? WELCOME_TITLE_COLOR : Color.WHITE;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OUTCOME_SIZE));
        drawCenteredText(g, text, client.getCanvasWidth() / 2, client.getCanvasHeight() / 3, color, alpha);
    }

    /** Draws the "CHANCE TILE!" title card -- same rainbow single-line treatment as "ITEM SPACE!"
     * (renderItemBanner), the first of three Chance Tile reveal stages (see
     * ChanceSpacePresentation's own scheduleAfterTurnEffects chain). */
    private void renderChanceSpaceTitle(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getChanceSpaceTitleUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        drawCenteredRainbowText(g, "CHANCE TILE!", RAINBOW_LETTER_COLORS, centerX, y, alpha);
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
        boolean gnomeTransferred = plugin.isChanceSpaceIconsGnomeTransferred();

        long elapsed = System.currentTimeMillis() - plugin.getChanceSpaceIconsStart();
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        drawChanceSpaceBackdrop(g, announcementLines, centerX, y, stageAlpha);

        float[] anim = slotAnim(elapsed, slotDelayMs[0], stageAlpha);
        if (anim != null) drawChanceSpaceToken(g, leftRsn, centerX - CHANCE_SPACE_ICON_SPACING, y, anim[0], anim[1]);

        anim = slotAnim(elapsed, slotDelayMs[1], stageAlpha);
        if (anim != null) drawChanceSpaceArrow(g, direction, outcomeType, gnomeTransferred, centerX, y, anim[0], anim[1]);

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
    private void drawChanceSpaceArrow(Graphics2D g, String direction, String outcomeType, boolean gnomeTransferred,
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
        else drawChanceSpaceGnomeIcon(g, cx, itemY, itemSize, alpha, gnomeTransferred);
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

    /** Draws chanceSpaceGnomeIcon the same way as the coin icon above, but dimmed with a red slash
     * through it when a gnome swap fizzled (neither player held one) -- so the tableau still reads
     * as "attempted, nothing moved" rather than a normal transfer. */
    private void drawChanceSpaceGnomeIcon(Graphics2D g, int cx, int cy, int size, float alpha, boolean transferred)
    {
        float iconAlpha = transferred ? alpha : alpha * 0.4f;
        drawIconImage(g, chanceSpaceGnomeIcon, cx, cy, size, iconAlpha);

        if (!transferred)
        {
            int r = size / 2;
            g.setStroke(CHANCE_SPACE_FIZZLE_STROKE);
            g.setColor(RunePartyRender.withAlpha(new Color(220, 50, 50), alpha));
            g.drawLine(cx - r, cy + r, cx + r, cy - r - size / 2);
        }
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

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OUTCOME_SIZE));
        drawCenteredText(g, text, client.getCanvasWidth() / 2, client.getCanvasHeight() / 3, Color.WHITE, alpha);
    }

    /** Draws the "MINIGAME!" title card. A pure title card -- instructions are shown by the
     * ready-check screen instead. */
    private void renderMinigameBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        drawCenteredRainbowText(g, "MINIGAME!", RAINBOW_LETTER_COLORS, centerX, y, alpha);
    }

    /** The closing bookend to renderMinigameBanner's "MINIGAME!". */
    private void renderMinigameOverBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameOverBannerUntil(), MINIGAME_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        drawCenteredRainbowText(g, "MINIGAME OVER!", RAINBOW_LETTER_COLORS, centerX, y, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE));
        drawCenteredText(g, "This is your team color!", centerX, y, color, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        drawCenteredRainbowText(g, "ITEM SPACE!", RAINBOW_LETTER_COLORS, centerX, y, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        boolean isLocal = isLocal(rsn);
        String subtitle = item.getEffectDescription(isLocal);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE));
        drawCenteredText(g, item.getDisplayName(), centerX, y, WELCOME_TITLE_COLOR, alpha);

        if (subtitle != null)
        {
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_SUBTITLE_SIZE));
            drawCenteredText(g, subtitle, centerX, y + 28, Color.LIGHT_GRAY, alpha);
        }
    }

    /** Fires instead of renderItemSpinner when the mover is already at the item cap -- no wheel,
     * just a two-line explainer. */
    private void renderItemCapBlocked(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getItemCapBlockedUntil(), ITEM_CAP_BLOCKED_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getItemCapBlockedRsn();
        if (rsn == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;
        int cap = plugin.getItemCapBlockedCap();

        boolean isLocal = isLocal(rsn);

        String title = isLocal ? "You already have " + cap + " items!" : rsn + " already has " + cap + " items!";
        String subtitle = isLocal
            ? "You must use an item before you can receive any more."
            : "They must use an item before they can receive any more.";

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_CAP_BLOCKED_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, Color.WHITE, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_CAP_BLOCKED_SUBTITLE_SIZE));
        drawCenteredText(g, subtitle, centerX, y + 28, Color.LIGHT_GRAY, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        boolean isLocal = isLocal(rsn);

        String verb = item.getUseAnnounceVerb();
        String title = (isLocal ? "You " + verb + " " : rsn + " " + verb + " ") + item.getDisplayName() + "!";
        String subtitle = item.getUseAnnouncementSubtitle(isLocal);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, Color.WHITE, alpha);

        if (subtitle != null)
        {
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_SUBTITLE_SIZE));
            drawCenteredText(g, subtitle, centerX, y + 28, Color.LIGHT_GRAY, alpha);
        }
    }

    /** Draws "You/&lt;caster&gt; cast teleblock on &lt;target&gt;/you!" plus a matching subtitle.
     * Personalized for whichever role the local viewer is; a third party sees both names. */
    private void renderTeleBlockCastAnnouncement(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getTeleBlockCastUntil(), ITEM_USED_ANNOUNCE_FADE_MS);
        if (alpha == null) return;
        String caster = plugin.getTeleBlockCastCasterRsn();
        String target = plugin.getTeleBlockCastTargetRsn();
        if (caster == null || target == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        boolean isCaster = isLocal(caster);
        boolean isTarget = isLocal(target);

        String casterPart = isCaster ? "You" : caster;
        String targetPart = isTarget ? "you" : target;
        String title = casterPart + " cast teleblock on " + targetPart + "!";
        String subtitle = isTarget ? "You will lose your next turn." : target + " will lose their next turn.";

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, Color.WHITE, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_SUBTITLE_SIZE));
        drawCenteredText(g, subtitle, centerX, y + 28, Color.LIGHT_GRAY, alpha);
    }

    /** Draws "You/&lt;rsn&gt; landed on a Coin Trap!" -- no subtitle, since the actual coin numbers
     * show up in each player's own coin popup instead. */
    private void renderCoinTrapAnnouncement(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getCoinTrapAnnounceUntil(), COIN_TRAP_ANNOUNCE_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getCoinTrapAnnounceRsn();
        if (rsn == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        String title = (isLocal(rsn) ? "You" : rsn) + " landed on a Coin Trap!";

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(COIN_TRAP_ANNOUNCE_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, Color.WHITE, alpha);
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

        int cx = client.getCanvasWidth() / 2;
        int cy = client.getCanvasHeight() / 2;
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        String displayName = plugin.getMinigameDisplayName();
        if (displayName != null)
        {
            g.setFont(MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE));
            drawCenteredText(g, displayName, centerX, y, WELCOME_TITLE_COLOR, alpha);
        }

        // Wrapped since a mini-game's instructions can run long; everything below is laid out
        // relative to afterInstructionsY so it shifts down instead of overlapping.
        int afterInstructionsY = y + 30;
        String instructions = plugin.getMinigameInstructions();
        if (instructions != null)
        {
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE));
            afterInstructionsY = drawWrappedCenteredText(g, instructions, centerX, y + 30,
                READY_CHECK_INSTRUCTIONS_MAX_WIDTH, READY_CHECK_INSTRUCTIONS_LINE_HEIGHT, Color.WHITE, alpha) - READY_CHECK_INSTRUCTIONS_LINE_HEIGHT;
        }

        Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        drawEmoteInstruction(g, "YES", " emote when you're ready!", emoteFont, emoteWordFont, centerX, afterInstructionsY + 36, alpha);

        Set<String> ready = plugin.getMinigameReadyRsns();
        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator.comparing((RosterReducer.RosterEntry e) -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(MINIGAME_READY_CHECK_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(MINIGAME_READY_CHECK_LINE_SIZE);
        drawPlayerRows(g, players, nameFont, statsFont, (entry, i) -> "",
            entry -> ready.contains(entry.rsn.toLowerCase()) ? "   Ready!" : "   Waiting...",
            entry -> ready.contains(entry.rsn.toLowerCase()) ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR,
            centerX, afterInstructionsY + 70, MINIGAME_READY_CHECK_LINE_HEIGHT, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_COUNTDOWN_SIZE * scale));
        if (number >= 1)
        {
            drawCenteredText(g, String.valueOf(number), centerX, y, MINIGAME_COUNTDOWN_NUMBER_COLOR, 1f);
        }
        else
        {
            drawCenteredRainbowText(g, "BEGIN!", RAINBOW_LETTER_COLORS, centerX, y, 1f);
        }
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        String text = RunePartyPlugin.JADDY_KEY.equals(plugin.getMinigameKey())
            ? "Choose a side -- stand in the pink or teal zone!"
            : "All players must stand within the arena!";

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE));
        drawCenteredText(g, text, centerX, y, Color.WHITE, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE));
        drawCenteredText(g, text, centerX, y, color, alpha);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3 - 20;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_ROUND_LABEL_SIZE));
        drawCenteredText(g, "Round " + plugin.getTrueOrFalseRoundNumber() + "/5", centerX, y, Color.WHITE, 1f);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_QUESTION_SIZE));
        int afterQuestionY = drawWrappedCenteredText(g, question, centerX, y + 34,
            TRUE_OR_FALSE_QUESTION_MAX_WIDTH, TRUE_OR_FALSE_QUESTION_LINE_HEIGHT, Color.WHITE, 1f);

        long now = System.currentTimeMillis();
        g.setFont(MARIO_PARTY_FONT.deriveFont(TRUE_OR_FALSE_COUNTDOWN_SIZE));
        if (now < plugin.getTrueOrFalseAnswerWindowStartsAt())
        {
            g.setFont(FontManager.getRunescapeSmallFont());
            drawCenteredText(g, "Get ready...", centerX, afterQuestionY + 34, Color.LIGHT_GRAY, 1f);
        }
        else
        {
            long remainingMs = plugin.getTrueOrFalseRoundEndsAt() - now;
            int secondsLeft = (int) Math.max(0, Math.ceil(remainingMs / 1000.0));
            drawCenteredText(g, String.valueOf(secondsLeft), centerX, afterQuestionY + 40, TRUE_OR_FALSE_COUNTDOWN_COLOR, 1f);
        }

        Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        int emoteY = afterQuestionY + 72;
        drawEmoteInstruction(g, "YES", " = True", emoteFont, emoteWordFont, centerX - 100, emoteY, 1f);
        drawEmoteInstruction(g, "NO", " = False", emoteFont, emoteWordFont, centerX + 100, emoteY, 1f);

        Set<String> answered = plugin.getTrueOrFalseAnsweredRsns();
        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator.comparing((RosterReducer.RosterEntry e) -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        drawPlayerRows(g, players, nameFont, statsFont, (entry, i) -> "",
            entry -> answered.contains(entry.rsn.toLowerCase()) ? "   Answered!" : "   Waiting...",
            entry -> answered.contains(entry.rsn.toLowerCase()) ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR,
            centerX, emoteY + 36, ROUND_COMPLETE_LINE_HEIGHT, 1f);
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        String title = "The answer was " + (correctAnswer ? "TRUE" : "FALSE") + "!";
        Color titleColor = correctAnswer ? TRUE_OR_FALSE_TRUE_COLOR : TRUE_OR_FALSE_FALSE_COLOR;
        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_REVEAL_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, titleColor, alpha);

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(TRUE_OR_FALSE_REVEAL_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(TRUE_OR_FALSE_REVEAL_LINE_SIZE);
        int lineY = y + 36;
        for (TrueOrFalseResult result : plugin.getTrueOrFalseLastResults())
        {
            String answerText = result.answer == null ? "no answer" : (result.answer ? "True" : "False");
            String status = "   " + answerText + (result.correct ? " -- correct!" : " -- wrong");
            Color statusColor = result.correct ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR;
            drawStandingsLine(g, nameFont, statsFont, "", result.rsn, Color.LIGHT_GRAY, status, statusColor, centerX, lineY, alpha);
            lineY += TRUE_OR_FALSE_REVEAL_LINE_HEIGHT;
        }
    }

    /** Word-wraps {@code text} onto as many lines as needed to stay within {@code maxWidth} pixels,
     * each centered and drawn {@code lineHeight} apart. {@code g}'s font must already be set.
     * Returns the y just past the last line drawn, so callers can lay out what comes next. */
    private int drawWrappedCenteredText(Graphics2D g, String text, int centerX, int y, int maxWidth, int lineHeight, Color color, float alpha)
    {
        FontMetrics fm = g.getFontMetrics();
        String[] words = text.split(" ");
        StringBuilder line = new StringBuilder();
        int lineY = y;
        for (String word : words)
        {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && fm.stringWidth(candidate) > maxWidth)
            {
                drawCenteredText(g, line.toString(), centerX, lineY, color, alpha);
                lineY += lineHeight;
                line = new StringBuilder(word);
            }
            else
            {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0)
        {
            drawCenteredText(g, line.toString(), centerX, lineY, color, alpha);
            lineY += lineHeight;
        }
        return lineY;
    }

    /** Draws the mini-game final-score recap -- "FINAL SCORE", then every seated player with the
     * score they earned this round, highest first, each name in its own seat color (see
     * drawPlayerRows) so it reads like a personal standings list rather than a flat report.
     * Score's own meaning varies per mini-game (unique tiles clicked, anchovies caught, correct
     * answers, ...) -- this banner doesn't need to know which, it just shows the raw number every
     * mini-game's own pay_out/pay_out_flat/pay_out_top already produces in MINIGAME_ENDED's
     * "results" list. Shown after renderMinigameOverBanner and before renderMinigameRewardsBanner,
     * so players see how they did before finding out what (if anything) that earned them. */
    private void renderMinigameScoreBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameScoreBannerUntil(), MINIGAME_REWARDS_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_REWARDS_TITLE_SIZE));
        drawCenteredRainbowText(g, "FINAL SCORE", RAINBOW_LETTER_COLORS, centerX, y, alpha);

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
        drawPlayerRows(g, players, nameFont, statsFont, (entry, i) -> "",
            entry -> "   " + scoreByRsn.getOrDefault(entry.rsn.toLowerCase(), 0) + " pts",
            entry -> Color.LIGHT_GRAY,
            centerX, y + 40, MINIGAME_REWARDS_LINE_HEIGHT, alpha);
    }

    /** Draws the mini-game rewards recap -- "REWARDS", then every seated player with the coins they
     * received, highest reward first ("no reward" in gray otherwise). Shown before
     * renderRoundCompleteBanner. */
    private void renderMinigameRewardsBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getMinigameRewardsBannerUntil(), MINIGAME_REWARDS_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_REWARDS_TITLE_SIZE));
        drawCenteredRainbowText(g, "REWARDS", RAINBOW_LETTER_COLORS, centerX, y, alpha);

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
        drawPlayerRows(g, players, nameFont, statsFont, (entry, i) -> "",
            entry -> rewardByRsn.get(entry.rsn.toLowerCase()) != null ? "   +" + rewardByRsn.get(entry.rsn.toLowerCase()) + " coins" : "   no reward",
            entry -> rewardByRsn.get(entry.rsn.toLowerCase()) != null ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR,
            centerX, y + 40, MINIGAME_REWARDS_LINE_HEIGHT, alpha);
    }

    /** Draws the post-round recap -- "ROUND x" (the upcoming round), "Current Standings", then every
     * seated player ranked by Golden Gnomes (coins as tiebreak), each in their own seat color. */
    private void renderRoundCompleteBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getRoundCompleteBannerUntil(), ROUND_COMPLETE_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(ROUND_COMPLETE_TITLE_SIZE));
        drawCenteredRainbowText(g, "ROUND " + plugin.getRoundCompleteRoundNumber(), RAINBOW_LETTER_COLORS, centerX, y, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_SUBTITLE_SIZE));
        drawCenteredText(g, "Current Standings", centerX, y + 34, Color.WHITE, alpha);

        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        players.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> e.goldenGnomeCount).reversed()
            .thenComparing(Comparator.comparingInt((RosterReducer.RosterEntry e) -> e.coins).reversed()));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        drawPlayerRows(g, players, nameFont, statsFont, (entry, i) -> "#" + i + "  ",
            entry -> "   " + entry.goldenGnomeCount + " GG, " + entry.coins + " coins",
            entry -> Color.LIGHT_GRAY,
            centerX, y + 66, ROUND_COMPLETE_LINE_HEIGHT, alpha);
    }

    /** First beat of the end-game awards ceremony -- "GAME OVER!". */
    private void renderGameOverBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getGameOverBannerUntil(), GAME_OVER_TITLE_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2 - 20;

        g.setFont(MARIO_PARTY_FONT.deriveFont(GAME_OVER_TITLE_SIZE));
        drawCenteredRainbowText(g, "GAME OVER!", RAINBOW_LETTER_COLORS, centerX, y, alpha);
    }

    /** Second beat -- "Now it's time to see the winner...", bridging into the standings countdown. */
    private void renderWinnerIntroBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWinnerIntroBannerUntil(), WINNER_INTRO_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(WINNER_INTRO_SIZE));
        drawCenteredText(g, "Now it's time to see the winner...", centerX, y, Color.WHITE, alpha);
    }

    /** The dramatic countdown itself -- one "In &lt;Nth&gt; place... &lt;Player&gt; -- N coins"
     * reveal per call, worst-place first, stopping once only the top two players remain. */
    private void renderPlaceReveal(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getPlaceRevealUntil(), PLACE_REVEAL_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getPlaceRevealRsn();
        if (rsn == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2 - 20;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(PLACE_REVEAL_RANK_SIZE));
        drawCenteredText(g, "In " + ordinal(plugin.getPlaceRevealRank()) + " place...", centerX, y, Color.LIGHT_GRAY, alpha);

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(rsn));
        Color nameColor = seatColor != null ? seatColor.awt : Color.WHITE;
        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(PLACE_REVEAL_LINE_SIZE));
        String stats = rsn + " -- " + plugin.getPlaceRevealGoldenGnomes() + " GG, " + plugin.getPlaceRevealCoins() + " coins";
        drawCenteredText(g, stats, centerX, y + 40, nameColor, alpha);
    }

    /** Penultimate beat -- "And the winner is...", the last breath before renderWinnerReveal. */
    private void renderWinnerSuspenseBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWinnerSuspenseUntil(), WINNER_SUSPENSE_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(WINNER_SUSPENSE_SIZE));
        drawCenteredText(g, "And the winner is...", centerX, y, Color.WHITE, alpha);
    }

    /** The payoff -- the winner's name in the rainbow treatment, plus their final coins/Golden
     * Gnome tally. Played alongside ConfettiOverlay's burst, which renders separately. */
    private void renderWinnerReveal(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getWinnerRevealUntil(), WINNER_REVEAL_FADE_MS);
        if (alpha == null) return;
        String rsn = plugin.getWinnerRsn();
        if (rsn == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2 - 10;

        g.setFont(MARIO_PARTY_FONT.deriveFont(WINNER_REVEAL_NAME_SIZE));
        drawCenteredRainbowText(g, rsn, RAINBOW_LETTER_COLORS, centerX, y, alpha);

        List<RosterReducer.RosterEntry> standings = plugin.getGameOverStandings();
        RosterReducer.RosterEntry winner = standings.isEmpty() ? null : standings.get(0);
        if (winner != null)
        {
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(WINNER_REVEAL_SUBTITLE_SIZE));
            String subtitle = winner.goldenGnomeCount + " Golden Gnomes, " + winner.coins + " coins";
            drawCenteredText(g, subtitle, centerX, y + 36, Color.LIGHT_GRAY, alpha);
        }
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

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(WELCOME_LEAD_SIZE));
        drawCenteredText(g, "WELCOME TO", centerX, y, Color.WHITE, alpha);

        g.setFont(MARIO_PARTY_FONT.deriveFont(RUNE_PARTY_SIZE));
        drawCenteredRainbowText(g, "RUNE PARTY", RAINBOW_LETTER_COLORS, centerX, y + 56, alpha);

        g.setFont(MARIO_PARTY_FONT.deriveFont(SHOWDOWN_SIZE));
        drawCenteredText(g, "SHOWDOWN", centerX, y + 96, WELCOME_TITLE_COLOR, alpha);
    }

    /** Draws the "HERE WE GO!" banner when the game starts, plus an instruction to gather on the
     * Start tile. */
    private void renderGameStartBanner(Graphics2D g)
    {
        Float alpha = BannerAnim.fadeAlpha(plugin.getGameStartBannerUntil(), GAME_START_FADE_MS);
        if (alpha == null) return;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(GAME_START_TITLE_SIZE));
        drawCenteredRainbowText(g, "HERE WE GO!", RAINBOW_LETTER_COLORS, centerX, y, alpha);

        g.setFont(FontManager.getRunescapeSmallFont());
        drawCenteredText(g, "Please stand on the Start Tile to begin.", centerX, y + 28, Color.LIGHT_GRAY, alpha);
    }

    /** Shared centered/shadowed string draw -- caller sets the font first. */
    private void drawCenteredText(Graphics2D g, String text, int centerX, int y, Color color, float alpha)
    {
        int x = centerX - g.getFontMetrics().stringWidth(text) / 2;
        drawLeftAlignedText(g, text, x, y, color, alpha);
    }

    /** Same as drawCenteredText, but colors each non-space character from {@code letterColors} in
     * order instead of one solid color. */
    private void drawCenteredRainbowText(Graphics2D g, String text, Color[] letterColors, int centerX, int y, float alpha)
    {
        FontMetrics fm = g.getFontMetrics();
        int totalWidth = 0;
        for (int i = 0; i < text.length(); i++) totalWidth += fm.charWidth(text.charAt(i));

        drawLeftAlignedRainbowText(g, text, letterColors, centerX - totalWidth / 2, y, alpha);
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
        int cx = client.getCanvasWidth() / 2 + jitterX;
        int cy = client.getCanvasHeight() / 2 + jitterY;

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
