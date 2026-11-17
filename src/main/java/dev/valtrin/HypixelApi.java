// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;

/** Thin client for the public (no key required) SkyBlock auction endpoints. */
final class HypixelApi {
    private static final String ROOT = "https://api.hypixel.net/";
    private static final String BASE = ROOT + "v2/skyblock/";
    private static final String UA = "Valtrin/22 (client-side Fabric mod, alerts only)";

    /** Everything that is not the live page-0 poll (bazaar, ended auctions, item list, pages 1..N). */
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /**
     * Used ONLY for polling page 0. HTTP/1.1 on purpose: HTTP/2 puts every request on one connection, and when that one
     * connection hangs (seen overnight: 4 outages while a browser on the same PC was fine) everything waits. Replaced by
     * a fresh client with {@link #resetPoll()} after a failure.
     */
    private volatile HttpClient pollHttp = newPollClient();

    private static HttpClient newPollClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    /** Throws away the poll connections and starts with a clean client (next request opens new connections). */
    void resetPoll() {
        pollHttp = newPollClient();
    }

    /** HTTP status errors carry the status (and Retry-After) so the caller can react to 429 / 404. */
    static final class HttpStatusException extends IOException {
        final int status;
        final long retryAfterMs;
        HttpStatusException(int status, long retryAfterMs) {
            super("HTTP " + status + (status == 429 ? " (rate limited)" : ""));
            this.status = status;
            this.retryAfterMs = retryAfterMs;
        }
    }

    /**
     * The same page 0 can be asked for under different URLs. Cloudflare caches each URL separately, so when one URL is
     * stuck on an old snapshot (for minutes at busy times) another can already be fresh. Other pages only have DEFAULT.
     */
    enum Key {
        DEFAULT(BASE + "auctions?page=0"),
        UPPER(BASE + "auctions?PAGE=0"),   // upper-case parameter name: still page 0, separate cache entry
        V1(ROOT + "skyblock/auctions?page=0"); // older path without /v2: separate cache entry

        final String url;
        Key(String url) { this.url = url; }
    }

    /** One page-0 answer. {@code ms} is how long the request took. */
    record Page0(JsonObject json, long lastUpdated, int totalPages, Key key, long receivedAtMs, long ms,
                 int age, String cache) {}

    // ---- light polling: ask "has page 0 changed since the copy I have?" (If-None-Match / If-Modified-Since).
    // An unchanged page comes back as a tiny 304 instead of the whole 1.3 MB. If the server gives no validators (or ignores
    // them and answers 200 anyway), nothing changes: every answer is simply a normal full answer.
    private final String[] etags = new String[Key.values().length];
    private final String[] lastMods = new String[Key.values().length];

    boolean hasValidator(Key k) {
        return etags[k.ordinal()] != null || lastMods[k.ordinal()] != null;
    }


