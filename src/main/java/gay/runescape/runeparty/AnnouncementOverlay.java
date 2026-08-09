package gay.runescape.runeparty;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontFormatException;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.io.IOException;
import java.io.InputStream;
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

    private static final long GAME_START_FADE_MS = 600;
    private static final float GAME_START_TITLE_SIZE = 58f;

    private static final Color SPIN_HINT_COLOR = new Color(255, 255, 255);
    private static final float SPIN_HINT_SIZE = 22f;
    private static final long SPIN_HINT_PULSE_PERIOD_MS = 1400; // gentle breathing alpha so a hint that has to persist (no fixed duration) doesn't just sit there static and easy to ignore
    private static final float SPIN_HINT_MIN_ALPHA = 0.55f;

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
        renderMinigameBanner(g);
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

    /** Draws the "MINIGAME!" banner on a MINIGAME_STARTED event -- server-driven, so every client
     * shows it at the same moment, same Mario-Party-logo rainbow-letter treatment as "RUNE PARTY"
     * (see drawCenteredRainbowText and RAINBOW_LETTER_COLORS), plus the mini-game's own
     * instructions underneath if the server sent any. This is also the fix for TileOverlay's target
     * arrow otherwise reappearing on the last roller of a round if they step off their landed tile
     * during the mini-game -- see TileOverlay#renderTargetArrow, which now suppresses itself
     * whenever isMinigameActive() is true instead of only reacting to this banner's presence. */
    private void renderMinigameBanner(Graphics2D g)
    {
        long remaining = plugin.getMinigameBannerUntil() - System.currentTimeMillis();
        if (remaining <= 0) return;

        float alpha = remaining < MINIGAME_FADE_MS ? remaining / (float) MINIGAME_FADE_MS : 1f;
        int centerX = client.getCanvasWidth() / 2;
        int y = client.getCanvasHeight() / 3;

        g.setFont(MARIO_PARTY_FONT.deriveFont(MINIGAME_TITLE_SIZE));
        drawCenteredRainbowText(g, "MINIGAME!", RAINBOW_LETTER_COLORS, centerX, y, alpha);

        String instructions = plugin.getMinigameInstructions();
        if (instructions != null)
        {
            g.setFont(FontManager.getRunescapeSmallFont());
            drawCenteredText(g, instructions, centerX, y + 28, Color.LIGHT_GRAY, alpha);
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
