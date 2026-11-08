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
 * What a player sends to the server for one unit: a move (hex x/y, movement mode, facing) or an attack (target id or
 * -1 for "no attack", physical or weapon, overheat, and a target for its rear-firing (REAR) weapons or -1).
 */
public record ASOrder(int unitId, boolean isMove, int x, int y, int mode, int facing, int targetId, boolean physical,
      int overheat, int rearTargetId) implements Serializable {

    /** A move order: where to, movement mode and facing. */
    public static ASOrder move(int unitId, int x, int y, int mode, int facing) {
        return new ASOrder(unitId, true, x, y, mode, facing, -1, false, 0, -1);
    }

    /** An attack order (targetId -1 = no attack). */
    public static ASOrder attack(int unitId, int targetId, boolean physical, int overheat) {
        return attack(unitId, targetId, physical, overheat, -1);
    }

    /** An attack order plus a target for the rear-firing (REAR) weapons (-1 = none). */
    public static ASOrder attack(int unitId, int targetId, boolean physical, int overheat, int rearTargetId) {
        return new ASOrder(unitId, false, 0, 0, 0, 0, targetId, physical, overheat, rearTargetId);
    }
}
