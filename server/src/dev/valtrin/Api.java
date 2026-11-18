// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * The server's web interface. Every request needs a valid access key in the X-Key header.
 *   GET /v1/stream   server-sent events: hello, flip, notice (one connection per key)
 *   GET /ping        "ok"
 *   GET /sales?id=ITEM&days=N, GET /coverage?days=N   the sales history used for pricing
 * Only GET is accepted. Keys are never logged. Failed attempts are rate-limited per client address, and a key that
 * asks for sales too fast is slowed down. Meant to sit behind Caddy (HTTPS) on 127.0.0.1.
 */
final class Api {
    private final ServerSettings cfg;
    private final KeyStore keys;
    private final Hub hub;
    private volatile CraftFlips crafts;
    private volatile StatsStore statsStore;

    void setStats(StatsStore s) { this.statsStore = s; }

    void setCrafts(CraftFlips c) { this.crafts = c; }
    private final InviteStore invites;
    private final DiscordLogin discord;
    private final DiscordRoles roles;
    private final DiscordGateway gateway;
    private final HttpServer server;

    /** ip -> {window start ms, failures in window}. */
    private final ConcurrentHashMap<String, long[]> fails = new ConcurrentHashMap<>();
    /** key hash -> {tokens*1000, last refill ms} for the sales endpoints. */
    private final ConcurrentHashMap<String, long[]> buckets = new ConcurrentHashMap<>();

    /** ip -> {window start ms, keys made in the window}, for /redeem. */
    private final ConcurrentHashMap<String, long[]> redeemed = new ConcurrentHashMap<>();

    Api(ServerSettings cfg, KeyStore keys, Hub hub, InviteStore invites, Path configDir) throws IOException {
        this.cfg = cfg;
        this.keys = keys;
        this.hub = hub;
        this.invites = invites;
        this.roles = new DiscordRoles(cfg, keys, configDir);
        this.gateway = new DiscordGateway(cfg, configDir);
        final long startedAt = System.currentTimeMillis();
        DiscordCommands dcmds = new DiscordCommands(cfg, gateway.token(), () -> "**Valtrin server**: online\n"
                + "Up for " + ((System.currentTimeMillis() - startedAt) / 3_600_000L) + " h\n"
                + "Flips found in the last hour: " + hub.countSince(3_600_000L) + "\n"
                + "Free plan alert delay: " + (cfg.freeDelayMs / 1000L) + " s");
        AuctionIndex aucIndex = new AuctionIndex();
        AuctionFeed.L = aucIndex;
        AuctionCommands aucCmds = new AuctionCommands(cfg, gateway.token(), aucIndex, new NotifyStore(configDir.resolve("discord-links.tsv")),
                AuctionCommands.mojangNames(), DiscordCommands.API, "https://api.mojang.com");
        dcmds.useAuctions(aucCmds);
        this.gateway.useCommands(dcmds);
        Thread.startVirtualThread(aucCmds::checkImage);
        this.discord = new DiscordLogin(cfg, keys, this, configDir);
        server = HttpServer.create(new InetSocketAddress(cfg.bind, cfg.port), 128);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
    }

    /** A Discord-linked key was made or changed: refresh the plan roles soon. */
    void accountChanged() {
        roles.poke();
    }

    DiscordRoles roles() {
        return roles;
    }

    void start() {
        roles.start();
        gateway.start();
        server.start();
        Log.LOGGER.info("Listening on {}:{}", cfg.bind, cfg.port);
    }

    void stop() {
        roles.stop();
        gateway.stop();
        server.stop(1);
    }

    // ------------------------------------------------------------------ request handling

