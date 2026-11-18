// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * "Sign in with Discord": a person clicks one button, logs in at Discord, and gets their own FREE key. Valtrin never
 * sees their Discord password, stores no tokens, and keeps only the Discord numeric id (to give each account one key).
 *
 * Flow: /login makes a random state (also set as a cookie) and sends the browser to Discord; Discord sends it back to
 * /auth/discord/callback with a code and the same state; the server checks the state against the cookie, swaps the
 * code for a token, reads the account id (and, if asked, the server list) once, and discards the token.
 */
final class DiscordLogin {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long STATE_TTL_MS = 10 * 60_000L;
    private static final long REGENERATE_MIN_MS = 60 * 60_000L;

    private final ServerSettings cfg;
    private final KeyStore keys;
    private final Api api;
    private final Path accountsFile;
    private final String secret;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ConcurrentHashMap<String, Long> states = new ConcurrentHashMap<>();
    /** ip -> {window start, requests} for /login and the callback. */
    private final ConcurrentHashMap<String, long[]> hits = new ConcurrentHashMap<>();

    DiscordLogin(ServerSettings cfg, KeyStore keys, Api api, Path configDir) {
        this.cfg = cfg;
        this.keys = keys;
        this.api = api;
        this.accountsFile = configDir.resolve("discord-accounts.tsv");
        String s = "";
        try {
            Path f = configDir.resolve("discord-secret.txt");
            if (Files.isRegularFile(f)) s = Files.readString(f, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            Log.LOGGER.warn("Could not read the Discord secret file: {}", e.toString());
        }
        this.secret = s;
        if (enabled()) Log.LOGGER.info("Sign in with Discord is on (min account age {} days{})", cfg.discordMinAccountDays,
                cfg.discordGuildId.isEmpty() ? "" : ", server members only");
        else if (!cfg.discordClientId.isEmpty() || !cfg.publicUrl.isEmpty()) Log.LOGGER.warn("Sign in with Discord is NOT on: needs publicUrl, discordClientId and config/discord-secret.txt");
    }

    boolean enabled() {
        return !cfg.publicUrl.isEmpty() && cfg.discordClientId.matches("[0-9]{5,25}") && secret.length() >= 16
                && (cfg.publicUrl.startsWith("https://") || cfg.publicUrl.startsWith("http://localhost") || cfg.publicUrl.startsWith("http://127.0.0.1"));
    }

    private String redirectUri() {
        return cfg.publicUrl + "/auth/discord/callback";
    }

    private boolean tooManyHits(String ip) {
        long now = System.currentTimeMillis();
        if (hits.size() > 50_000) hits.entrySet().removeIf(e -> now - e.getValue()[0] > 60_000);
        long[] w = hits.compute(ip, (k, v) -> {
            if (v == null || now - v[0] >= 60_000) return new long[]{now, 1};
            v[1]++;
            return v;
        });
        return w[1] > 30;
    }

    // ------------------------------------------------------------------ step 1: send the browser to Discord

    void login(HttpExchange ex) throws IOException {
        if (!enabled()) {
            Api.html(ex, 404, Pages.error("Not available", "Sign in with Discord is not set up on this server."));
            return;
        }
        if (tooManyHits(Api.clientIp(ex))) {
            Api.html(ex, 429, Pages.error("Slow down", "Too many tries. Wait a minute and try again."));
            return;
        }
        long now = System.currentTimeMillis();
        if (states.size() > 10_000) states.entrySet().removeIf(e -> now - e.getValue() > STATE_TTL_MS);
        byte[] raw = new byte[24];
        RANDOM.nextBytes(raw);
        String state = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        states.put(state, now);
        String scope = cfg.discordGuildId.isEmpty() ? "identify" : "identify guilds";
        String url = "https://discord.com/oauth2/authorize?client_id=" + enc(cfg.discordClientId) + "&redirect_uri=" + enc(redirectUri())
                + "&response_type=code&scope=" + enc(scope) + "&state=" + enc(state) + "&prompt=none";
        var h = ex.getResponseHeaders();
        h.add("Location", url);
        h.add("Set-Cookie", "vstate=" + state + "; Max-Age=600; Path=/auth; HttpOnly; SameSite=Lax" + (cfg.publicUrl.startsWith("https://") ? "; Secure" : ""));
        h.add("Cache-Control", "no-store");
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    // ------------------------------------------------------------------ step 2: Discord sends the person back

    void callback(HttpExchange ex) throws IOException {
        if (!enabled()) {
            Api.html(ex, 404, Pages.error("Not available", "Sign in with Discord is not set up on this server."));
            return;
        }
        String ip = Api.clientIp(ex);
        if (tooManyHits(ip) || api.failedTooOften(ip)) {
            Api.html(ex, 429, Pages.error("Slow down", "Too many tries. Wait a minute and try again."));
            return;
        }
        Map<String, String> q = Api.queryOf(ex);
        String state = q.get("state"), code = q.get("code");
        String cookie = cookie(ex, "vstate");
        Long made = state == null ? null : states.remove(state);
        if (state == null || cookie == null || !java.security.MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8), cookie.getBytes(StandardCharsets.UTF_8))
                || made == null || System.currentTimeMillis() - made > STATE_TTL_MS) {
            api.noteFailure(ip);
            fail(ex, 400, "That sign-in link expired or did not start here. Go back to the key page and click the button again.");
            return;
        }
        if (code == null || code.isEmpty() || code.length() > 200 || q.containsKey("error")) {
            fail(ex, 400, "Discord sign-in was cancelled. Click the button again to retry.");
            return;
        }
        String id, token;
        try {
            token = exchange(code);
            JsonObject me = getJson("/users/@me", token);
            id = text(me, "id");
            if (id == null || !id.matches("[0-9]{15,22}")) throw new IOException("bad id");
            long created = (Long.parseLong(id) >> 22) + 1_420_070_400_000L;
            if (System.currentTimeMillis() - created < cfg.discordMinAccountDays * 86_400_000L) {
                fail(ex, 403, "Your Discord account is too new (it must be at least " + cfg.discordMinAccountDays + " days old).");
                return;
            }
            if (!cfg.discordGuildId.isEmpty() && !inGuild(token, cfg.discordGuildId)) {
                fail(ex, 403, "You need to be a member of the Valtrin Discord server to get a key.");
                return;
            }
        } catch (IOException | RuntimeException e) {
            Log.LOGGER.warn("Discord sign-in failed: {}", e.toString());
            fail(ex, 502, "Could not finish signing in with Discord. Try again in a minute.");
            return;
        }
        issue(ex, id);
    }

