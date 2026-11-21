// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Every sold BIN auction, stored individually (item, rarity, modifiers, price, time) in plain text files:
 * {root}/{yyyy-MM-dd}/{ITEM_ID}.tsv, so a lookup only reads the files of one item for the last few days.
 * There is no age limit. When the folder grows past MAX_BYTES the oldest days are deleted first; that is checked at
 * start-up and then once a day while the program keeps running.
 * Also remembers which minutes the sold-auction feed was actually watched, so volume per day is only
 * divided by time we really observed.
 */
final class SaleLog {
    static final int LOAD_DAYS = 14;
    static final long MAX_BYTES = 20L * 1024 * 1024 * 1024;
    private static final long PRUNE_EVERY_MS = 24L * 3_600_000L;
    /** Items whose sales stay in memory (least recently used dropped first). The server raises it, see ServerMain. */
    private static final int MAX_LOADED_IDS = Math.max(100, Math.min(50_000, Integer.getInteger("valtrin.salesCacheIds", 600)));

    record Sale(long ts, long price, String tier, String base, List<ModTokens.Tok> toks, int schema) implements OwnModel.Cand {}

    /** When the last sale was added to this log (used to warn when nothing is being recorded). */
    volatile long lastAddAt = 0;
    private volatile long lastPruneAt = 0;

    private record Pending(String id, Sale sale, String enc) {}

