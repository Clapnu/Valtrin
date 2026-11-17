package dev.valtrin;
import java.util.*;
import java.nio.file.*;
public class T2 {
    public static void main(String[] a) throws Exception {
        Path home = Files.createTempDirectory("home2");
        System.setProperty("user.home", home.toString());
        Path sales = Files.createTempDirectory("rsales2");
        long lu = (System.currentTimeMillis() - 12L * 3_600_000L) / 60_000L * 60_000L;
        OwnModel.init(sales, null);
        ItemData d = ItemData.fromExtra(Map.of("id","STATS_ITEM"), "S").withTier("EPIC");
        for (int i = 0; i < 8; i++) OwnModel.log.add("STATS_ITEM", lu - 3_600_000L * (i + 1), 1_400_000 + i * 10_000, "EPIC", d.base(), new ArrayList<>());
        OwnModel.log.flush();
        FlipConfig.INSTANCE.record = true;
        List<Recorder.Rec> r1 = new ArrayList<>();
        for (int i = 0; i < 5; i++) r1.add(new Recorder.Rec("u" + i, lu - 1000, 1_500_000L + i * 1000, d, "s"));
        Recorder.record(lu, lu + 7000, r1);
        List<Recorder.Rec> r2 = new ArrayList<>(r1);
        r2.add(new Recorder.Rec("cheap", lu + 59_000, 800_000L, d, "s"));
        Recorder.record(lu + 60_000, lu + 67_000, r2);
        List<Recorder.Rec> r3 = new ArrayList<>(r2); r3.removeIf(x -> x.uuid().equals("cheap"));
        Recorder.record(lu + 120_000, lu + 127_000, r3);
        Thread.sleep(800);
        FlipConfig.INSTANCE.record = false;
        FlipConfig.INSTANCE.allowUnknownVolume = true;
        Replay.main(new String[]{home.resolve("ValtrinData/replay").toString(), sales.toString(), home.resolve("out.csv").toString()});
        System.out.println(Files.readString(home.resolve("out.csv")));
        System.exit(0);
    }
}
