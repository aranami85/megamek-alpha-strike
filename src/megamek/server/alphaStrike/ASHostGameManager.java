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
package megamek.server.alphaStrike;

import megamek.common.alphaStrike.ASMessages;
import java.util.ArrayList;
import java.util.List;

import megamek.common.Player;
import megamek.common.alphaStrike.ASGame;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.conversion.ASConverter;
import megamek.common.alphaStrike.play.ASLobbySupport;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.enums.GamePhase;
import megamek.common.game.Game;
import megamek.common.game.IGame;
import megamek.common.net.packets.Packet;
import megamek.common.units.Entity;
import megamek.logging.MMLogger;
import megamek.server.IGameManager;
import megamek.server.Server;
import megamek.server.commands.ServerCommand;
import megamek.server.totalWarfare.TWGameManager;
import megamek.common.interfaces.ReportEntry;

/**
 * Game manager for an Alpha Strike game that is set up in MegaMek's normal lobby ("Host Alpha Strike Game").
 *
 * The lobby part is run by MegaMek's own Total Warfare game manager, so the lobby looks and works exactly like a
 * normal MegaMek game (players, teams, unit selector, forces, skills, maps, deployment edges...). When everyone has
 * clicked Done and the map has been made, the units are converted to Alpha Strike cards with MegaMek's ASConverter and
 * this manager hands over to the {@link ASGameManager}. The clients are told (by a server chat line) to reopen as
 * Alpha Strike clients; they reconnect with the same name and take their player back.
 */
public class ASHostGameManager implements IGameManager {

    private static final MMLogger LOGGER = MMLogger.create(ASHostGameManager.class);

    private final LobbyManager lobby = new LobbyManager(this);
    private ASGameManager battle;

    /** MegaMek's normal game manager, except that it stops after the map is made (end of the EXCHANGE phase). */
    static class LobbyManager extends TWGameManager {
        private final ASHostGameManager host;

        LobbyManager(ASHostGameManager host) {
            this.host = host;
        }

        @Override
        protected void endCurrentPhase() {
            if (getGame().getPhase() == GamePhase.EXCHANGE) {
                host.startBattle();
            } else {
                super.endCurrentPhase();
            }
        }
    }

    private IGameManager current() {
        return (battle != null) ? battle : lobby;
    }

    /** True once the lobby has been turned into the Alpha Strike battle. */
    public boolean inBattle() {
        return battle != null;
    }

    /** Converts the lobby's game into an Alpha Strike game and switches to it. */
    synchronized void startBattle() {
        Game twGame = lobby.getGame();
        ASGame asGame = new ASGame();
        List<String> notes = new ArrayList<>();
        for (Player player : twGame.getPlayersList()) {
            asGame.addPlayer(player.getId(), player);
        }
        asGame.setBoard(0, twGame.getBoard(0));
        int converted = 0;
        for (Entity entity : twGame.getEntitiesVector()) {
            if (!ASConverter.canConvert(entity)) {
                notes.add(ASMessages.getString("ASHostGameManager.noCard", entity.getShortName()));
                continue;
            }
            try {
                AlphaStrikeElement element = ASConverter.convert(entity);
                element.setId(entity.getId());
                element.setOwnerId(entity.getOwnerId());
                asGame.addUnit(new ASUnit(element));
                converted++;
            } catch (Exception ex) {
                LOGGER.error(ex, "Could not convert {}", entity.getShortName());
                notes.add(ASMessages.getString("ASHostGameManager.notConverted", entity.getShortName()));
            }
        }
        copyC3Networks(twGame, asGame);
        for (Player player : asGame.getPlayersList()) {
            boolean ownsUnits = asGame.getUnits().stream().anyMatch(u -> u.getOwnerId() == player.getId());
            if (!ownsUnits) {
                player.setObserver(true);
            }
            // everyone reconnects with the Alpha Strike client and takes their player back
            player.setGhost(true);
            player.setDone(false);
        }
        if (asGame.sides().size() > 2) {
            notes.add(ASMessages.getString("ASHostGameManager.twoSides"));
        }
        asGame.setupTeams();
        asGame.setPhase(GamePhase.STARTING_SCENARIO);
        for (String note : notes) {
            lobby.sendServerChat(note);
        }
        LOGGER.info("Switching to Alpha Strike: {} units", converted);
        // tell the lobby clients to reopen as Alpha Strike clients (they still speak the lobby's protocol right now)
        lobby.sendServerChat(ASLobbySupport.SWITCH_TAG + " " + ASMessages.getString("ASHostGameManager.converted",
              converted));
        ASGameManager asManager = new ASGameManager();
        asManager.setGame(asGame);
        battle = asManager;
    }

