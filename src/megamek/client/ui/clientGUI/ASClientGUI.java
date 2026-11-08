/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.client.ui.clientGUI;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JEditorPane;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

import megamek.client.ASClient;
import megamek.client.event.BoardViewEvent;
import megamek.client.event.BoardViewListenerAdapter;
import megamek.client.ui.clientGUI.boardview.BoardView;
import megamek.client.ui.clientGUI.boardview.sprite.MovementEnvelopeSprite;
import megamek.client.ui.clientGUI.boardview.sprite.Sprite;
import megamek.client.ui.clientGUI.boardview.sprite.alphaStrike.ASC3LinkSprite;
import megamek.client.ui.clientGUI.boardview.sprite.alphaStrike.ASEcmSprite;
import megamek.client.ui.clientGUI.boardview.sprite.alphaStrike.ASUnitSprite;
import megamek.client.ui.clientGUI.boardview.toolTip.BoardViewTooltipProvider;
import megamek.client.ui.panels.alphaStrike.ASCardPanel;
import megamek.client.ui.widget.MegaMekButton;
import megamek.common.Player;
import megamek.common.alphaStrike.ASGame;
import megamek.common.alphaStrike.play.ASBoard;
import megamek.common.alphaStrike.play.ASEngine;
import megamek.common.alphaStrike.play.ASEngine.AttackInfo;
import megamek.common.alphaStrike.play.ASEngine.MoveOption;
import megamek.common.alphaStrike.play.ASRules;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.board.Coords;
import megamek.common.enums.GamePhase;
import megamek.common.event.board.GameBoardNewEvent;
import megamek.client.ui.util.MegaMekController;
import megamek.common.event.GameListenerAdapter;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.event.UnitChangedGameEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.game.Game;
import megamek.logging.MMLogger;

/**
 * The game window for an Alpha Strike game: MegaMek's own board view (map, hexes, zoom, tooltips) with Alpha Strike
 * unit counters, the selected unit's Alpha Strike card on the right with a battle log, and a button bar at the bottom
 * for the current phase. Built like the SBF client GUI.
 *
 * Movement: click one of your units (or "Next unit"); the hexes it can reach light up (sprint and jump in their own
 * colours). Click a hex, turn with the arrow buttons, then "Move". Combat: pick your unit, click a lit-up enemy (the
 * number is what you need on 2d6), add overheat if you like, then "Fire" - or "Hold fire".
 */
public class ASClientGUI extends AbstractClientGUI {

    private static final MMLogger logger = MMLogger.create(ASClientGUI.class);

    private final ASClient client;
    private final Game bvGame = new Game();
    private BoardView bv;
    private final List<Sprite> currentSprites = new ArrayList<>();
    private final JPanel clientGuiPanel = new JPanel(new BorderLayout());
    private final ASCardPanel cardPanel = new ASCardPanel();
    private final JLabel unitStatus = new JLabel(" ");
    private final JEditorPane log = new JEditorPane("text/html", "");
    private final JLabel statusBar = new JLabel(" ");
    private final JPanel buttonBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
    protected final CommonMenuBar menuBar = CommonMenuBar.getMenuBarForGame();

    // what the player is doing right now
    private int selectedUnit = -1;
    /** A unit picked just to look at (its card and a cyan outline on the map); -1 = show the selected unit. */
    private int viewedUnit = -1;
    private String shownCardKey = "";
    private ASUnitListPanel unitList;
    private final JEditorPane chatView = new JEditorPane("text/html", "");
    private final javax.swing.JTextField chatInput = new javax.swing.JTextField();
    private final List<String> chatLines = new ArrayList<>();
    private Coords plannedDest;
    private int plannedMode = -1;
    private int plannedFacing;
    private boolean jumpMode;
    private int targetId = -1;
    private int rearTargetId = -1;      // target for the REAR weapons (-1 = none)
    private boolean physical;
    private int overheat;
    private String targetNote;
    // map overlays (remembered between games)
    private static final java.util.prefs.Preferences PREFS =
          java.util.prefs.Preferences.userNodeForPackage(ASClientGUI.class);
    private final javax.swing.JCheckBox showEcm = new javax.swing.JCheckBox("ECM areas",
          PREFS.getBoolean("as.showEcm", true));
    private final javax.swing.JCheckBox showC3 = new javax.swing.JCheckBox("C3 links",
          PREFS.getBoolean("as.showC3", true));          // why the clicked enemy can't be attacked (Combat Phase), or null

    public ASClientGUI(ASClient client, MegaMekController controller) {
        super(client);
        this.client = client;
    }

    @Override
    public ASClient getClient() {
        return client;
    }

    private ASGame game() {
        return client.getGame();
    }

    private ASEngine engine() {
        return new ASEngine(game(), () -> 1);
    }

    // --- window ------------------------------------------------------------------------------------

