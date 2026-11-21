// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Your own flipping profit, worked out from the Auction House chat messages Hypixel sends you.
 * Every purchase is remembered; when you later collect coins for selling the same item, the oldest unsold purchase of
 * that item is matched to it (first in, first out) and the difference is the profit. The coins you collect already have
 * Hypixel's auction fees taken off, so the profit is after fees. Flips from any source count, not only Valtrin alerts.
 * Saved in ValtrinData/flip-stats.tsv (delete the file or use /flip profit reset to start over).
 */
public final class FlipStats {
    private FlipStats() {}

    // Amounts can be written in full ("25,700,000") or abbreviated ("25.7M", "1.2B", "850K").
    private static final String AMOUNT = "(\\d[\\d,]*(?:\\.\\d+)?[KMBkmb]?)";
    private static final Pattern BOUGHT = Pattern.compile("^You purchased (.+?) for " + AMOUNT + " coins!?");
    private static final Pattern COLLECTED = Pattern.compile("^You collected " + AMOUNT + " coins from selling (.+?) to .+? in an auction!?");

    private static final Pattern TRADE_DONE = Pattern.compile("^Trade completed with (.+?)!?$");
    private static final Pattern TRADE_LINE = Pattern.compile("^([+\\-\u2212])\\s*(.+)$");
    private static final Pattern COINS_ONLY = Pattern.compile("^" + AMOUNT + "\\s*coins?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_FRONT = Pattern.compile("^(\\d+)\\s*x\\s+(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COUNT_BACK = Pattern.compile("^(.+?)\\s+x\\s*(\\d+)$", Pattern.CASE_INSENSITIVE);

    // a trade being read: Hypixel lists what each side got in the lines after "Trade completed with ..."
    private static boolean tradeOpen = false;
    private static long tradeLastAt = 0;
    private static long tGaveCoins = 0, tGotCoins = 0;
    private static final List<Object[]> tGave = new ArrayList<>(); // {name, count}
    private static final List<Object[]> tGot = new ArrayList<>();

    public record Flip(long ts, String item, long cost, long got) {
        long profit() { return got - cost; }
    }

    public record Summary(int flips, long profit, long best, String bestItem, long worst, String worstItem, int wins,
                          int openCount, long openCost, int unmatchedSales, long cost) {
        /** Profit as a percentage of the coins spent buying these flips (0 when nothing was spent). */
        double profitPercent() { return cost > 0 ? 100.0 * profit / cost : 0; }
    }

    private static Path file;
    private static Path unmatchedFile;
    private static final List<Flip> flips = new ArrayList<>();
    private static final Map<String, ArrayDeque<long[]>> open = new HashMap<>();
    private static final Map<String, String> displayNames = new HashMap<>();
    /** Item key to rarity name (from the colour of the item's name in the chat line). Only kept while the game runs. */
    private static final Map<String, String> rarities = new HashMap<>();

    /** The rarity that goes with a chat colour (Hypixel's rarity colours), or null if it is not one of them. */
    static String rarityForRgb(int rgb) {
        switch (rgb & 0xFFFFFF) {
            case 0xFFFFFF: return "COMMON";
            case 0x55FF55: return "UNCOMMON";
            case 0x5555FF: return "RARE";
            case 0xAA00AA: return "EPIC";
            case 0xFFAA00: return "LEGENDARY";
            case 0xFF55FF: return "MYTHIC";
            case 0x55FFFF: return "DIVINE";
            case 0xFF5555: return "SPECIAL";
            case 0xAA0000: return "VERY_SPECIAL";
            default: return null;
        }
    }

    /**
     * Called with the pieces of a chat line (text and colour) just before {@link #onChat}. For a purchase line it
     * remembers the colour of the piece that holds the item name, which is the item's rarity.
     */
    public static synchronized void noteColors(java.util.List<String> texts, java.util.List<Integer> rgbs) {
        try {
            Matcher m = BOUGHT.matcher(String.join("", texts).strip());
            if (!m.find()) return;
            String name = m.group(1).strip();
            for (int i = 0; i < texts.size(); i++) {
                String t = texts.get(i).strip();
                Integer c = rgbs.get(i);
                if (c == null || t.length() < 3 || !name.contains(t)) continue;
                String r = rarityForRgb(c);
                if (r != null) { rarities.put(key(m.group(1)), r); return; }
            }
        } catch (RuntimeException ignored) {
            // a colour hint is a bonus only
        }
    }
    private static int unmatchedSales = 0;
    private static int unmatchedLogged = 0;
    private static boolean loading = false;

    public static synchronized void init(Path dataDir) {
        file = dataDir.resolve("flip-stats.tsv");
        unmatchedFile = dataDir.resolve("flip-stats-unmatched.txt");
        flips.clear();
        open.clear();
        unmatchedSales = 0;
        loading = true; // history read back from disk is never sent anywhere
        try {
            if (Files.exists(file)) {
                for (String l : Files.readAllLines(file)) {
                    String[] p = l.split("\t", 4);
                    if (p.length < 4) continue;
                    long ts = Long.parseLong(p[1]);
                    long amt = Long.parseLong(p[3]);
                    if (p[0].equals("P")) buy(ts, p[2], amt);
                    else if (p[0].equals("S")) sell(ts, p[2], amt);
                }
            }
        } catch (IOException | RuntimeException e) {
            // a damaged stats file only loses history; never break the game
        } finally {
            loading = false;
        }
    }

    /** Feed every chat line here (plain text, no colour codes). */
    public static synchronized void onChat(String line, long now) {
        if (line == null) return;
        String s = line.strip();
        if (tradeOpen) {
            Matcher t = TRADE_LINE.matcher(s);
            if (t.find()) {
                tradeLog(s);
                tradeLastAt = now;
                addTradeLine(t.group(1).equals("+"), t.group(2).strip());
                return;
            }
            finishTrade(now); // the lines after the trade are over
        }
        Matcher td = TRADE_DONE.matcher(s);
        if (td.find()) {
            tradeOpen = true;
            tradeLastAt = now;
            tGaveCoins = 0; tGotCoins = 0; tGave.clear(); tGot.clear();
            tradeLog("--- " + s);
            return;
        }
        Matcher m = BOUGHT.matcher(s);
        if (m.find()) {
            long cost = parseCoins(m.group(2));
            if (cost > 0) {
                buy(now, m.group(1), cost);
                append("P", now, m.group(1), cost);
            }
            return;
        }
        m = COLLECTED.matcher(s);
        if (m.find()) {
            long got = parseCoins(m.group(1));
            if (got > 0) {
                sell(now, m.group(2), got);
                append("S", now, m.group(2), got);
            }
            return;
        }
        // Auction-looking lines we did not understand: keep a few so the matching can be improved.
        String low = s.toLowerCase();
        if (unmatchedLogged < 200 && s.startsWith("You ") && (low.contains("auction") || low.startsWith("you purchased")) && unmatchedFile != null) {
            unmatchedLogged++;
            try {
                Files.writeString(unmatchedFile, s + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException ignored) {
            }
        }
    }

    private static void tradeLog(String s) {
        if (unmatchedFile == null) return;
        try {
            Files.writeString(unmatchedFile.resolveSibling("flip-stats-trades.txt"), s + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** Item counts come from chat, so an absurd number is clamped instead of throwing. */
    private static int safeCount(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return 64; }
    }

    private static void addTradeLine(boolean got, String body) {
        Matcher c = COINS_ONLY.matcher(body);
        if (c.find()) {
            long v = parseCoins(c.group(1));
            if (v > 0) { if (got) tGotCoins += v; else tGaveCoins += v; }
            return;
        }
        String name = body; int count = 1;
        Matcher f = COUNT_FRONT.matcher(body), b = COUNT_BACK.matcher(body);
        if (f.find()) { count = safeCount(f.group(1)); name = f.group(2).strip(); }
        else if (b.find()) { count = safeCount(b.group(2)); name = b.group(1).strip(); }
        (got ? tGot : tGave).add(new Object[]{name, Math.max(1, Math.min(64, count))});
    }

    /** Called every client tick: a trade whose lines have stopped arriving is counted. */
    public static synchronized void flushTrade(long now) {
        if (tradeOpen && now - tradeLastAt > 1500) finishTrade(now);
    }

    /**
     * A finished trade counts like auction flips: items you gave for coins are sales (the coins you got, minus any you
     * gave, shared over the items; trades have no fee), items you got for coins are purchases.
     */
    private static void finishTrade(long now) {
        tradeOpen = false;
        long net = tGotCoins - tGaveCoins;
        int gaveUnits = 0, gotUnits = 0;
        for (Object[] g : tGave) gaveUnits += (int) g[1];
        for (Object[] g : tGot) gotUnits += (int) g[1];
        if (gaveUnits > 0 && gotUnits == 0 && net > 0) {
            long each = net / gaveUnits;
            for (Object[] g : tGave) for (int i = 0; i < (int) g[1]; i++) {
                sell(now, (String) g[0], each);
                append("S", now, (String) g[0], each);
            }
        } else if (gotUnits > 0 && gaveUnits == 0 && net < 0) {
            long each = (-net) / gotUnits;
            for (Object[] g : tGot) for (int i = 0; i < (int) g[1]; i++) {
                buy(now, (String) g[0], each);
                append("P", now, (String) g[0], each);
            }
        } else {
            tradeLog("(not counted: items for items, or no coins)");
        }
        tGave.clear(); tGot.clear();
    }

    private static void buy(long ts, String item, long cost) {
        String k = key(item);
        displayNames.put(k, item);
        open.computeIfAbsent(k, x -> new ArrayDeque<>()).addLast(new long[]{ts, cost});
    }

    private static void sell(long ts, String item, long got) {
        String k = key(item);
        ArrayDeque<long[]> q = open.get(k);
        if (q == null || q.isEmpty()) {
            unmatchedSales++;
            return;
        }
        long[] p = q.pollFirst();
        flips.add(new Flip(ts, displayNames.getOrDefault(k, item), p[1], got));
        if (!loading && onFlipDone != null) {
            try { onFlipDone.done(displayNames.getOrDefault(k, item), p[1], got, ts - p[0]); } catch (Throwable ignored) {}
        }
        if (!loading) StatsReport.queue(ts, displayNames.getOrDefault(k, item), p[1], got, rarities.get(k));
    }

    private static void append(String type, long ts, String item, long amount) {
        if (file == null) return;
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, type + "\t" + ts + "\t" + item.replace('\t', ' ') + "\t" + amount + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
        }
    }

    /** Told about every finished flip (an AH sale matched to its purchase): name, paid, got after fees, held for ms. */
    public interface FlipDone { void done(String item, long paid, long got, long heldMs); }
    /** Set by the mod to show "Flip done" in chat; null in tests and on the server. */
    public static volatile FlipDone onFlipDone;

    public static synchronized Summary summary(long sinceMs) {
        int n = 0, wins = 0;
        long total = 0, spent = 0, best = Long.MIN_VALUE, worst = Long.MAX_VALUE;
        String bestItem = "", worstItem = "";
        for (Flip f : flips) {
            if (f.ts() < sinceMs) continue;
            n++;
            long p = f.profit();
            total += p;
            spent += f.cost();
            if (p > 0) wins++;
            if (p > best) { best = p; bestItem = f.item(); }
            if (p < worst) { worst = p; worstItem = f.item(); }
        }
        int oc = 0;
        long ocost = 0;
        for (ArrayDeque<long[]> q : open.values()) for (long[] p : q) { oc++; ocost += p[1]; }
        return new Summary(n, total, n == 0 ? 0 : best, bestItem, n == 0 ? 0 : worst, worstItem, wins, oc, ocost, unmatchedSales, spent);
    }

    /** One finished-flips report for /flip profit. daily = {epochDay, profit, flips} oldest first; weekly = {epochDay of that Monday, profit}. */
    public record Report(int flips, long profit, long spent, int wins, String bestItem, long bestProfit, long bestCost, long bestGot,
                         long firstTs, java.util.List<long[]> daily, java.util.List<long[]> weekly,
                         long bestDayEpoch, long bestDayProfit, int openCount, long openCost) {}

    /**
     * Flips collected in the past {@code days} x 24 hours counted back from now (a rolling window, not calendar days), or
     * all of them when {@code days} is 0. The per-day breakdown still groups by calendar day (local time). Profit is after Hypixel's fees (see the class comment).
     */
    public static synchronized Report report(int days) {
        return build(days, days > 0 ? System.currentTimeMillis() - days * 86_400_000L : Long.MIN_VALUE);
    }

    /** Writes every finished flip to a CSV file (time, item, cost, coins received, profit). Returns how many rows. */
    public static synchronized int exportCsv(Path out) throws IOException {
        StringBuilder sb = new StringBuilder("time,item,cost,received,profit\n");
        java.time.format.DateTimeFormatter fmt = java.time.format.DateTimeFormatter.ISO_LOCAL_DATE_TIME;
        for (Flip f : flips) {
            String item = f.item().replace("\"", "\"\"");
            // a cell that starts with = + - @ could be run as a formula by a spreadsheet: keep it plain text
            if (!item.isEmpty() && "=+-@".indexOf(item.charAt(0)) >= 0) item = "'" + item;
            sb.append(fmt.format(java.time.Instant.ofEpochMilli(f.ts()).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()))
              .append(",\"").append(item).append("\",").append(f.cost()).append(',').append(f.got()).append(',').append(f.profit()).append('\n');
        }
        Files.createDirectories(out.getParent());
        Files.writeString(out, sb.toString(), java.nio.charset.StandardCharsets.UTF_8);
        return flips.size();
    }

    /** When this game session started (the mod loaded). */
    static final long SESSION_START = System.currentTimeMillis();

    /** Flips collected since this game session started. */
    public static synchronized Report sessionReport() {
        return build(1, SESSION_START);
    }

    private static Report build(int days, long cutoffMs) {
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        java.time.LocalDate today = java.time.LocalDate.now(zone);
        long fromEpochDay = days > 0 ? java.time.Instant.ofEpochMilli(cutoffMs).atZone(zone).toLocalDate().toEpochDay() : Long.MIN_VALUE;
        java.util.TreeMap<Long, long[]> byDay = new java.util.TreeMap<>();
        int n = 0, wins = 0;
        long total = 0, spent = 0, best = Long.MIN_VALUE, bestCost = 0, bestGot = 0, first = 0;
        String bestItem = "";
        for (Flip f : flips) {
            long day = java.time.Instant.ofEpochMilli(f.ts()).atZone(zone).toLocalDate().toEpochDay();
            if (f.ts() < cutoffMs) continue;
            n++;
            long p = f.profit();
            total += p;
            spent += f.cost();
            if (p > 0) wins++;
            if (p > best) { best = p; bestItem = f.item(); bestCost = f.cost(); bestGot = f.got(); }
            if (first == 0 || f.ts() < first) first = f.ts();
            long[] d = byDay.computeIfAbsent(day, x -> new long[]{0, 0});
            d[0] += p;
            d[1]++;
        }
        java.util.List<long[]> daily = new ArrayList<>();
        if (days > 0) {
            for (long d = fromEpochDay; d <= today.toEpochDay(); d++) {
                long[] v = byDay.get(d);
                daily.add(new long[]{d, v == null ? 0 : v[0], v == null ? 0 : v[1]});
            }
        } else {
            for (java.util.Map.Entry<Long, long[]> e : byDay.entrySet()) daily.add(new long[]{e.getKey(), e.getValue()[0], e.getValue()[1]});
        }
        java.util.TreeMap<Long, Long> weeks = new java.util.TreeMap<>();
        long bestDay = 0, bestDayProfit = Long.MIN_VALUE;
        for (java.util.Map.Entry<Long, long[]> e : byDay.entrySet()) {
            long monday = java.time.LocalDate.ofEpochDay(e.getKey()).with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay();
            weeks.merge(monday, e.getValue()[0], Long::sum);
            if (e.getValue()[0] > bestDayProfit) { bestDayProfit = e.getValue()[0]; bestDay = e.getKey(); }
        }
        java.util.List<long[]> weekly = new ArrayList<>();
        if (days == 0 && !weeks.isEmpty()) {
            long firstWeek = weeks.firstKey(), lastWeek = weeks.lastKey();
            long startWeek = Math.max(firstWeek, lastWeek - 7 * 7); // at most 8 weeks
            for (long w = startWeek; w <= lastWeek; w += 7) weekly.add(new long[]{w, weeks.getOrDefault(w, 0L)});
        }
        int oc = 0;
        long ocost = 0;
        for (ArrayDeque<long[]> q : open.values()) for (long[] p : q) { oc++; ocost += p[1]; }
        return new Report(n, total, spent, wins, bestItem, n == 0 ? 0 : best, bestCost, bestGot, first, daily, weekly,
                bestDay, bestDayProfit == Long.MIN_VALUE ? 0 : bestDayProfit, oc, ocost);
    }

    /** ▁▂▃▄▅▆▇█ bars, one per value, scaled to the biggest positive value (zero or negative = the lowest bar). */
    static String spark(java.util.List<long[]> rows, int col) {
        String bars = "▁▂▃▄▅▆▇█";
        long max = 0;
        for (long[] r : rows) max = Math.max(max, r[col]);
        StringBuilder sb = new StringBuilder();
        for (long[] r : rows) {
            long v = r[col];
            int idx = max <= 0 || v <= 0 ? 0 : (int) Math.max(0, Math.min(7, Math.round(v * 7.0 / max)));
            sb.append(bars.charAt(idx));
        }
        return sb.toString();
    }

    public static synchronized void reset() {
        flips.clear();
        open.clear();
        unmatchedSales = 0;
        try {
            if (file != null) Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    static String key(String item) {
        return item.replaceAll("^\\[Lvl \\d+\\]\\s*", "").toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    /** "5,000,000", "25.7M", "1.2B", "850K" -> coins (abbreviated amounts are rounded by Hypixel, so they are approximate). */
    static long parseCoins(String s) {
        try {
            String t = s.replace(",", "").trim();
            if (t.isEmpty()) return -1;
            long mult = 1;
            char last = Character.toUpperCase(t.charAt(t.length() - 1));
            if (last == 'K') mult = 1_000L;
            else if (last == 'M') mult = 1_000_000L;
            else if (last == 'B') mult = 1_000_000_000L;
            if (mult != 1) t = t.substring(0, t.length() - 1);
            if (t.length() > 15) return -1;
            return Math.round(Double.parseDouble(t) * mult);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
