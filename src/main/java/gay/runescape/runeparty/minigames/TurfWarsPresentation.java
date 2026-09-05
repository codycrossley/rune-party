package gay.runescape.runeparty.minigames;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import gay.runescape.runeparty.net.ApiClient;
import gay.runescape.runeparty.net.Events;
import gay.runescape.runeparty.net.Json;
import gay.runescape.runeparty.RunePartyColor;
import gay.runescape.runeparty.RunePartyPlugin;
import gay.runescape.runeparty.TimedBanner;

import java.awt.Color;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Turf Wars' own client-side state (server-driven team-color assignment; tile ownership itself is
 * never folded here at all, see RunePartyPlugin#getTurfWarsTileCounts, which tallies TileReducer's
 * own already-broadcast tile colors directly -- a claim is just an ordinary tiles_marked update).
 * teamColors is real state, applied catch-up or not: lowercase rsn -> "#RRGGBB" color hex for the
 * round's own once-per-round assignment (two shared colors for an even seated-PLAYER count, one
 * unshared per-player seat color each for an odd one), read by getPlayerColor to know which color
 * a given player was assigned. roundStartAt is the wall-clock moment the round itself began --
 * stamped once off MINIGAME_ROUND_BEGIN, same single-stamp shape CoinRushPresentation's own
 * roundStartAt uses. */
public final class TurfWarsPresentation implements MinigamePresentationFeature
{
    private final RunePartyPlugin plugin;

    private final Map<String, String> teamColors = new ConcurrentHashMap<>(); // lowercase rsn -> "#RRGGBB"
    private volatile long roundStartAt = 0;
    // ---- Turf Wars' own team-assigned reveal (server-driven). Payload is the local player's own
    // color hex, snapshotted at trigger time -- fires once per round, local-player-only (every
    // other client sees its own color's reveal from its own copy of this same event). ----
    private final TimedBanner<String> teamAssignedBanner = new TimedBanner<>();
    // ---- Turf Wars' own end-of-round confetti (see onEnded), independent of CeremonyPresentation's
    // own whole-game confetti (ConfettiOverlay polls both separately). Skips entirely (banner never
    // armed) on a tie -- there's no single winning color to burst in that case. Payload is the
    // winning color itself. ----
    private final TimedBanner<Color> confettiBanner = new TimedBanner<>();

    public TurfWarsPresentation(RunePartyPlugin plugin)
    {
        this.plugin = plugin;
    }

    @Override
    public void apply(ApiClient.EventOut e, boolean catchingUp)
    {
        String type = e.type.toUpperCase(Locale.ROOT);
        if (!Events.MINIGAME_TEAMS_ASSIGNED.equals(type)) return;

        // Real state, applied catch-up or not -- a catching-up client still needs to know its own
        // color the instant it starts rendering anything (see getPlayerColor). Fired once per
        // round, before the board even swaps in. General-purpose: a flat list of (player, color)
        // pairs, not named team buckets -- two players sharing a color are a team, any number of
        // groups of any size (Turf Wars' own even-count 2-team split, or its odd-count
        // free-for-all, one solo "team" per player) falls out of this one shape.
        JsonArray assignments = Json.safeArray(e.payload, "assignments");
        for (int i = 0; i < assignments.size(); i++)
        {
            try
            {
                JsonObject entry = assignments.get(i).getAsJsonObject();
                String rsn = Json.safeStr(entry, "player");
                String color = Json.safeStr(entry, "color");
                if (rsn != null && color != null) teamColors.put(rsn.toLowerCase(Locale.ROOT), color);
            }
            catch (Exception ignored) { /* skip malformed entry */ }
        }
        // Skipped when the local player's own assigned color is identical to their existing seat
        // color -- an odd-numbered round's own free-for-all mode assigns everyone their own
        // already-existing seat color, so nothing about how they're rendered actually changed; the
        // reveal would just be announcing a "new" color that isn't new. An even-numbered round's
        // shared TEAM_A_COLOR/TEAM_B_COLOR pair is never identical to any seat color, so this never
        // suppresses the genuine 2-team reveal.
        if (!catchingUp && localColorDiffersFromSeatColor())
        {
            triggerTeamAssignedBanner();
        }
    }

    @Override
    public void onStarted(boolean catchingUp)
    {
        // Same reasoning as CoinRushPresentation's own onStarted -- teamColors starts fresh too: a
        // new round's assignment hasn't been announced yet.
        teamColors.clear();
        roundStartAt = 0;
    }

    @Override
    public void onRoundBegin()
    {
        roundStartAt = System.currentTimeMillis();
    }

