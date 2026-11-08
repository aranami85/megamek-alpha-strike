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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import megamek.logging.MMLogger;

/**
 * The connection to the Alpha Strike AI program (as_ai_engine.exe), running as a child process. Messages are one
 * JSON object per line on stdin/stdout. One program is shared by all AI bots in this MegaMek.
 * <p>
 * Where it is: -Das.engine = path of as_ai_engine.exe. The trained model is -Das.model, else as_ai_model.bin next to
 * the program (if neither exists the program uses its own default model file).
 */
public final class ASAIBridge {

    private static final MMLogger LOGGER = MMLogger.create(ASAIBridge.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static ASAIBridge instance;

    private final Process process;
    private final BufferedWriter toEngine;
    private final BufferedReader fromEngine;
    private boolean broken;

    /** The shared program, started on first use. Throws if it can't be started. */
    public static synchronized ASAIBridge get() throws Exception {
        if ((instance == null) || instance.broken || !instance.process.isAlive()) {
            instance = new ASAIBridge();
        }
        return instance;
    }

    /** The AI program, if the launcher gave its path with -Das.engine. */
    private static File engineFile() {
        String engine = System.getProperty("as.engine");
        if ((engine == null) || engine.isBlank()) {
            return null;
        }
        File f = new File(engine);
        return f.exists() ? f : null;
    }

    /** True if the AI bots can be used on this computer. */
    public static boolean configured() {
        return engineFile() != null;
    }

    private ASAIBridge() throws Exception {
        File engine = engineFile();
        if (engine == null) {
            throw new IllegalStateException("the AI program was not found (start MegaMek with -Das.engine=...)");
        }
        String modelProp = System.getProperty("as.model");
        File model = ((modelProp != null) && !modelProp.isBlank()) ? new File(modelProp)
              : new File(engine.getParentFile(), "as_ai_model.bin");
        ProcessBuilder pb = model.exists() ? new ProcessBuilder(engine.getPath(), model.getPath())
              : new ProcessBuilder(engine.getPath());
        pb.directory(engine.getParentFile());
        process = pb.start();
        toEngine = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        fromEngine = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        // the program's messages (and errors) go to MegaMek's log
        Thread drain = new Thread(() -> {
            try (BufferedReader err = new BufferedReader(
                  new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = err.readLine()) != null) {
                    LOGGER.info("[as_ai_engine] {}", line);
                }
            } catch (Exception ignored) {
            }
        }, "AS AI log");
        drain.setDaemon(true);
        drain.start();
        Runtime.getRuntime().addShutdownHook(new Thread(process::destroy));
        JsonNode hello = request(JSON.createObjectNode().put("cmd", "hello"));
        LOGGER.info("AS AI ready: {}", hello);
    }

    /** Sends one message and waits for the answer. */
    public synchronized JsonNode request(JsonNode message) throws Exception {
        try {
            toEngine.write(JSON.writeValueAsString(message));
            toEngine.newLine();
            toEngine.flush();
            String line = fromEngine.readLine();
            if (line == null) {
                throw new IllegalStateException("the AI program stopped (see MegaMek's log)");
            }
            JsonNode reply = JSON.readTree(line);
            if (!reply.path("ok").asBoolean(false)) {
                throw new IllegalStateException("AI: " + reply.path("error").asText("unknown error"));
            }
            return reply;
        } catch (Exception ex) {
            if (!process.isAlive()) {
                broken = true;
            }
            throw ex;
        }
    }

    /** The JSON mapper used for messages to the AI program. */
    public static ObjectMapper json() {
        return JSON;
    }
}
