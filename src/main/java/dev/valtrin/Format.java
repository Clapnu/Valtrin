// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.Locale;

final class Format {
    private Format() {}

    static String coins(long v) {
        long a = Math.abs(v);
        if (a >= 1_000_000_000L) return String.format(Locale.ROOT, "%.2fB", v / 1e9);
        if (a >= 1_000_000L) return String.format(Locale.ROOT, "%.2fM", v / 1e6);
        if (a >= 1_000L) return String.format(Locale.ROOT, "%.1fK", v / 1e3);
        return Long.toString(v);
    }

    /** Parses "500k", "1.5m", "2b" or "100000". Returns -1 if invalid. */
    static long parseCoins(String s) {
        try {
            s = s.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("_", "");
            double mult = 1;
            if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
            else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
            double v = Double.parseDouble(s) * mult;
            if (v < 0 || Double.isNaN(v) || Double.isInfinite(v)) return -1;
            return Math.round(v);
        } catch (Exception e) {
            return -1;
        }
    }

    /** Compact coins for chat: 34M, 37.8M, 2.66M, 500K, 1.2B. */
    static String compact(long v) {
        long a = Math.abs(v);
        String r;
        if (a >= 1_000_000_000L) r = trim(v / 1e9, 2) + "B";
        else if (a >= 1_000_000L) r = trim(v / 1e6, 2) + "M";
        else if (a >= 1_000L) r = trim(v / 1e3, 1) + "K";
        else r = Long.toString(v);
        return r;
    }

    private static String trim(double x, int decimals) {
        String s = String.format(Locale.ROOT, "%." + decimals + "f", x);
        if (s.contains(".")) s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
        return s;
    }
}
