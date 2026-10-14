package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RunePartyPlugin;

import net.runelite.client.input.MouseManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** Shows the local player's own live Crab Rave dance count -- a small titled panel, same
 * shape/positioning as DanceDanceRuneScapeHudOverlay/FishingCatchOverlay/SandwichRushHudOverlay.
 * Deliberately self-only, not a shared leaderboard: dance counts are entirely client-local until
 * the one final submission, so no client ever learns another player's running tally mid-round.
 * Stands in for a persistent on-screen reminder to dance -- the mini-game's own instructions
 * banner already covers that once, at MINIGAME_STARTED (see AnnouncementOverlay#
 * renderCrabRaveCountdown's own doc on why that isn't repeated here too). */
public class CrabRaveHudOverlay extends Overlay
{
    private static final int PADDING_X = 16;
    private static final int PADDING_Y = 10;
    private static final int TITLE_ROW_GAP = 4;
    private static final int CORNER_RADIUS = 12;

    private static final Color BACKGROUND = new Color(0, 0, 0, 103);
    // Hot pink, matching CrabRaveTile/TileOverlay's own CRAB_RAVE_ARENA_OUTLINE_COLOR theme.
    private static final Color BORDER = new Color(233, 30, 140, 255);
    private static final Color TITLE_COLOR = new Color(233, 30, 140, 255);
    private static final Color TEXT_COLOR = Color.WHITE;

    private static final float TITLE_SIZE = 20f;
    private static final float COUNT_SIZE = 32f;

    private static final String TITLE = "YOUR DANCES";

    // A separate small square button next to the dance-count panel, same rounded-rect/background/
    // border style as that panel (BACKGROUND/BORDER/CORNER_RADIUS above), holding a speaker glyph --
    // see drawMuteIcon/the ClickableOverlayHotspot field below. White when playable, a dull red
    // (plus a diagonal slash through the glyph) when muted -- see RunePartyPlugin#isCrabRaveMusicMuted.
    private static final int MUTE_ICON_SIZE = 18;
    private static final int MUTE_BOX_PADDING = 10;
    private static final int MUTE_BOX_SIZE = MUTE_ICON_SIZE + MUTE_BOX_PADDING * 2;
    private static final int BOX_GAP = 10; // space between the dance-count panel and the mute button
    private static final Color MUTE_ICON_COLOR = Color.WHITE;
    private static final Color MUTE_ICON_MUTED_COLOR = new Color(220, 90, 90);

    private final RunePartyPlugin plugin;
    private final ClickableOverlayHotspot muteHotspot;

