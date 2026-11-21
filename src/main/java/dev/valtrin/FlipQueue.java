// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.ArrayList;
import java.util.List;

/**
 * The flips that were alerted recently and not opened yet. The "open next flip" key takes the most profitable one
 * and runs /viewauction for it (the same thing clicking the alert does). Nothing is bought automatically.
 */
final class FlipQueue {
    private FlipQueue() {}

    record Item(String uuid, String name, long buy, long profit, long at) {}

    /** A queued flip is only offered for this long (the listing is probably gone after that). */
    static final long MAX_AGE_MS = 5 * 60_000L;
    private static final int MAX_ITEMS = 200;
    private static final List<Item> ITEMS = new ArrayList<>();

    static synchronized void add(Item item) {
        if (item == null || GONE.containsKey(norm(item.uuid()))) return; // already sold: never offer it
        ITEMS.add(item);
        if (ITEMS.size() > MAX_ITEMS) {
            // drop the least profitable
            ITEMS.sort((a, b) -> Long.compare(b.profit(), a.profit()));
            ITEMS.subList(MAX_ITEMS, ITEMS.size()).clear();
        }
    }

    /** Takes the most profitable flip that is still fresh, or null when none is waiting. */
    static synchronized Item next(long now) {
        ITEMS.removeIf(i -> now - i.at() > MAX_AGE_MS || GONE.containsKey(norm(i.uuid())));
        Item best = null;
        for (Item i : ITEMS) if (best == null || i.profit() > best.profit()) best = i;
        if (best != null) ITEMS.remove(best);
        return best;
    }

    static synchronized int size(long now) {
        ITEMS.removeIf(i -> now - i.at() > MAX_AGE_MS || GONE.containsKey(norm(i.uuid())));
        return ITEMS.size();
    }

    static synchronized void clear() {
        ITEMS.clear();
    }

    // ------------------------------------------------------------------ sold / gone listings

    /** Listings known to be sold or gone (auction id -> when we learned it), so the key never opens them. */
    private static final java.util.LinkedHashMap<String, Long> GONE = new java.util.LinkedHashMap<>();
    private static final int MAX_GONE = 20_000;

    /** An auction ended (sold, or a regular auction finished): drop it from the queue and never queue it again. */
    static synchronized void markGone(String uuid) {
        if (uuid == null) return;
        String u = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        GONE.put(u, System.currentTimeMillis());
        if (GONE.size() > MAX_GONE) {
            var it = GONE.entrySet().iterator();
            while (GONE.size() > MAX_GONE && it.hasNext()) { it.next(); it.remove(); }
        }
        ITEMS.removeIf(i -> u.equals(norm(i.uuid())));
    }

    /**
     * A full snapshot arrived: every queued listing that was in the previous snapshot but is missing now has sold or
     * been taken down. Listings the previous snapshot never had (e.g. a server alert newer than this computer's
     * picture of the market) are left alone, since missing from it says nothing about them.
     */
    static synchronized void dropMissing(java.util.Set<String> previous, java.util.Set<String> current) {
        if (previous == null || previous.isEmpty() || current == null || current.isEmpty()) return;
        ITEMS.removeIf(i -> previous.contains(i.uuid()) && !current.contains(i.uuid()));
    }

    static synchronized boolean isGone(String uuid) {
        return uuid != null && GONE.containsKey(norm(uuid));
    }

    private static String norm(String uuid) {
        return uuid == null ? "" : uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
    }
}
