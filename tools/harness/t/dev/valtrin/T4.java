package dev.valtrin;
import java.util.*;
import java.nio.file.*;
public class T4 {
    public static void main(String[] a) throws Exception {
        Path sales = Files.createTempDirectory("rsales4");
        long lu = 1_700_000_000_000L;
        OwnModel.init(sales, null);
        ItemData d0 = ItemData.fromExtra(Map.of("id","STATS_ITEM"), "S").withTier("EPIC");
        for (int i = 0; i < 8; i++) OwnModel.log.add("STATS_ITEM", lu - 3_600_000L * (i + 1), 1_400_000 + i * 10_000, "EPIC", d0.base(), new ArrayList<>());
        OwnModel.log.flush();
        OwnModel.init(sales, null);
        Clock.freeze(lu + 7000);
        ItemData d = ModTokens.toItemData("STATS_ITEM", d0.base(), "EPIC", ModTokens.decode(""));
        System.out.println("base=" + d.base() + " tier=" + d.tier() + " key=" + d.key());
        OwnModel.preload(List.of("STATS_ITEM"), 5000);
        System.out.println(OwnModel.stats(d, lu + 7000));
        System.exit(0);
    }
}