    private void handle(HttpExchange ex) throws IOException {
        try {
            String first = ex.getRequestURI().getPath();
            if (("/".equals(first) || "/index.html".equals(first)) && "GET".equals(ex.getRequestMethod())) {
                html(ex, 200, Pages.home(cfg.contactEmail));
                return;
            }
            if ("/terms".equals(first) && "GET".equals(ex.getRequestMethod())) {
                html(ex, 200, Pages.terms(cfg.contactEmail));
                return;
            }
            if ("/privacy".equals(first) && "GET".equals(ex.getRequestMethod())) {
                html(ex, 200, Pages.privacy(cfg.contactEmail));
                return;
            }
            if ("/redeem".equals(first)) {
                redeem(ex);
                return;
            }
            if (("/login".equals(first) || "/auth/discord/callback".equals(first)) && "GET".equals(ex.getRequestMethod())) {
                if ("/login".equals(first)) discord.login(ex);
                else discord.callback(ex);
                return;
            }
            if ("OPTIONS".equals(ex.getRequestMethod()) && ("/v1/flips".equals(first) || "/v1/craft".equals(first) || "/v1/stats".equals(first))) {
                preflight(ex);
                return;
            }
            boolean statsPath = "/v1/stats".equals(first);
            String method = ex.getRequestMethod();
            if (!"GET".equals(method) && !(statsPath && ("POST".equals(method) || "DELETE".equals(method)))) {
                reply(ex, 405, "method");
                return;
            }
            String ip = clientIp(ex);
            if (failedTooOften(ip)) {
                reply(ex, 429, "slow down");
                return;
            }
            String presented = ex.getRequestHeaders().getFirst("X-Key");
            KeyStore.Entry entry = keys.lookup(presented);
            if (entry == null) {
                noteFailure(ip);
                reply(ex, 401, "wrong key");
                return;
            }
            String path = ex.getRequestURI().getPath();
            switch (path) {
                case "/ping" -> reply(ex, 200, "ok");
                case "/v1/stream" -> stream(ex, entry, ip);
                case "/v1/flips" -> {
                    if (!takeToken(entry.hash())) {
                        reply(ex, 429, "slow down");
                        return;
                    }
                    flips(ex, entry);
                }
                case "/v1/craft" -> {
                    if (!takeToken(entry.hash())) {
                        reply(ex, 429, "slow down");
                        return;
                    }
                    craft(ex, entry);
                }
                case "/v1/stats" -> {
                    if (!takeToken(entry.hash())) {
                        reply(ex, 429, "slow down");
                        return;
                    }
                    stats(ex, entry);
                }
                case "/sales", "/coverage" -> {
                    if (!takeToken(entry.hash())) {
                        reply(ex, 429, "slow down");
                        return;
                    }
                    sales(ex, path);
                }
                default -> reply(ex, 404, "not found");
            }
        } catch (Exception e) {
            try {
                reply(ex, 500, "error");
            } catch (Exception ignored) {
                ex.close();
            }
        }
    }

    // ------------------------------------------------------------------ website feed

    /** True when the request comes from the one website allowed to read the feed from a browser. */
    private boolean allowedOrigin(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        return !cfg.siteOrigin.isEmpty() && cfg.siteOrigin.equals(origin);
    }

    private void corsHeaders(HttpExchange ex) {
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Origin", cfg.siteOrigin);
        h.set("Vary", "Origin");
    }

    private void preflight(HttpExchange ex) throws IOException {
        if (!allowedOrigin(ex)) {
            reply(ex, 404, "not found");
            return;
        }
        corsHeaders(ex);
        var h = ex.getResponseHeaders();
        h.set("Access-Control-Allow-Methods", "GET, DELETE");
        h.set("Access-Control-Allow-Headers", "X-Key");
        h.set("Access-Control-Max-Age", "600");
        ex.sendResponseHeaders(204, -1);
        ex.close();
    }

    /** GET /v1/flips: recent flips for the website, held back by the key's own delay, no seller names. */
    private void flips(HttpExchange ex, KeyStore.Entry entry) throws IOException {
        byte[] data = hub.recentJson(entry.tier(), System.currentTimeMillis(), 100).getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        if (allowedOrigin(ex)) corsHeaders(ex);
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(200, data.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(data);
        }
        ex.close();
    }

    /** GET /v1/craft: crafts that currently look profitable. Premium keys only (and only while premium is switched on). */
    private void craft(HttpExchange ex, KeyStore.Entry entry) throws IOException {
        boolean allowed = entry.tier() == KeyStore.Tier.PREMIUM && cfg.premiumEnabled;
        String body = allowed ? (crafts == null ? "{\"crafts\":[]}" : crafts.json(System.currentTimeMillis())) : "{\"error\":\"premium\"}";
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        if (allowedOrigin(ex)) corsHeaders(ex);
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(allowed ? 200 : 403, data.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(data);
        }
        ex.close();
    }

