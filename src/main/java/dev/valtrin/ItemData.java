// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parts of a SkyBlock item that matter for pricing. Built from the item's "extra attributes"
 * (the same data whether it came from an auction listing or from an item in your menu).
 *
 * Items are only compared with items that share the same {@link #key()}:
 * item id + stars + recombobulated or not + rarity (pets: type + rarity + level range).
 * Everything else on the item (enchants, gems, gem slots, scrolls, potato books...) is priced
 * separately as a modifier and added to / removed from the comparison.
 *
 * @param base     comparison key without rarity: id|stars|R or N, or PET_type_rarity_levelBucket
 * @param tier     rarity read from the item (null if unknown)
 * @param stars    upgrade_level (dungeon stars and master stars); part of the key, not priced
 * @param recomb   true if the item was recombobulated (including auto-recombobulated armor)
 * @param enchants enchant name -> level
 * @param gems     applied gems as {slotKey, gemType, quality}
 * @param unlocked unlocked gem slot keys such as "COMBAT_0" or "SAPPHIRE_1"
 * @param extras   other applied items: scrolls, potato books, Art of War, pet item, ...
 */
record ItemData(String id, String base, String tier, int stars, boolean recomb,
                Map<String, Integer> enchants, List<String[]> gems, List<String> unlocked,
                List<Extra> extras) {

    /** Marker extra for dyed / skinned items. Never priced. */
    static final String COSMETIC = "__COSMETIC__";

    /** Marker extra holding a kill-bonus tier (e.g. Enderman Bulwark). Compared, never priced. */
    static final String KILL_TIER = "KILL_TIER";

    /** Unit marker: coins paid into a Midas weapon, in millions (priced 1M each). */
    static final String MIDAS_COINS = "MIDAS_COINS";

    /** Dungeon item quality (baseStatBoostPercentage): compared by bucket, never priced. */
    static final String STAT_BOOST = "STAT_BOOST";
    /** Dungeon floor the item dropped from (item_tier). Compared only, and never loosened (a Floor 7 piece is not a Floor 1 piece). */
    static final String FLOOR_TIER = "FLOOR_TIER";

    /** Reforges that change the price a lot; other reforges are ignored. Compared, never priced. */
    private static final Set<String> PREMIUM_REFORGES = Set.of(
            "fabled", "withered", "ancient", "renowned", "giant", "necrotic", "loving", "warped", "spiritual");

    /**
     * Extras that change an item's price but have no price of their own: they are only compared, never added to a
     * value. (Kill tiers, dungeon item quality, reforges, runes, attributes, pet candy.)
     */
    static boolean compareOnly(String id) {
        return KILL_TIER.equals(id) || STAT_BOOST.equals(id) || FLOOR_TIER.equals(id) || id.startsWith("REFORGE_") || id.startsWith("RUNE_")
                || id.startsWith("ATTR_") || id.startsWith("PET_CANDY");
    }

    /** How often each item data field name was seen (for /flip keys). */
    static final java.util.concurrent.ConcurrentHashMap<String, Integer> SEEN_KEYS = new java.util.concurrent.ConcurrentHashMap<>();

    /** Field names the mod reads (shown as such in /flip keys). */
    static final Set<String> KNOWN_KEYS = Set.of(
            "id", "upgrade_level", "rarity_upgrades", "is_shiny", "enchantments", "gems", "hot_potato_count",
            "art_of_war_count", "wood_singularity_count", "farming_for_dummies_count", "tuned_transmission",
            "mana_disintegrator_count", "jalapeno_count", "polarvoid", "ethermerge", "ability_scroll",
            "drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module", "power_ability_scroll", "stats_book", "eman_kills", "dye_item", "skin",
            "petInfo", "attributes", "baseStatBoostPercentage", "item_tier", "modifier", "winning_bid", "additional_coins",
            "talisman_enrichment", "artOfPeaceApplied", "wet_book_count", "hook", "line", "sinker", "runes",
            "new_years_cake", "party_hat_year", "year", "potion", "potion_type", "effects", "potion_level", "splash", "enhanced", "extended");

    /** Total kills needed for each Enderman Bulwark tier 0..18 (tier 18 = max). */
    private static final int[] EMAN_TIERS = {0, 100, 200, 300, 500, 800, 1200, 1750, 2500, 3500, 5000,
            10000, 25000, 50000, 75000, 100000, 125000, 150000, 200000};

    /** Bonus tier for a kill count, or -1 when the item has no kill counter. */
    static int killTier(int kills) {
        if (kills < 0) return -1;
        int t = 0;
        for (int i = 0; i < EMAN_TIERS.length; i++) if (kills >= EMAN_TIERS[i]) t = i;
        return t;
    }

    /** True for items with attributes (Crimson / Kuudra gear): the plain group price is meaningless for them. */
    boolean hasAttributes() {
        for (Extra e : extras) if (e.itemId().startsWith("ATTR_")) return true;
        return false;
    }

    /** True for dyed or skinned items (their price includes a premium we deliberately don't try to value). */
    boolean cosmetic() {
        for (Extra e : extras) if (COSMETIC.equals(e.itemId())) return true;
        return false;
    }

    /** An applied upgrade item and how many of it. Priced from Bazaar, else lowest BIN. */
    record Extra(String itemId, int count) {}

    /** Marker for items we cannot compare reliably (books, runes, attribute items, parse failures). */
    static final ItemData NONE = new ItemData("", "", null, 0, false, Map.of(), List.of(), List.of(), List.of());

    private static final Set<String> GEM_TYPES = Set.of(
            "AMBER", "AMETHYST", "JADE", "JASPER", "OPAL", "PERIDOT",
            "RUBY", "SAPPHIRE", "TOPAZ", "CITRINE", "ONYX", "AQUAMARINE");

    private static final Pattern PET_LEVEL = Pattern.compile("\\[Lvl\\s*(\\d+)]");

    /** Simple "count field -> item" upgrades. (Recombobulator is NOT here: it is part of the key.) */
    private static final String[][] COUNTED = {
            {"art_of_war_count", "THE_ART_OF_WAR"},
            {"wood_singularity_count", "WOOD_SINGULARITY"},
            {"farming_for_dummies_count", "FARMING_FOR_DUMMIES"},
            {"tuned_transmission", "TRANSMISSION_TUNER"},
            {"mana_disintegrator_count", "MANA_DISINTEGRATOR"},
            {"jalapeno_count", "JALAPENO_BOOK"},
            {"polarvoid", "POLARVOID_BOOK"},
            {"ethermerge", "ETHERWARP_MERGER"},
    };

    /** Fields whose value is itself an item id (drill parts, dyes). */
    private static final String[] ITEM_FIELDS = {
            "drill_part_engine", "drill_part_fuel_tank", "drill_part_upgrade_module", "power_ability_scroll"
    };

    private static final String[] ROD_PARTS = {"hook", "line", "sinker"};

    // Order matters: longer / overlapping names first.
    private static final String[][] TIER_WORDS = {
            {"VERY SPECIAL", "VERY_SPECIAL"}, {"SPECIAL", "SPECIAL"}, {"ULTIMATE", "ULTIMATE"},
            {"DIVINE", "DIVINE"}, {"MYTHIC", "MYTHIC"}, {"LEGENDARY", "LEGENDARY"}, {"EPIC", "EPIC"},
            {"UNCOMMON", "UNCOMMON"}, {"RARE", "RARE"}, {"COMMON", "COMMON"}
    };

    /** Full comparison key: base + rarity. Sales, volume and price groups all use this. */
    String key() {
        return base + "|" + (tier == null ? "?" : tier);
    }

    /** Marks an item as having Etherwarp (seen in its ability line) when the item data didn't already say so. */
    ItemData withEtherwarpFromLore(List<String> lore) {
        boolean has = false;
        for (String l : lore) if (l.replaceAll("§.", "").contains("Ability: Ether Transmission")) { has = true; break; }
        if (!has) return this;
        for (Extra e : extras) if ("ETHERWARP_MERGER".equals(e.itemId())) return this;
        List<Extra> ex = new ArrayList<>(extras);
        ex.add(new Extra("ETHERWARP_MERGER", 1));
        return new ItemData(id, base, tier, stars, recomb, enchants, gems, unlocked, ex);
    }

    ItemData withTier(String newTier) {
        return new ItemData(id, base, newTier, stars, recomb, enchants, gems, unlocked, extras);
    }

    /** Short human-readable description of what this item is compared against. */
    String label() {
        StringBuilder sb = new StringBuilder(tier == null ? "unknown rarity" : tier.toLowerCase(Locale.ROOT).replace('_', ' '));
        if (stars > 0) sb.append(", ").append(stars).append(" star").append(stars == 1 ? "" : "s");
        if (recomb) sb.append(", recombobulated");
        return sb.toString();
    }

    /**
     * Reads the rarity from one lore line, e.g. "LEGENDARY LEGGINGS". Recombobulated items have an
     * obfuscated single character on each side ("a LEGENDARY LEGGINGS a"), which is removed first.
     * Returns null if the line is not a rarity line.
     */
    static String tierFromLine(String line) {
        if (line == null) return null;
        String s = line.replaceAll("§.", "").trim();
        s = s.replaceFirst("^\\S\\s+", "");
        s = s.replaceFirst("\\s+\\S$", "");
        s = s.trim();
        if (s.startsWith("SHINY ")) s = s.substring(6); // "SHINY MYTHIC DUNGEON BOOTS"
        if (s.isEmpty() || !s.equals(s.toUpperCase(Locale.ROOT))) return null;
        for (String[] t : TIER_WORDS) {
            if (s.equals(t[0]) || s.startsWith(t[0] + " ")) return t[1];
        }
        return null;
    }

    /** The rarity line is near the end of the lore, so search from the bottom. */
    static String tierFromLore(List<String> lore) {
        for (int i = lore.size() - 1; i >= 0; i--) {
            String t = tierFromLine(lore.get(i));
            if (t != null) return t;
        }
        return null;
    }

    /**
     * @param plainName the item's display name without color codes (used for the pet level)
     */
    static ItemData fromExtra(Map<String, Object> extra, String plainName) {
        String id = str(extra.get("id"));
        if (id.isEmpty()) return NONE;
        for (String key : extra.keySet()) {
            SEEN_KEYS.merge(key, 1, Integer::sum);
        }
        if (SEEN_KEYS.size() > 3000) SEEN_KEYS.clear();
        if (id.equals("ENCHANTED_BOOK") || id.equals("RUNE")) return NONE;

        if (id.equals("PET")) return pet(extra, plainName);

        int stars = num(extra.get("upgrade_level"));
        boolean recomb = num(extra.get("rarity_upgrades")) > 0;
        boolean shiny = num(extra.get("is_shiny")) > 0;
        String base = id + "|" + stars + "|" + (recomb ? "R" : "N") + (shiny ? "|S" : "");
        // Event items that exist once per year are different items in different years.
        int cakeYear = num(extra.get("new_years_cake"));
        if (cakeYear > 0) base += "|Y" + cakeYear;
        int hatYear = num(extra.get("party_hat_year"));
        if (hatYear > 0) base += "|Y" + hatYear;
        int year = num(extra.get("year"));
        if (year > 0 && cakeYear == 0 && hatYear == 0) base += "|Y" + year;
        // Potions: the potion kind, level and splash/drinkable are what make them different items.
        if (id.equals("POTION")) {
            String kind = str(extra.get("potion")).toLowerCase(Locale.ROOT);
            if (!kind.isEmpty()) base += "|" + kind + "_" + num(extra.get("potion_level"));
            else {
                // Newer potions have no "potion" field: use "potion_type" or the list of "effects" (effect + level only).
                String ptype = str(extra.get("potion_type")).toLowerCase(Locale.ROOT);
                if (!ptype.isEmpty()) base += "|" + ptype + "_" + num(extra.get("potion_level"));
                else if (extra.get("effects") instanceof List<?> fx) {
                    List<String> parts = new ArrayList<>();
                    for (Object o : fx) {
                        Map<String, Object> m = map(o);
                        String eff = str(m.get("effect")).toLowerCase(Locale.ROOT);
                        if (!eff.isEmpty()) parts.add(eff + "_" + num(m.get("level")));
                    }
                    java.util.Collections.sort(parts);
                    if (!parts.isEmpty()) base += "|FX:" + String.join("+", parts);
                }
            }
            if (num(extra.get("splash")) > 0) base += "|SPLASH";
            if (num(extra.get("enhanced")) > 0) base += "|ENH";
            if (num(extra.get("extended")) > 0) base += "|EXT";
        }

        Map<String, Integer> enchants = new HashMap<>();
        for (Map.Entry<String, Object> e : map(extra.get("enchantments")).entrySet()) {
            int lvl = num(e.getValue());
            if (ModTokens.unmaxedUltimate(e.getKey(), lvl)) continue; // an unmaxed ultimate is ignored completely
            enchants.put(e.getKey(), lvl);
        }

        Map<String, Object> gemsMap = map(extra.get("gems"));
        List<String> unlocked = new ArrayList<>();
        if (gemsMap.get("unlocked_slots") instanceof List<?> l) {
            for (Object o : l) if (o instanceof String s) unlocked.add(s);
        }
        List<String[]> gems = new ArrayList<>();
        for (Map.Entry<String, Object> e : gemsMap.entrySet()) {
            String key = e.getKey();
            if (key.equals("unlocked_slots") || key.endsWith("_gem")) continue;
            Object v = e.getValue();
            String quality = v instanceof String s ? s : str(map(v).get("quality"));
            int us = key.lastIndexOf('_');
            String type = us > 0 ? key.substring(0, us) : key;
            if (!GEM_TYPES.contains(type)) type = str(gemsMap.get(key + "_gem")); // COMBAT_0, UNIVERSAL_0, ...
            if (!quality.isEmpty() && !type.isEmpty()) gems.add(new String[]{key, type, quality});
        }

        List<Extra> extras = new ArrayList<>();
        int potatoes = num(extra.get("hot_potato_count"));
        if (potatoes > 0) {
            extras.add(new Extra("HOT_POTATO_BOOK", Math.min(potatoes, 10)));
            if (potatoes > 10) extras.add(new Extra("FUMING_POTATO_BOOK", potatoes - 10));
        }
        for (String[] c : COUNTED) {
            int n = num(extra.get(c[0]));
            if (n > 0) extras.add(new Extra(c[1], n));
        }
        if (extra.get("ability_scroll") instanceof List<?> scrolls) { // Wither Impact: Implosion, Shadow Warp, Wither Shield
            for (Object o : scrolls) if (o instanceof String s && !s.isEmpty()) extras.add(new Extra(s.toUpperCase(), 1));
        }
        // Book of Stats (the kill counter on the item matters less than having the book on it).
        if (extra.get("stats_book") != null) extras.add(new Extra("BOOK_OF_STATS", 1));
        for (String f : ITEM_FIELDS) {
            String v = str(extra.get(f));
            if (!v.isEmpty()) {
                extras.add(new Extra(v.toUpperCase(), 1));
                ModTokens.ALWAYS.add(v.toUpperCase());
            }
        }
        // Fishing rod parts: a compound with the part's id ("part"), or just the id.
        for (String f : ROD_PARTS) {
            Object o = extra.get(f);
            String part = o instanceof String s ? s : str(map(o).get("part"));
            if (!part.isEmpty()) {
                extras.add(new Extra(part.toUpperCase(), 1));
                ModTokens.ALWAYS.add(part.toUpperCase());
            }
        }
        // Dungeon item quality (0-50): full quality (50) is worth much more; the rest is compared in steps of 10.
        if (extra.get("baseStatBoostPercentage") instanceof Number q) {
            int pct = q.intValue();
            extras.add(new Extra(STAT_BOOST, pct >= 50 ? 50 : Math.max(0, pct) / 10 * 10));
        }
        if (extra.get("baseStatBoostPercentage") instanceof Number && extra.get("item_tier") instanceof Number ft && ft.intValue() > 0) {
            extras.add(new Extra(FLOOR_TIER, ft.intValue()));
        }
        // Reforge: only the ones that change the price a lot.
        String reforge = str(extra.get("modifier")).toLowerCase(Locale.ROOT);
        if (PREMIUM_REFORGES.contains(reforge)) extras.add(new Extra("REFORGE_" + reforge.toUpperCase(Locale.ROOT), 1));
        // Midas weapons: the coins paid into them are most of their price.
        if (id.startsWith("MIDAS")) {
            long coins = lng(extra.get("winning_bid")) + lng(extra.get("additional_coins"));
            long millions = coins / 1_000_000L;
            if (millions > 0) extras.add(new Extra(MIDAS_COINS, (int) Math.min(millions, 1000)));
        }
        // Accessory enrichment, Art of Peace, Wet Book (priced from the item if someone sells it).
        // Accessory enrichments are deliberately NOT tracked: they have little value and are being removed from the game.
        // (the key stays in KNOWN_KEYS so it is not reported as unknown)
        if (num(extra.get("artOfPeaceApplied")) > 0) extras.add(new Extra("THE_ART_OF_PEACE", 1));
        int wet = num(extra.get("wet_book_count"));
        if (wet > 0) extras.add(new Extra("WET_BOOK", wet));
        // Applied runes (compared only; the rune's own price is not known from the item).
        for (Map.Entry<String, Object> r : map(extra.get("runes")).entrySet()) {
            extras.add(new Extra("RUNE_" + r.getKey().toUpperCase(Locale.ROOT), Math.max(1, num(r.getValue()))));
        }
        // Attribute items (Crimson / Kuudra gear): only compared with items that have exactly the same attributes and levels.
        for (Map.Entry<String, Object> at : map(extra.get("attributes")).entrySet()) {
            extras.add(new Extra("ATTR_" + at.getKey().toUpperCase(Locale.ROOT), Math.max(1, num(at.getValue()))));
        }
        // Final Destination armor: its defense bonus grows with Enderman kills, so compare within the same bonus tier.
        if (id.startsWith("FINAL_DESTINATION_")) {
            int tier = killTier(num(extra.get("eman_kills")));
            if (tier >= 0) extras.add(new Extra(KILL_TIER, tier));
        }
        // Dyes and skins are cosmetic and hard to value: they add no value and the item is kept out of comparisons.
        // The dye / skin items themselves (DYE_*, *_SKIN_*) are never marked: they stay normal flippable items.
        boolean isCosmeticItem = id.startsWith("DYE_") || id.contains("SKIN");
        if (!isCosmeticItem && (!str(extra.get("dye_item")).isEmpty() || !str(extra.get("skin")).isEmpty())) {
            extras.add(new Extra(COSMETIC, 1));
        }

        return new ItemData(id, base, null, stars, recomb, enchants, gems, unlocked, extras);
    }

    private static ItemData pet(Map<String, Object> extra, String plainName) {
        try {
            JsonObject pet = JsonParser.parseString(str(extra.get("petInfo"))).getAsJsonObject();
            int level = -1;
            Matcher m = PET_LEVEL.matcher(plainName == null ? "" : plainName);
            if (m.find()) level = Integer.parseInt(m.group(1));

            String base = "PET_" + pet.get("type").getAsString() + "_" + pet.get("tier").getAsString()
                    + "_" + levelBucket(level);

            List<Extra> extras = new ArrayList<>();
            if (pet.has("heldItem") && !pet.get("heldItem").isJsonNull()) {
                extras.add(new Extra(pet.get("heldItem").getAsString().toUpperCase(), 1));
            }
            if (pet.has("candyUsed") && !pet.get("candyUsed").isJsonNull() && pet.get("candyUsed").getAsInt() > 0) {
                extras.add(new Extra("PET_CANDY", 1)); // candy-boosted pets sell for less: compared, never priced
            }
            if (pet.has("skin") && !pet.get("skin").isJsonNull()) {
                extras.add(new Extra(COSMETIC, 1));
            }
            String petTier = pet.has("tier") ? pet.get("tier").getAsString().toUpperCase() : null;
            return new ItemData("PET", base, petTier, 0, false, Map.of(), List.of(), List.of(), extras);
        } catch (Exception e) {
            return NONE;
        }
    }

    /** Pets are compared within a level bucket (prices scale hard with level). */
    static String levelBucket(int level) {
        if (level < 0) return "L?";
        if (level <= 1) return "L1";
        if (level == 100) return "L100";
        if (level >= 200) return "L200";
        if (level < 100) return "L" + (level / 10 * 10);
        return "H" + (level / 25 * 25);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }

    private static String str(Object o) {
        return o instanceof String s ? s : "";
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static int num(Object o) {
        return o instanceof Number n ? n.intValue() : 0;
    }
}
