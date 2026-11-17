// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * An item's modifiers as separate tokens (enchants, gems, unlocked slots, potato books, scrolls...),
 * stored with every sale and every listing. Prices are then compared between items that have the same
 * IMPORTANT modifiers (the ones worth real coins), so items are compared by their modifier combination.
 */
final class ModTokens {
    private ModTokens() {}

    /** @param family what kind of modifier (e.g. "e:ultimate_wise", "g:COMBAT_0", "x:HOT_POTATO_BOOK"); variant = its level / quality / count */
    record Tok(String family, String variant) {
        String text() { return family + "=" + variant; }
    }

    /**
     * Version of the token set recorded with every sale. Raise it whenever a new kind of token is added or a rule
     * changes, so sales recorded earlier (which cannot have the new tokens) are only used as a fallback.
     * 2 = everything up to v17 (the value when a sale line has no version column), 3 = v19 (floor tier, power scroll,
     * Book of Stats, potion fields, unmaxed ultimates dropped).
     */
    static final int SCHEMA = 3;
    static final int LEGACY_SCHEMA = 2;

    /** Highest level of ultimate enchants whose maximum is not 5. */
    private static final Map<String, Integer> ULTIMATE_MAX = Map.of("ultimate_flowstate", 3, "ultimate_one_for_all", 1);

    /** True for an ultimate enchant below its maximum level: those are ignored in pricing and matching. */
    static boolean unmaxedUltimate(String enchantKey, int level) {
        String k = enchantKey.toLowerCase(Locale.ROOT);
        return k.startsWith("ultimate_") && level < ULTIMATE_MAX.getOrDefault(k, 5);
    }

    /** Removes unmaxed ultimate enchants from stored tokens (older sales still carry them). */
    static List<Tok> normalize(List<Tok> in) {
        List<Tok> out = null;
        for (int i = 0; i < in.size(); i++) {
            Tok t = in.get(i);
            boolean drop = false;
            if (t.family().startsWith("x:TALISMAN_ENRICHMENT_")) drop = true; // enrichments are ignored (older sales still carry them)
            else if (t.family().startsWith("e:ultimate_")) {
                try { drop = unmaxedUltimate(t.family().substring(2), Integer.parseInt(t.variant())); } catch (NumberFormatException ignored) {}
            }
            if (drop && out == null) out = new ArrayList<>(in.subList(0, i));
            if (!drop && out != null) out.add(t);
        }
        return out == null ? in : out;
    }

    /** Items that sell for less than this get no value for an unlocked gem slot. */
    static final long SLOT_MIN_ITEM_VALUE = 10_000_000;

    /** A modifier worth less than this is ignored when matching items. */
    static final long IMPORTANT_MIN = 100_000;

    /**
     * A pet's held item (Tier Boost, Lucky Clover, Washed-up Souvenir...) only counts when matching pets, listings and sales
     * if it is worth at least this much. Cheaper held items are ignored (like any other cheap modifier), so a pet with a
     * 300k item is compared with the same pet with another or no item instead of finding no match at all.
     */
    static final long HELD_ITEM_MIN = 800_000;

    /** True for the held item of a pet (not candy, not a skin, not a kill tier: those have their own rules). */
    static boolean heldItem(Tok t, String itemId) {
        if (!"PET".equals(itemId) || !t.family().startsWith("x:")) return false;
        String id = t.family().substring(2).toUpperCase(Locale.ROOT);
        return !id.startsWith("PET_CANDY") && !id.equals(ItemData.COSMETIC) && !id.equals(ItemData.KILL_TIER);
    }

    /** Value from which this modifier counts when comparing items. */
    static long importantMin(Tok t, String itemId) {
        // Pet candy never splits pets into groups: in the recorded sales a candied pet sold for the same price as a clean
        // one (median ratio 1.01 over 7 pet groups), while splitting them left too few sales to price either.
        if ("PET".equals(itemId) && t.family().startsWith("x:PET_CANDY")) return Long.MAX_VALUE;
        return heldItem(t, itemId) ? HELD_ITEM_MIN : IMPORTANT_MIN;
    }

    /** Part / upgrade item ids (drill parts, fishing rod parts) that always count as important even when their price is unknown. */
    static final java.util.Set<String> ALWAYS = ConcurrentHashMap.newKeySet();

    static List<Tok> of(ItemData d) {
        List<Tok> out = new ArrayList<>();
        d.enchants().entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(e ->
                out.add(new Tok("e:" + e.getKey(), String.valueOf(e.getValue()))));
        for (String[] g : d.gems()) out.add(new Tok("g:" + g[0], g[2] + ":" + g[1]));
        for (String u : d.unlocked()) out.add(new Tok("u:" + u, "1"));
        for (ItemData.Extra x : d.extras()) {
            if (ItemData.COSMETIC.equals(x.itemId())) continue;
            out.add(new Tok("x:" + x.itemId(), String.valueOf(x.count())));
        }
        return out;
    }

