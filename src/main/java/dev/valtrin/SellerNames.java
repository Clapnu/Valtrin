// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonParser;

import java.io.BufferedWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns a seller's UUID (from the Hypixel auction) into a player name via Mojang's public session server.
 * Names that were found are kept in a file, so after a restart most sellers are known at once.
 */
final class SellerNames {
    private SellerNames() {}

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();
    /** uuid -> time of the last failed lookup (not asked again for a short while, so Mojang is not hammered). */
    private static final Map<String, Long> FAILED = new ConcurrentHashMap<>();
    private static final long RETRY_AFTER_MS = 30_000;
    private static final int MAX_FILE_LINES = 300_000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    private static volatile Path file;

    private static final java.util.concurrent.ExecutorService POOL = java.util.concurrent.Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "Valtrin-names");
        t.setDaemon(true);
        return t;
    });
    private static final java.util.concurrent.ExecutorService WRITER = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Valtrin-names-save");
        t.setDaemon(true);
        return t;
    });

    /** Loads the saved names (call once at start). */
    static void init(Path f) {
        file = f;
        try {
            if (!Files.exists(f)) return;
            int n = 0;
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                int tab = line.indexOf('\t');
                if (tab > 0 && tab < line.length() - 1) {
                    String id = line.substring(0, tab), nm = line.substring(tab + 1).trim();
                    if (!Validate.uuid(id) || !Validate.player(nm)) continue; // a damaged or edited file can't put odd text in
                    CACHE.put(id, nm);
                    n++;
                }
            }
            // Keep the file from growing forever: rewrite it without duplicates when it got big.
            if (n > MAX_FILE_LINES) {
                WRITER.execute(() -> rewrite(f));
            }
        } catch (Exception e) {
            Log.LOGGER.warn("Could not read saved seller names: {}", e.toString());
        }
    }

    static int cached() { return CACHE.size(); }

    /** The name if it is already known (no network, never waits), else null. */
    static String cachedName(String uuid) {
        if (uuid == null) return null;
        String hit = CACHE.get(uuid);
        return hit == null || hit.isEmpty() ? null : hit;
    }

    private static void rewrite(Path f) {
        try {
            StringBuilder sb = new StringBuilder();
            int n = 0;
            for (Map.Entry<String, String> e : CACHE.entrySet()) {
                if (n++ >= MAX_FILE_LINES / 2) break; // keep at most half of the limit
                sb.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) {
                Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception ignored) {}
    }

    private static void save(String uuid, String name) {
        Path f = file;
        if (f == null) return;
        WRITER.execute(() -> {
            try {
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                try (BufferedWriter w = Files.newBufferedWriter(f, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
                    w.write(uuid + "\t" + name + "\n");
                }
            } catch (Exception ignored) {}
        });
    }

    /** Looks up several sellers at once; whatever isn't found within maxMs is simply left out (those alerts have no button). */
    static Map<String, String> lookupAll(java.util.Collection<String> uuids, long maxMs) {
        Map<String, String> out = new ConcurrentHashMap<>();
        java.util.List<java.util.concurrent.CompletableFuture<Void>> fs = new java.util.ArrayList<>();
        for (String u : new java.util.LinkedHashSet<>(uuids)) {
            if (u == null) continue;
            String hit = CACHE.get(u);
            if (hit != null) { // known: no network, no waiting
                if (!hit.isEmpty()) out.put(u, hit);
                continue;
            }
            fs.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
                String n = lookup(u);
                if (n != null) out.put(u, n);
            }, POOL));
        }
        if (fs.isEmpty()) return out;
        try {
            java.util.concurrent.CompletableFuture.allOf(fs.toArray(new java.util.concurrent.CompletableFuture[0]))
                    .get(maxMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
            // deadline reached: use what we have (the lookups keep running and are cached for next time)
        }
        return out;
    }

    /** Starts looking up names in the background without waiting (so they are known by the time an alert needs them). */
    static void prefetch(java.util.Collection<String> uuids) {
        for (String u : new java.util.LinkedHashSet<>(uuids)) {
            if (u == null || CACHE.containsKey(u)) continue;
            POOL.execute(() -> lookup(u));
        }
    }

    /** Returns the name, or null if it can't be found quickly (the alert then has no "sellers ah" button). */
    static String lookup(String uuid) {
        if (uuid == null || !Validate.uuid(uuid)) return null;
        String hit = CACHE.get(uuid);
        if (hit != null) return hit.isEmpty() ? null : hit;
        Long failedAt = FAILED.get(uuid);
        if (failedAt != null && System.currentTimeMillis() - failedAt < RETRY_AFTER_MS) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder(
                            URI.create("https://sessionserver.mojang.com/session/minecraft/profile/" + uuid))
                    .timeout(Duration.ofMillis(1500))
                    .header("User-Agent", "Valtrin/1.0")
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200) {
                if (res.body().length() > 8192) throw new IllegalStateException("answer too large");
                String name = JsonParser.parseString(res.body()).getAsJsonObject().get("name").getAsString();
                if (!Validate.player(name)) throw new IllegalStateException("not a username");
                CACHE.put(uuid, name);
                FAILED.remove(uuid);
                save(uuid, name);
                return name;
            }
        } catch (Exception ignored) {
            // fall through: no name this time
        }
        FAILED.put(uuid, System.currentTimeMillis());
        if (FAILED.size() > 5000) FAILED.clear();
        return null;
    }
}
