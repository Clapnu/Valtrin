// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.function.LongSupplier;

/**
 * The time used by the price model. Normally the PC's clock; the replay tool sets it to the time of the snapshot
 * being replayed, so an old market can be judged exactly as it was (and sales made later are not visible yet).
 */
final class Clock {
    private Clock() {}

    private static volatile LongSupplier source = System::currentTimeMillis;
    private static volatile boolean replaying = false;

    static long now() {
        return source.getAsLong();
    }

    /** True while the replay tool drives the clock (sales newer than "now" must then be hidden). */
    static boolean replaying() {
        return replaying;
    }

    static void freeze(long millis) {
        replaying = true;
        source = () -> millis;
    }

    static void reset() {
        replaying = false;
        source = System::currentTimeMillis;
    }
}
