#!/usr/bin/env python3
"""Run the actual uniform ioctl, scheduler, VM, MMIO and retirement bodies.

Only kernel primitives/device replies are mocked; this is not GPU execution.
"""
import os
from pathlib import Path
import shlex
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
compute = (root / "driver/opengpu_compute.c").read_text()
hw = (root / "driver/opengpu_hw.c").read_text()
sched = (root / "driver/opengpu_scheduler.c").read_text()
gem = (root / "driver/opengpu_drm_device.c").read_text()


def between(source, begin, end):
    start = source.index(begin)
    return source[start:source.index(end, start)]


with tempfile.TemporaryDirectory(prefix="opengpu-uniform-flow-") as tmp:
    path = Path(tmp)
    bodies = {
        "uniform_ioctl.inc": between(compute, "struct opengpu_compute_texture_prepare {",
                                     "static int opengpu_self_test("),
        "uniform_job.inc": between(compute, "int opengpu_job_track_mapping(",
                                   "    if (job->type == OPENGPU_SCHED_BLIT)") +
                           "    return ERR_PTR(-EINVAL);\n}\n",
        "uniform_submit.inc": between(hw, "static int opengpu_hw_unified_submit_locked(",
                                      "static int opengpu_hw_unified_dma_submit_locked("),
        "uniform_hw.inc": between(hw, "int opengpu_hw_compute_async(",
                                  "\n/* Drop shared-L2 lines"),
        "uniform_drain.inc": between(hw, "static int opengpu_hw_unified_result_error(u32 opcode, u32 status)\n{",
                                     "/* submit_lock excludes another submitter"),
        "uniform_retire.inc": between(sched, "static void opengpu_sched_free_job(",
                                      "static const struct drm_sched_backend_ops"),
        "uniform_gem.inc": between(gem, "int opengpu_gem_backings_proven_disjoint(",
                                   "int opengpu_gem_backings_same("),
    }
    for name, body in bodies.items():
        (path / name).write_text(body)
    subprocess.run(shlex.split(os.environ.get("CC", "cc")) + [
        "-std=c11", "-O2", "-Wall", "-Wextra", "-Werror",
        "-I", str(root / "driver/tests/compute_submit_stubs"),
        "-I", str(root / "driver"), "-I", tmp,
        str(root / "driver/tests/opengpu_compute_uniform_flow_fixture.c"),
        "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
