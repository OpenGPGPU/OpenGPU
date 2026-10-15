#!/usr/bin/env python3
"""Compare model replayers on identical recorded full-system traffic."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import statistics
import subprocess


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b""):
            result.update(chunk)
    return result.hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("trace", type=Path)
    p.add_argument("baseline", type=Path)
    p.add_argument("--candidate", action="append", default=[], metavar="NAME=REPLAYER")
    p.add_argument("--rounds", type=int, default=3)
    p.add_argument("--window-start", type=int, help="warm up this many calls before forking replay windows")
    p.add_argument("--window-calls", type=int, default=512)
    p.add_argument("--window-repeats", type=int, default=7)
    p.add_argument("--window-preheat", type=int, default=64)
    p.add_argument("--output", required=True, type=Path)
    args = p.parse_args()
    if args.rounds < 1:
        p.error("--rounds must be positive")
    if args.window_start is not None and (args.window_start < 0 or args.window_calls < 1 or
                                         args.window_repeats < 1 or args.window_preheat < 0):
        p.error("window start/preheat must be nonnegative; length/repeats must be positive")
    env = {key: value for key, value in os.environ.items() if not key.startswith("ARTI_REPLAY_WINDOW_")}
    window = None
    if args.window_start is not None:
        window = {"start": args.window_start, "calls": args.window_calls,
                  "repeats": args.window_repeats, "preheat": args.window_preheat}
        for key, value in window.items():
            env["ARTI_REPLAY_WINDOW_"+key.upper()] = str(value)
    binaries = {"baseline": args.baseline.resolve()}
    for entry in args.candidate:
        name, sep, path = entry.partition("=")
        if not sep or not name or name in binaries:
            p.error("each candidate must have a unique NAME=REPLAYER")
        binaries[name] = Path(path).resolve()
    trace = args.trace.resolve()
    report = {"trace": str(trace), "trace_sha256": digest(trace),
              "window": window,
              "binaries": {name: {"path": str(path), "sha256": digest(path)}
                           for name, path in binaries.items()}, "runs": [], "summary": {}}
    args.output.parent.mkdir(parents=True, exist_ok=True)

    def save():
        temporary = args.output.with_suffix(args.output.suffix+".tmp")
        temporary.write_text(json.dumps(report, indent=2)+"\n")
        temporary.replace(args.output)

    expected = None
    for i in range(args.rounds):
        order = list(binaries)
        if i % 2:
            order.reverse()
        for name in order:
            run = subprocess.run([str(binaries[name]), str(trace)],
                                 text=True, capture_output=True, env=env)
            if run.returncode:
                report["failure"] = {"name": name, "round": i,
                                     "status": run.returncode, "stderr": run.stderr,
                                     "stdout": run.stdout}
                save()
                raise SystemExit(f"{name} failed: {run.stderr}")
            results = [json.loads(line) for line in run.stdout.splitlines() if line.strip()]
            if len(results) != (args.window_repeats if window else 1):
                raise SystemExit("Unexpected number of replay results; check replayer window support")
            for result in results:
                if window and result["ticks"] == 0:
                    report["failure"] = {"name": name, "round": i,
                                         "reason": "window contains no simulated ticks", "result": result}
                    save()
                    raise SystemExit("Window contains no simulated ticks; choose an active trace region")
                if window and (result.get("warmup_calls") != args.window_start+args.window_preheat or
                               result["calls"] != args.window_calls):
                    report["failure"] = {"name": name, "round": i,
                                         "reason": "requested replay window not executed", "result": result}
                    save()
                    raise SystemExit("Requested replay window was not executed")
                work = {key: result[key] for key in
                        ("calls", "ticks", "dma_reads", "dma_writes", "dma_bytes", "truncated_tail")}
                work["warmup_calls"] = result.get("warmup_calls", 0)
                if expected is None:
                    expected = work
                if work != expected:
                    report["failure"] = {"name": name, "round": i,
                                         "reason": "replayed work differs", "result": result}
                    save()
                    raise SystemExit("Replayed work differs; timings are not comparable")
                report["runs"].append({"name": name, "round": i, **result})
                save()
                print(f"round {i+1} {name}: {result['seconds']:.6f}s, {result['ticks']} ticks", flush=True)
    baseline = statistics.median(r["seconds"] for r in report["runs"] if r["name"] == "baseline")
    for name in binaries:
        samples = [r["seconds"] for r in report["runs"] if r["name"] == name]
        median = statistics.median(samples)
        report["summary"][name] = {"seconds": samples, "median_seconds": median,
                                   "reduction_percent": 100*(1-median/baseline),
                                   "round_medians": [statistics.median(
                                       r["seconds"] for r in report["runs"]
                                       if r["name"] == name and r["round"] == i)
                                       for i in range(args.rounds)]}
    save()
    print(json.dumps(report["summary"], indent=2))


if __name__ == "__main__":
    main()
