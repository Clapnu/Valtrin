package dev.valtrin;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Local end-to-end tests of the server program (keys, API, hub, delays) and of the mod's ServerLink against it. */
public final class ServerTests {
    /** Test helper: does this plain code hash to this stored hash? */
    static final class InviteStoreAccess {
        static boolean matches(String pretty, String storedHash) {
            String c = InviteStore.normalize(pretty);
            return c != null && KeyStore.hash("invite:" + c).equals(storedHash);
        }
    }

    static int pass, fail;
    static com.google.gson.JsonObject parse(String j) { return com.google.gson.JsonParser.parseString(j).getAsJsonObject(); }

    /** Waits up to 5 s for a recorded request containing the text. */
    static boolean waitFor(java.util.List<String> seen, String text) throws Exception {
        for (int i = 0; i < 100; i++) {
            synchronized (seen) { for (String x : seen) if (x.contains(text)) return true; }
            Thread.sleep(50);
        }
        synchronized (seen) { String all = String.join(" || ", seen); System.out.println("  (waited for \"" + text + "\"; saw " + (all.length() > 600 ? all.substring(0, 600) + "..." : all) + ")"); }
        return false;
    }

    /** A gzip-compressed NBT item with a display name and an item id, in the same shape as an auction's item_bytes. */
    static String itemB64(String displayName, String id, int upgrade) throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        java.io.DataOutputStream o = new java.io.DataOutputStream(raw);
        o.writeByte(10); o.writeUTF("");
        o.writeByte(9); o.writeUTF("i"); o.writeByte(10); o.writeInt(1);
        o.writeByte(10); o.writeUTF("tag");
        o.writeByte(10); o.writeUTF("display");
        o.writeByte(8); o.writeUTF("Name"); o.writeUTF(displayName);
        o.writeByte(9); o.writeUTF("Lore"); o.writeByte(8); o.writeInt(1); o.writeUTF("\u00a76\u00a7lLEGENDARY");
        o.writeByte(0);
        o.writeByte(10); o.writeUTF("ExtraAttributes");
        o.writeByte(8); o.writeUTF("id"); o.writeUTF(id);
        o.writeByte(3); o.writeUTF("upgrade_level"); o.writeInt(upgrade);
        o.writeByte(0);
        o.writeByte(0);
        o.writeByte(0);
        o.writeByte(0);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream g = new java.util.zip.GZIPOutputStream(gz)) { g.write(raw.toByteArray()); }
        return java.util.Base64.getEncoder().encodeToString(gz.toByteArray());
    }

    static void check(String n, boolean ok) { if (ok) pass++; else { fail++; System.out.println("FAIL: " + n); } }

    static final HttpClient HTTP = HttpClient.newHttpClient();

    static int status(String url, String key, String method) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).method(method, HttpRequest.BodyPublishers.noBody());
        if (key != null) b.header("X-Key", key);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString()).statusCode();
    }

    /** Opens a stream and puts each received line on the queue. */
    static BlockingQueue<String> open(String url, String key, Thread[] holder) throws Exception {
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        HttpRequest r = HttpRequest.newBuilder(URI.create(url)).header("X-Key", key).GET().build();
        HttpResponse<java.io.InputStream> resp = HTTP.send(r, HttpResponse.BodyHandlers.ofInputStream());
        q.add("STATUS " + resp.statusCode());
        Thread t = Thread.ofVirtual().start(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(resp.body()))) {
                String l;
                while ((l = br.readLine()) != null) q.add(l);
            } catch (Exception ignored) {}
            q.add("END");
        });
        if (holder != null) holder[0] = t;
        return q;
    }

    static String waitFor(BlockingQueue<String> q, String prefix, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            String l = q.poll(200, TimeUnit.MILLISECONDS);
            if (l != null && l.startsWith(prefix)) return l;
            if ("END".equals(l)) return null;
        }
        return null;
    }


    static HttpResponse<String> post(String url, String contentType, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    static HttpResponse<String> redeem(String base, String code, String name) throws Exception {
        return post(base + "/redeem", "application/x-www-form-urlencoded",
                "code=" + java.net.URLEncoder.encode(code, "UTF-8") + "&name=" + java.net.URLEncoder.encode(name, "UTF-8"));
    }

    static String keyIn(String html) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("vk_[A-Za-z0-9_-]{43}").matcher(html);
        return m.find() ? m.group() : null;
    }

    static FlipAlert alert(String uuid, String name) {
        return new FlipAlert(name, "LEGENDARY", uuid, null, 1_000_000, 2_000_000, 900_000, 90.0, 25, "detail", null, "HIGH", null);
    }

    static void tarEntry(java.io.ByteArrayOutputStream o, String name, char type, String body) throws java.io.IOException {
        byte[] b = body.getBytes(java.nio.charset.StandardCharsets.UTF_8), h = new byte[512];
        byte[] nm = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        System.arraycopy(nm, 0, h, 0, Math.min(100, nm.length));
        byte[] sz = String.format("%011o", b.length).getBytes();
        System.arraycopy(sz, 0, h, 124, sz.length);
        h[156] = (byte) type;
        o.write(h);
        o.write(b);
        o.write(new byte[(512 - b.length % 512) % 512]);
    }

    public static void main(String[] a) throws Exception {
        Path dir = Files.createTempDirectory("valtrin-st");
        int port = 20000 + (int) (Math.random() * 20000);
        Files.createDirectories(dir.resolve("config"));
        Files.writeString(dir.resolve("config/valtrin-server.properties"),
                "port=" + port + "\nfreeDelaySeconds=1\npremiumEnabled=false\nmaxSessions=3\nmaxFailsPerMinute=5\n");
        ServerSettings st = ServerSettings.load(dir.resolve("config"));
        KeyStore keys = new KeyStore(dir.resolve("config/keys.tsv"));
        String free = keys.add(KeyStore.Tier.FREE, "free1"), prem = keys.add(KeyStore.Tier.PREMIUM, "prem1");
        String free2 = keys.add(KeyStore.Tier.FREE, "free2"), free3 = keys.add(KeyStore.Tier.FREE, "free3"), free4 = keys.add(KeyStore.Tier.FREE, "free4");
        Hub hub = new Hub(st);
        Api api = new Api(st, keys, hub, new InviteStore(dir.resolve("config/invites.tsv")), dir.resolve("config"));
        api.start();
        String base = "http://127.0.0.1:" + port;

        // keys
        check("key looks right", KeyStore.looksLikeKey(free) && free.length() == 46);
        check("keys.tsv holds hashes, not keys", !Files.readString(dir.resolve("config/keys.tsv")).contains(free));
        check("lookup free", keys.lookup(free) != null && keys.lookup(free).tier() == KeyStore.Tier.FREE);
        check("lookup bad", keys.lookup(null) == null && keys.lookup("") == null && keys.lookup("vk_" + "A".repeat(43)) == null && keys.lookup(free + "x") == null);
        try { keys.add(KeyStore.Tier.FREE, "bad\tlabel"); check("label tab refused", false); } catch (IllegalArgumentException e) { check("label tab refused", true); }
        try { keys.add(KeyStore.Tier.FREE, "FREE1"); check("duplicate label refused", false); } catch (IllegalArgumentException e) { check("duplicate label refused", true); }
        check("file private", java.nio.file.Files.getPosixFilePermissions(dir.resolve("config/keys.tsv")).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));

        // plain requests
        check("ping with key", status(base + "/ping", free, "GET") == 200);
        check("settings: settings premium default", !st.premiumEnabled && st.freeDelayMs == 1000);
        check("post refused", status(base + "/ping", free, "POST") == 405);
        check("delete refused", status(base + "/ping", free, "DELETE") == 405);
        check("unknown path 404", status(base + "/etc/passwd", free, "GET") == 404);
        check("traversal id refused", status(base + "/sales?id=..%2F..%2Fetc%2Fpasswd", free, "GET") == 400);
        check("sales id with newline refused", status(base + "/sales?id=A%0AB", free, "GET") == 400);
        check("coverage ok or starting", status(base + "/coverage", free, "GET") == 503 || status(base + "/coverage", free, "GET") == 200);
        for (String badKey : new String[]{"", "vk_short", "vk_" + "A".repeat(43), free.toUpperCase(), "x".repeat(5000), "vk_" + "é".repeat(43)}) {
            int s;
            try { s = status(base + "/ping", badKey.isEmpty() ? null : badKey, "GET"); } catch (Exception e) { s = 401; }
            check("bad key refused " + (badKey.length() > 12 ? badKey.substring(0, 12) : badKey), s == 401 || s == 429);
        }
        check("rate limit on failures", status(base + "/ping", "vk_" + "B".repeat(43), "GET") == 429);
        check("rate limit blocks even the real key from that address", status(base + "/ping", free, "GET") == 429);

        // The failure limiter is per address; further tests use a fresh Api so the lockout does not get in the way.
        api.stop();
        Files.writeString(dir.resolve("config/valtrin-server.properties"),
                "port=" + (port + 1) + "\nfreeDelaySeconds=1\npremiumEnabled=false\nmaxSessions=3\nmaxFailsPerMinute=50\n");
        st = ServerSettings.load(dir.resolve("config"));
        hub = new Hub(st);
        api = new Api(st, keys, hub, new InviteStore(dir.resolve("config/invites.tsv")), dir.resolve("config"));
        api.start();
        base = "http://127.0.0.1:" + (port + 1);

        // delays: free waits 1 s, premium also waits while premium is not enabled
        BlockingQueue<String> qf = open(base + "/v1/stream", free, null);
        BlockingQueue<String> qp = open(base + "/v1/stream", prem, null);
        check("stream status 200", "STATUS 200".equals(qf.take()) && "STATUS 200".equals(qp.take()));
        check("hello arrives", waitFor(qf, "data: {\"tier\":\"FREE\",\"delayMs\":1000", 2000) != null);
        long t0 = System.currentTimeMillis();
        hub.flip(alert("0123456789abcdef0123456789abcdef", "Hyperion"), t0, true);
        String got = waitFor(qf, "data: {\"at\"", 4000);
        long dt = System.currentTimeMillis() - t0;
        check("free flip arrives after the delay", got != null && dt >= 900 && dt < 2500);
        check("premium waits too while premium is off", waitFor(qp, "data: {\"at\"", 3000) != null);
        hub.flip(alert("fedcba9876543210fedcba9876543210", "test"), t0, false);
        check("test alert is not sent", waitFor(qf, "data: {\"at\"", 1800) == null);

        // premium enabled: no delay
        api.stop();
        Files.writeString(dir.resolve("config/valtrin-server.properties"),
                "port=" + (port + 2) + "\nfreeDelaySeconds=3\npremiumEnabled=true\nmaxSessions=3\nmaxFailsPerMinute=50\nredeemPerAddressPerDay=4\n");
        st = ServerSettings.load(dir.resolve("config"));
        hub = new Hub(st);
        api = new Api(st, keys, hub, new InviteStore(dir.resolve("config/invites.tsv")), dir.resolve("config"));
        api.start();
        base = "http://127.0.0.1:" + (port + 2);
        qf = open(base + "/v1/stream", free, null);
        qp = open(base + "/v1/stream", prem, null);
        qf.take(); qp.take();
        waitFor(qf, "data: {\"tier\"", 2000); waitFor(qp, "data: {\"tier\"", 2000);
        t0 = System.currentTimeMillis();
        hub.flip(alert("0123456789abcdef0123456789abcdef", "Hyperion"), t0, true);
        String pg = waitFor(qp, "data: {\"at\"", 2000);
        long pdt = System.currentTimeMillis() - t0;
        check("premium flip is immediate when enabled", pg != null && pdt < 500);
        check("free still waits 3 s", waitFor(qf, "data: {\"at\"", 1500) == null && waitFor(qf, "data: {\"at\"", 3000) != null);

        // ---- website feed: /v1/flips
        final String feedBase = base;
        java.util.function.BiFunction<String, String[], HttpResponse<String>> feed = (k, hdr) -> {
            try {
                HttpRequest.Builder fb = HttpRequest.newBuilder(URI.create(feedBase + "/v1/flips")).GET();
                if (k != null) fb.header("X-Key", k);
                for (int i = 0; i + 1 < hdr.length; i += 2) fb.header(hdr[i], hdr[i + 1]);
                return HTTP.send(fb.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        long nowF = System.currentTimeMillis();
        hub.flip(new FlipAlert("Seller Test", "EPIC", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "sellerid0000000000000000000000ab", 5_000_000, 6_000_000, 900_000, 18.0, 12, "d", "SecretSeller", "HIGH", null), nowF, true);
        hub.flip(alert("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "Ancient Flip"), nowF - 31 * 60_000L, true);
        HttpResponse<String> fp = feed.apply(prem, new String[0]);
        check("feed needs a key", feed.apply(null, new String[0]).statusCode() == 401);
        check("premium feed has the new flip at once, as JSON", fp.statusCode() == 200 && fp.body().contains("Seller Test")
            && fp.headers().firstValue("Content-Type").orElse("").startsWith("application/json") && fp.headers().firstValue("Cache-Control").orElse("").equals("no-store"));
        check("feed never contains seller names or seller ids", !fp.body().contains("SecretSeller") && !fp.body().contains("sellerid0000") && !fp.body().contains("sellerName"));
        check("premium looks back 24 hours, free 30 minutes", fp.body().contains("\"historyMs\":86400000") && fp.body().contains("Ancient Flip"));
        HttpResponse<String> ff = feed.apply(free, new String[0]);
        check("free feed is held back by the free delay", ff.statusCode() == 200 && !ff.body().contains("Seller Test") && ff.body().contains("\"delayMs\":3000") && ff.body().contains("\"historyMs\":1800000"));
        check("free feed does not show flips older than 30 minutes", !ff.body().contains("Ancient Flip"));
        Thread.sleep(3200);
        HttpResponse<String> after = feed.apply(free, new String[0]);
        check("free feed shows the flip after the delay", after.body().contains("Seller Test"));
        check("feed allows only the website origin", "https://valtrin.app".equals(feed.apply(free, new String[]{"Origin", "https://valtrin.app"}).headers().firstValue("Access-Control-Allow-Origin").orElse(""))
            && feed.apply(free, new String[]{"Origin", "https://evil.example"}).headers().firstValue("Access-Control-Allow-Origin").isEmpty()
            && feed.apply(free, new String[0]).headers().firstValue("Access-Control-Allow-Origin").isEmpty());
        HttpResponse<String> pre = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/flips")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", "https://valtrin.app").header("Access-Control-Request-Method", "GET").build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> preBad = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/flips")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", "https://evil.example").build(), HttpResponse.BodyHandlers.ofString());
        check("browser preflight is answered for the website only", pre.statusCode() == 204 && pre.headers().firstValue("Access-Control-Allow-Headers").orElse("").equals("X-Key")
            && pre.headers().firstValue("Access-Control-Allow-Origin").orElse("").equals("https://valtrin.app") && preBad.statusCode() == 404);
        check("feed refuses posting", status(base + "/v1/flips", free, "POST") == 405);

        // ---- flip stats: store, endpoint and gating
        {
            api.setStats(new StatsStore(dir.resolve("stats-test")));
            long n0 = System.currentTimeMillis();
            java.util.function.BiFunction<String, String, HttpResponse<String>> post = (k, body) -> {
                try {
                    HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(feedBase + "/v1/stats")).POST(HttpRequest.BodyPublishers.ofString(body));
                    if (k != null) b.header("X-Key", k);
                    return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
                } catch (Exception e) { throw new RuntimeException(e); }
            };
            String good = "[{\"ts\":" + (n0 - 60000) + ",\"item\":\"Test Sword\",\"cost\":1000000,\"got\":1500000},"
                + "{\"ts\":" + (n0 - 30000) + ",\"item\":\"Other\\tItem\",\"cost\":200,\"got\":100},"
                + "{\"ts\":" + (n0 - 1000) + ",\"item\":\"Bad\",\"cost\":-5,\"got\":9},"
                + "{\"ts\":1,\"item\":\"Ancient\",\"cost\":5,\"got\":9}]";
            HttpResponse<String> p1 = post.apply(prem, good);
            check("stats: premium key stores good flips and skips invalid ones", p1.statusCode() == 200 && p1.body().contains("\"stored\":2"));
            check("stats: sending the same flips again stores nothing new", post.apply(prem, good).body().contains("\"stored\":0"));
            check("stats: free key is refused", post.apply(free, good).statusCode() == 403 && post.apply(null, good).statusCode() == 401);
            check("stats: junk bodies are refused", post.apply(prem, "{").statusCode() == 400 && post.apply(prem, "[{\"ts\":1}]").statusCode() == 400
                && post.apply(prem, "x".repeat(25000)).statusCode() == 413);
            check("stats: a very deeply nested body is refused without harm", post.apply(prem, "[".repeat(15000)).statusCode() == 400
                && HTTP.send(HttpRequest.newBuilder(URI.create(feedBase + "/ping")).header("X-Key", prem).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode() == 200);
            HttpResponse<String> g1 = HTTP.send(HttpRequest.newBuilder(URI.create(feedBase + "/v1/stats")).header("X-Key", prem).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("stats: GET gives the stored flips, newest first, tabs removed", g1.statusCode() == 200 && g1.body().contains("Test Sword") && g1.body().contains("Other Item")
                && g1.body().indexOf("Other Item") < g1.body().indexOf("Test Sword") && !g1.body().contains("Ancient"));
            String rar = "[{\"ts\":" + (n0 - 20000) + ",\"item\":\"Rarity Blade\",\"cost\":100,\"got\":300,\"rarity\":\"legendary\"},"
                + "{\"ts\":" + (n0 - 10000) + ",\"item\":\"Odd Blade\",\"cost\":100,\"got\":300,\"rarity\":\"<b>x\"}]";
            post.apply(prem, rar);
            post.apply(prem, rar.replace("legendary", "epic"));
            HttpResponse<String> g3 = HTTP.send(HttpRequest.newBuilder(URI.create(feedBase + "/v1/stats")).header("X-Key", prem).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("stats: a known rarity is stored and returned, an unknown one is dropped, a resend is not a new flip",
                g3.body().contains("\"item\":\"Rarity Blade\"") && g3.body().contains("\"rarity\":\"LEGENDARY\"") && !g3.body().contains("EPIC")
                && !g3.body().contains("<b>") && g3.body().split("Rarity Blade", -1).length == 2);
            HttpResponse<String> gfree = HTTP.send(HttpRequest.newBuilder(URI.create(feedBase + "/v1/stats")).header("X-Key", free).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("stats: free key cannot read stats", gfree.statusCode() == 403 && !gfree.body().contains("Test Sword"));
            HttpResponse<String> d1 = HTTP.send(HttpRequest.newBuilder(URI.create(feedBase + "/v1/stats")).header("X-Key", prem).DELETE().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> g2 = HTTP.send(HttpRequest.newBuilder(URI.create(feedBase + "/v1/stats")).header("X-Key", prem).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("stats: delete removes everything", d1.body().contains("\"deleted\":true") && g2.body().contains("\"flips\":[]"));
            HttpResponse<String> pre2 = HTTP.send(HttpRequest.newBuilder(URI.create(feedBase + "/v1/stats")).method("OPTIONS", HttpRequest.BodyPublishers.noBody()).header("Origin", "https://valtrin.app").build(), HttpResponse.BodyHandlers.ofString());
            check("stats: browser preflight allows DELETE for the website only", pre2.statusCode() == 204 && pre2.headers().firstValue("Access-Control-Allow-Methods").orElse("").contains("DELETE"));
            check("stats: other methods are refused", status(feedBase + "/v1/stats", prem, "PUT") == 405 && status(feedBase + "/v1/flips", prem, "DELETE") == 405);
        }

        // ---- discord gateway protocol (no network)
        {
            String idf = DiscordGateway.identify("tok.en.value-0123456789abcdefghij");
            check("gateway: identify asks for no intents and shows online", idf.contains("\"op\":2") && idf.contains("\"intents\":0") && idf.contains("\"status\":\"online\"") && idf.contains("tok.en.value-0123456789abcdefghij"));
            check("gateway: only auth failures are fatal", DiscordGateway.fatalClose(4004) && DiscordGateway.fatalClose(4014) && !DiscordGateway.fatalClose(1000) && !DiscordGateway.fatalClose(4000) && !DiscordGateway.fatalClose(4009));
            Files.createDirectories(dir.resolve("gwcfg"));
            Files.writeString(dir.resolve("gwcfg/discord-bot-token.txt"), "tok.en.value-0123456789abcdefghij\n");
            Files.writeString(dir.resolve("gwcfg/valtrin-server.properties"), "port=1\n");
            ServerSettings gs = ServerSettings.load(dir.resolve("gwcfg"));
            DiscordGateway gw = new DiscordGateway(gs, dir.resolve("gwcfg"));
            check("gateway: on by default when a token file exists", gw.enabled() && gs.discordBotOnline);
            check("gateway: off without a token file", !new DiscordGateway(gs, dir.resolve("nonexistent-cfg")).enabled());
            final java.util.List<String> sent = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            final boolean[] closed = {false};
            java.util.concurrent.ScheduledExecutorService te = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
            DiscordGateway.Conn c = gw.new Conn(new DiscordGateway.Out() {
                public void send(String t) { sent.add(t); }
                public void close() { closed[0] = true; }
            }, te);
            c.onFrame("not json");
            c.onFrame("[1,2]");
            check("gateway: junk frames are ignored", sent.isEmpty() && !c.ended);
            c.onFrame("{\"op\":10,\"d\":{\"heartbeat_interval\":5000}}");
            check("gateway: hello is answered with identify", sent.size() == 1 && sent.get(0).contains("\"op\":2"));
            c.onFrame("{\"op\":1,\"d\":null,\"s\":null}");
            check("gateway: a requested heartbeat is sent at once", sent.size() == 2 && sent.get(1).startsWith("{\"op\":1"));
            c.onFrame("{\"op\":0,\"t\":\"READY\",\"s\":1,\"d\":{}}");
            check("gateway: ready marks the bot online", gw.online());
            c.onFrame("{\"op\":9,\"d\":false}");
            check("gateway: invalid session closes so it reconnects", c.ended && closed[0] && !gw.online());
            te.shutdownNow();
        }

        // ---- discord slash commands (stub Discord on localhost)
        {
            Files.createDirectories(dir.resolve("cmdcfg"));
            Files.writeString(dir.resolve("cmdcfg/valtrin-server.properties"), "port=1\ndiscordClientId=123456789012345678\npublicUrl=https://api.example.test\nsiteOrigin=https://site.example.test\n");
            ServerSettings cs = ServerSettings.load(dir.resolve("cmdcfg"));
            final java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 4);
            stub.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " " + body);
                ex.sendResponseHeaders(200, -1);
                ex.close();
            });
            stub.start();
            try {
                String stubBase = "http://127.0.0.1:" + stub.getAddress().getPort();
                DiscordCommands dc = new DiscordCommands(cs, "tok.en.value-0123456789abcdefghij", () -> "STATUS-TEXT", stubBase);
                check("commands: usable with a token and an application id", dc.usable());
                check("commands: not usable without an application id", !new DiscordCommands(ServerSettings.load(dir.resolve("gwcfg")), "tok.en.value-0123456789abcdefghij", () -> "x", stubBase).usable());
                dc.register();
                dc.register(); // the second call does nothing
                check("commands: registered once, with the bot token, listing /valtrin and /status",
                    seen.size() == 1 && seen.get(0).startsWith("PUT /applications/123456789012345678/commands auth=Bot tok.en") && seen.get(0).contains("\"valtrin\"") && seen.get(0).contains("\"status\""));
                String v = dc.reply("valtrin");
                check("commands: /valtrin gives the links and never mentions buying for you", v.contains("<https://site.example.test>") && v.contains("<https://site.example.test/#mod>")
                    && v.contains("<https://api.example.test/redeem>") && dc.reply("status").equals("STATUS-TEXT") && dc.reply("other") == null);
                seen.clear();
                String ix = "{\"type\":2,\"id\":\"987654321098765432\",\"token\":\"abcdefghijklmnopqrstuvwxyz0123456789\",\"data\":{\"name\":\"status\"}}";
                dc.onInteraction(com.google.gson.JsonParser.parseString(ix).getAsJsonObject());
                check("commands: a slash command is answered privately",
                    seen.size() == 1 && seen.get(0).startsWith("POST /interactions/987654321098765432/abcdefghijklmnopqrstuvwxyz0123456789/callback auth=null")
                    && seen.get(0).contains("\"flags\":64") && seen.get(0).contains("STATUS-TEXT") && seen.get(0).contains("\"parse\":[]"));
                seen.clear();
                dc.onInteraction(com.google.gson.JsonParser.parseString(ix.replace("\"type\":2", "\"type\":3")).getAsJsonObject());
                dc.onInteraction(com.google.gson.JsonParser.parseString(ix.replace("\"status\"", "\"evil\"")).getAsJsonObject());
                dc.onInteraction(com.google.gson.JsonParser.parseString(ix.replace("987654321098765432", "../../x")).getAsJsonObject());
                dc.onInteraction(com.google.gson.JsonParser.parseString(ix.replace("abcdefghijklmnopqrstuvwxyz0123456789", "bad/token?x=1&y=2....")).getAsJsonObject());
                dc.onInteraction(com.google.gson.JsonParser.parseString("{\"type\":2}").getAsJsonObject());
                check("commands: buttons, unknown commands and odd ids or tokens are ignored", seen.isEmpty());
            } finally {
                stub.stop(0);
            }
        }

        // ---- auctions: index, picture, /link, /auctions, /notify, sale DMs (stub Discord + stub Mojang)
        {
            final String SELLER = "11111111111111111111111111111111", PROF_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", PROF_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
            AuctionIndex ix = new AuctionIndex();
            long nowMs = System.currentTimeMillis();
            ix.begin();
            for (int i = 0; i < 3; i++) ix.listing(com.google.gson.JsonParser.parseString("{\"uuid\":\"u" + i + "\",\"auctioneer\":\"" + SELLER + "\",\"profile_id\":\"" + PROF_A + "\",\"item_name\":\"Builder's Wand\",\"tier\":\"LEGENDARY\",\"starting_bid\":15000000,\"bin\":true,\"end\":" + (nowMs + 13 * 86_400_000L) + "}").getAsJsonObject());
            ix.listing(com.google.gson.JsonParser.parseString("{\"uuid\":\"h1\",\"auctioneer\":\"" + SELLER + "\",\"profile_id\":\"" + PROF_B + "\",\"item_name\":\"Hyperion\",\"tier\":\"LEGENDARY\",\"starting_bid\":820000000,\"bin\":true,\"end\":" + (nowMs + 47 * 60_000L) + "}").getAsJsonObject());
            ix.listing(com.google.gson.JsonParser.parseString("{\"uuid\":\"c1\",\"auctioneer\":\"" + SELLER + "\",\"item_name\":\"Claimed Thing\",\"claimed\":true,\"starting_bid\":5,\"bin\":true,\"end\":1}").getAsJsonObject());
            ix.listing(com.google.gson.JsonParser.parseString("{\"uuid\":\"bad\",\"auctioneer\":\"../evil\",\"item_name\":\"X\",\"starting_bid\":5,\"end\":1}").getAsJsonObject());
            ix.stars("h1", 6);
            check("auctions index: nothing is visible until the snapshot is complete", ix.listings(SELLER).isEmpty());
            ix.done(1);
            check("auctions index: claimed listings and bad ids are left out", ix.listings(SELLER).size() == 4 && ix.sellers() == 1);
            check("auctions index: profiles are counted per profile, biggest first", ix.profiles(SELLER).keySet().iterator().next().equals(PROF_A) && ix.profiles(SELLER).get(PROF_A) == 3);
            check("auctions index: stars reach the listing", ix.listings(SELLER).stream().anyMatch(l -> l.name.equals("Hyperion") && l.stars == 6));
            final long[] fake = {nowMs};
            ix.clock(() -> fake[0]);
            ix.addSale(new AuctionIndex.Sale("s1", SELLER, PROF_A, "22222222222222222222222222222222", "Heroic Spirit Sceptre", "LEGENDARY", 5, 0, 13_010_000L, nowMs - 1000));
            check("auctions index: a sale is kept for the seller", ix.sales(SELLER).size() == 1 && ix.soldCount() == 1);
            fake[0] = nowMs + AuctionIndex.SOLD_KEEP_MS + 5000;
            ix.addSale(new AuctionIndex.Sale("s2", "33333333333333333333333333333333", PROF_A, "", "Other", "RARE", 0, 0, 1L, fake[0]));
            check("auctions index: sales older than 36 hours are dropped", ix.sales(SELLER).isEmpty() && ix.soldCount() == 1);
            fake[0] = nowMs;
            ix.addSale(new AuctionIndex.Sale("s3", SELLER, PROF_A, "22222222222222222222222222222222", "Heroic Spirit Sceptre", "LEGENDARY", 5, 0, 13_010_000L, nowMs - 1000));

            // a real sale from the ended feed: the name, the stars and the master star come out of the item data
            String hb = itemB64("§6Hyperion §6✪✪✪✪✪§c➌", "HYPERION", 8);
            AuctionIndex ix2 = new AuctionIndex();
            ix2.sale(com.google.gson.JsonParser.parseString("{\"auction_id\":\"x1\",\"seller\":\"" + SELLER + "\",\"seller_profile\":\"" + PROF_A + "\",\"buyer\":\"22222222222222222222222222222222\",\"price\":820000000,\"timestamp\":" + nowMs + ",\"item_bytes\":\"" + hb + "\"}").getAsJsonObject());
            AuctionIndex.Sale rs = ix2.sales(SELLER).isEmpty() ? null : ix2.sales(SELLER).get(0);
            check("auctions index: a sale from the ended feed gets its name, 5 stars and master star 3", rs != null && rs.name().equals("Hyperion") && rs.stars() == 5 && rs.master() == 3 && rs.price() == 820_000_000L);

            // the picture
            java.util.List<AuctionImage.Card> cards = new java.util.ArrayList<>();
            cards.add(new AuctionImage.Card("Heroic Spirit Sceptre", "LEGENDARY", 5, 0, 13_010_000L, 1, "→ Matin007s", 0, true));
            cards.add(new AuctionImage.Card("Hyperion", "LEGENDARY", 5, 3, 820_000_000L, 1, "47m left", 2, false));
            cards.add(new AuctionImage.Card("Builder's Wand", "LEGENDARY", 0, 0, 15_000_000L, 6, "13d 0h left", 0, false));
            byte[] png = AuctionImage.render("Clapnu", "Updated just now · page 1 / 1", 4, 1, 13_010_000L, cards);
            check("auctions picture: a PNG with the expected size", png.length > 2000 && png[1] == 'P' && png[2] == 'N' && png[3] == 'G');
            java.awt.image.BufferedImage back = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
            check("auctions picture: 920 points wide at 2x, one row of cards", back.getWidth() == 1840 && back.getHeight() == (AuctionImage.HEAD_H + AuctionImage.CARD_H + AuctionImage.GAP + 34) * 2);
            check("auctions picture: icon categories", AuctionImage.category("Necromancer Lord Chestplate").equals("chest") && AuctionImage.category("[Lvl 100] Ender Dragon").equals("pet")
                && AuctionImage.category("Mantid Helianthus Leggings").equals("legs") && AuctionImage.category("Bat Artifact").equals("tali") && AuctionImage.category("Hyperion").equals("sword"));
            check("auctions: time left text", AuctionCommands.left(47 * 60_000L).equals("47m left") && AuctionCommands.left(5 * 3_600_000L + 12 * 60_000L).equals("5h 12m left")
                && AuctionCommands.left(27 * 3_600_000L).equals("1d 3h left") && AuctionCommands.left(-5).equals("ended"));

            // commands against a stub Discord and Mojang
            Files.createDirectories(dir.resolve("aucfg"));
            Files.writeString(dir.resolve("aucfg/valtrin-server.properties"), "port=1\ndiscordClientId=123456789012345678\n");
            ServerSettings as = ServerSettings.load(dir.resolve("aucfg"));
            final java.util.List<String> seen = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
            com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 8);
            stub.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.ISO_8859_1);
                seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " ct=" + ex.getRequestHeaders().getFirst("Content-Type") + " " + body);
                String path = ex.getRequestURI().getPath();
                byte[] out = new byte[0];
                if (path.equals("/users/profiles/minecraft/Clapnu") || path.equalsIgnoreCase("/minecraft/profile/lookup/name/Clapnu")) out = ("{\"id\":\"" + SELLER + "\",\"name\":\"Clapnu\"}").getBytes();
                else if (path.equals("/users/@me/channels")) out = "{\"id\":\"555555555555555555\"}".getBytes();
                ex.sendResponseHeaders((path.startsWith("/users/profiles/minecraft/") || path.startsWith("/minecraft/profile/lookup/")) && out.length == 0 ? 404 : 200, out.length == 0 ? -1 : out.length);
                if (out.length > 0) ex.getResponseBody().write(out);
                ex.close();
            });
            stub.start();
            try {
                String sb = "http://127.0.0.1:" + stub.getAddress().getPort();
                NotifyStore ns = new NotifyStore(dir.resolve("aucfg/links.tsv"));
                AuctionCommands ac = new AuctionCommands(as, "tok.en.value-0123456789abcdefghij", ix, ns, u -> java.util.Map.of("22222222222222222222222222222222", "Matin007s"), sb, sb);
                ac.checkImage();
                String ixTok = "abcdefghijklmnopqrstuvwxyz0123456789";
                java.util.function.BiFunction<String, String, String> cmd = (name, opts) -> "{\"type\":2,\"id\":\"987654321098765432\",\"token\":\"" + ixTok + "\",\"member\":{\"user\":{\"id\":\"777777777777777777\"}},\"data\":{\"name\":\"" + name + "\"" + (opts == null ? "" : ",\"options\":" + opts) + "}}";

                check("auctions command: /auctions with nothing linked asks to link (private)", ac.handle(parse(cmd.apply("auctions", null))) && waitFor(seen, "Type a Minecraft name") && seen.get(0).contains("\"flags\":64") && seen.get(0).contains("\"type\":4"));
                seen.clear();
                ac.handle(parse(cmd.apply("link", "[{\"name\":\"name\",\"type\":3,\"value\":\"Clapnu\"}]")));
                check("auctions command: /link looks the name up and answers privately", waitFor(seen, "Linked to **Clapnu**") && ns.get("777777777777777777") != null && ns.get("777777777777777777").uuid().equals(SELLER)
                    && seen.stream().anyMatch(x -> x.startsWith("GET /users/profiles/minecraft/Clapnu")) && seen.stream().anyMatch(x -> x.contains("\"type\":5") && x.contains("\"flags\":64")));
                seen.clear();
                ac.handle(parse(cmd.apply("link", "[{\"name\":\"name\",\"type\":3,\"value\":\"../x\"}]")));
                check("auctions command: /link refuses a bad name", waitFor(seen, "not a valid Minecraft name"));
                seen.clear();
                Thread.sleep(3100);
                ac.handle(parse(cmd.apply("link", "[{\"name\":\"name\",\"type\":3,\"value\":\"Nobodyhere\"}]")));
                check("auctions command: /link says so when Mojang does not know the name", waitFor(seen, "could not find a Minecraft player") || waitFor(seen, "Nobodyhere"));
                Thread.sleep(3100);
                seen.clear();
                ac.fallbackBase(sb);
                check("auctions names: capital letters do not matter (Mojang fallback)", ac.resolve("cLaPnU") != null && ac.resolve("cLaPnU")[1].equals("Clapnu"));
                Files.writeString(dir.resolve("aucfg/names.tsv"), "55555555555555555555555555555555\tSkyHelperFan\n");
                SellerNames.init(dir.resolve("aucfg/names.tsv"));
                check("auctions names: a name seen as a seller is found in any capitals", ac.resolve("skyhelperFAN") != null && ac.resolve("skyhelperFAN")[1].equals("SkyHelperFan") && ac.resolve("zzzznotreal") == null);
                seen.clear();
                ac.handle(parse(cmd.apply("auctions", null)));
                check("auctions command: /auctions shows a picture with buttons and a sort menu",
                    waitFor(seen, "files[0]") && seen.stream().anyMatch(x -> x.startsWith("PATCH /webhooks/123456789012345678/" + ixTok + "/messages/@original ct=multipart/form-data")
                        && x.contains("\"custom_id\":\"au:" + SELLER + ":0:end:all:n\"") && x.contains("\"custom_id\":\"as:" + SELLER + ":all\"") && x.contains("image/png") && x.contains("\"parse\":[]")));
                                check("auctions command: the reply was deferred first, not private", seen.stream().anyMatch(x -> x.contains("/callback") && x.contains("\"type\":5") && !x.contains("\"flags\"")));
                seen.clear();
                Thread.sleep(1600);
                ac.handle(parse(("{\"type\":3,\"id\":\"987654321098765433\",\"token\":\"" + ixTok + "\",\"member\":{\"user\":{\"id\":\"777777777777777777\"}},\"data\":{\"custom_id\":\"as:" + SELLER + ":" + PROF_A + ",\"values\":[\"price_hi\"]}}").replace(PROF_A + ",", PROF_A + "\",")));
                check("auctions command: the sort menu redraws the picture", waitFor(seen, "files[0]") && seen.stream().anyMatch(x -> x.contains("\"type\":6")) && seen.stream().anyMatch(x -> x.contains(":price_hi:" + PROF_A)));
                seen.clear();
                Thread.sleep(1600);
                ac.handle(parse("{\"type\":3,\"id\":\"987654321098765434\",\"token\":\"" + ixTok + "\",\"member\":{\"user\":{\"id\":\"777777777777777777\"}},\"data\":{\"custom_id\":\"au:" + SELLER + ":0:end:all:n\"}}"));
                check("auctions command: a button redraws the picture", waitFor(seen, "files[0]"));
                seen.clear();
                ac.handle(parse("{\"type\":4,\"id\":\"987654321098765435\",\"token\":\"" + ixTok + "\",\"member\":{\"user\":{\"id\":\"777777777777777777\"}},\"data\":{\"name\":\"auctions\",\"options\":[{\"name\":\"username\",\"type\":3,\"value\":\"Clapnu\"},{\"name\":\"profile\",\"type\":3,\"value\":\"\",\"focused\":true}]}}"));
                check("auctions command: profile suggestions list the profiles with their listing counts", waitFor(seen, "Profile 1 (3 listed)") && seen.stream().anyMatch(x -> x.contains("\"type\":8") && x.contains(PROF_A)));
                seen.clear();
                // odd custom ids and unknown things are ignored
                check("auctions command: odd button ids are ignored",
                    ac.handle(parse("{\"type\":3,\"id\":\"987654321098765436\",\"token\":\"" + ixTok + "\",\"member\":{\"user\":{\"id\":\"777777777777777777\"}},\"data\":{\"custom_id\":\"au:../../x:0:end:all:n\"}}")) && seen.isEmpty()
                    && !ac.handle(parse(cmd.apply("evil", null))) && !ac.handle(parse("{\"type\":2,\"id\":\"../x\",\"token\":\"" + ixTok + "\",\"data\":{\"name\":\"auctions\"}}")));

                // somebody else's buttons: only the person who ran the command may use them
                seen.clear();
                ac.handle(parse("{\"type\":3,\"id\":\"987654321098765440\",\"token\":\"" + ixTok + "\",\"member\":{\"user\":{\"id\":\"888888888888888888\"}},\"message\":{\"interaction_metadata\":{\"user\":{\"id\":\"777777777777777777\"}}},\"data\":{\"custom_id\":\"au:" + SELLER + ":0:end:all:n\"}}"));
                check("auctions buttons: another person cannot use them", waitFor(seen, "Only the person who ran this") && seen.stream().noneMatch(x -> x.contains("files[0]")));
                String hostile = AuctionCommands.esc("[Free coins](https://evil.example/x) @everyone\n**bold** evil.com");
                check("auctions text: item names cannot make links, mentions or new lines",
                    !hostile.contains("](") && !hostile.contains("https://") && !hostile.contains("\n") && !hostile.contains("evil.com") && hostile.contains("Free coins"));
                check("auctions text: item names lose colour codes and control characters and are cut at 80",
                    AuctionIndex.clean("\u00a76Hyperion\n\u00a7l").equals("Hyperion") && AuctionIndex.clean("x".repeat(200)).length() == 80);

                // /notify: a DM is sent to check, and a sale sends another
                seen.clear();
                ac.handle(parse(cmd.apply("notify", null)));
                check("auctions command: /notify opens a DM, sends a test message and turns sale DMs on",
                    waitFor(seen, "Sale DMs are **on**") && seen.stream().anyMatch(x -> x.startsWith("POST /users/@me/channels") && x.contains("777777777777777777"))
                    && seen.stream().anyMatch(x -> x.startsWith("POST /channels/555555555555555555/messages") && x.contains("nt:off")) && ns.get("777777777777777777").dms());
                seen.clear();
                ix.addSale(new AuctionIndex.Sale("s9", SELLER, PROF_A, "22222222222222222222222222222222", "Hyperion", "LEGENDARY", 5, 3, 820_000_000L, nowMs));
                check("auctions DM: a sale sends the owner one DM with the item, price and buyer",
                    waitFor(seen, "820,000,000") && seen.stream().anyMatch(x -> x.startsWith("POST /channels/555555555555555555/messages") && x.contains("Hyperion") && x.contains("Matin007s") && x.contains("nt:off")));
                seen.clear();
                ix.addSale(new AuctionIndex.Sale("s9b", SELLER, PROF_A, "", "[Click me](https://evil.example)", "RARE", 0, 0, 1_000L, nowMs));
                check("auctions DM: a hostile item name is not a link in the DM", waitFor(seen, "Click me") && seen.stream().noneMatch(x -> x.contains("](") || x.contains("https://evil")));
                seen.clear();
                ix.addSale(new AuctionIndex.Sale("s10", "44444444444444444444444444444444", PROF_A, "", "Other", "RARE", 0, 0, 5L, nowMs));
                Thread.sleep(300);
                check("auctions DM: a stranger's sale sends nothing", seen.isEmpty());
                ac.handle(parse("{\"type\":3,\"id\":\"987654321098765437\",\"token\":\"" + ixTok + "\",\"user\":{\"id\":\"777777777777777777\"},\"data\":{\"custom_id\":\"nt:off\"}}"));
                Thread.sleep(300);
                check("auctions DM: the off button turns sale DMs off", !ns.get("777777777777777777").dms());
                seen.clear();
                ix.addSale(new AuctionIndex.Sale("s11", SELLER, PROF_A, "", "Hyperion", "LEGENDARY", 5, 0, 5L, nowMs));
                Thread.sleep(300);
                check("auctions DM: nothing is sent once they are off", seen.isEmpty());

                seen.clear();
                ac.handle(parse(cmd.apply("unlink", null)));
                check("auctions command: /unlink forgets the link", waitFor(seen, "Your linked Minecraft name and sale DMs are removed") && ns.get("777777777777777777") == null);
                ns.link("777777777777777777", SELLER, "Clapnu");
                // the saved links survive a restart, and a damaged file is survivable
                NotifyStore again = new NotifyStore(dir.resolve("aucfg/links.tsv"));
                try { check("auctions links: the file is private to the server's user", Files.getPosixFilePermissions(dir.resolve("aucfg/links.tsv")).toString().equals("[OWNER_READ, OWNER_WRITE]")); } catch (UnsupportedOperationException e) { check("auctions links: private file (not supported here)", true); }
                check("auctions links: saved to a file and read back", again.get("777777777777777777") != null && again.get("777777777777777777").name().equals("Clapnu"));
                Files.writeString(dir.resolve("aucfg/links2.tsv"), "garbage\n777777777777777777\tnotauuid\tx\t1\n\n");
                check("auctions links: a damaged file is skipped line by line", new NotifyStore(dir.resolve("aucfg/links2.tsv")).get("777777777777777777") == null);
                AuctionCommands.Page apg = ac.build(SELLER, "Clapnu", "all", 0, "end");
                check("auctions page: sold card first, 3 wands grouped into 1, 3 cards in all", apg != null && apg.pages() == 1 && apg.text().contains("×3") && apg.text().indexOf("Heroic Spirit Sceptre") < apg.text().indexOf("Builder"));
                check("auctions page: a profile filter keeps only that profile", ac.build(SELLER, "Clapnu", PROF_B.substring(0, 8), 0, "end").text().contains("Hyperion")
                    && !ac.build(SELLER, "Clapnu", PROF_B.substring(0, 8), 0, "end").text().contains("Builder"));
                check("auctions page: nothing to show gives null", ac.build("99999999999999999999999999999999", "Nobody", "all", 0, "end") == null);
                if (png.length > 0) Files.write(Path.of(System.getProperty("java.io.tmpdir"), "valtrin-auction-sample.png"), png);
            } finally {
                stub.stop(0);
            }
        }

        // ---- craft flips: archive reader, pricing and gating
        {
            ByteArrayOutputStream tb = new ByteArrayOutputStream();
            tarEntry(tb, "NEU-abc/pax_global_header", 'g', "52 comment=abcdef\n");
            tarEntry(tb, "NEU-abc/README.md", '0', "hello");
            tarEntry(tb, "NEU-abc/items/FOO.json", '0', "{\"recipes\":[{\"type\":\"crafting\",\"A1\":\"BAR:2\",\"B1\":\"BAZ:1\",\"count\":1}]}");
            tarEntry(tb, "NEU-abc/items/NOREC.json", '0', "{\"displayname\":\"x\"}");
            tarEntry(tb, "NEU-abc/items/sub/DEEP.json", '0', "{\"recipes\":[{\"type\":\"crafting\",\"A1\":\"BAR:1\",\"count\":1}]}");
            tarEntry(tb, "NEU-abc/items/BAD;1.json", '0', "{\"recipes\":[{\"type\":\"crafting\",\"A1\":\"BAR:1\",\"count\":1}]}");
            tb.write(new byte[1024]);
            Map<String, RecipeData.Recipe> rd = CraftFlips.readArchive(new java.io.ByteArrayInputStream(tb.toByteArray()));
            check("craft: archive reader keeps only item files with recipes", rd.size() == 1 && rd.containsKey("FOO")
                && rd.get("FOO").ingredients().get("BAR") == 2L && rd.get("FOO").ingredients().get("BAZ") == 1L);
            // real archives made by the system tar (checked once by hand; kept as a regression test when tar is present)
            try {
                Path td = dir.resolve("tartest/NEU-REPO-abc123/items");
                Files.createDirectories(td);
                Files.writeString(td.resolve("FOO.json"), "{\"recipes\":[{\"type\":\"crafting\",\"A1\":\"BAR:2\",\"count\":1}]}");
                Files.writeString(td.resolve("FORGED.json"), "{\"recipes\":[{\"type\":\"forge\",\"inputs\":[\"BAR:3\"],\"count\":2}]}");
                Files.writeString(td.resolve("A".repeat(120) + ".json"), "{\"recipes\":[{\"type\":\"crafting\",\"A1\":\"BAR:1\"}]}");
                for (String fmt : new String[]{"gnu", "pax", "ustar"}) {
                    Process tp = new ProcessBuilder("tar", "--format=" + fmt, "-cf", dir.resolve("t_" + fmt + ".tar").toString(), "-C", dir.resolve("tartest").toString(), "NEU-REPO-abc123")
                        .redirectErrorStream(true).start();
                    if (tp.waitFor() != 0) continue; // this tar does not know that format: skip
                    try (java.io.InputStream tin = Files.newInputStream(dir.resolve("t_" + fmt + ".tar"))) {
                        Map<String, RecipeData.Recipe> tr = CraftFlips.readArchive(tin);
                        check("craft: real " + fmt + " tar is read (long names skipped)", tr.size() == 2 && tr.get("FORGED").count() == 2);
                    }
                }
            } catch (java.io.IOException tarMissing) { /* no tar program here */ }
            byte[] cut = java.util.Arrays.copyOf(tb.toByteArray(), 700);
            boolean threw = false;
            try { CraftFlips.readArchive(new java.io.ByteArrayInputStream(cut)); } catch (java.io.IOException e) { threw = true; }
            check("craft: a cut-off archive is an error, not a partial result", threw);

            Pricing.bazaar = Map.of("BAR", 100.0, "BAZ", 50.0, "FOO", 1000.0, "LOSS", 10.0, "LOSSIN", 100.0);
            Pricing.bazaarWeek = Map.of("FOO", 700.0);
            Map<String, RecipeData.Recipe> rec = new java.util.LinkedHashMap<>();
            rec.put("FOO", new RecipeData.Recipe(new java.util.LinkedHashMap<>(Map.of("BAR", 2L, "BAZ", 1L)), 1));
            rec.put("LOSS", new RecipeData.Recipe(new java.util.LinkedHashMap<>(Map.of("LOSSIN", 1L)), 1));
            rec.put("NOPRICE", new RecipeData.Recipe(new java.util.LinkedHashMap<>(Map.of("UNKNOWN_THING", 1L)), 1));
            CraftFlips cf = new CraftFlips(dir.resolve("craft-test"));
            cf.useRecipes(rec, System.currentTimeMillis());
            String cj = cf.json(System.currentTimeMillis());
            check("craft: profitable craft is listed with cost, sell, profit and volume",
                cj.contains("\"id\":\"FOO\"") && cj.contains("\"cost\":250") && cj.contains("\"sell\":1000") && cj.contains("\"profit\":738") && cj.contains("\"volume\":100.0"));
            check("craft: losing crafts and crafts with an unpriced ingredient are left out", !cj.contains("\"id\":\"LOSS\"") && !cj.contains("NOPRICE"));
            api.setCrafts(cf);
            HttpResponse<String> cr = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/craft")).header("X-Key", prem).GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> cfree = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/craft")).header("X-Key", free).GET().build(), HttpResponse.BodyHandlers.ofString());
            HttpResponse<String> cnone = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/v1/craft")).GET().build(), HttpResponse.BodyHandlers.ofString());
            check("craft: premium key gets the crafts as JSON", cr.statusCode() == 200 && cr.body().contains("\"id\":\"FOO\"") && cr.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
            check("craft: free key is told it is premium only, with no data", cfree.statusCode() == 403 && cfree.body().contains("premium") && !cfree.body().contains("FOO"));
            check("craft: needs a key", cnone.statusCode() == 401);
            Pricing.bazaar = Map.of();
            Pricing.bazaarWeek = Map.of();
        }

        // one session per key: a second connection replaces the first
        BlockingQueue<String> qf2 = open(base + "/v1/stream", free, null);
        qf2.take();
        check("old stream ended when the key connects again", waitFor(qf, "END", 3000) != null);
        // capacity (3): free + prem + free2; free3 must be refused (free is already counted once)
        BlockingQueue<String> q3 = open(base + "/v1/stream", free2, null);
        q3.take();
        waitFor(q3, "data: {\"tier\"", 2000);
        check("sessions counted", hub.sessionCount() == 3);
        check("full server refuses a new key", "STATUS 503".equals(open(base + "/v1/stream", free3, null).take()));

        // revoked keys stop working: new connections are refused at once
        keys.revoke("free4");
        check("revoked key refused", status(base + "/ping", free4, "GET") == 401);


        // ---- invite codes and the redeem page
        InviteStore inv = new InviteStore(dir.resolve("config/invites.tsv"));
        HttpResponse<String> rpage = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/redeem")).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> homePage = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
        check("project page is public", homePage.statusCode() == 200 && homePage.body().contains("never buys") && homePage.body().contains("not affiliated with or endorsed by Hypixel"));
        check("project page has no script and a strict policy", !homePage.body().contains("<script") && homePage.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        for (String lpath : new String[]{"/terms", "/privacy"}) {
            HttpResponse<String> lp = HTTP.send(HttpRequest.newBuilder(URI.create(base + lpath)).GET().build(), HttpResponse.BodyHandlers.ofString());
            check(lpath + " page is public, has no script, strict policy", lp.statusCode() == 200 && !lp.body().contains("<script")
                && lp.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'"));
        }
        HttpResponse<String> termsPage = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/terms")).GET().build(), HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> privacyPage = HTTP.send(HttpRequest.newBuilder(URI.create(base + "/privacy")).GET().build(), HttpResponse.BodyHandlers.ofString());
        check("terms page says not affiliated and no promise of profit", termsPage.body().contains("not affiliated with, endorsed by") && termsPage.body().contains("promise of profit"));
        check("privacy page says what is kept and what is not", privacyPage.body().contains("Discord account number") && privacyPage.body().contains("Any Hypixel API key"));
        check("home page links to terms and privacy", homePage.body().contains("href=/terms") && homePage.body().contains("href=/privacy"));
        check("post to /terms is refused", post(base + "/terms", "application/x-www-form-urlencoded", "a=b").statusCode() >= 400);
        check("post to / is refused", post(base + "/", "application/x-www-form-urlencoded", "a=b").statusCode() == 401 || post(base + "/", "application/x-www-form-urlencoded", "a=b").statusCode() == 405);
        check("redeem page opens without a key", rpage.statusCode() == 200 && rpage.body().contains("<form"));
        check("redeem page has a strict content policy", rpage.headers().firstValue("Content-Security-Policy").orElse("").contains("default-src 'none'")
                && rpage.headers().firstValue("Cache-Control").orElse("").equals("no-store"));
        check("redeem needs the form type", post(base + "/redeem", "application/json", "{}").statusCode() == 400);
        check("redeem refuses a huge body", post(base + "/redeem", "application/x-www-form-urlencoded", "code=" + "A".repeat(5000)).statusCode() == 413);
        check("redeem refuses a wrong code", redeem(base, "INV-AAAA-AAAA-AAAA-AAAA", "someone").statusCode() == 403);
        check("redeem refuses odd codes", redeem(base, "' OR 1=1 --", "someone").statusCode() == 403 && redeem(base, "x".repeat(200), "someone").statusCode() == 403);
        java.util.List<String> codes = inv.add(3, 1, 7);
        check("codes not stored in plain text", !Files.readString(dir.resolve("config/invites.tsv")).contains(codes.get(0).substring(4, 8)));
        check("redeem refuses bad names", redeem(base, codes.get(0), "<script>alert(1)</script>").statusCode() == 400
                && redeem(base, codes.get(0), "a").statusCode() == 400 && redeem(base, codes.get(0), "../etc").statusCode() == 400
                && redeem(base, codes.get(0), "name\nwith newline").statusCode() == 400);
        HttpResponse<String> ok1 = redeem(base, codes.get(0).toLowerCase().replace("-", " "), "Alex_1");
        String k1 = keyIn(ok1.body());
        check("redeem makes a key (code typed sloppily)", ok1.statusCode() == 200 && k1 != null);
        check("key page: strict policy allows only its own script", ok1.headers().firstValue("Content-Security-Policy").orElse("").contains("script-src 'sha256-" + Pages.SCRIPT_HASH + "'")
                && ok1.body().contains("<script>" + Pages.COPY_SCRIPT + "</script>") && ok1.body().contains("not affiliated with or endorsed by Hypixel"));
        check("every page carries the not-affiliated line", rpage.body().contains("not affiliated with or endorsed by Hypixel"));
        check("the new key works and is FREE", k1 != null && status(base + "/ping", k1, "GET") == 200 && keys.lookup(k1).tier() == KeyStore.Tier.FREE);
        check("a used code stops working", redeem(base, codes.get(0), "Alex_2").statusCode() == 403);
        check("taken name is refused and does not use the code", redeem(base, codes.get(1), "alex_1").statusCode() == 409);
        check("... so the code still works with another name", redeem(base, codes.get(1), "Sam").statusCode() == 200);
        // expired code
        java.util.List<String> old = inv.add(1, 1, 1);
        java.nio.file.Path invFile = dir.resolve("config/invites.tsv");
        StringBuilder rewritten = new StringBuilder();
        for (String line : Files.readAllLines(invFile)) {
            String[] p = line.split("\t");
            if (p.length == 5 && !line.startsWith("#") && Long.parseLong(p[3]) > System.currentTimeMillis() + 3_600_000L && p[1].equals("1") && p[2].equals("0")
                    && InviteStoreAccess.matches(old.get(0), p[0])) p[3] = "1";
            rewritten.append(String.join("\t", p)).append('\n');
        }
        Files.writeString(invFile, rewritten.toString());
        check("an expired code is refused", redeem(base, old.get(0), "Late").statusCode() == 403);
        // a multi-use code is capped per address (4 a day here; 2 keys were already made from this address)
        String shared = inv.add(1, 10, 7).get(0);
        check("shared code: 3rd key ok", redeem(base, shared, "Kim").statusCode() == 200);
        check("shared code: 4th key ok", redeem(base, shared, "Lee").statusCode() == 200);
        check("per-address daily cap", redeem(base, shared, "Max").statusCode() == 429);
        check("invites list shows usage", inv.list().stream().anyMatch(i -> i.used() == 1));
        try { inv.add(0, 1, 1); check("zero codes refused", false); } catch (IllegalArgumentException e) { check("zero codes refused", true); }
        try { inv.revoke("zz"); check("odd revoke id refused", false); } catch (IllegalArgumentException e) { check("odd revoke id refused", true); }
        String id = inv.list().get(0).hash().substring(0, 8);
        check("revoke removes a code", inv.revoke(id) == 1);

        // mod side: ServerLink against this server (3 s free delay shortened by using the premium key)
        FlipConfig c = FlipConfig.INSTANCE;
        c.useServerAlerts = true;
        c.sharedUrl = base;
        c.sharedKey = prem;
        c.minProfit = 0; c.minMarginPercent = 0; c.minVolume = 0; c.minConfidence = "low";
        final java.util.List<FlipAlert> seen = new java.util.concurrent.CopyOnWriteArrayList<>();
        final java.util.List<String> notes = new java.util.concurrent.CopyOnWriteArrayList<>();
        AlertSink sink = new AlertSink() {
            public void notice(boolean good, String lead, String rest, long at) { notes.add(lead); }
            public void flip(FlipAlert f, long at, boolean real) { seen.add(f); }
            public int pending() { return 0; }
            public boolean onHypixel() { return true; }
        };
        // the earlier premium stream holds this key: the link replaces it, which is the intended behaviour
        ServerLink link = new ServerLink(sink);
        link.start();
        for (int i = 0; i < 50 && !link.connected(); i++) Thread.sleep(100);
        check("link connects", link.connected() && link.delayMs() == 0);
        hub.flip(alert("0123456789abcdef0123456789abcdef", "Hyperion"), System.currentTimeMillis(), true);
        for (int i = 0; i < 30 && seen.isEmpty(); i++) Thread.sleep(100);
        check("link delivers the flip", seen.size() == 1 && seen.get(0).name().equals("Hyperion"));
        hub.flip(alert("0123456789abcdef0123456789abcdef", "Hyperion"), System.currentTimeMillis(), true);
        Thread.sleep(600);
        check("link drops a repeated auction", seen.size() == 1);
        hub.notice(false, "Lost contact", "with Hypixel", System.currentTimeMillis());
        for (int i = 0; i < 20 && notes.isEmpty(); i++) Thread.sleep(100);
        check("link delivers notices", notes.contains("Lost contact"));
        // revoke the key the link uses: the stream is ended within one keepalive and the link ends up refused
        keys.revoke("prem1");
        for (int i = 0; i < 220 && link.state() != ServerLink.State.REFUSED; i++) Thread.sleep(100);
        check("link stops when key is revoked", link.state() == ServerLink.State.REFUSED && !link.connected());
        link.stop();

        // wrong address scheme is never used
        c.sharedUrl = "http://example.com";
        check("link wanted() refuses plain http to other hosts", !ServerLink.wanted(c));


        // ---- Sign in with Discord, against a pretend Discord
        long nowMs = System.currentTimeMillis();
        long oldId = (((nowMs - 100L * 86_400_000L) - 1_420_070_400_000L) << 22) | 123, youngId = (((nowMs - 86_400_000L) - 1_420_070_400_000L) << 22) | 77;
        long outsiderId = (((nowMs - 200L * 86_400_000L) - 1_420_070_400_000L) << 22) | 5;
        com.sun.net.httpserver.HttpServer fake = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        fake.createContext("/oauth2/token", x -> {
            String body = new String(x.getRequestBody().readAllBytes());
            String tok = body.contains("code=good") ? "tok-good" : body.contains("code=young") ? "tok-young" : body.contains("code=outsider") ? "tok-out" : null;
            byte[] out = (tok == null ? "{\"error\":\"invalid_grant\"}" : "{\"access_token\":\"" + tok + "\",\"token_type\":\"Bearer\"}").getBytes();
            x.sendResponseHeaders(tok == null ? 400 : 200, out.length); x.getResponseBody().write(out); x.close();
        });
        fake.createContext("/users/@me", x -> {
            String auth = x.getRequestHeaders().getFirst("Authorization");
            boolean guilds = x.getRequestURI().getPath().endsWith("/guilds");
            String json = auth == null ? null
                    : guilds ? (auth.equals("tok-out") || auth.equals("Bearer tok-out") ? "[{\"id\":\"999\"}]" : "[{\"id\":\"111\"},{\"id\":\"555\"}]")
                    : auth.equals("Bearer tok-good") ? "{\"id\":\"" + oldId + "\",\"username\":\"a\"}"
                    : auth.equals("Bearer tok-young") ? "{\"id\":\"" + youngId + "\"}"
                    : auth.equals("Bearer tok-out") ? "{\"id\":\"" + outsiderId + "\"}" : null;
            byte[] out = (json == null ? "{}" : json).getBytes();
            x.sendResponseHeaders(json == null ? 401 : 200, out.length); x.getResponseBody().write(out); x.close();
        });
        final String ghostId = "800000000000000001", premId = "810000000000000001", freeRole = "880000000000000001", premRole = "880000000000000002";
        final String botToken = "test-bot-token-0123456789abcdefghijkl";
        final java.util.List<String> roleCalls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        fake.createContext("/guilds/770000000000000007/members/", x -> {
            String path = x.getRequestURI().getPath();
            roleCalls.add(x.getRequestMethod() + " " + path + " auth=" + x.getRequestHeaders().getFirst("Authorization"));
            x.sendResponseHeaders(path.contains("/" + ghostId + "/") ? 404 : 204, -1);
            x.close();
        });
        fake.start();
        api.stop();
        Files.writeString(dir.resolve("config/discord-secret.txt"), "test-secret-0123456789abcdef\n");
        Files.writeString(dir.resolve("config/discord-bot-token.txt"), botToken + "\n");
        Files.writeString(dir.resolve("config/valtrin-server.properties"), "port=" + (port + 3) + "\nfreeDelaySeconds=1\nmaxFailsPerMinute=50\n"
                + "publicUrl=http://127.0.0.1:" + (port + 3) + "\ndiscordClientId=123456789012345678\ndiscordGuildId=555\ndiscordMinAccountDays=7\n"
                + "discordApiBase=http://127.0.0.1:" + fake.getAddress().getPort() + "\n"
                + "discordBotOnline=false\ndiscordRoleGuildId=770000000000000007\ndiscordFreeRoleId=" + freeRole + "\ndiscordPremiumRoleId=" + premRole + "\n");
        st = ServerSettings.load(dir.resolve("config"));
        hub = new Hub(st);
        api = new Api(st, keys, hub, new InviteStore(dir.resolve("config/invites.tsv")), dir.resolve("config"));
        api.start();
        base = "http://127.0.0.1:" + (port + 3);
        final String dbase = base;
        HttpClient nofollow = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
        check("redeem page shows the Discord button", HTTP.send(HttpRequest.newBuilder(URI.create(base + "/redeem")).GET().build(), HttpResponse.BodyHandlers.ofString()).body().contains("Continue with Discord"));
        for (int round = 0; round < 1; round++) {
            HttpResponse<String> l = nofollow.send(HttpRequest.newBuilder(URI.create(dbase + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String loc = l.headers().firstValue("Location").orElse(""), setc = l.headers().firstValue("Set-Cookie").orElse("");
            check("login redirects to Discord", l.statusCode() == 302 && loc.startsWith("https://discord.com/oauth2/authorize?") && loc.contains("client_id=123456789012345678")
                    && loc.contains("scope=identify+guilds") && !loc.contains("secret"));
            check("login sets a protected state cookie", setc.contains("vstate=") && setc.contains("HttpOnly") && setc.contains("SameSite=Lax"));
        }
        java.util.function.Function<String, String[]> startLogin = u -> {
            try {
                HttpResponse<String> l = nofollow.send(HttpRequest.newBuilder(URI.create(dbase + "/login")).GET().build(), HttpResponse.BodyHandlers.ofString());
                String st1 = java.util.regex.Pattern.compile("state=([A-Za-z0-9_-]+)").matcher(l.headers().firstValue("Location").get()).results().findFirst().get().group(1);
                return new String[]{st1};
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        java.util.function.BiFunction<String[], String, HttpResponse<String>> callback = (a2, extra) -> {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(dbase + "/auth/discord/callback?" + extra)).GET();
                if (a2[0] != null) b.header("Cookie", "vstate=" + a2[0]);
                return nofollow.send(b.build(), HttpResponse.BodyHandlers.ofString());
            } catch (Exception e) { throw new RuntimeException(e); }
        };
        String s1 = startLogin.apply("")[0];
        HttpResponse<String> good = callback.apply(new String[]{s1}, "code=good&state=" + s1);
        String dk1 = keyIn(good.body());
        check("Discord sign-in makes a key", good.statusCode() == 200 && dk1 != null);
        check("the Discord key works and is FREE", dk1 != null && status(base + "/ping", dk1, "GET") == 200 && keys.lookup(dk1).tier() == KeyStore.Tier.FREE);
        check("accounts file holds the id, not the key", Files.readString(dir.resolve("config/discord-accounts.tsv")).contains(String.valueOf(oldId))
                && !Files.readString(dir.resolve("config/discord-accounts.tsv")).contains(dk1));
        check("a state cannot be replayed", callback.apply(new String[]{s1}, "code=good&state=" + s1).statusCode() == 400);
        String s2 = startLogin.apply("")[0];
        check("state without the matching cookie is refused", callback.apply(new String[]{"other"}, "code=good&state=" + s2).statusCode() == 400);
        String s3 = startLogin.apply("")[0];
        check("no cookie is refused", callback.apply(new String[]{null}, "code=good&state=" + s3).statusCode() == 400);
        check("invented state is refused", callback.apply(new String[]{"abc"}, "code=good&state=abc").statusCode() == 400);
        String s4 = startLogin.apply("")[0];
        check("cancelled sign-in is handled", callback.apply(new String[]{s4}, "error=access_denied&state=" + s4).statusCode() == 400);
        String s5 = startLogin.apply("")[0];
        check("too-new account refused", callback.apply(new String[]{s5}, "code=young&state=" + s5).statusCode() == 403);
        String s6 = startLogin.apply("")[0];
        check("not in the Discord server: refused", callback.apply(new String[]{s6}, "code=outsider&state=" + s6).statusCode() == 403);
        String s7 = startLogin.apply("")[0];
        check("bad code from Discord is handled", callback.apply(new String[]{s7}, "code=bad&state=" + s7).statusCode() == 502);
        String s8 = startLogin.apply("")[0];
        check("a second sign-in within the hour is held", callback.apply(new String[]{s8}, "code=good&state=" + s8).statusCode() == 429 && status(base + "/ping", dk1, "GET") == 200);
        // after an hour: a new key replaces the old one
        java.nio.file.Path acc = dir.resolve("config/discord-accounts.tsv");
        Files.writeString(acc, oldId + "\t1\n");
        String s9 = startLogin.apply("")[0];
        HttpResponse<String> again = callback.apply(new String[]{s9}, "code=good&state=" + s9);
        String dk2 = keyIn(again.body());
        check("later sign-in gives a new key", again.statusCode() == 200 && dk2 != null && !dk2.equals(dk1));
        check("the old key stops working, the new one works", status(base + "/ping", dk1, "GET") == 401 && status(base + "/ping", dk2, "GET") == 200);
        check("callback without parameters is handled", callback.apply(new String[]{null}, "").statusCode() == 400);
        // ---- plan roles on Discord (REST only, no bot connection)
        String freePut = "PUT /guilds/770000000000000007/members/" + oldId + "/roles/" + freeRole + " auth=Bot " + botToken;
        for (int i = 0; i < 80 && !roleCalls.contains(freePut); i++) Thread.sleep(100);
        check("sign-in gives the Free Plan role (sent with the bot token)", roleCalls.contains(freePut));
        keys.add(KeyStore.Tier.PREMIUM, "discord-" + premId);
        keys.add(KeyStore.Tier.FREE, "discord-" + ghostId);
        api.roles().sweep();
        check("a premium key gets the Premium Plan role and loses the Free one", roleCalls.contains("PUT /guilds/770000000000000007/members/" + premId + "/roles/" + premRole + " auth=Bot " + botToken)
            && roleCalls.contains("DELETE /guilds/770000000000000007/members/" + premId + "/roles/" + freeRole + " auth=Bot " + botToken));
        check("someone who is not in the Discord server is skipped without trouble", roleCalls.stream().filter(s -> s.startsWith("PUT /guilds/770000000000000007/members/" + ghostId)).count() == 1);
        api.roles().sweep();
        check("a not-in-server account is not asked about again right away", roleCalls.stream().filter(s -> s.startsWith("PUT /guilds/770000000000000007/members/" + ghostId)).count() == 1);
        keys.revoke("discord-" + premId);
        api.roles().sweep();
        check("a cancelled key loses both plan roles", roleCalls.contains("DELETE /guilds/770000000000000007/members/" + premId + "/roles/" + premRole + " auth=Bot " + botToken)
            && roleCalls.stream().filter(s -> s.startsWith("DELETE /guilds/770000000000000007/members/" + premId + "/roles/" + freeRole)).count() >= 1);
        check("every role request carried the bot token and nothing else leaked", roleCalls.stream().allMatch(s -> s.endsWith("auth=Bot " + botToken)));
        // signing in again must not turn a premium key into a free one
        keys.revoke("discord-" + oldId);
        String premKeyOld = keys.add(KeyStore.Tier.PREMIUM, "discord-" + oldId);
        Files.writeString(acc, oldId + "\t1\n");
        String sp = startLogin.apply("")[0];
        HttpResponse<String> premAgain = callback.apply(new String[]{sp}, "code=good&state=" + sp);
        String premKeyNew = keyIn(premAgain.body());
        check("re-signing in keeps a premium plan", premAgain.statusCode() == 200 && premKeyNew != null && !premKeyNew.equals(premKeyOld)
            && keys.lookup(premKeyNew) != null && keys.lookup(premKeyNew).tier() == KeyStore.Tier.PREMIUM);
        check("the old premium key is replaced, not left behind", keys.lookup(premKeyOld) == null);
        check("replace gives a new account the default plan", keys.lookup(keys.replace("discord-820000000000000009", KeyStore.Tier.FREE)).tier() == KeyStore.Tier.FREE);
        // after a restart the server forgets what it applied: a free key must still lose a stale Premium role
        String staleId = "820000000000000011";
        keys.add(KeyStore.Tier.FREE, "discord-" + staleId);
        new DiscordRoles(st, keys, dir.resolve("config")).sweep();
        check("after a restart a free account loses a leftover Premium Plan role", roleCalls.contains("DELETE /guilds/770000000000000007/members/" + staleId + "/roles/" + premRole + " auth=Bot " + botToken));
        java.nio.file.Path noTokenDir = Files.createTempDirectory("valtrin-notoken");
        check("plan roles stay off without a bot token", !new DiscordRoles(st, keys, noTokenDir).enabled() && api.roles().enabled());
        fake.stop(0);
        api.stop();
        System.out.println("server tests: passed " + pass + ", failed " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
