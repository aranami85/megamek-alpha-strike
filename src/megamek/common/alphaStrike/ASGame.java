/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.common.alphaStrike;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Vector;
import java.util.stream.Collectors;

import megamek.common.Player;
import megamek.common.Report;
import megamek.common.Team;
import megamek.common.alphaStrike.play.ASBoard;
import megamek.common.alphaStrike.play.ASRules;
import megamek.common.alphaStrike.play.ASTurn;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.board.Board;
import megamek.common.enums.GamePhase;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.event.UnitChangedGameEvent;
import megamek.common.event.entity.GameEntityChangeEvent;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.game.AbstractGame;
import megamek.common.game.InGameObject;
import megamek.common.interfaces.ReportEntry;
import megamek.common.options.GameOptions;
import megamek.common.options.OptionsConstants;

/**
 * An Alpha Strike game (ASCE rules played on MegaMek hex maps; see "Converting Alpha Strike to Hex Maps", ASCE pp.
 * 68-71). Built the same way as the Strategic BattleForce game ({@link megamek.common.strategicBattleSystems.SBFGame}):
 * this object holds the game state on the server and on every client; the rules live in
 * {@link megamek.common.alphaStrike.play.ASEngine}, the phase flow in megamek.server.alphaStrike.ASGameManager.
 *
 * Units are {@link ASUnit}s: an AlphaStrikeElement card plus its in-game state.
 */
public class ASGame extends AbstractGame {

    private GamePhase phase = GamePhase.UNKNOWN;
    private GamePhase lastPhase = GamePhase.UNKNOWN;
    private final GameOptions options = new GameOptions();
    private final ASRules rules = new ASRules();
    private final List<ASTurn> turnList = new ArrayList<>();
    private final List<InGameObject> graveyard = new ArrayList<>();
    private final Map<Integer, List<String>> reports = new HashMap<>();   // round -> report lines (HTML)
    private int initiativeWinner = -1;                                   // side (team) that won initiative
    private transient ASBoard asBoard;

    public ASGame() {
        setBoard(0, new Board());
    }

    // --- rules helpers ---------------------------------------------------------------------------

    public ASRules getRules() {
        return rules;
    }

    /** The rules' view of the map (rebuilt when the board changes). */
    public ASBoard asBoard() {
        if (asBoard == null || asBoard.width != getBoard().getWidth() || asBoard.height != getBoard().getHeight()) {
            asBoard = new ASBoard(getBoard());
        }
        return asBoard;
    }

    @Override
    public void setBoard(int boardId, Board board) {
        super.setBoard(boardId, board);
        asBoard = null;
    }

    @Override
    public void receiveBoards(Map<Integer, Board> boards) {
        super.receiveBoards(boards);
        asBoard = null;
    }

    @Override
    public void receiveBoard(int boardId, Board board) {
        super.receiveBoard(boardId, board);
        asBoard = null;
    }

    public List<ASUnit> getUnits() {
        return inGameObjects.values().stream()
              .filter(ASUnit.class::isInstance)
              .map(ASUnit.class::cast)
              .sorted((a, b) -> Integer.compare(a.getId(), b.getId()))
              .collect(Collectors.toList());
    }

    public ASUnit getUnit(int id) {
        InGameObject o = inGameObjects.get(id);
        return (o instanceof ASUnit u) ? u : null;
    }

    /** The side a unit fights for: its owner's team (or the owner alone without a team). */
    public int sideOf(ASUnit unit) {
        return sideOfPlayer(unit.getOwnerId());
    }

    public int sideOfPlayer(int playerId) {
        Player p = getPlayer(playerId);
        if (p == null) {
            return -1000 - playerId;
        }
        return (p.getTeam() > Player.TEAM_NONE) ? p.getTeam() : 1000 + playerId;
    }

    public String sideName(int side) {
        if (side >= 1000) {
            Player p = getPlayer(side - 1000);
            return (p != null) ? p.getName() : "Player " + (side - 1000);
        }
        List<String> names = getPlayersList().stream().filter(p -> p.getTeam() == side).map(Player::getName).toList();
        return names.isEmpty() ? "Team " + side : String.join(" & ", names);
    }

    /** The sides (teams) that have units, in order. */
    public List<Integer> sides() {
        return getUnits().stream().map(this::sideOf).distinct().sorted().collect(Collectors.toList());
    }

    public int aliveCount(int side) {
        return (int) getUnits().stream().filter(u -> u.alive() && sideOf(u) == side).count();
    }

    public int getInitiativeWinner() {
        return initiativeWinner;
    }

    public void setInitiativeWinner(int side) {
        initiativeWinner = side;
    }

    // --- units -----------------------------------------------------------------------------------

    /** Server-side. */
    public void addUnit(ASUnit unit) {
        int id = unit.getId();
        if (id < 0 || inGameObjects.containsKey(id)) {
            id = getNextEntityId();
            unit.setId(id);
        }
        inGameObjects.put(id, unit);
    }

    /** Client-side: a changed unit from the server. */
    public void receiveUnit(InGameObject unit) {
        InGameObject old = inGameObjects.put(unit.getId(), unit);
        fireGameEvent(new UnitChangedGameEvent(this, old, unit));
    }

    /** Client-side: all units from the server. */
    public void setUnitList(List<InGameObject> units) {
        inGameObjects.clear();
        for (InGameObject unit : units) {
            inGameObjects.put(unit.getId(), unit);
        }
        fireGameEvent(new GameEntityChangeEvent(this, null));
    }

