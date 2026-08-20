package gay.runescape.runeparty;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.io.IOException;
import java.io.InputStream;
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
import net.runelite.client.util.Text;

/** Big, brief, screen-centered instructional banners -- the first of which is "<player>'s Turn" on
 * every TURN_STARTED, in that player's own RunePartyColor (or "Your Turn!" for the local player) --
 * plus the retro dice-roll reveal after a DICE_ROLLED event (see renderDiceRoll), screen-centered
 * for the same reason: everyone at the table should be able to see what got rolled without hunting
 * for the roller's model on screen, and a one-shot "Welcome to Rune Party Showdown" title card (see
 * renderWelcomeBanner) shown only to whoever just created/joined a game. Most of these are
 * fade-on-a-timer, same pattern as Gnomeball's TimerOverlay#renderGoalFlash: the plugin stamps an
 * until-timestamp when the triggering event lands (see RunePartyPlugin's
 * turnAnnounceRsn/turnAnnounceUntil, diceRollRsn/diceRollUntil, and welcomeBannerUntil), this
 * overlay just counts down and fades against it every frame. renderSpinHint is the one exception --
 * duration-less, it's just a live read of plugin state that shows/hides itself instantly rather
 * than fading on a clock -- and it's addressed differently per viewer rather than local-only: the
 * mover sees "Use the SPIN! emote...", everyone else sees "Waiting for &lt;player&gt; to roll the
 * dice..." (see renderSpinHintSelf/renderSpinHintWaiting), the same "same moment, addressed per
 * viewer" split renderTurnAnnouncement and renderGoldenGnomeOffer/renderGoldenGnomeOutcome also
 * use. Meant to grow with more instructional banners as the game does, not just these. */
@Slf4j
public class AnnouncementOverlay extends Overlay
{
    private static final long FADE_DURATION_MS = 500; // tail end of the banner's life spent fading out

    private static final Color DIE_BORDER = Color.BLACK;
    private static final Color DIE_PIP = new Color(255, 255, 255, 230);
    private static final int DIE_SIZE = 90; // face side length at rest (pre-scale), in pixels -- big enough to read from across the table
    private static final int DIE_CORNER = 14; // rounded-rect corner radius -- chunky, toy-like
    private static final float DIE_BORDER_WIDTH = 5f;
    private static final float DIE_NUMBER_SIZE = 48f;
    private static final float DIE_FACE_OPACITY = 0.45f; // the face is deliberately see-through -- the game behind it stays visible; border/pips/number stay closer to full strength so it still reads clearly as a die
    private static final long DIE_SPIN_FACE_MS = 70; // how often the cycling face changes during the spin phase
    private static final long DIE_SETTLE_POP_MS = 220; // brief overshoot-then-settle scale pop once the real value lands

    private static final Color WELCOME_TITLE_COLOR = new Color(255, 215, 0);
    private static final long WELCOME_FADE_MS = 700; // longer, slower fade than the other banners -- a title card, not a quick pop-up
    private static final float WELCOME_LEAD_SIZE = 20f; // "WELCOME TO"
    private static final float RUNE_PARTY_SIZE = 52f; // "RUNE PARTY" -- the biggest text on the card
    private static final float SHOWDOWN_SIZE = 32f; // "SHOWDOWN"

    // Mario-Party-style logo treatment, shared by every big one-word(ish) exclamation on this
    // overlay -- "RUNE PARTY", "MINIGAME!", "HERE WE GO!": one color per non-space character,
    // cycling through this exact 9-entry sequence -- see drawCenteredRainbowText, which walks the
    // string and pulls the next entry for each one. All three happen to have exactly 9 colorable
    // characters, so one array covers all of them.
    private static final Color RAINBOW_RED = new Color(230, 45, 45);
    private static final Color RAINBOW_GREEN = new Color(60, 190, 80);
    private static final Color RAINBOW_YELLOW = new Color(250, 210, 40);
    private static final Color RAINBOW_BLUE = new Color(60, 130, 230);
    private static final Color[] RAINBOW_LETTER_COLORS = {
        RAINBOW_RED, RAINBOW_GREEN, RAINBOW_YELLOW, RAINBOW_BLUE, RAINBOW_GREEN,
        RAINBOW_RED, RAINBOW_BLUE, RAINBOW_GREEN, RAINBOW_YELLOW,
    };

    private static final long MINIGAME_FADE_MS = 500;
    private static final float MINIGAME_TITLE_SIZE = 58f;

    // Shared spinner wheel -- see drawWheel, reused by both renderMinigameSpinner and
    // renderItemSpinner. Segments reuse RAINBOW_LETTER_COLORS (same colors as "SPIN"/"MINIGAME!"),
    // one per registered entry (see gay.runescape.runeparty.minigames.Minigames#all /
    // gay.runescape.runeparty.items.Items#all).
    private static final long WHEEL_FADE_MS = 400;
    private static final int WHEEL_MAX_SEGMENTS = 4; // see selectWheelEntries -- caps how many options the wheel ever shows, regardless of how many entries end up registered
    private static final float WHEEL_RADIUS = 90f;
    private static final float WHEEL_ICON_SIZE = 34f;
    private static final int WHEEL_EXTRA_SPINS = 3; // full rotations before settling, purely visual
    private static final long WHEEL_SETTLE_POP_MS = 220; // same overshoot-then-settle feel as the dice roll's DIE_SETTLE_POP_MS
    private static final float WHEEL_NAME_SIZE = 30f; // the settled entry's name, revealed once the wheel stops
    private static final Color WHEEL_POINTER_COLOR = new Color(255, 215, 0);

    // "You already have N items!" -- see renderItemCapBlocked. Sized like the Golden Gnome
    // outcome banner it's most similar to (a plain two-line explainer, no wheel).
    private static final long ITEM_CAP_BLOCKED_FADE_MS = 500;
    private static final float ITEM_CAP_BLOCKED_TITLE_SIZE = 28f;
    private static final float ITEM_CAP_BLOCKED_SUBTITLE_SIZE = 20f;

    // "You used/<rsn> used <item>!" -- see renderItemUsedAnnouncement. Same two-line shape/sizing
    // as the item cap banner above.
    private static final long ITEM_USED_ANNOUNCE_FADE_MS = 500;
    private static final float ITEM_USED_ANNOUNCE_TITLE_SIZE = 28f;
    private static final float ITEM_USED_ANNOUNCE_SUBTITLE_SIZE = 20f;

    // "+N"/"-N" bonus label beside the die once a bonus-carrying roll reaches its badge phase --
    // see renderDiceRoll's bonus phases (RunePartyPlugin.DICE_ROLL_BONUS_BADGE_MS/FLIP_MS). Same
    // gain/loss palette as PlayerOverlay's own coin popup (COIN_POPUP_GAIN_COLOR/LOSS_COLOR),
    // duplicated here rather than shared since that pair is private to PlayerOverlay.
    private static final float DICE_ROLL_BONUS_LABEL_SIZE = 26f;
    private static final Color DICE_ROLL_BONUS_POSITIVE_COLOR = new Color(80, 220, 80);
    private static final Color DICE_ROLL_BONUS_NEGATIVE_COLOR = new Color(230, 70, 70);

    // Mini-game ready-check -- see renderMinigameReadyCheck. Duration-less like the Spin hint/
    // Golden Gnome offer (it persists until every seated PLAYER's YES-emoted), so it gets the same
    // breathing-alpha treatment to stay noticeable without a fade to draw the eye.
    private static final long MINIGAME_READY_CHECK_PULSE_PERIOD_MS = 1400;
    private static final float MINIGAME_READY_CHECK_MIN_ALPHA = 0.6f;

    // Mini-game countdown ("3... 2... 1... BEGIN!") -- see renderMinigameCountdown. The numbers
    // are a plain solid color (yellow, matching RAINBOW_YELLOW's tone) rather than rainbow -- only
    // the final "BEGIN!" gets the rainbow logo treatment, as its own little payoff moment.
    private static final float MINIGAME_COUNTDOWN_SIZE = 90f;
    private static final long MINIGAME_COUNTDOWN_POP_MS = 260; // brief scale-in pop at the start of each tick, same idea as the dice roll's settle pop
    private static final Color MINIGAME_COUNTDOWN_NUMBER_COLOR = RAINBOW_YELLOW;

    private static final long GAME_START_FADE_MS = 600;
    private static final float GAME_START_TITLE_SIZE = 58f;

