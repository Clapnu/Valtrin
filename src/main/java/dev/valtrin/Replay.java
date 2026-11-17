// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Replays a recorded market (see {@link Recorder}) through the current pricing code and reports what it would have
 * alerted, so two versions can be compared on exactly the same data. Not used inside Minecraft.
 *
 * java -cp valtrin.jar;gson.jar dev.valtrin.Replay {replayFolder} {salesFolder} [out.csv]
 *
 * Limits (be honest about them): bazaar prices are today's, not those of the replayed hour; the sold-auction feed is
 * not part of the recording, so "gone within 10 min" (sold, expired or cancelled) is only a rough proxy for "would have sold".
 */
public final class Replay {
    private Replay() {}

    private record L(String uuid, long start, long price, String id, String tier, String base, String toks, String seller, ItemData d) {}

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("usage: Replay <replay folder> <sales folder> [out.csv]");
            return;
        }
        Path replay = Path.of(args[0]);
        Path out = Path.of(args.length > 2 ? args[2] : "replay-out.csv");
        OwnModel.init(Path.of(args[1]), null);
        Market.volume = new VolumeTracker();
        try {
            HypixelApi api = new HypixelApi();
            Pricing.loadBazaar(api.getV2("skyblock/bazaar"));
            Pricing.loadItems(api.getV2("resources/skyblock/items"));
        } catch (Exception e) {
            System.out.println("Warning: could not load bazaar / item prices (" + e + "); modifier values will be low.");
        }

        List<Path> files = new ArrayList<>();
        try (Stream<Path> s = Files.list(replay)) {
            s.filter(p -> p.getFileName().toString().endsWith(".tsv.gz")).sorted(Comparator.comparingLong(Replay::luOf)).forEach(files::add);
        }
        Map<String, L> state = new HashMap<>();
        Map<String, Long> alertedAt = new HashMap<>();      // uuid -> seen time of the alert
        Map<String, Long> goneAfter = new HashMap<>();      // uuid -> seconds until it left the list
        StringBuilder csv = new StringBuilder("time,item,price,sell,profit,confidence,samples,level,volume_per_day,gone_within_10min,gone_after_s\n");
        long day = -1;
        int snaps = 0, alerts = 0, gone = 0;
        long profitSum = 0;
        Map<String, Integer> byConf = new TreeMap<>();
        long firstSeen = 0, lastSeen = 0;
        List<String[]> pendingRows = new ArrayList<>();

        for (Path f : files) {
            long seenAt = 0;
            boolean key = false;
            List<L> added = new ArrayList<>();
            List<String> removed = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(new GZIPInputStream(Files.newInputStream(f)), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    String[] p = line.split("\t", -1);
                    switch (p[0]) {
                        case "H" -> { key = p[2].equals("K"); seenAt = Long.parseLong(p[3]); }
                        case "A" -> {
                            List<ModTokens.Tok> toks = ModTokens.decode(p[7]);
                            ItemData d = ModTokens.toItemData(p[4], p[6], p[5], toks);
                            added.add(new L(p[1], Long.parseLong(p[2]), Long.parseLong(p[3]), p[4], p[5], p[6], p[7], p[8], d));
                        }
                        case "R" -> removed.add(p[1]);
                        default -> {}
                    }
                }
            }
            if (firstSeen == 0) firstSeen = seenAt;
            lastSeen = seenAt;
            boolean baseline = state.isEmpty() || key && state.isEmpty();
            if (key) state.clear();
            for (String u : removed) {
                state.remove(u);
            }
            // who left the list within 10 minutes of being alerted
            for (Iterator<Map.Entry<String, Long>> it = alertedAt.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<String, Long> e = it.next();
                if (!state.containsKey(e.getKey()) && removed.contains(e.getKey())) {
                    long secs = (seenAt - e.getValue()) / 1000;
                    goneAfter.put(e.getKey(), secs);
                    it.remove();
                } else if (seenAt - e.getValue() > 600_000L) it.remove();
            }
            for (L l : added) state.put(l.uuid(), l);

            Clock.freeze(seenAt);
            long d = Math.floorDiv(seenAt, 86_400_000L);
            if (d != day) { OwnModel.clearCaches(); day = d; }
            List<Market.Row> rows = new ArrayList<>();
            for (L l : state.values()) rows.add(new Market.Row(l.price(), l.d()));
            Market.publishSnapshot(rows, seenAt);
            snaps++;
            if (baseline || key) continue; // keyframes only set the starting point

            FlipConfig cfg = FlipConfig.INSTANCE;
            // The live mod judges late items as soon as their sales are read; here we simply wait for the reads.
            Set<String> ids = new HashSet<>();
            for (L l : added) ids.add(l.id());
            OwnModel.preload(ids, 60_000);
            for (L l : added) {
                if (seenAt - l.start() > cfg.maxAgeSeconds * 1000L) continue;
                if (Pricing.isBazaar(l.id())) continue;
                Market.Estimate est = Market.estimate(l.d(), l.price());
                if (!Market.passes(est, l.d(), l.id(), l.price())) continue;
                OwnModel.Stats st = OwnModel.stats(l.d(), seenAt);
                String conf = st == null ? "?" : st.conf();
                byConf.merge(conf, 1, Integer::sum);
                alerts++;
                profitSum += est.profit();
                alertedAt.put(l.uuid(), seenAt);
                pendingRows.add(new String[]{l.uuid(), java.time.Instant.ofEpochMilli(seenAt).toString(), l.id(), String.valueOf(l.price()),
                        String.valueOf(est.sell()), String.valueOf(est.profit()), conf,
                        String.valueOf(st == null ? 0 : st.samples()), String.valueOf(st == null ? -1 : st.level()),
                        String.valueOf(Math.round(Market.volumeExact(l.d()) * 10) / 10.0)});
            }
        }
        for (String[] r : pendingRows) {
            Long g = goneAfter.get(r[0]);
            boolean isGone = g != null && g <= 600;
            if (isGone) gone++;
            csv.append(r[1]).append(',').append(r[2]).append(',').append(r[3]).append(',').append(r[4]).append(',').append(r[5]).append(',')
                    .append(r[6]).append(',').append(r[7]).append(',').append(r[8]).append(',').append(r[9]).append(',').append(isGone).append(',').append(g == null ? -1 : g).append('\n');
        }
        Files.writeString(out, csv.toString());
        double hours = Math.max(1.0 / 60, (lastSeen - firstSeen) / 3_600_000.0);
        System.out.println("Replayed " + snaps + " snapshots over " + String.format(Locale.ROOT, "%.1f", hours) + " hours");
        System.out.println("Alerts: " + alerts + " (" + String.format(Locale.ROOT, "%.1f", alerts / hours) + " per hour), estimated profit sum " + profitSum);
        System.out.println("By confidence: " + byConf);
        System.out.println("Left the list within 10 minutes: " + gone + " of " + alerts + " (rough proxy for sold)");
        System.out.println("Details: " + out.toAbsolutePath());
    }

    private static long luOf(Path p) {
        String n = p.getFileName().toString();
        try { return Long.parseLong(n.substring(0, n.length() - 7)); } catch (Exception e) { return 0; }
    }
}
