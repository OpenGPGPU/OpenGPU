#!/usr/bin/env python3
"""Exercise the independent TCS/TES program validator/RX snapshot with the real owner and MMU."""
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
    parser.add_argument('--programs', type=Path, help='directory containing original LE native-tcs.words/native-tes.words')
    parser.add_argument('--tcs-layout', type=int, nargs=5, metavar=('IN_VERTS', 'IN_COMPS', 'OUT_VERTS', 'OUT_COMPS', 'PATCH_COMPS'))
    parser.add_argument('--tes-layout', type=int, nargs=2, metavar=('PATCH_WORDS', 'OUT_COMPS'))
    args = parser.parse_args()
    if args.programs and (args.tcs_layout is None or args.tes_layout is None):
        parser.error('--programs requires exact --tcs-layout and --tes-layout')
    if not args.programs and (args.tcs_layout is not None or args.tes_layout is not None):
        parser.error('layout metadata requires --programs')
    if any(value < 0 or value > 2**32-1 for value in (args.tcs_layout or []) + (args.tes_layout or [])):
        parser.error('layout fields must be u32')
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
                       [str(ROOT / 'driver/tests/opengpu_tessellation_program_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        command = [str(out / 'test')]
        if args.programs:
            command += [str(args.programs.resolve())] + list(map(str, args.tcs_layout + args.tes_layout))
        subprocess.run(command, check=True, env=environment)
        if args.negative_controls:
            production = (ROOT / 'driver/opengpu_tessellation_shader_validator.c').read_text()
            mutations = {
                'undefined-launch-registers': ('s->v[1]=tess_unknown();', 's->v[1]=tess_unknown();for(u32 r=0;r<32;r++)s->x[r]=tess_unknown();'),
                'params-without-stage': ('s->params=unmasked?TESS_ALL_PARAMS:s->mask_version;', 's->params=TESS_ALL_PARAMS;'),
                'barrier-without-drain': ('!depth && !early_exit && !tess_pending(branches)', '((void)early_exit,true)'),
                'publication-bypass': ('!s->invalid && s->emitted && s->ended', 'true'),
                'ignore-uniform-ownership': ('p->owned_uniforms &&', 'true &&'),
                'raw-memory-admission': ('else goto done; /* No raw memory', 'else if(op==0x03 || op==0x23){}else goto done; /* No raw memory'),
                'short-output-as-vl': ('s->x[9].value==p->lanes*p->warps', 's->x[9].value==p->output_vertices'),
            }
            for name, (before, after) in mutations.items():
                assert before in production
                mutant = out / (name + '.c')
                mutant.write_text(production.replace(before, after))
                binary = out / name
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               ['-DOPENGPU_TESS_VALIDATOR_SOURCE="' + str(mutant) + '"',
                                str(ROOT / 'driver/tests/opengpu_tessellation_program_snapshot_test.c'),
                                '-o', str(binary)], check=True, env=environment)
                result = subprocess.run([str(binary)], capture_output=True, text=True, env=environment)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail its semantic assertion: ' + name)
                print('tess program semantic negative control rejected: ' + name)


if __name__ == "__main__":
    main()
