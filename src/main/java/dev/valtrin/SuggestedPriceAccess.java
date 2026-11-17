// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

/** Public bridge so the mixin package can read the (package-private) suggestion. */
public final class SuggestedPriceAccess {
    private SuggestedPriceAccess() {}

    public static String signText() {
        return SuggestedPrice.signText();
    }
}
