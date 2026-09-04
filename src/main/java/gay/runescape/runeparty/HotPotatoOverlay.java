package gay.runescape.runeparty;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Locale;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/** Hot Potato's status overview: one row of every seated player's own token -- the exact same
 * colored-circle icon PlayerOverlay floats above their head in the world (see PlayerOverlay#
 * drawToken), just drawn flat here instead of anchored to a 3D model -- with the potato icon
 * floating above whichever one currently holds it. Deliberately minimalist: no names under the
 * tokens, the color alone (matching each player's own seat color everywhere else in this codebase)
 * is enough to tell them apart at a glance. A countdown mirrors CoinRushTimerOverlay's own urgency
 * coloring, and a "SPIN to pass it!" hint appears only for the local player while they're the one
 * holding it (see RunePartyPlugin#isLocalPlayerHoldingHotPotato) -- everyone else just watches the
 * row for who to keep an eye on. The holder's own in-world token also flashes -- see PlayerOverlay#
 * drawToken -- so this overview is never the only place that's visible. */
public class HotPotatoOverlay extends Overlay
{
    private static final int TOKEN_RADIUS = 12;
    private static final int TOKEN_GAP = 22;
    private static final int POTATO_SIZE = 26;
    private static final int POTATO_CLEARANCE = 6; // gap between the potato's own bottom edge and the token's top edge
    private static final int PADDING_X = 20;
    private static final int PADDING_Y = 12;
    private static final int TITLE_ROW_GAP = 6;
    private static final int HINT_ROW_GAP = 8;
    private static final int CORNER_RADIUS = 14;

    private static final Color BACKGROUND = new Color(20, 14, 8, 200);
    private static final Color BORDER = new Color(230, 170, 60, 220);
    private static final Color TITLE_COLOR = new Color(255, 225, 180);
    private static final Color HINT_COLOR = new Color(255, 215, 0);
    private static final Color URGENT_COLOR = new Color(235, 60, 60);
    private static final long URGENT_THRESHOLD_MS = 5000;

    private static final float TITLE_SIZE = 13f;
    private static final float TIMER_SIZE = 20f;
    private static final float HINT_SIZE = 14f;

    private static final String TITLE = "HOT POTATO";
    private static final String HINT = "SPIN to pass it!";

    private final RunePartyPlugin plugin;
    private final BufferedImage potatoIcon;
    private final BufferedImage skullIcon;

