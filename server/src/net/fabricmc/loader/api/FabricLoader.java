// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package net.fabricmc.loader.api;

import java.nio.file.Path;

/** Server-only stand-in for Fabric's loader: the engine asks it where the config folder is. */
public interface FabricLoader {
    static FabricLoader getInstance() {
        return new FabricLoader() {
            public Path getConfigDir() { return Path.of(System.getProperty("user.home"), "config"); }
            public Path getGameDir() { return Path.of(System.getProperty("user.home")); }
            public boolean isModLoaded(String id) { return false; }
        };
    }

    Path getConfigDir();

    Path getGameDir();

    boolean isModLoaded(String id);
}
