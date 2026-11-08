/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.server.alphaStrike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import megamek.common.Player;
import megamek.common.alphaStrike.ASGame;
import megamek.common.alphaStrike.play.ASEngine;
import megamek.common.alphaStrike.play.ASOrder;
import megamek.common.alphaStrike.play.ASTurn;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.compute.Compute;
import megamek.common.enums.GamePhase;
import megamek.common.game.IGame;
import megamek.common.game.InGameObject;
import megamek.common.interfaces.ReportEntry;
import megamek.common.net.enums.PacketCommand;
import megamek.common.net.packets.Packet;
import megamek.logging.MMLogger;
import megamek.server.AbstractGameManager;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;

/**
 * The server side of an Alpha Strike game: runs the phases, checks every order a player sends, applies the rules
 * ({@link ASEngine}) and tells the clients what happened. Modelled on the Strategic BattleForce game manager.
 *
 * Phase flow per round: INITIATIVE (rolled automatically) - MOVEMENT (one unit per turn, sides alternating) - FIRING
 * (one unit per turn; initiative loser first) - END (crits, wrecks, heat; automatic) - END_REPORT (every player
 * clicks Done) - next round, or VICTORY when a side is wiped out or the turn limit is reached.
 */
public final class ASGameManager extends AbstractGameManager {

    private static final MMLogger logger = MMLogger.create(ASGameManager.class);

    private ASGame game;
    private ASEngine engine;
    private final List<String> pendingReports = new ArrayList<>();
    /** Let players set up their units before round 1 (-Das.deploy=false skips it). */
    private final boolean deployEnabled = !"false".equalsIgnoreCase(System.getProperty("as.deploy"));
    private final Random deployRandom = new Random();

    private record PendingPacket(int recipient, Packet packet) {}

    private final List<PendingPacket> pendingPackets = new ArrayList<>();

    @Override
    public ASGame getGame() {
        return game;
    }

    @Override
    public void setGame(IGame g) {
        if (!(g instanceof ASGame asGame)) {
            logger.fatal("Attempted to set game to incorrect class.");
            return;
        }
        game = asGame;
        engine = new ASEngine(game, Compute::d6);
    }

    // --- packets ---------------------------------------------------------------------------------

    @Override
    public void handlePacket(int connId, Packet packet) {
        super.handlePacket(connId, packet);
        try {
            switch (packet.command()) {
                case ENTITY_MOVE, ENTITY_ATTACK -> receiveOrder(connId, (ASOrder) packet.getObject(0));
                default -> { }
            }
        } catch (Exception e) {
            logger.error("Bad Alpha Strike order", e);
        }
        sendPendingPackets();
    }

    private void sendPendingPackets() {
        if (pendingPackets.isEmpty()) {
            return;
        }
        List<PendingPacket> toSend = new ArrayList<>(pendingPackets);
        pendingPackets.clear();
        for (Player player : game.getPlayersList()) {
            List<Packet> packets = toSend.stream()
                  .filter(p -> p.recipient == Player.PLAYER_NONE || p.recipient == player.getId())
                  .map(PendingPacket::packet)
                  .toList();
            if (!packets.isEmpty()) {
                super.send(player.getId(), new Packet(PacketCommand.MULTI_PACKET, new ArrayList<>(packets)));
            }
        }
    }

    @Override
    public void send(Packet packet) {
        pendingPackets.add(new PendingPacket(Player.PLAYER_NONE, packet));
    }

    @Override
    public void send(int connId, Packet p) {
        pendingPackets.add(new PendingPacket(connId, p));
    }

    private Packet unitsPacket() {
        return new Packet(PacketCommand.SENDING_ENTITIES, new ArrayList<InGameObject>(game.getInGameObjects()),
              new ArrayList<>(game.getGraveyard()), game.getForces());
    }

    private Packet unitPacket(ASUnit unit) {
        return new Packet(PacketCommand.ENTITY_UPDATE, unit);
    }

    private Packet turnsPacket() {
        return new Packet(PacketCommand.SENDING_TURNS, new ArrayList<>(game.getTurnsList()));
    }

    private Packet allReportsPacket() {
        Map<Integer, List<String>> copy = new HashMap<>();
        game.getAllReports().forEach((round, lines) -> copy.put(round, new ArrayList<>(lines)));
        return new Packet(PacketCommand.SENDING_REPORTS_ALL, copy);
    }

    private Packet roundReportPacket(List<String> lines) {
        return new Packet(PacketCommand.SENDING_REPORTS, new ArrayList<>(lines), game.getCurrentRound());
    }

