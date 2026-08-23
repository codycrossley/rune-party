package gay.runescape.runeparty;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.outline.ModelOutlineRenderer;
import net.runelite.client.util.Text;

/** Outlines every seated PLAYER's model in their assigned RunePartyColor (derived from their turn
 * order, see RunePartyColor#forNumber) -- same ModelOutlineRenderer approach as Gnomeball's
 * PlayerOverlay, minus the field-boundary gating since Rune Party colors are a roster-wide
 * identity, not a "currently on the field" state -- plus a color-coded token hovering above their
 * head, with a pulsing glow in that same seat color (same breathing-alpha technique as Gnomeball's
 * TimerOverlay#renderPauseGlow) while it's their turn to roll, and a floating coin popup after a
 * standard/penalty tile reward. The retro dice-roll reveal lives in AnnouncementOverlay instead --
 * screen-centered so everyone can see it, not anchored to the roller's head. Spectators are left
 * unaltered. */
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

    private static final Color COIN_POPUP_GAIN_COLOR = new Color(80, 220, 80);
    private static final Color COIN_POPUP_LOSS_COLOR = new Color(230, 70, 70);
    private static final int COIN_POPUP_CLEARANCE = 22; // gap above the token
    private static final int COIN_POPUP_MAX_RISE = 16; // pixels risen by the time the popup fades out

    private static final Color GOLDEN_GNOME_POPUP_COLOR = new Color(255, 215, 0);
    // Taller than the coin popup's own clearance so the two can stack without overlapping when
    // both fire close together -- a Golden Gnome purchase's own popup, then moments later the
    // underlying tile's own coin popup (see RunePartyPlugin's GOLDEN_GNOME_PURCHASED/COINS_CHANGED
    // handling -- confirm_arrival always resolves the tile's effect right after the offer settles).
    private static final int GOLDEN_GNOME_POPUP_CLEARANCE = 46;
    private static final int GOLDEN_GNOME_POPUP_MAX_RISE = 16;

    private final Client client;
    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;
    private final RosterReducer roster;
    private final ModelOutlineRenderer modelOutlineRenderer;

    public PlayerOverlay(Client client, RunePartyConfig config, RunePartyPlugin plugin, RosterReducer roster, ModelOutlineRenderer modelOutlineRenderer)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.roster = roster;
        this.modelOutlineRenderer = modelOutlineRenderer;

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
            modelOutlineRenderer.drawOutline(p, OUTLINE_WIDTH,
                new Color(c.getRed(), c.getGreen(), c.getBlue(), OUTLINE_ALPHA), OUTLINE_FEATHER);

            // A mini-game isn't anyone's "turn" -- everyone submits independently -- so the glow/
            // thick border is suppressed then too, matching StatsOverlay's same carve-out.
            boolean onTurn = phase == GamePhase.ACTIVE && !plugin.isMinigameActive()
                && rsn.equalsIgnoreCase(plugin.getCurrentTurnRsn());
            drawToken(g, p, c, onTurn);

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

    private void drawToken(Graphics2D g, Player p, Color color, boolean onTurn)
    {
        int yOffset = p.getLogicalHeight() + TOKEN_RADIUS + TOKEN_HEAD_CLEARANCE;
        Point loc = p.getCanvasTextLocation(g, "", yOffset);
        if (loc == null) return;

        int cx = loc.getX();
        int cy = loc.getY();

        g.setColor(color);
        g.fillOval(cx - TOKEN_RADIUS, cy - TOKEN_RADIUS, TOKEN_RADIUS * 2, TOKEN_RADIUS * 2);

        if (onTurn)
        {
            int glowAlpha = (int) (100 + 155 * BannerAnim.pulse(System.currentTimeMillis(), GLOW_PULSE_PERIOD_MS));
            int glowRadius = TOKEN_RADIUS + GLOW_RADIUS_PAD;

            g.setStroke(TOKEN_GLOW_STROKE);
            g.setColor(withAlpha(color, glowAlpha));
            g.drawOval(cx - glowRadius, cy - glowRadius, glowRadius * 2, glowRadius * 2);

            g.setStroke(TOKEN_BORDER_ON_TURN);
            g.setColor(COLOR_TURN_BORDER);
            g.drawOval(cx - TOKEN_RADIUS, cy - TOKEN_RADIUS, TOKEN_RADIUS * 2, TOKEN_RADIUS * 2);
        }
        else
        {
            g.setStroke(TOKEN_BORDER);
            g.setColor(Color.BLACK);
            g.drawOval(cx - TOKEN_RADIUS, cy - TOKEN_RADIUS, TOKEN_RADIUS * 2, TOKEN_RADIUS * 2);
        }
    }

    /** Floats "+3" (or "-3" for a penalty tile, or a Coin Trap steal's -20/+20 on the victim/owner
     * respectively) above a player's head, then swaps to their new running total for the rest of
     * the popup's life -- same client-side start/until-timestamp pattern as AnnouncementOverlay's
     * turn banner (see RunePartyPlugin's enqueueCoinPopup, which stamps each CoinPopup's own
     * start/until). {@code popup.start} can be stamped into the future -- see the "now < start"
     * guard below -- when a Golden Gnome popup, or an earlier coin popup, for the same player is
     * still showing, so this one waits its turn instead of overlapping it; nothing renders at all
     * until that future start time actually arrives. A totalless popup (see CoinPopup's own doc --
     * Coin Rush's mid-round "+2" flash, the only current one) never advances past the delta phase:
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
        g.setColor(withAlpha(Color.BLACK, a));
        g.drawString(text, loc.getX() + 1, loc.getY() + 1);
        g.setColor(withAlpha(color, a));
        g.drawString(text, loc.getX(), loc.getY());
    }

    /** Floats "+1 Golden Gnome" above a player's head after a purchase, then swaps to their new
     * running total ("N Golden Gnomes", or "1 Golden Gnome" singular) for the rest of the popup's
     * life -- same shape and timing as drawCoinPopup (reuses RunePartyPlugin's
     * COIN_POPUP_DELTA_PHASE_MS/FADE_MS directly rather than duplicating them, per how the feature
     * was asked for: "similar to how the coins popup works"), just always the gain color since a
     * purchase is never a loss here -- the coin cost has its own separate feedback (the outcome
     * banner), not this popup. Sits higher above the head than the coin popup (see
     * GOLDEN_GNOME_POPUP_CLEARANCE) so the two never overlap when both are showing at once. */
    private void drawGoldenGnomePopup(Graphics2D g, Player p)
    {
        long now = System.currentTimeMillis();
        Float alpha = BannerAnim.fadeAlpha(plugin.getGoldenGnomePopupUntil(), RunePartyPlugin.COIN_POPUP_FADE_MS);
        if (alpha == null) return;

        long elapsed = now - plugin.getGoldenGnomePopupStart();
        boolean showTotal = elapsed >= RunePartyPlugin.COIN_POPUP_DELTA_PHASE_MS;
        int total = plugin.getGoldenGnomePopupNewTotal();
        String text = showTotal ? (total + " Golden " + (total == 1 ? "Gnome" : "Gnomes")) : "+1 Golden Gnome";

        int rise = (int) Math.min(GOLDEN_GNOME_POPUP_MAX_RISE, elapsed / 50);

        g.setFont(FontManager.getRunescapeBoldFont());
        int yOffset = p.getLogicalHeight() + TOKEN_RADIUS + TOKEN_HEAD_CLEARANCE + GOLDEN_GNOME_POPUP_CLEARANCE + rise;
        Point loc = p.getCanvasTextLocation(g, text, yOffset);
        if (loc == null) return;

        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        g.setColor(withAlpha(Color.BLACK, a));
        g.drawString(text, loc.getX() + 1, loc.getY() + 1);
        g.setColor(withAlpha(GOLDEN_GNOME_POPUP_COLOR, a));
        g.drawString(text, loc.getX(), loc.getY());
    }

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
