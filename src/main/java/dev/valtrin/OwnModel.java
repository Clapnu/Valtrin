// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pricing from this mod's own recorded sales and the current listings: items are compared with sales and
 * listings that have the same IMPORTANT modifiers. If too few match, the least valuable modifier is dropped from the comparison (and its
 * coin value is added back) step by step, finally falling back to the plain rarity/stars group.
 */
final class OwnModel {
    private OwnModel() {}

    interface Cand {
        String base();
        String tier();
        List<ModTokens.Tok> toks();
        long price();
        /** Token-set version this sale was recorded with (listings are always current). */
        default int schema() { return ModTokens.SCHEMA; }
    }

    record Lst(long price, String tier, String base, List<ModTokens.Tok> toks) implements Cand {}

    /** conf = "HIGH" / "MED" / "LOW" (see {@link #confidence}); relIqr = (Q75 - Q25) / median of the sales used; trimmed = outliers left out. */
    record Stats(long median, long mean, int samples, int level, int levels, double perDay, int windowDays,
                 String conf, double relIqr, int trimmed, long p25, boolean cautious, long min, long max, long recent) {
        /**
         * The value to compare prices with: the median, or the 25th percentile when the data is thin / loosely matched,
         * and never more than the median of the latest exact sales (recent = -1 when there are fewer than 3 of those):
         * if the price has just dropped, the older sales must not make a listing look like a flip.
         */
        long value() {
            long v = cautious ? Math.min(median, p25) : median;
            return (recent > 0 && recent < v) ? recent : v;
        }
    }

    /** Look-back windows (days), shortest first. Must not exceed SaleLog.LOAD_DAYS. */
    private static final int[] WINDOWS_DAYS = {1, 2, 4, 7, 14};
    /** Exact-match sales wanted before a window is accepted. */
    private static final int TARGET_SAMPLES = 10;
    record Lbins(long lowest, long second, int count, int level, int levels) {}

    static SaleLog log;
    private static volatile Map<String, List<Lst>> listings = Map.of();
    private static volatile long listingsAt = 0;

    private record Cached<T>(T value, long at, long epoch) {}
    private static final Map<String, Cached<Stats>> STATS = new ConcurrentHashMap<>();
    private static final Map<String, Cached<Lbins>> LBINS = new ConcurrentHashMap<>();
    private static final long TTL_MS = 30_000;

    // ------------------------------------------------------------------ wiring

    /** @param oldDir where earlier versions kept the sales (moved to dir on first start if dir is empty) */
    static void init(Path dir, Path oldDir) {
        try {
            if (oldDir != null && java.nio.file.Files.isDirectory(oldDir)
                    && (!java.nio.file.Files.exists(dir) || isEmpty(dir))) {
                java.nio.file.Files.createDirectories(dir.getParent());
                if (java.nio.file.Files.exists(dir)) java.nio.file.Files.delete(dir);
                java.nio.file.Files.move(oldDir, dir);
            }
        } catch (Exception e) {
            Log.LOGGER.warn("Could not move old sales data: {}", e.toString());
        }
        localDir = dir;
        reconfigure();
    }

    private static Path localDir;

    /** (Re)creates the sales log: a shared recorder server if one is configured, else the local folder. */
    static synchronized void reconfigure() {
        FlipConfig cfg = FlipConfig.INSTANCE;
        SaleLog old = log;
        if (cfg.sharedUrl != null && !cfg.sharedUrl.isBlank() && Validate.serverUrl(cfg.sharedUrl) == null) {
            Log.LOGGER.warn("Shared server address refused (it must be https://): using local data");
            log = new SaleLog(localDir);
        } else if (cfg.sharedUrl != null && !cfg.sharedUrl.isBlank()) {
            log = new SaleLog(cfg.sharedUrl.trim(), cfg.sharedKey);
        } else {
            log = new SaleLog(localDir);
        }
        log.init();
        STATS.clear();
        if (old != null && !old.remote()) old.flush();
    }

