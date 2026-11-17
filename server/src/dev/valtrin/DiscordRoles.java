// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Gives people who are in the Valtrin Discord server a "Free Plan" or "Premium Plan" role that matches their key.
 *
 * <p>This is deliberately small. It is not a chat bot: it never connects to Discord's live gateway, never reads
 * messages and has no commands. It only makes one-off web requests ("add this role to this member", "take this role
 * away") using a bot token that has the Manage Roles permission. It does nothing unless config/discord-bot-token.txt,
 * discordRoleGuildId and discordFreeRoleId are set. Giving a role never affects who may get a key or what a key can do:
 * the service works the same for people who are not in the Discord server.
 *
 * <p>The token is read once from a file only the server's user can read, is sent only to Discord in an
 * Authorization header, and is never logged. Only numeric ids that pass a strict check are put into a request address.
 */
final class DiscordRoles {
    private static final Pattern SNOWFLAKE = Pattern.compile("[0-9]{15,22}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]{30,120}");
    private static final long MEMBER_RECHECK_MS = 24L * 3_600_000L;
    private static final long NON_MEMBER_RECHECK_MS = 30L * 60_000L;
    private static final int MAX_CALLS_PER_SWEEP = 60;

    /** What was last applied for one Discord account. */
    private record Applied(boolean premium, boolean member, long at) {}

    private final ServerSettings cfg;
    private final KeyStore keys;
    private final String token;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ConcurrentHashMap<String, Applied> applied = new ConcurrentHashMap<>();
    private final AtomicBoolean queued = new AtomicBoolean();
    private ScheduledExecutorService ex;

    DiscordRoles(ServerSettings cfg, KeyStore keys, Path configDir) {
        this.cfg = cfg;
        this.keys = keys;
        String t = "";
        try {
            Path f = configDir.resolve("discord-bot-token.txt");
            if (Files.isRegularFile(f)) t = Files.readString(f, StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            Log.LOGGER.warn("Could not read the Discord bot token file: {}", e.toString());
        }
        this.token = TOKEN.matcher(t).matches() ? t : "";
        boolean wanted = !cfg.discordRoleGuildId.isEmpty() || !cfg.discordFreeRoleId.isEmpty() || !t.isEmpty();
        if (enabled()) Log.LOGGER.info("Plan roles on Discord are on (free role{})", cfg.discordPremiumRoleId.isEmpty() ? "" : " and premium role");
        else if (wanted) Log.LOGGER.warn("Plan roles on Discord are NOT on: needs discordRoleGuildId, discordFreeRoleId and config/discord-bot-token.txt");
    }

    boolean enabled() {
        return !token.isEmpty() && SNOWFLAKE.matcher(cfg.discordRoleGuildId).matches() && SNOWFLAKE.matcher(cfg.discordFreeRoleId).matches()
                && (cfg.discordPremiumRoleId.isEmpty() || SNOWFLAKE.matcher(cfg.discordPremiumRoleId).matches());
    }

    void start() {
        if (!enabled()) return;
        ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "valtrin-roles");
            t.setDaemon(true);
            return t;
        });
        ex.scheduleWithFixedDelay(this::safeSweep, 20, 300, TimeUnit.SECONDS);
    }

    void stop() {
        if (ex != null) ex.shutdownNow();
    }

    /** A key was made or changed: check soon (many calls in a row make one check). */
    void poke() {
        if (ex == null || !queued.compareAndSet(false, true)) return;
        try {
            ex.schedule(() -> {
                queued.set(false);
                safeSweep();
            }, 2, TimeUnit.SECONDS);
        } catch (RuntimeException e) {
            queued.set(false);
        }
    }

    private void safeSweep() {
        try {
            sweep();
        } catch (Throwable t) {
            Log.LOGGER.warn("Plan role check failed: {}", t.toString());
        }
    }

    /** Public for the tests, which call it directly. */
    synchronized void sweep() throws InterruptedException {
        if (!enabled()) return;
        // Discord account id -> has a premium key (premium wins when an account somehow has both).
        Map<String, Boolean> want = new HashMap<>();
        for (KeyStore.Entry e : keys.list()) {
            String label = e.label();
            if (!label.startsWith("discord-")) continue;
            String id = label.substring(8);
            if (!SNOWFLAKE.matcher(id).matches()) continue;
            boolean premium = e.tier() == KeyStore.Tier.PREMIUM && !cfg.discordPremiumRoleId.isEmpty();
            want.merge(id, premium, (a, b) -> a || b);
        }
        long now = System.currentTimeMillis();
        int[] calls = {0};
        for (Map.Entry<String, Boolean> w : want.entrySet()) {
            if (calls[0] >= MAX_CALLS_PER_SWEEP) return;
            String id = w.getKey();
            boolean premium = w.getValue();
            Applied prev = applied.get(id);
            boolean due = prev == null || prev.premium != premium || now - prev.at > (prev.member ? MEMBER_RECHECK_MS : NON_MEMBER_RECHECK_MS);
            if (!due) continue;
            int st = call("PUT", id, premium ? cfg.discordPremiumRoleId : cfg.discordFreeRoleId, calls);
            if (st == 204) {
                // swap: the other plan role goes away when the plan changed (or when making a premium member)
                if (!cfg.discordPremiumRoleId.isEmpty() && (premium || (prev != null && prev.premium))) {
                    call("DELETE", id, premium ? cfg.discordFreeRoleId : cfg.discordPremiumRoleId, calls);
                }
                if (prev == null || prev.premium != premium || !prev.member) {
                    Log.LOGGER.info("Gave the {} plan role to a Discord account (id {})", premium ? "Premium" : "Free", id);
                }
                applied.put(id, new Applied(premium, true, now));
            } else if (st == 404) {
                applied.put(id, new Applied(premium, false, now)); // not in the Discord server: fine, try again later
            } else if (st == 403) {
                Log.LOGGER.warn("Discord refused the role change: give the bot Manage Roles and put its role above the plan roles");
                return;
            } else if (st == 429 || st < 0) {
                return; // slow down or Discord is unreachable: try again next time
            }
        }
        // keys that are gone: take the plan roles away
        for (Map.Entry<String, Applied> a : applied.entrySet()) {
            if (want.containsKey(a.getKey())) continue;
            if (calls[0] >= MAX_CALLS_PER_SWEEP) return;
            if (a.getValue().member) {
                int s1 = call("DELETE", a.getKey(), cfg.discordFreeRoleId, calls);
                int s2 = cfg.discordPremiumRoleId.isEmpty() ? 204 : call("DELETE", a.getKey(), cfg.discordPremiumRoleId, calls);
                if (s1 == 429 || s2 == 429 || s1 < 0 || s2 < 0) return;
            }
            applied.remove(a.getKey());
        }
    }

    /** One request to Discord; returns the status, or -1 if it could not be made. */
    private int call(String method, String userId, String roleId, int[] calls) throws InterruptedException {
        calls[0]++;
        Thread.sleep(250); // stay far below Discord's limits
        try {
            URI uri = URI.create(cfg.discordApiBase + "/guilds/" + cfg.discordRoleGuildId + "/members/" + userId + "/roles/" + roleId);
            HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bot " + token).header("User-Agent", "Valtrin (https://valtrin.app, 1)")
                    .header("X-Audit-Log-Reason", "Valtrin plan role");
            HttpRequest req = "PUT".equals(method) ? b.PUT(HttpRequest.BodyPublishers.noBody()).build() : b.DELETE().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (java.io.IOException | RuntimeException e) {
            Log.LOGGER.warn("Could not reach Discord to change a role: {}", e.toString());
            return -1;
        }
    }
}
