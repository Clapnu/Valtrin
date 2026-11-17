package dev.valtrin;
import java.util.*;
import java.nio.file.*;
public class T5 {
    public static void main(String[] a) throws Exception {
        Path sales = Files.createTempDirectory("rsales5");
        long lu = (System.currentTimeMillis() - 12L * 3_600_000L) / 60_000L * 60_000L;
        OwnModel.init(sales, null);
        ItemData d0 = ItemData.fromExtra(Map.of("id","STATS_ITEM"), "S").withTier("EPIC");
        for (int i = 0; i < 8; i++) OwnModel.log.add("STATS_ITEM", lu - 3_600_000L * (i + 1), 1_400_000 + i * 10_000, "EPIC", d0.base(), new ArrayList<>());
        OwnModel.log.flush();
        OwnModel.init(sales, null);
        Market.volume = new VolumeTracker();
        Clock.freeze(lu + 67000);
        ItemData d = ModTokens.toItemData("STATS_ITEM", d0.base(), "EPIC", ModTokens.decode(""));
        List<Market.Row> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) rows.add(new Market.Row(1_500_000L + i * 1000, d));
        Market.publishSnapshot(rows, lu + 67000);
        OwnModel.preload(List.of("STATS_ITEM"), 5000);
        System.out.println("loaded=" + OwnModel.log.isLoaded("STATS_ITEM") + " peek=" + (OwnModel.log.peek("STATS_ITEM")==null?null:OwnModel.log.peek("STATS_ITEM").size()));
        System.out.println("stats0=" + OwnModel.stats(d, Clock.now()));
        Market.Estimate est = Market.estimate(d, 800_000);
        System.out.println(est);
        System.out.println(OwnModel.stats(d, Clock.now()));
        System.exit(0);
    }
}
