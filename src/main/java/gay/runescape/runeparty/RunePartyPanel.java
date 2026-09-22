package gay.runescape.runeparty;

import gay.runescape.runeparty.courses.CoursePreset;
import gay.runescape.runeparty.net.ApiClient;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.*;
import gay.runescape.runeparty.items.Item;
import gay.runescape.runeparty.items.Items;
import gay.runescape.runeparty.minigames.Minigame;
import gay.runescape.runeparty.minigames.Minigames;

/** Sidebar UI -- a CardLayout(Connect/In-Game) switching between the pre-join form and the
 * in-game roster/controls. See RunePartyPlugin for the action methods every button here calls
 * into, and RunePartyPlugin#handleEvent for how server-pushed state flows back into refresh(). */
public class RunePartyPanel extends PluginPanel
{
    private static final int BORDER = 8;
    private static final Color ROW_EVEN = new Color(40, 40, 40);
    private static final Color ROW_ODD  = new Color(50, 50, 50);
    private static final Color COLOR_TURN = new Color(255, 210, 0);

    private final RunePartyPlugin plugin;

    private final CardLayout cardLayout = new CardLayout();
    private final JPanel cardPanel = new JPanel(cardLayout);

    // Connect card
    private final JTextField joinCodeField = new JTextField();
    private final JButton joinBtn   = new JButton("Join Game");
    private final JButton createBtn = new JButton("Create New Game");

    // In-game card -- status
    private final JLabel joinCodeValueLabel = new JLabel("—");
    private final JButton copyJoinCodeBtn = new JButton("Copy");
    private final JLabel statusLabel = new JLabel(" ");
    // Label toggles between "Show Map"/"Hide Map" based on plugin.isMapShowing() -- see refresh(),
    // the only place that flips it.
    private final JButton showMapBtn = new JButton("Show Map");
    // Label toggles between "View Board"/"Return to Normal View" based on
    // plugin.isBoardViewActive() -- see refresh(), the only place that flips it.
    private final JButton viewBoardBtn = new JButton("View Board");

    // Item use -- one button per distinct held item, visible only on the local player's own turn
    // (see RunePartyPlugin#isLocalPlayerReadyToUseItem), rebuilt only when the held items actually
    // change rather than on every refresh(). itemsCard is the titled "ITEMS" grouping card
    // wrapping it, same bordered-card look as hostControlsCard's "HOST CONTROLS" -- only itemsCard's
    // own visibility is toggled (see refreshItemUse); itemUsePanel itself just holds the buttons.
    private final JPanel itemsCard = new JPanel();
    private final JPanel itemUsePanel = new JPanel();
    private String lastItemsKey = null;

    // Roster/stats
    private final JPanel rosterTablePanel = new JPanel();

    // Host controls (grouping card -- visible to host only, LOBBY or ACTIVE)
    private final JPanel hostControlsCard = new JPanel();

    // Host course tools (LOBBY only)
    private final JPanel courseToolsPanel = new JPanel();
    private final JComboBox<CoursePreset> presetDropdown = new JComboBox<>();
    private final JButton placeBtn = new JButton("Place");
    private final JButton clearCourseBtn = new JButton("Clear Course");
    private final JButton buildCustomBtn = new JButton("Build Custom Course");

    // Host mini-game spawn placement (LOBBY only) -- unlike courseToolsPanel above, deliberately
    // NOT hidden once the course is a locked Standard Course (see RunePartyPlugin#
    // enterMinigameSpawnPlacementMode's own doc for why this is a different, orthogonal concern
    // from editing the course itself). minigameSpawnStatusLabel/clearMinigameSpawnBtn's own
    // enabled-state track whichever mini-game the dropdown currently has selected, refreshed by
    // refreshMinigameSpawnPanel() below.
    private final JPanel minigameSpawnPanel = new JPanel();
    private final JComboBox<Minigame> minigameSpawnDropdown = new JComboBox<>();
    private final JLabel minigameSpawnStatusLabel = new JLabel(" ");
    private final JButton placeMinigameSpawnBtn = new JButton("Place");
    private final JButton clearMinigameSpawnBtn = new JButton("Clear");

    /** A stand-in Minigame just so the Golden Gnome Awards ceremony can share
     * minigameSpawnDropdown's own JComboBox&lt;Minigame&gt; typing -- see this class's own
     * constructor for why it's added there. drawIcon is never actually called: this entry is
     * never registered in Minigames, so AnnouncementOverlay's real selection wheel (the only
     * other place a WheelEntry's icon is ever drawn) never sees it -- this dropdown's own renderer
     * shows getDisplayName() only. */
    private static final Minigame CEREMONY_ENTRY = new Minigame()
    {
        @Override
        public String getKey() { return RunePartyPlugin.CEREMONY_KEY; }

        @Override
        public String getDisplayName() { return "Ceremony (Golden Gnome Awards)"; }

        @Override
        public void drawIcon(Graphics2D g, int x, int y, int size, float alpha) { }
    };

