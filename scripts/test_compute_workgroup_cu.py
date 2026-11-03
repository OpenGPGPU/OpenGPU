#!/usr/bin/env python3
"""Execute bounded workgroups on an existing product-profile CU model."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True, help='new local output directory')
    args = parser.parse_args()
    model, output = args.model_dir.resolve(), args.output.resolve()
    manifest = json.loads((model / 'fixture-manifest.json').read_text())
    build = json.loads((model / 'build.json').read_text())
    if (manifest.get('lanes'), manifest.get('warps')) != (16, 8) or build.get('exit_code') != 0:
        raise ValueError('a completed 16-lane, eight-warp model is required')
    if 'GpuComputeUnit' not in build['context']['hierarchy']['modules']:
        raise ValueError('the model must include the GpuComputeUnit hierarchy library')
    if subprocess.check_output(['verilator', '--version'], text=True) != build['context']['tools']['verilator']:
        raise ValueError('Verilator version differs from the model build')
    details = subprocess.check_output(['verilator', '-V'], text=True)
    match = re.search(r'^\s*VERILATOR_ROOT\s*=\s*(\S+)', details, re.MULTILINE)
    if not match:
        raise ValueError('cannot find the Verilator include directory')
    include = Path(match.group(1)) / 'include'
    build_dir = model / 'build'
    library = build_dir / 'VGpuComputeUnit/libGpuComputeUnit.a'
    inputs = [library, build_dir / 'VGpuComputeUnit/VGpuComputeUnit.h',
              build_dir / 'verilated.o', build_dir / 'verilated_dpi.o',
              build_dir / 'verilated_threads.o', include / 'verilated.h']
    for path in inputs:
        if not path.is_file():
            raise FileNotFoundError(path)
    cxx = shlex.split(os.environ.get('CXX', 'c++'))
    if not cxx:
        raise ValueError('CXX must name a compiler')
    output.mkdir(parents=True, exist_ok=False)
    simulator = output / 'workgroup-cu'
    link_flags = ['-Wl,-U,__Z15vl_time_stamp64v,-U,__Z13sc_time_stampv,-U,_vlog_startup_routines'] \
        if sys.platform == 'darwin' else []
    subprocess.run(cxx + ['-std=c++17', '-O1', '-I' + str(ROOT / 'driver'),
        '-I' + str(build_dir / 'VGpuComputeUnit'), '-I' + str(include), '-I' + str(include / 'vltstd'),
        str(ROOT / 'driver/tests/opengpu_compute_workgroup_cu.cpp'), str(library),
        str(build_dir / 'verilated.o'), str(build_dir / 'verilated_dpi.o'),
        str(build_dir / 'verilated_threads.o')] + link_flags + ['-pthread', '-o', str(simulator)],
        check=True)
    print('CU library SHA-256:', hashlib.sha256(library.read_bytes()).hexdigest(), flush=True)
    for shape in ((128, 1, 1), (8, 4, 4), (33, 1, 1)):
        subprocess.run([str(simulator), *map(str, shape)], check=True)
    control = subprocess.run([str(simulator), '128', '1', '1', 'wrong'],
                             text=True, capture_output=True)
    if control.returncode == 0 or 'workgroup mismatch' not in control.stderr:
        raise RuntimeError('incorrect-output negative control did not fail')
    print('COMPUTE_WORKGROUP_CU PASS shapes=3 negative_controls=1', flush=True)


if __name__ == '__main__':
    main()
