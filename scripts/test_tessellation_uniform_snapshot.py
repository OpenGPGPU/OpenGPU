#!/usr/bin/env python3
"""Exercise the independent TCS/TES uniform encoder with the real owner and MMU."""
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
    parser.add_argument("--negative-controls", action="store_true",
                        help="compile isolated wrong-stage/mask/dedup/reserved/capture variants")
    args = parser.parse_args()
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='j', dir=temporary_root) as temporary:
        out = Path(temporary)
        source = (ROOT / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(source[first:last])
        flags = ['-std=c11', '-O2', '-Wall', '-Wextra', '-Werror']
        if args.sanitize or os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT / 'driver/tests', ROOT / 'driver',
                    ROOT / 'driver/tests/private_snapshot_stubs',
                    ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(path) for path in includes] +
                       [str(ROOT / 'driver/tests/opengpu_tessellation_uniform_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        subprocess.run([str(out / 'test')], check=True, env=environment)
        if args.negative_controls:
            production = (ROOT / 'driver/opengpu_tessellation_uniform_snapshot.c').read_text()
            mutations = {
                'wrong-stage': ('ROOT_STAGE_WORD,stage+OPENGPU_TESSELLATION_UNIFORM_CONTROL_STAGE);',
                                'ROOT_STAGE_WORD,stage);'),
                'wrong-mask': ('1u<<(stage+OPENGPU_TESSELLATION_UNIFORM_CONTROL_STAGE)', '1u<<stage'),
                'merge-different-view': ('copies[slot].view.offset==v->offset && copies[slot].view.bytes==v->bytes', 'true'),
                'reserved-word': ('tess_word(data,offset+8,OPENGPU_TESSELLATION_UNIFORM_VERSION);',
                                  'tess_word(data,offset+8,OPENGPU_TESSELLATION_UNIFORM_VERSION);tess_word(data,offset+60,1);'),
                'live-plan': ('stages=captured;', '(void)captured;'),
            }
            for name, (before, after) in mutations.items():
                assert before in production
                mutant = out / (name + '.c')
                mutant.write_text(production.replace(before, after))
                binary = out / name
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               ['-DOPENGPU_TESS_UNIFORM_SNAPSHOT_SOURCE="' + str(mutant) + '"',
                                str(ROOT / 'driver/tests/opengpu_tessellation_uniform_snapshot_test.c'),
                                '-o', str(binary)], check=True, env=environment)
                result = subprocess.run([str(binary)], capture_output=True, text=True, env=environment)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail its semantic assertion: ' + name)
                print('GUR3 semantic negative control rejected: ' + name)



if __name__ == '__main__':
    main()
