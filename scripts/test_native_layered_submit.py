#!/usr/bin/env python3
"""Versioned layered Linux submission composed with the actual private owner."""
from pathlib import Path
import os
import shlex
import subprocess
import tempfile
import sys
from test_native_submit import write_bodies
ROOT = Path(__file__).resolve().parents[1]
if __name__ == '__main__':
    parent = ROOT / '.artifacts'
    parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='layered-submit-', dir=parent) as directory:
        out = Path(directory)
        write_bodies(out)
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT/'driver', ROOT/'driver/tests', ROOT/'driver/tests/private_snapshot_stubs',
                    ROOT/'driver/tests/mmu_stubs', ROOT/'driver/tests/compute_submit_stubs']
        env = dict(os.environ, TMPDIR=directory)
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags + ['-I'+str(p) for p in includes] + [
            str(ROOT/'driver/tests/opengpu_native_layered_submit_test.c'),
            str(ROOT/'driver/opengpu_native_frame.c'), '-o', str(out/'test')], env=env, check=True)
        subprocess.run([str(out/'test')], env=env, check=True)
        # No host/kernel headers or multilib runtime are involved in the
        # freestanding i386/x64/ARM64 shape checks.
        (out/'drm').mkdir()
        (out/'drm/drm.h').write_text("typedef unsigned int __u32; typedef int __s32; typedef unsigned long long __u64;\n"
            "typedef unsigned long long __aligned_u64 __attribute__((aligned(8)));\n"
            "#define DRM_COMMAND_BASE 0x40\n#define DRM_IOWR(a,b) 0\n#define DRM_IOW(a,b) 0\n")
        compiler = shlex.split(os.environ.get('CC', 'cc'))
        targets = [['-target', target] for target in ('i386-unknown-linux-gnu','x86_64-unknown-linux-gnu','aarch64-unknown-linux-gnu')] if sys.platform == 'darwin' else [[], ['-m32']]
        for target in targets:
            subprocess.run(compiler + target + ['-std=c11','-Wall','-Wextra','-Werror','-D__KERNEL__','-nostdinc',
                '-I'+str(out),'-fsyntax-only',str(ROOT/'driver/tests/opengpu_native_layered_submit_abi_test.c')], env=env, check=True)
        print('layered submit UAPI freestanding 32/64-bit layouts: PASS')
