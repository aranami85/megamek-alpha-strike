/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.common.alphaStrike.play;

import static megamek.common.alphaStrike.play.ASUnit.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.IntSupplier;

import megamek.common.alphaStrike.ASGame;

/**
 * The Alpha Strike rules on hexes (ASCE; page numbers in brackets) - a port of the Python simulator's as_engine.py /
 * as_core engine.rs. Works on an {@link ASGame}; the server uses all of it, clients use the parts that show what is
 * possible (legal moves, to-hit numbers).
 *
 * A turn: initiative (2d6 per side, ties re-roll; the loser moves first) - movement in pairs, loser first (a side with
 * 2x/3x as many units left moves 2/3 per pair) [p. 32] - combat: the loser makes ALL its attacks, then the winner
 * [p. 40]; damage lands at once, but destruction and crits wait for the End Phase - End Phase: crits, wrecks removed,
 * heat [p. 53].
 */
public class ASEngine {

    private final ASGame game;
    private final IntSupplier d6;

    /**
     * One possible attack. band 0/1/2 = S/M/L, -1 = physical. rear = the attacker is in the target's rear arc.
     * arc = which weapons: ARC_FRONT (normal), ARC_TURRET (TUR, outside the front arc), ARC_REAR (REAR weapons).
     */
    public record AttackInfo(int target, boolean physical, int tn, int band, double damage, int maxOv, boolean rear,
          boolean woods, boolean partial, int arc) {}

    public static final int ARC_FRONT = 0, ARC_TURRET = 1, ARC_REAR = 2;

    /** One place a unit can end its move. */
    public record MoveOption(int dest, int mode, int cost) {}

    public ASEngine(ASGame game, IntSupplier d6) {
        this.game = game;
        this.d6 = d6;
    }

    private ASBoard board() {
        return game.asBoard();
    }

    private ASRules rules() {
        return game.getRules();
    }

    public int roll2d6() {
        return d6.getAsInt() + d6.getAsInt();
    }

    // --- units ---------------------------------------------------------------------------------

    public List<ASUnit> enemies(ASUnit unit) {
        List<ASUnit> out = new ArrayList<>();
        int side = game.sideOf(unit);
        for (ASUnit u : game.getUnits()) {
            if (u.alive() && game.sideOf(u) != side) {
                out.add(u);
            }
        }
        return out;
    }

    private ASUnit occupantAt(int hex) {
        for (ASUnit u : game.getUnits()) {
            if (u.alive() && u.position != null && board().index(u.position) == hex) {
                return u;
            }
        }
        return null;
    }

    private boolean occupied(int hex, Map<Integer, ASUnit> occ) {
        return occ.containsKey(hex);
    }

    private Map<Integer, ASUnit> occupancy() {
        Map<Integer, ASUnit> occ = new LinkedHashMap<>();
        for (ASUnit u : game.getUnits()) {
            if (u.alive() && u.position != null) {
                occ.put(board().index(u.position), u);
            }
        }
        return occ;
    }

    // --- movement [pp. 32-38] -----------------------------------------------------------------------

    /** Every (hex, mode) the unit can end its move in, in the same order as the Python simulator. */
    public List<MoveOption> legalMoves(ASUnit unit) {
        ASBoard b = board();
        ASRules r = rules();
        int pos = b.index(unit.position);
        List<MoveOption> out = new ArrayList<>();
        if (!unit.alive() || unit.immobile()) {
            out.add(new MoveOption(pos, IMMOBILE, 0));
            return out;
        }
        out.add(new MoveOption(pos, STAND, 0));
        Map<Integer, ASUnit> occ = occupancy();
        int groundIn = unit.groundInches();
        int mp = 0;
        Map<Integer, Integer> best = new LinkedHashMap<>();
        if (groundIn >= 1) {
            mp = r.toHexes(groundIn);
            int sprintMp = r.toHexes(Math.ceil(groundIn * r.sprintFactor));
            boolean[] enemyHex = new boolean[b.n];
            for (ASUnit e : enemies(unit)) {
                if (e.position != null) {
                    enemyHex[b.index(e.position)] = true;
                }
            }
            best.put(pos, 0);
            PriorityQueue<int[]> frontier = new PriorityQueue<>((x, y) -> x[0] != y[0] ? Integer.compare(x[0], y[0])
                  : Integer.compare(x[1], y[1]));
            frontier.add(new int[] { 0, pos });
            while (!frontier.isEmpty()) {
                int[] item = frontier.poll();
                int cost = item[0];
                int here = item[1];
                if (cost > best.getOrDefault(here, 1 << 30)) {
                    continue;
                }
                for (int dir = 0; dir < 6; dir++) {
                    int nxt = b.neighbor(here, dir);
                    if (nxt < 0 || enemyHex[nxt]) {
                        continue;
                    }
                    Integer step = b.enterCost(here, nxt, r);
                    if (step == null) {
                        continue;
                    }
                    int total = cost + step;
                    if (total <= sprintMp && total < best.getOrDefault(nxt, 1 << 30)) {
                        best.put(nxt, total);
                        frontier.add(new int[] { total, nxt });
                    }
                }
            }
            for (Map.Entry<Integer, Integer> e : best.entrySet()) {
                int dest = e.getKey();
                if (dest != pos && !occupied(dest, occ)) {
                    out.add(new MoveOption(dest, e.getValue() <= mp ? GROUND : SPRINT, e.getValue()));
                }
            }
            // Minimum movement: a mobile unit can always move 2" (1 hex) [p. 32]
            for (int dir = 0; dir < 6; dir++) {
                int nxt = b.neighbor(pos, dir);
                if (nxt < 0 || occupied(nxt, occ)) {
                    continue;
                }
                boolean isGround = best.containsKey(nxt) && best.get(nxt) <= mp;
                if (!isGround && b.enterCost(pos, nxt, r) != null) {
                    out.add(new MoveOption(nxt, MINIMUM, 1));
                }
            }
        }
        int jump = r.toHexes(unit.jumpIn);
        if (jump > 0 && b.waterDepth(pos) == 0) {          // can't jump out of water [p. 38]
            int ceiling = b.floor(pos) + Math.floorDiv(unit.jumpIn, 2);
            for (int dest : b.within(pos, jump)) {
                if (dest == pos || occupied(dest, occ) || !b.passable(dest)) {
                    continue;
                }
                int[] line = b.firstLine(pos, dest);
                boolean clear = true;
                for (int k = 1; k < line.length - 1; k++) {
                    if (line[k] >= 0 && b.terrainTop(line[k]) > ceiling) {
                        clear = false;
                        break;
                    }
                }
                if (clear) {
                    out.add(new MoveOption(dest, JUMP, b.distance(pos, dest)));
                }
            }
        }
        return out;
    }

