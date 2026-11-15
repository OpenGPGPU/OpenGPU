#!/usr/bin/env python3
"""Validate internal GVM2 with original GNDs and the actual GVS owner/MMU."""
import argparse
import os
from pathlib import Path
import re
import shlex
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--negative-controls', action='store_true')
    args = parser.parse_args()
    artifacts = ROOT / '.artifacts'
    artifacts.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='vertex-plan-', dir=artifacts) as temporary:
        out = Path(temporary)
        hw = (ROOT / 'driver/opengpu_hw.c').read_text()
        first = hw.index('int opengpu_hw_snapshot_submission_lock(')
        last = hw.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(hw[first:last])
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if args.sanitize or os.environ.get('OPENGPU_TEST_SANITIZERS') == '1':
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, ROOT / 'driver/tests', ROOT / 'driver',
                    ROOT / 'driver/tests/private_snapshot_stubs',
                    ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver/tests/compute_submit_stubs']
        command = shlex.split(os.environ.get('CC', 'cc')) + flags + ['-I' + str(p) for p in includes]
        sources = [str(ROOT / 'driver/tests/opengpu_tessellation_vertex_plan_test.c'),
                   str(ROOT / 'driver/opengpu_native_frame.c')]
        env = dict(os.environ, TMPDIR=temporary)

        def compile_run(name, override=None):
            binary = out / name
            defines = [] if override is None else ['-DOPENGPU_VERTEX_OUTER_SOURCE="' + str(override) + '"']
            subprocess.run(command + defines + sources + ['-o', str(binary)], check=True, env=env)
            return subprocess.run([str(binary)], capture_output=True, text=True, env=env)

        result = compile_run('positive')
        print(result.stdout, end='')
        if result.returncode:
            raise SystemExit(result.stderr or result.returncode)
        if args.negative_controls:
            production = (ROOT / 'driver/opengpu_tessellation_vertex_plan.c').read_text()
            mutations = {
                'catalog-by-plan-array-position': (
                    '&c->plans[c->draw_index[r->original_draw_index]-1]', '&c->plans[r->original_draw_index]'),
                'live-original-after-callback': (
                    's.draw=c->draws+(size_t)r->original_draw_index*2048;',
                    's.draw=(const u8 *)c->source.draws+(size_t)r->original_draw_index*2048;'),
                'live-callbacks-after-mutation': ('&s,&c->copy,&root', '&s,copy,&root'),
                'ignore-range-boundaries': ('if(r->flags!=flags)return -EINVAL;', 'if(false && r->flags!=flags)return -EINVAL;'),
                'ignore-original-cover': ('first!=patch', 'false'),
                'ignore-explicit-invocation': ('r->invocation_index!=invocation', 'false'),
                'wrong-original-draw-bytes': ('s.draw=c->draws+(size_t)r->original_draw_index*2048;',
                                             's.draw=c->draws;'),
                'drop-index-provenance': ('s.plan.indices=p->indices;', ''),
                'executable-map': ('opengpu_private_snapshot_append(owner,c->map,',
                                   'opengpu_private_snapshot_append_code(owner,c->map,'),
                'continuation-switches-catalog': ('outer_word(r,1)!=last_draw',
                                                  '(outer_word(r,1)!=last_draw && false)'),
            }
            for name, (before, after) in mutations.items():
                if production.count(before) != 1:
                    raise SystemExit('negative-control anchor must be unique: ' + name)
                path = out / (name + '.c')
                path.write_text(production.replace(before, after))
                mutant = compile_run(name, path)
                if not mutant.returncode or not re.search(r'assertion.*failed', mutant.stderr, re.I | re.S):
                    raise SystemExit('negative control lacked a semantic assertion: ' + name + '\n' + mutant.stderr)
                compact = re.sub(r'\s+', '', mutant.stderr)
                expected = ('got==expected&&out==0x5a5a5a5a' if name in {
                    'ignore-range-boundaries', 'ignore-original-cover', 'ignore-explicit-invocation'} else
                    '(leaf&15u)==permissions' if name == 'executable-map' else
                    '!opengpu_tessellation_vertex_outer_map_canonical(words,sizeof(words))' if
                    name == 'continuation-switches-catalog' else
                    '!opengpu_tessellation_vertex_outer_append(o,&f->source,&f->callbacks,&out)')
                if expected not in compact:
                    raise SystemExit('negative control failed at an unexpected assertion: ' + name + '\n' + mutant.stderr)
                print('GVM2 semantic negative control rejected: ' + name + ' -> ' + expected)


if __name__ == '__main__':
    main()
