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
package megamek.common.alphaStrike.tools;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Builds MegaMek-AS.jar: a copy of MegaMek.jar with the Alpha Strike mode's classes added (MegaMek.jar is "sealed",
 * so the classes can't simply sit next to it). The original MegaMek.jar is only read. Plain Java (no MegaMek classes
 * needed), so it runs straight from the Alpha Strike classes jar:
 *
 * <pre>java -cp megamek-as-classes.jar megamek.common.alphaStrike.tools.MakeMegaMekAS MegaMek.jar
 * megamek-as-classes.jar MegaMek-AS.jar</pre>
 */
public final class MakeMegaMekAS {

    /** The MegaMek build the Alpha Strike classes were compiled against (MegaMek 0.51.0). */
    public static final String BUILT_FOR = "2026-06-06";

    private MakeMegaMekAS() {
    }

    /** Merges the Alpha Strike classes into a copy of MegaMek.jar. Arguments: MegaMek.jar, classes jar, output jar. */
    public static void main(String[] args) throws IOException {
        if (args.length != 3) {
            System.err.println("usage: MakeMegaMekAS <MegaMek.jar> <megamek-as-classes.jar> <output MegaMek-AS.jar>");
            System.exit(2);
        }
        File source = new File(args[0]);
        File ours = new File(args[1]);
        File out = new File(args[2]);
        Map<String, byte[]> add = new HashMap<>();
        try (ZipFile z = new ZipFile(ours)) {
            Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.isDirectory() && !e.getName().startsWith("META-INF/")) {
                    add.put(e.getName(), read(z.getInputStream(e)));
                }
            }
        }
        File tmp = new File(out.getPath() + ".tmp");
        String buildDate = "?";
        try (ZipFile src = new ZipFile(source); ZipOutputStream zo = new ZipOutputStream(new FileOutputStream(tmp))) {
            Enumeration<? extends ZipEntry> en = src.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                String name = e.getName();
                if (add.containsKey(name)) {
                    continue;                          // replaced by our version
                }
                String upper = name.toUpperCase();
                if (upper.startsWith("META-INF/") && (upper.endsWith(".SF") || upper.endsWith(".RSA")
                      || upper.endsWith(".DSA"))) {
                    continue;                          // old signatures would no longer match
                }
                byte[] data = e.isDirectory() ? new byte[0] : read(src.getInputStream(e));
                if (name.equals("META-INF/MANIFEST.MF")) {
                    String text = new String(data, StandardCharsets.UTF_8).replace("\r\n", "\n");
                    StringBuilder sb = new StringBuilder();
                    for (String line : text.split("\n")) {
                        if (line.startsWith("Build-Date:")) {
                            buildDate = line.substring(11).trim();
                        }
                        if (!line.startsWith("Sealed:")) {
                            sb.append(line).append("\r\n");
                        }
                    }
                    data = sb.toString().getBytes(StandardCharsets.UTF_8);
                }
                ZipEntry copy = new ZipEntry(name);
                copy.setTime(e.getTime());
                zo.putNextEntry(copy);
                zo.write(data);
                zo.closeEntry();
            }
            for (var entry : add.entrySet()) {
                zo.putNextEntry(new ZipEntry(entry.getKey()));
                zo.write(entry.getValue());
                zo.closeEntry();
            }
        }
        if (out.exists() && !out.delete()) {
            throw new IOException("Can't replace " + out + " (is MegaMek still running?)");
        }
        if (!tmp.renameTo(out)) {
            throw new IOException("Can't write " + out);
        }
        System.out.println("Built " + out.getName() + " (" + add.size() + " Alpha Strike files added)");
        if (!buildDate.startsWith(BUILT_FOR)) {
            System.out.println("WARNING: this Alpha Strike mode was made for MegaMek 0.51.0 (built " + BUILT_FOR
                  + "); your MegaMek.jar was built " + buildDate + ". It may not work - MegaMek 0.51.0 is best.");
        }
    }

    private static byte[] read(InputStream in) throws IOException {
        try (in) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            in.transferTo(bo);
            return bo.toByteArray();
        }
    }
}