    @Override
    public void sendCurrentInfo(int connId) {
        send(connId, packetHelper.createGameSettingsPacket());
        Player player = game.getPlayer(connId);
        if (player != null) {
            send(connId, packetHelper.createCurrentRoundNumberPacket());
            send(connId, packetHelper.createBoardsPacket());
            send(connId, allReportsPacket());
            send(connId, unitsPacket());
            send(connId, packetHelper.createPhaseChangePacket());
            if (game.getPhase().usesTurns() && game.getTurn() != null) {
                send(connId, turnsPacket());
                send(connId, packetHelper.createTurnIndexPacket(Player.PLAYER_NONE));
            }
        }
        sendPendingPackets();
    }

    // --- phases ------------------------------------------------------------------------------------

    @Override
    protected void prepareForCurrentPhase() {
        switch (game.getPhase()) {
            case INITIATIVE -> {
                pendingReports.clear();
                resetPlayersDone(false);
                if (game.getCurrentRound() < 0) {
                    game.setCurrentRound(0);          // first round = round 1
                }
                incrementAndSendGameRound();
                deployAll();                          // (only units that have no position yet)
                engine.startTurn();
                List<Integer> sides = game.sides();
                if (sides.size() >= 2) {
                    List<String> lines = new ArrayList<>();
                    lines.add("<b>Round " + game.getCurrentRound() + "</b>");
                    int winner = engine.rollInitiative(sides.get(0), sides.get(1), lines);
                    game.setInitiativeWinner(winner);
                    pendingReports.addAll(lines);
                    send(new Packet(PacketCommand.SENDING_REPORTS, new ArrayList<>(lines), -1)); // live log
                }
                send(unitsPacket());
            }
            case MOVEMENT, FIRING -> {
                setTurnsFor(game.getPhase());
                send(turnsPacket());
                send(unitsPacket());
            }
            case END -> {
                // (END isn't a "playable" phase, so MegaMek skips straight to endCurrentPhase(): do the work here)
                pendingReports.add("<b>End Phase</b>");
                pendingReports.addAll(engine.endPhase());
                checkVictory();
                game.addReports(game.getCurrentRound(), pendingReports);
                send(roundReportPacket(pendingReports));
                pendingReports.clear();
                send(unitsPacket());
            }
            case DEPLOYMENT -> {
                // before round 1: everyone is set up along their edge; players may rearrange inside their strip
                deployAll();
                resetPlayersDone(true);
                transmitAllPlayerDones();
                String line = "<b>Deployment</b>: move your units anywhere in your " + ASEngine.DEPLOY_DEPTH
                      + "-hex strip, then click Done.";
                send(new Packet(PacketCommand.SENDING_REPORTS, new ArrayList<>(List.of(line)), -1));
                send(unitsPacket());
            }
            case END_REPORT, VICTORY -> {
                resetPlayersDone(true);
                transmitAllPlayerDones();
            }
            default -> { }
        }
    }

    @Override
    protected void executeCurrentPhase() {
        switch (game.getPhase()) {
            case MOVEMENT, FIRING -> changeToNextTurn(Player.PLAYER_NONE);
            default -> { }
        }
    }

    @Override
    protected void endCurrentPhase() {
        switch (game.getPhase()) {
            case LOUNGE, EXCHANGE, STARTING_SCENARIO -> changePhase(deployEnabled ? GamePhase.DEPLOYMENT
                  : GamePhase.INITIATIVE);
            case DEPLOYMENT, INITIATIVE_REPORT -> changePhase(GamePhase.INITIATIVE);
            case INITIATIVE -> changePhase(GamePhase.MOVEMENT);
            case MOVEMENT -> changePhase(GamePhase.FIRING);
            case FIRING -> changePhase(GamePhase.END);
            case END -> changePhase(isGameOver() ? GamePhase.VICTORY : GamePhase.END_REPORT);
            case END_REPORT -> changePhase(GamePhase.INITIATIVE);
            default -> { }
        }
        sendPendingPackets();
    }

    private void resetPlayersDone(boolean report) {
        for (Player player : game.getPlayersList()) {
            player.setDone(false);
        }
    }

    // --- turns ---------------------------------------------------------------------------------

    /** Builds this phase's turn list from Alpha Strike's alternating order. */
    private void setTurnsFor(GamePhase phase) {
        List<Integer> sides = game.sides();
        List<ASTurn> turns = new ArrayList<>();
        if (sides.size() >= 2) {
            int winner = game.getInitiativeWinner();
            int loser = sides.get(0) == winner ? sides.get(1) : sides.get(0);
            List<Integer> order = phase.isMovement() ? engine.movementOrder(loser, winner)
                  : engine.combatOrder(loser, winner);
            // units left to act per player, to share a team's steps among its players
            Map<Integer, Integer> left = new HashMap<>();
            for (ASUnit u : game.getUnits()) {
                if (u.alive() && u.position != null) {
                    left.merge(u.getOwnerId(), 1, Integer::sum);
                }
            }
            for (int side : order) {
                int bestPlayer = Player.PLAYER_NONE;
                int most = 0;
                for (Player p : game.getPlayersList()) {
                    int n = left.getOrDefault(p.getId(), 0);
                    if (game.sideOfPlayer(p.getId()) == side && n > most) {
                        most = n;
                        bestPlayer = p.getId();
                    }
                }
                if (bestPlayer != Player.PLAYER_NONE) {
                    left.merge(bestPlayer, -1, Integer::sum);
                    turns.add(new ASTurn(bestPlayer));
                }
            }
        }
        game.setTurns(turns);
        game.resetTurnIndex();
    }

