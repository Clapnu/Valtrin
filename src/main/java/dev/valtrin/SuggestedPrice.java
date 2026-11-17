// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.ArrayList;
import java.util.List;

/**
 * Price suggestion for the item in the "Create Auction" menu, built from this mod's own data. It offers up to three prices,
 * each with how long it should take to sell: Undercut (the lowest BIN of comparable items minus a small percentage; the
 * default), Lowest BIN (match the cheapest listing) and Median (the 7-day median sold price). Click one to pick it; the pick
 * is what the sign box is filled with. It is only shown and offered; the mod never submits anything.
 */
final class SuggestedPrice {
    private SuggestedPrice() {}

    /** One offered price: label, coins, and expected minutes to sell (-1 = unknown). */
    record Option(String label, long price, long minutes) {}

    private static volatile List<Option> options = List.of();
    private static volatile int chosen = 0;
    private static volatile String lastKey = "";
    private static volatile long at = 0;
    static volatile String detail = "";

    private static final long VALID_MS = 3 * 60_000L;

    /** Recomputes the options for this item. Returns false while data is still loading or none can be made. */
    static boolean update(ItemData d) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        if (d == null || d.tier() == null || d.cosmetic() || Pricing.isBazaar(d.id())) {
            detail = d != null && d.cosmetic() ? "dyed/skinned items can't be priced" : "no price data for this item";
            options = List.of();
            return false;
        }
        if (OwnModel.loading(d)) { detail = "loading sales data..."; return false; }

        OwnModel.Lbins lb = OwnModel.lbins(d, -1, 1);
        OwnModel.Stats st = OwnModel.stats(d, Clock.now());
        long lbin = lb == null ? -1 : lb.lowest();
        long median = st == null ? -1 : st.median();

        List<Option> list = new ArrayList<>();
        if (lbin > 0) {
            long under = lbin - Math.max(1, Math.round(lbin * cfg.undercutPercent / 100.0));
            if (under > 0) list.add(new Option("Undercut", under, SellTime.minutes(d, under, -1)));
            list.add(new Option("Lowest BIN", lbin, SellTime.minutes(d, lbin, -1)));
        }
        if (median > 1000 && (lbin <= 0 || Math.abs(median - lbin) * 100 > lbin)) {
            list.add(new Option("Median", median, SellTime.minutes(d, median, -1)));
        }
        if (list.isEmpty()) { detail = "no recent listings or sales"; options = List.of(); return false; }

        String key = d.id() + "|" + d.tier() + "|" + d.base();
        if (!key.equals(lastKey)) { lastKey = key; chosen = 0; }
        if (chosen >= list.size()) chosen = 0;
        options = list;
        at = Clock.now();
        detail = "";
        return true;
    }

    static List<Option> options() { return Clock.now() - at <= VALID_MS ? options : List.of(); }

    static int chosenIndex() { return chosen; }

    static void choose(int i) { if (i >= 0 && i < options.size()) chosen = i; }

    /** The chosen price in coins, or -1 if none / too old. */
    static long current() {
        List<Option> o = options();
        return o.isEmpty() ? -1 : o.get(Math.min(chosen, o.size() - 1)).price();
    }

    /** Text for the sign box: plain digits, or null. */
    static String signText() {
        long v = current();
        return v > 0 ? Long.toString(v) : null;
    }
}
