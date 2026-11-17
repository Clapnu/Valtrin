package net.fabricmc.loader.api;
import java.nio.file.Path;
/** Test-only stand-in so FlipConfig can load outside Minecraft: points the config folder at a temp directory. */
public interface FabricLoader {
    static FabricLoader getInstance() {
        return new FabricLoader() {
            public Path getConfigDir() { return Path.of(System.getProperty("java.io.tmpdir")); }
            public Path getGameDir() { return Path.of(System.getProperty("java.io.tmpdir")); }
            public boolean isModLoaded(String id) { return false; }
        };
    }
    Path getConfigDir(); Path getGameDir(); boolean isModLoaded(String id);
}
