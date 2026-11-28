#!/usr/bin/env python3
import os
import re
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    drm = (ROOT / 'driver/opengpu_drm_device.c').read_text()
    submit = (ROOT / 'driver/opengpu_native_submit.c').read_text()
    uapi = (ROOT / 'driver/opengpu_drm.h').read_text()
    hw = (ROOT / 'driver/opengpu_hw.c').read_text()
    assert 'OPENGPU_SUBMIT_TESSELLATION_JOINT_GEOMETRY' not in drm
    assert 'OPENGPU_SUBMIT_TESSELLATION_JOINT_GEOMETRY' not in uapi
    assert 'opengpu_protected_tessellation_joint_geometry_ioctl' not in submit
    source_mask = re.search(
        r'source_flags\s*=\s*gpu->hw\.compute_resource_flags\s*&\s*~\((.*?)\);',
        hw, re.S)
    assert source_mask
    assert 'OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_V1' in source_mask.group(1)
    assert 'OPENGPU_COMPUTE_RESOURCE_JOINT_TESSELLATION_GEOMETRY_V1' in source_mask.group(1)
    parent = ROOT / '.artifacts'
    parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='geometry-host-', dir=parent) as directory:
        binary = Path(directory) / 'test'
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(ROOT / 'driver'),
                        str(ROOT / 'driver/tests/opengpu_tessellation_joint_geometry_host_test.c'),
                        '-o', str(binary)], check=True)
        subprocess.run([str(binary)], check=True)
    print('private geometry host public-ioctl exclusion: PASS')


if __name__ == '__main__':
    main()
