// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ActiveTextCollector;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Style;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Right-click a flip alert in the open chat to run /ah <seller> (opens that seller's auction house).
 * One command per click that you do yourself; nothing is bought or sent automatically.
 * Left-click still opens the auction itself (vanilla chat behaviour).
 */
final class SellerClick {
    private SellerClick() {}

    private static final int KEEP = 60;
    /** The seller of one alert: Mojang uuid (always) and name (when it was already known at alert time). */
    private record Seller(String uuid, String name) {}
    /** auction uuid -> seller, for the most recent alerts. */
    private static final Map<String, Seller> SELLERS = new LinkedHashMap<>() {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Seller> e) { return size() > KEEP; }
    };

    /** Called for every alert. The name may still be unknown here; it is looked up when you click (usually already cached by then). */
    static void remember(String auctionUuid, String sellerUuid, String name) {
        if (auctionUuid == null) return;
        boolean hasName = name != null && !name.isBlank();
        if (!hasName && (sellerUuid == null || sellerUuid.isBlank())) return;
        synchronized (SELLERS) { SELLERS.put(auctionUuid, new Seller(sellerUuid, hasName ? name : null)); }
    }

    /** Hook up the right-click on every chat screen that opens. */
    static void register(Screen screen) {
        if (!(screen instanceof ChatScreen)) return;
        ScreenMouseEvents.allowMouseClick(screen).register((s, click) -> {
            try {
                if (click.button() != 1) return true; // only the right mouse button
                return !handle(s, click.x(), click.y());
            } catch (Throwable t) {
                return true; // never break chat
            }
        });
    }

    /** True if the click was on a Valtrin alert and was handled. */
    private static boolean handle(Screen screen, double x, double y) {
        Minecraft mc = Minecraft.getInstance();
        Style style = styleAt(mc, screen, x, y);
        if (style == null) return false;
        if (!(style.getClickEvent() instanceof ClickEvent.RunCommand run)) return false;
        String cmd = run.command();
        String prefix = "/viewauction ";
        if (cmd == null || !cmd.startsWith(prefix)) return false;
        String uuid = cmd.substring(prefix.length()).trim();
        Seller s;
        synchronized (SELLERS) { s = SELLERS.get(uuid); }
        if (s == null) {
            if (mc.player != null) mc.player.sendSystemMessage(FlipAlerts.warn("Seller unknown.", "This alert is too old, its seller is no longer remembered."));
            return true;
        }
        String name = s.name() != null ? s.name() : SellerNames.cachedName(s.uuid());
        if (name != null) {
            openAh(mc, name);
            return true;
        }
        // Not known yet: ask Mojang in the background (up to 1.5 s) and run the command on the game thread when it is back.
        final String sellerUuid = s.uuid();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            String n = SellerNames.lookup(sellerUuid);
            mc.execute(() -> {
                if (n != null) openAh(mc, n);
                else if (mc.player != null) mc.player.sendSystemMessage(FlipAlerts.warn("Seller name not found.", "Mojang did not answer, try again in a moment."));
            });
        });
        return true;
    }

    private static void openAh(Minecraft mc, String seller) {
        if (!Validate.player(seller)) return; // only a real username is ever put into a command
        if (mc.getConnection() != null) mc.getConnection().sendCommand("ah " + seller);
    }

    /**
     * The text style under the mouse in the open chat, found the same way Minecraft's own left-click does it
     * (ChatScreen.mouseClicked): a ClickableStyleFinder is run over the chat's visible text at the mouse position.
     */
    private static Style styleAt(Minecraft mc, Screen screen, double x, double y) {
        ActiveTextCollector.ClickableStyleFinder finder = new ActiveTextCollector.ClickableStyleFinder(mc.font, (int) x, (int) y);
        ChatComponent.DisplayMode mode = ChatComponent.DisplayMode.FOREGROUND;
        try { // the screen keeps its display mode in a private field (normal / restricted); fall back to FOREGROUND
            java.lang.reflect.Field f = ChatScreen.class.getDeclaredField("displayMode");
            f.setAccessible(true);
            if (f.get(screen) instanceof ChatComponent.DisplayMode m) mode = m;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
        }
        mc.gui.hud.getChat().captureClickableText(finder, mc.getWindow().getGuiScaledHeight(), mc.gui.hud.getGuiTicks(), mode);
        return finder.result();
    }
}