    private void changeToNextTurn(int prevPlayerId) {
        if (!game.hasMoreTurns()) {
            endCurrentPhase();
            return;
        }
        ASTurn next = game.changeToNextTurn();
        boolean valid = next.isValid(game);
        while (game.hasMoreTurns() && !valid) {
            next = game.changeToNextTurn();
            valid = next.isValid(game);
        }
        if (!valid) {
            endCurrentPhase();
            return;
        }
        send(packetHelper.createTurnIndexPacket(prevPlayerId));
        Player player = game.getPlayer(next.playerId());
        if (player != null && player.isGhost()) {
            sendGhostSkipMessage(player);
        }
    }

    // --- orders --------------------------------------------------------------------------------

    private void receiveOrder(int connId, ASOrder order) {
        if (game.getPhase().isDeployment()) {
            receiveDeployment(connId, order);
            return;
        }
        ASTurn turn = game.getTurn();
        ASUnit unit = game.getUnit(order.unitId());
        if (turn == null || unit == null || !turn.isValid(connId, unit, game)) {
            logger.error("Player {} sent an order for unit {} out of turn", connId, order.unitId());
            send(connId, turnsPacket());
            send(connId, packetHelper.createTurnIndexPacket(Player.PLAYER_NONE));
            return;
        }
        if (order.isMove() && game.getPhase().isMovement()) {
            int dest = game.asBoard().index(new megamek.common.board.Coords(order.x(), order.y()));
            if (!engine.isLegalMove(unit, dest, order.mode())) {
                logger.error("Illegal move for {}: {}", unit, order);
                send(connId, unitPacket(unit));
                return;
            }
            String from = hexName(unit.position);
            engine.applyMove(unit, dest, order.mode(), order.facing());
            if (order.mode() != ASUnit.STAND && order.mode() != ASUnit.IMMOBILE) {
                String verb = switch (order.mode()) {
                    case ASUnit.JUMP -> "jumps";
                    case ASUnit.SPRINT -> "sprints";
                    default -> "moves";
                };
                String line = tag(unit) + " " + verb + " " + from + " -> " + hexName(unit.position);
                pendingReports.add(line);
                send(new Packet(PacketCommand.SENDING_REPORTS, new ArrayList<>(List.of(line)), -1)); // live log
            }
            send(unitPacket(unit));
        } else if (!order.isMove() && game.getPhase().isFiring()) {
            List<String> lines = engine.applyAttack(unit, order.targetId(), order.physical(), order.overheat(),
                  order.rearTargetId());
            pendingReports.addAll(lines);
            send(new Packet(PacketCommand.SENDING_REPORTS, new ArrayList<>(lines), -1)); // live combat log
            send(unitPacket(unit));
            ASUnit target = game.getUnit(order.targetId());
            if (target != null) {
                send(unitPacket(target));
            }
            ASUnit rearTarget = game.getUnit(order.rearTargetId());
            if (rearTarget != null && rearTarget != target) {
                send(unitPacket(rearTarget));
            }
        } else {
            logger.error("Order {} doesn't fit phase {}", order, game.getPhase());
            return;
        }
        changeToNextTurn(connId);
    }

    /** Deployment Phase: a player moves (or turns) one of their units inside their strip. No turns: any order. */
    private void receiveDeployment(int connId, ASOrder order) {
        ASUnit unit = game.getUnit(order.unitId());
        Player player = game.getPlayer(connId);
        if (unit == null || player == null || unit.getOwnerId() != connId || !order.isMove() || player.isDone()) {
            logger.error("Player {} sent a bad deployment order {}", connId, order);
            send(connId, unitsPacket());
            return;
        }
        int dest = game.asBoard().index(new megamek.common.board.Coords(order.x(), order.y()));
        if (!engine.redeploy(unit, dest, order.facing())) {
            logger.error("Can't deploy {} there: {}", unit, order);
        }
        send(unitsPacket());      // (a swap moves two units)
    }

    private static String hexName(megamek.common.board.Coords c) {
        return String.format("%02d%02d", c.getX() + 1, c.getY() + 1);
    }

