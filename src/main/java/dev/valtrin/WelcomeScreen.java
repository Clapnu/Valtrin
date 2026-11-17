// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/** Shown once after an update (and from Settings > Display): what's new, a quick start, and whether this is the newest version. */
public final class WelcomeScreen extends Screen {
    private static final String[][] NEW = {
            {"Sells in about…", "Alerts and the Create Auction window say how long a price should take to sell."},
            {"Price options", "Create Auction now offers Undercut, Lowest BIN and Median. Click one."},
            {"Settings screen", "Every setting in one clean window. Open it with the . key or /flip menu."},
            {"Jackpot sound", "Big flips play eight fast dings. The normal chime is twice as loud."},
    };
    private static final String[] START = {
            "Press Z to open the best waiting flip, or click an alert.",
            "Right-click an alert to see that seller's auctions.",
            "Valtrin only alerts. It never buys or sends anything for you.",
    };

    private final Screen parent;
    private int px, py, pw, ph;

    public WelcomeScreen(Screen parent) {
        super(Component.literal("Valtrin"));
        this.parent = parent;
        VersionCheck.start();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    private void done() { Minecraft.getInstance().setScreenAndShow(parent); }

    private void layout() {
        pw = Math.min(width - 16, 400);
        ph = Math.min(height - 16, 262);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        layout();
        g.fill(0, 0, width, height, 0xA0000000);
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, SettingsScreen.PURPLE);
        g.fill(px, py, px + pw, py + ph, SettingsScreen.PANEL);
        g.fill(px, py, px + pw, py + 3, SettingsScreen.GOLD);

        int x = px + 14, y = py + 14;
        g.text(font, "✦ Valtrin", x, y, SettingsScreen.GOLD, true);
        String ver = "v" + FlipConfig.VERSION;
        g.text(font, ver, px + pw - 14 - font.width(ver), y, SettingsScreen.PURPLE, true);
        y += 14;
        g.text(font, "Flip alerts that never touch your mouse", x, y, SettingsScreen.GREY, true);
        y += 16;

        // version line
        String line; int col;
        String latest = VersionCheck.latest;
        if (!VersionCheck.available()) { line = "Update check starts once Valtrin is on Modrinth."; col = SettingsScreen.DIM; }
        else if (latest == null) { line = "Checking for a newer version…"; col = SettingsScreen.DIM; }
        else if (VersionCheck.compare(latest, FlipConfig.VERSION) > 0) { line = "You are on an older version. v" + latest + " is available on Modrinth."; col = SettingsScreen.RED; }
        else { line = "You are on the newest version."; col = SettingsScreen.GREEN; }
        g.fill(x - 4, y - 4, px + pw - 10, y + 12, SettingsScreen.PANEL2);
        g.text(font, line, x, y, col, true);
        y += 22;

        g.text(font, "WHAT'S NEW", x, y, SettingsScreen.GOLD, true);
        y += 13;
        for (String[] n : NEW) {
            g.text(font, "▸ " + n[0], x, y, SettingsScreen.WHITE, true);
            y += 10;
            g.text(font, trim(n[1], pw - 40), x + 10, y, SettingsScreen.GREY, true);
            y += 13;
        }
        y += 4;
        g.text(font, "QUICK START", x, y, SettingsScreen.GOLD, true);
        y += 13;
        for (String s : START) {
            g.text(font, trim("• " + s, pw - 30), x, y, SettingsScreen.GREY, true);
            y += 11;
        }

        button(g, "Open settings", px + pw - 14 - 90 - 6 - 70, py + ph - 26, 90, 16, false, mx, my);
        button(g, "Got it", px + pw - 14 - 70, py + ph - 26, 70, 16, true, mx, my);
    }

    private String trim(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (s.length() > 3 && font.width(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private void button(GuiGraphicsExtractor g, String text, int x, int y, int w, int h, boolean primary, int mx, int my) {
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + h;
        g.fill(x, y, x + w, y + h, primary ? (hov ? 0xFFFFD866 : SettingsScreen.GOLD) : (hov ? 0xFF3A2D5C : 0xFF4A3A78));
        SettingsScreen.label(g, font, text, x + w / 2, y + 4, primary ? 0xFF140F1F : SettingsScreen.WHITE);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() != 0) return false;
        double mx = e.x(), my = e.y();
        if (mx >= px + pw - 14 - 70 && mx < px + pw - 14 && my >= py + ph - 26 && my < py + ph - 10) { done(); return true; }
        int sx = px + pw - 14 - 90 - 6 - 70;
        if (mx >= sx && mx < sx + 90 && my >= py + ph - 26 && my < py + ph - 10) {
            Minecraft.getInstance().setScreenAndShow(new SettingsScreen(parent));
            return true;
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        if (e.key() == 256 || e.key() == 257) { done(); return true; }
        return super.keyPressed(e);
    }
}
