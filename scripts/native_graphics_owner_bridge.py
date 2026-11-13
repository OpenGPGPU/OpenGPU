#!/usr/bin/env python3
"""Keep real native owners alive through external DUT execution and image checks.

The bridge command is an argv JSON array. Literal {ipc} arguments are replaced
with the fresh GPU-internal directory, which must be writable in that command's
environment. No shell is used. The C producer publishes ready.json only after
preparing both real owners; response.bin authorizes observed retirement only
after the actual executor and independent original-source oracles succeed.
"""
import argparse
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import time

from native_graphics_uniform_oracle import SparseMemory, expected_color, verify

ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def uint(value, bits, label):
    require(type(value) is int and 0 <= value < 1 << bits, "invalid " + label)
    return value


def strict_loads(text):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "duplicate JSON key: " + key)
            result[key] = value
        return result
    return json.loads(text, object_pairs_hook=unique,
                      parse_constant=lambda value: (_ for _ in ()).throw(ValueError("invalid JSON " + value)))


def strict_json(path):
    return strict_loads(path.read_text())

def profile_bytes(profile):
    require(type(profile) is dict, "probe must be a JSON object")
    require(profile.get("schema") == "opengpu-native-owner-profile-v1", "incorrect probe schema")
    for field, expected in (("sourceQualificationRequired", True), ("gpuExecution", False),
                            ("clockQualified", False), ("fpsQualified", False)):
        require(profile.get(field) is expected, "incorrect probe flag " + field)
    require(type(profile.get("resetCount")) is int and profile["resetCount"] == 1, "incorrect probe reset count")
    words = profile.get("words")
    require(type(words) is list and len(words) == 96, "probe must contain all 96 discovery words")
    result = bytearray(b"OGPF0001" + struct.pack("<I", len(words)))
    seen = set()
    for word in words:
        require(type(word) is dict and set(word) == {"address", "value"}, "invalid discovery pair")
        address = uint(word["address"], 32, "discovery address")
        require(not address & 3 and address not in seen, "unaligned or duplicate discovery address")
        seen.add(address)
        result += struct.pack("<II", address, uint(word["value"], 32, "discovery value"))
    registers = profile.get("registers")
    require(type(registers) is list and len(registers) == 5, "probe must include five live registers")
    result += struct.pack("<I", 5)
    seen = set()
    for word in registers:
        require(type(word) is dict and set(word) == {"address", "value"}, "invalid live register pair")
        address = uint(word["address"], 32, "live register address")
        require(address in {0, 0x60, 0x68, 0x320, 0x158} and address not in seen, "incorrect live register address")
        seen.add(address)
        result += struct.pack("<II", address, uint(word["value"], 32, "live register value"))
    return bytes(result)


def ipc_file(directory, name, required=True):
    require(type(name) is str and name and Path(name).name == name and name not in {".", ".."},
            "IPC path must be a single relative filename")
    path = directory / name
    require(not path.is_symlink() and (path.is_file() or (not required and not path.exists())), "missing or indirect IPC file: " + name)
    return path


def ready_files(directory):
    ready = strict_json(ipc_file(directory, "ready.json"))
    require(ready.get("schema") == "opengpu-native-owner-ready-v1", "incorrect READY schema")
    require(type(ready.get("captures")) is list and len(ready["captures"]) == 2 and
            type(ready.get("sources")) is list and len(ready["sources"]) == 2,
            "READY must contain both actual owners")
    memory = ipc_file(directory, ready.get("memory"))
    require(ready["captures"] == ["record-0.capture", "record-1.capture"], "incorrect active capture filenames")
    captures = [ipc_file(directory, name, required=index == 0) for index, name in enumerate(ready["captures"])]
    sources = [ipc_file(directory, name) for name in ready["sources"]]
    require(len(set([memory] + captures + sources)) == 5, "aliased IPC inputs")
    return memory, captures, sources


