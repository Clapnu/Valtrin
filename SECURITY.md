# Valtrin: Security Review

**Version reviewed:** 23.7.10 in full; 23.9.0 adds the server program and the mod's optional server connection, reviewed in section 9 · **Scope:** the Valtrin client mod and the server program in `server/` · **Method:** source review (AI-assisted reading, author-directed), then fixes and hostile-input tests

This document lists what Valtrin touches, what could go wrong, what has been fixed, and what is still open. Status is reported as it is: a finding is only **Fixed** if the change is in the code and covered by the checks in section 6.

## 1. What Valtrin is

A client-side Fabric mod for Minecraft Java Edition (26.x) that shows alerts and tooltips for Hypixel SkyBlock Auction House flips. It **never** buys, clicks or automates anything. It reads public data and prints chat messages and tooltips. The only commands it can send are `/viewauction <id>` and `/ah <username>` when you click or right-click an alert (or press the open-next-flip key), and both are built only from validated values (see E1).

Java 25, one third-party library (Gson, which Minecraft already ships).

## 2. What is worth protecting

| Asset | Why it matters |
| --- | --- |
| Your computer and files | A mod runs with your full user permissions. |
| Your Minecraft account | Valtrin never reads login or session tokens (no use of the session or auth classes). |
| The optional shared-server key | A credential, if you use a shared data server. |
| Your flip statistics | Personal in-game financial history. |

## 3. Data flows

| # | Flow | Data sent | Data received |
| --- | --- | --- | --- |
| F1 | Hypixel public auction API (HTTPS, no API key) | your IP address, a User-Agent | auction pages, Bazaar, ended auctions |
| F2 | Mojang session server (HTTPS) | seller UUIDs (public IDs), your IP | seller usernames |
| F3 | NEU item repository on GitHub (HTTPS) | the item IDs you look at, your IP | recipe JSON |
| F4 | Optional shared Valtrin server (set by you) | the access key header, file paths | sales files |
| F5 | Local files in `ValtrinData` and the config folder | n/a | n/a |
| F6 | Game chat lines | n/a | Hypixel system messages (used for the profit stats) |

**Not collected:** no account name, no tokens, no telemetry, no analytics, no crash reporting. By default Valtrin makes network requests only while you are connected to Hypixel.

## 4. Findings and status

| ID | Finding | Status | What was done |
| --- | --- | --- | --- |
| E1 | Chat commands built from unvalidated network data | **Fixed** | Auction IDs must be 32 hex characters (or dashed UUID form) and usernames must match `[A-Za-z0-9_]{1,16}` before any command is built or any click event is attached. |
| E2 | Item ID used in a file path and URL without validation | **Fixed** | Item IDs must match `[A-Z0-9_:;-]{1,64}`; anything else is never turned into a path or URL. |
| E3 | Unbounded response sizes | **Fixed** | Shared-server answers are capped at 64 MB, Hypixel answers at 128 MB (after decompression) and Mojang answers at 8 KB. |
| E4 | Shared server accepted plain HTTP; key stored in plain text | **Partly fixed** | Only `https://` is accepted (`http://` only for localhost), so the key is not sent in clear text. The key is still stored unencrypted in the config file, as for most game mods. It is masked in the settings screen and never printed. |
| E11 | Item data parser had no limits | **Fixed** | Nesting depth 32, list length 4,096 and 1 MB after decompression. Errors from hostile data are caught. A 50 MB decompression bomb is refused in under 5 seconds. |
| E12 | No shared input validation | **Fixed** | One `Validate` class is used for IDs, names, URLs and text at the points where data enters from outside. Chat lines are single lines and tabs are removed before data is written to a file. |
| E13 | Profit stats accepted chat from any server | **Fixed** | Chat is ignored unless you are connected to Hypixel. |
| E14 | Shared key typed as a command stays in command history | **Fixed** | `/flip set sharedkey` no longer stores anything. The key is entered in the settings screen. |
| E15 | Config written non-atomically; no range checks | **Fixed** | The config is written to a temporary file and swapped in. All values are clamped to safe ranges on load and on save. No backup copy is kept. |
| E17 | Settings had no upper bounds | **Fixed** | All numeric settings are clamped (for example, the poll interval cannot go below 1 second). |
| E18 | Recipe lookups reveal inspected items to GitHub | **Mitigated** | New setting "Recipe lookups" turns the requests off. It is on by default because crafted-item values are less exact without it. |
| E19 | Seller-name cache: unvalidated entries, non-atomic rewrite | **Fixed** | Only valid UUID/username pairs are loaded, the cache is rewritten through a temporary file, and answers are capped. |

