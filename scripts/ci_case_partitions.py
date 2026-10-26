#!/usr/bin/env python3
"""Plan complete suite/case partitions; expensive host cases stay bounded."""
import argparse
import base64
from collections import defaultdict
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

from changed_test_suites import local_only_suites

ROOT = Path(__file__).resolve().parents[1]
MAX_CASES = 8
WHOLE_SUITE_LIMIT = 2
# Untagged cases use their harness's default local payload/slot model.
# Explicit labels still override these defaults and create distinct groups.
PROFILE_DEFAULTS = {
    "opengpu.graphics.TypedTileImagePipelineSpec": (("local", "true"), ("oneSlot", "false")),
    "opengpu.graphics.TypedTileRenderPipelineSpec": (("local", "true"), ("oneSlot", "false")),
}
PROFILE = re.compile(r"\b(local|oneSlot|CUs|lanes|warps|payloadBytes|banked|defaultPayload|shared|oversizeSpill)=([A-Za-z0-9]+)\b")


def case_partitioned(suite):
    return suite in PROFILE_DEFAULTS or suite in {"opengpu.testutil.GpuSimSpec", "opengpu.graphics.FragmentShaderStageSpec", "opengpu.graphics.VertexShaderStageSpec",
                     "opengpu.graphics.PersistentTileSharedSystemSpec",
                     "opengpu.graphics.TypedReplayTileImagePipelineSpec"} or (
        suite.startswith("opengpu.graphics.") and suite.endswith("HostAxiSpec"))


def suites_of(selection):
    suites = selection if isinstance(selection, list) else [suite for group in selection.values() for suite in group]
    if len(suites) != len(set(suites)):
        raise ValueError("Suite appears in multiple input partitions")
    if not all(re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", suite) for suite in suites):
        raise ValueError("Invalid suite name")
    local = set(suites) & local_only_suites(ROOT)
    if local:
        raise ValueError("Local-only qualification cannot run in CI: " + ", ".join(sorted(local)))
    return suites


def catalog_from(path):
    catalog = {}
    for line in Path(path).read_text().splitlines():
        suite, encoded = line.split("\t")
        cases = [base64.b64decode(value, validate=True).decode("utf-8") for value in encoded.split(",") if value]
        if suite in catalog or not cases or len(cases) != len(set(cases)):
            raise ValueError("Missing or duplicated case catalog: " + suite)
        catalog[suite] = cases
    return catalog


def profile(name, defaults=()):
    fields = dict(defaults)
    fields.update(PROFILE.findall(name))
    # Translation changes runtime inputs, whereas these dimensions change the
    # elaborated model. Keep the bare/Sv32 pair together for model reuse.
    count = re.search(r"(\d+) shared CUs", name)
    lanes = re.search(r"(\d+) lanes/(\d+) warps", name)
    if count:
        fields["CUs"] = count[1]
    if lanes:
        fields["lanes"], fields["warps"] = lanes.groups()
    return tuple(sorted(fields.items()))


def suite_parts(suite, catalog, shard="graphics-0", exact=False):
    if not case_partitioned(suite):
        return [dict(id=suite, cache=suite, shard=shard, suites=[suite], cases=[])]
    if suite not in catalog:
        raise ValueError("Selected host suite is missing its case catalog: " + suite)
    cases = catalog[suite]
    groups = defaultdict(list)
    for name in sorted(cases):
        groups[profile(name, PROFILE_DEFAULTS.get(suite, ()))].append(name)
    if not exact and len(cases) <= WHOLE_SUITE_LIMIT and len(groups) == 1:
        return [dict(id=suite, cache=suite, shard=shard, suites=[suite], cases=[])]
    parts = []
    for key, names in sorted(groups.items()):
        suffix = hashlib.sha256(repr(key).encode()).hexdigest()[:8]
        # Cold fragment cases accumulated 27 minutes despite the local matrix
        # split. Untagged vertex cases similarly accumulated 28 minutes.
        limit = 2 if suite == "opengpu.graphics.FragmentShaderStageSpec" or (
            suite == "opengpu.graphics.VertexShaderStageSpec" and not key) else MAX_CASES
        for start in range(0, len(names), limit):
            parts.append(dict(id=f"{suite}-{len(parts)}", cache=f"{suite}-{suffix}",
                              shard=shard, suites=[suite], cases=names[start:start + limit]))
    assigned = [name for part in parts for name in part["cases"]]
    if sorted(assigned) != sorted(cases):
        raise ValueError("Case partition lost or duplicated a test: " + suite)
    return parts


def plan(selection, catalog, exact_suites=()):
    suites_of(selection)
    if isinstance(selection, list):
        result = [part for suite in selection for part in suite_parts(suite, catalog, exact=suite in exact_suites)]
    else:
        result = []
        for shard, suites in selection.items():
            whole = []
            for suite in suites:
                parts = suite_parts(suite, catalog, shard)
                if parts[0]["cases"]:
                    result.extend(parts)
                else:
                    whole.append(suite)
            if whole:
                result.append(dict(id=shard, cache=shard, shard=shard, suites=whole, cases=[]))
    if len(result) > 200:
        raise ValueError("Partition plan exceeds the configured 200-job budget")
    return result


def graph(selected, scope):
    def command(*args):
        return subprocess.check_output(["bash", str(ROOT / "scripts/ci_shard_tests.sh"), *args], cwd=ROOT, text=True)
    shards = json.loads(command("shards-json", selected))
    return {shard: command("suites", shard, selected).split() for shard in shards
            if scope == "full" or shard.startswith("graphics-")}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    g = commands.add_parser("graph")
    g.add_argument("selected")
    g.add_argument("--scope", choices=["full", "graphics"], default="full")
    c = commands.add_parser("candidates")
    c.add_argument("selection", type=Path)
    p = commands.add_parser("plan")
    p.add_argument("selection", type=Path)
    p.add_argument("catalog", type=Path)
    p.add_argument("--base", help="Narrow assertion-only host-test additions for a PR")
    args = parser.parse_args()
    if args.command == "graph":
        print(json.dumps(graph(args.selected, args.scope), separators=(",", ":")))
    else:
        selection = json.loads(args.selection.read_text())
        if args.command == "candidates":
            for suite in sorted(suites_of(selection)):
                if case_partitioned(suite):
                    print(suite)
        else:
            catalog = catalog_from(args.catalog)
            exact = ()
            if args.base:
                from ci_changed_host_cases import locations_from, narrow
                original = selection
                selection, catalog, exact = narrow(selection, catalog, ROOT, args.base,
                    locations_from(str(args.catalog) + ".locations.tsv"))
                from ci_telemetry_coverage import narrow as telemetry_coverage
                selection = telemetry_coverage(selection, catalog, exact, ROOT, args.base)
                print(f"PR selection: {len(original)} candidate suites -> {len(selection)} required suites; "
                      f"{len(exact)} suites use exact changed-case subsets. "
                      "Other host scenarios remain in the complete regression.", file=sys.stderr)
            print(json.dumps(plan(selection, catalog, exact), separators=(",", ":")))


if __name__ == "__main__":
    main()