    /** Is this a legal move for the unit? (mode must match one of the legal options for that hex) */
    public boolean isLegalMove(ASUnit unit, int dest, int mode) {
        return legalMoves(unit).stream().anyMatch(m -> m.dest() == dest && m.mode() == mode);
    }

    public void applyMove(ASUnit unit, int dest, int mode, int facing) {
        unit.position = board().coords(dest);
        unit.mode = mode;
        unit.moved = true;
        if (mode != IMMOBILE) {
            unit.facing = Math.floorMod(facing, 6);
        }
    }

    // --- attacks [pp. 42-48] ----------------------------------------------------------------------

    /** Can att standing at aPos attack tgt at tPos? Returns the to-hit number and damage, or null. */
    public AttackInfo checkAttack(ASUnit att, int aPos, int aMode, int aFacing, ASUnit tgt, int tPos, int tMode,
          int tFacing, boolean physical) {
        return attackCore(att, aPos, aMode, aFacing, tgt, tPos, tMode, tFacing, physical, false);
    }

    /**
     * A REAR-weapons attack [p. 79]: only on a target outside the front arc, REAR damage, +1 to hit, no overheat.
     * It can go with a normal attack in the same turn, but the normal attack loses that much damage.
     */
    public AttackInfo checkRearAttack(ASUnit att, int aPos, int aMode, int aFacing, ASUnit tgt, int tPos, int tMode,
          int tFacing) {
        if (!att.has(F_REAR)) {
            return null;
        }
        return attackCore(att, aPos, aMode, aFacing, tgt, tPos, tMode, tFacing, false, true);
    }

    private AttackInfo attackCore(ASUnit att, int aPos, int aMode, int aFacing, ASUnit tgt, int tPos, int tMode,
          int tFacing, boolean physical, boolean rearWeapons) {
        if (att.shutdown || aMode == SPRINT || aPos == tPos) {
            return null;
        }
        ASBoard b = board();
        ASRules r = rules();
        int dist = b.distance(aPos, tPos);
        if (dist > r.rangeBands[2]) {
            return null;
        }
        boolean outsideFront = ASBoard.angleOff(aFacing, b.bearing(aPos, tPos)) > 90.01;
        int arc = ARC_FRONT;
        if (rearWeapons) {
            if (!outsideFront) {
                return null;
            }
            arc = ARC_REAR;
        } else if (outsideFront) {
            if (physical || !att.has(F_TUR)) {
                return null;
            }
            arc = ARC_TURRET;                       // the turret fires all round, with its own damage [p. 80]
        }
        int band;
        double damage;
        if (physical) {
            int reach = att.has(F_MEL) ? 2 : 1;
            if (dist != 1 || Math.abs(b.floor(aPos) - b.floor(tPos)) > reach) {
                return null;
            }
            band = -1;
            damage = att.size + (att.has(F_MEL) ? 1 : 0);
        } else {
            band = r.rangeBand(dist);
            if (band < 0) {
                return null;
            }
            damage = switch (arc) {
                case ARC_TURRET -> att.turretDamageAt(band);
                case ARC_REAR -> att.rearDamageAt(band);
                default -> att.damageAt(band);
            };
            if (damage <= 0) {
                return null;
            }
        }
        ASBoard.LOS los = b.los(aPos, tPos);
        if (los.blocked()) {
            return null;
        }
        int tn = att.skill;
        if (aMode == JUMP) {
            tn += r.attackerJumped;
        } else if (aMode == STAND || aMode == IMMOBILE) {
            tn += r.attackerStoodStill;
        }
        tn += tgt.tmmNow(tMode, r);
        if (los.woods()) {
            tn += r.woodsMod;
        }
        if (los.partial()) {
            tn += r.partialCoverMod;
        }
        if (!physical) {
            tn += r.fireControlMod * att.fcHits;
            tn += att.heat;
            tn += r.rangeMods[c3Band(att, tPos, band)];      // C3: the closest networked spotter's range [p. 80]
        }
        tn += specialMods(att, tgt, tMode, physical, band, null);
        if (arc == ARC_REAR) {
            tn += 1;
        }
        if (tn > 12) {
            return null;
        }
        boolean rear = b.inRearArc(tPos, tFacing, aPos);
        int maxOv = (physical || arc == ARC_REAR) ? 0 : att.maxOv(band);
        return new AttackInfo(tgt.getId(), physical, tn, band, damage, maxOv, rear, los.woods(), los.partial(), arc);
    }