    // --- IGameManager: everything goes to the current manager ------------------------------------

    @Override
    public IGame getGame() {
        return current().getGame();
    }

    @Override
    public void setGame(IGame g) {
        current().setGame(g);
    }

    @Override
    public void resetGame() {
        current().resetGame();
    }

    @Override
    public void disconnect(Player player) {
        current().disconnect(player);
    }

    @Override
    public void sendCurrentInfo(int connId) {
        current().sendCurrentInfo(connId);
        if (battle == null) {
            lobby.sendServerChat(connId, ASLobbySupport.LOBBY_TAG + " "
                  + ASMessages.getString("ASHostGameManager.lobbyNote"));
        }
    }

    @Override
    public void send(Packet packet) {
        current().send(packet);
    }

    @Override
    public void send(int connId, Packet p) {
        current().send(connId, p);
    }

    @Override
    public void saveGame(String fileName, boolean sendChat) {
        current().saveGame(fileName, sendChat);
    }

    @Override
    public void sendSaveGame(int connId, String fileName, String localPath) {
        current().sendSaveGame(connId, fileName, localPath);
    }

    @Override
    public void removeAllEntitiesOwnedBy(Player player) {
        current().removeAllEntitiesOwnedBy(player);
    }

    @Override
    public void handlePacket(int connId, Packet packet) {
        current().handlePacket(connId, packet);
    }

    @Override
    public void handleCfrPacket(Server.ReceivedPacket rp) {
        current().handleCfrPacket(rp);
    }

    @Override
    public void requestGameMaster(Player player) {
        current().requestGameMaster(player);
    }

    @Override
    public List<ServerCommand> getCommandList(Server server) {
        return lobby.getCommandList(server);
    }

    @Override
    public void addReport(ReportEntry r) {
        current().addReport(r);
    }

    @Override
    public void calculatePlayerInitialCounts() {
        current().calculatePlayerInitialCounts();
    }

    @Override
    public void requestTeamChangeForPlayer(int teamID, Player player) {
        current().requestTeamChangeForPlayer(teamID, player);
    }

    /**
     * Carries the C3 networks set up in the lobby (MegaMek's own C3 links, also saved in MUL files) over to the
     * Alpha Strike units: c3Parent = the unit's master, c3Net = one id per whole network.
     */
    private static void copyC3Networks(Game twGame, ASGame asGame) {
        java.util.Map<String, Integer> nhNets = new java.util.HashMap<>();
        for (ASUnit u : asGame.getUnits()) {
            Entity e = twGame.getEntity(u.getId());
            if ((e == null) || (u.c3Type == ASUnit.C3_NONE)) {
                continue;
            }
            if ((u.c3Type == ASUnit.C3_IMPROVED) || (u.c3Type == ASUnit.C3_NOVA)) {
                String net = e.getC3NetId();
                long members = (net == null) ? 0 : twGame.getEntitiesVector().stream()
                      .filter(o -> net.equals(o.getC3NetId())).count();
                if (members >= 2) {
                    u.c3Net = nhNets.computeIfAbsent(net, k -> 100000 + nhNets.size());
                }
                continue;
            }
            int master = e.getC3MasterId();
            u.c3Parent = (master == e.getId()) ? -1 : master;
        }
        // network id = the top master of each chain; units with no master and no members stay out
        for (ASUnit u : asGame.getUnits()) {
            if ((u.c3Type != ASUnit.C3_MASTER) && (u.c3Type != ASUnit.C3_SLAVE)) {
                continue;
            }
            ASUnit top = u;
            for (int k = 0; (k < 12) && (top.c3Parent >= 0) && (asGame.getUnit(top.c3Parent) != null); k++) {
                top = asGame.getUnit(top.c3Parent);
            }
            final ASUnit root = top;
            boolean hasMembers = (root != u) || asGame.getUnits().stream().anyMatch(o -> o.c3Parent == root.getId());
            if (hasMembers) {
                u.c3Net = root.getId();
            }
        }
    }
}
