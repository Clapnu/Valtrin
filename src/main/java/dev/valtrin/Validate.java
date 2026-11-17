// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.net.URI;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * One place that decides whether text from outside Valtrin (Hypixel, Mojang, a shared server, files, chat) is safe to
 * use in a chat command, a file name, a URL or a data file. Every trust boundary goes through here.
 */
final class Validate {
    private Validate() {}

    private static final Pattern AUCTION_ID = Pattern.compile("^[0-9a-fA-F]{32}$|^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_]{1,16}$");
    private static final Pattern ITEM_ID = Pattern.compile("^[A-Z0-9_:;\\-]{1,64}$");

    /** An auction or player UUID (32 hex characters, or the dashed form). */
    static boolean uuid(String s) { return s != null && AUCTION_ID.matcher(s).matches(); }

    /** A Minecraft username. */
    static boolean player(String s) { return s != null && PLAYER.matcher(s).matches(); }

    /** A SkyBlock item id such as HYPERION or ENCHANTED_BOOK. */
    static boolean itemId(String s) { return s != null && ITEM_ID.matcher(s).matches(); }

    /** Text that is safe on one line of a tab-separated file or in chat: control characters become spaces, length is capped. */
    static String text(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            sb.append(c < 32 || c == 127 ? ' ' : c);
        }
        return sb.toString();
    }

    /**
     * The shared-server address if it is acceptable: https, or http only for this computer (localhost). Anything else
     * would send the access key in clear text. Returns null when it is not acceptable.
     */
    static String serverUrl(String url) {
        if (url == null) return null;
        try {
            URI u = URI.create(url.trim());
            String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(Locale.ROOT);
            String host = u.getHost() == null ? "" : u.getHost().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) return null;
            boolean local = host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
            if (scheme.equals("https") || (scheme.equals("http") && local)) return url.trim();
            return null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
