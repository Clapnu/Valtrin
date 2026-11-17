// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.inventory.Slot;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Visual-only helpers: price/value lines in item tooltips, and green/red slot colors in the
 * auction house. Nothing here clicks or sends anything.
 */
final class AhOverlay {
    private AhOverlay() {}

    private static final int GREEN = 0x5500FF44;
    private static final int RED = 0x44FF2A2A;

    /** Shown in /flip status so menu-title matching can be checked. */
    static volatile String lastTitle = "(none yet)";
    /** The last item whose tooltip was shown (for /flip nbt). */
    static volatile net.minecraft.world.item.ItemStack lastHovered;

    private static Object lastTipStack;
    private static StackInfo lastTipInfo;

    private record Cached(Object stack, StackInfo info) {}
    private static final Map<Integer, Cached> CACHE = new HashMap<>();

    private static Field LEFT, TOP;
    static {
        try {
            LEFT = AbstractContainerScreen.class.getDeclaredField("leftPos");
            LEFT.setAccessible(true);
            TOP = AbstractContainerScreen.class.getDeclaredField("topPos");
            TOP.setAccessible(true);
        } catch (Throwable t) {
            LEFT = null;
            TOP = null;
        }
    }

    static void register() {
        ItemTooltipCallback.EVENT.register((stack, context, type, lines) -> {
            lastHovered = stack;
            FlipConfig cfg = FlipConfig.INSTANCE;
            if (!cfg.tooltipEnabled || !active()) return;
            try {
                // The tooltip is rebuilt every frame; only re-read the item when it changes.
                StackInfo info;
                if (stack == lastTipStack) {
                    info = lastTipInfo;
                } else {
                    info = StackInfo.read(stack);
                    lastTipStack = stack;
                    lastTipInfo = info;
                }
                if (info != null) addTooltip(info, lines);
            } catch (Throwable t) {
                ValtrinMod.LOGGER.debug("Tooltip failed", t);
            }
        });

        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (screen instanceof AbstractContainerScreen<?> container) {
                CACHE.clear();
                net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents.allowMouseClick(screen).register((sc, click) -> {
                    try {
                        return !(click.button() == 0 && chipClick(container, click.x(), click.y()));
                    } catch (Throwable t) {
                        return true;
                    }
                });
                ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                    try {
                        highlight(container, graphics);
                        createMenu(container, graphics);
                    } catch (Throwable t) {
                        ValtrinMod.LOGGER.debug("Highlight failed", t);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ create-auction suggestion

    private static void createMenu(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        if (!cfg.suggestEnabled || !active()) return;
        String title = screen.getTitle().getString();
        if (!(title.contains("Create") && title.contains("Auction"))) return;

        List<Slot> slots = screen.getMenu().slots;
        int menuSlots = Math.max(0, slots.size() - 36);
        StackInfo info = null;
        if (menuSlots > 13 && slots.get(13).hasItem()) info = StackInfo.read(slots.get(13).getItem());
        if (info == null) {
            for (int i = 0; i < menuSlots && info == null; i++) {
                if (slots.get(i).hasItem()) info = StackInfo.read(slots.get(i).getItem());
            }
        }
        if (info == null) return;

        boolean ok = SuggestedPrice.update(info.data());
        net.minecraft.client.gui.Font font = net.minecraft.client.Minecraft.getInstance().font;
        chips.clear();
        String head = "Suggested Price";
        java.util.List<SuggestedPrice.Option> opts = ok ? SuggestedPrice.options() : java.util.List.of();
        int rowH = 24;
        int w = font.width(head);
        String[] l1 = new String[opts.size()], l2 = new String[opts.size()];
        for (int i = 0; i < opts.size(); i++) {
            SuggestedPrice.Option o = opts.get(i);
            l1[i] = o.label() + "  " + Format.coins(o.price());
            l2[i] = "Sells in about " + SellTime.format(o.minutes());
            w = Math.max(w, Math.max(font.width(l1[i]), font.width(l2[i])) + 8);
        }
        String sub = opts.isEmpty() ? SuggestedPrice.detail : "Click a price to use it";
        String note = ok ? "Enchants/gems not added" : "";
        w = Math.max(w, Math.max(font.width(sub), font.width(note)));
        int x = leftPos(screen) + 176 + 8;
        if (x + w + 6 > screen.width - 2) x = Math.max(2, leftPos(screen) - w - 14);
        int y = topPos(screen) + 20;
        int h = 14 + Math.max(1, opts.size()) * rowH + 22;
        g.fill(x - 4, y - 4, x + w + 4, y + h - 6, 0xCC15101F);
        g.fill(x - 4, y - 4, x - 3, y + h - 6, 0xFFB77CFF);
        g.text(font, head, x, y, 0xFFFFC83D, true);
        int cy = y + 14;
        int sel = SuggestedPrice.chosenIndex();
        for (int i = 0; i < opts.size(); i++) {
            boolean on = i == sel;
            if (on) g.fill(x - 2, cy - 2, x + w + 2, cy + rowH - 4, 0x55B77CFF);
            g.text(font, l1[i], x + 2, cy, on ? 0xFFFFC83D : 0xFFF4F0FF, true);
            g.text(font, l2[i], x + 2, cy + 10, 0xFF9A94AD, true);
            chips.add(new int[]{x - 2, cy - 2, x + w + 2, cy + rowH - 4});
            cy += rowH;
        }
        if (opts.isEmpty()) cy += rowH;
        g.text(font, sub, x, cy - 4, 0xFF9A94AD, true);
        if (!note.isEmpty()) g.text(font, note, x, cy + 6, 0xFF7F7993, true);
    }

    /** Clickable price rows of the Create Auction panel as {x1, y1, x2, y2}, in the order of SuggestedPrice.options(). */
    private static final java.util.List<int[]> chips = new java.util.ArrayList<>();

    /** A left click on one of the price rows picks that price. True when the click was used. */
    private static boolean chipClick(AbstractContainerScreen<?> screen, double mx, double my) {
        String title = screen.getTitle().getString();
        if (!(title.contains("Create") && title.contains("Auction"))) return false;
        for (int i = 0; i < chips.size(); i++) {
            int[] c = chips.get(i);
            if (mx >= c[0] && mx <= c[2] && my >= c[1] && my <= c[3]) {
                SuggestedPrice.choose(i);
                return true;
            }
        }
        return false;
    }

    private static boolean active() {
        return !FlipConfig.INSTANCE.hypixelOnly || ValtrinMod.onHypixel;
    }

    // ------------------------------------------------------------------ highlights

    private static void highlight(AbstractContainerScreen<?> screen, GuiGraphicsExtractor g) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        if (!cfg.highlightEnabled || !active() || !Pricing.ready()) return;

        String title = screen.getTitle().getString();
        lastTitle = title;
        if (title.contains("Create") && title.contains("Auction")) return; // handled by createMenu()
        boolean own = title.contains("Manage Auctions") || title.contains("Your Auctions");
        boolean browse = title.contains("Auction") && !own;
        if (!own && !browse) return;

        List<Slot> slots = screen.getMenu().slots;
        int menuSlots = Math.max(0, slots.size() - 36); // skip the player's own inventory
        int left = leftPos(screen);
        int top = topPos(screen);

        for (int i = 0; i < menuSlots; i++) {
            Slot slot = slots.get(i);
            if (!slot.hasItem()) continue;

            Object stack = slot.getItem();
            Cached c = CACHE.get(i);
            StackInfo info;
            if (c != null && c.stack() == stack) {
                info = c.info();
            } else {
                info = StackInfo.read(slot.getItem());
                CACHE.put(i, new Cached(stack, info));
            }
            if (info == null || info.buyItNow() <= 0 || info.tier() == null) continue;

            Market.Estimate est = Market.estimate(info.data(), info.buyItNow());
            int color = 0;
            if (own) {
                if (info.activeListing() && Market.isOvercut(est, info.buyItNow())) color = RED;
            } else if (Market.passes(est, info.data(), info.name(), info.buyItNow())) {
                color = GREEN;
            }
            if (color != 0) {
                int x = left + slot.x;
                int y = top + slot.y;
                g.fill(x, y, x + 16, y + 16, color);
            }
        }
    }

    private static int leftPos(AbstractContainerScreen<?> s) {
        try {
            if (LEFT != null) return LEFT.getInt(s);
        } catch (Throwable ignored) {}
        return (s.width - 176) / 2;
    }

    private static int topPos(AbstractContainerScreen<?> s) {
        try {
            if (TOP != null) return TOP.getInt(s);
        } catch (Throwable ignored) {}
        int rows = (s.getMenu().slots.size() - 36) / 9;
        return (s.height - (114 + rows * 18)) / 2;
    }

    // ------------------------------------------------------------------ tooltip

    private static void addTooltip(StackInfo info, List<Component> lines) {
        FlipConfig cfg = FlipConfig.INSTANCE;
        ItemData d = info.data();
        if (Pricing.isBazaar(d.id())) return; // bazaar items: nothing to add
        long price = info.buyItNow();

        long[] raw = Market.raw(d);
        Market.Estimate est = Pricing.ready() ? Market.estimate(d, price) : null;
        if (raw == null && est == null) return;
        boolean loading = d.tier() != null && OwnModel.loading(d);

        lines.add(Component.empty().append(V.t("✦ ", V.GOLD)).append(V.bold("Valtrin", V.PURPLE)));

        // Lowest / next lowest BIN: lowest listings of items with the same important modifiers
        long lb = -1, nlb = -1;
        if (raw != null && raw.length > 0) {
            lb = raw[0];
            if (raw.length > 1) nlb = raw[1];
        }
        if (lb > 0) {
            lines.add(row2("Lowest BIN", Format.coins(lb), V.WHITE, null));
            if (nlb > 0) lines.add(row2("Next lowest", Format.coins(nlb), V.WHITE, null));
        } else if (loading) {
            lines.add(row2("Lowest BIN", "...", V.GREY, null));
        } else if (est != null && est.medianSell() > 0) {
            lines.add(row2("Lowest BIN", "~" + Format.coins(est.medianSell()), V.WHITE, "(estimate, no match found)"));
        } else {
            lines.add(row2("Lowest BIN", "none listed", V.GREY, null));
        }

        // Med (7 day median sold) and Vol (sales per day)
        long med = est != null ? est.medianSell() : -1;
        double vx = Market.volumeExact(d);
        String vol = vx < 0 ? "..." : (vx < 10 ? String.format("%.1f", vx) : String.valueOf(Math.round(vx)));
        OwnModel.Stats winStats = OwnModel.stats(d, System.currentTimeMillis());
        String win = winStats != null ? " (" + winStats.windowDays() + "d)" : "";
        MutableComponent medLine = Component.literal("Median" + win + ": ").withStyle(V.GREY)
                .append(Component.literal(med > 0 ? Format.coins(med) : "...").withStyle(V.GOLD))
                .append(Component.literal("  Sales/day: ").withStyle(V.GREY))
                .append(Component.literal(vol).withStyle(V.GOLD));
        lines.add(medLine);

        // Short-term median: the newest exact sales. When it is LOWER than the long-term median the price is dropping,
        // and the alerts use it instead (a rise in the last few sales is ignored).
        if (winStats != null && winStats.recent() > 0) {
            boolean dropping = winStats.recent() < winStats.median();
            MutableComponent rec = Component.literal("Last " + OwnModel.RECENT_N + " sales: ").withStyle(V.GREY)
                    .append(Component.literal(Format.coins(winStats.recent())).withStyle(dropping ? V.RED : V.GOLD));
            if (dropping) rec.append(Component.literal("  (price dropping, used for flips)").withStyle(V.DIM));
            lines.add(rec);
        }

        // Craft cost (only when the item has a recipe) goes above Value (base market price + modifiers).
        long recipe = Pricing.recipeCost(d.id());
        Pricing.CraftBreakdown cb = Pricing.craft(d, -1);
        if (cb != null && (cb.total() > 0 || recipe > 0)) {
            // One or the other: craftable items show Craft Cost (recipe + modifiers),
            // everything else shows Value (market price of the base item + modifiers).
            boolean hasMods = cb.mods() > 0;
            if (recipe > 0) {
                lines.add(row2("Craft Cost", Format.coins(recipe + cb.mods()), V.GOLD, null));
                java.util.List<String> parts = RecipeData.breakdownLines(d.id());
                if (parts != null) {
                    if (ShiftKey.down()) {
                        lines.add(Component.literal("Recipe (biggest costs):").withStyle(V.GREY));
                        for (String p : parts) lines.add(Component.literal("  " + p).withStyle(V.DIM));
                    } else {
                        lines.add(Component.literal("Hold Shift: recipe breakdown").withStyle(V.DIM));
                    }
                }
                if (hasMods) lines.add(Component.literal("Recipe: ").withStyle(V.GREY)
                        .append(Component.literal(Format.coins(recipe)).withStyle(V.GOLD))
                        .append(Component.literal(" + modifiers ").withStyle(V.GREY))
                        .append(Component.literal(Format.coins(cb.mods())).withStyle(V.GOLD)));
            } else {
                lines.add(row2("Value", Format.coins(cb.total()), V.GOLD, null));
                if (hasMods) lines.add(Component.literal("Base item: ").withStyle(V.GREY)
                        .append(Component.literal(Format.coins(cb.base())).withStyle(V.GOLD))
                        .append(Component.literal(" + modifiers ").withStyle(V.GREY))
                        .append(Component.literal(Format.coins(cb.mods())).withStyle(V.GOLD)));
            }
            if (hasMods) lines.add(Component.literal("Enchants " + Format.coins(cb.enchants()) + ", stars " + Format.coins(cb.stars())
                    + ", recomb " + Format.coins(cb.recomb()) + ", gems " + Format.coins(cb.gems() + cb.slots())
                    + ", other " + Format.coins(cb.extras())).withStyle(V.DIM));
        }

        if (d.cosmetic()) {
            lines.add(Component.literal("Dye/skin value ignored").withStyle(V.DIM));
        }

        if (est != null && est.sell() > 0 && price > 0) {
            lines.add(Component.literal("Profit: ").withStyle(V.GREY)
                    .append(Component.literal((est.profit() >= 0 ? "+" : "") + Format.coins(est.profit())
                            + String.format(" (%.1f%%)", est.margin()))
                            .withStyle(est.profit() >= 0 ? V.GREEN : V.RED)));
        }

        // Is this auction a flip, and if not, why not (the same decision the alerts use)?
        String title = lastTitle == null ? "" : lastTitle;
        boolean ownMenu = title.contains("Manage Auctions") || title.contains("Your Auctions");
        if (est != null && price > 0 && !ownMenu) {
            if (Market.passes(est, d, info.name(), price)) {
                lines.add(V.t("● Flagged", V.GREEN).append(V.t(" as a flip", V.GREY)));
            } else {
                String code = Market.rejectCode(est, d, info.name(), price);
                lines.add(V.t("✗ Not flagged", V.RED).append(V.t(": " + (code == null ? "does not look profitable" : RejectLog.explain(code, cfg)), V.GREY)));
            }
        }
    }

    private static Component row2(String label, String value, net.minecraft.network.chat.Style color, String note) {
        MutableComponent c = Component.literal(label + ": ").withStyle(V.GREY)
                .append(Component.literal(value).withStyle(color));
        if (note != null) c.append(Component.literal(" " + note).withStyle(V.DIM));
        return c;
    }

    private static Component row(String label, String value) {
        return Component.literal(label + ": ").withStyle(V.GREY)
                .append(Component.literal(value).withStyle(V.GOLD));
    }

    private static Component sub(String label, long value) {
        return Component.literal("   " + label + ": ").withStyle(V.DIM)
                .append(Component.literal(Format.coins(value)).withStyle(V.GREY));
    }
}
