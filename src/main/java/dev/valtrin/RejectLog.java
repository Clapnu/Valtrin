// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Listings that LOOKED profitable but did not alert, with the reason (a short code). Shown by /flip why and written to
 * ValtrinData/rejects.tsv (capped at 2 MB) so the filters can be tuned with real examples.
 */
final class RejectLog {
    private RejectLog() {}

    record Row(long ts, String uuid, String name, long price, long lbin, long median, int samples, String conf, String code) {}

    private static final int RING = 400;
    private static final long MAX_FILE = 2_000_000;
    private static final ArrayDeque<Row> ring = new ArrayDeque<>();
    private static final Set<String> logged = new HashSet<>();
    private static volatile Path file;
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Valtrin-rejects");
        t.setDaemon(true);
        return t;
    });

    static void init(Path f) { file = f; }

    static synchronized void add(Row r) {
        // one line per listing and reason (the same listing is judged again while it waits for volume)
        if (!logged.add(r.uuid() + r.code())) return;
        if (logged.size() > 20000) logged.clear();
        ring.addLast(r);
        while (ring.size() > RING) ring.pollFirst();
        Path f = file;
        if (f == null) return;
        String line = r.ts() + "\t" + r.uuid() + "\t" + r.name().replace('\t', ' ') + "\t" + r.price() + "\t" + r.lbin() + "\t"
                + r.median() + "\t" + r.samples() + "\t" + (r.conf() == null ? "-" : r.conf()) + "\t" + r.code() + "\n";
        WRITER.execute(() -> {
            try {
                if (f.getParent() != null) Files.createDirectories(f.getParent());
                if (Files.exists(f) && Files.size(f) > MAX_FILE) {
                    Files.move(f, f.resolveSibling("rejects.old.tsv"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                boolean fresh = !Files.exists(f);
                Files.writeString(f, (fresh ? "ts_ms\tuuid\tname\tprice\tlbin_value\tmedian_value\tsamples\tconf\treason\n" : "") + line,
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (Exception ignored) {}
        });
    }

    /** Newest first; {@code filter} (item name part, case-insensitive) may be empty. */
    static synchronized List<Row> last(String filter, int n) {
        String f = filter == null ? "" : filter.toLowerCase(Locale.ROOT).trim();
        List<Row> out = new ArrayList<>();
        java.util.Iterator<Row> it = ring.descendingIterator();
        while (it.hasNext() && out.size() < n) {
            Row r = it.next();
            if (f.isEmpty() || r.name().toLowerCase(Locale.ROOT).contains(f)) out.add(r);
        }
        return out;
    }

    /** Plain-words meaning of a reason code. */
    static String explain(String code, FlipConfig c) {
        return switch (code) {
            case "NO_EXACT_SALES" -> "no recorded sales of this exact item (same modifiers) yet";
            case "FEW_SAMPLES" -> "fewer than " + c.minMedianSamples + " exact sales (minsamples)";
            case "NO_LISTINGS" -> "fewer than " + c.minListings + " comparable listings to compare with";
            case "NO_REFERENCE" -> "no usable reference price";
            case "NOT_CHEAPER" -> "not cheaper than the reference price (" + c.referenceMode + " mode)";
            case "BELOW_PROFIT" -> "profit under your minprofit (" + Format.coins(c.minProfit) + ") in " + c.referenceMode + " mode";
            case "BELOW_MARGIN" -> "margin under your minmargin (" + c.minMarginPercent + "%) in " + c.referenceMode + " mode";
            case "ABOVE_MAXMARGIN" -> "margin above your maxmargin (looks too good to be true)";
            case "ABOVE_MAXPRICE" -> "price above your maxprice";
            case "LOW_CONF" -> "price confidence below your minconfidence (" + c.minConfidence + ")";
            case "LOW_VOLUME" -> "sells fewer than " + c.minVolume + " per day (minvolume)";
            case "LOADING" -> "sales volume not known yet (still warming up)";
            case "PET_OFF" -> "pets are switched off (includepets)";
            case "BLACKLIST" -> "item is on your blacklist";
            default -> code;
        };
    }
}
