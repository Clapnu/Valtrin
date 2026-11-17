// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Locale;
import java.util.Set;

/**
 * The messages between the Valtrin server and the mod (JSON inside server-sent events). The server builds them from
 * its own data; the mod treats everything it receives as untrusted: every field is checked here and anything odd makes
 * the whole message be ignored. Nothing in a message can run a command: the mod builds the click action itself from a
 * validated auction id.
 */
final class Wire {
    private Wire() {}

    /** Largest message the mod will read. Real messages are well under 4 KB. */
    static final int MAX_DATA = 16_384;
    private static final long MAX_COINS = 10_000_000_000_000L; // 10 trillion, far above any real price
    private static final Set<String> CONF = Set.of("HIGH", "MED", "LOW");

    record Flip(FlipAlert alert, long at) {}
    record Notice(boolean good, String lead, String rest) {}
    record Hello(String tier, long delayMs, String version) {}

    // ------------------------------------------------------------------ encoding (server side)

    static String encodeFlip(FlipAlert f, long at) {
        JsonObject o = new JsonObject();
        o.addProperty("at", at);
        o.addProperty("name", f.name());
        o.addProperty("tier", f.tier());
        o.addProperty("uuid", f.uuid());
        if (f.sellerUuid() != null) o.addProperty("sellerUuid", f.sellerUuid());
        o.addProperty("buy", f.buy());
        o.addProperty("sell", f.sell());
        o.addProperty("profit", f.profit());
        o.addProperty("margin", f.margin());
        o.addProperty("volume", f.volume());
        o.addProperty("detail", f.detail());
        if (f.sellerName() != null) o.addProperty("sellerName", f.sellerName());
        if (f.conf() != null) o.addProperty("conf", f.conf());
        SellTime.Resale r = f.resale();
        if (r != null) {
            JsonObject ro = new JsonObject();
            ro.addProperty("price", r.price());
            ro.addProperty("minutes", r.minutes());
            ro.addProperty("profit", r.profit());
            ro.addProperty("perHour", r.perHour());
            o.add("resale", ro);
        }
        return o.toString();
    }

    static String encodeNotice(boolean good, String lead, String rest) {
        JsonObject o = new JsonObject();
        o.addProperty("good", good);
        o.addProperty("lead", lead);
        o.addProperty("rest", rest);
        return o.toString();
    }

    static String encodeHello(String tier, long delayMs, String version) {
        JsonObject o = new JsonObject();
        o.addProperty("tier", tier);
        o.addProperty("delayMs", delayMs);
        o.addProperty("version", version);
        return o.toString();
    }

    // ------------------------------------------------------------------ decoding (mod side, untrusted input)

    /** A flip message, or null if anything in it is missing, the wrong type or out of range. */
    static Flip decodeFlip(String json) {
        try {
            JsonObject o = object(json);
            if (o == null) return null;
            String uuid = str(o, "uuid", 36);
            if (!Validate.uuid(uuid)) return null;
            String sellerUuid = str(o, "sellerUuid", 36);
            if (sellerUuid != null && !Validate.uuid(sellerUuid)) sellerUuid = null;
            String name = line(str(o, "name", 200), 120);
            if (name == null || name.isBlank()) return null;
            String tier = str(o, "tier", 20);
            if (tier == null || !tier.matches("[A-Z_]{1,20}")) tier = "COMMON";
            Long buy = coins(o, "buy", 1), sell = coins(o, "sell", 1), profit = coins(o, "profit", 1);
            Long at = num(o, "at", 0, Long.MAX_VALUE / 4);
            if (buy == null || sell == null || profit == null || at == null) return null;
            Double margin = dbl(o, "margin", 0, 1_000_000);
            Double volume = dbl(o, "volume", -1, 10_000_000);
            if (margin == null || volume == null) return null;
            String detail = multiline(str(o, "detail", 4000), 2000);
            String sellerName = str(o, "sellerName", 16);
            if (sellerName != null && !Validate.player(sellerName)) sellerName = null;
            String conf = str(o, "conf", 4);
            if (conf != null) {
                conf = conf.toUpperCase(Locale.ROOT);
                if (!CONF.contains(conf)) conf = null;
            }
            SellTime.Resale resale = null;
            JsonElement re = o.get("resale");
            if (re != null && re.isJsonObject()) {
                JsonObject ro = re.getAsJsonObject();
                Long rp = num(ro, "price", 0, MAX_COINS), rm = num(ro, "minutes", 0, 100_000_000L);
                Long rpr = num(ro, "profit", -MAX_COINS, MAX_COINS), rph = num(ro, "perHour", -MAX_COINS, MAX_COINS);
                if (rp != null && rm != null && rpr != null && rph != null) resale = new SellTime.Resale(rp, rm, rpr, rph);
            }
            return new Flip(new FlipAlert(name, tier, uuid, sellerUuid, buy, sell, profit, margin, volume, detail,
                    sellerName, conf, resale), at);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Notice decodeNotice(String json) {
        try {
            JsonObject o = object(json);
            if (o == null) return null;
            JsonElement g = o.get("good");
            if (g == null || !g.isJsonPrimitive() || !g.getAsJsonPrimitive().isBoolean()) return null;
            String lead = line(str(o, "lead", 300), 120), rest = line(str(o, "rest", 600), 240);
            if (lead == null || lead.isBlank()) return null;
            return new Notice(g.getAsBoolean(), lead, rest == null ? "" : rest);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static Hello decodeHello(String json) {
        try {
            JsonObject o = object(json);
            if (o == null) return null;
            String tier = line(str(o, "tier", 20), 20);
            Long delay = num(o, "delayMs", 0, 3_600_000L);
            String version = line(str(o, "version", 40), 40);
            if (tier == null || delay == null) return null;
            return new Hello(tier, delay, version == null ? "" : version);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------ small checked getters

    private static JsonObject object(String json) {
        if (json == null || json.length() > MAX_DATA) return null;
        JsonElement e = JsonParser.parseString(json);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    /** A string field no longer than {@code max}, or null if missing, not a string or too long. */
    private static String str(JsonObject o, String key, int max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isString()) return null;
        String s = e.getAsString();
        return s.length() > max ? null : s;
    }

    private static Long num(JsonObject o, String key, long min, long max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        try {
            double d = e.getAsDouble();
            if (Double.isNaN(d) || Double.isInfinite(d)) return null;
            long v = e.getAsLong();
            return v < min || v > max ? null : v;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long coins(JsonObject o, String key, long min) {
        return num(o, key, min, MAX_COINS);
    }

    private static Double dbl(JsonObject o, String key, double min, double max) {
        JsonElement e = o.get(key);
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        double d = e.getAsDouble();
        return Double.isNaN(d) || Double.isInfinite(d) || d < min || d > max ? null : d;
    }

    /** One line of plain text: control characters become spaces, length is capped. */
    private static String line(String s, int max) {
        return s == null ? null : Validate.text(s, max).trim();
    }

    /** Several lines: only line breaks are kept; other control characters become spaces. */
    static String multiline(String s, int max) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(Math.min(s.length(), max));
        for (int i = 0; i < s.length() && sb.length() < max; i++) {
            char c = s.charAt(i);
            sb.append(c == '\n' ? '\n' : (c < 32 || c == 127 ? ' ' : c));
        }
        return sb.toString();
    }
}
