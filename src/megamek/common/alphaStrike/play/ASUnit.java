/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.common.alphaStrike.play;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import megamek.common.alphaStrike.ASDamage;
import megamek.common.alphaStrike.ASDamageVector;
import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.BattleForceSUA;
import megamek.common.board.Coords;
import megamek.common.game.InGameObject;
import megamek.common.units.Entity;

/**
 * A unit on the table in an Alpha Strike game: its card (an {@link AlphaStrikeElement}, made by MegaMek's own
 * ASConverter) plus everything that changes during the game (position, facing, damage, heat, crits, this turn's
 * movement mode...). The card values the rules use are copied into plain fields so the rules code stays simple.
 */
public class ASUnit implements InGameObject, Serializable {

    // movement modes (same order as the Python simulator)
    public static final int STAND = 0;
    public static final int GROUND = 1;
    public static final int MINIMUM = 2;
    public static final int SPRINT = 3;
    public static final int JUMP = 4;
    public static final int IMMOBILE = 5;
    public static final String[] MODE_NAMES = { "stand", "ground", "minimum", "sprint", "jump", "immobile" };

    // special abilities the rules use
    public static final int F_CASE = 1, F_CASEII = 2, F_ENE = 4, F_OVL = 8, F_MEL = 16, F_BAR = 32, F_ARM = 64,
          F_HT = 128,
          F_STL = 1 << 8,       // stealth: +1 TN at Medium, +2 at Long; its own ECM is off and it counts as jammed [p. 79]
          F_MAS = 1 << 9,       // mimetic armor: +3 TN vs weapon attacks if it stood still [p. 78]
          F_LMAS = 1 << 10,     // light mimetic: +2
          F_AMS = 1 << 11,      // anti-missile: -1 damage (min 1) from IF/SRM/LRM units, front arc [p. 76]
          F_SHLD = 1 << 12,     // shield: -1 damage from weapons/physical (min 0), +1 TN on its own weapon attacks [p. 76]
          F_TSM = 1 << 13,      // TSM: +2" Move and +1 physical damage when heat >= 1 [p. 79]
          F_ITSM = 1 << 14,     // industrial TSM: +1 physical damage, +2 TN physical [p. 78]
          F_CR = 1 << 15,       // critical-resistant: -2 on the critical hit roll [p. 86]
          F_FR = 1 << 16,       // fire resistant: no heat from HT attacks [p. 78]
          F_MISSILE = 1 << 17,  // has IF / SRM / LRM (what AMS stops)
          F_REAR = 1 << 18,     // rear-firing weapons: REAR#/#/# [p. 79]
          F_TUR = 1 << 19;      // turret: TUR(#/#/#) attacks outside the front arc [p. 80]

    // C3 [pp. 80-82]
    public static final int C3_NONE = 0, C3_MASTER = 1, C3_SLAVE = 2, C3_IMPROVED = 3, C3_NOVA = 4;
    public static final String[] C3_NAMES = { "", "C3 master", "C3 slave", "C3i", "Nova CEWS" };

    // critical hit effects
    public static final int C_AMMO = 1, C_ENGINE = 2, C_FC = 3, C_WEAPON = 4, C_MP = 5, C_DESTROYED = 6;

    private int id = Entity.NONE;
    private int ownerId = -1;
    private final AlphaStrikeElement element;

    // the card (never changes)
    public final String name;
    public final int skill, pv, size, baseMove, baseJump, baseTmm, fullArmor, fullStructure, ov, flags, jmps, jmpw;
    public final int[] ht = new int[3];
    /** ECM bubble radius in hexes (ECM/AECM/WAT/NOVA 12" = 6, LECM 2" = 1), 0 = none [pp. 77-78]. */
    public final int ecmRange;
    public final boolean angelEcm, novaEcm;
    /** C3 system (C3_*) and whether it is a boosted one (only Angel ECM jams it). */
    public final int c3Type;
    public final boolean c3Boosted;
    /** C3 network set up in the lobby: the unit's master (-1 = none / top of the network) and network id (-1 = none). */
    public int c3Parent = -1;
    public int c3Net = -1;
    /** Damage S/M/L times two (so "0*" = 1 fits in an int; MegaMek's network filter doesn't allow double[]). */
    private final int[] dmgTimes2 = new int[3];
    /** REAR and TUR damage S/M/L, also times two. */
    private final int[] rearTimes2 = new int[3];
    private final int[] turTimes2 = new int[3];

    // the game state
    public Coords position;            // null = not deployed
    public int facing;
    public int armor, structure, moveIn, jumpIn, tmm, heat;
    public boolean shutdown, armUsed, destroyed, doomed;
    public int engineHits, fcHits, mpHits, weaponHits;
    public boolean moved, acted, fired;
    public int mode = STAND;
    public int ovUsed, incomingHeat;
    public final List<Integer> pendingCrits = new ArrayList<>();
    public int damageDealt, damageTaken, kills;

