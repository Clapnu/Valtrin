// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Valtrin server, first version: runs the same engine as the mod without Minecraft. It polls Hypixel once, records sold
 * auctions and (optionally) every auction snapshot, finds flips with the mod's own pricing code, and for now prints
 * each flip as one "FLIP {json}" line. Every minute it prints a "STATS" line with memory and CPU use so the size of the
 * server can be judged. Nothing is ever bought or sent to Hypixel: it only reads public data.
 *
 * Start:  java -Xmx1200m -Duser.home=/home/valtrin/valtrin-data -jar valtrin-server.jar
 * (user.home decides where the data folder "ValtrinData" and the settings go.)
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        Files.createDirectories(home);
        Files.createDirectories(home.resolve("config"));
        KeyStore keys = new KeyStore(home.resolve("config").resolve("keys.tsv"));
        InviteStore invites = new InviteStore(home.resolve("config").resolve("invites.tsv"));
        if (args.length > 0) {
            System.exit(adminCommand(keys, invites, args));
            return;
        }
        System.setProperty("sun.net.httpserver.maxReqHeaders", "40");
        System.setProperty("sun.net.httpserver.maxReqTime", "30");
        System.setProperty("sun.net.httpserver.maxRspTime", "0");
        Log.LOGGER.info("Valtrin server {} starting; data folder {}", FlipConfig.VERSION, home.resolve("ValtrinData"));

        FlipConfig.load();
        FlipConfig.INSTANCE.record = true;     // keep the market's changes for later replay (30 days)
        FlipConfig.INSTANCE.pageLog = false;   // diagnostic logs are for the mod on a PC

        ServerSettings settings = ServerSettings.load(home.resolve("config"));
        Hub sink = new Hub(settings);
        VolumeTracker volume = new VolumeTracker();
        volume.load();
        FlipEngine engine = new FlipEngine(volume, sink);
        engine.start();
        Api api = new Api(settings, keys, sink, invites, home.resolve("config"));
        CraftFlips crafts = new CraftFlips(home.resolve("ValtrinData"));
        api.setCrafts(crafts);
        api.setStats(new StatsStore(home.resolve("ValtrinData")));
        crafts.start();
        api.start();
        Log.LOGGER.info("Keys: {} | free delay {} s | premium {}", keys.count(), settings.freeDelayMs / 1000,
                settings.premiumEnabled ? "ENABLED" : "off (premium keys get the free delay)");

        ScheduledExecutorService stats = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "valtrin-stats");
            t.setDaemon(true);
            return t;
        });
        long startedAt = System.currentTimeMillis();
        stats.scheduleAtFixedRate(() -> {
            try {
                Runtime rt = Runtime.getRuntime();
                long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20, maxMb = rt.maxMemory() >> 20;
                String cpu = "?";
                try {
                    var os = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
                    cpu = String.format(java.util.Locale.ROOT, "%.0f%%", os.getProcessCpuLoad() * 100.0);
                } catch (Throwable ignored) {}
                List<String> speed = engine.speedLines();
                Log.LOGGER.info("STATS up {} min | heap {} of {} MB | cpu {} | flips {} | notices {} | streams {} | sent {} | dropped {} | {}",
                        (System.currentTimeMillis() - startedAt) / 60_000L, usedMb, maxMb, cpu, sink.flips.get(),
                        sink.notices.get(), sink.sessionCount(), sink.sent.get(), sink.dropped.get(), speed.isEmpty() ? "" : speed.get(0));
            } catch (Throwable t) {
                Log.LOGGER.warn("Stats line failed: {}", t.toString());
            }
        }, 60, 60, TimeUnit.SECONDS);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            Log.LOGGER.info("Valtrin server stopping");
            try { api.stop(); crafts.stop(); engine.stop(); } catch (Throwable ignored) {}
        }, "valtrin-shutdown"));

        Thread.currentThread().join();
    }

    /** "keys add free|premium LABEL", "keys list", "keys revoke LABEL". Returns the exit code. */
    private static int adminCommand(KeyStore keys, InviteStore invites, String[] a) {
        try {
            if (a.length >= 2 && a[0].equals("invites")) {
                switch (a[1]) {
                    case "add" -> {
                        // invites add <number of codes> [uses per code] [days valid]
                        int n = a.length > 2 ? Integer.parseInt(a[2]) : 1;
                        int uses = a.length > 3 ? Integer.parseInt(a[3]) : 1;
                        int days = a.length > 4 ? Integer.parseInt(a[4]) : 30;
                        System.out.println(n + " code(s), " + uses + " use(s) each, valid " + days + " days (shown once):");
                        for (String c : invites.add(n, uses, days)) System.out.println(c);
                        return 0;
                    }
                    case "list" -> {
                        for (InviteStore.Invite i : invites.list()) {
                            System.out.println(i.hash().substring(0, 8) + "\t" + i.used() + "/" + i.maxUses() + " used\texpires "
                                    + java.time.Instant.ofEpochMilli(i.expiresAt()) + (i.usable(System.currentTimeMillis()) ? "" : "\t(not usable)"));
                        }
                        return 0;
                    }
                    case "revoke" -> {
                        if (a.length != 3) throw new IllegalArgumentException("invites revoke <id from invites list>");
                        int n = invites.revoke(a[2]);
                        System.out.println(n + " code(s) removed");
                        return n > 0 ? 0 : 1;
                    }
                    default -> { /* falls to usage */ }
                }
            }
            if (a.length >= 4 && a[0].equals("keys") && a[1].equals("add")) {
                KeyStore.Tier t = a[2].equalsIgnoreCase("premium") ? KeyStore.Tier.PREMIUM
                        : a[2].equalsIgnoreCase("free") ? KeyStore.Tier.FREE : null;
                if (t == null) throw new IllegalArgumentException("tier must be free or premium");
                String key = keys.add(t, String.join(" ", java.util.Arrays.copyOfRange(a, 3, a.length)));
                System.out.println("New " + t + " key (shown once, give it to the user, it cannot be shown again):");
                System.out.println(key);
                return 0;
            }
            if (a.length == 2 && a[0].equals("keys") && a[1].equals("list")) {
                for (KeyStore.Entry e : keys.list()) {
                    System.out.println(e.tier() + "\t" + e.label() + "\tcreated " + java.time.Instant.ofEpochMilli(e.createdAt()));
                }
                System.out.println(keys.count() + " key(s)");
                return 0;
            }
            if (a.length >= 3 && a[0].equals("keys") && a[1].equals("revoke")) {
                int n = keys.revoke(String.join(" ", java.util.Arrays.copyOfRange(a, 2, a.length)));
                System.out.println(n + " key(s) revoked" + (n > 0 ? " (takes effect within a few seconds)" : ""));
                return n > 0 ? 0 : 1;
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
        System.err.println("Usage: valtrin-server.jar keys add <free|premium> <label> | keys list | keys revoke <label> | invites add [codes] [uses each] [days] | invites list | invites revoke <id>");
        return 2;
    }
}