    /**
     * Plain-words answer to "can this unit shoot that one, and why that number?" for the window: either the roll
     * needed with each modifier ("needs 9+: skill 4, TMM +2, medium +2, woods +1") or why there's no attack.
     */
    public String explainAttack(ASUnit att, ASUnit tgt, boolean physical) {
        if (tgt.doomed || tgt.destroyed) {
            return tgt.name + " is already destroyed";
        }
        if (att.position == null || tgt.position == null) {
            return "not on the map";
        }
        if (att.shutdown) {
            return att.name + " is shut down";
        }
        if (att.mode == SPRINT) {
            return att.name + " sprinted this turn (no attacks)";
        }
        ASBoard b = board();
        ASRules r = rules();
        int aPos = b.index(att.position);
        int tPos = b.index(tgt.position);
        int dist = b.distance(aPos, tPos);
        if (physical && dist != 1) {
            return "physical attacks need an adjacent target";
        }
        if (dist > r.rangeBands[2]) {
            return "out of range (" + dist + " hexes, long range ends at " + r.rangeBands[2] + ")";
        }
        int arc = ARC_FRONT;
        if (ASBoard.angleOff(att.facing, b.bearing(aPos, tPos)) > 90.01) {
            if (!physical && att.has(F_TUR)) {
                arc = ARC_TURRET;
            } else if (!physical && att.has(F_REAR)) {
                arc = ARC_REAR;
            } else {
                return "outside " + att.name + "'s front arc - turn to face it";
            }
        }
        int band = physical ? -1 : r.rangeBand(dist);
        double armDamage = band < 0 ? 0 : switch (arc) {
            case ARC_TURRET -> att.turretDamageAt(band);
            case ARC_REAR -> att.rearDamageAt(band);
            default -> att.damageAt(band);
        };
        String armName = switch (arc) {
            case ARC_TURRET -> "turret";
            case ARC_REAR -> "rear weapons";
            default -> "";
        };
        if (!physical && (band < 0 || armDamage <= 0)) {
            return att.name + (armName.isEmpty() ? "" : "'s " + armName) + " has no damage at "
                  + (band < 0 ? "that range" : BAND_NAMES[band] + " range");
        }
        ASBoard.LOS los = b.los(aPos, tPos);
        if (los.blocked()) {
            return "no line of sight (" + b.blockReason(aPos, tPos) + ")";
        }
        List<String> mods = new ArrayList<>();
        int tn = att.skill;
        mods.add("skill " + att.skill);
        if (att.mode == JUMP && r.attackerJumped != 0) {
            tn += r.attackerJumped;
            mods.add("you jumped " + signed(r.attackerJumped));
        } else if ((att.mode == STAND || att.mode == IMMOBILE) && r.attackerStoodStill != 0) {
            tn += r.attackerStoodStill;
            mods.add("stood still " + signed(r.attackerStoodStill));
        }
        int tmm = tgt.tmmNow(tgt.mode, r);
        tn += tmm;
        mods.add("target TMM " + signed(tmm));
        if (los.woods()) {
            tn += r.woodsMod;
            mods.add("woods " + signed(r.woodsMod));
        }
        if (los.partial()) {
            tn += r.partialCoverMod;
            mods.add("partial cover " + signed(r.partialCoverMod));
        }
        if (!physical) {
            if (att.fcHits > 0) {
                tn += r.fireControlMod * att.fcHits;
                mods.add("fire control hits " + signed(r.fireControlMod * att.fcHits));
            }
            if (att.heat > 0) {
                tn += att.heat;
                mods.add("heat " + signed(att.heat));
            }
            int modBand = c3Band(att, tPos, band);
            tn += r.rangeMods[modBand];
            if (modBand != band) {
                mods.add(BAND_NAMES[modBand] + " range via C3 (" + c3Spotter(att, tPos, band).name + " spots) "
                      + signed(r.rangeMods[modBand]));
            } else {
                mods.add(BAND_NAMES[band] + " range " + signed(r.rangeMods[band]));
            }
        } else if (Math.abs(b.floor(aPos) - b.floor(tPos)) > (att.has(F_MEL) ? 2 : 1)) {
            return "too big a height difference for a physical attack";
        }
        tn += specialMods(att, tgt, tgt.mode, physical, band, mods);
        if (arc == ARC_REAR) {
            tn += 1;
            mods.add("rear weapons +1");
        } else if (arc == ARC_TURRET) {
            mods.add("turret");
        }
        String text = "needs " + tn + "+: " + String.join(", ", mods);
        return tn > 12 ? "can't hit - " + text + " (more than 12)" : text;
    }

    private static final String[] BAND_NAMES = { "short", "medium", "long" };

    private static String signed(int v) {
        return v >= 0 ? "+" + v : String.valueOf(v);
    }

    /** Every legal attack for a unit right now (combat phase). */
    public List<AttackInfo> attackOptions(ASUnit unit) {
        List<AttackInfo> out = new ArrayList<>();
        if (unit.acted || unit.shutdown || !unit.alive() || unit.position == null) {
            return out;
        }
        ASBoard b = board();
        int pos = b.index(unit.position);
        for (ASUnit t : enemies(unit)) {
            if (t.position == null || t.doomed) {
                continue;                       // (a unit destroyed this turn is no target any more)
            }
            for (boolean physical : new boolean[] { false, true }) {
                AttackInfo info = checkAttack(unit, pos, unit.mode, unit.facing, t, b.index(t.position), t.mode,
                      t.facing, physical);
                if (info != null) {
                    out.add(info);
                }
            }
        }
        return out;
    }

