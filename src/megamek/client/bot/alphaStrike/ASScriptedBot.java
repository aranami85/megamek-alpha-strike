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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import megamek.client.ASClient;
import megamek.common.alphaStrike.ASGame;
import megamek.common.alphaStrike.play.ASBoard;
import megamek.common.alphaStrike.play.ASEngine;
import megamek.common.alphaStrike.play.ASEngine.AttackInfo;
import megamek.common.alphaStrike.play.ASEngine.MoveOption;
import megamek.common.alphaStrike.play.ASRules;
import megamek.common.alphaStrike.play.ASUnit;
import megamek.common.enums.GamePhase;
import megamek.common.event.GamePhaseChangeEvent;
import megamek.common.event.GameTurnChangeEvent;
import megamek.common.event.GameListenerAdapter;
import megamek.logging.MMLogger;

/**
 * A simple Alpha Strike opponent (the Python simulator's "scripted" bot, as_bots.py): it scores every hex it can reach
 * as (damage it can deal from there) - caution x (damage it would take there), likes its best range band, focuses
 * fire and goes for kills. It plays as a normal client, so it can join any Alpha Strike game.
 */
public class ASScriptedBot extends ASClient {

    private static final MMLogger LOGGER = MMLogger.create(ASScriptedBot.class);

    private final double caution = 0.5;
    private final int maxHeat = 1;
    private final int killHeat = 2;
    private final double aggression = 0.15;
    private final Random rng = new Random();
    private final Map<Integer, Integer> preferredRange = new HashMap<>();
    private volatile boolean busy;

    /** A scripted bot connecting to the game server. */
    public ASScriptedBot(String name, String host, int port) {
        super(name, host, port);
        getGame().addGameListener(new GameListenerAdapter() {
            @Override
            public void gameTurnChange(GameTurnChangeEvent e) {
                act();
            }

            @Override
            public void gamePhaseChange(GamePhaseChangeEvent e) {
                GamePhase p = e.getNewPhase();
                if (p.isReport() || p.isVictory() || p.isDeployment()) {   // (bots keep the automatic set-up)
                    sendDone(true);
                }
            }
        });
    }

    @Override
    protected boolean isBot() {
        return true;
    }

    private void act() {
        if (!isMyTurn() || busy) {
            return;
        }
        busy = true;
        try {
            ASGame game = getGame();
            List<ASUnit> ready = game.eligibleUnits(getLocalPlayerNumber());
            if (ready.isEmpty()) {
                return;
            }
            ASEngine engine = new ASEngine(game, () -> 1 + rng.nextInt(6));
            if (game.getPhase().isMovement()) {
                chooseMove(game, engine, ready);
            } else if (game.getPhase().isFiring()) {
                chooseAttack(game, engine, ready);
            }
        } catch (Exception ex) {
            LOGGER.error("Alpha Strike bot failed to act", ex);
        } finally {
            busy = false;
        }
    }

    /** Picks and sends one move for one of the units that may move now. */
    protected void chooseMove(ASGame game, ASEngine engine, List<ASUnit> ready) {
        ASUnit unit = ready.get(0);
        for (ASUnit u : ready) {
            if ((u.groundInches() < unit.groundInches())
                  || ((u.groundInches() == unit.groundInches()) && (u.getId() < unit.getId()))) {
                unit = u;
            }
        }
        ASBoard b = game.asBoard();
        MoveOption best = null;
        int bestFacing = unit.facing;
        double bestScore = -1e18;
        for (MoveOption m : engine.legalMoves(unit)) {
            double[] fs = scoreHex(game, engine, unit, m.dest(), m.mode());
            double score = fs[1] + rng.nextDouble() * 0.01;
            if (score > bestScore) {
                bestScore = score;
                best = m;
                bestFacing = (int) fs[0];
            }
        }
        if (best == null) {
            return;
        }
        int x = best.dest() % b.width;
        int y = best.dest() / b.width;
        moveUnit(unit.getId(), x, y, best.mode(), bestFacing);
    }