    /** @param light send validators so an unchanged page is answered with a tiny 304 (the future then completes with null) */
    CompletableFuture<Page0> page0(Key k, int timeoutMs, boolean light) {
        long t0 = System.currentTimeMillis();
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(k.url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("User-Agent", UA)
                .header("Accept-Encoding", "gzip");
        if (light) {
            String et = etags[k.ordinal()], lm = lastMods[k.ordinal()];
            if (et != null) rb.header("If-None-Match", et);
            else if (lm != null) rb.header("If-Modified-Since", lm);
        }
        HttpRequest req = rb.GET().build();
        return pollHttp.sendAsync(req, HttpResponse.BodyHandlers.ofInputStream()).thenApply(res -> {
            try {
                if (res.statusCode() == 304) {
                    noteClock(res);
                    res.body().close();
                    return null;
                }
                int age = -1;
                try { age = Integer.parseInt(res.headers().firstValue("Age").orElse("-1").trim()); } catch (NumberFormatException ignored) {}
                String cache = res.headers().firstValue("cf-cache-status").orElse("-");
                JsonObject o = read(res);
                res.headers().firstValue("ETag").ifPresent(v -> etags[k.ordinal()] = v);
                res.headers().firstValue("Last-Modified").ifPresent(v -> lastMods[k.ordinal()] = v);
                long now = System.currentTimeMillis();
                return new Page0(o, o.get("lastUpdated").getAsLong(), o.get("totalPages").getAsInt(), k, now, now - t0, age, cache);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private HttpRequest request(String path, int timeoutSec) {
        return HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("User-Agent", UA)
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
    }

    JsonObject get(String path) throws IOException, InterruptedException {
        HttpResponse<InputStream> res = http.send(request(path, 25), HttpResponse.BodyHandlers.ofInputStream());
        return read(res);
    }

    /** For endpoints directly under /v2/, e.g. "skyblock/bazaar" or "resources/skyblock/items". */
    JsonObject getV2(String path) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(ROOT + "v2/" + path))
                .timeout(Duration.ofSeconds(40))
                .header("User-Agent", UA)
                .header("Accept-Encoding", "gzip")
                .GET()
                .build();
        return read(http.send(req, HttpResponse.BodyHandlers.ofInputStream()));
    }

    CompletableFuture<JsonObject> getAsync(String path) {
        return http.sendAsync(request(path, 25), HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(res -> {
                    try {
                        return read(res);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
    }

    /**
     * One auction page (1..N) with a short timeout. If it fails, the same page is asked for once more under the older
     * path without /v2 (a different cache entry and often a different edge connection).
     */
    CompletableFuture<JsonObject> auctionPage(int page, int timeoutSec, long hedgeMs) {
        CompletableFuture<JsonObject> out = new CompletableFuture<>();
        java.util.concurrent.atomic.AtomicBoolean altStarted = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();
        HttpRequest alt = HttpRequest.newBuilder(URI.create(ROOT + "skyblock/auctions?page=" + page))
                .timeout(Duration.ofSeconds(timeoutSec))
                .header("User-Agent", UA)
                .header("Accept-Encoding", "gzip")
                .GET().build();
        Runnable startAlt = () -> {
            if (!altStarted.compareAndSet(false, true)) return;
            http.sendAsync(alt, HttpResponse.BodyHandlers.ofInputStream()).thenApply(HypixelApi::readUnchecked)
                    .whenComplete((o, err) -> {
                        if (err == null) out.complete(o);
                        else if (failed.incrementAndGet() == 2) out.completeExceptionally(err);
                    });
        };
        http.sendAsync(request("auctions?page=" + page, timeoutSec), HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(HypixelApi::readUnchecked)
                .whenComplete((o, err) -> {
                    if (err == null) { out.complete(o); return; }
                    startAlt.run();
                    if (failed.incrementAndGet() == 2) out.completeExceptionally(err);
                });
        // A page that is still missing after hedgeMs is asked for a second time under the older path (a different cache
        // entry, often a different connection); whichever answer arrives first is used. This cuts the slow tail.
        CompletableFuture.delayedExecutor(hedgeMs, java.util.concurrent.TimeUnit.MILLISECONDS).execute(() -> {
            if (!out.isDone()) startAlt.run();
        });
        return out;
    }

    private static JsonObject readUnchecked(HttpResponse<InputStream> res) {
        try {
            return read(res);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Hypixel's clock minus this computer's clock (ms), measured from the HTTP Date header. Fixes a wrong PC clock. */
    static volatile long clockOffsetMs = 0;
    static volatile boolean clockKnown = false;

    /**
     * The HTTP Date header has whole seconds only and is never newer than the real time, and the answer needs time to
     * arrive, so a single reading is always a little too low (and sometimes a lot, for a cached copy). The best estimate
     * is the HIGHEST reading of the last few hundred answers: no reading can be too high.
     */
    private static final long[] CLOCK_RING = new long[300];
    private static int clockCount = 0;

    private static void noteClock(HttpResponse<?> res) {
        try {
            String d = res.headers().firstValue("Date").orElse(null);
            if (d == null) return;
            long server = java.time.ZonedDateTime.parse(d, java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
            long off = server - System.currentTimeMillis();
            synchronized (CLOCK_RING) {
                CLOCK_RING[clockCount++ % CLOCK_RING.length] = off;
                int n = Math.min(clockCount, CLOCK_RING.length);
                long best = Long.MIN_VALUE;
                for (int i = 0; i < n; i++) best = Math.max(best, CLOCK_RING[i]);
                clockOffsetMs = best;
            }
            clockKnown = true;
        } catch (Exception ignored) {}
    }

    /** No Hypixel answer is anywhere near this big (a full auction page is a few MB); more means something is wrong. */
    private static final long MAX_ANSWER_BYTES = 128L * 1024 * 1024;

    private static JsonObject read(HttpResponse<InputStream> res) throws IOException {
        noteClock(res);
        if (res.statusCode() != 200) {
            long retry = 0;
            try {
                retry = Long.parseLong(res.headers().firstValue("Retry-After").orElse("0").trim()) * 1000L;
            } catch (NumberFormatException ignored) {}
            res.body().close();
            throw new HttpStatusException(res.statusCode(), retry);
        }
        String enc = res.headers().firstValue("Content-Encoding").orElse("");
        try (InputStream in = new LimitedInputStream(enc.equalsIgnoreCase("gzip")
                ? new GZIPInputStream(res.body()) : res.body(), MAX_ANSWER_BYTES);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject obj;
            try {
                obj = JsonParser.parseReader(reader).getAsJsonObject();
            } catch (RuntimeException e) {
                throw new IOException("unreadable answer: " + e.getMessage(), e); // e.g. an HTML error page from the edge
            }
            if (obj.has("success") && !obj.get("success").getAsBoolean()) {
                throw new IOException("API returned success=false");
            }
            return obj;
        }
    }

    /** item_bytes is a plain base64 string in v2, but older shapes wrapped it in {"data": "..."}. */
    static String itemBytes(JsonElement e) {
        if (e == null || e.isJsonNull()) return null;
        if (e.isJsonPrimitive()) return e.getAsString();
        if (e.isJsonObject() && e.getAsJsonObject().has("data")) {
            return e.getAsJsonObject().get("data").getAsString();
        }
        return null;
    }
}
