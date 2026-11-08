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

    private static final MMLogger LOGGER = MMLogger.create(ASClient.class);

    private final ASGame game = new ASGame();

    /** Everything that happened this round so far (HTML lines), and listeners that want to know when it grows. */
    private final List<String> combatLog = new CopyOnWriteArrayList<>();
    private final List<Runnable> reportListeners = new CopyOnWriteArrayList<>();

    /** An Alpha Strike client for this player name and server. */
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

    /** Called whenever new combat report lines arrive. */
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
            LOGGER.error("Invalid Alpha Strike packet: " + packet.command(), e);
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

    /** The current round's report lines, including the live combat log. */
    public List<String> roundReportLines() {
        List<String> lines = new ArrayList<>(game.getReports(game.getCurrentRound()));
        lines.addAll(combatLog);
        return lines;
    }
}
