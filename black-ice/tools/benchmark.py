#!/usr/bin/env python3
"""Summarize native BlackIceBench samples; never treat refresh FPS as game FPS."""
import argparse
import json
import math
import re
import statistics
from pathlib import Path

FIELDS = ("refresh_fps", "internal_fps", "speed_percent", "frame_time_ms", "ee_usage_percent", "gs_usage_percent")


def segments(text):
    runs = []
    for line in text.splitlines():
        match = re.search(r"\bv1,(\d+),([0-9a-fA-F]{8}),([^\s]+)", line)
        if not match:
            continue
        try:
            values = [float(v) for v in match[3].split(",")]
        except ValueError:
            continue
        if len(values) != len(FIELDS) or not all(math.isfinite(v) for v in values):
            continue
        if any(v < 0 for i, v in enumerate(values) if i != 1) or (values[1] < 0 and values[1] != -1):
            continue
        sample = dict(zip(FIELDS, values), time_ms=int(match[1]), crc=match[2].lower())
        if sample["crc"] == "00000000":
            continue
        if not runs or sample["crc"] != runs[-1][-1]["crc"] or not 0 < sample["time_ms"] - runs[-1][-1]["time_ms"] <= 2000:
            runs.append([])
        runs[-1].append(sample)
    return runs


def summarize(run, warmup, duration):
    if warmup < 0 or duration <= 0:
        raise ValueError("warmup must be nonnegative and duration positive")
    start = run[0]["time_ms"] + warmup * 1000
    end = start + duration * 1000
    if run[-1]["time_ms"] < end:
        raise ValueError("Recording too short for the requested warmup and duration")
    window = [s for s in run if start <= s["time_ms"] < end]
    if len(window) < 2:
        raise ValueError("Not enough samples")
    result = {"crc": run[0]["crc"], "samples": len(window), "warmup_seconds": warmup, "duration_seconds": duration}
    for name in FIELDS:
        values = [s[name] for s in window if s[name] >= 0]
        result[name + "_sample_mean"] = statistics.mean(values) if values else None
    result["below_95_percent_speed_sample_fraction"] = sum(s["speed_percent"] < 95 for s in window) / len(window)
    result["note"] = "Half-second sample means, not per-frame percentiles. Internal FPS is an emulator estimate; null means unavailable."
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--segment", type=int, default=0, help="Continuous gameplay segment index (0-based)")
    parser.add_argument("--warmup", type=float, default=30)
    parser.add_argument("--duration", type=float, default=120)
    args = parser.parse_args()
    runs = segments(args.log.read_text())
    if not 0 <= args.segment < len(runs):
        parser.error(f"Found {len(runs)} gameplay segments; choose a valid --segment")
    try:
        result = summarize(runs[args.segment], args.warmup, args.duration)
    except ValueError as error:
        parser.error(str(error))
    result["segments_found"] = len(runs)
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
