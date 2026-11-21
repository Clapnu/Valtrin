// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * /flip timing: how long the FIRST alert of each Hypixel snapshot takes to reach this player's chat.
 *
 * The clock starts when Valtrin (the server, or this computer when it searches itself) receives Hypixel's new
 * snapshot. How long Hypixel takes to publish the snapshot is not counted. For server alerts the time is split into:
 *   server  = snapshot received -> alert raised (server clock, exact)
 *   network = alert raised -> alert received here, minus the plan delay (needs the server's clock, see {@link #sync},
 *             so it is an estimate: about plus or minus half a ping)
 *   display = alert received -> shown in chat (this computer's clock, exact)
 * The plan delay (free keys) is kept separately and is not part of the total.
 *
 * Only the first alert shown per snapshot is measured, and only for a listing that is new in that snapshot.
 * The last {@value #KEEP} measurements are kept in ValtrinData/timing.tsv so they survive a restart.
 * Plain Java on purpose (the server jar compiles this file too, but never uses it).
 */
final class Timing {
    private Timing() {}

    /** What an alert carries until it is shown. Times are on the clock of the machine that found the flip, except recvAt. */
    record Tag(boolean remote, long snap, long snapAt, long issuedAt, long recvAt, long delayMs) {}

    /**
     * One snapshot's first shown alert. {@code offset} is the server clock minus this PC's clock when it was shown, or
     * {@link Long#MIN_VALUE} when not known yet (it is then filled in from a later clock check, if any).
     */
    record Row(long shownAt, boolean remote, long snap, long snapAt, long issuedAt, long recvAt, long delay, long offset) {
        long server() { return Math.max(0, issuedAt - snapAt); }

        long display() { return Math.max(0, shownAt - (remote ? recvAt : issuedAt)); }

        /** Server to this PC without the plan delay, or -1 when the clocks cannot be compared. */
        long network(long fallbackOffset) {
            if (!remote) return 0;
            long off = offset != Long.MIN_VALUE ? offset : fallbackOffset;
            if (off == Long.MIN_VALUE) return -1;
            return Math.max(0, recvAt + off - issuedAt - delay);
        }

        /** Snapshot received to shown in chat, without the plan delay; -1 when unknown. */
        long total(long fallbackOffset) {
            long n = network(fallbackOffset);
            return n < 0 ? -1 : server() + n + display();
        }
    }

    static final int KEEP = 500;
    private static final ArrayDeque<Row> rows = new ArrayDeque<>();
    private static final LinkedHashSet<Long> counted = new LinkedHashSet<>();
    private static volatile Path file;
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "valtrin-timing");
        t.setDaemon(true);
        return t;
    });

    // ------------------------------------------------------------------ server clock

    private static volatile long offsetMs = Long.MIN_VALUE;
    private static volatile long offsetRtt = Long.MAX_VALUE;
    private static volatile long offsetAt = 0;

    /**
     * One clock check against the server: sent at t0, server said serverNow, answer arrived at t1 (this PC's clock).
     * Keeps the sample with the shortest round trip (the most exact one); a sample older than 15 minutes is replaced.
     */
    static synchronized void sync(long t0, long serverNow, long t1) {
        long rtt = t1 - t0;
        if (rtt < 0 || rtt > 10_000) return;
        long off = serverNow - (t0 + t1) / 2;
        long now = System.currentTimeMillis();
        if (offsetMs == Long.MIN_VALUE || rtt <= offsetRtt || now - offsetAt > 15 * 60_000L) {
            offsetMs = off;
            offsetRtt = rtt;
            offsetAt = now;
        }
    }

    /** Server clock minus this PC's clock, or Long.MIN_VALUE when never checked. */
    static long offset() { return offsetMs; }

    /** Round trip of the clock check in use (its error is at most half of this). */
    static long offsetRtt() { return offsetRtt; }

    /** True when the clock was last checked more than 10 minutes ago (or never). */
    static boolean syncDue() {
        return offsetMs == Long.MIN_VALUE || System.currentTimeMillis() - offsetAt > 10 * 60_000L;
    }

    // ------------------------------------------------------------------ recording

    static Tag local(FlipAlert f) {
        return f.snap() > 0 ? new Tag(false, f.snap(), f.snapAt(), f.issuedAt(), f.issuedAt(), 0) : null;
    }

    static Tag remote(FlipAlert f, long recvAt, long delayMs) {
        return f.snap() > 0 ? new Tag(true, f.snap(), f.snapAt(), f.issuedAt(), recvAt, Math.max(0, delayMs)) : null;
    }

    /** Called when an alert is put into chat. Only the first one per snapshot is kept. */
    static void shown(Tag t, long now) {
        if (t == null || t.snap() <= 0 || t.snapAt() <= 0 || t.issuedAt() <= 0) return;
        Row r;
        synchronized (Timing.class) {
            if (!counted.add(t.snap())) return;
            while (counted.size() > KEEP + 100) counted.remove(counted.iterator().next());
            r = new Row(now, t.remote(), t.snap(), t.snapAt(), t.issuedAt(), t.recvAt(), t.delayMs(), t.remote() ? offsetMs : 0);
            rows.addLast(r);
            while (rows.size() > KEEP) rows.pollFirst();
        }
        append(r);
    }

    static synchronized List<Row> since(long fromMs) {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) if (r.shownAt() >= fromMs) out.add(r);
        return out;
    }

    // ------------------------------------------------------------------ numbers

    record Stats(int n, long median, long p25, long p75, long p90, long best) {}

    /** Median and friends of the non-negative values, or null when there are none. */
    static Stats stats(List<Long> values) {
        List<Long> v = new ArrayList<>();
        for (Long x : values) if (x != null && x >= 0) v.add(x);
        if (v.isEmpty()) return null;
        Collections.sort(v);
        return new Stats(v.size(), pick(v, 0.5), pick(v, 0.25), pick(v, 0.75), pick(v, 0.9), v.get(0));
    }

    private static long pick(List<Long> sorted, double q) {
        int i = (int) Math.floor(q * (sorted.size() - 1) + 0.5);
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    // ------------------------------------------------------------------ file

    /** Loads the saved measurements (call once at start). */
    static void init(Path f) {
        file = f;
        try {
            if (!Files.exists(f)) return;
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            synchronized (Timing.class) {
                for (int i = Math.max(0, lines.size() - KEEP); i < lines.size(); i++) {
                    Row r = parse(lines.get(i));
                    if (r == null) continue;
                    rows.addLast(r);
                    counted.add(r.snap());
                }
            }
        } catch (Exception ignored) {}
    }

    private static Row parse(String line) {
        try {
            String[] p = line.split("\t");
            if (p.length < 8 || p[0].startsWith("shown")) return null;
            return new Row(Long.parseLong(p[0]), "1".equals(p[1]), Long.parseLong(p[2]), Long.parseLong(p[3]),
                    Long.parseLong(p[4]), Long.parseLong(p[5]), Long.parseLong(p[6]), "-".equals(p[7]) ? Long.MIN_VALUE : Long.parseLong(p[7]));
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void append(Row r) {
        Path f = file;
        if (f == null) return;
        String line = r.shownAt() + "\t" + (r.remote() ? 1 : 0) + "\t" + r.snap() + "\t" + r.snapAt() + "\t" + r.issuedAt()
                + "\t" + r.recvAt() + "\t" + r.delay() + "\t" + (r.offset() == Long.MIN_VALUE ? "-" : String.valueOf(r.offset())) + "\n";
        IO.execute(() -> {
            try {
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                boolean fresh = !Files.exists(f);
                if (!fresh && Files.size(f) > 256_000L) {
                    // keep the file small: rewrite it with the last KEEP rows
                    StringBuilder sb = new StringBuilder("shown_ms\tremote\tsnap\tsnap_at\tissued_at\trecv_at\tdelay_ms\toffset_ms\n");
                    List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                    for (int i = Math.max(1, lines.size() - KEEP); i < lines.size(); i++) sb.append(lines.get(i)).append('\n');
                    sb.append(line);
                    Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
                    Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                    Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
                Files.writeString(f, (fresh ? "shown_ms\tremote\tsnap\tsnap_at\tissued_at\trecv_at\tdelay_ms\toffset_ms\n" : "") + line,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });
    }
}
