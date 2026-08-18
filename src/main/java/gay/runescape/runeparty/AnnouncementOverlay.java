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
 * local-only and duration-less, it's just a live read of plugin state that shows/hides itself
 * instantly rather than fading on a clock. Meant to grow with more instructional banners as the
 * game does, not just these. */
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

    // Mini-game selection spinner -- see renderMinigameSpinner. The wheel's segments reuse
    // RAINBOW_LETTER_COLORS (same colors as "SPIN"/"MINIGAME!"), one per registered mini-game
    // (see gay.runescape.runeparty.minigames.Minigames#all).
    private static final long MINIGAME_SPINNER_FADE_MS = 400;
    private static final int MINIGAME_SPINNER_MAX_SEGMENTS = 4; // see selectWheelEntries -- caps how many options the wheel ever shows, regardless of how many mini-games end up registered
    private static final float MINIGAME_SPINNER_RADIUS = 90f;
    private static final float MINIGAME_SPINNER_ICON_SIZE = 34f;
    private static final int MINIGAME_SPINNER_EXTRA_SPINS = 3; // full rotations before settling, purely visual
    private static final long MINIGAME_SPINNER_SETTLE_POP_MS = 220; // same overshoot-then-settle feel as the dice roll's DIE_SETTLE_POP_MS
    private static final float MINIGAME_SPINNER_NAME_SIZE = 30f; // the mini-game's name, revealed once the wheel settles
    private static final Color MINIGAME_SPINNER_POINTER_COLOR = new Color(255, 215, 0);

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
        if (phase != GamePhase.ACTIVE && phase != GamePhase.LOBBY) return null;

        renderWelcomeBanner(g);
        renderGameStartBanner(g);
        renderTurnAnnouncement(g);
        renderSpinHint(g);
        renderGoldenGnomeOffer(g);
        renderGoldenGnomeOutcome(g);
        renderMinigameBanner(g);
        renderMinigameSpinner(g);
        renderMinigameReadyCheck(g);
        renderMinigameCountdown(g);
        renderMinigameRewardsBanner(g);
        renderRoundCompleteBanner(g);
        renderDiceRoll(g);

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

    /** Reminds the local player to "Use the SPIN! emote to roll the dice." -- local-only, unlike
     * every other banner here: it's not tied to a server event or a fixed duration, it's a
     * continuous read of RunePartyPlugin#isLocalPlayerReadyToRoll() (the same check
     * onAnimationChanged gates the real roll on), so it's visible for exactly as long as spinning
     * would actually do something and disappears the instant that stops being true -- whether
     * because they rolled, a mini-game started, or their turn simply ended. Deliberately never
     * shown while they still need to walk back to their tile first (isLocalPlayerReadyToRoll
     * already requires that), so this and TileOverlay's "Return Here!" arrow are always mutually
     * exclusive rather than both nagging at once. Sits just under the turn banner's own slot and
     * gently pulses since, unlike that banner, it has no fade-out to naturally draw the eye. "SPIN!"
     * itself gets the same Mario-Party-logo rainbow-letter treatment as "RUNE PARTY"/"MINIGAME!"
     * (see drawLeftAlignedRainbowText and RAINBOW_LETTER_COLORS -- "SPIN!" is only 5 characters, so
     * it just uses the first 5 entries of that 9-entry sequence), stitched into the plain-white rest
     * of the sentence via drawLeftAlignedText -- both return the x just past what they drew, so the
     * three segments chain into one still-centered line despite switching font and color partway
     * through. */
    private void renderSpinHint(Graphics2D g)
    {
        if (!plugin.isLocalPlayerReadyToRoll()) return;

        long phaseMs = System.currentTimeMillis() % SPIN_HINT_PULSE_PERIOD_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / SPIN_HINT_PULSE_PERIOD_MS));
        float alpha = SPIN_HINT_MIN_ALPHA + (1f - SPIN_HINT_MIN_ALPHA) * pulse;

        String prefix = "Use the ";
        String spinWord = "SPIN";
        String suffix = " emote to roll the dice.";

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

    /** Draws the Golden Gnome offer -- "You found a GOLDEN GNOME!" (with "GOLDEN GNOME" in the
     * Mario Party Hudson font, same gold as "SHOWDOWN", stitched into the surrounding plain-white
     * text via drawLeftAlignedText the same way renderSpinHint stitches "SPIN"), "Would you like to
     * buy one?" underneath, then the two emote instructions -- "YES" and "NO" get the same
     * Mario-Party-logo rainbow-letter treatment as "SPIN" (see drawLeftAlignedRainbowText and
     * RAINBOW_LETTER_COLORS), stitched into the surrounding plain text the same way. Broadcast to everyone (like
     * renderMinigameBanner/renderGameStartBanner), not local-only, since it's a shared moment
     * everyone's watching even though only the finder's own YES/NO emote does anything (see
     * RunePartyPlugin#isLocalPlayerAwaitingGoldenGnomeResponse, which onAnimationChanged and this
     * method's own instructions both key off of implicitly -- the offer text itself doesn't change
     * per viewer, same as the dice-roll reveal). Duration-less like renderSpinHint -- it's a live
     * read of goldenGnomeOfferRsn, not a timed fade, so it pulses instead for the same "needs to
     * stay noticeable without a fade to draw the eye" reason. */
    private void renderGoldenGnomeOffer(Graphics2D g)
    {
        if (plugin.getGoldenGnomeOfferRsn() == null) return;

        long phaseMs = System.currentTimeMillis() % GOLDEN_GNOME_OFFER_PULSE_PERIOD_MS;
        float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / GOLDEN_GNOME_OFFER_PULSE_PERIOD_MS));
        float alpha = GOLDEN_GNOME_OFFER_MIN_ALPHA + (1f - GOLDEN_GNOME_OFFER_MIN_ALPHA) * pulse;

        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        String prefix = "You found a ";
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
        drawCenteredText(g, "Would you like to buy one?", centerX, y + 32, Color.WHITE, alpha);

        Font emoteFont = FontManager.getRunescapeBoldFont().deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        Font emoteWordFont = MARIO_PARTY_FONT.deriveFont(GOLDEN_GNOME_OFFER_EMOTE_SIZE);
        drawEmoteInstruction(g, "YES", " emote: purchase", emoteFont, emoteWordFont, centerX, y + 66, alpha);
        drawEmoteInstruction(g, "NO", " emote: decline", emoteFont, emoteWordFont, centerX, y + 96, alpha);
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
     * own announcement that waits for this one via extendTurnEffectGate instead. */
    private void renderGoldenGnomeOutcome(Graphics2D g)
    {
        long remaining = plugin.getGoldenGnomeOutcomeBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        String outcome = plugin.getGoldenGnomeOutcome();
        String text = "purchased".equals(outcome) ? "You got a Golden Gnome!"
            : "cant_afford".equals(outcome) ? "You can't afford this!"
            : null;
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
     * a real spinner once more mini-games exist. */
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
        List<Minigame> wheelEntries = selectWheelEntries(all, selected);
        int targetIndex = Math.max(0, wheelEntries.indexOf(selected));
        int n = wheelEntries.size();
        float segmentDeg = 360f / n;
        float targetCenterAngle = targetIndex * segmentDeg + segmentDeg / 2f;

        long elapsed = now - plugin.getMinigameSpinnerStart();
        boolean spinning = elapsed < RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS;
        float totalRotationDeg = (360f - targetCenterAngle) + MINIGAME_SPINNER_EXTRA_SPINS * 360f;

        float rotationDeg;
        float scale = 1f;
        if (spinning)
        {
            float t = elapsed / (float) RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS;
            float eased = 1f - (float) Math.pow(1f - t, 3); // ease-out cubic, slows into the landing
            rotationDeg = eased * totalRotationDeg;
        }
        else
        {
            rotationDeg = totalRotationDeg;
            long sinceSettle = elapsed - RunePartyPlugin.MINIGAME_SPINNER_SPIN_PHASE_MS;
            if (sinceSettle < MINIGAME_SPINNER_SETTLE_POP_MS)
            {
                float t = sinceSettle / (float) MINIGAME_SPINNER_SETTLE_POP_MS;
                scale = 1.25f - 0.25f * t;
            }
        }

        float alpha = remaining < MINIGAME_SPINNER_FADE_MS ? remaining / (float) MINIGAME_SPINNER_FADE_MS : 1f;

        int cx = client.getCanvasWidth() / 2;
        int cy = client.getCanvasHeight() / 2;
        float radius = MINIGAME_SPINNER_RADIUS * scale;

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
            wheelEntries.get(i).drawIcon(wheel, Math.round(p.x), Math.round(p.y), Math.round(MINIGAME_SPINNER_ICON_SIZE), alpha);
        }
        wheel.dispose();

        // Fixed pointer above the wheel -- doesn't rotate, the wheel spins under it.
        int pointerTip = Math.round(cy - radius - 6);
        Polygon pointer = new Polygon();
        pointer.addPoint(cx - 10, pointerTip - 16);
        pointer.addPoint(cx + 10, pointerTip - 16);
        pointer.addPoint(cx, pointerTip);
        g.setColor(withAlpha(MINIGAME_SPINNER_POINTER_COLOR, alpha));
        g.fillPolygon(pointer);

        if (!spinning && selected != null)
        {
            g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_SPINNER_NAME_SIZE));
            drawCenteredRainbowText(g, selected.getDisplayName(), RAINBOW_LETTER_COLORS, cx, Math.round(cy + MINIGAME_SPINNER_RADIUS + 50), alpha);
        }
    }

    /** Picks which mini-games actually appear on the wheel -- every one if there are
     * MINIGAME_SPINNER_MAX_SEGMENTS or fewer registered, otherwise {@code selected} (it has to be
     * showable, since the wheel always lands on it) plus a sample of the rest, so the wheel never
     * grows unboundedly as more mini-games get added to the registry. Seeded off
     * minigameSpinnerStart -- constant for this spin's whole animation -- rather than
     * System.currentTimeMillis(), so the same sample holds steady across every frame instead of
     * reshuffling on each one. */
    private List<Minigame> selectWheelEntries(List<Minigame> all, Minigame selected)
    {
        if (all.size() <= MINIGAME_SPINNER_MAX_SEGMENTS) return all;

        List<Minigame> others = new ArrayList<>(all);
        others.remove(selected);
        Collections.shuffle(others, new Random(plugin.getMinigameSpinnerStart()));

        List<Minigame> entries = new ArrayList<>();
        entries.add(selected);
        entries.addAll(others.subList(0, MINIGAME_SPINNER_MAX_SEGMENTS - 1));
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
     * PLAYER ranked highest-coins-first (Golden Gnomes as the tiebreak -- Mario Party's own
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
            .comparingInt((RosterReducer.RosterEntry e) -> e.coins).reversed()
            .thenComparing(Comparator.comparingInt((RosterReducer.RosterEntry e) -> e.goldenGnomeCount).reversed()));

        Font nameFont = FontManager.getRunescapeBoldFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);
        Font statsFont = FontManager.getRunescapeSmallFont().deriveFont(ROUND_COMPLETE_LINE_SIZE);

        int lineY = y + 66;
        int rank = 1;
        for (RosterReducer.RosterEntry entry : players)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.number);
            Color nameColor = seatColor != null ? seatColor.awt : Color.LIGHT_GRAY;
            String stats = "   " + entry.coins + " coins, " + entry.goldenGnomeCount + " GN";
            drawStandingsLine(g, nameFont, statsFont, "#" + rank + "  ", entry.rsn, nameColor, stats, Color.LIGHT_GRAY, centerX, lineY, alpha);
            lineY += ROUND_COMPLETE_LINE_HEIGHT;
            rank++;
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
     * diceRollStart/diceRollUntil. */
    private void renderDiceRoll(Graphics2D g)
    {
        String rsn = plugin.getDiceRollRsn();
        if (rsn == null) return;

        long now = System.currentTimeMillis();
        long remaining = plugin.getDiceRollUntil() - now;
        if (remaining <= 0) return;

        long elapsed = now - plugin.getDiceRollStart();
        boolean spinning = elapsed < RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS;
        int shown = spinning ? 1 + (int) ((now / DIE_SPIN_FACE_MS) % 10) : plugin.getDiceRollValue();

        float scale = 1f;
        if (!spinning)
        {
            long sinceSettle = elapsed - RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS;
            if (sinceSettle < DIE_SETTLE_POP_MS)
            {
                float t = sinceSettle / (float) DIE_SETTLE_POP_MS;
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