    private final Path root;
    private final Map<String, Long> loadedAt = new ConcurrentHashMap<>();
    private volatile long lastCoverageReload = 0;
    private volatile long heartbeatCheckedAt = 0;
    private volatile boolean heartbeatFresh = false;
    private final Map<String, List<Sale>> loaded = new LinkedHashMap<>(256, 0.75f, true);
    private final Set<String> loading = ConcurrentHashMap.newKeySet();
    /** Items kept in memory no matter what (the most listed ones), so busy items are never dropped and re-read mid-session. */
    private volatile Set<String> pinned = Set.of();
    /** Item id -> time before which a failed read is not tried again. */
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Pending> pending = new ConcurrentLinkedQueue<>();
    private final ExecutorService io = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "valtrin-salelog");
        t.setDaemon(true);
        return t;
    });

    private final TreeSet<Long> covered = new TreeSet<>();
    private final ConcurrentLinkedQueue<Long> coveredNew = new ConcurrentLinkedQueue<>();
    volatile long storedSales = 0;

    // Remote mode: sales are read from a shared recorder server instead of local files.
    private final String remoteUrl;
    private final String remoteKey;
    private final java.util.concurrent.ConcurrentHashMap<Integer, long[]> remoteCoverage = new java.util.concurrent.ConcurrentHashMap<>();
    volatile String remoteError = "";
    private java.net.http.HttpClient http;

    SaleLog(Path root) {
        this.root = root;
        this.remoteUrl = null;
        this.remoteKey = null;
    }

    /** A shared server answer larger than this is refused (64 MB, far above real files). */
    private static final int MAX_REMOTE_BYTES = 64 * 1024 * 1024;

    SaleLog(String remoteUrl, String remoteKey) {
        this.root = null;
        this.remoteUrl = remoteUrl.endsWith("/") ? remoteUrl.substring(0, remoteUrl.length() - 1) : remoteUrl;
        this.remoteKey = remoteKey == null ? "" : remoteKey;
        this.http = java.net.http.HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build();
    }

    boolean remote() {
        return remoteUrl != null;
    }

    String remoteUrl() {
        return remoteUrl;
    }

    /** GET {remote}/path with the access key; returns the (decompressed) body or null on failure. */
    private String remoteGet(String path) {
        try {
            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(java.net.URI.create(remoteUrl + path))
                    .timeout(java.time.Duration.ofSeconds(30))
                    .header("X-Key", remoteKey)
                    .header("Accept-Encoding", "gzip")
                    .GET().build();
            java.net.http.HttpResponse<java.io.InputStream> res = http.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
            if (res.statusCode() != 200) {
                res.body().close();
                remoteError = "HTTP " + res.statusCode() + (res.statusCode() == 401 ? " (wrong key)" : "");
                return null;
            }
            String enc = res.headers().firstValue("Content-Encoding").orElse("");
            try (java.io.InputStream in = enc.equalsIgnoreCase("gzip") ? new java.util.zip.GZIPInputStream(res.body()) : res.body()) {
                byte[] raw = in.readNBytes(MAX_REMOTE_BYTES + 1);
                if (raw.length > MAX_REMOTE_BYTES) { remoteError = "answer too large"; return null; }
                String body = new String(raw, StandardCharsets.UTF_8);
                remoteError = "";
                return body;
            }
        } catch (Exception e) {
            remoteError = String.valueOf(e.getMessage() == null ? e : e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------ stand-alone recorder

    /** True while the stand-alone recorder program is running (it touches this file every minute). */
    boolean recorderActive() {
        if (remote()) return true;
        long now = Clock.now();
        if (now - heartbeatCheckedAt < 15_000) return heartbeatFresh;
        heartbeatCheckedAt = now;
        try {
            Path hb = root.resolve("recorder.heartbeat");
            heartbeatFresh = Files.isRegularFile(hb) && now - Files.getLastModifiedTime(hb).toMillis() < 3 * 60_000L;
        } catch (IOException e) {
            heartbeatFresh = false;
        }
        return heartbeatFresh;
    }

    /** Called by the recorder: marks it as running. */
    void heartbeat() {
        try {
            Files.writeString(root.resolve("recorder.heartbeat"), String.valueOf(Clock.now()), StandardCharsets.UTF_8);
        } catch (IOException ignored) {}
    }

    Path root() {
        return root;
    }

    // ------------------------------------------------------------------ start-up

    void init() {
        if (remote()) return;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            Log.LOGGER.warn("Cannot create sales folder", e);
            return;
        }
        io.execute(() -> {
            lastPruneAt = System.currentTimeMillis();
            prune();
            loadCoverage();
        });
    }

    private void prune() {
        try (Stream<Path> days = Files.list(root)) {
            List<Path> dirs = days.filter(Files::isDirectory).sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
            long total = 0;
            List<long[]> sizes = new ArrayList<>();
            for (Path dir : dirs) {
                long size = dirSize(dir);
                total += size;
                sizes.add(new long[]{size});
            }
            for (int i = 0; i < dirs.size(); i++) {
                try {
                    LocalDate.parse(dirs.get(i).getFileName().toString());
                } catch (Exception e) {
                    continue; // not one of ours
                }
                if (total > MAX_BYTES) {
                    total -= sizes.get(i)[0];
                    deleteTree(dirs.get(i));
                }
            }
        } catch (IOException ignored) {}
    }

    private static long dirSize(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> {
                try { return Files.size(p); } catch (IOException e) { return 0; }
            }).sum();
        } catch (IOException e) {
            return 0;
        }
    }

    private static void deleteTree(Path dir) {
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }

    // ------------------------------------------------------------------ writing

    void add(String id, long ts, long price, String tier, String base, List<ModTokens.Tok> toks) {
        if (remote()) return;
        Sale s = new Sale(ts, price, tier, base, toks, ModTokens.SCHEMA);
        lastAddAt = System.currentTimeMillis();
        pending.add(new Pending(id, s, ModTokens.encode(toks)));
        synchronized (loaded) {
            List<Sale> l = loaded.get(id);
            if (l != null) l.add(s);
        }
        storedSales++;
    }

    void markCovered(long minute) {
        if (remote()) return;
        synchronized (covered) {
            if (covered.add(minute)) coveredNew.add(minute);
        }
    }

    /** Writes everything queued since the last call. Safe to call from any thread. */
    synchronized void flush() {
        if (remote()) return;
        long nowMs = System.currentTimeMillis();
        if (lastPruneAt > 0 && nowMs - lastPruneAt >= PRUNE_EVERY_MS) {
            lastPruneAt = nowMs;
            io.execute(this::prune); // a program that runs for weeks must trim itself too, not only at start-up
        }
        Map<Path, StringBuilder> files = new java.util.HashMap<>();
        Pending p;
        while ((p = pending.poll()) != null) {
            String day = LocalDate.ofEpochDay(Math.floorDiv(p.sale().ts(), 86_400_000L)).toString();
            Path f = root.resolve(day).resolve(safe(p.id()) + ".tsv");
            files.computeIfAbsent(f, k -> new StringBuilder()).append(p.sale().ts()).append('\t').append(p.sale().price())
                    .append('\t').append(p.sale().tier()).append('\t').append(p.sale().base()).append('\t').append(p.enc()).append('\t').append(p.sale().schema()).append('\n');
        }
        for (Map.Entry<Path, StringBuilder> e : files.entrySet()) {
            try {
                Files.createDirectories(e.getKey().getParent());
                Files.writeString(e.getKey(), e.getValue(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                Log.LOGGER.warn("Could not write sales: {}", ex.toString());
            }
        }
        StringBuilder cov = new StringBuilder();
        Long m;
        while ((m = coveredNew.poll()) != null) cov.append(m).append('\n');
        if (cov.length() > 0) {
            try {
                Files.writeString(root.resolve("coverage.txt"), cov, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ex) {
                Log.LOGGER.warn("Could not write coverage: {}", ex.toString());
            }
        }
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_]", "_");
    }

    // ------------------------------------------------------------------ reading

    boolean isLoaded(String id) {
        synchronized (loaded) {
            return loaded.containsKey(id);
        }
    }

    /** Version of the sales file format. 2 = sales carry all modifiers of v14 (Etherwarp, kill tier, dungeon quality, ...). */
    static final int FORMAT = 2;

    /** Creates the folder and marks it with the current format (used by the mod and the stand-alone recorder). */
    static void markFormat(Path root) {
        try {
            Files.createDirectories(root);
            Path f = root.resolve("format.txt");
            if (!Files.exists(f)) Files.writeString(f, String.valueOf(FORMAT), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Log.LOGGER.warn("Could not mark sales folder: {}", e.toString());
        }
    }

    void dropCache() {
        synchronized (loaded) {
            loaded.clear();
            loadedAt.clear();
        }
    }

    void pin(Set<String> ids) {
        pinned = ids;
    }

    /** Recent sales of one item id, or null while they are still being read from disk (a read is started). */
    List<Sale> peek(String id) {
        List<Sale> have = null;
        synchronized (loaded) {
            List<Sale> l = loaded.get(id);
            if (l != null) have = new ArrayList<>(l);
        }
        boolean stale = have != null && recorderActive()
                && Clock.now() - loadedAt.getOrDefault(id, 0L) > 120_000L;
        if (have != null && !stale) return have;
        Long wait = retryAt.get(id);
        if (have == null && wait != null && Clock.now() < wait) return null; // a read just failed: try again shortly
        if (loading.add(id)) {
            io.execute(() -> {
                try {
                    List<Sale> fetched = read(id);
                    if (fetched == null) {
                        // A failed read must NOT look like "this item has no sales": leave it unloaded and try again soon.
                        retryAt.put(id, Clock.now() + 5_000L);
                        return;
                    }
                    retryAt.remove(id);
                    synchronized (loaded) {
                        List<Sale> existing = loaded.get(id);
                        boolean replace = recorderActive();
                        if (existing != null && !replace) fetched.addAll(existing);
                        loaded.put(id, fetched);
                        loadedAt.put(id, Clock.now());
                        evict();
                    }
                } finally {
                    loading.remove(id);
                }
            });
        }
        return have;
    }

    /** Drops the least recently used items above the limit, but never the pinned (busiest) ones. Call with the lock held. */
    private void evict() {
        Set<String> keep = pinned;
        java.util.Iterator<String> it = loaded.keySet().iterator();
        while (loaded.size() > MAX_LOADED_IDS && it.hasNext()) {
            String id = it.next();
            if (keep.contains(id)) continue;
            it.remove();
            loadedAt.remove(id);
        }
    }

    /** Token-set version of a stored line (a missing column = a sale from before versions existed). */
    private static int schemaOf(String[] p) {
        if (p.length < 6) return ModTokens.LEGACY_SCHEMA;
        try { return Integer.parseInt(p[5].trim()); } catch (NumberFormatException e) { return ModTokens.LEGACY_SCHEMA; }
    }

    private static List<Sale> parse(String text) {
        List<Sale> out = new ArrayList<>();
        for (String line : text.split("\n")) {
            String[] p = line.split("\t", 6);
            if (p.length < 5) continue;
            try {
                out.add(new Sale(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], p[3], ModTokens.normalize(ModTokens.decode(p[4])), schemaOf(p)));
            } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** All stored lines of one item from the last {@code days} days (used by the recorder's server). */
    String rawSales(String id, int days) {
        StringBuilder sb = new StringBuilder();
        LocalDate today = LocalDate.ofEpochDay(Math.floorDiv(Clock.now(), 86_400_000L));
        long cutoff = Clock.now() - (long) days * 86_400_000L;
        for (int i = Math.min(days, 60); i >= 0; i--) {
            Path f = root.resolve(today.minusDays(i).toString()).resolve(safe(id) + ".tsv");
            if (!Files.isRegularFile(f)) continue;
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    int t = line.indexOf('\t');
                    if (t <= 0) continue;
                    try {
                        if (Long.parseLong(line.substring(0, t)) >= cutoff) sb.append(line).append('\n');
                    } catch (NumberFormatException ignored) {}
                }
            } catch (IOException ignored) {}
        }
        return sb.toString();
    }

    private List<Sale> read(String id) {
        if (remote()) {
            String body = remoteGet("/sales?id=" + java.net.URLEncoder.encode(id, StandardCharsets.UTF_8) + "&days=" + LOAD_DAYS);
            return body == null ? null : parse(body);
        }
        List<Sale> out = new ArrayList<>();
        LocalDate today = LocalDate.ofEpochDay(Math.floorDiv(Clock.now(), 86_400_000L));
        // Sales waiting to be written are not on disk yet; flushing first keeps the picture complete.
        flush();
        boolean error = false;
        for (int i = LOAD_DAYS; i >= 0; i--) {
            Path f = root.resolve(today.minusDays(i).toString()).resolve(safe(id) + ".tsv");
            if (!Files.isRegularFile(f)) continue;
            try {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    String[] p = line.split("\t", 6);
                    if (p.length < 5) continue;
                    try {
                        out.add(new Sale(Long.parseLong(p[0]), Long.parseLong(p[1]), p[2], p[3], ModTokens.normalize(ModTokens.decode(p[4])), schemaOf(p)));
                    } catch (NumberFormatException ignored) {}
                }
            } catch (IOException ex) {
                error = true; // file busy / unreadable right now
            }
        }
        // A read that failed and found nothing is reported as failed (retried soon), not as "no sales".
        return error && out.isEmpty() ? null : out;
    }

    private void loadCoverage() {
        Path f = root.resolve("coverage.txt");
        if (!Files.isRegularFile(f)) return;
        long cutoff = Clock.now() / 60_000L - (long) (LOAD_DAYS + 1) * 1440;
        try {
            synchronized (covered) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    try {
                        long m = Long.parseLong(line.trim());
                        if (m >= cutoff) covered.add(m);
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (IOException ignored) {}
    }

    /** Minutes of the last {@code days} days during which the sold-auction feed was watched. */
    long coveredMinutes(long now, int days) {
        if (remote()) {
            long[] c = remoteCoverage.get(days);
            if (c == null || now - c[1] > 120_000L) {
                if (c == null) remoteCoverage.put(days, new long[]{0, now}); // placeholder, prevents a pile of requests
                else c[1] = now;
                io.execute(() -> {
                    String body = remoteGet("/coverage?days=" + days);
                    if (body != null) {
                        try { remoteCoverage.put(days, new long[]{Long.parseLong(body.trim()), Clock.now()}); } catch (NumberFormatException ignored) {}
                    }
                });
            }
            long[] cur = remoteCoverage.get(days);
            return cur == null ? 0 : cur[0];
        }
        if (recorderActive() && now - lastCoverageReload > 120_000L) {
            lastCoverageReload = now;
            io.execute(this::loadCoverage);
        }
        long to = now / 60_000L, from = to - (long) days * 1440;
        synchronized (covered) {
            return covered.subSet(from, true, to, true).size();
        }
    }

    long totalBytes() {
        return root == null ? 0 : dirSize(root);
    }
}