    private static boolean isEmpty(Path dir) {
        try (var s = java.nio.file.Files.list(dir)) {
            return s.findAny().isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    /** True while the stand-alone recorder is running; then the mod only reads and does not record sales itself. */
    static boolean recorderActive() {
        return log != null && log.recorderActive();
    }

    static void recordSale(ItemData d, long ts, long price) {
        if (log == null || recorderActive() || d == ItemData.NONE || d.tier() == null || d.cosmetic() || price <= 0) return;
        if (Pricing.isBazaar(d.id())) return;
        log.add(d.id(), ts, price, d.tier(), d.base(), ModTokens.of(d));
    }

    static void markCovered(long updatedMillis) {
        if (log != null && !recorderActive()) log.markCovered(updatedMillis / 60_000L);
    }

    static void flush() {
        if (log != null) log.flush();
    }

    static void setListings(Map<String, List<Lst>> byId, long now) {
        listings = byId;
        listingsAt = now;
        LBINS.clear();
    }

    /**
     * Adds just-seen listings to the current listing table WITHOUT rebuilding it from all ~40,000 listings (fast path:
     * new listings are judged the moment page 0 arrives, before the other pages are downloaded). Listings that already
     * ended are still in the table until the next full snapshot, which can only make the lowest BIN lower
     * (= a more careful profit estimate), never produce a fake flip.
     */
    static synchronized void addListings(Map<String, List<Lst>> add, long now) {
        Map<String, List<Lst>> next = new java.util.HashMap<>(listings);
        for (Map.Entry<String, List<Lst>> e : add.entrySet()) {
            List<Lst> merged = new java.util.ArrayList<>(next.getOrDefault(e.getKey(), List.of()));
            merged.addAll(e.getValue());
            next.put(e.getKey(), merged);
        }
        listings = next;
        listingsAt = Math.max(now, listingsAt + 1); // a new epoch, so cached lowest-BIN answers are not reused
        LBINS.clear();
    }

    /** True while this item's sales are still being read from disk / the shared server (a read is started). */
    static boolean loading(ItemData d) {
        return log != null && d.tier() != null && !log.isLoaded(d.id()) && log.peek(d.id()) == null;
    }

    /** True when this item's sales are in memory (or there is no sales log at all). */
    static boolean ready(String id) {
        return log == null || log.isLoaded(id);
    }

    /** Starts reading the sales of these item ids in the background (no waiting) so later judging finds them ready. */
    static void warm(java.util.Collection<String> ids) {
        if (log == null) return;
        for (String id : ids) if (!log.isLoaded(id)) log.peek(id);
    }

    /** Forgets all cached statistics and loaded sales (used by the replay tool when the replayed day changes). */
    static void clearCaches() {
        STATS.clear();
        LBINS.clear();
        if (log != null) log.dropCache();
    }

    /** Keeps these items' sales in memory (never evicted) and starts reading any that are missing. */
    static void pin(java.util.Collection<String> ids) {
        if (log == null) return;
        log.pin(new java.util.HashSet<>(ids));
        warm(ids);
    }

    /** Starts reading the sales of these item ids and waits (up to maxMs) until they are ready. */
    static void preload(java.util.Collection<String> ids, long maxMs) {
        if (log == null) return;
        long deadline = System.currentTimeMillis() + maxMs; // real time: the model clock may be frozen by the replay tool
        for (String id : ids) log.peek(id);
        while (System.currentTimeMillis() < deadline) {
            boolean all = true;
            for (String id : ids) if (!log.isLoaded(id)) { all = false; break; }
            if (all) return;
            try { Thread.sleep(50); } catch (InterruptedException e) { return; }
        }
    }

    static String status() {
        if (log == null) return "off";
        if (log.remote()) return "shared server " + log.remoteUrl() + (log.remoteError.isEmpty() ? " (ok)" : " (" + log.remoteError + ")");
        return (log.recorderActive() ? "recorder running, " : "") + log.storedSales + " sales stored this session, " + log.coveredMinutes(Clock.now(), 7) / 60 + "h of 7d watched";
    }

    // ------------------------------------------------------------------ matching

    /** Modifiers that are never loosened (a different Etherwarp / attribute state is a different item, not a price adjustment). */
    private static boolean strict(String family) {
        return family.startsWith("x:ATTR_") || family.equals("x:ETHERWARP_MERGER") || family.equals("x:" + ItemData.FLOOR_TIER);
    }

    /** The rune type + tier tokens of an item (empty if none). */
    private static String runeKey(List<ModTokens.Tok> toks) {
        StringBuilder sb = new StringBuilder();
        for (ModTokens.Tok t : toks) if (t.family().startsWith("x:RUNE_")) sb.append(t.text()).append(';');
        return sb.toString();
    }

    private static String strictKey(List<ModTokens.Tok> toks) {
        StringBuilder sb = new StringBuilder();
        for (ModTokens.Tok t : toks) if (strict(t.family())) sb.append(t.text()).append(';');
        return sb.toString();
    }

    /**
     * How many modifiers may be ignored when looking for comparable sales. 0 = only exact matches (decided 2026-11-12: the
     * backtest showed level 1 over-estimating by more than 50% in 3-15% of cases and level 2 being almost useless; a flip
     * with no exact comparable sales simply gets no estimate). Raise it only after re-testing with tools/backtest.
     */
    static final int MAX_RELAX = 0;

    /** Only adjust for cheap modifiers when they differ by at least this much in total. */
    private static final long MINOR_ADJUST_MIN = 300_000;

    private static boolean important(ModTokens.Tok t, String id) {
        return ModTokens.value(t, id) >= ModTokens.importantMin(t, id);
    }

    /** Adjusted prices (sorted) of the best-matching group, and which relaxation level it took. */
    /** recent = median of the (up to) RECENT_N newest comparable sales, or -1 when fewer than 3 are available. */
    private record Match(long[] adj, int level, int levels, long recent) {}

    /**
     * @param skipOnePrice if >= 0, one candidate with exactly this price and the same modifiers is left out (the item itself)
     */
    private static Match match(ItemData d, List<? extends Cand> cands, int minCount, long skipOnePrice) {
        String id = d.id();
        List<ModTokens.Tok> dTok = ModTokens.of(d);
        Map<String, ModTokens.Tok> dImp = new HashMap<>();
        Map<String, ModTokens.Tok> dAll = new HashMap<>();
        for (ModTokens.Tok t : dTok) {
            dAll.put(t.family(), t);
            if (important(t, id)) dImp.put(t.family(), t);
        }
        // families sorted by value, cheapest first = dropped first
        // Attribute levels can't be loosened: a different attribute is a different item.
        List<String> order = new ArrayList<>();
        for (String fam : dImp.keySet()) if (!strict(fam)) order.add(fam);
        order.sort((a, b) -> Long.compare(ModTokens.value(dImp.get(a), id), ModTokens.value(dImp.get(b), id)));
        int n = order.size();

        List<Cand> base = new ArrayList<>();
        boolean skipped = skipOnePrice < 0;
        String dEnc = ModTokens.encode(dTok);
        // Rune items: every rune type and tier is its own item (Golden Carpet III is not Flower Carpet III).
        String dRune = id.endsWith("_RUNE") || id.equals("RUNE") ? runeKey(dTok) : null;
        for (Cand c : cands) {
            if (!c.base().equals(d.base()) || !c.tier().equals(d.tier())) continue;
            if (dRune != null && !runeKey(c.toks()).equals(dRune)) continue;
            if (!skipped && c.price() == skipOnePrice && ModTokens.encode(c.toks()).equals(dEnc)) { skipped = true; continue; }
            base.add(c);
        }
        if (base.isEmpty()) return null;
        List<Map<String, ModTokens.Tok>> maps = new ArrayList<>(base.size());
        for (Cand c : base) {
            Map<String, ModTokens.Tok> cm = new HashMap<>();
            for (ModTokens.Tok t : c.toks()) cm.put(t.family(), t);
            maps.add(cm);
        }

        // Many cheap modifiers (each under the importance cutoff) still add up: adjust by half of the difference.
        long dMinor = 0;
        for (ModTokens.Tok t : dTok) if (!important(t, id)) dMinor += ModTokens.value(t, id);

        for (int k = 0; k <= Math.min(n, MAX_RELAX); k++) {
            Set<String> dropped = new HashSet<>(order.subList(0, k));
            long addBack = 0;
            for (String f : dropped) addBack += ModTokens.value(dAll.get(f), id);
            long[] adj = new long[base.size()];
            long[] adjTs = new long[base.size()];
            int cnt = 0;
            for (int ci = 0; ci < base.size(); ci++) {
                Cand c = base.get(ci);
                Map<String, ModTokens.Tok> cm = maps.get(ci);
                boolean ok = true;
                long a = c.price() + addBack;
                long cMinor = 0;
                for (ModTokens.Tok t : c.toks()) {
                    if (dropped.contains(t.family())) { a -= ModTokens.value(t, id); continue; }
                    if (!important(t, id)) { cMinor += ModTokens.value(t, id); continue; }
                    ModTokens.Tok want = dImp.get(t.family());
                    if (want == null || !want.variant().equals(t.variant())) { ok = false; break; }
                }
                if (ok) {
                    for (String f : dImp.keySet()) {
                        if (dropped.contains(f)) continue;
                        ModTokens.Tok have = cm.get(f);
                        if (have == null || !have.variant().equals(dImp.get(f).variant())) { ok = false; break; }
                    }
                }
                if (ok) {
                    long diff = dMinor - cMinor;
                    if (Math.abs(diff) >= MINOR_ADJUST_MIN) a += diff / 2;
                }
                if (ok && a > 0 && a >= c.price() / 10) { adjTs[cnt] = c instanceof SaleLog.Sale sl ? sl.ts() : 0; adj[cnt++] = a; }
            }
            if (cnt >= minCount) {
                long recent = recentMedian(adj, adjTs, cnt);
                long[] r = Arrays.copyOf(adj, cnt);
                Arrays.sort(r);
                return new Match(r, k, n + 1, recent);
            }
        }
        if (MAX_RELAX == 0) return null; // no loosening at all: no exact match, no price (so no guessed flips)
        // last resort: everyone of the same rarity/stars, with all modifier values removed and this item's added.
        // Etherwarp and attributes are never averaged away: only items with exactly the same ones take part.
        String dStrict = strictKey(dTok);
        long dAllValue = 0;
        for (ModTokens.Tok t : dTok) dAllValue += ModTokens.value(t, id);
        long[] adj = new long[base.size()];
        int cnt = 0;
        for (Cand c : base) {
            if (!strictKey(c.toks()).equals(dStrict)) continue;
            long mods = 0;
            for (ModTokens.Tok t : c.toks()) mods += ModTokens.value(t, id);
            long a = c.price() - mods + dAllValue;
            if (a > 0 && a >= c.price() / 10) adj[cnt++] = a;
        }
        if (cnt < Math.min(minCount, 3)) return null; // never trust a single sale: at least 3 (or minCount if lower)
        long[] r = Arrays.copyOf(adj, cnt);
        Arrays.sort(r);
        return new Match(r, n + 1, n + 1, -1);
    }

    /** How many of the newest exact sales make up the short-term median (shown in the tooltip as "Last N sales"). */
    static final int RECENT_N = 3;

    /** Median of the RECENT_N newest (by time) comparable sales, ignoring junk under 5% of the overall median; -1 if fewer than 3 remain. */
    private static long recentMedian(long[] adj, long[] ts, int cnt) {
        if (cnt < 3) return -1;
        long[] all = Arrays.copyOf(adj, cnt);
        Arrays.sort(all);
        long floor = (long) (median(all) * 0.05);
        Integer[] idx = new Integer[cnt];
        for (int i = 0; i < cnt; i++) idx[i] = i;
        Arrays.sort(idx, (x, y) -> Long.compare(ts[y], ts[x])); // newest first
        long[] last = new long[Math.min(RECENT_N, cnt)];
        int m = 0;
        for (int i = 0; i < cnt && m < last.length; i++) {
            if (ts[idx[i]] <= 0) return -1; // listings carry no time; only recorded sales count
            if (adj[idx[i]] >= floor) last[m++] = adj[idx[i]];
        }
        if (m < 3) return -1;
        long[] l = Arrays.copyOf(last, m);
        Arrays.sort(l);
        return median(l);
    }

    // ------------------------------------------------------------------ public queries

    private static String keyOf(ItemData d) {
        return d.base() + "|" + d.tier() + "|" + ModTokens.encode(ModTokens.of(d));
    }

    /** Median / volume of sales of this kind of item (window of 1-14 days depending on how often it sells), or null while data is still loading or missing. */
    static Stats stats(ItemData d, long now) {
        if (log == null || d.tier() == null || d == ItemData.NONE) return null;
        long epoch = Pricing.bazaarAt;
        String k = keyOf(d);
        Cached<Stats> c = STATS.get(k);
        if (c != null && now - c.at() < TTL_MS && c.epoch() == epoch) return c.value();

        List<SaleLog.Sale> all = log.peek(d.id());
        if (all == null) return null;
        // Adaptive window: busy items use the shortest window that holds enough exact matches (reacts fast to price
        // changes); rarely sold items keep widening, up to the longest window, to gather enough sales.
        Stats out = null;
        int minSamples = Math.max(3, FlipConfig.INSTANCE.minMedianSamples);
        // Sales recorded with the current token set are preferred: older ones may be missing newer modifiers.
        // They are used for the price when there are at least 5 exact current matches; volume always counts everything.
        Found modern = find(d, all, now, minSamples, true);
        Found every = find(d, all, now, minSamples, false);
        Found f = (modern != null && modern.m().level() == 0 && modern.m().adj().length >= 5) ? modern : every;
        Match m = f == null ? null : f.m();
        int usedDays = f == null ? WINDOWS_DAYS[WINDOWS_DAYS.length - 1] : f.days();
        if (m != null) {
            long[] all0 = m.adj();
            int n0 = all0.length;
            long[] a = FlipConfig.INSTANCE.trimOutliers ? trim(all0, m.level() == 0) : all0;
            int n = a.length;
            long median = median(a);
            long sum = 0;
            for (long v : a) sum += v;
            Found vf = every != null ? every : f;
            int vDays = vf.days();
            int vCount = junkFree(vf.m().adj());
            long covered = log.coveredMinutes(now, vDays);
            // Sales per day uses matched sales above the junk floor, old and new format.
            double perDay = covered < Math.max(240, FlipConfig.INSTANCE.volumeWarmupMinutes) ? -1 : vCount / Math.max(covered / 1440.0, 1.0 / 1440);
            double relIqr = median > 0 && n >= 4 ? (a[(n * 3) / 4] - a[n / 4]) / (double) median : 0;
            long p25 = a[(n - 1) / 4];
            boolean cautious = m.level() >= 2 || n < 10;
            out = new Stats(median, sum / n, n, m.level(), m.levels(), perDay, usedDays,
                    confidence(n, m.level(), relIqr), relIqr, n0 - n, p25, cautious, a[0], a[n - 1], m.recent());
        }
        if (STATS.size() > 20000) STATS.clear();
        STATS.put(k, new Cached<>(out, now, epoch));
        return out;
    }

    private record Found(Match m, int days) {}

    /** The best window / relaxation level for this item, optionally using only sales of the current token-set version. */
    private static Found find(ItemData d, List<SaleLog.Sale> all, long now, int minSamples, boolean modernOnly) {
        Match m = null;
        int usedDays = WINDOWS_DAYS[WINDOWS_DAYS.length - 1];
        for (int w : WINDOWS_DAYS) {
            long cutoff = now - w * 24L * 3_600_000L;
            List<SaleLog.Sale> recent = new ArrayList<>();
            for (SaleLog.Sale s : all) {
                if (s.ts() < cutoff || (Clock.replaying() && s.ts() > now)) continue;
                if (modernOnly && s.schema() < ModTokens.SCHEMA) continue;
                recent.add(s);
            }
            recent = dedupeBursts(recent);
            if (recent.isEmpty()) continue;
            Match cand = match(d, recent, minSamples, -1);
            if (cand == null || cand.adj().length == 0) continue;
            m = cand;
            usedDays = w;
            if (cand.level() == 0 && cand.adj().length >= TARGET_SAMPLES) break; // exact matches, plenty of them
        }
        return m == null ? null : new Found(m, usedDays);
    }

    /** One seller dumping the same item at the same price within two minutes (or a sale feed repeat) counts once. */
    static List<SaleLog.Sale> dedupeBursts(List<SaleLog.Sale> in) {
        List<SaleLog.Sale> sorted = new ArrayList<>(in);
        sorted.sort(Comparator.comparingLong(SaleLog.Sale::ts));
        Map<String, Long> last = new HashMap<>();
        List<SaleLog.Sale> out = new ArrayList<>(sorted.size());
        for (SaleLog.Sale x : sorted) {
            String key = x.price() + "|" + x.tier() + "|" + x.base() + "|" + ModTokens.encode(x.toks());
            Long t = last.get(key);
            last.put(key, x.ts());
            if (t != null && x.ts() - t <= 120_000L) continue;
            out.add(x);
        }
        return out;
    }

    /** Number of sales above the 5%-of-median junk floor. */
    static int junkFree(long[] a) {
        if (a.length == 0) return 0;
        long floor = (long) (median(a) * 0.05);
        int c = 0;
        for (long v : a) if (v >= floor) c++;
        return c;
    }

    private static long median(long[] a) {
        int n = a.length;
        return n % 2 == 1 ? a[n / 2] : (a[n / 2 - 1] + a[n / 2]) / 2;
    }

    /**
     * Tiered outlier trim. {@code exact} = the sample is made of identical-modifier sales (level 0).
     * Fixed junk floor at every size: under 5% of the median is junk (1-coin transfers, alt trades).
     * Exact sales: under 6 = nothing more; 6-11 = junk only (below 0.15x / above 6x); 12-29 = below 0.25x / above 4x;
     * 30+ = below 0.3x / above 3x. Loosened (non-exact) samples only get the junk floor. With 20+ kept, the lowest and
     * highest 10% go too. Never trims below 3 sales. Input and output are sorted ascending.
     */
    static long[] trim(long[] a, boolean exact) {
        int n = a.length;
        if (n < 3) return a;
        long med = median(a);
        double lo = 0.05, hi = Double.MAX_VALUE;
        if (exact) {
            if (n >= 30) { lo = 0.3; hi = 3; }
            else if (n >= 12) { lo = 0.25; hi = 4; }
            else if (n >= 6) { lo = 0.15; hi = 6; }
        }
        int from = 0, to = n;
        while (from < to && a[from] < med * lo) from++;
        while (to > from && a[to - 1] > med * hi) to--;
        if (to - from >= 20) {
            int cut = (to - from) / 10;
            from += cut;
            to -= cut;
        }
        if (to - from < 3) return a;
        return java.util.Arrays.copyOfRange(a, from, to);
    }

    static long[] trim(long[] a) { return trim(a, true); }

    /** HIGH = enough exact-modifier sales that agree with each other; LOW = few, loosely matched or widely spread. */
    static String confidence(int n, int level, double relIqr) {
        if (n < 5 || level > 1 || relIqr > 0.6) return "LOW";
        if (n >= 15 && level == 0 && relIqr < 0.25) return "HIGH";
        return "MED";
    }

    static int confRank(String conf) {
        return switch (conf == null ? "" : conf) {
            case "HIGH" -> 3;
            case "MED" -> 2;
            case "LOW" -> 1;
            default -> 0;
        };
    }

    /**
     * How many current listings of this kind of item (same modifiers, value-adjusted) are priced at or below {@code price},
     * so how many sales come first when a new listing is made at that price. -1 when the listing table is empty.
     * @param skipOnePrice if >= 0, one listing with exactly this price is not counted (the flip you are about to buy)
     */
    static int listingsAtOrBelow(ItemData d, long price, long skipOnePrice) {
        if (d.tier() == null || d == ItemData.NONE || listingsAt == 0) return -1;
        List<Lst> l = listings.get(d.id());
        if (l == null) return 0;
        Match m = match(d, l, 1, skipOnePrice);
        if (m == null) return 0;
        int n = 0;
        for (long a : m.adj()) if (a <= price) n++;
        return n;
    }

    /** Lowest and second-lowest current listing of this kind of item (value-adjusted when relaxed). */
    static Lbins lbins(ItemData d, long excludePrice, int minCount) {
        if (d.tier() == null || d == ItemData.NONE || listingsAt == 0) return null;
        long now = Clock.now();
        String k = keyOf(d) + "|" + excludePrice + "|" + minCount;
        Cached<Lbins> c = LBINS.get(k);
        if (c != null && c.epoch() == listingsAt) return c.value();
        List<Lst> l = listings.get(d.id());
        Lbins out = null;
        if (l != null) {
            Match m = match(d, l, minCount, excludePrice);
            if (m != null && m.adj().length > 0) {
                long[] a = m.adj();
                out = new Lbins(a[0], a.length > 1 ? a[1] : -1, a.length, m.level(), m.levels());
            }
        }
        if (LBINS.size() > 20000) LBINS.clear();
        LBINS.put(k, new Cached<>(out, now, listingsAt));
        return out;
    }
}
