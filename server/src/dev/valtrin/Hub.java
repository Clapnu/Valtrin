// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Sends the engine's alerts to every connected mod, each after its own delay (free keys wait, premium keys do not,
 * once premium is enabled). The engine thread never waits on a client: each session has a small bounded queue, and a
 * session whose queue is full (a client that stopped reading) is dropped.
 */
final class Hub implements AlertSink {
    static final class Session {
        final String keyHash;
        final KeyStore.Tier tier;
        final String ip;
        final BlockingQueue<String> out = new ArrayBlockingQueue<>(64);
        volatile boolean closed;

        Session(String keyHash, KeyStore.Tier tier, String ip) {
            this.keyHash = keyHash;
            this.tier = tier;
            this.ip = ip;
        }

        void close() {
            closed = true;
            out.offer("");
        }
    }

    private final ServerSettings cfg;
    private final ConcurrentHashMap<String, Session> byKey = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "valtrin-delay");
        t.setDaemon(true);
        return t;
    });
    /** A recent flip as the website may show it: no seller name or seller id. */
    private record Recent(long at, String name, String tier, String uuid, long buy, long sell, long profit, double margin, double volume, String conf) {}

    private static final int MAX_RECENT = 4000;
    private static final long KEEP_MS = 24L * 3_600_000L;
    private static final long FREE_HISTORY_MS = 30L * 60_000L;
    private final ConcurrentLinkedDeque<Recent> recent = new ConcurrentLinkedDeque<>();
    final AtomicLong flips = new AtomicLong(), notices = new AtomicLong(), sent = new AtomicLong(), dropped = new AtomicLong();

    Hub(ServerSettings cfg) {
        this.cfg = cfg;
    }

    long delayFor(KeyStore.Tier tier) {
        return tier == KeyStore.Tier.PREMIUM && cfg.premiumEnabled ? 0 : cfg.freeDelayMs;
    }

    /** How far back a key may look: 30 minutes for free keys, 24 hours for premium once premium is enabled. */
    long historyMsFor(KeyStore.Tier tier) {
        return tier == KeyStore.Tier.PREMIUM && cfg.premiumEnabled ? KEEP_MS : FREE_HISTORY_MS;
    }

    /** How many flips were found in the last {@code ms} milliseconds (a count only, no names). */
    int countSince(long ms) {
        long from = System.currentTimeMillis() - ms;
        int n = 0;
        for (Recent r : recent) if (r.at() >= from) n++;
        return n;
    }

    private void remember(FlipAlert f, long at) {
        recent.addLast(new Recent(at, f.name(), f.tier(), f.uuid(), f.buy(), f.sell(), f.profit(), f.margin(), f.volume(), f.conf()));
        long cutoff = System.currentTimeMillis() - KEEP_MS;
        Recent first;
        while ((first = recent.peekFirst()) != null && (recent.size() > MAX_RECENT || first.at() < cutoff)) recent.pollFirst();
    }

    /**
     * The flips a key may see right now, newest first, as JSON for the website: each flip is held back by the key's own
     * delay (so the website never gives a free key anything sooner than the mod gets it) and only goes back as far as
     * the key's history window. Seller names are never included.
     */
    String recentJson(KeyStore.Tier tier, long now, int limit) {
        long delay = delayFor(tier), window = historyMsFor(tier);
        JsonArray arr = new JsonArray();
        Iterator<Recent> it = recent.descendingIterator();
        while (it.hasNext() && arr.size() < limit) {
            Recent r = it.next();
            if (r.at() + delay > now) continue;
            if (r.at() < now - window) continue; // not break: flips are not guaranteed to be stored in time order
            JsonObject o = new JsonObject();
            o.addProperty("at", r.at());
            o.addProperty("name", r.name());
            o.addProperty("tier", r.tier());
            o.addProperty("uuid", r.uuid());
            o.addProperty("buy", r.buy());
            o.addProperty("sell", r.sell());
            o.addProperty("profit", r.profit());
            o.addProperty("margin", r.margin());
            o.addProperty("volume", r.volume());
            if (r.conf() != null) o.addProperty("conf", r.conf());
            arr.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("now", now);
        out.addProperty("delayMs", delay);
        out.addProperty("historyMs", window);
        out.add("flips", arr);
        return out.toString();
    }

    int sessionCount() {
        return byKey.size();
    }

    /** Registers a session; a second connection with the same key replaces the first. Null if the server is full. */
    Session open(String keyHash, KeyStore.Tier tier, String ip) {
        Session s = new Session(keyHash, tier, ip);
        synchronized (this) {
            if (!byKey.containsKey(keyHash) && byKey.size() >= cfg.maxSessions) return null;
            Session old = byKey.put(keyHash, s);
            if (old != null) old.close();
        }
        return s;
    }

    void close(Session s) {
        s.closed = true;
        byKey.remove(s.keyHash, s);
    }

    private void deliver(String event, String json, boolean isFlip) {
        String frame = "event: " + event + "\ndata: " + json + "\n\n";
        for (Session s : List.copyOf(byKey.values())) {
            long delay = delayFor(s.tier);
            Runnable r = () -> {
                if (s.closed) return;
                if (s.out.offer(frame)) sent.incrementAndGet();
                else {
                    dropped.incrementAndGet();
                    s.close();
                    byKey.remove(s.keyHash, s);
                }
            };
            if (delay <= 0) r.run();
            else timer.schedule(r, delay, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void notice(boolean good, String lead, String rest, long at) {
        notices.incrementAndGet();
        Log.LOGGER.info("NOTICE {} {} {}", good ? "good" : "warn", lead, rest);
        deliver("notice", Wire.encodeNotice(good, lead, rest), false);
    }

    @Override
    public void flip(FlipAlert f, long at, boolean real) {
        if (!real) return;
        flips.incrementAndGet();
        remember(f, at);
        deliver("flip", Wire.encodeFlip(f, at), true);
    }

    @Override
    public int pending() {
        return 0;
    }

    @Override
    public boolean onHypixel() {
        return true;
    }
}
