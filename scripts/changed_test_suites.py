#!/usr/bin/env python3
"""Select changed Scala tests, fixture consumers, and directly matching specs.

This supplements the bounded smoke catalog; it is not a production dependency
graph. Broad integration remains in scala_regression.yml.
"""
import json
from pathlib import Path
import re
import sys


def changed_suites(root, changed):
    changed = set(changed)
    test_root = root / "src/test/scala"
    files = {}
    nodes = []
    for path in sorted(test_root.rglob("*.scala")):
        source = path.read_text()
        # Ignore comments so a documentation mention cannot create dependencies.
        source = re.sub(r"/\*.*?\*/|//[^\n]*", "", source, flags=re.S)
        package = re.search(r"^package\s+([\w.]+)", source, re.M)
        declarations = list(re.finditer(
            r"^((?:(?:abstract|final|sealed)\s+)*)(class|trait|object)\s+(\w+)",
            source, re.M,
        ))
        file_nodes = set()
        for index, declaration in enumerate(declarations):
            modifiers, kind, name = declaration.groups()
            end = declarations[index + 1].start() if index + 1 < len(declarations) else len(source)
            references = set(re.findall(r"\b\w+\b", source[declaration.end():end])) - {name}
            suite = (package[1] + "." + name
                     if package and kind == "class" and "abstract" not in modifiers
                     and name.endswith("Spec") else None)
            file_nodes.add(len(nodes))
            nodes.append((name, references, suite))
        files[path.relative_to(root).as_posix()] = file_nodes

    affected = set(changed) & files.keys()
    ci_inputs = {
        ".github/actions/setup-simulation/action.yml", "scripts/prefetch_scala_bridge.sh",
        ".github/workflows/scala_test.yml", "scripts/changed_test_suites.py",
        "scripts/check_pr_test_results.py", "scripts/ci_shard_tests.sh",
        "scripts/ci_pr_suites.txt", "scripts/select_tests.sh",
        "scripts/ci_case_partitions.py", "scripts/ci_changed_host_cases.py", "scripts/ci_telemetry_coverage.py", "scripts/run_ci_partition.py",
        "src/test/scala/opengpu/testutil/CiSuiteCases.scala",
        ".github/workflows/scala_regression.yml",
    }
    if changed & ci_inputs:
        # Exercise the actual required matrix when its own plumbing changes.
        affected.add("src/test/scala/opengpu/testutil/GpuSimSpec.scala")
    for name in changed:
        if name.startswith("src/main/scala/") and name.endswith(".scala"):
            candidate = name.replace("src/main/scala/", "src/test/scala/", 1)
            candidate = candidate[:-6] + "Spec.scala"
            if candidate in files:
                affected.add(candidate)
    # Include inherited suites and consumers of shared test fixtures, even if
    # the fixture filename itself does not end in Spec.scala. References are
    # deliberately conservative across packages (extra coverage is safe).
    selected = set().union(*(files[path] for path in affected if path in files))
    while True:
        symbols = {nodes[index][0] for index in selected}
        consumers = {index for index, (_, references, _) in enumerate(nodes)
                     if references & symbols}
        expanded = selected | consumers
        if expanded == selected:
            break
        selected = expanded
    return sorted({nodes[index][2] for index in selected if nodes[index][2]})


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[1]
    print(json.dumps(changed_suites(root, sys.argv[1:]), separators=(",", ":")))
