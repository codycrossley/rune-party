package gay.runescape.runeparty.models;

import gay.runescape.runeparty.GamePhase;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.RunePartyRender;
import gay.runescape.runeparty.TileReducer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Animation;
import net.runelite.api.Client;
import net.runelite.api.JagexColor;
import net.runelite.api.Model;
import net.runelite.api.ModelData;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import lombok.extern.slf4j.Slf4j;

/** Spawns two real 3D TzTok-Jad models (NPC {@link #JAD_NPC_ID}) for the "Who's Your Jaddy?"
 * mini-game, each recolored into one of Turf Wars' own two team colors (see RunePartyPlugin#
 * TEAM_A_COLOR/TEAM_B_COLOR) and standing at the center of its own JADDY_TILE zone, facing each
 * other. Idles on JAD_IDLE_ANIMATION_ID until the server's own JADDY_ATTACK_TRIGGERED fires an
 * attack animation on whichever side is attacking that beat and a health bar/damage popup on
 * whichever side is defending, then JADDY_DUEL_RESOLVED plays JAD_DEATH_ANIMATION_ID on whichever
 * side lost before both despawn.
 * <p>
 * Zone geometry (where each Jad stands, which color it's recolored to) is read fresh from {@link
 * TileReducer} every frame rather than driven by its own dedicated spawn event -- the two zones'
 * colors are already TEAM_A_COLOR/TEAM_B_COLOR the instant the board swaps in (see
 * minigames/whos_your_jaddy.py), so there's nothing this class needs a server event to learn that
 * it can't already read straight off the board. That stops the instant a duel resolves (see {@link
 * #resolved}) so a server-side board restore mid-death-animation can't revive the loser -- both
 * slots keep animating (and keep showing their own last-known health bar reading) at their
 * last-known position until the scheduled despawn, and a short {@link #suppressRespawnUntil} window
 * after that despawn guards against the rare case where the board restore is somehow slower than
 * the client's own death-hold timer.
 * <p>
 * Recolor technique mirrors CoinTrapModel#buildGoldModel -- every distinct raw face color mapped to
 * the target color's own hue/saturation, preserving each face's own luminance -- just parameterized
 * by target color instead of a single hardcoded gold, and built from the merged multi-part NPC
 * model ({@link RunePartyRender#loadNpcModelData}) rather than a single-part tile decoration's raw
 * model.
 * <p>
 * Health/damage state (Slot#hp/maxHp/hitsplat*) is never folded into the server's own durable round
 * state -- it lives only as each Slot's own plain fields, rebuilt fresh from whatever
 * JADDY_ATTACK_TRIGGERED/JADDY_DUEL_RESOLVED calls actually land on this instance. Both of those are
 * applied by RunePartyPlugin's own handleEvent regardless of catch-up (see that switch's own doc on
 * JADDY_ATTACK_TRIGGERED for the frozen-forever bug this fixes) -- a client that reconnects mid-duel
 * gets a rapid, possibly-flickery replay of whatever it missed rather than a full resync, but ends
 * up at the correct final reading either way, which is what matters for a purely cosmetic feature
 * like this one. */
@Slf4j
public class JaddyDuelModel extends Overlay
{
    private static final int JAD_NPC_ID = 3127; // TzTok-Jad, same NPC JadEncounter spawns undyed
    private static final int DEFAULT_RADIUS = 320; // see JadEncounter's own doc for this fallback

    // How long, after a resolved duel's own despawn, to ignore the board even if JADDY_TILE
    // entries are somehow still (or again) present -- see this class's own doc. Comfortably longer
    // than a normal event round-trip; only matters on an unusually slow one.
    private static final long DUEL_RESPAWN_SUPPRESS_MS = 3000;

    // Assumed starting reading for a freshly (re)spawned slot, before any real JADDY_ATTACK_TRIGGERED
    // has landed -- matches the server's own HP_MAX (minigames/whos_your_jaddy.py) so a fresh duel's
    // bars read as genuinely full rather than empty/unknown for that first idle stretch.
    private static final int DEFAULT_HP = 100;

