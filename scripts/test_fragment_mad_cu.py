#!/usr/bin/env python3
"""Run small emitted fragment programs on a prebuilt product-profile CU."""
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
    if sys.byteorder != 'little':
        raise ValueError('the emitted fixture requires a little-endian host')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model-dir', type=Path, required=True,
                        help='completed native product runner output directory')
    parser.add_argument('--output', type=Path, required=True,
                        help='new local output directory')
    parser.add_argument('--mesa-dir', type=Path, default=ROOT / 'depends/mesa')
    parser.add_argument('--drm-include', type=Path, default=ROOT / 'depends/aarch64-root/include')
    args = parser.parse_args()
    model = args.model_dir.resolve()
    output = args.output.resolve()
    mesa = args.mesa_dir.resolve()
    drm = args.drm_include.resolve()
    manifest = json.loads((model / 'fixture-manifest.json').read_text())
    build = json.loads((model / 'build.json').read_text())
    if (manifest.get('lanes'), manifest.get('warps')) != (16, 8) or build.get('exit_code') != 0:
        raise ValueError('a completed 16-lane, 8-warp model is required')
    if 'GpuComputeUnit' not in build['context']['hierarchy']['modules']:
        raise ValueError('model build must include the GpuComputeUnit hierarchy library')
    version = subprocess.check_output(['verilator', '--version'], text=True)
    if version != build['context']['tools']['verilator']:
        raise ValueError('Verilator version differs from the model build')
    details = subprocess.check_output(['verilator', '-V'], text=True)
    match = re.search(r'^\s*VERILATOR_ROOT\s*=\s*(\S+)', details, re.MULTILINE)
    if not match:
        raise ValueError('cannot find the Verilator include directory')
    include = Path(match.group(1)) / 'include'
    build_dir = model / 'build'
    library = build_dir / 'VGpuComputeUnit/libGpuComputeUnit.a'
    cc = shlex.split(os.environ.get('CC', 'cc'))
    cxx = shlex.split(os.environ.get('CXX', 'c++'))
    if not cc or not cxx:
        raise ValueError('CC and CXX must name compilers')
    includes = [ROOT / 'userspace/gallium', ROOT / 'userspace', ROOT / 'driver', drm,
                mesa / 'src/gallium/include', mesa / 'src/gallium/auxiliary',
                mesa / 'src', mesa / 'include']
    sources = [ROOT / 'userspace/tests/fs_fragcoord_mad_fixture.c',
               ROOT / 'userspace/gallium/opengpu_fs_emit.c',
               mesa / 'src/gallium/auxiliary/tgsi/tgsi_parse.c',
               mesa / 'src/gallium/auxiliary/tgsi/tgsi_build.c']
    for path in [*sources, drm / 'drm/drm.h', library,
                 build_dir / 'VGpuComputeUnit/VGpuComputeUnit.h',
                 build_dir / 'verilated.o', build_dir / 'verilated_dpi.o',
                 build_dir / 'verilated_threads.o', include / 'verilated.h']:
        if not path.is_file():
            raise FileNotFoundError(path)
    output.mkdir(parents=True, exist_ok=False)
    fixture = output / 'fragment-fixture'
    platform_flags = ['-D__aligned_u64=__u64'] if sys.platform == 'darwin' else [
        '-DHAVE_ENDIAN_H', '-D_POSIX_C_SOURCE=200809L']
    strip = ['-Wl,-dead_strip'] if sys.platform == 'darwin' else ['-Wl,--gc-sections']
    subprocess.run(cc + platform_flags + ['-std=c11', '-O2', '-ffunction-sections',
        '-fdata-sections'] + ['-I' + str(p) for p in includes] + [str(p) for p in sources] +
        strip + ['-o', str(fixture)], check=True)
    simulator = output / 'fragment-cu'
    link_flags = ['-Wl,-U,__Z15vl_time_stamp64v,-U,__Z13sc_time_stampv,-U,_vlog_startup_routines'] \
        if sys.platform == 'darwin' else []
    subprocess.run(cxx + ['-std=c++17', '-O1', '-I' + str(build_dir / 'VGpuComputeUnit'),
        '-I' + str(include), '-I' + str(include / 'vltstd'),
        str(ROOT / 'userspace/tests/fs_fragcoord_mad_cu.cpp'), str(library),
        str(build_dir / 'verilated.o'), str(build_dir / 'verilated_dpi.o'),
        str(build_dir / 'verilated_threads.o')] + link_flags + ['-pthread', '-o', str(simulator)],
        check=True)
    print('CU library SHA-256:', hashlib.sha256(library.read_bytes()).hexdigest(), flush=True)
    subprocess.run([str(simulator)], check=True)
    for opcode in ('mad', 'fma'):
        binary = output / (opcode + '.bin')
        subprocess.run([str(fixture), opcode, str(binary)], check=True)
        for scenario in range(3):
            subprocess.run([str(simulator), str(binary), str(scenario)], check=True)
    for opcode in ('mad-cancel', 'fma-cancel', 'separate-cancel'):
        binary = output / (opcode + '.bin')
        subprocess.run([str(fixture), opcode, str(binary)], check=True)
        subprocess.run([str(simulator), str(binary), '4' if opcode.startswith('separate') else '3'],
                       check=True)
    for opcode, scenario, mismatch in (
        ('separate-cancel', '3', 'got=000000ff expected=800000ff'),
        ('fma-cancel', '4', 'got=800000ff expected=000000ff'),
    ):
        control = subprocess.run([str(simulator), str(output / (opcode + '.bin')), scenario],
                                 text=True, capture_output=True)
        if control.returncode == 0 or mismatch not in control.stderr:
            raise RuntimeError('fused/separate negative control did not fail as expected')
    print('FRAGMENT_MAD_CU PASS position_cases=6 cancellation_cases=3 negative_controls=2 lanes=4',
          flush=True)


if __name__ == '__main__':
    main()
