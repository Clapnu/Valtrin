// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.Locale;

/**
 * "Sells in about X": how long a listing at a given price should take to sell. It is the item's sales per day (recorded
 * sales of the exact same kind of item) times the listings that are priced at or below yours: the cheapest listing is next
 * in line for the next sale, each cheaper one adds one more sale to wait for. An estimate, never a promise.
 */
final class SellTime {
    private SellTime() {}

    private static final long CAP_MINUTES = 14L * 1440;

    /** Result for a flip alert: the price to relist at, how long it should take, and what that earns. */
    record Resale(long price, long minutes, long profit, long perHour) {}

    /** Expected minutes until a new listing at {@code price} sells, or -1 when the sales rate is unknown. */
    static long minutes(ItemData d, long price, long skipOnePrice) {
        if (d == null || d.tier() == null || price <= 0) return -1;
        OwnModel.Stats st = OwnModel.stats(d, Clock.now());
        if (st == null || st.perDay() <= 0) return -1;
        int ahead = Math.max(0, OwnModel.listingsAtOrBelow(d, price, skipOnePrice));
        double m = (ahead + 1) * 1440.0 / st.perDay();
        return Math.max(1, Math.min(CAP_MINUTES, Math.round(m)));
    }

    /** "50 min", "1 h 40 min", "4 h", "1.5 days", "?" when unknown. */
    static String format(long minutes) {
        if (minutes < 0) return "?";
        if (minutes >= CAP_MINUTES) return "more than 14 days";
        if (minutes < 60) return minutes + " min";
        if (minutes < 1440) {
            long h = minutes / 60, m = minutes % 60;
            return m == 0 ? h + " h" : h + " h " + m + " min";
        }
        double days = minutes / 1440.0;
        return String.format(Locale.ROOT, "%.1f days", days).replace(".0 days", " days");
    }

    /**
     * What happens after buying this flip: relist just under the next-lowest comparable listing (the same undercut as the
     * Create Auction suggestion), how long that should take to sell, and the profit per hour. Null when it cannot be told.
     * @param buy what the flip costs (its own listing is not counted as a competitor)
     */
    static Resale forFlip(ItemData d, long buy, Market.Estimate est) {
        if (est == null || est.lbinSell() <= 0) return null;
        long lbin = est.lbinSell();
        long cut = Math.max(1, Math.round(lbin * FlipConfig.INSTANCE.undercutPercent / 100.0));
        long relist = lbin - cut;
        if (relist <= 0) return null;
        long minutes = minutes(d, relist, buy);
        if (minutes < 0) return null;
        long profit = relist - FlipEngine.fees(relist) - buy;
        long perHour = profit > 0 ? Math.round(profit * 60.0 / minutes) : 0;
        return new Resale(relist, minutes, profit, perHour);
    }
}