    /** Arms ConfettiOverlay's Turf Wars burst -- called from MinigamePresentation's own
     * handleMinigameEnded, before that method clears its own minigameKey, in whichever color
     * currently holds strictly more tiles than every other color in play (see
     * RunePartyPlugin#getTurfWarsTileCounts, tallied fresh from TileReducer's own live board --
     * there's no dedicated score field, the board's own current colors are the score). Never armed
     * on a tie for the top spot -- whether that's the classic 2-color even-mode tie or an N-way tie
     * among free-for-all solo colors, there's no single winning color to burst in that case.
     * Independent of CeremonyPresentation's own whole-game confetti (a different TimedBanner,
     * polled separately by ConfettiOverlay). */
    @Override
    public void onEnded()
    {
        Map<String, Integer> counts = plugin.getTurfWarsTileCounts();
        String winnerHex = null;
        int winnerCount = 0;
        boolean tied = false;
        for (Map.Entry<String, Integer> entry : counts.entrySet())
        {
            int count = entry.getValue();
            if (count > winnerCount)
            {
                winnerHex = entry.getKey();
                winnerCount = count;
                tied = false;
            }
            else if (count == winnerCount && count > 0)
            {
                tied = true;
            }
        }
        if (winnerHex == null || winnerCount == 0 || tied) return;

        Color winnerColor;
        try { winnerColor = Color.decode(winnerHex); }
        catch (NumberFormatException e) { return; }

        confettiBanner.payload = winnerColor;
        confettiBanner.until = System.currentTimeMillis() + RunePartyPlugin.CONFETTI_DURATION_MS;
    }

    @Override
    public boolean showsFinalScore() { return true; }

    @Override
    public void reset()
    {
        teamAssignedBanner.reset();
        confettiBanner.reset();
        teamColors.clear();
        roundStartAt = 0;
    }

    /** Arms AnnouncementOverlay's team-assigned reveal -- fired once, right when
     * MINIGAME_TEAMS_ASSIGNED lands (well before the board even swaps in), chained via armBanner
     * behind whatever's already reserving turnEffectGateUntil (typically the "MINIGAME!"
     * banner/spinner sequence armed moments earlier by MINIGAME_STARTED) so the reveal never stomps
     * on it. Reads the local player's own color back out of teamColors (already folded in by the
     * caller, immediately above) rather than re-parsing the event payload. */
    private void triggerTeamAssignedBanner()
    {
        plugin.armBanner(teamAssignedBanner, RunePartyPlugin.TEAM_ASSIGNED_BANNER_DURATION_MS, () ->
        {
            String self = plugin.getLocalRsn();
            return self != null ? teamColors.get(self.toLowerCase(Locale.ROOT)) : null;
        }, true);
    }

    /** Whether the local player's own just-assigned color (teamColors, already folded in by
     * apply() immediately above) is actually different from their own existing RunePartyColor seat
     * color -- see apply()'s own doc for why this gates triggerTeamAssignedBanner. False
     * (suppressing the banner) whenever either side can't be resolved at all -- no assigned color
     * yet, or no seat color to compare against -- since there's nothing meaningful to announce
     * either way in that case. */
    private boolean localColorDiffersFromSeatColor()
    {
        String self = plugin.getLocalRsn();
        if (self == null) return false;
        String assigned = teamColors.get(self.toLowerCase(Locale.ROOT));
        if (assigned == null) return false;

        RunePartyColor seat = RunePartyColor.forNumber(plugin.getRosterReducer().getColorNumber(self));
        if (seat == null) return false;
        String seatHex = String.format("#%02X%02X%02X", seat.awt.getRed(), seat.awt.getGreen(), seat.awt.getBlue());
        return !assigned.equalsIgnoreCase(seatHex);
    }

    /** This player's own assigned color hex, or null if teamColors hasn't been populated yet for
     * them (no Turf Wars round active, or MINIGAME_TEAMS_ASSIGNED hasn't landed yet this round) --
     * takes an arbitrary rsn (not just the local player's own) so both AnnouncementOverlay's
     * team-assigned banner (local player only) and PlayerOverlay's own per-player indicator
     * recoloring (every seated player) can share this one lookup. */
    public String getPlayerColor(String rsn)
    {
        return rsn != null ? teamColors.get(rsn.toLowerCase(Locale.ROOT)) : null;
    }

    /** When the round's own fixed-duration clock runs out -- 0 if no round is active yet or the
     * round hasn't actually become playable, same shape CoinRushPresentation#getEndsAt already
     * uses. */
    public long getEndsAt() { return roundStartAt != 0 ? roundStartAt + RunePartyPlugin.TURF_WARS_ROUND_MS : 0; }

    public long getTeamAssignedBannerUntil() { return teamAssignedBanner.until; }
    /** The local player's own color hex, snapshotted when the reveal was armed, or null if no
     * reveal is currently armed/showing. */
    public String getTeamAssignedBannerTeam() { return teamAssignedBanner.payload; }
    public long getConfettiUntil() { return confettiBanner.until; }
    public Color getConfettiColor() { return confettiBanner.payload; }
}
