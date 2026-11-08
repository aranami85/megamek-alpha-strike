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

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ServiceLoader;

import megamek.common.alphaStrike.play.ASLobbySupport;
import megamek.logging.MMLogger;

/** Makes the Alpha Strike bot for a bot type (see ASLobbySupport): AI v3 / AI v2.1 / scripted, or an add-on's bot. */
public final class ASBots {

    private static final MMLogger LOGGER = MMLogger.create(ASBots.class);
    private static final Map<String, ASBotAddon> ADDONS = new LinkedHashMap<>();

    static {
        try {
            for (ASBotAddon addon : ServiceLoader.load(ASBotAddon.class, ASBots.class.getClassLoader())) {
                ADDONS.put(addon.type(), addon);
                ASLobbySupport.registerBotType(addon.type(), addon.label(), addon.shortLabel(), addon.defaultName());
            }
        } catch (Throwable t) {
            LOGGER.error(t, "Could not load an Alpha Strike bot add-on");
        }
    }

    private ASBots() {
    }

    /** Makes sure add-on bot types are known (call before listing the bot types). */
    public static void init() {
    }

    /** Makes the bot client for a bot type. */
    public static ASScriptedBot create(String name, String host, int port, String type) {
        ASBotAddon addon = ADDONS.get(type);
        if ((addon != null) && addon.available()) {
            return addon.create(name, host, port);
        }
        if (ASLobbySupport.BOT_AI_V3.equals(type) || ASLobbySupport.BOT_AI_NET.equals(type)) {
            return new ASAIBot(name, host, port, type);
        }
        return new ASScriptedBot(name, host, port);
    }

    /** True if the AI bots (as_ai_engine) can be used on this computer. */
    public static boolean aiAvailable() {
        return ASAIBridge.configured();
    }

    /** True if an add-on supplies this bot type. */
    public static boolean isAddon(String type) {
        return ADDONS.containsKey(type);
    }

    /** Can this bot type be played on this computer? */
    public static boolean available(String type) {
        ASBotAddon addon = ADDONS.get(type);
        if (addon != null) {
            return addon.available();
        }
        return ASLobbySupport.BOT_SCRIPTED.equals(type) || aiAvailable();
    }
}