    @Override
    public int getNextEntityId() {
        return inGameObjects.keySet().stream().mapToInt(Integer::intValue).max().orElse(0) + 1;
    }

    @Override
    public void replaceUnits(List<InGameObject> units) {
        units.forEach(u -> inGameObjects.put(u.getId(), u));
    }

    @Override
    public List<InGameObject> getGraveyard() {
        return graveyard;
    }

    public void setGraveyard(List<InGameObject> newGraveyard) {
        graveyard.clear();
        graveyard.addAll(newGraveyard);
    }

    // --- turns -----------------------------------------------------------------------------------

    @Override
    public ASTurn getTurn() {
        if ((turnIndex < 0) || (turnIndex >= turnList.size())) {
            return null;
        }
        return turnList.get(turnIndex);
    }

    @Override
    public List<ASTurn> getTurnsList() {
        return Collections.unmodifiableList(turnList);
    }

    public void setTurns(List<ASTurn> newTurns) {
        turnList.clear();
        turnList.addAll(newTurns);
    }

    @Override
    public boolean hasMoreTurns() {
        return turnList.size() > turnIndex + 1;
    }

    public ASTurn changeToNextTurn() {
        turnIndex++;
        return getTurn();
    }

    /** Client-side. */
    public void setTurnIndex(int index, int prevPlayerId) {
        turnIndex = index;
        ASTurn turn = getTurn();
        if (turn != null) {
            fireGameEvent(new GameTurnChangeEvent(this, getPlayer(turn.playerId()), prevPlayerId));
        }
    }

    /** Units the given player may act with right now. */
    public List<ASUnit> eligibleUnits(int playerId) {
        ASTurn turn = getTurn();
        if (turn == null || turn.playerId() != playerId) {
            return List.of();
        }
        return getUnits().stream().filter(u -> turn.isValidEntity(u, this)).toList();
    }

    // --- phases ----------------------------------------------------------------------------------

    @Override
    public GamePhase getPhase() {
        return phase;
    }

    @Override
    public void setPhase(GamePhase phase) {
        this.phase = phase;
    }

    @Override
    public void receivePhase(GamePhase newPhase) {
        GamePhase old = phase;
        setPhase(newPhase);
        fireGameEvent(new GamePhaseChangeEvent(this, old, newPhase));
    }

    public GamePhase getLastPhase() {
        return lastPhase;
    }

    @Override
    public void setLastPhase(GamePhase lastPhase) {
        this.lastPhase = lastPhase;
    }

    @Override
    public boolean isCurrentPhasePlayable() {
        return switch (phase) {
            case INITIATIVE, END, TARGETING, PHYSICAL, OFFBOARD, OFFBOARD_REPORT, PREMOVEMENT, PRE_FIRING,
                 DEPLOY_MINEFIELDS, SET_ARTILLERY_AUTO_HIT_HEXES, TARGETING_REPORT -> false;
            case MOVEMENT, FIRING -> hasMoreTurns();
            case DEPLOYMENT -> true;            // (players set up, then click Done)
            default -> true;
        };
    }

    @Override
    public boolean isForceVictory() {
        return false;
    }

    // --- players and teams -------------------------------------------------------------------------

    @Override
    public void addPlayer(int id, Player player) {
        super.addPlayer(id, player);
        player.setGame(this);
        setupTeams();
    }

    @Override
    public void setPlayer(int id, Player player) {
        players.put(id, player);
        player.setGame(this);
        setupTeams();
    }

    @Override
    public void removePlayer(int id) {
        players.remove(id);
        setupTeams();
    }

    @Override
    public void setupTeams() {
        Vector<Team> initTeams = new Vector<>();
        Map<Integer, Team> byId = new HashMap<>();
        for (Player player : getPlayersList()) {
            if (player.getTeam() == Player.TEAM_UNASSIGNED) {
                continue;
            }
            if (player.getTeam() == Player.TEAM_NONE) {
                Team t = new Team(Player.TEAM_NONE);
                t.addPlayer(player);
                initTeams.add(t);
            } else {
                Team t = byId.computeIfAbsent(player.getTeam(), Team::new);
                if (!initTeams.contains(t)) {
                    initTeams.add(t);
                }
                t.addPlayer(player);
            }
        }
        teams.clear();
        teams.addAll(initTeams);
    }

    // --- options and reports -----------------------------------------------------------------------

    @Override
    public GameOptions getOptions() {
        return options;
    }

    public boolean usesDoubleBlind() {
        return options.booleanOption(OptionsConstants.ADVANCED_DOUBLE_BLIND);
    }

    @Override
    public ReportEntry getNewReport(int messageId) {
        return new Report(messageId);
    }

    public void addReports(int round, List<String> lines) {
        reports.computeIfAbsent(round, r -> new ArrayList<>()).addAll(lines);
    }

    public List<String> getReports(int round) {
        return reports.getOrDefault(round, List.of());
    }

    public Map<Integer, List<String>> getAllReports() {
        return reports;
    }

    public void replaceAllReports(Map<Integer, List<String>> newReports) {
        reports.clear();
        reports.putAll(newReports);
    }

    public Optional<ASUnit> findUnit(int id) {
        return Optional.ofNullable(getUnit(id));
    }
}
