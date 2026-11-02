#!/usr/bin/env python3
"""Exercise actual blit ioctl validation prefixes with small GEM/DMA mocks."""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
source = (ROOT / "driver/opengpu_compute.c").read_text()
helper = source.index("static int opengpu_blit_validate_aliases(")
linear = source.index("int opengpu_compute_blit_ioctl(", helper)
linear_end = source.index("    if (args->out_syncobj) {", linear)
span = source.index("static bool opengpu_strided_range_end(", linear_end)
strided = source.index("int opengpu_compute_strided_blit_ioctl(", span)
strided_end = source.index("    if (args->out_syncobj) {", strided)
finish = """    drm_gem_object_put(destination_object);
    drm_gem_object_put(source_object);
    return 0;
out_destination:
    drm_gem_object_put(destination_object);
out_source:
    drm_gem_object_put(source_object);
    return ret;
}
"""
with tempfile.TemporaryDirectory(prefix="opengpu-blit-alias-") as tmp:
    path = Path(tmp)
    (path / "blit_alias_under_test.inc").write_text(
        source[helper:linear] + source[linear:linear_end] + finish +
        source[span:strided] + source[strided:strided_end] + finish)
    cc = shlex.split(os.environ.get("CC", "cc"))
    subprocess.run(cc + ["-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-Wno-unused-variable", "-I", str(ROOT / "driver"), "-I", tmp,
        str(ROOT / "driver/tests/opengpu_blit_alias_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
