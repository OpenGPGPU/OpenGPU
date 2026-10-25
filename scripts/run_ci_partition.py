#!/usr/bin/env python3
"""Run the exact planned ScalaTest work and verify its fresh XML reports."""
import json
import math
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import threading
import time
import xml.etree.ElementTree as ET

from check_pr_test_results import check_results
from ci_case_partitions import suites_of


def process_snapshot(root_pid):
    """Observe only sbt's process tree; RSS is a sum, not system memory use."""
    result = subprocess.run(["ps", "-eo", "pid=,ppid=,rss=,comm="],
                            capture_output=True, text=True, timeout=5, check=True)
    processes = {}
    for line in result.stdout.splitlines():
        pid, parent, rss, command = line.split(None, 3)
        processes[int(pid)] = (int(parent), int(rss), Path(command).name)
    owned = {root_pid}
    while True:
        children = {pid for pid, (parent, _, _) in processes.items() if parent in owned}
        if children <= owned:
            break
        owned.update(children)
    active = [processes[pid] for pid in owned if pid in processes]
    commands = ",".join(sorted({command for _, _, command in active})) or "exiting"
    rss_mib = sum(rss for _, rss, _ in active) / 1024
    by_command = {}
    for _, rss, command in active:
        by_command[command] = by_command.get(command, 0) + rss
    largest = ",".join(f"{command}:{rss / 1024:.0f}MiB" for command, rss in
                       sorted(by_command.items(), key=lambda item: item[1], reverse=True)[:4])
    return f"processes={commands}; summed RSS={rss_mib:.0f} MiB; largest RSS={largest}"


def system_memory_snapshot():
    try:
        fields = dict(line.split(":", 1) for line in Path("/proc/meminfo").read_text().splitlines())
        available = int(fields["MemAvailable"].split()[0]) / (1024 ** 2)
        total = int(fields["MemTotal"].split()[0]) / (1024 ** 2)
        return f"system available={available:.1f}/{total:.1f} GiB"
    except (OSError, KeyError, ValueError):
        return "system memory unavailable"


def heartbeat(process, stop, emit, interval):
    started = time.monotonic()
    while not stop.wait(interval):
        if process.poll() is not None:
            return
        try:
            observation = process_snapshot(process.pid)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            observation = f"process observation unavailable ({type(error).__name__})"
        free_gib = shutil.disk_usage(".").free / (1024 ** 3)
        emit(f"[ci heartbeat] elapsed={time.monotonic() - started:.0f}s; "
             f"{observation}; {system_memory_snapshot()}; disk free={free_gib:.1f} GiB\n")


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
    interval = float(os.environ.get("OPENGPU_CI_HEARTBEAT_SECONDS", "60"))
    if not math.isfinite(interval) or interval <= 0:
        raise ValueError("Heartbeat interval must be a positive finite number")
    report_dir = Path("target/test-reports")
    log_path = Path(os.environ["CI_TEST_LOG"])
    command = command_for(suites, cases)
    shutil.rmtree(report_dir, ignore_errors=True)
    print("Running " + command, flush=True)
    with log_path.open("w") as log:
        output_lock = threading.Lock()

        def emit(line):
            with output_lock:
                log.write(line)
                log.flush()
                print(line, end="", flush=True)

        process = subprocess.Popen(["sbt", "-Dopengpu.productQualification=0", *flags,
            "set Test / parallelExecution := false", "set Test / logBuffered := false", command],
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        stop = threading.Event()
        reporter = threading.Thread(target=heartbeat,
            args=(process, stop, emit, interval), daemon=True)
        reporter.start()
        try:
            for line in process.stdout:
                emit(line)
            status = process.wait()
        finally:
            stop.set()
            reporter.join()
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
