#!/usr/bin/env python3
"""Reject successful sbt invocations that did not finish real ScalaTest work."""
from pathlib import Path
import re
import sys


def check_results(log):
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
    if total <= 0 or completed <= 0 or aborted or failed or canceled or pending:
        raise ValueError("PR tests must execute and finish without failures, cancellation, pending tests or aborted suites")
    if succeeded != total:
        raise ValueError("Executed test count does not match successful test count")
    return f"ScalaTest: {succeeded} tests passed across {completed} suites; {ignored} ignored tests."


if __name__ == "__main__":
    try:
        print(check_results(Path(sys.argv[1]).read_text()))
    except ValueError as error:
        sys.exit(str(error))
