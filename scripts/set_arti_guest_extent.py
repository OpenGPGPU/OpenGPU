#!/usr/bin/env python3
"""Match a private ARTI guest DTB to an already emitted model's viewport.

Only the GPU width, height and stride properties may change. The complete
decoded tree must survive a dtc round trip unchanged apart from those cells.
This does not alter RTL capacities or the shared QEMU machine configuration.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile


def patch_extent(source, width, height, stride):
    if not all(0 < value <= 0xffffffff for value in (width, height, stride)) or stride < width * 4:
        raise ValueError("extent must be positive 32-bit cells with stride >= width * 4")
    nodes = list(re.finditer(r"(?ms)^\tarti-rtl@b000000\s*\{(.*?)^\t\};", source))
    if len(nodes) != 1:
        raise ValueError("expected exactly one root ARTI GPU node")
    node = nodes[0]
    body, changed = node[1], {}
    for name, value in (("opengpu,width", width), ("opengpu,height", height),
                        ("opengpu,stride", stride)):
        pattern = re.compile(r"(?m)^(\t\t" + re.escape(name) + r"\s*=\s*<)(0x[0-9a-f]+|\d+)(>;)")
        matches = list(pattern.finditer(body))
        if len(matches) != 1:
            raise ValueError("expected one 32-bit GPU cell: " + name)
        changed[name] = dict(before=int(matches[0][2], 0), after=value)
        body = pattern.sub(lambda m: m[1] + hex(value) + m[3], body)
    return source[:node.start(1)] + body + source[node.end(1):], changed


def build(source, output, width, height, stride, dtc):
    source, output = Path(source).resolve(), Path(output).resolve()
    metadata = output.with_name(output.name + ".json")
    if output.exists() or metadata.exists():
        raise FileExistsError("choose a fresh output DTB and metadata path")
    original_hash = hashlib.sha256(source.read_bytes()).hexdigest()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".arti-dtb-", dir=output.parent) as temp:
        temp = Path(temp)
        decoded = subprocess.check_output([dtc, "-I", "dtb", "-O", "dts", str(source)], text=True)
        patched, changed = patch_extent(decoded, width, height, stride)
        (temp / "guest.dts").write_text(patched)
        subprocess.run([dtc, "-I", "dts", "-O", "dtb", "-o", str(temp / "guest.dtb"),
                        str(temp / "guest.dts")], check=True)
        round_trip = subprocess.check_output([dtc, "-I", "dtb", "-O", "dts",
                                               str(temp / "guest.dtb")], text=True)
        if patched != round_trip:
            raise ValueError("dtc changed other decoded tree content")
        if hashlib.sha256(source.read_bytes()).hexdigest() != original_hash:
            raise ValueError("source DTB changed during preparation")
        data = (temp / "guest.dtb").read_bytes()
    with output.open("xb") as stream:
        stream.write(data)
    result = dict(source=str(source), source_sha256=original_hash, output=str(output),
                  output_sha256=hashlib.sha256(data).hexdigest(), changed_properties=changed,
                  complete_tree_round_trip_equal=True, simulation_qualified=False,
                  scope="Guest metadata only; dimensions must match the selected emitted RTL model.")
    with metadata.open("x") as stream:
        stream.write(json.dumps(result, indent=2) + "\n")
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="DTB dumped from the chosen private QEMU machine")
    parser.add_argument("output", type=Path, help="fresh DTB, normally under .artifacts/")
    parser.add_argument("--width", type=int, required=True)
    parser.add_argument("--height", type=int, required=True)
    parser.add_argument("--stride", type=int, required=True)
    parser.add_argument("--dtc", default="dtc", help="host device-tree compiler (also in the Linux build tree)")
    args = parser.parse_args()
    result = build(args.source, args.output, args.width, args.height, args.stride, args.dtc)
    print(result["output"])


if __name__ == "__main__":
    main()
