Hostile-input tests for Valtrin's trust boundaries (Validate, chat parsing in FlipStats, the item-data reader ItemKeys, FlipConfig.clamp).

Run (from the project folder, after compiling the mod classes into out/):
  javac -proc:none -cp out:<gson.jar>:<slf4j-api.jar>:<minecraft.jar> -d tests tools/securitytests/net/fabricmc/loader/api/FabricLoader.java tools/securitytests/dev/valtrin/SecurityTests.java
  java -cp tests:out:<gson.jar>:<slf4j-api.jar>:<minecraft.jar> dev.valtrin.SecurityTests

Last result (23.6.3 work, 2026-11-14): passed 62, failed 0.
Covers: path-like and newline ids, username/URL validation (https required, http only for localhost), spoofed and oversized chat lines,
trade lines, 9000 random/truncated item blobs, 100,000-level nesting, a list claiming 2 billion entries, a 50 MB decompression bomb
(refused in under 5 seconds), negative array sizes, and out-of-range settings.
