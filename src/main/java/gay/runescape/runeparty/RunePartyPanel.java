package gay.runescape.runeparty;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import net.runelite.client.ui.*;
import gay.runescape.runeparty.minigames.Minigames;

/** Sidebar UI -- same CardLayout(Connect/In-Game) shape as GnomeballPanel in the sibling
 * gnomeball repo, content adapted to turn order/course-building instead of team assignment/field
 * zones. See RunePartyPlugin for the action methods every button here calls into, and
 * RunePartyPlugin#handleEvent for how server-pushed state flows back into refresh(). */
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
    private final JButton showMapBtn = new JButton("Show Map");

    // Mini-game -- minigameControlSlot holds whichever Minigame's own control panel is active
    // (see gay.runescape.runeparty.minigames.Minigames#get), rebuilt only when activeMinigameKey
    // changes rather than on every refresh() -- see the Minigame#createControlPanel contract.
    private final JPanel minigamePanel = new JPanel();
    private final JLabel minigameInstructionsLabel = new JLabel(" ");
    private final JPanel minigameControlSlot = new JPanel(new BorderLayout());
    private String activeMinigameKey = null;

    // Roster/stats
    private final JPanel rosterTablePanel = new JPanel();

    // Host controls (grouping card -- visible to host only, LOBBY or ACTIVE)
    private final JPanel hostControlsCard = new JPanel();

    // Host course tools (LOBBY only)
    private final JPanel courseToolsPanel = new JPanel();
    private final JComboBox<CoursePreset> presetDropdown = new JComboBox<>();
    private final JButton placeBtn = new JButton("Place");
    private final JButton clearCourseBtn = new JButton("Clear Course");

    // Host game settings (LOBBY only) -- turns-per-player, sent along with Start Game
    private static final int DEFAULT_MAX_ROUNDS = 5;
    private final JSpinner maxRoundsSpinner = new JSpinner(new SpinnerNumberModel(DEFAULT_MAX_ROUNDS, 2, 50, 1));
    private final JPanel gameSettingsRow = new JPanel(new BorderLayout(4, 0));

    // Host lifecycle controls
    private final JButton startGameBtn = new JButton("Start Game");
    private final JButton endGameBtn = new JButton("End Game");

    private final JButton leaveGameBtn = new JButton("Leave Game");

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
        showMapBtn.addActionListener(e -> plugin.showMap());

        buildMinigamePanel();
        minigamePanel.setAlignmentX(LEFT_ALIGNMENT);

        rosterTablePanel.setLayout(new BoxLayout(rosterTablePanel, BoxLayout.Y_AXIS));
        rosterTablePanel.setBackground(ColorScheme.DARK_GRAY_COLOR);
        rosterTablePanel.setAlignmentX(LEFT_ALIGNMENT);

        buildCourseToolsPanel();
        courseToolsPanel.setAlignmentX(LEFT_ALIGNMENT);

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

        card.add(joinCodeRow);
        card.add(Box.createVerticalStrut(8));
        card.add(statusLabel);
        card.add(Box.createVerticalStrut(6));
        card.add(showMapBtn);
        card.add(Box.createVerticalStrut(8));
        card.add(minigamePanel);
        card.add(Box.createVerticalStrut(8));
        card.add(sectionLabel("Players"));
        card.add(rosterTablePanel);
        card.add(Box.createVerticalStrut(8));
        card.add(hostControlsCard);
        card.add(Box.createVerticalStrut(8));
        card.add(leaveGameBtn);
        return card;
    }

    /** Groups everything host-only (course building, start/end game) under one bordered card, same
     * "HOST CONTROLS" grouping GnomeballPanel uses -- so a non-host roster member never sees a wall
     * of buttons that don't apply to them, and a host sees their tools as one clearly-scoped unit. */
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

    private void buildMinigamePanel()
    {
        minigamePanel.setLayout(new BoxLayout(minigamePanel, BoxLayout.Y_AXIS));
        minigamePanel.setBackground(new Color(30, 30, 30));
        minigamePanel.setBorder(new EmptyBorder(6, 6, 6, 6));

        minigameInstructionsLabel.setForeground(Color.WHITE);
        minigameInstructionsLabel.setFont(FontManager.getRunescapeSmallFont());

        minigameControlSlot.setOpaque(false);

        minigamePanel.add(minigameInstructionsLabel);
        minigamePanel.add(Box.createVerticalStrut(4));
        minigamePanel.add(minigameControlSlot);
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

        courseToolsPanel.add(caption);
        courseToolsPanel.add(Box.createVerticalStrut(4));
        courseToolsPanel.add(presetDropdown);
        courseToolsPanel.add(Box.createVerticalStrut(4));
        courseToolsPanel.add(placeBtn);
        courseToolsPanel.add(Box.createVerticalStrut(4));
        courseToolsPanel.add(clearCourseBtn);
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

        // Gated on isMinigamePlayable(), not isMinigameActive() -- while the selection
        // spinner/instructions/ready-check sequence is still playing out in AnnouncementOverlay,
        // this section stays hidden entirely (no panel presence at all), same as the Golden Gnome
        // offer has none of its own either -- see RunePartyPlugin#isMinigamePlayable.
        minigamePanel.setVisible(plugin.isMinigamePlayable());
        if (plugin.isMinigamePlayable())
        {
            minigameInstructionsLabel.setText("<html>" + plugin.getMinigameInstructions() + "</html>");

            String key = plugin.getMinigameKey();
            if (!java.util.Objects.equals(key, activeMinigameKey))
            {
                activeMinigameKey = key;
                minigameControlSlot.removeAll();
                minigameControlSlot.add(Minigames.get(key).createControlPanel(plugin));
                minigameControlSlot.revalidate();
                minigameControlSlot.repaint();
            }
        }
        else
        {
            activeMinigameKey = null; // next activation always rebuilds fresh, even for a repeat mini-game
        }

        hostControlsCard.setVisible(isHost && (phase == GamePhase.LOBBY || phase == GamePhase.ACTIVE));
        courseToolsPanel.setVisible(isHost && phase == GamePhase.LOBBY);
        gameSettingsRow.setVisible(isHost && phase == GamePhase.LOBBY);
        startGameBtn.setVisible(isHost && phase == GamePhase.LOBBY);
        endGameBtn.setVisible(isHost && phase == GamePhase.ACTIVE);

        refreshRoster(plugin.getRosterReducer().snapshot());
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

        JPanel header = new JPanel(new GridLayout(1, 4));
        header.setBackground(new Color(30, 30, 30));
        header.setBorder(new EmptyBorder(3, 6, 3, 6));
        header.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));
        header.add(smallLabel("#"));
        header.add(smallLabel("Player"));
        header.add(smallLabel("Coins"));
        header.add(smallLabel("Golden Gnomes"));
        rosterTablePanel.add(header);

        for (int i = 0; i < entries.size(); i++)
        {
            RosterReducer.RosterEntry entry = entries.get(i);
            JPanel row = new JPanel(new GridLayout(1, 4));
            row.setBackground(i % 2 == 0 ? ROW_EVEN : ROW_ODD);
            row.setBorder(new EmptyBorder(3, 6, 3, 6));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 22));

            boolean onTurn = entry.rsn.equalsIgnoreCase(plugin.getCurrentTurnRsn());
            Color nameColor = onTurn ? COLOR_TURN : (entry.joined ? Color.WHITE : ColorScheme.MEDIUM_GRAY_COLOR);

            RunePartyColor seatColor = entry.role == RunePartyRole.PLAYER ? RunePartyColor.forNumber(entry.number) : null;
            Color numberColor = seatColor != null ? seatColor.awt : Color.WHITE;
            row.add(smallLabel(entry.role == RunePartyRole.PLAYER ? entry.number : "", numberColor));
            JLabel nameLabel = smallLabel(entry.rsn, nameColor);
            row.add(nameLabel);
            row.add(smallLabel(String.valueOf(entry.coins)));
            row.add(smallLabel(String.valueOf(entry.goldenGnomeCount)));

            if (plugin.isHost() && entry.role == RunePartyRole.SPECTATOR && !plugin.isGameFull())
            {
                JPopupMenu popup = buildAddToGamePopup(entry.rsn);
                attachPopup(row, popup);
                attachPopup(nameLabel, popup);
                String hint = "Right-click to add " + entry.rsn + " to the game";
                row.setToolTipText(hint);
                nameLabel.setToolTipText(hint);
            }

            rosterTablePanel.add(row);
        }

        rosterTablePanel.revalidate();
        rosterTablePanel.repaint();
    }

    private JPopupMenu buildAddToGamePopup(String rsn)
    {
        JPopupMenu popup = new JPopupMenu();
        JMenuItem addItem = new JMenuItem("Add to Game");
        addItem.addActionListener(e -> plugin.assignRole(rsn, RunePartyRole.PLAYER));
        popup.add(addItem);
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
                .append(e.coins).append(':').append(e.goldenGnomeCount).append(';');
        }
        return sb.toString();
    }
}
