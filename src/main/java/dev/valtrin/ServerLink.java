// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Optional: receive flip alerts from a Valtrin server instead of searching on this computer. The connection is a
 * plain HTTPS GET that stays open (server-sent events), signed in with the access key in a request header.
 *
 * Safety: everything that arrives is untrusted. {@link Wire} checks every field, flips are filtered with this
 * player's own settings, and the click action is built here from a validated auction id only. The server cannot make
 * the mod run commands, buy anything or open anything by itself. A redirect is never followed, so the key cannot be
 * sent anywhere but the address the player entered.
 */
final class ServerLink {
    enum State { OFF, CONNECTING, CONNECTED, REFUSED, WAITING }

    private final AlertSink sink;
    private volatile State state = State.OFF;
    private volatile String detail = "";
    private volatile long delayMs = -1;
    private volatile boolean running;
    private volatile Thread thread;
    /** Settings that were in use for the open connection; a change makes the loop reconnect. */
    private volatile String connectedWith = "";

    // flood guard: at most 30 flips a second get through, repeats of recent ids are dropped
    private long windowStart;
    private int windowCount;
    private final ArrayDeque<String> recent = new ArrayDeque<>();

    ServerLink(AlertSink sink) {
        this.sink = sink;
    }

    private final java.util.concurrent.atomic.AtomicLong flipsShown = new java.util.concurrent.atomic.AtomicLong();
    private volatile long lastFlipAt = 0;

    /** Flips this connection has shown (after the player's own filters). */
    long flipsShown() { return flipsShown.get(); }
    long lastFlipAt() { return lastFlipAt; }

    State state() { return state; }
    String detail() { return detail; }
    long delayMs() { return delayMs; }
    boolean connected() { return state == State.CONNECTED; }

    /** True if this player has asked for server alerts and entered a usable address and key. */
    static boolean wanted(FlipConfig c) {
        return c.useServerAlerts && Validate.serverUrl(c.sharedUrl) != null && KeyShape.ok(c.sharedKey);
    }

    /** The key's outer shape, checked before it is ever sent anywhere. */
    static final class KeyShape {
        static boolean ok(String k) {
            return k != null && k.length() == 46 && k.matches("vk_[A-Za-z0-9_-]{43}");
        }
    }

