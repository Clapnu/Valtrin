// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

/**
 * Optional (/flip set record on): writes every auction snapshot to ValtrinData/replay so the market can be replayed
 * later through the same pricing code. One file per snapshot: a full "keyframe" once an hour, otherwise only what
 * changed (added and removed listings). Item images (item_bytes) are NOT stored, only the parsed modifiers.
 * File: replay/{lastUpdated}.tsv.gz. Lines: H (header), A (added listing), R (removed uuid).
 */
final class Recorder {
    private Recorder() {}

    /** One listing of a snapshot. */
    record Rec(String uuid, long start, long price, ItemData d, String seller) {}

    private static final long KEYFRAME_EVERY_MS = 3_600_000L;
    private static final long KEEP_MS = 30L * 86_400_000L;

    private static final ExecutorService EX = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Valtrin-recorder");
        t.setDaemon(true);
        return t;
    });

    // Writer thread only.
    private static Map<String, Rec> prev = new HashMap<>();
    private static long lastKey = 0;
    private static boolean wasOn = false;

    static Path dir() {
        return Path.of(System.getProperty("user.home"), "ValtrinData", "replay");
    }

    static void record(long lu, long seenAt, List<Rec> recs) {
        boolean on = FlipConfig.INSTANCE.record;
        if (!on) {
            if (wasOn) EX.execute(() -> { prev = new HashMap<>(); wasOn = false; });
            return;
        }
        EX.execute(() -> {
            try {
                write(lu, seenAt, recs);
            } catch (Exception e) {
                Log.LOGGER.warn("Recording a snapshot failed: {}", e.toString());
            }
        });
    }

    private static void write(long lu, long seenAt, List<Rec> recs) throws Exception {
        Map<String, Rec> now = new HashMap<>();
        for (Rec r : recs) if (r.d().tier() != null && !r.d().cosmetic()) now.put(r.uuid(), r);
        boolean key = !wasOn || prev.isEmpty() || lu - lastKey >= KEYFRAME_EVERY_MS;
        wasOn = true;
        Path d = dir();
        Files.createDirectories(d);
        Path tmp = d.resolve(lu + ".tmp");
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(tmp)), StandardCharsets.UTF_8))) {
            w.write("H\t" + lu + "\t" + (key ? "K" : "D") + "\t" + seenAt + "\n");
            for (Rec r : now.values()) {
                if (key || !prev.containsKey(r.uuid())) {
                    ItemData x = r.d();
                    w.write("A\t" + r.uuid() + "\t" + r.start() + "\t" + r.price() + "\t" + x.id() + "\t" + x.tier() + "\t"
                            + x.base() + "\t" + ModTokens.encode(ModTokens.of(x)) + "\t" + (r.seller() == null ? "" : r.seller()) + "\n");
                }
            }
            if (!key) {
                for (String u : prev.keySet()) if (!now.containsKey(u)) w.write("R\t" + u + "\n");
            }
        }
        Files.move(tmp, d.resolve(lu + ".tsv.gz"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        prev = now;
        if (key) {
            lastKey = lu;
            prune(d, lu);
        }
    }

    /** Deletes recordings older than 30 days (a replay can only start at a keyframe anyway). */
    private static void prune(Path d, long lu) {
        try (Stream<Path> s = Files.list(d)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (!n.endsWith(".tsv.gz")) return;
                try {
                    if (lu - Long.parseLong(n.substring(0, n.length() - 7)) > KEEP_MS) Files.deleteIfExists(p);
                } catch (Exception ignored) {}
            });
        } catch (Exception ignored) {}
    }
}
