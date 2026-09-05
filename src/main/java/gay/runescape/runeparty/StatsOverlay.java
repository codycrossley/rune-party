package gay.runescape.runeparty;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.util.Comparator;
import java.util.List;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.OverlayPriority;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.PanelComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;

/** Persistent Mario-Party-style stats HUD: a "ROUND x/y" line once the game's actually started,
 * then every seated player's coin count and Golden Gnome count, always in turn order, each name in
 * that player's own RunePartyColor with the current turn highlighted on the stats side -- a ranked
 * view lives in AnnouncementOverlay's post-round "Current Standings" recap instead, so this
 * persistent HUD stays stable/scannable rather than reshuffling every time someone's coin total
 * changes. Hidden outright for the entire time any mini-game is active (banner through rewards
 * recap) -- a mini-game gets its own dedicated overlay for whatever it needs to show live (see
 * e.g. CoinRushScoreboardOverlay, TurfWarsScoreOverlay, HotPotatoOverlay, SandwichRushHudOverlay),
 * rather than this persistent HUD being repurposed/hijacked the way it briefly was for Coin Rush.
 * Purely a renderer over RosterReducer/RunePartyPlugin -- all the totals it reads are
 * server-mutated, this class never computes or guesses one itself. */
public class StatsOverlay extends Overlay
{
    private static final Color COLOR_TURN = new Color(255, 210, 0);
    private static final Color COLOR_NORMAL = Color.WHITE;
    private static final Color COLOR_NAME_FALLBACK = Color.LIGHT_GRAY; // used only if a seat color can't be resolved

    // PanelComponent's own default width is too narrow for "PlayerName" plus "123 coins, 8 GG" on
    // one line, which was wrapping to a 2nd line.
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

        // Hidden outright for as long as any mini-game is active -- see this class's own doc for
        // why (a mini-game's own dedicated overlay shows whatever it needs to instead).
        if (plugin.isMinigameActive()) return null;

        // seatedPlayers() excludes both spectators and a host-added PLAYER who hasn't run the join
        // flow themselves yet, keeping this HUD to players actually in the game right now.
        List<RosterReducer.RosterEntry> players = plugin.getRosterReducer().seatedPlayers();
        if (players.isEmpty()) return null;

        // Turn order, not ranked -- a ranked view lives in AnnouncementOverlay's post-round
        // "Current Standings" recap instead, so this persistent HUD reads left-to-right the same
        // way turn order actually plays out at the table.
        players.sort(Comparator.comparing((RosterReducer.RosterEntry e) -> e.number));

        panelComponent.getChildren().clear();
        panelComponent.getChildren().add(TitleComponent.builder()
            .text("Rune Party")
            .color(Color.WHITE)
            .build());

        // 0 until GAME_STARTED actually lands -- LOBBY has no round to show yet, so this line
        // doesn't appear until there's something real to say.
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
            // isMinigameActive() is already ruled out above, so onTurn only ever needs the plain
            // "is it genuinely their turn" check here.
            boolean onTurn = phase == GamePhase.ACTIVE && entry.rsn.equalsIgnoreCase(currentTurn);

            // Name always reads in the player's own seat color -- whose turn it is is shown on the
            // stats side instead, so the two pieces of information never fight for the same color.
            RunePartyColor seatColor = RunePartyColor.forNumber(entry.colorNumber);
            Color nameColor = seatColor != null ? seatColor.awt : COLOR_NAME_FALLBACK;
            Color statsColor = onTurn ? COLOR_TURN : COLOR_NORMAL;

            panelComponent.getChildren().add(LineComponent.builder()
                .left(entry.rsn)
                .leftColor(nameColor)
                .right(entry.coins + " coins, " + entry.goldenGnomeCount + " GG")
                .rightColor(statsColor)
                .build());
        }

        return panelComponent.render(g);
    }
}
