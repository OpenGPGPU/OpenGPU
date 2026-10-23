#!/usr/bin/env python3
"""Reject successful sbt invocations that did not finish real ScalaTest work."""
from pathlib import Path
import argparse
import re
import sys
import xml.etree.ElementTree as ET


def check_results(log, reports=None, expected=()):
    log = re.sub(r"\x1b\[[0-9;]*m", "", log)
    counts = re.findall(r"Total number of tests run:\s*(\d+)", log)
    suites = re.findall(r"Suites: completed (\d+), aborted (\d+)", log)
    results = re.findall(
        r"Tests: succeeded (\d+), failed (\d+), canceled (\d+), ignored (\d+), pending (\d+)",
        log,
    )
    if len(counts) != 1 or len(suites) != 1 or len(results) != 1:
        raise ValueError("Expected one complete ScalaTest summary")
    total = int(counts[0])
    completed, aborted = map(int, suites[0])
    succeeded, failed, canceled, ignored, pending = map(int, results[0])
    if total <= 0 or completed <= 0 or aborted or failed or canceled or ignored or pending:
        raise ValueError("Tests must execute without failures, cancellation, ignored/pending tests or aborted suites")
    if succeeded != total:
        raise ValueError("Executed test count does not match successful test count")
    report_tests = 0
    for name in expected:
        path = Path(reports) / ("TEST-" + name + ".xml")
        if not path.is_file():
            raise ValueError("Missing test report for selected suite: " + name)
        suite = ET.parse(path).getroot()
        cases = suite.findall("testcase")
        if (suite.get("name") != name or not cases
                or int(suite.get("tests", "0")) != len(cases)):
            raise ValueError("Selected suite did not execute tests: " + name)
        report_tests += len(cases)
        if any(case.find(tag) is not None for case in cases
               for tag in ("skipped", "failure", "error")):
            raise ValueError("Selected suite has skipped or failed tests: " + name)
    if expected and (report_tests != total or len(set(expected)) != completed):
        raise ValueError("Selected suite reports do not match the completed test summary")
    return f"ScalaTest: {succeeded} tests passed across {completed} suites; {ignored} ignored tests."


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("log", type=Path)
    parser.add_argument("--reports", type=Path, default=Path("target/test-reports"))
    parser.add_argument("--suites", nargs="*", default=[])
    args = parser.parse_args()
    try:
        print(check_results(args.log.read_text(), args.reports, args.suites))
    except ValueError as error:
        sys.exit(str(error))
