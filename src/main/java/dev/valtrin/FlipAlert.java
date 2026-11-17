// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

/**
 * Everything one flip alert shows, as plain data. {@code volume} is -1 when unknown, {@code sellerName} and
 * {@code conf} may be null, {@code resale} may be null.
 */
record FlipAlert(String name, String tier, String uuid, String sellerUuid, long buy, long sell, long profit,
                 double margin, double volume, String detail, String sellerName, String conf,
                 SellTime.Resale resale) {
}
