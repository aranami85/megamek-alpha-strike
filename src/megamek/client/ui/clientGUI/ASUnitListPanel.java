/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek.
 *
 * MegaMek is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */
package megamek.client.ui.clientGUI;

import megamek.common.alphaStrike.ASMessages;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;

import megamek.client.ui.clientGUI.boardview.sprite.alphaStrike.ASUnitImages;
import megamek.common.Player;
import megamek.common.alphaStrike.ASGame;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.enums.GamePhase;

/**
 * The unit lists on the left of the Alpha Strike window: one list per side with each unit's picture, armor and
 * structure, heat and whether it has already moved / attacked this phase. Clicking a unit tells the window (which
 * selects it if it's yours and may act, otherwise shows its card and highlights it on the map).
 */
public class ASUnitListPanel extends JPanel {

    private final IntConsumer onClick;
    private final JPanel sections = new JPanel();
    private final Map<Integer, JList<ASUnit>> lists = new LinkedHashMap<>();
    private final Map<Integer, JLabel> headers = new LinkedHashMap<>();
    private ASGame game;
    private GamePhase phase = GamePhase.UNKNOWN;
    private boolean updating;

    /** The unit lists; onClick gets the id of a clicked unit. */
    public ASUnitListPanel(IntConsumer onClick) {
        super(new BorderLayout());
        this.onClick = onClick;
        sections.setLayout(new BoxLayout(sections, BoxLayout.Y_AXIS));
        JScrollPane scroll = new JScrollPane(sections);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        add(scroll, BorderLayout.CENTER);
        setPreferredSize(new Dimension(250, 400));
    }

    /** Rebuilds the lists from the game. highlightId = the unit selected or looked at (-1 = none). */
    public void update(ASGame game, int localPlayerId, int highlightId) {
        this.game = game;
        this.phase = game.getPhase();
        updating = true;
        try {
            List<Integer> sides = game.sides();
            if (!lists.keySet().equals(new java.util.LinkedHashSet<>(sides))) {
                buildSections(sides);
            }
            int mySide = game.sideOfPlayer(localPlayerId);
            for (int side : sides) {
                List<ASUnit> units = new ArrayList<>();
                for (ASUnit u : game.getUnits()) {
                    if (game.sideOf(u) == side) {
                        units.add(u);
                    }
                }
                units.sort(Comparator.comparing((ASUnit u) -> u.destroyed).thenComparing(u -> u.getId()));
                JList<ASUnit> list = lists.get(side);
                DefaultListModel<ASUnit> model = (DefaultListModel<ASUnit>) list.getModel();
                model.clear();
                units.forEach(model::addElement);
                list.clearSelection();
                for (int i = 0; i < units.size(); i++) {
                    if (units.get(i).getId() == highlightId) {
                        list.setSelectedIndex(i);
                    }
                }
                headers.get(side).setText(header(game, side, side == mySide, units));
            }
        } finally {
            updating = false;
        }
        revalidate();
        repaint();
    }

    private void buildSections(List<Integer> sides) {
        sections.removeAll();
        lists.clear();
        headers.clear();
        for (int side : sides) {
            JLabel header = new JLabel();
            header.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
            header.setAlignmentX(Component.LEFT_ALIGNMENT);
            JList<ASUnit> list = new JList<>(new DefaultListModel<>());
            list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            list.setCellRenderer(new Renderer());
            list.setAlignmentX(Component.LEFT_ALIGNMENT);
            list.addListSelectionListener(e -> {
                if (!updating && !e.getValueIsAdjusting() && (list.getSelectedValue() != null)) {
                    onClick.accept(list.getSelectedValue().getId());
                }
            });
            // clicking the already highlighted unit again still centers the map on it
            list.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    int i = list.locationToIndex(e.getPoint());
                    if ((i >= 0) && (i == list.getSelectedIndex())) {
                        onClick.accept(list.getModel().getElementAt(i).getId());
                    }
                }
            });
            sections.add(header);
            sections.add(list);
            lists.put(side, list);
            headers.put(side, header);
        }
    }

    private static String header(ASGame game, int side, boolean mine, List<ASUnit> units) {
        StringBuilder names = new StringBuilder();
        Color colour = Color.GRAY;
        for (Player p : game.getPlayersList()) {
            if ((game.sideOfPlayer(p.getId()) == side) && !p.isObserver()) {
                if (!names.isEmpty()) {
                    names.append(", ");
                }
                names.append(p.getName());
                colour = p.getColour().getColour();
            }
        }
        int pv = 0;
        int alive = 0;
        for (ASUnit u : units) {
            pv += u.pv;
            alive += (u.destroyed || u.doomed) ? 0 : 1;
        }
        String hex = String.format("#%02x%02x%02x", colour.getRed(), colour.getGreen(), colour.getBlue());
        String you = mine ? ASMessages.getString("ASUnitListPanel.you") : "";
        return ASMessages.getString("ASUnitListPanel.header", hex, game.sideName(side), you, names.toString(), alive,
              units.size(), pv);
    }

    /** What the unit has done this phase: "to move", "moved (sprint)", "to fire", "fired", "held fire". */
    private String status(ASUnit u) {
        if (u.destroyed) {
            return ASMessages.getString("ASUnitListPanel.destroyed");
        }
        if (u.doomed) {                 // killed this turn: still on the map until the End Phase
            return ASMessages.getString((phase.isFiring() && !u.acted) ? "ASUnitListPanel.destroyedFiresBack"
                  : "ASUnitListPanel.destroyed");
        }
        if (phase.isMovement()) {
            return u.moved ? ASMessages.getString("ASUnitListPanel.moved", ASUnit.modeName(u.mode))
                  : ASMessages.getString("ASUnitListPanel.toMove");
        }
        if (phase.isFiring()) {
            if (u.acted) {
                return ASMessages.getString(u.fired ? "ASUnitListPanel.fired" : "ASUnitListPanel.heldFire");
            }
            return ASMessages.getString("ASUnitListPanel.toAttack");
        }
        return u.moved ? ASMessages.getString("ASUnitListPanel.mode", ASUnit.modeName(u.mode)) : "";
    }

    private class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
              boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, "", index, isSelected, cellHasFocus);
            ASUnit u = (ASUnit) value;
            Player owner = (game == null) ? null : game.getPlayer(u.getOwnerId());
            BufferedImage pic = ASUnitImages.imageFor(u, owner);
            label.setIcon((pic == null) ? null : new ImageIcon(pic.getScaledInstance(42, 36, Image.SCALE_SMOOTH)));
            boolean dead = u.destroyed || u.doomed;
            boolean done = (phase.isMovement() && u.moved) || (phase.isFiring() && u.acted) || dead;
            String nameStyle = done ? " style='color:#8a8a8a'" : "";
            String heat = (u.heat > 0) ? ASMessages.getString("ASUnitListPanel.heat", u.heat) : "";
            String row = ASMessages.getString("ASUnitListPanel.row", u.armor, u.fullArmor, u.structure,
                  u.fullStructure, heat, u.pv);
            label.setText("<html><b" + nameStyle + ">" + (dead ? "<s>" + u.name + "</s>" : u.name)
                  + "</b><br><small>" + row + "<br>" + status(u) + "</small></html>");
            label.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
            label.setIconTextGap(6);
            return label;
        }
    }
}