    /**
     * /v1/stats (premium keys only): POST takes completed flips from the player's own mod (opt-in in the mod), GET gives
     * them back for the website, DELETE removes everything stored for the account.
     */
    private void stats(HttpExchange ex, KeyStore.Entry entry) throws IOException {
        boolean allowed = entry.tier() == KeyStore.Tier.PREMIUM && cfg.premiumEnabled && statsStore != null;
        String m = ex.getRequestMethod();
        if (!allowed) {
            ex.getRequestBody().readNBytes(1);
            json(ex, 403, "{\"error\":\"premium\"}");
            return;
        }
        long now = System.currentTimeMillis();
        String label = entry.label();
        if ("POST".equals(m)) {
            byte[] raw = ex.getRequestBody().readNBytes(20_001);
            if (raw.length > 20_000) { json(ex, 413, "{\"error\":\"too big\"}"); return; }
            List<StatsStore.Flip> in = new ArrayList<>();
            try {
                com.google.gson.JsonElement e = com.google.gson.JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
                if (!e.isJsonArray() || e.getAsJsonArray().size() > StatsStore.MAX_BATCH) throw new IllegalArgumentException("shape");
                for (com.google.gson.JsonElement x : e.getAsJsonArray()) {
                    com.google.gson.JsonObject o = x.getAsJsonObject();
                    StatsStore.Flip f = StatsStore.clean(o.get("ts").getAsLong(), o.get("item").getAsString(),
                            o.get("cost").getAsLong(), o.get("got").getAsLong(),
                            o.has("rarity") && o.get("rarity").isJsonPrimitive() ? o.get("rarity").getAsString() : null, now);
                    if (f != null) in.add(f);
                }
            } catch (RuntimeException bad) {
                json(ex, 400, "{\"error\":\"bad request\"}");
                return;
            }
            int n = statsStore.add(label, in, now);
            json(ex, 200, "{\"stored\":" + n + "}");
        } else if ("DELETE".equals(m)) {
            json(ex, 200, "{\"deleted\":" + statsStore.delete(label) + "}");
        } else {
            List<StatsStore.Flip> l = statsStore.recent(label, now, 36_500, StatsStore.MAX_LINES);
            StringBuilder sb = new StringBuilder("{\"now\":" + now + ",\"flips\":[");
            for (int i = 0; i < l.size(); i++) {
                StatsStore.Flip f = l.get(i);
                if (i > 0) sb.append(',');
                com.google.gson.JsonObject o = new com.google.gson.JsonObject();
                o.addProperty("ts", f.ts());
                o.addProperty("item", f.item());
                o.addProperty("cost", f.cost());
                o.addProperty("got", f.got());
                if (!f.rarity().isEmpty()) o.addProperty("rarity", f.rarity());
                sb.append(o);
            }
            json(ex, 200, sb.append("]}").toString());
        }
    }

