# Where Valtrin's data comes from

Every number the mod shows is computed from the sources below. Keep this file up to date; it is the record of provenance.

| Data | Source | Used for |
|---|---|---|
| Current BIN listings | Hypixel public API `skyblock/auctions` | Lowest BIN, new-listing detection |
| Sold auctions | Hypixel public API `skyblock/auctions_ended` | Recorded sales: median, sales per day |
| Bazaar prices | Hypixel public API `skyblock/bazaar` | Enchant, gem, star and material values |
| Item data (star costs, gem slots) | Hypixel public API `resources/skyblock/items` | Upgrade costs |
| Seller names | Mojang session server profile lookup | "seller's AH" button in alerts |
| Matching rules, modifier weights, thresholds | Written for this project | Choosing comparable sales and listings |

No third-party price or flip service is called, and none of their data or code is included.
The optional shared-data address (`/flip set shared`) is empty by default and only talks to a server you set up.

## Crafting recipes (NotEnoughUpdates item repository)
Recipe data is downloaded at runtime, one item file at a time, from
https://github.com/NotEnoughUpdates/NotEnoughUpdates-REPO and cached in ValtrinData/recipes. It is used only to show
an item's crafting cost. The repository is licensed under the MIT License:

    MIT License

    Copyright (c) 2020 Moulberry

    Permission is hereby granted, free of charge, to any person obtaining a copy
    of this software and associated documentation files (the "Software"), to deal
    in the Software without restriction, including without limitation the rights
    to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
    copies of the Software, and to permit persons to whom the Software is
    furnished to do so, subject to the following conditions:

    The above copyright notice and this permission notice shall be included in all
    copies or substantial portions of the Software.

    THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
    IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
    FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
    AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
    LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
    OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
    SOFTWARE.

## Server program

The server jar (built by `server/build.sh`, not committed) bundles Gson (Apache License 2.0), the same library Minecraft ships.
