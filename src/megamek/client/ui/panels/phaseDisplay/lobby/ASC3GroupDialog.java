/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.client.ui.panels.phaseDisplay.lobby;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import megamek.common.Player;
import megamek.common.game.Game;
import megamek.common.units.Entity;
import megamek.common.util.C3Util;

/**
 * "C3 Groups" in the lobby: build a C3 network by clicking units instead of the right-click menus.
 * <p>
 * Click <b>New group</b>, then click the units to add. The window keeps the group legal as you go (Alpha Strike:
 * Commander's Edition pp. 80-82, same as MegaMek's own C3 rules):
 * <ul>
 * <li>standard / boosted C3: at least one master; a master runs up to 3 units. With two or more masters, one is the
 * company master and up to 3 others run their own lances of slaves under it (MegaMek's rule: a company master only
 * takes slaves directly if it has two C3 master computers, "x2"). 12 units at most.</li>
 * <li>C3i: up to 6 C3i units, no master. Nova CEWS: up to 3 Nova units.</li>
 * </ul>
 * Units that can't be added any more are greyed out. The links are MegaMek's normal C3 links, so they show in the
 * unit list, are saved in MUL files, and carry over into the Alpha Strike game.
 */
public class ASC3GroupDialog extends JDialog {

    private enum Kind {
        NONE("no C3"), MASTER("C3 master"), SLAVE("C3 slave"), C3I("C3i"), NOVA("Nova CEWS");

        final String label;

        Kind(String label) {
            this.label = label;
        }

        boolean nonHierarchic() {
            return this == C3I || this == NOVA;
        }
    }

    private record Group(Entity root, List<Entity> members, Kind kind) { }

    private static final int MAX_NETWORK = 12;

    private final ChatLounge lounge;
    private final JComboBox<String> playerBox = new JComboBox<>();
    private final List<Player> players = new ArrayList<>();
    private final DefaultListModel<Group> groupModel = new DefaultListModel<>();
    private final JList<Group> groupList = new JList<>(groupModel);
    private final JPanel unitRows = new JPanel();
    private final JLabel message = new JLabel(" ");
    private final JLabel preview = new JLabel();
    private final JComboBox<String> topBox = new JComboBox<>();
    private final JButton newButton = new JButton("New group");
    private final JButton editButton = new JButton("Edit group");
    private final JButton breakButton = new JButton("Break up");
    private final JButton saveButton = new JButton("Save group");
    private final JButton cancelButton = new JButton("Cancel");
    private final List<Entity> picked = new ArrayList<>();
    private final List<Entity> topChoices = new ArrayList<>();
    private boolean building;
    private boolean updatingTop;
    private Entity top;

