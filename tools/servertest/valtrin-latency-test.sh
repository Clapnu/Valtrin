#!/usr/bin/env bash
# Valtrin server latency test. Run this on each TRIAL server (Linux, any provider) and compare the results.
# It only makes normal public requests (no API key). Safe to run; takes about 1 minute.
# Usage:  bash valtrin-latency-test.sh
set -u
URL="https://api.hypixel.net/v2/skyblock/auctions?page=0"

echo "=== Server: $(hostname) | $(date -u '+%F %T UTC') ==="
echo "Public IP / location (from ipinfo):"
curl -s --max-time 5 https://ipinfo.io/ip; echo
curl -s --max-time 5 https://ipinfo.io/city; curl -s --max-time 5 https://ipinfo.io/region; echo

echo
echo "--- Which Cloudflare city answers for Hypixel's API? (lower distance = faster) ---"
curl -sI --max-time 8 "$URL" | grep -i -E '^(cf-ray|cf-cache-status|age|server):'

echo
echo "--- 15 timed requests to the auctions page (seconds). Look at connect and first-byte (ttfb) ---"
printf "%-4s %-8s %-8s %-8s %-8s %-10s\n" "n" "dns" "connect" "tls" "ttfb" "total"
for i in $(seq 1 15); do
  curl -s -o /dev/null --max-time 15 -H 'Accept-Encoding: gzip' \
    -w "%{time_namelookup} %{time_connect} %{time_appconnect} %{time_starttransfer} %{time_total}\n" "$URL" |
  awk -v n="$i" '{printf "%-4s %-8.3f %-8.3f %-8.3f %-8.3f %-10.3f\n", n, $1, $2, $3, $4, $5}'
  sleep 1
done | tee /tmp/valtrin-latency.txt

echo
echo "--- Averages (skip run 1, it includes DNS warm-up) ---"
tail -n +2 /tmp/valtrin-latency.txt | awk '{c+=$3; t+=$5; n++} END {printf "avg connect %.1f ms | avg first-byte %.1f ms over %d runs\n", c/n*1000, t/n*1000, n}'

echo
echo "--- Ping to Cloudflare's nearest edge (1.1.1.1) ---"
ping -c 10 -q 1.1.1.1 2>/dev/null | tail -n 2 || echo "ping not available"
echo
echo "Send these numbers back. LOWEST avg connect and first-byte wins."
