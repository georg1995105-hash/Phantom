#!/usr/bin/env python3
"""Compare real, common audio measurements; no model calls or transcript storage outside the input CSV."""
import argparse
import csv
import re
import statistics
import unicodedata


def distance(a, b):
    row = list(range(len(b) + 1))
    for i, token in enumerate(a, 1):
        nxt = [i]
        for j, other in enumerate(b, 1):
            nxt.append(min(nxt[-1] + 1, row[j] + 1, row[j - 1] + (token != other)))
        row = nxt
    return row[-1]


def normalize(text):
    return " ".join(re.findall(r"[^\W_]+", unicodedata.normalize("NFC", text).casefold(), re.UNICODE))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("csv")
    args = parser.parse_args()
    with open(args.csv, encoding="utf-8", newline="") as source:
        rows = list(csv.DictReader(source))
    measured = [r for r in rows if all(r.get(k, "").strip() for k in ("clip_id", "wav_sha256", "device", "engine", "reference", "raw", "final"))]
    if not measured:
        print("UNMEASURED: no real audio pairs; empty rows are not successful recognition.")
        return
    groups = {}
    for r in measured:
        key = (r["device"], r["engine"], r.get("model", ""))
        sha = r["wav_sha256"].lower()
        if not re.fullmatch(r"[0-9a-f]{64}", sha):
            raise ValueError("Expected an actual SHA-256 for each WAV")
        clip = (r["clip_id"], sha)
        bucket = groups.setdefault(key, {})
        if clip in bucket:
            raise ValueError("Duplicate engine/audio measurement; use distinct IDs for repetitions")
        bucket[clip] = r
    common = set.intersection(*(set(g) for g in groups.values()))
    if len(groups) < 2 or not common:
        print("UNMEASURED COMPARISON: at least two engines and common WAV hashes are required.")
        return
    for clip in common:
        if len({normalize(g[clip]["reference"]) for g in groups.values()}) != 1:
            raise ValueError("Different references for identical audio")
    print(f"Common audio measurements: {len(common)}; groups: {len(groups)}")
    for key, group in sorted(groups.items()):
        print(" / ".join(key))
        data = [group[c] for c in sorted(common)]
        for variant in ("raw", "final"):
            we = wc = ce = cc = strict_edits = strict_chars = 0
            for r in data:
                ref, hyp = normalize(r["reference"]), normalize(r[variant])
                we += distance(ref.split(), hyp.split()); wc += len(ref.split())
                ce += distance(ref, hyp); cc += len(ref)
                strict_edits += distance(r["reference"], r[variant]); strict_chars += len(r["reference"])
            print(f"  {variant}: WER={we/max(1,wc):.2%}, CER={ce/max(1,cc):.2%}, exact-text CER={strict_edits/max(1,strict_chars):.2%}")
        for column in ("decode_ms", "peak_pss_kb"):
            values = [float(r[column]) for r in data if r.get(column, "").strip()]
            if values:
                print(f"  {column}: median={statistics.median(values):.1f}, max={max(values):.1f}, n={len(values)}")
        factors = [float(r["decode_ms"]) / float(r["audio_ms"]) for r in data if r.get("decode_ms", "").strip() and float(r.get("audio_ms") or 0) > 0]
        if factors:
            print(f"  Real-time factor: median={statistics.median(factors):.3f}")


if __name__ == "__main__":
    main()
