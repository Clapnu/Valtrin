// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * Who is selling what, built only from Hypixel's public auction data that the server already reads:
 * the listings of the latest full snapshot, and the sales of the last 36 hours from the ended-auctions feed.
 * Kept in memory (sales are lost on a restart). It cannot know whether a sold auction was claimed: that is not public.
 */
final class AuctionIndex implements AuctionFeed.Listener {
    static final long SOLD_KEEP_MS = 36L * 3_600_000L;
    static final int SOLD_CAP = 250_000;

    static final class Listing {
        final String uuid, seller, profile, name, tier;
        final long price, end;
        final boolean bin;
        volatile int stars;

        Listing(String uuid, String seller, String profile, String name, String tier, long price, long end, boolean bin) {
            this.uuid = uuid; this.seller = seller; this.profile = profile; this.name = name; this.tier = tier;
            this.price = price; this.end = end; this.bin = bin;
        }
    }

    /** @param stars dungeon stars 0-5; @param master master stars 0-5 */
    record Sale(String id, String seller, String profile, String buyer, String name, String tier, int stars, int master,
                long price, long ts) {}

    private Map<String, List<Listing>> building = new HashMap<>();
    private Map<String, Listing> buildingById = new HashMap<>();
    private volatile Map<String, List<Listing>> live = Map.of();
    private volatile long snapshotAt;

    private final ConcurrentHashMap<String, ConcurrentLinkedDeque<Sale>> sold = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Sale> order = new ConcurrentLinkedQueue<>();
    private volatile Consumer<Sale> saleHook;
    private volatile java.util.function.LongSupplier clock = System::currentTimeMillis;

    void onSale(Consumer<Sale> hook) { saleHook = hook; }
    void clock(java.util.function.LongSupplier c) { clock = c; }
    int soldCount() { return order.size(); }
    int sellers() { return live.size(); }
    long snapshotAt() { return snapshotAt; }

    // ------------------------------------------------------------------ feed (engine threads)

    @Override public synchronized void begin() {
        building = new HashMap<>(70_000);
        buildingById = new HashMap<>(70_000);
    }

    @Override public synchronized void listing(JsonObject o) {
        if (o.has("claimed") && o.get("claimed").getAsBoolean()) return;
        String seller = str(o, "auctioneer");
        if (!Validate.uuid(seller)) return;
        boolean bin = o.has("bin") && o.get("bin").getAsBoolean();
        long start = o.has("starting_bid") ? o.get("starting_bid").getAsLong() : 0;
        long bid = o.has("highest_bid_amount") ? o.get("highest_bid_amount").getAsLong() : 0;
        String name = str(o, "item_name");
        if (name.isEmpty()) return;
        Listing l = new Listing(str(o, "uuid"), seller, str(o, "profile_id"), name.length() > 80 ? name.substring(0, 80) : name,
                o.has("tier") ? str(o, "tier") : "COMMON", bin ? start : Math.max(start, bid),
                o.has("end") ? o.get("end").getAsLong() : 0, bin);
        building.computeIfAbsent(seller, k -> new ArrayList<>(2)).add(l);
        buildingById.put(l.uuid, l);
    }

    @Override public synchronized void stars(String uuid, int stars) {
        Listing l = buildingById.get(uuid);
        if (l != null) l.stars = stars;
    }

    @Override public synchronized void done(long lastUpdated) {
        live = building;
        snapshotAt = clock.getAsLong();
        building = new HashMap<>();
        buildingById = new HashMap<>();
    }

    @Override public void sale(JsonObject o) {
        String seller = str(o, "seller");
        if (!Validate.uuid(seller)) return;
        String bytes = o.has("item_bytes") ? HypixelApi.itemBytes(o.get("item_bytes")) : null;
        String name = ItemKeys.displayName(bytes);
        if (name == null) return;
        int stars = 0, master = 0;
        StringBuilder plain = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '✪') stars++;
            else if (c >= '➊' && c <= '➎') master = c - '➉';
            else plain.append(c);
        }
        ItemData d = ItemKeys.parse(bytes);
        String tier = d == ItemData.NONE || d.tier() == null ? "COMMON" : d.tier();
        long ts = o.has("timestamp") ? o.get("timestamp").getAsLong() : clock.getAsLong();
        Sale s = new Sale(str(o, "auction_id"), seller, str(o, "seller_profile"), str(o, "buyer"), plain.toString().strip(), tier,
                Math.min(5, stars), master, o.has("price") ? o.get("price").getAsLong() : 0, ts);
        addSale(s);
    }

    void addSale(Sale s) {
        sold.computeIfAbsent(s.seller(), k -> new ConcurrentLinkedDeque<>()).addFirst(s);
        order.add(s);
        prune();
        Consumer<Sale> h = saleHook;
        if (h != null) try { h.accept(s); } catch (RuntimeException ignored) {}
    }

    private void prune() {
        long cutoff = clock.getAsLong() - SOLD_KEEP_MS;
        Sale s;
        while ((s = order.peek()) != null && (s.ts() < cutoff || order.size() > SOLD_CAP)) {
            order.poll();
            ConcurrentLinkedDeque<Sale> dq = sold.get(s.seller());
            if (dq != null) {
                dq.removeLastOccurrence(s);
                if (dq.isEmpty()) sold.remove(s.seller(), dq);
            }
        }
    }

    // ------------------------------------------------------------------ queries

    /** The seller's listings, all profiles, as they were in the last full snapshot. */
    List<Listing> listings(String sellerUuid) {
        List<Listing> l = live.get(sellerUuid);
        return l == null ? List.of() : new ArrayList<>(l);
    }

    /** Sales of the last 36 hours, newest first. */
    List<Sale> sales(String sellerUuid) {
        ConcurrentLinkedDeque<Sale> dq = sold.get(sellerUuid);
        if (dq == null) return List.of();
        long cutoff = clock.getAsLong() - SOLD_KEEP_MS;
        List<Sale> out = new ArrayList<>();
        for (Sale s : dq) if (s.ts() >= cutoff) out.add(s);
        out.sort(Comparator.comparingLong(Sale::ts).reversed());
        return out;
    }

    /** Profile id -> number of listings, biggest first. */
    Map<String, Integer> profiles(String sellerUuid) {
        Map<String, Integer> m = new HashMap<>();
        for (Listing l : listings(sellerUuid)) if (!l.profile.isEmpty()) m.merge(l.profile, 1, Integer::sum);
        for (Sale s : sales(sellerUuid)) if (!s.profile().isEmpty()) m.merge(s.profile(), 0, Integer::sum);
        Map<String, Integer> out = new LinkedHashMap<>();
        m.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).forEach(e -> out.put(e.getKey(), e.getValue()));
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
    }
}
