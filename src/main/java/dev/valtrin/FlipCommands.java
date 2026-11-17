// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;
import java.util.function.LongConsumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

final class FlipCommands {
    private FlipCommands() {}

    static void register() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
                dispatcher.register(build()));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> build() {
        return literal("flip")
                .executes(c -> status(c.getSource()))
                .then(literal("status").executes(c -> status(c.getSource())))
                .then(literal("snapshots").executes(c -> snapshots(c.getSource())))
                .then(literal("why")
                        .executes(c -> why(c.getSource(), ""))
                        .then(argument("item", StringArgumentType.greedyString()).executes(c -> why(c.getSource(), StringArgumentType.getString(c, "item")))))
                .then(literal("keys").executes(c -> keys(c.getSource())))
                .then(literal("nbt").executes(c -> nbt(c.getSource())))
                .then(literal("filters").executes(c -> filters(c.getSource())))
                .then(literal("toggle").executes(c -> {
                    FlipConfig cfg = FlipConfig.INSTANCE;
                    cfg.enabled = !cfg.enabled;
                    FlipConfig.save();
                    reply(c.getSource(), "Flip alerts " + (cfg.enabled ? "ENABLED" : "DISABLED"),
                            cfg.enabled ? ChatFormatting.GREEN : ChatFormatting.RED);
                    return 1;
                }))
                .then(literal("export").executes(c -> {
                    try {
                        java.nio.file.Path p = java.nio.file.Path.of(System.getProperty("user.home"), "ValtrinData", "flips-export.csv");
                        int n = FlipStats.exportCsv(p);
                        reply(c.getSource(), "Saved " + n + " flips to " + p, ChatFormatting.GREEN);
                        return 1;
                    } catch (Exception ex) {
                        reply(c.getSource(), "Could not write the file.", ChatFormatting.RED);
                        return 0;
                    }
                }))
                .then(literal("mute").executes(c -> {
                    java.util.List<AlertHistory.Entry> last = AlertHistory.recent(1);
                    if (last.isEmpty()) {
                        reply(c.getSource(), "No alert to mute yet.", ChatFormatting.RED);
                        return 0;
                    }
                    String n = last.get(0).name().replaceAll("^\\[Lvl \\d+\\]\\s*", "").replaceAll("[\\u2720-\\u2729\\u272A\\u2776-\\u2793\\u25C6\\u273F]+", "").trim();
                    if (n.length() < 3) {
                        reply(c.getSource(), "That name is too short to mute safely. Use /flip blacklist add <text>.", ChatFormatting.RED);
                        return 0;
                    }
                    for (String b : FlipConfig.INSTANCE.blacklist) if (b.equalsIgnoreCase(n)) {
                        reply(c.getSource(), n + " is already muted.", ChatFormatting.YELLOW);
                        return 1;
                    }
                    FlipConfig.INSTANCE.blacklist.add(n);
                    FlipConfig.save();
                    reply(c.getSource(), "Muted: " + n + ". Undo with /flip blacklist remove " + n, ChatFormatting.YELLOW);
                    return 1;
                }))
                .then(literal("doctor").executes(c -> doctor(c.getSource())))
                .then(literal("preset").then(argument("name", StringArgumentType.word()).executes(c -> {
                    String n = StringArgumentType.getString(c, "name");
                    if (!FlipConfig.applyPreset(n)) {
                        reply(c.getSource(), "Presets: safe, balanced, aggressive.", ChatFormatting.RED);
                        return 0;
                    }
                    FlipConfig.save();
                    reply(c.getSource(), "Filters set to " + FlipConfig.currentPreset() + ".", ChatFormatting.GREEN);
                    return 1;
                })))
                .then(literal("session").executes(c -> session(c.getSource())))
                .then(literal("recent").executes(c -> recent(c.getSource())))
                .then(literal("menu").executes(c -> {
                    ValtrinMod.openSettingsNext = true;
                    return 1;
                }))
                .then(literal("next").executes(c -> {
                    ValtrinMod.openNextFlip(net.minecraft.client.Minecraft.getInstance(), true);
                    return 1;
                }))
                .then(literal("help").executes(c -> help(c.getSource())))
                .then(literal("profit")
                        .executes(c -> profit(c.getSource(), ""))
                        .then(literal("reset").executes(c -> {
                            FlipStats.reset();
                            reply(c.getSource(), "Profit history cleared.", ChatFormatting.GREEN);
                            return 1;
                        }))
                        .then(argument("days", StringArgumentType.word())
                                .suggests((ctx, b) -> {
                                    for (String x : new String[]{"1", "3", "7", "14", "all"}) b.suggest(x);
                                    return b.buildFuture();
                                })
                                .executes(c -> profit(c.getSource(), StringArgumentType.getString(c, "days")))))
                .then(literal("stats").executes(c -> statsMoved(c.getSource()))
                        .then(argument("rest", StringArgumentType.greedyString()).executes(c -> statsMoved(c.getSource()))))
                .then(literal("test").executes(c -> {
                    ValtrinMod.engine.sendTestAlert();
                    return 1;
                }))
                .then(literal("reset").executes(c -> {
                    FlipConfig.reset();
                    reply(c.getSource(), "All filters reset to defaults.", ChatFormatting.YELLOW);
                    return 1;
                }))
                .then(literal("set")
                        .then(coins("minprofit", "Minimum profit", v -> FlipConfig.INSTANCE.minProfit = v))
                        .then(coins("maxprice", "Max buy price (0 = no limit)", v -> FlipConfig.INSTANCE.maxPrice = v))
                        .then(decimal("minmargin", "Minimum margin %", v -> FlipConfig.INSTANCE.minMarginPercent = v))
                        .then(decimal("maxmargin", "Max margin % (0 = off)", v -> FlipConfig.INSTANCE.maxMarginPercent = v))
                        .then(integer("minvolume", "Minimum sales/day (0 = ignore)", v -> FlipConfig.INSTANCE.minVolume = v))
                        .then(integer("alertlimit", "Max alerts per 10 seconds, best flips kept (0 = no limit)", v -> FlipConfig.INSTANCE.alertLimit = v))
                        .then(integer("minlistings", "Minimum other listings", v -> FlipConfig.INSTANCE.minListings = v))
                        .then(integer("maxage", "Seconds after Valtrin first sees a listing that it can still be alerted", v -> FlipConfig.INSTANCE.maxAgeSeconds = Math.max(30, v)))
                        .then(integer("maxlistedage", "Ignore listings already older than this many seconds when first seen", v -> FlipConfig.INSTANCE.maxListedAgeSeconds = Math.max(30, v)))
                        .then(bool("bindhold", "Holding the open-next-flip key repeats (off = one flip per press)", v -> FlipConfig.INSTANCE.bindHold = v))
                        .then(integer("bindrepeat", "Milliseconds between repeats while the key is held (min 300)", v -> FlipConfig.INSTANCE.bindRepeatMs = Math.max(300, v)))
                        .then(integer("pagebatch", "Auction pages downloaded at once (10 = gentler)", v -> FlipConfig.INSTANCE.pageBatch = Math.max(1, v)))
                        .then(integer("poll", "Poll interval in ms (min 1000)", v -> FlipConfig.INSTANCE.pollIntervalMs = Math.max(1000, v)))
                        .then(integer("sellerwait", "Longest wait for a seller name before an alert goes out, ms (0 = never wait)", v -> FlipConfig.INSTANCE.sellerWaitMs = Math.max(0, Math.min(1000, v))))
                        .then(literal("shared").then(argument("url", StringArgumentType.greedyString()).executes(c -> {
                            String u = StringArgumentType.getString(c, "url").trim();
                            if (!u.equalsIgnoreCase("off") && Validate.serverUrl(u) == null) {
                                reply(c.getSource(), "The address must start with https:// (http:// only works for localhost).", ChatFormatting.RED);
                                return 0;
                            }
                            FlipConfig.INSTANCE.sharedUrl = u.equalsIgnoreCase("off") ? "" : u;
                            FlipConfig.save();
                            OwnModel.reconfigure();
                            reply(c.getSource(), FlipConfig.INSTANCE.sharedUrl.isEmpty()
                                    ? "Shared server off: using data on this computer."
                                    : "Shared server set. Enter the key in the settings screen (/flip menu, Advanced tab) if you have not.", ChatFormatting.GREEN);
                            return 1;
                        })))
                        .then(literal("sharedkey").executes(c -> {
                            // A key typed as a command stays in the game's command history, so it is entered in the settings screen instead.
                            reply(c.getSource(), "Enter the key in the settings screen (/flip menu, Advanced tab) so it never lands in your chat history.", ChatFormatting.YELLOW);
                            return 0;
                        }).then(argument("key", StringArgumentType.greedyString()).executes(c -> {
                            reply(c.getSource(), "Not saved. Enter the key in the settings screen (/flip menu, Advanced tab) so it never lands in your chat history.", ChatFormatting.YELLOW);
                            return 0;
                        })))
                        .then(bool("pets", "Include pets", v -> FlipConfig.INSTANCE.includePets = v))
                        .then(bool("record", "Record auction snapshots to ValtrinData/replay (about 25 MB per day)", v -> FlipConfig.INSTANCE.record = v))
                        .then(bool("pagelog", "Write ValtrinData/poll-log.tsv: when each Hypixel auction page updates (diagnostic only)", v -> FlipConfig.INSTANCE.pageLog = v))
                        .then(bool("trim", "Leave odd sales (alt trades, mistakes) out of the median", v -> FlipConfig.INSTANCE.trimOutliers = v))
                        .then(literal("minconfidence").then(argument("value", StringArgumentType.word()).executes(c -> {
                            String v = StringArgumentType.getString(c, "value").toLowerCase(java.util.Locale.ROOT);
                            if (!v.equals("low") && !v.equals("med") && !v.equals("high")) {
                                reply(c.getSource(), "Use low, med or high.", ChatFormatting.RED);
                                return 0;
                            }
                            FlipConfig.INSTANCE.minConfidence = v;
                            FlipConfig.save();
                            reply(c.getSource(), "Only alerts with at least " + v + " confidence are sent"
                                    + (v.equals("low") ? " (every alert)." : "."), ChatFormatting.GREEN);
                            return 1;
                        })))
                        .then(bool("sound", "Alert sound", v -> FlipConfig.INSTANCE.soundEnabled = v))
                        .then(bool("quiet", "Quiet hours (no sound in the set hours)", v -> FlipConfig.INSTANCE.quietHours = v))
                        .then(integer("quietfrom", "Quiet hours start (0-23)", v -> FlipConfig.INSTANCE.quietStartHour = Math.min(23, v)))
                        .then(integer("quietuntil", "Quiet hours end (0-23)", v -> FlipConfig.INSTANCE.quietEndHour = Math.min(23, v)))
                        .then(bool("unknownvolume", "Alert while volume is unknown", v -> FlipConfig.INSTANCE.allowUnknownVolume = v))
                        .then(bool("hypixelonly", "Only run on Hypixel", v -> FlipConfig.INSTANCE.hypixelOnly = v))
                        .then(bool("highlight", "Green/red AH highlights", v -> FlipConfig.INSTANCE.highlightEnabled = v))
                        .then(bool("tooltip", "Price tooltips", v -> FlipConfig.INSTANCE.tooltipEnabled = v))
                        .then(integer("minsamples", "Min sold-price samples for median mode", v -> FlipConfig.INSTANCE.minMedianSamples = v))
                        .then(integer("modvalue", "% of modifier value counted", v -> FlipConfig.INSTANCE.modValuePercent = Math.min(v, 200)))
                        .then(coins("slotvalue", "Fallback gem slot value", v -> FlipConfig.INSTANCE.gemSlotFallback = v))
                        .then(bool("suggest", "Suggested price in Create Auction menu", v -> FlipConfig.INSTANCE.suggestEnabled = v))
                        .then(bool("signfill", "Pre-type suggested price in the price sign", v -> FlipConfig.INSTANCE.signFill = v))
                        .then(decimal("undercut", "Suggested price undercut % below the lowest matching BIN", v -> FlipConfig.INSTANCE.undercutPercent = v))
                        .then(modeCommand()))
                .then(literal("blacklist")
                        .then(literal("add").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                            String t = StringArgumentType.getString(c, "text").trim();
                            FlipConfig.INSTANCE.blacklist.add(t);
                            FlipConfig.save();
                            reply(c.getSource(), "Blacklisted: " + t, ChatFormatting.YELLOW);
                            return 1;
                        })))
                        .then(literal("remove").then(argument("text", StringArgumentType.greedyString()).executes(c -> {
                            String t = StringArgumentType.getString(c, "text").trim();
                            boolean removed = FlipConfig.INSTANCE.blacklist.removeIf(s -> s.equalsIgnoreCase(t));
                            FlipConfig.save();
                            reply(c.getSource(), removed ? "Removed: " + t : "Not in blacklist: " + t,
                                    removed ? ChatFormatting.YELLOW : ChatFormatting.RED);
                            return removed ? 1 : 0;
                        }))));
    }

    /** /flip set mode lbin|median|both */
    /** /flip stats (with or without a number) now lives in /flip profit. */
    private static int statsMoved(FabricClientCommandSource src) {
        src.sendFeedback(V.say("/flip stats is now ").append(V.suggest("/flip profit 7"))
                .append(V.t("  or  ", V.GREY)).append(V.suggest("/flip profit all")));
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> modeCommand() {
        return literal("mode").then(argument("value", StringArgumentType.word()).executes(c -> {
            String v = StringArgumentType.getString(c, "value").toLowerCase(java.util.Locale.ROOT);
            if (!v.equals("lbin") && !v.equals("median") && !v.equals("both")) {
                reply(c.getSource(), "Mode must be lbin, median or both.", ChatFormatting.RED);
                return 0;
            }
            FlipConfig.INSTANCE.referenceMode = v;
            FlipConfig.save();
            String what = switch (v) {
                case "median" -> "profit is measured against the median sold price (1-14 day window, shorter for busy items)";
                case "both" -> "profit must hold against both LBIN and the median (the lower one is used)";
                default -> "profit is measured against the lowest / next lowest BIN";
            };
            reply(c.getSource(), "Mode set to " + v + ": " + what, ChatFormatting.GREEN);
            return 1;
        }));
    }

    // ---------------------------------------------------------------- argument helpers

    private static LiteralArgumentBuilder<FabricClientCommandSource> coins(String name, String label, LongConsumer setter) {
        return literal(name).then(argument("value", StringArgumentType.word()).executes(c -> {
            long v = Format.parseCoins(StringArgumentType.getString(c, "value"));
            if (v < 0) {
                reply(c.getSource(), "Invalid amount. Try 500k, 1.5m or 2b.", ChatFormatting.RED);
                return 0;
            }
            setter.accept(v);
            FlipConfig.save();
            reply(c.getSource(), label + " set to " + Format.coins(v), ChatFormatting.GREEN);
            return 1;
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> decimal(String name, String label, DoubleConsumer setter) {
        return literal(name).then(argument("value", DoubleArgumentType.doubleArg(0)).executes(c -> {
            double v = DoubleArgumentType.getDouble(c, "value");
            setter.accept(v);
            FlipConfig.save();
            reply(c.getSource(), label + " set to " + v, ChatFormatting.GREEN);
            return 1;
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> integer(String name, String label, IntConsumer setter) {
        return literal(name).then(argument("value", IntegerArgumentType.integer(0)).executes(c -> {
            int v = IntegerArgumentType.getInteger(c, "value");
            setter.accept(v);
            FlipConfig.save();
            reply(c.getSource(), label + " set to " + v, ChatFormatting.GREEN);
            return 1;
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> bool(String name, String label, Consumer<Boolean> setter) {
        return literal(name).then(argument("value", BoolArgumentType.bool()).executes(c -> {
            boolean v = BoolArgumentType.getBool(c, "value");
            setter.accept(v);
            FlipConfig.save();
            reply(c.getSource(), label + " set to " + v, ChatFormatting.GREEN);
            return 1;
        }));
    }

    // ---------------------------------------------------------------- output

    /** /flip profit [1-14|all]: profit from your own Auction House flips (read from chat), after auction fees. */
    private static int profit(FabricClientCommandSource src, String arg) {
        String a = arg.trim().toLowerCase(java.util.Locale.ROOT);
        int days;
        if (a.isEmpty()) days = 1;
        else if (a.equals("all")) days = 0;
        else {
            try { days = Integer.parseInt(a); } catch (NumberFormatException e) { days = -1; }
            if (days < 1 || days > 14) days = -1;
        }
        if (days < 0) {
            src.sendFeedback(V.prefix().append(V.t("Use a number from 1 to 14, or all.", V.RED)));
            src.sendFeedback(V.suggest("/flip profit 7").append(V.t("   ", V.GREY)).append(V.suggest("/flip profit all")));
            return 0;
        }
        FlipStats.Report r = FlipStats.report(days);
        String span = days == 0 ? "all time" : days == 1 ? "past 24h" : "past " + days + " days";
        src.sendFeedback(V.title("Profit", span));
        src.sendFeedback(V.rule());
        if (r.flips() == 0) {
            src.sendFeedback(V.t(days == 0 ? "No flips sold yet." : days == 1 ? "No flips sold in the past 24 hours." : "No flips sold in the past " + days + " days.", V.GREY));
            if (days == 0) src.sendFeedback(V.t("Profit counts when you collect the coins.", V.GREY));
            else src.sendFeedback(V.t("Try ", V.GREY).append(V.suggest("/flip profit " + (days < 7 ? "7" : days < 14 ? "14" : "all"))));
            return 1;
        }
        java.time.format.DateTimeFormatter dayFmt = java.time.format.DateTimeFormatter.ofPattern("EEE MMM d", java.util.Locale.ENGLISH);
        java.time.LocalDate today = java.time.LocalDate.now();

        // hover: profit per day, newest first
        java.util.List<Component> perDay = new java.util.ArrayList<>();
        perDay.add(V.bold("Profit per day", V.GOLD));
        int shown = 0;
        for (int i = r.daily().size() - 1; i >= 0 && shown < 14; i--, shown++) {
            long[] d = r.daily().get(i);
            java.time.LocalDate date = java.time.LocalDate.ofEpochDay(d[0]);
            String label = date.equals(today) ? "Today" : date.equals(today.minusDays(1)) ? "Yesterday" : dayFmt.format(date);
            perDay.add(V.row(label, V.t(signed(d[1]), d[1] < 0 ? V.RED : d[1] == 0 ? V.GREY : V.GOLD),
                    V.t(d[2] > 0 ? "  (" + d[2] + " sold)" : "", V.GREY)));
        }
        perDay.add(V.t("Counted when you collect the coins, after auction fees.", V.DIM));
        Component perDayTip = V.lines(perDay);

        long p = r.profit();
        net.minecraft.network.chat.MutableComponent profitRow = V.row("Profit",
                V.bold(signed(p), p < 0 ? V.RED : V.GOLD),
                V.t(days == 0 ? " (since " + dayFmt.format(java.time.Instant.ofEpochMilli(r.firstTs()).atZone(java.time.ZoneId.systemDefault()).toLocalDate()) + ")"
                        : days > 1 ? " (" + Format.compact(p / days) + "/day)" : "", V.GREY));
        src.sendFeedback(V.hover(profitRow, perDayTip));

        double avg = r.spent() > 0 ? 100.0 * p / r.spent() : 0;
        src.sendFeedback(V.row("Flips", V.value(r.flips() + " sold"), V.t(" · avg ", V.GREY),
                V.t((avg >= 0 ? "▲" : "▼") + Math.round(Math.abs(avg)) + "%", avg >= 0 ? V.GOLD : V.RED)));
        src.sendFeedback(V.row("Spent", V.value(Format.compact(r.spent()))));
        src.sendFeedback(V.row("Best flip", V.value(r.bestItem())));
        src.sendFeedback(V.t("  ", V.GREY).append(V.t(signed(r.bestProfit()), r.bestProfit() < 0 ? V.RED : V.GOLD))
                .append(V.t(" (" + Format.compact(r.bestCost()) + " → " + Format.compact(r.bestGot()) + ")", V.GREY)));
        if (days == 0 && r.bestDayProfit() > 0) {
            src.sendFeedback(V.row("Best day", V.value(dayFmt.format(java.time.LocalDate.ofEpochDay(r.bestDayEpoch()))),
                    V.t(" · ", V.GREY), V.coins(signed(r.bestDayProfit()))));
        }
        if (days > 1 && r.daily().size() > 1) {
            src.sendFeedback(V.hover(V.row("Trend", V.coins(FlipStats.spark(r.daily(), 1))), perDayTip));
        } else if (days == 0 && r.weekly().size() > 1) {
            src.sendFeedback(V.row("Trend", V.coins(FlipStats.spark(r.weekly(), 1)), V.t(" weekly", V.GREY)));
        }
        if (r.openCount() > 0) {
            src.sendFeedback(V.row("Unsold", V.value(r.openCount() + (r.openCount() == 1 ? " item" : " items")),
                    V.t(" (" + Format.compact(r.openCost()) + ")", V.GREY)));
        }
        return 1;
    }

    /** /flip doctor: a quick health check with a pass or warning for each part, so a problem is easy to find. */
    private static int doctor(FabricClientCommandSource src) {
        FlipEngine e = ValtrinMod.engine;
        FlipConfig c = FlipConfig.INSTANCE;
        long now = System.currentTimeMillis();
        int[] warns = {0};
        src.sendFeedback(V.title("Doctor", null));
        src.sendFeedback(V.rule());
        java.util.function.BiConsumer<Boolean, String[]> line = (ok, p) -> {
            if (!ok) warns[0]++;
            src.sendFeedback(V.t(ok ? "✔ " : "⚠ ", ok ? V.GREEN : V.YELLOW).append(V.t(p[0], V.WHITE)).append(V.t(p[1].isEmpty() ? "" : "  " + p[1], V.GREY)));
        };
        line.accept(c.enabled, new String[]{"Alerts", c.enabled ? "on" : "off: turn on in /flip menu"});
        line.accept(ValtrinMod.onHypixel || !c.hypixelOnly, new String[]{"Server", ValtrinMod.onHypixel ? "Hypixel" : "not Hypixel, alerts paused"});
        if (e != null) {
            line.accept(!e.lostContact(), new String[]{"Connection", e.lostContact() ? "no contact with Hypixel" : "online"});
            long pollAgo = e.lastPollAt == 0 ? -1 : (now - e.lastPollAt) / 1000;
            line.accept(pollAgo >= 0 && pollAgo < 30, new String[]{"Polling", pollAgo < 0 ? "not started yet" : "last check " + pollAgo + " s ago"});
            long snapAgo = e.lastRefreshAt == 0 ? -1 : (now - e.lastRefreshAt) / 1000;
            line.accept(snapAgo >= 0 && snapAgo < 180, new String[]{"Auction data", snapAgo < 0 ? "no snapshot yet" : "newest snapshot " + snapAgo + " s ago"});
            line.accept(e.volumeWarmedUp(), new String[]{"Volume data", e.volumeWarmedUp() ? "ready" : "still warming up"});
        }
        line.accept(Pricing.ready(), new String[]{"Price data", Pricing.status()});
        line.accept(true, new String[]{"Sales log", OwnModel.status()});
        boolean clockOk = !HypixelApi.clockKnown || Math.abs(HypixelApi.clockOffsetMs) < 5000;
        line.accept(clockOk, new String[]{"PC clock", !HypixelApi.clockKnown ? "not measured yet"
                : clockOk ? "in sync" : "off by " + Math.round(-HypixelApi.clockOffsetMs / 1000.0) + " s (Valtrin corrects for it, but a synced clock is best)"});
        boolean writable;
        try {
            java.nio.file.Path dir = java.nio.file.Path.of(System.getProperty("user.home"), "ValtrinData");
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Path t = dir.resolve(".doctor");
            java.nio.file.Files.writeString(t, "ok");
            java.nio.file.Files.deleteIfExists(t);
            writable = true;
        } catch (Exception ex) { writable = false; }
        line.accept(writable, new String[]{"Data folder", writable ? "ValtrinData is writable" : "cannot write to ValtrinData"});
        boolean sharedOk = c.sharedUrl.isEmpty() || Validate.serverUrl(c.sharedUrl) != null;
        line.accept(sharedOk, new String[]{"Shared server", c.sharedUrl.isEmpty() ? "not used" : sharedOk ? "https address set" : "address refused: it must start with https://"});
        line.accept(c.soundEnabled, new String[]{"Sound", c.soundEnabled ? "on" : "off"});
        boolean strict = c.minProfit > 20_000_000L || c.minVolume > 200 || c.minMarginPercent > 50;
        line.accept(!strict, new String[]{"Filters", FlipConfig.currentPreset() + (strict ? ": very strict, you may see almost no alerts" : "")});
        src.sendFeedback(V.t(warns[0] == 0 ? "Everything looks fine." : warns[0] + (warns[0] == 1 ? " warning" : " warnings") + " above.", warns[0] == 0 ? V.GREEN : V.YELLOW));
        return 1;
    }

    /** /flip session: what this game session has done so far. */
    private static int session(FabricClientCommandSource src) {
        long mins = Math.max(0, (System.currentTimeMillis() - FlipStats.SESSION_START) / 60_000);
        String online = mins < 60 ? mins + " min" : (mins / 60) + " h " + (mins % 60) + " min";
        FlipStats.Report r = FlipStats.sessionReport();
        int alerts = ValtrinMod.engine == null ? 0 : ValtrinMod.engine.alertsSent;
        src.sendFeedback(V.title("Session", online));
        src.sendFeedback(V.rule());
        src.sendFeedback(V.row("Alerts", V.value(Integer.toString(alerts)), V.t(alerts > 0 && mins > 0 ? String.format(java.util.Locale.ROOT, " · %.1f per hour", alerts * 60.0 / Math.max(1, mins)) : "", V.GREY)));
        if (r.flips() == 0) {
            src.sendFeedback(V.row("Flips sold", V.value("0")));
            src.sendFeedback(V.t("Profit counts when you collect the coins.", V.GREY));
        } else {
            src.sendFeedback(V.row("Flips sold", V.value(Integer.toString(r.flips())), V.t(" · " + r.wins() + " profitable", V.GREY)));
            src.sendFeedback(V.row("Profit", V.coins((r.profit() >= 0 ? "+" : "") + Format.compact(r.profit())), V.t(" after fees", V.GREY)));
            if (r.bestProfit() > 0) src.sendFeedback(V.row("Best flip", V.value(r.bestItem()), V.coins(" +" + Format.compact(r.bestProfit()))));
            if (mins >= 5) src.sendFeedback(V.row("Per hour", V.coins("~" + Format.compact(Math.round(r.profit() * 60.0 / mins)))));
        }
        if (r.openCount() > 0) src.sendFeedback(V.row("Unsold", V.value(r.openCount() + (r.openCount() == 1 ? " item" : " items")), V.t(" (" + Format.compact(r.openCost()) + ")", V.GREY)));
        return 1;
    }

    /** /flip recent: the last alerts of this session, newest first. Click one to open its auction (it may already be sold). */
    private static int recent(FabricClientCommandSource src) {
        java.util.List<AlertHistory.Entry> list = AlertHistory.recent(10);
        src.sendFeedback(V.title("Recent alerts", null));
        src.sendFeedback(V.rule());
        if (list.isEmpty()) {
            src.sendFeedback(V.t("No alerts yet this session.", V.GREY));
            return 1;
        }
        long now = System.currentTimeMillis();
        for (AlertHistory.Entry e : list) {
            long s = Math.max(0, (now - e.at()) / 1000);
            String ago = s < 90 ? s + " s" : s < 5400 ? (s / 60) + " min" : (s / 3600) + " h";
            net.minecraft.network.chat.MutableComponent line = Component.literal("▸ ").withStyle(style -> style.withColor(V.PURPLE_RGB))
                    .append(Component.literal(e.name()).withStyle(FlipAlerts.rarity(e.tier())))
                    .append(V.bold(" +" + Format.compact(e.profit()), V.GOLD))
                    .append(V.t("  buy " + Format.compact(e.price()) + " · " + ago + " ago", V.GREY));
            line.withStyle(style -> style
                    .withClickEvent(new net.minecraft.network.chat.ClickEvent.RunCommand("/viewauction " + e.uuid()))
                    .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(V.t("Click to open the auction (it may already be sold)", V.PURPLE))));
            src.sendFeedback(line);
        }
        return 1;
    }

    private static String signed(long v) {
        return (v >= 0 ? "+" : "") + Format.compact(v);
    }

    private static int help(FabricClientCommandSource src) {
        src.sendFeedback(V.title("Commands", null));
        src.sendFeedback(V.rule());
        src.sendFeedback(cmd("/flip status", "/flip status", "health and speed"));
        src.sendFeedback(cmd("/flip profit", "/flip profit ", "<1-14> or all"));
        src.sendFeedback(cmd("/flip mute", "/flip mute", "stop alerts for the item of your latest alert"));
        src.sendFeedback(cmd("/flip export", "/flip export", "save your finished flips as a CSV file"));
        src.sendFeedback(cmd("/flip doctor", "/flip doctor", "health check with a pass or warning for each part"));
        src.sendFeedback(cmd("/flip preset", "/flip preset balanced", "filter presets: safe, balanced, aggressive"));
        src.sendFeedback(cmd("/flip session", "/flip session", "this session: alerts, flips, profit per hour"));
        src.sendFeedback(cmd("/flip recent", "/flip recent", "your last alerts, click to open"));
        src.sendFeedback(cmd("/flip menu", "/flip menu", "open the settings screen (or press the . key)"));
        src.sendFeedback(cmd("/flip next", "/flip next", "open next flip"));
        src.sendFeedback(cmd("/flip why", "/flip why ", "why not flagged"));
        src.sendFeedback(cmd("/flip set", "/flip set ", "change a setting"));
        src.sendFeedback(V.t("More: filters, toggle, blacklist, reset, snapshots, keys, nbt, test", V.DIM));
        src.sendFeedback(V.t("▸ click a command to fill it in", V.PURPLE));
        return 1;
    }

    /** A clickable command line: purple command, grey description. Clicking puts the command in the chat box. */
    private static Component cmd(String shown, String suggest, String desc) {
        Component c = Component.literal(shown).withStyle(V.PURPLE).withStyle(s -> s
                .withClickEvent(new net.minecraft.network.chat.ClickEvent.SuggestCommand(suggest))
                .withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(V.t("Click to type " + suggest.trim(), V.GREY))));
        return Component.empty().append(c).append(V.t("  " + desc, V.GREY));
    }

    private static void reply(FabricClientCommandSource src, String msg, ChatFormatting color) {
        net.minecraft.network.chat.Style st = color == ChatFormatting.RED ? V.RED
                : color == ChatFormatting.GREEN ? V.GREEN
                : color == ChatFormatting.GRAY || color == ChatFormatting.DARK_GRAY ? V.GREY : V.WHITE;
        src.sendFeedback(V.prefix().append(V.t(msg, st)));
    }

    private static int filters(FabricClientCommandSource src) {
        FlipConfig c = FlipConfig.INSTANCE;
        reply(src, "Current filters:", ChatFormatting.YELLOW);
        line(src, "enabled", String.valueOf(c.enabled));
        line(src, "mode", c.referenceMode + (c.referenceMode.equals("lbin") ? "" : " (min " + c.minMedianSamples + " samples)"));
        line(src, "modvalue", c.modValuePercent + "% of modifier value counted");
        line(src, "slotvalue", Format.coins(c.gemSlotFallback) + " (fallback per unlocked gem slot)");
        line(src, "minprofit", Format.coins(c.minProfit));
        line(src, "minmargin", c.minMarginPercent + "%");
        line(src, "maxmargin", c.maxMarginPercent > 0 ? c.maxMarginPercent + "%" : "off");
        line(src, "minvolume", c.minVolume > 0 ? c.minVolume + "/day" : "ignored");
        line(src, "maxprice", c.maxPrice > 0 ? Format.coins(c.maxPrice) : "no limit");
        line(src, "minlistings", String.valueOf(c.minListings));
        line(src, "maxage", c.maxAgeSeconds + "s after Valtrin first sees a listing (listings already older than "
                + c.maxListedAgeSeconds + "s when first seen are ignored)");
        line(src, "next-flip key", "set in Options > Controls > Valtrin (default Z); hold repeat "
                + (c.bindHold ? "every " + c.bindRepeatMs + "ms" : "off") + ", " + FlipQueue.size(System.currentTimeMillis()) + " flip(s) waiting");
        line(src, "pets", String.valueOf(c.includePets));
        line(src, "trim", c.trimOutliers + " (odd sales left out of the median)");
        line(src, "minconfidence", c.minConfidence);
        line(src, "record", String.valueOf(c.record));
        line(src, "pagelog", c.pageLog + " (poll-log.tsv)");
        line(src, "alertlimit", c.alertLimit > 0 ? c.alertLimit + " per 10s" : "no limit");
        line(src, "sound", String.valueOf(c.soundEnabled));
        line(src, "blacklist", c.blacklist.isEmpty() ? "(empty)" : String.join(", ", c.blacklist));
        return 1;
    }

    private static void line(FabricClientCommandSource src, String key, String value) {
        src.sendFeedback(V.t("  " + key + ": ", V.GREY).append(V.t(value, V.WHITE)));
    }

    /** /flip keys: which item data fields exist on the items seen so far, and which of them the mod reads. */
    private static int keys(FabricClientCommandSource src) {
        java.util.List<java.util.Map.Entry<String, Integer>> l = new java.util.ArrayList<>(ItemData.SEEN_KEYS.entrySet());
        l.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        reply(src, "Item data fields seen (count) - green = used for pricing, gray = not used yet", ChatFormatting.YELLOW);
        int shown = 0;
        for (java.util.Map.Entry<String, Integer> e : l) {
            if (shown++ >= 60) break;
            boolean known = ItemData.KNOWN_KEYS.contains(e.getKey());
            src.sendFeedback(Component.literal("  " + e.getKey() + "  " + e.getValue())
                    .withStyle(known ? ChatFormatting.GREEN : ChatFormatting.GRAY));
        }
        if (l.isEmpty()) reply(src, "Nothing seen yet - wait for the first auction snapshot.", ChatFormatting.GRAY);
        return 1;
    }

    /** /flip nbt: the data of the last item you pointed at, and how the mod reads it. Also saved to ValtrinData/nbt-dump.txt. */
    private static int nbt(FabricClientCommandSource src) {
        net.minecraft.world.item.ItemStack st = AhOverlay.lastHovered;
        if (st == null || st.isEmpty()) {
            reply(src, "Point at an item (hover it in a menu or inventory) first, then run /flip nbt.", ChatFormatting.RED);
            return 0;
        }
        net.minecraft.world.item.component.CustomData cd = st.get(net.minecraft.core.component.DataComponents.CUSTOM_DATA);
        if (cd == null) {
            reply(src, "That item has no SkyBlock data.", ChatFormatting.RED);
            return 0;
        }
        String snbt = cd.copyTag().toString();
        StackInfo info = StackInfo.read(st);
        String read = info == null ? "not comparable (the mod skips it)"
                : info.data().base() + " " + info.data().tier() + " | " + ModTokens.encode(ModTokens.of(info.data()));
        reply(src, st.getHoverName().getString(), ChatFormatting.YELLOW);
        line(src, "mod reads it as", read);
        for (int i = 0; i < snbt.length(); i += 200) {
            src.sendFeedback(Component.literal("  " + snbt.substring(i, Math.min(snbt.length(), i + 200))).withStyle(ChatFormatting.GRAY));
        }
        try {
            java.nio.file.Path f = java.nio.file.Path.of(System.getProperty("user.home"), "ValtrinData", "nbt-dump.txt");
            java.nio.file.Files.createDirectories(f.getParent());
            java.nio.file.Files.writeString(f, st.getHoverName().getString() + "\n" + read + "\n" + snbt + "\n\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            line(src, "saved to", f.toString());
        } catch (Exception e) {
            line(src, "save failed", e.toString());
        }
        return 1;
    }

    /** /flip why [item]: the latest listings that looked profitable but did not alert, with the reason. */
    private static int why(FabricClientCommandSource src, String filter) {
        java.util.List<RejectLog.Row> rows = RejectLog.last(filter, 10);
        if (rows.isEmpty()) {
            reply(src, filter.isBlank() ? "Nothing rejected yet (only listings that looked profitable are listed)."
                    : "No rejected listing matching '" + filter.trim() + "' since the game started.", ChatFormatting.YELLOW);
            return 1;
        }
        reply(src, "Why these did NOT alert (newest first)", ChatFormatting.YELLOW);
        for (RejectLog.Row r : rows) {
            line(src, r.name() + " " + Format.coins(r.price()),
                    RejectLog.explain(r.code(), FlipConfig.INSTANCE)
                            + " [lowest BIN value " + (r.lbin() > 0 ? Format.coins(r.lbin()) : "-")
                            + ", median value " + (r.median() > 0 ? Format.coins(r.median()) : "-")
                            + ", " + r.samples() + " sales" + (r.conf() == null ? "" : ", " + r.conf()) + "]");
        }
        return 1;
    }

    private static int snapshots(FabricClientCommandSource src) {
        java.util.List<FlipEngine.Snap> l = ValtrinMod.engine.snapshotHistory();
        reply(src, "Last " + l.size() + " snapshots (newest last)", ChatFormatting.YELLOW);
        for (FlipEngine.Snap s : l) {
            line(src, java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(s.lu()), java.time.ZoneId.systemDefault()).withNano(0).toString(),
                    (s.gapMs() < 0 ? "first" : s.gapMs() / 1000 + "s after the previous one")
                            + ", " + s.seenAgeMs() / 1000 + "s old when we got it"
                            + ", newest listing " + (s.newestListingAgeMs() < 0 ? "?" : s.newestListingAgeMs() / 1000 + "s old")
                            + ", " + (s.newListings() < 0 ? "?" : s.newListings()) + " new listings of " + s.total());
        }
        return 1;
    }

    private static int status(FabricClientCommandSource src) {
        FlipEngine e = ValtrinMod.engine;
        FlipConfig c = FlipConfig.INSTANCE;
        java.util.Locale L = java.util.Locale.ROOT;

        // details that sit behind the hover
        java.util.List<Component> speed = new java.util.ArrayList<>();
        speed.add(V.bold("Speed details", V.GOLD));
        for (String l : e.speedLines()) speed.add(V.t(l, V.GREY));
        speed.add(V.t(e.lastLagMs < 0 ? "full snapshot: none yet"
                : "full snapshot: " + String.format(L, "%.1f", e.lastFetchMs / 1000.0) + "s download + "
                + String.format(L, "%.1f", e.lastProcessMs / 1000.0) + "s processing (new listings on page 0 are judged before this)", V.GREY));
        if (e.lastPageMaxMs >= 0) speed.add(V.t("pages: typical " + String.format(L, "%.1f", e.lastPageMedianMs / 1000.0)
                + "s, slowest " + String.format(L, "%.1f", e.lastPageMaxMs / 1000.0) + "s", V.GREY));
        speed.add(V.t(e.lightStatus(), V.GREY));
        speed.add(V.t(e.lastPollAt == 0 ? "polling: not yet"
                : "polling: last check " + (System.currentTimeMillis() - e.lastPollAt) / 1000 + "s ago, Hypixel's newest snapshot was "
                + String.format(L, "%.0f", e.apiSnapshotAgeMs / 1000.0) + "s old"
                + (HypixelApi.clockKnown ? ", PC clock off by " + String.format(L, "%.0f", -HypixelApi.clockOffsetMs / 1000.0) + "s" : ""), V.GREY));

        long ago = e.lastRefreshAt == 0 ? -1 : (System.currentTimeMillis() - e.lastRefreshAt) / 1000;
        java.util.List<Component> data = new java.util.ArrayList<>();
        data.add(V.bold("Data details", V.GOLD));
        data.add(V.row("Last snapshot", V.value(ago < 0 ? "none yet" : ago + "s ago")));
        data.add(V.row("Volume data", V.value(e.volumeWarmedUp() ? "ready" : "warming up (" + c.volumeWarmupMinutes + " min needed)")));
        data.add(V.row("Price data", V.value(Pricing.status())));
        data.add(V.row("Sales log", V.value(OwnModel.status())));
        data.add(V.row("Seller names", V.value(SellerNames.cached() + " saved")));
        data.add(V.row("Last menu title", V.value(AhOverlay.lastTitle)));

        src.sendFeedback(V.title("Status", null));
        src.sendFeedback(V.rule());
        src.sendFeedback(V.row("Alerts", c.enabled ? V.t("ON", V.GREEN) : V.t("OFF", V.RED)));
        if (!ValtrinMod.onHypixel) src.sendFeedback(V.row("Server", V.t("⚠ not Hypixel, alerts paused", V.RED)));
        if (e.lostContact()) {
            src.sendFeedback(V.row("Connection", V.t("⚠ no contact with Hypixel", V.RED)));
        } else {
            int out = e.outagesLastHour();
            src.sendFeedback(V.row("Connection", V.t("● online", V.GREEN), V.t(" · " + out + (out == 1 ? " outage" : " outages") + " in the last hour", V.GREY)));
        }
        ServerLink sl = ValtrinMod.link;
        if (sl != null && sl.state() != ServerLink.State.OFF) {
            boolean ok = sl.connected();
            src.sendFeedback(V.row("Valtrin server", ok ? V.t("● connected", V.GREEN) : V.t("⚠ " + sl.state().name().toLowerCase(), V.RED),
                    V.t(sl.detail().isEmpty() ? "" : " · " + sl.detail(), V.GREY)));
        } else if (FlipConfig.INSTANCE.useServerAlerts) {
            src.sendFeedback(V.row("Valtrin server", V.t("⚠ " + (sl == null ? "off" : sl.detail()), V.RED)));
        }
        boolean viaServer = sl != null && sl.connected();
        if (viaServer) {
            // This computer is not searching Hypixel while the server delivers alerts, so its own speed numbers would be stale.
            java.util.List<Component> why = new java.util.ArrayList<>();
            why.add(V.bold("Server mode", V.GOLD));
            why.add(V.t("The Valtrin server searches Hypixel and sends you the flips.", V.GREY));
            why.add(V.t("This computer is not searching, so its own speed numbers are not shown.", V.GREY));
            why.add(V.t("Turn off Alerts from the server (/flip menu, Advanced) to search here yourself.", V.GREY));
            src.sendFeedback(V.hover(V.row("Speed", V.t("handled by the server", V.GREY)), V.lines(why)));
            long d = sl.delayMs();
            src.sendFeedback(V.hover(V.row("Alert delay", d <= 0 ? V.bold("none", V.GREEN) : V.bold((d / 1000) + " s", V.GOLD), V.t(" set by your key", V.GREY)), V.lines(why)));
            long last = sl.lastFlipAt();
            src.sendFeedback(V.row("Last flip", last == 0 ? V.t("none yet this session", V.GREY)
                    : V.value(Math.max(0, (System.currentTimeMillis() - last) / 60_000L) + " min ago")));
        } else {
            String med = e.detectMedian();
            src.sendFeedback(V.hover(V.row("Speed", V.value(med), V.t(" after stamp", V.GREY)), V.lines(speed)));
            long am = e.alertMedianMs();
            src.sendFeedback(V.hover(V.row("Alert in", am < 0 ? V.t("-", V.GREY) : V.bold(am + " ms", V.GOLD)), V.lines(speed)));
            src.sendFeedback(V.hover(V.row("Tracking", V.value(String.format(L, "%,d", e.tracked)), V.t(" auctions", V.GREY)), V.lines(data)));
        }
        long sentTotal = e.alertsSent + (sl == null ? 0 : sl.flipsShown());
        src.sendFeedback(V.row("Sent", V.value(String.valueOf(sentTotal)), V.t(" alerts this session", V.GREY)));
        if (!e.lastError.isEmpty()) src.sendFeedback(V.row("Last error", V.t(e.lastError, V.RED)));
        src.sendFeedback(V.t("▸ hover any line for details · ", V.PURPLE).append(V.suggest("/flip help")));
        return 1;
    }
}
