// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.Gson;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Hypixel has no "sales per day" or "sold prices" endpoint, so we build them: every ended BIN
 * auction seen via /skyblock/auctions_ended is counted into 10-minute buckets per item group
 * (volume, kept 24h) and its price is remembered (median, kept 7 days). A group is the item id +
 * stars + recombobulated + rarity (see ItemData.key()), so volume and median are specific to the
 * exact kind of item. Data is saved to disk, so the numbers get better the more you play.
 *
 * Sold prices are stored together with the value of the item's modifiers, so the median can be
 * computed for the plain item and then re-adjusted for the exact item you're looking at.
 */
final class VolumeTracker {
    private static final long BUCKET_MS = 10L * 60 * 1000;
    private static final long VOLUME_WINDOW_MS = 7L * 24 * 60 * 60 * 1000; // sales/day is averaged over up to 7 days
    private static final long PRICE_WINDOW_MS = 7L * 24 * 60 * 60 * 1000;
    private static final int MAX_PRICE_SAMPLES = 60;
    private static final int SCHEMA = 4;

    private static final class Data {
        int schema = 0;
        long trackingSince = 0;
        long lastActive = 0;
        Map<String, TreeMap<Long, Integer>> sales = new HashMap<>();
        /** item -> flat list of [timestamp, price, modifierValue, ...], newest last. */
        Map<String, long[]> prices = new HashMap<>();
    }

    /** Median of recent sold prices for the plain item (modifiers removed). */
    record Median(long value, long average, int samples) {}

    private static final Gson GSON = new Gson();
    private final Path file = FlipConfig.configFile("valtrin-volume-v4.json.gz", "auxflipper-volume-v4.json.gz", "flipscope-volume-v4.json.gz", "skyflipper-volume-v4.json.gz");
    private Data data = new Data();

    synchronized void load() {
        try {
            if (Files.exists(file)) {
                try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
                    Data d = GSON.fromJson(r, Data.class);
                    if (d != null && d.schema == SCHEMA && d.sales != null && d.prices != null) {
                        data = d;
                        // Time the game was closed isn't "observed" time - exclude it from the baseline.
                        long now = Clock.now();
                        long gap = now - data.lastActive;
                        if (data.trackingSince > 0 && gap > 5 * 60 * 1000) {
                            data.trackingSince = Math.min(now, data.trackingSince + gap);
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.LOGGER.warn("Could not read volume data, starting fresh", e);
        }
    }

    synchronized void touch(long now) {
        if (data.trackingSince == 0) data.trackingSince = now;
        data.lastActive = now;
    }

    /**
     * Counts one sale. If the price and modifier value are known (modifierValue >= 0) the price is
     * also remembered for the median.
     */
    synchronized void recordSale(String baseKey, long timestamp, long price, long modifierValue) {
        data.sales.computeIfAbsent(baseKey, k -> new TreeMap<>())
                .merge(timestamp / BUCKET_MS, 1, Integer::sum);
        if (price <= 0 || modifierValue < 0) return;

        long[] cur = data.prices.get(baseKey);
        int n = cur == null ? 0 : cur.length;
        int keep = Math.min(n, (MAX_PRICE_SAMPLES - 1) * 3);
        long[] next = new long[keep + 3];
        if (keep > 0) System.arraycopy(cur, n - keep, next, 0, keep);
        next[keep] = timestamp;
        next[keep + 1] = price;
        next[keep + 2] = modifierValue;
        data.prices.put(baseKey, next);
    }

    /**
     * Median/average sold price of the plain item over the last 7 days.
     * @param modFactor fraction of modifier value that is counted (the "% counted" setting)
     */
    synchronized Median median(String baseKey, long now, double modFactor) {
        long[] cur = data.prices.get(baseKey);
        if (cur == null || cur.length < 3) return null;
        long cutoff = now - PRICE_WINDOW_MS;
        List<Long> list = new ArrayList<>();
        long sum = 0;
        for (int i = 0; i + 2 < cur.length; i += 3) {
            if (cur[i] < cutoff) continue;
            long plain = cur[i + 1] - Math.round(cur[i + 2] * modFactor);
            if (plain <= 0 || plain < cur[i + 1] / 10) continue; // modifiers can't be ~all of a sale
            list.add(plain);
            sum += plain;
        }
        if (list.isEmpty()) return null;
        Collections.sort(list);
        int n = list.size();
        long median = n % 2 == 1 ? list.get(n / 2) : (list.get(n / 2 - 1) + list.get(n / 2)) / 2;
        return new Median(median, sum / n, n);
    }

    /** Average sales per day, or -1 while still warming up. */
    synchronized int perDay(String baseKey, long now, int warmupMinutes) {
        if (data.trackingSince == 0) return -1;
        long observed = now - data.trackingSince;
        if (observed < warmupMinutes * 60_000L) return -1;

        int count = 0;
        TreeMap<Long, Integer> buckets = data.sales.get(baseKey);
        if (buckets != null) {
            long minBucket = (now - VOLUME_WINDOW_MS) / BUCKET_MS;
            for (int v : buckets.tailMap(minBucket, true).values()) count += v;
        }
        // average per day over the time actually observed (at most 7 days)
        double days = Math.max(Math.min(observed, VOLUME_WINDOW_MS), 60_000L) / 86_400_000.0;
        return (int) Math.round(count / days);
    }

    synchronized boolean warmedUp(long now, int warmupMinutes) {
        return data.trackingSince != 0 && now - data.trackingSince >= warmupMinutes * 60_000L;
    }

    synchronized void save(long now) {
        long minBucket = (now - VOLUME_WINDOW_MS) / BUCKET_MS;
        Iterator<Map.Entry<String, TreeMap<Long, Integer>>> it = data.sales.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, TreeMap<Long, Integer>> e = it.next();
            e.getValue().headMap(minBucket, false).clear();
            if (e.getValue().isEmpty()) it.remove();
        }
        Iterator<Map.Entry<String, long[]>> pit = data.prices.entrySet().iterator();
        while (pit.hasNext()) {
            long[] v = pit.next().getValue();
            if (v.length < 3 || v[v.length - 3] < now - PRICE_WINDOW_MS) pit.remove();
        }
        data.schema = SCHEMA;
        data.lastActive = now;
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            Log.LOGGER.warn("Could not save volume data", e);
        }
    }
}
