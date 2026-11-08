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
package megamek.common.alphaStrike;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * The text of the Alpha Strike mode (window, dialogs, battle report) from
 * megamek/common/alphaStrike/messages.properties,
 * so it can be translated like the rest of MegaMek.
 */
public final class ASMessages {

    private static final String BUNDLE_NAME = "megamek.common.alphaStrike.messages";
    private static ResourceBundle bundle;

    private ASMessages() {
    }

    private static synchronized ResourceBundle bundle() {
        if (bundle == null) {
            Locale locale = Locale.getDefault();
            try {
                locale = megamek.MegaMek.getMMOptions().getLocale();
            } catch (Throwable ignored) {
                // (no MegaMek options, e.g. in a tool or a test: use the default locale)
            }
            bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale);
        }
        return bundle;
    }

    /**
     * Returns the text for the given key, or "!key!" if it is missing.
     *
     * @param key the key in messages.properties
     *
     * @return the text
     */
    public static String getString(String key) {
        try {
            return bundle().getString(key);
        } catch (MissingResourceException e) {
            return '!' + key + '!';
        }
    }

    /**
     * Returns the text for the given key with {0}, {1}... replaced by the arguments. Numbers are written plainly
     * (no thousands separators), so report lines look the same in every locale.
     *
     * @param key  the key in messages.properties
     * @param args the values for {0}, {1}...
     *
     * @return the text
     */
    public static String getString(String key, Object... args) {
        Object[] plain = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            plain[i] = (args[i] instanceof Number) ? String.valueOf(args[i]) : args[i];
        }
        return MessageFormat.format(getString(key), plain);
    }
}
