package gay.runescape.runeparty;

import java.awt.Color;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;

/** Small rendering helpers every overlay/dialog previously kept its own copy of (see
 * ARCHITECTURE_REVIEW.md's C3) -- {@code withAlpha} existed 5 times, and the "black shadow offset
 * one pixel, then the real color on top" idiom 3 of those same times. Two copies of a 3-line color
 * helper would be a fine judgment call (see RunePartyMapDialog's own small independent copies of
 * this kind of thing elsewhere); five was worth collapsing.
 * <p>
 * {@code loadNpcModel}/{@code orientationFacing} were added for JadOverlay (the Jad Tile's
 * spawned-model effect) but don't belong to Jad specifically -- both are generic enough for any
 * future NPC-model-based tile effect to reuse without re-deriving them. */
final class RunePartyRender
{
    private RunePartyRender()
    {
    }

    /** Loads and merges an NPC's own model parts into one final renderable {@link Model}, for
     * spawning as a {@link net.runelite.api.RuneLiteObject} rather than a real NPC -- same
     * technique Gnomeball's {@code CheerleaderRenderer#resolveModels}/{@code buildHueShiftedModel}
     * already prove out for NPC 3158, minus the hue-shift step (not every caller needs a recolor).
     * Returns {@code null} while any part isn't cached yet -- {@code Client#loadModelData} can
     * return null for a couple of frames right after the client starts, same as every other
     * lazy-model-load site in this codebase (see TileOverlay's own docs on this) -- callers should
     * keep calling this every frame until it succeeds, then cache the result themselves rather
     * than re-merging every frame. */
    static Model loadNpcModel(Client client, int npcId)
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

        ModelData merged = parts.length == 1 ? parts[0] : client.mergeModels(parts);
        return merged.light();
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

    /** Clamped to [0, 255] before handing java.awt.Color a component value -- one of the five
     * previous copies of this (ConfettiOverlay's) didn't clamp, which would have thrown if a
     * caller's own alpha arithmetic ever drifted fractionally outside [0f, 1f]. */
    static Color withAlpha(Color c, float alpha)
    {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Draws {@code text} once in black at (x+1, y+1), then again in {@code color} at (x, y) --
     * the shadow-then-draw idiom PlayerOverlay's coin/Golden-Gnome popups and TileOverlay's
     * return-arrow label all independently repeated, each already scaling both draws to the same
     * {@code alpha}. Not a fit for every "draw text with a shadow" site in this codebase --
     * CoinRushTimerOverlay's own drawShadowedText is a permanently-opaque HUD label with a
     * different (+2, +2) offset, and AnnouncementOverlay's drawLeftAlignedText dims its shadow to
     * 0.7x the main alpha and returns the x position past the text for layout chaining -- both
     * real behavioral differences, not just duplicate names for this same thing, so both stay
     * separate rather than being forced through this one signature. */
    static void drawShadowed(Graphics2D g, String text, int x, int y, Color color, int alpha)
    {
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.drawString(text, x + 1, y + 1);
        g.setColor(withAlpha(color, alpha));
        g.drawString(text, x, y);
    }
}