    public ASC3GroupDialog(JFrame owner, ChatLounge lounge) {
        super(owner, "C3 Groups", true);
        this.lounge = lounge;
        for (Player p : game().getPlayersList()) {
            if (lounge.lobbyActions.isSelfOrLocalBot(p) && game().getEntitiesVector().stream()
                  .anyMatch(e -> e.getOwnerId() == p.getId() && kind(e) != Kind.NONE)) {
                players.add(p);
                playerBox.addItem(p.getName());
            }
        }
        JPanel north = new JPanel(new FlowLayout(FlowLayout.LEFT));
        north.add(new JLabel("Units of:"));
        north.add(playerBox);
        playerBox.addActionListener(e -> {
            stopBuilding();
            reload();
        });

        // left: the groups there are
        groupList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        groupList.setCellRenderer((list, g, index, selected, focus) -> {
            JLabel l = new JLabel("<html>" + groupHtml(g, index + 1) + "</html>");
            l.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
            l.setOpaque(true);
            l.setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            l.setForeground(selected ? list.getSelectionForeground() : list.getForeground());
            return l;
        });
        groupList.addListSelectionListener(e -> updateButtons());
        JPanel left = new JPanel(new BorderLayout());
        left.setBorder(BorderFactory.createTitledBorder("C3 groups"));
        left.add(new JScrollPane(groupList), BorderLayout.CENTER);
        JPanel leftButtons = new JPanel(new GridLayout(1, 3, 4, 4));
        leftButtons.add(newButton);
        leftButtons.add(editButton);
        leftButtons.add(breakButton);
        left.add(leftButtons, BorderLayout.SOUTH);
        left.setPreferredSize(new Dimension(380, 420));

        // right: click units to build a group
        unitRows.setLayout(new BoxLayout(unitRows, BoxLayout.Y_AXIS));
        JPanel right = new JPanel(new BorderLayout());
        right.setBorder(BorderFactory.createTitledBorder("Units with C3"));
        right.add(new JScrollPane(unitRows), BorderLayout.CENTER);
        JPanel rightSouth = new JPanel();
        rightSouth.setLayout(new BoxLayout(rightSouth, BoxLayout.Y_AXIS));
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topRow.add(new JLabel("Company master:"));
        topRow.add(topBox);
        topBox.addActionListener(e -> {
            if (!updatingTop && topBox.getSelectedIndex() >= 0 && topBox.getSelectedIndex() < topChoices.size()) {
                top = topChoices.get(topBox.getSelectedIndex());
                refreshRows();
            }
        });
        topRow.setAlignmentX(LEFT_ALIGNMENT);
        preview.setAlignmentX(LEFT_ALIGNMENT);
        message.setAlignmentX(LEFT_ALIGNMENT);
        rightSouth.add(topRow);
        preview.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        rightSouth.add(preview);
        message.setForeground(new Color(220, 50, 50));
        message.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        rightSouth.add(message);
        JPanel rightButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        rightButtons.add(cancelButton);
        rightButtons.add(saveButton);
        rightButtons.setAlignmentX(LEFT_ALIGNMENT);
        rightSouth.add(rightButtons);
        right.add(rightSouth, BorderLayout.SOUTH);
        right.setPreferredSize(new Dimension(460, 460));

        newButton.addActionListener(e -> startBuilding(null));
        editButton.addActionListener(e -> {
            if (groupList.getSelectedValue() != null) {
                startBuilding(groupList.getSelectedValue());
            }
        });
        breakButton.addActionListener(e -> breakUp(groupList.getSelectedValue()));
        saveButton.addActionListener(e -> save());
        cancelButton.addActionListener(e -> stopBuilding());

        JButton close = new JButton("Close");
        close.addActionListener(e -> setVisible(false));
        JPanel south = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        south.add(new JLabel("<html><i>Tip: right-clicking units in the lobby still has MegaMek's own C3 menu.</i>"
              + "</html>"));
        south.add(close);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(north, BorderLayout.NORTH);
        getContentPane().add(left, BorderLayout.WEST);
        getContentPane().add(right, BorderLayout.CENTER);
        getContentPane().add(south, BorderLayout.SOUTH);
        reload();
        pack();
        setLocationRelativeTo(owner);
    }

    // --- data ------------------------------------------------------------------------------------------------

    private Game game() {
        return lounge.game();
    }

    private Player player() {
        int i = playerBox.getSelectedIndex();
        return (i >= 0 && i < players.size()) ? players.get(i) : null;
    }

    private static Kind kind(Entity e) {
        if (e.hasNovaCEWS()) {
            return Kind.NOVA;
        } else if (e.hasC3i()) {
            return Kind.C3I;
        } else if (e.hasC3M()) {
            return Kind.MASTER;
        } else if (e.hasC3S()) {
            return Kind.SLAVE;
        }
        return Kind.NONE;
    }

    private List<Entity> myC3Units() {
        Player p = player();
        List<Entity> out = new ArrayList<>();
        if (p == null) {
            return out;
        }
        for (Entity e : game().getEntitiesVector()) {
            if (e.getOwnerId() == p.getId() && kind(e) != Kind.NONE) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparing((Entity e) -> kind(e).ordinal()).thenComparing(Entity::getShortName));
        return out;
    }

    /** The top of a standard C3 chain (the company / lance master). */
    private static Entity rootOf(Entity e) {
        Entity r = e;
        for (int k = 0; k < MAX_NETWORK; k++) {
            Entity m = r.getC3Master();
            if (m == null || m.equals(r)) {
                break;
            }
            r = m;
        }
        return r;
    }

