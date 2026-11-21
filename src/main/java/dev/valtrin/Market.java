// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.Locale;
import java.util.Map;

/**
 * Latest snapshot of every BIN price on the auction house, shared between the alert engine
 * (background thread) and the tooltip/highlight code (render thread), plus the value logic.
 *
 * An item is only compared with items that have the same key (id + stars + recombobulated +
 * rarity, see {@link ItemData#key()}). Within that group:
 *   plain price  = what a comparable listing / sale cost after removing its modifiers
 *   item value   = plain price + modifier value of THIS item (enchants, gems, slots, scrolls...)
 * "Median" mode uses the 7-day median sold price of the same group as the plain price instead.
 */
final class Market {
    private static final int PINNED_IDS = Math.max(50, Math.min(50_000, Integer.getInteger("valtrin.pinnedIds", 250)));

    private Market() {}

    /** group key -> raw BIN prices, ascending. */
    static volatile Map<String, long[]> prices = Map.of();
    /** group key -> BIN prices with each listing's modifier value removed, ascending. */
    static volatile Map<String, long[]> norm = Map.of();
    /** item id -> cheapest BIN of that id (any rarity/stars), used to price scrolls and similar. */
    static volatile Map<String, Long> lowestIds = Map.of();
    static volatile long updatedAt = 0;
    /** Market data older than this is not shown: after a few minutes without a refresh (for example while the Valtrin server delivers the alerts and this computer is not searching Hypixel) old prices would mislead. */
    static final long FRESH_MS = 5 * 60_000L;

    /** Set by the mod: true while the Valtrin server delivers the alerts. The server program itself never sets it (always false). */
    static volatile java.util.function.BooleanSupplier serverMode = () -> false;

    static boolean fresh() {
        return updatedAt > 0 && Clock.now() - updatedAt < FRESH_MS;
    }
    static volatile VolumeTracker volume;

    /** All numbers behind one valuation. A value of -1 means "not available". */
    record Estimate(long lbinSell, long medianSell, long sell, Pricing.ModValue mv, long mod,
                    long floor, int others, int samples, long profit, double margin) {}

    /** One listing of a snapshot with its parsed item. */
    record Row(long price, ItemData d) {}

    /**
     * Makes a snapshot of all listings the market the estimates are based on: raw and modifier-free prices per group,
     * lowest price per item, and the listings used for matching. Shared by the live engine and the replay tool.
     */
    static void publishSnapshot(java.util.Collection<Row> rows, long now) {
        double f = FlipConfig.INSTANCE.modValuePercent / 100.0;
        Map<String, java.util.List<Long>> rawBy = new java.util.HashMap<>();
        Map<String, java.util.List<Long>> normBy = new java.util.HashMap<>();
        Map<String, Long> lowest = new java.util.HashMap<>();
        Map<String, java.util.List<OwnModel.Lst>> byId = new java.util.HashMap<>();
        for (Row r : rows) {
            ItemData d = r.d();
            if (d == ItemData.NONE || d.cosmetic()) continue; // dyed/skinned listings don't count as comparable prices
            String group = d.key();
            rawBy.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(r.price());
            lowest.merge(d.id(), r.price(), Math::min);
            long plain = plain(r.price(), Math.round(Pricing.modifierValue(d).total() * f));
            if (plain > 0) normBy.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(plain);
            if (d.tier() != null && !Pricing.isBazaar(d.id())) {
                byId.computeIfAbsent(d.id(), k -> new java.util.ArrayList<>()).add(new OwnModel.Lst(r.price(), d.tier(), d.base(), ModTokens.of(d)));
            }
        }
        OwnModel.setListings(byId, now);
        // Keep the sales of the most listed items in memory so new listings can be judged without waiting for disk.
        // (PINNED_IDS: 250 in the mod; the server, which has memory to spare, keeps far more, see ServerMain.)
        OwnModel.pin(byId.entrySet().stream()
                .sorted((x, y) -> Integer.compare(y.getValue().size(), x.getValue().size()))
                .limit(PINNED_IDS).map(Map.Entry::getKey).toList());
        prices = sorted(rawBy);
        norm = sorted(normBy);
        lowestIds = lowest;
        updatedAt = now;
    }