## 5. Controls already in place

- No process execution, deserialization, dynamic class loading or dynamic code loading anywhere in the source.
- No API keys or secrets in the source or resources. The Hypixel endpoints used need no key.
- All network traffic is HTTPS to fixed hosts with timeouts, except the optional shared server you configure.
- File deletion is narrow: only dated folders are pruned, and directory walks do not follow symlinks.
- Item IDs used in sales-log file names are reduced to `[A-Za-z0-9_]`.
- Parsing failures degrade to "no data" instead of crashing the game.
- Valtrin never submits, buys, bids or sends gameplay actions.

## 6. Tests

`tools/securitytests` contains hostile-input tests, run against the compiled mod classes. **Result: 139 passed, 0 failed.**

They cover path-like and newline-containing IDs, username and URL validation, fake and oversized chat lines, trade lines, about 9,000 random or truncated item blobs, 100,000-level nesting, a list claiming 2 billion entries, a decompression bomb, negative array sizes and out-of-range settings.

## 7. Still open

- **Static analysis** (SpotBugs or similar) has not been run.
- **Dependency and release checks:** a published SHA-256 per release jar and Gradle dependency verification are not set up yet.
- **The shared-server key is stored in plain text** in the config file (see E4).
- **Shared-server data is not signed.** Valtrin trusts the server you configure to send valid data, and validates it only by parsing it.
- **The tests are not wired into the build.** They run by hand.

## 9. Server program and the mod's server connection (23.9.0)

Reviewed by the author with AI assistance; not audited by an outside party. Tested with 36 extra mod-side checks (`SecurityTests`) and 79 end-to-end checks against a real local server (`server/test/ServerTests.java`).

**Website feed.** `/v1/flips` is read-only JSON for the Valtrin website. It needs a valid key, takes a token from that key's rate bucket, and follows the key's plan for delay and history. It leaves out seller names. Browsers may read it only from the one address in the `siteOrigin` setting (CORS), and the website keeps the key in the browser tab only (sessionStorage), sending it only to api.valtrin.app.

**Craft Flips feed.** `/v1/craft` is read-only JSON, premium keys only, with the same key check, rate limit and browser rule as `/v1/flips`. The server downloads the NotEnoughUpdates item repository (one archive, about once a week) from GitHub and reads it as a stream: only files named `items/<id>.json` up to 256 KB are parsed, ids are validated, and a damaged or cut-off archive is rejected as a whole. It stores only recipes, no personal data.

**Flip Stats.** `/v1/stats` takes POST (from the mod, only when the player has turned sharing on), GET and DELETE (from the website), premium keys only. A POST is limited to 20 KB and 50 flips, every field is validated, and only time, item name, the item's rarity (one of a fixed list), coins paid and coins received are stored, in one text file per account named by a hash of the key label (kept until the player deletes it, newest 20,000 lines only). The mod never sends flips from before sharing was turned on, never follows redirects, and stops when the server says the key is not premium. DELETE removes the account's file.

**Discord presence.** The server keeps a standard Discord gateway connection open so the bot shows as online. It requests no privileged intents, receives no messages, posts nothing, and uses the same token file as the plan roles. discordBotOnline=false turns it off.

**Mod side (receiving alerts).** The server is treated as untrusted. Every message goes through `Wire`: wrong types, missing fields, out-of-range numbers, oversized text, bad auction ids and control characters cause the message to be dropped or cleaned. The stream reader (`SseReader`) refuses lines over 32 KB and events over 32 KB. A server message can only produce a chat alert: the click action is built by the mod from an auction id that matches a strict pattern, and nothing the server sends is ever run as a command. The key is sent only in a header, only to the address the player entered, and only over https (http only for localhost); redirects are not followed, so the key cannot be passed on. A flood guard allows 30 flips a second and drops repeats of the same auction. The player's own filters apply to what arrives. If the server goes silent for 45 s the connection is dropped and retried with backoff.