    /** The C3 groups of the chosen player as they are in the lobby right now. */
    private List<Group> currentGroups() {
        Map<String, List<Entity>> byKey = new LinkedHashMap<>();
        Map<String, Entity> roots = new LinkedHashMap<>();
        for (Entity e : myC3Units()) {
            Kind k = kind(e);
            String key;
            Entity root;
            if (k.nonHierarchic()) {
                key = "nh:" + e.getC3NetId();
                root = null;
            } else {
                root = rootOf(e);
                key = "c3:" + root.getId();
            }
            byKey.computeIfAbsent(key, x -> new ArrayList<>()).add(e);
            if (root != null) {
                roots.put(key, root);
            }
        }
        List<Group> out = new ArrayList<>();
        for (var en : byKey.entrySet()) {
            if (en.getValue().size() >= 2) {
                Entity first = en.getValue().get(0);
                out.add(new Group(roots.get(en.getKey()), en.getValue(), kind(first).nonHierarchic() ? kind(first)
                      : Kind.MASTER));
            }
        }
        return out;
    }

    private int groupNumberOf(Entity e, List<Group> groups) {
        for (int i = 0; i < groups.size(); i++) {
            if (groups.get(i).members().contains(e)) {
                return i + 1;
            }
        }
        return 0;
    }

    // --- the group being built -------------------------------------------------------------------------------

    private Kind pickedKind() {
        for (Entity e : picked) {
            Kind k = kind(e);
            if (k.nonHierarchic()) {
                return k;
            }
        }
        return picked.isEmpty() ? Kind.NONE : Kind.MASTER;
    }

    private long count(List<Entity> list, Kind k) {
        return list.stream().filter(e -> kind(e) == k).count();
    }

    /**
     * Can a standard network hold this many masters and slaves (with up to `spare` more masters still to come)?
     * One master: 3 slaves. More: a company master with up to 3 lance masters (2 if it has a double computer, which
     * may then also take 3 slaves itself), 3 slaves per lance master. Never more than 12 units.
     */
    private static boolean standardFits(long masters, long slaves, long spare, boolean topDouble) {
        for (long k = 0; k <= spare; k++) {
            long m = masters + k;
            long cap = (m == 1) ? 3 : 3 * (m - 1) + (topDouble ? 3 : 0);
            boolean mastersOk = m >= 1 && (m == 1 || m - 1 <= (topDouble ? 2 : 3));
            if (mastersOk && slaves <= cap && m + slaves <= MAX_NETWORK) {
                return true;
            }
        }
        return false;
    }

    private boolean topDouble() {
        return top != null && top.hasC3MM();
    }

    /** May this unit still be added to the group being built? */
    private boolean canAdd(Entity e) {
        if (picked.contains(e)) {
            return true;
        }
        Kind k = kind(e);
        Kind current = pickedKind();
        if (current == Kind.NONE) {
            return true;
        }
        if (current.nonHierarchic()) {
            return k == current && picked.size() < (current == Kind.NOVA ? 3 : 6);
        }
        if (k.nonHierarchic()) {
            return false;
        }
        long m = count(picked, Kind.MASTER) + (k == Kind.MASTER ? 1 : 0);
        long s = count(picked, Kind.SLAVE) + (k == Kind.SLAVE ? 1 : 0);
        long spare = myC3Units().stream().filter(u -> !picked.contains(u) && u != e && kind(u) == Kind.MASTER)
              .count();
        return standardFits(m, s, spare, topDouble());
    }

    /** Lance masters (the masters under the company master) and which slaves go to which master. */
    private Map<Entity, List<Entity>> structure() {
        Map<Entity, List<Entity>> tree = new LinkedHashMap<>();
        List<Entity> masters = picked.stream().filter(e -> kind(e) == Kind.MASTER).toList();
        if (masters.isEmpty()) {
            return tree;
        }
        Entity company = (top != null && masters.contains(top)) ? top : masters.get(0);
        tree.put(company, new ArrayList<>());
        List<Entity> lances = masters.stream().filter(m -> m != company).toList();
        for (Entity m : lances) {
            tree.get(company).add(m);
            tree.put(m, new ArrayList<>());
        }
        for (Entity s : picked) {
            if (kind(s) != Kind.SLAVE) {
                continue;
            }
            Entity best = null;               // lance masters first (fewest slaves), then the company master
            for (Entity m : lances) {
                if (tree.get(m).size() < 3 && (best == null || tree.get(m).size() < tree.get(best).size())) {
                    best = m;
                }
            }
            if (best == null && (lances.isEmpty() || company.hasC3MM()) && tree.get(company).stream()
                  .filter(x -> kind(x) == Kind.SLAVE).count() < 3) {
                best = company;                // a lone master, or a company master with a double computer
            }
            if (best != null) {
                tree.get(best).add(s);
            }
        }
        return tree;
    }

