// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Invite codes: the way a person gets a FREE key without the owner typing a command for each one. A code looks like
 * INV-XXXX-XXXX-XXXX-XXXX (80 random bits). Only its SHA-256 hash is stored (invites.tsv, readable by the server's user
 * only). Each code has a number of uses and an expiry date. A code can only ever make FREE keys.
 */
final class InviteStore {
    record Invite(String hash, int maxUses, int used, long expiresAt, long createdAt) {
        boolean usable(long now) { return used < maxUses && now < expiresAt; }
    }

    private static final String ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O, 0, 1
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;

    InviteStore(Path file) {
        this.file = file;
    }

    /** The code as typed, reduced to its 16 characters (dashes, spaces and case ignored), or null if it cannot be a code. */
    static String normalize(String typed) {
        if (typed == null || typed.length() > 40) return null;
        String s = typed.toUpperCase(java.util.Locale.ROOT).replaceAll("[\\s-]", "");
        if (s.startsWith("INV")) s = s.substring(3);
        if (s.length() != 16) return null;
        for (int i = 0; i < 16; i++) if (ALPHABET.indexOf(s.charAt(i)) < 0) return null;
        return s;
    }

    private static String pretty(String c16) {
        return "INV-" + c16.substring(0, 4) + "-" + c16.substring(4, 8) + "-" + c16.substring(8, 12) + "-" + c16.substring(12);
    }

    private static String hash(String c16) {
        return KeyStore.hash("invite:" + c16);
    }

    /** Makes {@code count} new codes. Returns them in plain text (the only time they are visible). */
    synchronized List<String> add(int count, int usesEach, int days) throws IOException {
        if (count < 1 || count > 500 || usesEach < 1 || usesEach > 1000 || days < 1 || days > 365) throw new IllegalArgumentException("count 1-500, uses 1-1000, days 1-365");
        return KeyStore.withFileLock(file, () -> {
            Map<String, Invite> all = read();
            List<String> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            for (int i = 0; i < count; i++) {
                StringBuilder c = new StringBuilder();
                for (int j = 0; j < 16; j++) c.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
                Invite inv = new Invite(hash(c.toString()), usesEach, 0, now + days * 86_400_000L, now);
                all.put(inv.hash(), inv);
                out.add(pretty(c.toString()));
            }
            write(all);
            return out;
        });
    }

    /** Uses up one use of the code. False if the code is unknown, used up or expired. */
    synchronized boolean take(String typed) throws IOException {
        String c = normalize(typed);
        if (c == null) return false;
        return KeyStore.withFileLock(file, () -> {
            Map<String, Invite> all = read();
            Invite inv = all.get(hash(c));
            long now = System.currentTimeMillis();
            if (inv == null || !inv.usable(now)) return false;
            all.put(inv.hash(), new Invite(inv.hash(), inv.maxUses(), inv.used() + 1, inv.expiresAt(), inv.createdAt()));
            write(all);
            return true;
        });
    }

    /** Gives a use back (when the key could not be made after the code was used). */
    synchronized void refund(String typed) {
        String c = normalize(typed);
        if (c == null) return;
        try {
            KeyStore.withFileLock(file, () -> {
                Map<String, Invite> all = read();
                Invite inv = all.get(hash(c));
                if (inv != null && inv.used() > 0) {
                    all.put(inv.hash(), new Invite(inv.hash(), inv.maxUses(), inv.used() - 1, inv.expiresAt(), inv.createdAt()));
                    write(all);
                }
                return null;
            });
        } catch (IOException ignored) { /* best effort */ }
    }

    synchronized List<Invite> list() throws IOException {
        return KeyStore.withFileLock(file, () -> new ArrayList<>(read().values()));
    }

    /** Removes the codes whose id (first characters of the hash) starts with the given text. */
    synchronized int revoke(String idPrefix) throws IOException {
        if (idPrefix == null || !idPrefix.matches("[0-9a-f]{6,64}")) throw new IllegalArgumentException("id: at least 6 hex characters (see invites list)");
        return KeyStore.withFileLock(file, () -> {
            Map<String, Invite> all = read();
            int before = all.size();
            all.keySet().removeIf(h -> h.startsWith(idPrefix));
            if (all.size() != before) write(all);
            return before - all.size();
        });
    }

    private Map<String, Invite> read() throws IOException {
        Map<String, Invite> m = new LinkedHashMap<>();
        if (!Files.isRegularFile(file)) return m;
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String[] p = line.split("\t");
            if (p.length < 5 || !p[0].matches("[0-9a-f]{64}")) continue;
            try {
                m.put(p[0], new Invite(p[0], Integer.parseInt(p[1]), Integer.parseInt(p[2]), Long.parseLong(p[3]), Long.parseLong(p[4])));
            } catch (NumberFormatException ignored) { /* skip bad line */ }
        }
        return m;
    }

    private void write(Map<String, Invite> m) throws IOException {
        StringBuilder sb = new StringBuilder("# hash<TAB>max uses<TAB>used<TAB>expires (ms)<TAB>created (ms). Codes are stored hashed.\n");
        for (Invite i : m.values()) {
            sb.append(i.hash()).append('\t').append(i.maxUses()).append('\t').append(i.used()).append('\t')
                    .append(i.expiresAt()).append('\t').append(i.createdAt()).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) { /* not POSIX */ }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
}
