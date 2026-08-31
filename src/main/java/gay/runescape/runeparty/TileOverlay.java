package gay.runescape.runeparty;

import gay.runescape.runeparty.models.ArenaFireModel;
import gay.runescape.runeparty.models.CoinRushModel;
import gay.runescape.runeparty.models.CoinTrapModel;
import gay.runescape.runeparty.models.GoldenGnomeModel;
import gay.runescape.runeparty.models.PondModel;
import gay.runescape.runeparty.models.TableModel;
import net.runelite.api.Client;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Tile;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.ui.overlay.*;
import net.runelite.client.util.Text;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.geom.Path2D;
import java.util.Collection;
import java.util.List;

/** Renders the course: committed tiles from TileReducer, plus a live placement/removal preview
 * while the host is building. Unlike Gnomeball's TileOverlay -- whose FIELD/ZONE tiles are large
 * connected regions rendered as an outline -- a course is a one-tile-wide walked path, so every
 * tile renders individually as its own rounded-corner outline (see renderOutlinedTile), inset a
 * little inward from the tile's true boundary so adjacent tiles' outlines read as a clean grid
 * rather than doubled-up touching edges, with a connecting line drawn between consecutive path
 * indices to make the route itself legible. The one exception is the Fishing Contest platform's
 * FISHING_TILE block, which -- like Gnomeball's own zones -- genuinely is one connected region
 * rather than a walked path, so it renders as a single merged-area outline instead (see
 * renderFishingZoneOutline), sharing roundedInsetPolygon with every per-tile outline so its corners
 * read exactly the same. */
@Slf4j
public class TileOverlay extends Overlay
{
    // Fallback for any tile type the served catalog doesn't have (see defaultColorFor) -- not
    // itself served, since it only ever needs to exist client-side.
    private static final Color COLOR_UNKNOWN_TYPE = new Color(255, 255, 0);
    private static final Color COLOR_ROUTE_LINE    = new Color(255, 255, 255, 100);
    private static final Color COLOR_TARGET_ARROW  = new Color(255, 215, 0);

    // How far a tile's drawn outline sits inward from its true boundary, and how rounded its
    // corners are -- both in on-screen pixels, so neither scales with the tile's own on-screen
    // size (a distant, small-on-screen tile gets proportionally less inset/rounding than a close
    // one, same as SOLID_STROKE's own fixed pixel width already does). See renderOutlinedTile.
    private static final double TILE_OUTLINE_INSET_PX = 4.0;
    private static final double TILE_OUTLINE_CORNER_RADIUS_PX = 7.0;

    // Applied to FISHING_TILE's own catalog color only (see renderFishingZoneOutline), not to
    // resolveColor generally -- every per-tile outline elsewhere still draws at that color's own
    // full opacity, this alpha is layered on top of it just for the merged zone outline so it reads
    // as a lighter boundary marker rather than as prominent as an individual tile's own outline.
    private static final int FISHING_ZONE_OUTLINE_ALPHA = 120;

    private static final Stroke SOLID_STROKE   = new BasicStroke(3.5f);
    private static final Stroke PREVIEW_STROKE = new BasicStroke(3.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f, new float[]{7f, 5f}, 0f);
    private static final Stroke ROUTE_STROKE   = new BasicStroke(2f);

    private static final Color COLOR_PLACEMENT_ARROW = new Color(255, 140, 0);

    private final Client client;
    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;
    private final TileReducer tileReducer;

    // The tile-decoration 3D models -- Golden Gnome, Coin Trap, Coin Rush, Arena Fire -- each own
    // their own SceneObjectSet-backed diff/spawn (see ARCHITECTURE_REVIEW.md's C5) under models/,
    // split out of this class since more of these are planned; this overlay just owns one instance
    // of each and calls update()/clear() at the right moments below.
    private final GoldenGnomeModel goldenGnomeModel;
    private final CoinTrapModel coinTrapModel;
    private final CoinRushModel coinRushModel;
    private final ArenaFireModel arenaFireModel;
    private final PondModel pondModel;
    private final TableModel tableModel;

