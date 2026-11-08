/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.common.alphaStrike.play;

import java.io.Serializable;

/**
 * What a player sends to the server for one unit: a move (hex x/y, movement mode, facing) or an attack (target id or
 * -1 for "no attack", physical or weapon, overheat, and a target for its rear-firing (REAR) weapons or -1).
 */
public record ASOrder(int unitId, boolean isMove, int x, int y, int mode, int facing, int targetId, boolean physical,
      int overheat, int rearTargetId) implements Serializable {

    public static ASOrder move(int unitId, int x, int y, int mode, int facing) {
        return new ASOrder(unitId, true, x, y, mode, facing, -1, false, 0, -1);
    }

    public static ASOrder attack(int unitId, int targetId, boolean physical, int overheat) {
        return attack(unitId, targetId, physical, overheat, -1);
    }

    public static ASOrder attack(int unitId, int targetId, boolean physical, int overheat, int rearTargetId) {
        return new ASOrder(unitId, false, 0, 0, 0, 0, targetId, physical, overheat, rearTargetId);
    }
}
