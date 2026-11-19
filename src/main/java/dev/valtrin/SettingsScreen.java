// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The Valtrin settings screen: every setting that has a command, in five tabs. Open it with the settings key (period, "."),
 * /flip menu, or the button in the welcome window. Changes apply at once; Done saves, Cancel puts everything back.
 */
public final class SettingsScreen extends Screen {
    // ---- look (same colours as the chat alerts) ----
    static final int PURPLE = 0xFFB77CFF, GOLD = 0xFFFFC83D, WHITE = 0xFFF4F0FF, GREY = 0xFF9A94AD, DIM = 0xFF7F7993;
    static final int RED = 0xFFFF7A7A, GREEN = 0xFF6EE7A8;
    static final int PANEL = 0xF0140F1F, PANEL2 = 0xFF1E1730, FIELD = 0xFF0F0B18, LINE = 0xFF3A2D5C;

    private static final String[] TABS = {"Alerts", "Filters", "Pricing", "Display", "Advanced"};
    private static final int ROW_H = 22, CTRL_W = 112;

    private enum Kind { HEADER, NOTE, TOGGLE, FIELD, CYCLE, ACTION }

    /** One line of the settings list. */
    private static final class Row {
        Kind kind; String label, hint = "", button = "";
        Supplier<Boolean> getB; Consumer<Boolean> setB;
        Supplier<String> getS; Function<String, Boolean> setS; boolean masked;
        Runnable run;
        int cx, cy; // where the control was drawn last frame
        Row(Kind k, String label) { this.kind = k; this.label = label; }
        Row hint(String h) { this.hint = h; return this; }
    }

    private final Screen parent;
    private final FlipConfig undo;
    private final String sharedUrlAtOpen, sharedKeyAtOpen;
    private int tab = 0;
    private int scroll = 0;
    private List<Row> rows = new ArrayList<>();
    private Row editing = null;
    private StringBuilder buf = new StringBuilder();
    private String flash = "";
    private long flashUntil = 0;
    private int px, py, pw, ph;           // panel
    private int listTop, listBottom;      // visible list
    private boolean saved = false;

    public SettingsScreen(Screen parent) {
        super(Component.literal("Valtrin settings"));
        this.parent = parent;
        this.undo = FlipConfig.snapshot();
        this.sharedUrlAtOpen = FlipConfig.INSTANCE.sharedUrl;
        this.sharedKeyAtOpen = FlipConfig.INSTANCE.sharedKey;
        build();
    }

    // ------------------------------------------------------------------ rows

    private static Row header(String t) { return new Row(Kind.HEADER, t); }
    private static Row note(String t) { return new Row(Kind.NOTE, t); }

    private static Row toggle(String label, String hint, Supplier<Boolean> g, Consumer<Boolean> s) {
        Row r = new Row(Kind.TOGGLE, label).hint(hint); r.getB = g; r.setB = s; return r;
    }

    private static Row field(String label, String hint, Supplier<String> g, Function<String, Boolean> s) {
        Row r = new Row(Kind.FIELD, label).hint(hint); r.getS = g; r.setS = s; return r;
    }

    private static Row cycle(String label, String hint, Supplier<String> g, Runnable next) {
        Row r = new Row(Kind.CYCLE, label).hint(hint); r.getS = g; r.run = next; return r;
    }

    private static Row action(String label, String hint, String button, Runnable run) {
        Row r = new Row(Kind.ACTION, label).hint(hint); r.button = button; r.run = run; return r;
    }

    private static Function<String, Boolean> coinsSetter(java.util.function.LongConsumer set) {
        return t -> { long v = Format.parseCoins(t.trim()); if (v < 0) return false; set.accept(v); return true; };
    }

    private static Function<String, Boolean> intSetter(int min, int max, java.util.function.IntConsumer set) {
        return t -> {
            try { int v = Integer.parseInt(t.trim()); set.accept(Math.max(min, Math.min(max, v))); return true; }
            catch (NumberFormatException e) { return false; }
        };
    }

    private static Function<String, Boolean> decSetter(double min, double max, java.util.function.DoubleConsumer set) {
        return t -> {
            try { double v = Double.parseDouble(t.trim().replace(',', '.')); set.accept(Math.max(min, Math.min(max, v))); return true; }
            catch (NumberFormatException e) { return false; }
        };
    }