    public TileOverlay(Client client, RunePartyConfig config, RunePartyPlugin plugin, TileReducer tileReducer)
    {
        this.client = client;
        this.config = config;
        this.plugin = plugin;
        this.tileReducer = tileReducer;

        this.goldenGnomeModel = new GoldenGnomeModel(client, plugin);
        this.coinTrapModel = new CoinTrapModel(client, plugin);
        this.coinRushModel = new CoinRushModel(client, plugin);
        this.arenaFireModel = new ArenaFireModel(client);
        this.pondModel = new PondModel(client);
        this.tableModel = new TableModel(client);

        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_SCENE);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showTileOverlay())
        {
            clearGoldenGnomeModels();
            clearCoinTrapModels();
            clearCoinRushModels();
            clearArenaFireModels();
            clearPondModels();
            clearTableModels();
            return null;
        }
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE)
        {
            clearGoldenGnomeModels();
            clearCoinTrapModels();
            clearCoinRushModels();
            clearArenaFireModels();
            clearPondModels();
            clearTableModels();
            return null;
        }

        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        renderCommittedCourse(g);
        coinRushModel.update();

        if (plugin.isCoursePlacementMode())
        {
            renderPresetPreview(g);
        }

        renderTargetArrow(g);
        renderReturnToPositionArrow(g);
        renderStartArrow(g);
        renderItemPlacementArrows(g);
        renderGoldenGnomePurchaseArrow(g);

        return null;
    }

    private void renderCommittedCourse(Graphics2D g)
    {
        List<TileReducer.TileEntry> entries = tileReducer.snapshot();

        for (TileReducer.TileEntry entry : entries)
        {
            if ("GOLDEN_GNOME_TILE".equals(entry.tileType)) continue; // rendered as a 3D model instead, see models/GoldenGnomeModel
            if ("COIN_TRAP_TILE".equals(entry.tileType)) continue; // rendered as a 3D model instead, see models/CoinTrapModel
            if ("ARENA_TILE".equals(entry.tileType) && ArenaFireModel.isDead(entry.color)) continue; // rendered as a 3D model instead, see models/ArenaFireModel
            if ("POND_TILE".equals(entry.tileType)) continue; // rendered as a 3D model instead, see models/PondModel
            if ("FISHING_TILE".equals(entry.tileType)) continue; // rendered as one merged-zone outline instead, see renderFishingZoneOutline
            Color base = resolveColor(entry.color, entry.tileType);
            renderOutlinedTile(g, entry.point, base, SOLID_STROKE);
        }

        renderFishingZoneOutline(g, entries);

        goldenGnomeModel.update(entries);
        coinTrapModel.update(entries);
        arenaFireModel.update(entries);
        tableModel.update(entries);
        pondModel.update(entries);
        renderRouteLines(g, entries);
    }

    /** Draws the whole Fishing Contest platform's FISHING_TILE block as one merged-area outline,
     * rather than each of its tiles getting its own individually like renderCommittedCourse's main
     * loop does for a walked path -- the platform is a genuinely connected region (see this class's
     * own doc), so tracing every internal seam would just be visual noise. Derives the region's
     * center from the POND_TILE modifier stacked at its exact center coordinate (see
     * minigames/fishing_contest.py's own _centered_platform, the only source of this layout) rather
     * than averaging FISHING_TILE corners itself, and its size from the FISHING_TILE entries' own
     * bounding box rather than a hardcoded constant, so this keeps working if PLATFORM_SIZE ever
     * changes. {@link Perspective#getCanvasTileAreaPoly} is the AoE-polygon counterpart to
     * getCanvasTilePoly (which a single renderOutlinedTile call uses) -- same 4-corner polygon
     * shape, just spanning the whole region instead of one tile, so it drops straight into the same
     * roundedInsetPolygon call every other outline here already uses. */
    private void renderFishingZoneOutline(Graphics2D g, List<TileReducer.TileEntry> entries)
    {
        WorldPoint center = null;
        Color color = null;
        Integer minX = null, maxX = null, minY = null, maxY = null;
        for (TileReducer.TileEntry entry : entries)
        {
            if ("POND_TILE".equals(entry.tileType))
            {
                center = entry.point;
            }
            else if ("FISHING_TILE".equals(entry.tileType))
            {
                WorldPoint p = entry.point;
                if (color == null) color = resolveColor(entry.color, entry.tileType);
                minX = (minX == null) ? p.getX() : Math.min(minX, p.getX());
                maxX = (maxX == null) ? p.getX() : Math.max(maxX, p.getX());
                minY = (minY == null) ? p.getY() : Math.min(minY, p.getY());
                maxY = (maxY == null) ? p.getY() : Math.max(maxY, p.getY());
            }
        }
        if (center == null || minX == null) return;

        LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), center);
        if (lp == null) return;

        Polygon poly = Perspective.getCanvasTileAreaPoly(client, lp, maxX - minX + 1, maxY - minY + 1, center.getPlane(), 0);
        if (poly == null) return;

        g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), FISHING_ZONE_OUTLINE_ALPHA));
        g.setStroke(SOLID_STROKE);
        g.draw(roundedInsetPolygon(poly, TILE_OUTLINE_INSET_PX, TILE_OUTLINE_CORNER_RADIUS_PX));
    }

    /** Despawns and forgets every Golden Gnome RuneLiteObject -- called whenever this overlay
     * stops actively rendering the course (see render()'s early returns) and from
     * RunePartyPlugin#shutDown, since a RuneLiteObject otherwise stays registered with the client
     * independently of this overlay or even the plugin being active. */
    public void clearGoldenGnomeModels()
    {
        goldenGnomeModel.clear();
    }

    /** Despawns and forgets every Coin Trap RuneLiteObject -- same reasoning/call sites as
     * clearGoldenGnomeModels. */
    public void clearCoinTrapModels()
    {
        coinTrapModel.clear();
    }

    /** Despawns and forgets every Coin Rush RuneLiteObject -- same reasoning/call sites as
     * clearGoldenGnomeModels/clearCoinTrapModels. */
    public void clearCoinRushModels()
    {
        coinRushModel.clear();
    }

    /** Despawns and forgets every Arena Fire RuneLiteObject -- same reasoning/call sites as
     * clearGoldenGnomeModels/clearCoinTrapModels/clearCoinRushModels. */
    public void clearArenaFireModels()
    {
        arenaFireModel.clear();
    }

    /** Despawns and forgets every Pond RuneLiteObject -- same reasoning/call sites as
     * clearGoldenGnomeModels/clearCoinTrapModels/clearCoinRushModels/clearArenaFireModels. */
    public void clearPondModels()
    {
        pondModel.clear();
    }

    /** Despawns and forgets every Table RuneLiteObject -- same reasoning/call sites as
     * clearGoldenGnomeModels/clearCoinTrapModels/clearCoinRushModels/clearArenaFireModels/
     * clearPondModels. */
    public void clearTableModels()
    {
        tableModel.clear();
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

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(moverRsn));
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
     * tile left to return to until the round's next TURN_STARTED. Also suppressed for the whole
     * duration of a pending roll (isPendingRoll), which covers wandering off toward the Golden
     * Gnome to purchase it (see purchaseGoldenGnomeAt) just as well as any other mid-roll detour --
     * "return" isn't the point until they've actually confirmed arrival. Unlike renderTargetArrow
     * (which broadcasts whose turn is resolving to everyone watching), this only ever renders for
     * the mover themselves -- it's a personal nudge to walk back, not board state anyone else needs
     * to see. */
    private void renderReturnToPositionArrow(Graphics2D g)
    {
        if (plugin.isPendingRoll() || plugin.isMinigameActive()) return;
        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null || !isLocalPlayer(moverRsn)) return;

        TileReducer.TileEntry tile = tileReducer.tileAtIndex(plugin.getPlayerPosition(moverRsn));
        if (tile == null) return;

        WorldPoint localPos = client.getLocalPlayer() != null ? client.getLocalPlayer().getWorldLocation() : null;
        if (localPos != null && localPos.equals(tile.point)) return; // already back -- nothing to show

        RunePartyColor seatColor = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(moverRsn));
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
        g.setColor(RunePartyRender.withAlpha(color, alpha));
        g.fillPolygon(arrow);
        g.setColor(RunePartyRender.withAlpha(Color.BLACK, alpha));
        g.setStroke(SOLID_STROKE);
        g.drawPolygon(arrow);

        FontMetrics fm = g.getFontMetrics();
        int textWidth = fm.stringWidth(label);
        int textY = baseY - 6;
        RunePartyRender.drawShadowed(g, label, center.x - textWidth / 2, textY, color, alpha);
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

        drawBouncingArrowWithLabel(g, start.point, "Start Here!", defaultColorFor("START"));
    }

    /** "Place" arrows over the two candidate tiles (one step ahead, one step behind the local
     * player's own current course position) while a requires_placement item is armed -- see
     * RunePartyPlugin#beginItemPlacement/getItemPlacementCandidates. Local-only: nobody but the
     * player actually placing sees these, since only they can act on the right-click "Place
     * &lt;item&gt;" entry these tiles carry (see RunePartyPlugin#onMenuEntryAdded). */
    private void renderItemPlacementArrows(Graphics2D g)
    {
        if (plugin.getItemPlacementKey() == null) return;
        for (WorldPoint point : plugin.getItemPlacementCandidates())
        {
            drawBouncingArrowWithLabel(g, point, "Place", COLOR_PLACEMENT_ARROW);
        }
    }

    /** "Purchase!" arrow over the Golden Gnome's own current tile while the local player has a
     * roll pending on their own turn -- see RunePartyPlugin#addGoldenGnomePurchaseMenuEntry, the
     * right-click entry this arrow is pointing at. Local-only, same reasoning
     * renderItemPlacementArrows gives: nobody but the current roller can actually act on the menu
     * entry it's advertising. Gated on exactly the same conditions that menu entry itself checks
     * (already-purchased-this-turn, reachability) -- reported: this used to skip both, so the
     * arrow kept advertising a purchase the menu entry would no longer even offer, either because
     * it was already bought this turn or because the gnome had relocated somewhere out of reach.
     * Still doesn't re-check affordability -- same as the menu entry, purchase-golden-gnome is the
     * real authority on that (see that endpoint's own doc); showing the arrow for a purchase that
     * turns out unaffordable just means an attempt from here 409s, same as any other doomed click.
     * Uses the Golden Gnome tile type's own served color, same reasoning renderStartArrow ties its
     * own arrow back to START's own green. */
    private void renderGoldenGnomePurchaseArrow(Graphics2D g)
    {
        if (!plugin.isPendingRoll()) return;
        if (plugin.isGoldenGnomePurchasedThisTurn()) return;
        String moverRsn = plugin.getCurrentTurnRsn();
        if (moverRsn == null || !isLocalPlayer(moverRsn)) return;

        WorldPoint goldenGnomePoint = plugin.findGoldenGnomeTilePoint();
        if (goldenGnomePoint == null) return;

        Integer goldenGnomePathIndex = tileReducer.pathIndexAt(goldenGnomePoint);
        if (goldenGnomePathIndex == null || !plugin.getPendingReachableIndices().contains(goldenGnomePathIndex)) return;

        drawBouncingArrowWithLabel(g, goldenGnomePoint, "Purchase!", defaultColorFor("GOLDEN_GNOME_TILE"));
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
        String local = plugin.getLocalRsn();
        return local != null && local.equalsIgnoreCase(rsn);
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
            renderOutlinedTile(g, pt.point, base, PREVIEW_STROKE);
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

    /** Draws one tile's outline -- no fill -- inset TILE_OUTLINE_INSET_PX inward from the tile's
     * true on-screen boundary with TILE_OUTLINE_CORNER_RADIUS_PX-rounded corners (see
     * roundedInsetPolygon), in {@code color} at full opacity. Perspective.getCanvasTilePoly's own
     * quad is generally NOT axis-aligned -- the camera can be pitched/rotated to any angle -- so
     * this can't just be a RoundRectangle2D the way a screen-space UI element could; the inset/
     * rounding has to work on the polygon's own (possibly skewed) edges directly. */
    private void renderOutlinedTile(Graphics2D g, WorldPoint wp, Color color, Stroke stroke)
    {
        Collection<WorldPoint> localPoints = WorldPoint.toLocalInstance(client.getTopLevelWorldView(), wp);
        for (WorldPoint local : localPoints)
        {
            LocalPoint lp = LocalPoint.fromWorld(client.getTopLevelWorldView(), local);
            if (lp == null) continue;

            Polygon poly = Perspective.getCanvasTilePoly(client, lp);
            if (poly == null) continue;

            g.setColor(color);
            g.setStroke(stroke);
            g.draw(roundedInsetPolygon(poly, TILE_OUTLINE_INSET_PX, TILE_OUTLINE_CORNER_RADIUS_PX));
        }
    }

    /** Builds a rounded-corner outline of {@code poly}, offset inward by {@code insetPx} along
     * each edge's own inward normal -- works for any simple convex polygon in any winding order,
     * not just an axis-aligned rectangle, since {@code poly} here is a perspective-projected tile
     * quad whose on-screen shape depends on the camera's own pitch/rotation.
     * <p>
     * Two passes: first, each edge is pushed inward by {@code insetPx} (the inward direction
     * determined per edge by testing which of its two perpendiculars actually points toward the
     * polygon's centroid, so this works regardless of winding order), and consecutive offset edges
     * are intersected to get each new inset vertex -- standard polygon-offsetting. Second, each
     * inset vertex's corner is rounded off by cutting it back {@code cornerRadiusPx} along both
     * adjoining (inset) edges and joining those two cut points with a quadratic Bezier curve using
     * the sharp vertex itself as the control point -- the standard Java2D technique for rounding an
     * arbitrarily-angled polygon's corners (unlike {@code RoundRectangle2D}, which only handles
     * axis-aligned rectangles). The corner radius is clamped per-vertex to at most half its
     * shorter adjoining inset edge, so two corners' cuts can never overlap on a small on-screen
     * tile. */
    private static Path2D roundedInsetPolygon(Polygon poly, double insetPx, double cornerRadiusPx)
    {
        int n = poly.npoints;
        double[] xs = new double[n];
        double[] ys = new double[n];
        double cx = 0, cy = 0;
        for (int i = 0; i < n; i++)
        {
            xs[i] = poly.xpoints[i];
            ys[i] = poly.ypoints[i];
            cx += xs[i];
            cy += ys[i];
        }
        cx /= n;
        cy /= n;

        // Each offset edge, as a point on the line (lx/ly) plus its own unit direction (ldx/ldy).
        double[] lx = new double[n];
        double[] ly = new double[n];
        double[] ldx = new double[n];
        double[] ldy = new double[n];
        for (int i = 0; i < n; i++)
        {
            int j = (i + 1) % n;
            double ex = xs[j] - xs[i];
            double ey = ys[j] - ys[i];
            double len = Math.hypot(ex, ey);
            if (len < 1e-6) { ex = 1; ey = 0; len = 1; } // degenerate (coincident) vertices -- shouldn't happen for a real tile quad, guarded anyway
            double dx = ex / len, dy = ey / len;

            // Perpendicular to the edge; sign chosen so it points toward the polygon's own
            // centroid, i.e. inward, regardless of the polygon's winding order.
            double nx = -dy, ny = dx;
            double midx = (xs[i] + xs[j]) / 2, midy = (ys[i] + ys[j]) / 2;
            if (nx * (cx - midx) + ny * (cy - midy) < 0) { nx = -nx; ny = -ny; }

            lx[i] = xs[i] + nx * insetPx;
            ly[i] = ys[i] + ny * insetPx;
            ldx[i] = dx;
            ldy[i] = dy;
        }

        // Each inset vertex is where its two adjoining offset edges (as infinite lines) cross.
        double[] ix = new double[n];
        double[] iy = new double[n];
        for (int i = 0; i < n; i++)
        {
            int prev = (i - 1 + n) % n;
            double denom = ldx[prev] * ldy[i] - ldy[prev] * ldx[i];
            if (Math.abs(denom) < 1e-9)
            {
                // Parallel offset edges (shouldn't happen for a real 4-corner tile quad) -- fall
                // back to the original, un-inset vertex rather than producing a garbage point.
                ix[i] = xs[i];
                iy[i] = ys[i];
                continue;
            }
            double t = ((lx[i] - lx[prev]) * ldy[i] - (ly[i] - ly[prev]) * ldx[i]) / denom;
            ix[i] = lx[prev] + ldx[prev] * t;
            iy[i] = ly[prev] + ldy[prev] * t;
        }

        Path2D.Double path = new Path2D.Double();
        for (int i = 0; i < n; i++)
        {
            int prev = (i - 1 + n) % n;
            int next = (i + 1) % n;

            double toPrevLen = Math.hypot(ix[prev] - ix[i], iy[prev] - iy[i]);
            double toNextLen = Math.hypot(ix[next] - ix[i], iy[next] - iy[i]);
            double r = Math.min(cornerRadiusPx, Math.min(toPrevLen, toNextLen) / 2);

            double p1x = ix[i] + (toPrevLen > 1e-6 ? (ix[prev] - ix[i]) / toPrevLen * r : 0);
            double p1y = iy[i] + (toPrevLen > 1e-6 ? (iy[prev] - iy[i]) / toPrevLen * r : 0);
            double p2x = ix[i] + (toNextLen > 1e-6 ? (ix[next] - ix[i]) / toNextLen * r : 0);
            double p2y = iy[i] + (toNextLen > 1e-6 ? (iy[next] - iy[i]) / toNextLen * r : 0);

            if (i == 0) path.moveTo(p1x, p1y); else path.lineTo(p1x, p1y);
            path.quadTo(ix[i], iy[i], p2x, p2y);
        }
        path.closePath();
        return path;
    }

    /** Looks up this tile type's color in the served catalog (see RunePartyPlugin#getTileTypeCatalog,
     * populated once at startup from GET /v1/tile-types) rather than a hardcoded table -- falls
     * back to COLOR_UNKNOWN_TYPE for a type the catalog doesn't have (yet, or ever). */
    private Color defaultColorFor(String tileType)
    {
        ApiClient.TileTypeOut t = plugin.getTileTypeCatalog().get(tileType);
        if (t == null || t.colorHex == null) return COLOR_UNKNOWN_TYPE;
        try { return Color.decode(t.colorHex); }
        catch (NumberFormatException e) { return COLOR_UNKNOWN_TYPE; }
    }

    private Color resolveColor(String hex, String tileType)
    {
        if (hex == null || hex.isBlank()) return defaultColorFor(tileType);
        try { return Color.decode(hex); }
        catch (NumberFormatException e) { return defaultColorFor(tileType); }
    }
}