    // How long a damage popup stays up above its target before fading -- first estimate, not
    // measured, same caveat every other purely-visual duration constant in this codebase carries.
    private static final long HITSPLAT_DURATION_MS = 900;

    // Both in the same 3D "height above the tile" unit LocalPoint/Perspective use for a z-offset,
    // not screen pixels -- first estimates for TzTok-Jad's own unusually tall model, not measured.
    private static final int HEALTH_BAR_HEIGHT_OFFSET = 500;
    private static final int HITSPLAT_EXTRA_SCREEN_PX = 16; // stacked just above the bar itself, in real screen pixels

    private static final int HEALTH_BAR_WIDTH_PX = 44;
    private static final int HEALTH_BAR_HEIGHT_PX = 6;
    private static final Color HEALTH_BAR_BACKGROUND = new Color(20, 20, 20, 215);
    private static final Color HEALTH_BAR_BORDER = new Color(0, 0, 0, 215);
    private static final Color HEALTH_BAR_GREEN = new Color(40, 200, 60);
    private static final Color HEALTH_BAR_YELLOW = new Color(230, 200, 40);
    private static final Color HEALTH_BAR_RED = new Color(210, 40, 40);
    private static final Color HITSPLAT_COLOR = new Color(230, 30, 30);

    private final Client client;
    private final ClientThread clientThread;
    private final RunePartyPlugin plugin;
    private final TileReducer tileReducer;

    // One "slot" per side -- built/spawned/animated identically, just recolored to a different
    // target and always facing the other slot's own current center.
    private final Slot slotA = new Slot();
    private final Slot slotB = new Slot();

    // Set by resolve() (JADDY_DUEL_RESOLVED) -- which slot is the loser; null until resolved.
    private volatile Slot pendingLoser = null;
    private volatile boolean deathApplied = false;

    // True from the instant resolve() lands until the scheduled despawn actually runs -- freezes
    // zone-geometry reads/respawn logic entirely (see this class's own doc).
    private volatile boolean resolved = false;
    private volatile long suppressRespawnUntil = 0;

    public JaddyDuelModel(Client client, ClientThread clientThread, RunePartyPlugin plugin, TileReducer tileReducer)
    {
        this.client = client;
        this.clientThread = clientThread;
        this.plugin = plugin;
        this.tileReducer = tileReducer;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE)
        {
            clear();
            return null;
        }

        if (resolved)
        {
            // Frozen at whatever slotA/slotB.center/facing were the instant resolve() landed --
            // never re-derived from the board while this is true, see this class's own doc.
            renderSlotSafely(g, slotA);
            renderSlotSafely(g, slotB);
            return null;
        }

        if (System.currentTimeMillis() < suppressRespawnUntil)
        {
            clear();
            return null;
        }

        if (!updateZoneGeometry())
        {
            clear(); // no JADDY_TILE zones currently on the board -- no duel in progress
            return null;
        }

        renderSlotSafely(g, slotA);
        renderSlotSafely(g, slotB);

