package gay.runescape.runeparty.minigames;

import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TileReducer;

import net.runelite.api.Player;
import net.runelite.api.coords.WorldPoint;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Rainbow Rush's own client-side state -- entirely local until the one-shot finish report (see
 * onTick), same "nothing shared with the server mid-round" shape DanceDanceRuneScapePresentation
 * uses. Unlike DDR's fixed-duration round, Rainbow Rush has no normal timer at all: the round only
 * ends once some player's own report lands server-side (first to visit every course tile), with
 * RunePartyPlugin#RAINBOW_RUSH_MAX_DURATION_MS as a server-enforced backup ceiling if nobody ever
 * finishes -- see getEndsAt.
 * <p>
 * visitedPathIndices is tracked per-player, on purpose: unlike Turf Wars' tile ownership (real
 * shared state living in TileReducer, since a claim is board state everyone sees), visiting a tile
 * here is personal progress, and this repo only ever renders the *local* player's own (see
 * TileOverlay#renderRainbowRushTile, the only reader of isVisited). */
public final class RainbowRushPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private final Set<Integer> visitedPathIndices = ConcurrentHashMap.newKeySet();
    private volatile long roundStartAt = 0;
    private volatile boolean submitted = false;

    public RainbowRushPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    /** Called once per game tick from RunePartyPlugin#onGameTick while this mini-game is active.
     * No-op until onRoundBegin has actually stamped roundStartAt -- same reasoning
     * DanceDanceRuneScapePresentation#onTick's own guard gives: the pre-round gather walk to START
     * shouldn't itself count as visiting it. Marks whichever course tile (any TileReducer entry
     * with a pathIndex) the local player is currently standing on as visited, then fires the
     * one-shot finish report -- reusing the existing generic submitMinigameResult endpoint, whose
     * own doc already covers exactly this shape ("the server determines the winner ... from all
     * submitted results") -- the instant every course tile's been covered. Also fires that same
     * one-shot report, unfinished, the moment getEndsAt's own backup ceiling elapses -- same
     * "unconditional submission once the local timer runs out" shape
     * FishingContestPresentation/ClickClickClickPresentation already use, so a round that times out
     * with nobody finishing still leaves every player's own partial progress in the server's result
     * set (score/final-recap included) instead of an empty one.
     * <p>
     * Also no-op for RunePartyPlugin#RAINBOW_RUSH_TRAFFIC_LIGHT_MS past roundStartAt -- the same
     * red/orange/green "get ready" beat AnnouncementOverlay#renderRainbowRushTrafficLight plays,
     * timed off this exact same roundStartAt. Nothing should count as visited while the light's
     * still red/orange, or a fast player standing right next to Start could score a tile before
     * "Begin!" even appears on screen. */
    public void onTick(Player selfPlayer)
    {
        if (roundStartAt == 0 || submitted) return;
        if (System.currentTimeMillis() - roundStartAt < RunePartyPlugin.RAINBOW_RUSH_TRAFFIC_LIGHT_MS) return;

        WorldPoint pos = selfPlayer != null ? selfPlayer.getWorldLocation() : null;
        if (pos != null)
        {
            Integer pathIndex = plugin.getTileReducer().pathIndexAt(pos);
            if (pathIndex != null) visitedPathIndices.add(pathIndex);
        }

        // realTileCount(), not courseLength() -- a host-edited course can have gaps in its own
        // pathIndex sequence (see TileReducer#realTileCount's own doc), and courseLength()'s "one
        // past the highest index" would then demand more tiles than actually exist, making a real
        // finish mathematically impossible.
        int realTileCount = plugin.getTileReducer().realTileCount();
        boolean finished = realTileCount > 0 && visitedPathIndices.size() >= realTileCount;
        boolean timedOut = System.currentTimeMillis() >= getEndsAt();
        if (finished || timedOut)
        {
            submitted = true;
            plugin.submitMinigameResult(visitedPathIndices.size());
        }
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as every other client-local mini-game's own onStarted -- a fresh instance
        // starts with no visited tiles and hasn't submitted yet, regardless of catch-up.
        visitedPathIndices.clear();
        roundStartAt = 0;
        submitted = false;
    }

    @Override
    public void onRoundBegin()
    {
        roundStartAt = System.currentTimeMillis();
    }

    @Override
    public boolean showsFinalScore() { return true; }

    @Override
    public void reset()
    {
        visitedPathIndices.clear();
        roundStartAt = 0;
        submitted = false;
    }

    /** Whether the local player has personally stood on the course tile at {@code pathIndex} yet
     * this round -- see TileOverlay#renderRainbowRushTile, the only consumer. */
    public boolean isVisited(int pathIndex) { return visitedPathIndices.contains(pathIndex); }

    /** The local player's own running count of distinct course tiles visited this round. */
    public int getVisitedCount() { return visitedPathIndices.size(); }

    /** When the server's own backup ceiling kicks in if nobody's finished by then -- 0 if no round
     * is active yet. Not a normal win condition, see this class's own doc. */
    public long getEndsAt() { return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.RAINBOW_RUSH_MAX_DURATION_MS : 0; }

    /** When MINIGAME_ROUND_BEGIN actually landed for this round -- 0 if the round hasn't begun yet.
     * See AnnouncementOverlay#renderRainbowRushTrafficLight, the only consumer. */
    public long getRoundStartAt() { return roundStartAt; }
}
