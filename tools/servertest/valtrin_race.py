#!/usr/bin/env python3
"""
Valtrin freshness race logger.

Run this on EACH trial server at the SAME time (start them within a minute of each other).
It watches Hypixel's auction list and writes down the exact moment this server first sees each new snapshot.
Afterwards, valtrin_race_compare.py lines the files up and shows which server got every snapshot first.

Why this matters: the speed of a flip alert is set by how soon the server sees a new snapshot, and that depends on
the Cloudflare city answering for the server, not only on ping. Only this test measures it.

Needs only Python 3 (already on Ubuntu). No API key. One small request at a time, page 0 only.

Usage:   python3 valtrin_race.py <label> [minutes]        e.g.  python3 valtrin_race.py nj 30
Output:  race-<label>.tsv  (copy it back to your PC, then run valtrin_race_compare.py on all of them)
"""
import gzip
import json
import os
import subprocess
import sys
import time
import urllib.request

URL = os.environ.get("VALTRIN_RACE_URL", "https://api.hypixel.net/v2/skyblock/auctions?page=0")
FAST_STEP = 0.5      # seconds between requests right when a new snapshot is expected
SLOW_STEP = 2.0      # seconds between requests the rest of the time
FAST_FROM = float(os.environ.get("VALTRIN_RACE_FAST_FROM", "45"))     # start polling fast this many seconds after the last change
FAST_UNTIL = float(os.environ.get("VALTRIN_RACE_FAST_UNTIL", "80"))    # ... and stop being fast after this many seconds
TIMEOUT = 10


def clock_ok():
    """Server clocks must be synced or the comparison is meaningless."""
    try:
        out = subprocess.run(["timedatectl", "show", "-p", "NTPSynchronized", "--value"],
                             capture_output=True, text=True, timeout=5).stdout.strip()
        return out == "yes"
    except Exception:
        return None


def fetch():
    req = urllib.request.Request(URL, headers={"Accept-Encoding": "gzip", "User-Agent": "Valtrin-race-test"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
        first_byte = time.time()
        raw = r.read()
        hdr = r.headers
        if hdr.get("Content-Encoding", "").lower() == "gzip":
            raw = gzip.decompress(raw)
        data = json.loads(raw)
    ray = hdr.get("cf-ray", "")
    colo = ray.rsplit("-", 1)[-1] if "-" in ray else ""
    return {
        "last": int(data.get("lastUpdated", 0)),
        "seen": first_byte,
        "ttfb_ms": (first_byte - t0) * 1000.0,
        "colo": colo,
        "cache": hdr.get("cf-cache-status", ""),
        "age": hdr.get("age", ""),
    }


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    label = "".join(c for c in sys.argv[1] if c.isalnum() or c in "-_")[:24] or "server"
    minutes = float(sys.argv[2]) if len(sys.argv) > 2 else 30.0
    ok = clock_ok()
    print("Clock synced:", {True: "yes", False: "NO - fix before trusting results", None: "unknown"}[ok])
    out_path = "race-%s.tsv" % label
    end = time.time() + minutes * 60
    last_seen = None
    last_change = time.time()
    n = 0
    with open(out_path, "w") as f:
        f.write("lastUpdated\tfirst_seen_ms\tttfb_ms\tcolo\tcache\tage\n")
        while time.time() < end:
            try:
                r = fetch()
            except Exception as e:
                print("request failed:", e)
                time.sleep(SLOW_STEP)
                continue
            if r["last"] and r["last"] != last_seen:
                last_seen = r["last"]
                last_change = time.time()
                n += 1
                f.write("%d\t%d\t%.1f\t%s\t%s\t%s\n" % (r["last"], int(r["seen"] * 1000), r["ttfb_ms"], r["colo"], r["cache"], r["age"]))
                f.flush()
                print("snapshot %d: %d  ttfb %.0f ms  colo %s  cache %s" % (n, r["last"], r["ttfb_ms"], r["colo"], r["cache"]))
            since = time.time() - last_change
            time.sleep(FAST_STEP if FAST_FROM <= since <= FAST_UNTIL else SLOW_STEP)
    print("Done. Wrote %s with %d snapshots. Copy it to your PC." % (out_path, n))


if __name__ == "__main__":
    main()
