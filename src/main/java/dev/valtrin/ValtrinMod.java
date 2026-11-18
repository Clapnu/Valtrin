// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ValtrinMod implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("valtrin");

    // The engine logs through Log so it can also run without Minecraft; inside the game that goes to the normal log.
    static {
        Log.LOGGER = new Log.Out() {
            public void debug(String f, Object... a) { LOGGER.debug(f, a); }
            public void info(String f, Object... a) { LOGGER.info(f, a); }
            public void warn(String f, Object... a) { LOGGER.warn(f, a); }
            public void error(String f, Object... a) { LOGGER.error(f, a); }
        };
    }

    /** An alert and the time it was made, so alerts that waited too long (loading screen, menu) can be dropped. */
    public record TimedAlert(Component msg, long at, long profit) {
        public TimedAlert(Component msg, long at) { this(msg, at, 0L); }
    }

    /** Alerts older than this when the player can finally see them are dropped (the listing is long gone). */
    private static final long MAX_ALERT_AGE_MS = 30_000;

    /** Alerts produced by the background engine; drained on the client thread. */
    public static final Queue<TimedAlert> ALERTS = new ConcurrentLinkedQueue<>();

    /** Updated every tick so the background thread knows whether to poll. */
    public static volatile boolean onHypixel = false;

    public static FlipEngine engine;
    static ServerLink link;

    /** True while the Valtrin server is delivering the alerts (this computer is then not searching Hypixel itself). */
    static boolean serverConnected() {
        ServerLink l = link;
        return l != null && l.connected();
    }

    /** The "open next flip" key. Change it in Options > Controls > Valtrin. */
    private static KeyMapping nextFlipKey;
    private static KeyMapping settingsKey;
    /** Set by /flip menu: the settings screen opens on the next tick (after chat has closed). */
    public static volatile boolean openSettingsNext = false;
    private static int welcomeDelay = 0;
    private static volatile long chatClosedAt = 0;
    private static volatile boolean returnToChat = false;
    /** Menus opened by the open-next-flip key (Z) are not given chat back when they close: you go straight back to the game. */
    private static volatile long flipKeyMenuUntil = 0;
    private static long lastOpenAt = 0;
    private static long lastEmptyNoticeAt = 0;
    private static boolean upgradeNoticePending = false;
    /** True while any screen (menu, chat, auction GUI) is open. Tracked with Fabric events instead of reading Minecraft.screen, whose field is not the same in every Minecraft version (it crashed on 26.2). */
    private static volatile boolean screenOpen = false;
    private static boolean keyErrorLogged = false;
    private static boolean keyWasDown = false;
    private static int chimeSecondTicks = 0;

    /** Volume of the normal chime (it was 0.45; twice as loud now). */
    private static final float CHIME_VOLUME = 0.90f;

    /** The jackpot sound for big flips: assets/valtrin/sounds/jackpot.ogg (eight fast alternating dings). */
    private static final SoundEvent JACKPOT = SoundEvent.createVariableRangeEvent(Identifier.fromNamespaceAndPath("valtrin", "jackpot"));

    /** Soft bell note on the "Jukebox/Note Blocks" volume slider (SoundSource.RECORDS). */
    private static void playNote(Minecraft client, float pitch) {
        try {
            var p = client.player;
            if (p == null || client.level == null) return;
            client.level.playLocalSound(p.getX(), p.getY(), p.getZ(),
                    SoundEvents.NOTE_BLOCK_HARP.value(), SoundSource.RECORDS, CHIME_VOLUME, pitch, false);
        } catch (Throwable t) {
            try { client.player.playSound(SoundEvents.NOTE_BLOCK_HARP.value(), CHIME_VOLUME, pitch); } catch (Throwable ignored) {}
        }
    }

    /** Plays the jackpot sound (same volume slider as the chime). Also used by the settings screen to preview it. */
    public static void playJackpot(Minecraft client) {
        try {
            var p = client.player;
            if (p == null || client.level == null) return;
            client.level.playLocalSound(p.getX(), p.getY(), p.getZ(), JACKPOT, SoundSource.RECORDS, 1.0f, 1.0f, false);
        } catch (Throwable ignored) {
            // a missing sound must never break the alerts
        }
    }

    /** Plays the normal two-note chime (also used by the settings screen to preview it). */
    public static void playChime(Minecraft client) {
        playNote(client, 1.0f);
        chimeSecondTicks = 3;
    }

    /**
     * Opens the auction of the most profitable flip that was alerted and is still fresh (runs /viewauction, the same as
     * clicking the alert). One flip per call; nothing is bought.
     * @param manual true when the player asked with /flip next (a "nothing waiting" notice is always shown then)
     */
    public static void openNextFlip(Minecraft client, boolean manual) {
        if (client == null || client.player == null || client.getConnection() == null) return;
        long now = System.currentTimeMillis();
        FlipQueue.Item item = FlipQueue.next(now);
        if (item == null) {
            lastOpenAt = now;
            if (manual || now - lastEmptyNoticeAt > 3_000) {
                lastEmptyNoticeAt = now;
                client.player.sendSystemMessage(FlipAlerts.notice("No flips waiting."));
            }
            return;
        }
        lastOpenAt = now;
        flipKeyMenuUntil = now + 6000; // this menu is a flip: closing it goes back to the game, not to chat
        returnToChat = false;
        if (!Validate.uuid(item.uuid())) return; // only a real auction id is ever put into a command
        client.getConnection().sendCommand("viewauction " + item.uuid());
        int left = FlipQueue.size(now);
        client.player.sendSystemMessage(FlipAlerts.opened(item.name().replaceAll("§.", ""), item.profit(), left));
    }

    @Override
    public void onInitializeClient() {
        FlipConfig.load();
        upgradeNoticePending = FlipConfig.INSTANCE.configVersion < FlipConfig.CURRENT_VERSION;

        // Key to open the next flip. The player can rebind or unbind it in Options > Controls (category "Valtrin").
        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("valtrin", "flips"));
        nextFlipKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.valtrin.next_flip", InputConstants.Type.KEYSYM, InputConstants.KEY_Z, category));

        settingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.valtrin.settings", InputConstants.Type.KEYSYM, InputConstants.KEY_PERIOD, category));

        VolumeTracker volume = new VolumeTracker();
        volume.load();
        ModAlertSink alertSink = new ModAlertSink(ALERTS);
        engine = new FlipEngine(volume, alertSink);
        // The server link gets its own sink that never drops anything; the local engine's sink drops its alerts while the server delivers them.
        link = new ServerLink(new ModAlertSink(ALERTS));
        alertSink.setLink(link);
        Market.serverMode = ValtrinMod::serverConnected;
        engine.start();
        link.start();

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            screenOpen = true;
            SellerClick.register(screen);
            long nowMs = System.currentTimeMillis();
            if (screen instanceof net.minecraft.client.gui.screens.ChatScreen) {
                returnToChat = false; // chat was opened again by the player: nothing to give back
                ScreenEvents.remove(screen).register(s -> { screenOpen = false; chatClosedAt = System.currentTimeMillis(); });
            } else {
                // a menu (auction house...) that opened right after chat closed: remember to give chat back when it closes
                if (screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
                        && nowMs - chatClosedAt < 1500 && nowMs > flipKeyMenuUntil) returnToChat = true;
                ScreenEvents.remove(screen).register(s -> screenOpen = false);
            }
        });

        RecipeData.init(java.nio.file.Path.of(System.getProperty("user.home"), "ValtrinData"));
        FlipStats.init(java.nio.file.Path.of(System.getProperty("user.home"), "ValtrinData"));
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (overlay || !onHypixel) return; // other servers can't feed the profit stats
            try {
                java.util.List<String> texts = new java.util.ArrayList<>();
                java.util.List<Integer> rgbs = new java.util.ArrayList<>();
                message.visit((style, text) -> {
                    texts.add(text);
                    rgbs.add(style.getColor() == null ? null : style.getColor().getValue());
                    return java.util.Optional.empty();
                }, net.minecraft.network.chat.Style.EMPTY);
                FlipStats.noteColors(texts, rgbs);
                FlipStats.onChat(message.getString(), System.currentTimeMillis());
            } catch (Throwable ignored) {
                // stats must never affect the game
            }
        });

        FlipCommands.register();

        Market.volume = volume;
        AhOverlay.register();

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            ServerData server = client.getCurrentServer();
            // Not on the alpha server: it has its own separate economy, so main-server prices would give wrong flips.
            onHypixel = server != null && server.ip != null
                    && server.ip.toLowerCase().contains("hypixel.net")
                    && !server.ip.toLowerCase().startsWith("alpha.");

            if (client.player == null) {
                // No player (loading screen / disconnected): throw away alerts that are already too old to be useful.
                long cut = System.currentTimeMillis() - MAX_ALERT_AGE_MS;
                TimedAlert head;
                while ((head = ALERTS.peek()) != null && head.at() < cut) ALERTS.poll();
                return;
            }

            if (upgradeNoticePending) {
                upgradeNoticePending = false;
                client.player.sendSystemMessage(V.title("Updated", "v" + FlipConfig.CURRENT_VERSION));
                client.player.sendSystemMessage(V.rule());
                for (String line : FlipConfig.upgradeNotice(FlipConfig.INSTANCE.configVersion)) client.player.sendSystemMessage(V.t(line, V.GREY));
                FlipConfig.INSTANCE.configVersion = FlipConfig.CURRENT_VERSION;
                FlipConfig.save();
            }

            try { FlipStats.flushTrade(System.currentTimeMillis()); } catch (Throwable ignored) {}

            // Closing a menu that was opened from chat (a seller's AH, a flip) brings chat back instead of the game view,
            // so the mouse stays free. Only the chat screen is reopened; nothing is typed, sent or clicked.
            if (returnToChat && !screenOpen) {
                returnToChat = false;
                if (FlipConfig.INSTANCE.returnToChat && onHypixel) {
                    client.setScreenAndShow(new net.minecraft.client.gui.screens.ChatScreen("", false));
                    return;
                }
            }

            // Settings key / /flip menu, and the welcome window once per version (a couple of seconds after joining a world).
            try {
                boolean wantSettings = openSettingsNext;
                while (settingsKey.consumeClick()) { if (!screenOpen) wantSettings = true; }
                if (wantSettings) {
                    openSettingsNext = false;
                    client.setScreenAndShow(new SettingsScreen(null));
                } else if (!screenOpen && !FlipConfig.VERSION.equals(FlipConfig.INSTANCE.welcomeSeen)) {
                    if (++welcomeDelay >= 60) {
                        FlipConfig.INSTANCE.welcomeSeen = FlipConfig.VERSION;
                        FlipConfig.save();
                        client.setScreenAndShow(new WelcomeScreen(null));
                    }
                }
            } catch (Throwable t) {
                LOGGER.warn("Settings/welcome screen failed", t);
            }

            // Open-next-flip key. Windows sends repeated key presses while a key is held and Minecraft counts every one
            // as a new click, so clicks alone would fire many times a second. Instead: one flip on the moment the key
            // goes down, then (only if bindhold is on) one every bindrepeat milliseconds while it stays down.
            // Never let a problem here take the game down.
            try {
                boolean clicked = false;
                while (nextFlipKey.consumeClick()) clicked = true;
                boolean down = nextFlipKey.isDown();
                long nowMs = System.currentTimeMillis();
                if (!keyWasDown && (clicked || down)) {
                    openNextFlip(client, false);
                } else if (FlipConfig.INSTANCE.bindHold && down && !screenOpen
                        && nowMs - lastOpenAt >= Math.max(300, FlipConfig.INSTANCE.bindRepeatMs)) {
                    openNextFlip(client, false);
                }
                keyWasDown = down;
            } catch (Throwable t) {
                if (!keyErrorLogged) {
                    keyErrorLogged = true;
                    LOGGER.error("Open-next-flip key failed (further errors are not logged)", t);
                }
            }

            int sent = 0;
            long bestProfit = 0;
            TimedAlert msg;
            long cut = System.currentTimeMillis() - MAX_ALERT_AGE_MS;
            // second note of the chime, a few ticks after the first
            if (chimeSecondTicks > 0 && --chimeSecondTicks == 0) playNote(client, 1.498f);
            while (sent < 10 && (msg = ALERTS.poll()) != null) {
                if (msg.at() < cut) continue; // too old: the flip is surely gone
                client.player.sendSystemMessage(msg.msg());
                bestProfit = Math.max(bestProfit, msg.profit());
                sent++;
            }
            // one sound per batch, so several alerts in the same tick don't stack into noise;
            // a big flip in the batch plays the jackpot instead of the chime
            if (sent > 0 && FlipConfig.INSTANCE.soundEnabled
                    && !FlipConfig.INSTANCE.isQuiet(java.time.LocalTime.now().getHour())) {
                FlipConfig fc = FlipConfig.INSTANCE;
                if (fc.jackpotSound && fc.jackpotMinProfit > 0 && bestProfit >= fc.jackpotMinProfit) {
                    playJackpot(client);
                } else {
                    playChime(client);
                }
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { link.stop(); engine.stop(); });

        LOGGER.info("Valtrin loaded. Use /flip for commands.");
    }
}
