// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class FlipConfig {
    public static volatile FlipConfig INSTANCE = new FlipConfig();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("valtrin.json");

    // ---- Filters ----
    public boolean enabled = true;
    /** Minimum profit in coins, after estimated AH fees. */
    public long minProfit = 300_000;
    /** Minimum profit margin in percent (profit / buy price * 100). */
    public double minMarginPercent = 8.0;
    /** Minimum average sales per day (BIN sales observed by this mod). 0 = ignore volume. */
    public int minVolume = 10;
    /** Maximum buy price (your purse). 0 = no limit. */
    public long maxPrice = 0;
    /** Minimum number of OTHER matching BIN listings needed to trust the reference price. */
    public int minListings = 2;
    /** Ignore absurd margins (usually false flips). 0 = disabled. */
    public double maxMarginPercent = 0;
    /** Skip auctions older than this when first seen (guards against stale alerts). */
    /** How long after Valtrin FIRST SEES a listing it can still be alerted (also used for flips waiting under the alert limit). */
    public int maxAgeSeconds = 120;
    /** Listings that were already older than this when first seen are ignored (stale leftovers). Hypixel can show listings minutes late. */
    public int maxListedAgeSeconds = 900;
    /** Settings version, so a new release can tell you about changed defaults once. */
    public int configVersion = 0;
    /** Key "open next flip": holding it repeats (every bindRepeatMs); off = one flip per press. */
    public boolean bindHold = true;
    public int bindRepeatMs = 700;
    public boolean includePets = false;
    /** What profit is measured against: "lbin" (lowest / next lowest BIN), "median" (7-day median sold), "both" (the lower of the two). */
    public String referenceMode = "both";
    /** Median mode needs at least this many sold-price samples for the item. */
    public int minMedianSamples = 5;
    /** Leave odd sales (alt trades, 1-coin transfers) out of the median. */
    public boolean trimOutliers = true;
    /** Lowest confidence that still alerts: "low" (everything), "med" or "high". */
    public String minConfidence = "med";
    /** Write every auction snapshot (changes only) to ValtrinData/replay, so a market can be replayed later. Off by default. */
    public boolean record = false;
    /** Writes ValtrinData\\poll-log.tsv: when each page of Hypixel's auction list changes (diagnostic, no behavior change). */
    public boolean pageLog = true;
    /** Ask for page 0 with validators so unchanged answers are tiny (set false in the config file to turn off). */
    public boolean lightPolling = true;
    /** Percent of an item's modifier value (enchants, gems, slots, stars, scrolls...) counted in its price. */
    public int modValuePercent = 100;
    /** Value of an unlocked gem slot when the real unlock cost isn't known. */
    public long gemSlotFallback = 250_000;
    /** Item-name substrings (case-insensitive) that are never alerted. */
    public List<String> blacklist = new ArrayList<>();

    // ---- Behaviour ----
    /** Most alerts per 10 seconds; if there are more, only the most profitable are sent. 0 = no limit. */
    public int alertLimit = 10;
    public boolean soundEnabled = true;
    /** No alert sounds during these local hours (alerts still appear in chat). Handy for letting it run overnight. */
    public boolean quietHours = false;
    public int quietStartHour = 23;
    public int quietEndHour = 7;
    /** Big flips play the jackpot sound instead of the chime. */
    public boolean jackpotSound = true;
    /** Profit (coins) from which a flip counts as big for the jackpot sound. */
    public long jackpotMinProfit = 20_000_000L;
    public boolean hypixelOnly = true;
    /** Download crafting recipes (one small request per item you look at) from the public NEU item repository on GitHub. */
    public boolean recipeLookups = true;
    /** Closing a menu that was opened from chat (a seller's AH) brings chat back instead of the game view. */
    public boolean returnToChat = true;
    /** Green/red slot colors in the auction house. */
    public boolean highlightEnabled = true;
    /** Extra price lines (lowest BIN, median, volume) in item tooltips. */
    public boolean tooltipEnabled = true;
    /** Show a suggested price in the Create Auction menu. */
    public boolean suggestEnabled = true;
    /** Pre-type the suggested price into the price sign (you still press Done yourself). */
    public boolean signFill = true;
    /** Suggested price = lowest matching BIN minus this percent. */
    public double undercutPercent = 1.0;
    public int pollIntervalMs = 1000;
    /** Longest wait for a seller's name before an alert goes out. 0 = never wait: a seller not seen before gets no 'sellers ah' button (its name is looked up for next time). */
    public int sellerWaitMs = 0;
    /** How many auction pages are downloaded at the same time (10 = the older, gentler behaviour). */
    public int pageBatch = 50;
    /** Minutes of sales tracking required before volume numbers are trusted. */
    public int volumeWarmupMinutes = 30;
    /** If true, alert even when volume is still unknown (warming up). */
    public boolean allowUnknownVolume = false;

    /** The mod version whose welcome window was last shown ("" = never). */
    public String welcomeSeen = "";

    /** Address of a shared Valtrin data server (leave empty to use only data on this computer). */
    public String sharedUrl = "";
    public String sharedKey = "";
    /** Receive flip alerts from the Valtrin server (address and key above) instead of searching on this computer. */
    public boolean useServerAlerts = false;
    /** Send completed flips (time, item, coins paid and received) to your Valtrin server for the Flip Stats page. Off unless you turn it on. */
    public boolean reportStats = false;

    /** A file in the config folder; moves it from its pre-rename name if only that one exists. */
    static Path configFile(String name, String... legacy) {
        Path dir = FabricLoader.getInstance().getConfigDir();
        Path n = dir.resolve(name);
        try {
            if (!Files.exists(n)) {
                for (String l : legacy) {
                    Path o = dir.resolve(l);
                    if (Files.exists(o)) { Files.move(o, n); break; }
                }
            }
        } catch (Exception ignored) {}
        return n;
    }

    /** Raise this when defaults change so existing users get a one-time notice. */
    public static final int CURRENT_VERSION = 23;

    /** Chat lines telling an existing user which settings differ from the new recommended defaults (empty = nothing to say). */
    public static java.util.List<String> upgradeNotice(int from) {
        FlipConfig c = INSTANCE;
        FlipConfig d = new FlipConfig();
        java.util.List<String> out = new ArrayList<>();
        out.add("New look: purple and gold, one compact line per flip with confidence dots (red low, yellow medium, green high).");
        out.add("/flip profit <1-14> or all replaces /flip stats. /flip status is shorter (hover a line for details). /flip help lists the commands.");
        out.add("Auction tooltips now say whether an item is flagged as a flip and, if not, why. The lost-contact notice waits 70 s.");
        if (from < 22) {
            out.add("Valtrin 22: speed. New listings are judged the moment Hypixel's newest page arrives, and the connection recovers by itself. /flip why <item> explains why a good-looking listing did not alert.");
        }
        if (from < 21) {
            out.add("Valtrin 21: accuracy first. Flips are measured against the LOWER of the lowest comparable BIN and the sold-price median (needs 5+ exact sales). Fewer alerts, but the price behind them is proven. Change with /flip set mode, minsamples, minconfidence.");
        }
        if (from < 20) {
            java.util.List<String> cmds = new ArrayList<>();
            if (OwnModel.confRank(c.minConfidence.toUpperCase(java.util.Locale.ROOT)) < OwnModel.confRank(d.minConfidence.toUpperCase(java.util.Locale.ROOT)))
                cmds.add("/flip set minconfidence " + d.minConfidence);
            if (c.minMarginPercent < d.minMarginPercent) cmds.add("/flip set minmargin " + d.minMarginPercent);
            if (c.minProfit < d.minProfit) cmds.add("/flip set minprofit " + d.minProfit);
            if (!cmds.isEmpty()) {
                out.add("Valtrin 20: newer safer defaults exist. Your saved settings were kept. To use them run: " + String.join("  ", cmds));
            }
            out.add("Valtrin 20: 'maxage' now counts from when Valtrin FIRST SEES a listing. Open-next-flip key: Options > Controls > Valtrin (default Z), or /flip next.");
        }
        return out;
    }

    /**
     * Version 21: accuracy first. Profit must hold against BOTH the lowest comparable BIN and the proven sold price,
     * and the sold price needs at least 5 exact sales. Applied once to settings saved by older versions (the user can
     * still loosen them afterwards with /flip set).
     */
    private static void applyAccuracyDefaults(FlipConfig c) {
        c.referenceMode = "both";
        if (c.minMedianSamples < 5) c.minMedianSamples = 5;
        if (OwnModel.confRank(String.valueOf(c.minConfidence).toUpperCase(java.util.Locale.ROOT)) < OwnModel.confRank("MED")) c.minConfidence = "med";
    }

    public static void load() {
        try {
            Path src = FILE;
            if (!Files.exists(src)) { // settings from earlier builds
                for (String l : new String[]{"auxflipper.json", "flipscope.json", "skyflipper.json"}) {
                    if (Files.exists(FILE.resolveSibling(l))) { src = FILE.resolveSibling(l); break; }
                }
            }
            if (Files.exists(src)) {
                try (Reader r = Files.newBufferedReader(src)) {
                    FlipConfig c = GSON.fromJson(r, FlipConfig.class);
                    if (c != null) {
                        if (c.blacklist == null) c.blacklist = new ArrayList<>();
                        if (c.configVersion < 21) applyAccuracyDefaults(c);
                        c.clamp();
                        INSTANCE = c;
                    }
                }
            }
        } catch (Exception e) {
            Log.LOGGER.warn("Could not read config, using defaults", e);
        }
        save();
    }

    public static synchronized void save() {
        INSTANCE.clamp(); // every way of changing a setting (command, settings screen, file) ends up inside safe ranges
        // write a temporary file first and swap it in, so a crash can never leave a half-written config
        Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
        try (Writer w = Files.newBufferedWriter(tmp)) {
            GSON.toJson(INSTANCE, w);
        } catch (Exception e) {
            Log.LOGGER.warn("Could not save config", e);
            return;
        }
        try {
            Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception atomicFailed) {
            try { Files.move(tmp, FILE, java.nio.file.StandardCopyOption.REPLACE_EXISTING); } catch (Exception e) {
                Log.LOGGER.warn("Could not save config", e);
            }
        }
    }

    /** The filter presets shown in the settings screen: name, what it is for. */
    static final String[][] PRESETS = {
            {"Safe", "Fewer, surer flips: 1M profit, 10% margin, 20 sales/day, high confidence."},
            {"Balanced", "The recommended defaults: 300K profit, 8% margin, 10 sales/day, medium confidence."},
            {"Risky", "Lots of alerts, including smaller, slower and less certain flips: 100K profit, 5% margin, 5 sales/day, any confidence."},
    };

    /** Sets the filters of a preset (Safe, Balanced, Risky; "aggressive" still works as the old name for Risky). Returns false for an unknown name. */
    static boolean applyPreset(String name) {
        FlipConfig c = INSTANCE;
        switch (name == null ? "" : name.toLowerCase(java.util.Locale.ROOT)) {
            case "safe" -> { c.minProfit = 1_000_000; c.minMarginPercent = 10; c.minVolume = 20; c.minListings = 2; c.minConfidence = "high"; }
            case "balanced" -> { FlipConfig d = new FlipConfig(); c.minProfit = d.minProfit; c.minMarginPercent = d.minMarginPercent; c.minVolume = d.minVolume; c.minListings = d.minListings; c.minConfidence = "med"; }
            case "risky", "aggressive" -> { c.minProfit = 100_000; c.minMarginPercent = 5; c.minVolume = 5; c.minListings = 1; c.minConfidence = "low"; }
            default -> { return false; }
        }
        return true;
    }

    /** True when quiet hours are on and {@code hour} (0-23) falls in the quiet range; the range may cross midnight. */
    boolean isQuiet(int hour) {
        if (!quietHours || quietStartHour == quietEndHour) return false;
        return quietStartHour < quietEndHour
                ? hour >= quietStartHour && hour < quietEndHour
                : hour >= quietStartHour || hour < quietEndHour;
    }

    /** Which preset the current filters match ("Custom" if none). */
    static String currentPreset() {
        FlipConfig c = INSTANCE;
        if (c.minProfit == 1_000_000 && c.minMarginPercent == 10 && c.minVolume == 20 && c.minListings == 2 && "high".equals(c.minConfidence)) return "Safe";
        if (c.minProfit == 300_000 && c.minMarginPercent == 8 && c.minVolume == 10 && c.minListings == 2 && "med".equals(c.minConfidence)) return "Balanced";
        if (c.minProfit == 100_000 && c.minMarginPercent == 5 && c.minVolume == 5 && c.minListings == 1 && "low".equals(c.minConfidence)) return "Risky";
        return "Custom";
    }

    /** Keeps every setting inside a safe range, so a hand-edited or damaged config file can't hammer Hypixel or break the filters. */
    void clamp() {
        pollIntervalMs = Math.max(1000, Math.min(60_000, pollIntervalMs));
        pageBatch = Math.max(1, Math.min(100, pageBatch));
        sellerWaitMs = Math.max(0, Math.min(1000, sellerWaitMs));
        bindRepeatMs = Math.max(300, Math.min(10_000, bindRepeatMs));
        alertLimit = Math.max(0, Math.min(1000, alertLimit));
        quietStartHour = Math.max(0, Math.min(23, quietStartHour));
        quietEndHour = Math.max(0, Math.min(23, quietEndHour));
        maxAgeSeconds = Math.max(30, Math.min(100_000, maxAgeSeconds));
        maxListedAgeSeconds = Math.max(30, Math.min(100_000, maxListedAgeSeconds));
        modValuePercent = Math.max(0, Math.min(200, modValuePercent));
        minVolume = Math.max(0, minVolume);
        minListings = Math.max(0, minListings);
        minMedianSamples = Math.max(1, minMedianSamples);
        minProfit = Math.max(0, minProfit);
        maxPrice = Math.max(0, maxPrice);
        undercutPercent = Math.max(0, Math.min(50, undercutPercent));
        if (!"lbin".equals(referenceMode) && !"median".equals(referenceMode) && !"both".equals(referenceMode)) referenceMode = "both";
        if (!"low".equals(minConfidence) && !"med".equals(minConfidence) && !"high".equals(minConfidence)) minConfidence = "med";
        if (sharedUrl == null) sharedUrl = "";
        if (sharedKey == null) sharedKey = "";
        if (blacklist == null) blacklist = new ArrayList<>();
        if (welcomeSeen == null) welcomeSeen = "";
    }

    /** The mod version shown in the settings screen and the welcome window (keep equal to fabric.mod.json). */
    public static final String VERSION = "23.9.7";

    /** A copy of the current settings, so the settings screen can undo its changes with Cancel. */
    static FlipConfig snapshot() {
        return GSON.fromJson(GSON.toJson(INSTANCE), FlipConfig.class);
    }

    static void restore(FlipConfig c) {
        if (c != null) { if (c.blacklist == null) c.blacklist = new ArrayList<>(); INSTANCE = c; }
    }

    public static void reset() {
        INSTANCE = new FlipConfig();
        INSTANCE.configVersion = CURRENT_VERSION; // a reset is not an upgrade: no "updated" notice at the next start
        save();
    }
}