    private void issue(HttpExchange ex, String discordId) throws IOException {
        String label = "discord-" + discordId;
        long now = System.currentTimeMillis();
        String key;
        try {
            key = KeyStore.withFileLock(accountsFile, () -> {
                Map<String, long[]> known = new java.util.HashMap<>();
                if (Files.isRegularFile(accountsFile)) for (String line : Files.readAllLines(accountsFile, StandardCharsets.UTF_8)) {
                    String[] p = line.split("\t");
                    if (p.length == 2 && p[0].matches("[0-9]{15,22}")) known.put(p[0], new long[]{Long.parseLong(p[1])});
                }
                long[] last = known.get(discordId);
                if (last != null && now - last[0] < REGENERATE_MIN_MS) return null;
                String k = keys.replace(label, KeyStore.Tier.FREE); // keeps a premium plan if the account has one
                known.put(discordId, new long[]{now});
                StringBuilder sb = new StringBuilder();
                for (var e : known.entrySet()) sb.append(e.getKey()).append('\t').append(e.getValue()[0]).append('\n');
                Path tmp = accountsFile.resolveSibling(accountsFile.getFileName() + ".tmp");
                Files.writeString(tmp, sb.toString(), StandardCharsets.UTF_8);
                try { Files.setPosixFilePermissions(tmp, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) {}
                Files.move(tmp, accountsFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                return k;
            });
        } catch (IOException | RuntimeException e) {
            Log.LOGGER.warn("Could not make a key for a Discord account: {}", e.toString());
            fail(ex, 500, "Something went wrong. Try again in a minute.");
            return;
        }
        if (key == null) {
            fail(ex, 429, "You made a key less than an hour ago. If you lost it, wait an hour and sign in again to get a new one (the old one stops working).");
            return;
        }
        Log.LOGGER.info("Key made for a Discord account (id {})", discordId);
        api.accountChanged();
        Api.html(ex, 200, Pages.yourKey(key, cfg.publicUrl, "If you sign in again later you get a new key and this one stops working."));
    }

    // ------------------------------------------------------------------ talking to Discord

    private String exchange(String code) throws IOException {
        String body = "client_id=" + enc(cfg.discordClientId) + "&client_secret=" + enc(secret) + "&grant_type=authorization_code&code=" + enc(code)
                + "&redirect_uri=" + enc(redirectUri());
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.discordApiBase + "/oauth2/token")).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded").header("User-Agent", "Valtrin (https://valtrin.app, 1)")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        JsonObject o = send(req);
        String t = text(o, "access_token");
        if (t == null || t.length() > 200) throw new IOException("no token");
        return t;
    }

    private JsonObject getJson(String path, String token) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.discordApiBase + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("User-Agent", "Valtrin (https://valtrin.app, 1)").GET().build();
        return send(req);
    }

    private boolean inGuild(String token, String guildId) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.discordApiBase + "/users/@me/guilds")).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).header("User-Agent", "Valtrin (https://valtrin.app, 1)").GET().build();
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200 || r.body().length() > 2_000_000) throw new IOException("guilds " + r.statusCode());
            for (JsonElement e : JsonParser.parseString(r.body()).getAsJsonArray()) {
                if (e.isJsonObject() && guildId.equals(text(e.getAsJsonObject(), "id"))) return true;
            }
            return false;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException(ie);
        }
    }

    private JsonObject send(HttpRequest req) throws IOException {
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() != 200 || r.body().length() > 200_000) throw new IOException("discord answered " + r.statusCode());
            JsonElement e = JsonParser.parseString(r.body());
            if (!e.isJsonObject()) throw new IOException("unexpected answer");
            return e.getAsJsonObject();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException(ie);
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static String text(JsonObject o, String k) {
        JsonElement e = o.get(k);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static String cookie(HttpExchange ex, String name) {
        var list = ex.getRequestHeaders().get("Cookie");
        if (list == null) return null;
        for (String header : list) {
            if (header.length() > 4000) continue;
            for (String part : header.split(";")) {
                String p = part.trim();
                if (p.startsWith(name + "=")) return p.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static void fail(HttpExchange ex, int code, String message) throws IOException {
        Api.html(ex, code, Pages.error("Could not get a key", message));
    }
}