    // --- window --------------------------------------------------------------------------------------------

    private void reload() {
        groupModel.clear();
        currentGroups().forEach(groupModel::addElement);
        refreshRows();
    }

    private void refreshRows() {
        unitRows.removeAll();
        List<Group> groups = currentGroups();
        List<Entity> units = myC3Units();
        if (units.isEmpty()) {
            unitRows.add(new JLabel("  None of these units have C3, C3i or Nova equipment."));
        }
        for (Entity e : units) {
            int g = groupNumberOf(e, groups);
            boolean in = picked.contains(e);
            String role = kind(e).label + (e.hasBoostedC3() ? " (boosted)" : "") + (e.hasC3MM() ? " (x2)" : "");
            String where = g > 0 ? " <font color='#888888'>(group " + g + (building && !in ? ", will move" : "")
                  + ")</font>" : "";
            JCheckBox box = new JCheckBox("<html><b>" + e.getShortName() + "</b> &nbsp;<i>" + role + "</i>" + where
                  + "</html>", in);
            box.setEnabled(building && canAdd(e));
            box.addActionListener(ev -> {
                if (box.isSelected()) {
                    picked.add(e);
                } else {
                    picked.remove(e);
                    if (e == top) {
                        top = null;
                    }
                }
                refreshRows();
            });
            unitRows.add(box);
        }
        // company master choice
        updatingTop = true;
        topBox.removeAllItems();
        topChoices.clear();
        List<Entity> masters = picked.stream().filter(e -> kind(e) == Kind.MASTER).toList();
        if (top != null && !masters.contains(top)) {
            top = null;
        }
        if (top == null && !masters.isEmpty()) {
            top = masters.get(0);
        }
        for (Entity m : masters) {
            topChoices.add(m);
            topBox.addItem(m.getShortName());
        }
        if (top != null) {
            topBox.setSelectedIndex(topChoices.indexOf(top));
        }
        topBox.setEnabled(building && masters.size() >= 2);
        updatingTop = false;
        preview.setText(building ? "<html>" + previewHtml() + "</html>" : " ");
        message.setText(building ? problem() == null ? " " : problem() : " ");
        updateButtons();
        unitRows.revalidate();
        unitRows.repaint();
    }

    /** What's wrong with the group being built (null = it can be saved). */
    private String problem() {
        Kind k = pickedKind();
        long m = count(picked, Kind.MASTER);
        long s = count(picked, Kind.SLAVE);
        if (!k.nonHierarchic() && s > 0 && m == 0) {
            return "Must select at least 1 master.";
        }
        if (picked.size() < 2) {
            return "Click at least 2 units.";
        }
        if (k.nonHierarchic()) {
            return null;
        }
        if (!standardFits(m, s, 0, topDouble())) {
            return "Too many slaves for " + m + " master" + (m > 1 ? "s" : "") + ": add a master or drop a slave.";
        }
        return null;
    }

    private String previewHtml() {
        Kind k = pickedKind();
        if (picked.isEmpty()) {
            return "<i>Click units on the list to add them.</i>";
        }
        if (k.nonHierarchic()) {
            int max = k == Kind.NOVA ? 3 : 6;
            return k.label + " network: " + picked.size() + "/" + max + " units, no master needed.";
        }
        long m = count(picked, Kind.MASTER);
        long cap = m <= 1 ? 4 : Math.min(MAX_NETWORK, m + 3 * (m - 1) + (topDouble() ? 3 : 0));
        StringBuilder sb = new StringBuilder(picked.size() + "/" + cap + " units");
        Map<Entity, List<Entity>> tree = structure();
        if (!tree.isEmpty()) {
            Entity company = tree.keySet().iterator().next();
            sb.append("<br>").append(company.getShortName()).append(m > 1 ? " (company master)" : " (master)");
            for (Entity child : tree.get(company)) {
                sb.append("<br>&nbsp;&nbsp;- ").append(child.getShortName());
                if (tree.containsKey(child)) {
                    sb.append(" (lance master)");
                    for (Entity s : tree.get(child)) {
                        sb.append("<br>&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;- ").append(s.getShortName());
                    }
                }
            }
        }
        return sb.toString();
    }

