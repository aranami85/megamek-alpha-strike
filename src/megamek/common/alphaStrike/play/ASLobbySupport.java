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

import megamek.common.alphaStrike.ASMessages;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

import megamek.common.alphaStrike.AlphaStrikeElement;
import megamek.common.alphaStrike.conversion.ASConverter;
import megamek.common.units.Entity;

/**
 * Helpers for an Alpha Strike game that is set up in MegaMek's normal lobby. The lobby still holds normal MegaMek
 * units (Entities, so the unit selector, forces, skills, camo, load/save force all work unchanged); when this is active
 * the lobby shows each unit's Alpha Strike values (converted with MegaMek's own ASConverter) and PV instead of BV.
 */
public final class ASLobbySupport {

    /** Server chat text that tells a client "this lobby is for an Alpha Strike game". */
    public static final String LOBBY_TAG = "[Alpha Strike game]";
    /** Server chat text that tells clients to switch from the lobby to the Alpha Strike game window. */
    public static final String SWITCH_TAG = "[Alpha Strike: starting the battle]";

    private static volatile boolean active;

    /** Small cache so the lobby doesn't convert the same unit object over and over (keyed by object identity). */
    private static final Map<Entity, Cached> cache = new WeakHashMap<>();

    private record Cached(Entity entity, int skillKey, AlphaStrikeElement element) {}

    /** Bot types for Alpha Strike games (what plays a bot player once the battle starts). */
    public static final String BOT_AI_V3 = "ai_v3";
    public static final String BOT_AI_NET = "ai";
    public static final String BOT_SCRIPTED = "scripted";
    /** Bot type -> text for the Add Bot dialog (add-on bots, see ASBotAddon, come first). */
    public static final Map<String, String> BOT_LABELS = new LinkedHashMap<>();
    private static final Map<String, String> SHORT_LABELS = new ConcurrentHashMap<>();
    private static final Map<String, String> DEFAULT_NAMES = new ConcurrentHashMap<>();

    static {
        for (String type : new String[] { BOT_AI_V3, BOT_AI_NET, BOT_SCRIPTED }) {
            BOT_LABELS.put(type, ASMessages.getString("ASLobbySupport.bot." + type));
            SHORT_LABELS.put(type, ASMessages.getString("ASLobbySupport.botShort." + type));
        }
        try {
            Class.forName("megamek.client.bot.alphaStrike.ASBots");    // loads add-on bot types, if any
        } catch (Throwable ignored) {
            // (no client classes, e.g. a dedicated server)
        }
    }

    /** An add-on bot type (called by ASBots for each ASBotAddon found). Listed before the built-in ones. */
    public static synchronized void registerBotType(String type, String label, String shortLabel, String defaultName) {
        Map<String, String> old = new LinkedHashMap<>(BOT_LABELS);
        BOT_LABELS.clear();
        BOT_LABELS.put(type, label);
        old.forEach(BOT_LABELS::putIfAbsent);
        SHORT_LABELS.put(type, shortLabel);
        if (defaultName != null) {
            DEFAULT_NAMES.put(type, defaultName);
        }
    }

    /** Suggested name for a new bot of this type, or null. */
    public static String defaultBotName(String type) {
        return DEFAULT_NAMES.get(type);
    }

    /** Bot player name -> bot type, for bots added on this computer. */
    private static final Map<String, String> botTypes = new ConcurrentHashMap<>();

    public static void setBotType(String botName, String type) {
        botTypes.put(botName, type);
    }

    /** The bot type chosen for this bot player (scripted if none was chosen). */
    public static String botType(String botName) {
        return botTypes.getOrDefault(botName, BOT_SCRIPTED);
    }

    /** True if a bot type was chosen for this bot player. */
    public static boolean hasBotType(String botName) {
        return botTypes.containsKey(botName);
    }

    /** The Add Bot dialog text for a bot type. */
    public static String botLabel(String type) {
        return BOT_LABELS.getOrDefault(type, type);
    }

    /** Short name for the lobby's player list. */
    public static String botShortLabel(String botName) {
        return SHORT_LABELS.getOrDefault(botType(botName), SHORT_LABELS.get(BOT_SCRIPTED));
    }

    private ASLobbySupport() {
    }

    public static boolean isActive() {
        return active;
    }

    public static void setActive(boolean isActive) {
        active = isActive;
    }

    /** True if MegaMek can make an Alpha Strike card for this unit. */
    public static boolean canConvert(Entity entity) {
        return ASConverter.canConvert(entity);
    }

    /** The unit's Alpha Strike element (card) including its pilot skill, or null if it can't be converted. */
    public static synchronized AlphaStrikeElement element(Entity entity) {
        if ((entity == null) || !ASConverter.canConvert(entity)) {
            return null;
        }
        int skillKey = (entity.getCrew() == null) ? -1
              : entity.getCrew().getGunnery() * 100 + entity.getCrew().getPiloting();
        Cached c = cache.get(entity);
        if ((c != null) && (c.entity() == entity) && (c.skillKey() == skillKey)) {
            return c.element();
        }
        try {
            AlphaStrikeElement element = ASConverter.convert(entity);
            element.setId(entity.getId());
            element.setOwnerId(entity.getOwnerId());
            cache.put(entity, new Cached(entity, skillKey, element));
            return element;
        } catch (Exception ex) {
            return null;
        }
    }

    /** Point Value of the unit (with skill), 0 if it can't be converted. */
    public static int pv(Entity entity) {
        AlphaStrikeElement e = element(entity);
        return (e == null) ? 0 : e.getPointValue();
    }

    /** The Alpha Strike point value of a lobby unit at its pilot skill (cached). */
    public static int pv(Collection<Entity> entities) {
        return entities.stream().mapToInt(ASLobbySupport::pv).sum();
    }
}
