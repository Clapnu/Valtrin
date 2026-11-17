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
        ITEMS.add(item);
        if (ITEMS.size() > MAX_ITEMS) {
            // drop the least profitable
            ITEMS.sort((a, b) -> Long.compare(b.profit(), a.profit()));
            ITEMS.subList(MAX_ITEMS, ITEMS.size()).clear();
        }
    }

    /** Takes the most profitable flip that is still fresh, or null when none is waiting. */
    static synchronized Item next(long now) {
        ITEMS.removeIf(i -> now - i.at() > MAX_AGE_MS);
        Item best = null;
        for (Item i : ITEMS) if (best == null || i.profit() > best.profit()) best = i;
        if (best != null) ITEMS.remove(best);
        return best;
    }

    static synchronized int size(long now) {
        ITEMS.removeIf(i -> now - i.at() > MAX_AGE_MS);
        return ITEMS.size();
    }

    static synchronized void clear() {
        ITEMS.clear();
    }
}
