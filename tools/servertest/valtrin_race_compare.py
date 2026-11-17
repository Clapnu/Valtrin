#!/usr/bin/env python3
"""
Compare race files from several trial servers.

Usage:  python3 valtrin_race_compare.py race-nj.tsv race-chi.tsv race-fra.tsv
        (works on Windows too if Python is installed)

For every snapshot that ALL servers saw, it finds who saw it first and how many milliseconds later each other
server was. Lower is better. A server with a median lag near 0 and many wins is the fastest place to run Valtrin.
"""
import os
import statistics
import sys


def load(path):
    label = os.path.basename(path)
    label = label[5:] if label.startswith("race-") else label
    label = label.rsplit(".", 1)[0]
    rows = {}
    colos = {}
    with open(path) as f:
        next(f, None)
        for line in f:
            p = line.rstrip("\n").split("\t")
            if len(p) < 4:
                continue
            try:
                rows[int(p[0])] = int(p[1])
            except ValueError:
                continue
            colos[p[3]] = colos.get(p[3], 0) + 1
    return label, rows, colos


def pct(vals, q):
    vals = sorted(vals)
    return vals[min(len(vals) - 1, int(len(vals) * q))]


def main():
    if len(sys.argv) < 3:
        print(__doc__)
        sys.exit(1)
    servers = [load(p) for p in sys.argv[1:]]
    common = set(servers[0][1])
    for _, rows, _ in servers[1:]:
        common &= set(rows)
    if len(common) < 3:
        print("Only %d snapshots were seen by every server. Run the logger longer, and start all servers together." % len(common))
        sys.exit(1)
    lags = {label: [] for label, _, _ in servers}
    wins = {label: 0 for label, _, _ in servers}
    for snap in sorted(common):
        times = {label: rows[snap] for label, rows, _ in servers}
        best = min(times.values())
        for label, t in times.items():
            lags[label].append(t - best)
            if t == best:
                wins[label] += 1
    print("Snapshots compared: %d\n" % len(common))
    print("%-14s %6s %10s %10s %10s  %s" % ("server", "wins", "median ms", "p90 ms", "worst ms", "Cloudflare city (count)"))
    ranked = sorted(servers, key=lambda s: statistics.median(lags[s[0]]))
    for label, _, colos in ranked:
        v = lags[label]
        city = ", ".join("%s (%d)" % kv for kv in sorted(colos.items(), key=lambda kv: -kv[1])[:2])
        print("%-14s %6d %10.0f %10.0f %10.0f  %s" % (label, wins[label], statistics.median(v), pct(v, 0.9), max(v), city))
    print("\nFastest: %s. Differences under about 500 ms are inside the test's accuracy; treat them as a tie." % ranked[0][0])


if __name__ == "__main__":
    main()
