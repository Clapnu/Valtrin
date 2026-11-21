// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

/**
 * Everything one flip alert shows, as plain data. {@code volume} is -1 when unknown, {@code sellerName} and
 * {@code conf} may be null, {@code resale} may be null.
 *
 * Timing (only used by /flip timing, 0 = unknown): {@code snap} is Hypixel's lastUpdated stamp of the snapshot the
 * listing first appeared in, {@code snapAt} is when the machine that found the flip received that snapshot, and
 * {@code issuedAt} is when it raised the alert. Both times are on that machine's clock (the server's for server
 * alerts). {@code snap} is only set when the listing is new in that snapshot, so a leftover from an older snapshot is
 * never counted as a snapshot's first alert.
 */
record FlipAlert(String name, String tier, String uuid, String sellerUuid, long buy, long sell, long profit,
                 double margin, double volume, String detail, String sellerName, String conf,
                 SellTime.Resale resale, long snap, long snapAt, long issuedAt) {

    FlipAlert(String name, String tier, String uuid, String sellerUuid, long buy, long sell, long profit,
              double margin, double volume, String detail, String sellerName, String conf, SellTime.Resale resale) {
        this(name, tier, uuid, sellerUuid, buy, sell, profit, margin, volume, detail, sellerName, conf, resale, 0L, 0L, 0L);
    }
}
