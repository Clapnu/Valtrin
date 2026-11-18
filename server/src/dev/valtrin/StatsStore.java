// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Completed flips that a player's own mod chose to report (opt-in, premium keys only). One small text file per
 * account, named by a hash of the key's label, with one line per flip: time, item name, coins paid, coins received.
 * Nothing else is stored: no chat text, no Minecraft name, no address. Lines are kept until the player deletes
 * them; only the newest 20,000 are kept, and the player can delete everything at any time.
 */
final class StatsStore {
    static final int MAX_BATCH = 50;
    static final int MAX_LINES = 20_000;
    static final long MIN_TS = 1_577_836_800_000L; // 2020-01-01: a timestamp before this is nonsense. Flips are otherwise kept until the player deletes them
    static final long MAX_COINS = 1_000_000_000_000L;

    record Flip(long ts, String item, long cost, long got, String rarity) {}

    /** The item rarities the mod may report; anything else is stored as empty. */
    static final java.util.Set<String> RARITIES = java.util.Set.of("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC",
            "DIVINE", "SPECIAL", "VERY_SPECIAL", "ULTIMATE", "SUPREME");

    private final Path dir;

    StatsStore(Path dataDir) {
        this.dir = dataDir.resolve("stats");
    }

    private Path file(String label) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(label.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 12; i++) sb.append(String.format("%02x", h[i]));
            return dir.resolve(sb + ".tsv");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Checks one reported flip; returns null when it is not acceptable. */
    static Flip clean(long ts, String item, long cost, long got, long now) {
        return clean(ts, item, cost, got, null, now);
    }

    static Flip clean(long ts, String item, long cost, long got, String rarity, long now) {
        if (ts < MIN_TS || ts > now + 300_000L) return null;
        if (cost <= 0 || cost > MAX_COINS || got <= 0 || got > MAX_COINS) return null;
        String name = Validate.text(item == null ? "" : item, 60).replace('\t', ' ').strip();
        if (name.isEmpty()) return null;
        String r = rarity == null ? "" : rarity.strip().toUpperCase(java.util.Locale.ROOT);
        return new Flip(ts, name, cost, got, RARITIES.contains(r) ? r : "");
    }

    /** What makes a flip the same flip (the rarity is not part of it). */
    private static String key(Flip f) {
        return f.ts + "\t" + f.item + "\t" + f.cost + "\t" + f.got;
    }

    private static String line(Flip f) {
        return key(f) + (f.rarity.isEmpty() ? "" : "\t" + f.rarity);
    }

    /** Adds the flips that are new (same time, item and amounts twice is one flip). Returns how many were stored. */
    synchronized int add(String label, List<Flip> in, long now) throws IOException {
        if (in.isEmpty()) return 0;
        Files.createDirectories(dir);
        Path f = file(label);
        List<Flip> have = readAll(f, now);
        Set<String> seen = new HashSet<>();
        for (Flip h : have) seen.add(key(h));
        StringBuilder add = new StringBuilder();
        int n = 0;
        for (Flip x : in) {
            if (seen.add(key(x))) {
                add.append(line(x)).append('\n');
                have.add(x);
                n++;
            }
        }
        if (n == 0) return 0;
        if (have.size() > MAX_LINES) { // too many: rewrite with the newest ones only
            have.sort((a, b) -> Long.compare(a.ts, b.ts));
            have = new ArrayList<>(have.subList(have.size() - MAX_LINES, have.size()));
            StringBuilder all = new StringBuilder();
            for (Flip h : have) all.append(line(h)).append('\n');
            Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
            Files.writeString(tmp, all.toString(), StandardCharsets.UTF_8);
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        } else {
            Files.writeString(f, add.toString(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        return n;
    }

    /** Newest first, at most {@code limit}, only the last {@code days} days. */
    synchronized List<Flip> recent(String label, long now, int days, int limit) {
        List<Flip> all = readAll(file(label), now);
        long from = now - days * 86_400_000L;
        List<Flip> out = new ArrayList<>();
        for (Flip f : all) if (f.ts >= from) out.add(f);
        out.sort((a, b) -> Long.compare(b.ts, a.ts));
        return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
    }

    /** Removes everything stored for this account. Returns true if there was anything. */
    synchronized boolean delete(String label) {
        try {
            return Files.deleteIfExists(file(label));
        } catch (IOException e) {
            Log.LOGGER.warn("Could not delete a stats file: {}", e.toString());
            return false;
        }
    }

    private static List<Flip> readAll(Path f, long now) {
        List<Flip> out = new ArrayList<>();
        try {
            if (!Files.isRegularFile(f)) return out;
            for (String l : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                String[] p = l.split("\t", -1);
                if (p.length != 4 && p.length != 5) continue;
                try {
                    long ts = Long.parseLong(p[0]);
                    if (ts < MIN_TS) continue;
                    out.add(new Flip(ts, p[1], Long.parseLong(p[2]), Long.parseLong(p[3]),
                            p.length == 5 && RARITIES.contains(p[4]) ? p[4] : ""));
                } catch (NumberFormatException ignored) { /* damaged line: skipped */ }
            }
        } catch (IOException e) {
            Log.LOGGER.warn("Could not read a stats file: {}", e.toString());
        }
        return out;
    }
}