    private static final long ROUND_COMPLETE_FADE_MS = 500;
    private static final float ROUND_COMPLETE_TITLE_SIZE = 46f; // "ROUND x"
    private static final float ROUND_COMPLETE_SUBTITLE_SIZE = 20f; // "Current Standings"
    private static final float ROUND_COMPLETE_LINE_SIZE = 18f; // each player's standings line
    private static final int ROUND_COMPLETE_LINE_HEIGHT = 24;

    private static final long MINIGAME_REWARDS_FADE_MS = 500;
    private static final float MINIGAME_REWARDS_TITLE_SIZE = 46f; // "REWARDS"
    private static final float MINIGAME_REWARDS_LINE_SIZE = 18f; // each player's reward line
    private static final int MINIGAME_REWARDS_LINE_HEIGHT = 24;
    private static final Color MINIGAME_REWARDS_COLOR = new Color(80, 220, 120); // "+N coins"
    private static final Color MINIGAME_REWARDS_NONE_COLOR = Color.GRAY; // "no reward"

    // End-game awards ceremony -- see RunePartyPlugin#triggerGameOverSequence for the full chain
    // ("GAME OVER!" -> intro -> one place reveal per eliminated player -> suspense -> winner). Each
    // phase gets its own fade constant/size below, in the order it actually plays.
    private static final long GAME_OVER_TITLE_FADE_MS = 600;
    private static final float GAME_OVER_TITLE_SIZE = 64f; // "GAME OVER!" -- the biggest text of the whole ceremony

    private static final long WINNER_INTRO_FADE_MS = 500;
    private static final float WINNER_INTRO_SIZE = 26f; // "Now it's time to see the winner..."

    private static final long PLACE_REVEAL_FADE_MS = 500;
    private static final float PLACE_REVEAL_RANK_SIZE = 40f; // "In 4th place..."
    private static final float PLACE_REVEAL_LINE_SIZE = 26f; // "<Player> -- N GN, M coins"

    private static final long WINNER_SUSPENSE_FADE_MS = 500;
    private static final float WINNER_SUSPENSE_SIZE = 34f; // "And the winner is..."

    private static final long WINNER_REVEAL_FADE_MS = 700;
    private static final float WINNER_REVEAL_NAME_SIZE = 62f; // the winner's own name, rainbow-treated same as "GAME OVER!"
    private static final float WINNER_REVEAL_SUBTITLE_SIZE = 22f; // "N Golden Gnomes, M coins"

    private static final Color SPIN_HINT_COLOR = new Color(255, 255, 255);
    private static final float SPIN_HINT_SIZE = 22f;
    private static final long SPIN_HINT_PULSE_PERIOD_MS = 1400; // gentle breathing alpha so a hint that has to persist (no fixed duration) doesn't just sit there static and easy to ignore
    private static final float SPIN_HINT_MIN_ALPHA = 0.55f;

    private static final float GOLDEN_GNOME_OFFER_TITLE_SIZE = 30f; // "You found a GOLDEN GNOME!"
    private static final float GOLDEN_GNOME_OFFER_SUBTITLE_SIZE = 20f; // "Would you like to buy one?"
    private static final float GOLDEN_GNOME_OFFER_EMOTE_SIZE = 24f; // "'YES' emote: purchase" / "'NO' emote: decline"
    // Same breathing-alpha idea as the Spin hint -- this offer is also duration-less (it persists
    // until the local player's YES/NO emote resolves it), so it needs its own way to stay noticeable.
    private static final long GOLDEN_GNOME_OFFER_PULSE_PERIOD_MS = 1400;
    private static final float GOLDEN_GNOME_OFFER_MIN_ALPHA = 0.6f;

    private static final long GOLDEN_GNOME_OUTCOME_FADE_MS = 500;
    private static final float GOLDEN_GNOME_OUTCOME_SIZE = 32f;

    // Bundled at src/main/resources/gay/runescape/runeparty/mario-party-hudson.ttf -- loaded once
    // at class-init, deriveFont(size) per use same as FontManager's own fonts. Falls back to the
    // client's own bold font if the resource is ever missing, so a packaging mistake degrades
    // gracefully instead of crashing the overlay.
    private static final Font MARIO_PARTY_FONT = loadMarioPartyFont();

    private static Font loadMarioPartyFont()
    {
        try (InputStream is = AnnouncementOverlay.class.getResourceAsStream("mario-party-hudson.ttf"))
        {
            if (is == null) throw new IOException("mario-party-hudson.ttf resource not found");
            return Font.createFont(Font.TRUETYPE_FONT, is);
        }
        catch (FontFormatException | IOException e)
        {
            log.warn("Failed to load the Mario Party Hudson font, falling back to the default", e);
            return FontManager.getRunescapeBoldFont();
        }
    }

    private final Client client;
    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;

