// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/**
 * Reads Hypixel's item_bytes (gzip-compressed NBT) with a tiny built-in parser instead of
 * Minecraft's own classes, so this does not break when Minecraft changes its internals.
 */
final class ItemKeys {
    private ItemKeys() {}

    /**
     * Parses an auction's item_bytes, including the rarity (read from the item's lore, because
     * the "ended auctions" feed has no rarity field). Returns ItemData.NONE for anything we can't
     * price reliably.
     */
    static ItemData parse(String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > MAX_BYTES * 2) return ItemData.NONE;
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            try (DataInputStream in = new DataInputStream(new LimitedInputStream(new GZIPInputStream(new ByteArrayInputStream(raw)), MAX_BYTES))) {
                if (in.readByte() != 10) return ItemData.NONE;   // root must be a compound
                in.readUTF();                                     // root name (unused)
                Map<String, Object> root = readCompound(in, 0);

                if (!(root.get("i") instanceof List<?> list) || list.isEmpty()) return ItemData.NONE;
                Map<String, Object> tag = asMap(asMap(list.get(0)).get("tag"));
                Map<String, Object> extra = asMap(tag.get("ExtraAttributes"));
                Map<String, Object> display = asMap(tag.get("display"));
                String name = asString(display.get("Name")).replaceAll("§.", "");

                ItemData d = ItemData.fromExtra(extra, name);
                if (d == ItemData.NONE) return d;

                List<String> lore = new ArrayList<>();
                if (display.get("Lore") instanceof List<?> l) {
                    for (Object o : l) if (o instanceof String s) lore.add(s);
                }
                String tier = ItemData.tierFromLore(lore);
                if (tier != null) d = d.withTier(tier);
                d = d.withEtherwarpFromLore(lore);
                return d;
            }
        } catch (Throwable e) { // includes StackOverflowError / OutOfMemoryError from hostile data
            return ItemData.NONE;
        }
    }

    /** The item's display name without colour codes (stars included, e.g. "Hyperion ✪✪✪✪✪➌"), or null. */
    static String displayName(String base64) {
        if (base64 == null || base64.isEmpty() || base64.length() > MAX_BYTES * 2) return null;
        try {
            byte[] raw = Base64.getDecoder().decode(base64);
            try (DataInputStream in = new DataInputStream(new LimitedInputStream(new GZIPInputStream(new ByteArrayInputStream(raw)), MAX_BYTES))) {
                if (in.readByte() != 10) return null;
                in.readUTF();
                Map<String, Object> root = readCompound(in, 0);
                if (!(root.get("i") instanceof List<?> list) || list.isEmpty()) return null;
                Map<String, Object> display = asMap(asMap(asMap(list.get(0)).get("tag")).get("display"));
                String n = asString(display.get("Name")).replaceAll("§.", "").strip();
                return n.isEmpty() || n.length() > 120 ? null : n;
            }
        } catch (Throwable e) {
            return null;
        }
    }

    /** Parses an uncompressed NBT compound that is already the item's extra attributes. */
    static ItemData parseUncompressed(byte[] raw, String plainName) {
        if (raw == null || raw.length > MAX_BYTES) return ItemData.NONE;
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw))) {
            if (in.readByte() != 10) return ItemData.NONE;
            in.readUTF();
            return ItemData.fromExtra(readCompound(in, 0), plainName);
        } catch (Throwable e) {
            return ItemData.NONE;
        }
    }

    // ---------------------------------------------------------------- tiny NBT reader

    /** Limits against hostile or damaged item data: nesting, list length and the total size after decompression. */
    private static final int MAX_DEPTH = 32, MAX_LIST = 4096, MAX_BYTES = 1 << 20;


    private static Map<String, Object> readCompound(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
        Map<String, Object> map = new HashMap<>();
        while (true) {
            byte type = in.readByte();
            if (type == 0) return map;               // TAG_End
            String name = in.readUTF();
            map.put(name, readPayload(type, in, depth + 1));
        }
    }

    private static Object readPayload(byte type, DataInputStream in, int depth) throws IOException {
        switch (type) {
            case 1: return in.readByte();
            case 2: return in.readShort();
            case 3: return in.readInt();
            case 4: return in.readLong();
            case 5: return in.readFloat();
            case 6: return in.readDouble();
            case 7: {                                // byte array
                int len = in.readInt();
                if (len < 0) throw new IOException("negative NBT array");
                in.skipNBytes(len);
                return null;
            }
            case 8: return in.readUTF();
            case 9: {                                // list
                byte elem = in.readByte();
                int len = in.readInt();
                if (len < 0 || len > MAX_LIST) throw new IOException("NBT list too long");
                if (depth > MAX_DEPTH) throw new IOException("NBT nested too deeply");
                List<Object> out = new ArrayList<>(Math.min(len, 1024));
                for (int i = 0; i < len; i++) out.add(readPayload(elem, in, depth + 1));
                return out;
            }
            case 10: return readCompound(in, depth);
            case 11: {                               // int array
                int len = in.readInt();
                if (len < 0) throw new IOException("negative NBT array");
                in.skipNBytes(4L * len);
                return null;
            }
            case 12: {                               // long array
                int len = in.readInt();
                if (len < 0) throw new IOException("negative NBT array");
                in.skipNBytes(8L * len);
                return null;
            }
            default: throw new IOException("Unknown NBT tag " + type);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of();
    }

    private static String asString(Object o) {
        return o instanceof String s ? s : "";
    }
}
