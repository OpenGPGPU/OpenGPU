#!/usr/bin/env python3
"""Compose protected tessellation with actual owner, MMU and original typed sources."""
import argparse
import os
import re
from pathlib import Path
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--negative-controls', action='store_true')
    args = parser.parse_args()
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='j', dir=temporary_root) as temporary:
        out = Path(temporary)
        source = (ROOT / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(source[first:last])
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if args.sanitize or os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT / 'driver/tests', ROOT / 'driver',
                    ROOT / 'driver/tests/private_snapshot_stubs',
                    ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        environment.setdefault('ASAN_OPTIONS', 'quarantine_size_mb=16')
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(path) for path in includes] +
                       [str(ROOT / 'driver/tests/opengpu_protected_tessellation_test.c'),
                        str(ROOT / 'driver/opengpu_native_frame.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        subprocess.run([str(out / 'test')], check=True, env=environment)
        if args.negative_controls:
            production = (ROOT / 'driver/opengpu_protected_tessellation.c').read_text()
            mutations = {
                'unverified-availability': ('if(!opengpu_protected_tessellation_profile_valid(&source->profile))return -EOPNOTSUPP;', 'if(!opengpu_protected_tessellation_profile_valid(&source->profile) && false)return -EOPNOTSUPP;'),
                'no-patch-initialization': ('p->available && p->patch_zero_initialized &&', 'p->available &&'),
                'cross-domain-header': ('pt_get(d,0)!=l->magic ||', '(pt_get(d,0)<0x474e5401u || pt_get(d,0)>0x474e5406u) ||'),
                'unchecked-extension': ('!pt_zero(d+496*4,16*4) ||', 'false ||'),
                'skip-physical-alias': ('ret=pt_alias(s,copy);if(ret)goto done;', 'ret=pt_alias(s,copy);if(ret && ret!=-EINVAL)goto done;'),
                'logical-only-alias': ('first&~63ULL,(end+63u)&~63ULL', 'first,end'),
                'live-plans': ('c->source.plans=c->owned[4];', 'c->source.plans=s->plans;'),
            }
            for name, (before, after) in mutations.items():
                assert before in production
                mutant = out / (name + '.c');mutant.write_text(production.replace(before, after))
                binary = out / name
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               ['-DOPENGPU_PROTECTED_TESS_SOURCE="' + str(mutant) + '"',
                                str(ROOT / 'driver/tests/opengpu_protected_tessellation_test.c'),
                                str(ROOT / 'driver/opengpu_native_frame.c'), '-o', str(binary)], check=True, env=environment)
                result = subprocess.run([str(binary)], capture_output=True, text=True, env=environment)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail semantic assertion: ' + name)
                print('protected tessellation semantic negative control rejected: ' + name)



if __name__ == '__main__':
    main()
