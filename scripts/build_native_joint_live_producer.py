#!/usr/bin/env python3
"""Build the original Mesa joint DRM producer with the pinned Linux SDK."""
from pathlib import Path
import runpy
import tempfile

import test_native_joint as original


def main():
    kernel = 'scripts/native_joint_live_kernel.c'
    units = ('userspace/tests/native_joint_resources.c', 'userspace/tests/native_joint_frontend.cpp')
    original.GPU_UNITS = tuple(unit for unit in original.GPU_UNITS if unit not in units) + (
        'userspace/gallium/opengpu_native_joint_build_source.c',
        'userspace/tests/native_joint_live_flow.c', kernel,
    )
    original.GPU_UNIT_FLAGS = {kernel: [
        '-I' + str(original.ROOT / 'driver/tests' / name)
        for name in ('private_snapshot_stubs', 'mmu_stubs', 'compute_submit_stubs')
    ]}
    with tempfile.TemporaryDirectory(prefix='joint-mmio-headers-') as directory:
        path = Path(directory)
        generator = runpy.run_path(str(original.ROOT / 'scripts/test_native_mmio_capture.py'))
        generator['write_bodies'](path)
        original.GENERATED_INPUTS = {file.name: file.read_text() for file in sorted(path.iterdir())}
        original.__doc__ = __doc__
        original.main(build_only=True)


if __name__ == '__main__':
    main()