    private static String num(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "");
    }

    private void build() {
        FlipConfig c = FlipConfig.INSTANCE;
        List<Row> r = new ArrayList<>();
        switch (tab) {
            case 0 -> {
                r.add(header("Alerts"));
                r.add(toggle("Valtrin on", "Master switch: off stops all alerts.", () -> c().enabled, v -> c().enabled = v));
                r.add(toggle("Alert sound", "A two-note chime when flip alerts arrive.", () -> c().soundEnabled, v -> c().soundEnabled = v));
                r.add(toggle("Jackpot sound", "Big flips play eight fast dings instead of the chime.", () -> c().jackpotSound, v -> c().jackpotSound = v));
                r.add(field("Jackpot from profit", "Profit from which a flip counts as big. 0 = never.", () -> Format.compact(c().jackpotMinProfit),
                        coinsSetter(v -> c().jackpotMinProfit = v)));
                r.add(field("Alerts per 10 s", "Most alerts shown per 10 seconds, the best ones are kept. 0 = no limit.", () -> Integer.toString(c().alertLimit),
                        intSetter(0, 1000, v -> c().alertLimit = v)));
                r.add(cycle("Minimum confidence", "Lowest confidence that still alerts: low = everything.", () -> c().minConfidence,
                        () -> c().minConfidence = c().minConfidence.equals("low") ? "med" : c().minConfidence.equals("med") ? "high" : "low"));
                r.add(toggle("Quiet hours", "No sound during the hours below (alerts still show in chat). Good for overnight.", () -> c().quietHours, v -> c().quietHours = v));
                r.add(field("Quiet from (hour)", "0-23, your local time. 23 = 11 pm.", () -> Integer.toString(c().quietStartHour), intSetter(0, 23, v -> c().quietStartHour = v)));
                r.add(field("Quiet until (hour)", "0-23, your local time. 7 = 7 am.", () -> Integer.toString(c().quietEndHour), intSetter(0, 23, v -> c().quietEndHour = v)));
                r.add(header("Try it"));
                r.add(action("Normal chime", "Plays the normal flip sound.", "Play", () -> ValtrinMod.playChime(Minecraft.getInstance())));
                r.add(action("Jackpot sound", "Plays the big-flip sound.", "Play", () -> ValtrinMod.playJackpot(Minecraft.getInstance())));
                r.add(action("Test alert", "Sends a fake alert to chat so you can see the look. Closes this screen.", "Send", () -> {
                    if (ValtrinMod.engine != null) ValtrinMod.engine.sendTestAlert();
                    close(true);
                }));
                r.add(header("Open next flip key"));
                r.add(toggle("Hold to repeat", "Holding the key opens one flip after another.", () -> c().bindHold, v -> c().bindHold = v));
                r.add(field("Repeat every (ms)", "Time between flips while the key is held (min 300).", () -> Integer.toString(c().bindRepeatMs),
                        intSetter(300, 10000, v -> c().bindRepeatMs = v)));
                r.add(action("Open next flip now", "Same as the key: opens the best waiting flip. Closes this screen.", "Open", () -> {
                    close(true);
                    ValtrinMod.openNextFlip(Minecraft.getInstance(), true);
                }));
                r.add(note("Change the key in Options > Controls > Valtrin."));
            }
            case 1 -> {
                r.add(header("Presets"));
                r.add(note("Current: " + FlipConfig.currentPreset()));
                for (String[] pr : FlipConfig.PRESETS) {
                    r.add(action(pr[0], pr[1], "Use " + pr[0], () -> { FlipConfig.applyPreset(pr[0]); rebuildLater = true; }));
                }
                r.add(header("What counts as a flip"));
                r.add(field("Minimum profit", "After auction fees. Try 500k, 1.5m.", () -> Format.compact(c().minProfit), coinsSetter(v -> c().minProfit = v)));
                r.add(field("Minimum margin %", "Profit as a percent of the buy price.", () -> num(c().minMarginPercent), decSetter(0, 1000, v -> c().minMarginPercent = v)));
                r.add(field("Maximum margin %", "Hides absurd margins that are usually false flips. 0 = off.", () -> num(c().maxMarginPercent), decSetter(0, 100000, v -> c().maxMarginPercent = v)));
                r.add(field("Minimum sales/day", "Items that sell less often are skipped. 0 = ignore.", () -> Integer.toString(c().minVolume), intSetter(0, 100000, v -> c().minVolume = v)));
                r.add(field("Maximum buy price", "Your purse. 0 = no limit.", () -> Format.compact(c().maxPrice), coinsSetter(v -> c().maxPrice = v)));
                r.add(field("Minimum other listings", "Other matching listings needed to trust the price.", () -> Integer.toString(c().minListings), intSetter(0, 1000, v -> c().minListings = v)));
                r.add(toggle("Include pets", "Pets are left out by default.", () -> c().includePets, v -> c().includePets = v));
                r.add(toggle("Alert while volume unknown", "Alert even when sales/day is still warming up.", () -> c().allowUnknownVolume, v -> c().allowUnknownVolume = v));
                r.add(header("Never alert these items"));
                r.add(field("Add a word", "Items whose name contains it are never alerted. Type and press Enter.", () -> "",
                        t -> {
                            String w = t.trim();
                            if (w.isEmpty()) return true;
                            for (String b : c().blacklist) if (b.equalsIgnoreCase(w)) return true;
                            c().blacklist.add(w);
                            rebuildLater = true;
                            return true;
                        }));
                for (String b : new ArrayList<>(c().blacklist)) {
                    r.add(action("  " + b, "Click Remove to allow this again.", "Remove", () -> { c().blacklist.remove(b); rebuildLater = true; }));
                }
                if (c().blacklist.isEmpty()) r.add(note("Nothing blocked."));
            }
            case 2 -> {
                r.add(header("How profit is measured"));
                r.add(cycle("Compare against", "lbin = lowest BIN, median = recent sold price, both = the lower of the two.", () -> c().referenceMode,
                        () -> c().referenceMode = c().referenceMode.equals("both") ? "lbin" : c().referenceMode.equals("lbin") ? "median" : "both"));
                r.add(field("Min median samples", "Sold-price samples needed in median mode.", () -> Integer.toString(c().minMedianSamples), intSetter(1, 1000, v -> c().minMedianSamples = v)));
                r.add(toggle("Ignore odd sales", "Leaves alt trades and mistakes out of the median.", () -> c().trimOutliers, v -> c().trimOutliers = v));
                r.add(field("Modifier value %", "How much of enchants, gems and stars counts in the price.", () -> Integer.toString(c().modValuePercent), intSetter(0, 200, v -> c().modValuePercent = v)));
                r.add(field("Gem slot value", "Used when the real unlock cost is not known.", () -> Format.compact(c().gemSlotFallback), coinsSetter(v -> c().gemSlotFallback = v)));
                r.add(header("Create Auction window"));
                r.add(toggle("Show suggested price", "Shows Undercut, Lowest BIN and Median with how long each takes to sell.", () -> c().suggestEnabled, v -> c().suggestEnabled = v));
                r.add(toggle("Fill the price sign", "Pre-types the chosen price. You still press Done yourself.", () -> c().signFill, v -> c().signFill = v));
                r.add(field("Undercut %", "How far below the lowest BIN the suggested price sits.", () -> num(c().undercutPercent), decSetter(0, 50, v -> c().undercutPercent = v)));
            }
            case 3 -> {
                r.add(header("On screen"));
                r.add(toggle("Green/red slot colours", "Green = flip, red = your listing was undercut.", () -> c().highlightEnabled, v -> c().highlightEnabled = v));
                r.add(toggle("Price tooltips", "Lowest BIN, median and volume in item tooltips.", () -> c().tooltipEnabled, v -> c().tooltipEnabled = v));
                r.add(toggle("Back to chat", "Closing a menu you opened from chat (like a seller's AH) returns to chat, so the mouse stays free.", () -> c().returnToChat, v -> c().returnToChat = v));
                r.add(toggle("Only on Hypixel", "Off lets Valtrin run on any server.", () -> c().hypixelOnly, v -> c().hypixelOnly = v));
                r.add(header("Freshness"));
                r.add(field("Alert window (s)", "How long after Valtrin first sees a listing it can still be alerted.", () -> Integer.toString(c().maxAgeSeconds), intSetter(30, 100000, v -> c().maxAgeSeconds = v)));
                r.add(field("Ignore older than (s)", "Listings already this old when first seen are skipped.", () -> Integer.toString(c().maxListedAgeSeconds), intSetter(30, 100000, v -> c().maxListedAgeSeconds = v)));
                r.add(header("Chat reports"));
                r.add(action("Profit, 24 hours", "Your flip profit over the past 24 hours, in chat. Closes this screen.", "Show", () -> run("flip profit 1")));
                r.add(action("Profit, 7 days", "Your flip profit this week, in chat.", "Show", () -> run("flip profit 7")));
                r.add(action("This session", "Alerts, flips and profit per hour since you started the game, in chat.", "Show", () -> run("flip session")));
                r.add(action("Recent alerts", "Your last alerts of this session, in chat.", "Show", () -> run("flip recent")));
                r.add(action("Export flips (CSV)", "Saves your finished flips to ValtrinData/flips-export.csv.", "Export", () -> run("flip export")));
                r.add(action("Mute last alert", "Stops alerts for the item of your latest alert. Undo it in the Filters tab.", "Mute", () -> run("flip mute")));
                r.add(action("Health check", "Checks connection, data, clock and settings, in chat.", "Run", () -> run("flip doctor")));
                r.add(action("Status", "Speed, data and connection, in chat.", "Show", () -> run("flip status")));
                r.add(action("Command list", "Every /flip command, in chat.", "Show", () -> run("flip help")));
                r.add(action("Welcome window", "Shows the what's-new window again.", "Open", () -> Minecraft.getInstance().setScreenAndShow(new WelcomeScreen(this))));
            }
            default -> {
                r.add(header("Speed"));
                r.add(field("Poll interval (ms)", "Time between checks of Hypixel (min 1000).", () -> Integer.toString(c().pollIntervalMs), intSetter(1000, 60000, v -> c().pollIntervalMs = v)));
                r.add(field("Pages at once", "Auction pages downloaded together (10 = gentler).", () -> Integer.toString(c().pageBatch), intSetter(1, 100, v -> c().pageBatch = v)));
                r.add(field("Seller name wait (ms)", "Longest wait for a seller name before an alert. 0 = never wait.", () -> Integer.toString(c().sellerWaitMs), intSetter(0, 1000, v -> c().sellerWaitMs = v)));
                r.add(header("Shared data server"));
                r.add(field("Server address", "Leave empty to use only data on this computer. If set, sales prices come from that server and your key is sent to it. Only use an address you trust.", () -> c().sharedUrl, t -> {
                    String u = t.trim();
                    if (!u.isEmpty() && !u.equalsIgnoreCase("off") && Validate.serverUrl(u) == null) { say("The address must start with https://"); return true; }
                    c().sharedUrl = u.equalsIgnoreCase("off") ? "" : u;
                    return true;
                }));
                r.add(toggle("Alerts from the server", "Get flip alerts from the Valtrin server (needs address and key). While connected, alerts come from the server; this computer still reads prices for tooltips.", () -> c().useServerAlerts, v -> c().useServerAlerts = v));
                Row key = field("Server key", "The key for the server above.", () -> c().sharedKey.isEmpty() ? "" : "••••••••", t -> { c().sharedKey = t.trim(); return true; });
                key.masked = true;
                r.add(key);
                r.add(header("Privacy"));
                r.add(toggle("Recipe lookups", "Downloads crafting recipes from GitHub, one request per item you look at. Off sends nothing there but crafted-item values get less exact.", () -> c().recipeLookups, v -> c().recipeLookups = v));
                r.add(header("Diagnostics"));
                r.add(toggle("Record snapshots", "Saves auction snapshots to ValtrinData/replay (about 25 MB a day).", () -> c().record, v -> c().record = v));
                r.add(toggle("Poll log", "Writes ValtrinData/poll-log.tsv (diagnostic only).", () -> c().pageLog, v -> c().pageLog = v));
                r.add(header("Reset"));
                r.add(action("Default settings", "Puts every setting back to its default.", "Reset", () -> { FlipConfig.reset(); rebuildLater = true; }));
            }
        }
        rows = r;
        editing = null;
        clampScroll();
    }

    private boolean rebuildLater = false;

    private static FlipConfig c() { return FlipConfig.INSTANCE; }

    private void run(String command) {
        close(true);
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() != null) mc.getConnection().sendCommand(command);
    }

    // ------------------------------------------------------------------ closing

    private void close(boolean save) {
        if (editing != null) commit();
        if (save) {
            FlipConfig.save();
            if (!sharedUrlAtOpen.equals(c().sharedUrl) || !sharedKeyAtOpen.equals(c().sharedKey)) OwnModel.reconfigure();
            if (ValtrinMod.link != null) ValtrinMod.link.settingsChanged();
        }
        saved = true;
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    private void cancel() {
        FlipConfig.restore(undo);
        saved = true;
        Minecraft.getInstance().setScreenAndShow(parent);
    }

    @Override
    public void removed() {
        // closed some other way (the game took the screen away): keep what was changed
        if (!saved) FlipConfig.save();
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public boolean shouldCloseOnEsc() { return false; }

    // ------------------------------------------------------------------ editing

    private void startEdit(Row r) {
        if (editing != null) commit();
        editing = r;
        buf = new StringBuilder(r.masked ? "" : r.getS.get());
    }

    private void commit() {
        Row r = editing;
        editing = null;
        if (r == null) return;
        String text = buf.toString();
        if (r.masked && text.isEmpty() && !c().sharedKey.isEmpty()) return; // untouched key stays
        boolean ok;
        try { ok = r.setS.apply(text); } catch (RuntimeException e) { ok = false; }
        if (!ok) say("That isn't a valid value.");
        if (rebuildLater) { rebuildLater = false; build(); }
    }

    private void say(String t) { flash = t; flashUntil = System.currentTimeMillis() + 2500; }

    // ------------------------------------------------------------------ layout & drawing

    private void layout() {
        pw = Math.min(width - 16, 440);
        ph = Math.min(height - 16, 330);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        listTop = py + 52;
        listBottom = py + ph - 44;
    }

    private int contentHeight() { return rows.size() * ROW_H; }

    private void clampScroll() {
        int max = Math.max(0, contentHeight() - Math.max(40, listBottom - listTop));
        scroll = Math.max(0, Math.min(max, scroll));
    }

    /** Centred text. Dark text is drawn flat: Minecraft's text shadow turns it into a dirty smear on a bright button. */
    static void label(GuiGraphicsExtractor g, net.minecraft.client.gui.Font font, String text, int cx, int y, int color) {
        g.text(font, text, cx - font.width(text) / 2, y, color, color != 0xFF140F1F);
    }

    private static boolean in(double mx, double my, int x, int y, int w, int h) { return mx >= x && mx < x + w && my >= y && my < y + h; }

    private int tabWidth() { return (pw - 24) / TABS.length; }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float delta) {
        layout();
        clampScroll();
        if (rebuildLater && editing == null) { rebuildLater = false; build(); }
        g.fill(0, 0, width, height, 0xA0000000);
        g.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, PURPLE);
        g.fill(px, py, px + pw, py + ph, PANEL);

        // header
        g.text(font, "Valtrin", px + 12, py + 9, GOLD, true);
        g.text(font, "settings", px + 12 + font.width("Valtrin ") , py + 9, WHITE, true);
        String ver = "v" + FlipConfig.VERSION;
        g.text(font, ver, px + pw - 12 - font.width(ver), py + 9, DIM, true);
        g.fill(px + 8, py + 22, px + pw - 8, py + 23, LINE);

        // tabs
        int tw = tabWidth();
        for (int i = 0; i < TABS.length; i++) {
            int x = px + 12 + i * tw;
            boolean on = i == tab, hov = in(mx, my, x, py + 27, tw - 3, 18);
            g.fill(x, py + 27, x + tw - 3, py + 45, on ? PURPLE : hov ? 0xFF2A2142 : PANEL2);
            label(g, font, TABS[i], x + (tw - 3) / 2, py + 32, on ? 0xFF140F1F : hov ? WHITE : GREY);
        }

        // list
        g.enableScissor(px + 4, listTop, px + pw - 4, listBottom);
        Row hovered = null;
        int ctrlX = px + pw - 16 - CTRL_W;
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            int y = listTop + i * ROW_H - scroll;
            if (y + ROW_H < listTop || y > listBottom) continue;
            r.cx = ctrlX; r.cy = y + 2;
            boolean rowHover = in(mx, my, px + 8, y, pw - 16, ROW_H) && my >= listTop && my < listBottom;
            switch (r.kind) {
                case HEADER -> {
                    g.text(font, r.label.toUpperCase(Locale.ROOT), px + 12, y + 9, GOLD, true);
                    g.fill(px + 12 + font.width(r.label.toUpperCase(Locale.ROOT)) + 6, y + 13, px + pw - 14, y + 14, LINE);
                }
                case NOTE -> g.text(font, r.label, px + 12, y + 7, DIM, true);
                default -> {
                    if (rowHover) { g.fill(px + 8, y, px + pw - 8, y + ROW_H - 2, 0x33B77CFF); hovered = r; }
                    g.text(font, r.label, px + 14, y + 7, WHITE, true);
                    drawControl(g, r, ctrlX, y + 2, mx, my);
                }
            }
        }
        g.disableScissor();

        // scroll bar
        int vis = listBottom - listTop;
        if (contentHeight() > vis) {
            int bh = Math.max(16, vis * vis / contentHeight());
            int by = listTop + (int) ((long) (vis - bh) * scroll / Math.max(1, contentHeight() - vis));
            g.fill(px + pw - 6, listTop, px + pw - 4, listBottom, FIELD);
            g.fill(px + pw - 6, by, px + pw - 4, by + bh, PURPLE);
        }

        // footer: hint or message, then buttons
        g.fill(px + 8, listBottom + 2, px + pw - 8, listBottom + 3, LINE);
        String line; int col;
        if (System.currentTimeMillis() < flashUntil) { line = flash; col = RED; }
        else if (hovered != null) { line = hovered.hint; col = GREY; }
        else { line = "Changes apply at once. Done saves, Cancel undoes."; col = DIM; }
        g.text(font, trim(line, pw - 24), px + 12, listBottom + 8, col, true);
        button(g, "Done", px + pw - 12 - 70, py + ph - 24, 70, 16, true, mx, my);
        button(g, "Cancel", px + pw - 12 - 70 - 6 - 60, py + ph - 24, 60, 16, false, mx, my);
    }

    private String trim(String s, int maxW) {
        if (font.width(s) <= maxW) return s;
        while (s.length() > 3 && font.width(s + "...") > maxW) s = s.substring(0, s.length() - 1);
        return s + "...";
    }

    private void button(GuiGraphicsExtractor g, String text, int x, int y, int w, int h, boolean primary, int mx, int my) {
        boolean hov = in(mx, my, x, y, w, h);
        g.fill(x, y, x + w, y + h, primary ? (hov ? 0xFFFFD866 : GOLD) : (hov ? 0xFF3A2D5C : PANEL2));
        label(g, font, text, x + w / 2, y + 4, primary ? 0xFF140F1F : WHITE);
    }

    private void drawControl(GuiGraphicsExtractor g, Row r, int x, int y, int mx, int my) {
        int h = ROW_H - 6;
        boolean hov = in(mx, my, x, y, CTRL_W, h);
        switch (r.kind) {
            case TOGGLE -> {
                boolean on = r.getB.get();
                g.fill(x + CTRL_W - 40, y, x + CTRL_W, y + h, on ? 0xFF2F7F5A : PANEL2);
                g.fill(on ? x + CTRL_W - 20 : x + CTRL_W - 40, y, on ? x + CTRL_W : x + CTRL_W - 20, y + h, on ? GREEN : DIM);
                label(g, font, on ? "ON" : "OFF", on ? x + CTRL_W - 30 : x + CTRL_W - 10, y + 4, 0xFF140F1F);
            }
            case FIELD -> {
                boolean ed = editing == r;
                g.fill(x, y, x + CTRL_W, y + h, ed ? PURPLE : LINE);
                g.fill(x + 1, y + 1, x + CTRL_W - 1, y + h - 1, FIELD);
                String t = ed ? buf + ((System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "") : r.getS.get();
                if (!ed && t.isEmpty()) t = r.masked ? "not set" : "click to type";
                g.text(font, trim(t, CTRL_W - 8), x + 4, y + 4, ed ? WHITE : (r.getS.get().isEmpty() ? DIM : GOLD), true);
            }
            case CYCLE -> {
                g.fill(x, y, x + CTRL_W, y + h, hov ? 0xFF3A2D5C : PANEL2);
                g.centeredText(font, r.getS.get() + "  ▸", x + CTRL_W / 2, y + 4, GOLD);
            }
            case ACTION -> {
                g.fill(x, y, x + CTRL_W, y + h, hov ? PURPLE : 0xFF4A3A78);
                label(g, font, r.button, x + CTRL_W / 2, y + 4, hov ? 0xFF140F1F : WHITE);
            }
            default -> {}
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
        if (e.button() != 0) return false;
        double mx = e.x(), my = e.y();
        int tw = tabWidth();
        for (int i = 0; i < TABS.length; i++) {
            if (in(mx, my, px + 12 + i * tw, py + 27, tw - 3, 18)) {
                if (editing != null) commit();
                tab = i; scroll = 0; build();
                return true;
            }
        }
        if (in(mx, my, px + pw - 12 - 70, py + ph - 24, 70, 16)) { close(true); return true; }
        if (in(mx, my, px + pw - 12 - 70 - 6 - 60, py + ph - 24, 60, 16)) { cancel(); return true; }

        Row hit = null;
        if (my >= listTop && my < listBottom) {
            for (Row r : rows) {
                if (r.kind == Kind.HEADER || r.kind == Kind.NOTE) continue;
                if (in(mx, my, r.cx, r.cy, CTRL_W, ROW_H - 6)) { hit = r; break; }
            }
        }
        if (editing != null && editing != hit) commit();
        if (hit == null) return true;
        switch (hit.kind) {
            case TOGGLE -> hit.setB.accept(!hit.getB.get());
            case FIELD -> startEdit(hit);
            case CYCLE, ACTION -> hit.run.run();
            default -> {}
        }
        if (rebuildLater && editing == null) { rebuildLater = false; build(); }
        return true;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double sx, double sy) {
        scroll -= (int) Math.signum(sy) * 22;
        clampScroll();
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent e) {
        int k = e.key();
        if (editing != null) {
            if (k == 257 || k == 335) { commit(); return true; }          // Enter
            if (k == 256) { editing = null; return true; }                  // Esc: drop the edit
            if (k == 259) { if (buf.length() > 0) buf.setLength(buf.length() - 1); return true; }
            if (k == 86 && (e.modifiers() & (2 | 8)) != 0) { paste(); return true; } // Ctrl+V (Cmd+V on Mac)
            return true;
        }
        if (k == 256) { close(true); return true; }
        return super.keyPressed(e);
    }

    /** Pastes the clipboard into the field being edited: first line only, no control characters, length capped. */
    private void paste() {
        String clip;
        try {
            clip = Minecraft.getInstance().keyboardHandler.getClipboard();
        } catch (RuntimeException ex) {
            return;
        }
        if (clip == null) return;
        int nl = clip.indexOf('\n');
        if (nl >= 0) clip = clip.substring(0, nl);
        StringBuilder in = new StringBuilder();
        for (int i = 0; i < clip.length() && buf.length() + in.length() < 200; i++) {
            char ch = clip.charAt(i);
            if (ch >= 32 && ch != 127) in.append(ch);
        }
        buf.append(in.toString().strip());
    }

    @Override
    public boolean charTyped(CharacterEvent e) {
        if (editing == null) return false;
        String s = e.codepointAsString();
        if (!s.isEmpty() && s.charAt(0) >= 32 && s.charAt(0) != 127 && buf.length() < 200) buf.append(s);
        return true;
    }
}
