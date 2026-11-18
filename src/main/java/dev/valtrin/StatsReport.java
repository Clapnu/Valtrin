// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional: sends your completed flips (when it was, item name, coins paid, coins received) to the Valtrin server you
 * entered, so they show on your Flip Stats page on the website. It is OFF by default and only runs when you turn it on
 * with /flip profit share on. Nothing else is sent: no chat text, no Minecraft name. Flips from before you turned it on
 * are never sent. It uses the same address and key as server alerts, never follows a redirect, and stops if the server
 * says the key is not premium.
 */
final class StatsReport {
    private StatsReport() {}

    record Item(long ts, String item, long cost, long got, String rarity) {}

    private static final List<Item> QUEUE = new ArrayList<>();
    private static final int MAX_QUEUE = 500;
    private static final int BATCH = 50;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private static Thread worker;
    private static volatile long pausedUntil = 0;

    /** Called when a flip is completed. Does nothing unless reporting is on and the server settings are usable. */
    static void queue(long ts, String item, long cost, long got, String rarity) {
        FlipConfig c = FlipConfig.INSTANCE;
        if (c == null || !c.reportStats || !ServerLink.wanted(c)) return;
        synchronized (QUEUE) {
            if (QUEUE.size() >= MAX_QUEUE) QUEUE.remove(0);
            QUEUE.add(new Item(ts, item, cost, got, rarity));
        }
        start();
    }

    private static synchronized void start() {
        if (worker != null && worker.isAlive()) return;
        worker = new Thread(StatsReport::loop, "valtrin-stats-report");
        worker.setDaemon(true);
        worker.start();
    }

    private static void loop() {
        try {
            while (true) {
                Thread.sleep(20_000);
                FlipConfig c = FlipConfig.INSTANCE;
                if (c == null || !c.reportStats || !ServerLink.wanted(c)) {
                    synchronized (QUEUE) { QUEUE.clear(); }
                    return; // turned off: forget what was waiting
                }
                if (System.currentTimeMillis() < pausedUntil) continue;
                List<Item> batch;
                synchronized (QUEUE) {
                    if (QUEUE.isEmpty()) return;
                    batch = new ArrayList<>(QUEUE.subList(0, Math.min(BATCH, QUEUE.size())));
                }
                int code = send(c.sharedUrl, c.sharedKey, batch);
                if (code == 200) {
                    synchronized (QUEUE) { QUEUE.subList(0, Math.min(batch.size(), QUEUE.size())).clear(); }
                } else if (code == 403 || code == 401) {
                    synchronized (QUEUE) { QUEUE.clear(); }
                    pausedUntil = System.currentTimeMillis() + 600_000L; // the key is not premium (or is wrong): try again much later
                    return;
                } else {
                    pausedUntil = System.currentTimeMillis() + 120_000L; // server busy or unreachable: keep them and wait
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // never break the game
        }
    }

    /** The request body: only the four fields per flip, plus the item's rarity when it is known. */
    static String body(List<Item> items) {
        JsonArray a = new JsonArray();
        for (Item i : items) {
            JsonObject o = new JsonObject();
            o.addProperty("ts", i.ts());
            o.addProperty("item", i.item());
            o.addProperty("cost", i.cost());
            o.addProperty("got", i.got());
            if (i.rarity() != null) o.addProperty("rarity", i.rarity());
            a.add(o);
        }
        return a.toString();
    }

    private static int send(String base, String key, List<Item> items) {
        try {
            String root = Validate.serverUrl(base);
            if (root == null) return -1;
            if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
            HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/v1/stats")).timeout(Duration.ofSeconds(10))
                    .header("X-Key", key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body(items))).build();
            return HTTP.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (Exception e) {
            return -1;
        }
    }
}
