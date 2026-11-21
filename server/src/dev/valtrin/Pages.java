// Copyright (c) 2026 Valtrin. All rights reserved. See LICENSE.
package dev.valtrin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

/** The few web pages the server shows (get a key, your key, errors). Plain HTML and CSS; one tiny script for the copy button. */
final class Pages {
    private Pages() {}

    /** The only script on any page (the copy button). Its hash is allowed by the content-security policy, nothing else may run. */
    static final String COPY_SCRIPT = "document.querySelectorAll('[data-copy]').forEach(function(b){b.addEventListener('click',function(){"
            + "var n=document.getElementById('k'),s=n.textContent.trim();"
            + "function pick(){var r=document.createRange();r.selectNodeContents(n);var g=getSelection();g.removeAllRanges();g.addRange(r);b.textContent='Press Ctrl+C'}"
            + "function ok(){b.textContent='Copied \\u2713';setTimeout(function(){b.textContent='Copy key'},2000)}"
            + "if(navigator.clipboard&&navigator.clipboard.writeText){navigator.clipboard.writeText(s).then(ok,pick)}else{pick()}})});";

    static final String SCRIPT_HASH;

    static {
        String h;
        try {
            h = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(COPY_SCRIPT.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        SCRIPT_HASH = h;
    }

    static final String CSP = "default-src 'none'; style-src 'unsafe-inline'; script-src 'sha256-" + SCRIPT_HASH
            + "'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'";

    static String esc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private static final String CSS = ":root{color-scheme:dark}*{box-sizing:border-box}"
            + "body{margin:0;min-height:100vh;font:16px/1.55 system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;color:#ece8f5;"
            + "background:radial-gradient(1200px 600px at 50% -10%,#2b1f4a 0%,#161122 55%,#100c19 100%) fixed;padding:28px 16px}"
            + "main{max-width:520px;margin:0 auto}"
            + ".brand{display:flex;align-items:center;gap:10px;justify-content:center;margin:2vh 0 22px;font-size:26px;font-weight:700;letter-spacing:.04em;color:#f5c542}"
            + ".brand span{font-size:22px;color:#8a63f0}"
            + ".card{background:rgba(255,255,255,.045);border:1px solid #33284f;border-radius:18px;padding:30px 28px;box-shadow:0 18px 50px rgba(0,0,0,.35)}"
            + "h1{margin:0 0 6px;font-size:24px;line-height:1.25;color:#fff}.sub{margin:0 0 22px;color:#b3a9cc}"
            + "h2{margin:26px 0 10px;font-size:15px;letter-spacing:.06em;text-transform:uppercase;color:#f5c542}"
            + ".btn{display:flex;align-items:center;justify-content:center;width:100%;padding:14px 20px;border-radius:12px;background:#5865f2;color:#fff;text-decoration:none;font-size:17px;font-weight:600;transition:background .15s,transform .05s}"
            + ".btn:hover{background:#6b77f5}.btn:active{transform:translateY(1px)}"
            + ".fine{margin:10px 0 0;text-align:center;color:#8f84ab;font-size:13px}"
            + ".or{display:flex;align-items:center;gap:12px;margin:24px 0 8px;color:#8f84ab;font-size:13px;text-transform:uppercase;letter-spacing:.1em}"
            + ".or:before,.or:after{content:'';flex:1;height:1px;background:#33284f}"
            + "details{margin-top:6px}summary{cursor:pointer;color:#cfc4ec;padding:8px 0;list-style:none}summary::-webkit-details-marker{display:none}"
            + "summary:before{content:'\\25B8';display:inline-block;width:1.2em;color:#8a63f0}details[open] summary:before{content:'\\25BE'}"
            + "label{display:block;margin:14px 0 6px;font-size:14px;color:#cfc4ec}"
            + "input{width:100%;padding:12px 14px;border-radius:10px;border:1px solid #4a3d6e;background:#1d1630;color:#fff;font-size:16px}"
            + "input:focus{outline:2px solid #8a63f0;outline-offset:1px;border-color:#8a63f0}input::placeholder{color:#6f6590}"
            + "button{font:inherit;cursor:pointer}.go{margin-top:18px;width:100%;padding:13px 18px;border:0;border-radius:12px;background:#8a63f0;color:#fff;font-size:16px;font-weight:600}.go:hover{background:#9a77f5}"
            + ".keybox{display:flex;gap:10px;align-items:stretch;margin:6px 0 4px}"
            + "#k{flex:1;min-width:0;margin:0;padding:14px;border:1px dashed #8a63f0;border-radius:12px;background:#1d1630;color:#f5c542;"
            + "font:15px/1.4 ui-monospace,SFMono-Regular,Menlo,Consolas,monospace;white-space:pre-wrap;overflow-wrap:anywhere;word-break:break-all;user-select:all}"
            + ".copy{padding:0 18px;border:0;border-radius:12px;background:#8a63f0;color:#fff;font-weight:600;white-space:nowrap}.copy:hover{background:#9a77f5}"
            + ".ok{display:inline-flex;align-items:center;justify-content:center;width:44px;height:44px;border-radius:50%;background:#1f7a4d;color:#fff;font-size:24px;margin-bottom:12px}"
            + ".note{margin:16px 0 0;padding:12px 14px;border-radius:10px;font-size:14px}"
            + ".warn{background:rgba(245,197,66,.1);border:1px solid rgba(245,197,66,.35);color:#f3dc92}"
            + ".msg{background:rgba(255,107,107,.1);border:1px solid rgba(255,107,107,.4);color:#ffb0b0}"
            + "ol{margin:0;padding:0;list-style:none;counter-reset:s}ol li{position:relative;padding:0 0 12px 38px;counter-increment:s;color:#d9d1ec}"
            + "ol li:before{content:counter(s);position:absolute;left:0;top:0;width:26px;height:26px;border-radius:50%;background:#2d2250;color:#f5c542;font-size:13px;font-weight:700;display:flex;align-items:center;justify-content:center}"
            + "code{background:#241b3b;padding:2px 6px;border-radius:6px;color:#f5c542;font-size:.92em}"
            + "ul.list{margin:0;padding:0 0 0 20px;color:#d9d1ec}ul.list li{margin:0 0 8px}.btn{max-width:320px}"
            + ".back{display:inline-block;margin-top:18px;color:#b9a6f5}"
            + "footer{margin:22px 4px 0;text-align:center;color:#7d729a;font-size:12.5px}"
            + "@media(max-width:420px){.card{padding:24px 18px}.keybox{flex-direction:column}.copy{padding:12px}}";

    static String page(String title, String body, boolean withScript) {
        return page(title, body, withScript, false);
    }

    static String page(String title, String body, boolean withScript, boolean wide) {
        return "<!doctype html><html lang=en><head><meta charset=utf-8><meta name=viewport content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>" + CSS + (wide ? "main{max-width:760px}" : "") + "</style></head><body><main>"
                + "<div class=brand><span>&#9670;</span>Valtrin</div>"
                + "<div class=card>" + body + "</div>"
                + "<footer>Valtrin is an independent project and is not affiliated with or endorsed by Hypixel Inc. or Mojang. "
                + "It only shows alerts and never buys anything for you.</footer>"
                + "</main>" + (withScript ? "<script>" + COPY_SCRIPT + "</script>" : "") + "</body></html>";
    }

    /** The "get your key" page. {@code discord}: show the Discord button. {@code message}: an error to show (may be empty). */
    static String getKey(boolean discord, String message) {
        StringBuilder b = new StringBuilder();
        b.append("<h1>Get your Valtrin key</h1><p class=sub>Free flip alerts for Hypixel SkyBlock. Sign in once, paste the key into the mod, done.</p>");
        if (!message.isEmpty()) b.append("<div class='note msg'>").append(esc(message)).append("</div><div style='height:14px'></div>");
        if (discord) {
            b.append("<a class=btn href=/login>Continue with Discord</a>")
                    .append("<p class=fine>One key per Discord account. Valtrin only reads your Discord ID.</p>")
                    .append("<div class=or>or</div><details").append(message.isEmpty() ? "" : " open").append("><summary>I have an invite code</summary>");
        }
        b.append("<form method=post action=/redeem autocomplete=off>"
                + "<label for=c>Invite code</label><input id=c name=code placeholder=INV-XXXX-XXXX-XXXX-XXXX maxlength=40 required>"
                + "<label for=n>Your name</label><input id=n name=name placeholder=\"for example your Minecraft name\" maxlength=30 required>"
                + "<button class=go type=submit>Get my key</button></form>");
        if (discord) b.append("</details>");
        return page("Get your Valtrin key", b.toString(), false);
    }

    /** The page that shows a new key once. {@code address}: the https address to enter in the mod. */
    static String yourKey(String key, String address, String extraNote) {
        return page("Your Valtrin key",
                "<div class=ok>&#10003;</div><h1>You're in</h1><p class=sub>Here is your key.</p>"
                        + "<div class=keybox><pre id=k>" + esc(key) + "</pre><button class=copy type=button data-copy>Copy key</button></div>"
                        + "<div class='note warn'><b>Copy it now.</b> It is shown only once. " + esc(extraNote) + "</div>"
                        + "<h2>Set it up in the game</h2><ol>"
                        + "<li>Open <code>/flip menu</code> and go to the <b>Advanced</b> tab.</li>"
                        + "<li>Set <b>Server address</b> to <code>" + esc(address) + "</code>.</li>"
                        + "<li>Click <b>Server key</b>, press <code>Ctrl+V</code> to paste, then press Enter.</li>"
                        + "<li>Turn on <b>Alerts from the server</b> and close the menu.</li></ol>"
                        + "<p class=fine style='text-align:left'>Keep your key private. It is yours alone.</p>", true);
    }

    /** The public project page shown at the server's address, for visitors and for Hypixel's reviewers. */
    static String home(String contactEmail) {
        String contact = contactEmail.isEmpty() ? "" : "<p>Contact: <code>" + esc(contactEmail) + "</code></p>";
        return page("Valtrin: SkyBlock flip alerts", ""
                + "<h1>Valtrin</h1><p class=sub>Auction House flip alerts for Hypixel SkyBlock, delivered to a Fabric mod.</p>"
                + "<p>Valtrin looks at Hypixel's public Auction House data, estimates what each listing is worth from past sales, and "
                + "alerts you in chat when something is listed well below that value. You look at the alert and decide whether to buy it yourself.</p>"
                + "<p><a class=btn href=/redeem>Get a free key</a></p>"
                + "<h2>How it works</h2><ol>"
                + "<li>This server reads Hypixel's public Auction House data once and records sold auctions.</li>"
                + "<li>It prices each new listing against that sales history and finds the ones worth a look.</li>"
                + "<li>The Valtrin mod receives those alerts over an encrypted connection and shows them in your chat, with the reasoning behind each one.</li></ol>"
                + "<h2>What it does not do</h2><ul class=list>"
                + "<li>It never buys, bids, clicks or sends commands for you. The only commands the mod can send to the game are opening an auction (/viewauction) or a seller's auctions (/ah), and only when you click an alert or a seller name, or press its key, yourself.</li>"
                + "<li>It does not read your Minecraft login or session, and it does not use or share any Hypixel API key.</li>"
                + "<li>It only presents public market information. Every decision, and every purchase, is made by the player.</li></ul>"
                + "<h2>Plans</h2><ul class=list>"
                + "<li><b>Free:</b> every alert, with a 7 second delay. Free keys are given to anyone who signs in with Discord.</li>"
                + "<li><b>Premium (planned):</b> the same alerts without the delay. It is not available yet. It will only be offered once it fits the rules of Hypixel and Mojang, and priced fairly.</li></ul>"
                + "<h2>Privacy</h2><ul class=list>"
                + "<li>To give each person one key, the server keeps their Discord account number. It does not keep Discord names, emails or passwords, and Discord login tokens are discarded right after use.</li>"
                + "<li>Keys are stored only as irreversible hashes.</li>"
                + "<li>Only if you turn it on in the mod (Premium only), the flips you complete are stored for your Flip Stats page: time, item, coins paid and received. You can delete them any time.</li>"
                + "<li>Server logs record connection times and IP addresses for security and abuse control. They are not shared or sold.</li></ul>"
                + contact
                + "<p class=fine style='text-align:left'>Valtrin is a client-side Fabric mod for Minecraft Java Edition. It is free to use. "
                + "<a href=/terms>Terms of Service</a> &middot; <a href=/privacy>Privacy Policy</a></p>",
                false, true);
    }

    private static String contactLine(String contactEmail) {
        return contactEmail.isEmpty() ? "" : "<p>Questions, or a request to delete your data: <code>" + esc(contactEmail) + "</code></p>";
    }

    /** Terms of Service, linked from the Discord application settings. Plain language, accurate to what the server does. */
    static String terms(String contactEmail) {
        return page("Valtrin: Terms of Service", ""
                + "<h1>Terms of Service</h1><p class=sub>Last updated 21 November 2026</p>"
                + "<p>These terms cover the Valtrin alert service (this server and the Valtrin mod). By getting a key or using the service you agree to them.</p>"
                + "<h2>What the service is</h2><p>Valtrin shows estimates of Hypixel SkyBlock Auction House flips. The estimates are information only. "
                + "They can be wrong or out of date, and nothing here is a promise of profit. You decide whether to buy, and you carry any loss.</p>"
                + "<h2>Your key</h2><ul class=list>"
                + "<li>A key is for one person. Do not share it, sell it or publish it. One key can be connected at a time.</li>"
                + "<li>Keep it private. Anyone who has it can use your connection.</li>"
                + "<li>You must meet Discord's minimum age to sign in with Discord.</li></ul>"
                + "<h2>What you agree not to do</h2><ul class=list>"
                + "<li>Do not attack, overload, scan or try to break the service, and do not try to get around its limits or delays.</li>"
                + "<li>Do not copy, resell or republish the alert stream, or use it to run a competing service.</li>"
                + "<li>Do not use it in a way that breaks Hypixel's or Minecraft's rules. Valtrin never plays the game for you; keep it that way.</li></ul>"
                + "<h2>Free and paid use</h2><p>Free use gives every alert with a 7 second delay. A paid option without the delay is planned and will be offered only "
                + "once it fits the rules of Hypixel and Mojang. Nothing is charged today.</p>"
                + "<h2>Changes, access and ending</h2><p>The service is provided as it is, with no promise that it will always be available or accurate. "
                + "Keys can be limited or cancelled, for example for abuse. The service or these terms may change; the date above shows the latest version. "
                + "To the extent the law allows, Valtrin is not liable for losses from using the service.</p>"
                + "<h2>Made with AI</h2><p>Valtrin is built by one person, with the help of AI tools (Anthropic's Claude) for writing and reviewing code. "
                + "It is tested, but it has not been audited by an outside party, and it may contain mistakes. Use it at your own risk.</p>"
                + "<h2>Not affiliated</h2><p>Valtrin is not affiliated with, endorsed by or connected to Hypixel Inc. or Mojang/Microsoft. Hypixel and Minecraft are trademarks of their owners.</p>"
                + contactLine(contactEmail)
                + "<p><a href=/>&larr; Back</a> &middot; <a href=/privacy>Privacy Policy</a></p>", false, true);
    }

    /** Privacy Policy, linked from the Discord application settings. Describes exactly what the server stores. */
    static String privacy(String contactEmail) {
        return page("Valtrin: Privacy Policy", ""
                + "<h1>Privacy Policy</h1><p class=sub>Last updated 18 November 2026</p>"
                + "<p>This explains what the Valtrin server keeps about you. The short version: very little.</p>"
                + "<h2>What we keep</h2><ul class=list>"
                + "<li><b>Discord account number</b> (only if you sign in with Discord) and the time your key was made. This lets us give one key per person. "
                + "We do not keep your Discord name, email, avatar, friends or servers, and we do not keep Discord login tokens.</li>"
                + "<li><b>Your key</b>, stored only as an irreversible hash, with its plan and whether it is active.</li>"
                + "<li>If you are also in the Valtrin Discord server, the server may give you a \"Free Plan\" or \"Premium Plan\" role there that matches your key. It does this using your Discord account number only, and joining the Discord server is never required.</li>"
                + "<li><b>Flip Stats (optional, off by default, Premium only).</b> Only if you turn on sharing in the mod (with /flip profit share on), each flip you complete is sent here and kept: "
                + "the time, the item name, the item's rarity (its colour), the coins you paid and the coins you received. Nothing else is sent with it. It is shown to you on the Valtrin website, kept until you delete it (the newest 20,000 flips are kept), "
                + "and you can delete all of it yourself at any time with the Delete button on the Flip Stats page. Turning sharing off stops it at once.</li>"
                + "<li><b>Linked Minecraft name (optional, Discord bot only).</b> Only if you run /link in the Valtrin Discord server: your Discord account number, the Minecraft name you gave and its player ID, and whether you turned on sale DMs with /notify. "
                + "It is used only to show your own auction house with /auctions and to send you a Discord message when one of your auctions sells. You can delete it any time with /unlink.</li>"
                + "<li><b>Security logs</b> with connection times and IP addresses, used to stop abuse and to fix problems. They are kept for a limited time.</li></ul>"
                + "<h2>What we do not keep</h2><ul class=list>"
                + "<li>Your Minecraft login, session or password. The mod does not send them to this server, and an in-game name is kept only if you give it to the Discord bot with /link. Your in-game activity is not sent either, apart from the optional Flip Stats above.</li>"
                + "<li>Your alert settings. Filters you choose in the mod are applied on your own computer.</li>"
                + "<li>Any Hypixel API key. The service does not use or collect one.</li></ul>"
                + "<h2>Auction house data</h2><p>The /auctions command shows what a player has listed or recently sold. This comes from Hypixel's public Auction House data, which anyone can read. The server keeps the last 36 hours of sales in memory only. It does not use any Hypixel API key.</p>"
                + "<h2>Discord sign-in</h2><p>When you sign in with Discord we ask only for your public account number (the identify permission). "
                + "Discord's own privacy policy applies to your Discord account.</p>"
                + "<h2>Who sees it</h2><p>Nobody else. We do not sell or share this information or use it for advertising. "
                + "The server is hosted on a rented virtual server; it is handled only by the person who runs Valtrin, except where the law requires otherwise.</p>"
                + "<h2>Your choices</h2><p>You can delete your Flip Stats yourself on the website at any time, and turn sharing off in the mod. You can also ask us to delete your key and the stored Discord account number at any time. After that you can get a new key by signing in again.</p>"
                + "<h2>Changes</h2><p>If this changes, the date above changes. Valtrin is not directed at children under Discord's minimum age.</p>"
                + contactLine(contactEmail)
                + "<p><a href=/>&larr; Back</a> &middot; <a href=/terms>Terms of Service</a></p>", false, true);
    }

    static String error(String title, String message) {
        return page("Valtrin", "<h1>" + esc(title) + "</h1><div class='note msg'>" + esc(message) + "</div><a class=back href=/redeem>&larr; Back</a>", false);
    }
}