    /** Every legal REAR-weapons attack for a unit right now (targets outside its front arc). */
    public List<AttackInfo> rearOptions(ASUnit unit) {
        List<AttackInfo> out = new ArrayList<>();
        if (unit.acted || unit.shutdown || !unit.alive() || unit.position == null || !unit.has(F_REAR)) {
            return out;
        }
        ASBoard b = board();
        int pos = b.index(unit.position);
        for (ASUnit t : enemies(unit)) {
            if (t.position != null && !t.doomed) {
                AttackInfo info = checkRearAttack(unit, pos, unit.mode, unit.facing, t, b.index(t.position), t.mode,
                      t.facing);
                if (info != null) {
                    out.add(info);
                }
            }
        }
        return out;
    }

    public List<String> applyAttack(ASUnit unit, int targetId, boolean physical, int ov) {
        return applyAttack(unit, targetId, physical, ov, -1);
    }

    /** Make an attack (target -1 = no attack) and/or a REAR attack (rearTargetId). Returns report lines. */
    public List<String> applyAttack(ASUnit unit, int targetId, boolean physical, int ov, int rearTargetId) {
        List<String> report = new ArrayList<>();
        unit.acted = true;
        ASBoard b = board();
        AttackInfo rearInfo = null;
        ASUnit rearTgt = rearTargetId >= 0 ? game.getUnit(rearTargetId) : null;
        if (rearTgt != null && rearTgt.alive() && !rearTgt.doomed && game.sideOf(rearTgt) != game.sideOf(unit)) {
            rearInfo = checkRearAttack(unit, b.index(unit.position), unit.mode, unit.facing, rearTgt,
                  b.index(rearTgt.position), rearTgt.mode, rearTgt.facing);
        }
        if (rearTargetId >= 0 && rearInfo == null) {
            report.add(unit.name + " has no legal rear-weapons attack on that target.");
        }
        if (targetId < 0 && rearInfo == null) {
            report.add(unit.name + " holds fire.");
            return report;
        }
        // REAR first: every point it can deal comes off the forward (or turret) attack [p. 79]
        int reduction = 0;
        if (rearInfo != null) {
            reduction = (int) rearInfo.damage();
            unit.fired = true;
            resolve(unit, rearTgt, rearInfo, 0, rearInfo.damage(), "rear weapons", report);
        }
        if (targetId < 0) {
            return report;
        }
        ASUnit tgt = game.getUnit(targetId);
        AttackInfo info = null;
        if (tgt != null && tgt.alive() && !tgt.doomed && game.sideOf(tgt) != game.sideOf(unit)) {
            info = checkAttack(unit, b.index(unit.position), unit.mode, unit.facing, tgt, b.index(tgt.position),
                  tgt.mode, tgt.facing, physical);
        }
        if (info == null) {
            report.add(unit.name + " has no legal attack on that target.");
            return report;
        }
        double base = info.damage();
        if (!physical && reduction > 0) {
            base = Math.max(0, (base >= 1 ? (int) base : 0) - reduction);
            if (base <= 0) {
                report.add(unit.name + " has no damage left for " + tgt.name + " after its rear attack.");
                return report;
            }
        }
        ov = Math.max(0, Math.min(ov, info.maxOv()));
        if (!physical) {
            unit.fired = true;
            unit.ovUsed = ov;
        }
        String how = physical ? "physical attack" : (info.arc() == ARC_TURRET ? "turret" : "");
        resolve(unit, tgt, info, ov, base, how, report);
        return report;
    }

    /** Rolls one attack and deals its damage. how = "", "turret", "rear weapons" or "physical attack". */
    private void resolve(ASUnit unit, ASUnit tgt, AttackInfo info, int ov, double baseDamage, String how,
          List<String> report) {
        boolean physical = info.physical();
        int roll = roll2d6();
        boolean hit = roll >= info.tn();
        String kind = physical ? "physical attack" : ("SML".charAt(info.band()) + " range"
              + (how.isEmpty() ? "" : ", " + how));
        report.add(unit.name + " attacks " + tgt.name + " (" + kind + ", needs " + info.tn()
              + (ov > 0 ? ", overheat " + ov : "") + "): rolls " + roll + " - " + (hit ? "HIT" : "miss"));
        if (!hit) {
            return;
        }
        double dmg = baseDamage;
        if (dmg == 0.5) {                       // 0* = 1 point on a 4+ [p. 48]
            dmg = d6.getAsInt() >= 4 ? 1 : 0;
        }
        int damage = (int) dmg;
        if (damage > 0) {
            damage += ov;
            if (info.rear()) {
                damage += rules().rearDamageBonus;
            }
            if (physical && ((unit.has(F_TSM) && unit.heat >= 1) || unit.has(F_ITSM))) {
                damage += 1;                     // TSM (running hot) / industrial TSM [pp. 78-79]
                report.add("  +1 damage from " + (unit.has(F_ITSM) ? "industrial TSM" : "TSM"));
            }
            boolean fromFront = !board().inRearArc(board().index(tgt.position), tgt.facing,
                  board().index(unit.position));
            if (!physical && tgt.has(F_AMS) && unit.has(F_MISSILE) && (fromFront || tgt.has(F_TUR)) && damage > 1) {
                damage -= 1;                     // AMS: -1 (to a minimum of 1) vs missile units, front arc [p. 76]
                report.add("  " + tgt.name + "'s AMS stops 1 point");
            }
            if (tgt.has(F_SHLD)) {
                damage -= 1;                     // shield: -1 (to a minimum of 0) [p. 76]
                report.add("  " + tgt.name + "'s shield stops 1 point");
            }
        }
        damage(tgt, damage, unit, roll == 12, true, report);
        if (!physical && info.arc() == ARC_FRONT) {         // HT only with the normal attack
            int heat = unit.heatAt(info.band());
            if (heat != 0 && tgt.has(F_FR)) {
                report.add("  " + tgt.name + " is fire resistant: no heat");
            } else if (heat != 0) {
                tgt.incomingHeat += heat;
            }
        }
    }

