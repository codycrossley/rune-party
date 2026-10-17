package gay.runescape.runeparty.overlays;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;

/** Small rendering helpers shared across this package -- {@code withAlpha} and the "black shadow
 * offset one pixel, then the real color on top" idiom, previously duplicated at several call
 * sites (TileOverlay, PlayerOverlay, AnnouncementOverlay, ...). {@code withAlpha(Color, float)} is
 * also public so overlays.layout's Text node (a separate package, on purpose -- see that
 * package's own doc) can reuse it for its own shadow draw. */
public final class RunePartyRender
{
    private RunePartyRender()
    {
    }

    /** Loads and merges an NPC's own model parts into one final renderable {@link Model}, for
     * spawning as a {@link net.runelite.api.RuneLiteObject} rather than a real NPC. Returns
     * {@code null} while any part isn't cached yet -- {@code Client#loadModelData} can return null
     * for a couple of frames right after the client starts -- callers should keep calling this
     * every frame until it succeeds, then cache the result themselves rather than re-merging every
     * frame. Thin wrapper over {@link #loadNpcModelData}, for a caller that just wants the finished,
     * lit model with no recolor of its own (see that method's own doc for a caller -- e.g.
     * models/JaddyDuelModel -- that needs the raw, pre-lit data instead). */
    static Model loadNpcModel(Client client, int npcId)
    {
        ModelData merged = loadNpcModelData(client, npcId);
        return merged != null ? merged.light() : null;
    }

    /** Same merge/retry contract as {@link #loadNpcModel}, but from {@link
     * NPCComposition#getChatheadModels()} instead of {@link NPCComposition#getModels()} -- the
     * real, separate model resource the game's own dialogue interface uses for this NPC's
     * portrait (already framed/posed for a close-up, unlike the full overworld model), for
     * ChatheadRenderer to project into a 2D portrait. Returns null (not just for "not cached yet,"
     * but permanently) for an NPC with no chathead models at all, same as an absent
     * NPCComposition -- callers should treat both identically. */
    static Model loadNpcChatheadModel(Client client, int npcId)
    {
        NPCComposition comp = client.getNpcDefinition(npcId);
        if (comp == null) return null;

        int[] modelIds = comp.getChatheadModels();
        if (modelIds == null || modelIds.length == 0) return null;

        ModelData[] parts = new ModelData[modelIds.length];
        for (int i = 0; i < modelIds.length; i++)
        {
            ModelData part = client.loadModelData(modelIds[i]);
            if (part == null) return null; // not cached yet -- caller retries next frame
            parts[i] = part;
        }

        ModelData merged = parts.length == 1 ? parts[0] : client.mergeModels(parts);
        return merged.light();
    }

    /** Same merge as {@link #loadNpcChatheadModel}, but returns the raw, pre-lit {@link ModelData}
     * instead of a finished {@link Model} -- for a caller whose in-world model is recolored by
     * hand (see ItemShopNpcOverlay's own RECOLOR_FIND/RECOLOR_REPLACE doc) and needs its chathead
     * portrait recolored to match before calling {@code ModelData#light()} itself -- otherwise the
     * dialogue box's portrait shows the NPC's stock colors while the model standing on the tile
     * shows the recolored ones. Same "null while not cached yet, keep calling every frame"
     * contract as loadNpcChatheadModel. */
    static ModelData loadNpcChatheadModelData(Client client, int npcId)
    {
        NPCComposition comp = client.getNpcDefinition(npcId);
        if (comp == null) return null;

        int[] modelIds = comp.getChatheadModels();
        if (modelIds == null || modelIds.length == 0) return null;

        ModelData[] parts = new ModelData[modelIds.length];
        for (int i = 0; i < modelIds.length; i++)
        {
            ModelData part = client.loadModelData(modelIds[i]);
            if (part == null) return null; // not cached yet -- caller retries next frame
            parts[i] = part;
        }

        return parts.length == 1 ? parts[0] : client.mergeModels(parts);
    }

    /** Same merge as {@link #loadNpcModel}, but returns the raw, pre-lit {@link ModelData} instead
     * of a finished {@link Model} -- for a caller that needs to recolor every face first (see
     * models/CoinTrapModel#buildGoldModel's own doc on why recoloring needs the raw data, not a
     * lit Model) before calling {@code ModelData#light()} itself. Same "null while not cached yet,
     * keep calling every frame" contract as loadNpcModel. */
    static ModelData loadNpcModelData(Client client, int npcId)
    {
        NPCComposition comp = client.getNpcDefinition(npcId);
        if (comp == null) return null;

        int[] modelIds = comp.getModels();
        if (modelIds == null || modelIds.length == 0) return null;

        ModelData[] parts = new ModelData[modelIds.length];
        for (int i = 0; i < modelIds.length; i++)
        {
            ModelData part = client.loadModelData(modelIds[i]);
            if (part == null) return null; // not cached yet -- caller retries next frame
            parts[i] = part;
        }

        return parts.length == 1 ? parts[0] : client.mergeModels(parts);
    }

    /** The Jagex Angle Unit (0-2047 per revolution) a {@code RuneLiteObject} standing at {@code
     * from} needs to face {@code to} -- see {@link net.runelite.api.coords.Angle}'s own javadoc for
     * the reference mapping this is derived from (0 = South, 512 = West, 1024 = North, 1536 =
     * East). Plane is ignored -- facing is a purely horizontal rotation. */
    static int orientationFacing(WorldPoint from, WorldPoint to)
    {
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        int jau = (int) Math.round(Math.atan2(-dx, -dy) / (2 * Math.PI) * 2048.0);
        return Math.floorMod(jau, 2048);
    }

