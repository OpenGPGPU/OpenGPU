#!/usr/bin/env python3
"""Test Gallium vertex fetch against the matching native Mesa format library."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]
mesa = Path(os.environ.get('OPENGPU_MESA_DIR', ROOT / 'depends/mesa'))
build = Path(os.environ.get('OPENGPU_MESA_BUILD', ROOT / 'depends/mesa-build'))
drm = Path(os.environ.get('OPENGPU_DRM_INCLUDE', ROOT / 'depends/aarch64-root/include'))
archives = [build / 'src/util/format/libmesa_format.a', build / 'src/util/libmesa_util.a']
if not (mesa / 'src/gallium/include/pipe/p_screen.h').is_file() or not all(p.is_file() for p in archives):
    print('Gallium vertex formats: SKIP (set OPENGPU_MESA_DIR and OPENGPU_MESA_BUILD to a native Mesa build)')
    sys.exit(0)
if not (mesa / 'VERSION').read_text().strip().startswith('22.3.6'):
    raise SystemExit('Gallium vertex formats require Mesa 22.3.6')
if not (drm / 'drm/drm.h').is_file():
    raise SystemExit('Gallium vertex formats require DRM UAPI headers')
includes = [ROOT / 'userspace/gallium', ROOT / 'userspace', ROOT / 'driver', drm,
            mesa / 'src/gallium/include', mesa / 'src/gallium/auxiliary',
            mesa / 'src', mesa / 'include', build / 'src', build / 'src/util/format']
flags = ['-std=c11', '-O1', '-g', '-ffunction-sections', '-fdata-sections',
         '-D_POSIX_C_SOURCE=200809L', '-DHAVE_PTHREAD', '-DHAVE_STRUCT_TIMESPEC',
         '-DHAVE_TIMESPEC_GET'] + ['-I' + str(p) for p in includes]
if sys.platform == 'darwin':
    flags += ['-D__user=', '-D__aligned_u64=__u64']
    link = ['-Wl,-dead_strip', *map(str, archives), '-lpthread', '-lm']
else:
    flags += ['-DHAVE_ENDIAN_H']
    link = ['-Wl,--gc-sections', '-Wl,--start-group', *map(str, archives),
            '-Wl,--end-group', '-lpthread', '-lm', '-ldl']
with tempfile.TemporaryDirectory(prefix='opengpu-vertex-formats-') as tmp:
    binary = Path(tmp) / 'test'
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                   [str(ROOT / 'userspace/tests/gallium_vertex_formats.c')] +
                   link + ['-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
