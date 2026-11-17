package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * Stand-alone Valtrin data recorder. Watches Hypixel's public "ended auctions" feed and stores every sold BIN
 * auction (with ALL modifiers the current mod knows about) in the folder the Valtrin mod reads.
 * Needs no Minecraft and no login.
 *
 * Usage: java -jar valtrin-recorder.jar [data folder]   (default: ~/ValtrinData/sales)
 */
public final class RecorderMain {
    private static final String BASE = System.getProperty("hypixel.base", "https://api.hypixel.net/v2/skyblock/");

    public static void main(String[] args) throws Exception {
        Path root = args.length > 0 ? Path.of(args[0])
                : Path.of(System.getProperty("user.home"), "ValtrinData", "sales");
        SaleLog.markFormat(root); // creates the folder and marks it as the current sales format

        // Only one recorder at a time.
        FileChannel lockChannel = FileChannel.open(root.resolve("recorder.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock lock = lockChannel.tryLock();
        if (lock == null) {
            System.out.println("Another Valtrin recorder is already running. Exiting.");
            return;
        }

        SaleLog log = new SaleLog(root);
        log.init();
        Runtime.getRuntime().addShutdownHook(new Thread(log::flush));

        startServer(log);

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        long lastUpdated = 0;
        Set<String> lastIds = new HashSet<>();
        long recorded = 0, lastReport = 0;
        int failures = 0;
        System.out.println("Valtrin recorder started. Saving to: " + root);

        while (true) {
            long sleep = 15_000;
            try {
                log.heartbeat();
                JsonObject o = get(http, "auctions_ended");
                long lu = o.get("lastUpdated").getAsLong();
                if (lu != lastUpdated) {
                    lastUpdated = lu;
                    Set<String> ids = new HashSet<>();
                    JsonArray arr = o.getAsJsonArray("auctions");
                    if (arr != null) {
                        for (JsonElement el : arr) {
                            JsonObject a = el.getAsJsonObject();
                            String id = a.get("auction_id").getAsString();
                            ids.add(id);
                            if (lastIds.contains(id)) continue;
                            if (!a.has("bin") || !a.get("bin").getAsBoolean()) continue;
                            ItemData d = ItemKeys.parse(HypixelApi.itemBytes(a.get("item_bytes")));
                            if (d == ItemData.NONE || d.tier() == null || d.cosmetic()) continue;
                            long price = a.has("price") ? a.get("price").getAsLong() : 0;
                            if (price <= 0) continue;
                            log.add(d.id(), a.get("timestamp").getAsLong(), price, d.tier(), d.base(), ModTokens.of(d));
                            recorded++;
                        }
                    }
                    lastIds = ids;
                    log.markCovered(lu / 60_000L);
                    log.flush();
                }
                failures = 0;
            } catch (Exception e) {
                failures++;
                sleep = Math.min(120_000, 10_000L * failures);
                System.out.println("Poll failed (" + e + "), retrying in " + sleep / 1000 + "s");
            }
            long now = System.currentTimeMillis();
            if (now - lastReport > 5 * 60_000L) {
                lastReport = now;
                System.out.println(java.time.LocalTime.now().withNano(0) + "  sales recorded since start: " + recorded);
            }
            Thread.sleep(sleep);
        }
    }

    /** Optional: share the recorded data with friends' mods. Configured by server.properties next to the jar (port=, key=). */
    private static void startServer(SaleLog log) {
        try {
            Path jar = Path.of(Recorder.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path props = (Files.isDirectory(jar) ? jar : jar.getParent()).resolve("server.properties");
            if (!Files.isRegularFile(props)) return;
            java.util.Properties p = new java.util.Properties();
            try (var in = Files.newInputStream(props)) { p.load(in); }
            String key = p.getProperty("key", "").trim();
            int port = Integer.parseInt(p.getProperty("port", "8787").trim());
            if (key.length() < 8 || key.equals("CHANGE-ME-TO-A-LONG-RANDOM-WORD")) {
                System.out.println("server.properties found but the key is missing/too short (min 8 characters): sharing is OFF.");
                return;
            }
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(port), 0);
            server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
            server.createContext("/", ex -> handle(ex, log, key));
            server.start();
            System.out.println("Sharing data with friends on port " + port + " (key required).");
        } catch (Exception e) {
            System.out.println("Could not start the sharing server: " + e);
        }
    }

    private static void handle(com.sun.net.httpserver.HttpExchange ex, SaleLog log, String key) throws java.io.IOException {
        try {
            String sent = ex.getRequestHeaders().getFirst("X-Key");
            boolean ok = sent != null && java.security.MessageDigest.isEqual(
                    sent.getBytes(StandardCharsets.UTF_8), key.getBytes(StandardCharsets.UTF_8));
            if (!ok) { reply(ex, 401, "wrong key"); return; }
            String path = ex.getRequestURI().getPath();
            java.util.Map<String, String> q = new java.util.HashMap<>();
            String raw = ex.getRequestURI().getRawQuery();
            if (raw != null) for (String part : raw.split("&")) {
                int i = part.indexOf('=');
                if (i > 0) q.put(part.substring(0, i), java.net.URLDecoder.decode(part.substring(i + 1), StandardCharsets.UTF_8));
            }
            int days = 14;
            try { days = Math.max(1, Math.min(30, Integer.parseInt(q.getOrDefault("days", "14")))); } catch (NumberFormatException ignored) {}
            switch (path) {
                case "/ping" -> reply(ex, 200, "ok");
                case "/sales" -> {
                    String id = q.get("id");
                    if (id == null || id.isEmpty() || id.length() > 80) { reply(ex, 400, "id"); return; }
                    reply(ex, 200, log.rawSales(id, days));
                }
                case "/coverage" -> reply(ex, 200, String.valueOf(log.coveredMinutes(System.currentTimeMillis(), days)));
                default -> reply(ex, 404, "not found");
            }
        } catch (Exception e) {
            reply(ex, 500, "error");
        }
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] data = body.getBytes(StandardCharsets.UTF_8);
        String accept = ex.getRequestHeaders().getFirst("Accept-Encoding");
        if (code == 200 && data.length > 512 && accept != null && accept.contains("gzip")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(bo)) { gz.write(data); }
            data = bo.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, data.length == 0 ? -1 : data.length);
        if (data.length > 0) try (java.io.OutputStream o = ex.getResponseBody()) { o.write(data); }
        ex.close();
    }

    private static JsonObject get(HttpClient http, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(BASE + path))
                .timeout(Duration.ofSeconds(25))
                .header("User-Agent", "Valtrin-Recorder/1.0 (personal data recorder)")
                .header("Accept-Encoding", "gzip")
                .GET().build();
        HttpResponse<InputStream> res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        if (res.statusCode() != 200) {
            res.body().close();
            throw new java.io.IOException("HTTP " + res.statusCode());
        }
        String enc = res.headers().firstValue("Content-Encoding").orElse("");
        try (InputStream in = enc.equalsIgnoreCase("gzip") ? new GZIPInputStream(res.body()) : res.body();
             InputStreamReader r = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            if (obj.has("success") && !obj.get("success").getAsBoolean()) throw new java.io.IOException("API success=false");
            return obj;
        }
    }
}