def response_bytes(records, first_record=0):
    require(len(records) in (1, 2) and first_record in (0, 1) and len(records) + first_record <= 2,
            "incorrect execution receipt count")
    output = bytearray(b"OGRSP001" + struct.pack("<I", len(records)))
    for index, record in enumerate(records, first_record):
        require(record.get("phase") == "retired", "receipt must follow actual retirement trace verification")
        require(type(record) is dict and uint(record.get("record"), 32, "record index") == index,
                "out-of-order executor receipt")
        for field, expected in (("ownerReplay", True), ("resetsBetweenRecords", False),
                                ("heldCompletionDrained", True), ("readOnlyGuards", True),
                                ("independentImagesRequired", True), ("sourceQualificationRequired", True),
                                ("clockQualified", False), ("fpsQualified", False), ("externalDrained", True),
                                ("retiredIrqAsserted", False)):
            require(record.get(field) is expected, "incorrect executor flag " + field)
        require(type(record.get("resets")) is int and record["resets"] == 1, "incorrect series reset count")
        gnx = record.get("gnx")
        require(type(gnx) is list and len(gnx) == 12, "receipt must retain all 12 raw GNX words")
        values = [index, uint(record.get("commandId"), 32, "command id"),
                  uint(record.get("completion"), 32, "completion")]
        values += [uint(value, 32, "raw GNX word") for value in gnx]
        output += struct.pack("<15IQ", *values, uint(record.get("appendReadBytes"), 64, "append read bytes"))
        for field in ("heldLegacyStatus", "heldStatus", "heldIrq", "heldIrqAsserted", "retiredStatus", "retiredIrq",
                      "retiredIrqAsserted", "externalDrained"):
            value = record.get(field)
            if field.endswith("Asserted") or field == "externalDrained":
                require(type(value) is bool, "invalid receipt boolean " + field)
                value = int(value)
            output += struct.pack("<I", uint(value, 32, field))
    return bytes(output)


def checked_images(initial, results, sources):
    descriptions = [strict_json(source) for source in sources]
    colors = [expected_color(description) for description in descriptions]
    require(all(all(color) for color in colors) and colors[0] != colors[1],
            "original sources must produce different nonzero colors")
    require(descriptions[0]["color"]["physical_base"] != descriptions[1]["color"]["physical_base"],
            "simultaneously prepared owners must use distinct mutable color allocations")
    # This additional oracle input comes exclusively from the previous original
    # GEM allocation lookup. It never changes a capture, VA, instruction or RAM.
    descriptions[1]["preserve"] = [descriptions[0]["color"]]
    with SparseMemory(initial) as before, SparseMemory(results / "record-0.memory") as first, \
            SparseMemory(results / "record-1.memory") as second:
        verify(before, first, descriptions[0])
        verify(before, second, descriptions[1], first)


def atomic_bytes(path, data):
    require(not path.exists() and not path.is_symlink(), "IPC output already exists: " + path.name)
    temporary = path.with_suffix(path.suffix + ".new")
    with temporary.open("xb") as stream:
        stream.write(data)
        stream.flush()
        os.fsync(stream.fileno())
    os.replace(temporary, path)


def wait_ready(process, directory, deadline):
    while not (directory / "ready.json").exists():
        require(not (directory / "error").exists(), "C bridge reported an error")
        require(process.poll() is None, "C bridge exited before publishing live owners")
        require(time.monotonic() < deadline, "timed out waiting for prepared owners")
        time.sleep(0.1)
    require(process.poll() is None, "C bridge did not keep owners alive")


def held_bytes(record, index):
    require(type(record) is dict and uint(record.get("record"), 32, "held record index") == index,
            "incorrect held record")
    for field in ("heldCompletionDrained", "sourceQualificationRequired"):
        require(record.get(field) is True, "incorrect held flag " + field)
    require(not any(key.startswith("retired") for key in record),
            "held observation must not authorize retirement")
    gnx = record.get("gnx")
    require(type(gnx) is list and len(gnx) == 12, "held receipt must retain all raw GNX words")
    asserted = record.get("heldIrqAsserted")
    require(type(asserted) is bool, "incorrect held IRQ boolean")
    words = [index, uint(record.get("commandId"), 32, "held command id"),
             uint(record.get("completion"), 32, "held completion")]
    words += [uint(word, 32, "raw held GNX word") for word in gnx]
    return b"OGHLD001" + struct.pack("<I15IQ4I", 1, *words,
        uint(record.get("appendReadBytes"), 64, "held append read bytes"),
        uint(record.get("heldLegacyStatus"), 32, "held legacy status"),
        uint(record.get("heldStatus"), 32, "held status"),
        uint(record.get("heldIrq"), 32, "held IRQ"), int(asserted))


