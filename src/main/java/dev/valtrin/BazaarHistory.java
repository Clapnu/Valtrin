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
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Hypixel's public Bazaar API only exposes the CURRENT instant-buy and instant-sell prices (the
 * weighted prices an instant trade executes at right now). It does not publish executed trades or
 * any history. So the mod records both prices for every enchanted book each time it refreshes the
 * Bazaar (about every 5 minutes while you're on Hypixel), groups them into hourly buckets, keeps
 * 7 days and saves them to disk. The 7-day figure is the average of the instant-buy and
 * instant-sell prices recorded over that window.
 */
final class BazaarHistory {
    private static final long HOUR_MS = 3_600_000L;
    private static final long WINDOW_MS = 7L * 24 * HOUR_MS;
    private static final int SCHEMA = 1;
    private static final String PREFIX = "ENCHANTMENT_";

    private static final class Data {
        int schema = 0;
        /** product -> hour bucket -> [sum of instant-buy prices, sum of instant-sell prices, samples] */
        Map<String, TreeMap<Long, double[]>> hours = new HashMap<>();
        long since = 0;
    }

    private static final Gson GSON = new Gson();
    private final Path file = FlipConfig.configFile("valtrin-bazaar-v1.json.gz", "auxflipper-bazaar-v1.json.gz", "flipscope-bazaar-v1.json.gz", "skyflipper-bazaar-v1.json.gz");
    private Data data = new Data();
    private volatile Map<String, Double> averages = Map.of();

    synchronized void load() {
        try {
            if (Files.exists(file)) {
                try (Reader r = new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8)) {
                    Data d = GSON.fromJson(r, Data.class);
                    if (d != null && d.schema == SCHEMA && d.hours != null) data = d;
                }
            }
        } catch (Exception e) {
            Log.LOGGER.warn("Could not read Bazaar history, starting fresh", e);
        }
        rebuild(System.currentTimeMillis());
    }

    /** @param prices product id -> {instant-buy price, instant-sell price} */
    synchronized void record(Map<String, double[]> prices, long now) {
        if (data.since == 0) data.since = now;
        long hour = now / HOUR_MS;
        for (Map.Entry<String, double[]> e : prices.entrySet()) {
            if (!e.getKey().startsWith(PREFIX)) continue;
            double buy = e.getValue()[0];
            double sell = e.getValue()[1];
            if (buy <= 0 || sell <= 0) continue; // need both sides to average them
            double[] b = data.hours
                    .computeIfAbsent(e.getKey(), k -> new TreeMap<>())
                    .computeIfAbsent(hour, h -> new double[3]);
            b[0] += buy;
            b[1] += sell;
            b[2] += 1;
        }
        rebuild(now);
    }

    /** Average of instant-buy and instant-sell over the last 7 days, or null if never recorded. */
    Double average(String productId) {
        return averages.get(productId);
    }

    int products() {
        return averages.size();
    }

    /** Hours of history recorded so far, capped at the 7-day window. */
    long hoursRecorded(long now) {
        return data.since == 0 ? 0 : Math.min(WINDOW_MS, now - data.since) / HOUR_MS;
    }

    // Recomputes the lookup table once per Bazaar refresh so lookups stay O(1).
    private void rebuild(long now) {
        long minHour = (now - WINDOW_MS) / HOUR_MS;
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, TreeMap<Long, double[]>> e : data.hours.entrySet()) {
            e.getValue().headMap(minHour, false).clear();
            double buy = 0, sell = 0, count = 0;
            for (double[] b : e.getValue().values()) {
                buy += b[0];
                sell += b[1];
                count += b[2];
            }
            if (count > 0) out.put(e.getKey(), (buy / count + sell / count) / 2.0);
        }
        data.hours.values().removeIf(TreeMap::isEmpty);
        averages = out;
    }

    synchronized void save() {
        data.schema = SCHEMA;
        try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(file)), StandardCharsets.UTF_8)) {
            GSON.toJson(data, w);
        } catch (Exception e) {
            Log.LOGGER.warn("Could not save Bazaar history", e);
        }
    }
}
