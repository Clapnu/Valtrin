package dev.valtrin;
import java.util.*;
import java.nio.file.*;
public class T {
    static int fails = 0;
    static void check(String name, boolean ok) { System.out.println((ok ? "PASS " : "FAIL ") + name); if (!ok) fails++; }
    static Map<String,Object> m(Object... kv){ Map<String,Object> r=new HashMap<>(); for(int i=0;i<kv.length;i+=2) r.put((String)kv[i],kv[i+1]); return r; }
    public static void main(String[] a) throws Exception {
        // trim
        long[] s1 = {1, 900, 1000, 1000, 1100, 1200, 9000};
        long[] t1 = OwnModel.trim(s1);
        check("trim drops 1 and 9000", t1.length == 5 && t1[0] == 900 && t1[4] == 1200);
        check("trim keeps small sets", OwnModel.trim(new long[]{1,1000,5000}).length == 3);
        check("confidence HIGH", OwnModel.confidence(20, 0, 0.1).equals("HIGH"));
        check("confidence LOW few", OwnModel.confidence(3, 0, 0.1).equals("LOW"));
        check("confidence LOW relaxed", OwnModel.confidence(30, 2, 0.1).equals("LOW"));
        check("confidence MED", OwnModel.confidence(8, 1, 0.3).equals("MED"));

        // item parsing
        ItemData dung = ItemData.fromExtra(m("id","HYPERION","upgrade_level",5,"baseStatBoostPercentage",50,"modifier","fabled"), "Hyperion");
        check("stat boost + fabled tokens", ModTokens.encode(ModTokens.of(dung)).contains("x:STAT_BOOST=50") && ModTokens.encode(ModTokens.of(dung)).contains("x:REFORGE_FABLED=1"));
        ItemData dung2 = ItemData.fromExtra(m("id","HYPERION","upgrade_level",5,"baseStatBoostPercentage",43,"modifier","jaded"), "Hyperion");
        check("quality 43 -> 40, jaded ignored", ModTokens.encode(ModTokens.of(dung2)).equals("x:STAT_BOOST=40"));
        ItemData midas = ItemData.fromExtra(m("id","MIDAS_STAFF","winning_bid",50_000_000,"additional_coins",30_000_000L), "Midas Staff");
        check("midas 80M", ModTokens.encode(ModTokens.of(midas)).equals("x:MIDAS_COINS=80"));
        check("midas priced 1M per unit", Pricing.modifierValue(midas).extras() == 80_000_000L);
        check("stat boost not priced", Pricing.modifierValue(dung).extras() == 0);
        ItemData attr = ItemData.fromExtra(m("id","CRIMSON_CHESTPLATE","attributes",m("mana_pool",3,"vitality",2)), "Crimson");
        check("attributes parsed", attr != ItemData.NONE && attr.hasAttributes() && ModTokens.encode(ModTokens.of(attr)).contains("x:ATTR_MANA_POOL=3"));
        ItemData rod = ItemData.fromExtra(m("id","ROD_OF_LEGENDS","hook",m("part","salty_hook"),"line",m("part","titan_line"),"sinker","ICE_SINKER"), "Rod");
        check("rod parts", ModTokens.encode(ModTokens.of(rod)).contains("x:SALTY_HOOK=1") && ModTokens.encode(ModTokens.of(rod)).contains("x:ICE_SINKER=1"));
        check("rod parts always important", ModTokens.value(new ModTokens.Tok("x:SALTY_HOOK","1"), "ROD_OF_LEGENDS") >= ModTokens.IMPORTANT_MIN);
        ItemData rune = ItemData.fromExtra(m("id","ARMOR","runes",m("ZAP",3)), "A");
        check("rune compared", ModTokens.encode(ModTokens.of(rune)).equals("x:RUNE_ZAP=3"));
        ItemData drill = ItemData.fromExtra(m("id","DIVAN_DRILL","drill_part_engine","amber_polished_drill_engine"), "D");
        check("drill part kept important", ModTokens.value(new ModTokens.Tok("x:AMBER_POLISHED_DRILL_ENGINE","1"), "DIVAN_DRILL") >= ModTokens.IMPORTANT_MIN);
        ItemData cake = ItemData.fromExtra(m("id","NEW_YEAR_CAKE","new_years_cake",2022), "Cake");
        check("cake year in base", cake.base().contains("|Y2022"));
        check("keys seen", ItemData.SEEN_KEYS.containsKey("baseStatBoostPercentage") && ItemData.KNOWN_KEYS.contains("baseStatBoostPercentage"));
        // round trip tokens -> item
        ItemData rt = ModTokens.toItemData(dung.id(), dung.base(), "MYTHIC", ModTokens.of(dung));
        check("token round trip", ModTokens.encode(ModTokens.of(rt)).equals(ModTokens.encode(ModTokens.of(dung))));
        ItemData ench = ItemData.fromExtra(m("id","X","enchantments",m("sharpness",7,"ultimate_wise",5),"gems",m("unlocked_slots",List.of("COMBAT_0"),"COMBAT_0",m("quality","PERFECT"),"COMBAT_0_gem","JASPER")), "X");
        ItemData rt2 = ModTokens.toItemData("X", ench.base(), "EPIC", ModTokens.of(ench));
        check("round trip enchants+gems+slots", ModTokens.encode(ModTokens.of(rt2)).equals(ModTokens.encode(ModTokens.of(ench))));

        // seller names persistence
        Path tmp = Files.createTempFile("names", ".tsv");
        Files.writeString(tmp, "abc\tSteve\nxyz\tAlex\n");
        SellerNames.init(tmp);
        check("names loaded", SellerNames.cached() == 2 && "Steve".equals(SellerNames.lookupAll(List.of("abc"), 10).get("abc")));

        // sales log: failed read is not "no sales"; pinned items survive eviction
        Path root = Files.createTempDirectory("sales");
        OwnModel.init(root, null);
        long now = System.currentTimeMillis();
        OwnModel.log.add("ITEM_A", now - 3_600_000L, 1_000_000, "EPIC", "ITEM_A|0|N", new ArrayList<>());
        OwnModel.log.flush();
        OwnModel.pin(List.of("ITEM_A"));
        for (int i = 0; i < 20; i++) { OwnModel.log.peek("ITEM_A"); Thread.sleep(20); }
        check("sales loaded", OwnModel.log.isLoaded("ITEM_A"));
        for (int i = 0; i < 700; i++) { String id = "FILL_" + i; OwnModel.log.peek(id); }
        Thread.sleep(1500);
        check("pinned item survives eviction", OwnModel.log.isLoaded("ITEM_A"));

        // stats: single sale -> no estimate; 5 sales with an outlier -> trimmed
        ItemData plain = ItemData.fromExtra(m("id","STATS_ITEM"), "S").withTier("EPIC");
        for (long p : new long[]{1_000_000, 1_020_000, 990_000, 1_010_000, 1_005_000, 5, 20_000_000}) {
            OwnModel.recordSale(plain, now - 3_600_000L, p);
        }
        OwnModel.log.flush();
        OwnModel.clearCaches();
        OwnModel.Stats st = null;
        for (int i = 0; i < 40 && st == null; i++) { st = OwnModel.stats(plain, now); if (st == null) Thread.sleep(50); }
        check("stats computed", st != null);
        if (st != null) {
            System.out.println("   stats: median=" + st.median() + " n=" + st.samples() + " trimmed=" + st.trimmed() + " conf=" + st.conf());
            check("outliers trimmed (5 and 20M)", st.trimmed() == 2 && st.median() > 990_000 && st.median() < 1_030_000);
        }
        ItemData lone = ItemData.fromExtra(m("id","LONE_ITEM","enchantments",m("sharpness",7)), "S").withTier("EPIC");
        OwnModel.recordSale(lone, now - 3_600_000L, 7_000_000);
        OwnModel.log.flush(); OwnModel.clearCaches();
        OwnModel.Stats st2 = null;
        for (int i = 0; i < 20; i++) { st2 = OwnModel.stats(lone, now); Thread.sleep(50); }
        check("single sale gives no estimate", st2 == null);

        // recorder -> replay end to end
        Path home = Files.createTempDirectory("home");
        System.setProperty("user.home", home.toString());
        FlipConfig.INSTANCE.record = true;
        long lu = 1_700_000_000_000L;
        List<Recorder.Rec> r1 = new ArrayList<>();
        ItemData rp = plain;
        for (int i = 0; i < 5; i++) r1.add(new Recorder.Rec("u" + i, lu - 1000, 1_500_000L + i * 1000, rp, "s"));
        Recorder.record(lu, lu + 7000, r1);
        List<Recorder.Rec> r2 = new ArrayList<>(r1);
        r2.add(new Recorder.Rec("cheap", lu + 59_000, 800_000L, rp, "s"));
        Recorder.record(lu + 60_000, lu + 67_000, r2);
        Thread.sleep(800);
        long files = Files.list(home.resolve("ValtrinData").resolve("replay")).filter(p -> p.toString().endsWith(".tsv.gz")).count();
        check("recorder wrote 2 files", files == 2);
        // replay needs sales BEFORE the replayed time; make them for lu
        Path salesDir = Files.createTempDirectory("rsales");
        System.out.println("   (replay run:)");
        FlipConfig.INSTANCE.record = false;
        System.out.println("fails=" + fails);
        System.exit(fails == 0 ? 0 : 1);
    }
}
