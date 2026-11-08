/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
 */
package megamek.common.alphaStrike.play;

import megamek.common.alphaStrike.ASGame;
import megamek.common.game.AbstractPlayerTurn;
import megamek.common.game.IGame;
import megamek.common.game.InGameObject;

/**
 * One step of a phase: the given player moves (Movement Phase) or attacks with (Combat Phase) ONE of their units that
 * hasn't acted yet. Alpha Strike alternates sides unit by unit [ASCE p. 32, 40].
 */
public class ASTurn extends AbstractPlayerTurn {

    public ASTurn(int playerId) {
        super(playerId);
    }

    @Override
    public boolean isValidEntity(InGameObject unit, IGame game) {
        if (!(unit instanceof ASUnit u) || u.getOwnerId() != playerId() || !u.alive() || u.position == null) {
            return false;
        }
        return switch (game.getPhase()) {
            case MOVEMENT -> !u.moved;
            case FIRING -> !u.acted;
            default -> false;
        };
    }

    public boolean isValid(ASGame game) {
        return (game.getPlayer(playerId()) != null)
              && game.getUnits().stream().anyMatch(u -> isValidEntity(u, game));
    }
}
