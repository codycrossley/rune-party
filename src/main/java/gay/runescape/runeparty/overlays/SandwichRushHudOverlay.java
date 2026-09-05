package gay.runescape.runeparty.overlays;

import gay.runescape.runeparty.RosterReducer;
import gay.runescape.runeparty.RunePartyPlugin;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.util.ImageUtil;

/** Shows the local player's own Sandwich Rush progress -- a titled panel with one icon per
 * ingredient (dimmed until held, full brightness plus a gold border once collected) plus a running
 * completed-sandwich count, while a Sandwich Rush round is active AND actually playable -- same
 * reveal-spoiler fix FishingCatchOverlay avoids. Also gated on the local viewer actually being a
 * seated PLAYER -- an observer has no held ingredients or sandwich count of their own to show.
 * <p>
 * Deliberately self-only, same as FishingCatchOverlay's catch tally -- the shared final tally
 * happens for free via the existing end-of-round rewards recap, same as every other minigame's
 * payout. */
public class SandwichRushHudOverlay extends Overlay
{
    private static final int ICON_SIZE = 30;
    private static final int ICON_GAP = 8;
    private static final int SEPARATOR_GAP = 16;
    private static final int PADDING_X = 16;
    private static final int PADDING_Y = 10;
    private static final int TITLE_ROW_GAP = 4;
    private static final int CORNER_RADIUS = 12;
    private static final int NOT_HELD_ALPHA = 85; // out of 255 -- dim but still identifiable, not invisible

    private static final Color BACKGROUND = new Color(28, 20, 8, 195);
    private static final Color BORDER = new Color(230, 170, 60, 220);
    private static final Color HELD_BORDER = new Color(255, 215, 0);
    private static final Color TITLE_COLOR = new Color(255, 225, 180);
    private static final Color TEXT_COLOR = Color.WHITE;

    private static final float TITLE_SIZE = 13f;
    private static final float COUNT_SIZE = 22f;

    private static final String TITLE = "SANDWICH RUSH";
    // Same fixed order RunePartyPlugin#SANDWICH_RUSH_ITEM_ICON_RESOURCES already iterates in.
    private static final String[] INGREDIENT_ORDER = {"tomato", "cheese", "cabbage", "bread"};

    private final RunePartyPlugin plugin;
    private final Map<String, BufferedImage> ingredientIcons = new LinkedHashMap<>();
    private final BufferedImage sandwichIcon;

    public SandwichRushHudOverlay(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
        for (String item : INGREDIENT_ORDER)
        {
            ingredientIcons.put(item, ImageUtil.loadImageResource(getClass(), RunePartyPlugin.SANDWICH_RUSH_ITEM_ICON_RESOURCES.get(item)));
        }
        this.sandwichIcon = ImageUtil.loadImageResource(getClass(), "minigame_resources/triangle_sandwich.png");

        setPosition(OverlayPosition.TOP_CENTER);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!plugin.isSandwichRushActive() || !plugin.isMinigamePlayable()) return null;
        if (!isLocalPlayerSeated()) return null;

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        Font titleFont = FontManager.getRunescapeSmallFont().deriveFont(TITLE_SIZE);
        Font countFont = FontManager.getRunescapeBoldFont().deriveFont(COUNT_SIZE);

        Set<String> held = plugin.getSandwichHeld();
        String countText = String.valueOf(plugin.getSandwichCount());

        g.setFont(countFont);
        FontMetrics countFm = g.getFontMetrics();
        int ingredientsWidth = INGREDIENT_ORDER.length * ICON_SIZE + (INGREDIENT_ORDER.length - 1) * ICON_GAP;
        int countWidth = ICON_SIZE + 6 + countFm.stringWidth(countText);
        int rowWidth = ingredientsWidth + SEPARATOR_GAP + countWidth;
        int rowHeight = Math.max(ICON_SIZE, countFm.getHeight());

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(TITLE);

        int width = PADDING_X * 2 + Math.max(rowWidth, titleWidth);
        int height = PADDING_Y * 2 + titleFm.getHeight() + TITLE_ROW_GAP + rowHeight;

        g.setColor(BACKGROUND);
        g.fillRoundRect(0, 0, width, height, CORNER_RADIUS, CORNER_RADIUS);
        g.setStroke(new BasicStroke(2f));
        g.setColor(BORDER);
        g.drawRoundRect(1, 1, width - 2, height - 2, CORNER_RADIUS, CORNER_RADIUS);

        g.setFont(titleFont);
        g.setColor(TITLE_COLOR);
        int titleY = PADDING_Y + titleFm.getAscent();
        g.drawString(TITLE, (width - titleWidth) / 2, titleY);

        int rowTop = titleY + titleFm.getDescent() + TITLE_ROW_GAP;
        int x = (width - rowWidth) / 2;
        int iconY = rowTop + (rowHeight - ICON_SIZE) / 2;

        for (String item : INGREDIENT_ORDER)
        {
            drawIngredientIcon(g, ingredientIcons.get(item), x, iconY, held.contains(item));
            x += ICON_SIZE + ICON_GAP;
        }

        x += SEPARATOR_GAP - ICON_GAP;
        if (sandwichIcon != null)
        {
            g.drawImage(sandwichIcon, x, iconY, ICON_SIZE, ICON_SIZE, null);
        }
        int textX = x + ICON_SIZE + 6;
        int textY = rowTop + rowHeight / 2 + countFm.getAscent() / 2 - 2;
        g.setFont(countFont);
        g.setColor(Color.BLACK);
        g.drawString(countText, textX + 1, textY + 1);
        g.setColor(TEXT_COLOR);
        g.drawString(countText, textX, textY);

        return new Dimension(width, height);
    }

    /** held: full brightness plus a small gold border. Not held: same icon dimmed to
     * NOT_HELD_ALPHA via AlphaComposite, no border -- still identifiable, just visually secondary.
     * icon may be null (a missing/failed-to-load resource) -- skips drawing it rather than
     * throwing. */
    private void drawIngredientIcon(Graphics2D g, BufferedImage icon, int x, int y, boolean held)
    {
        if (icon == null) return;

        if (!held)
        {
            Composite oldComposite = g.getComposite();
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, NOT_HELD_ALPHA / 255f));
            g.drawImage(icon, x, y, ICON_SIZE, ICON_SIZE, null);
            g.setComposite(oldComposite);
            return;
        }

        g.drawImage(icon, x, y, ICON_SIZE, ICON_SIZE, null);
        g.setStroke(new BasicStroke(2f));
        g.setColor(HELD_BORDER);
        g.drawRoundRect(x - 2, y - 2, ICON_SIZE + 4, ICON_SIZE + 4, 6, 6);
    }

    /** A spectator has no held ingredients/sandwich count of their own to show, so this checks
     * seatedPlayers() membership directly rather than letting an empty/zero panel render for
     * them. */
    private boolean isLocalPlayerSeated()
    {
        String self = plugin.getLocalRsn();
        if (self == null) return false;
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().seatedPlayers())
        {
            if (entry.rsn.equalsIgnoreCase(self)) return true;
        }
        return false;
    }
}
