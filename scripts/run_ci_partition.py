#!/usr/bin/env python3
"""Run the exact planned ScalaTest work and verify its fresh XML reports."""
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

from check_pr_test_results import check_results
from ci_case_partitions import suites_of


def command_for(suites, cases):
    suites_of(suites)
    if not suites or (cases and len(suites) != 1):
        raise ValueError("A case partition must select exactly one suite")
    if not all(isinstance(name, str) and name for name in cases) or len(cases) != len(set(cases)):
        raise ValueError("Invalid or duplicated case names")
    # sbt's command parser passes quoted names to ScalaTest as single tokens.
    command = "testOnly " + " ".join(suites)
    if cases:
        command += " -- " + " ".join("-t " + json.dumps(name, ensure_ascii=False) for name in cases)
    return command


def check_cases(reports, suites, cases):
    if cases:
        root = ET.parse(Path(reports) / ("TEST-" + suites[0] + ".xml")).getroot()
        actual = [case.get("name") for case in root.findall("testcase")]
        if sorted(actual) != sorted(cases):
            raise ValueError("Executed XML cases do not match the planned partition")


def main():
    suites = json.loads(os.environ["SUITES_JSON"])
    cases = json.loads(os.environ.get("CASES_JSON", "[]"))
    flags = shlex.split(os.environ.get("SBT_FLAGS", ""))
    report_dir = Path("target/test-reports")
    log_path = Path(os.environ["CI_TEST_LOG"])
    command = command_for(suites, cases)
    shutil.rmtree(report_dir, ignore_errors=True)
    print("Running " + command, flush=True)
    with log_path.open("w") as log:
        process = subprocess.Popen(["sbt", "-Dopengpu.productQualification=0", *flags,
            "set Test / parallelExecution := false", "set Test / logBuffered := false", command],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        for line in process.stdout:
            log.write(line); print(line, end="", flush=True)
        status = process.wait()
    if status:
        return status
    result = check_results(log_path.read_text(), report_dir, suites)
    check_cases(report_dir, suites, cases)
    print(result)
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a") as summary:
            summary.write(result + "\n")
    return 0


if __name__ == "__main__":
    sys.exit(main())
