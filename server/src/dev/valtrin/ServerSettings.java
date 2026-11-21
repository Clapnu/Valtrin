// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/** Settings of the server program, read from config/valtrin-server.properties (made with defaults if missing). */
final class ServerSettings {
    /** Address to listen on. Keep 127.0.0.1 when a front web server (Caddy) provides HTTPS. */
    final String bind;
    final int port;
    /** Delay for free keys, in ms. */
    final long freeDelayMs;
    /** Premium keys get no delay only when this is true. Leave false until Hypixel has approved selling. */
    final boolean premiumEnabled;
    final int maxSessions;
    final int maxFailsPerMinute;
    /** Public https address of this server, e.g. https://api.valtrin.app (needed for "Sign in with Discord"). */
    final String publicUrl;
    /** The one website address allowed to read /v1/flips from a browser (empty = no browser access). */
    final String siteOrigin;
    final String discordClientId;
    /** Optional: only members of this Discord server may get a key. */
    final String discordGuildId;
    /** Optional: the Discord server where plan roles are given, and the ids of the Free and Premium roles. */
    final String discordRoleGuildId;
    final String discordFreeRoleId;
    final String discordPremiumRoleId;
    final int discordMinAccountDays;
    /** Discord's web address; only changed by the tests. */
    final String discordApiBase;
    /** Shown on the public project page, if set (for example a contact email). */
    final String contactEmail;
    /** The /redeem page (invite codes) is available. */
    final boolean redeemEnabled;
    final boolean discordBotOnline;
    /** Most keys one client address can make through /redeem per day. */
    final int redeemPerAddressPerDay;

    private ServerSettings(Properties p) {
        bind = p.getProperty("bind", "127.0.0.1").trim();
        port = intIn(p, "port", 8080, 1, 65535);
        freeDelayMs = intIn(p, "freeDelaySeconds", 7, 0, 3600) * 1000L;
        premiumEnabled = Boolean.parseBoolean(p.getProperty("premiumEnabled", "false").trim());
        maxSessions = intIn(p, "maxSessions", 300, 1, 5000);
        maxFailsPerMinute = intIn(p, "maxFailsPerMinute", 20, 1, 10_000);
        publicUrl = p.getProperty("publicUrl", "").trim().replaceAll("/+$", "");
        String so = p.getProperty("siteOrigin", "https://valtrin.app").trim().replaceAll("/+$", "");
        siteOrigin = so.matches("https://[a-z0-9.-]{3,100}") ? so : "";
        discordClientId = p.getProperty("discordClientId", "").trim();
        discordGuildId = p.getProperty("discordGuildId", "").trim();
        discordRoleGuildId = p.getProperty("discordRoleGuildId", "").trim();
        discordFreeRoleId = p.getProperty("discordFreeRoleId", "").trim();
        discordPremiumRoleId = p.getProperty("discordPremiumRoleId", "").trim();
        discordMinAccountDays = intIn(p, "discordMinAccountDays", 7, 0, 3650);
        discordApiBase = p.getProperty("discordApiBase", "https://discord.com/api").trim().replaceAll("/+$", "");
        String ce = p.getProperty("contactEmail", "").trim();
        contactEmail = ce.matches("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,100}\\.[A-Za-z]{2,}") ? ce : "";
        redeemEnabled = Boolean.parseBoolean(p.getProperty("redeemEnabled", "true").trim());
        discordBotOnline = Boolean.parseBoolean(p.getProperty("discordBotOnline", "true").trim());
        redeemPerAddressPerDay = intIn(p, "redeemPerAddressPerDay", 5, 1, 1000);
    }

    private static int intIn(Properties p, String k, int def, int min, int max) {
        try {
            int v = Integer.parseInt(p.getProperty(k, String.valueOf(def)).trim());
            return v < min || v > max ? def : v;
        } catch (NumberFormatException e) {
            return def;
        }
    }

    static ServerSettings load(Path configDir) {
        Path f = configDir.resolve("valtrin-server.properties");
        Properties p = new Properties();
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            } catch (IOException e) {
                Log.LOGGER.warn("Could not read {}: {}", f, e.toString());
            }
        } else {
            try {
                Files.createDirectories(configDir);
                Files.writeString(f, """
                        # Valtrin server settings
                        # Address to listen on. Keep 127.0.0.1 when Caddy (HTTPS) sits in front.
                        bind=127.0.0.1
                        port=8080
                        # Delay for free keys, in seconds.
                        freeDelaySeconds=7
                        # Premium keys get 0 delay only when this is true. Nothing is sold until it fits the rules of Hypixel and Mojang.
                        premiumEnabled=false
                        maxSessions=300
                        maxFailsPerMinute=20
                        # Invite-code page (https://your-domain/redeem) and how many keys one address may make per day.
                        redeemEnabled=true
                        # "Sign in with Discord" (see server/README.txt): your public https address, the application's
                        # client id, optionally your Discord server's id (only its members get keys), and the minimum account age.
                        # The application's secret goes in config/discord-secret.txt, never in this file.
                        publicUrl=
                        # Shown on the public project page at the server address (optional).
                        contactEmail=
                        # The one website address allowed to read /v1/flips from a browser. Empty turns browser access off.
                        siteOrigin=https://valtrin.app
                        discordClientId=
                        discordGuildId=
                        # Optional: give people in your Discord server a Free Plan / Premium Plan role that matches their key.
                        # Needs config/discord-bot-token.txt (a bot with the Manage Roles permission). Never required to use Valtrin.
                        discordRoleGuildId=
                        discordFreeRoleId=
                        discordPremiumRoleId=
                        # Show the bot as online in Discord (uses the same bot token file). Needs no extra permissions.
                        discordBotOnline=true
                        discordMinAccountDays=7
                        redeemPerAddressPerDay=5
                        """);
            } catch (IOException ignored) { /* defaults are fine */ }
        }
        return new ServerSettings(p);
    }
}