    /**
     * Fast path: makes just-seen listings count as comparable listings right away (only what the estimate of the next
     * listings needs: the per-item listing table). The full {@link #publishSnapshot} follows when all pages are in.
     */
    static void publishIncremental(java.util.Collection<Row> newRows, long now) {
        Map<String, java.util.List<OwnModel.Lst>> byId = new java.util.HashMap<>();
        for (Row r : newRows) {
            ItemData d = r.d();
            if (d == ItemData.NONE || d.cosmetic()) continue;
            if (d.tier() != null && !Pricing.isBazaar(d.id())) {
                byId.computeIfAbsent(d.id(), k -> new java.util.ArrayList<>()).add(new OwnModel.Lst(r.price(), d.tier(), d.base(), ModTokens.of(d)));
            }
        }
        if (!byId.isEmpty()) OwnModel.addListings(byId, now);
    }

    /**
     * Why a listing that LOOKS profitable (under the lowest BIN or the median alone) did not alert. A short code, or
     * null when it would pass (or does not look profitable at all). Mirrors {@link #check} in the same order.
     */
    static String rejectCode(Estimate e, ItemData d, String name, long price) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        if (e == null || price <= 0 || d.tier() == null) return null;
        long best = Math.max(e.lbinSell(), e.medianSell());
        if (best <= price) return null;
        long pp = best - FlipEngine.fees(best) - price;
        if (pp < cfg.minProfit || pp * 100.0 / price < cfg.minMarginPercent) return null; // not even close: not worth a line
        if (!cfg.includePets && d.base().startsWith("PET_")) return "PET_OFF";
        String lower = name.toLowerCase(Locale.ROOT);
        for (String b : cfg.blacklist) if (!b.isBlank() && lower.contains(b.toLowerCase(Locale.ROOT))) return "BLACKLIST";
        if (e.sell() <= 0) {
            boolean needLbin = !cfg.referenceMode.equals("median");
            boolean needMed = !cfg.referenceMode.equals("lbin");
            if (needLbin && e.lbinSell() <= 0) return "NO_LISTINGS";
            if (needMed && e.medianSell() <= 0) {
                OwnModel.Stats st = OwnModel.stats(d, Clock.now());
                return st == null || st.samples() == 0 ? "NO_EXACT_SALES" : "FEW_SAMPLES";
            }
            return "NO_REFERENCE";
        }
        if (e.sell() <= price) return "NOT_CHEAPER";
        if (e.profit() < cfg.minProfit) return "BELOW_PROFIT";
        if (e.margin() < cfg.minMarginPercent) return "BELOW_MARGIN";
        if (cfg.maxMarginPercent > 0 && e.margin() > cfg.maxMarginPercent) return "ABOVE_MAXMARGIN";
        if (cfg.maxPrice > 0 && price > cfg.maxPrice) return "ABOVE_MAXPRICE";
        int minConf = OwnModel.confRank(cfg.minConfidence.toUpperCase(Locale.ROOT));
        if (minConf > 1) {
            OwnModel.Stats st = OwnModel.stats(d, Clock.now());
            if (st == null || OwnModel.confRank(st.conf()) < minConf) return "LOW_CONF";
        }
        if (cfg.minVolume > 0 && volume != null) {
            int vol = volumePerDay(d);
            if (vol < 0) return "LOADING";
            if (vol < cfg.minVolume) return "LOW_VOLUME";
        }
        return null;
    }

    private static Map<String, long[]> sorted(Map<String, java.util.List<Long>> in) {
        Map<String, long[]> out = new java.util.HashMap<>();
        for (Map.Entry<String, java.util.List<Long>> e : in.entrySet()) {
            out.put(e.getKey(), e.getValue().stream().mapToLong(Long::longValue).sorted().toArray());
        }
        return out;
    }

    // ------------------------------------------------------------------ lookups

    /** Raw ascending BIN prices of the item's exact group, or null (also null if rarity is unknown). */
    static long[] raw(ItemData d) {
        if (d.tier() == null) return null;
        // lowest listings of items with the same important modifiers
        OwnModel.Lbins lb = OwnModel.lbins(d, -1, 2);
        if (lb != null) return lb.second() > 0 ? new long[]{lb.lowest(), lb.second()} : new long[]{lb.lowest()};
        return null; // no group-wide fallback: it would mix variants (Etherwarp, attributes, ...)
    }

    static long lowestById(String id) {
        Long v = lowestIds.get(id);
        return v == null ? 0 : v;
    }

    /**
     * The price once modifiers are removed. Returns -1 if the modifiers would make up almost all of
     * the price (then the leftover is meaningless noise and the listing is left out of comparisons).
     */
    static long plain(long price, long mod) {
        long p = price - mod;
        return (p > 0 && p >= price / 10) ? p : -1;
    }

    /** Sales per day for this exact item group (rarity, stars, recombobulation...), -1 while warming up. */
    static int volumePerDay(ItemData d) {
        OwnModel.Stats st = OwnModel.stats(d, Clock.now());
        // Own recorded sales only (unknown while warming up). The old group-wide tracker mixed variants together.
        return st != null && st.perDay() >= 0 ? (int) Math.round(st.perDay()) : -1;
    }

    /** Sales per day with decimals (e.g. 0.2 or 28.9), or -1 if unknown. */
    static double volumeExact(ItemData d) {
        OwnModel.Stats st = OwnModel.stats(d, Clock.now());
        if (st != null && st.perDay() >= 0) return st.perDay();
        return volumePerDay(d);
    }

    // ------------------------------------------------------------------ valuation

    /**
     * @param price the item's listed price, or <= 0 if it isn't listed (e.g. an item in your inventory)
     */
    static Estimate estimate(ItemData d, long price) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        double f = cfg.modValuePercent / 100.0;
        Pricing.ModValue mv = Pricing.modifierValue(d);
        long mod = Math.round(mv.total() * f);

        // Lowest BIN / next lowest come ONLY from listings with the same important modifiers (OwnModel.lbins below).
        // The plain group-wide lowest price is not used: it ignores things like Etherwarp, so a cheap plain listing
        // could make an Etherwarp item look overpriced (and an Etherwarp listing could make a plain item look like a flip).
        long floor = -1, lbinSell = -1;
        int others = 0;

        // The median sold price comes ONLY from the recorded sales that carry every modifier (see OwnModel).
        // The older group-wide volume tracker is not used here: it pools sales of different variants (e.g. Aspect of the Void
        // with and without Etherwarp) into one price, which produced false flips.
        long medianSell = -1;
        int samples = 0;

        // Attribute items must only be compared with items that have the same attributes (done below from own data).
        if (d.hasAttributes()) {
            floor = -1; lbinSell = -1; others = 0; medianSell = -1; samples = 0;
        }

        // Compare with sales and listings that have the same important modifiers.
        if (d.tier() != null) {
            OwnModel.Lbins lb = OwnModel.lbins(d, price > 0 ? price : -1, Math.max(1, cfg.minListings));
            if (lb != null) { lbinSell = lb.lowest(); floor = lb.lowest(); others = lb.count(); }
            OwnModel.Stats st = OwnModel.stats(d, Clock.now());
            if (st != null && st.samples() >= Math.max(1, cfg.minMedianSamples)) { medianSell = st.value(); samples = st.samples(); }
        }

        // The reference price follows the mode: "lbin" = lowest matching BIN, "median" = 7-day median sold,
        // "both" = the lower of the two.
        long sell = switch (cfg.referenceMode) {
            case "median" -> medianSell;
            case "both" -> (lbinSell > 0 && medianSell > 0) ? Math.min(lbinSell, medianSell) : -1;
            default -> lbinSell;
        };

        long profit = 0;
        double margin = 0;
        if (sell > 0 && price > 0) {
            profit = sell - FlipEngine.fees(sell) - price;
            margin = profit * 100.0 / price;
        }
        return new Estimate(lbinSell, medianSell, sell, mv, mod, floor, others, samples, profit, margin);
    }

    /** Result of {@link #check}: passes, fails, or fails ONLY because the sales volume is still unknown (warming up). */
    static final int PASS = 0, FAIL = 1, WAIT_VOLUME = 2;

    /** True when this listing passes all of your flip filters (alerts and bright green highlight). */
    static boolean passes(Estimate e, ItemData d, String name, long price) {
        return check(e, d, name, price) == PASS;
    }

    /** Applies all flip filters; WAIT_VOLUME means everything else passed and only the unknown volume held it back. */
    static int check(Estimate e, ItemData d, String name, long price) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        if (e == null || price <= 0 || e.sell() <= price) return FAIL;
        if (d.tier() == null) return FAIL;
        if (e.profit() < cfg.minProfit || e.margin() < cfg.minMarginPercent) return FAIL;
        if (cfg.maxMarginPercent > 0 && e.margin() > cfg.maxMarginPercent) return FAIL;
        if (cfg.maxPrice > 0 && price > cfg.maxPrice) return FAIL;
        if (!cfg.includePets && d.base().startsWith("PET_")) return FAIL;

        String lower = name.toLowerCase(Locale.ROOT);
        for (String b : cfg.blacklist) {
            if (!b.isBlank() && lower.contains(b.toLowerCase(Locale.ROOT))) return FAIL;
        }

        int minConf = OwnModel.confRank(cfg.minConfidence.toUpperCase(Locale.ROOT));
        if (minConf > 1) {
            OwnModel.Stats st = OwnModel.stats(d, Clock.now());
            if (st == null || OwnModel.confRank(st.conf()) < minConf) return FAIL;
        }

        if (cfg.minVolume > 0 && volume != null) {
            int vol = volumePerDay(d);
            if (vol < 0) {
                if (!cfg.allowUnknownVolume) return WAIT_VOLUME;
            } else if (vol < cfg.minVolume) {
                return FAIL;
            }
        }
        return PASS;
    }

    /** True when someone else's comparable listing, adjusted for modifiers, is cheaper (red highlight). */
    static boolean isOvercut(Estimate e, long price) {
        return e != null && price > 0 && e.lbinSell() > 0 && price > e.lbinSell();
    }

    /** Breakdown used in alert hovers. */
    static String describe(ItemData d, Estimate e) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        Pricing.ModValue mv = e.mv();
        StringBuilder sb = new StringBuilder();
        sb.append("Compared with: ").append(d.label());
        sb.append("\nMode: ").append(cfg.referenceMode);
        if (e.lbinSell() > 0) sb.append("\nLBIN-based value: ").append(Format.coins(e.lbinSell()));
        if (e.medianSell() > 0) sb.append("\nMedian-based value (").append(e.samples()).append(" sales): ").append(Format.coins(e.medianSell()));
        if (d.tier() != null) {
            OwnModel.Stats st = OwnModel.stats(d, Clock.now());
            if (st != null) sb.append("\nSales matched: ").append(st.samples()).append(" (relaxed ").append(st.level()).append(" of ")
                    .append(st.levels() - 1).append(" modifier steps), median ").append(Format.coins(st.median()))
                    .append("\nConfidence: ").append(st.conf()).append(" (").append(st.windowDays()).append("d window, spread ")
                    .append(Math.round(st.relIqr() * 100)).append("%")
                    .append(", range ").append(Format.coins(st.min())).append("-").append(Format.coins(st.max()))
                    .append(st.trimmed() > 0 ? ", " + st.trimmed() + " odd sales left out" : "").append(")");
            else sb.append("\nSales: none usable yet");
        }
        sb.append("\nModifiers: +").append(Format.coins(mv.total()))
                .append(" (enchants ").append(Format.coins(mv.enchants()))
                .append(", gems ").append(Format.coins(mv.gems()))
                .append(", slots ").append(Format.coins(mv.slots()))
                .append(", other ").append(Format.coins(mv.extras())).append(")");
        return sb.toString();
    }
}
