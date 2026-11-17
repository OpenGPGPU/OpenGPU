#!/usr/bin/env python3
"""Generate the complete inline-assertion owner model with Verilator."""
import argparse
import json
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys

import prepare_native_owner_flashsim as owner
import qualify_native_product_compiled as common
from owner_resource_contract import SAMPLE_PROFILE
from run_owner_bounded import run_bounded

ROOT = Path(__file__).resolve().parents[1]
TOP = common.TOP
FLAGS = ['--cc', '--assert', '--hierarchical', '--build-jobs', '1',
         # Preserve runtime helpers for wide operations. Expanding the full
         # MSHR waiter vectors defeats function splitting and produces huge C++.
         '--expand-limit', '0',
         '--output-groups', '0', '--output-split', '10000',
         '--output-split-cfuncs', '200', '-Wno-fatal', '-Wno-UNOPTFLAT',
         '-CFLAGS', '-std=c++17 -O1 -g0']


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def output(directory):
    directory = Path(directory).resolve(strict=True)
    require(directory.is_relative_to(ROOT / '.artifacts'), 'Owner output must be inside this checkout')
    return directory / 'verilator-dut'


def context(directory):
    prepared = owner.prepared(directory)
    manifest = json.loads((directory / 'owner-manifest.json').read_text())
    require(manifest['profile'] == SAMPLE_PROFILE and manifest.get('assertionMode') == 'inline',
            'Hierarchical owner compilation requires the complete inline sample factory')
    verilator = Path(shutil.which('verilator')).resolve(strict=True)
    kit = Path(subprocess.check_output(
        [str(verilator), '--getenv', 'VERILATOR_ROOT'], text=True).strip()).resolve(strict=True)
    programs = [verilator, verilator.with_name('verilator_bin'), Path(sys.executable).resolve(),
        Path(__file__), *(ROOT / 'scripts' / name for name in
        ('run_owner_bounded.py', 'owner_process_memory.py', 'owner_process_lifetime.py'))]
    programs.extend(path for folder in ('include', 'bin')
                    for path in (kit / folder).rglob('*') if path.is_file())
    return dict(schema='opengpu-owner-verilator-context-v1', profile=SAMPLE_PROFILE,
        preparation_sha256=common.sha(directory / 'preparation.json'),
        manifest_sha256=prepared['manifest_sha256'], rtl_sha256=prepared['rtl_sha256'],
        hierarchy=common.hierarchy_plan(prepared['rtl_sha256'], 'fp32'), flags=FLAGS,
        verilator=str(verilator), verilator_root=str(kit),
        version=subprocess.check_output([str(verilator), '--version'], text=True),
        tools={str(path): common.sha(path) for path in programs},
        environment={name: os.environ.get(name, '') for name in
            ('VERILATOR_ROOT', 'VERILATOR_BIN', 'CXX', 'CXXFLAGS', 'CPPFLAGS', 'LDFLAGS',
             'SDKROOT', 'CPATH', 'CPLUS_INCLUDE_PATH', 'MACOSX_DEPLOYMENT_TARGET')})


def checked_generation(directory):
    directory = Path(directory).resolve(strict=True)
    target = output(directory)
    record = json.loads((target / 'generation.json').read_text())
    require(record.get('schema') == 'opengpu-owner-verilator-generation-v1'
            and record.get('context') == context(directory), 'Owner generation inputs changed')
    files = record.get('generated')
    require(isinstance(files, dict) and files and 'model/' + 'V' + TOP + '.h' in files,
            'Incomplete generated owner model')
    for name, digest in files.items():
        path = target / name
        require(path.resolve().is_relative_to(target) and path.is_file() and not path.is_symlink()
                and common.sha(path) == digest, 'Generated owner source changed: ' + name)
    return record


def generate(directory):
    directory = Path(directory).resolve(strict=True)
    bound = context(directory)
    target = output(directory)
    # Existing partial output needs explicit consumer/identity diagnosis.
    # Never erase it or infer success from a retained C++ header.
    target.mkdir()
    model = target / 'model'
    inputs, directories = common.simulation_inputs(directory / 'rtl')
    filelist = target / 'inputs.f'
    expected_list = ''.join(json.dumps(str(path)) + '\n' for path in inputs)
    filelist.write_text(expected_list)
    control = target / 'hierarchy.vlt'
    expected_control = common.hierarchy_control(bound['hierarchy'])
    control.write_text(expected_control)
    command = [bound['verilator'], *FLAGS, '--top-module', TOP, '--prefix', 'V' + TOP,
        '--Mdir', str(model), '-F', str(filelist), str(control)]
    command.extend('-I' + str(path) for path in directories)

    def guard():
        require(context(directory) == bound, 'Owner source or compiler changed during generation')
        require(filelist.read_text() == expected_list and control.read_text() == expected_control,
                'Owner compilation input list or hierarchy changed')

    guard()
    log = target / 'generate.log'
    print('Generating complete full112 inline owner; 24GiB charged-tree budget; '
          'serial hierarchical compilation', flush=True)
    with log.open('w') as stream:
        sys.stdout.flush()
        sys.stderr.flush()
        saved = os.dup(1), os.dup(2)
        try:
            os.dup2(stream.fileno(), 1)
            os.dup2(stream.fileno(), 2)
            code = run_bounded(command, 24576, 1800)
        finally:
            sys.stdout.flush()
            sys.stderr.flush()
            os.dup2(saved[0], 1)
            os.dup2(saved[1], 2)
            for fd in saved:
                os.close(fd)
    require(code == 0, 'Verilator generation failed; inspect ' + str(log))
    guard()
    generated = {str(path.relative_to(target)): common.sha(path)
                 for path in model.rglob('*') if path.is_file()}
    require('model/V' + TOP + '.h' in generated and any(name.endswith('.cpp') for name in generated),
            'Verilator produced no complete C++ owner model')
    common.write_json(target / 'generation.json', dict(
        schema='opengpu-owner-verilator-generation-v1', context=bound, generated=generated))
    checked_generation(directory)
    print('\n'.join(log.read_text(errors='replace').splitlines()[-3:]), flush=True)
    log.unlink()
    print('Complete owner C++ generation PASS; native compilation and original probes remain required',
          flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner-output', required=True, type=Path)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    def interrupted(signum, _frame):
        raise InterruptedError('owner generation interrupted by signal ' + str(signum))
    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        if args.check:
            checked_generation(args.owner_output)
            print('Complete owner generated sources and tool/input identity PASS')
        else:
            generate(args.owner_output)
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    main()
