package gay.runescape.runeparty;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Persistent Mario-Party-style stats HUD: a "ROUND x/y" line once the game's actually started
 * (see RunePartyPlugin#getCurrentRound/getMaxRounds), then every seated (role PLAYER *and*
 * actually joined -- see the roster filter below, which excludes both spectators and a host-added
 * PLAYER who hasn't run the join flow yet) player's coin count and Golden Gnome count, always in
 * turn order (see the roster's own "number" field), each name in that player's own RunePartyColor
 * with the current turn highlighted on the stats side -- a ranked view lives in
 * AnnouncementOverlay's post-round "Current Standings" recap instead (see
 * renderRoundCompleteBanner), so this persistent HUD stays stable/scannable rather than reshuffling
 * every time someone's coin total changes. Same fixed-corner overlay pattern Gnomeball's TimerOverlay uses
 * (TOP_LEFT/ABOVE_WIDGETS), but built with PanelComponent/LineComponent since this is a multi-row
 * table rather than a single flashy clock line. Purely a renderer over RosterReducer/
 * RunePartyPlugin -- all the coin/Golden Gnome/round totals it reads are server-mutated (see
 * ApiClient's roll-dice/confirm-arrival/purchase-golden-gnome/submit-minigame-result docs), this
 * class never computes or guesses a total itself. */
public class StatsOverlay extends Overlay
{
    private static final Color COLOR_TURN = new Color(255, 210, 0);
    private static final Color COLOR_NORMAL = Color.WHITE;
    private static final Color COLOR_NAME_FALLBACK = Color.LIGHT_GRAY; // used only if a seat color can't be resolved

    // PanelComponent's own default (ComponentConstants.STANDARD_WIDTH) is only 129px -- too narrow
    // for "PlayerName" plus "123 coins, 8 GN" on one line, which is what was wrapping to a 2nd line.
    private static final int PANEL_WIDTH = 220;

    private final RunePartyConfig config;
    private final RunePartyPlugin plugin;
    private final PanelComponent panelComponent = new PanelComponent();

    public StatsOverlay(RunePartyConfig config, RunePartyPlugin plugin)
    {
        this.config = config;
        this.plugin = plugin;

        panelComponent.setPreferredSize(new Dimension(PANEL_WIDTH, 0));

        setPosition(OverlayPosition.TOP_LEFT);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
        setPriority(OverlayPriority.MED);
    }

    @Override
    public Dimension render(Graphics2D g)
    {
        if (!config.showStatsOverlay()) return null;
        GamePhase phase = plugin.getPhase();
        if (phase != GamePhase.LOBBY && phase != GamePhase.ACTIVE && phase != GamePhase.ENDED) return null;

        List<RosterReducer.RosterEntry> players = new ArrayList<>();
        for (RosterReducer.RosterEntry entry : plugin.getRosterReducer().snapshot())
        {
            // role==PLAYER alone isn't enough -- a host can add someone via assignRole before
            // they've ever run the join flow themselves (see RunePartyPlugin#addToGameMenuEntry),
            // which leaves them "joined": false until they do. Excluding those keeps this HUD to
            // players who are actually in the game right now, not just seated.
            if (entry.role == RunePartyRole.PLAYER && entry.joined) players.add(entry);
        }
        if (players.isEmpty()) return null;

        // Turn order, not ranked -- a ranked view (highest-coins-first, Golden Gnomes as the
        // tiebreak) now lives in AnnouncementOverlay's post-round "Current Standings" recap
        // instead (see renderRoundCompleteBanner), so this persistent HUD reads left-to-right the
        // same way turn order actually plays out at the table.
        players.sort(Comparator.comparing((RosterReducer.RosterEntry e) -> e.number));

        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("Rune Party")
            .color(Color.WHITE)
            .build());

        // 0 until GAME_STARTED actually lands (see RunePartyPlugin#getMaxRounds) -- LOBBY has no
        // round to show yet, so this line just doesn't appear until there's something real to say.
        int maxRounds = plugin.getMaxRounds();
        if (maxRounds > 0)
        {
            panelComponent.getChildren().add(LineComponent.builder()
                .left("ROUND " + plugin.getCurrentRound() + "/" + maxRounds)
                .leftColor(COLOR_TURN)
                .build());
        }

        String currentTurn = plugin.getCurrentTurnRsn();
        for (RosterReducer.RosterEntry entry : players)
        {
            // A mini-game isn't anyone's "turn" -- everyone submits a result independently -- so
            // suppress the turn highlight entirely while one's active rather than leaving the last
            // roller looking like they're still up.
            boolean onTurn = phase == GamePhase.ACTIVE && !plugin.isMinigameActive() && entry.rsn.equalsIgnoreCase(currentTurn);

            // Name always reads in the player's own seat color (see PlayerOverlay/RunePartyPanel,
            // which color-code the same player identically) -- whose turn it is is shown on the
            // stats side instead, so the two pieces of information never fight for the same color.
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.number);
            Color nameColor = seatColor != null ? seatColor.awt : COLOR_NAME_FALLBACK;
            Color statsColor = onTurn ? COLOR_TURN : COLOR_NORMAL;

            panelComponent.getChildren().add(LineComponent.builder()
                .left(entry.rsn)
                .leftColor(nameColor)
                .right(entry.coins + " coins, " + entry.goldenGnomeCount + " GN")
                .rightColor(statsColor)
                .build());
        }

        return panelComponent.render(g);
    }
}
