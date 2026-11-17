// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class FlipAlerts {
    private FlipAlerts() {}

    static ChatFormatting rarity(String tier) {
        if (tier == null) return ChatFormatting.WHITE;
        return switch (tier) {
            case "UNCOMMON" -> ChatFormatting.GREEN;
            case "RARE" -> ChatFormatting.BLUE;
            case "EPIC" -> ChatFormatting.DARK_PURPLE;
            case "LEGENDARY" -> ChatFormatting.GOLD;
            case "MYTHIC" -> ChatFormatting.LIGHT_PURPLE;
            case "DIVINE" -> ChatFormatting.AQUA;
            case "SPECIAL", "VERY_SPECIAL" -> ChatFormatting.RED;
            default -> ChatFormatting.WHITE;
        };
    }

    // ---- plain notices (all in the Valtrin look, see V) -----------------------------------------------

    /** A plain one-line notice: "✦ Valtrin › text". */
    static Component notice(String text) {
        return V.say(text);
    }

    /** A problem: "✦ Valtrin › ⚠ lead rest" with the lead in red. */
    static Component warn(String lead, String rest) {
        return V.say("⚠ " + lead, V.RED, rest);
    }

    /** Something is fine again: "✦ Valtrin › ● lead rest" with the lead in green. */
    static Component good(String lead, String rest) {
        return V.say("● " + lead, V.GREEN, rest);
    }

    /** The open-next-flip key (or /flip next) opened an auction. */
    static Component opened(String name, long profit, int waiting) {
        MutableComponent c = V.prefix().append(V.t("Opened ", V.WHITE))
                .append(V.t(name, V.PURPLE))
                .append(V.t(" +" + Format.compact(profit), V.GOLD));
        c.append(Component.literal("\n"));
        c.append(V.t("   " + waiting + " more waiting", V.GREY));
        return c;
    }

    /**
     * One compact alert line:
     * ✦ Valtrin │ Torrhus Belt +2.81M ▲8% ●●○ ▸
     * Click anywhere on the line to run /viewauction (nothing is bought automatically); everything else is in the hover.
     */
    static Component build(String name, String tier, String uuid,
                           long buy, long sell, long profit, double margin, double volume, String detail,
                           String sellerName, String conf, SellTime.Resale resale) {
        String cleanName = name.replaceAll("§.", "");
        long fees = sell - buy - profit;
        String vol = volume < 0 ? "?" : (volume < 10 ? String.format(Locale.ROOT, "%.1f", volume)
                : String.valueOf(Math.round(volume)));

        List<Component> tip = new ArrayList<>();
        tip.add(Component.literal(cleanName).withStyle(rarity(tier), ChatFormatting.BOLD));
        tip.add(V.row("Buy", V.value(Format.compact(buy)), V.t("   Sell est ", V.GREY), V.value(Format.compact(sell))));
        tip.add(V.row("Profit", V.coins("+" + Format.compact(profit) + " after tax"), V.t(String.format(Locale.ROOT, " (%.1f%%)", margin), V.GREY)));
        tip.add(V.row("Volume", V.value(vol + " sales/day")));
        if (sellerName != null) tip.add(V.row("Seller", V.value(sellerName)));
        if (conf != null) tip.add(V.row("Confidence", V.dots(conf), V.t(" " + V.confWord(conf), V.GREY)));
        tip.add(V.t("Est. AH fees " + Format.coins(fees), V.DIM));
        if (sell > 0 && buy > 0 && sell > buy) {
            long below = Math.round((sell - buy) * 100.0 / sell);
            String why = "listed " + below + "% below its estimated sell price";
            if (volume >= 0 && volume < 5) why += ", but it sells slowly";
            else if (volume >= 50) why += ", and it sells fast";
            tip.add(V.row("Why", V.value(why)));
        }
        if (resale != null) {
            tip.add(V.row("Resale", V.value("list at " + Format.compact(resale.price())), V.t("  sells in about ", V.GREY),
                    V.value(SellTime.format(resale.minutes()))));
            if (resale.perHour() > 0) tip.add(V.row("Per hour", V.coins("~" + Format.compact(resale.perHour()) + "/h")));
        }
        if (detail != null && !detail.isBlank()) {
            for (String l : detail.split("\n")) if (!l.isBlank()) tip.add(V.t(l, V.DIM));
        }
        tip.add(V.t("▸ Click to open the auction", V.PURPLE));
        tip.add(V.t("▸ Right-click for " + (sellerName != null ? sellerName + "'s" : "the seller's") + " AH", V.PURPLE));

        MutableComponent root = Component.empty().withStyle(style -> style
                .withClickEvent(new ClickEvent.RunCommand("/viewauction " + (Validate.uuid(uuid) ? uuid : "")))
                .withHoverEvent(new HoverEvent.ShowText(V.lines(tip))));

        // A blank line above each alert leaves a gap between alerts, so a fast click lands on the right one.
        if (V.ALERT_GAP) root.append(Component.literal("\n"));
        root.append(V.prefixMark(profit >= V.BIG_PROFIT));
        root.append(V.bold("Valtrin", V.PURPLE));
        root.append(V.t(" │ ", V.GREY));
        root.append(Component.literal(cleanName).withStyle(rarity(tier)));
        root.append(V.bold(" +" + Format.compact(profit), V.GOLD));
        root.append(V.t(" ▲" + Math.round(margin) + "%", V.GREY));
        if (conf != null) root.append(Component.literal(" ").append(V.dots(conf)));
        root.append(V.t(" ▸", V.PURPLE));
        return root;
    }
}
