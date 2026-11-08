/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import megamek.common.alphaStrike.ASGame;
import megamek.common.alphaStrike.play.ASOrder;
import megamek.common.alphaStrike.play.ASTurn;
import megamek.common.force.Forces;
import megamek.common.game.InGameObject;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.logging.MMLogger;

/**
 * A client (human player or bot) in an Alpha Strike game. Keeps its copy of the {@link ASGame} up to date from the
 * server's packets and sends the player's orders. Modelled on SBFClient.
 */
public class ASClient extends AbstractClient {

    private static final MMLogger logger = MMLogger.create(ASClient.class);

    private final ASGame game = new ASGame();

    /** Everything that happened this round so far (HTML lines), and listeners that want to know when it grows. */
    private final List<String> combatLog = new CopyOnWriteArrayList<>();
    private final List<Runnable> reportListeners = new CopyOnWriteArrayList<>();

    public ASClient(String name, String host, int port) {
        super(name, host, port);
    }

    @Override
    public ASGame getGame() {
        return game;
    }

    @Override
    public boolean isMyTurn() {
        return (game.getTurn() != null) && game.getTurn().isValid(localPlayerNumber, game)
              && game.getTurn().isValid(game);
    }

    public List<String> getCombatLog() {
        return combatLog;
    }

    public void addReportListener(Runnable listener) {
        reportListeners.add(listener);
    }

    private void reportsChanged() {
        roundReport = String.join("<br>", game.getReports(game.getCurrentRound()));
        phaseReport = roundReport;
        reportListeners.forEach(Runnable::run);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected boolean handleGameSpecificPacket(Packet packet) {
        try {
            switch (packet.command()) {
                case SENDING_ENTITIES -> {
                    List<InGameObject> units = (List<InGameObject>) packet.getObject(0);
                    List<InGameObject> graveyard = (List<InGameObject>) packet.getObject(1);
                    Forces forces = (Forces) packet.getObject(2);
                    if (forces != null) {
                        game.setForces(forces);
                    }
                    game.setGraveyard(graveyard);
                    game.setUnitList(units);
                }
                case ENTITY_UPDATE -> game.receiveUnit((InGameObject) packet.getObject(0));
                case SENDING_TURNS -> game.setTurns((List<ASTurn>) packet.getObject(0));
                case TURN -> game.setTurnIndex(packet.getIntValue(0), packet.getIntValue(1));
                case SENDING_REPORTS_ALL -> {
                    game.replaceAllReports((Map<Integer, List<String>>) packet.getObject(0));
                    reportsChanged();
                }
                case SENDING_REPORTS -> {
                    List<String> lines = (List<String>) packet.getObject(0);
                    int round = packet.getIntValue(1);
                    if (round < 0) {
                        combatLog.addAll(lines);         // live: an attack just happened
                    } else {
                        game.addReports(round, lines);   // the whole round, at its end
                        combatLog.clear();
                    }
                    reportsChanged();
                }
                case SENDING_GAME_SETTINGS, SENDING_PLANETARY_CONDITIONS, SENDING_MINEFIELDS -> { }
                default -> {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            logger.error("Invalid Alpha Strike packet: " + packet.command(), e);
            return false;
        }
    }

    /** Move a unit: hex x/y, movement mode (ASUnit.STAND...), facing 0-5. */
    public void moveUnit(int unitId, int x, int y, int mode, int facing) {
        send(new Packet(PacketCommand.ENTITY_MOVE, ASOrder.move(unitId, x, y, mode, facing)));
    }

    /** Attack with a unit; targetId -1 = no attack this turn. */
    public void attack(int unitId, int targetId, boolean physical, int overheat) {
        attack(unitId, targetId, physical, overheat, -1);
    }

    /** Attack plus a rear-weapons (REAR) attack on rearTargetId (-1 = none). */
    public void attack(int unitId, int targetId, boolean physical, int overheat, int rearTargetId) {
        send(new Packet(PacketCommand.ENTITY_ATTACK, ASOrder.attack(unitId, targetId, physical, overheat,
              rearTargetId)));
    }

    public List<String> roundReportLines() {
        List<String> lines = new ArrayList<>(game.getReports(game.getCurrentRound()));
        lines.addAll(combatLog);
        return lines;
    }
}
