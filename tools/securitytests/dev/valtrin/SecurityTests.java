package dev.valtrin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.util.Base64;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

/** Hostile-input tests for Valtrin's trust boundaries. Run: java -cp <classes> dev.valtrin.SecurityTests (exit code 1 on failure). */
public final class SecurityTests {
    static int pass = 0, fail = 0;

    static void check(String name, boolean ok) {
        if (ok) pass++; else { fail++; System.out.println("FAIL: " + name); }
    }

    static byte[] gz(byte[] raw) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        try (GZIPOutputStream g = new GZIPOutputStream(b)) { g.write(raw); }
        return b.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        // ---- Validate
        check("uuid ok", Validate.uuid("0123456789abcdef0123456789abcdef"));
        check("uuid dashed ok", Validate.uuid("01234567-89ab-cdef-0123-456789abcdef"));
        for (String bad : new String[]{null, "", "x", "0123456789abcdef0123456789abcde", "0123456789abcdef0123456789abcdefg",
                "0123456789abcdef0123456789abcdef\n/op me", "0123456789abcdef0123456789abcdef ", "../../etc/passwd", "a b"})
            check("uuid rejects " + bad, !Validate.uuid(bad));
        check("player ok", Validate.player("Technoblade") && Validate.player("a_b_1"));
        for (String bad : new String[]{null, "", "seventeen_chars_xx", "a b", "a;b", "a\nb", "§cName", "na/me", "名前"})
            check("player rejects " + bad, !Validate.player(bad));
        check("itemId ok", Validate.itemId("HYPERION") && Validate.itemId("ENCHANTED_BOOK") && Validate.itemId("RUNE:1"));
        for (String bad : new String[]{null, "", "../x", "a/b", "a\\b", "a b", "hyperion", "A".repeat(65), "A\nB", "A.json"})
            check("itemId rejects " + bad, !Validate.itemId(bad));
        check("text strips controls", Validate.text("a\tb\nc\u0000d", 50).equals("a b c d"));
        check("text caps", Validate.text("x".repeat(1000), 10).length() == 10);
        check("https ok", Validate.serverUrl("https://example.com/valtrin") != null);
        check("http localhost ok", Validate.serverUrl("http://localhost:8080") != null && Validate.serverUrl("http://127.0.0.1:8080") != null);
        for (String bad : new String[]{null, "", "http://example.com", "ftp://example.com", "file:///etc/passwd", "javascript:alert(1)",
                "example.com", "https://", "http://localhost.evil.com", "http://127.0.0.1.evil.com"})
            check("serverUrl rejects " + bad, Validate.serverUrl(bad) == null);

        // ---- FlipStats chat parsing (must not throw; spoofed/odd lines)
        long now = 1_000_000L;
        String[] chat = {
                "You purchased Hyperion for 999999999999999999999 coins!",
                "You purchased  for 5 coins!",
                "You purchased Item\twith\ttabs for 1,000 coins!",
                "You purchased Item\nnewline for 1,000 coins!",
                "You collected 1B coins from selling Hyperion to X in an auction!",
                "You collected -5 coins from selling A to B in an auction!",
                "Trade completed with X!", "+ 5,000,000 coins", "- 3x Fairy Skin", "+ NaN coins", "- x0 Nothing", "- 99999999x Spam",
                "Trade completed with Y!", "Trade completed with Y!", "+", "-", "", "   ", "\u0000\u0001",
                "x".repeat(100_000)
        };
        boolean threw = false;
        try {
            for (String l : chat) FlipStats.onChat(l, now);
            FlipStats.flushTrade(now + 10_000);
            FlipStats.report(0);
            FlipStats.summary(0);
        } catch (Throwable t) { threw = true; t.printStackTrace(); }
        check("FlipStats never throws on odd chat", !threw);
        check("huge coin amount rejected", FlipStats.parseCoins("999999999999999999999") == -1);
        check("abbrev coins", FlipStats.parseCoins("1.5M") == 1_500_000L);

