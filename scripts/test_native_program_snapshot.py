#!/usr/bin/env python3
"""Exercise actual native code admission/owner/MMU; optional real Mesa inputs."""
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
    parser.add_argument('--snapshot-root', type=Path, default=ROOT,
                        help='source tree containing the actual private owner/MMU implementation')
    parser.add_argument('--programs', type=Path,
                        help='active actual graphics fixture directory with four original code-GEM inputs')
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--negative-controls', action='store_true')
    parser.add_argument('--primitive-artifacts', type=Path, nargs='+',
                        help='original OGNSF002 files; CPU-only validator/RX inputs')
    parser.add_argument('--owned-primitive-inputs', action='store_true',
                        help='explicit offline verified-test permission, never hardware discovery')
    args = parser.parse_args()
    if bool(args.primitive_artifacts) != args.owned_primitive_inputs:
        parser.error('--primitive-artifacts requires explicit --owned-primitive-inputs, and vice versa')
    if args.primitive_artifacts and args.programs:
        parser.error('legacy --programs and --primitive-artifacts are separate input contracts')
    dependency = args.snapshot_root.resolve(strict=True)
    for name in ('driver/opengpu_private_snapshot.h', 'driver/tests/opengpu_private_snapshot_test.c'):
        if not (dependency / name).is_file():
            raise SystemExit('actual private owner prerequisite missing: ' + str(dependency / name))
    if args.programs:
        programs = args.programs.resolve(strict=True)
        for name in ('real-vs', 'real-fs', 'full-vs', 'full-fs'):
            if not (programs / ('native-' + name + '.words')).is_file():
                raise SystemExit('actual code-GEM input missing: native-' + name + '.words')
    temporary_root = ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='p', dir=temporary_root) as temporary:
        out = Path(temporary)
        source = (dependency / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        (out / 'snapshot_submission_under_test.inc').write_text(source[first:last])
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if args.sanitize:
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, dependency / 'driver/tests', dependency / 'driver',
                    dependency / 'driver/tests/private_snapshot_stubs',
                    dependency / 'driver/tests/mmu_stubs', dependency / 'driver/tests/compute_submit_stubs']
        environment = dict(os.environ, TMPDIR=temporary)
        subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                       ['-I' + str(path) for path in includes] +
                       [str(ROOT / 'driver/tests/opengpu_native_program_snapshot_test.c'), '-o', str(out / 'test')],
                       check=True, env=environment)
        inputs = ['--primitive-artifacts'] + [str(p.resolve(strict=True)) for p in args.primitive_artifacts] if args.primitive_artifacts else []
        subprocess.run([str(out / 'test')] + ([str(programs)] if args.programs else inputs),
                       check=True, env=environment)
        if args.negative_controls:
            production = (dependency / 'driver/opengpu_native_program_snapshot.c').read_text()
            fixture = (ROOT / 'driver/tests/opengpu_native_program_snapshot_test.c').read_text()
            literal = '#include "../opengpu_native_program_snapshot.c"'
            assert fixture.count(literal) == 1
            for kind in ('view', 'profile'):
                before = f'captured_{kind}=*{kind};{kind}=&captured_{kind};'
                assert production.count(before) == 1
                mutant = out / (kind + '-uncaptured.c')
                mutant.write_text(production.replace(before, f'(void)captured_{kind};'))
                source = out / (kind + '-test.c')
                source.write_text(fixture.replace(literal, '#include "' + str(mutant) + '"'))
                binary = out / (kind + '-test')
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               [str(source), '-o', str(binary)], check=True, env=environment)
                result = subprocess.run([str(binary)], capture_output=True, text=True, env=environment)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail its semantic assertion: ' + kind)
                print('native snapshot semantic negative control rejected: uncaptured-' + kind)
            header = (dependency / 'driver/opengpu_shader_validator.h').read_text()
            for name, before, after in (
                ('primitive-permission', '!primitive_inputs_enabled || geometry_inputs_enabled ||', 'geometry_inputs_enabled ||'),
                ('primitive-gs-isolation', '!primitive_inputs_enabled || geometry_inputs_enabled ||', '!primitive_inputs_enabled ||'),
                ('primitive-sample-permission', '(version==9 && !sample_inputs_enabled)', 'false'),
                ('primitive-input-boundary', 'start=9u+4u*varyings', 'start=10u+4u*varyings'),
                ('primitive-legacy-isolation', 'if(primitive_inputs_enabled)return false;', ''),
            ):
                assert header.count(before) == 1
                mutated_header = out / (name + '.h')
                mutated_header.write_text(header.replace(before, after))
                mutated_source = out / (name + '.c')
                include = '#include "opengpu_shader_validator.h"'
                assert production.count(include) == 1
                mutated_source.write_text(production.replace(include, '#include "' + str(mutated_header) + '"'))
                source = out / (name + '-test.c')
                source.write_text(fixture.replace(literal, '#include "' + str(mutated_source) + '"'))
                binary = out / name
                subprocess.run(shlex.split(os.environ.get('CC', 'cc')) + flags +
                               ['-I' + str(path) for path in includes] +
                               [str(source), '-o', str(binary)], check=True, env=environment)
                result = subprocess.run([str(binary)], capture_output=True, text=True, env=environment)
                if result.returncode == 0 or not re.search(r'assertion.*failed', result.stderr, re.I | re.S):
                    raise SystemExit('negative control did not fail its semantic assertion: ' + name)
                print('native primitive semantic negative control rejected: ' + name)
    if args.programs:
        print('Actual Mesa original code-GEM inputs -> actual private RX owner/MMU: PASS (CPU only)')


if __name__ == '__main__':
    main()
