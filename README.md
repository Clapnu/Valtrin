# Valtrin

**Auction House flip alerts for Hypixel SkyBlock. Alerts only, never automated.**

Valtrin is a client-side Fabric mod that watches Hypixel's public auction data and tells you in chat when a listing is worth buying. One compact line per flip, with the profit, the margin and a confidence rating. You click the alert and buy the item yourself.

```
✦ Valtrin │ Terror Chestplate +3.18M ▲11% ●●● ▸
```

> Built for Minecraft 26.2 · Fabric · Java 25

---

## Made with AI

Valtrin is built by one person (Clapnu) who does not write code by hand. Claude, an AI assistant from Anthropic, wrote and reviewed most of the code, the tests, the server and the documentation, directed and checked by the author. The security review in SECURITY.md was done the same way and has **not** been audited by an outside party. The software is provided as it is, with no warranty. Read the source, check the release hash, and use it at your own risk. Independent reviews are welcome.

## Principles

- **Alerts and tooltips only.** Valtrin never buys, bids, clicks in a menu or sends a command for you. Every purchase is your own action.
- **No Hypixel API key.** It reads Hypixel's public auction endpoints only. The optional Valtrin server uses its own free access key.
- **Client-side.** Nothing to install on a server. Your settings and data stay on your computer, unless you turn on the optional server features (alerts from the server, shared sales, Flip Stats sharing), which are off by default.

## Features

### Flip alerts
- Compact purple-and-gold chat alerts, best profit first
- Confidence dots: red = low, yellow = medium, green = high
- Hover for the buy price, estimated sell price, profit after auction fees, sales per day, the seller, and how long ago the item was listed
- **Sells in about X**: an estimate of how long a relist takes to sell, plus profit per hour
- Click an alert to open the auction. Right-click it to see that seller's other auctions
- A two-note chime for normal flips and a **jackpot sound** for big ones
- Press **Z** to open the best waiting flip (closing it returns you to the game)

### Pricing
- Compares a listing against the lowest BIN, the 7-day median sold price, or the lower of the two
- Values enchants, gems, stars and other modifiers, and leaves out odd sales such as alt trades
- Rates its own confidence from how many matching sales and listings it can see

### Create Auction window
- Suggests a price with three clickable options: **Undercut**, **Lowest BIN** and **Median**, each with how long it should take to sell
- Fills the price sign with the one you pick. You still press Done yourself

### Auction House
- Green and red slot highlights for flips and for your listings that were undercut
- Extra price lines in item tooltips

### Your results
- `/flip profit` reports your flip profit over the past 24 hours, N days or all time, from your own chat messages, after fees. Auction House sales and trades both count
- `/flip session` shows this game session: alerts, flips sold, profit and profit per hour
- `/flip recent` lists your last alerts so you can open one you scrolled past
- `/flip export` saves your finished flips as a CSV file

### Tuning
- **Presets:** Safe, Balanced and Risky set the filters together
- **Quiet hours:** no alert sounds during hours you choose, while alerts still show
- **Mute:** `/flip mute` stops alerts for the item of your latest alert
- `/flip doctor` checks the connection, data, clock and settings and prints a pass or warning for each

### Settings
- A clean in-game settings screen with every option. Open it with the **.** (period) key or `/flip menu`
- A welcome window after each update showing what's new

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/installer/) for Minecraft 26.2.
2. Put the [Fabric API](https://modrinth.com/mod/fabric-api) jar and the Valtrin jar in your `mods` folder.
3. Join Hypixel. Valtrin only runs there by default.

## Commands

| Command | What it does |
| --- | --- |
| `/flip menu` | Open the settings screen |
| `/flip next` | Open the best waiting flip |
| `/flip profit [1-14\|all]` | Your flip profit over a rolling period |
| `/flip session` | This session: alerts, flips, profit per hour |
| `/flip recent` | Your last alerts, click to open |
| `/flip export` | Save finished flips as a CSV |
| `/flip mute` | Stop alerts for your latest alert's item |
| `/flip preset <name>` | Safe, balanced or risky filters |
| `/flip doctor` | Health check |
| `/flip status` | Speed, data and connection |
| `/flip timing` | How fast the first alert of each Hypixel snapshot reaches your chat |
| `/flip ping` | Round trip between you and the Valtrin server |
| `/flip set <option> <value>` | Change any setting from chat |
| `/flip blacklist add\|remove <text>` | Items to never alert |
| `/flip toggle` | Turn alerts on or off |
| `/flip test` | Send a fake alert to see the look |
| `/flip help` | List every command |

Everything the commands do is also in the settings screen.

## How it works

1. Valtrin polls Hypixel's public auction list, using light requests that only download what changed.
2. It builds its own model of the market from live listings and recorded sales, so it doesn't depend on a third-party price service.
3. Each new listing is compared against comparable items, with the same item, rarity, stars and important modifiers.
4. Listings that clear your filters (minimum profit, margin, daily sales, confidence) become alerts.
5. Everything is judged cheapest-first, so the best flips are reported quickly.

More detail on where the data comes from is in [DATA_SOURCES.md](DATA_SOURCES.md).

## Building from source

Requires JDK 25.

**Windows**
```
.\gradlew.bat build
```

**Mac / Linux**
```
./gradlew build
```

The mod jar is created in `build/libs/`. Use the one without `-sources`.

## Verify a download

Each release page lists the SHA-256 of its jar. Check yours on Windows with:
```
Get-FileHash .\Valtrin-<version>.jar -Algorithm SHA256
```
On Mac / Linux: `shasum -a 256 Valtrin-<version>.jar`. If it does not match the release page, do not use it. You can also build the same tagged source yourself (above) and read it first. See SECURITY.md for what has and has not been reviewed.

## Project layout

```
src/main/java/dev/valtrin/   mod code
  FlipEngine                 polling, judging, alerts
  OwnModel / Market          price model and estimates
  SellTime / SuggestedPrice  sell-time estimates, Create Auction options
  SettingsScreen             in-game settings
  FlipStats                  your profit tracking
src/main/resources/          sounds, language files, fabric.mod.json
```

## Server

`server/` holds the Valtrin server: the same engine as the mod, run without Minecraft. It reads public Hypixel data once and streams flip alerts to mods that connect with an access key (free keys with a delay, premium keys without once enabled). The mod can optionally use it (Settings, Advanced, "Alerts from the server"). `server/build.sh` builds it, and `server/README.txt` explains the settings, keys and tests. It is not part of the mod jar.

## License

Copyright (c) 2026 Valtrin. All rights reserved. The source is public so it can be read and reviewed; copying, reusing or selling it is not permitted without written permission. See [LICENSE](LICENSE). Jars before 23.9.8 contain a CC0 license file by mistake (a Fabric template leftover); the license is All Rights Reserved.

## Disclaimer

Valtrin is an independent project and is not affiliated with or endorsed by Hypixel Inc. or Mojang. It estimates prices from public data, and estimates can be wrong, so check every flip before you buy it.
