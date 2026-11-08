/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek. MegaMek is free software: you can redistribute it and/or modify it under the terms of
 * the GNU General Public License (GPL), version 3 or (at your option) any later version.
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
