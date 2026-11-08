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
package megamek.client.bot.alphaStrike;

import megamek.common.alphaStrike.ASMessages;
import java.util.List;
import java.util.Random;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import megamek.common.alphaStrike.ASGame;
import megamek.common.alphaStrike.play.ASTurn;
import megamek.common.alphaStrike.play.ASBoard;
import megamek.common.alphaStrike.play.ASEngine;
import megamek.common.alphaStrike.play.ASEngine.AttackInfo;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.Player;
import megamek.common.enums.GamePhase;
import megamek.logging.MMLogger;

/**
 * Alpha Strike bot that asks the trained AI (as_ai_engine, via {@link ASAIBridge}) for every move and attack. Bot
 * types:
 * "ai_v3" = look-ahead (strongest), "ai" = the network alone. If the AI can't be reached, or it suggests something
 * the rules don't allow, the scripted bot's choice is used for that decision.
 */
public class ASAIBot extends ASScriptedBot {

    private static final MMLogger LOGGER = MMLogger.create(ASAIBot.class);

    private final String botType;
    private final Random seeds = new Random();
    private boolean warned;
    private int aiDecisions;
    private int fallbacks;

    /** How many decisions the AI made, and how many fell back to the scripted bot. */
    public int[] decisionCounts() {
        return new int[] { aiDecisions, fallbacks };
    }

    /** An AI bot of this type (ai_v3 or ai) connecting to the game server. */
    public ASAIBot(String name, String host, int port, String botType) {
        super(name, host, port);
        this.botType = botType;
        // start the helper now (loading the networks takes a few seconds) so the first turn isn't slow
        Thread warmUp = new Thread(() -> {
            try {
                ASAIBridge.get();
            } catch (Exception ex) {
                LOGGER.error(ex, "The AI could not be started - {} will play as the scripted bot", name);
            }
        }, "AI start");
        warmUp.setDaemon(true);
        warmUp.start();
    }

    @Override
    protected void chooseMove(ASGame game, ASEngine engine, List<ASUnit> ready) {
        try {
            JsonNode r = ask(game, ready, 0);
            ASUnit unit = game.getUnit(r.path("unit").asInt());
            int dest = r.path("a").asInt();
            int mode = r.path("b").asInt();
            int facing = r.path("c").asInt();
            boolean legal = (unit != null) && ready.contains(unit)
                  && engine.legalMoves(unit).stream().anyMatch(m -> (m.dest() == dest) && (m.mode() == mode));
            if (legal) {
                ASBoard b = game.asBoard();
                moveUnit(unit.getId(), dest % b.width, dest / b.width, mode, facing);
                aiDecisions++;
                return;
            }
            LOGGER.warn("The AI suggested an illegal move {}; using the scripted bot's move", r);
        } catch (Exception ex) {
            warnOnce(ex);
        }
        fallbacks++;
        super.chooseMove(game, engine, ready);
    }

    @Override
    protected void chooseAttack(ASGame game, ASEngine engine, List<ASUnit> ready) {
        try {
            JsonNode r = ask(game, ready, 1);
            ASUnit unit = game.getUnit(r.path("unit").asInt());
            int target = r.path("a").asInt();
            boolean physical = r.path("b").asInt() == 1;
            int ov = r.path("c").asInt();
            if ((unit != null) && ready.contains(unit)) {
                if (target < 0) {
                    attack(unit.getId(), -1, false, 0);
                    aiDecisions++;
                    return;
                }
                for (AttackInfo info : engine.attackOptions(unit)) {
                    if ((info.target() == target) && (info.physical() == physical)) {
                        attack(unit.getId(), target, physical, Math.max(0, Math.min(ov, info.maxOv())),
                              rearPick(game, engine, unit, info));
                        aiDecisions++;
                        return;
                    }
                }
            }
            LOGGER.warn("The AI suggested an illegal attack {}; using the scripted bot's attack", r);
        } catch (Exception ex) {
            warnOnce(ex);
        }
        fallbacks++;
        super.chooseAttack(game, engine, ready);
    }