    private String groupHtml(Group g, int number) {
        StringBuilder sb = new StringBuilder("<b>Group ").append(number).append("</b> - ");
        if (g.kind().nonHierarchic()) {
            sb.append(g.kind().label).append(", ").append(g.members().size()).append(" units");
            g.members().forEach(e -> sb.append("<br>&nbsp;&nbsp;").append(e.getShortName()));
            return sb.toString();
        }
        sb.append("C3, ").append(g.members().size()).append(" units<br>").append(g.root().getShortName())
              .append(g.root().isC3CompanyCommander() ? " (company master)" : " (master)");
        appendChildren(sb, g, g.root(), 1);
        return sb.toString();
    }

    private void appendChildren(StringBuilder sb, Group g, Entity master, int depth) {
        for (Entity e : g.members()) {
            Entity m = e.getC3Master();
            if (e != master && master.equals(m) && depth < 4) {
                sb.append("<br>").append("&nbsp;&nbsp;&nbsp;&nbsp;".repeat(depth)).append("- ")
                      .append(e.getShortName()).append(kind(e) == Kind.MASTER ? " (lance master)" : "");
                appendChildren(sb, g, e, depth + 1);
            }
        }
    }

    private void updateButtons() {
        boolean editable = !lounge.localPlayer().isDone();
        newButton.setEnabled(!building && editable && !myC3Units().isEmpty());
        editButton.setEnabled(!building && editable && groupList.getSelectedValue() != null);
        breakButton.setEnabled(!building && editable && groupList.getSelectedValue() != null);
        saveButton.setEnabled(building);
        cancelButton.setEnabled(building);
        groupList.setEnabled(!building);
        playerBox.setEnabled(!building);
    }

    private void startBuilding(Group group) {
        building = true;
        picked.clear();
        top = null;
        if (group != null) {
            picked.addAll(group.members());
            top = group.root();
        }
        refreshRows();
    }

    private void stopBuilding() {
        building = false;
        picked.clear();
        top = null;
        refreshRows();
    }

    // --- changing the lobby ----------------------------------------------------------------------------------

    private void save() {
        String why = problem();
        if (why != null) {
            message.setText(why);
            return;
        }
        Set<Entity> changed = new HashSet<>(C3Util.disconnectFromNetwork(game(), new ArrayList<>(picked)));
        changed.addAll(picked);
        Kind k = pickedKind();
        if (k.nonHierarchic()) {
            try {
                C3Util.joinNh(game(), new ArrayList<>(picked), picked.get(0).getId(), false);
            } catch (Exception ex) {
                message.setText("MegaMek refused that network: " + ex.getClass().getSimpleName());
                return;
            }
        } else {
            Map<Entity, List<Entity>> tree = structure();
            Entity company = tree.keySet().iterator().next();
            boolean isCompany = tree.size() > 1;            // has lance masters under it
            company.setC3Master(isCompany ? company.getId() : Entity.NONE, true);
            for (Entity child : tree.get(company)) {
                if (kind(child) == Kind.MASTER) {
                    child.setC3Master(company, true);     // a lance master under the company master
                }
            }
            for (Entity child : tree.get(company)) {
                if (kind(child) != Kind.MASTER) {
                    child.setC3Master(company, true);     // a slave in one of the company master's free slots
                }
            }
            for (var en : tree.entrySet()) {
                if (en.getKey() != company) {
                    for (Entity s : en.getValue()) {
                        s.setC3Master(en.getKey(), true);
                    }
                }
            }
        }
        // (MegaMek may re-link other units while doing this: send all of this player's C3 units)
        changed.addAll(myC3Units());
        lounge.lobbyActions.sendUpdates(changed);
        stopBuilding();
        reload();
    }

    private void breakUp(Group g) {
        if (g == null) {
            return;
        }
        Set<Entity> changed = new HashSet<>(C3Util.disconnectFromNetwork(game(), new ArrayList<>(g.members())));
        changed.addAll(g.members());
        lounge.lobbyActions.sendUpdates(changed);
        reload();
    }
}
