#!/usr/bin/env python3
"""Run actual storage/texture bind/map/unbind with bounded GEM/MMU services."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
source = (root / "driver/opengpu_compute.c").read_text()
mapping = source[source.index("static int opengpu_binding_map_storage("):
                 source.index("/* Map a per-job command snapshot")]
start = source.index("int opengpu_compute_resource_bind_ioctl(")
end = source.index("static struct opengpu_resource_binding *\nopengpu_binding_lookup(", start)
with tempfile.TemporaryDirectory(prefix="opengpu-storage-bind-") as tmp:
    path = Path(tmp)
    (path / "storage_binding_under_test.inc").write_text(mapping + source[start:end])
    subprocess.run(shlex.split(os.environ.get("CC", "cc")) + [
        "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", str(root / "driver/tests/compute_submit_stubs"),
        "-I", str(root / "driver"), "-I", tmp,
        str(root / "driver/tests/opengpu_storage_binding_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
