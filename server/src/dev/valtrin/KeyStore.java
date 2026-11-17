// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The access keys of the Valtrin server. A key looks like "vk_" + 43 random characters (256 bits). Only the SHA-256 of
 * a key is stored (in keys.tsv, readable by the server's user only), so a stolen file does not reveal working keys.
 * The file is re-read when it changes, so adding or revoking a key needs no restart.
 */
final class KeyStore {
    enum Tier { FREE, PREMIUM }

    record Entry(String hash, Tier tier, String label, long createdAt) {}

    private static final Pattern KEY = Pattern.compile("^vk_[A-Za-z0-9_-]{43}$");
    private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9_.@ -]{1,40}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Path file;
    private volatile Map<String, Entry> byHash = Map.of();
    private volatile long loadedMtime = Long.MIN_VALUE;

    KeyStore(Path file) {
        this.file = file;
    }

    /** True if text could be a key at all (cheap check before hashing). */
    static boolean looksLikeKey(String s) {
        return s != null && s.length() == 46 && KEY.matcher(s).matches();
    }

    static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The entry for a presented key, or null. Unknown keys and malformed text both give null. */
    Entry lookup(String key) {
        if (!looksLikeKey(key)) return null;
        reloadIfChanged();
        // The map lookup compares hashes of the key, not the key, so timing reveals nothing about stored keys.
        return byHash.get(hash(key));
    }

    /** True while a key with this hash is still valid (used to end the stream of a key that was revoked). */
    boolean hasHash(String hash) {
        reloadIfChanged();
        return byHash.containsKey(hash);
    }

    int count() {
        reloadIfChanged();
        return byHash.size();
    }

    List<Entry> list() {
        reloadIfChanged();
        List<Entry> l = new ArrayList<>(byHash.values());
        l.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        return l;
    }

    /** Runs a read-modify-write on a data file while holding a lock that other Valtrin processes (the admin commands) respect. */
    static <T> T withFileLock(Path target, java.util.concurrent.Callable<T> job) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path lock = target.resolveSibling(target.getFileName() + ".lock");
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(lock,
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.WRITE);
             java.nio.channels.FileLock ignored = ch.lock()) {
            return job.call();
        } catch (IOException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    boolean hasLabel(String label) {
        reloadIfChanged();
        for (Entry e : byHash.values()) if (e.label.equalsIgnoreCase(label)) return true;
        return false;
    }

    static boolean validLabel(String label) {
        return label != null && LABEL.matcher(label).matches();
    }

    /** Makes a new key; returns it (the only time it exists in plain text). */
    synchronized String add(Tier tier, String label) throws IOException {
        return withFileLock(file, () -> addLocked(tier, label));
    }

    private String addLocked(Tier tier, String label) throws IOException {
        if (label == null || !LABEL.matcher(label).matches()) throw new IllegalArgumentException("label: 1-40 letters, digits, space . _ @ -");
        loadedMtime = Long.MIN_VALUE; // another process may have changed the file: read it fresh
        reloadIfChanged();
        for (Entry e : byHash.values()) if (e.label.equalsIgnoreCase(label)) throw new IllegalArgumentException("a key with that label exists");
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String key = "vk_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Map<String, Entry> next = new HashMap<>(byHash);
        Entry e = new Entry(hash(key), tier, label, System.currentTimeMillis());
        next.put(e.hash, e);
        write(next);
        return key;
    }

    /** Removes every key with this label (case-insensitive). Returns how many were removed. */
    synchronized int revoke(String label) throws IOException {
        return withFileLock(file, () -> revokeLocked(label));
    }

    private int revokeLocked(String label) throws IOException {
        loadedMtime = Long.MIN_VALUE;
        reloadIfChanged();
        Map<String, Entry> next = new HashMap<>(byHash);
        int before = next.size();
        next.values().removeIf(e -> e.label.equalsIgnoreCase(label));
        if (next.size() != before) write(next);
        return before - next.size();
    }

    private void write(Map<String, Entry> m) throws IOException {
        Files.createDirectories(file.toAbsolutePath().getParent());
        StringBuilder sb = new StringBuilder("# hash<TAB>tier<TAB>label<TAB>created (ms). Keys are stored hashed.\n");
        for (Entry e : m.values()) {
            sb.append(e.hash).append('\t').append(e.tier.name()).append('\t').append(e.label).append('\t').append(e.createdAt).append('\n');
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException ignored) { /* not a POSIX file system */ }
        Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        byHash = Map.copyOf(m);
        loadedMtime = mtime();
    }

    private long mtime() {
        try {
            return Files.getLastModifiedTime(file).toMillis();
        } catch (IOException e) {
            return -1;
        }
    }

    private void reloadIfChanged() {
        long m = mtime();
        if (m == loadedMtime) return;
        Map<String, Entry> m2 = new HashMap<>();
        try {
            if (m >= 0) for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] p = line.split("\t");
                if (p.length < 4 || !p[0].matches("[0-9a-f]{64}") || !LABEL.matcher(p[2]).matches()) continue;
                try {
                    m2.put(p[0], new Entry(p[0], Tier.valueOf(p[1]), p[2], Long.parseLong(p[3])));
                } catch (IllegalArgumentException ignored) { /* skip bad line */ }
            }
        } catch (IOException e) {
            return; // keep the keys already loaded
        }
        byHash = Map.copyOf(m2);
        loadedMtime = m;
    }
}