    public AnnouncementOverlay(Client client, RunePartyConfig config, RunePartyPlugin plugin)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showOverlay()) return null;
        GamePhase phase = plugin.getPhase();
        // ENDED is included alongside the usual ACTIVE/LOBBY here specifically for the end-game
        // awards ceremony below (see renderGameOverBanner and friends) -- GAME_ENDED is what flips
        // phase to ENDED in the first place, so without this the ceremony would never get a chance
        // to render past that exact instant. Every other render* call below still gates itself on
        // its own until-timestamp, so leaving them in the call list for ENDED too is harmless: they
        // simply have nothing left to show by the time the game's actually over.
        if (phase != GamePhase.ACTIVE && phase != GamePhase.LOBBY && phase != GamePhase.ENDED) return null;

        renderWelcomeBanner(g);
        renderGameStartBanner(g);
        renderTurnAnnouncement(g);
        renderSpinHint(g);
        renderGoldenGnomeOffer(g);
        renderGoldenGnomeOutcome(g);
        renderItemSpinner(g);
        renderItemCapBlocked(g);
        renderItemUsedAnnouncement(g);
        renderMinigameBanner(g);
        renderMinigameSpinner(g);
        renderMinigameReadyCheck(g);
        renderMinigameCountdown(g);
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

        long remaining = plugin.getTurnAnnounceUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < FADE_DURATION_MS ? remaining / (float) FADE_DURATION_MS : 1f;

        String localRsn = localRsn();
        String text = (localRsn != null && localRsn.equalsIgnoreCase(rsn)) ? "Your Turn!" : rsn + "'s Turn";

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(rsn));
        Color color = seatColor != null ? seatColor.awt : Color.WHITE;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(36f));
        drawCenteredText(g, text, client.getCanvasWidth() / 2, client.getCanvasHeight() / 4, color, alpha);
    }

    /** Dispatches to whichever half of the Spin hint applies to the local viewer -- "Use the SPIN!
     * emote..." (renderSpinHintSelf) for whoever's actually up, "Waiting for &lt;player&gt; to roll
     * the dice..." (renderSpinHintWaiting) for everyone else at the table, mirroring
     * renderTurnAnnouncement/renderGoldenGnomeOffer's own "same moment, addressed per viewer"
     * pattern. Neither half is tied to a server event or a fixed duration -- both are continuous
     * reads of plugin state (isLocalPlayerReadyToRoll / isAwaitingSomeonesRoll) that disappear the
     * instant they stop being true, same as before this split. The bystander half explicitly checks
     * it isn't looking at its own mover first, so someone who's wandered off their tile mid-turn
     * (isLocalPlayerReadyToRoll false for them, since they still need to walk back -- see
     * TileOverlay's "Return Here!" arrow) never sees "Waiting for themselves". */
    private void renderSpinHint(Graphics2D g)
    {
        if (plugin.isLocalPlayerReadyToRoll())
        {
            renderSpinHintSelf(g);
            return;
        }

        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null) return;
        String localRsn = localRsn();
        if (localRsn != null && localRsn.equalsIgnoreCase(moverRsn)) return;
        if (!plugin.isAwaitingSomeonesRoll()) return;

        renderSpinHintWaiting(g, moverRsn);
    }

    /** Reminds the local player, when it's their turn, to "Use the SPIN! emote to roll the
     * dice." -- or, if they're currently holding at least one item and haven't already spent this
     * turn's one-item allowance (see RosterReducer#getItems and RunePartyPlugin#isItemUsedThisTurn,
     * both live reads re-checked every frame, same as everything else here), "...or use an item in
     * the panel." tacked on instead, so the hint automatically reverts to the plain phrasing the
     * instant an item gets used -- whether or not any remain -- with no event of its own needed to
     * notice that. Only the wording changes; RunePartyPanel#refreshItemUse is what actually gates
     * the item buttons themselves. Deliberately never shown while they still need to walk back to their tile first
     * (isLocalPlayerReadyToRoll already requires that), so this and TileOverlay's "Return Here!"
     * arrow are always mutually exclusive rather than both nagging at once. Sits just under the
     * turn banner's own slot and gently pulses since, unlike that banner, it has no fade-out to
     * naturally draw the eye. "SPIN!" itself gets the same Mario-Party-logo rainbow-letter
     * treatment as "RUNE PARTY"/"MINIGAME!" (see drawLeftAlignedRainbowText and
     * RAINBOW_LETTER_COLORS -- "SPIN!" is only 5 characters, so it just uses the first 5 entries of
     * that 9-entry sequence), stitched into the plain-white rest of the sentence via
     * drawLeftAlignedText -- both return the x just past what they drew, so the three segments
     * chain into one still-centered line despite switching font and color partway through. */
    private void renderSpinHintSelf(Graphics2D g)
    {
        long phaseMs = System.currentTimeMillis() % SPIN_HINT_PULSE_PERIOD_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / SPIN_HINT_PULSE_PERIOD_MS));
        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * pulse;

        String self = localRsn();
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

    /** The bystander half of the Spin hint -- "Waiting for &lt;player&gt; to roll the dice..." in
     * the mover's own seat color for their name, same position/size/pulse as renderSpinHintSelf so
     * the two feel like the same hint just addressed differently, the way
     * renderTurnAnnouncement's "Your Turn!"/"&lt;rsn&gt;'s Turn" already do. */
    private void renderSpinHintWaiting(Graphics2D g, String rsn)
    {
        long phaseMs = System.currentTimeMillis() % SPIN_HINT_PULSE_PERIOD_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / SPIN_HINT_PULSE_PERIOD_MS));
        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * pulse;

        String prefix = "Waiting for ";
        String suffix = " to roll the dice...";

        Font font = FontManager.getRunescapeBoldFont().deriveFont(SPIN_HINT_SIZE);
        g.setFont(font);
        int prefixWidth = g.getFontMetrics().stringWidth(prefix);
        int nameWidth = g.getFontMetrics().stringWidth(rsn);
        int suffixWidth = g.getFontMetrics().stringWidth(suffix);

        int y = client.getCanvasHeight() / 4 + 40;
        int x = client.getCanvasWidth() / 2 - (prefixWidth + nameWidth + suffixWidth) / 2;

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(rsn));
        Color nameColor = seatColor != null ? seatColor.awt : SPIN_HINT_COLOR;

        x = drawLeftAlignedText(g, prefix, x, y, SPIN_HINT_COLOR, alpha);
        x = drawLeftAlignedText(g, rsn, x, y, nameColor, alpha);
        drawLeftAlignedText(g, suffix, x, y, SPIN_HINT_COLOR, alpha);
    }

    /** Draws the Golden Gnome offer -- "You found a GOLDEN GNOME!" (with "GOLDEN GNOME" in the
     * Mario Party Hudson font, same gold as "SHOWDOWN", stitched into the surrounding plain-white
     * text via drawLeftAlignedText the same way renderSpinHint stitches "SPIN"), then a subtitle and
     * either the real YES/NO emote instructions (see drawEmoteInstruction, "YES"/"NO" get the same
     * Mario-Party-logo rainbow-letter treatment as "SPIN") or a "waiting on them" line underneath,
     * depending on who's looking. Broadcast to everyone (like renderMinigameBanner/
     * renderGameStartBanner), not local-only, since it's a shared moment everyone's watching -- but
     * addressed differently per viewer the same way renderTurnAnnouncement already is: the finder
     * (goldenGnomeOfferRsn, compared against localRsn()) sees "You found..." plus the actual
     * actionable instructions, since only their own YES/NO emote does anything (see
     * RunePartyPlugin#isLocalPlayerAwaitingGoldenGnomeResponse); everyone else sees "&lt;rsn&gt;
     * found..." plus a plain "waiting on them" line instead of instructions that don't apply to
     * them. Duration-less like renderSpinHint -- it's a live read of goldenGnomeOfferRsn, not a
     * timed fade, so it pulses instead for the same "needs to stay noticeable without a fade to draw
     * the eye" reason. */
    private void renderGoldenGnomeOffer(Graphics2D g)
    {
        String finderRsn = plugin.getGoldenGnomeOfferRsn();
        if (finderRsn == null) return;

        long phaseMs = System.currentTimeMillis() % GOLDEN_GNOME_OFFER_PULSE_PERIOD_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / GOLDEN_GNOME_OFFER_PULSE_PERIOD_MS));
        float alpha = GOLDEN_GNOME_OFFER_MIN_ALPHA + (1f - GOLDEN_GNOME_OFFER_MIN_ALPHA) * pulse;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        String localRsn = localRsn();
        boolean isLocal = localRsn != null && localRsn.equalsIgnoreCase(finderRsn);

        String prefix = isLocal ? "You found a " : finderRsn + " found a ";
        String goldenGnome = "GOLDEN GNOME";
        String suffix = "!";

        Font normalFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE);
        Font goldenGnomeFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE);

        g.setFont(normalFont);
        int prefixWidth = g.getFontMetrics().stringWidth(prefix);
        int suffixWidth = g.getFontMetrics().stringWidth(suffix);
        g.setFont(goldenGnomeFont);
        int goldenGnomeWidth = g.getFontMetrics().stringWidth(goldenGnome);

        int x = centerX - (prefixWidth + goldenGnomeWidth + suffixWidth) / 2;

        g.setFont(normalFont);
        x = drawLeftAlignedText(g, prefix, x, y, Color.WHITE, alpha);
        g.setFont(goldenGnomeFont);
        x = drawLeftAlignedText(g, goldenGnome, x, y, WELCOME_TITLE_COLOR, alpha);
        g.setFont(normalFont);
        drawLeftAlignedText(g, suffix, x, y, Color.WHITE, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE));
        if (isLocal)
        {
            drawCenteredText(g, "Would you like to buy one?", centerX, y + 32, Color.WHITE, alpha);

            Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
            Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
            drawEmoteInstruction(g, "YES", " emote: purchase", emoteFont, emoteWordFont, centerX, y + 66, alpha);
            drawEmoteInstruction(g, "NO", " emote: decline", emoteFont, emoteWordFont, centerX, y + 96, alpha);
        }
        else
        {
            drawCenteredText(g, "Would " + finderRsn + " like to buy one?", centerX, y + 32, Color.WHITE, alpha);

            g.setFont(FontManager.getRunescapeSmallFont());
            drawCenteredText(g, "Waiting for " + finderRsn + " to decide...", centerX, y + 66, Color.LIGHT_GRAY, alpha);
        }
    }

    /** Draws one Golden Gnome emote instruction line -- {@code '<word>' emote: <suffix>} -- with
     * the quoted emote name in the Mario Party rainbow font and the rest in plain bold, all
     * centered as one line the same way renderGoldenGnomeOffer's own title stitches segments
     * together (just centered instead of pre-positioned, since there's no fixed left edge here). */
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

    /** Draws one renderRoundCompleteBanner standings row -- {@code <rank>  <name>   <stats>} -- as
     * one centered line with the rank in light gray, the name in the player's own seat color, and
     * the stats in light gray again, chained together the same segment-by-segment way
     * drawEmoteInstruction stitches its own three-part line. */
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

    /** Draws the Golden Gnome offer's follow-up -- "You got a Golden Gnome!" on a purchase, or
     * "You can't afford this!" if they accepted without enough coins (a decline gets no banner at
     * all, see RunePartyPlugin's GOLDEN_GNOME_OFFER_RESOLVED handling) -- fires immediately rather
     * than waiting on scheduleAfterTurnEffects, same as the coin/dice popups; it's the *next* turn's
     * own announcement that waits for this one via extendTurnEffectGate instead. Addressed to
     * whoever the outcome actually belongs to (goldenGnomeOutcomeRsn) the same way
     * renderTurnAnnouncement/renderGoldenGnomeOffer already are -- "You..." for that player, "
     * &lt;rsn&gt;..." for everyone else watching, rather than every client showing "You..." for an
     * outcome that may well belong to someone else. */
    private void renderGoldenGnomeOutcome(Graphics2D g)
    {
        long remaining = plugin.getGoldenGnomeOutcomeBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        String outcome = plugin.getGoldenGnomeOutcome();
        String rsn = plugin.getGoldenGnomeOutcomeRsn();
        String localRsn = localRsn();
        boolean isLocal = rsn != null && localRsn != null && localRsn.equalsIgnoreCase(rsn);

        String text;
        if ("purchased".equals(outcome))
        {
            text = isLocal ? "You got a Golden Gnome!" : rsn != null ? rsn + " got a Golden Gnome!" : null;
        }
        else if ("cant_afford".equals(outcome))
        {
            text = isLocal ? "You can't afford this!" : rsn != null ? rsn + " can't afford this!" : null;
        }
        else
        {
            text = null;
        }
        if (text == null) return;

        float alpha = remaining < GOLDEN_GNOME_OUTCOME_FADE_MS ? remaining / (float) GOLDEN_GNOME_OUTCOME_FADE_MS : 1f;
        Color color = "purchased".equals(outcome) ? WELCOME_TITLE_COLOR : Color.WHITE;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OUTCOME_SIZE));
        drawCenteredText(g, text, client.getCanvasWidth() / 2, client.getCanvasHeight() / 3, color, alpha);
    }

    /** Draws the "MINIGAME!" banner on a MINIGAME_STARTED event -- server-driven, so every client
     * shows it at the same moment, same Mario-Party-logo rainbow-letter treatment as "RUNE PARTY"
     * (see drawCenteredRainbowText and RAINBOW_LETTER_COLORS). A pure title card now -- the
     * mini-game's own instructions used to show underneath here, but that's the selection
     * spinner/ready-check screen's job instead (see renderMinigameSpinner/
     * renderMinigameReadyCheck, both chained to appear right after this banner), so showing them
     * twice would be redundant. This is also the fix for TileOverlay's target arrow otherwise
     * reappearing on the last roller of a round if they step off their landed tile during the
     * mini-game -- see TileOverlay#renderTargetArrow, which now suppresses itself whenever
     * isMinigameActive() is true instead of only reacting to this banner's presence. */
    private void renderMinigameBanner(Graphics2D g)
    {
        long remaining = plugin.getMinigameBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < MINIGAME_FADE_MS ? remaining / (float) MINIGAME_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        drawCenteredRainbowText(g, "MINIGAME!", RAINBOW_LETTER_COLORS, centerX, y, alpha);
    }

    /** Draws the mini-game selection spinner -- a rainbow prize wheel (RAINBOW_LETTER_COLORS,
     * same colors as "SPIN"/"MINIGAME!"), one segment per gay.runescape.runeparty.minigames.
     * Minigames#all entry, each showing that mini-game's own drawIcon. The mini-game itself was
     * already picked server-side (see MINIGAME_STARTED's "key"/RunePartyPlugin#getMinigameKey) --
     * this never gambles on the outcome, it only spins for MINIGAME_SPINNER_SPIN_PHASE_MS (eased
     * to a stop, same overshoot-then-settle feel as the dice roll's die) and always lands exactly
     * on the segment matching that key, then holds for MINIGAME_SPINNER_HOLD_MS with the mini-game's
     * getDisplayName() revealed underneath -- the "landing announces the name" moment. Triggered
     * from RunePartyPlugin#scheduleMinigameSpinner, chained behind the "MINIGAME!" banner. With
     * only one mini-game registered the wheel is a single segment that always wins -- it'll read as
     * a real spinner once more mini-games exist. The actual wheel drawing is shared with
     * renderItemSpinner via drawWheel -- this method only works out the timing/easing/target
     * segment, which differs per wheel "menu" (different underlying timestamps and entry lists). */
    private void renderMinigameSpinner(Graphics2D g)
    {
        long until = plugin.getMinigameSpinnerUntil();
        if (until == 0) return;
        long now = System.currentTimeMillis();
        long remaining = until - now;
        if (remaining <= 0) return;

        List<Minigame> all = Minigames.all();
        if (all.isEmpty()) return;

        Minigame selected = Minigames.get(plugin.getMinigameKey());
        List<Minigame> wheelEntries = selectWheelEntries(all, selected, plugin.getMinigameSpinnerStart());
        int targetIndex = Math.max(0, wheelEntries.indexOf(selected));

        long elapsed = now - plugin.getMinigameSpinnerStart();
        boolean spinning = elapsed < RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS;
        float rotationDeg = wheelRotationDeg(wheelEntries.size(), targetIndex, elapsed, RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS, spinning);
        float scale = wheelSettleScale(elapsed, RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS, spinning);
        float alpha = remaining < WHEEL_FADE_MS ? remaining / (float) WHEEL_FADE_MS : 1f;

        drawWheel(g, wheelEntries, targetIndex, rotationDeg, scale, alpha, spinning, selected.getDisplayName());
    }

    /** Draws the Item Space wheel -- the same shared drawWheel routine renderMinigameSpinner
     * uses, one segment per gay.runescape.runeparty.items.Items#all entry. The item itself was
     * already picked server-side (see ITEM_GRANTED's "itemKey"/RunePartyPlugin#getItemGrantKey)
     * -- this never gambles on the outcome, the same relationship the mini-game wheel has with
     * its own pick. Once settled, reveals "You got &lt;item&gt;!" for whoever actually landed on
     * the tile, "&lt;rsn&gt; got &lt;item&gt;!" for everyone else watching -- the same per-viewer
     * split renderGoldenGnomeOutcome uses. Triggered from RunePartyPlugin#scheduleItemSpinner,
     * chained behind whatever turn-effect visual (a coin popup, etc.) was already showing. */
    private void renderItemSpinner(Graphics2D g)
    {
        long until = plugin.getItemSpinnerUntil();
        if (until == 0) return;
        long now = System.currentTimeMillis();
        long remaining = until - now;
        if (remaining <= 0) return;

        List<Item> all = Items.all();
        if (all.isEmpty()) return;

        Item selected = Items.get(plugin.getItemGrantKey());
        List<Item> wheelEntries = selectWheelEntries(all, selected, plugin.getItemSpinnerStart());
        int targetIndex = Math.max(0, wheelEntries.indexOf(selected));

        long elapsed = now - plugin.getItemSpinnerStart();
        boolean spinning = elapsed < RunePartyPlugin.ITEM_SPINNER_SPIN_PHASE_MS;
        float rotationDeg = wheelRotationDeg(wheelEntries.size(), targetIndex, elapsed, RunePartyPlugin.ITEM_SPINNER_SPIN_PHASE_MS, spinning);
        float scale = wheelSettleScale(elapsed, RunePartyPlugin.ITEM_SPINNER_SPIN_PHASE_MS, spinning);
        float alpha = remaining < WHEEL_FADE_MS ? remaining / (float) WHEEL_FADE_MS : 1f;

        String grantRsn = plugin.getItemGrantRsn();
        String localRsn = localRsn();
        boolean isLocal = grantRsn != null && localRsn != null && localRsn.equalsIgnoreCase(grantRsn);
        String revealText = grantRsn == null ? null
            : isLocal ? "You got " + selected.getDisplayName() + "!"
            : grantRsn + " got " + selected.getDisplayName() + "!";

        drawWheel(g, wheelEntries, targetIndex, rotationDeg, scale, alpha, spinning, revealText);
    }

    /** Fires instead of renderItemSpinner when the server refuses to grant an item because the
     * mover's already holding RunePartyPlugin's ITEM_CAP (see ITEM_CAP_BLOCKED handling/
     * scheduleItemCapBlockedAnnouncement) -- no wheel, just a two-line explainer, same per-viewer
     * "You..."/"&lt;rsn&gt;..." split every other outcome banner on this overlay uses. */
    private void renderItemCapBlocked(Graphics2D g)
    {
        long remaining = plugin.getItemCapBlockedUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;
        String rsn = plugin.getItemCapBlockedRsn();
        if (rsn == null) return;

        float alpha = remaining < ITEM_CAP_BLOCKED_FADE_MS ? remaining / (float) ITEM_CAP_BLOCKED_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;
        int cap = plugin.getItemCapBlockedCap();

        String localRsn = localRsn();
        boolean isLocal = localRsn != null && localRsn.equalsIgnoreCase(rsn);

        String title = isLocal ? "You already have " + cap + " items!" : rsn + " already has " + cap + " items!";
        String subtitle = isLocal
            ? "You must use an item before you can receive any more."
            : "They must use an item before they can receive any more.";

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_CAP_BLOCKED_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, Color.WHITE, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_CAP_BLOCKED_SUBTITLE_SIZE));
        drawCenteredText(g, subtitle, centerX, y + 28, Color.LIGHT_GRAY, alpha);
    }

    /** Draws "You used/&lt;rsn&gt; used &lt;item&gt;!" plus that item's own subtitle (see
     * Item#getUseAnnouncementSubtitle) on ITEM_USED -- only ever armed for items that opt in via
     * Item#hasUseAnnouncement (see RunePartyPlugin's ITEM_USED handling/scheduleItemUsedAnnouncement);
     * PlaceholderItem never sets this up, so its coin popup remains its only feedback. Same
     * per-viewer "You.../&lt;rsn&gt;..." split and two-line layout as renderItemCapBlocked. */
    private void renderItemUsedAnnouncement(Graphics2D g)
    {
        long remaining = plugin.getItemUsedAnnounceUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;
        String rsn = plugin.getItemUsedAnnounceRsn();
        Item item = Items.get(plugin.getItemUsedAnnounceItemKey());
        if (rsn == null || item == null) return;

        float alpha = remaining < ITEM_USED_ANNOUNCE_FADE_MS ? remaining / (float) ITEM_USED_ANNOUNCE_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        String localRsn = localRsn();
        boolean isLocal = localRsn != null && localRsn.equalsIgnoreCase(rsn);

        String title = (isLocal ? "You used " : rsn + " used ") + item.getDisplayName() + "!";
        String subtitle = item.getUseAnnouncementSubtitle(isLocal);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_TITLE_SIZE));
        drawCenteredText(g, title, centerX, y, Color.WHITE, alpha);

        if (subtitle != null)
        {
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ITEM_USED_ANNOUNCE_SUBTITLE_SIZE));
            drawCenteredText(g, subtitle, centerX, y + 28, Color.LIGHT_GRAY, alpha);
        }
    }

    /** How far the wheel has rotated at {@code elapsed} into its spin -- eased to a stop
     * (ease-out cubic) across {@code spinPhaseMs}, then held fixed on the target once settled.
     * Shared timing math for both renderMinigameSpinner and renderItemSpinner. */
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

    /** The brief overshoot-then-settle scale pop right after the wheel stops -- same feel as the
     * dice roll's DIE_SETTLE_POP_MS. 1f while still spinning or once the pop's finished. */
    private static float wheelSettleScale(long elapsed, long spinPhaseMs, boolean spinning)
    {
        if (spinning) return 1f;
        long sinceSettle = elapsed - spinPhaseMs;
        if (sinceSettle >= WHEEL_SETTLE_POP_MS) return 1f;
        float t = sinceSettle / (float) WHEEL_SETTLE_POP_MS;
        return 1.25f - 0.25f * t;
    }

    /** Draws one frame of a spinner wheel -- wedges, border, each entry's icon, a fixed pointer
     * above it, and (once settled) {@code revealText} shown underneath in the Mario-Party-logo
     * rainbow treatment. Shared by renderMinigameSpinner and renderItemSpinner -- everything
     * wheel-specific (which entries, how far rotated, what scale/alpha, and what the reveal
     * actually says once settled -- the mini-game wheel just shows the plain name, the item wheel
     * phrases it as "You got..."/"&lt;rsn&gt; got...", see renderItemSpinner) is worked out by the
     * caller; this only knows how to paint one. {@code revealText} is ignored while
     * {@code spinning}, and skipped entirely if null. */
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
            drawWheelWedge(wheel, cx, cy, radius, i * segmentDeg, (i + 1) * segmentDeg, withAlpha(base, alpha * 0.85f));
        }
        wheel.setStroke(new BasicStroke(2f));
        wheel.setColor(withAlpha(Color.WHITE, alpha));
        wheel.drawOval(Math.round(cx - radius), Math.round(cy - radius), Math.round(radius * 2), Math.round(radius * 2));
        for (int i = 0; i < n; i++)
        {
            float center = i * segmentDeg + segmentDeg / 2f;
            Point2D.Float p = pointOnCircle(cx, cy, radius * 0.62f, center);
            // Counter-rotate by -rotationDeg around the icon's own (already-orbited) position:
            // cancels out just the local tumble the wheel's own rotate() would otherwise put on
            // each icon's drawing, leaving the orbit (their position swinging around the center as
            // the wheel spins) intact. Without this, every icon spins in place along with its
            // wedge, landing at whatever arbitrary tilt the settle angle happens to be -- including
            // the revealed winner, which is the most visible case. Upright icons orbiting a
            // spinning wheel is the standard prize-wheel look (e.g. Mario Party's own item
            // roulette) precisely because raw wedge-locked icons read as broken/awkward instead.
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
        g.setColor(withAlpha(WHEEL_POINTER_COLOR, alpha));
        g.fillPolygon(pointer);

        if (!spinning && revealText != null)
        {
            g.setFont(MARIO_PARTY_FONT.deriveFont(WHEEL_NAME_SIZE));
            drawCenteredRainbowText(g, revealText, RAINBOW_LETTER_COLORS, cx, Math.round(cy + WHEEL_RADIUS + 50), alpha);
        }
    }

    /** Picks which entries actually appear on the wheel -- every one if there are
     * WHEEL_MAX_SEGMENTS or fewer registered, otherwise {@code selected} (it has to be showable,
     * since the wheel always lands on it) plus a sample of the rest, so the wheel never grows
     * unboundedly as more mini-games/items get added to their registries. Seeded off
     * {@code seed} -- each caller passes its own spin's start timestamp, constant for that spin's
     * whole animation, rather than System.currentTimeMillis() -- so the same sample holds steady
     * across every frame instead of reshuffling on each one. */
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

    /** Fills one wedge of the selection spinner's wheel -- a triangle fan from the center out to
     * the circle, approximated with short line segments between {@code startAngleDeg} and
     * {@code endAngleDeg} (clockwise from straight up, see pointOnCircle) rather than an Arc2D, so
     * the angle convention here matches pointOnCircle's exactly instead of juggling two different
     * ones. */
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
     * clockwise from straight up (0 = top, 90 = right, 180 = bottom, 270 = left) -- the "clock
     * face" convention, chosen over Arc2D's mathematical (counterclockwise-from-3-o'clock) one
     * specifically so every angle used by the spinner wheel means the same thing everywhere it's
     * used. */
    private static Point2D.Float pointOnCircle(int cx, int cy, float radius, float angleDeg)
    {
        double rad = Math.toRadians(angleDeg);
        return new Point2D.Float((float) (cx + radius * Math.sin(rad)), (float) (cy - radius * Math.cos(rad)));
    }

    /** Draws the mini-game ready-check screen -- the mini-game's name and instructions (moved
     * here from renderMinigameBanner's old sub-line), "Use the 'YES' emote when you're ready!" in
     * the same rainbow-word treatment renderGoldenGnomeOffer uses for its own YES/NO instructions
     * (see drawEmoteInstruction), then every seated, joined PLAYER in turn order (same order
     * StatsOverlay uses) with a Ready/Waiting status pulled from RunePartyPlugin#
     * getMinigameReadyRsns. Not timer-gated, like renderGoldenGnomeOffer -- it's a live read of
     * plugin state, kept visible for MINIGAME_COUNTDOWN_START_DELAY_MS after the last player's
     * ready lands (see the countdownRevealed check below) specifically so everyone gets a beat to
     * actually see every player marked "Ready!" before the screen changes, rather than it flipping
     * to the countdown the instant the last YES emote resolves.
     * <p>
     * Both gates here follow the same shape: real state (isMinigameCountdownStarted/
     * isMinigameSpinnerSkippedForClient) always applies, but the cosmetic timestamp that actually
     * reveals the *next* screen (getMinigameCountdownBannerUntil/getMinigameSpinnerUntil) only gets
     * armed for a client watching things happen live -- checking "now &lt; until" directly would be
     * wrong in both cases, since until legitimately sits at 0 both before that timestamp is armed
     * *and* forever for a client that caught up after the fact, and those two need opposite
     * behavior (keep waiting vs. skip straight through). isMinigameCountdownSkippedForClient()/
     * isMinigameSpinnerSkippedForClient() are what tell them apart. */
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

        long phaseMs = now % MINIGAME_READY_CHECK_PULSE_PERIOD_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / MINIGAME_READY_CHECK_PULSE_PERIOD_MS));
        float alpha = MINIGAME_READY_CHECK_MIN_ALPHA + (1f - MINIGAME_READY_CHECK_MIN_ALPHA) * pulse;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        String displayName = plugin.getMinigameDisplayName();
        if (displayName != null)
        {
            g.setFont(MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_TITLE_SIZE));
            drawCenteredText(g, displayName, centerX, y, WELCOME_TITLE_COLOR, alpha);
        }

        String instructions = plugin.getMinigameInstructions();
        if (instructions != null)
        {
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_SUBTITLE_SIZE));
            drawCenteredText(g, instructions, centerX, y + 30, Color.WHITE, alpha);
        }

        Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        drawEmoteInstruction(g, "YES", " emote when you're ready!", emoteFont, emoteWordFont, centerX, y + 66, alpha);

        Set<String> ready = plugin.getMinigameReadyRsns();
        List<RosterReducer.RosterEntry> players = new ArrayList<>();
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().snapshot())
        {
            if (entry.role == RunePartyRole.PLAYER && entry.joined) players.add(entry);
        }
        players.sort(Comparator.comparing((RosterReducer.RosterEntry e) -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        int lineY = y + 100;
        for (RosterReducer.RosterEntry entry : players)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.number);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            boolean isReady = ready.contains(entry.rsn.toLowerCase());
            String status = isReady ? "   Ready!" : "   Waiting...";
            Color statusColor = isReady ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR;
            drawStandingsLine(g, nameFont, statsFont, "", entry.rsn, nameColor, status, statusColor, centerX, lineY, alpha);
            lineY += ROUND_COMPLETE_LINE_HEIGHT;
        }
    }

    /** Draws the "3... 2... 1... BEGIN!" countdown once every seated PLAYER's YES-emoted ready and
     * the ready-check screen's own MINIGAME_COUNTDOWN_START_DELAY_MS pause has elapsed (see
     * MINIGAME_COUNTDOWN_STARTED/RunePartyPlugin#getMinigameCountdownBannerUntil) -- one tick at a
     * time, derived from elapsed time the same "shown = f(elapsed)" way renderDiceRoll cycles its
     * die face, with a brief scale-in pop each time the tick changes. The numbers are a plain
     * yellow (MINIGAME_COUNTDOWN_NUMBER_COLOR); the final "BEGIN!" switches to the same
     * Mario-Party-logo rainbow treatment as "MINIGAME!"/"HERE WE GO!" as its own little payoff.
     * Only a client watching this happen live ever sees it -- see isMinigamePlayable's catch-up
     * split, the reason a reconnecting client skips straight to playable instead of waiting here. */
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

    /** Draws the mini-game rewards recap -- "REWARDS" in the same Mario-Party-logo rainbow
     * treatment as "ROUND x"/"MINIGAME!", then every seated, joined PLAYER with the coins they
     * received from the mini-game that just ended, highest reward first ("no reward" in gray for
     * anyone not in that list) -- see RunePartyPlugin#triggerMinigameRewardsBanner, which parses
     * MINIGAME_ENDED's own "payouts" list once at trigger time. Who's in that list and for how
     * much is entirely up to whichever Minigame just ran (see app.py/minigames -- each one defines
     * its own resolve_rewards()), this banner just displays whatever it decided. Shown *before*
     * renderRoundCompleteBanner -- see RunePartyPlugin#scheduleRoundCompleteBanner, which defers
     * that one behind this banner's own turnEffectGateUntil extension instead of both appearing at
     * once. */
    private void renderMinigameRewardsBanner(Graphics2D g)
    {
        long remaining = plugin.getMinigameRewardsBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < MINIGAME_REWARDS_FADE_MS ? remaining / (float) MINIGAME_REWARDS_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_REWARDS_TITLE_SIZE));
        drawCenteredRainbowText(g, "REWARDS", RAINBOW_LETTER_COLORS, centerX, y, alpha);

        Map<String, Integer> rewardByRsn = new HashMap<>();
        for (RunePartyPlugin.MinigameReward reward : plugin.getMinigameRewards())
        {
            rewardByRsn.put(reward.rsn.toLowerCase(), reward.coins);
        }

        List<RosterReducer.RosterEntry> players = new ArrayList<>();
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().snapshot())
        {
            if (entry.role == RunePartyRole.PLAYER && entry.joined) players.add(entry);
        }
        players.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> rewardByRsn.getOrDefault(e.rsn.toLowerCase(), 0))
            .reversed()
            .thenComparing(e -> e.number));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(MINIGAME_REWARDS_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(MINIGAME_REWARDS_LINE_SIZE);

        int lineY = y + 40;
        for (RosterReducer.RosterEntry entry : players)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.number);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            Integer coins = rewardByRsn.get(entry.rsn.toLowerCase());
            String stats = coins != null ? "   +" + coins + " coins" : "   no reward";
            Color statsColor = coins != null ? MINIGAME_REWARDS_COLOR : MINIGAME_REWARDS_NONE_COLOR;
            drawStandingsLine(g, nameFont, statsFont, "", entry.rsn, nameColor, stats, statsColor, centerX, lineY, alpha);
            lineY += MINIGAME_REWARDS_LINE_HEIGHT;
        }
    }

    /** Draws the post-round recap -- "ROUND x" (the *upcoming* round about to start, not the one
     * that just finished -- see RunePartyPlugin#scheduleRoundCompleteBanner) in the same
     * Mario-Party-logo rainbow treatment as "MINIGAME!"/"HERE WE GO!" (see drawCenteredRainbowText
     * and RAINBOW_LETTER_COLORS), then "Current Standings" underneath, then every seated, joined
     * PLAYER ranked highest-Golden-Gnomes-first (coins as the tiebreak -- Mario Party's own
     * standings order), each name in that player's own RunePartyColor same as
     * StatsOverlay/PlayerOverlay color-code the same player. StatsOverlay's persistent HUD used to
     * rank players this same way before it switched to always showing turn order (see
     * StatsOverlay); this recap is now the one place a ranked view still exists. Triggered from
     * RunePartyPlugin#scheduleRoundCompleteBanner on MINIGAME_ENDED, which also extends
     * turnEffectGateUntil so the new round's first TURN_STARTED banner waits behind this one
     * instead of overlapping it. */
    private void renderRoundCompleteBanner(Graphics2D g)
    {
        long remaining = plugin.getRoundCompleteBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < ROUND_COMPLETE_FADE_MS ? remaining / (float) ROUND_COMPLETE_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(ROUND_COMPLETE_TITLE_SIZE));
        drawCenteredRainbowText(g, "ROUND " + plugin.getRoundCompleteRoundNumber(), RAINBOW_LETTER_COLORS, centerX, y, alpha);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_SUBTITLE_SIZE));
        drawCenteredText(g, "Current Standings", centerX, y + 34, Color.WHITE, alpha);

        List<RosterReducer.RosterEntry> players = new ArrayList<>();
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().snapshot())
        {
            if (entry.role == RunePartyRole.PLAYER && entry.joined) players.add(entry);
        }
        players.sort(Comparator
            .comparingInt((RosterReducer.RosterEntry e) -> e.goldenGnomeCount).reversed()
            .thenComparing(Comparator.comparingInt((RosterReducer.RosterEntry e) -> e.coins).reversed()));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);

        int lineY = y + 66;
        int rank = 1;
        for (RosterReducer.RosterEntry entry : players)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.number);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            String stats = "   " + entry.goldenGnomeCount + " GN, " + entry.coins + " coins";
            drawStandingsLine(g, nameFont, statsFont, "#" + rank + "  ", entry.rsn, nameColor, stats, Color.LIGHT_GRAY, centerX, lineY, alpha);
            lineY += ROUND_COMPLETE_LINE_HEIGHT;
            rank++;
        }
    }

    /** First beat of the end-game awards ceremony -- "GAME OVER!" in the same Mario-Party-logo
     * rainbow treatment as "RUNE PARTY"/"MINIGAME!"/"HERE WE GO!", gated on
     * RunePartyPlugin#getGameOverBannerUntil (see triggerGameOverSequence). */
    private void renderGameOverBanner(Graphics2D g)
    {
        long remaining = plugin.getGameOverBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < GAME_OVER_TITLE_FADE_MS ? remaining / (float) GAME_OVER_TITLE_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2 - 20;

        g.setFont(MARIO_PARTY_FONT.deriveFont(GAME_OVER_TITLE_SIZE));
        drawCenteredRainbowText(g, "GAME OVER!", RAINBOW_LETTER_COLORS, centerX, y, alpha);
    }

    /** Second beat -- "Now it's time to see the winner...", a plain subtitle bridging "GAME OVER!"
     * into the standings countdown (see renderPlaceReveal). Gated on
     * RunePartyPlugin#getWinnerIntroBannerUntil, scheduled behind the game-over title via
     * scheduleWinnerIntro. */
    private void renderWinnerIntroBanner(Graphics2D g)
    {
        long remaining = plugin.getWinnerIntroBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < WINNER_INTRO_FADE_MS ? remaining / (float) WINNER_INTRO_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(WINNER_INTRO_SIZE));
        drawCenteredText(g, "Now it's time to see the winner...", centerX, y, Color.WHITE, alpha);
    }

    /** The dramatic countdown itself -- one "In &lt;Nth&gt; place... &lt;Player&gt; -- N coins"
     * reveal per call, worst-place first, stopping once only the top two players remain (see
     * RunePartyPlugin#schedulePlaceReveal). The player's own RunePartyColor seat color is used for
     * their name/coins line, same as every other standings view, so it reads consistently with
     * renderRoundCompleteBanner's mid-game recap. */
    private void renderPlaceReveal(Graphics2D g)
    {
        long remaining = plugin.getPlaceRevealUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;
        String rsn = plugin.getPlaceRevealRsn();
        if (rsn == null) return;

        float alpha = remaining < PLACE_REVEAL_FADE_MS ? remaining / (float) PLACE_REVEAL_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2 - 20;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(PLACE_REVEAL_RANK_SIZE));
        drawCenteredText(g, "In " + ordinal(plugin.getPlaceRevealRank()) + " place...", centerX, y, Color.LIGHT_GRAY, alpha);

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(rsn));
        Color nameColor = seatColor != null ? seatColor.awt : Color.WHITE;
        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(PLACE_REVEAL_LINE_SIZE));
        String stats = rsn + " -- " + plugin.getPlaceRevealGoldenGnomes() + " GN, " + plugin.getPlaceRevealCoins() + " coins";
        drawCenteredText(g, stats, centerX, y + 40, nameColor, alpha);
    }

    /** Penultimate beat -- "And the winner is...", the last breath before renderWinnerReveal. Gated
     * on RunePartyPlugin#getWinnerSuspenseUntil, scheduled behind the last place reveal (or
     * directly behind the intro, for a 2-player game with nothing to individually reveal). */
    private void renderWinnerSuspenseBanner(Graphics2D g)
    {
        long remaining = plugin.getWinnerSuspenseUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < WINNER_SUSPENSE_FADE_MS ? remaining / (float) WINNER_SUSPENSE_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 2;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(WINNER_SUSPENSE_SIZE));
        drawCenteredText(g, "And the winner is...", centerX, y, Color.WHITE, alpha);
    }

    /** The payoff -- the winner's own name in the same Mario-Party-logo rainbow treatment as "GAME
     * OVER!"/"RUNE PARTY", plus their final coins/Golden Gnome tally underneath. Played alongside
     * ConfettiOverlay's burst (see RunePartyPlugin#getConfettiUntil, kicked off the same instant by
     * scheduleWinnerReveal) -- this method only draws the text; the confetti itself is a fully
     * separate overlay so it can render above/around everything else on screen, not just within
     * this banner's own bounds. */
    private void renderWinnerReveal(Graphics2D g)
    {
        long remaining = plugin.getWinnerRevealUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;
        String rsn = plugin.getWinnerRsn();
        if (rsn == null) return;

        float alpha = remaining < WINNER_REVEAL_FADE_MS ? remaining / (float) WINNER_REVEAL_FADE_MS : 1f;
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

    /** "1st"/"2nd"/"3rd"/"4th"... -- the 11-13 exception (11th/12th/13th, not 11st/12nd/13rd) is
     * the only wrinkle in an otherwise last-digit-based rule. */
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

    /** Draws the one-shot title card -- "WELCOME TO", then "RUNE PARTY" on its own line (in the
     * Mario Party Hudson font, one rainbow color per letter, and the biggest text on the card),
     * then "SHOWDOWN" underneath -- see RunePartyPlugin#triggerWelcomeBanner, called once right
     * after createGame/joinGame succeeds. Local-player-only: there's no server event backing this,
     * so nobody else at the table ever sees it. Slower to fade than the other banners
     * (WELCOME_FADE_MS vs FADE_DURATION_MS) since this is meant to read as a proper title card, not
     * a quick pop-up. */
    private void renderWelcomeBanner(Graphics2D g)
    {
        long remaining = plugin.getWelcomeBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < WELCOME_FADE_MS ? remaining / (float) WELCOME_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(WELCOME_LEAD_SIZE));
        drawCenteredText(g, "WELCOME TO", centerX, y, Color.WHITE, alpha);

        g.setFont(MARIO_PARTY_FONT.deriveFont(RUNE_PARTY_SIZE));
        drawCenteredRainbowText(g, "RUNE PARTY", RAINBOW_LETTER_COLORS, centerX, y + 56, alpha);

        g.setFont(MARIO_PARTY_FONT.deriveFont(SHOWDOWN_SIZE));
        drawCenteredText(g, "SHOWDOWN", centerX, y + 96, WELCOME_TITLE_COLOR, alpha);
    }

    /** Draws the "HERE WE GO!" banner on a GAME_STARTED event -- server-driven, so host and joiners
     * alike see it at the same moment, same Mario-Party-logo rainbow-letter treatment as "RUNE
     * PARTY"/"MINIGAME!" (see drawCenteredRainbowText and RAINBOW_LETTER_COLORS), plus an
     * instruction line underneath telling everyone where to gather -- the same START tile
     * TileOverlay#renderStartArrow points a bouncing arrow at for the rest of the gathering window,
     * this is just the one-shot announcement that window has begun. Fires immediately (no
     * turnEffectGateUntil delay, unlike the turn/minigame banners) since nothing can be mid-effect
     * before the game has even started. */
    private void renderGameStartBanner(Graphics2D g)
    {
        long remaining = plugin.getGameStartBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < GAME_START_FADE_MS ? remaining / (float) GAME_START_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(GAME_START_TITLE_SIZE));
        drawCenteredRainbowText(g, "HERE WE GO!", RAINBOW_LETTER_COLORS, centerX, y, alpha);

        g.setFont(FontManager.getRunescapeSmallFont());
        drawCenteredText(g, "Please stand on the Start Tile to begin.", centerX, y + 28, Color.LIGHT_GRAY, alpha);
    }

    /** Shared centered/shadowed string draw for every banner in this overlay -- caller sets the
     * font first (they all differ in size). */
    private void drawCenteredText(Graphics2D g, String text, int centerX, int y, Color color, float alpha)
    {
        int x = centerX - g.getFontMetrics().stringWidth(text) / 2;
        drawLeftAlignedText(g, text, x, y, color, alpha);
    }

    /** Same centered/shadowed draw as drawCenteredText, but colors each non-space character from
     * {@code letterColors} in order instead of drawing the whole string in one color -- the
     * Mario-Party-logo look for "RUNE PARTY". Measures total width first (summing per-character
     * advances, since a mixed-color string can't use a single stringWidth call) so the whole line
     * still centers correctly, then hands off to drawLeftAlignedRainbowText to actually draw it. */
    private void drawCenteredRainbowText(Graphics2D g, String text, Color[] letterColors, int centerX, int y, float alpha)
    {
        FontMetrics fm = g.getFontMetrics();
        int totalWidth = 0;
        for (int i = 0; i < text.length(); i++) totalWidth += fm.charWidth(text.charAt(i));

        drawLeftAlignedRainbowText(g, text, letterColors, centerX - totalWidth / 2, y, alpha);
    }

    /** Draws {@code text} left-aligned from canvas x {@code x} at baseline y {@code y}, same
     * shadow treatment as every other banner, and returns the x just past what it drew -- so a
     * composite line built from differently-styled segments (see renderSpinHint, which chains this
     * with drawLeftAlignedRainbowText) can keep chaining off the end of the previous one. */
    private int drawLeftAlignedText(Graphics2D g, String text, int x, int y, Color color, float alpha)
    {
        g.setColor(withAlpha(Color.BLACK, alpha * 0.7f));
        g.drawString(text, x + 2, y + 2);
        g.setColor(withAlpha(color, alpha));
        g.drawString(text, x, y);
        return x + g.getFontMetrics().stringWidth(text);
    }

    /** Left-aligned counterpart to drawCenteredRainbowText -- draws {@code text} starting at canvas
     * x {@code x}, one color per non-space character pulled from {@code letterColors} starting at
     * index 0, and returns the x just past what it drew. */
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
                g.setColor(withAlpha(Color.BLACK, alpha * 0.7f));
                g.drawString(s, x + 2, y + 2);
                g.setColor(withAlpha(color, alpha));
                g.drawString(s, x, y);
                colorIndex++;
            }
            x += charWidth;
        }
        return x;
    }

    /** Draws a big, retro, toy-like die dead center of the screen after a DICE_ROLLED event --
     * filled in the roller's own seat color (at DIE_FACE_OPACITY, so the game underneath stays
     * visible through it) so it's obvious at a glance whose roll this is, faces 1-10 rather than
     * the usual 1-6 (see RunePartyPlugin's DICE_ROLLED handling, which is what actually rolls it --
     * this is purely the client-side reveal, and screen-centered rather than anchored to the
     * roller's model so every client can see it regardless of camera angle or whether the roller is
     * even in view). Cycles through random faces every DIE_SPIN_FACE_MS for
     * RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS to read as "rolling" (with a little jitter for
     * motion) -- that phase only starts once rollDice() actually fires, which onAnimationChanged
     * delays until the Spin emote itself has finished, so the die never starts cycling mid-emote.
     * It then snaps to the real value with a brief overshoot pop, holds it, and fades -- same
     * start/until-timestamp pattern as renderTurnAnnouncement, stamped by RunePartyPlugin's
     * diceRollStart/diceRollUntil.
     * <p>
     * When the roll carried an item bonus (getDiceRollBonus != 0 -- see Energy Potion), three extra
     * beats are spliced in right after the initial settle pop, before the normal hold begins: the
     * die first settles on the bare base roll same as a plain roll would, holds there for
     * RunePartyPlugin.DICE_ROLL_BONUS_BADGE_MS while a "+N" label pops in underneath, then over
     * DICE_ROLL_BONUS_FLIP_MS the die itself pops again to the
     * bonus-inclusive total while the label grows into "+N = total" -- after which the label
     * disappears and rendering continues exactly as a plain roll's hold/fade would. TileOverlay's
     * renderTargetArrow waits out this same window before revealing where that total actually
     * lands. A plain roll (bonus == 0) never enters either extra branch below, so its timeline is
     * byte-for-byte the original one. */
    private void renderDiceRoll(Graphics2D g)
    {
        String rsn = plugin.getDiceRollRsn();
        if (rsn == null) return;

        long now = System.currentTimeMillis();
        long remaining = plugin.getDiceRollUntil() - now;
        if (remaining <= 0) return;

        long elapsed = now - plugin.getDiceRollStart();
        boolean spinning = elapsed < RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS;

        int bonus = plugin.getDiceRollBonus();
        int total = plugin.getDiceRollValue();
        int base = total - bonus;

        // Sub-phase boundaries within the bonus reveal, relative to the moment the spin itself
        // ends -- see this method's own doc for what happens in each. All three collapse to the
        // exact same point (DIE_SETTLE_POP_MS) when bonus == 0, so the "flipping"/"badge" branches
        // below are simply never reached for a plain roll.
        long sinceSpinEnd = elapsed - RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS;
        long badgeStart = DIE_SETTLE_POP_MS;
        long flipStart = badgeStart + (bonus != 0 ? RunePartyPlugin.DICE_ROLL_BONUS_BADGE_MS : 0);
        long flipEnd = flipStart + (bonus != 0 ? RunePartyPlugin.DICE_ROLL_BONUS_FLIP_MS : 0);
        // The merged "+N = total" label outlives the flip pop itself by RESULT_HOLD_MS, so it's
        // actually readable rather than vanishing the instant the die finishes popping.
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
            // Flipping from the base roll to the bonus-inclusive total -- same overshoot pop as
            // the initial settle, just re-triggered on the flip itself.
            shown = total;
            float t = (sinceSpinEnd - flipStart) / (float) RunePartyPlugin.DICE_ROLL_BONUS_FLIP_MS;
            scale = 1.35f - 0.35f * t;
        }
        else
        {
            shown = total;
            // Only bonus == 0 ever reaches this with sinceSpinEnd < DIE_SETTLE_POP_MS -- a
            // bonus-carrying roll's own settle pop already played out above, in the base-roll
            // branch, so by the time it falls through here the die is done popping.
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

        float alpha = remaining < RunePartyPlugin.DICE_ROLL_FADE_MS ? remaining / (float) RunePartyPlugin.DICE_ROLL_FADE_MS : 1f;

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(rsn));
        Color color = seatColor != null ? seatColor.awt : Color.WHITE;

        g.setColor(withAlpha(Color.BLACK, alpha * 0.3f));
        g.fillRoundRect(cx - half + 4, cy - half + 4, size, size, DIE_CORNER, DIE_CORNER);

        g.setColor(withAlpha(color, alpha * DIE_FACE_OPACITY));
        g.fillRoundRect(cx - half, cy - half, size, size, DIE_CORNER, DIE_CORNER);
        g.setStroke(new BasicStroke(DIE_BORDER_WIDTH));
        g.setColor(withAlpha(DIE_BORDER, alpha));
        g.drawRoundRect(cx - half, cy - half, size, size, DIE_CORNER, DIE_CORNER);

        int pipPad = Math.max(10, size / 6);
        int pipR = 5;
        g.setColor(withAlpha(DIE_PIP, alpha));
        g.fillOval(cx - half + pipPad - pipR, cy - half + pipPad - pipR, pipR * 2, pipR * 2);
        g.fillOval(cx + half - pipPad - pipR, cy - half + pipPad - pipR, pipR * 2, pipR * 2);
        g.fillOval(cx - half + pipPad - pipR, cy + half - pipPad - pipR, pipR * 2, pipR * 2);
        g.fillOval(cx + half - pipPad - pipR, cy + half - pipPad - pipR, pipR * 2, pipR * 2);

        g.setFont(FontManager.getRunescapeBoldFont().deriveFont(DIE_NUMBER_SIZE));
        String text = String.valueOf(shown);
        FontMetrics fm = g.getFontMetrics();
        int tx = cx - fm.stringWidth(text) / 2;
        int ty = cy + fm.getAscent() / 2 - 4;
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.drawString(text, tx + 2, ty + 2);
        g.setColor(withAlpha(Color.WHITE, alpha));
        g.drawString(text, tx, ty);

        // "+N"/"-N" (then "+N = total"/"-N = total" once the flip starts) underneath the die --
        // only during the bonus reveal itself; gone again by the time the normal hold (and
        // TileOverlay's target arrow) takes over. See this method's own doc for the phase
        // boundaries. bonus's own sign covers the negative case -- only a positive one needs "+"
        // stitched on explicitly.
        if (bonus != 0 && !spinning && sinceSpinEnd >= badgeStart && sinceSpinEnd < resultHoldEnd)
        {
            String signedBonus = (bonus > 0 ? "+" : "") + bonus;
            String label = sinceSpinEnd < flipStart ? signedBonus : (signedBonus + " = " + total);
            Color labelColor = bonus > 0 ? DICE_ROLL_BONUS_POSITIVE_COLOR : DICE_ROLL_BONUS_NEGATIVE_COLOR;
            g.setFont(FontManager.getRunescapeBoldFont().deriveFont(DICE_ROLL_BONUS_LABEL_SIZE));
            drawCenteredText(g, label, cx, cy + half + 30, labelColor, alpha);
        }
    }

    private String localRsn()
    {
        if (client.getLocalPlayer() == null) return null;
        String name = client.getLocalPlayer().getName();
        return name != null ? Text.toJagexName(name) : null;
    }

    private static Color withAlpha(Color c, float alpha)
    {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }
}
