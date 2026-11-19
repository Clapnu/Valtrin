# Changelog

Each release in short. Full notes are on the Modrinth page.

## 23.9.9

- Settings, /flip server and /flip status now say plainly that a shared server address makes sales prices come from that server and sends your key to it. Alerts, prices and speed are unchanged
- Build: Gradle dependency verification is on. A checksum for every library and plugin is in gradle/verification-metadata.xml, Loom is pinned to 1.19.4 and the Gradle download is pinned by checksum, so a changed dependency fails the build. The jar is still the same every time you build it
- Site: pet names on the Flip Stats page keep their square brackets again, like [Lvl 100] Mithril Golem

## 23.9.8

- Fixed: the license file packed inside the jar was a leftover Fabric template file (CC0). It now matches the project license, All Rights Reserved, as in LICENSE, fabric.mod.json and the README. Older jars contain the stale file by mistake
- Builds now use fixed file order and timestamps, and the Gradle wrapper is included in the tagged source, so you can build the tag yourself and compare. The release page lists the jar's SHA-256
- Mod behaviour is unchanged: alerts, prices and speed are the same as 23.9.7
- Server: item names are cleaned before they go into Discord text (no links, mentions or new lines), one item that fails to draw no longer turns pictures off for everyone, at most two pictures are drawn at once, the links file is private to the server's user, and only the person who ran /auctions can use its buttons
- Server: the free plan alert delay is now 7 seconds
- Docs: the README, SECURITY.md and the website now say plainly that Valtrin is built with AI assistance and has not had an outside audit, and SECURITY.md lists what the optional server keeps and how far to trust it

## 23.9.7