    // --- damage and crits [pp. 48-50] ---------------------------------------------------------------

    public void damage(ASUnit tgt, int amount, ASUnit source, boolean natural12, boolean canCrit,
          List<String> report) {
        if (amount <= 0) {
            return;
        }
        if (natural12 && canCrit) {
            report.add("  natural 12!");
            critical(tgt, report);
        }
        int toArmor = Math.min(tgt.armor, amount);
        tgt.armor -= toArmor;
        int toStruct = Math.min(tgt.structure, amount - toArmor);
        tgt.structure -= toStruct;
        int leftOver = amount - toArmor - toStruct;
        tgt.damageTaken += toArmor + toStruct;
        if (source != null) {
            source.damageDealt += toArmor + toStruct;
        }
        report.add("  " + tgt.name + " takes " + amount + ": armor " + tgt.armor + ", structure " + tgt.structure);
        if (leftOver > 0 || (toStruct > 0 && tgt.structure <= 0)) {
            if (!tgt.doomed) {
                tgt.doomed = true;
                if (source != null) {
                    source.kills++;
                }
                report.add("  " + tgt.name + " is DESTROYED");
            }
            return;
        }
        if (!canCrit) {
            return;
        }
        if (toStruct > 0) {
            critical(tgt, report);
            if (tgt.has(F_BAR)) {
                critical(tgt, report);
            }
        } else if (tgt.has(F_BAR)) {
            critical(tgt, report);
        }
    }

    public void critical(ASUnit tgt, List<String> report) {
        if (tgt.doomed) {
            return;
        }
        if (tgt.has(F_ARM) && !tgt.armUsed) {
            tgt.armUsed = true;
            report.add("  " + tgt.name + "'s armored components stop a critical hit");
            return;
        }
        int roll = roll2d6();
        int rolled = roll;
        if (tgt.has(F_CR)) {
            roll -= 2;                           // critical-resistant [p. 86]: 1 or less = no critical
        }
        int effect = switch (roll) {
            case 2 -> C_AMMO;
            case 3, 11 -> C_ENGINE;
            case 4, 10 -> C_FC;
            case 6, 8 -> C_WEAPON;
            case 7 -> C_MP;
            case 12 -> C_DESTROYED;
            default -> 0;
        };
        if (effect != 0) {
            tgt.pendingCrits.add(effect);
            String[] names = { "", "ammo hit", "engine hit", "fire control hit", "weapon hit", "MP hit", "destroyed" };
            report.add("  critical hit on " + tgt.name + " (rolled " + rolled + (rolled != roll ? " -2 CR" : "") + "): "
                  + names[effect]);
        }
    }

    private static int halve(int value, int minLoss) {
        int loss = Math.max(minLoss, (int) (value / 2.0 + 0.5));
        return Math.max(0, value - loss);
    }

    private void applyCrits(ASUnit u, List<String> report) {
        while (!u.pendingCrits.isEmpty()) {
            int effect = u.pendingCrits.remove(0);
            switch (effect) {
                case C_AMMO -> {
                    if (u.has(F_ENE) || u.has(F_CASEII)) {
                        continue;
                    }
                    if (u.has(F_CASE)) {
                        damage(u, 1, null, false, true, report);
                        continue;
                    }
                    u.doomed = true;
                    report.add("  " + u.name + "'s ammunition explodes!");
                }
                case C_ENGINE -> {
                    u.engineHits++;
                    if (u.engineHits >= 2) {
                        u.doomed = true;
                        report.add("  " + u.name + "'s engine is destroyed");
                    }
                }
                case C_FC -> u.fcHits++;
                case C_WEAPON -> {
                    if (u.damageAt(0) <= 0 && u.damageAt(1) <= 0 && u.damageAt(2) <= 0) {
                        damage(u, 1, null, false, false, report);
                    } else {
                        u.weaponHits++;
                    }
                }
                case C_MP -> {
                    if (u.moveIn <= 0 && u.jumpIn <= 0) {
                        damage(u, 1, null, false, false, report);
                    } else {
                        u.mpHits++;
                        u.moveIn = halve(u.moveIn, 2);
                        u.jumpIn = u.jumpIn != 0 ? halve(u.jumpIn, 2) : 0;
                        u.tmm = halve(u.tmm, 1);
                    }
                }
                case C_DESTROYED -> u.doomed = true;
                default -> { }
            }
        }
    }

    // --- turn structure -------------------------------------------------------------------------------

    /** Rolls 2d6 for each side until they differ. Returns the winning side (team id) and writes the report. */
    public int rollInitiative(int sideA, int sideB, List<String> report) {
        int a;
        int bRoll;
        do {
            a = roll2d6();
            bRoll = roll2d6();
        } while (a == bRoll);
        int winner = a > bRoll ? sideA : sideB;
        report.add("Initiative: " + game.sideName(sideA) + " " + a + ", " + game.sideName(sideB) + " " + bRoll
              + " - " + game.sideName(winner) + " wins (moves last, shoots last)");
        return winner;
    }