    synchronized void start() {
        if (running) return;
        running = true;
        Thread t = new Thread(this::loop, "valtrin-server-link");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    synchronized void stop() {
        running = false;
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    /** Makes the loop notice changed settings now (connect, reconnect or disconnect). */
    void settingsChanged() {
        Thread t = thread;
        if (t != null) t.interrupt();
    }

    // ------------------------------------------------------------------ connection loop

    private void loop() {
        long backoff = 2_000;
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        while (running) {
            FlipConfig c = FlipConfig.INSTANCE;
            try {
                if (!wanted(c)) {
                    state = State.OFF;
                    detail = c.useServerAlerts ? "needs an https address and a key (settings, Advanced)" : "";
                    delayMs = -1;
                    sleep(3_000);
                    continue;
                }
                String url = c.sharedUrl.trim();
                String key = c.sharedKey;
                connectedWith = url + "\n" + key;
                state = State.CONNECTING;
                detail = "";
                long waitNext = connectOnce(http, url, key);
                backoff = waitNext > 0 ? waitNext : backoff;
                state = state == State.REFUSED ? State.REFUSED : State.WAITING;
            } catch (InterruptedException e) {
                if (!running) break;
                backoff = 2_000; // settings changed: try again soon
                continue;
            } catch (Exception e) {
                state = State.WAITING;
                detail = "connection lost";
                Log.LOGGER.warn("Valtrin server connection: {}", e.toString());
            }
            if (state == State.REFUSED) {
                try { sleep(60_000); } catch (InterruptedException e) { if (!running) break; }
                backoff = 2_000;
            } else {
                try {
                    sleep(backoff + ThreadLocalRandom.current().nextLong(backoff / 2 + 1));
                } catch (InterruptedException e) {
                    if (!running) break;
                }
                backoff = Math.min(60_000, backoff * 2);
            }
        }
        state = State.OFF;
    }

    private static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** One connection. Returns the suggested wait before the next try (0 = default backoff). */
    private long connectOnce(HttpClient http, String base, String key) throws Exception {
        String root = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        HttpRequest req = HttpRequest.newBuilder(URI.create(root + "/v1/stream"))
                .header("X-Key", key).header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        int code = resp.statusCode();
        if (code == 401) {
            state = State.REFUSED;
            detail = "the server refused the key";
            resp.body().close();
            return 0;
        }
        if (code == 429 || code == 503) {
            state = State.WAITING;
            detail = code == 429 ? "server asks to slow down" : "server is full or starting";
            resp.body().close();
            return 30_000;
        }
        if (code != 200) {
            state = State.WAITING;
            detail = "server answered " + code;
            resp.body().close();
            return 0;
        }
        try (InputStream body = resp.body()) {
            SseReader sse = new SseReader(body);
            Thread self = Thread.currentThread();
            // watchdog: the server sends something at least every 15 s; silence for 45 s means a dead connection
            Thread dog = Thread.ofVirtual().unstarted(() -> {
                try {
                    while (running) {
                        Thread.sleep(5_000);
                        boolean changed = !connectedWith.equals(FlipConfig.INSTANCE.sharedUrl.trim() + "\n" + FlipConfig.INSTANCE.sharedKey)
                                || !wanted(FlipConfig.INSTANCE);
                        if (changed || System.currentTimeMillis() - sse.lastActivityAt > 45_000) {
                            try { body.close(); } catch (IOException ignored) {}
                            self.interrupt();
                            return;
                        }
                    }
                } catch (InterruptedException ignored) { /* stream ended */ }
            });
            dog.start();
            try {
                SseReader.Event ev;
                while (running && (ev = sse.next()) != null) handle(ev);
            } finally {
                dog.interrupt();
            }
        }
        state = State.WAITING;
        detail = "connection closed";
        return 0;
    }

    // ------------------------------------------------------------------ messages

    private void handle(SseReader.Event ev) {
        switch (ev.type()) {
            case "hello" -> {
                Wire.Hello h = Wire.decodeHello(ev.data());
                if (h == null) return;
                delayMs = h.delayMs();
                state = State.CONNECTED;
                detail = h.tier().toLowerCase(Locale.ROOT) + (h.delayMs() > 0 ? ", " + (h.delayMs() / 1000) + " s delay" : ", no delay");
                Log.LOGGER.info("Connected to Valtrin server ({})", detail);
            }
            case "flip" -> {
                Wire.Flip f = Wire.decodeFlip(ev.data());
                if (f != null) onFlip(f.alert());
            }
            case "notice" -> {
                Wire.Notice n = Wire.decodeNotice(ev.data());
                if (n != null) sink.notice(n.good(), n.lead(), n.rest(), System.currentTimeMillis());
            }
            default -> { /* unknown events are ignored */ }
        }
    }

    private void onFlip(FlipAlert f) {
        long now = System.currentTimeMillis();
        if (!accept(f, FlipConfig.INSTANCE)) return;
        synchronized (this) {
            if (now - windowStart > 1000) { windowStart = now; windowCount = 0; }
            if (++windowCount > 30) return;
            if (recent.contains(f.uuid())) return;
            recent.addLast(f.uuid());
            while (recent.size() > 500) recent.removeFirst();
        }
        flipsShown.incrementAndGet();
        lastFlipAt = now;
        sink.flip(f, now, true);
        FlipQueue.add(new FlipQueue.Item(f.uuid(), f.name(), f.buy(), f.profit(), now));
        AlertHistory.add(f.uuid(), f.name(), f.tier(), f.buy(), f.profit(), now);
    }

    /** The player's own filters, applied to what the server sent. Only ever stricter than the server's. */
    static boolean accept(FlipAlert f, FlipConfig c) {
        if (!c.enabled) return false;
        if (f.profit() < c.minProfit) return false;
        if (f.margin() < c.minMarginPercent) return false;
        if (f.volume() >= 0 && f.volume() < c.minVolume) return false;
        if (OwnModel.confRank(f.conf()) < OwnModel.confRank(String.valueOf(c.minConfidence).toUpperCase(Locale.ROOT))) return false;
        if (c.maxPrice > 0 && f.buy() > c.maxPrice) return false;
        String lower = f.name().toLowerCase(Locale.ROOT);
        for (String b : c.blacklist) if (b != null && !b.isBlank() && lower.contains(b.toLowerCase(Locale.ROOT))) return false;
        return true;
    }
}
