package dev.valtrin;
import java.util.*;
import java.nio.file.*;
public class T3 {
    public static void main(String[] a) throws Exception {
        Path sales = Files.createTempDirectory("rsales3");
        long lu = 1_700_000_000_000L;
        OwnModel.init(sales, null);
        ItemData d = ItemData.fromExtra(Map.of("id","STATS_ITEM"), "S").withTier("EPIC");
        for (int i = 0; i < 8; i++) OwnModel.log.add("STATS_ITEM", lu - 3_600_000L * (i + 1), 1_400_000 + i * 10_000, "EPIC", d.base(), new ArrayList<>());
        OwnModel.log.flush();
        OwnModel.init(sales, null);
        Clock.freeze(lu + 7000);
        OwnModel.preload(List.of("STATS_ITEM"), 5000);
        System.out.println("loaded=" + OwnModel.log.isLoaded("STATS_ITEM") + " peek=" + (OwnModel.log.peek("STATS_ITEM") == null ? null : OwnModel.log.peek("STATS_ITEM").size()));
        System.out.println(OwnModel.stats(d, lu + 7000));
        Files.walk(sales).forEach(p -> System.out.println(p));
        System.exit(0);
    }
}