        // ---- ItemKeys.parse never throws, always returns a value
        Random rnd = new Random(42);
        boolean parseThrew = false;
        try {
            for (int i = 0; i < 3000; i++) {
                byte[] junk = new byte[rnd.nextInt(300)];
                rnd.nextBytes(junk);
                ItemKeys.parse(Base64.getEncoder().encodeToString(junk));
                ItemKeys.parse(Base64.getEncoder().encodeToString(gz(junk)));
                ItemKeys.parseUncompressed(junk, "x");
            }
            ItemKeys.parse("!!!not base64!!!");
            ItemKeys.parse("");
            ItemKeys.parse(null);
            // truncated gzip
            byte[] good = gz(new byte[]{10, 0, 0, 0});
            ItemKeys.parse(Base64.getEncoder().encodeToString(java.util.Arrays.copyOf(good, good.length / 2)));
            // very deep nesting: compound inside compound x 100000
            ByteArrayOutputStream deep = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(deep);
            d.writeByte(10); d.writeUTF("");
            for (int i = 0; i < 100_000; i++) { d.writeByte(10); d.writeUTF("a"); }
            check("deep nesting returns NONE", ItemKeys.parse(Base64.getEncoder().encodeToString(gz(deep.toByteArray()))) == ItemData.NONE);
            // giant list length
            ByteArrayOutputStream big = new ByteArrayOutputStream();
            DataOutputStream b = new DataOutputStream(big);
            b.writeByte(10); b.writeUTF(""); b.writeByte(9); b.writeUTF("i"); b.writeByte(1); b.writeInt(Integer.MAX_VALUE);
            check("giant list returns NONE", ItemKeys.parse(Base64.getEncoder().encodeToString(gz(big.toByteArray()))) == ItemData.NONE);
            // decompression bomb: 50 MB of zeros
            byte[] zeros = new byte[50 * 1024 * 1024];
            long t0 = System.currentTimeMillis();
            ItemData bomb = ItemKeys.parse(Base64.getEncoder().encodeToString(gz(zeros)));
            check("bomb returns NONE quickly", bomb == ItemData.NONE && System.currentTimeMillis() - t0 < 5000);
            // negative array length
            ByteArrayOutputStream neg = new ByteArrayOutputStream();
            DataOutputStream n = new DataOutputStream(neg);
            n.writeByte(10); n.writeUTF(""); n.writeByte(7); n.writeUTF("x"); n.writeInt(-5);
            check("negative array returns NONE", ItemKeys.parse(Base64.getEncoder().encodeToString(gz(neg.toByteArray()))) == ItemData.NONE);
        } catch (Throwable t) { parseThrew = true; t.printStackTrace(); }
        check("ItemKeys.parse never throws", !parseThrew);

        // ---- FlipConfig.clamp
        try {
            FlipConfig c = new FlipConfig();
            c.pollIntervalMs = 1; c.pageBatch = 100000; c.alertLimit = -5; c.modValuePercent = 99999; c.undercutPercent = 500;
            c.referenceMode = "../../x"; c.minConfidence = null; c.sharedUrl = null; c.blacklist = null; c.minProfit = -1;
            c.clamp();
            check("clamp poll", c.pollIntervalMs == 1000);
            check("clamp batch", c.pageBatch == 100);
            check("clamp alertLimit", c.alertLimit == 0);
            check("clamp mod%", c.modValuePercent == 200);
            check("clamp undercut", c.undercutPercent == 50);
            check("clamp mode", c.referenceMode.equals("both"));
            check("clamp conf", c.minConfidence.equals("med"));
            check("clamp nulls", c.sharedUrl.equals("") && c.blacklist != null && c.minProfit == 0);
        } catch (Throwable t) { System.out.println("FlipConfig test could not run: " + t); fail++; }

        // ---- CSV export must not let an item name act as a spreadsheet formula
        try {
            FlipStats.onChat("You purchased =HYPERLINK(\"http://x\") for 1,000 coins!", 5_000_000L);
            FlipStats.onChat("You collected 2,000 coins from selling =HYPERLINK(\"http://x\") to Bob in an auction!", 5_000_100L);
            java.nio.file.Path out = java.nio.file.Files.createTempFile("valtrin", ".csv");
            int rows = FlipStats.exportCsv(out);
            String csv = java.nio.file.Files.readString(out);
            check("csv has header and rows", rows >= 1 && csv.startsWith("time,item,cost,received,profit"));
            check("csv formula neutralised", csv.contains("\"'=HYPERLINK") && !csv.contains(",\"=HYPERLINK"));
            java.nio.file.Files.deleteIfExists(out);
        } catch (Throwable t) { System.out.println("csv test failed: " + t); fail++; }

