// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Crafting recipes, from the NotEnoughUpdates item repository (MIT license, Copyright (c) 2020 Moulberry; see
 * DATA_SOURCES.md). One small file per item is downloaded the first time an item is looked at, then kept in
 * ValtrinData/recipes for a week. Nothing is downloaded or shown until an item with a recipe is looked at.
 * The cost is the ingredients priced at current Bazaar / sold prices; if any ingredient has no price, no cost is shown.
 */
public final class RecipeData {
    private RecipeData() {}

    private static final String BASE = "https://raw.githubusercontent.com/NotEnoughUpdates/NotEnoughUpdates-REPO/master/items/";
    private static final long KEEP_MS = 7L * 86_400_000L;
    private static final long RETRY_MS = 10L * 60_000L;
    private static final long PRICE_CACHE_MS = 300_000L;

    /** A crafting recipe: ingredient id -> amount, and how many items one craft makes. */
    public record Recipe(Map<String, Long> ingredients, int count) {}

    private enum State { LOADING, NONE, HAVE, RETRY }

    private static final class Entry {
        volatile State state = State.LOADING;
        volatile Recipe recipe;
        volatile long retryAt;
        volatile long cost = -1;
        volatile long costAt;
    }

    private static final Map<String, Entry> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService IO = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "valtrin-recipes");
        t.setDaemon(true);
        return t;
    });
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private static Path dir;

    public static void init(Path dataDir) {
        dir = dataDir.resolve("recipes");
    }

    /** Cost of crafting one {@code id}, or -1 when it has no recipe, is not loaded yet, or an ingredient has no price. */
    public static long cost(String id) {
        if (id == null || id.isEmpty() || id.contains(";") || id.equals("PET")) return -1;
        if (!FlipConfig.INSTANCE.recipeLookups) return -1; // lookups switched off: nothing is sent to GitHub
        long now = System.currentTimeMillis();
        Entry e = CACHE.get(id);
        if (e == null) {
            e = new Entry();
            Entry prev = CACHE.putIfAbsent(id, e);
            if (prev == null) {
                final Entry ne = e;
                IO.execute(() -> load(id, ne));
                return -1;
            }
            e = prev;
        }
        if (e.state == State.RETRY && now >= e.retryAt) {
            e.state = State.LOADING;
            final Entry ne = e;
            IO.execute(() -> load(id, ne));
            return -1;
        }
        if (e.state != State.HAVE || e.recipe == null) return -1;
        if (now - e.costAt < PRICE_CACHE_MS) return e.cost;
        long c = price(e.recipe);
        e.cost = c;
        e.costAt = now;
        return c;
    }

    /**
     * The most expensive ingredients (up to 5, in-game names) with the price of each and where it came from, one line each,
     * e.g. "8x Enchanted Hay Bale 1.22M (bazaar)". Null when the recipe is not known. Shown only while Shift is held.
     */
    public static java.util.List<String> breakdownLines(String id) {
        Entry e = id == null ? null : CACHE.get(id);
        if (e == null || e.state != State.HAVE || e.recipe == null) return null;
        java.util.List<String[]> parts = new java.util.ArrayList<>();
        java.util.List<Long> totals = new java.util.ArrayList<>();
        for (Map.Entry<String, Long> in : e.recipe.ingredients().entrySet()) {
            String k = in.getKey();
            long unit = k.equals("SKYBLOCK_COIN") ? 1 : Pricing.itemValue(k);
            String src = k.equals("SKYBLOCK_COIN") ? "coins" : Pricing.itemSource(k);
            long total = Math.max(0, unit) * in.getValue();
            totals.add(total);
            parts.add(new String[]{in.getValue() + "x " + Pricing.displayName(k), Format.compact(total), src});
        }
        Integer[] order = new Integer[parts.size()];
        for (int i = 0; i < order.length; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Long.compare(totals.get(b), totals.get(a)));
        java.util.List<String> out = new java.util.ArrayList<>();
        int shown = Math.min(5, order.length);
        for (int i = 0; i < shown; i++) {
            String[] p = parts.get(order[i]);
            out.add(p[0] + " " + p[1] + " (" + p[2] + ")");
        }
        if (order.length > shown) out.add("+" + (order.length - shown) + " cheaper ingredients");
        return out;
    }

    private static long price(Recipe r) {
        long sum = 0;
        for (Map.Entry<String, Long> in : r.ingredients().entrySet()) {
            long unit = in.getKey().equals("SKYBLOCK_COIN") ? 1 : Pricing.itemValue(in.getKey());
            if (unit <= 0) return -1;
            sum += unit * in.getValue();
        }
        return sum <= 0 ? -1 : sum / Math.max(1, r.count());
    }

    private static void load(String id, Entry e) {
        if (!Validate.itemId(id)) { e.state = State.NONE; return; } // never build a path or URL from an odd id
        try {
            Path f = dir == null ? null : dir.resolve(id + ".json");
            Path none = dir == null ? null : dir.resolve(id + ".none");
            long now = System.currentTimeMillis();
            if (f != null && Files.exists(f) && now - Files.getLastModifiedTime(f).toMillis() < KEEP_MS) {
                finish(e, parse(Files.readString(f)));
                return;
            }
            if (none != null && Files.exists(none) && now - Files.getLastModifiedTime(none).toMillis() < KEEP_MS) {
                e.state = State.NONE;
                return;
            }
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(BASE + id + ".json"))
                    .timeout(Duration.ofSeconds(10)).header("User-Agent", "Valtrin").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 200) {
                if (f != null) {
                    Files.createDirectories(dir);
                    Files.writeString(f, r.body());
                }
                finish(e, parse(r.body()));
            } else if (r.statusCode() == 404) {
                if (none != null) {
                    Files.createDirectories(dir);
                    Files.writeString(none, "");
                }
                e.state = State.NONE;
            } else {
                retryLater(e);
            }
        } catch (IOException | InterruptedException | RuntimeException ex) {
            retryLater(e);
        }
    }

    private static void finish(Entry e, Recipe r) {
        e.recipe = r;
        e.state = r == null ? State.NONE : State.HAVE;
    }

    private static void retryLater(Entry e) {
        e.retryAt = System.currentTimeMillis() + RETRY_MS;
        e.state = State.RETRY;
    }

    /** The item's first crafting-table recipe (or, if there is none, its first Forge recipe), or null if it has neither. */
    static Recipe parse(String json) {
        try {
            return parseUnsafe(json);
        } catch (RuntimeException e) {
            return null; // damaged or hostile recipe data means "no recipe", never an error
        }
    }

    private static Recipe parseUnsafe(String json) {
        JsonObject o = JsonParser.parseString(json).getAsJsonObject();
        if (o.has("recipes") && o.get("recipes").isJsonArray()) {
            JsonArray arr = o.getAsJsonArray("recipes");
            Recipe forge = null;
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject r = el.getAsJsonObject();
                String type = r.has("type") ? r.get("type").getAsString() : "";
                if (type.equals("crafting")) return grid(r); // a crafting-table recipe wins
                if (type.equals("forge") && forge == null) forge = forge(r); // Forge recipes (drills, Divan items...) count too
            }
            return forge;
        }
        if (o.has("recipe") && o.get("recipe").isJsonObject()) return grid(o.getAsJsonObject("recipe")); // older files
        return null;
    }

    /** A Forge recipe: "inputs" is a list of "ITEM_ID:amount" strings (no grid slots). */
    private static Recipe forge(JsonObject r) {
        if (!r.has("inputs") || !r.get("inputs").isJsonArray()) return null;
        Map<String, Long> ing = new LinkedHashMap<>();
        for (JsonElement el : r.getAsJsonArray("inputs")) {
            if (!el.isJsonPrimitive()) return null;
            String s = el.getAsString().trim();
            if (s.isEmpty()) continue;
            int colon = s.lastIndexOf(':');
            String id = colon < 0 ? s : s.substring(0, colon);
            long n = 1;
            if (colon >= 0) {
                try {
                    n = Long.parseLong(s.substring(colon + 1));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            if (id.isEmpty() || n <= 0 || n > 1_000_000_000L) return null;
            ing.merge(id, n, Long::sum);
        }
        if (ing.isEmpty()) return null;
        int count = r.has("count") && r.get("count").isJsonPrimitive() ? Math.max(1, r.get("count").getAsInt()) : 1;
        return new Recipe(ing, count);
    }

    private static Recipe grid(JsonObject r) {
        Map<String, Long> ing = new LinkedHashMap<>();
        for (String slot : new String[]{"A1", "A2", "A3", "B1", "B2", "B3", "C1", "C2", "C3"}) {
            if (!r.has(slot) || !r.get(slot).isJsonPrimitive()) continue;
            String s = r.get(slot).getAsString().trim();
            if (s.isEmpty()) continue;
            int colon = s.lastIndexOf(':');
            String id = colon < 0 ? s : s.substring(0, colon);
            long n = 1;
            if (colon >= 0) {
                try {
                    n = Long.parseLong(s.substring(colon + 1));
                } catch (NumberFormatException ex) {
                    return null;
                }
            }
            ing.merge(id, n, Long::sum);
        }
        if (ing.isEmpty()) return null;
        int count = r.has("count") && r.get("count").isJsonPrimitive() ? Math.max(1, r.get("count").getAsInt()) : 1;
        return new Recipe(ing, count);
    }
}
