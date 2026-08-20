package gay.runescape.runeparty;

import net.runelite.api.Client;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.RuneLiteObject;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.Text;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Renders the course: committed tiles from TileReducer, plus a live placement/removal preview
 * while the host is building. Unlike Gnomeball's TileOverlay -- whose FIELD/ZONE tiles are large
 * connected regions rendered as an outline -- a course is a one-tile-wide walked path, so every
 * tile renders individually filled/bordered (Gnomeball's non-outline-type styling) with a
 * connecting line drawn between consecutive path indices to make the route itself legible. */
@Slf4j
public class TileOverlay extends Overlay
{
    private static final Color COLOR_UNKNOWN_TYPE = new Color(255, 255, 0); // fallback for any tile type not explicitly recognized below
    private static final Color COLOR_PATH          = new Color(40, 130, 230); // the "standard" tile -- plain, always worth 3 coins on landing
    private static final Color COLOR_PENALTY_TILE  = new Color(220, 50, 50); // functions like PATH, but -3 coins on landing (floored at 0)
    private static final Color COLOR_START         = new Color(60, 179, 74);
    // Only used for the live placement preview now (see renderPresetPreview) -- a committed Golden
    // Gnome tile renders as a 3D model instead (model 31481, see updateGoldenGnomeModels), but the
    // preview is a lightweight dashed-outline pass over the whole course before it's even
    // committed, same as every other tile type there, so it keeps the simple color-fill look.
    private static final Color COLOR_GOLDEN_GNOME_TILE = new Color(255, 210, 0);
    private static final Color COLOR_EVENT_TILE    = new Color(170, 80, 220);
    private static final Color COLOR_ITEM_TILE     = new Color(255, 140, 0); // landing spins the item wheel (see AnnouncementOverlay#renderItemSpinner) and grants a random item
    private static final Color COLOR_ROUTE_LINE    = new Color(255, 255, 255, 100);
    private static final Color COLOR_TARGET_ARROW  = new Color(255, 215, 0);

    private static final int FILL_ALPHA   = 128; // 50% opacity fill
    private static final int BORDER_ALPHA = 255; // solid border

    private static final Stroke SOLID_STROKE   = new BasicStroke(2f);
    private static final Stroke PREVIEW_STROKE = new BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[]{6f, 4f}, 0f);
    private static final Stroke ROUTE_STROKE   = new BasicStroke(2f);

    // A committed Golden Gnome tile renders as this model, spawned as a RuneLiteObject in the
    // scene, instead of a color fill -- see updateGoldenGnomeModels.
    private static final int GOLDEN_GNOME_MODEL_ID = 32303;

    private final Client client;
    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;
    private final TileReducer tileReducer;

    // One RuneLiteObject per currently-marked Golden Gnome tile, keyed by its WorldPoint -- see
    // updateGoldenGnomeModels/clearGoldenGnomeModels, the only things that touch this.
    private final Map<WorldPoint, RuneLiteObject> goldenGnomeModels = new HashMap<>();

    // Debug aid while tracking down why GOLDEN_GNOME_MODEL_ID isn't rendering -- logs the outcome
    // of each loadModel() attempt once per point instead of every frame, so the client log shows
    // whether it's failing to load at all vs. loading but not showing up.
    private final Set<WorldPoint> goldenGnomeModelLoadLogged = new HashSet<>();

    public TileOverlay(Client client, RunePartyConfig config, RunePartyPlugin plugin, TileReducer tileReducer)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.tileReducer = tileReducer;

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showTileOverlay())
        {
            clearGoldenGnomeModels();
            return null;
        }
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE)
        {
            clearGoldenGnomeModels();
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        renderCommittedCourse(g);

        if (plugin.isCoursePlacementMode())
        {
            renderPresetPreview(g);
        }

        renderTargetArrow(g);
        renderReturnToPositionArrow(g);
        renderStartArrow(g);

        return null;
    }

    private void renderCommittedCourse(Graphics2D g)
    {
        List<TileReducer.TileEntry> entries = tileReducer.snapshot();

        for (TileReducer.TileEntry entry : entries)
        {
            if ("GOLDEN_GNOME_TILE".equals(entry.tileType)) continue; // rendered as a 3D model instead, see updateGoldenGnomeModels below
            Color base = resolveColor(entry.color, entry.tileType);
            renderFilledTile(g, entry.point, withAlpha(base, FILL_ALPHA), withAlpha(base, BORDER_ALPHA), SOLID_STROKE);
        }

        updateGoldenGnomeModels(entries);
        renderRouteLines(g, entries);
    }

    /** Keeps one RuneLiteObject (model GOLDEN_GNOME_MODEL_ID) spawned in the scene for every
     * currently-marked Golden Gnome tile, diffed each frame against TileReducer's live snapshot --
     * same "the reducer is the one source of truth" pattern every other tile visual here already
     * follows. A RuneLiteObject is registered directly with the client, not the OverlayManager, so
     * it doesn't get cleaned up just because this overlay stops rendering -- see
     * clearGoldenGnomeModels for the other half of that. loadModel can return null for a couple of
     * frames right after the client starts while the model's still loading from cache, so this
     * keeps retrying every frame until it succeeds rather than giving up after one null.
     * <p>
     * TileReducer is real state, updated the instant TILE_UNMARKED/TILE_MARKED land regardless of
     * how this overlay wants to present it -- but a Golden Gnome relocating is choreographed
     * against two spotanims (see RunePartyPlugin's GOLDEN_GNOME_MOVED handling), so the model
     * shouldn't just teleport the moment those events arrive. RunePartyPlugin#
     * getGoldenGnomeMoveOldPoint/getGoldenGnomeMoveNewPoint (with their matching hide/show
     * timestamps) are what let this method override the raw diff for exactly as long as that
     * choreography needs: force-persisting the old spot a beat after TileReducer already dropped
     * it, and force-suppressing the new spot a beat before TileReducer's already-added entry
     * actually shows. */
    private void updateGoldenGnomeModels(List<TileReducer.TileEntry> entries)
    {
        Set<WorldPoint> current = new HashSet<>();
        for (TileReducer.TileEntry entry : entries)
        {
            if ("GOLDEN_GNOME_TILE".equals(entry.tileType)) current.add(entry.point);
        }

        long now = System.currentTimeMillis();
        WorldPoint moveOld = plugin.getGoldenGnomeMoveOldPoint();
        if (moveOld != null && now < plugin.getGoldenGnomeMoveHideOldAt())
        {
            current.add(moveOld);
        }
        WorldPoint moveNew = plugin.getGoldenGnomeMoveNewPoint();
        if (moveNew != null && now < plugin.getGoldenGnomeMoveShowNewAt())
        {
            current.remove(moveNew);
        }

        goldenGnomeModels.entrySet().removeIf(e ->
        {
            if (current.contains(e.getKey())) return false;
            e.getValue().setActive(false);
            return true;
        });

        for (WorldPoint point : current)
        {
            RuneLiteObject obj = goldenGnomeModels.computeIfAbsent(point, p -> client.createRuneLiteObject());

            if (obj.getModel() == null)
            {
                Model model = client.loadModel(GOLDEN_GNOME_MODEL_ID);
                if (model != null)
                {
                    obj.setModel(model);
                    if (goldenGnomeModelLoadLogged.add(point))
                    {
                        log.info("Golden Gnome model {} loaded at {}: vertexCount={} faceCount={}",
                            GOLDEN_GNOME_MODEL_ID, point, model.getVerticesCount(), model.getFaceCount());
                    }
                }
                else if (goldenGnomeModelLoadLogged.add(point))
                {
                    log.info("Golden Gnome model {} returned null from loadModel() at {} (will keep retrying every frame)",
                        GOLDEN_GNOME_MODEL_ID, point);
                }
            }

            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), point);
            if (lp == null)
            {
                obj.setActive(false);
                continue;
            }

            obj.setLocation(lp, point.getPlane());
            if (obj.getModel() != null && !obj.isActive())
            {
                obj.setActive(true);
                log.info("Golden Gnome RuneLiteObject activated at {}", point);
            }
        }
    }

    /** Despawns and forgets every Golden Gnome RuneLiteObject -- called whenever this overlay
     * stops actively rendering the course (see render()'s early returns) and from
     * RunePartyPlugin#shutDown, since a RuneLiteObject otherwise stays registered with the client
     * independently of this overlay or even the plugin being active. */
    public void clearGoldenGnomeModels()
    {
        for (RuneLiteObject obj : goldenGnomeModels.values())
        {
            obj.setActive(false);
        }
        goldenGnomeModels.clear();
    }

    /** Draws a bouncing, pulsing arrow -- in the mover's own RunePartyColor (see
     * RunePartyPlugin#getRosterReducer, falls back to gold if their seat color can't be resolved)
     * -- labeled with their name centered above it, over <i>every</i> candidate tile the current
     * roll could resolve to. Usually that's one tile, but a roll whose path crosses a fork can
     * offer more than one (see RunePartyPlugin#getPendingTargetIndices and the server's
     * _reachable_targets) -- the player picks which one to walk to, so every candidate gets its
     * own arrow. Visible to every client watching, not just the mover, the same way the rest of
     * the board state is shared. Held back until AnnouncementOverlay's dice-roll reveal has
     * actually settled on the real number (RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS after
     * diceRollStart, plus RunePartyPlugin.DICE_ROLL_BONUS_REVEAL_MS more on top of that whenever
     * this roll carried an item bonus -- see getDiceRollBonus -- so the "+N = total" reveal always
     * finishes before this arrow spoils where that total actually lands) --
     * pendingTargetIndices is known the instant DICE_ROLLED lands, same moment
     * the die starts cycling random faces, so showing the destination immediately would spoil the
     * roll before the die even reveals what it landed on. All of them disappear together as soon as
     * the mover is actually standing on any one candidate (checked directly against their on-screen
     * position every frame, not just on the next TURN_STARTED echo) or, failing that, once
     * TURN_STARTED clears pendingRoll/pendingTargetIndices (see RunePartyPlugin#handleEvent) -- e.g.
     * if the mover isn't currently rendered for this client. Also suppressed once a mini-game
     * starts: when the last roller of a round lands, the server fires MINIGAME_STARTED instead of
     * TURN_STARTED (see the server's _advance_turn_or_start_minigame), so
     * pendingRoll/pendingTargetIndices are never cleared by that path -- without this check, that
     * mover stepping off their landed tile during the mini-game would make this arrow reappear
     * telling them to go back, which is no longer where they need to be. */
    private void renderTargetArrow(Graphics2D g)
    {
        if (!plugin.isPendingRoll() || plugin.isMinigameActive()) return;
        long revealDelay = RunePartyPlugin.DICE_ROLL_SPIN_PHASE_MS
            + (plugin.getDiceRollBonus() != 0 ? RunePartyPlugin.DICE_ROLL_BONUS_REVEAL_MS : 0);
        if (System.currentTimeMillis() - plugin.getDiceRollStart() < revealDelay) return;
        List<Integer> targetIndices = plugin.getPendingTargetIndices();
        if (targetIndices.isEmpty()) return;
        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null) return;

        Player mover = findPlayerByRsn(moverRsn);
        WorldPoint moverPos = mover != null ? mover.getWorldLocation() : null;

        if (moverPos != null)
        {
            for (int targetIndex : targetIndices)
            {
                TileReducer.TileEntry target = tileReducer.tileAtIndex(targetIndex);
                if (target != null && target.point.equals(moverPos)) return; // already chose one -- hide every candidate
            }
        }

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(moverRsn));
        Color arrowColor = seatColor != null ? seatColor.awt : COLOR_TARGET_ARROW;

        for (int targetIndex : targetIndices)
        {
            TileReducer.TileEntry target = tileReducer.tileAtIndex(targetIndex);
            if (target == null) continue;
            drawBouncingArrowWithLabel(g, target.point, moverRsn, arrowColor);
        }
    }

    /** Draws the "come back here" arrow over the current mover's own tracked board position (see
     * RunePartyPlugin#getPlayerPosition) whenever it's their turn, they haven't rolled yet, and
     * they're not actually standing there -- e.g. they wandered off after landing last round.
     * Mirrors renderStartArrow's pre-game "gather here" instruction, just generalized to every
     * turn instead of only the first: RunePartyPlugin#onAnimationChanged enforces the same
     * requirement server-side-of-the-gesture (the Spin emote does nothing until they're back), this
     * is purely the visual telling them where "back" is. Suppressed during a mini-game the same way
     * renderTargetArrow is (see that method's own doc) -- once minigameActive flips true there's no
     * tile left to return to until the round's next TURN_STARTED. Same reasoning covers a pending
     * Golden Gnome offer: pendingRoll is already false by the time one exists (PLAYER_MOVED clears
     * it before the offer is even created), so without this check, wandering off mid-offer -- while
     * deciding whether to buy -- would make this arrow reappear too, even though "return" isn't
     * really the point right now. Unlike renderTargetArrow (which broadcasts whose turn is
     * resolving to everyone watching), this only ever renders for the mover themselves -- it's a
     * personal nudge to walk back, not board state anyone else needs to see. */
    private void renderReturnToPositionArrow(Graphics2D g)
    {
        if (plugin.isPendingRoll() || plugin.isMinigameActive() || plugin.getGoldenGnomeOfferRsn() != null) return;
        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null || !isLocalPlayer(moverRsn)) return;

        TileReducer.TileEntry tile = tileReducer.tileAtIndex(plugin.getPlayerPosition(moverRsn));
        if (tile == null) return;

        WorldPoint localPos = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
        if (localPos != null && localPos.equals(tile.point)) return; // already back -- nothing to show

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getNumber(moverRsn));
        Color arrowColor = seatColor != null ? seatColor.awt : COLOR_TARGET_ARROW;
        drawBouncingArrowWithLabel(g, tile.point, "Return Here!", arrowColor);
    }

    /** Draws a bouncing, pulsing arrow over {@code tilePoint} with {@code label} centered above
     * it, in {@code color}. Shared by renderTargetArrow (whose-turn-is-it), renderReturnToPositionArrow
     * (go back to your last tile before rolling again), and renderStartArrow (the pre-game
     * gathering instruction) -- same animation, different trigger condition, color, and label. */
    private void drawBouncingArrowWithLabel(Graphics2D g, WorldPoint tilePoint, String label, Color color)
    {
        Point center = tileCenterOnCanvas(tilePoint);
        if (center == null) return;

        long now = System.currentTimeMillis();
        int bounce = (int) Math.round(Math.sin(now / 180.0) * 6);
        float pulse = (float) (0.55 + 0.45 * Math.sin(now / 260.0));

        int baseY = center.y - 38 + bounce;
        int tipY = baseY + 16;
        int halfWidth = 9;

        Polygon arrow = new Polygon();
        arrow.addPoint(center.x, tipY);
        arrow.addPoint(center.x - halfWidth, baseY);
        arrow.addPoint(center.x + halfWidth, baseY);

        int alpha = (int) (pulse * 255);
        g.setColor(withAlpha(color, alpha));
        g.fillPolygon(arrow);
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.setStroke(SOLID_STROKE);
        g.drawPolygon(arrow);

        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        int textY = baseY - 6;
        g.setColor(withAlpha(Color.BLACK, alpha));
        g.drawString(label, center.x - textWidth / 2 + 1, textY + 1);
        g.setColor(withAlpha(color, alpha));
        g.drawString(label, center.x - textWidth / 2, textY);
    }

    /** Draws the pre-game "gather here" arrow over the START tile (always path index 0, see
     * CoursePreset) during the window between GAME_STARTED and the first TURN_STARTED -- i.e.
     * while RunePartyPlugin#getCurrentTurnRsn is still null, see RunePartyPlugin#
     * checkGatheringAtStart for the matching per-player confirm-start reporting. Uses the START
     * tile's own green so the arrow visually ties back to the tile it's pointing at. */
    private void renderStartArrow(Graphics2D g)
    {
        if (plugin.getPhase() != GamePhase.ACTIVE) return;
        if (plugin.getCurrentTurnRsn() != null) return; // turn order has begun -- gathering is over

        TileReducer.TileEntry start = tileReducer.tileAtIndex(0);
        if (start == null) return;

        drawBouncingArrowWithLabel(g, start.point, "Start Here!", COLOR_START);
    }

    private Player findPlayerByRsn(String rsn)
    {
        if (rsn == null) return null;
        for (Player p : client.getPlayers())
        {
            if (p == null || p.getName() == null) continue;
            if (rsn.equalsIgnoreCase(Text.toJagexName(p.getName()))) return p;
        }
        return null;
    }

    /** Whether {@code rsn} is this client's own local player -- see renderReturnToPositionArrow,
     * the only caller. */
    private boolean isLocalPlayer(String rsn)
    {
        Player local = client.getLocalPlayer();
        if (local == null || local.getName() == null) return false;
        return rsn.equalsIgnoreCase(Text.toJagexName(local.getName()));
    }

    /** Draws a line from each path tile to every tile it actually leads to (see
     * TileReducer#resolveNextIndices), so the walked route reads clearly even though every tile
     * renders as an independent filled square -- a fork fans out into more than one line, and a
     * branch's tiles converge back onto whatever merge tile they were pointed at. Only draws
     * between tiles that both actually exist -- a gap in the course just leaves that one segment
     * undrawn rather than guessing a connection across it. */
    private void renderRouteLines(Graphics2D g, List<TileReducer.TileEntry> entries)
    {
        if (entries.isEmpty()) return;

        g.setStroke(ROUTE_STROKE);
        g.setColor(COLOR_ROUTE_LINE);

        for (TileReducer.TileEntry from : entries)
        {
            if (from.pathIndex == null) continue;

            for (int nextIndex : tileReducer.resolveNextIndices(from))
            {
                TileReducer.TileEntry to = tileReducer.tileAtIndex(nextIndex);
                if (to == null) continue;

                Point fromCanvas = tileCenterOnCanvas(from.point);
                Point toCanvas = tileCenterOnCanvas(to.point);
                if (fromCanvas == null || toCanvas == null) continue;

                g.drawLine(fromCanvas.x, fromCanvas.y, toCanvas.x, toCanvas.y);
            }
        }
    }

    private Point tileCenterOnCanvas(WorldPoint wp)
    {
        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), wp);
        if (lp == null) return null;
        Polygon poly = Perspective.getCanvasTilePoly(client, lp);
        if (poly == null) return null;
        Rectangle bounds = poly.getBounds();
        return new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
    }

    private void renderPresetPreview(Graphics2D g)
    {
        CoursePreset preset = plugin.getSelectedPreset();
        if (preset == null) return;

        Tile hovered = client.getTopLevelWorldView().getSelectedSceneTile();
        if (hovered == null) return;
        WorldPoint center = hovered.getWorldLocation();
        if (center == null) return;

        List<CoursePreset.PlacedTile> placed = preset.layout(center, plugin.getPresetRotationSteps());

        for (CoursePreset.PlacedTile pt : placed)
        {
            Color base = resolveColor(pt.color, pt.tileType);
            renderFilledTile(g, pt.point, withAlpha(base, FILL_ALPHA), withAlpha(base, BORDER_ALPHA), PREVIEW_STROKE);
        }

        // A decorative tile (a Golden Gnome modifier stacked on another tile, see
        // CoursePreset.RelativeTile#decorative) never gets a pathIndex of its own on commit, so it
        // doesn't count toward the course's real length for the "+1" wraparound below, and it never
        // has a route line drawn *from* it -- only non-decorative entries do. Correctness here
        // relies on the same "decoratives are always listed after every real tile" ordering commit
        // itself requires (see RunePartyPlugin#commitPreset), so list index i still equals a
        // non-decorative entry's real pathIndex.
        int courseLength = 0;
        for (CoursePreset.PlacedTile pt : placed) if (!pt.decorative) courseLength++;

        g.setStroke(PREVIEW_STROKE);
        g.setColor(COLOR_ROUTE_LINE);
        for (int i = 0; i < placed.size(); i++)
        {
            CoursePreset.PlacedTile pt = placed.get(i);
            if (pt.decorative) continue;
            for (int nextIndex : resolvePreviewNextIndices(pt, i, courseLength))
            {
                if (nextIndex < 0 || nextIndex >= placed.size()) continue;
                Point fromCanvas = tileCenterOnCanvas(pt.point);
                Point toCanvas = tileCenterOnCanvas(placed.get(nextIndex).point);
                if (fromCanvas == null || toCanvas == null) continue;
                g.drawLine(fromCanvas.x, fromCanvas.y, toCanvas.x, toCanvas.y);
            }
        }
    }

    /** Same default-or-explicit resolution as TileReducer#resolveNextIndices, but for a live
     * placement preview's in-memory PlacedTile list (not yet committed, so there's no TileReducer
     * entry -- or courseLength -- to resolve against yet). {@code length} is the *real* course
     * length (non-decorative tiles only, see renderPresetPreview), not placed.size(). */
    private static int[] resolvePreviewNextIndices(CoursePreset.PlacedTile pt, int index, int length)
    {
        if (pt.nextIndices.length > 0) return pt.nextIndices;
        if (length == 0) return new int[0];
        return new int[] { (index + 1) % length };
    }

    private void renderFilledTile(Graphics2D g, WorldPoint wp, Color fill, Color border, Stroke stroke)
    {
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            g.setColor(fill);
            g.fillPolygon(poly);
            g.setColor(border);
            g.setStroke(stroke);
            g.drawPolygon(poly);
        }
    }

    private static Color withAlpha(Color c, int alpha)
    {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), alpha);
    }

    private static Color defaultColorFor(String tileType)
    {
        if ("PATH".equals(tileType)) return COLOR_PATH;
        if ("PENALTY_TILE".equals(tileType)) return COLOR_PENALTY_TILE;
        if ("START".equals(tileType)) return COLOR_START;
        if ("GOLDEN_GNOME_TILE".equals(tileType)) return COLOR_GOLDEN_GNOME_TILE;
        if ("EVENT_TILE".equals(tileType)) return COLOR_EVENT_TILE;
        if ("ITEM_TILE".equals(tileType)) return COLOR_ITEM_TILE;
        return COLOR_UNKNOWN_TYPE;
    }

    private static Color resolveColor(String hex, String tileType)
    {
        if (hex == null || hex.isBlank()) return defaultColorFor(tileType);
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return defaultColorFor(tileType); }
    }
}
