package gay.runescape.runeparty;

import java.awt.Color;
import java.awt.Graphics2D;
import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.coords.WorldPoint;

/** Small rendering helpers shared across overlays/dialogs -- {@code withAlpha} and the
 * "black shadow offset one pixel, then the real color on top" idiom, previously duplicated at
 * several call sites.
 * <p>
 * {@code loadNpcModel}/{@code orientationFacing} were added for JadEncounter but are generic
 * enough for any NPC-model-based tile effect to reuse, which is why both are {@code public} while
 * the rest of this class stays package-private -- JadEncounter lives under models/, a different
 * package. */
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
     * frame. */
    public static Model loadNpcModel(Client client, int npcId)
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
    public static int orientationFacing(WorldPoint from, WorldPoint to)
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

    /** Clamped to [0, 255] before handing java.awt.Color a component value, in case a caller's
     * alpha arithmetic drifts fractionally outside [0f, 1f]. */
    static Color withAlpha(Color c, float alpha)
    {
        int a = Math.max(0, Math.min(255, (int) (alpha * 255)));
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), a);
    }

    /** Draws {@code text} once in black at (x+1, y+1), then again in {@code color} at (x, y) --
     * the shadow-then-draw idiom shared by PlayerOverlay's coin/Golden-Gnome popups and
     * TileOverlay's return-arrow label. Not a fit for every "draw text with a shadow" site in this
     * codebase -- CoinRushTimerOverlay and AnnouncementOverlay each have real behavioral
     * differences (a different offset, a dimmed shadow, layout chaining), so they stay separate. */
    static void drawShadowed(Graphics2D g, String text, int x, int y, Color color, int alpha)
    {
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.drawString(text, x + 1, y + 1);
        g.setColor(withAlpha(color, alpha));
        g.drawString(text, x, y);
    }
}