    /** Which side moves at each step [p. 32]: pairs of moves, loser first. */
    public List<Integer> movementOrder(int loser, int winner) {
        Map<Integer, Integer> left = new LinkedHashMap<>();
        left.put(loser, game.aliveCount(loser));
        left.put(winner, game.aliveCount(winner));
        List<Integer> order = new ArrayList<>();
        while (left.get(loser) > 0 || left.get(winner) > 0) {
            Map<Integer, Integer> counts = new LinkedHashMap<>();
            for (int s : new int[] { loser, winner }) {
                int other = left.get(s == loser ? winner : loser);
                int mine = left.get(s);
                int c = other == 0 ? mine : (mine >= 2 * other ? mine / other : 1);
                counts.put(s, Math.min(c, mine));
            }
            for (int s : new int[] { loser, winner }) {
                for (int k = 0; k < counts.get(s); k++) {
                    order.add(s);
                }
                left.put(s, left.get(s) - counts.get(s));
            }
        }
        return order;
    }

    /** The initiative loser makes all its attacks, then the winner [p. 40]. */
    public List<Integer> combatOrder(int loser, int winner) {
        List<Integer> order = new ArrayList<>();
        for (int k = 0; k < game.aliveCount(loser); k++) {
            order.add(loser);
        }
        for (int k = 0; k < game.aliveCount(winner); k++) {
            order.add(winner);
        }
        return order;
    }

    public void startTurn() {
        for (ASUnit u : game.getUnits()) {
            if (u.alive()) {
                u.moved = u.acted = u.fired = false;
                u.mode = u.immobile() ? IMMOBILE : STAND;
                u.ovUsed = 0;
                u.incomingHeat = 0;
            }
        }
    }

    /** End Phase [p. 53]: crits take effect, wrecks are removed, heat. Returns report lines. */
    public List<String> endPhase() {
        List<String> report = new ArrayList<>();
        ASRules r = rules();
        for (ASUnit u : game.getUnits()) {
            if (u.alive()) {
                applyCrits(u, report);
            }
        }
        for (ASUnit u : game.getUnits()) {
            if (u.alive() && u.doomed) {
                u.destroyed = true;
                report.add(u.name + " is removed from the battlefield.");
            }
        }
        for (ASUnit u : game.getUnits()) {
            if (!u.alive() || u.position == null) {
                continue;
            }
            boolean wasShutdown = u.shutdown;
            int heat = u.heat + u.ovUsed + ((u.fired && u.engineHits != 0) ? 1 : 0);
            if (board().waterDepth(board().index(u.position)) >= 1) {
                heat -= 1;
            }
            if (!u.fired) {
                heat = 0;
            }
            if (wasShutdown) {
                heat = 0;
                u.shutdown = false;
                report.add(u.name + " restarts.");
            }
            heat += Math.min(u.incomingHeat, r.maxHtHeatPerTurn);
            u.heat = Math.max(0, Math.min(heat, r.shutdownHeat));
            if (u.heat >= r.shutdownHeat) {
                u.shutdown = true;
                report.add(u.name + " overheats and SHUTS DOWN.");
            }
        }
        return report;
    }

    /** Share of the side's PV still fighting, weighted by remaining armor + structure. */
    public double score(int side) {
        double total = 0;
        double left = 0;
        for (ASUnit u : game.getUnits()) {
            if (game.sideOf(u) != side) {
                continue;
            }
            total += u.pv;
            if (u.alive()) {
                left += u.pv * (double) u.hp() / u.hpFull();
            }
        }
        return left / Math.max(total, 1);
    }

    /** Places a side's units in its deployment strip (3 hexes deep along its edge), facing the map center. */
    public void deploy(int side, int edge, java.util.Random rng) {
        ASBoard b = board();
        deployEdges.put(side, edge);
        List<Integer> spots = new ArrayList<>();
        for (int i = 0; i < b.n; i++) {
            if (inStrip(edge, i) && b.passable(i) && occupantAt(i) == null) {
                spots.add(i);
            }
        }
        java.util.Collections.shuffle(spots, rng);
        int center = (b.height / 2) * b.width + b.width / 2;
        for (ASUnit u : game.getUnits()) {
            if (game.sideOf(u) != side || u.position != null || spots.isEmpty()) {
                continue;
            }
            int pos = spots.remove(spots.size() - 1);
            u.position = b.coords(pos);
            u.facing = b.faceToward(pos, center);
        }
    }

    // --- special abilities that change the roll [pp. 76-79] ------------------------------------------------------

    /** Target number changes from special abilities (adds plain-words notes to mods if it isn't null). */
    private int specialMods(ASUnit att, ASUnit tgt, int tMode, boolean physical, int band, List<String> mods) {
        int tn = 0;
        if (!physical) {
            if (tgt.has(F_STL) && band >= 1) {
                tn += band;                                  // stealth: +1 Medium, +2 Long
                note(mods, "stealth +" + band);
            }
            if ((tgt.has(F_MAS) || tgt.has(F_LMAS)) && (tMode == STAND || tMode == IMMOBILE)) {
                int m = tgt.has(F_MAS) ? 3 : 2;              // mimetic armor on a unit that stood still
                tn += m;
                note(mods, (tgt.has(F_MAS) ? "mimetic armor +" : "light mimetic armor +") + m);
            }
            if (att.has(F_SHLD)) {
                tn += 1;
                note(mods, "your shield +1");
            }
        } else if (att.has(F_ITSM)) {
            tn += 2;
            note(mods, "industrial TSM +2");
        }
        return tn;
    }

    private static void note(List<String> mods, String text) {
        if (mods != null) {
            mods.add(text);
        }
    }

    // --- ECM and C3 [pp. 77-82] ---------------------------------------------------------------------------------