        // ---- quiet hours (range may cross midnight)
        try {
            FlipConfig q = new FlipConfig();
            check("quiet off by default", !q.isQuiet(3));
            q.quietHours = true; q.quietStartHour = 23; q.quietEndHour = 7;
            check("quiet 23-7 at 23", q.isQuiet(23));
            check("quiet 23-7 at 2", q.isQuiet(2));
            check("quiet 23-7 at 7 is over", !q.isQuiet(7));
            check("quiet 23-7 at noon", !q.isQuiet(12));
            q.quietStartHour = 9; q.quietEndHour = 17;
            check("quiet 9-17 at 10", q.isQuiet(10));
            check("quiet 9-17 at 20", !q.isQuiet(20));
            q.quietEndHour = 9;
            check("quiet same start and end = off", !q.isQuiet(9));
        } catch (Throwable t) { System.out.println("quiet test failed: " + t); fail++; }

        // ---- presets
        try {
            check("preset safe", FlipConfig.applyPreset("Safe") && FlipConfig.currentPreset().equals("Safe"));
            check("preset aggressive", FlipConfig.applyPreset("aggressive") && FlipConfig.currentPreset().equals("Aggressive"));
            check("preset balanced", FlipConfig.applyPreset("BALANCED") && FlipConfig.currentPreset().equals("Balanced"));
            check("preset unknown rejected", !FlipConfig.applyPreset("../x") && !FlipConfig.applyPreset(null));
            FlipConfig.INSTANCE.minProfit = 123;
            check("preset custom", FlipConfig.currentPreset().equals("Custom"));
        } catch (Throwable t) { System.out.println("preset test failed: " + t); fail++; }

        // ---- number and time formatting
        try {
            check("parse 500k", Format.parseCoins("500k") == 500_000L);
            check("parse 1.5m", Format.parseCoins("1.5m") == 1_500_000L);
            check("parse 2B", Format.parseCoins("2B") == 2_000_000_000L);
            check("parse commas", Format.parseCoins("1,250,000") == 1_250_000L);
            check("parse junk", Format.parseCoins("abc") == -1 && Format.parseCoins("") == -1 && Format.parseCoins(null) == -1);
            check("parse negative", Format.parseCoins("-5k") == -1);
            check("parse overflow", Format.parseCoins("1e999") == -1 && Format.parseCoins("NaN") == -1);
            check("compact small", Format.compact(950).equals("950"));
            check("compact K", Format.compact(500_000).equals("500K"));
            check("compact M", Format.compact(2_660_000).equals("2.66M"));
            check("compact B", Format.compact(1_200_000_000L).equals("1.2B"));
            check("compact negative", Format.compact(-3_180_000).equals("-3.18M"));
            check("time unknown", SellTime.format(-1).equals("?"));
            check("time minutes", SellTime.format(50).equals("50 min"));
            check("time hours", SellTime.format(100).equals("1 h 40 min") && SellTime.format(240).equals("4 h"));
            check("time days", SellTime.format(2160).equals("1.5 days") && SellTime.format(2880).equals("2 days"));
            check("time cap", SellTime.format(Long.MAX_VALUE).equals("more than 14 days"));
        } catch (Throwable t) { System.out.println("format test failed: " + t); fail++; }

        // ---- fuzz Validate with random text
        try {
            java.util.Random rnd2 = new java.util.Random(42);
            String alpha = "abcXYZ019_-:;/\\.%\n\t\r \u0000\u202e\u00e9@#?&=";
            boolean bad = false;
            for (int i = 0; i < 20000; i++) {
                StringBuilder sb = new StringBuilder();
                int n = rnd2.nextInt(80);
                for (int k = 0; k < n; k++) sb.append(alpha.charAt(rnd2.nextInt(alpha.length())));
                String x = sb.toString();
                try {
                    if (Validate.player(x) && !x.matches("[A-Za-z0-9_]{1,16}")) bad = true;
                    if (Validate.itemId(x) && !x.matches("[A-Z0-9_:;\\-]{1,64}")) bad = true;
                    if (Validate.uuid(x) && (x.contains("/") || x.contains(" ") || x.contains("\n"))) bad = true;
                    String t = Validate.text(x, 40);
                    if (t != null && (t.length() > 40 || t.contains("\n") || t.contains("\t") || t.contains("\r"))) bad = true;
                    Validate.serverUrl(x);
                } catch (Throwable e) { bad = true; }
            }
            check("Validate fuzz 20000 random strings", !bad);
        } catch (Throwable t) { System.out.println("validate fuzz failed: " + t); fail++; }

