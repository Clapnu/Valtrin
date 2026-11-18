// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Keeps the Valtrin Discord bot showing as "online" (with a "Watching flip alerts" status) by holding Discord's gateway
 * connection open. It sends only the standard handshake (identify and heartbeat) with no privileged intents, never reads
 * messages, and never posts anything. The token is the same file the plan roles use (config/discord-bot-token.txt) and
 * is sent only to Discord. Switch it off with discordBotOnline=false in the settings file.
 */
final class DiscordGateway {
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._-]{30,120}");
    private static final String URL = "wss://gateway.discord.gg/?v=10&encoding=json";

    /** Where frames go; replaced in tests. */
    interface Out {
        void send(String text);
        void close();
    }

    private final String token;
    private final boolean wanted;
    private final String url;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService ex;
    private volatile boolean fatal;
    private volatile boolean online;
    private int failures;

    DiscordGateway(ServerSettings cfg, Path configDir) {
        this(cfg, configDir, URL);
    }

    DiscordGateway(ServerSettings cfg, Path configDir, String url) {
        String t = "";
        try {
            Path f = configDir.resolve("discord-bot-token.txt");
            if (Files.isRegularFile(f)) t = Files.readString(f, StandardCharsets.UTF_8).trim();
        } catch (java.io.IOException e) {
            Log.LOGGER.warn("Could not read the Discord bot token file: {}", e.toString());
        }
        this.token = TOKEN.matcher(t).matches() ? t : "";
        this.wanted = cfg.discordBotOnline;
        this.url = url;
    }

    private volatile DiscordCommands commands;

    /** Adds the slash commands (answered through the same connection). */
    void useCommands(DiscordCommands c) { this.commands = c; }

    String token() { return token; }

    boolean enabled() { return wanted && !token.isEmpty(); }
    boolean online() { return online; }

    void start() {
        if (!enabled() || !running.compareAndSet(false, true)) return;
        ex = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "valtrin-gateway");
            t.setDaemon(true);
            return t;
        });
        ex.execute(this::connect);
        Log.LOGGER.info("Discord bot presence is on (shows the bot as online)");
    }

    void stop() {
        running.set(false);
        if (ex != null) ex.shutdownNow();
    }

    // ------------------------------------------------------------------ one connection

    /** The state of one gateway connection. All the protocol rules live here so they can be tested without a network. */
    final class Conn {
        private final Out out;
        private final ScheduledExecutorService timer;
        private ScheduledFuture<?> beat;
        volatile boolean ended;
        private Long seq;
        private boolean acked = true;
        boolean identified;

        Conn(Out out, ScheduledExecutorService timer) {
            this.out = out;
            this.timer = timer;
        }

        /** One text frame from Discord. */
        void onFrame(String text) {
            JsonObject m;
            try {
                JsonElement e = JsonParser.parseString(text);
                if (!e.isJsonObject()) return;
                m = e.getAsJsonObject();
            } catch (RuntimeException ex2) {
                return;
            }
            int op = m.has("op") && m.get("op").isJsonPrimitive() ? m.get("op").getAsInt() : -1;
            if (m.has("s") && m.get("s").isJsonPrimitive()) seq = m.get("s").getAsLong();
            switch (op) {
                case 10 -> { // Hello: start the heartbeat, then say who we are
                    long every = 41_250;
                    try {
                        every = Math.max(5_000, Math.min(120_000, m.getAsJsonObject("d").get("heartbeat_interval").getAsLong()));
                    } catch (RuntimeException ignored) { /* default */ }
                    final long ms = every;
                    beat = timer.scheduleAtFixedRate(this::heartbeat, (long) (ms * Math.random()), ms, TimeUnit.MILLISECONDS);
                    out.send(identify(token));
                    identified = true;
                }
                case 11 -> acked = true;                 // heartbeat acknowledged
                case 1 -> out.send(beatFrame());         // Discord asked for a heartbeat right now
                case 7, 9 -> end("Discord asked to reconnect");
                case 0 -> {
                    String t = m.has("t") && m.get("t").isJsonPrimitive() ? m.get("t").getAsString() : "";
                    if (t.equals("READY")) {
                        online = true;
                        failures = 0;
                        Log.LOGGER.info("Discord bot is online");
                        DiscordCommands c = commands;
                        if (c != null && c.usable()) Thread.startVirtualThread(c::register);
                    } else if (t.equals("INTERACTION_CREATE")) {
                        DiscordCommands c = commands;
                        try {
                            JsonObject d = m.getAsJsonObject("d");
                            if (c != null && d != null) Thread.startVirtualThread(() -> c.onInteraction(d));
                        } catch (RuntimeException ignored) { /* not an object */ }
                    }
                }
                default -> { /* nothing else matters */ }
            }
        }

        private void heartbeat() {
            if (ended) return;
            if (!acked) { end("no heartbeat answer"); return; } // the connection died quietly
            acked = false;
            out.send(beatFrame());
        }

        private String beatFrame() {
            return "{\"op\":1,\"d\":" + (seq == null ? "null" : seq.toString()) + "}";
        }

        void end(String why) {
            if (ended) return;
            ended = true;
            online = false;
            if (beat != null) beat.cancel(false);
            Log.LOGGER.info("Discord gateway closed: {}", why);
            try { out.close(); } catch (RuntimeException ignored) { /* already closed */ }
        }
    }

    /** The identify frame. Public for tests. No intents are requested, so nothing about messages or members is received. */
    static String identify(String token) {
        JsonObject d = new JsonObject();
        d.addProperty("token", token);
        d.addProperty("intents", 0);
        JsonObject props = new JsonObject();
        props.addProperty("os", "linux");
        props.addProperty("browser", "valtrin");
        props.addProperty("device", "valtrin");
        d.add("properties", props);
        JsonObject act = new JsonObject();
        act.addProperty("name", "flip alerts");
        act.addProperty("type", 3); // "Watching"
        com.google.gson.JsonArray acts = new com.google.gson.JsonArray();
        acts.add(act);
        JsonObject pres = new JsonObject();
        pres.add("activities", acts);
        pres.addProperty("status", "online");
        pres.addProperty("afk", false);
        d.add("presence", pres);
        JsonObject m = new JsonObject();
        m.addProperty("op", 2);
        m.add("d", d);
        return m.toString();
    }

    /** Close codes after which retrying is pointless (bad token, bad intents...). */
    static boolean fatalClose(int code) {
        return code == 4004 || code == 4010 || code == 4011 || code == 4012 || code == 4013 || code == 4014;
    }

    // ------------------------------------------------------------------ network

    private void connect() {
        if (!running.get() || fatal) return;
        final WebSocket[] wsRef = new WebSocket[1];
        final Conn[] conn0 = new Conn[1];
        final Conn conn = conn0[0] = new Conn(new Out() {
            // One frame at a time: Java's WebSocket refuses a second send while one is still going out.
            @Override public void send(String text) {
                WebSocket w = wsRef[0];
                if (w == null) return;
                synchronized (wsRef) {
                    try {
                        w.sendText(text, true).get(8, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        conn0[0].end("send failed: " + e);
                    }
                }
            }
            @Override public void close() { WebSocket w = wsRef[0]; if (w != null) w.sendClose(1000, "bye"); }
        }, ex);
        final StringBuilder buf = new StringBuilder();
        try {
            http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), new WebSocket.Listener() {
                @Override public void onOpen(WebSocket w) { wsRef[0] = w; w.request(1); }
                @Override public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
                    buf.append(data);
                    if (buf.length() > 1_000_000) { buf.setLength(0); conn.end("frame too large"); return null; }
                    if (last) {
                        String s = buf.toString();
                        buf.setLength(0);
                        conn.onFrame(s);
                    }
                    w.request(1);
                    return null;
                }
                @Override public CompletionStage<?> onClose(WebSocket w, int code, String reason) {
                    if (fatalClose(code)) {
                        fatal = true;
                        Log.LOGGER.warn("Discord bot presence stopped: Discord closed the connection with code {} (check the bot token and settings)", code);
                    }
                    conn.end("close code " + code);
                    retry();
                    return null;
                }
                @Override public void onError(WebSocket w, Throwable t) {
                    conn.end("error " + t);
                    retry();
                }
            }).join();
        } catch (Exception e) {
            Log.LOGGER.warn("Discord gateway connect failed: {}", e.toString());
            retry();
        }
    }

    private void retry() {
        if (!running.get() || fatal) return;
        failures++;
        long wait = Math.min(300, 5L * (1L << Math.min(failures, 6)));
        try {
            ex.schedule(this::connect, wait, TimeUnit.SECONDS);
        } catch (RuntimeException ignored) { /* stopping */ }
    }
}