    /** Returns {facing, score} for moving unit to dest (as_bots.ScriptedBot.score_hex). */
    private double[] scoreHex(ASGame game, ASEngine engine, ASUnit unit, int dest, int mode) {
        ASBoard b = game.asBoard();
        List<ASUnit> enemies = engine.enemies(unit);
        if (enemies.isEmpty()) {
            return new double[] { unit.facing, 0 };
        }
        ASUnit near = enemies.get(0);
        int nearD = b.distance(dest, b.index(near.position));
        for (ASUnit e : enemies) {
            int d = b.distance(dest, b.index(e.position));
            if (d < nearD) {
                near = e;
                nearD = d;
            }
        }
        int nearPos = b.index(near.position);
        int facing = (nearPos != dest) ? b.faceToward(dest, nearPos) : unit.facing;
        double offense = 0;
        double threat = 0;
        for (ASUnit e : enemies) {
            if (e.doomed) {
                continue;
            }
            int ePos = b.index(e.position);
            int dist = b.distance(dest, ePos);
            if (dist > 21) {
                continue;
            }
            int eMode = e.moved ? e.mode : ASUnit.GROUND;
            boolean physical = dist == 1;
            AttackInfo info = engine.checkAttack(unit, dest, mode, facing, e, ePos, eMode, e.facing, physical);
            if (info != null) {
                offense = Math.max(offense, attackValue(e, info, 0));
            }
            int eFace = b.faceToward(ePos, dest);
            AttackInfo back = engine.checkAttack(e, ePos, eMode, eFace, unit, dest, mode, facing, physical);
            if (back != null) {
                threat += ASRules.hitChance(back.tn()) * (back.damage() + (back.rear() ? 1 : 0));
            }
        }
        threat *= unit.pv / (double) unit.hpFull();
        double score = offense - caution * threat;
        if (offense == 0) {
            score -= 0.3 * nearD;
        }
        score -= aggression * Math.max(0, nearD - preferredRange(game, unit));
        return new double[] { facing, score };
    }

    private int preferredRange(ASGame game, ASUnit unit) {
        return preferredRange.computeIfAbsent(unit.getId(), id -> {
            ASRules r = game.getRules();
            int sk = unit.skill + 2;
            int best = 0;
            double bestValue = -1;
            for (int band = 0; band < 3; band++) {
                double v = unit.damageAt(band) * ASRules.hitChance(sk + r.rangeMods[band]);
                if (v > bestValue) {
                    bestValue = v;
                    best = band;
                }
            }
            return r.rangeBands[best];
        });
    }

    private static double attackValue(ASUnit target, AttackInfo info, int ov) {
        double damage = info.damage() + ov + (info.rear() ? 1 : 0);
        double remaining = target.hp();
        double value = ASRules.hitChance(info.tn()) * Math.min(damage, remaining);
        if (damage >= remaining) {
            value *= 1.5;
        }
        if (target.armor == 0) {
            value *= 1.2;
        }
        return value * (target.pv / (double) target.hpFull());
    }

    private int chooseOv(ASUnit unit, ASUnit target, AttackInfo info) {
        if (info.maxOv() <= 0) {
            return 0;
        }
        int engineHit = (unit.engineHits != 0) ? 1 : 0;
        int room = maxHeat - unit.heat - engineHit;
        double remaining = target.hp();
        if ((info.damage() < remaining) && (remaining <= info.damage() + info.maxOv())) {
            room = Math.max(room, killHeat - unit.heat - engineHit);
        }
        return Math.max(0, Math.min(info.maxOv(), room));
    }

    /** Picks and sends one attack for one of the units that may attack now. */
    protected void chooseAttack(ASGame game, ASEngine engine, List<ASUnit> ready) {
        double bestValue = -1;
        ASUnit bestUnit = null;
        AttackInfo bestInfo = null;
        int bestOv = 0;
        for (ASUnit unit : ready) {
            for (AttackInfo info : engine.attackOptions(unit)) {
                ASUnit target = game.getUnit(info.target());
                if ((target == null) || target.doomed) {
                    continue;
                }
                int ov = chooseOv(unit, target, info);
                double value = attackValue(target, info, ov);
                if ((bestUnit == null) || (value > bestValue)) {
                    bestValue = value;
                    bestUnit = unit;
                    bestInfo = info;
                    bestOv = ov;
                }
            }
        }
        if (bestUnit == null) {
            for (ASUnit unit : ready) {           // nothing in front: maybe its rear weapons can shoot something
                int rear = rearPick(game, engine, unit, null);
                if (rear >= 0) {
                    attack(unit.getId(), -1, false, 0, rear);
                    return;
                }
            }
            attack(ready.get(0).getId(), -1, false, 0);
        } else {
            attack(bestUnit.getId(), bestInfo.target(), bestInfo.physical(), bestOv,
                  rearPick(game, engine, bestUnit, bestInfo));
        }
    }

    /**
     * A target for the unit's REAR weapons, or -1. Used when there's no normal attack, or when the rear shot is
     * likelier to hit and the normal attack still keeps at least 1 damage (rear damage comes off it).
     */
    protected int rearPick(ASGame game, ASEngine engine, ASUnit unit, AttackInfo main) {
        AttackInfo best = null;
        double bestValue = -1;
        for (AttackInfo r : engine.rearOptions(unit)) {
            ASUnit t = game.getUnit(r.target());
            if ((t == null) || t.doomed || ((main != null) && (r.target() == main.target()))) {
                continue;
            }
            double v = attackValue(t, r, 0);
            if (v > bestValue) {
                bestValue = v;
                best = r;
            }
        }
        if (best == null) {
            return -1;
        }
        if (main == null) {
            return best.target();
        }
        boolean keepsDamage = main.physical() || (main.damage() - (int) best.damage() >= 1);
        return (keepsDamage && (best.tn() <= main.tn())) ? best.target() : -1;
    }
}
