#!/usr/bin/env python3
"""Test the real Gallium graphics constant-buffer snapshot and bounds callback."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
mesa = Path(os.environ.get('OPENGPU_MESA_DIR', ROOT / 'depends/mesa'))
drm = Path(os.environ.get('OPENGPU_DRM_INCLUDE', ROOT / 'depends/aarch64-root/include'))
if not (mesa / 'src/gallium/include/pipe/p_screen.h').is_file():
    print('Gallium graphics constants: SKIP (set OPENGPU_MESA_DIR to Mesa 22.3.6)')
    sys.exit(0)
if not (mesa / 'VERSION').read_text().strip().startswith('22.3.6'):
    raise SystemExit('Gallium graphics constants require Mesa 22.3.6')
if not (drm / 'drm/drm.h').is_file():
    raise SystemExit('Gallium graphics constants require DRM UAPI headers')
includes = [ROOT / 'userspace/gallium', ROOT / 'userspace', ROOT / 'driver', drm,
            mesa / 'src/gallium/include', mesa / 'src/gallium/auxiliary',
            mesa / 'src', mesa / 'include']
flags = ['-std=c11', '-O1', '-g', '-ffunction-sections', '-fdata-sections',
         '-D_POSIX_C_SOURCE=200809L', '-DHAVE_PTHREAD', '-DHAVE_STRUCT_TIMESPEC',
         '-DHAVE_TIMESPEC_GET'] + ['-I' + str(p) for p in includes]
if sys.platform == 'darwin':
    flags += ['-D__user=', '-D__aligned_u64=__u64']
    link = ['-Wl,-dead_strip']
else:
    flags += ['-DHAVE_ENDIAN_H']
    link = ['-Wl,--gc-sections']
with tempfile.TemporaryDirectory(prefix='opengpu-graphics-constants-') as tmp:
    binary = Path(tmp) / 'test'
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                   [str(ROOT / 'userspace/tests/gallium_graphics_constants.c')] +
                   link + ['-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