    /** Rebuilds an item from its stored tokens (the reverse of {@link #of}); used by the replay tool. */
    static ItemData toItemData(String id, String base, String tier, List<Tok> toks) {
        Map<String, Integer> enchants = new java.util.HashMap<>();
        List<String[]> gems = new ArrayList<>();
        List<String> unlocked = new ArrayList<>();
        List<ItemData.Extra> extras = new ArrayList<>();
        for (Tok t : toks) {
            String f = t.family();
            try {
                if (f.startsWith("e:")) enchants.put(f.substring(2), Integer.parseInt(t.variant()));
                else if (f.startsWith("g:")) {
                    String[] qt = t.variant().split(":");
                    if (qt.length == 2) gems.add(new String[]{f.substring(2), qt[1], qt[0]});
                } else if (f.startsWith("u:")) unlocked.add(f.substring(2));
                else if (f.startsWith("x:")) extras.add(new ItemData.Extra(f.substring(2), Integer.parseInt(t.variant())));
            } catch (NumberFormatException ignored) {}
        }
        return new ItemData(id, base, tier, 0, false, enchants, gems, unlocked, extras);
    }

    static String encode(List<Tok> toks) {
        StringBuilder sb = new StringBuilder();
        for (Tok t : toks) {
            if (sb.length() > 0) sb.append(';');
            sb.append(t.text());
        }
        return sb.toString();
    }

    static List<Tok> decode(String s) {
        List<Tok> out = new ArrayList<>();
        if (s == null || s.isEmpty()) return out;
        for (String part : s.split(";")) {
            int i = part.lastIndexOf('=');
            if (i > 0) out.add(new Tok(part.substring(0, i), part.substring(i + 1)));
        }
        return out;
    }

    // ---- coin value of one token (cached until prices are refreshed)

    private static final Map<String, Long> VALUES = new ConcurrentHashMap<>();
    private static volatile long valuesFor = -1;

    static long value(Tok t, String itemId) {
        long epoch = Pricing.bazaarAt + Pricing.itemsAt;
        if (epoch != valuesFor) { VALUES.clear(); valuesFor = epoch; }
        String k = t.family().startsWith("u:") ? t.text() + "@" + itemId : t.text();
        Long v = VALUES.get(k);
        if (v == null) {
            v = compute(t, itemId);
            VALUES.put(k, v);
        }
        return v;
    }

    private static long compute(Tok t, String itemId) {
        String f = t.family();
        try {
            if (f.startsWith("e:")) return Pricing.enchantValue(f.substring(2), Integer.parseInt(t.variant()));
            if (f.startsWith("g:")) {
                String[] qt = t.variant().split(":");
                return qt.length == 2 ? Pricing.gemValue(qt[0], qt[1]) : 0;
            }
            // Unlocked gem slots: cheap items (Fig armor...) sold with the slot for no more than plain ones, so the slot adds nothing.
            // Expensive endgame items (Divan, Hyperion...) have costly slots that buyers do pay for: half the unlock cost counts there.
            if (f.startsWith("u:")) {
                long item = Pricing.itemValue(itemId);
                if (item < SLOT_MIN_ITEM_VALUE) return 0;
                return Pricing.slotValue(itemId, f.substring(2)) / 2;
            }
            // Kill tiers are only compared. Just above the importance cutoff, so they are loosened before real modifiers.
            if (f.equals("x:" + ItemData.KILL_TIER)) return IMPORTANT_MIN + Integer.parseInt(t.variant());
            // Etherwarp is a big price jump even when its listing price is unknown right now: always an important modifier.
            if (f.equals("x:ETHERWARP_MERGER")) return Math.max(IMPORTANT_MIN, Pricing.extraValue("ETHERWARP_MERGER", 1));
            if (f.startsWith("x:")) {
                String id = f.substring(2).toUpperCase(Locale.ROOT);
                int n = Integer.parseInt(t.variant());
                // Things that change the price but have no price of their own: compared, never priced.
                // Just above the importance cutoff, so they are loosened before real modifiers.
                if (ItemData.compareOnly(id)) return IMPORTANT_MIN + Math.max(0, n);
                long v = Pricing.extraValue(id, n);
                // Drill / rod parts matter even while no one sells that part right now.
                if (ALWAYS.contains(id)) return Math.max(IMPORTANT_MIN, v);
                return v;
            }
        } catch (NumberFormatException ignored) {}
        return 0;
    }
}