    /** An active ECM bubble: who makes it, centre hex, radius in hexes, Angel? Nova? */
    public record EcmBubble(ASUnit unit, int center, int radius, boolean angel, boolean nova) { }

    /** A C3 link to draw: two units of the same network and whether data flows over it right now. */
    public record C3Link(ASUnit a, ASUnit b, boolean working) { }

    private long c3Key = Long.MIN_VALUE;
    private Map<Integer, Boolean> c3Cache = new java.util.HashMap<>();
    private List<EcmBubble> ecmCache = List.of();

    /** Working ECM bubbles: units with ECM that are on the map, not destroyed, not shut down, not in stealth. */
    public List<EcmBubble> ecmBubbles() {
        refreshC3();
        return ecmCache;
    }

    private static boolean active(ASUnit u) {
        return u.alive() && !u.shutdown && u.position != null;
    }

    private List<EcmBubble> findBubbles() {
        List<EcmBubble> out = new ArrayList<>();
        for (ASUnit u : game.getUnits()) {
            if (u.ecmRange > 0 && active(u) && !u.has(F_STL)) {
                out.add(new EcmBubble(u, board().index(u.position), u.ecmRange, u.angelEcm, u.novaEcm));
            }
        }
        return out;
    }

    /** Is this hex inside an enemy (of side) ECM bubble that jams this kind of C3? */
    private boolean jammedAt(List<EcmBubble> ecm, int side, int hex, int c3Type, boolean boosted) {
        for (EcmBubble e : ecm) {
            if (game.sideOf(e.unit()) == side || board().distance(hex, e.center()) > e.radius()) {
                continue;
            }
            if (c3Type == C3_NOVA ? e.nova() : (!boosted || e.angel())) {
                return true;
            }
        }
        return false;
    }

    /** Does the line between two hexes run into or through such a bubble? */
    private boolean jammedLine(List<EcmBubble> ecm, int side, int a, int b, int c3Type, boolean boosted) {
        for (int hex : board().line(a, b, 1.0)) {
            if (hex >= 0 && jammedAt(ecm, side, hex, c3Type, boosted)) {
                return true;
            }
        }
        return false;
    }

    /** This unit is jammed where it stands (stealth units always are: they block their own emissions). */
    private boolean selfJammed(List<EcmBubble> ecm, ASUnit u) {
        // stealth works like a (standard) enemy ECM on itself: boosted C3 and Nova shrug that off
        boolean stealthJam = u.has(F_STL) && u.c3Type != C3_NOVA && !u.c3Boosted;
        return stealthJam || jammedAt(ecm, game.sideOf(u), board().index(u.position), u.c3Type, u.c3Boosted);
    }

    /** Recomputes the bubbles and who is connected when units moved / died / shut down (cheap check first). */
    private void refreshC3() {
        long key = 17;
        for (ASUnit u : game.getUnits()) {
            key = key * 31 + u.getId();
            key = key * 31 + (u.position == null ? -1 : board().index(u.position));
            key = key * 31 + (active(u) ? 1 : 0) + (u.c3Net + 2) * 4L;
        }
        if (key == c3Key) {
            return;
        }
        c3Key = key;
        ecmCache = findBubbles();
        c3Cache = new java.util.HashMap<>();
        Map<Integer, List<ASUnit>> nets = new LinkedHashMap<>();
        for (ASUnit u : game.getUnits()) {
            if (u.c3Net >= 0 && u.c3Type != C3_NONE) {
                nets.computeIfAbsent(u.c3Net, k -> new ArrayList<>()).add(u);
            }
        }
        for (List<ASUnit> net : nets.values()) {
            boolean nonHierarchic = net.stream().anyMatch(u -> u.c3Type == C3_IMPROVED || u.c3Type == C3_NOVA);
            if (nonHierarchic) {
                List<ASUnit> able = net.stream().filter(u -> active(u) && !selfJammed(ecmCache, u)).toList();
                for (ASUnit u : net) {
                    boolean ok = false;
                    if (able.contains(u)) {
                        for (ASUnit v : able) {
                            if (v != u && !jammedLine(ecmCache, game.sideOf(u), board().index(u.position),
                                  board().index(v.position), u.c3Type, false)) {
                                ok = true;
                                break;
                            }
                        }
                    }
                    c3Cache.put(u.getId(), ok);
                }
            } else {
                for (ASUnit u : net) {
                    c3Cache.put(u.getId(), linked(u, 0));
                }
            }
        }
    }

    /** Standard / boosted C3: connected if it isn't jammed and its line to a connected master isn't either. */
    private boolean linked(ASUnit u, int depth) {
        if (depth > 12 || !active(u) || selfJammed(ecmCache, u)) {
            return false;
        }
        if (u.c3Parent < 0 || u.c3Parent == u.getId()) {
            return true;                                     // the network's master
        }
        ASUnit master = game.getUnit(u.c3Parent);
        if (master == null || !linked(master, depth + 1)) {
            return false;                                    // master gone / jammed: its whole branch goes down
        }
        boolean boosted = u.c3Boosted && master.c3Boosted;  // a link is only as tough as its weaker end
        return !jammedLine(ecmCache, game.sideOf(u), board().index(u.position), board().index(master.position),
              u.c3Type, boosted);
    }

    /** Is this unit part of a working C3 network right now? */
    public boolean c3Connected(ASUnit u) {
        refreshC3();
        return c3Cache.getOrDefault(u.getId(), false);
    }

