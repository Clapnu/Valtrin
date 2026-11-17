// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.List;
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
    final AtomicLong flips = new AtomicLong(), notices = new AtomicLong(), sent = new AtomicLong(), dropped = new AtomicLong();

    Hub(ServerSettings cfg) {
        this.cfg = cfg;
    }

    long delayFor(KeyStore.Tier tier) {
        return tier == KeyStore.Tier.PREMIUM && cfg.premiumEnabled ? 0 : cfg.freeDelayMs;
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
