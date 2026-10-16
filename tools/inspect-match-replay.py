#!/usr/bin/env python3
"""Inspect recorded GPS and the checked-in response fixtures without network access.

The app's RoadGeometryValidatorTest exercises plausibility. This companion report
makes point/leg coverage and unresolved junction evidence readable in CI artifacts.
"""
import argparse
import json
from pathlib import Path


def summarize(label, result, sample_ids):
    if result.get("code") != "Ok":
        raise ValueError(f"{label}: non-OK fixture")
    traces = result.get("tracepoints")
    if not isinstance(traces, list) or len(traces) != len(sample_ids):
        raise ValueError(f"{label}: inconsistent waypoint count")
    matches = result.get("matchings")
    if not isinstance(matches, list):
        raise ValueError(f"{label}: missing matchings")
    valid = []
    for index, trace in enumerate(traces):
        if trace is None:
            continue
        if not isinstance(trace, dict):
            raise ValueError(f"{label}: invalid tracepoint")
        matching = trace.get("matchings_index")
        if not isinstance(matching, int) or not 0 <= matching < len(matches):
            raise ValueError(f"{label}: missing matching reference")
        valid.append(sample_ids[index])
    streets = set()
    legs = 0
    for match in matches:
        for leg in match.get("legs", []):
            legs += 1
            for step in leg.get("steps", []):
                if step.get("name"):
                    streets.add(step["name"])
    return {
        "sample_count": len(sample_ids),
        "matched_tracepoint_count": len(valid),
        "matched_ids": valid,
        "unmatched_ids": [point for point in sample_ids if point not in valid],
        "leg_count": legs,
        "road_names": sorted(streets),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--fixture", type=Path,
                        default=Path(__file__).resolve().parents[1] /
                        "app/src/test/resources/matching/turn17-replay.json")
    args = parser.parse_args()
    fixture = json.loads(args.fixture.read_text(encoding="utf-8"))
    points = fixture["points"]
    if len(points) < 3:
        raise ValueError("Recorded fixture needs at least three points")
    ids = [int(point["id"]) for point in points]
    if ids != sorted(set(ids)):
        raise ValueError("Recorded point IDs must be unique and chronological")
    current = summarize("current", fixture["current_response"], ids[2:8])
    expanded = summarize("expanded", fixture["expanded_response"], ids)
    report = {
        "mode": "offline-fixture-inspection",
        "source": args.fixture.name,
        "current": current,
        "expanded": expanded,
        "note": "Fixture geometry is not a live provider or device accuracy measurement.",
    }
    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "matching-fixture-report.json").write_text(
        json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
