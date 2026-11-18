// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The bot's two slash commands, /valtrin (links to the website, the free key and the mod) and /status (is the server up,
 * how many flips were found in the last hour, the free plan's delay). Both answer only the person who typed them, show
 * nothing about any person, and need no extra Discord permissions. The commands are registered once each time the
 * server starts. Running a slash command from your own application is also what Discord's Active Developer badge asks for.
 */
final class DiscordCommands {
    static final String API = "https://discord.com/api/v10";
    private static final Pattern ID = Pattern.compile("[0-9]{5,25}");
    private static final Pattern IX_TOKEN = Pattern.compile("[A-Za-z0-9._-]{20,300}");

    private final ServerSettings cfg;
    private final String token;
    private final String base;
    private final Supplier<String> status;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private boolean registered;
    private volatile AuctionCommands auctions;

    /** Adds /auctions, /link and /notify (and their buttons). */
    void useAuctions(AuctionCommands a) { this.auctions = a; }

    DiscordCommands(ServerSettings cfg, String token, Supplier<String> status) {
        this(cfg, token, status, API);
    }

    DiscordCommands(ServerSettings cfg, String token, Supplier<String> status, String base) {
        this.cfg = cfg;
        this.token = token;
        this.status = status;
        this.base = base;
    }

    boolean usable() { return !token.isEmpty() && ID.matcher(cfg.discordClientId).matches(); }

    /** The command list sent to Discord. */
    static JsonArray definitions() {
        JsonArray a = new JsonArray();
        a.add(command("valtrin", "Links to the Valtrin website, a free key and the mod download"));
        a.add(command("status", "Is the Valtrin server up, and how many flips it found in the last hour"));
        for (JsonElement e : AuctionCommands.definitions()) a.add(e);
        return a;
    }

    private static JsonObject command(String name, String description) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name);
        o.addProperty("description", description);
        o.addProperty("type", 1);
        return o;
    }

    /** The text of the answer to a command, or null for a command that is not ours. */
    String reply(String name) {
        if ("valtrin".equals(name)) {
            StringBuilder sb = new StringBuilder("**Valtrin**: flip alerts for Hypixel SkyBlock. Alerts only: it never buys or clicks for you.\n");
            if (!cfg.siteOrigin.isEmpty()) {
                sb.append("Website: <").append(cfg.siteOrigin).append(">\n");
                sb.append("Mod download: <").append(cfg.siteOrigin).append("/#mod>\n");
                sb.append("Set it up: <").append(cfg.siteOrigin).append("/#account>\n");
            }
            if (!cfg.publicUrl.isEmpty()) sb.append("Get a free key: <").append(cfg.publicUrl).append("/redeem>\n");
            return sb.toString().strip();
        }
        if ("status".equals(name)) {
            String s;
            try { s = status.get(); } catch (RuntimeException e) { s = null; }
            return s == null || s.isBlank() ? "The Valtrin server is online." : s;
        }
        return null;
    }

    /** Tells Discord which commands exist. Done once per start; a failure only means the commands will not show. */
    void register() {
        synchronized (this) {
            if (registered || !usable()) return;
            registered = true;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/applications/" + cfg.discordClientId + "/commands"))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bot " + token).header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(definitions().toString())).build();
            int code = http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (code == 200) Log.LOGGER.info("Discord slash commands are registered (/valtrin, /status, /auctions, /link and /notify)");
            else Log.LOGGER.warn("Could not register the Discord slash commands (Discord answered {})", code);
        } catch (Exception e) {
            Log.LOGGER.warn("Could not register the Discord slash commands: {}", e.toString());
        }
    }

    /** An INTERACTION_CREATE event from the gateway. Answers a slash command of ours, ignores everything else. */
    void onInteraction(JsonObject d) {
        try {
            AuctionCommands ac = auctions;
            if (d != null && ac != null && d.has("type") && ac.handle(d)) return;
            if (d == null || !d.has("type") || d.get("type").getAsInt() != 2) return; // 2 = a slash command
            String id = d.get("id").getAsString(), itok = d.get("token").getAsString();
            if (!ID.matcher(id).matches() || !IX_TOKEN.matcher(itok).matches()) return;
            JsonElement data = d.get("data");
            if (data == null || !data.isJsonObject() || !data.getAsJsonObject().has("name")) return;
            String text = reply(data.getAsJsonObject().get("name").getAsString());
            if (text == null) return;
            JsonObject msg = new JsonObject();
            msg.addProperty("content", text);
            msg.addProperty("flags", 64); // only the person who ran it sees it
            JsonObject none = new JsonObject();
            none.add("parse", new JsonArray());
            msg.add("allowed_mentions", none);
            JsonObject body = new JsonObject();
            body.addProperty("type", 4);
            body.add("data", msg);
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/interactions/" + id + "/" + itok + "/callback"))
                    .timeout(Duration.ofSeconds(8)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            http.send(req, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Log.LOGGER.warn("A Discord slash command could not be answered: {}", e.toString());
        }
    }
}