    // Host game settings (LOBBY only) -- turns-per-player, sent along with Start Game
    private static final int DEFAULT_MAX_ROUNDS = 5;
    private final JSpinner maxRoundsSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_MAX_ROUNDS, 2, 50, 1));
    private final JPanel gameSettingsRow = new JPanel(new BorderLayout(4, 0));

    // Host lifecycle controls
    private final JButton startGameBtn = new JButton("Start Game");
    private final JButton endGameBtn = new JButton("End Game");

    private final JButton leaveGameBtn = new JButton("Leave Game");

    // Tile legend -- a static reference card, always visible underneath everything else in the
    // in-game card (see buildLegendCard/refreshLegend), listing every standard course tile type's
    // own color/name. Rebuilt only once the served catalog's own size actually changes (see
    // legendBuiltForCatalogSize), not on every refresh() -- same "rebuild only when the key
    // changes" restraint itemUsePanel already uses, just keyed on catalog size rather than a
    // content string since the catalog itself never changes shape mid-game.
    private final JPanel legendCard = new JPanel();
    private final JPanel legendRowsPanel = new JPanel();
    private int legendBuiltForCatalogSize = -1;

    private String lastRosterKey = null;

    public RunePartyPanel(RunePartyPlugin plugin)
    {
        super(true);
        setBorder(BorderFactory.createEmptyBorder());
        this.plugin = plugin;
        setLayout(new BorderLayout());
        setBackground(ColorScheme.DARK_GRAY_COLOR);
        add(buildTopPanel(), BorderLayout.NORTH);
        add(buildCardPanel(), BorderLayout.CENTER);

        for (CoursePreset preset : CoursePreset.ALL) presetDropdown.addItem(preset);
        presetDropdown.addActionListener(e -> plugin.selectPreset((CoursePreset) presetDropdown.getSelectedItem()));
        if (presetDropdown.getItemCount() > 0)
        {
            presetDropdown.setSelectedIndex(0);
            plugin.selectPreset((CoursePreset) presetDropdown.getSelectedItem());
        }

        List<Minigame> boardSwappingMinigames = new java.util.ArrayList<>();
        for (Minigame m : Minigames.all())
        {
            if (RunePartyPlugin.BOARD_SWAPPING_MINIGAME_KEYS.contains(m.getKey())) boardSwappingMinigames.add(m);
        }
        // The Golden Gnome Awards ceremony (see server-side ceremony.py) isn't a registered
        // Minigame -- it has no ready-check/countdown/wheel-pick of its own, see that file's own
        // doc -- but it board-swaps an arena the exact same way (ceremony.py's own
        // _ceremony_arena_center reads the identical minigameSpawnPoints/arena_offset keyed by
        // RunePartyPlugin.CEREMONY_KEY), so it belongs in this dropdown too. CEREMONY_ENTRY is
        // added directly here rather than registered in Minigames, so it can never turn up on
        // AnnouncementOverlay's real selection wheel.
        boardSwappingMinigames.add(CEREMONY_ENTRY);
        boardSwappingMinigames.sort(java.util.Comparator.comparing(Minigame::getDisplayName));
        for (Minigame m : boardSwappingMinigames) minigameSpawnDropdown.addItem(m);
        minigameSpawnDropdown.setRenderer((list, value, index, isSelected, cellHasFocus) ->
            new JLabel(value != null ? value.getDisplayName() : ""));
        minigameSpawnDropdown.addActionListener(e -> refreshMinigameSpawnPanel());
        if (minigameSpawnDropdown.getItemCount() > 0) minigameSpawnDropdown.setSelectedIndex(0);

        refresh();
    }

    // -------------------------------------------------------------------------
    // Build
    // -------------------------------------------------------------------------

    private JPanel buildTopPanel()
    {
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        top.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        top.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel title = new JLabel("Rune Party");
        title.setFont(FontManager.getRunescapeBoldFont());
        title.setForeground(ColorScheme.BRAND_ORANGE);
        top.add(title);
        return top;
    }

    private JPanel buildCardPanel()
    {
        cardPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        cardPanel.add(buildConnectCard(), "CONNECT");
        cardPanel.add(buildInGameCard(), "IN_GAME");

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(ColorScheme.DARK_GRAY_COLOR);
        wrapper.add(cardPanel, BorderLayout.NORTH);
        return wrapper;
    }

    private JPanel buildConnectCard()
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel joinLabel = new JLabel("Join Code");
        joinLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        joinLabel.setAlignmentX(LEFT_ALIGNMENT);

        joinCodeField.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        joinCodeField.setAlignmentX(LEFT_ALIGNMENT);

        joinBtn.setAlignmentX(LEFT_ALIGNMENT);
        joinBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        joinBtn.addActionListener(e -> plugin.joinGame(joinCodeField.getText().trim()));

        JSeparator sep = new JSeparator();
        sep.setMaximumSize(new Dimension(Integer.MAX_VALUE, 1));
        sep.setAlignmentX(LEFT_ALIGNMENT);

        createBtn.setAlignmentX(LEFT_ALIGNMENT);
        createBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        createBtn.addActionListener(e -> plugin.createGame());

        card.add(joinLabel);
        card.add(Box.createVerticalStrut(4));
        card.add(joinCodeField);
        card.add(Box.createVerticalStrut(4));
        card.add(joinBtn);
        card.add(Box.createVerticalStrut(10));
        card.add(sep);
        card.add(Box.createVerticalStrut(10));
        card.add(createBtn);
        return card;
    }

    private JPanel buildInGameCard()
    {
        JPanel card = new JPanel();
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(new EmptyBorder(BORDER, BORDER, BORDER, BORDER));
        card.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JPanel joinCodeRow = new JPanel(new BorderLayout());
        joinCodeRow.setBackground(ColorScheme.DARK_GRAY_COLOR);
        joinCodeRow.setAlignmentX(LEFT_ALIGNMENT);
        JLabel joinCodeCaption = new JLabel("Join Code:");
        joinCodeCaption.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        joinCodeValueLabel.setForeground(Color.WHITE);

        JPanel joinCodeValueGroup = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        joinCodeValueGroup.setBackground(ColorScheme.DARK_GRAY_COLOR);
        copyJoinCodeBtn.setMargin(new Insets(0, 6, 0, 6));
        copyJoinCodeBtn.setFont(FontManager.getRunescapeSmallFont());
        copyJoinCodeBtn.addActionListener(e -> copyJoinCodeToClipboard());
        joinCodeValueGroup.add(joinCodeValueLabel);
        joinCodeValueGroup.add(copyJoinCodeBtn);

        joinCodeRow.add(joinCodeCaption, BorderLayout.WEST);
        joinCodeRow.add(joinCodeValueGroup, BorderLayout.EAST);
        joinCodeRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        statusLabel.setAlignmentX(LEFT_ALIGNMENT);
        statusLabel.setForeground(COLOR_TURN);
        statusLabel.setFont(FontManager.getRunescapeSmallFont());

        showMapBtn.setAlignmentX(LEFT_ALIGNMENT);
        showMapBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        showMapBtn.addActionListener(e -> plugin.toggleMap());

        viewBoardBtn.setAlignmentX(LEFT_ALIGNMENT);
        viewBoardBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        viewBoardBtn.addActionListener(e -> plugin.toggleBoardView());

        buildItemsCard();
        itemsCard.setAlignmentX(LEFT_ALIGNMENT);

        rosterTablePanel.setLayout(new BoxLayout(rosterTablePanel, BoxLayout.Y_AXIS));
        rosterTablePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        rosterTablePanel.setAlignmentX(LEFT_ALIGNMENT);

        buildCourseToolsPanel();
        courseToolsPanel.setAlignmentX(LEFT_ALIGNMENT);

        buildMinigameSpawnPanel();
        minigameSpawnPanel.setAlignmentX(LEFT_ALIGNMENT);

        startGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        startGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        startGameBtn.addActionListener(e -> plugin.startGame((Integer) maxRoundsSpinner.getValue()));

        endGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        endGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        endGameBtn.addActionListener(e -> plugin.endGame());

        buildHostControlsCard();
        hostControlsCard.setAlignmentX(LEFT_ALIGNMENT);

        leaveGameBtn.setAlignmentX(LEFT_ALIGNMENT);
        leaveGameBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
        leaveGameBtn.addActionListener(e -> plugin.leaveGame());

        buildLegendCard();
        legendCard.setAlignmentX(LEFT_ALIGNMENT);

        card.add(joinCodeRow);
        card.add(Box.createVerticalStrut(8));
        card.add(statusLabel);
        card.add(Box.createVerticalStrut(6));
        card.add(showMapBtn);
        card.add(Box.createVerticalStrut(6));
        card.add(viewBoardBtn);
        card.add(Box.createVerticalStrut(8));
        card.add(itemsCard);
        card.add(Box.createVerticalStrut(8));
        card.add(sectionLabel("Players"));
        card.add(rosterTablePanel);
        card.add(Box.createVerticalStrut(8));
        card.add(hostControlsCard);
        card.add(Box.createVerticalStrut(8));
        card.add(leaveGameBtn);
        card.add(Box.createVerticalStrut(8));
        card.add(legendCard);
        return card;
    }

    /** Groups everything host-only (course building, start/end game) under one bordered card, so a
     * non-host roster member never sees a wall of buttons that don't apply to them, and a host
     * sees their tools as one clearly-scoped unit. */
    private void buildHostControlsCard()
    {
        hostControlsCard.setLayout(new BoxLayout(hostControlsCard, BoxLayout.Y_AXIS));
        hostControlsCard.setBackground(new Color(34, 30, 26));
        hostControlsCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(90, 70, 40), 1),
            new EmptyBorder(8, 8, 8, 8)));
        hostControlsCard.setVisible(false);

        JLabel title = new JLabel("HOST CONTROLS");
        title.setForeground(ColorScheme.BRAND_ORANGE);
        title.setFont(FontManager.getRunescapeSmallFont());
        title.setAlignmentX(LEFT_ALIGNMENT);

        hostControlsCard.add(title);
        hostControlsCard.add(Box.createVerticalStrut(6));
        hostControlsCard.add(courseToolsPanel);
        hostControlsCard.add(Box.createVerticalStrut(8));
        hostControlsCard.add(minigameSpawnPanel);
        hostControlsCard.add(Box.createVerticalStrut(8));
        hostControlsCard.add(buildGameSettingsRow());
        hostControlsCard.add(Box.createVerticalStrut(4));
        hostControlsCard.add(startGameBtn);
        hostControlsCard.add(endGameBtn);
    }

    /** Turns-per-player, set here and sent along with the Start Game request (see
     * RunePartyPlugin#startGame) -- the game ends once every player has had this many turns and
     * that final round's mini-game has resolved. LOBBY-only, same as the rest of setup -- see
     * refresh(), which toggles this alongside courseToolsPanel/startGameBtn. */
    private JPanel buildGameSettingsRow()
    {
        gameSettingsRow.setBackground(new Color(34, 30, 26));
        gameSettingsRow.setAlignmentX(LEFT_ALIGNMENT);
        gameSettingsRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));

        JLabel label = new JLabel("Turns per player");
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setFont(FontManager.getRunescapeSmallFont());

        maxRoundsSpinner.setMaximumSize(new Dimension(60, 24));

        gameSettingsRow.add(label, BorderLayout.WEST);
        gameSettingsRow.add(maxRoundsSpinner, BorderLayout.EAST);
        return gameSettingsRow;
    }

    /** A titled, bordered reference card listing every standard course tile type's own served
     * color and name -- same "titled card" look hostControlsCard/itemsCard already use, just its
     * own green accent so the three read as visually distinct. Unlike those two, never toggled:
     * this is static reference info relevant for the whole time a player's in a game, not something
     * gated on host/turn/phase. Row content comes from refreshLegend(), called from refresh() --
     * this method only builds the empty shell (title, row container), since the served tile-type
     * catalog usually hasn't landed yet when the panel itself is first constructed. */
    private void buildLegendCard()
    {
        legendCard.setLayout(new BoxLayout(legendCard, BoxLayout.Y_AXIS));
        legendCard.setBackground(new Color(24, 34, 28));
        legendCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(50, 105, 70), 1),
            new EmptyBorder(8, 8, 8, 8)));

        JLabel title = new JLabel("TILE LEGEND");
        title.setForeground(new Color(120, 205, 150));
        title.setFont(FontManager.getRunescapeSmallFont());
        title.setAlignmentX(LEFT_ALIGNMENT);

        legendRowsPanel.setLayout(new BoxLayout(legendRowsPanel, BoxLayout.Y_AXIS));
        legendRowsPanel.setOpaque(false);
        legendRowsPanel.setAlignmentX(LEFT_ALIGNMENT);

        legendCard.add(title);
        legendCard.add(Box.createVerticalStrut(6));
        legendCard.add(legendRowsPanel);
    }

    /** (Re)builds legendRowsPanel from the served tile-type catalog -- one row per standard course
     * tile type, a small color swatch (see TileSwatchIcon) next to its display name. Same
     * isModifier/isMinigameTile exclusion RunePartyMapOverlay#buildLegendRows uses for its own
     * legend, and the same reasoning: Golden Gnome/Coin Trap are decorative overlays with their own
     * bespoke marker elsewhere, not a course tile color worth a swatch, and every mini-game-only
     * type (Arena, Turf Wars, Sandwich Rush, Hot Potato, Who's Your Jaddy, Fishing/Pond, Dance
     * Dance RuneScape, Crab Rave, Brutus Attack, Repeat After Me) only ever exists while that one
     * mini-game's own arena is swapped in -- this card is a reference for the standard course, not
     * whatever's currently playing. Iterates the catalog in its own served order (a LinkedHashMap,
     * see RunePartyPlugin#tileTypeCatalog) rather than re-sorting, so this card's own row order
     * always matches the map overlay's -- no reason for the two legends to disagree. Guarded by
     * legendBuiltForCatalogSize so this only actually rebuilds once, the moment the catalog first
     * lands (it never changes shape again after that). */
    private void refreshLegend()
    {
        Map<String, ApiClient.TileTypeOut> catalog = plugin.getTileTypeCatalog();
        if (catalog.isEmpty() || catalog.size() == legendBuiltForCatalogSize) return;
        legendBuiltForCatalogSize = catalog.size();

        legendRowsPanel.removeAll();
        for (ApiClient.TileTypeOut t : catalog.values())
        {
            if (t.isModifier || t.isMinigameTile) continue;

            JLabel row = new JLabel(t.displayName != null ? t.displayName : t.key);
            row.setIcon(new TileSwatchIcon(legendTileColor(t)));
            row.setIconTextGap(8);
            row.setForeground(Color.WHITE);
            row.setFont(FontManager.getRunescapeSmallFont());
            row.setAlignmentX(LEFT_ALIGNMENT);
            legendRowsPanel.add(row);
            legendRowsPanel.add(Box.createVerticalStrut(4));
        }
        legendRowsPanel.revalidate();
        legendRowsPanel.repaint();
    }

    /** Decodes a served tile type's own colorHex -- falls back to plain yellow on a missing/
     * malformed value, same defensive fallback RunePartyMapOverlay#tileColor/TileOverlay#
     * defaultColorFor already use for an unresolvable catalog color. */
    private static Color legendTileColor(ApiClient.TileTypeOut t)
    {
        if (t.colorHex == null || t.colorHex.isBlank()) return Color.YELLOW;
        try { return Color.decode(t.colorHex); }
        catch (NumberFormatException e) { return Color.YELLOW; }
    }

    /** A small rounded-corner color square, painted directly rather than a bundled image asset --
     * the legend row's own icon (see refreshLegend). */
    private static final class TileSwatchIcon implements Icon
    {
        private static final int SIZE = 12;
        private final Color color;

        TileSwatchIcon(Color color) { this.color = color; }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y)
        {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(color);
            g2.fillRoundRect(x, y, SIZE, SIZE, 4, 4);
            g2.setColor(Color.BLACK);
            g2.drawRoundRect(x, y, SIZE, SIZE, 4, 4);
            g2.dispose();
        }

        @Override public int getIconWidth() { return SIZE; }
        @Override public int getIconHeight() { return SIZE; }
    }

    /** Groups the item-use buttons under one titled, bordered card -- same look as
     * buildHostControlsCard, just its own blue accent instead of the host's orange/brown, so the
     * two grouping cards read as distinct at a glance. Only itemsCard's own visibility is ever
     * toggled (see refreshItemUse) -- itemUsePanel inside it stays permanently visible and just
     * holds whichever buttons are currently built. */
    private void buildItemsCard()
    {
        itemsCard.setLayout(new BoxLayout(itemsCard, BoxLayout.Y_AXIS));
        itemsCard.setBackground(new Color(24, 30, 38));
        itemsCard.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(new Color(40, 75, 115), 1),
            new EmptyBorder(8, 8, 8, 8)));
        itemsCard.setVisible(false);

        JLabel title = new JLabel("ITEMS");
        title.setForeground(new Color(110, 170, 230));
        title.setFont(FontManager.getRunescapeSmallFont());
        title.setAlignmentX(LEFT_ALIGNMENT);

        itemUsePanel.setLayout(new BoxLayout(itemUsePanel, BoxLayout.Y_AXIS));
        itemUsePanel.setAlignmentX(LEFT_ALIGNMENT);

        itemsCard.add(title);
        itemsCard.add(Box.createVerticalStrut(6));
        itemsCard.add(itemUsePanel);
    }

    /** Place mode is entered from here; rotation stays reachable only via the right-click "Rotate
     * Course" entry that appears once placement mode is active (see
     * RunePartyPlugin#addPresetMenuEntries) rather than a duplicate panel button. There's no
     * per-preset Remove tool -- Clear Course (unmarking the whole committed course) is the host's
     * one "start over" action, so a targeted removal flow would just be a second way to do the same
     * thing. */
    private void buildCourseToolsPanel()
    {
        courseToolsPanel.setLayout(new BoxLayout(courseToolsPanel, BoxLayout.Y_AXIS));
        courseToolsPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel caption = sectionLabel("Course");
        presetDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        presetDropdown.setAlignmentX(LEFT_ALIGNMENT);

        placeBtn.setAlignmentX(LEFT_ALIGNMENT);
        placeBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        placeBtn.addActionListener(e -> plugin.enterCoursePlacementMode());

        clearCourseBtn.setAlignmentX(LEFT_ALIGNMENT);
        clearCourseBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        clearCourseBtn.addActionListener(e -> plugin.clearCourse());

        // Free-form alternative to the preset dropdown/placeBtn above -- toggles
        // RunePartyPlugin#customCourseBuildMode, mutually exclusive with preset placement mode
        // (see that field's own doc). Text/enabled-state of both this and the preset controls is
        // kept in sync with whichever mode's actually armed by refreshCourseBuildButton(), called
        // from refresh() -- there's no separate "mid-build" status card the way item
        // placement/targeting get, same restraint the preset flow's own placeBtn already takes
        // (the in-world menu itself, not the panel, carries the rest of the interaction).
        buildCustomBtn.setAlignmentX(LEFT_ALIGNMENT);
        buildCustomBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        buildCustomBtn.addActionListener(e -> {
            if (plugin.isCustomCourseBuildMode()) plugin.exitCustomCourseBuildMode();
            else plugin.enterCustomCourseBuildMode();
        });

        courseToolsPanel.add(caption);
        courseToolsPanel.add(Box.createVerticalStrut(4));
        courseToolsPanel.add(presetDropdown);
        courseToolsPanel.add(Box.createVerticalStrut(4));
        courseToolsPanel.add(placeBtn);
        courseToolsPanel.add(Box.createVerticalStrut(4));
        courseToolsPanel.add(buildCustomBtn);
        courseToolsPanel.add(Box.createVerticalStrut(4));
        courseToolsPanel.add(clearCourseBtn);
    }

    /** Keeps buildCustomBtn's label/the preset controls' enabled-state in sync with whichever
     * course-building mode (if any) is actually armed -- see RunePartyPlugin#customCourseBuildMode/
     * coursePlacementMode's own mutual-exclusion doc. Called from refresh(). */
    private void refreshCourseBuildButton()
    {
        boolean building = plugin.isCustomCourseBuildMode();
        buildCustomBtn.setText(building ? "Stop Building" : "Build Custom Course");
        presetDropdown.setEnabled(!building);
        placeBtn.setEnabled(!building);
    }

    /** Lets the host pin a specific board-swapping mini-game's own arena to an exact world point
     * for the rest of this game -- see RunePartyPlugin#enterMinigameSpawnPlacementMode's own doc
     * for why this stays available even once the course is a locked Standard Course, unlike
     * courseToolsPanel above. Dropdown is restricted to RunePartyPlugin#BOARD_SWAPPING_MINIGAME_KEYS
     * -- placing a spawn point for a mini-game with no arena to swap at all would be dead
     * configuration. placeMinigameSpawnBtn toggles to "Cancel" while this exact mini-game's own
     * placement mode is armed (see refreshMinigameSpawnPanel), same convention buildCustomBtn
     * already uses for its own build-mode toggle. */
    private void buildMinigameSpawnPanel()
    {
        minigameSpawnPanel.setLayout(new BoxLayout(minigameSpawnPanel, BoxLayout.Y_AXIS));
        minigameSpawnPanel.setBackground(ColorScheme.DARK_GRAY_COLOR);

        JLabel caption = sectionLabel("Mini-game Spawn Points");

        minigameSpawnDropdown.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        minigameSpawnDropdown.setAlignmentX(LEFT_ALIGNMENT);

        minigameSpawnStatusLabel.setForeground(Color.LIGHT_GRAY);
        minigameSpawnStatusLabel.setFont(FontManager.getRunescapeSmallFont());
        minigameSpawnStatusLabel.setAlignmentX(LEFT_ALIGNMENT);

        placeMinigameSpawnBtn.setAlignmentX(LEFT_ALIGNMENT);
        placeMinigameSpawnBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        placeMinigameSpawnBtn.addActionListener(e -> {
            Minigame selected = (Minigame) minigameSpawnDropdown.getSelectedItem();
            if (selected == null) return;
            if (selected.getKey().equals(plugin.getMinigameSpawnPlacementKey())) plugin.cancelMinigameSpawnPlacementMode();
            else plugin.enterMinigameSpawnPlacementMode(selected.getKey());
        });

        clearMinigameSpawnBtn.setAlignmentX(LEFT_ALIGNMENT);
        clearMinigameSpawnBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
        clearMinigameSpawnBtn.addActionListener(e -> {
            Minigame selected = (Minigame) minigameSpawnDropdown.getSelectedItem();
            if (selected != null) plugin.clearMinigameSpawnPoint(selected.getKey());
        });

        minigameSpawnPanel.add(caption);
        minigameSpawnPanel.add(Box.createVerticalStrut(4));
        minigameSpawnPanel.add(minigameSpawnDropdown);
        minigameSpawnPanel.add(Box.createVerticalStrut(4));
        minigameSpawnPanel.add(minigameSpawnStatusLabel);
        minigameSpawnPanel.add(Box.createVerticalStrut(4));
        minigameSpawnPanel.add(placeMinigameSpawnBtn);
        minigameSpawnPanel.add(Box.createVerticalStrut(4));
        minigameSpawnPanel.add(clearMinigameSpawnBtn);
    }

    /** Keeps the status line/button labels in sync with whichever mini-game the dropdown currently
     * has selected and whether it already has a host-placed spawn point -- called from refresh()
     * and whenever the dropdown selection itself changes. */
    private void refreshMinigameSpawnPanel()
    {
        Minigame selected = (Minigame) minigameSpawnDropdown.getSelectedItem();
        if (selected == null)
        {
            minigameSpawnStatusLabel.setText(" ");
            placeMinigameSpawnBtn.setEnabled(false);
            clearMinigameSpawnBtn.setEnabled(false);
            return;
        }

        boolean armedForThisOne = selected.getKey().equals(plugin.getMinigameSpawnPlacementKey());
        placeMinigameSpawnBtn.setText(armedForThisOne ? "Cancel" : "Place");
        placeMinigameSpawnBtn.setEnabled(true);

        boolean hasCustomSpot = plugin.getMinigameSpawnPoint(selected.getKey()) != null;
        minigameSpawnStatusLabel.setText(hasCustomSpot ? "Custom spot set" : "Default (course center)");
        clearMinigameSpawnBtn.setEnabled(hasCustomSpot);
    }

    private void copyJoinCodeToClipboard()
    {
        String code = plugin.getJoinCode();
        if (code == null || code.isEmpty()) return;

        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(code), null);

        copyJoinCodeBtn.setText("Copied!");
        Timer resetLabel = new Timer(1200, e -> copyJoinCodeBtn.setText("Copy"));
        resetLabel.setRepeats(false);
        resetLabel.start();
    }

    private static JLabel sectionLabel(String text)
    {
        JLabel label = new JLabel(text);
        label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
        label.setFont(FontManager.getRunescapeSmallFont());
        label.setAlignmentX(LEFT_ALIGNMENT);
        return label;
    }

    // -------------------------------------------------------------------------
    // Refresh
    // -------------------------------------------------------------------------

    public void refresh()
    {
        GamePhase phase = plugin.getPhase();
        boolean isHost = plugin.isHost();

        if (phase == GamePhase.DISCONNECTED)
        {
            cardLayout.show(cardPanel, "CONNECT");
            return;
        }

        cardLayout.show(cardPanel, "IN_GAME");
        String joinCode = plugin.getJoinCode();
        joinCodeValueLabel.setText(joinCode != null ? joinCode : "—");
        copyJoinCodeBtn.setEnabled(joinCode != null);

        statusLabel.setText(statusText(phase));

        showMapBtn.setVisible(phase == GamePhase.LOBBY || phase == GamePhase.ACTIVE || phase == GamePhase.ENDED);
        showMapBtn.setText(plugin.isMapShowing() ? "Hide Map" : "Show Map");

        // Only LOBBY/ACTIVE, matching toggleBoardView's own phase guard -- unlike showMapBtn,
        // not offered in ENDED (toggling would just no-op there). If board view somehow was still
        // active right as the game ended, RunePartyPlugin#resetState un-sticks it automatically
        // the moment the player actually leaves.
        viewBoardBtn.setVisible(phase == GamePhase.LOBBY || phase == GamePhase.ACTIVE);
        viewBoardBtn.setText(plugin.isBoardViewActive() ? "Return to Normal View" : "View Board");

        hostControlsCard.setVisible(isHost && (phase == GamePhase.LOBBY || phase == GamePhase.ACTIVE));
        courseToolsPanel.setVisible(isHost && phase == GamePhase.LOBBY && !plugin.isStandardCourseLocked());
        refreshCourseBuildButton();
        minigameSpawnPanel.setVisible(isHost && phase == GamePhase.LOBBY);
        refreshMinigameSpawnPanel();
        gameSettingsRow.setVisible(isHost && phase == GamePhase.LOBBY);
        startGameBtn.setVisible(isHost && phase == GamePhase.LOBBY);
        endGameBtn.setVisible(isHost && phase == GamePhase.ACTIVE);

        List<RosterReducer.RosterEntry> entries = plugin.getRosterReducer().snapshot();
        refreshItemUse(entries);
        refreshRoster(entries);
        refreshLegend();
    }

    /** Shows one "Use &lt;item&gt; (x&lt;count&gt;)" button per distinct item the local player
     * holds, or a plain "no items yet" line if they hold none -- visible for the whole game
     * (any seated PLAYER, any turn), not just while there's something to click. Buttons themselves
     * are disabled unless it's genuinely the local player's turn to act (isLocalPlayerReadyToUseItem())
     * and they haven't already used one this turn (isItemUsedThisTurn()) -- one item per turn,
     * regardless of how many they still hold. Rebuilt only when the held items or that
     * enabled/disabled state actually change (see lastItemsKey), not on every refresh() call. */
    private void refreshItemUse(List<RosterReducer.RosterEntry> entries)
    {
        // Mid-placement (see RunePartyPlugin#beginItemPlacement) takes over this card entirely --
        // right-clicking the highlighted "Place <item>" tile is the real confirm step (see
        // RunePartyPlugin#onMenuEntryAdded), this is just a status readout plus an escape hatch,
        // same as course placement mode has no panel presence beyond its own "Place"/dropdown
        // controls and relies on the in-world menu for the rest.
        String placementKey = plugin.getItemPlacementKey();
        if (placementKey != null)
        {
            itemsCard.setVisible(true);
            String key = "placing:" + placementKey;
            if (key.equals(lastItemsKey)) return;
            lastItemsKey = key;

            itemUsePanel.removeAll();
            JLabel hint = new JLabel("<html>Right-click the highlighted tile to place your "
                + Items.get(placementKey).getDisplayName() + ", or Cancel below.</html>");
            hint.setAlignmentX(LEFT_ALIGNMENT);
            itemUsePanel.add(hint);
            itemUsePanel.add(Box.createVerticalStrut(6));
            JButton cancelBtn = new JButton("Cancel");
            cancelBtn.setAlignmentX(LEFT_ALIGNMENT);
            cancelBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            cancelBtn.addActionListener(e -> plugin.cancelItemPlacement());
            itemUsePanel.add(cancelBtn);
            itemUsePanel.revalidate();
            itemUsePanel.repaint();
            return;
        }

        // Mid-targeting (see RunePartyPlugin#beginItemTargeting) is the requires_target sibling of
        // the requires_placement card just above -- right-clicking the highlighted player's own
        // "Use <item>" entry (see RunePartyPlugin#onMenuEntryAdded) is the real confirm step, same
        // "status readout plus an escape hatch" restraint that card's own doc explains.
        String targetKey = plugin.getItemTargetKey();
        if (targetKey != null)
        {
            itemsCard.setVisible(true);
            String key = "targeting:" + targetKey;
            if (key.equals(lastItemsKey)) return;
            lastItemsKey = key;

            itemUsePanel.removeAll();
            JLabel hint = new JLabel("<html>Right-click another player and choose \"Use "
                + Items.get(targetKey).getDisplayName() + "\", or Cancel below.</html>");
            hint.setAlignmentX(LEFT_ALIGNMENT);
            itemUsePanel.add(hint);
            itemUsePanel.add(Box.createVerticalStrut(6));
            JButton cancelBtn = new JButton("Cancel");
            cancelBtn.setAlignmentX(LEFT_ALIGNMENT);
            cancelBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            cancelBtn.addActionListener(e -> plugin.cancelItemTargeting());
            itemUsePanel.add(cancelBtn);
            itemUsePanel.revalidate();
            itemUsePanel.repaint();
            return;
        }

        RosterReducer.RosterEntry localEntry = null;
        String localRsn = plugin.getLocalRsn();
        if (localRsn != null)
        {
            for (RosterReducer.RosterEntry entry : entries)
            {
                if (entry.rsn.equalsIgnoreCase(localRsn)) { localEntry = entry; break; }
            }
        }

        // Visible for the whole game -- any seated, joined PLAYER, regardless of whose turn it is
        // or whether they're currently holding anything -- rather than popping in/out as items are
        // gained/spent. A spectator, or anyone before the game's actually ACTIVE, has no items of
        // their own to ever show here.
        boolean isSeatedPlayer = localEntry != null && localEntry.role == RunePartyRole.PLAYER && localEntry.joined;
        boolean showItemsCard = plugin.getPhase() == GamePhase.ACTIVE && isSeatedPlayer;
        itemsCard.setVisible(showItemsCard);
        if (!showItemsCard)
        {
            lastItemsKey = null; // next activation always rebuilds fresh
            return;
        }

        // Conceals one copy of whatever item a live, not-yet-revealed grant just added -- the
        // roster itself already reflects ITEM_GRANTED unconditionally (real state), but the wheel
        // reveal is purely cosmetic, so without this a freshly granted item's own "Use X" button
        // would appear here the instant it lands, well before AnnouncementOverlay's own wheel has
        // actually spun to a stop -- spoiling which item it is. See
        // RunePartyPlugin#isItemSelectionRevealed's own doc.
        Map<String, Integer> displayItems = concealPendingItemGrant(localEntry.items);

        if (displayItems.isEmpty())
        {
            String key = "empty:" + plugin.isItemSelectionRevealed();
            if (key.equals(lastItemsKey)) return;
            lastItemsKey = key;

            itemUsePanel.removeAll();
            JLabel none = new JLabel("No items yet -- land on an Item Space to get one.");
            none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
            none.setFont(FontManager.getRunescapeSmallFont());
            none.setAlignmentX(LEFT_ALIGNMENT);
            itemUsePanel.add(none);
            itemUsePanel.revalidate();
            itemUsePanel.repaint();
            return;
        }

        // Buttons stay visible the whole time now, just disabled outside the "genuinely your turn,
        // haven't already used one" window -- see isLocalPlayerReadyToUseItem/isItemUsedThisTurn --
        // folded into the rebuild key so a turn starting/ending re-enables/disables them without
        // waiting on the held items themselves to change. Deliberately NOT
        // isLocalPlayerReadyToRoll() -- that one also requires standing on the turn's own tracked
        // position, a real requirement for physically rolling but not one the server enforces for
        // items at all (see isLocalPlayerReadyToUseItem's own doc) -- using it here greyed out
        // every item button the instant a player wandered even slightly off that spot. Also folds
        // in isItemSelectionRevealed() directly (not just its effect on displayItems) so the panel
        // still rebuilds the instant a reveal finishes even when that reveal happened to conceal a
        // held count down to the same buildItemsKey string it'll produce once revealed (impossible
        // today since concealment always changes the string, but cheap insurance either way).
        boolean canUseNow = plugin.isLocalPlayerReadyToUseItem() && !plugin.isItemUsedThisTurn();
        String key = "items:" + canUseNow + ":" + plugin.isItemSelectionRevealed() + ":" + buildItemsKey(displayItems);
        if (key.equals(lastItemsKey)) return;
        lastItemsKey = key;

        itemUsePanel.removeAll();
        for (Map.Entry<String, Integer> held : new TreeMap<>(displayItems).entrySet())
        {
            String itemKey = held.getKey();
            Item item = Items.get(itemKey);
            String verb = item.requiresPlacement() ? "Place " : "Use ";
            JButton useBtn = new JButton(verb + item.getDisplayName() + " (x" + held.getValue() + ")");
            useBtn.setAlignmentX(LEFT_ALIGNMENT);
            useBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 28));
            useBtn.setEnabled(canUseNow);
            useBtn.addActionListener(e -> {
                if (item.requiresPlacement()) plugin.beginItemPlacement(itemKey);
                else if (item.requiresTarget()) plugin.beginItemTargeting(itemKey);
                else plugin.useItem(itemKey);
            });
            itemUsePanel.add(useBtn);
            itemUsePanel.add(Box.createVerticalStrut(4));
        }
        itemUsePanel.revalidate();
        itemUsePanel.repaint();
    }

    /** {@code items}, minus one copy of whatever item RunePartyPlugin#isItemSelectionRevealed says
     * is still a pending, unrevealed grant for the local player -- unchanged (the exact same map
     * instance) once that reveal has actually happened, or if there's nothing pending at all. */
    private Map<String, Integer> concealPendingItemGrant(Map<String, Integer> items)
    {
        if (plugin.isItemSelectionRevealed()) return items;
        String pendingKey = plugin.getItemGrantKey();
        Integer count = pendingKey != null ? items.get(pendingKey) : null;
        if (count == null) return items;

        Map<String, Integer> concealed = new TreeMap<>(items);
        if (count <= 1) concealed.remove(pendingKey);
        else concealed.put(pendingKey, count - 1);
        return concealed;
    }

    private static String buildItemsKey(Map<String, Integer> items)
    {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : new TreeMap<>(items).entrySet())
        {
            sb.append(e.getKey()).append(':').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    private String statusText(GamePhase phase)
    {
        switch (phase)
        {
            case LOBBY:
                return "Waiting in lobby...";
            case ACTIVE:
                String turn = plugin.getCurrentTurnRsn();
                if (plugin.isMinigameActive()) return "Mini-game in progress!";
                if (turn == null) return "Everyone gather at the start tile!";
                Integer roll = plugin.getLastDiceRoll();
                return turn + "'s turn" + (roll != null && plugin.isPendingRoll() ? " (rolled " + roll + ")" : "");
            case ENDED:
                return "Game ended";
            default:
                return " ";
        }
    }

    private void refreshRoster(List<RosterReducer.RosterEntry> entries)
    {
        String key = buildRosterKey(entries) + plugin.getPhase() + '|' + plugin.getCurrentTurnRsn();
        if (key.equals(lastRosterKey)) return;
        lastRosterKey = key;

        rosterTablePanel.removeAll();

        JPanel header = new JPanel(new GridLayout(1, 5));
        header.setBackground(new Color(30, 30, 30));
        header.setBorder(new EmptyBorder(3, 6, 3, 6));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        header.add(smallLabel("#"));
        header.add(smallLabel("Player"));
        header.add(smallLabel("Coins"));
        header.add(smallLabel("Golden Gnomes"));
        header.add(smallLabel("Items"));
        rosterTablePanel.add(header);

        for (int i = 0; i < entries.size(); i++)
        {
            RosterReducer.RosterEntry entry = entries.get(i);
            JPanel row = new JPanel(new GridLayout(1, 5));
            row.setBackground(i % 2 == 0 ? ROW_EVEN : ROW_ODD);
            row.setBorder(new EmptyBorder(3, 6, 3, 6));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

            boolean onTurn = entry.rsn.equalsIgnoreCase(plugin.getCurrentTurnRsn());
            Color nameColor = onTurn ? COLOR_TURN : (entry.joined ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);

            RunePartyColor seatColor = entry.role == RunePartyRole.PLAYER ? RunePartyColor.forNumber(entry.colorNumber) : null;
            Color numberColor = seatColor != null ? seatColor.awt : Color.WHITE;
            row.add(smallLabel(entry.role == RunePartyRole.PLAYER ? entry.number : "", numberColor));
            JLabel nameLabel = smallLabel(entry.rsn, nameColor);
            row.add(nameLabel);
            row.add(smallLabel(String.valueOf(entry.coins)));
            row.add(smallLabel(String.valueOf(entry.goldenGnomeCount)));
            int itemCount = entry.items.values().stream().mapToInt(Integer::intValue).sum();
            row.add(smallLabel(String.valueOf(itemCount)));

            if (plugin.isHost() && entry.role == RunePartyRole.SPECTATOR && !plugin.isGameFull())
            {
                JPopupMenu popup = buildAddToGamePopup(entry.rsn);
                attachPopup(row, popup);
                attachPopup(nameLabel, popup);
                String hint = "Right-click to add " + entry.rsn + " to the game";
                row.setToolTipText(hint);
                nameLabel.setToolTipText(hint);
            }
            else if (plugin.isHost() && entry.role == RunePartyRole.PLAYER
                && !entry.rsn.equalsIgnoreCase(plugin.getLocalRsn()))
            {
                JPopupMenu popup = buildRemovePlayerPopup(entry.rsn);
                attachPopup(row, popup);
                attachPopup(nameLabel, popup);
                String hint = "Right-click to remove " + entry.rsn + " from the game";
                row.setToolTipText(hint);
                nameLabel.setToolTipText(hint);
            }

            rosterTablePanel.add(row);
        }

        rosterTablePanel.revalidate();
        rosterTablePanel.repaint();
    }

    /** One submenu entry per currently-available seat color (see RunePartyPlugin#
     * availableSeatColors, shared with addToGameMenuEntry's own world-menu submenu), rather than a
     * single flat "Add to Game" item -- lets the host pick a specific color instead of always
     * following whatever order players happened to be added in. */
    private JPopupMenu buildAddToGamePopup(String rsn)
    {
        JPopupMenu popup = new JPopupMenu();
        JMenu addMenu = new JMenu("Add to Game");
        for (RunePartyColor color : plugin.availableSeatColors())
        {
            JMenuItem colorItem = new JMenuItem(color.displayName);
            colorItem.setForeground(color.awt);
            colorItem.addActionListener(e -> plugin.assignRole(rsn, RunePartyRole.PLAYER, color.seatNumber()));
            addMenu.add(colorItem);
        }
        popup.add(addMenu);
        return popup;
    }

    private JPopupMenu buildRemovePlayerPopup(String rsn)
    {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem removeItem = new JMenuItem("Remove Player");
        removeItem.addActionListener(e -> plugin.removePlayer(rsn));
        popup.add(removeItem);
        return popup;
    }

    private static void attachPopup(JComponent c, JPopupMenu popup)
    {
        c.addMouseListener(new MouseAdapter()
        {
            @Override public void mousePressed(MouseEvent e)  { if (e.isPopupTrigger()) popup.show(c, e.getX(), e.getY()); }
            @Override public void mouseReleased(MouseEvent e) { if (e.isPopupTrigger()) popup.show(c, e.getX(), e.getY()); }
        });
    }

    private static JLabel smallLabel(String text)
    {
        return smallLabel(text, Color.WHITE);
    }

    private static JLabel smallLabel(String text, Color color)
    {
        JLabel label = new JLabel(text);
        label.setForeground(color);
        label.setFont(FontManager.getRunescapeSmallFont());
        return label;
    }

    private static String buildRosterKey(List<RosterReducer.RosterEntry> entries)
    {
        StringBuilder sb = new StringBuilder();
        for (RosterReducer.RosterEntry e : entries)
        {
            sb.append(e.rsn).append(':').append(e.role).append(':').append(e.number).append(':')
                .append(e.online).append(':').append(e.joined).append(':')
                .append(e.coins).append(':').append(e.goldenGnomeCount).append(':')
                .append(buildItemsKey(e.items)).append(';');
        }
        return sb.toString();
    }
}
