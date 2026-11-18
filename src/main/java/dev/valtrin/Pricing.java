// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an item's modifiers into coins.
 *  - Enchants: 7-day average of the Bazaar instant-buy and instant-sell price of the book.
 *  - Other Bazaar items: average of the current instant-buy and instant-sell price.
 *  - Gem-slot unlock costs come from Hypixel's item resource (/resources/skyblock/items).
 *
 * Stars and recombobulation are NOT priced here: they are part of the comparison key, so an item
 * is only ever compared with items that have the same stars and recombobulation state.
 */
final class Pricing {
    private Pricing() {}

    record Cost(String type, String ref, double amount) {}
    record Slot(String type, List<Cost> costs) {}
    record ItemCosts(List<Slot> slots) {}

    record ModValue(long enchants, long gems, long slots, long extras) {
        long total() { return enchants + gems + slots + extras; }
    }

    /** Enchants not sold on the Bazaar are valued at this many coins each. */
    private static final long ENCHANT_FALLBACK = 1_000;

    /** product id -> average of current instant-buy and instant-sell price. */
    static volatile Map<String, Double> bazaar = Map.of();
    static volatile Map<String, ItemCosts> items = Map.of();
    /** item id -> cost of each dungeon star (index 0 = first star), from Hypixel's item resource. */
    static volatile Map<String, List<List<Cost>>> starCosts = Map.of();
    static volatile long bazaarAt = 0;
    /** product id -> units sold over the last 7 days (Hypixel's sellMovingWeek), for Bazaar volume. */
    static volatile Map<String, Double> bazaarWeek = Map.of();
    static volatile long itemsAt = 0;
    /** 7-day history of enchanted book prices, built by the mod while it runs. */
    static final BazaarHistory history = new BazaarHistory();

    /** Prices are only trusted once the Bazaar has loaded. */
    /** True for items sold on the Bazaar (their prices are already in Hypixel's own tooltip; no auction data applies). */
    static boolean isBazaar(String id) {
        return id != null && bazaar.containsKey(id);
    }

    static boolean ready() {
        return !bazaar.isEmpty();
    }

    static String status() {
        long hrs = history.hoursRecorded(Clock.now());
        return (bazaar.isEmpty() ? "bazaar: loading" : "bazaar: " + bazaar.size() + " products")
                + ", " + (items.isEmpty() ? "slot costs: not loaded (using fallback)" : "slot costs: " + items.size() + " items")
                + ", enchant history: " + history.products() + " books over " + hrs + "h";
    }

    // ------------------------------------------------------------------ loading