    public ASUnit(AlphaStrikeElement element) {
        this.element = element;
        id = element.getId();
        ownerId = element.getOwnerId();
        name = element.getName();
        skill = element.getSkill();
        pv = element.getPointValue();
        size = element.getSize();
        int ground = element.getPrimaryMovementValue();
        int jump = element.getJumpMove();
        baseMove = ground > 0 ? ground : jump;
        baseJump = Math.max(jump, 0);
        baseTmm = element.getTMM();
        fullArmor = element.getFullArmor();
        fullStructure = element.getFullStructure();
        ov = element.getOV();
        ASDamageVector d = element.getStandardDamage();
        ASDamage[] bands = { d.S(), d.M(), d.L() };
        for (int i = 0; i < 3; i++) {
            ASDamage b = bands[i];
            dmgTimes2[i] = (b == null) ? 0 : (b.minimal ? 1 : 2 * b.damage);
        }
        int f = 0;
        f |= element.hasSUA(BattleForceSUA.CASE) ? F_CASE : 0;
        f |= element.hasSUA(BattleForceSUA.CASEII) ? F_CASEII : 0;
        f |= element.hasSUA(BattleForceSUA.ENE) ? F_ENE : 0;
        f |= element.hasSUA(BattleForceSUA.OVL) ? F_OVL : 0;
        f |= element.hasSUA(BattleForceSUA.MEL) ? F_MEL : 0;
        f |= element.hasSUA(BattleForceSUA.BAR) ? F_BAR : 0;
        f |= element.hasSUA(BattleForceSUA.ARM) ? F_ARM : 0;
        if (element.hasSUA(BattleForceSUA.HT) && element.getSUA(BattleForceSUA.HT) instanceof ASDamageVector htv) {
            f |= F_HT;
            ASDamage[] hb = { htv.S(), htv.M(), htv.L() };
            for (int i = 0; i < 3; i++) {
                ht[i] = (hb[i] == null) ? 0 : hb[i].damage;
            }
        }
        f |= has(element, BattleForceSUA.STL) ? F_STL : 0;
        f |= has(element, BattleForceSUA.MAS) ? F_MAS : 0;
        f |= has(element, BattleForceSUA.LMAS) ? F_LMAS : 0;
        f |= has(element, BattleForceSUA.AMS) || has(element, BattleForceSUA.RAMS) ? F_AMS : 0;
        f |= has(element, BattleForceSUA.SHLD) ? F_SHLD : 0;
        f |= has(element, BattleForceSUA.TSM) ? F_TSM : 0;
        f |= has(element, BattleForceSUA.ITSM) ? F_ITSM : 0;
        f |= has(element, BattleForceSUA.CR) ? F_CR : 0;
        f |= has(element, BattleForceSUA.FR) ? F_FR : 0;
        f |= has(element, BattleForceSUA.IF) || has(element, BattleForceSUA.SRM) || has(element, BattleForceSUA.LRM)
              ? F_MISSILE : 0;
        if (element.hasSUA(BattleForceSUA.REAR) && element.getSUA(BattleForceSUA.REAR) instanceof ASDamageVector rv) {
            f |= F_REAR;
            fill(rearTimes2, rv);
        }
        if (element.hasSUA(BattleForceSUA.TUR)) {
            Object tv = element.getTUR().getSUA(BattleForceSUA.STD);
            if (tv instanceof ASDamageVector tdv) {
                f |= F_TUR;
                fill(turTimes2, tdv);
            }
        }
        flags = f;
        angelEcm = has(element, BattleForceSUA.AECM);
        novaEcm = has(element, BattleForceSUA.NOVA);
        if (angelEcm || novaEcm || has(element, BattleForceSUA.ECM) || has(element, BattleForceSUA.WAT)) {
            ecmRange = 6;
        } else if (has(element, BattleForceSUA.LECM)) {
            ecmRange = 1;
        } else {
            ecmRange = 0;
        }
        if (novaEcm) {
            c3Type = C3_NOVA;
        } else if (has(element, BattleForceSUA.C3I)) {
            c3Type = C3_IMPROVED;
        } else if (has(element, BattleForceSUA.C3M) || has(element, BattleForceSUA.C3BSM)) {
            c3Type = C3_MASTER;
        } else if (has(element, BattleForceSUA.C3S) || has(element, BattleForceSUA.C3BSS)
              || has(element, BattleForceSUA.C3EM)) {
            c3Type = C3_SLAVE;           // (an emergency master works as a slave until it takes over - not modelled)
        } else {
            c3Type = C3_NONE;
        }
        c3Boosted = has(element, BattleForceSUA.C3BSM) || has(element, BattleForceSUA.C3BSS);
        jmps = intSua(element, BattleForceSUA.JMPS);
        jmpw = intSua(element, BattleForceSUA.JMPW);
        armor = fullArmor;
        structure = fullStructure;
        moveIn = baseMove;
        jumpIn = baseJump;
        tmm = baseTmm;
    }