    public HotPotatoOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        this.potatoIcon = ImageUtil.loadImageResource(getClass(), "minigame_resources/potato.png");
        this.skullIcon = ImageUtil.loadImageResource(getClass(), "minigame_resources/skull.png");

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isHotPotatoActive() || !plugin.isMinigamePlayable()) return null;

        List<RosterReducer.RosterEntry> seated = plugin.getRosterReducer().seatedPlayers();
        if (seated.isEmpty()) return null;
        seated.sort((a, b) ->
        {
            int na = a.number.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(a.number);
            int nb = b.number.isEmpty() ? Integer.MAX_VALUE : Integer.parseInt(b.number);
            return Integer.compare(na, nb);
        });

        String holder = plugin.getHotPotatoHolder();
        boolean localHolding = plugin.isLocalPlayerHoldingHotPotato();

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font timerFont = RunePartyFonts.MARIO_PARTY.deriveFont(TIMER_SIZE);
        Font hintFont = FontManager.getRunescapeBoldFont().deriveFont(HINT_SIZE);

        long remainingMs = plugin.getHotPotatoEndsAt() - System.currentTimeMillis();
        long remainingSec = Math.max(0, (remainingMs + 999) / 1000);
        String timerText = String.valueOf(remainingSec);
        Color timerColor = remainingMs <= URGENT_THRESHOLD_MS ? URGENT_COLOR : HINT_COLOR;

        int rowWidth = (seated.size() - 1) * (TOKEN_RADIUS * 2 + TOKEN_GAP) + TOKEN_RADIUS * 2;

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(TITLE);

        g.setFont(timerFont);
        FontMetrics timerFm = g.getFontMetrics();

        g.setFont(hintFont);
        FontMetrics hintFm = g.getFontMetrics();
        int hintWidth = localHolding ? hintFm.stringWidth(HINT) : 0;

        int contentWidth = Math.max(Math.max(titleWidth, rowWidth), hintWidth);
        int width = PADDING_X * 2 + contentWidth;

        int tokensRowHeight = POTATO_SIZE + POTATO_CLEARANCE + TOKEN_RADIUS * 2;
        int height = PADDING_Y * 2 + titleFm.getHeight() + TITLE_ROW_GAP + timerFm.getHeight() + TITLE_ROW_GAP + tokensRowHeight;
        if (localHolding) height += HINT_ROW_GAP + hintFm.getHeight();

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(titleFont);
        g.setColor(TITLE_COLOR);
        int titleY = PADDING_Y + titleFm.getAscent();
        g.drawString(TITLE, (width - titleWidth) / 2, titleY);

        g.setFont(timerFont);
        int timerY = titleY + titleFm.getDescent() + TITLE_ROW_GAP + timerFm.getAscent();
        drawShadowedText(g, timerText, (width - timerFm.stringWidth(timerText)) / 2, timerY, timerColor);

        int tokensTop = timerY + timerFm.getDescent() + TITLE_ROW_GAP;
        int tokenCenterY = tokensTop + POTATO_SIZE + POTATO_CLEARANCE + TOKEN_RADIUS;
        int x = (width - ((seated.size() - 1) * (TOKEN_RADIUS * 2 + TOKEN_GAP) + TOKEN_RADIUS * 2)) / 2 + TOKEN_RADIUS;

        for (RosterReducer.RosterEntry entry : seated)
        {
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color tokenColor = seatColor != null ? seatColor.awt : Color.GRAY;

            g.setColor(tokenColor);
            g.fillOval(x - TOKEN_RADIUS, tokenCenterY - TOKEN_RADIUS, TOKEN_RADIUS * 2, TOKEN_RADIUS * 2);
            g.setStroke(new BasicStroke(1.5f));
            g.setColor(Color.BLACK);
            g.drawOval(x - TOKEN_RADIUS, tokenCenterY - TOKEN_RADIUS, TOKEN_RADIUS * 2, TOKEN_RADIUS * 2);

            // Mutually exclusive -- an eliminated player can never hold the potato again (see
            // app.py's hot_potato_pass/hot_potato.py's own hold-timeout reassignment, both of
            // which exclude the eliminated set), so at most one of these two ever draws.
            if (potatoIcon != null && holder != null && holder.equalsIgnoreCase(entry.rsn))
            {
                g.drawImage(potatoIcon, x - POTATO_SIZE / 2, tokenCenterY - TOKEN_RADIUS - POTATO_CLEARANCE - POTATO_SIZE, POTATO_SIZE, POTATO_SIZE, null);
            }
            else if (skullIcon != null && plugin.getHotPotatoEliminatedRsns().contains(entry.rsn.toLowerCase(Locale.ROOT)))
            {
                g.drawImage(skullIcon, x - POTATO_SIZE / 2, tokenCenterY - TOKEN_RADIUS - POTATO_CLEARANCE - POTATO_SIZE, POTATO_SIZE, POTATO_SIZE, null);
            }

            x += TOKEN_RADIUS * 2 + TOKEN_GAP;
        }

        if (localHolding)
        {
            g.setFont(hintFont);
            int hintY = tokensTop + tokensRowHeight + HINT_ROW_GAP + hintFm.getAscent();
            drawShadowedText(g, HINT, (width - hintWidth) / 2, hintY, HINT_COLOR);
        }

        return new Dimension(width, height);
    }

    private static void drawShadowedText(Graphics2D g, String text, int x, int y, Color color)
    {
        g.setColor(Color.BLACK);
        g.drawString(text, x + 2, y + 2);
        g.setColor(color);
        g.drawString(text, x, y);
    }
}
