#!/usr/bin/env python3
"""Run real OpenGPU map callbacks with Mesa 22.3 copy/clear helpers and mocked GEMs."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
mesa = Path(os.environ.get('OPENGPU_MESA_DIR', ROOT / 'depends/mesa'))
drm = Path(os.environ.get('OPENGPU_DRM_INCLUDE', ROOT / 'depends/aarch64-root/include'))
if not (mesa / 'src/gallium/auxiliary/util/u_surface.c').is_file():
    print('Gallium transfer fixture: SKIP (set OPENGPU_MESA_DIR to Mesa 22.3.6)')
    sys.exit(0)
if not (drm / 'drm/drm.h').is_file():
    raise SystemExit('Gallium transfer fixture: set OPENGPU_DRM_INCLUDE to DRM UAPI headers')
if not (mesa / 'VERSION').read_text().strip().startswith('22.3.6'):
    raise SystemExit('Gallium transfer fixture requires Mesa 22.3.6')

compiler = shlex.split(os.environ.get('CC', 'cc'))
includes = [ROOT / 'userspace/gallium', ROOT / 'userspace', ROOT / 'driver', drm,
            mesa / 'src/gallium/include', mesa / 'src/gallium/auxiliary',
            mesa / 'src', mesa / 'include']
flags = ['-std=c11', '-O1', '-g', '-ffunction-sections', '-fdata-sections',
         '-D_POSIX_C_SOURCE=200809L', '-DHAVE_PTHREAD', '-DHAVE_STRUCT_TIMESPEC',
         '-DHAVE_TIMESPEC_GET'] + ['-I' + str(p) for p in includes]
if sys.platform == 'darwin':
    # Uninstalled Linux-tree UAPI headers need the usual headers_install substitutions.
    flags += ['-D__user=', '-D__aligned_u64=__u64']
elif sys.platform == 'linux':
    flags += ['-DHAVE_ENDIAN_H']
link = ['-Wl,-dead_strip'] if sys.platform == 'darwin' else ['-Wl,--gc-sections']
sources = [ROOT / 'userspace/tests/gallium_transfer_fixture.c',
           mesa / 'src/gallium/auxiliary/util/u_surface.c',
           mesa / 'src/gallium/auxiliary/util/u_transfer.c',
           mesa / 'src/util/format/u_format.c']
with tempfile.TemporaryDirectory(prefix='opengpu-transfer-test-') as tmp:
    binary = Path(tmp) / 'test'
    subprocess.run(compiler + flags + [str(x) for x in sources] + link + ['-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
