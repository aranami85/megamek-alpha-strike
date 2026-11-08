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
package megamek.common.alphaStrike.play;

import java.io.Serializable;

/**
 * The rule numbers of Alpha Strike on hexes in one place (ASCE page numbers in brackets). Same defaults as the Python
 * Alpha Strike simulator (as_engine.RULES).
 */
public class ASRules implements Serializable {

    public int[] rangeBands = { 3, 12, 21 };    // last hex of Short, Medium, Long [p. 68]
    public int[] rangeMods = { 0, 2, 4 };
    public boolean moveRoundUp = false;         // odd inches: 5" -> 2 hexes
    public int costClear = 1;
    public int costRough = 1;
    public int costWoods = 1;
    public int costWater1 = 1;
    public int costPerLevel = 1;
    public int maxLevelChange = 4;              // 'Mechs: 2" of elevation per 1" moved [p. 34]
    public double sprintFactor = 1.5;
    public int attackerJumped = 2;
    public int attackerStoodStill = -1;
    public int targetJumpedExtra = 1;
    public int targetImmobile = -4;
    public int woodsMod = 1;
    public int partialCoverMod = 1;
    public int fireControlMod = 2;
    public int rearDamageBonus = 1;
    public int maxHtHeatPerTurn = 2;
    public int shutdownHeat = 4;
    public int maxTurns = 15;

    /** Range band (0 S, 1 M, 2 L) for a distance in hexes, -1 = out of range. */
    public int rangeBand(int distance) {
        for (int band = 0; band < 3; band++) {
            if (distance <= rangeBands[band]) {
                return band;
            }
        }
        return -1;
    }

    /** Chance that 2d6 rolls this number or higher. */
    public static double hitChance(int tn) {
        if (tn <= 2) {
            return 1.0;
        }
        if (tn > 12) {
            return 0.0;
        }
        int count = 0;
        for (int a = 1; a <= 6; a++) {
            for (int b = 1; b <= 6; b++) {
                if (a + b >= tn) {
                    count++;
                }
            }
        }
        return count / 36.0;
    }

    /** Standard Alpha Strike TMM for a Move value in inches. */
    public static int tmmForMove(int inches) {
        int[][] table = { { 4, 0 }, { 8, 1 }, { 12, 2 }, { 18, 3 }, { 34, 4 } };
        for (int[] row : table) {
            if (inches <= row[0]) {
                return row[1];
            }
        }
        return 5;
    }

    /** Inches to hexes (1 hex = 2"). */
    public int toHexes(double inches) {
        return moveRoundUp ? (int) Math.ceil(inches / 2) : (int) Math.floor(inches / 2);
    }
}
