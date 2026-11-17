Valtrin server tests (run on trial servers, not in the game)
valtrin-latency-test.sh   quick check: which Cloudflare city answers, connect and first-byte times (bash, any Linux server)
valtrin_race.py           freshness race logger: run on every trial server at the same time, 30 minutes
valtrin_race_compare.py   run on your PC with all the race-*.tsv files: shows which server sees each snapshot first
The race scripts were tested against a simulated local server only, not the real Hypixel API.
