package dev.valtrin;

import java.io.BufferedReader;
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
                + "discordRoleGuildId=770000000000000007\ndiscordFreeRoleId=" + freeRole + "\ndiscordPremiumRoleId=" + premRole + "\n");
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
        java.nio.file.Path noTokenDir = Files.createTempDirectory("valtrin-notoken");
        check("plan roles stay off without a bot token", !new DiscordRoles(st, keys, noTokenDir).enabled() && api.roles().enabled());
        fake.stop(0);
        api.stop();
        System.out.println("server tests: passed " + pass + ", failed " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
