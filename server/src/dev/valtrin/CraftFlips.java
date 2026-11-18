// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Craft Flips for the website: items that cost less to craft than they sell for.
 * Recipes come from the NotEnoughUpdates item repository (MIT license, see DATA_SOURCES.md), downloaded as one archive
 * about once a week and read as a stream, so the whole archive is never held in memory. Prices are the ones the engine
 * already has (Bazaar and auction data). Nothing here buys, sells or sends anything.
 */
final class CraftFlips {
    private static final String ARCHIVE = "https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO/archive/refs/heads/master.tar.gz";
    private static final long RECIPES_KEEP_MS = 7L * 86_400_000L;
    private static final int MAX_ENTRY = 262_144;          // a recipe file bigger than this is ignored
    private static final long MAX_ARCHIVE = 400L << 20;     // never read more than this many bytes of archive
    private static final int OUT = 200;                     // crafts kept in the feed
    private static final int VOLUME_CANDIDATES = 300;       // only the most profitable crafts get a volume lookup

    private final Path file;
    private volatile Map<String, RecipeData.Recipe> recipes = Map.of();
    private volatile long recipesAt = 0;
    private volatile String craftsJson = "[]";
    private volatile long updatedAt = 0;
    private volatile int shown = 0;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private ScheduledExecutorService ex;
    private static final long VOLUME_TTL_MS = 15L * 60_000L;
    private final Map<String, double[]> volumeCache = new java.util.concurrent.ConcurrentHashMap<>(); // id -> {at, perDay}

    CraftFlips(Path dataDir) {
        this.file = dataDir.resolve("craft-recipes.json");
    }

