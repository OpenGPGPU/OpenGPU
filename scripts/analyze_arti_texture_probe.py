#!/usr/bin/env python3
"""Summarize observational texture reuse counts; no speedup prediction."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--replay-json", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    replay = json.loads(args.replay_json.read_text())
    assert replay["ticks"] > 0 and not replay["truncated_tail"]
    rows = []
    for line in args.log.read_text().splitlines():
        if not line.startswith("TEXTURE_REUSE "):
            continue
        row = {key: int(value) for key, value in
               (field.split("=", 1) for field in line.split()[1:])}
        assert row["fetch"] == (row["requests"] + row["issue_blocked"] +
                                row["response_wait"])
        assert 0 <= row["lru4"] <= row["lru8"] <= row["lru16"] <= row["vector_reuse"] <= row["requests"]
        assert 0 <= row["quad_reuse"] <= row["vector_reuse"]
        row["lru8_hit_fraction"] = row["lru8"] / row["requests"] if row["requests"] else 0
        rows.append(row)
    assert rows and len({(r["width"], r["height"]) for r in rows}) == len(rows)
    report = dict(log=str(args.log), log_sha256=digest(args.log),
                  replay_json=str(args.replay_json), replay_sha256=digest(args.replay_json),
                  replay={k: v for k, v in replay.items() if k != "seconds"},
                  textures=rows,
                  scope="Physical tap requests inferred from issued-bit transitions. LRU and address sets reset per vector instruction; quad sets reset at lane/4 boundaries. Counts measure potential word reuse, not saved cycles. Probe elapsed time excluded.")
    args.output.write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
