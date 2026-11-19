// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Which Discord account is linked to which Minecraft name, and who asked for sale DMs.
 * One tab-separated file: discord id, uuid, name, notify (0 or 1). Nothing else about a person is kept.
 * The link is self-declared (Hypixel's data that could prove it needs an API key), so it is only used for public data.
 */
final class NotifyStore {
    static final int MAX = 50_000;
    private static final Pattern ID = Pattern.compile("[0-9]{5,25}");

    record Link(String discordId, String uuid, String name, boolean dms) {}

    private final Path file;
    private final Map<String, Link> byDiscord = new LinkedHashMap<>();

    NotifyStore(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String[] p = line.split("\t");
                    if (p.length != 4 || !ID.matcher(p[0]).matches() || !Validate.uuid(p[1]) || !Validate.player(p[2])) continue;
                    byDiscord.put(p[0], new Link(p[0], p[1], p[2], "1".equals(p[3])));
                }
            }
        } catch (IOException e) {
            Log.LOGGER.warn("Could not read the Discord links: {}", e.toString());
        }
    }

    synchronized Link get(String discordId) { return byDiscord.get(discordId); }

    synchronized boolean link(String discordId, String uuid, String name) {
        if (!ID.matcher(discordId).matches() || !Validate.uuid(uuid) || !Validate.player(name)) return false;
        Link old = byDiscord.get(discordId);
        if (old == null && byDiscord.size() >= MAX) return false;
        byDiscord.put(discordId, new Link(discordId, uuid, name, old != null && old.uuid().equals(uuid) && old.dms()));
        save();
        return true;
    }

    synchronized boolean setNotify(String discordId, boolean on) {
        Link l = byDiscord.get(discordId);
        if (l == null) return false;
        byDiscord.put(discordId, new Link(l.discordId(), l.uuid(), l.name(), on));
        save();
        return true;
    }

    synchronized void unlink(String discordId) {
        if (byDiscord.remove(discordId) != null) save();
    }

    /** Discord ids that want DMs for this seller. */
    synchronized List<String> watchers(String uuid) {
        List<String> out = new ArrayList<>(1);
        for (Link l : byDiscord.values()) if (l.dms() && l.uuid().equals(uuid)) out.add(l.discordId());
        return out;
    }

    synchronized boolean anyWatching() {
        for (Link l : byDiscord.values()) if (l.dms()) return true;
        return false;
    }

    private void save() {
        try {
            StringBuilder sb = new StringBuilder();
            for (Link l : byDiscord.values())
                sb.append(l.discordId()).append('\t').append(l.uuid()).append('\t').append(l.name()).append('\t').append(l.dms() ? '1' : '0').append('\n');
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
            try { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.LOGGER.warn("Could not save the Discord links: {}", e.toString());
        }
    }
}