    void start() {
        ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "valtrin-craft");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::safeTick, 30, 60, TimeUnit.SECONDS);
    }

    void stop() {
        if (ex != null) ex.shutdownNow();
    }

    String json(long now) {
        return "{\"now\":" + now + ",\"updatedAt\":" + updatedAt + ",\"recipes\":" + recipes.size()
                + ",\"crafts\":" + craftsJson + "}";
    }

    // ------------------------------------------------------------------ loop

    private void safeTick() {
        try {
            long now = System.currentTimeMillis();
            if (recipes.isEmpty() || now - recipesAt > RECIPES_KEEP_MS) loadRecipes(now);
            if (recipes.isEmpty() || !Pricing.ready()) return;
            compute(now);
        } catch (Throwable t) {
            Log.LOGGER.warn("Craft flips update failed: {}", t.toString());
        }
    }

    private void loadRecipes(long now) {
        if (recipes.isEmpty()) { // just started: use the copy on disk if there is one, however old
            Map<String, RecipeData.Recipe> disk = readFile();
            long age = fileAge(now);
            if (disk != null && !disk.isEmpty() && age >= 0) {
                recipes = disk;
                recipesAt = now - age;
                Log.LOGGER.info("Craft flips: {} recipes read from disk", disk.size());
                if (age < RECIPES_KEEP_MS) return;
            }
        }
        try {
            Map<String, RecipeData.Recipe> fresh = download();
            if (fresh.size() >= 100) {
                recipes = fresh;
                recipesAt = now;
                writeFile(fresh);
                Log.LOGGER.info("Craft flips: {} recipes downloaded", fresh.size());
            } else {
                Log.LOGGER.warn("Craft flips: the recipe archive had only {} recipes, ignored", fresh.size());
                recipesAt = now - RECIPES_KEEP_MS + 600_000L; // try again in 10 minutes
            }
        } catch (Exception e) {
            Log.LOGGER.warn("Craft flips: recipe download failed: {}", e.toString());
            recipesAt = now - RECIPES_KEEP_MS + 600_000L;
        }
    }

    // ------------------------------------------------------------------ prices

    private record Row(String id, long cost, long sell, long profit, double margin, String src, boolean bazaar,
                       Map<String, Long> ing) {}

    private void compute(long now) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, RecipeData.Recipe> e : recipes.entrySet()) {
            String id = e.getKey();
            RecipeData.Recipe r = e.getValue();
            long sum = 0;
            boolean ok = true;
            for (Map.Entry<String, Long> in : r.ingredients().entrySet()) {
                long unit = in.getKey().equals("SKYBLOCK_COIN") ? 1 : Pricing.itemValue(in.getKey());
                if (unit <= 0) { ok = false; break; }
                sum += unit * in.getValue();
            }
            if (!ok || sum <= 0) continue;
            long cost = sum / Math.max(1, r.count());
            if (cost <= 0) continue;
            long sell = Pricing.itemValue(id);
            if (sell <= 0) continue;
            boolean bazaar = Pricing.isBazaar(id);
            long net = bazaar ? Math.round(sell * 0.9875) : sell - FlipEngine.fees(sell); // Bazaar 1.25% tax, or Auction House fees
            long profit = net - cost;
            if (profit <= 0) continue;
            double margin = profit * 100.0 / cost;
            if (margin > 900) continue; // such a gap is almost always a thin or wrong price, not a real craft
            rows.add(new Row(id, cost, sell, profit, margin, Pricing.itemSource(id), bazaar, r.ingredients()));
        }
        rows.sort((a, b) -> Long.compare(b.profit, a.profit));
        if (rows.size() > VOLUME_CANDIDATES) rows = new ArrayList<>(rows.subList(0, VOLUME_CANDIDATES));

        List<double[]> vols = new ArrayList<>();
        volumeCache.keySet().removeIf(k -> { double[] c = volumeCache.get(k); return c == null || now - c[0] > 2 * VOLUME_TTL_MS; });
        for (Row r : rows) vols.add(new double[]{cachedVolume(r.id, r.bazaar, now)});
        JsonArray out = new JsonArray();
        for (int i = 0; i < rows.size() && out.size() < OUT; i++) {
            Row r = rows.get(i);
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id);
            o.addProperty("name", Pricing.displayName(r.id));
            o.addProperty("cost", r.cost);
            o.addProperty("sell", r.sell);
            o.addProperty("profit", r.profit);
            o.addProperty("margin", Math.round(r.margin * 10) / 10.0);
            o.addProperty("volume", Math.round(vols.get(i)[0] * 10) / 10.0);
            o.addProperty("src", r.src);
            JsonArray ing = new JsonArray();
            for (String line : ingredientLines(r.ing)) ing.add(line);
            o.add("ing", ing);
            out.add(o);
        }
        craftsJson = out.toString();
        updatedAt = now;
        shown = out.size();
    }

    private static List<String> ingredientLines(Map<String, Long> ing) {
        List<long[]> tot = new ArrayList<>();
        List<String> keys = new ArrayList<>(ing.keySet());
        for (String k : keys) {
            long unit = k.equals("SKYBLOCK_COIN") ? 1 : Math.max(0, Pricing.itemValue(k));
            tot.add(new long[]{unit * ing.get(k), keys.indexOf(k)});
        }
        tot.sort((a, b) -> Long.compare(b[0], a[0]));
        List<String> lines = new ArrayList<>();
        int n = Math.min(5, tot.size());
        for (int i = 0; i < n; i++) {
            String k = keys.get((int) tot.get(i)[1]);
            String src = k.equals("SKYBLOCK_COIN") ? "coins" : Pricing.itemSource(k);
            lines.add(ing.get(k) + "x " + Pricing.displayName(k) + " " + Format.compact(tot.get(i)[0]) + " (" + src + ")");
        }
        if (tot.size() > n) lines.add("+" + (tot.size() - n) + " cheaper ingredients");
        return lines;
    }

    /** Auction volumes come from the sold-auction log, which is read from disk, so they are kept for 15 minutes. */
    private double cachedVolume(String id, boolean bazaar, long now) {
        if (bazaar) return volume(id, true, now); // a map lookup, always fresh
        double[] c = volumeCache.get(id);
        if (c != null && now - c[0] < VOLUME_TTL_MS) return c[1];
        double v = volume(id, false, now);
        if (v >= 0) volumeCache.put(id, new double[]{now, v}); // -1 = log still loading: ask again next minute
        return v;
    }

    /** Sales per day: Bazaar items from Hypixel's 7-day figure, auction items from the sold-auction log (last 7 days). */
    private static double volume(String id, boolean bazaar, long now) {
        if (bazaar) {
            Double w = Pricing.bazaarWeek.get(id);
            return w == null ? 0 : w / 7.0;
        }
        SaleLog log = OwnModel.log;
        if (log == null) return -1;
        List<SaleLog.Sale> l = log.peek(id);
        if (l == null) return -1;
        long from = now - 7L * 86_400_000L, oldest = now;
        int n = 0;
        for (SaleLog.Sale s : l) {
            if (s.ts() < from || s.ts() > now) continue;
            n++;
            oldest = Math.min(oldest, s.ts());
        }
        if (n == 0) return 0;
        double days = Math.max(1.0, Math.min(7.0, (now - oldest) / 86_400_000.0));
        return n / days;
    }

    // ------------------------------------------------------------------ recipe archive

    private Map<String, RecipeData.Recipe> download() throws IOException, InterruptedException {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(ARCHIVE)).timeout(Duration.ofSeconds(120))
                .header("User-Agent", "Valtrin").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("status " + r.statusCode());
        }
        try (InputStream in = r.body()) {
            // A stalled connection has no read timeout of its own, so a watchdog closes it after 4 minutes.
            Thread dog = new Thread(() -> {
                try { Thread.sleep(240_000L); in.close(); } catch (InterruptedException | IOException ignored) { /* finished in time */ }
            }, "valtrin-craft-watchdog");
            dog.setDaemon(true);
            dog.start();
            try {
                return readArchive(new GZIPInputStream(new BufferedInputStream(in, 1 << 16)));
            } finally {
                dog.interrupt();
            }
        }
    }

    /** Reads a tar stream and keeps the recipe of every items/ID.json. Visible for tests. */
    static Map<String, RecipeData.Recipe> readArchive(InputStream tar) throws IOException {
        Map<String, RecipeData.Recipe> out = new LinkedHashMap<>();
        byte[] h = new byte[512];
        long total = 0;
        String longName = null;
        while (true) {
            if (!readFully(tar, h, 512)) break;
            total += 512;
            if (h[0] == 0) break; // end of archive
            String name = cstr(h, 0, 100);
            String prefix = cstr(h, 345, 155);
            if (!prefix.isEmpty()) name = prefix + "/" + name;
            if (longName != null) { name = longName; longName = null; }
            long size = octal(h, 124, 12);
            char type = (char) h[156];
            if (size < 0 || total + size > MAX_ARCHIVE) throw new IOException("bad or oversized archive");
            long padded = (size + 511) / 512 * 512;
            boolean wanted = (type == '0' || type == 0) && size > 0 && size <= MAX_ENTRY && isItemFile(name);
            if (type == 'L' && size <= 4096) {            // GNU long name for the next entry
                byte[] b = new byte[(int) size];
                if (!readFully(tar, b, b.length)) throw new IOException("short archive");
                longName = cstr(b, 0, b.length);
                skip(tar, padded - size);
            } else if (wanted) {
                byte[] b = new byte[(int) size];
                if (!readFully(tar, b, b.length)) throw new IOException("short archive");
                skip(tar, padded - size);
                String id = itemId(name);
                if (Validate.itemId(id) && !id.contains(";") && !id.equals("PET")) {
                    RecipeData.Recipe rec = RecipeData.parse(new String(b, StandardCharsets.UTF_8));
                    if (rec != null && rec.count() > 0) out.put(id, rec);
                }
            } else {
                skip(tar, padded);
            }
            total += padded;
        }
        return out;
    }

    private static boolean isItemFile(String name) {
        int i = name.indexOf("/items/");
        return i >= 0 && name.endsWith(".json") && name.indexOf('/', i + 7) < 0;
    }

    private static String itemId(String name) {
        int i = name.lastIndexOf('/');
        return name.substring(i + 1, name.length() - 5);
    }

    private static boolean readFully(InputStream in, byte[] b, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(b, off, n - off);
            if (r < 0) {
                if (off == 0) return false;
                throw new IOException("short archive");
            }
            off += r;
        }
        return true;
    }

    private static void skip(InputStream in, long n) throws IOException {
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, n));
            if (r < 0) throw new IOException("short archive");
            n -= r;
        }
    }

    private static String cstr(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && end < b.length && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // base-256 size for very large entries
            long v = 0;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xff);
            return v;
        }
        String s = cstr(b, off, len).trim();
        if (s.isEmpty()) return 0;
        try {
            return Long.parseLong(s, 8);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ------------------------------------------------------------------ disk copy

    private long fileAge(long now) {
        try {
            return now - Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private Map<String, RecipeData.Recipe> readFile() {
        try {
            if (!Files.exists(file)) return null;
            JsonObject o = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            Map<String, RecipeData.Recipe> out = new LinkedHashMap<>();
            for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
                JsonObject r = e.getValue().getAsJsonObject();
                Map<String, Long> ing = new LinkedHashMap<>();
                for (Map.Entry<String, com.google.gson.JsonElement> i : r.getAsJsonObject("i").entrySet()) {
                    ing.put(i.getKey(), i.getValue().getAsLong());
                }
                if (!ing.isEmpty() && Validate.itemId(e.getKey())) out.put(e.getKey(), new RecipeData.Recipe(ing, Math.max(1, r.get("c").getAsInt())));
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private void writeFile(Map<String, RecipeData.Recipe> m) {
        try {
            JsonObject o = new JsonObject();
            for (Map.Entry<String, RecipeData.Recipe> e : m.entrySet()) {
                JsonObject r = new JsonObject();
                JsonObject i = new JsonObject();
                for (Map.Entry<String, Long> in : e.getValue().ingredients().entrySet()) i.addProperty(in.getKey(), in.getValue());
                r.add("i", i);
                r.addProperty("c", e.getValue().count());
                o.add(e.getKey(), r);
            }
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, o.toString());
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.LOGGER.warn("Craft flips: could not save recipes: {}", e.toString());
        }
    }

    /** For tests: use these recipes and work out the feed now. */
    void useRecipes(Map<String, RecipeData.Recipe> m, long now) {
        recipes = m;
        recipesAt = now;
        compute(now);
    }

    int shown() { return shown; }
    long updatedAt() { return updatedAt; }
}
