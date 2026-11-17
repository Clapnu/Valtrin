// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Asks Modrinth once per game start whether a newer Valtrin exists (only the public version list is read, nothing is sent
 * about you). It stays off until {@link #PROJECT} names the Modrinth project.
 */
final class VersionCheck {
    private VersionCheck() {}

    /** The Modrinth project slug or id. Empty until Valtrin is published there; the check is skipped while empty. */
    static final String PROJECT = "";

    /** null = unknown / not checked, otherwise the newest version number found. */
    static volatile String latest = null;
    private static volatile boolean started = false;

    static boolean available() { return !PROJECT.isEmpty(); }

    static void start() {
        if (started || !available()) return;
        started = true;
        Thread t = new Thread(() -> {
            try {
                HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
                HttpRequest rq = HttpRequest.newBuilder(URI.create("https://api.modrinth.com/v2/project/" + PROJECT + "/version"))
                        .timeout(Duration.ofSeconds(10)).header("User-Agent", "Valtrin/" + FlipConfig.VERSION).build();
                HttpResponse<String> rs = c.send(rq, HttpResponse.BodyHandlers.ofString());
                if (rs.statusCode() != 200) return;
                JsonArray arr = JsonParser.parseString(rs.body()).getAsJsonArray();
                String best = null;
                for (JsonElement e : arr) {
                    String v = e.getAsJsonObject().get("version_number").getAsString();
                    if (best == null || compare(v, best) > 0) best = v;
                }
                latest = best;
            } catch (Throwable ignored) {
                // no network or a changed answer: just no update line
            }
        }, "valtrin-version-check");
        t.setDaemon(true);
        t.start();
    }

    /** Compares dotted version numbers (23.6.0 vs 23.10.1). */
    static int compare(String a, String b) {
        String[] x = a.replaceAll("[^0-9.]", "").split("\\."), y = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? parse(x[i]) : 0, q = i < y.length ? parse(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 0; } }
}
