package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyColor;
import gay.runescape.runeparty.RunePartyConfig;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.RunePartyRole;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.Text;

/** Outlines every seated PLAYER's model in their assigned RunePartyColor (derived from their turn
 * order) via ModelOutlineRenderer, plus a color-coded token hovering above their head, with a
 * pulsing glow in that same seat color while it's their turn to roll, and a floating coin popup
 * after a standard/penalty tile reward. The retro dice-roll reveal lives in AnnouncementOverlay
 * instead -- screen-centered so everyone can see it, not anchored to the roller's head. Spectators
 * are left unaltered.
 * <p>
 * While a Turf Wars round is active, both the outline and the token switch to that player's own
 * assigned team color instead of their usual seat color, for every seated player -- makes "who's
 * on which team" readable at a glance for the whole round, not just a banner shown once at the
 * start. Who's Your Jaddy? gets the same treatment, just live rather than assigned once -- a
 * player's own outline/token switches the instant their real WorldLocation lands on a JADDY_TILE
 * zone, and switches right back the instant they step off it, since picking a side there is just
 * standing on it (see RunePartyPlugin#getJaddyZoneColor). */
public class PlayerOverlay extends Overlay
{
    private static final int OUTLINE_WIDTH = 2;
    private static final int OUTLINE_FEATHER = 2;
    private static final int OUTLINE_ALPHA = 200;

    private static final Color COLOR_TURN_BORDER = new Color(255, 210, 0);
    private static final int TOKEN_RADIUS = 6;
    private static final int TOKEN_HEAD_CLEARANCE = 14; // gap between the head and the token's bottom edge
    private static final Stroke TOKEN_BORDER = new BasicStroke(1.5f);
    private static final Stroke TOKEN_BORDER_ON_TURN = new BasicStroke(3f);
    private static final Stroke TOKEN_GLOW_STROKE = new BasicStroke(3f);
    private static final long GLOW_PULSE_PERIOD_MS = 1200;
    private static final int GLOW_RADIUS_PAD = 4; // how far the glow ring sits outside the token's own border

    // Hot Potato's own holder-only token override -- see drawToken's own doc. A noticeably faster
    // pulse than the on-turn glow above (450ms vs. 1200ms) so it reads as urgent rather than a
    // calm breathing effect.
    private static final long HOT_POTATO_FLASH_PERIOD_MS = 450;
    private static final int HOT_POTATO_FLASH_RADIUS_BONUS = 6; // extra pixels of radius at the pulse's peak
    private static final Color HOT_POTATO_SAFE_COLOR = new Color(255, 215, 0);
    private static final Color HOT_POTATO_URGENT_COLOR = new Color(220, 30, 30);

    private static final Color COIN_POPUP_GAIN_COLOR = new Color(80, 220, 80);
    private static final Color COIN_POPUP_LOSS_COLOR = new Color(230, 70, 70);
    private static final int COIN_POPUP_CLEARANCE = 22; // gap above the token
    private static final int COIN_POPUP_MAX_RISE = 16; // pixels risen by the time the popup fades out

    private static final Color GOLDEN_GNOME_POPUP_COLOR = new Color(255, 215, 0);
    // Taller than the coin popup's own clearance so the two can stack without overlapping when
    // both fire close together -- a Golden Gnome purchase's own popup, then moments later the
    // underlying tile's own coin popup.
    private static final int GOLDEN_GNOME_POPUP_CLEARANCE = 46;
    private static final int GOLDEN_GNOME_POPUP_MAX_RISE = 16;

    // Hot Potato's own elimination marker -- a persistent skull hovering above an eliminated
    // player's token for the rest of the round (see drawHotPotatoEliminatedIcon), not a fading
    // popup like the coin/Golden Gnome ones above.
    private static final int HOT_POTATO_SKULL_ICON_SIZE = 18;
    private static final int HOT_POTATO_SKULL_ICON_CLEARANCE = 12; // gap above the token's own top edge

    private final Client client;
    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;
    private final RosterReducer roster;
    private final ModelOutlineRenderer modelOutlineRenderer;
    private final BufferedImage hotPotatoSkullIcon;

