#!/usr/bin/env python3
"""Explicit real-Mesa TGSI/emitter/validator CPU test; never starts a GPU."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    mesa = Path(os.environ.get('OPENGPU_MESA_DIR', ROOT / 'depends/mesa')).resolve()
    if not (mesa / 'VERSION').is_file() or (mesa / 'VERSION').read_text().strip() != '22.3.6':
        raise SystemExit('set OPENGPU_MESA_DIR to actual Mesa 22.3.6 sources')
    drm = Path(os.environ.get('OPENGPU_DRM_INCLUDE', ROOT / 'depends/aarch64-root/include')).resolve()
    if not (drm / 'drm/drm.h').is_file():
        raise SystemExit('set OPENGPU_DRM_INCLUDE to actual DRM UAPI headers')
    includes = [ROOT / 'userspace/gallium', ROOT / 'userspace', ROOT / 'driver', drm,
                mesa / 'src/gallium/include', mesa / 'src/gallium/auxiliary', mesa / 'src', mesa / 'include']
    flags = ['-std=c11', '-O1', '-g', '-Wall', '-Wextra', '-Werror',
             '-Wno-unused-parameter', '-Wno-sign-compare', '-ffunction-sections', '-fdata-sections']
    if sys.platform == 'linux':
        flags += ['-DHAVE_ENDIAN_H']
    elif sys.platform == 'darwin':
        flags += ['-D__user=', '-D__aligned_u64=__u64']
    link = ['-Wl,-dead_strip'] if sys.platform == 'darwin' else ['-Wl,--gc-sections']
    if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    sources = [ROOT / 'userspace/tests/cs_shared_own_stripe.c',
               ROOT / 'userspace/gallium/opengpu_cs_emit.c',
               mesa / 'src/gallium/auxiliary/tgsi/tgsi_build.c',
               mesa / 'src/gallium/auxiliary/tgsi/tgsi_parse.c']
    parent = ROOT / '.artifacts'; parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='own-stripe-', dir=parent) as directory:
        binary = Path(directory) / 'test'
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(p) for p in includes] + [str(p) for p in sources] +
                       link + ['-o', str(binary)], check=True, env=dict(os.environ, TMPDIR=directory))
        subprocess.run([str(binary)], check=True, env=dict(os.environ, TMPDIR=directory))
        atomic_binary = Path(directory) / 'atomics'
        atomic_sources = [ROOT / 'userspace/tests/cs_shared_atomics.c'] + sources[1:]
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(p) for p in includes] + [str(p) for p in atomic_sources] +
                       link + ['-o', str(atomic_binary)], check=True, env=dict(os.environ, TMPDIR=directory))
        subprocess.run([str(atomic_binary)], check=True, env=dict(os.environ, TMPDIR=directory))
        register_binary = Path(directory) / 'registers'
        register_sources = [ROOT / 'userspace/tests/cs_shared_registers.c',
                            mesa / 'src/gallium/auxiliary/tgsi/tgsi_build.c',
                            mesa / 'src/gallium/auxiliary/tgsi/tgsi_parse.c']
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(p) for p in includes] + [str(p) for p in register_sources] +
                       link + ['-o', str(register_binary)], check=True, env=dict(os.environ, TMPDIR=directory))
        subprocess.run([str(register_binary)], check=True, env=dict(os.environ, TMPDIR=directory))


if __name__ == '__main__':
    main()
