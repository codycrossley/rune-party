package gay.runescape.runeparty;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.Text;

/** Big, brief, screen-centered instructional banners -- the first of which is "<player>'s Turn" on
 * every TURN_STARTED, in that player's own RunePartyColor (or "Your Turn!" for the local player).
 * Same client-side fade-on-a-timer pattern as Gnomeball's TimerOverlay#renderGoalFlash: the plugin
 * stamps a until-timestamp when the triggering event lands (see RunePartyPlugin's
 * turnAnnounceRsn/turnAnnounceUntil), this overlay just counts down and fades against it every
 * frame. Meant to grow with more instructional banners as the game does, not just this one. */
public class AnnouncementOverlay extends Overlay
{
    private static final long FADE_DURATION_MS = 500; // tail end of the banner's life spent fading out

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
        if (phase != GamePhase.ACTIVE) return null;

        renderTurnAnnouncement(g);

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
        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(text);
        int x = (client.getCanvasWidth() - textWidth) / 2;
        int y = client.getCanvasHeight() / 4;

        g.setColor(withAlpha(Color.BLACK, alpha * 0.7f));
        g.drawString(text, x + 2, y + 2);
        g.setColor(withAlpha(color, alpha));
        g.drawString(text, x, y);
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
