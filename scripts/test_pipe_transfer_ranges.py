#!/usr/bin/env python3
"""Test winsys transfer range admission without a GPU."""
import os
from pathlib import Path
import shlex
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
drm_env = os.environ.get('OPENGPU_DRM_INCLUDE')
drm = Path(drm_env) if drm_env else root / 'userspace/tests/pipe_transfer_stubs'
if not (drm / 'drm/drm.h').is_file():
    raise SystemExit('winsys transfer ranges: OPENGPU_DRM_INCLUDE lacks DRM UAPI headers')
flags = ['-std=c11', '-O1', '-g', '-ffunction-sections', '-fdata-sections',
         '-D_POSIX_C_SOURCE=200809L', '-I' + str(root / 'userspace'),
         '-I' + str(root / 'driver'), '-I' + str(drm)]
if sys.platform == 'darwin' and drm_env:
    flags += ['-D__user=', '-D__aligned_u64=__u64']
if sys.platform == 'darwin':
    link = ['-Wl,-dead_strip']
else:
    link = ['-Wl,--gc-sections']
with tempfile.TemporaryDirectory(prefix='opengpu-transfer-ranges-') as tmp:
    binary = Path(tmp) / 'test'
    subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                   [str(root / 'userspace/tests/pipe_transfer_ranges.c')] +
                   link + ['-o', str(binary)], check=True)
    subprocess.run([str(binary)], check=True)
