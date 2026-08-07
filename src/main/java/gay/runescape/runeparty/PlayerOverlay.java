package gay.runescape.runeparty;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.Stroke;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Point;
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
 * TimerOverlay#renderPauseGlow) while it's their turn to roll. Spectators are left unaltered. */
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

            RunePartyColor seatColor = RunePartyColor.forNumber(roster.getNumber(rsn));
            if (seatColor == null) continue;

            Color c = seatColor.awt;
            modelOutlineRenderer.drawOutline(p, OUTLINE_WIDTH,
                new Color(c.getRed(), c.getGreen(), c.getBlue(), OUTLINE_ALPHA), OUTLINE_FEATHER);

            // A mini-game isn't anyone's "turn" -- everyone submits independently -- so the glow/
            // thick border is suppressed then too, matching StatsOverlay's same carve-out.
            boolean onTurn = phase == GamePhase.ACTIVE && !plugin.isMinigameActive()
                && rsn.equalsIgnoreCase(plugin.getCurrentTurnRsn());
            drawToken(g, p, c, onTurn);
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
            long phaseMs = System.currentTimeMillis() % GLOW_PULSE_PERIOD_MS;
            float pulse = (float) (0.5 + 0.5 * Math.sin(2 * Math.PI * phaseMs / GLOW_PULSE_PERIOD_MS));
            int glowAlpha = (int) (100 + 155 * pulse);
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

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }
}