    /** The working members of this unit's network (including itself), or nothing if it isn't connected. */
    public List<ASUnit> c3Partners(ASUnit u) {
        if (u.c3Net < 0 || !c3Connected(u)) {
            return List.of();
        }
        List<ASUnit> out = new ArrayList<>();
        for (ASUnit v : game.getUnits()) {
            if (v.c3Net == u.c3Net && c3Cache.getOrDefault(v.getId(), false)) {
                out.add(v);
            }
        }
        return out.size() >= 2 ? out : List.of();
    }

    /** The range band used for the to-hit roll: the closest network member that can see the target [p. 80]. */
    private int c3Band(ASUnit att, int tPos, int band) {
        ASUnit spotter = c3Spotter(att, tPos, band);
        return spotter == null ? band : rules().rangeBand(board().distance(board().index(spotter.position), tPos));
    }

    /** The network member (not the attacker) that gives a better range band than the attacker's own, or null. */
    public ASUnit c3Spotter(ASUnit att, int tPos, int band) {
        ASUnit best = null;
        int bestBand = band;
        for (ASUnit m : c3Partners(att)) {
            if (m == att) {
                continue;
            }
            int mPos = board().index(m.position);
            int mb = rules().rangeBand(board().distance(mPos, tPos));
            if (mb >= 0 && mb < bestBand && !board().los(mPos, tPos).blocked()) {
                best = m;
                bestBand = mb;
            }
        }
        return best;
    }

    /** Every C3 link in the game, for drawing: master-member lines, or a star for C3i / Nova. */
    public List<C3Link> c3Links() {
        refreshC3();
        List<C3Link> out = new ArrayList<>();
        Map<Integer, ASUnit> firstOfNet = new LinkedHashMap<>();
        for (ASUnit u : game.getUnits()) {
            if (u.c3Net < 0 || u.c3Type == C3_NONE || u.position == null || !u.alive()) {
                continue;
            }
            boolean ok = c3Cache.getOrDefault(u.getId(), false);
            if (u.c3Type == C3_IMPROVED || u.c3Type == C3_NOVA) {
                ASUnit hub = firstOfNet.putIfAbsent(u.c3Net, u);
                if (hub != null) {
                    out.add(new C3Link(hub, u, ok && c3Cache.getOrDefault(hub.getId(), false)));
                }
            } else if (u.c3Parent >= 0 && u.c3Parent != u.getId()) {
                ASUnit m = game.getUnit(u.c3Parent);
                if (m != null && m.position != null && m.alive()) {
                    out.add(new C3Link(m, u, ok));
                }
            }
        }
        return out;
    }

    /** Short C3 status for the window: "", "C3 master - linked (4 units)", "C3 slave - cut off". */
    public String c3Status(ASUnit u) {
        if (u.c3Type == C3_NONE) {
            return "";
        }
        String kind = C3_NAMES[u.c3Type] + (u.c3Boosted ? " (boosted)" : "");
        if (u.c3Net < 0) {
            return kind + " - not in a network";
        }
        List<ASUnit> partners = c3Partners(u);
        if (!partners.isEmpty()) {
            return kind + " - linked (" + partners.size() + " units)";
        }
        if (u.has(F_STL) && u.c3Type != C3_NOVA && !u.c3Boosted) {
            return kind + " - off (stealth jams its own electronics)";
        }
        return kind + " - cut off" + (active(u) ? " (enemy ECM or its master is down)" : "");
    }

    /** Each side's deployment edge (0 N, 1 E, 2 S, 3 W), as used by deploy(). */
    private final Map<Integer, Integer> deployEdges = new LinkedHashMap<>();

    public static final int DEPLOY_DEPTH = 3;

    private boolean inStrip(int edge, int i) {
        ASBoard b = board();
        int x = i % b.width;
        int y = i / b.width;
        return switch (edge) {
            case 0 -> y < DEPLOY_DEPTH;                        // north
            case 1 -> x >= b.width - DEPLOY_DEPTH;             // east
            case 2 -> y >= b.height - DEPLOY_DEPTH;            // south
            default -> x < DEPLOY_DEPTH;                       // west
        };
    }

    /** Edge a side deploys on (0 N, 1 E, 2 S, 3 W). */
    public int deployEdge(int side) {
        Integer e = deployEdges.get(side);
        if (e != null) {
            return e;
        }
        // (on a client: the strip that holds all of the side's units, as the server set them up)
        int fallback = game.sides().indexOf(side) == 0 ? 3 : 1;
        for (int edge : new int[] { fallback, 3, 1, 0, 2 }) {
            boolean all = true;
            boolean any = false;
            for (ASUnit u : game.getUnits()) {
                if (game.sideOf(u) == side && u.alive() && u.position != null) {
                    any = true;
                    all &= inStrip(edge, board().index(u.position));
                }
            }
            if (any && all) {
                return edge;
            }
        }
        return fallback;
    }

    public void setDeployEdge(int side, int edge) {
        deployEdges.put(side, edge);
    }

    /** Can a unit of this side be set up in hex i during the Deployment Phase? */
    public boolean canDeployAt(int side, int i) {
        return inStrip(deployEdge(side), i) && board().passable(i);
    }

    /**
     * Deployment Phase: put a unit in hex i (in its side's strip) with a facing. A friendly unit already there
     * swaps places with it. Returns false if it isn't allowed.
     */
    public boolean redeploy(ASUnit unit, int i, int facing) {
        int side = game.sideOf(unit);
        if (!canDeployAt(side, i)) {
            return false;
        }
        ASUnit other = occupantAt(i);
        if (other != null && other != unit) {
            if (game.sideOf(other) != side || unit.position == null) {
                return false;
            }
            other.position = unit.position;   // swap
        }
        unit.position = board().coords(i);
        unit.facing = Math.floorMod(facing, 6);
        return true;
    }
}