- Server: new Discord commands /auctions (a player's auction house as one picture: listings with rarity colours and stars, recent sales stamped SOLD and shown first, identical items grouped, sort and page buttons, optional profile), /link, /unlink and /notify (a DM when one of your auctions sells, off until you turn it on). Built only from Hypixel's public auction data; no API key. Mod unchanged
- Fixed: price tooltips and the auction-house colours no longer stop while you are connected to the Valtrin server. This computer keeps reading prices; the alerts themselves still come only from the server
- Item rarity is now sent with Flip Stats (optional, same opt-in), so the website can colour item names
- Server: new /valtrin and /status Discord slash commands (links, and whether the server is up). Only the person who types them sees the answer
- New and OFF by default: Flip Stats sharing. With /flip profit share on, each flip you complete (time, item, coins paid, coins received) is sent to your Valtrin server and shown on the website's Flip Stats page. Premium keys only, never sends flips from before you turned it on, and the page has a button that deletes it all. /flip profit share off stops it
- Server: shows the Discord bot as online, using the same bot token file as the plan roles (setting discordBotOnline, on by default)
- Server: new Craft Flips feed for the website (/v1/craft, premium keys only). It finds items that cost less to craft than they sell for, using recipes from the NotEnoughUpdates item repository and prices the server already has. It never buys or crafts anything
- Server: new read-only /v1/flips feed for the Valtrin website. It needs your key, respects your plan's delay and history window, leaves out seller names, and only the website's own address (siteOrigin setting) may read it from a browser
- Server: after a restart, an account that is on the free plan now also loses a leftover Premium Plan role in Discord
- Server: signing in with Discord again no longer turns a premium key into a free one. The new key keeps the plan the account already had

## 23.9.6

- The "Aggressive" filter preset is now called "Risky", because it lowers every bar (profit, margin, sales per day, confidence) and so shows more alerts that are smaller, slower or less certain. `/flip preset aggressive` still works

## 23.9.5

- Server: optional "Free Plan" / "Premium Plan" roles in your Discord server, matching each person's key. It makes small one-off requests to Discord only (no chat bot, no live connection, no message reading) and needs a bot token with Manage Roles. Off unless configured. Joining the Discord server is never required. Mod unchanged

## 23.9.4

- Server: Terms of Service (/terms) and Privacy Policy (/privacy) pages, linked from the project page. They say exactly what the server keeps. Mod unchanged

## 23.9.3

- Server: a public project page at the server's address (what Valtrin is, what it never does, plans, privacy, link to get a key). Mod unchanged

## 23.9.2

- /flip status in server mode: no more stale speed numbers. It shows "handled by the server", your key's alert delay, the time since the last flip, and counts flips from the server in "Sent"

## 23.9.1

- Server: "Sign in with Discord" on the key page (`/redeem`): one click, no code, no password. One key per Discord account (signing in again after an hour makes a new key and cancels the old), the account must be old enough (default 7 days), and optionally the person must be in your Discord server. The secret lives in `config/discord-secret.txt`, tokens are used once and never stored
- Server: redesigned key page (works on phones), a copy button on the key, and a "not affiliated with Hypixel or Mojang" line on every page
- Server: invite codes. `invites add [codes] [uses each] [days]` makes codes; a person opens `https://your-domain/redeem`, enters a code and a name, and gets their own FREE key (shown once). Codes are stored hashed, single-use by default, expire, can only make free keys, and each address can make only a few keys a day. Needs no account or bot
- Settings screen: Ctrl+V pastes into a field you are editing (first line only, control characters removed). Useful for the server address and key

## 23.9.0

- The engine now hands its alerts to a small "sink" instead of building Minecraft chat messages itself. In the game the alerts look and behave exactly as before; this makes it possible to run the same engine on a server without Minecraft
- New: first version of the Valtrin server program (`server/`), which runs the engine headless. It only reads public Hypixel data and prints flips and memory/CPU statistics for now
- Storage: sold-auction history no longer has an age limit; the oldest days are deleted only when the folder passes 20 GB, checked at start-up and once a day. Recorded auction-page snapshots are kept for 30 days instead of 3
- The engine's logging goes through its own small logger so it also works outside the game
- Server program is now complete: access keys (stored hashed), alerts streamed to connected mods, free keys delayed 15 s (setting), premium keys 0 s only when `premiumEnabled=true`, per-address limits on wrong keys, per-key limits on history requests, `keys add|list|revoke` commands
- Mod: new optional setting "Alerts from the server" (Settings, Advanced, uses the server address and key). While connected the mod stops searching Hypixel itself and shows the server's flips, filtered by your own minimum profit, margin, volume, confidence, price and blacklist. Everything the server sends is validated; it cannot make the mod run a command. The click action is still built by the mod from a checked auction id
- Tests: 139 mod checks (was 103) and 40 end-to-end server checks

## 23.7.10

- Licence: the project is now all rights reserved (LICENSE file, copyright headers, mod metadata) instead of MIT. The author is listed as Valtrin

## 23.7.9

- Fixed: damaged or tampered recipe data is now treated as "no recipe" inside the parser itself, instead of relying on the caller to catch the error
- Tests: hostile recipe files added (100 total)

## 23.7.8

- **Why line**: the alert hover now says in plain words why it is a flip, for example "listed 24% below its estimated sell price", and notes when it sells slowly or fast

## 23.7.7

- Tests: a tampered seller-name file is tested and only valid names load
- Tests: damaged or hostile settings files are now tested and load safely
- Fixed: a trade line with an absurdly large item count could throw an error in the profit tracker. Counts are now clamped. Found by a new 30,000-line chat fuzz test

## 23.7.6

- Docs: README rewritten for everything added since 23.6 (commands, presets, quiet hours, session and export)
- Tests: 17 new checks for coin parsing, compact numbers and sell-time text (95 total, including a 20,000-string fuzz of the input checks)
- Docs: new DESIGN_NOTES.md explaining how flip detection, pricing and sell-time estimates work

## 23.7.5
- **Quiet hours**: no alert sounds during hours you choose (default off, 23 to 7 when enabled), alerts still show. In settings and `/flip set quiet|quietfrom|quietuntil`.

## 23.7.4
- **/flip export** saves your finished flips as a CSV (item names that look like spreadsheet formulas are neutralised). **/flip mute** stops alerts for the item of your latest alert. Both also in settings.

## 23.7.3
- **/flip doctor**: a health check (connection, polling, data, price data, clock, data folder, shared server, filters) with a pass or warning for each, also a settings button.

## 23.7.2
- **Filter presets** (Safe, Balanced, Aggressive) in the settings Filters tab and as `/flip preset <name>`.

## 23.7.1
- **/flip session** shows this session: alerts, flips sold, profit, best flip and profit per hour; also a settings button.

## 23.7.0
- **/flip recent** lists your last 10 alerts (click to open the auction); also a button in the settings Display tab.

## 23.6.x
- **23.6.8** `/flip profit 1-14` uses a rolling window (the past N x 24 hours from now) instead of calendar days.
- **23.6.7** Menus opened with the open-next-flip key (Z) close back to the game, not to chat.
- **23.6.6** Hypixel answers are size-capped (closes the last size-limit item in SECURITY.md); the first snapshot after start no longer skews the speed numbers.
- **23.6.5** Settings key is now the period key (the old default clashed with Minecraft's social screen).
- **23.6.4** Dark button text is drawn flat so it stays readable.
- **23.6.3** Security hardening: validated inputs, https-only shared server, size limits, safer saving, `SECURITY.md`, 62 hostile-input tests.
- **23.6.2** Trades count in `/flip profit`.
- **23.6.1** Closing a menu opened from chat returns to chat.
- **23.6.0** "Sells in about X", price options in Create Auction, settings screen, jackpot sound, welcome window.

## 23.5.0
- Right-click on an alert works when the seller name wasn't cached; fast-check flips are no longer lost on errors; incomplete downloads no longer create false "new" listings; rate-limit pauses are capped.

## Earlier
Versions 5 to 23.4 and the earlier SkyFlipper line: faster polling, the own sales model, confidence ratings, the purple-and-gold alert design and the profit report.
