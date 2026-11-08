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

/**
 * An extra kind of Alpha Strike bot supplied by a separate jar on the class path (found with Java's ServiceLoader:
 * the jar lists its class in META-INF/services/megamek.client.bot.alphaStrike.ASBotAddon). It shows up in the Add Bot
 * dialog next to the built-in AI and scripted bots.
 */
public interface ASBotAddon {

    /** Short key stored for the bot player, e.g. "mybot". */
    String type();

    /** Text in the Add Bot dialog. */
    String label();

    /** Short text for the lobby's player list. */
    String shortLabel();

    /** Name suggested for a new bot of this kind, or null for the usual "Bot". */
    String defaultName();

    /** False if it can't run on this computer (then it isn't offered). */
    boolean available();

    /** Makes the bot client. */
    ASScriptedBot create(String name, String host, int port);
}