    static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    /** The width any "center this box on the game view" calculation should actually measure
     * against -- the real 3D game viewport, not the full RuneLite canvas (client.getCanvasWidth()).
     * In OSRS's Fixed layout (and some Resizable ones), the viewport is a sub-rectangle of the
     * canvas: the inventory/minimap/chat widgets occupy the rest of it. Centering a panel on the
     * raw canvas puts it off-center toward those widgets -- originally found (and fixed) in
     * AnnouncementOverlay, whose own banners used to drift the same way; RunePartyMapOverlay's own
     * board panel had the identical bug (left/top padding looked right since the viewport sits
     * flush against the canvas's own top-left corner, but the panel crept flush against the
     * bottom-right, where the widgets eat into the canvas instead).
     * <p>
     * {@code client.getViewportXOffset()} is normally just a handful of px -- doubling it here
     * builds a "virtual canvas" exactly the size the real viewport would need to be centered
     * within, so {@code origin = (drawableWidth(client) - boxWidth) / 2} lands the box exactly on
     * the real viewport's own center (equivalently: {@code xOffset + (viewportWidth - boxWidth) /
     * 2}, i.e. the viewport's own left edge plus however far the box should be inset to center
     * within just the viewport's own width) -- dramatically simpler than threading an explicit
     * origin offset through every centering call site individually. */
    static int drawableWidth(Client client)
    {
        return client.getViewportWidth() + 2 * client.getViewportXOffset();
    }

    /** Vertical counterpart to drawableWidth(Client) -- see its own doc. */
    static int drawableHeight(Client client)
    {
        return client.getViewportHeight() + 2 * client.getViewportYOffset();
    }

    /** Clamped to [0, 255] before handing java.awt.Color a component value, in case a caller's
     * alpha arithmetic drifts fractionally outside [0f, 1f]. */
    public static Color withAlpha(Color c, float alpha)
    {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** A small titled stat panel -- rounded-rect background+border, a centered plain title line,
     * then a centered, drop-shadowed stat line below it. CrabRaveHudOverlay/
     * DanceDanceRuneScapeHudOverlay/CoinRushScoreboardOverlay/RuneMatchOverlay/ClickClickClickOverlay
     * all used to hand-roll this exact shape independently (color/font-size/padding/text differences
     * only) -- this is the one place that math/drawing now lives. Returns the panel's own computed
     * Dimension, the same value every one of those render() methods already returned directly, so a
     * caller's own render() can just be {@code return RunePartyRender.drawTitledStatPanel(...);}.
     * Standardizes two pre-existing, purely incidental inconsistencies between those five files'
     * own hand-rolled versions (confirmed as unintentional drift, not a deliberate design choice):
     * the stat line's drop-shadow is always the 1px offset {@link #drawShadowed} already uses (two
     * of the five previously used a 2px offset instead), and the title is never shadowed (one of the
     * five previously shadowed its own title too) -- both changes are visually negligible. Caller is
     * responsible for its own AA rendering hints (set once per frame, not worth re-setting here) and
     * for returning null itself on its own "don't show this frame" gate before ever calling this. */
    static Dimension drawTitledStatPanel(Graphics2D g, int paddingX, int paddingY, int titleGap, int cornerRadius,
        Color background, Color border, Font titleFont, String title, Color titleColor,
        Font statFont, String statText, Color statColor)
    {
        g.setFont(statFont);
        FontMetrics statFm = g.getFontMetrics();
        int statWidth = statFm.stringWidth(statText);

        g.setFont(titleFont);
        FontMetrics titleFm = g.getFontMetrics();
        int titleWidth = titleFm.stringWidth(title);

        int width = paddingX * 2 + Math.max(statWidth, titleWidth);
        int height = paddingY * 2 + titleFm.getHeight() + titleGap + statFm.getHeight();

        g.setColor(background);
        g.fillRoundRect(0, 0, width, height, cornerRadius, cornerRadius);
        g.setStroke(new BasicStroke(2f));
        g.setColor(border);
        g.drawRoundRect(1, 1, width - 2, height - 2, cornerRadius, cornerRadius);

        g.setFont(titleFont);
        g.setColor(titleColor);
        int titleY = paddingY + titleFm.getAscent();
        g.drawString(title, (width - titleWidth) / 2, titleY);

        g.setFont(statFont);
        int statY = titleY + titleFm.getDescent() + titleGap + statFm.getAscent();
        drawShadowed(g, statText, (width - statWidth) / 2, statY, statColor, 255);

        return new Dimension(width, height);
    }

    /** Draws {@code text} once in black at (x+1, y+1), then again in {@code color} at (x, y) --
     * the shadow-then-draw idiom shared by PlayerOverlay's coin/Golden-Gnome popups,
     * TileOverlay's return-arrow label, and drawTitledStatPanel's own stat line above. Not a fit
     * for every "draw text with a shadow" site in this codebase -- AnnouncementOverlay has real
     * behavioral differences (a dimmed shadow, layout chaining), so it stays separate. */
    static void drawShadowed(Graphics2D g, String text, int x, int y, Color color, int alpha)
    {
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.drawString(text, x + 1, y + 1);
        g.setColor(withAlpha(color, alpha));
        g.drawString(text, x, y);
    }
}