    private static void fill(int[] times2, ASDamageVector v) {
        ASDamage[] b = { v.S(), v.M(), v.L() };
        for (int i = 0; i < 3; i++) {
            times2[i] = (b[i] == null) ? 0 : (b[i].minimal ? 1 : 2 * b[i].damage);
        }
    }

    /** REAR damage at band 0/1/2 (0.5 = "0*"); weapon hits count against it too. */
    public double rearDamageAt(int band) {
        return reduced(rearTimes2[band] / 2.0);
    }

    /** TUR damage at band 0/1/2 (0.5 = "0*"). */
    public double turretDamageAt(int band) {
        return reduced(turTimes2[band] / 2.0);
    }

    private double reduced(double d) {
        if (weaponHits != 0) {
            return d >= 1 ? Math.max(0, (int) d - weaponHits) : 0;
        }
        return d;
    }

    private static boolean has(AlphaStrikeElement e, BattleForceSUA sua) {
        return e.hasSUA(sua);
    }

    private static int intSua(AlphaStrikeElement e, BattleForceSUA sua) {
        if (!e.hasSUA(sua)) {
            return 0;
        }
        Object v = e.getSUA(sua);
        if (v instanceof Integer i) {
            return i;
        } else if (v instanceof Double dv) {
            return dv.intValue();
        } else if (v instanceof ASDamage ad) {
            return ad.damage;
        }
        return 0;
    }

    public AlphaStrikeElement getElement() {
        return element;
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }

    public boolean alive() {
        return !destroyed;
    }

    public int groundInches() {
        int loss = 2 * heat;
        if (has(F_TSM) && heat >= 1) {
            loss = (heat == 1) ? -2 : loss - 2;     // TSM: +2" when hot, and heat 1 costs nothing [p. 79]
        }
        return Math.max(moveIn - loss, 0);
    }

    public boolean immobile() {
        return shutdown || (groundInches() <= 0 && jumpIn <= 0);
    }

    public int hp() {
        return armor + structure;
    }

    public int hpFull() {
        return Math.max(fullArmor + fullStructure, 1);
    }

    /** Target movement modifier for a movement mode this turn [p. 42]. */
    public int tmmNow(int mode, ASRules r) {
        if (mode == IMMOBILE || shutdown) {
            return r.targetImmobile;
        }
        if (mode == STAND || mode == MINIMUM) {
            return 0;
        }
        int t = tmm;
        if (mode == JUMP) {
            if (baseJump != baseMove) {
                t = ASRules.tmmForMove(jumpIn);
            }
            t += r.targetJumpedExtra + jmps - jmpw;
        }
        if (heat >= 2) {
            t -= 1;
        }
        return Math.max(t, 0);
    }

    /** Damage at range band 0/1/2 after weapon hits; 0.5 = "0*". */
    public double damageAt(int band) {
        double d = baseDamage(band);
        if (weaponHits != 0) {
            return d >= 1 ? Math.max(0, (int) d - weaponHits) : 0;
        }
        return d;
    }

    /** Card damage at band 0/1/2 (S/M/L), 0.5 = "0*". */
    public double baseDamage(int band) {
        return dmgTimes2[band] / 2.0;
    }

    public int heatAt(int band) {
        return has(F_HT) ? Math.max(0, ht[band] - weaponHits) : 0;
    }

    public int maxOv(int band) {
        return (band == 2 && !has(F_OVL)) ? 0 : ov;
    }

    // --- InGameObject --------------------------------------------------------------------

    @Override
    public int getId() {
        return id;
    }

    @Override
    public void setId(int newId) {
        id = newId;
    }

    @Override
    public int getOwnerId() {
        return ownerId;
    }

    @Override
    public void setOwnerId(int newOwnerId) {
        ownerId = newOwnerId;
    }

    @Override
    public int getStrength() {
        return pv;
    }

    @Override
    public String generalName() {
        return element.getChassis();
    }

    @Override
    public String specificName() {
        return element.getModel();
    }

    @Override
    public boolean isMek() {
        return element.isMek();
    }

    @Override
    public boolean isGround() {
        return element.isGround();
    }

    /** Short status for tooltips and reports: "Atlas AS7-D  A5/S8  heat 1". */
    public String statusText() {
        return name + "  A" + armor + "/S" + structure + (heat > 0 ? "  heat " + heat : "")
              + (shutdown ? "  SHUTDOWN" : "") + (destroyed || doomed ? "  DESTROYED" : "");
    }

    @Override
    public String toString() {
        return "[ASUnit " + id + "] " + name;
    }
}
