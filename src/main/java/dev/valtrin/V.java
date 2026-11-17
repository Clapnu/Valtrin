// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

import java.util.List;

/**
 * The Valtrin look: one place for every colour, symbol and layout rule used in chat and tooltips.
 * Purple = brand and names, gold = coins and profit, soft white = values, grey = labels,
 * red = problems only, green = "all good" only. Change a colour here and it changes everywhere.
 */
final class V {
    private V() {}

    static final int PURPLE_RGB = 0xB77CFF, GOLD_RGB = 0xFFC83D, WHITE_RGB = 0xF4F0FF, GREY_RGB = 0x9A94AD,
            DIM_RGB = 0x7F7993, RED_RGB = 0xFF7A7A, GREEN_RGB = 0x6EE7A8, YELLOW_RGB = 0xFFEB4D;

    static final Style PURPLE = color(PURPLE_RGB);
    static final Style GOLD = color(GOLD_RGB);
    static final Style WHITE = color(WHITE_RGB);
    static final Style GREY = color(GREY_RGB);
    static final Style DIM = color(DIM_RGB);
    static final Style RED = color(RED_RGB);
    static final Style GREEN = color(GREEN_RGB);
    static final Style YELLOW = color(YELLOW_RGB);

    /** Profit from this much gets a star instead of the normal brand mark in alerts. */
    /** Blank line above each flip alert so the lines are easier to click. Set false for tightly packed alerts. */
    static final boolean ALERT_GAP = true;

    static final long BIG_PROFIT = 20_000_000L;

    private static Style color(int rgb) {
        return Style.EMPTY.withColor(TextColor.fromRgb(rgb));
    }

    // ---- building blocks ------------------------------------------------------------------------------

    static MutableComponent t(String text, Style style) {
        return Component.literal(text).withStyle(style);
    }

    static MutableComponent bold(String text, Style style) {
        return Component.literal(text).withStyle(style.withBold(true));
    }

    /** The leading mark of an alert line: ✦ normally, a bold ★ for a big flip. */
    static MutableComponent prefixMark(boolean big) {
        return big ? bold("★ ", GOLD) : t("✦ ", GOLD);
    }

    /** "✦ Valtrin › " (the star is gold, the name purple and bold). */
    static MutableComponent prefix(boolean star) {
        return Component.empty()
                .append(star ? bold("★ ", GOLD) : t("✦ ", GOLD))
                .append(bold("Valtrin", PURPLE))
                .append(t(" › ", GREY));
    }

    static MutableComponent prefix() { return prefix(false); }

    /** One normal message: prefix then grey text. */
    static MutableComponent say(String text) {
        return prefix().append(t(text, GREY));
    }

    /** A message with a coloured lead-in, e.g. "⚠ Lost contact." in red, then grey detail. */
    static MutableComponent say(String lead, Style leadStyle, String rest) {
        MutableComponent c = prefix().append(t(lead, leadStyle));
        if (rest != null && !rest.isEmpty()) c.append(t(" " + rest, GREY));
        return c;
    }

    /** Title line of a block: "✦ Valtrin Profit · 7 days". */
    static MutableComponent title(String name, String sub) {
        MutableComponent c = Component.empty()
                .append(t("✦ ", GOLD))
                .append(bold("Valtrin", PURPLE))
                .append(bold(" " + name, WHITE));
        if (sub != null && !sub.isEmpty()) c.append(t(" · " + sub, GREY));
        return c;
    }

    /** The purple line under a title. */
    static MutableComponent rule() {
        return t("──────────────────────", PURPLE);
    }

    /** "Label: " in grey, then the value parts. */
    static MutableComponent row(String label, Component... value) {
        MutableComponent c = t(label + ": ", GREY);
        for (Component v : value) c.append(v);
        return c;
    }

    static MutableComponent value(String text) { return t(text, WHITE); }

    static MutableComponent coins(String text) { return t(text, GOLD); }

    /** Confidence dots: low = one red dot, medium = two yellow, high = three green. */
    static MutableComponent dots(String conf) {
        if ("HIGH".equals(conf)) return t("●●●", GREEN);
        if ("MED".equals(conf)) return t("●●○", YELLOW);
        return t("●○○", RED);
    }

    static String confWord(String conf) {
        return "HIGH".equals(conf) ? "high" : "MED".equals(conf) ? "medium" : "low";
    }

    /** Applies a hover text to a component. */
    static MutableComponent hover(MutableComponent c, Component text) {
        return c.withStyle(s -> s.withHoverEvent(new HoverEvent.ShowText(text)));
    }

    /** A click-to-fill command piece: purple "▸ /flip profit 7", puts the command in the chat box. */
    static MutableComponent suggest(String command) {
        return Component.literal("▸ " + command).withStyle(s -> s
                .withColor(TextColor.fromRgb(PURPLE_RGB))
                .withClickEvent(new ClickEvent.SuggestCommand(command))
                .withHoverEvent(new HoverEvent.ShowText(t("Click to type " + command, GREY))));
    }

    /** Joins lines with newlines (for hover texts). */
    static MutableComponent lines(List<? extends Component> parts) {
        MutableComponent c = Component.empty();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) c.append(Component.literal("\n"));
            c.append(parts.get(i));
        }
        return c;
    }

    /** "35M" style text for a coin amount. */
    static String compact(long v) { return Format.compact(v); }
}