    private void warnOnce(Exception ex) {
        if (!warned) {
            warned = true;
            LOGGER.error(ex, "The AI is unavailable - {} uses the scripted bot instead", getName());
            sendChat(ASMessages.getString("ASAIBot.unavailable", ex.getMessage()));
        }
    }

    /** Sends the whole situation to the AI and returns its answer (unit, a, b, c). */
    private JsonNode ask(ASGame game, List<ASUnit> ready, int phase) throws Exception {
        ObjectNode msg = ASAIBridge.json().createObjectNode();
        msg.put("cmd", "decide");
        msg.put("bot", botType);
        ASBoard b = game.asBoard();
        msg.put("w", b.width);
        msg.put("h", b.height);
        ArrayNode hexes = msg.putArray("hexes");
        for (int i = 0; i < b.n; i++) {
            ArrayNode hx = hexes.addArray();
            for (int v : b.hexRecord(i)) {
                hx.add(v);
            }
        }
        List<Integer> sides = game.sides();
        ArrayNode units = msg.putArray("units");
        for (ASUnit u : game.getUnits()) {
            int side = sides.indexOf(game.sideOf(u));
            if ((side < 0) || (side > 1)) {
                continue;
            }
            ObjectNode o = units.addObject();
            o.put("id", u.getId());
            o.put("side", side);
            ArrayNode spec = o.putArray("spec");
            for (int v : new int[] { u.skill, u.pv, u.size, u.baseMove, u.baseJump, u.baseTmm, u.fullArmor,
                  u.fullStructure, u.ov, u.flags, u.ht[0], u.ht[1], u.ht[2], u.jmps, u.jmpw }) {
                spec.add(v);
            }
            o.putArray("dmg").add(u.baseDamage(0)).add(u.baseDamage(1)).add(u.baseDamage(2));
            ArrayNode st = o.putArray("state");
            int pos = (u.position == null) ? -1 : b.index(u.position);
            for (int v : new int[] { pos, u.facing, u.armor, u.structure, u.moveIn, u.jumpIn, u.tmm, u.heat,
                  u.shutdown ? 1 : 0, u.engineHits, u.fcHits, u.mpHits, u.weaponHits, u.armUsed ? 1 : 0,
                  (u.destroyed || (u.position == null)) ? 1 : 0, u.doomed ? 1 : 0, u.moved ? 1 : 0, u.mode,
                  u.acted ? 1 : 0, u.fired ? 1 : 0, u.ovUsed, u.incomingHeat, u.damageDealt, u.damageTaken,
                  u.kills }) {
                st.add(v);
            }
            List<Integer> crits = u.pendingCrits;
            st.add(Math.min(crits.size(), 4));
            for (int k = 0; k < 4; k++) {
                st.add((k < crits.size()) ? crits.get(k) : 0);
            }
        }
        // this turn's order of play (side per step) and which step we're at
        ArrayNode order = msg.putArray("move_order");
        List<ASTurn> turns = game.getTurnsList();
        int first = -1;
        int last = -1;
        for (ASTurn t : turns) {
            int s = sides.indexOf(game.sideOfPlayer(t.playerId()));
            order.add(s);
            if (first < 0) {
                first = s;
            }
            last = s;
        }
        // initiative winner: moves last; in combat the loser shoots first
        int winner = (phase == 0) ? last : ((first < 0) ? 0 : 1 - first);
        msg.put("init_winner", Math.max(0, winner));
        msg.put("move_step", Math.max(0, game.getTurnIndex()));
        msg.put("turn", Math.max(1, game.getCurrentRound()));
        msg.put("phase", phase);
        msg.put("side", sides.indexOf(game.sideOfPlayer(getLocalPlayerNumber())));
        ArrayNode rd = msg.putArray("ready");
        for (ASUnit u : ready) {
            rd.add(u.getId());
        }
        msg.put("seed", 1.0 + seeds.nextInt(1_000_000));
        if (phase == 1) {
            msg.remove("move_order");
            msg.putArray("move_order");
        }
        return ASAIBridge.get().request(msg);
    }
}
