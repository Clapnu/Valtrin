// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * /auctions, /link and /notify, plus the buttons under the picture, the profile suggestions and the sale DMs.
 * Everything is built from public data (see {@link AuctionIndex}). Nothing here ever uses a Hypixel API key.
 */
final class AuctionCommands {
    static final int PAGE = 10;
    private static final Pattern ID = Pattern.compile("[0-9]{5,25}");
    private static final Pattern IX_TOKEN = Pattern.compile("[A-Za-z0-9._-]{20,300}");
    private static final Pattern HEX = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PROF = Pattern.compile("[0-9a-f]{6,32}|all");
    private static final String[] SORTS = {"end", "price_hi", "price_lo", "rarity"};
    private static final String[] SORT_LABEL = {"Ending soonest", "Price: high to low", "Price: low to high", "Rarity"};

    private final ServerSettings cfg;
    private final String token;
    private final String base;
    private final String mojang;
    private final AuctionIndex index;
    private final NotifyStore store;
    private final Function<Collection<String>, Map<String, String>> names;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ConcurrentHashMap<String, long[]> lastUse = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> uuidCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> dmChannels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, long[]> dmCount = new ConcurrentHashMap<>();
    private volatile boolean imageOk = true;
    /** At most two pictures are drawn at once, so a rush of /auctions can never take CPU from the alert stream. */
    private final java.util.concurrent.Semaphore drawing = new java.util.concurrent.Semaphore(2);

    AuctionCommands(ServerSettings cfg, String token, AuctionIndex index, NotifyStore store,
                    Function<Collection<String>, Map<String, String>> names, String base, String mojang) {
        this.cfg = cfg; this.token = token; this.index = index; this.store = store; this.names = names;
        this.base = base; this.mojang = mojang;
        index.onSale(this::onSale);
    }

    static Function<Collection<String>, Map<String, String>> mojangNames() {
        return u -> SellerNames.lookupAll(u, 1500);
    }

    void checkImage() {
        imageOk = AuctionImage.available();
        Log.LOGGER.info(imageOk ? "Auction pictures: ready" : "Auction pictures: this machine cannot draw text (install fontconfig and fonts-dejavu-core); /auctions will answer in text");
    }

    // ------------------------------------------------------------------ command definitions

    static JsonArray definitions() {
        JsonArray a = new JsonArray();
        JsonObject au = cmd("auctions", "Show someone's SkyBlock auction house (yours if you leave the name empty)");
        JsonArray o = new JsonArray();
        JsonObject u = opt("username", "Minecraft name (leave empty for your linked account)", false);
        u.addProperty("min_length", 1); u.addProperty("max_length", 16);
        o.add(u);
        JsonObject p = opt("profile", "Only show one SkyBlock profile", false);
        p.addProperty("autocomplete", true);
        o.add(p);
        au.add("options", o);
        a.add(au);
        JsonObject ln = cmd("link", "Link your Minecraft name so /auctions and /notify know who you are");
        JsonArray lo = new JsonArray();
        JsonObject n = opt("name", "Your Minecraft name", true);
        n.addProperty("min_length", 1); n.addProperty("max_length", 16);
        lo.add(n);
        ln.add("options", lo);
        a.add(ln);
        a.add(cmd("notify", "Turn DMs on or off for when one of your auctions sells"));
        a.add(cmd("unlink", "Forget your linked Minecraft name"));
        return a;
    }