    public PlayerOverlay(Client client, RunePartyConfig config, RunePartyPlugin plugin, RosterReducer roster, ModelOutlineRenderer modelOutlineRenderer)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.roster = roster;
        this.modelOutlineRenderer = modelOutlineRenderer;
        this.hotPotatoSkullIcon = ImageUtil.loadImageResource(getClass(), "minigame_resources/skull.png");

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showOverlay()) return null;
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE) return null;

        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;

            String rsn = Text.toJagexName(p.getName());
            if (rsn == null || rsn.isBlank()) continue;
            if (roster.getRole(rsn) != RunePartyRole.PLAYER) continue;

            RunePartyColor seatColor = RunePartyColor.forNumber(roster.getColorNumber(rsn));
            if (seatColor == null) continue;

            Color c = seatColor.awt;
            if (plugin.isTurfWarsActive())
            {
                // Recolors every seated player's own outline/token to their Turf Wars assigned
                // color instead of their usual per-seat one, for the whole round. Falls back to
                // the seat color if this player's own assignment hasn't landed yet.
                Color teamColor = plugin.getPlayerTeamColor(rsn);
                if (teamColor != null) c = teamColor;
            }
            else if (plugin.isJaddyActive())
            {
                // Same idea as Turf Wars above, but there's no assignment event at all here --
                // whichever JADDY_TILE zone this player's own real WorldLocation currently sits on
                // (a live board lookup, see RunePartyPlugin#getJaddyZoneColor) IS their team, purely
                // for as long as they're standing there. Falls back to the seat color the instant
                // they step off both zones (or before they've picked one at all), so switching
                // sides mid-duel reads instantly rather than needing its own event.
                Color zoneColor = plugin.getJaddyZoneColor(p.getWorldLocation());
                if (zoneColor != null) c = zoneColor;
            }
            modelOutlineRenderer.drawOutline(p, OUTLINE_WIDTH,
                new Color(c.getRed(), c.getGreen(), c.getBlue(), OUTLINE_ALPHA), OUTLINE_FEATHER);

            // A mini-game isn't anyone's "turn" -- everyone submits independently -- so the glow/
            // thick border is suppressed then too, matching StatsOverlay's same carve-out.
            boolean onTurn = phase == GamePhase.ACTIVE && !plugin.isMinigameActive()
                && rsn.equalsIgnoreCase(plugin.getCurrentTurnRsn());
            // Mutually exclusive with onTurn by construction -- isHotPotatoActive() implies a
            // mini-game is active, which onTurn's own check above already rules out.
            boolean holdingHotPotato = phase == GamePhase.ACTIVE && plugin.isHotPotatoActive() && plugin.isMinigamePlayable()
                && rsn.equalsIgnoreCase(plugin.getHotPotatoHolder());
            drawToken(g, p, c, onTurn, holdingHotPotato);

            // Mutually exclusive with holdingHotPotato by construction -- once eliminated, a
            // player can never hold the potato again (see app.py's hot_potato_pass/hot_potato.py's
            // own random-explosion reassignment, both of which exclude the eliminated set).
            if (phase == GamePhase.ACTIVE && plugin.isHotPotatoActive()
                && plugin.getHotPotatoEliminatedRsns().contains(rsn.toLowerCase(Locale.ROOT)))
            {
                drawHotPotatoEliminatedIcon(g, p);
            }

            RunePartyPlugin.CoinPopup coinPopup = phase == GamePhase.ACTIVE ? plugin.getCoinPopup(rsn) : null;
            if (coinPopup != null)
            {
                drawCoinPopup(g, p, coinPopup);
            }

            if (phase == GamePhase.ACTIVE && rsn.equalsIgnoreCase(plugin.getGoldenGnomePopupRsn()))
            {
                drawGoldenGnomePopup(g, p);
            }
        }

        return null;
    }

    /** holdingHotPotato overrides the token's own fill color and radius while true: a fast pulse
     * between HOT_POTATO_URGENT_COLOR and HOT_POTATO_SAFE_COLOR (blended by how much of the whole
     * round's own clock is left, see plugin.getHotPotatoEndsAt), growing and shrinking in size the
     * same way -- there's no per-holder deadline to track toward (the potato explodes on a random
     * schedule server-side, see hot_potato.py's own doc), so this is purely a lively "you have it,
     * watch out" cue, not a countdown. Makes the one player everyone needs to watch read as an
     * unmistakable "hot" beacon from across the arena. Mutually exclusive with onTurn by
     * construction (see render()'s own doc on that), so there's no priority to decide between the
     * two beyond a plain if/else. */
    private void drawToken(Graphics2D g, Player p, Color color, boolean onTurn, boolean holdingHotPotato)
    {
        int radius = TOKEN_RADIUS;
        Color fillColor = color;
        if (holdingHotPotato)
        {
            long endsAt = plugin.getHotPotatoEndsAt();
            long remainingMs = endsAt != 0 ? Math.max(0, endsAt - System.currentTimeMillis()) : RunePartyPlugin.HOT_POTATO_DURATION_MS;
            float remainingFraction = Math.min(1f, remainingMs / (float) RunePartyPlugin.HOT_POTATO_DURATION_MS);
            fillColor = lerpColor(HOT_POTATO_URGENT_COLOR, HOT_POTATO_SAFE_COLOR, remainingFraction);

            float pulse = BannerAnim.pulse(System.currentTimeMillis(), HOT_POTATO_FLASH_PERIOD_MS);
            radius = TOKEN_RADIUS + Math.round(pulse * HOT_POTATO_FLASH_RADIUS_BONUS);
        }

        int yOffset = p.getLogicalHeight() + radius + TOKEN_HEAD_CLEARANCE;
        Point loc = p.getCanvasTextLocation(g, "", yOffset);
        if (loc == null) return;

        int cx = loc.getX();
        int cy = loc.getY();

        g.setColor(fillColor);
        g.fillOval(cx - radius, cy - radius, radius * 2, radius * 2);

        if (onTurn)
        {
            int glowAlpha = (int) (100 + 155 * BannerAnim.pulse(System.currentTimeMillis(), GLOW_PULSE_PERIOD_MS));
            int glowRadius = radius + GLOW_RADIUS_PAD;

            g.setStroke(TOKEN_GLOW_STROKE);
            g.setColor(RunePartyRender.withAlpha(fillColor, glowAlpha));
            g.drawOval(cx - glowRadius, cy - glowRadius, glowRadius * 2, glowRadius * 2);

            g.setStroke(TOKEN_BORDER_ON_TURN);
            g.setColor(COLOR_TURN_BORDER);
            g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }
        else
        {
            g.setStroke(TOKEN_BORDER);
            g.setColor(Color.BLACK);
            g.drawOval(cx - radius, cy - radius, radius * 2, radius * 2);
        }
    }

    private static Color lerpColor(Color a, Color b, float t)
    {
        t = Math.max(0f, Math.min(1f, t));
        int r = Math.round(a.getRed() + (b.getRed() - a.getRed()) * t);
        int gr = Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t);
        int bl = Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t);
        return new Color(r, gr, bl);
    }

    /** Floats "+3" (or "-3" for a penalty tile, or a Coin Trap steal's -20/+20 on the victim/owner
     * respectively) above a player's head, then swaps to their new running total for the rest of
     * the popup's life. {@code popup.start} can be stamped into the future -- see the "now < start"
     * guard below -- when a Golden Gnome popup, or an earlier coin popup, for the same player is
     * still showing, so this one waits its turn instead of overlapping it. A totalless popup (see
     * CoinPopup's own doc -- Coin Rush's mid-round "+2" flash) never advances past the delta phase:
     * there's no real total to show yet, the round only pays out in one lump sum at its end. */
    private void drawCoinPopup(Graphics2D g, Player p, RunePartyPlugin.CoinPopup popup)
    {
        long now = System.currentTimeMillis();
        if (now < popup.start) return; // RunePartyPlugin can push this into the future to wait out a still-showing popup for the same player
        Float alpha = BannerAnim.fadeAlpha(popup.until, RunePartyPlugin.COIN_POPUP_FADE_MS);
        if (alpha == null) return;

        long elapsed = now - popup.start;
        boolean showTotal = !popup.totalless && elapsed >= RunePartyPlugin.COIN_POPUP_DELTA_PHASE_MS;
        String text = showTotal ? (popup.newTotal + " coins") : ((popup.delta >= 0 ? "+" : "") + popup.delta + " coins");
        Color color = showTotal ? Color.WHITE : (popup.delta >= 0 ? COIN_POPUP_GAIN_COLOR : COIN_POPUP_LOSS_COLOR);

        int rise = (int) Math.min(COIN_POPUP_MAX_RISE, elapsed / 50);

        g.setFont(FontManager.getRunescapeBoldFont());
        int yOffset = p.getLogicalHeight() + TOKEN_RADIUS + TOKEN_HEAD_CLEARANCE + COIN_POPUP_CLEARANCE + rise;
        Point loc = p.getCanvasTextLocation(g, text, yOffset);
        if (loc == null) return;

        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        RunePartyRender.drawShadowed(g, text, loc.getX(), loc.getY(), color, a);
    }

    /** Floats "+1 Golden Gnome" above a player's head after a purchase (or "-1 Golden Gnome" after
     * a Jad smash penalty), then swaps to their new running total ("N Golden Gnomes", or "1 Golden
     * Gnome" singular) for the rest of the popup's life -- same shape and timing as drawCoinPopup.
     * Always the gain color regardless of sign, since a loss here doesn't get its own separate
     * feedback elsewhere. Sits higher above the head than the coin popup so the two never overlap
     * when both are showing at once. */
    private void drawGoldenGnomePopup(Graphics2D g, Player p)
    {
        long now = System.currentTimeMillis();
        Float alpha = BannerAnim.fadeAlpha(plugin.getGoldenGnomePopupUntil(), RunePartyPlugin.COIN_POPUP_FADE_MS);
        if (alpha == null) return;

        long elapsed = now - plugin.getGoldenGnomePopupStart();
        boolean showTotal = elapsed >= RunePartyPlugin.COIN_POPUP_DELTA_PHASE_MS;
        int total = plugin.getGoldenGnomePopupNewTotal();
        int delta = plugin.getGoldenGnomePopupDelta();
        String text = showTotal ? (total + " Golden " + (total == 1 ? "Gnome" : "Gnomes"))
            : ((delta >= 0 ? "+" : "") + delta + " Golden " + (Math.abs(delta) == 1 ? "Gnome" : "Gnomes"));

        int rise = (int) Math.min(GOLDEN_GNOME_POPUP_MAX_RISE, elapsed / 50);

        g.setFont(FontManager.getRunescapeBoldFont());
        int yOffset = p.getLogicalHeight() + TOKEN_RADIUS + TOKEN_HEAD_CLEARANCE + GOLDEN_GNOME_POPUP_CLEARANCE + rise;
        Point loc = p.getCanvasTextLocation(g, text, yOffset);
        if (loc == null) return;

        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        RunePartyRender.drawShadowed(g, text, loc.getX(), loc.getY(), GOLDEN_GNOME_POPUP_COLOR, a);
    }

    /** Hovers hotPotatoSkullIcon just above an eliminated player's own (normal-size, no-longer-
     * flashing) token, for as long as they stay eliminated -- unlike every popup above, this has no
     * fade/rise of its own, it's a plain persistent marker for the rest of the round. Anchored the
     * same "TOKEN_RADIUS + TOKEN_HEAD_CLEARANCE" clearance drawCoinPopup's own yOffset uses, plus
     * this icon's own small gap on top, then drawn upward from that point (image bottom at the
     * anchor) the same way text grows upward from its own baseline there. */
    private void drawHotPotatoEliminatedIcon(Graphics2D g, Player p)
    {
        if (hotPotatoSkullIcon == null) return;

        int yOffset = p.getLogicalHeight() + TOKEN_RADIUS + TOKEN_HEAD_CLEARANCE + HOT_POTATO_SKULL_ICON_CLEARANCE;
        Point loc = p.getCanvasTextLocation(g, "", yOffset);
        if (loc == null) return;

        g.drawImage(hotPotatoSkullIcon, loc.getX() - HOT_POTATO_SKULL_ICON_SIZE / 2, loc.getY() - HOT_POTATO_SKULL_ICON_SIZE,
            HOT_POTATO_SKULL_ICON_SIZE, HOT_POTATO_SKULL_ICON_SIZE, null);
    }
}