    public CrabRaveHudOverlay(RunePartyPlugin plugin, MouseManager mouseManager)
    {
        this.plugin = plugin;
        this.muteHotspot = new ClickableOverlayHotspot(mouseManager);

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    public void register()
    {
        muteHotspot.register();
    }

    public void unregister()
    {
        muteHotspot.unregister();
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isCrabRaveActive() || !plugin.isMinigamePlayable())
        {
            muteHotspot.setBounds(null); // nothing drawn this frame -- the hotspot must not keep swallowing clicks on empty space
            return null;
        }

        // Handled before drawing (not after), same order ChatboxDialogueOverlay's own pendingClick
        // is run in -- so a toggled mute state's new icon shows the very same frame it changed,
        // rather than lagging one frame behind.
        if (muteHotspot.consumeClick())
        {
            plugin.toggleCrabRaveMusicMuted();
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font countFont = FontManager.getRunescapeBoldFont().deriveFont(COUNT_SIZE);

        String countText = String.valueOf(plugin.getCrabRaveDanceCount());

        g.setFont(countFont);
        FontMetrics countFm = g.getFontMetrics();
        int countWidth = countFm.stringWidth(countText);

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(TITLE);

        int danceBoxWidth = PADDING_X * 2 + Math.max(countWidth, titleWidth);
        int danceBoxHeight = PADDING_Y * 2 + titleFm.getHeight() + TITLE_ROW_GAP + countFm.getHeight();

        // Two independent boxes, same rounded-rect/background/border style, side by side -- not one
        // panel with the mute button embedded in it. Vertically centered against each other in case
        // one ends up taller (in practice the dance-count box, with its own much larger COUNT_SIZE
        // font, always is).
        int totalHeight = Math.max(danceBoxHeight, MUTE_BOX_SIZE);
        int danceBoxY = (totalHeight - danceBoxHeight) / 2;
        int muteBoxX = danceBoxWidth + BOX_GAP;
        int muteBoxY = (totalHeight - MUTE_BOX_SIZE) / 2;
        int width = muteBoxX + MUTE_BOX_SIZE;

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, danceBoxY, danceBoxWidth, danceBoxHeight, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(1, danceBoxY + 1, danceBoxWidth - 2, danceBoxHeight - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(titleFont);
        g.setColor(TITLE_COLOR);
        int titleY = danceBoxY + PADDING_Y + titleFm.getAscent();
        g.drawString(TITLE, (danceBoxWidth - titleWidth) / 2, titleY);

        g.setFont(countFont);
        int countY = titleY + titleFm.getDescent() + TITLE_ROW_GAP + countFm.getAscent();
        int countX = (danceBoxWidth - countWidth) / 2;
        g.setColor(Color.BLACK);
        g.drawString(countText, countX + 1, countY + 1);
        g.setColor(TEXT_COLOR);
        g.drawString(countText, countX, countY);

        g.setColor(BACKGROUND);
        g.fillRoundRect(muteBoxX, muteBoxY, MUTE_BOX_SIZE, MUTE_BOX_SIZE, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(muteBoxX + 1, muteBoxY + 1, MUTE_BOX_SIZE - 2, MUTE_BOX_SIZE - 2, CORNER_RADIUS, CORNER_RADIUS);

        boolean muted = plugin.isCrabRaveMusicMuted();
        int iconX = muteBoxX + MUTE_BOX_PADDING;
        int iconY = muteBoxY + MUTE_BOX_PADDING;
        drawMuteIcon(g, iconX, iconY, muted);

        // ClickableOverlayHotspot#setBounds needs absolute canvas coordinates, not this overlay's
        // own local drawing origin -- see that method's own doc. getBounds() is this overlay's own
        // on-screen rectangle as of last frame (TOP_CENTER is a managed position, not DYNAMIC, so
        // RuneLite's own OverlayRenderer -- not this class -- decides where that actually is). The
        // hotspot covers the whole mute button, not just the icon's own smaller glyph, for a more
        // forgiving click target.
        Point origin = getBounds().getLocation();
        muteHotspot.setBounds(new Rectangle(origin.x + muteBoxX, origin.y + muteBoxY, MUTE_BOX_SIZE, MUTE_BOX_SIZE));

        return new Dimension(width, totalHeight);
    }

    /** A plain vector speaker glyph -- a rectangular body plus a triangular cone, with either
     * sound-wave arcs (playing) or a diagonal slash through the whole glyph (muted). No image asset
     * needed, same "simple shapes via Graphics2D" treatment several other small icons in this
     * codebase already use. */
    private void drawMuteIcon(Graphics2D g, int x, int y, boolean muted)
    {
        g.setColor(muted ? MUTE_ICON_MUTED_COLOR : MUTE_ICON_COLOR);

        int bodyWidth = MUTE_ICON_SIZE / 3;
        int bodyHeight = MUTE_ICON_SIZE / 2;
        int bodyX = x;
        int bodyY = y + (MUTE_ICON_SIZE - bodyHeight) / 2;
        g.fillRect(bodyX, bodyY, bodyWidth, bodyHeight);

        Polygon cone = new Polygon();
        cone.addPoint(bodyX + bodyWidth, bodyY);
        cone.addPoint(bodyX + bodyWidth, bodyY + bodyHeight);
        cone.addPoint(x + MUTE_ICON_SIZE / 2, y + MUTE_ICON_SIZE);
        cone.addPoint(x + MUTE_ICON_SIZE / 2, y);
        g.fillPolygon(cone);

        g.setStroke(new BasicStroke(1.5f));
        if (muted)
        {
            g.drawLine(x, y, x + MUTE_ICON_SIZE, y + MUTE_ICON_SIZE);
        }
        else
        {
            int waveX = x + MUTE_ICON_SIZE / 2 + 2;
            g.drawArc(waveX, y, MUTE_ICON_SIZE / 3, MUTE_ICON_SIZE, -40, 80);
        }
    }
}