    private String tag(ASUnit unit) {
        Player p = game.getPlayer(unit.getOwnerId());
        return "[" + (p != null ? p.getName() : "?") + "] " + unit.name;
    }

    // --- deployment and victory -------------------------------------------------------------------

    /** Units without a position deploy automatically along their player's edge (scenario "deploy:"). */
    private void deployAll() {
        List<Integer> sides = game.sides();
        for (int k = 0; k < sides.size(); k++) {
            int side = sides.get(k);
            int edge = k == 0 ? 3 : 1; // default: first side west, second side east
            for (Player p : game.getPlayersList()) {
                if (game.sideOfPlayer(p.getId()) == side) {
                    String start = megamek.common.interfaces.IStartingPositions.START_LOCATION_NAMES[
                          Math.max(0, Math.min(p.getStartingPos(),
                                megamek.common.interfaces.IStartingPositions.START_LOCATION_NAMES.length - 1))];
                    if (start.startsWith("EDG") || start.startsWith("CTR") || start.equals("Any")) {
                        continue;                     // "any"/edge/center: keep the default edge
                    } else if (start.startsWith("N")) {
                        edge = 0;
                    } else if (start.startsWith("S")) {
                        edge = 2;
                    } else if (start.startsWith("E")) {
                        edge = 1;
                    } else if (start.startsWith("W")) {
                        edge = 3;
                    }
                }
            }
            engine.deploy(side, edge, deployRandom);
        }
    }

    private boolean isGameOver() {
        long sidesLeft = game.sides().stream().filter(s -> game.aliveCount(s) > 0).count();
        return sidesLeft <= 1 || game.getCurrentRound() >= game.getRules().maxTurns;
    }

    private void checkVictory() {
        if (!isGameOver()) {
            return;
        }
        List<Integer> sides = game.sides();
        List<Integer> alive = sides.stream().filter(s -> game.aliveCount(s) > 0).toList();
        String result;
        if (alive.size() == 1) {
            result = game.sideName(alive.get(0)) + " wins!";
        } else if (alive.isEmpty()) {
            result = "Draw - nobody is left standing.";
        } else {
            // out of time: whoever has more left wins [same as the simulator]
            int a = sides.get(0);
            int b = sides.get(1);
            double sa = engine.score(a);
            double sb = engine.score(b);
            result = Math.abs(sa - sb) < 0.05 ? "Draw (time is up, forces about even)"
                  : game.sideName(sa > sb ? a : b) + " wins on points (time is up)";
            result += String.format(" - left: %s %.0f%%, %s %.0f%%", game.sideName(a), 100 * sa, game.sideName(b),
                  100 * sb);
        }
        pendingReports.add("<b>GAME OVER: " + result + "</b>");
    }

    // --- the rest of IGameManager ------------------------------------------------------------------

    @Override
    public void resetGame() {
    }

    @Override
    public void disconnect(Player player) {
        // While players are (re)connecting at the start, a disconnect is expected (the lobby window closing).
        if (player == null || game.getPhase() == GamePhase.STARTING_SCENARIO || game.getPhase().isVictory()) {
            return;
        }
        player.setGhost(true);
        transmitPlayerUpdate(player);
        sendServerChat(player.getName() + " disconnected. Reconnect with the same name to continue.");
    }

    /** At the start, wait until every player who owns units has connected (bots and humans reconnecting). */
    @Override
    protected void checkReady() {
        if (game.getPhase().isDeployment()) {
            for (Player p : game.getPlayersList()) {
                if (!p.isGhost() && !p.isObserver() && !p.isDone()) {
                    return;
                }
            }
            endCurrentPhase();
            return;
        }
        if (game.getPhase() == GamePhase.STARTING_SCENARIO) {
            for (Player p : game.getPlayersList()) {
                if (p.isGhost() && !p.isObserver()
                      && game.getUnits().stream().anyMatch(u -> u.getOwnerId() == p.getId())) {
                    return;
                }
            }
        }
        super.checkReady();
    }

    @Override
    public void removeAllEntitiesOwnedBy(Player player) {
    }

    @Override
    public void handleCfrPacket(Server.ReceivedPacket rp) {
    }

    @Override
    public void requestGameMaster(Player player) {
    }

    @Override
    public List<ServerCommand> getCommandList(Server server) {
        return Collections.emptyList();
    }

    @Override
    public void addReport(ReportEntry r) {
        pendingReports.add(r.text());
    }

    @Override
    public void calculatePlayerInitialCounts() {
    }

    @Override
    public void requestTeamChangeForPlayer(int teamID, Player player) {
    }

    @Override
    protected void transmitAllPlayerDones() {
        game.getPlayersList().forEach(p -> send(packetHelper.createPlayerDonePacket(p.getId())));
    }
}