        return null;
    }

    /** Wraps renderSlot in a try/catch so a rendering problem in one slot (a bad canvas projection,
     * say) can never abort this whole render() call and, with it, silently skip the OTHER slot too
     * -- exactly what happened when an earlier bug threw partway through slotA's own health bar
     * draw, which also meant slotB (whichever Jad renders second) never got a chance to spawn that
     * frame, or any frame after, since the same bug re-threw every time. Neither slot depends on
     * the other's own state, so isolating them here is safe. */
    private void renderSlotSafely(Graphics2D g, Slot slot)
    {
        try
        {
            renderSlot(g, slot);
        }
        catch (Exception e)
        {
            log.warn("JaddyDuelModel failed to render a slot", e);
        }
    }

    /** Reads the two JADDY_TILE zones' own current colors/centers straight off TileReducer's live
     * snapshot into slotA (TEAM_A_COLOR)/slotB (TEAM_B_COLOR) -- see this class's own doc for why
     * there's no dedicated spawn-point event. Returns false (leaving both slots untouched) if fewer
     * than 2 distinct zone colors are currently on the board, e.g. mid board-swap. */
    private boolean updateZoneGeometry()
    {
        List<TileReducer.TileEntry> entries = tileReducer.snapshot();

        Integer aMinX = null, aMaxX = null, aMinY = null, aMaxY = null;
        Integer bMinX = null, bMaxX = null, bMinY = null, bMaxY = null;
        int plane = 0;
        boolean sawA = false, sawB = false;
        int jaddyTileCount = 0; // diagnostic only -- see logZoneDiagnosticIfNeeded

        for (TileReducer.TileEntry entry : entries)
        {
            if (!"JADDY_TILE".equals(entry.tileType)) continue;
            jaddyTileCount++;
            if (entry.color == null) continue;
            WorldPoint p = entry.point;
            plane = p.getPlane();

            if (isColor(entry.color, RunePartyPlugin.TEAM_A_COLOR))
            {
                sawA = true;
                aMinX = aMinX == null ? p.getX() : Math.min(aMinX, p.getX());
                aMaxX = aMaxX == null ? p.getX() : Math.max(aMaxX, p.getX());
                aMinY = aMinY == null ? p.getY() : Math.min(aMinY, p.getY());
                aMaxY = aMaxY == null ? p.getY() : Math.max(aMaxY, p.getY());
            }
            else if (isColor(entry.color, RunePartyPlugin.TEAM_B_COLOR))
            {
                sawB = true;
                bMinX = bMinX == null ? p.getX() : Math.min(bMinX, p.getX());
                bMaxX = bMaxX == null ? p.getX() : Math.max(bMaxX, p.getX());
                bMinY = bMinY == null ? p.getY() : Math.min(bMinY, p.getY());
                bMaxY = bMaxY == null ? p.getY() : Math.max(bMaxY, p.getY());
            }
        }

        if (!sawA || !sawB)
        {
            logZoneDiagnosticIfNeeded(entries, jaddyTileCount);
            return false;
        }

        WorldPoint centerA = new WorldPoint((aMinX + aMaxX) / 2, (aMinY + aMaxY) / 2, plane);
        WorldPoint centerB = new WorldPoint((bMinX + bMaxX) / 2, (bMinY + bMaxY) / 2, plane);

        slotA.targetColor = RunePartyPlugin.TEAM_A_COLOR;
        slotB.targetColor = RunePartyPlugin.TEAM_B_COLOR;
        slotA.center = centerA;
        slotA.facing = centerB;
        slotB.center = centerB;
        slotB.facing = centerA;
        return true;
    }

    // Throttles logZoneDiagnosticIfNeeded to roughly once every 2 seconds -- render() calls
    // updateZoneGeometry() every frame, and without this a genuinely stuck "Jaddy active, zones
    // never resolve" state would spam the log at ~60Hz instead of giving one readable line at a
    // steady cadence.
    private volatile long lastZoneDiagnosticLogAt = 0;

    /** Fires (throttled) exactly when this class needs it most: the server's own key says Who's
     * Your Jaddy? is the active minigame, yet this frame's TileReducer snapshot doesn't contain two
     * distinct JADDY_TILE colors -- the exact, otherwise-completely-silent state behind every
     * "Jads never rendered" report investigated this session, none of which turned out to involve
     * an exception, a disconnect, or a server-side stall. Reports the raw facts needed to tell
     * apart the three real possibilities: zero JADDY_TILE entries at all (TileReducer never folded
     * the board swap), entries present but with a null color (a fold bug), or entries present with
     * colors that don't match TEAM_A_COLOR/TEAM_B_COLOR (a color/format mismatch) -- plus every
     * distinct plane seen, since a plane mismatch is the other standing suspicion. */
    private void logZoneDiagnosticIfNeeded(List<TileReducer.TileEntry> entries, int jaddyTileCount)
    {
        if (!plugin.isJaddyActive()) return;
        long now = System.currentTimeMillis();
        if (now - lastZoneDiagnosticLogAt < 2000) return;
        lastZoneDiagnosticLogAt = now;

        int nullColorCount = 0;
        Set<String> distinctColors = new HashSet<>();
        Set<Integer> distinctPlanes = new HashSet<>();
        for (TileReducer.TileEntry entry : entries)
        {
            if (!"JADDY_TILE".equals(entry.tileType)) continue;
            if (entry.color == null) nullColorCount++;
            else distinctColors.add(entry.color);
            distinctPlanes.add(entry.point.getPlane());
        }

        log.warn("JaddyDuelModel: Jaddy is active but zones haven't resolved -- jaddyTileEntries={} "
                + "nullColor={} distinctColors={} distinctPlanes={} expectedTeamA={} expectedTeamB={}",
            jaddyTileCount, nullColorCount, distinctColors, distinctPlanes,
            String.format("#%06X", RunePartyPlugin.TEAM_A_COLOR.getRGB() & 0xFFFFFF),
            String.format("#%06X", RunePartyPlugin.TEAM_B_COLOR.getRGB() & 0xFFFFFF));
    }

    private static boolean isColor(String hex, Color target)
    {
        try { return Color.decode(hex).getRGB() == target.getRGB(); }
        catch (NumberFormatException e) { return false; }
    }

    private void renderSlot(Graphics2D g, Slot slot)
    {
        if (slot.center == null) return; // resolved==true but geometry was never actually captured -- shouldn't happen, defensive

        if (slot.model == null && !slot.modelLoadFailed)
        {
            slot.model = buildRecoloredModel(slot);
            if (slot.model == null) return; // not cached yet -- keep retrying every frame
            // Diagnostic for the "Jads invisible, nothing else wrong" reports -- a degenerate
            // (near-zero vertex/face count) recolored model would explain total invisibility with
            // no exception anywhere, since every call downstream of this one (setModel,
            // setActive(true), the health bar's own independent 2D draw) would still "succeed" on a
            // model that just has nothing in it to actually draw.
            log.warn("JaddyDuelModel: built model for {} -- vertices={} faces={}",
                slot.targetColor, slot.model.getVerticesCount(), slot.model.getFaceCount());
        }
        if (slot.model == null) return; // load genuinely failed -- nothing to spawn

        if (slot.object == null)
        {
            slot.object = client.createRuneLiteObject();
            slot.object.setModel(slot.model);
            int radius = resolveRadius();
            slot.object.setRadius(radius);
            log.warn("JaddyDuelModel: spawned object for {} at {} facing {} radius={}",
                slot.targetColor, slot.center, slot.facing, radius);
        }

        slot.object.setOrientation(RunePartyRender.orientationFacing(slot.center, slot.facing));

        boolean isLoser = pendingLoser == slot;
        if (isLoser)
        {
            if (!deathApplied)
            {
                Animation anim = client.loadAnimation(RunePartyPlugin.JAD_DEATH_ANIMATION_ID);
                if (anim != null)
                {
                    slot.object.setShouldLoop(false);
                    slot.object.setAnimation(anim);
                    deathApplied = true;
                    plugin.scheduleDelayed(this::finishDuel, RunePartyPlugin.JADDY_DEATH_HOLD_MS);
                }
            }
        }
        else if (slot.pendingAttackAnimationId != -1)
        {
            Animation anim = client.loadAnimation(slot.pendingAttackAnimationId);
            if (anim != null)
            {
                slot.object.setShouldLoop(false);
                slot.object.setAnimation(anim);
                slot.pendingAttackAnimationId = -1;
                slot.idleApplied = true; // suppressed until the scheduled hold below flips it back
                plugin.scheduleDelayed(() -> slot.idleApplied = false, RunePartyPlugin.JADDY_ATTACK_ANIMATION_HOLD_MS);
            }
        }
        else if (!slot.idleApplied)
        {
            Animation anim = client.loadAnimation(RunePartyPlugin.JAD_IDLE_ANIMATION_ID);
            if (anim != null)
            {
                slot.object.setShouldLoop(true);
                slot.object.setAnimation(anim);
                slot.idleApplied = true;
            }
        }

        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), slot.center);
        if (lp == null)
        {
            slot.object.setActive(false);
            return;
        }
        slot.object.setLocation(lp, slot.center.getPlane());
        if (!slot.object.isActive()) slot.object.setActive(true);

        renderHealthBar(g, slot, lp);
    }

    /** Draws slot's own health bar (background/border/fraction-filled foreground, green above half,
     * yellow above a quarter, red below) hovering above its model, plus a fading "-N" damage popup
     * stacked just above the bar for HITSPLAT_DURATION_MS after its own last hit. Both anchored off
     * the same single Perspective.localToCanvas projection -- the popup is offset from it in real
     * screen pixels rather than its own separate 3D projection, so it always reads as visually
     * grouped with the bar regardless of camera angle. */
    private void renderHealthBar(Graphics2D g, Slot slot, LocalPoint lp)
    {
        // The 4-arg overload -- (Client, LocalPoint, plane, heightOffset). The 3-arg one takes a
        // plane where this heightOffset is, which silently resolved (no compile error, wrong
        // behavior) since HEALTH_BAR_HEIGHT_OFFSET happens to also be an int -- passing it as a
        // plane index threw internally (planes only run 0-3), aborting this whole render() call
        // before slotB was ever reached.
        Point anchor = Perspective.localToCanvas(client, lp, slot.center.getPlane(), HEALTH_BAR_HEIGHT_OFFSET);
        if (anchor == null) return;

        int barX = anchor.getX() - HEALTH_BAR_WIDTH_PX / 2;
        int barY = anchor.getY() - HEALTH_BAR_HEIGHT_PX / 2;

        g.setColor(HEALTH_BAR_BORDER);
        g.fillRect(barX - 1, barY - 1, HEALTH_BAR_WIDTH_PX + 2, HEALTH_BAR_HEIGHT_PX + 2);
        g.setColor(HEALTH_BAR_BACKGROUND);
        g.fillRect(barX, barY, HEALTH_BAR_WIDTH_PX, HEALTH_BAR_HEIGHT_PX);

        float fraction = slot.maxHp > 0 ? Math.max(0f, Math.min(1f, slot.hp / (float) slot.maxHp)) : 1f;
        Color fill = fraction > 0.5f ? HEALTH_BAR_GREEN : fraction > 0.25f ? HEALTH_BAR_YELLOW : HEALTH_BAR_RED;
        g.setColor(fill);
        g.fillRect(barX, barY, Math.round(HEALTH_BAR_WIDTH_PX * fraction), HEALTH_BAR_HEIGHT_PX);

        long now = System.currentTimeMillis();
        if (now >= slot.hitsplatUntil) return;

        float remaining = (slot.hitsplatUntil - now) / (float) HITSPLAT_DURATION_MS;
        int alpha = Math.max(0, Math.min(255, Math.round(remaining * 255)));
        String text = "-" + slot.hitsplatDamage;

        g.setFont(FontManager.getRunescapeBoldFont());
        FontMetrics fm = g.getFontMetrics();
        int textX = anchor.getX() - fm.stringWidth(text) / 2;
        int textY = barY - HITSPLAT_EXTRA_SCREEN_PX;

        g.setColor(new Color(0, 0, 0, alpha));
        g.drawString(text, textX + 1, textY + 1);
        g.setColor(new Color(HITSPLAT_COLOR.getRed(), HITSPLAT_COLOR.getGreen(), HITSPLAT_COLOR.getBlue(), alpha));
        g.drawString(text, textX, textY);
    }

    /** Builds slot.targetColor's own recolored model the first time it's needed -- a no-op once
     * already built (or once a load attempt has already genuinely failed, see
     * slot.modelLoadFailed). See CoinTrapModel#buildGoldModel's own doc for the recolor technique
     * itself; this is the same approach parameterized by target color, applied to the merged
     * multi-part Jad model instead of a single-part tile decoration. Returns null (and retries
     * next call) while the raw model data isn't cached yet. */
    private Model buildRecoloredModel(Slot slot)
    {
        ModelData raw = RunePartyRender.loadNpcModelData(client, JAD_NPC_ID);
        if (raw == null) return null; // not cached yet -- retried next call

        short[] faceColors = raw.getFaceColors();
        if (faceColors == null || faceColors.length == 0)
        {
            // A genuine, permanent failure (as opposed to raw == null above, which just means "not
            // cached yet, retry next frame") -- logged because this is otherwise a Jad that silently
            // never spawns, with nothing anywhere to say why.
            log.warn("JaddyDuelModel: NPC {} raw model data has no face colors -- recolor for {} permanently failed",
                JAD_NPC_ID, slot.targetColor, new Throwable("stack trace for diagnostics"));
            slot.modelLoadFailed = true;
            return null;
        }

        Set<Short> distinct = new HashSet<>();
        for (short c : faceColors) distinct.add(c);

        int targetRgb = slot.targetColor.getRGB() & 0xFFFFFF;
        int targetHue = JagexColor.unpackHue(JagexColor.rgbToHSL(targetRgb, 1.0));
        int targetSaturation = JagexColor.unpackSaturation(JagexColor.rgbToHSL(targetRgb, 1.0));

        // ModelData#recolor's own doc: "you should call cloneColors() before calling this method"
        // -- it mutates its own backing color array in place. raw here is the client's own shared,
        // cached model data for NPC 3127 -- every other consumer (JadEncounter's classic single-Jad
        // encounter, and this class's OTHER slot, recolored to a different target right after this
        // one) reads from that exact same cache. Skipping this clone means whichever slot recolors
        // last wins for everybody -- the other slot's own already-built Model can end up sharing
        // its color data, and JadEncounter's own Jad silently stops being its natural color.
        ModelData result = raw.cloneColors();
        for (short original : distinct)
        {
            short recolored = JagexColor.packHSL(targetHue, targetSaturation, JagexColor.unpackLuminance(original));
            result = result.recolor(original, recolored);
        }
        return result.light();
    }

    /** One attack beat, fired by JADDY_ATTACK_TRIGGERED: the attacking side (matched to slotA/slotB
     * by color, see resolveSlot) plays animationId once; the defending side takes damage -- its
     * health bar drops to defenderHp/defenderMaxHp and a "-damage" popup appears above it, both
     * applied immediately (there's nothing to lazily retry for a plain field update, unlike the
     * animation itself, see slot.pendingAttackAnimationId's own doc). No-op once a duel has already
     * resolved, or if either color doesn't resolve to a live slot (e.g. a stray event after
     * clear()). */
    public void playAttack(String attackingColorHex, String defendingColorHex, int animationId,
                            int damage, int defenderHp, int defenderMaxHp)
    {
        if (resolved) return;
        Slot attacker = resolveSlot(attackingColorHex);
        Slot defender = resolveSlot(defendingColorHex);
        if (attacker == null || defender == null) return;

        attacker.pendingAttackAnimationId = animationId;

        defender.hp = defenderHp;
        defender.maxHp = defenderMaxHp;
        defender.hitsplatDamage = damage;
        defender.hitsplatUntil = System.currentTimeMillis() + HITSPLAT_DURATION_MS;
    }

    private Slot resolveSlot(String colorHex)
    {
        if (isColor(colorHex, RunePartyPlugin.TEAM_A_COLOR)) return slotA;
        if (isColor(colorHex, RunePartyPlugin.TEAM_B_COLOR)) return slotB;
        return null;
    }

    /** Marks the duel resolved -- winningColorHex is whichever of TEAM_A_COLOR/TEAM_B_COLOR
     * survived, fired by JADDY_DUEL_RESOLVED. Freezes both slots at their current geometry (see
     * this class's own doc) and arms the losing slot's death animation, applied lazily inside
     * render() same as every other animation trigger here. */
    public void resolve(String winningColorHex)
    {
        Color winning;
        try { winning = Color.decode(winningColorHex); }
        catch (NumberFormatException e) { return; }

        pendingLoser = winning.getRGB() == RunePartyPlugin.TEAM_A_COLOR.getRGB() ? slotB : slotA;
        deathApplied = false;
        resolved = true;
    }

    /** Despawns both Jads and arms {@link #suppressRespawnUntil}'s brief grace window -- the tail
     * end of a resolved duel, scheduled by renderSlot's own death-animation branch. Distinct from
     * {@link #clear()} (called constantly, harmlessly, whenever no Jaddy duel is on the board at
     * all) specifically so that routine case never accidentally suppresses a genuinely new duel's
     * own respawn. */
    private void finishDuel()
    {
        clear();
        suppressRespawnUntil = System.currentTimeMillis() + DUEL_RESPAWN_SUPPRESS_MS;
    }

    /** Despawns both Jads immediately, if either is currently up -- for shutDown(), render()'s own
     * phase/no-duel-on-board branches, and finishDuel() above. Safe to call when nothing's spawned.
     * Deliberately does NOT touch suppressRespawnUntil -- see finishDuel's own doc for why that's
     * kept separate. */
    public void clear()
    {
        pendingLoser = null;
        deathApplied = false;
        resolved = false;
        clearSlot(slotA);
        clearSlot(slotB);
    }

    private void clearSlot(Slot slot)
    {
        slot.center = null;
        slot.facing = null;
        slot.idleApplied = false;
        slot.pendingAttackAnimationId = -1;
        slot.hp = DEFAULT_HP;
        slot.maxHp = DEFAULT_HP;
        slot.hitsplatUntil = 0;
        RuneLiteObject obj = slot.object;
        slot.object = null;
        if (obj != null) clientThread.invoke(() -> obj.setActive(false));
    }

    private int resolveRadius()
    {
        NPCComposition comp = client.getNpcDefinition(JAD_NPC_ID);
        return comp != null ? comp.getSize() * Perspective.LOCAL_TILE_SIZE / 2 : DEFAULT_RADIUS;
    }

    /** One side of the duel -- its own recolored model (built once, reused across duels),
     * RuneLiteObject, current center/facing (re-derived from the board every frame, see
     * updateZoneGeometry), idle-loop/pending-attack-animation state, and its own live health bar/
     * hitsplat reading. Plain field bag, not a public type -- only ever touched from this outer
     * class. */
    private static final class Slot
    {
        Color targetColor;
        Model model;
        boolean modelLoadFailed;
        RuneLiteObject object;
        WorldPoint center;
        WorldPoint facing;
        boolean idleApplied;

        // Set by playAttack() when this slot is the attacker that beat -- consumed the frame the
        // animation resource actually loads, same "retry every frame until ready, then fire once"
        // idiom JadEncounter's own smashPending uses. -1 means no attack pending.
        int pendingAttackAnimationId = -1;

        // This slot's own current health bar reading -- see this class's own doc for why neither
        // is folded from server state; defaults to DEFAULT_HP/DEFAULT_HP so a freshly spawned duel
        // reads as full before its first real hit lands.
        int hp = DEFAULT_HP;
        int maxHp = DEFAULT_HP;

        // The most recent hit this slot took, and when its own damage popup should stop showing --
        // 0 means no popup currently showing.
        int hitsplatDamage;
        long hitsplatUntil;
    }
}
