// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.*;
import java.util.concurrent.*;

/**
 * Polls the public Hypixel APIs, values every BIN listing (including enchants, gems, slots, stars,
 * scrolls and pet level), finds NEW listings priced well below their value, applies the user's
 * filters and queues a clickable chat alert.
 */
final class FlipEngine {
    private record Entry(Auction a, ItemData d) {}
    private record Candidate(Entry e, Market.Estimate est, int vol) {}

    private static final int BATCH = 50; // all auction pages at once (one connection, multiplexed)
    /** The chat queue is drained every tick; this only guards against a runaway flood. */
    private static final int MAX_QUEUE = 200;
    /** Flips that did not fit under the alert limit wait this many at most (best profit first). */
    private static final int MAX_OVERFLOW = 2000;

    private final HypixelApi api = new HypixelApi();
    private final VolumeTracker volume;
    private final AlertSink sink;
    /** Times (ms) of alerts queued in the last 10 seconds, for the alert limit. Worker thread only. */
    private final java.util.ArrayDeque<Long> recentSent = new java.util.ArrayDeque<>();
    private final ScheduledExecutorService pool = Executors.newScheduledThreadPool(3, r -> {
        Thread t = new Thread(r, "Valtrin-worker");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, ItemData> cache = new ConcurrentHashMap<>();

    // ---- coverage: nothing that passes the filters may be skipped
    /** uuid -> our clock when we first saw the listing. A listing is judged by how long WE have known it, not by its start time. */
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();
    /** uuid -> when an alert was sent (30 min), so a listing is never alerted twice. */
    private final Map<String, Long> alerted = new ConcurrentHashMap<>();
    /** Listings rejected only for a temporary reason (sales loading, volume still unknown): judged again every snapshot. */
    private final Map<String, Entry> pending = new ConcurrentHashMap<>();
    /** Flips that passed but did not fit under the alert limit yet; sent as room appears (best profit first). */
    private final List<Candidate> overflow = new ArrayList<>();
    private final java.util.concurrent.atomic.AtomicBoolean drainScheduled = new java.util.concurrent.atomic.AtomicBoolean(false);

    // ---- Hypixel publishing mode (per-minute at busy times, ~5-minute batches at night)
    private final java.util.ArrayDeque<Long> page0Stamps = new java.util.ArrayDeque<>();
    private volatile boolean batchMode = false;
    boolean batchMode() { return batchMode; }

    private volatile boolean running = false;
    private volatile Set<String> seen = Set.of();
    private long lastUpdated = 0;
    private long lastEndedUpdated = 0;
    private Set<String> lastEndedIds = Set.of();
    private long lastSave = System.currentTimeMillis();
    private long lastHistorySave = System.currentTimeMillis();
    private int failures = 0;

    // Stats shown by /flip status
    volatile long lastRefreshAt = 0;
    volatile int tracked = 0;
    volatile int alertsSent = 0;
    volatile String lastError = "";
    /** Timing of the latest snapshot (ms): download, judging, and total delay after Hypixel's own snapshot time. */
    volatile long lastFetchMs = 0, lastProcessMs = 0, lastLagMs = -1;
    /** How long the typical and the slowest page of the last full download took (ms), -1 = unknown. */
    volatile long lastPageMedianMs = -1, lastPageMaxMs = -1;
    /** One processed snapshot, for /flip snapshots. */
    record Snap(long lu, long gapMs, long seenAgeMs, long newestListingAgeMs, int newListings, int total) {}
    private final java.util.ArrayDeque<Snap> snaps = new java.util.ArrayDeque<>();
    synchronized java.util.List<Snap> snapshotHistory() { return new java.util.ArrayList<>(snaps); }


    private static String pct(List<Long> sorted, double q) {
        if (sorted.isEmpty()) return "-";
        long v = sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * (sorted.size() - 1) + 0.5)));
        return String.format(java.util.Locale.ROOT, "%.1f", v / 1000.0) + "s";
    }

    /** Median seconds after Hypixel's stamp that we see a new snapshot, like "7.0s" ("-" before the first one). */
    synchronized String detectMedian() {
        List<Long> d = new ArrayList<>(detectLagMs);
        java.util.Collections.sort(d);
        return pct(d, 0.5);
    }

    /** Median ms from first seeing a listing to its alert, or -1 when no alert has been sent yet. */
    synchronized long alertMedianMs() {
        List<Long> a = new ArrayList<>(alertLagMs);
        if (a.isEmpty()) return -1;
        java.util.Collections.sort(a);
        return a.get(a.size() / 2);
    }

    /** True while the "lost contact" notice is active. */
    boolean lostContact() { return lostNotified; }

    /** Number of recovered outages in the last hour. */
    int outagesLastHour() {
        long now = System.currentTimeMillis();
        int n = 0;
        synchronized (outages) {
            for (long[] o : outages) if (now - o[0] < 3_600_000L) n++;
        }
        return n;
    }

    /** Lines for /flip status: how fast we see Hypixel's snapshots and which URL wins. */
    synchronized List<String> speedLines() {
        List<String> out = new ArrayList<>();
        List<Long> d = new ArrayList<>(detectLagMs);
        java.util.Collections.sort(d);
        out.add("seen after Hypixel's stamp: median " + pct(d, 0.5) + ", slowest 5% " + pct(d, 0.95) + " (last " + d.size() + " snapshots)");
        List<Long> a = new ArrayList<>(alertLagMs);
        java.util.Collections.sort(a);
        out.add("first seen -> alert: median " + (a.isEmpty() ? "-" : (a.get(a.size() / 2)) + " ms") + ", slowest 5% "
                + (a.isEmpty() ? "-" : a.get(Math.min(a.size() - 1, (int) (a.size() * 0.95))) + " ms") + " (last " + a.size() + " alerts)");
        // which URL showed each snapshot first, and how far the normal URL was behind the winner
        StringBuilder w = new StringBuilder();
        for (HypixelApi.Key k : KEYS) w.append(k.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(keyWins[k.ordinal()]).append("   ");
        out.add("first with a new snapshot: " + w.toString().trim());
        int behind = 0, total = 0;
        long worst = 0;
        for (long[] at : stampSeen.values()) {
            long first = Long.MAX_VALUE;
            for (long t : at) if (t > 0) first = Math.min(first, t);
            if (first == Long.MAX_VALUE) continue;
            total++;
            long def = at[0];
            long late = def == 0 ? -1 : def - first;
            if (late < 0 || late > 3000) behind++;
            if (late > worst) worst = late;
        }
        out.add("normal URL 3+ s behind (or missing): " + behind + " of " + total + " recent snapshots"
                + (worst > 0 ? " (worst " + (worst / 1000) + " s)" : ""));
        long now = System.currentTimeMillis();
        int n = 0;
        long secs = 0;
        synchronized (outages) {
            for (long[] o : outages) if (now - o[0] < 3_600_000L) { n++; secs += o[1] / 1000; }
        }
        out.add("connection: " + (lostNotified ? "NO CONTACT right now" : "ok") + "; outages in the last hour: " + n
                + (n > 0 ? " (" + secs + " s)" : ""));
        List<Long> st = new ArrayList<>(stampTimes);
        int gaps = 0, longGaps = 0;
        for (int i = 1; i < st.size(); i++) { gaps++; if (st.get(i) - st.get(i - 1) >= 240_000L) longGaps++; }
        out.add("Hypixel published in 5-minute batches: " + longGaps + " of " + gaps + " snapshots in the last hour");
        return out;
    }

    /** Latest poll: when it happened (local clock) and how old Hypixel's newest snapshot was then (corrected for clock offset). */
    volatile long lastPollAt = 0, apiSnapshotAgeMs = -1;

    FlipEngine(VolumeTracker volume, AlertSink sink) {
        this.volume = volume;
        this.sink = sink;
    }

    void start() {
        running = true;
        Pricing.history.load();
        // Sales data folder. Older builds recorded sales WITHOUT some modifiers (Etherwarp, kill tiers, dungeon item quality...),
        // so folders from earlier versions are not reused: only a folder marked with format.txt is trusted.
        java.nio.file.Path home = java.nio.file.Path.of(System.getProperty("user.home"));
        java.nio.file.Path salesDir = home.resolve("ValtrinData").resolve("sales");
        SaleLog.markFormat(salesDir);
        OwnModel.init(salesDir, null);
        SellerNames.init(home.resolve("ValtrinData").resolve("seller-names.tsv"));
        pageLogFile = home.resolve("ValtrinData").resolve("poll-log.tsv");
        RejectLog.init(home.resolve("ValtrinData").resolve("rejects.tsv"));
        pool.schedule(this::pricingTick, 1, TimeUnit.SECONDS);
        pool.schedule(this::auctionTick, 3, TimeUnit.SECONDS);
        pool.schedule(this::endedTick, 5, TimeUnit.SECONDS);
    }

    void stop() {
        running = false;
        volume.save(System.currentTimeMillis());
        OwnModel.flush();
        Pricing.history.save();
        pool.shutdownNow();
        engineExec.shutdownNow();
    }

    boolean volumeWarmedUp() {
        return volume.warmedUp(System.currentTimeMillis(), FlipConfig.INSTANCE.volumeWarmupMinutes);
    }

    private boolean shouldPoll() {
        FlipConfig cfg = FlipConfig.INSTANCE;
        return cfg.enabled && (!cfg.hypixelOnly || sink.onHypixel());
    }

    // ------------------------------------------------------------------ bazaar + item costs

    private void pricingTick() {
        if (!running) return;
        long delay = 30_000;
        try {
            if (!shouldPoll()) {
                delay = 5_000;
            } else {
                long now = System.currentTimeMillis();
                if (Pricing.bazaarAt == 0 || now - Pricing.bazaarAt > 5 * 60_000L) {
                    Pricing.loadBazaar(api.getV2("skyblock/bazaar"));
                    if (now - lastHistorySave > 10 * 60_000L) {
                        Pricing.history.save();
                        lastHistorySave = now;
                    }
                }
                if (Pricing.itemsAt == 0 || now - Pricing.itemsAt > 6 * 3_600_000L) {
                    try {
                        Pricing.loadItems(api.getV2("resources/skyblock/items"));
                    } catch (Exception e) {
                        // Star/slot costs fall back to defaults; try again in 10 minutes.
                        Pricing.itemsAt = now - 6 * 3_600_000L + 10 * 60_000L;
                        Log.LOGGER.warn("Item costs failed to load: {}", e.toString());
                    }
                }
            }
        } catch (Exception e) {
            lastError = "pricing: " + e.getMessage();
            delay = 15_000;
            Log.LOGGER.warn("Bazaar refresh failed: {}", e.toString());
        }
        if (running) pool.schedule(this::pricingTick, delay, TimeUnit.MILLISECONDS);
    }

    // ------------------------------------------------------------------ auctions

    // ------------------------------------------------------------------ speed: stamp-aware polling
    //
    // Hypixel publishes a new snapshot about every 60 s; it becomes visible 6-8 s after its timestamp. So: sleep until just
    // before that, then ask for page 0 every 200 ms under three different URLs (see HypixelApi.Key; one URL can be stuck on
    // an old snapshot for minutes while another is fresh). The FIRST answer with a newer timestamp wins and is judged at once
    // (fastPath); the other ~38 pages are downloaded afterwards for the full picture (fullPass).

    private static final int WINDOW_START_MS = 4_500;   // after the expected next stamp
    private static final int WINDOW_LEN_MS = 12_000;    // fine polling (200 ms) lasts this long, then 1 s catch-up polls
    private static final int PAGE0_TIMEOUT_MS = 4_000;
    private static final HypixelApi.Key[] KEYS = HypixelApi.Key.values();

    /** Newest page-0 timestamp handed to the engine thread. */
    private final java.util.concurrent.atomic.AtomicLong stampClaimed = new java.util.concurrent.atomic.AtomicLong(0);
    private final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    /** One thread runs fastPath/fullPass in order, so the listing sets are never changed by two snapshots at once. */
    private final ExecutorService engineExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Valtrin-engine");
        t.setDaemon(true);
        return t;
    });
    private int keyRot = 0;
    private volatile long pauseUntil = 0;               // 429: no polling until then
    private final long[] keyDisabledUntil = new long[KEYS.length];
    private final int[] keyBadStatus = new int[KEYS.length];
    private volatile long lastGoodAt = 0;               // any valid page-0 answer (any key)
    private volatile long pollingSince = 0;
    private volatile long lastResetAt = 0;
    /** The "lost contact" notice only appears after this long without ANY valid answer from Hypixel (while requests are being sent). */
    private static final long LOST_CONTACT_MS = 70_000L;
    private volatile boolean lostNotified = false;
    private volatile long lastFireAt = 0, activeSince = 0, blindFrom = 0; // activeSince: start of the current run of requests

    // statistics for /flip status
    private final java.util.ArrayDeque<Long> detectLagMs = new java.util.ArrayDeque<>();   // after Hypixel's stamp
    private final java.util.ArrayDeque<Long> alertLagMs = new java.util.ArrayDeque<>();    // first seen -> alert queued
    private final int[] keyWins = new int[KEYS.length];
    private final java.util.concurrent.ConcurrentHashMap<Long, long[]> stampSeen = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.ArrayDeque<long[]> outages = new java.util.ArrayDeque<>();     // {endedAt, durationMs}
    private final java.util.ArrayDeque<Long> stampTimes = new java.util.ArrayDeque<>();    // page-0 stamps seen, last hour
    private volatile long currentDetectAt = 0;

    private void auctionTick() {
        if (!running) return;
        long delay = 1000;
        try {
            if (!shouldPoll()) {
                seen = Set.of();      // so re-enabling doesn't alert on the whole market
                lastUpdated = 0;
                stampClaimed.set(0);
                lastGoodAt = 0;
                pollingSince = 0;
                lostNotified = false;
                lastFireAt = 0;
                delay = 3000;
            } else if (!Pricing.ready()) {
                delay = 2000;         // wait for Bazaar prices, otherwise modifier values would be wrong
            } else {
                delay = pollStep();
            }
        } catch (Exception e) {
            lastError = String.valueOf(e.getMessage());
            delay = 500;
            Log.LOGGER.warn("Auction poll step failed: {}", e.toString());
        }
        if (running) pool.schedule(this::auctionTick, delay, TimeUnit.MILLISECONDS);
    }

    /** Starts the page-0 requests that are due now and returns how long to wait before the next step. */
    private long pollStep() {
        long now = System.currentTimeMillis();
        if (pollingSince == 0) pollingSince = now;
        checkLostContact(now);
        if (now < pauseUntil) return Math.min(1000, pauseUntil - now);

        long serverNow = now + HypixelApi.clockOffsetMs;
        long known = stampClaimed.get();
        // Time after the EXPECTED next stamp. When a whole minute passed without a newer stamp, the next expected one is
        // another minute later, and the fine 200 ms window is opened again at each of them (instead of 1 s polls only).
        long elapsed = known == 0 ? 0 : serverNow - known;
        long missed = known == 0 ? 0 : Math.max(1, elapsed / 60_000L);
        long since = known == 0 ? Long.MAX_VALUE : elapsed - missed * 60_000L;
        if (known != 0 && missed == 1 && since < WINDOW_START_MS) {
            return Math.max(20, Math.min(55_000, WINDOW_START_MS - since)); // nothing can be new yet
        }
        if (inFlight.get() >= 12) return 200; // answers are not coming back: do not pile up requests
        if (known != 0 && since >= WINDOW_START_MS && since < WINDOW_START_MS + WINDOW_LEN_MS) {
            fire(nextKey(now));      // window: one request per 200 ms, rotating the URL
            return 200;
        }
        // catch-up (or start-up): all URLs at once every second (every 2 s when it has gone on for minutes)
        for (HypixelApi.Key k : KEYS) if (now >= keyDisabledUntil[k.ordinal()]) fire(k);
        return elapsed > 160_000L && known != 0 ? 2000 : 1000;
    }

    private HypixelApi.Key nextKey(long now) {
        for (int i = 0; i < KEYS.length; i++) {
            HypixelApi.Key k = KEYS[(keyRot++ & 0x7fffffff) % KEYS.length];
            if (now >= keyDisabledUntil[k.ordinal()]) return k;
        }
        return KEYS[0];
    }

    private void fire(HypixelApi.Key k) {
        long t = System.currentTimeMillis();
        if (t - lastFireAt > 5000) activeSince = t; // a new run of requests (the wait between two snapshots is not an outage)
        lastFireAt = t;
        inFlight.incrementAndGet();
        boolean light = lightActive() && api.hasValidator(k);
        api.page0(k, PAGE0_TIMEOUT_MS, light).whenComplete((p, err) -> {
            inFlight.decrementAndGet();
            if (err != null) { onKeyFailure(k, err); return; }
            try {
                if (p == null) { onUnchanged(k); return; } // 304: nothing new under this URL
                onPage0(p);
            } catch (Exception e) {
                Log.LOGGER.warn("Page-0 answer could not be used: {}", e.toString());
            }
        });
    }

    private void onKeyFailure(HypixelApi.Key k, Throwable err) {
        Throwable t = err;
        while (t.getCause() != null && (t instanceof java.util.concurrent.CompletionException || t instanceof java.io.UncheckedIOException)) t = t.getCause();
        long now = System.currentTimeMillis();
        lastError = k.name() + ": " + t.getMessage();
        int i = k.ordinal();
        if (t instanceof HypixelApi.HttpStatusException h) {
            if (h.status == 429) {
                long pauseMs = Math.max(3000, Math.min(60_000L, h.retryAfterMs)); // never longer than a minute
                boolean wasPaused = now < pauseUntil;
                pauseUntil = now + pauseMs;
                Log.LOGGER.warn("Hypixel says slow down (HTTP 429): pausing {} s", pauseMs / 1000);
                if (!wasPaused && pauseMs >= 10_000L) {
                    sink.notice(false, "Hypixel asked Valtrin to slow down.",
                            "Polling is paused for " + (pauseMs / 1000) + " s, then it continues by itself.", now);
                }
                return;
            }
            // 404/403/400 on one URL for 5 answers in a row: that URL was probably removed, stop using it for 10 minutes.
            if (h.status >= 400 && h.status < 500 && k != HypixelApi.Key.DEFAULT && ++keyBadStatus[i] >= 5) {
                keyDisabledUntil[i] = now + 600_000L;
                keyBadStatus[i] = 0;
                Log.LOGGER.warn("Hypixel URL {} answers HTTP {}: not used for 10 minutes", k, h.status);
            }
            return;
        }
        // Timeout / connection problem. If NO URL has worked for 3 s, throw the connections away (a hung connection stays hung).
        if (now - lastGoodAt > 3000 && now - lastResetAt > 3000) {
            lastResetAt = now;
            api.resetPoll();
            Log.LOGGER.warn("Hypixel connection reset after: {}", t.toString());
        }
    }

    // ---- light polling with a built-in A/B check: every 5th snapshot window is polled the old way (full answers), the
    // winning speed of both kinds is compared, and light polling switches itself off if it is clearly slower.
    private volatile boolean plainWindow = false;
    private volatile boolean lightFallback = false;
    private int windowNo = 0;
    private final java.util.ArrayDeque<Long> lightLags = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<Long> plainLags = new java.util.ArrayDeque<>();

    private boolean lightActive() {
        return FlipConfig.INSTANCE.lightPolling && !lightFallback && !plainWindow;
    }

    private static long median(java.util.Collection<Long> c) {
        List<Long> l = new ArrayList<>(c);
        java.util.Collections.sort(l);
        return l.get(l.size() / 2);
    }

    /** Called for the winning answer of each snapshot (inside synchronized(this)). */
    private void noteWinLag(long lag) {
        java.util.ArrayDeque<Long> dq = lightActive() ? lightLags : plainLags;
        dq.addLast(lag);
        while (dq.size() > 30) dq.pollFirst();
        if (!lightFallback && lightLags.size() >= 12 && plainLags.size() >= 5
                && median(lightLags) > median(plainLags) + 500) {
            lightFallback = true;
            Log.LOGGER.warn("Light polling is slower than full polling ({} ms vs {} ms): switched off",
                    median(lightLags), median(plainLags));
        }
    }

    /** One line for /flip status. */
    synchronized String lightStatus() {
        if (!FlipConfig.INSTANCE.lightPolling) return "polling mode: full answers (light polling is off in the config)";
        if (lightFallback) return "polling mode: full answers (light polling was slower here, so it switched itself off)";
        String ab = lightLags.isEmpty() || plainLags.isEmpty() ? "no comparison yet"
                : String.format(java.util.Locale.ROOT, "light %.1fs vs full %.1fs", median(lightLags) / 1000.0, median(plainLags) / 1000.0);
        return "polling mode: light (" + ab + ")";
    }

    /** A 304 answer: the page has not changed, but the connection is alive. */
    private void onUnchanged(HypixelApi.Key k) {
        long now = System.currentTimeMillis();
        keyBadStatus[k.ordinal()] = 0;
        lastGoodAt = now;
        lastPollAt = now;
        apiSnapshotAgeMs = now + HypixelApi.clockOffsetMs - stampClaimed.get();
    }

    /** A valid page 0 arrived (any URL). The first one with a newer timestamp is handed to the engine thread. */
    private void onPage0(HypixelApi.Page0 p) {
        long now = p.receivedAtMs();
        int ki = p.key().ordinal();
        keyBadStatus[ki] = 0;
        // recovered after being blind?
        long prevGood = lastGoodAt;
        lastGoodAt = now;
        if (lostNotified) {
            lostNotified = false;
            long blind = now - (blindFrom > 0 ? blindFrom : prevGood);
            synchronized (outages) {
                outages.addLast(new long[]{now, blind});
                while (outages.size() > 200) outages.pollFirst();
            }
            blindFrom = 0;
            sink.notice(true, "Back online", "after " + (blind / 1000) + " s.", now);
        }
        lastPollAt = now;
        long lu = p.lastUpdated();
        if (lu > now + HypixelApi.clockOffsetMs + 30_000L) return; // a timestamp from the future: ignore, it would block every real one
        noteAge(p, now);
        long[] seenAt = stampSeen.computeIfAbsent(lu, k -> new long[KEYS.length]);
        if (seenAt[ki] == 0) seenAt[ki] = now;
        if (stampSeen.size() > 60) {
            long min = Long.MAX_VALUE;
            for (long k : stampSeen.keySet()) min = Math.min(min, k);
            stampSeen.remove(min);
        }
        boolean won = false;
        for (;;) {
            long cur = stampClaimed.get();
            if (lu <= cur) break;
            if (stampClaimed.compareAndSet(cur, lu)) { won = true; break; }
        }
        apiSnapshotAgeMs = now + HypixelApi.clockOffsetMs - stampClaimed.get();
        if (!won) return;
        synchronized (this) {
            keyWins[ki]++;
            long winLag = now + HypixelApi.clockOffsetMs - lu;
            // The very first snapshot after start is already as old as it happens to be (up to a minute), so it says
            // nothing about how fast Valtrin reacts: it is left out of the speed numbers.
            if (windowNo > 0) {
                detectLagMs.addLast(winLag);
                while (detectLagMs.size() > 50) detectLagMs.pollFirst();
                noteWinLag(winLag);
            }
            windowNo++;
            plainWindow = windowNo % 5 == 0; // the NEXT window is the plain one every 5th time
        }
        writeAgeLog(p);
        engineExec.execute(() -> onNewStamp(p));
    }

    // ---- cache diagnostics: how old was the cached copy each answer came from? (age-log.tsv, one block per new snapshot)
    private final java.util.ArrayDeque<String> ageRing = new java.util.ArrayDeque<>();

    private void noteAge(HypixelApi.Page0 p, long now) {
        if (!FlipConfig.INSTANCE.pageLog) return;
        String row = now + "\t" + p.key().name() + "\t" + p.lastUpdated() + "\t" + p.age() + "\t" + p.cache() + "\t" + p.ms();
        synchronized (ageRing) {
            ageRing.addLast(row);
            while (ageRing.size() > 8) ageRing.pollFirst();
        }
    }

    /** Called when a new snapshot is first seen: writes the last answers (the ones before it were still the old snapshot). */
    private void writeAgeLog(HypixelApi.Page0 winner) {
        java.nio.file.Path base = pageLogFile;
        if (base == null || !FlipConfig.INSTANCE.pageLog) return;
        java.nio.file.Path f = base.resolveSibling("age-log.tsv");
        StringBuilder sb = new StringBuilder();
        synchronized (ageRing) {
            for (String r : ageRing) sb.append(r).append('\n');
            ageRing.clear();
        }
        sb.append("# new snapshot ").append(winner.lastUpdated()).append(" via ").append(winner.key()).append('\n');
        // How far behind the winner did each URL show the PREVIOUS snapshot (ms; "-" = it never did before the next one)?
        long prevStamp = 0;
        for (long k : stampSeen.keySet()) if (k < winner.lastUpdated() && k > prevStamp) prevStamp = k;
        long[] seenAt = prevStamp == 0 ? null : stampSeen.get(prevStamp);
        if (seenAt != null) {
            long first = Long.MAX_VALUE;
            for (long t : seenAt) if (t > 0) first = Math.min(first, t);
            StringBuilder ks = new StringBuilder("# keys for snapshot ").append(prevStamp).append(':');
            for (HypixelApi.Key k : KEYS) {
                long t = seenAt[k.ordinal()];
                ks.append(' ').append(k.name()).append(' ').append(t == 0 ? "-" : "+" + (t - first));
            }
            sb.append(ks).append('\n');
        }
        String text = sb.toString();
        PAGELOG.execute(() -> {
            try {
                boolean fresh = !java.nio.file.Files.exists(f);
                if (fresh) java.nio.file.Files.writeString(f, "local_ms\tkey\tstamp\tage_s\tcache\tms\n", java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE);
                else if (java.nio.file.Files.size(f) > 8_000_000L) return; // stop at 8 MB
                java.nio.file.Files.writeString(f, text, java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });
    }

    /** Warns once when no URL has answered for 70 s (and again with "back online" when it works again). */
    private void checkLostContact(long now) {
        // Only counts while requests are actually being sent: between two snapshots Valtrin sleeps for about 55 s on purpose.
        if (now - lastFireAt > 5000) return;
        long ref = Math.max(lastGoodAt, activeSince);
        if (!lostNotified && ref > 0 && now - ref > LOST_CONTACT_MS) {
            lostNotified = true;
            blindFrom = ref;
            sink.notice(false, "Lost contact.", "Retrying, alerts paused.", now);
        }
    }

    /** Engine thread: judge the new listings of this page 0 at once, then do the full download. */
    private void onNewStamp(HypixelApi.Page0 p) {
        currentDetectAt = p.receivedAtMs();
        try {
            fastPath(p);
        } catch (Exception e) {
            Log.LOGGER.warn("Fast path failed: {}", e.toString());
        }
        try {
            seePage(0, p.json(), lastUpdated, p.key().name() + ":" + p.ms());
            noteStampTime(p.lastUpdated());
        } catch (Exception ignored) {}
        try {
            fullPass(p);
            failures = 0;
            lastError = "";
            checkRecorder();
        } catch (Exception e) {
            failures++;
            lastError = String.valueOf(e.getMessage());
            Log.LOGGER.warn("Full snapshot failed ({}): {}", failures, e.toString());
        }
    }

    private synchronized void noteStampTime(long stamp) {
        stampTimes.addLast(stamp);
        long cutoff = stamp - 3_600_000L;
        while (!stampTimes.isEmpty() && stampTimes.peekFirst() < cutoff) stampTimes.pollFirst();
    }

    private volatile int knownPages = 0;
    /** True when some auction pages did not arrive for the snapshot being processed (see process). */
    private boolean partialSnapshot = false;

    // ---- per-page diagnostic log + stale page re-check
    private final Map<Integer, Long> pageLu = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, Set<String>> pageIds = new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long lastStaleRefetchAt = 0;
    private volatile long pageLogLines = 0;
    private static final java.util.concurrent.ExecutorService PAGELOG = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Valtrin-pagelog");
        t.setDaemon(true);
        return t;
    });

    /** Number of lines written to the page log this session (shown in /flip status). */
    long pageLogLines() { return pageLogLines; }

    /**
     * Looks at one probed page. Writes a log line when the page's timestamp changed.
     * @return true if this page advanced to a newer timestamp (it may be a page that lagged behind the others)
     */
    private boolean seePage(int page, JsonObject o, long globalLu, String winner) {
        long plu = o.get("lastUpdated").getAsLong();
        Long prev = pageLu.get(page);
        if (prev != null && prev == plu) return false;
        pageLu.put(page, plu);
        boolean advanced = prev != null && plu > prev;
        if (page == 0) noteStamp(plu);
        if (!FlipConfig.INSTANCE.pageLog) return advanced;
        long now = System.currentTimeMillis();
        long serverNow = now + HypixelApi.clockOffsetMs;
        int count = 0, fresh = 0;
        long newest = 0;
        Set<String> ids = new HashSet<>();
        Set<String> old = pageIds.get(page);
        JsonArray arr = o.getAsJsonArray("auctions");
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject a = el.getAsJsonObject();
                count++;
                String u = a.get("uuid").getAsString();
                ids.add(u);
                if (old != null && !old.contains(u)) fresh++;
                long st = a.has("start") ? a.get("start").getAsLong() : 0;
                if (st > newest) newest = st;
            }
        }
        pageIds.put(page, ids);
        String line = now + "\t" + page + "\t" + plu + "\t" + String.format(java.util.Locale.ROOT, "%.1f", (serverNow - plu) / 1000.0)
                + "\t" + count + "\t" + (old == null ? -1 : fresh) + "\t"
                + (newest == 0 ? -1 : (serverNow - newest) / 1000) + "\t" + (plu <= globalLu && advanced ? "BEHIND" : "") + "\t" + winner + "\n";
        writePageLog(line);
        return advanced;
    }

    /** Page 0 holds the newest listings: when it only changes every ~5 minutes, Hypixel is publishing in batches. */
    private synchronized void noteStamp(long plu) {
        if (!page0Stamps.isEmpty() && page0Stamps.peekLast() == plu) return;
        page0Stamps.addLast(plu);
        while (page0Stamps.size() > 4) page0Stamps.pollFirst();
        if (page0Stamps.size() < 4) return;
        Long prev = null;
        boolean allLong = true;
        for (long t : page0Stamps) {
            if (prev != null && t - prev < 240_000L) allLong = false;
            prev = t;
        }
        batchMode = allLong;
    }

    private void writePageLog(String line) {
        java.nio.file.Path f = pageLogFile;
        if (f == null) return;
        pageLogLines++;
        PAGELOG.execute(() -> {
            try {
                boolean fresh = !java.nio.file.Files.exists(f);
                if (f.getParent() != null) java.nio.file.Files.createDirectories(f.getParent());
                String text = (fresh ? "local_ms\tpage\tpage_last_updated\tsec_after_stamp\tauctions\tnew_uuids\tnewest_listing_age_s\tflag\twinner\n" : "") + line;
                java.nio.file.Files.writeString(f, text, java.nio.charset.StandardCharsets.UTF_8,
                        java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });
    }
    private volatile java.nio.file.Path pageLogFile;

    // ---- warning when nothing is being recorded
    private final long startedAt = System.currentTimeMillis();
    private long lastRecorderWarnAt = 0;

    private void checkRecorder() {
        SaleLog l = OwnModel.log;
        // With the separate recorder running, this game does not record sales itself (OwnModel.recordSale), so there is nothing to check here.
        if (l == null || l.remote() || l.recorderActive()) return;
        long now = System.currentTimeMillis();
        long last = Math.max(l.lastAddAt, startedAt);
        if (now - last > 10 * 60_000L && now - lastRecorderWarnAt > 30 * 60_000L) {
            lastRecorderWarnAt = now;
            sink.notice(false, "No sold auctions recorded for 10 minutes.",
                    "Hypixel's sold-auctions feed may be down, prices and volume will get stale.", now);
        }
    }

    /**
     * FAST PATH: runs the moment a newer page 0 arrives. Only listings that are new on page 0 are judged, against the
     * previous full snapshot plus these new listings. Nothing else is waited for (no other pages, no seller names).
     * The decisions are the same as in the full pass (see DESIGN_22.md); only the timing changes.
     */
    private void fastPath(HypixelApi.Page0 p) {
        Set<String> previous = seen;
        if (previous.isEmpty()) return; // first snapshot after start is only a baseline (full pass)
        long now = System.currentTimeMillis();
        long serverNow = now + HypixelApi.clockOffsetMs;
        Map<String, Auction> page = new LinkedHashMap<>();
        parsePage(p.json(), page);
        List<Entry> news = new ArrayList<>();
        List<Market.Row> newRows = new ArrayList<>();
        for (Auction a : page.values()) {
            if (previous.contains(a.uuid())) continue;
            ItemData d = parseItem(a);
            if (d == ItemData.NONE) continue;
            news.add(new Entry(a, d));
            newRows.add(new Market.Row(a.price(), d));
        }
        // From now on these listings count as known (the full pass would otherwise judge them a second time).
        Set<String> next = new HashSet<>(previous);
        next.addAll(page.keySet());
        seen = next;
        if (news.isEmpty()) return;
        Market.publishIncremental(newRows, now);
        for (Entry e : news) firstSeen.putIfAbsent(e.a().uuid(), now);
        List<Entry> toJudge = selectToJudge(news, previous, now, serverNow);
        try {
            judgeAndEmit(toJudge, serverNow);
        } finally {
            // The fast path compared against last minute's listing table, which can still contain listings that have sold
            // since. Whatever did not alert is looked at once more against the full snapshot (see recheckFastRejects).
            // In a finally block: if judging fails half way, these listings (already marked as known) are still judged later.
            for (Entry e : toJudge) {
                String u = e.a().uuid();
                if (!alerted.containsKey(u) && !pending.containsKey(u)) fastRejected.put(u, e);
            }
        }
    }

    /** Listings the fast path judged without an alert, kept until the full snapshot is in. */
    private final Map<String, Entry> fastRejected = new java.util.concurrent.ConcurrentHashMap<>();

    /** The fast-path rejects that are still listed, judged again with the full snapshot's (fresh) listing table. */
    private List<Entry> recheckFastRejects(List<Entry> entries, long now) {
        if (fastRejected.isEmpty()) return List.of();
        long keepMs = Math.max(30, FlipConfig.INSTANCE.maxAgeSeconds) * 1000L;
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            String u = e.a().uuid();
            if (!fastRejected.containsKey(u) || alerted.containsKey(u)) continue;
            Long fs = firstSeen.get(u);
            if (fs != null && now - fs > keepMs) continue;
            out.add(e);
        }
        fastRejected.clear();
        return out;
    }

    private ItemData parseItem(Auction a) {
        // Rarity comes from the item's lore, same source as sold auctions, so keys always match;
        // the API's own tier is only a fallback.
        return cache.computeIfAbsent(a.uuid(), u -> {
            ItemData d = ItemKeys.parse(a.itemBytes());
            return (d == ItemData.NONE || d.tier() != null) ? d : d.withTier(a.tier());
        });
    }

    /**
     * FULL PASS: downloads the other pages (page 0 is already here) and processes the whole snapshot as before:
     * lowest-BIN table, replay recording, new listings that were not on page 0, listings waiting for volume.
     */
    private void fullPass(HypixelApi.Page0 p) throws Exception {
        long lu = p.lastUpdated();
        int pages = p.totalPages();
        if (pages > 0) knownPages = pages;
        long fetchStart = System.currentTimeMillis();

        Map<String, Auction> all = new LinkedHashMap<>();
        AuctionFeed.begin();
        parsePage(p.json(), all);
        int got = 1;
        int kp = knownPages;
        int batch = Math.max(1, FlipConfig.INSTANCE.pageBatch);
        List<Long> pageMs = new ArrayList<>();
        // Ask for a slow page a second time after about 1.5x the usual page time (between 1 and 2.5 s).
        long hedge = lastPageMedianMs > 0 ? Math.max(1000L, Math.min(2500L, lastPageMedianMs * 3 / 2)) : 2500L;
        for (int start = 1; start < pages; start += batch) {
            List<CompletableFuture<JsonObject>> futures = new ArrayList<>();
            long batchStart = System.currentTimeMillis();
            for (int pg = start; pg < Math.min(pages, start + batch); pg++) {
                CompletableFuture<JsonObject> f = api.auctionPage(pg, 15, hedge);
                f.thenRun(() -> { synchronized (pageMs) { pageMs.add(System.currentTimeMillis() - batchStart); } });
                futures.add(f);
            }
            for (int i = 0; i < futures.size(); i++) {
                JsonObject o;
                try {
                    o = futures.get(i).get(30, TimeUnit.SECONDS);
                } catch (Exception e) {
                    Log.LOGGER.warn("Auction page {} not available ({}); skipped", start + i, e.toString());
                    continue;                          // the list shrank while we were reading it, or the page is slow
                }
                got++;
                lu = Math.max(lu, o.get("lastUpdated").getAsLong());
                int pg = start + i;
                if (pg == kp / 2 || pg == kp - 1) seePage(pg, o, lastUpdated, ""); // same diagnostics as before
                parsePage(o, all);
            }
        }
        synchronized (pageMs) {
            if (pageMs.isEmpty()) { lastPageMedianMs = -1; lastPageMaxMs = -1; }
            else {
                List<Long> sorted = new ArrayList<>(pageMs);
                java.util.Collections.sort(sorted);
                lastPageMedianMs = sorted.get(sorted.size() / 2);
                lastPageMaxMs = sorted.get(sorted.size() - 1);
            }
        }
        if (pages > 1 && got < Math.max(2, pages / 2)) {
            // Most pages missing: a snapshot built from the rest would make half the market look "new" next time.
            // Keep the previous picture (the fast path already judged this page 0) and try again at the next stamp.
            Log.LOGGER.warn("Only {} of {} auction pages arrived; snapshot skipped", got, pages);
            api.resetPoll();
            return;
        }

        // Snapshot history: how far apart snapshots arrive and how fresh their newest listings are.
        long serverNow = System.currentTimeMillis() + HypixelApi.clockOffsetMs;
        long newestStart = 0;
        int fresh = 0;
        Set<String> prevSeen = seen;
        for (Auction a : all.values()) {
            if (a.start() > newestStart) newestStart = a.start();
            if (!prevSeen.contains(a.uuid())) fresh++;
        }
        synchronized (this) {
            snaps.addLast(new Snap(lu, lastUpdated == 0 ? -1 : lu - lastUpdated, serverNow - lu,
                    newestStart == 0 ? -1 : serverNow - newestStart, prevSeen.isEmpty() ? -1 : fresh, all.size()));
            while (snaps.size() > 10) snaps.pollFirst();
        }
        lastUpdated = lu;
        partialSnapshot = pages > 1 && got < pages;
        lastFetchMs = System.currentTimeMillis() - fetchStart;
        long procStart = System.currentTimeMillis();
        process(lu, all);
        AuctionFeed.done(lu);
        lastProcessMs = System.currentTimeMillis() - procStart;
        lastLagMs = System.currentTimeMillis() + HypixelApi.clockOffsetMs - lu;
    }

    private static void parsePage(JsonObject page, Map<String, Auction> out) {
        JsonArray arr = page.getAsJsonArray("auctions");
        if (arr == null) return;
        for (JsonElement el : arr) {
            JsonObject o = el.getAsJsonObject();
            AuctionFeed.listing(o);
            if (!o.has("bin") || !o.get("bin").getAsBoolean()) continue;
            if (o.has("claimed") && o.get("claimed").getAsBoolean()) continue;
            String uuid = o.get("uuid").getAsString();
            out.put(uuid, new Auction(
                    uuid,
                    o.get("item_name").getAsString(),
                    o.has("tier") ? o.get("tier").getAsString() : "COMMON",
                    o.get("starting_bid").getAsLong(),
                    o.get("start").getAsLong(),
                    HypixelApi.itemBytes(o.get("item_bytes")),
                    o.has("auctioneer") ? o.get("auctioneer").getAsString() : null));
        }
    }

    private void process(long lu, Map<String, Auction> all) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        long now = System.currentTimeMillis();
        long serverNow = now + HypixelApi.clockOffsetMs; // listing start times are on Hypixel's clock

        // Parse every listing (rarity comes from the item's lore, same source as sold auctions, so keys always match;
        // the API's own tier is only a fallback).
        List<Entry> entries = new ArrayList<>(all.size());
        List<Market.Row> rows = new ArrayList<>(all.size());
        for (Auction a : all.values()) {
            ItemData d = parseItem(a);
            if (d == ItemData.NONE) continue;
            entries.add(new Entry(a, d));
            AuctionFeed.stars(a.uuid(), d.stars());
            rows.add(new Market.Row(a.price(), d));
        }
        cache.keySet().retainAll(all.keySet());

        // Share the snapshot with the tooltip/highlight code and the evaluation below.
        Market.publishSnapshot(rows, now);
        Recorder.record(lu, now, entries.stream().map(e -> new Recorder.Rec(e.a().uuid(), e.a().start(), e.a().price(),
                e.d(), e.a().seller())).toList());

        Set<String> previous = seen;
        // A page that did not arrive must not make its listings look "new" again at the next snapshot (they would be
        // alerted late, with a wrong "seen" time): for such a snapshot the old listings stay known.
        Set<String> nowSeen = new HashSet<>(all.keySet());
        if (partialSnapshot) nowSeen.addAll(previous);
        seen = nowSeen;
        tracked = all.size();
        lastRefreshAt = now;

        // When did WE first see each listing? (Hypixel can show a listing minutes after it was posted, especially in
        // 5-minute batch mode, so the start time says little about whether it is still worth alerting.)
        for (Entry e : entries) firstSeen.putIfAbsent(e.a().uuid(), now);
        if (!partialSnapshot) firstSeen.keySet().retainAll(all.keySet());
        pending.keySet().retainAll(all.keySet());
        alerted.values().removeIf(t -> now - t > 30 * 60_000L);

        if (previous.isEmpty()) return; // first snapshot = baseline only, no alerts

        // New listings (not seen on page 0 by the fast path either), plus those waiting from earlier snapshots,
        // plus the fast-path rejects (second look against the full snapshot).
        List<Entry> toJudge = new ArrayList<>(selectToJudge(entries, previous, now, serverNow));
        Set<String> have = new HashSet<>();
        for (Entry e : toJudge) have.add(e.a().uuid());
        for (Entry e : recheckFastRejects(entries, now)) if (have.add(e.a().uuid())) toJudge.add(e);
        judgeAndEmit(toJudge, serverNow);
    }

    /** Which of these listings are to be judged now: new ones, and ones waiting for a temporary reason. */
    private List<Entry> selectToJudge(List<Entry> entries, Set<String> previous, long now, long serverNow) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        long keepMs = Math.max(30, cfg.maxAgeSeconds) * 1000L;
        long listedCapMs = Math.max(30, cfg.maxListedAgeSeconds) * 1000L;
        List<Entry> toJudge = new ArrayList<>();
        for (Entry e : entries) {
            Auction a = e.a();
            boolean isNew = !previous.contains(a.uuid());
            boolean waiting = pending.containsKey(a.uuid());
            if (!isNew && !waiting) continue;
            if (alerted.containsKey(a.uuid())) continue;
            // Already very old when we first saw it: probably an odd leftover (page that came back), not a fresh listing.
            if (isNew && serverNow - a.start() > listedCapMs) continue;
            Long fs = firstSeen.get(a.uuid());
            if (fs != null && now - fs > keepMs) { pending.remove(a.uuid()); continue; }
            toJudge.add(e);
        }
        return toJudge;
    }

    /** Reads the sales of the items first (in the background), judges what is ready now, and the rest as soon as it is. */
    private void judgeAndEmit(List<Entry> toJudge, long serverNow) {
        // Read the recorded sales of every item that has a new listing BEFORE judging them,
        // otherwise a listing can be judged while its sales are still loading.
        Set<String> ids = new HashSet<>();
        for (Entry e : toJudge) {
            if (e.d().tier() == null || e.d().cosmetic() || Pricing.isBazaar(e.d().id())) continue;
            ids.add(e.d().id());
        }
        // No waiting: start reading the sales of every needed item in the background, judge what is ready right
        // now and send those alerts at once. Listings whose sales are still loading are judged one item at a time
        // as soon as that item's sales are ready (see judgeLate), instead of being rejected for good.
        OwnModel.warm(ids);
        List<Entry> unready = new ArrayList<>();
        List<Entry> quick = new ArrayList<>();
        emit(judge(toJudge, serverNow, unready, quick));
        logRejectsLater(quick);
        if (!unready.isEmpty()) {
            Map<String, List<Entry>> byItem = new HashMap<>();
            for (Entry e : unready) byItem.computeIfAbsent(e.d().id(), k -> new ArrayList<>()).add(e);
            long deadline = System.currentTimeMillis() + 8_000;
            if (running) pool.schedule(() -> judgeLate(byItem, deadline), 20, TimeUnit.MILLISECONDS);
        }
    }

    /** Judges the waiting listings of every item whose sales are ready now; checks again shortly for the rest. */
    private void judgeLate(Map<String, List<Entry>> byItem, long deadline) {
        if (!running) return;
        try {
            List<Candidate> ready = new ArrayList<>();
            List<Entry> quick = new ArrayList<>();
            for (Iterator<Map.Entry<String, List<Entry>>> it = byItem.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, List<Entry>> en = it.next();
                if (!OwnModel.ready(en.getKey())) continue;
                ready.addAll(judge(en.getValue(), System.currentTimeMillis() + HypixelApi.clockOffsetMs, new ArrayList<>(), quick));
                it.remove();
            }
            emit(ready);
            logRejectsLater(quick);
        } catch (Exception ex) {
            Log.LOGGER.warn("Late judging failed: {}", ex.toString());
        }
        if (!byItem.isEmpty() && System.currentTimeMillis() < deadline && running) {
            pool.schedule(() -> judgeLate(byItem, deadline), deadline - System.currentTimeMillis() > 7_500 ? 20 : 100, TimeUnit.MILLISECONDS); // first 0.5 s: check every 20 ms
        } else if (!byItem.isEmpty()) {
            // Sales still not readable after the deadline: keep the listings and try again with the next snapshot.
            for (List<Entry> l : byItem.values()) for (Entry e : l) pending.put(e.a().uuid(), e);
        }
    }

    /**
     * Quick no: in "lbin" and "both" mode a listing can only pass when it is cheaper than the lowest comparable listing,
     * so when it is not (or there is none) the slow sales maths is skipped. Same answer as the full check, just sooner.
     */
    private static boolean cheapReject(ItemData d, long price) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        if (price <= 0 || d.tier() == null || cfg.referenceMode.equals("median")) return false;
        OwnModel.Lbins lb = OwnModel.lbins(d, price, Math.max(1, cfg.minListings));
        return lb == null || lb.lowest() <= price;
    }

    /** Writes the "/flip why" log line for listings that were rejected by the quick check (after the alerts went out). */
    private void logRejectsLater(List<Entry> rejected) {
        if (rejected.isEmpty() || !running) return;
        pool.execute(() -> {
            for (Entry e : rejected) {
                try {
                    logReject(e, Market.estimate(e.d(), e.a().price()));
                } catch (RuntimeException ignored) {}
            }
        });
    }

    /** Judges the given listings; those whose sales are still loading go into {@code unready}. */
    private List<Candidate> judge(List<Entry> entries, long serverNow, List<Entry> unready, List<Entry> quickRejected) {
        List<Candidate> found = new ArrayList<>();
        for (Entry e : entries) {
            Auction a = e.a();
            if (alerted.containsKey(a.uuid())) continue;
            if (cheapReject(e.d(), a.price())) {
                pending.remove(a.uuid());
                quickRejected.add(e);
                continue;
            }
            if (e.d().tier() != null && !e.d().cosmetic() && !Pricing.isBazaar(e.d().id()) && OwnModel.loading(e.d())) {
                unready.add(e);
                continue;
            }
            Market.Estimate est = Market.estimate(e.d(), a.price());
            int verdict = Market.check(est, e.d(), a.name(), a.price());
            if (verdict == Market.PASS) {
                pending.remove(a.uuid());
                found.add(new Candidate(e, est, Market.volumePerDay(e.d())));
            } else if (verdict == Market.WAIT_VOLUME) {
                pending.put(a.uuid(), e); // volume still unknown (warming up): look again next snapshot
                logReject(e, est);
            } else {
                pending.remove(a.uuid());
                logReject(e, est);
            }
        }
        return found;
    }

    /** Remembers why a listing that looked profitable did not alert (/flip why). */
    private void logReject(Entry e, Market.Estimate est) {
        try {
            String code = Market.rejectCode(est, e.d(), e.a().name(), e.a().price());
            if (code == null) return;
            OwnModel.Stats st = e.d().tier() == null ? null : OwnModel.stats(e.d(), Clock.now());
            RejectLog.add(new RejectLog.Row(System.currentTimeMillis(), e.a().uuid(), e.a().name(), e.a().price(),
                    est.lbinSell(), est.medianSell(), st == null ? 0 : st.samples(), st == null ? null : st.conf(), code));
        } catch (RuntimeException ignored) {}
    }

    /** Queues alerts for the candidates, best profit first, within the alert limit. */
    private void emit(List<Candidate> found) {
        if (found.isEmpty()) return;
        List<Candidate> chosen = choose(found);
        if (chosen.isEmpty()) return;

        // Look up all sellers at the same time (short deadline) instead of one after another.
        // This runs outside the lock, so other alerts are not held up while names are fetched.
        List<String> sellers = new ArrayList<>();
        for (Candidate c : chosen) sellers.add(c.e().a().seller());
        Map<String, String> names = SellerNames.lookupAll(sellers, Math.max(0, FlipConfig.INSTANCE.sellerWaitMs));

        long at = System.currentTimeMillis();
        long serverNow = at + HypixelApi.clockOffsetMs;
        for (Candidate c : chosen) {
            Auction a = c.e().a();
            Market.Estimate est = c.est();
            Long fs = firstSeen.get(a.uuid());
            String timing = "Listed " + ago((serverNow - a.start()) / 1000) + " ago, seen by Valtrin "
                    + ago(fs == null ? 0 : (at - fs) / 1000) + " ago";
            sink.flip(new FlipAlert(a.name(), a.tier(), a.uuid(), a.seller(), a.price(), est.sell(),
                    est.profit(), est.margin(), Market.volumeExact(c.e().d()),
                    Market.describe(c.e().d(), est) + "\n" + timing, names.get(a.seller()),
                    alertConf(c.e().d()), SellTime.forFlip(c.e().d(), a.price(), est)), at, true);
            FlipQueue.add(new FlipQueue.Item(a.uuid(), a.name(), a.price(), est.profit(), at));
            AlertHistory.add(a.uuid(), a.name(), a.tier(), a.price(), est.profit(), at);
            writeAlertLog(a, est, fs, at, serverNow);
            alertsSent++;
            if (fs != null) {
                synchronized (this) {
                    alertLagMs.addLast(at - fs);
                    while (alertLagMs.size() > 50) alertLagMs.pollFirst();
                }
            }
        }
    }

    /** One line per alert in alerts.tsv (next to poll-log.tsv): exact listing id and times, so alerts can be matched with other tools' alerts. */
    private void writeAlertLog(Auction a, Market.Estimate est, Long firstSeenAt, long at, long serverNow) {
        java.nio.file.Path base = pageLogFile;
        if (base == null || !FlipConfig.INSTANCE.pageLog) return;
        java.nio.file.Path f = base.resolveSibling("alerts.tsv");
        String line = at + "\t" + a.uuid() + "\t" + a.name().replace('\t', ' ').replace('\n', ' ') + "\t" + a.price() + "\t" + est.profit()
                + "\t" + (firstSeenAt == null ? -1 : at - firstSeenAt) + "\t" + (serverNow - a.start()) / 1000 + "\n";
        PAGELOG.execute(() -> {
            try {
                boolean fresh = !java.nio.file.Files.exists(f);
                if (!fresh && java.nio.file.Files.size(f) > 8_000_000L) return;
                java.nio.file.Files.writeString(f, (fresh ? "local_ms\tuuid\tname\tprice\tprofit\tseen_to_alert_ms\tlisted_age_s\n" : "") + line,
                        java.nio.charset.StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });
    }

    private static String ago(long seconds) {
        if (seconds < 0) seconds = 0;
        return seconds < 120 ? seconds + "s" : (seconds / 60) + "m" + (seconds % 60) + "s";
    }

    /**
     * Applies the alert limit (best profit first). Flips that do not fit are NOT dropped: they wait in the overflow
     * list and go out as room appears, for as long as the listing is still there and not too old.
     */
    private List<Candidate> choose(List<Candidate> found) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        List<Candidate> chosen = new ArrayList<>();
        boolean left = false;
        synchronized (this) {
            long now = System.currentTimeMillis();
            List<Candidate> all = new ArrayList<>(found);
            all.addAll(overflow);
            overflow.clear();
            Set<String> uniq = new HashSet<>();
            all.removeIf(c -> !uniq.add(c.e().a().uuid()) || alerted.containsKey(c.e().a().uuid()));
            all.sort((x, y) -> Long.compare(y.est().profit(), x.est().profit()));
            // Alert limit: at most alertLimit alerts per 10 seconds (0 = no limit).
            while (!recentSent.isEmpty() && now - recentSent.peekFirst() >= 10_000L) recentSent.pollFirst();
            int limit = cfg.alertLimit;
            for (Candidate c : all) {
                boolean full = (limit > 0 && recentSent.size() >= limit) || sink.pending() + chosen.size() >= MAX_QUEUE;
                if (full) {
                    if (overflow.size() < MAX_OVERFLOW) overflow.add(c);
                    continue;
                }
                recentSent.addLast(now);
                alerted.put(c.e().a().uuid(), now);
                chosen.add(c);
            }
            left = !overflow.isEmpty();
        }
        if (left) scheduleDrain();
        return chosen;
    }

    private void scheduleDrain() {
        if (running && drainScheduled.compareAndSet(false, true)) pool.schedule(this::drainOverflow, 1, TimeUnit.SECONDS);
    }

    /** Sends waiting flips as the alert limit allows; drops those that are gone or older than maxage (since first seen). */
    private void drainOverflow() {
        drainScheduled.set(false);
        if (!running) return;
        try {
            long now = System.currentTimeMillis();
            long keepMs = Math.max(30, FlipConfig.INSTANCE.maxAgeSeconds) * 1000L;
            List<Candidate> waiting;
            synchronized (this) {
                waiting = new ArrayList<>(overflow);
                overflow.clear();
            }
            Set<String> present = seen;
            waiting.removeIf(c -> {
                Long fs = firstSeen.get(c.e().a().uuid());
                return !present.contains(c.e().a().uuid()) || (fs != null && now - fs > keepMs);
            });
            emit(waiting);
        } catch (Exception ex) {
            Log.LOGGER.warn("Overflow drain failed: {}", ex.toString());
        }
    }

    /**
     * Approximate AH costs when relisting at {@code sell}: listing fee (1% / 2% / 2.5% by price)
     * plus the 1% claim tax on sales of 1M+. Verify against the SkyBlock wiki if Hypixel changes them.
     */
    static long fees(long sell) {
        double create = sell < 10_000_000 ? 0.01 : sell < 100_000_000 ? 0.02 : 0.025;
        double claim = sell >= 1_000_000 ? 0.01 : 0.0;
        return Math.round(sell * (create + claim));
    }

    // ------------------------------------------------------------------ sales volume and sold prices

    private void endedTick() {
        if (!running) return;
        try {
            if (shouldPoll()) pollEnded();
        } catch (Exception e) {
            Log.LOGGER.warn("Ended-auction poll failed: {}", e.toString());
        }
        if (running) pool.schedule(this::endedTick, 15, TimeUnit.SECONDS);
    }

    private void pollEnded() throws Exception {
        JsonObject o = api.get("auctions_ended");
        long lu = o.get("lastUpdated").getAsLong();
        long now = System.currentTimeMillis();
        volume.touch(now);
        if (lu == lastEndedUpdated) return;
        lastEndedUpdated = lu;

        Set<String> ids = new HashSet<>();
        JsonArray arr = o.getAsJsonArray("auctions");
        if (arr != null) {
            for (JsonElement el : arr) {
                JsonObject a = el.getAsJsonObject();
                String id = a.get("auction_id").getAsString();
                ids.add(id);
                if (lastEndedIds.contains(id)) continue;
                AuctionFeed.sale(a);
                if (!a.has("bin") || !a.get("bin").getAsBoolean()) continue;

                ItemData d = ItemKeys.parse(HypixelApi.itemBytes(a.get("item_bytes")));
                // The ended-auctions feed has no rarity field, so it is read from the item's lore.
                // Without a rarity the sale can't be filed under the right group, so it is skipped.
                if (d == ItemData.NONE || d.tier() == null || d.cosmetic()) continue;

                long sold = a.has("price") ? a.get("price").getAsLong() : 0;
                long mod = Pricing.ready() ? Pricing.modifierValue(d).total() : -1;
                volume.recordSale(d.key(), a.get("timestamp").getAsLong(), sold, mod);
                OwnModel.recordSale(d, a.get("timestamp").getAsLong(), sold);
            }
        }
        lastEndedIds = ids;
        OwnModel.markCovered(lu);

        if (now - lastSave > 2 * 60_000L) {
            volume.save(now);
            OwnModel.flush();
            lastSave = now;
        }
    }

    // ------------------------------------------------------------------ test helper

    /** HIGH / MED / LOW for the alert line, or null when no sales stats exist for the item. */
    private static String alertConf(ItemData d) {
        try {
            OwnModel.Stats st = d.tier() == null ? null : OwnModel.stats(d, Clock.now());
            return st == null ? null : st.conf();
        } catch (RuntimeException e) {
            return null;
        }
    }

    void sendTestAlert() {
        long sell = 1_600_000;
        long profit = sell - fees(sell) - 1_000_000;
        sink.flip(new FlipAlert("Test Item (not a real flip)", "LEGENDARY", "00000000000000000000000000000000", null,
                1_000_000, sell, profit, profit * 100.0 / 1_000_000, 28.9,
                "Test alert - no real data.\nIn real alerts this lists the value and modifier breakdown.",
                "Technoblade", "HIGH", new SellTime.Resale(1_550_000, 50, 380_000, 456_000)), System.currentTimeMillis(), false);
    }
}
