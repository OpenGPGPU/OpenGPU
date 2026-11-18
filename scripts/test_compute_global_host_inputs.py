#!/usr/bin/env python3
"""CPU-only compiler/lifetime negatives; optional real native-build controls."""
import argparse
from contextlib import contextmanager
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

from compute_global_atomic_cases import CASES
from compute_global_host_inputs import Inputs, REQUIRED, UNRECORDED_ENV, dependency_names
from native_sample_compiler_inputs import sha256

ROOT = Path(__file__).resolve().parents[1]
IMAGE = 'sha256:' + '1' * 64


class CompilerInputs(unittest.TestCase):
    def setUp(self):
        (ROOT / '.artifacts').mkdir(exist_ok=True)
        self.temporary = tempfile.TemporaryDirectory(prefix='global-input-controls-', dir=ROOT / '.artifacts')
        self.addCleanup(self.temporary.cleanup)
        self.out = Path(self.temporary.name)

    def test_full_dependency_parser_preserves_spaces_and_rejects_relative_or_truncated_inputs(self):
        dep = self.out / 'unit.d'
        dep.write_text('/tmp/unit.o: /usr/include/stdint.h \\\n /tmp/generated\\ file.inc\n')
        self.assertEqual(dependency_names(dep), {'/usr/include/stdint.h', '/tmp/generated file.inc'})
        for text in ('not a make dependency', 'out: relative.h', 'out:'):
            dep.write_text(text)
            with self.assertRaises(ValueError):
                dependency_names(dep)

    def test_bad_image_source_qualification_and_environment_fail_before_native_tool_probes(self):
        source = self.out / 'source'
        for name in REQUIRED:
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text('explicit CPU input fixture\n')
        qualified = {name: sha256(source / name) for name in REQUIRED}
        original = dict(schema='opengpu-global-host-inputs-v1', source=str(source),
                        qualified=qualified, compilerImage=IMAGE)
        request = self.out / 'inputs.json'
        controls = []
        for key, value in (('schema', 'foreign'), ('source', str(self.out)), ('compilerImage', 'mutable:tag'),
                           ('qualified', {}), ('qualified', dict(qualified, **{next(iter(REQUIRED)): '0' * 64}))):
            controls.append((dict(original, **{key: value}), {}))
        controls += [(original, {'CPATH': '/foreign/includes'}), (original, {'CC': 'cc -include /foreign.h'}),
                     (original, {'OPENGPU_COMPILER_IMAGE': 'sha256:' + '2' * 64})]
        env = {name: '' for name in UNRECORDED_ENV}
        env.update(OPENGPU_COMPILER_IMAGE=IMAGE, CC='cc')
        with patch('compute_global_host_inputs.subprocess.check_output', side_effect=AssertionError('unexpected native probe')):
            for value, overrides in controls:
                request.write_text(json.dumps(value))
                with patch.dict(os.environ, dict(env, **overrides)), self.assertRaises(ValueError):
                    Inputs(request, source)

    def test_live_and_matrix_arguments_are_rejected_before_build_outputs_exist(self):
        for index, extra in enumerate(([], ['--sanitize'], ['--bound-inputs', str(self.out / 'absent')],
                                      ['--global-atomic-matrix', '--case', CASES[1]])):
            output = self.out / ('never-created-%u' % index)
            command = [sys.executable, str(ROOT / 'scripts/compute_global_host_producer.py'), '--build', str(output),
                       '--profile-wire', str(self.out / 'absent-profile'), '--session', str(self.out / 'session'),
                       '--case', CASES[0], *extra]
            result = subprocess.run(command, capture_output=True, text=True,
                                    env=dict(os.environ, PYTHONDONTWRITEBYTECODE='1'))
            self.assertEqual(result.returncode, 2, result.stderr)
            self.assertFalse(output.exists())


@contextmanager
def changed(path, data):
    original = path.read_bytes()
    path.write_bytes(data)
    try:
        yield
    finally:
        path.write_bytes(original)


def native_controls(out, request):
    commands = json.loads((out / 'commands.json').read_text())
    guard = Inputs(request, ROOT)
    guard.prepare(out, [(c['label'], c['argv']) for c in commands[:-1]], commands[-1]['argv'])
    guard.before_link()
    binary = out / 'compute_global_host'
    guard.built(commands, binary)
    checked = 0
    paths = [binary, Path(next(iter(guard.objects))), Path(next(iter(guard.generated))),
             out / 'commands.json', Path(next(iter(guard.dependencies))), out / 'link-original-global-caller.log']
    for path in paths:
        data = path.read_bytes()
        with changed(path, data + b'\nchanged CPU negative control\n'):
            try:
                guard.unchanged(full=True)
            except ValueError:
                checked += 1
            else:
                raise AssertionError('changed original compiler input/output accepted: ' + str(path))
    # Independently reconstruct the actual command/dependency/link checks,
    # rather than relying only on the immutable receipt hash above.
    dep = Path(next(iter(guard.dependencies)))
    with changed(dep, dep.read_bytes() + b' /tmp/foreign-native-input.h\n'):
        try:
            guard.built(commands, binary)
        except ValueError:
            checked += 1
        else:
            raise AssertionError('foreign full-MD dependency accepted')
    log = out / 'link-original-global-caller.log'
    with changed(log, log.read_bytes() + b'/tmp/foreign-native-input.o\n'):
        try:
            guard.built(commands, binary)
        except ValueError:
            checked += 1
        else:
            raise AssertionError('foreign native link object accepted')
    guard.unchanged(full=True)
    assert checked == 8, checked
    print('original real native compiler/header/object/binary/link controls: 8 PASS (CPU only)')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-build', type=Path)
    parser.add_argument('--bound-inputs', type=Path)
    args = parser.parse_args()
    if args.native_build is None:
        if args.bound_inputs is not None:
            parser.error('--bound-inputs requires --native-build')
        unittest.main(argv=[sys.argv[0]])
    else:
        if args.bound_inputs is None:
            parser.error('--native-build requires --bound-inputs')
        native_controls(args.native_build.resolve(strict=True), args.bound_inputs.resolve(strict=True))