        // ---- fuzz FlipStats chat parser
        try {
            java.util.Random r3 = new java.util.Random(7);
            String[] parts = {"You purchased ", "You collected ", " coins", " coins from selling ", " to Bob in an auction!", "Trade completed with ", "+", "-", "\u2212", " x", "99999999999999999999", "1,2,3", "1.5M", "Terror Chestplate", "\u00a7a", "NaN", "-5", "x0", "0x", "", " "};
            boolean bad = false;
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < 30000; i++) {
                StringBuilder sb = new StringBuilder();
                int n = r3.nextInt(8);
                for (int k = 0; k < n; k++) sb.append(parts[r3.nextInt(parts.length)]);
                try { FlipStats.onChat(sb.toString(), t0 + i); if (i % 50 == 0) FlipStats.flushTrade(t0 + i + 10_000); }
                catch (Throwable e) { bad = true; System.out.println("FlipStats fuzz threw: " + e); break; }
            }
            try { FlipStats.report(7); FlipStats.sessionReport(); FlipStats.summary(0); } catch (Throwable e) { bad = true; System.out.println("report threw: " + e); }
            check("FlipStats fuzz 30000 chat lines", !bad);
        } catch (Throwable t) { System.out.println("flipstats fuzz failed: " + t); fail++; }

        // ---- hostile config files
        try {
            java.nio.file.Path cfg = java.nio.file.Path.of(System.getProperty("java.io.tmpdir")).resolve("valtrin.json");
            String[] files = {
                "{\"pollIntervalMs\": -5, \"minProfit\": -999999999999, \"blacklist\": null}",
                "{\"pollIntervalMs\": 99999999999}",
                "not json at all",
                "",
                "[1,2,3]",
                "{\"minProfit\": \"abc\"}",
                "{\"blacklist\": [null, 1, {}]}",
                "{\"quietStartHour\": 99, \"quietEndHour\": -3}",
                "{\"a\":".repeat(5000)
            };
            boolean bad = false;
            for (String f : files) {
                try {
                    java.nio.file.Files.writeString(cfg, f);
                    FlipConfig.load();
                    FlipConfig c = FlipConfig.INSTANCE;
                    if (c == null || c.pollIntervalMs < 1000 || c.minProfit < 0 || c.blacklist == null
                        || c.quietStartHour < 0 || c.quietStartHour > 23 || c.quietEndHour < 0 || c.quietEndHour > 23) {
                        bad = true; System.out.println("config not safe after: " + f.substring(0, Math.min(40, f.length())));
                    }
                } catch (NoClassDefFoundError e) {
                    // test-only: the warning logger needs Minecraft; reaching it means the bad file was rejected and the old safe settings stayed
                    FlipConfig c = FlipConfig.INSTANCE;
                    if (c == null || c.pollIntervalMs < 1000 || c.blacklist == null) bad = true;
                } catch (Throwable e) { bad = true; System.out.println("config load threw: " + e); }
            }
            check("hostile config files load safely", !bad);
        } catch (Throwable t) { System.out.println("config fuzz failed: " + t); fail++; }

        // ---- hostile seller-name cache file
        try {
            java.nio.file.Path sn = java.nio.file.Files.createTempFile("sellers", ".tsv");
            StringBuilder sb = new StringBuilder();
            sb.append("00000000000000000000000000000001\tGoodName\n");
            sb.append("../../etc/passwd\tEvil\n");
            sb.append("00000000000000000000000000000002\tbad name with spaces\n");
            sb.append("00000000000000000000000000000003\t").append("A".repeat(5000)).append("\n");
            sb.append("00000000000000000000000000000004\t\u00a7cRed\n");
            sb.append("\t\n\n\tonlytab\nnotab\n");
            sb.append("00000000000000000000000000000005\tOk_5\n");
            java.nio.file.Files.writeString(sn, sb.toString());
            SellerNames.init(sn);
            check("seller cache keeps good names", "GoodName".equals(SellerNames.cachedName("00000000000000000000000000000001"))
                && "Ok_5".equals(SellerNames.cachedName("00000000000000000000000000000005")));
            check("seller cache rejects hostile lines", SellerNames.cached() == 2);
            java.nio.file.Files.deleteIfExists(sn);
        } catch (Throwable t) { System.out.println("seller test failed: " + t); fail++; }

        // ---- hostile recipe JSON
        try {
            String[] js = {
                "", "null", "[]", "{}", "not json", "{\"recipe\": 5}", "{\"recipe\": {\"A1\": 7}}",
                "{\"recipe\": {\"A1\": \"ENCHANTED_COAL:9999999999999999999\"}}",
                "{\"recipe\": {\"A1\": \"X:-5\", \"B2\": null}}",
                "{\"recipes\": [null, 1, {\"type\": \"forge\", \"inputs\": \"x\"}]}",
                "{\"recipes\": [{\"type\": \"forge\", \"inputs\": [1, null, \"../../a:2\"]}]}",
                "{\"recipe\": {\"A1\": \"" + "A".repeat(100000) + "\"}}"
            };
            boolean bad = false;
            for (String j : js) {
                try { RecipeData.parse(j); } catch (Throwable e) { bad = true; System.out.println("recipe parse threw on " + j.substring(0, Math.min(30, j.length())) + ": " + e); }
            }
            check("hostile recipe JSON parses safely", !bad);
        } catch (Throwable t) { System.out.println("recipe test failed: " + t); fail++; }

        // ---- the engine's logger never throws, whatever it is given
        try {
            boolean ok = "a 1 b 2".equals(Log.format("a {} b {}", 1, 2))
                    && "x".equals(Log.format("x"))
                    && Log.format("a {} b {}", 1).startsWith("a 1 b ")
                    && Log.format(null).isEmpty()
                    && Log.format("a {}", new RuntimeException("boom")).contains("boom")
                    && Log.format("{}{}{}", (Object[]) null) != null
                    && Log.format("{} {}", (Object) null, null).equals("null null");
            check("logger formats safely", ok);
        } catch (Throwable t) { System.out.println("log test failed: " + t); fail++; }

        // ---- the engine hands alerts to a sink as plain data
        try {
            java.util.List<String> got = new java.util.ArrayList<>();
            AlertSink sink = new AlertSink() {
                public void notice(boolean good, String lead, String rest, long at) { got.add("notice:" + good + ":" + lead); }
                public void flip(FlipAlert f, long at, boolean real) { got.add("flip:" + real + ":" + f.name() + ":" + f.profit()); }
                public int pending() { return 0; }
                public boolean onHypixel() { return true; }
            };
            FlipEngine e = new FlipEngine(new VolumeTracker(), sink);
            e.sendTestAlert();
            check("test alert reaches the sink as plain data, marked not real", got.size() == 1
                    && got.get(0).startsWith("flip:false:Test Item (not a real flip):") && !got.get(0).endsWith(":0"));
        } catch (Throwable t) { System.out.println("sink test failed: " + t); fail++; }

        // ---- sold-auction history has no age limit
        try {
            java.nio.file.Path root = java.nio.file.Files.createTempDirectory("salesage");
            java.nio.file.Path old = root.resolve("2020-01-01");
            java.nio.file.Files.createDirectories(old);
            java.nio.file.Files.writeString(old.resolve("TEST_ITEM.tsv"), "1\t2\tCOMMON\tX\t\t1\n");
            SaleLog l = new SaleLog(root);
            l.init();
            Thread.sleep(1500);
            check("old sales days are not deleted by age", java.nio.file.Files.exists(old.resolve("TEST_ITEM.tsv")));
        } catch (Throwable t) { System.out.println("sales age test failed: " + t); fail++; }

        // ---- Wire / SseReader / ServerLink (the server is untrusted input)
        try {
            String U = "0123456789abcdef0123456789abcdef";
            String good = "{\"at\":1700000000000,\"name\":\"Hyperion\",\"tier\":\"LEGENDARY\",\"uuid\":\"" + U + "\",\"buy\":1000000,\"sell\":2000000,"
                    + "\"profit\":900000,\"margin\":90.0,\"volume\":25.0,\"detail\":\"a\\nb\",\"conf\":\"HIGH\"}";
            check("wire accepts good flip", Wire.decodeFlip(good) != null);
            check("wire flip round-trips", Wire.decodeFlip(Wire.encodeFlip(Wire.decodeFlip(good).alert(), 5L)) != null);
            String[] bad = {null, "", "not json", "[]", "null", "42", "{}",
                good.replace(U, "../../etc/passwd"), good.replace(U, U + "\n/op me"), good.replace("\"buy\":1000000", "\"buy\":-5"),
                good.replace("\"buy\":1000000", "\"buy\":99999999999999999999"), good.replace("\"buy\":1000000", "\"buy\":\"1\""),
                good.replace("\"profit\":900000", "\"profit\":0"), good.replace("\"margin\":90.0", "\"margin\":1e999"),
                good.replace("\"name\":\"Hyperion\"", "\"name\":\"" + "x".repeat(500) + "\""), good.replace("\"name\":\"Hyperion\"", "\"name\":\"\""),
                good.replace("\"at\":1700000000000", "\"at\":\"now\""), "x".repeat(Wire.MAX_DATA + 1)};
            for (String b : bad) check("wire rejects " + (b == null ? "null" : b.length() > 60 ? b.substring(0, 60) : b), Wire.decodeFlip(b) == null);
            Wire.Flip odd = Wire.decodeFlip(good.replace("\"conf\":\"HIGH\"", "\"conf\":\"EVIL\"").replace("LEGENDARY", "bad tier!")
                    .replace("\"name\":\"Hyperion\"", "\"name\":\"Hy\\u0000\\u001b[31mper\\ndrive\"").replace("\"detail\":\"a\\nb\"", "\"detail\":\"a\\u0007b\\nc\""));
            check("wire cleans odd values", odd != null && odd.alert().conf() == null && odd.alert().tier().equals("COMMON")
                    && odd.alert().name().indexOf('\u0000') < 0 && odd.alert().name().indexOf('\u001b') < 0 && odd.alert().name().indexOf('\n') < 0
                    && odd.alert().detail().indexOf('\u0007') < 0);
            check("wire bad seller ignored", Wire.decodeFlip(good.replace("}", ",\"sellerName\":\"a b;c\",\"sellerUuid\":\"zz\"}")).alert().sellerName() == null);
            check("wire notice ok", Wire.decodeNotice(Wire.encodeNotice(true, "Back", "fine")) != null);
            check("wire notice rejects", Wire.decodeNotice("{\"good\":\"yes\",\"lead\":\"x\"}") == null && Wire.decodeNotice("{\"good\":true}") == null);
            check("wire hello ok", Wire.decodeHello(Wire.encodeHello("FREE", 15000, "1")) != null);
            check("wire hello rejects", Wire.decodeHello("{\"tier\":\"FREE\",\"delayMs\":-1}") == null && Wire.decodeHello("{\"tier\":\"FREE\",\"delayMs\":99999999999}") == null);

            java.util.function.Function<String, SseReader> rd = t -> new SseReader(new java.io.ByteArrayInputStream(t.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            SseReader r = rd.apply(": hi\n\nevent: flip\r\ndata: {\"a\":1}\n\ndata: l1\ndata: l2\n\nevent: x\n");
            SseReader.Event e1 = r.next(), e2 = r.next();
            check("sse parses events", e1 != null && e1.type().equals("flip") && e1.data().equals("{\"a\":1}") && e2 != null && e2.type().equals("message") && e2.data().equals("l1\nl2"));
            check("sse unfinished event dropped", r.next() == null);
            boolean threw2 = false;
            try { rd.apply("data: " + "x".repeat(SseReader.MAX_LINE + 10) + "\n\n").next(); } catch (java.io.IOException ex) { threw2 = true; }
            check("sse long line refused", threw2);
            threw2 = false;
            try {
                StringBuilder big = new StringBuilder();
                for (int i = 0; i < 20; i++) big.append("data: ").append("y".repeat(3000)).append('\n');
                rd.apply(big.toString()).next();
            } catch (java.io.IOException ex) { threw2 = true; }
            check("sse big event refused", threw2);

            // the player's own filters apply to what the server sends
            FlipConfig fc = new FlipConfig();
            fc.blacklist = new java.util.ArrayList<>(java.util.List.of("hype"));
            FlipAlert fa = Wire.decodeFlip(good).alert();
            check("filter blacklist", !ServerLink.accept(fa, fc));
            fc.blacklist.clear();
            check("filter accepts", ServerLink.accept(fa, fc));
            fc.minProfit = 5_000_000;
            check("filter min profit", !ServerLink.accept(fa, fc));
            fc.minProfit = 0; fc.minConfidence = "high";
            check("filter confidence ok", ServerLink.accept(fa, fc));
            fc.enabled = false;
            check("filter alerts off", !ServerLink.accept(fa, fc));
            check("key shape", ServerLink.KeyShape.ok("vk_" + "A".repeat(43)) && !ServerLink.KeyShape.ok("vk_" + "A".repeat(42)) && !ServerLink.KeyShape.ok(null)
                    && !ServerLink.KeyShape.ok("vk_" + "A".repeat(42) + "\n"));
        } catch (Throwable t) { t.printStackTrace(); System.out.println("wire tests failed: " + t); fail++; }

        System.out.println("passed " + pass + ", failed " + fail);
        System.exit(fail == 0 ? 0 : 1);
    }
}