    private static JsonObject cmd(String name, String desc) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name); o.addProperty("description", desc); o.addProperty("type", 1);
        return o;
    }

    private static JsonObject opt(String name, String desc, boolean required) {
        JsonObject o = new JsonObject();
        o.addProperty("name", name); o.addProperty("description", desc); o.addProperty("type", 3); o.addProperty("required", required);
        return o;
    }

    // ------------------------------------------------------------------ interactions

    /** @return true when the interaction was one of ours. */
    boolean handle(JsonObject d) {
        try {
            int type = d.get("type").getAsInt();
            String id = d.get("id").getAsString(), itok = d.get("token").getAsString();
            if (!ID.matcher(id).matches() || !IX_TOKEN.matcher(itok).matches()) return false;
            JsonObject data = d.has("data") && d.get("data").isJsonObject() ? d.getAsJsonObject("data") : null;
            if (data == null) return false;
            String user = userId(d);
            if (user == null) return false;
            if (type == 2) {
                String name = str(data, "name");
                switch (name) {
                    case "auctions": auctions(id, itok, user, data); return true;
                    case "link": link(id, itok, user, data); return true;
                    case "notify": notify(id, itok, user); return true;
                    case "unlink": store.unlink(user); ephemeral(id, itok, "Done. Your linked Minecraft name and sale DMs are removed."); return true;
                    default: return false;
                }
            }
            if (type == 4) {
                if ("auctions".equals(str(data, "name"))) { autocomplete(id, itok, data); return true; }
                return false;
            }
            if (type == 3) {
                String cid = str(data, "custom_id");
                if (cid.startsWith("au:") || cid.startsWith("as:")) { component(id, itok, user, cid, data, d); return true; }
                if (cid.equals("nt:off")) { notifyOff(id, itok, user); return true; }
            }
            return false;
        } catch (RuntimeException e) {
            Log.LOGGER.warn("An auction command failed: {}", e.toString());
            return false;
        }
    }

    /** Who ran the command a message belongs to (null when Discord does not say). */
    private static String ownerOf(JsonObject d) {
        try {
            JsonObject m = d.has("message") && d.get("message").isJsonObject() ? d.getAsJsonObject("message") : null;
            if (m == null) return null;
            for (String k : new String[]{"interaction_metadata", "interaction"}) {
                if (m.has(k) && m.get(k).isJsonObject() && m.getAsJsonObject(k).has("user") && m.getAsJsonObject(k).get("user").isJsonObject()) {
                    String id = str(m.getAsJsonObject(k).getAsJsonObject("user"), "id");
                    if (ID.matcher(id).matches()) return id;
                }
            }
        } catch (RuntimeException ignored) {}
        return null;
    }

    private static String userId(JsonObject d) {
        JsonObject u = null;
        if (d.has("member") && d.get("member").isJsonObject() && d.getAsJsonObject("member").has("user")) u = d.getAsJsonObject("member").getAsJsonObject("user");
        else if (d.has("user") && d.get("user").isJsonObject()) u = d.getAsJsonObject("user");
        String id = u == null ? null : str(u, "id");
        return id != null && ID.matcher(id).matches() ? id : null;
    }

    private static String option(JsonObject data, String name) {
        if (!data.has("options") || !data.get("options").isJsonArray()) return "";
        for (JsonElement e : data.getAsJsonArray("options")) {
            JsonObject o = e.getAsJsonObject();
            if (name.equals(str(o, "name")) && o.has("value")) return o.get("value").getAsString().strip();
        }
        return "";
    }

    // ---- /link and /notify

    private void link(String id, String itok, String user, JsonObject data) {
        String name = option(data, "name");
        if (!Validate.player(name)) { ephemeral(id, itok, "That is not a valid Minecraft name."); return; }
        if (rateLimited("l" + user, 3000)) { ephemeral(id, itok, "One moment, then try again."); return; }
        defer(id, itok, true);
        Thread.startVirtualThread(() -> {
            String[] r = resolve(name);
            if (r == null) { followup(itok, "I could not find a Minecraft player called **" + name + "**. Check the spelling and try again."); return; }
            store.link(user, r[0], r[1]);
            followup(itok, "Linked to **" + r[1] + "**. `/auctions` now shows your auction house, and `/notify` turns on a DM when one of your auctions sells.\n"
                    + "Valtrin only reads public auction data, so it cannot check that the name is yours. Link your own name.");
        });
    }

    private void notify(String id, String itok, String user) {
        NotifyStore.Link l = store.get(user);
        if (l == null) { ephemeral(id, itok, "Link your Minecraft name first with `/link`."); return; }
        if (l.dms()) {
            store.setNotify(user, false);
            ephemeral(id, itok, "Sale DMs are **off**. Turn them back on any time with `/notify`.");
            return;
        }
        defer(id, itok, true);
        Thread.startVirtualThread(() -> {
            JsonObject msg = new JsonObject();
            msg.addProperty("content", "Sale DMs are on for **" + l.name() + "**. I will message you here when one of your auctions sells.");
            msg.add("components", offRow());
            if (dm(user, msg)) {
                store.setNotify(user, true);
                followup(itok, "Sale DMs are **on** for **" + l.name() + "**. I sent you a message to check it works.");
            } else {
                followup(itok, "I could not send you a DM. In Discord, open Privacy Settings for this server and allow direct messages from server members, then run `/notify` again.");
            }
        });
    }

    private void notifyOff(String id, String itok, String user) {
        store.setNotify(user, false);
        JsonObject data = new JsonObject();
        data.addProperty("content", "Sale DMs are **off**. Turn them back on with `/notify`.");
        data.add("components", new JsonArray());
        respond(id, itok, 7, data);
    }

    private static JsonArray offRow() {
        JsonArray rows = new JsonArray();
        JsonObject row = new JsonObject();
        row.addProperty("type", 1);
        JsonArray c = new JsonArray();
        c.add(button("nt:off", "Turn off sale DMs", 4, false));
        row.add("components", c);
        rows.add(row);
        return rows;
    }

    // ---- /auctions

    private void auctions(String id, String itok, String user, JsonObject data) {
        String name = option(data, "username"), profile = option(data, "profile");
        if (!profile.isEmpty() && !PROF.matcher(profile.toLowerCase(Locale.ROOT)).matches()) profile = "";
        final String prof = profile.isEmpty() ? "all" : profile.toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            NotifyStore.Link l = store.get(user);
            if (l == null) { ephemeral(id, itok, "Type a Minecraft name, or run `/link` once so `/auctions` knows who you are."); return; }
            name = l.name();
        } else if (!Validate.player(name)) { ephemeral(id, itok, "That is not a valid Minecraft name."); return; }
        if (rateLimited("a" + user, 4000)) { ephemeral(id, itok, "One moment, then try again."); return; }
        defer(id, itok, false);
        final String who = name;
        Thread.startVirtualThread(() -> {
            try {
                String[] r = resolve(who);
                if (r == null) { followup(itok, "I could not find a Minecraft player called **" + who + "**."); return; }
                Page pg = build(r[0], r[1], prof, 0, "end");
                if (pg == null) { followup(itok, "**" + r[1] + "** has nothing listed and nothing sold in the last 36 hours that I could see."); return; }
                editOriginal(itok, pg, false);
            } catch (RuntimeException e) {
                Log.LOGGER.warn("/auctions failed: {}", e.toString());
                followup(itok, "Something went wrong reading that auction house. Try again in a moment.");
            }
        });
    }

    private void component(String id, String itok, String user, String cid, JsonObject data, JsonObject d) {
        String[] p = cid.split(":");
        // au:<uuid>:<page>:<sort>:<profile>   as:<uuid>:<profile>  (the sort menu; value = sort)
        try {
            String uuid = p[1];
            if (!HEX.matcher(uuid).matches()) return;
            String prof, sort = "end";
            int page = 0;
            if (p[0].equals("au")) {
                page = Integer.parseInt(p[2]);
                sort = p[3];
                prof = p[4];
            } else {
                prof = p[2];
                if (data.has("values") && data.getAsJsonArray("values").size() > 0) sort = data.getAsJsonArray("values").get(0).getAsString();
            }
            if (!PROF.matcher(prof).matches() || !java.util.Arrays.asList(SORTS).contains(sort) || page < 0 || page > 500) return;
            String owner = ownerOf(d);
            if (owner != null && !owner.equals(user)) { ephemeral(id, itok, "Only the person who ran this can use its buttons. Run `/auctions` yourself to get your own."); return; }
            if (rateLimited("c" + user, 1500)) { respond(id, itok, 6, null); return; }
            respond(id, itok, 6, null); // acknowledge now, edit the message next
            final String f1 = sort, f2 = prof;
            final int f3 = page;
            Thread.startVirtualThread(() -> {
                try {
                    String nm = nameOf(uuid);
                    Page pg = build(uuid, nm, f2, f3, f1);
                    if (pg != null) editOriginal(itok, pg, true);
                } catch (RuntimeException e) {
                    Log.LOGGER.warn("An auction button failed: {}", e.toString());
                }
            });
        } catch (RuntimeException e) {
            // malformed custom id: ignore
        }
    }

    private void autocomplete(String id, String itok, JsonObject data) {
        JsonArray choices = new JsonArray();
        try {
            String name = option(data, "username");
            String hit = name.isEmpty() ? null : uuidCache.get(name.toLowerCase(Locale.ROOT));
            String uuid = hit == null ? null : hit.substring(0, hit.indexOf(' '));
            if (uuid != null) {
                int i = 1;
                for (Map.Entry<String, Integer> e : index.profiles(uuid).entrySet()) {
                    if (choices.size() >= 8) break;
                    JsonObject c = new JsonObject();
                    c.addProperty("name", "Profile " + i++ + " (" + e.getValue() + " listed)");
                    c.addProperty("value", e.getKey().replace("-", "").toLowerCase(Locale.ROOT));
                    choices.add(c);
                }
            }
            if (choices.isEmpty()) {
                JsonObject c = new JsonObject();
                c.addProperty("name", "All profiles");
                c.addProperty("value", "all");
                choices.add(c);
            }
        } catch (RuntimeException ignored) {}
        JsonObject d = new JsonObject();
        d.add("choices", choices);
        respond(id, itok, 8, d);
    }

    // ------------------------------------------------------------------ building a page

    record Page(byte[] png, String text, int page, int pages, String uuid, String prof, String sort) {}

    /** @return null when the player has nothing to show. */
    Page build(String uuid, String name, String prof, int page, String sort) {
        long now = System.currentTimeMillis() + HypixelApi.clockOffsetMs;
        List<AuctionIndex.Listing> ls = new ArrayList<>();
        for (AuctionIndex.Listing l : index.listings(uuid)) if (matches(prof, l.profile)) ls.add(l);
        List<AuctionIndex.Sale> ss = new ArrayList<>();
        for (AuctionIndex.Sale s : index.sales(uuid)) if (matches(prof, s.profile())) ss.add(s);
        if (ls.isEmpty() && ss.isEmpty()) return null;

        // group identical listings
        Map<String, List<AuctionIndex.Listing>> groups = new LinkedHashMap<>();
        for (AuctionIndex.Listing l : ls) groups.computeIfAbsent(l.name + "|" + l.tier + "|" + l.stars + "|" + l.price + "|" + l.bin, k -> new ArrayList<>()).add(l);
        List<List<AuctionIndex.Listing>> gl = new ArrayList<>(groups.values());
        Comparator<List<AuctionIndex.Listing>> cmp;
        switch (sort) {
            case "price_hi": cmp = Comparator.comparingLong((List<AuctionIndex.Listing> g) -> g.get(0).price).reversed(); break;
            case "price_lo": cmp = Comparator.comparingLong((List<AuctionIndex.Listing> g) -> g.get(0).price); break;
            case "rarity": cmp = Comparator.comparingInt((List<AuctionIndex.Listing> g) -> rank(g.get(0).tier)).reversed().thenComparingLong(g -> g.get(0).price); break;
            default: cmp = Comparator.comparingLong((List<AuctionIndex.Listing> g) -> minEnd(g)); break;
        }
        gl.sort(cmp);

        // sold first (newest first), same item grouped by the buyer when there is one
        Map<String, String> buyerNames = names.apply(ss.stream().limit(30).map(AuctionIndex.Sale::buyer).filter(Validate::uuid).toList());
        List<AuctionImage.Card> cards = new ArrayList<>();
        long soldTotal = 0;
        for (AuctionIndex.Sale s : ss) soldTotal += s.price();
        for (AuctionIndex.Sale s : ss) {
            String b = buyerNames.get(s.buyer());
            cards.add(new AuctionImage.Card(s.name(), s.tier(), s.stars(), s.master(), s.price(), 1, b == null ? "sold" : "→ " + b, 0, true));
        }
        for (List<AuctionIndex.Listing> g : gl) {
            AuctionIndex.Listing f = g.get(0);
            long end = minEnd(g);
            long left = end - now;
            int stars = Math.min(5, f.stars), master = Math.max(0, f.stars - 5);
            String line = f.bin ? left(left) : "bid · " + left(left);
            cards.add(new AuctionImage.Card(f.name, f.tier, stars, Math.min(5, master), f.price, g.size(), line,
                    left < 3_600_000L ? 2 : left < 6 * 3_600_000L ? 1 : 0, false));
        }
        int pages = Math.max(1, (cards.size() + PAGE - 1) / PAGE);
        int pg = Math.min(Math.max(0, page), pages - 1);
        List<AuctionImage.Card> slice = cards.subList(pg * PAGE, Math.min(cards.size(), pg * PAGE + PAGE));
        String foot = "Updated just now · page " + (pg + 1) + " / " + pages;
        byte[] png = null;
        if (imageOk) {
            boolean got = false;
            try {
                got = drawing.tryAcquire(2, java.util.concurrent.TimeUnit.SECONDS);
                if (got) png = AuctionImage.render(name, foot, ls.size(), ss.size(), soldTotal, slice);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Throwable t) {
                // one odd item must not switch pictures off for everyone: only stop if this machine really cannot draw
                Log.LOGGER.warn("Drawing the auction picture failed, answering this one in text: {}", t.toString());
                if (!AuctionImage.available()) imageOk = false;
            } finally {
                if (got) drawing.release();
            }
        }
        return new Page(png, textFor(name, ls.size(), ss.size(), soldTotal, slice, foot), pg, pages, uuid, prof, sort);
    }

    static String textFor(String name, int listed, int sold, long soldTotal, List<AuctionImage.Card> cards, String foot) {
        StringBuilder sb = new StringBuilder("**").append(name).append("**: ").append(listed).append(" listed, ").append(sold).append(" sold recently (")
                .append(Format.compact(soldTotal)).append(")\n");
        for (AuctionImage.Card c : cards) {
            sb.append(c.sold() ? "✅ ~~" : "• ").append(esc(c.name())).append(c.stars() > 0 ? " " + "✪".repeat(c.stars()) : "")
                    .append(c.count() > 1 ? " ×" + c.count() : "").append(c.sold() ? "~~" : "").append(" — **").append(Format.compact(c.price()))
                    .append("**").append(c.count() > 1 ? " each" : "").append(" — ").append(c.line()).append('\n');
        }
        return sb.append("_").append(foot).append("_").toString();
    }

    private static boolean matches(String prof, String profileId) {
        if (prof == null || prof.equals("all")) return true;
        return profileId != null && profileId.replace("-", "").toLowerCase(Locale.ROOT).startsWith(prof);
    }

    private static long minEnd(List<AuctionIndex.Listing> g) {
        long m = Long.MAX_VALUE;
        for (AuctionIndex.Listing l : g) m = Math.min(m, l.end);
        return m;
    }

    private static int rank(String tier) {
        String[] order = {"COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY_SPECIAL", "ULTIMATE", "SUPREME"};
        for (int i = 0; i < order.length; i++) if (order[i].equalsIgnoreCase(tier)) return i;
        return 0;
    }

    /** Item names come from other players, so before they go into Discord text: no markdown, no clickable links, one line. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < 32 || c == 127) { b.append(' '); continue; }
            if ("\\*_~`|>[]()<#@:".indexOf(c) >= 0) b.append('\\');
            b.append(c);
        }
        return b.toString().replace("://", ":\u200b//").replaceAll("\\.(?=[A-Za-z])", ".\u200b");
    }

    static String left(long ms) {
        if (ms <= 0) return "ended";
        long m = ms / 60_000L;
        if (m < 60) return Math.max(1, m) + "m left";
        long h = m / 60;
        if (h < 24) return h + "h " + (m % 60) + "m left";
        return (h / 24) + "d " + (h % 24) + "h left";
    }

    // ------------------------------------------------------------------ sale DMs

    private void onSale(AuctionIndex.Sale s) {
        if (!store.anyWatching()) return;
        List<String> who = store.watchers(s.seller());
        if (who.isEmpty()) return;
        Thread.startVirtualThread(() -> {
            for (String user : who) {
                long[] c = dmCount.computeIfAbsent(user, k -> new long[]{0, 0});
                long now = System.currentTimeMillis();
                synchronized (c) {
                    if (now - c[0] > 3_600_000L) { c[0] = now; c[1] = 0; }
                    if (++c[1] > 30) continue; // never more than 30 DMs an hour to one person
                }
                Map<String, String> nm = names.apply(Validate.uuid(s.buyer()) ? List.of(s.buyer()) : List.of());
                String buyer = nm.get(s.buyer());
                JsonObject embed = new JsonObject();
                embed.addProperty("title", "Auction sold");
                embed.addProperty("color", 0x3ba55d);
                String item = esc(s.name()) + (s.stars() > 0 ? " " + "✪".repeat(s.stars()) : "") + (s.master() > 0 ? String.valueOf((char) ('➉' + s.master())) : "");
                embed.addProperty("description", "**" + item + "**\nSold for **" + String.format(Locale.ROOT, "%,d", s.price()) + "** coins"
                        + (buyer != null ? " to **" + buyer + "**" : "") + ".\nClaim it from the Auction House in game. `/auctions` shows the rest.");
                JsonObject msg = new JsonObject();
                JsonArray embeds = new JsonArray();
                embeds.add(embed);
                msg.add("embeds", embeds);
                msg.add("components", offRow());
                dm(user, msg);
            }
        });
    }

    boolean dm(String userId, JsonObject message) {
        try {
            String ch = dmChannels.get(userId);
            if (ch == null) {
                JsonObject body = new JsonObject();
                body.addProperty("recipient_id", userId);
                String r = postJson("/users/@me/channels", body.toString(), true);
                if (r == null) return false;
                ch = str(JsonParser.parseString(r).getAsJsonObject(), "id");
                if (!ID.matcher(ch).matches()) return false;
                dmChannels.put(userId, ch);
            }
            message.add("allowed_mentions", noMentions());
            return postJson("/channels/" + ch + "/messages", message.toString(), true) != null;
        } catch (Exception e) {
            return false;
        }
    }

    private String postJson(String path, String json, boolean bot) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json));
        if (bot) b.header("Authorization", "Bot " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() >= 200 && r.statusCode() < 300 ? r.body() : null;
    }

    // ------------------------------------------------------------------ names

    private volatile String fallbackBase = "https://api.minecraftservices.com";
    void fallbackBase(String b) { fallbackBase = b; }

    /** name -> {uuid, exact name}. Capital letters do not matter. Tries what is remembered, then Mojang's two public lookups, then names seen as sellers. */
    String[] resolve(String name) {
        if (!Validate.player(name)) return null;
        String key = name.toLowerCase(Locale.ROOT);
        String hit = uuidCache.get(key);
        if (hit != null) { int t = hit.indexOf(' '); return new String[]{hit.substring(0, t), hit.substring(t + 1)}; }
        String[] r = lookup(mojang + "/users/profiles/minecraft/" + name);
        if (r == null) r = lookup(fallbackBase + "/minecraft/profile/lookup/name/" + name);
        if (r == null) r = SellerNames.byName(name);
        if (r == null) return null;
        uuidCache.put(key, r[0] + " " + r[1]);
        if (uuidCache.size() > 20_000) uuidCache.clear();
        return r;
    }

    private String[] lookup(String url) {
        try {
            HttpRequest rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).header("User-Agent", "Valtrin/1.0").GET().build();
            HttpResponse<java.io.InputStream> r = http.send(rq, HttpResponse.BodyHandlers.ofInputStream());
            if (r.statusCode() != 200) { r.body().close(); return null; }
            byte[] raw;
            try (java.io.InputStream in = r.body()) { raw = in.readNBytes(4097); }
            if (raw.length > 4096) return null;
            JsonObject o = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8)).getAsJsonObject();
            String id = str(o, "id"), nm = str(o, "name");
            return Validate.uuid(id) && Validate.player(nm) ? new String[]{id, nm} : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String nameOf(String uuid) {
        String n = SellerNames.cachedName(uuid);
        if (n != null) return n;
        for (Map.Entry<String, String> e : uuidCache.entrySet()) if (e.getValue().startsWith(uuid + " ")) return e.getValue().substring(uuid.length() + 1);
        return "Player";
    }

    private boolean rateLimited(String user, long ms) {
        long now = System.currentTimeMillis();
        long[] t = lastUse.computeIfAbsent(user, k -> new long[]{0});
        synchronized (t) {
            if (now - t[0] < ms) return true;
            t[0] = now;
        }
        if (lastUse.size() > 50_000) lastUse.clear();
        return false;
    }

    // ------------------------------------------------------------------ Discord replies

    private void respond(String id, String itok, int type, JsonObject data) {
        try {
            JsonObject body = new JsonObject();
            body.addProperty("type", type);
            if (data != null) body.add("data", data);
            HttpRequest rq = HttpRequest.newBuilder(URI.create(base + "/interactions/" + id + "/" + itok + "/callback")).timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
            http.send(rq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Log.LOGGER.warn("Could not answer a Discord interaction: {}", e.toString());
        }
    }

    private void ephemeral(String id, String itok, String text) {
        JsonObject d = new JsonObject();
        d.addProperty("content", text);
        d.addProperty("flags", 64);
        d.add("allowed_mentions", noMentions());
        respond(id, itok, 4, d);
    }

    private void defer(String id, String itok, boolean priv) {
        JsonObject d = new JsonObject();
        if (priv) d.addProperty("flags", 64);
        respond(id, itok, 5, d);
    }

    private void followup(String itok, String text) {
        try {
            JsonObject d = new JsonObject();
            d.addProperty("content", text);
            d.add("allowed_mentions", noMentions());
            HttpRequest rq = HttpRequest.newBuilder(URI.create(base + "/webhooks/" + cfg.discordClientId + "/" + itok + "/messages/@original"))
                    .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/json")
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(d.toString())).build();
            http.send(rq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Log.LOGGER.warn("Could not send a Discord follow-up: {}", e.toString());
        }
    }

    private void editOriginal(String itok, Page pg, boolean update) {
        try {
            JsonObject d = new JsonObject();
            d.addProperty("content", pg.png() == null ? pg.text() : "");
            d.add("allowed_mentions", noMentions());
            d.add("components", controls(pg));
            if (pg.png() != null) {
                JsonArray att = new JsonArray();
                JsonObject a = new JsonObject();
                a.addProperty("id", 0);
                a.addProperty("filename", "auctions.png");
                att.add(a);
                d.add("attachments", att);
            } else {
                d.add("attachments", new JsonArray());
            }
            String url = base + "/webhooks/" + cfg.discordClientId + "/" + itok + "/messages/@original";
            HttpRequest rq;
            if (pg.png() != null) {
                String boundary = "valtrin" + UUID.randomUUID().toString().replace("-", "");
                ByteArrayOutputStream out = new ByteArrayOutputStream(pg.png().length + 1024);
                out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"payload_json\"\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(d.toString().getBytes(StandardCharsets.UTF_8));
                out.write(("\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"files[0]\"; filename=\"auctions.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(pg.png());
                out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .method("PATCH", HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build();
            } else {
                rq = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(d.toString())).build();
            }
            http.send(rq, HttpResponse.BodyHandlers.discarding());
        } catch (Exception e) {
            Log.LOGGER.warn("Could not send the auction house: {}", e.toString());
        }
    }

    private static JsonArray controls(Page pg) {
        String tail = ":" + pg.sort() + ":" + pg.prof();
        JsonArray rows = new JsonArray();
        JsonObject r1 = new JsonObject();
        r1.addProperty("type", 1);
        JsonArray b = new JsonArray();
        b.add(button("au:" + pg.uuid() + ":" + Math.max(0, pg.page() - 1) + tail + ":p", "\u25c0", 2, pg.page() <= 0));
        b.add(button("au:" + pg.uuid() + ":" + pg.page() + tail + ":r", "Refresh", 2, false));
        b.add(button("au:" + pg.uuid() + ":" + Math.min(pg.pages() - 1, pg.page() + 1) + tail + ":n", "\u25b6", 2, pg.page() >= pg.pages() - 1));
        r1.add("components", b);
        rows.add(r1);
        JsonObject r2 = new JsonObject();
        r2.addProperty("type", 1);
        JsonObject sel = new JsonObject();
        sel.addProperty("type", 3);
        sel.addProperty("custom_id", "as:" + pg.uuid() + ":" + pg.prof());
        sel.addProperty("placeholder", "Sort: " + SORT_LABEL[java.util.Arrays.asList(SORTS).indexOf(pg.sort())]);
        JsonArray opts = new JsonArray();
        for (int i = 0; i < SORTS.length; i++) {
            JsonObject o = new JsonObject();
            o.addProperty("label", SORT_LABEL[i]);
            o.addProperty("value", SORTS[i]);
            opts.add(o);
        }
        sel.add("options", opts);
        JsonArray sc = new JsonArray();
        sc.add(sel);
        r2.add("components", sc);
        rows.add(r2);
        return rows;
    }

    private static JsonObject button(String id, String label, int style, boolean disabled) {
        JsonObject o = new JsonObject();
        o.addProperty("type", 2);
        o.addProperty("style", style);
        o.addProperty("label", label);
        o.addProperty("custom_id", id);
        if (disabled) o.addProperty("disabled", true);
        return o;
    }

    private static JsonObject noMentions() {
        JsonObject none = new JsonObject();
        none.add("parse", new JsonArray());
        return none;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : "";
    }
}