    @Override
    public void initialize() {
        super.initialize();
        frame.setTitle(client.getName() + " - MegaMek Alpha Strike");
        frame.setJMenuBar(menuBar);
        frame.getContentPane().setLayout(new BorderLayout());
        frame.getContentPane().add(clientGuiPanel, BorderLayout.CENTER);
        try {
            bvGame.setBoard(0, game().getBoard(0));
            bv = new BoardView(bvGame, MegaMekGUI.getKeyDispatcher(), null, 0);
            bv.setTooltipProvider(new Tooltips());
            boardViews.put(0, bv);
            boardViewsContainer.updateMapTabs();
            bv.addBoardViewListener(new Clicks());
        } catch (Exception ex) {
            logger.fatal(ex, "Could not create the board view");
            die();
            return;
        }
        // right side: card, unit status, battle log
        JPanel side = new JPanel(new BorderLayout());
        side.setPreferredSize(new Dimension(420, 400));
        cardPanel.setScale(0.38f);
        JPanel top = new JPanel();
        top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        // The card sits in a fixed-size holder so showing / changing / hiding it never re-lays out the window
        // (a layout pass under the map left paint artifacts on the board until you zoomed).
        JPanel cardHolder = new JPanel(new BorderLayout());
        Dimension cardSize = new Dimension(Math.round(1050 * 0.38f) + 4, Math.round(750 * 0.38f) + 4);
        cardHolder.setPreferredSize(cardSize);
        cardHolder.setMinimumSize(cardSize);
        cardHolder.setMaximumSize(cardSize);
        cardHolder.add(cardPanel, BorderLayout.CENTER);
        cardPanel.setVisible(false);
        top.add(cardHolder);
        unitStatus.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        top.add(unitStatus);
        side.add(top, BorderLayout.NORTH);
        log.setEditable(false);
        chatView.setEditable(false);
        JPanel chatPanel = new JPanel(new BorderLayout());
        chatPanel.setBorder(BorderFactory.createTitledBorder("Chat"));
        chatPanel.add(new JScrollPane(chatView), BorderLayout.CENTER);
        chatPanel.add(chatInput, BorderLayout.SOUTH);
        chatInput.setToolTipText("Type and press Enter to chat");
        chatInput.addActionListener(e -> {
            String text = chatInput.getText().trim();
            if (!text.isEmpty()) {
                client.sendChat(text);
                chatInput.setText("");
            }
        });
        javax.swing.JSplitPane logAndChat = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT,
              new JScrollPane(log), chatPanel);
        logAndChat.setResizeWeight(0.65);
        side.add(logAndChat, BorderLayout.CENTER);
        side.setMinimumSize(new Dimension(420, 200));
        Component boardPanel = boardViewsContainer.getPanel();
        boardPanel.setMinimumSize(new Dimension(200, 200));
        boardPanel.setPreferredSize(new Dimension(900, 700));
        clientGuiPanel.add(boardPanel, BorderLayout.CENTER);
        clientGuiPanel.add(side, BorderLayout.EAST);
        unitList = new ASUnitListPanel(this::listClicked);
        clientGuiPanel.add(unitList, BorderLayout.WEST);
        // bottom: status + buttons
        JPanel bottom = new JPanel(new BorderLayout());
        statusBar.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        statusBar.setFont(statusBar.getFont().deriveFont(Font.BOLD, 14f));
        bottom.add(statusBar, BorderLayout.WEST);
        bottom.add(buttonBar, BorderLayout.EAST);
        clientGuiPanel.add(bottom, BorderLayout.SOUTH);
        // top: overlay switches
        JPanel overlays = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 8, 0));
        overlays.add(new javax.swing.JLabel("Show on map:"));
        for (javax.swing.JCheckBox box : new javax.swing.JCheckBox[] { showEcm, showC3 }) {
            box.setFocusable(false);
            box.addActionListener(e -> {
                PREFS.putBoolean("as.showEcm", showEcm.isSelected());
                PREFS.putBoolean("as.showC3", showC3.isSelected());
                refresh();
            });
            overlays.add(box);
        }
        showEcm.setToolTipText("Halo around units with ECM / Angel ECM (dashed) / light ECM: their 12\" (6 hex) "
              + "jamming radius");
        showC3.setToolTipText("Lines between units of a C3 network: solid = linked, red dashed = cut off");
        clientGuiPanel.add(overlays, BorderLayout.NORTH);

        game().addGameListener(new GameListenerAdapter() {
            @Override
            public void gamePhaseChange(GamePhaseChangeEvent e) {
                SwingUtilities.invokeLater(() -> {
                    menuBar.setPhase(e.getNewPhase());
                    resetPlan();
                    refresh();
                });
            }

            @Override
            public void gameTurnChange(GameTurnChangeEvent e) {
                SwingUtilities.invokeLater(() -> {
                    resetPlan();
                    autoSelect();
                    if (myTurn()) {
                        viewedUnit = -1;
                    }
                    refresh();
                    ASUnit sel = selected();
                    if (myTurn() && sel != null && sel.position != null && bv != null) {
                        bv.centerOnHex(sel.position);   // bring the unit whose turn it is into view
                    }
                });
            }

            @Override
            public void gamePlayerChat(megamek.common.event.player.GamePlayerChatEvent e) {
                SwingUtilities.invokeLater(() -> addChat(e.getMessage()));
            }

            @Override
            public void gameBoardNew(GameBoardNewEvent e) {
                SwingUtilities.invokeLater(() -> {
                    bvGame.setBoard(0, e.getNewBoard());
                    // MegaMek only resizes the map's scroll area when you zoom: do it now, or the map is stuck at
                    // the window's size (can't scroll, and old pictures stay on screen until you zoom)
                    bv.updateBoard();
                    bv.getPanel().revalidate();
                    bv.getPanel().repaint();
                    refresh();
                });
            }

            @Override
            public void gameEntityChange(GameEntityChangeEvent e) {
                SwingUtilities.invokeLater(ASClientGUI.this::refresh);
            }

            @Override
            public void gameUnitChange(megamek.common.event.GameEvent e) {
                SwingUtilities.invokeLater(ASClientGUI.this::refresh);
            }
        });
        client.addReportListener(() -> SwingUtilities.invokeLater(this::updateLog));
        frame.setVisible(true);
        refresh();
    }

    @Override
    public JComponent turnTimerComponent() {
        return menuBar;
    }

    @Override
    public void setChatBoxActive(boolean active) {
    }

    @Override
    public void clearChatBox() {
    }

    @Override
    protected boolean saveGame() {
        return true;
    }

    // --- state helpers -----------------------------------------------------------------------------

    private boolean myTurn() {
        return client.isMyTurn();
    }

    private ASUnit selected() {
        return selectedUnit < 0 ? null : game().getUnit(selectedUnit);
    }

    private boolean iAmDone() {
        Player me = client.getLocalPlayer();
        return me == null || me.isDone();
    }

    /** Deployment Phase: one of my units that I may still move around. */
    private boolean canPlace(ASUnit u) {
        return u != null && game().getPhase().isDeployment() && !iAmDone() && u.alive()
              && u.getOwnerId() == client.getLocalPlayerNumber() && u.position != null;
    }

    private void placeAt(ASUnit u, Coords c, int facing) {
        client.moveUnit(u.getId(), c.getX(), c.getY(), ASUnit.STAND, Math.floorMod(facing, 6));
    }

    private boolean isEligible(ASUnit u) {
        return u != null && myTurn() && game().getTurn() != null && game().getTurn().isValidEntity(u, game());
    }

    private void resetPlan() {
        plannedDest = null;
        plannedMode = -1;
        jumpMode = false;
        targetId = -1;
        rearTargetId = -1;
        physical = false;
        overheat = 0;
        targetNote = null;
    }

    private void autoSelect() {
        if (!isEligible(selected())) {
            selectedUnit = -1;
            List<ASUnit> eligible = game().eligibleUnits(client.getLocalPlayerNumber());
            if (!eligible.isEmpty()) {
                selectedUnit = eligible.get(0).getId();
            }
        }
    }

    private void selectNext() {
        List<ASUnit> eligible = game().eligibleUnits(client.getLocalPlayerNumber());
        if (eligible.isEmpty()) {
            return;
        }
        int idx = 0;
        for (int i = 0; i < eligible.size(); i++) {
            if (eligible.get(i).getId() == selectedUnit) {
                idx = (i + 1) % eligible.size();
            }
        }
        selectedUnit = eligible.get(idx).getId();
        resetPlan();
        refresh();
        if (eligible.get(idx).position != null) {
            bv.centerOnHex(eligible.get(idx).position);
        }
    }

    private String playerName(int playerId) {
        Player p = game().getPlayer(playerId);
        return p != null ? p.getName() : "?";
    }

    // --- drawing ----------------------------------------------------------------------------------

    private void refresh() {
        if (bv == null) {
            return;
        }
        if (game().getPhase().usesTurns() && myTurn() && selected() == null) {
            autoSelect();
        }
        updateSprites();
        updateSidePanel();
        updateButtons();
        if (unitList != null) {
            unitList.update(game(), client.getLocalPlayerNumber(), viewedUnit >= 0 ? viewedUnit : selectedUnit);
        }
    }

    private void addChat(String message) {
        if (message == null) {
            return;
        }
        String safe = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        String line;
        if (safe.startsWith("***")) {
            line = "<span style='color:#909090'>" + safe + "</span>";
        } else {
            int colon = safe.indexOf(':');
            line = colon > 0 ? "<b>" + safe.substring(0, colon) + ":</b>" + safe.substring(colon + 1) : safe;
        }
        chatLines.add(line);
        while (chatLines.size() > 200) {
            chatLines.remove(0);
        }
        chatView.setText("<html><body style='font-family:sans-serif;font-size:10px'>" + String.join("<br>", chatLines)
              + "</body></html>");
        chatView.setCaretPosition(chatView.getDocument().getLength());
    }

    private void updateSprites() {
        bv.removeSprites(currentSprites);
        currentSprites.clear();
        ASUnit sel = selected();
        Map<Integer, String> targetTexts = new HashMap<>();
        GamePhase phase = game().getPhase();
        if (sel != null && phase.isFiring() && isEligible(sel)) {
            for (AttackInfo info : engine().attackOptions(sel)) {
                String text = info.tn() + "+" + (info.physical() ? "P" : "") + (info.arc() == ASEngine.ARC_TURRET ? "T" : "");
                targetTexts.merge(info.target(), text, (a, b) -> a + " " + b);
            }
            for (AttackInfo info : engine().rearOptions(sel)) {
                targetTexts.merge(info.target(), "R" + info.tn() + "+", (a, b) -> a + " " + b);
            }
        }
        // ECM halos and C3 links
        ASEngine overlayEngine = engine();
        if (showEcm.isSelected()) {
            ASBoard eb = game().asBoard();
            for (ASEngine.EcmBubble e : overlayEngine.ecmBubbles()) {
                java.util.Set<Coords> area = new java.util.HashSet<>();
                for (int i = 0; i < eb.n; i++) {
                    if (eb.distance(i, e.center()) <= e.radius()) {
                        area.add(eb.coords(i));
                    }
                }
                Player owner = game().getPlayer(e.unit().getOwnerId());
                Color c = owner != null ? owner.getColour().getColour() : Color.GRAY;
                currentSprites.add(new ASEcmSprite(bv, area, c, e.angel()));
            }
        }
        if (showC3.isSelected()) {
            for (ASEngine.C3Link link : overlayEngine.c3Links()) {
                Player owner = game().getPlayer(link.a().getOwnerId());
                Color c = owner != null ? owner.getColour().getColour().brighter() : Color.LIGHT_GRAY;
                currentSprites.add(new ASC3LinkSprite(bv, link.a().position, link.b().position, c, link.working()));
            }
        }
        // deployment strip
        if (phase.isDeployment() && !iAmDone()) {
            int side = game().sideOfPlayer(client.getLocalPlayerNumber());
            ASBoard b = game().asBoard();
            ASEngine eng = engine();
            java.util.Set<Coords> zone = new java.util.HashSet<>();
            for (int i = 0; i < b.n; i++) {
                if (eng.canDeployAt(side, i)) {
                    zone.add(b.coords(i));
                }
            }
            for (Coords c : zone) {
                int edges = 0;
                for (int dir = 0; dir < 6; dir++) {
                    if (!zone.contains(c.translated(dir))) {
                        edges += 1 << dir;
                    }
                }
                currentSprites.add(new MovementEnvelopeSprite(bv, GUIP.getMoveDefaultColor(), c, edges));
            }
        }
        // movement envelope
        if (sel != null && phase.isMovement() && isEligible(sel)) {
            Map<Coords, Color> colors = new HashMap<>();
            for (MoveOption m : engine().legalMoves(sel)) {
                boolean isJump = m.mode() == ASUnit.JUMP;
                if (isJump != jumpMode && m.mode() != ASUnit.STAND) {
                    continue;
                }
                Coords c = game().asBoard().coords(m.dest());
                Color color = switch (m.mode()) {
                    case ASUnit.SPRINT -> GUIP.getMoveSprintColor();
                    case ASUnit.JUMP -> GUIP.getMoveJumpColor();
                    case ASUnit.STAND -> GUIP.getMoveDefaultColor();
                    default -> GUIP.getMoveDefaultColor();
                };
                colors.merge(c, color, (a, b) -> a.equals(GUIP.getMoveDefaultColor()) ? a : b);
            }
            for (Map.Entry<Coords, Color> e : colors.entrySet()) {
                int edges = 0;
                for (int dir = 0; dir < 6; dir++) {
                    Color adj = colors.get(e.getKey().translated(dir));
                    if (!e.getValue().equals(adj)) {
                        edges += 1 << dir;
                    }
                }
                currentSprites.add(new MovementEnvelopeSprite(bv, e.getValue(), e.getKey(), edges));
            }
        }
        for (ASUnit u : game().getUnits()) {
            if (u.position == null) {
                continue;
            }
            Player owner = game().getPlayer(u.getOwnerId());
            boolean planned = (u.getId() == selectedUnit) && plannedDest != null;
            ASUnitSprite s = new ASUnitSprite(bv, u, owner, u.position, u.facing, false);
            s.setSelected(u.getId() == selectedUnit && !planned);
            s.setViewed(u.getId() == viewedUnit && u.getId() != selectedUnit);
            s.setDone((phase.isMovement() && u.moved) || (phase.isFiring() && u.acted));
            if (targetTexts.containsKey(u.getId())) {
                s.setTargetInfo(targetTexts.get(u.getId()), u.getId() == targetId || u.getId() == rearTargetId);
            }
            currentSprites.add(s);
            if (planned) {
                ASUnitSprite ghost = new ASUnitSprite(bv, u, owner, plannedDest, plannedFacing, true);
                ghost.setSelected(true);
                currentSprites.add(ghost);
            }
        }
        currentSprites.forEach(Sprite::prepare);
        bv.addSprites(currentSprites);
        bv.repaint();
    }

    private void updateSidePanel() {
        ASUnit sel = viewedUnit >= 0 ? game().getUnit(viewedUnit) : selected();
        if (sel == null) {
            if (cardPanel.isVisible()) {
                cardPanel.setVisible(false);
                shownCardKey = "";
                repaintAll();
            }
            unitStatus.setText(" ");
            return;
        }
        sel.getElement().setCurrentArmor(sel.armor);
        sel.getElement().setCurrentStructure(sel.structure);
        String key = sel.getId() + ":" + sel.armor + ":" + sel.structure + ":" + sel.heat + ":" + sel.engineHits
              + ":" + sel.fcHits + ":" + sel.mpHits + ":" + sel.weaponHits;
        if (!key.equals(shownCardKey)) {              // only redraw the card when something on it changed
            shownCardKey = key;
            cardPanel.setASElement(sel.getElement());
            cardPanel.setVisible(true);
            repaintAll();
        }
        StringBuilder sb = new StringBuilder("<html><b>").append(sel.name).append("</b> (")
              .append(playerName(sel.getOwnerId())).append(")<br>Armor ").append(sel.armor).append("/")
              .append(sel.fullArmor).append(", structure ").append(sel.structure).append("/").append(sel.fullStructure)
              .append(", heat ").append(sel.heat);
        if (sel.engineHits + sel.fcHits + sel.mpHits + sel.weaponHits > 0) {
            sb.append("<br>Crits: engine ").append(sel.engineHits).append(", fire control ").append(sel.fcHits)
                  .append(", MP ").append(sel.mpHits).append(", weapons ").append(sel.weaponHits);
        }
        sb.append("<br>Move ").append(sel.groundInches()).append("\"")
              .append(sel.jumpIn > 0 ? " / jump " + sel.jumpIn + "\"" : "")
              .append(", TMM ").append(sel.tmm);
        String c3 = engine().c3Status(sel);
        if (!c3.isEmpty()) {
            sb.append("<br>").append(c3);
        }
        unitStatus.setText(sb.append("</html>").toString());
    }

    /** Repaint the whole window (and the map) after the side panel changed, so nothing stale stays on screen. */
    private void repaintAll() {
        SwingUtilities.invokeLater(() -> {
            frame.getContentPane().repaint();
            if (bv != null) {
                bv.repaint();
            }
        });
    }

    private void updateLog() {
        List<String> lines = new ArrayList<>();
        for (int round = 1; round <= Math.max(game().getCurrentRound(), 1); round++) {
            lines.addAll(game().getReports(round));
        }
        lines.addAll(client.getCombatLog());
        log.setText("<html><body style='font-family:sans-serif;font-size:10px'>" + String.join("<br>", lines)
              + "</body></html>");
        log.setCaretPosition(log.getDocument().getLength());
    }

    // --- buttons --------------------------------------------------------------------------------

    private MegaMekButton button(String text, Runnable action, boolean enabled) {
        MegaMekButton b = new MegaMekButton(text);
        b.addActionListener(e -> action.run());
        b.setEnabled(enabled);
        buttonBar.add(b);
        return b;
    }

    private void updateButtons() {
        buttonBar.removeAll();
        GamePhase phase = game().getPhase();
        ASUnit sel = selected();
        boolean mine = myTurn();
        String waiting = game().getTurn() != null ? "Waiting for " + playerName(game().getTurn().playerId()) : "";
        if (phase.isMovement()) {
            if (!mine) {
                statusBar.setText("Movement - " + waiting);
            } else if (sel == null) {
                statusBar.setText("Movement - your turn: pick a unit to move");
            } else if (plannedDest == null) {
                statusBar.setText("Your turn: click where " + sel.name + " should go ("
                      + (jumpMode ? "jumping" : "lit hexes; outer ring = sprint") + ")");
            } else {
                statusBar.setText(sel.name + ": " + ASUnit.MODE_NAMES[plannedMode] + " to "
                      + String.format("%02d%02d", plannedDest.getX() + 1, plannedDest.getY() + 1)
                      + ", facing " + facingName(plannedFacing) + (plannedMode == ASUnit.SPRINT ? " (no attacks!)" : ""));
            }
            button("Next unit", this::selectNext, mine);
            boolean canJump = sel != null && sel.jumpIn > 0;
            button(jumpMode ? "Jump: ON" : "Jump: off", () -> {
                jumpMode = !jumpMode;
                plannedDest = null;
                refresh();
            }, mine && canJump);
            button("Turn left", () -> turn(-1), mine && sel != null);
            button("Turn right", () -> turn(1), mine && sel != null);
            button("Stand still", this::planStand, mine && sel != null);
            button("Move", this::sendMove, mine && sel != null && plannedDest != null);
        } else if (phase.isFiring()) {
            AttackInfo info = chosenAttack();
            AttackInfo rearInfo = (sel != null && rearTargetId >= 0) ? rearFor(sel, rearTargetId) : null;
            if (!mine) {
                statusBar.setText("Combat - " + waiting);
            } else if (sel == null) {
                statusBar.setText("Combat - your turn: pick a unit");
            } else if (info == null && rearInfo != null) {
                statusBar.setText("<html>" + rearText(rearInfo) + "</html>");
            } else if (info == null && targetNote != null) {
                statusBar.setText("<html>" + sel.name + " can't attack that: <span style='color:#ff7070'>"
                      + targetNote + "</span></html>");
            } else if (info == null) {
                statusBar.setText("Your turn: click a target for " + sel.name + " (number = roll needed on 2d6)");
            } else {
                ASUnit t = game().getUnit(info.target());
                int cut = (rearInfo != null && !info.physical()) ? (int) rearInfo.damage() : 0;
                String dmgText = info.damage() == 0.5 ? "0*" : String.valueOf((int) info.damage());
                if (cut > 0) {
                    dmgText = Math.max(0, (info.damage() >= 1 ? (int) info.damage() : 0) - cut) + " (-" + cut
                          + " for the rear attack)";
                }
                statusBar.setText(sel.name + " → " + t.name + (info.arc() == ASEngine.ARC_TURRET ? " (turret)" : "")
                      + ": needs " + info.tn() + "+ ("
                      + Math.round(100 * ASRules.hitChance(info.tn())) + "%), damage "
                      + dmgText
                      + (overheat > 0 ? " +" + overheat + " OV" : "") + (info.rear() ? " +1 rear" : "")
                      + (info.physical() ? " (physical)" : ""));
                String why = engine().explainAttack(sel, t, info.physical());
                int colon = why.indexOf(':');
                statusBar.setText("<html>" + statusBar.getText() + " &nbsp;<span style='font-size:90%;font-weight:normal'>("
                      + (colon >= 0 ? why.substring(colon + 1).trim() : why) + ")</span>"
                      + (rearInfo != null ? "<br>" + rearText(rearInfo) : "") + "</html>");
            }
            boolean hasPhysical = sel != null && targetId >= 0 && attackFor(sel, targetId, true) != null;
            button("Next unit", this::selectNext, mine);
            button(physical ? "Physical: ON" : "Physical: off", () -> {
                physical = !physical;
                overheat = 0;
                refresh();
            }, mine && hasPhysical);
            int maxOv = info != null ? info.maxOv() : 0;
            button("OV -", () -> {
                overheat = Math.max(0, overheat - 1);
                refresh();
            }, mine && overheat > 0);
            button("OV +", () -> {
                overheat = Math.min(maxOv, overheat + 1);
                refresh();
            }, mine && overheat < maxOv);
            button("Hold fire", () -> sendAttack(true), mine && sel != null);
            button("Fire", () -> sendAttack(false), mine && (info != null || rearInfo != null));
        } else if (phase.isDeployment()) {
            boolean done = iAmDone();
            if (done) {
                statusBar.setText("Deployment - waiting for the other players");
            } else if (!canPlace(sel)) {
                statusBar.setText("Deployment: click one of your units, then a lit hex in your strip. Done when ready.");
            } else {
                statusBar.setText("Deployment: " + sel.name + " at " + String.format("%02d%02d",
                      sel.position.getX() + 1, sel.position.getY() + 1) + ", facing " + facingName(sel.facing)
                      + " - click a lit hex to move it, or turn it");
            }
            button("Turn left", () -> placeAt(sel, sel.position, sel.facing - 1), canPlace(sel));
            button("Turn right", () -> placeAt(sel, sel.position, sel.facing + 1), canPlace(sel));
            button("Done", () -> {
                client.sendDone(true);
                selectedUnit = -1;
                refresh();
            }, !done);
        } else if (phase.isReport() || phase.isVictory()) {
            Player me = client.getLocalPlayer();
            statusBar.setText(phase.isVictory() ? "Game over - see the battle log" : "End of round "
                  + game().getCurrentRound() + " - read the battle log, then click Done");
            button("Done", () -> {
                client.sendDone(true);
                refresh();
            }, me != null && !me.isDone());
        } else {
            statusBar.setText("Waiting for the server... (" + phase + ")");
        }
        buttonBar.revalidate();
        buttonBar.repaint();
    }

    private static String facingName(int f) {
        return new String[] { "N", "NE", "SE", "S", "SW", "NW" }[Math.floorMod(f, 6)];
    }

    // --- movement --------------------------------------------------------------------------------

    private void turn(int delta) {
        ASUnit sel = selected();
        if (sel == null) {
            return;
        }
        if (plannedDest == null) {
            planStand();
        }
        plannedFacing = Math.floorMod(plannedFacing + delta, 6);
        refresh();
    }

    private void planStand() {
        ASUnit sel = selected();
        if (sel == null) {
            return;
        }
        plannedDest = sel.position;
        plannedMode = sel.immobile() ? ASUnit.IMMOBILE : ASUnit.STAND;
        if (plannedFacing < 0 || plannedMode == ASUnit.IMMOBILE) {
            plannedFacing = sel.facing;
        }
        refresh();
    }

    private void planMoveTo(Coords c) {
        ASUnit sel = selected();
        ASBoard b = game().asBoard();
        int dest = b.index(c);
        int best = -1;
        int[] preference = jumpMode ? new int[] { ASUnit.JUMP }
              : new int[] { ASUnit.STAND, ASUnit.GROUND, ASUnit.MINIMUM, ASUnit.SPRINT };
        List<MoveOption> legal = engine().legalMoves(sel);
        for (int mode : preference) {
            if (best < 0 && legal.stream().anyMatch(m -> m.dest() == dest && m.mode() == mode)) {
                best = mode;
            }
        }
        if (best < 0) {
            return;
        }
        plannedDest = c;
        plannedMode = best;
        // face the nearest enemy by default
        int nearest = -1;
        int nd = Integer.MAX_VALUE;
        for (ASUnit e : engine().enemies(sel)) {
            if (e.position != null && b.distance(dest, b.index(e.position)) < nd) {
                nd = b.distance(dest, b.index(e.position));
                nearest = b.index(e.position);
            }
        }
        plannedFacing = (nearest >= 0 && nearest != dest) ? b.faceToward(dest, nearest) : sel.facing;
        refresh();
    }

    private void sendMove() {
        ASUnit sel = selected();
        if (sel == null || plannedDest == null) {
            return;
        }
        client.moveUnit(sel.getId(), plannedDest.getX(), plannedDest.getY(), plannedMode, plannedFacing);
        selectedUnit = -1;
        resetPlan();
        refresh();
    }

    // --- combat ----------------------------------------------------------------------------------

    private AttackInfo attackFor(ASUnit unit, int target, boolean phys) {
        for (AttackInfo info : engine().attackOptions(unit)) {
            if (info.target() == target && info.physical() == phys) {
                return info;
            }
        }
        return null;
    }

    private AttackInfo chosenAttack() {
        ASUnit sel = selected();
        if (sel == null || targetId < 0) {
            return null;
        }
        AttackInfo info = attackFor(sel, targetId, physical);
        if (info == null && !physical) {
            info = attackFor(sel, targetId, true);
            physical = info != null;
        }
        return info;
    }

    private void sendAttack(boolean holdFire) {
        ASUnit sel = selected();
        if (sel == null) {
            return;
        }
        AttackInfo info = chosenAttack();
        AttackInfo rearInfo = rearTargetId >= 0 ? rearFor(sel, rearTargetId) : null;
        if (holdFire || (info == null && rearInfo == null)) {
            client.attack(sel.getId(), -1, false, 0);
        } else {
            client.attack(sel.getId(), info == null ? -1 : info.target(), info != null && info.physical(),
                  info == null ? 0 : Math.min(overheat, info.maxOv()), rearInfo == null ? -1 : rearInfo.target());
        }
        selectedUnit = -1;
        resetPlan();
        refresh();
    }

    // --- mouse ---------------------------------------------------------------------------------

    private class Clicks extends BoardViewListenerAdapter {
        @Override
        public void hexMoused(BoardViewEvent b) {
            if (b.getType() != BoardViewEvent.BOARD_HEX_CLICKED || b.getButton() != MouseEvent.BUTTON1) {
                return;
            }
            Coords c = b.getCoords();
            SwingUtilities.invokeLater(() -> clicked(c));
        }
    }

    private void clicked(Coords c) {
        if (c == null) {
            return;
        }
        ASUnit here = null;
        for (ASUnit u : game().getUnits()) {
            if (u.alive() && c.equals(u.position)) {
                here = u;
            }
        }
        GamePhase phase = game().getPhase();
        ASUnit sel = selected();
        if (phase.isDeployment()) {
            if (here != null && canPlace(here) && (sel == null || here.getId() != sel.getId())) {
                selectedUnit = here.getId();
                viewedUnit = -1;
            } else if (canPlace(sel) && (here == null || here.getId() == sel.getId())
                  && engine().canDeployAt(game().sideOf(sel), game().asBoard().index(c))) {
                placeAt(sel, c, sel.facing);
            } else if (here != null) {
                viewedUnit = here.getId();
            }
            refresh();
            return;
        }
        if (here != null && isEligible(here) && (sel == null || here.getId() != sel.getId() || !phase.isMovement())) {
            selectedUnit = here.getId();          // one of your units that may act now
            viewedUnit = -1;
            resetPlan();
            refresh();
            return;
        }
        if (phase.isMovement() && isEligible(sel) && (here == null || here.getId() == sel.getId())) {
            planMoveTo(c);
            return;
        }
        if (phase.isFiring() && isEligible(sel) && here != null && pickTarget(sel, here.getId())) {
            viewedUnit = here.getId();            // (and show its card)
            refresh();
            return;
        }
        if (here != null) {
            viewedUnit = here.getId();            // just look at it
            noteWhyNot(sel, here);
            refresh();
        }
    }

    /**
     * Clicked an enemy in the Combat Phase: make it the target of the normal (or turret) attack, or of the rear
     * weapons if only they can reach it. Clicking a target both can reach again switches between the two.
     * Returns false if no attack can reach it.
     */
    private boolean pickTarget(ASUnit sel, int id) {
        boolean main = attackFor(sel, id, false) != null || attackFor(sel, id, true) != null;
        boolean rear = rearFor(sel, id) != null;
        if (!main && !rear) {
            return false;
        }
        targetNote = null;
        if (rear && (!main || targetId == id)) {
            rearTargetId = (rearTargetId == id && !main) ? -1 : id;     // rear weapons (click again = off)
            if (targetId == id) {
                targetId = -1;
            }
        } else {
            targetId = id;
            physical = attackFor(sel, id, false) == null;
            overheat = 0;
            if (rearTargetId == id) {
                rearTargetId = -1;
            }
        }
        return true;
    }

    private AttackInfo rearFor(ASUnit sel, int id) {
        for (AttackInfo info : engine().rearOptions(sel)) {
            if (info.target() == id) {
                return info;
            }
        }
        return null;
    }

    private String rearText(AttackInfo r) {
        ASUnit t = game().getUnit(r.target());
        return "Rear weapons → " + t.name + ": needs " + r.tn() + "+ (" + Math.round(100 * ASRules.hitChance(r.tn()))
              + "%), damage " + (r.damage() == 0.5 ? "0*" : String.valueOf((int) r.damage()))
              + " <span style='font-size:90%;font-weight:normal'>(click it again to cancel)</span>";
    }

    /** Clicked an enemy that isn't a legal target: remember why, for the status bar. */
    private void noteWhyNot(ASUnit sel, ASUnit enemy) {
        targetNote = null;
        if (game().getPhase().isFiring() && isEligible(sel) && game().sideOf(enemy) != game().sideOf(sel)) {
            targetId = -1;
            targetNote = engine().explainAttack(sel, enemy, false);
        }
    }

    /** A unit was clicked in the unit lists on the left. */
    private void listClicked(int unitId) {
        ASUnit u = game().getUnit(unitId);
        if (u == null) {
            return;
        }
        GamePhase phase = game().getPhase();
        ASUnit sel = selected();
        if (canPlace(u)) {
            selectedUnit = unitId;
            viewedUnit = -1;
        } else if (isEligible(u)) {
            selectedUnit = unitId;
            viewedUnit = -1;
            resetPlan();
        } else if (phase.isFiring() && isEligible(sel) && pickTarget(sel, unitId)) {
            viewedUnit = unitId;
        } else {
            viewedUnit = unitId;
            noteWhyNot(sel, u);
        }
        refresh();
        if (u.position != null) {
            bv.centerOnHex(u.position);
        }
    }

    // --- tooltips --------------------------------------------------------------------------------

    private class Tooltips implements BoardViewTooltipProvider {
        @Override
        public String getTooltip(Point point, Coords movementTarget) {
            Coords c = bv.getCoordsAt(point);
            if (c == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            for (ASUnit u : game().getUnits()) {
                if (c.equals(u.position)) {
                    sb.append("<b>").append(u.name).append("</b> - ").append(playerName(u.getOwnerId()))
                          .append("<br>").append(u.getElement().getASUnitType()).append(" SZ").append(u.size)
                          .append(", skill ").append(u.skill).append(", ").append(u.pv).append(" PV")
                          .append("<br>Armor ").append(u.armor).append("/").append(u.fullArmor)
                          .append(", structure ").append(u.structure).append("/").append(u.fullStructure)
                          .append(u.heat > 0 ? ", heat " + u.heat : "")
                          .append("<br>Damage S/M/L: ").append(dmgText(u, 0)).append("/").append(dmgText(u, 1))
                          .append("/").append(dmgText(u, 2)).append(u.ov > 0 ? ", OV " + u.ov : "")
                          .append("<br>Move ").append(u.groundInches()).append("\"")
                          .append(u.jumpIn > 0 ? " / " + u.jumpIn + "\"j" : "").append(", TMM ").append(u.tmm)
                          .append(u.moved && game().getPhase().isMovement() ? "<br>(" + ASUnit.MODE_NAMES[u.mode]
                                + " this turn)" : "")
                          .append(u.destroyed ? "<br><b>DESTROYED</b>" : "")
                          .append(u.doomed && !u.destroyed ? "<br><b style='color:red'>DESTROYED this turn</b>"
                                + " (leaves the map in the End Phase)" : "")
                          .append(specialsLine(u))
                          .append(attackLine(u)).append("<br>");
                }
            }
            return sb.isEmpty() ? null : "<html>" + sb + "</html>";
        }

        /** Card specials and the unit's C3 state. */
        private String specialsLine(ASUnit u) {
            String sp = "";
            try {
                sp = u.getElement().getSpecialsDisplayString(", ", u.getElement());
            } catch (Exception ignored) {
                // (no specials)
            }
            String c3 = engine().c3Status(u);
            return (sp.isBlank() ? "" : "<br>Specials: " + sp) + (c3.isEmpty() ? "" : "<br>" + c3);
        }

        /** In the Combat Phase: what the selected unit would need to hit this enemy, or why it can't. */
        private String attackLine(ASUnit u) {
            ASUnit sel = selected();
            if (sel == null || !game().getPhase().isFiring() || !isEligible(sel)
                  || game().sideOf(u) == game().sideOf(sel)) {
                return "";
            }
            return "<br><i>" + sel.name + ": " + engine().explainAttack(sel, u, false) + "</i>";
        }

        private String dmgText(ASUnit u, int band) {
            double d = u.damageAt(band);
            return d == 0.5 ? "0*" : String.valueOf((int) d);
        }
    }
}
