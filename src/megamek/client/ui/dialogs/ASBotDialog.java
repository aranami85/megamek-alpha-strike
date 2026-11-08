/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.client.ui.dialogs;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JTextField;

import megamek.common.Player;
import megamek.common.alphaStrike.play.ASLobbySupport;

/**
 * "Add Bot" for Alpha Strike games: the bot's name, who plays it (AI v3, AI v2.1, the scripted bot, or a bot from an
 * add-on jar) and its team.
 */
public class ASBotDialog extends JDialog {

    private final JTextField nameField = new JTextField(16);
    private final JComboBox<String> teamBox = new JComboBox<>();
    private final List<JRadioButton> aiButtons = new ArrayList<>();
    private final List<String> aiTypes = new ArrayList<>();
    private boolean confirmed = false;
    private String defaultBotName;
    private Collection<String> takenNames;

    public ASBotDialog(JFrame owner, String defaultName, int defaultTeam, Collection<String> takenNames) {
        super(owner, "Add Alpha Strike Bot", true);
        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 12, 6, 12));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;

        c.gridx = 0;
        c.gridy = 0;
        form.add(new JLabel("Name:"), c);
        c.gridx = 1;
        nameField.setText(defaultName);
        form.add(nameField, c);

        c.gridx = 0;
        c.gridy++;
        c.anchor = GridBagConstraints.NORTHWEST;
        form.add(new JLabel("Played by:"), c);
        JPanel ais = new JPanel();
        ais.setLayout(new javax.swing.BoxLayout(ais, javax.swing.BoxLayout.Y_AXIS));
        ButtonGroup group = new ButtonGroup();
        JRadioButton firstEnabled = null;
        megamek.client.bot.alphaStrike.ASBots.init();
        for (var entry : ASLobbySupport.BOT_LABELS.entrySet()) {
            String type = entry.getKey();
            boolean ok = megamek.client.bot.alphaStrike.ASBots.available(type);
            if (!ok && megamek.client.bot.alphaStrike.ASBots.isAddon(type)) {
                continue;                     // an add-on that can't run here: don't even show it
            }
            JRadioButton rb = new JRadioButton(entry.getValue() + (ok ? "" : "  - AI not found"));
            rb.setEnabled(ok);
            if (ok && firstEnabled == null) {
                firstEnabled = rb;
            }
            rb.addActionListener(e -> suggestName(type));
            group.add(rb);
            ais.add(rb);
            aiButtons.add(rb);
            aiTypes.add(type);
        }
        if (firstEnabled != null) {
            firstEnabled.setSelected(true);
        }
        this.defaultBotName = defaultName;
        this.takenNames = takenNames;
        suggestName(getBotType());
        c.gridx = 1;
        form.add(ais, c);

        c.gridx = 0;
        c.gridy++;
        c.anchor = GridBagConstraints.WEST;
        form.add(new JLabel("Team:"), c);
        for (int t = 1; t < Player.TEAM_NAMES.length; t++) {
            teamBox.addItem(Player.TEAM_NAMES[t]);
        }
        teamBox.setSelectedIndex(Math.max(0, Math.min(defaultTeam - 1, teamBox.getItemCount() - 1)));
        c.gridx = 1;
        form.add(teamBox, c);

        c.gridx = 0;
        c.gridy++;
        c.gridwidth = 2;
        form.add(new JLabel("<html><i>Alpha Strike mode plays two sides: put your units on one team and the bot on"
              + " the other.</i></html>"), c);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton ok = new JButton("OK");
        JButton cancel = new JButton("Cancel");
        JLabel error = new JLabel(" ");
        ok.addActionListener(e -> {
            String name = getBotName();
            if (name.isEmpty() || takenNames.contains(name)) {
                error.setText(name.isEmpty() ? "Please enter a name." : "That name is already taken.");
                pack();
                return;
            }
            confirmed = true;
            setVisible(false);
        });
        cancel.addActionListener(e -> setVisible(false));
        buttons.add(error);
        buttons.add(ok);
        buttons.add(cancel);
        getRootPane().setDefaultButton(ok);

        getContentPane().setLayout(new BorderLayout());
        getContentPane().add(form, BorderLayout.CENTER);
        getContentPane().add(buttons, BorderLayout.SOUTH);
        pack();
        setLocationRelativeTo(owner);
    }

    /** Add-on bots may suggest their own name; the others get "Bot", "Bot 2"... (only if no name was typed in). */
    private void suggestName(String type) {
        String current = getBotName();
        boolean untouched = current.isEmpty() || current.equals(defaultBotName) || current.equals(lastSuggested);
        if (!untouched) {
            return;
        }
        String base = ASLobbySupport.defaultBotName(type);
        String name = defaultBotName;
        if (base != null) {
            name = base;
            for (int k = 2; takenNames.contains(name); k++) {
                name = base + " " + k;
            }
        }
        lastSuggested = name;
        nameField.setText(name);
    }

    private String lastSuggested = "";

    public boolean isConfirmed() {
        return confirmed;
    }

    public String getBotName() {
        return nameField.getText().trim();
    }

    /** Team number (1 = Team 1). */
    public int getTeam() {
        return teamBox.getSelectedIndex() + 1;
    }

    public String getBotType() {
        for (int k = 0; k < aiButtons.size(); k++) {
            if (aiButtons.get(k).isSelected()) {
                return aiTypes.get(k);
            }
        }
        return ASLobbySupport.BOT_SCRIPTED;
    }
}