**Server side.**
- Keys: 256 random bits (`vk_` + 43 characters), stored only as SHA-256 hashes in a file readable by the server's user only; a leaked file does not reveal keys. Keys are shown once when made. Key text is never logged and request headers are removed from the web server's log.
- Access: every request needs a valid key; only GET is accepted; unknown paths give 404. Wrong keys are rate-limited per client address (default 20 a minute, then 429 even for a correct key from that address until the minute ends). History requests are limited per key (token bucket). The item id of a history request must match a strict pattern, so it cannot reach other files.
- Capacity: maximum sessions (default 300), one connection per key (a new one replaces the old), a bounded queue per connection (a client that stops reading is dropped), header count and request time limits.
- Revoking a key stops new connections at once and ends an open one within 15 s.
- Delays: premium keys get no delay only when `premiumEnabled=true`; otherwise they get the free delay, so nothing is given away before selling is allowed.
- The program runs as an ordinary user, listens on 127.0.0.1 and is meant to sit behind Caddy (automatic HTTPS). The service unit drops privileges (no new privileges, read-only home except the data folder).

**Invite codes (/redeem).** The only page that does not need a key, so it is the main thing an outsider can reach. Codes have 80 random bits, are stored only as hashes, expire, have a use limit, and can only create FREE keys, never premium. Wrong codes count as failed attempts against the per-address limit, so guessing is not practical. Each address can make a few keys a day (default 5). The form accepts only a short POST with the form content type, names are restricted to letters, digits, dot, dash and underscore (so nothing can be injected into the page or the key file), the page sends a strict content-security policy and no-store caching, and the new key is shown once and never logged. Codes and key files are updated under a file lock so the admin commands and the running server cannot overwrite each other. Tested with 21 checks (wrong, odd, expired, reused, oversized and badly named requests; the daily cap; a taken name not using up a code).

**Sign in with Discord.** Uses Discord's standard OAuth login (authorization code flow). The server never sees a Discord password. It requests only the `identify` scope (plus `guilds` when a server-membership check is turned on), reads the account id (and server list) once, then throws the token away; only the numeric id and the time of the last key are kept. A random one-use `state` value, also stored in an HttpOnly SameSite cookie, is checked on return, which blocks cross-site forgery and replay. The client secret is read from a file readable only by the server's user and is never logged or put in the settings file. Requests to Discord have time limits and size limits, redirects are not followed, and a failed or odd answer ends in a plain error page. One account gets one key; a second sign-in within an hour is refused (so a stolen browser session cannot churn keys), and after that it replaces the old key. Accounts younger than a set age (default 7 days) are refused. Tested against a pretend Discord with 18 checks (state mismatch, replay, missing cookie, cancelled login, too-new account, not in the server, bad code, hourly hold, key replacement).

**Still open for the server.** (1) HTTPS needs a domain name; until then use it only on localhost or with a few trusted people over a tunnel. (2) The address limit trusts the proxy's `X-Forwarded-For` only when the request comes from this machine; if the port were ever exposed directly, an attacker could not fake it (a direct caller is not loopback) but would be limited by their own address. (3) A client that stops reading keeps one idle thread until the connection times out, though it no longer counts as a session. (4) Discord accounts are free to make, so the age check and server-membership check slow abuse but do not stop a determined person who owns several old accounts; a person who gets an invite code can pass it on while it has uses left, and one address can make several keys a day; abuse of a key is handled by revoking it, and a shared code can be cancelled with `invites revoke`. (5) Whether serving other players from Hypixel's public data is allowed under Hypixel's rules must be confirmed with Hypixel before inviting users, and selling needs their approval.

## 8. Reporting a vulnerability

If you find a security problem, please report it privately through **GitHub Security Advisories** on this repository (Security tab → Report a vulnerability) instead of opening a public issue. Include the version, what you did, and what happened. Please give time for a fix before sharing details publicly.

*Valtrin is an independent project and is not affiliated with or endorsed by Hypixel Inc. or Mojang.*

**Plan roles on Discord (optional).** When configured, the server uses a bot token to add or remove a "Free Plan" / "Premium Plan" role. It is deliberately not a chat bot: there is no live connection to Discord, it reads no messages and it has no commands, only one-off web requests to add or remove a role. The token lives in a file readable only by the server's user, is sent only to Discord in an Authorization header, and is never logged or put in the settings file. Only numeric ids that pass a strict check are placed in a request address; requests have time limits, redirects are not followed, at most 2 KB of an answer is read and it is only checked for Discord's "unknown role" code, and the server makes at most 60 requests per check, one every quarter second, and stops on a rate-limit answer. If the token is wrong or the permission is missing, only role-giving stops; keys and alerts are unaffected. Tested with a pretend Discord (role given at sign-in, premium swap, someone not in the server, cancelled key, token only in the Authorization header, off without a token).