    private void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        if (allowedOrigin(ex)) corsHeaders(ex);
        h.set("Content-Type", "application/json; charset=utf-8");
        h.set("Cache-Control", "no-store");
        h.set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(data);
        }
        ex.close();
    }

    // ------------------------------------------------------------------ invite codes

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("^[A-Za-z0-9_.-]{2,30}$");

    private void redeem(HttpExchange ex) throws IOException {
        if (!cfg.redeemEnabled) {
            reply(ex, 404, "not found");
            return;
        }
        String method = ex.getRequestMethod();
        if ("GET".equals(method)) {
            html(ex, 200, form(""));
            return;
        }
        if (!"POST".equals(method)) {
            reply(ex, 405, "method");
            return;
        }
        String ip = clientIp(ex);
        if (failedTooOften(ip)) {
            html(ex, 429, form("Too many tries. Wait a minute and try again."));
            return;
        }
        String ct = ex.getRequestHeaders().getFirst("Content-Type");
        byte[] raw = ex.getRequestBody().readNBytes(2049);
        if (raw.length > 2048) {
            html(ex, 413, form("That request was too large."));
            return;
        }
        if (ct == null || !ct.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            html(ex, 400, form("Use the form on this page."));
            return;
        }
        Map<String, String> f = new HashMap<>();
        for (String part : new String(raw, StandardCharsets.UTF_8).split("&", 10)) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            try {
                f.put(part.substring(0, i), java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { /* skip */ }
        }
        String code = f.getOrDefault("code", ""), name = f.getOrDefault("name", "").trim();
        if (!NAME.matcher(name).matches() || !KeyStore.validLabel(name)) {
            html(ex, 400, form("Pick a name of 2 to 30 letters, digits, dots, dashes or underscores (for example your Minecraft name)."));
            return;
        }
        if (redeemedTooOften(ip)) {
            html(ex, 429, form("This address already made its keys for today. Try again tomorrow."));
            return;
        }
        if (keys.hasLabel(name)) {
            html(ex, 409, form("That name is already taken. Pick another."));
            return;
        }
        if (!invites.take(code)) {
            noteFailure(ip);
            html(ex, 403, form("That code didn't work. It may be wrong, already used or expired."));
            return;
        }
        String key;
        try {
            key = keys.add(KeyStore.Tier.FREE, name);
        } catch (IllegalArgumentException e) {
            invites.refund(code);
            html(ex, 409, form("That name is already taken. Pick another."));
            return;
        } catch (IOException e) {
            invites.refund(code);
            html(ex, 500, form("Something went wrong. Try again in a minute."));
            return;
        }
        noteRedeemed(ip);
        Log.LOGGER.info("Key made with an invite code for {} from {}", name, ip);
        html(ex, 200, Pages.yourKey(key, "https://" + host(ex), "Keep it private: it can be taken away if it is shared."));
    }

    private static String host(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Host");
        return h != null && h.matches("[A-Za-z0-9.-]{1,100}(:[0-9]{1,5})?") ? h : "this server";
    }

    static String esc(String s) {
        return Pages.esc(s);
    }

    private boolean redeemedTooOften(String ip) {
        long[] w = redeemed.get(ip);
        return w != null && System.currentTimeMillis() - w[0] < 86_400_000L && w[1] >= cfg.redeemPerAddressPerDay;
    }

    private void noteRedeemed(String ip) {
        long now = System.currentTimeMillis();
        if (redeemed.size() > 50_000) redeemed.entrySet().removeIf(e -> now - e.getValue()[0] > 86_400_000L);
        redeemed.compute(ip, (k, w) -> {
            if (w == null || now - w[0] >= 86_400_000L) return new long[]{now, 1};
            w[1]++;
            return w;
        });
    }

    String form(String message) {
        return Pages.getKey(discord != null && discord.enabled(), message);
    }

    static String page(String body) {
        return Pages.page("Valtrin", body, false);
    }

    static void html(HttpExchange ex, int code, String html) throws IOException {
        byte[] data = html.getBytes(StandardCharsets.UTF_8);
        var h = ex.getResponseHeaders();
        h.add("Content-Type", "text/html; charset=utf-8");
        h.add("Cache-Control", "no-store");
        h.add("Referrer-Policy", "no-referrer");
        h.add("X-Content-Type-Options", "nosniff");
        h.add("X-Frame-Options", "DENY");
        h.add("Content-Security-Policy", Pages.CSP);
        ex.sendResponseHeaders(code, data.length);
        try (OutputStream o = ex.getResponseBody()) {
            o.write(data);
        }
        ex.close();
    }

    private void sales(HttpExchange ex, String path) throws IOException {
        Map<String, String> q = query(ex);
        int days = 14;
        try {
            days = Math.max(1, Math.min(30, Integer.parseInt(q.getOrDefault("days", "14"))));
        } catch (NumberFormatException ignored) { /* default */ }
        String id = path.equals("/sales") ? q.get("id") : null;
        if (path.equals("/sales") && !Validate.itemId(id)) {
            reply(ex, 400, "id");
            return;
        }
        SaleLog log = OwnModel.log;
        if (log == null) {
            reply(ex, 503, "starting");
            return;
        }
        if (path.equals("/coverage")) {
            reply(ex, 200, String.valueOf(log.coveredMinutes(System.currentTimeMillis(), days)));
            return;
        }
        reply(ex, 200, log.rawSales(id, days));
    }

    private void stream(HttpExchange ex, KeyStore.Entry entry, String ip) throws IOException {
        Hub.Session s = hub.open(entry.hash(), entry.tier(), ip);
        if (s == null) {
            reply(ex, 503, "full");
            return;
        }
        Log.LOGGER.info("Stream open: {} ({}) from {}, delay {} ms", entry.label(), entry.tier(), ip, hub.delayFor(entry.tier()));
        try {
            var h = ex.getResponseHeaders();
            h.add("Content-Type", "text/event-stream; charset=utf-8");
            h.add("Cache-Control", "no-cache");
            h.add("X-Accel-Buffering", "no");
            ex.sendResponseHeaders(200, 0);
            OutputStream out = ex.getResponseBody();
            write(out, "event: hello\ndata: " + Wire.encodeHello(entry.tier().name(), hub.delayFor(entry.tier()), FlipConfig.VERSION) + "\n\n");
            while (!s.closed) {
                String frame = s.out.poll(15, TimeUnit.SECONDS);
                if (s.closed) break;
                if (frame == null && !keys.hasHash(entry.hash())) break; // key was revoked
                write(out, frame == null ? ": keepalive\n\n" : frame);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // client went away
        } finally {
            hub.close(s);
            Log.LOGGER.info("Stream closed: {}", entry.label());
            ex.close();
        }
    }

    private static void write(OutputStream out, String text) throws IOException {
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    // ------------------------------------------------------------------ helpers

    private static void reply(HttpExchange ex, int code, String body) throws IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (code == 200 && data.length > 512 && accept != null && accept.contains("gzip")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) {
                gz.write(data);
            }
            data = bo.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        if (data.length > 0) try (OutputStream o = ex.getResponseBody()) {
            o.write(data);
        }
        ex.close();
    }

    static Map<String, String> queryOf(HttpExchange ex) {
        return query(ex);
    }

    private static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.length() > 500) return q;
        for (String part : raw.split("&", 20)) {
            int i = part.indexOf('=');
            if (i <= 0) continue;
            try {
                q.put(part.substring(0, i), java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            } catch (IllegalArgumentException ignored) { /* bad escape: skip */ }
        }
        return q;
    }

    /** The caller's address. Behind Caddy (a loopback peer) the last X-Forwarded-For entry, which Caddy added, is used. */
    static String clientIp(HttpExchange ex) {
        var peer = ex.getRemoteAddress().getAddress();
        String ip = peer.getHostAddress();
        if (peer.isLoopbackAddress()) {
            String xff = ex.getRequestHeaders().getFirst("X-Forwarded-For");
            if (xff != null && xff.length() < 300) {
                String last = xff.substring(xff.lastIndexOf(',') + 1).trim();
                if (last.matches("[0-9a-fA-F:.]{2,45}")) return last;
            }
        }
        return ip;
    }

    boolean failedTooOften(String ip) {
        long[] w = fails.get(ip);
        long now = System.currentTimeMillis();
        return w != null && now - w[0] < 60_000 && w[1] >= cfg.maxFailsPerMinute;
    }

    void noteFailure(String ip) {
        long now = System.currentTimeMillis();
        if (fails.size() > 50_000) fails.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        fails.compute(ip, (k, w) -> {
            if (w == null || now - w[0] >= 60_000) return new long[]{now, 1};
            w[1]++;
            return w;
        });
    }

    /** Token bucket per key: 200 requests burst, 40 per second sustained. */
    private boolean takeToken(String keyHash) {
        long now = System.currentTimeMillis();
        long[] b = buckets.computeIfAbsent(keyHash, k -> new long[]{200_000, now});
        synchronized (b) {
            b[0] = Math.min(200_000, b[0] + (now - b[1]) * 40);
            b[1] = now;
            if (b[0] < 1000) return false;
            b[0] -= 1000;
            return true;
        }
    }
}
