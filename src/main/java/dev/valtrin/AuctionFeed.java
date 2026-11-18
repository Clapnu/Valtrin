// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonObject;

/**
 * A tiny window onto the public auction data the engine already downloads, for the server's /auctions command.
 * Nothing is listening in the mod, so every call does nothing there. A listener that fails never breaks the engine.
 */
final class AuctionFeed {
    private AuctionFeed() {}

    interface Listener {
        /** A full snapshot is about to be read. */
        void begin();
        /** One listing from a snapshot page (still unfiltered: sold, claimed and non-BIN listings included). */
        void listing(JsonObject o);
        /** The star count of a listing the engine parsed. */
        void stars(String uuid, int stars);
        /** The snapshot was read completely. */
        void done(long lastUpdated);
        /** One auction from the ended-auctions feed (a sale). */
        void sale(JsonObject o);
    }

    static volatile Listener L;

    static void begin() { Listener l = L; if (l != null) try { l.begin(); } catch (RuntimeException ignored) {} }
    static void listing(JsonObject o) { Listener l = L; if (l != null) try { l.listing(o); } catch (RuntimeException ignored) {} }
    static void stars(String uuid, int stars) { Listener l = L; if (l != null) try { l.stars(uuid, stars); } catch (RuntimeException ignored) {} }
    static void done(long lu) { Listener l = L; if (l != null) try { l.done(lu); } catch (RuntimeException ignored) {} }
    static void sale(JsonObject o) { Listener l = L; if (l != null) try { l.sale(o); } catch (RuntimeException ignored) {} }
}