    static void loadBazaar(JsonObject root) {
        JsonObject products = root.getAsJsonObject("products");
        if (products == null) return;
        Map<String, Double> out = new HashMap<>();
        Map<String, double[]> both = new HashMap<>();
        Map<String, Double> week = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : products.entrySet()) {
            JsonObject p = e.getValue().getAsJsonObject();
            JsonObject qs = p.getAsJsonObject("quick_status");
            if (qs == null) continue;
            double buy = num(qs, "buyPrice");
            double sell = num(qs, "sellPrice");
            double avg = (buy > 0 && sell > 0) ? (buy + sell) / 2.0 : Math.max(buy, sell);
            if (avg > 0) out.put(e.getKey(), avg);
            both.put(e.getKey(), new double[]{buy, sell});
            week.put(e.getKey(), num(qs, "sellMovingWeek"));
        }
        if (!out.isEmpty()) {
            bazaar = out;
            bazaarWeek = week;
            bazaarAt = Clock.now();
            history.record(both, bazaarAt);
        }
    }

    private static volatile Map<String, String> itemNames = new HashMap<>();

    /** In-game name of an item id ("MELON_HELMET" -> "Tater Helmet"); falls back to a tidied-up id before the item list is loaded. */
    static String displayName(String id) {
        if (id == null) return "";
        if (id.equals("SKYBLOCK_COIN")) return "Coins";
        String n = itemNames.get(id);
        if (n != null && !n.isEmpty()) return n;
        StringBuilder sb = new StringBuilder();
        for (String w : id.toLowerCase(Locale.ROOT).split("_")) {
            if (w.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    static void loadItems(JsonObject root) {
        JsonArray arr = root.getAsJsonArray("items");
        if (arr == null) return;
        Map<String, String> names = new HashMap<>();
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject it = el.getAsJsonObject();
            String nid = str(it, "id");
            String nm = str(it, "name");
            if (!nid.isEmpty() && !nm.isEmpty()) names.put(nid, nm);
        }
        if (!names.isEmpty()) itemNames = names;
        Map<String, ItemCosts> out = new HashMap<>();
        Map<String, List<List<Cost>>> stars = new HashMap<>();
        for (JsonElement el : arr) {
            JsonObject it = el.getAsJsonObject();
            String id = str(it, "id");
            if (id.isEmpty()) continue;

            if (it.has("upgrade_costs") && it.get("upgrade_costs").isJsonArray()) {
                List<List<Cost>> perStar = new ArrayList<>();
                for (JsonElement lvl : it.getAsJsonArray("upgrade_costs")) {
                    List<Cost> costs = new ArrayList<>();
                    if (lvl.isJsonArray()) {
                        for (JsonElement c : lvl.getAsJsonArray()) if (c.isJsonObject()) costs.add(parseCost(c.getAsJsonObject()));
                    }
                    perStar.add(costs);
                }
                if (!perStar.isEmpty()) stars.put(id, perStar);
            }

            List<Slot> slots = new ArrayList<>();
            if (it.has("gemstone_slots") && it.get("gemstone_slots").isJsonArray()) {
                for (JsonElement s : it.getAsJsonArray("gemstone_slots")) {
                    if (!s.isJsonObject()) continue;
                    JsonObject so = s.getAsJsonObject();
                    List<Cost> costs = new ArrayList<>();
                    if (so.has("costs") && so.get("costs").isJsonArray()) {
                        for (JsonElement c : so.getAsJsonArray("costs")) {
                            if (c.isJsonObject()) costs.add(parseCost(c.getAsJsonObject()));
                        }
                    }
                    slots.add(new Slot(str(so, "slot_type").toUpperCase(Locale.ROOT), costs));
                }
            }
            if (!slots.isEmpty()) out.put(id, new ItemCosts(slots));
        }
        if (!stars.isEmpty()) starCosts = stars;
        if (!out.isEmpty()) {
            items = out;
            itemsAt = Clock.now();
        }
    }

    private static Cost parseCost(JsonObject c) {
        String type = str(c, "type").toUpperCase(Locale.ROOT);
        return switch (type) {
            case "COINS" -> new Cost(type, "", num(c, "coins"));
            case "ESSENCE" -> new Cost(type, str(c, "essence_type"), num(c, "amount"));
            default -> new Cost("ITEM", str(c, "item_id"), num(c, "amount"));
        };
    }

    // ------------------------------------------------------------------ valuation

    /** Bazaar average if sold there, otherwise the lowest BIN seen for that item id (0 if unknown). */
    static long itemValue(String id) {
        if (id.equals(ItemData.MIDAS_COINS)) return 1_000_000; // one unit = 1M coins paid into a Midas weapon
        Double p = bazaar.get(id);
        if (p != null) return Math.round(p);
        // Auction-only items (drill parts, scrolls, ...): what they actually SOLD for beats the cheapest listing,
        // which in thin markets is often a mispriced or one-off listing.
        long sold = soldMedian(id);
        return sold > 0 ? sold : Market.lowestById(id);
    }

    /** Where {@link #itemValue} got its number from: "bazaar", "sold" (recent sales), "lowest BIN" or "none". */
    static String itemSource(String id) {
        if (id.equals(ItemData.MIDAS_COINS)) return "fixed";
        if (bazaar.get(id) != null) return "bazaar";
        if (soldMedian(id) > 0) return "sold";
        return Market.lowestById(id) > 0 ? "lowest BIN" : "none";
    }

    private static final Map<String, long[]> SOLD_CACHE = new java.util.concurrent.ConcurrentHashMap<>(); // id -> {at, median}

    /** Median sold price of an item id over the last 14 days (needs 3+ sales), else 0. Cached for 5 minutes. */
    private static long soldMedian(String id) {
        SaleLog log = OwnModel.log;
        if (log == null) return 0;
        long now = Clock.now();
        long[] c = SOLD_CACHE.get(id);
        if (c != null && now - c[0] < 300_000L) return c[1];
        long med = 0;
        java.util.List<SaleLog.Sale> l = log.peek(id);
        if (l == null) return 0; // still being read from disk: ask again next time
        // "Base" means the bare item: 0 stars, no recombobulator, no valuable modifiers (nothing worth 100k+).
        // Fewer than 3 such sales: no sold price (the caller falls back to the cheapest plain listing).
        List<SaleLog.Sale> recent = l.stream().filter(x -> x.ts() > now - 14L * 86_400_000L && x.ts() <= now).toList();
        long[] ps = prices(recent, id);
        if (ps.length >= 3) med = ps.length % 2 == 1 ? ps[ps.length / 2] : (ps[ps.length / 2 - 1] + ps[ps.length / 2]) / 2;
        SOLD_CACHE.put(id, new long[]{now, med});
        return med;
    }

    /** Sorted sold prices of bare copies: 0 stars, no recomb, no modifier worth {@link ModTokens#IMPORTANT_MIN} or more. */
    private static long[] prices(List<SaleLog.Sale> sales, String id) {
        return sales.stream().filter(x -> {
            String[] b = x.base().split("\\|");
            if (b.length < 3 || !b[1].equals("0") || !b[2].equals("N")) return false;
            for (ModTokens.Tok t : x.toks()) {
                if (t.family().startsWith("u:")) continue; // slot value itself depends on this price
                if (ModTokens.value(t, id) >= ModTokens.IMPORTANT_MIN) return false;
            }
            return true;
        }).mapToLong(SaleLog.Sale::price).sorted().toArray();
    }

    private static long costValue(List<Cost> costs) {
        double sum = 0;
        for (Cost c : costs) {
            switch (c.type()) {
                case "COINS" -> sum += c.amount();
                case "ESSENCE" -> {
                    Double p = bazaar.get("ESSENCE_" + c.ref().toUpperCase(Locale.ROOT));
                    if (p != null) sum += p * c.amount();
                }
                default -> sum += itemValue(c.ref()) * c.amount();
            }
        }
        return Math.round(sum);
    }

    /** Coin value of everything applied to the item (before the "% counted" setting). */
    static ModValue modifierValue(ItemData d) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        Map<String, Double> bz = bazaar;

        // Enchants: 7-day average of the book's instant-buy and instant-sell price. If the book has
        // no history yet, its current Bazaar price; if it isn't on the Bazaar at all, +1k each.
        long enchants = 0;
        for (Map.Entry<String, Integer> e : d.enchants().entrySet()) {
            String productId = "ENCHANTMENT_" + e.getKey().toUpperCase(Locale.ROOT) + "_" + e.getValue();
            Double p = history.average(productId);
            if (p == null) p = bz.get(productId);
            enchants += (p != null && p > 0) ? Math.round(p) : ENCHANT_FALLBACK;
        }

        // Gems currently socketed.
        long gems = 0;
        for (String[] g : d.gems()) {
            Double p = bz.get(g[2].toUpperCase(Locale.ROOT) + "_" + g[1].toUpperCase(Locale.ROOT) + "_GEM");
            if (p != null) gems += Math.round(p);
        }

        // Unlocked gem slots: real unlock cost per slot when known, else a flat fallback.
        long slots = 0;
        if (!d.unlocked().isEmpty()) {
            ItemCosts ic = items.get(d.id());
            int matched = 0;
            if (ic != null) {
                Map<String, Integer> seen = new HashMap<>();
                for (Slot s : ic.slots()) {
                    int idx = seen.merge(s.type(), 1, Integer::sum) - 1;
                    if (d.unlocked().contains(s.type() + "_" + idx)) {
                        slots += costValue(s.costs());
                        matched++;
                    }
                }
            }
            slots += (long) Math.max(0, d.unlocked().size() - matched) * cfg.gemSlotFallback;
        }

        // Scrolls (Wither Impact etc.), potato books, Art of War, pet item, ...
        long extras = 0;
        for (ItemData.Extra x : d.extras()) {
            if (ItemData.COSMETIC.equals(x.itemId()) || ItemData.compareOnly(x.itemId())) continue; // dyes, skins, kill tiers, rune/reforge markers add no craft cost
            extras += (long) x.count() * itemValue(x.itemId());
        }

        return new ModValue(enchants, gems, slots, extras);
    }

    // ------------------------------------------------------------------ single-modifier values (used by the own-data model)

    static long enchantValue(String name, int level) {
        String productId = "ENCHANTMENT_" + name.toUpperCase(Locale.ROOT) + "_" + level;
        Double p = history.average(productId);
        if (p == null) p = bazaar.get(productId);
        return (p != null && p > 0) ? Math.round(p) : ENCHANT_FALLBACK;
    }

    static long gemValue(String quality, String type) {
        Double p = bazaar.get(quality.toUpperCase(Locale.ROOT) + "_" + type.toUpperCase(Locale.ROOT) + "_GEM");
        return p == null ? 0 : Math.round(p);
    }

    /** Unlock cost of one gem slot such as COMBAT_0 (real cost when known, else the fallback setting). */
    static long slotValue(String itemId, String slotKey) {
        int us = slotKey.lastIndexOf('_');
        if (us > 0) {
            String type = slotKey.substring(0, us);
            int idx;
            try { idx = Integer.parseInt(slotKey.substring(us + 1)); } catch (NumberFormatException e) { idx = -1; }
            ItemCosts ic = items.get(itemId);
            if (ic != null && idx >= 0) {
                int seen = 0;
                for (Slot s : ic.slots()) {
                    if (!s.type().equals(type)) continue;
                    if (seen++ == idx) return costValue(s.costs());
                }
            }
        }
        return FlipConfig.INSTANCE.gemSlotFallback;
    }

    static long extraValue(String itemId, int count) {
        return (long) count * itemValue(itemId);
    }

    // ------------------------------------------------------------------ craft cost

    private static final String[] MASTER_STARS = {
            "FIRST_MASTER_STAR", "SECOND_MASTER_STAR", "THIRD_MASTER_STAR", "FOURTH_MASTER_STAR", "FIFTH_MASTER_STAR"};

    /** Coins spent on dungeon stars 1..stars (essence for 1-5, the master star items for 6-10). */
    static long starValue(String itemId, int stars) {
        if (stars <= 0) return 0;
        long sum = 0;
        List<List<Cost>> perStar = starCosts.get(itemId);
        if (perStar != null) {
            for (int i = 0; i < Math.min(stars, perStar.size()); i++) sum += costValue(perStar.get(i));
        }
        for (int i = 5; i < Math.min(stars, 10); i++) sum += itemValue(MASTER_STARS[i - 5]);
        return sum;
    }

    /**
     * Cost of crafting this item from its recipe (ingredients priced at current Bazaar / lowest BIN), or -1 when
     * the item has no known recipe. Recipes come from RecipeData (NotEnoughUpdates item repository); the tooltip shows a "Craft Cost" line only when it is positive.
     */
    static long recipeCost(String id) {
        return RecipeData.cost(id);
    }

    /** What it takes to build this exact item: its recipe plus everything applied to it. */
    record CraftBreakdown(long base, boolean recipe, long enchants, long stars, long recomb, long gems, long slots, long extras) {
        long mods() { return enchants + stars + recomb + gems + slots + extras; }
        long total() { return base + mods(); }
    }

    /**
     * @param recipeCost the item's crafting-recipe cost, or -1 if it has none (then its market price is the base)
     */
    static CraftBreakdown craft(ItemData d, long recipeCost) {
        if (!ready() || d.id().equals("PET")) return null;
        // Runes: the item id covers every rune type, so a "base item" price would be some other rune's price.
        if (d.id().endsWith("_RUNE") || d.id().equals("RUNE")) return null;
        ModValue mv = modifierValue(d);
        long base = recipeCost > 0 ? recipeCost : itemValue(d.id());
        long recomb = d.recomb() ? itemValue("RECOMBOBULATOR_3000") : 0;
        return new CraftBreakdown(base, recipeCost > 0, mv.enchants(), starValue(d.id(), d.stars()), recomb, mv.gems(), mv.slots(), mv.extras());
    }

    // ------------------------------------------------------------------ json helpers

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }

    private static double num(JsonObject o, String k) {
        try {
            return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsDouble() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
