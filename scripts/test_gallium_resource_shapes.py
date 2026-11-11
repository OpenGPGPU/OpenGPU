#!/usr/bin/env python3
"""Exercise actual Gallium resource callbacks against mocked GEM allocators."""
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
    print('Gallium resource shape fixture: SKIP (set OPENGPU_MESA_DIR to Mesa 22.3.6)')
    sys.exit(0)
if not (drm / 'drm/drm.h').is_file():
    raise SystemExit('Gallium resource shape fixture: set OPENGPU_DRM_INCLUDE to DRM UAPI headers')
if not (mesa / 'VERSION').read_text().strip().startswith('22.3.6'):
    raise SystemExit('Gallium resource shape fixture requires Mesa 22.3.6')
includes = [ROOT / 'userspace/gallium', ROOT / 'userspace', ROOT / 'driver', drm,
            mesa / 'src/gallium/include', mesa / 'src/gallium/auxiliary',
            mesa / 'src', mesa / 'include']
flags = ['-std=c11', '-O1', '-g', '-ffunction-sections', '-fdata-sections',
         '-D_POSIX_C_SOURCE=200809L', '-DHAVE_PTHREAD', '-DHAVE_STRUCT_TIMESPEC',
         '-DHAVE_TIMESPEC_GET'] + ['-I' + str(p) for p in includes]
if sys.platform == 'darwin':
    flags += ['-D__user=', '-D__aligned_u64=__u64']
elif sys.platform == 'linux':
    flags += ['-DHAVE_ENDIAN_H']
link = ['-Wl,-dead_strip'] if sys.platform == 'darwin' else ['-Wl,--gc-sections']
with tempfile.TemporaryDirectory(prefix='opengpu-resource-shape-') as tmp:
    binary = Path(tmp) / 'test'
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                   [str(ROOT / 'userspace/tests/gallium_resource_shape_fixture.c')] +
                   link + ['-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
