#!/usr/bin/env python3
"""Explicit offline GS-linked FS5/7 byte -> real validator/private RX/MMU CPU consumer.

No shader interpretation, hardware discovery, permission grant or GPU execution.
The original no-argument native-program fixture is always run unchanged first.
"""
import argparse
import hashlib
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import tempfile

from owner_process_lifetime import CleanupIncomplete, stop_group
ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def artifact(parser, fields):
    raw, abi, varyings, colors, uniforms, geometry, sample = fields
    for value, maximum in ((abi, 7), (varyings, 16), (colors, 4), (uniforms, 1), (geometry, 1), (sample, 1)):
        if not re.fullmatch(r'[0-9]+', value) or len(value)>2 or int(value)>maximum:
            parser.error('invalid explicit offline fragment profile')
    if abi not in ('5','7') or geometry!='1' or sample!=('1' if abi=='7' else '0') or uniforms not in ('0','1'):
        parser.error('ABI5/7 requires explicit geometry1, sample0/1 respectively, uniform0|1')
    path = Path(raw).absolute()
    if path.is_symlink() or not path.is_file():
        parser.error('fragment artifact must be a regular nonsymlink file')
    path = path.resolve(strict=True)
    if not path.stat().st_size or path.stat().st_size > 2048 or path.stat().st_size % 4:
        parser.error('fragment artifact requires1..512 complete LE instruction words')
    return [str(path),abi,str(int(varyings)),str(int(colors)),uniforms,geometry,sample]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--fragment-artifact', nargs=7, action='append', default=[],
                        metavar=('PATH','ABI','VARYINGS','COLORS','OWNED_UNIFORMS','GEOMETRY','SAMPLE'),
                        help='repeat per original FS program; explicit synthetic CPU profile, capacity128; never imports producer authority')
    args = parser.parse_args()
    inputs = [artifact(parser, fields) for fields in args.fragment_artifact]
    dependency = ROOT
    for name in ('driver/opengpu_private_snapshot.h', 'driver/tests/opengpu_private_snapshot_test.c'):
        if not (dependency / name).is_file():
            parser.error('actual private owner prerequisite missing: '+str(dependency / name))
    bound = {path.resolve(): digest(path) for base in {ROOT / 'driver', dependency / 'driver'}
             for path in base.rglob('*') if path.is_file() and not path.is_symlink()
             and path.suffix in ('.c', '.h', '.inc')}
    for name in ('test_native_geometry_fragment_artifacts.py', 'owner_process_lifetime.py'):
        path = ROOT / 'scripts' / name; bound[path] = digest(path)
    for fields in inputs:
        path = Path(fields[0]); bound[path] = digest(path)
    def unchanged():
        assert all(path.is_file() and digest(path) == sha for path, sha in bound.items()), 'offline consumer input changed'
    temporary_root = Path(os.environ.get('TMPDIR', ROOT / '.artifacts')).resolve(strict=True) if 'TMPDIR' in os.environ else ROOT / '.artifacts'
    temporary_root.mkdir(exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix='geometry-fs-rx-', dir=temporary_root))
    preserve = False
    try:
        source = (dependency / 'driver/opengpu_hw.c').read_text()
        first = source.index('int opengpu_hw_snapshot_submission_lock(')
        last = source.index('\n}\n', first) + 2
        generated = out / 'snapshot_submission_under_test.inc'
        generated.write_text(source[first:last]); bound[generated] = digest(generated)
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror']
        if args.sanitize: flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = [out, dependency / 'driver/tests', dependency / 'driver',
                    dependency / 'driver/tests/private_snapshot_stubs',
                    dependency / 'driver/tests/mmu_stubs', dependency / 'driver/tests/compute_submit_stubs']
        compiler = shlex.split(os.environ.get('CC', 'cc'))
        executable = Path(shutil.which(compiler[0]) or compiler[0]).resolve(strict=True)
        bound[executable] = digest(executable)
        binary = out / 'test'; mmd = out / 'test.d'
        def run(argv):
            unchanged()
            process = subprocess.Popen(list(map(str, argv)), cwd=out, env=dict(os.environ, TMPDIR=str(out)),
                                       start_new_session=True)
            try:
                code = process.wait(timeout=180)
            finally:
                stop_group(process)
            unchanged()
            if code: raise subprocess.CalledProcessError(code, argv)
        run(compiler + flags + ['-I'+str(path) for path in includes] + ['-MMD', '-MF', str(mmd),
            str(ROOT / 'driver/tests/opengpu_native_geometry_fragment_artifact_test.c'), '-o', str(binary)])
        for name in shlex.split(mmd.read_text().replace('\\\n', '').split(':', 1)[1]):
            path = Path(name).resolve(strict=True)
            assert path in bound, 'undeclared actual MMD dependency: '+str(path)
        bound[mmd] = digest(mmd); bound[binary] = digest(binary)
        arguments = [binary]
        for fields in inputs: arguments += ['--fragment-artifact', *fields]
        run(arguments)
        print('Offline GS-linked FS artifacts / original fixture / actual validator RX MMU PASS; CPU only, GPU=false')
    except CleanupIncomplete:
        preserve = True
        print('Consumer exit unknown; preserving active inputs at '+str(out), flush=True)
        raise
    finally:
        if not preserve: shutil.rmtree(out)


if __name__ == '__main__': main()