def wait_file(path, bridge, executor, timeout):
    deadline = time.monotonic() + timeout
    while not path.exists():
        require(not (path.parent / "error").exists(), "C bridge reported an active-session error")
        require(bridge.poll() is None, "C bridge exited with live owners")
        require(executor.poll() is None, "actual executor exited before " + path.name)
        require(time.monotonic() < deadline, "timed out waiting for " + path.name)
        time.sleep(0.1)
    require(not path.is_symlink() and path.is_file(), "invalid active result file")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--executor", type=Path, required=True)
    parser.add_argument("--bridge-command", type=Path, required=True, help="JSON argv array, with {ipc} substitution")
    parser.add_argument("--timeout", type=int, default=3600)
    args = parser.parse_args()
    require(args.timeout > 0, "timeout must be positive")
    command = strict_json(args.bridge_command)
    require(type(command) is list and command and all(type(arg) is str and arg for arg in command),
            "bridge command must be a nonempty argv array")
    executor = args.executor.resolve(strict=True)
    # Use the main GPU's short internal path for macOS socket compatibility.
    gpu = next(parent for parent in (ROOT, *ROOT.parents) if (parent / ".git").is_dir())
    parent = gpu / ".artifacts"
    parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="g", dir=parent) as temporary:
        directory = Path(temporary)
        environment = dict(os.environ, TMPDIR=temporary,
                           OPENGPU_NATIVE_HOST_DIRECTORY=temporary,
                           OPENGPU_NATIVE_HOST_PROFILE=str(directory / "profile.bin"))
        with (directory / "executor-probe.log").open("w") as log:
            subprocess.run([executor, "--probe-profile", directory / "profile.json"],
                           env=environment, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=args.timeout)
        atomic_bytes(directory / "profile.bin", profile_bytes(strict_json(directory / "profile.json")))
        process = None
        try:
            argv = [arg.replace("{ipc}", temporary) for arg in command]
            with (directory / "bridge.log").open("w") as log:
                process = subprocess.Popen(argv, env=environment, stdout=log, stderr=subprocess.STDOUT)
                wait_ready(process, directory, time.monotonic() + args.timeout)
                initial, captures, sources = ready_files(directory)
                results = directory / "results"
                with (directory / "executor-series.log").open("w") as executor_log:
                    running = subprocess.Popen([executor, "--active-series", initial, results, directory, "2"],
                        env=environment, stdout=executor_log, stderr=subprocess.STDOUT)
                    records = []
                    try:
                        for index in range(2):
                            wait_file(directory / ("record-%d.held.json" % index), process, running, args.timeout)
                            held = strict_json(directory / ("record-%d.held.json" % index))
                            atomic_bytes(directory / ("record-%d.held.bin" % index), held_bytes(held, index))
                            # The C producer records real hw-drain reads/POP/IRQ
                            # into OGRTR001. Only the live DUT executes them.
                            wait_file(directory / ("record-%d.retired.json" % index), process, running, args.timeout)
                            record = strict_json(directory / ("record-%d.retired.json" % index))
                            receipt = response_bytes([record], index)
                            for field in ("commandId", "completion", "gnx", "appendReadBytes", "heldLegacyStatus", "heldStatus",
                                          "heldIrq", "heldIrqAsserted"):
                                require(record.get(field) == held.get(field),
                                        "retired observation changed held field " + field)
                            require(process.poll() is None, "C bridge released owners before observed drain")
                            if index == 0:
                                with SparseMemory(initial) as before, SparseMemory(results / "record-0.memory") as after:
                                    verify(before, after, strict_json(sources[0]))
                            else:
                                checked_images(initial, results, sources)
                            # Per-record response authorizes execution completion
                            # only. Both real owners must retain their mappings.
                            atomic_bytes(directory / ("record-%d.response.bin" % index), receipt)
                            records.append(record)
                        require(running.wait(timeout=args.timeout) == 0, "actual active executor failed")
                        require(process.poll() is None, "C bridge released owners before final qualification")
                        atomic_bytes(directory / "response.bin", response_bytes(records))
                    finally:
                        if running.poll() is None:
                            running.terminate()
                            try:
                                running.wait(timeout=10)
                            except subprocess.TimeoutExpired:
                                running.kill()
                                running.wait()
                require(process.wait(timeout=args.timeout) == 0, "C bridge rejected observed retirement")
                text = (directory / "bridge.log").read_text()
                require(text.splitlines().count("ACTUAL_GRAPHICS_NATIVE_OWNER_RETIRED records=2 observed=true") == 1,
                        "missing real observed-retirement completion")
            print("Native graphics owner series PASS: two real snapshots, actual DUT and independent images; no clock/FPS qualification")
        except BaseException:
            if not (directory / "error").exists():
                atomic_bytes(directory / "error", b"qualification failed; no owner-release authorization\n")
            # Failure never fabricates completion or sends an approving response.
            # The producer's explicit failure teardown owns its OS allocations.
            if process is not None and process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait()
            for name in ("executor-probe.log", "executor-series.log", "bridge.log"):
                path = directory / name
                if path.exists():
                    print(path.read_text(errors="replace")[-6000:])
            raise


if __name__ == "__main__":
    main()
