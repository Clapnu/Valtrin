Valtrin server
==============

What it is: the mod's engine without Minecraft, plus a small web interface. It reads public Hypixel data once,
records sold auctions and auction snapshots, finds flips with the mod's own pricing code, and sends each flip to every
connected mod (after that key's delay). It never buys anything and never sends commands to anyone.

Web interface (all need the header  X-Key: <access key>; GET, except where noted):
    /v1/stream      alerts, as server-sent events (events: hello, flip, notice). One connection per key.
    /ping           "ok"
    /v1/time        the server clock in ms (plain number), for /flip ping and /flip timing in the mod
    /v1/flips       recent flips as JSON for the website (needs X-Key, delay and history follow the plan, no seller names; browser access only from siteOrigin)
    /v1/craft       crafts that currently look profitable, as JSON for the website (premium keys only, same browser rule as /v1/flips)
    /v1/stats       completed flips a player chose to share from the mod (premium keys only): POST from the mod, GET and DELETE from the website
    /sales, /coverage    the sold-auction history the mods use for pricing (same as the old recorder)

Settings: config/valtrin-server.properties (made on first start, inside the data folder given by -Duser.home):
    bind=127.0.0.1          keep this when Caddy (HTTPS) is in front
    port=8080
    freeDelaySeconds=7      delay for FREE keys
    premiumEnabled=false    premium keys get no delay ONLY when this is true; leave false until Hypixel has approved selling
    maxSessions=300
    maxFailsPerMinute=20    wrong-key attempts per address before it is blocked for the rest of the minute

Access keys (a key is shown once; only its hash is stored, in config/keys.tsv):
    java -Duser.home=/home/valtrin/valtrin-data -jar valtrin-server.jar keys add free  "friend-name"
    java -Duser.home=/home/valtrin/valtrin-data -jar valtrin-server.jar keys add premium "customer-name"
    java -Duser.home=/home/valtrin/valtrin-data -jar valtrin-server.jar keys list
    java -Duser.home=/home/valtrin/valtrin-data -jar valtrin-server.jar keys revoke "friend-name"
Sign in with Discord (automatic keys, recommended). One-time setup:
  1. discord.com/developers/applications -> New Application. Copy the Application (client) ID.
  2. OAuth2 page: copy the Client Secret (shown once). Add the redirect  https://YOUR-DOMAIN/auth/discord/callback
  3. On the server, store the secret without showing it:
       read -s -p "Secret: " S && printf '%s' "$S" > ~/valtrin-data/config/discord-secret.txt && chmod 600 ~/valtrin-data/config/discord-secret.txt && unset S
  4. Add to config/valtrin-server.properties:  publicUrl=https://YOUR-DOMAIN  discordClientId=THE-ID
     optional:  discordGuildId=YOUR-DISCORD-SERVER-ID  (only its members get keys)   discordMinAccountDays=7
  5. Restart the server; the log says "Sign in with Discord is on". People open https://YOUR-DOMAIN/redeem and click the button.
One key per Discord account; signing in again (after an hour) gives a new key and cancels the old one.

Discord auction commands (/auctions, /link, /unlink, /notify) work with the same bot token and application id as the other
commands, and need no new settings. /auctions draws a picture, which needs a font on the server. If the log line after start says
"Auction pictures: this machine cannot draw text", run:  sudo apt-get install -y fontconfig fonts-dejavu-core  and restart; until then it
answers in plain text. Data comes only from Hypixel's public auction feeds the server already reads (no Hypixel key). Sales are kept in
memory for 36 hours, so they start empty after a restart. Links (Discord id, Minecraft name, DM on/off) are in config/discord-links.tsv.

Invite codes (people get their own FREE key without you typing a command for each):
    ... -jar valtrin-server.jar invites add 20          20 single-use codes, valid 30 days
    ... -jar valtrin-server.jar invites add 1 20 7      ONE code that 20 people can use, valid 7 days
    ... -jar valtrin-server.jar invites list            usage (shows an id for each code, not the code)
    ... -jar valtrin-server.jar invites revoke ID       cancel a code (use the id from the list)
Send each person a code and the link  https://your-domain/redeem . They enter the code and a name and get their key,
shown once. A code can only make FREE keys. Each address can make at most redeemPerAddressPerDay keys a day (default 5).
Turn the page off with redeemEnabled=false.

Changes apply within seconds, no restart. A revoked key's open connection ends within 15 seconds.

Build (only needed if you change the code; a ready jar is sent with each release):
    bash server/build.sh          needs a JDK and the Gson jar (set GSON=/path/to/gson-2.10.jar)
Tests:
    javac -cp server/build/classes -d /tmp/st server/test/ServerTests.java
    java -Duser.home=/tmp/sthome -cp /tmp/st:server/build/classes dev.valtrin.ServerTests

Running it: see tools/servertest/install-service.sh (starts by itself, restarts if it stops) and the steps in the
release notes. The mod needs https:// to talk to a real server (http:// only works for localhost), so a domain name is
needed before other people can connect.


PLAN ROLES ON DISCORD (optional)
--------------------------------
Gives people who are in your Discord server a "Free Plan" or "Premium Plan" role that matches their key. It is not a chat bot:
it makes small one-off requests to Discord and never reads messages. Nobody has to join your Discord to get a key.
1. Discord Developer Portal > your application > Bot > create the bot (no special "intents" are needed). Copy its token.
2. Invite it to your server with the Manage Roles permission, then drag the bot's role ABOVE the Free Plan and Premium Plan roles
   in Server Settings > Roles (Discord only lets a bot change roles below its own).
3. Put the token in a file only the server's user can read. Do NOT paste it anywhere else:
       nano ~/valtrin-data/config/discord-bot-token.txt     (paste the token, save)
       chmod 600 ~/valtrin-data/config/discord-bot-token.txt
4. In config/valtrin-server.properties set discordRoleGuildId (the server's id), discordFreeRoleId and discordPremiumRoleId
   (right-click each > Copy ID, with Developer Mode on). Restart the server. The log says "Plan roles on Discord are on".
Roles are checked a few seconds after someone signs in and then every 5 minutes. People who join the Discord later get theirs
within about 30 minutes. A cancelled key loses the role the next time the server checks (not after a server restart).
