#!/usr/bin/env python3
"""Compile the bound complete Verilator model and its native runtime."""
import argparse
import json
import os
from pathlib import Path
import re
import shutil
import shlex
import signal
import subprocess
import sys

import generate_owner_verilator as generator
import qualify_native_product_compiled as common
from run_owner_bounded import run_bounded

ROOT = Path(__file__).resolve().parents[1]
PREFIX = 'V' + common.TOP


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def tool(name):
    path = shutil.which(name)
    require(path is not None, 'Missing native tool: ' + name)
    if sys.platform == 'darwin' and Path(path).parent == Path('/usr/bin'):
        path = subprocess.check_output(['xcrun', '--find', Path(path).name], text=True).strip()
    return dict(path=os.path.abspath(path), realpath=str(Path(path).resolve()),
        sha256=common.sha(Path(path)),
        version=subprocess.check_output([path, '--version'], text=True))


def context(directory, compiler, optimization, jobs, memory_mib):
    generator.checked_generation(directory)
    target = generator.output(directory)
    sdk = None
    if sys.platform == 'darwin':
        sdk = dict(path=subprocess.check_output(['xcrun', '--show-sdk-path'], text=True).strip(),
                   version=subprocess.check_output(['xcrun', '--show-sdk-version'], text=True).strip())
    return dict(generation_sha256=common.sha(target / 'generation.json'), sdk=sdk,
        compiler=tool(compiler), make=tool('make'), optimization=optimization,
        jobs=jobs, memory_mib=memory_mib,
        programs={str(path): common.sha(path) for path in
            (Path(__file__), *(ROOT / 'scripts' / name for name in
             ('run_owner_bounded.py', 'owner_process_memory.py', 'owner_process_lifetime.py')))},
        environment={name: os.environ.get(name, '') for name in
            ('CXXFLAGS', 'CPPFLAGS', 'LDFLAGS', 'SDKROOT', 'CPATH',
             'CPLUS_INCLUDE_PATH', 'MACOSX_DEPLOYMENT_TARGET', 'DEVELOPER_DIR')})


def artifacts(model):
    return {str(path.relative_to(model)): common.sha(path) for path in model.rglob('*')
            if path.is_file() and path.suffix in ('.o', '.a')}


def checked_build(directory):
    directory = Path(directory).resolve(strict=True)
    target = generator.output(directory)
    record = json.loads((target / 'build.json').read_text())
    bound = record.get('context', {})
    require(record.get('schema') == 'opengpu-owner-verilator-build-v1'
            and bound.get('optimization') in ('-O0', '-O1')
            and type(bound.get('jobs')) is int and bound['jobs'] > 0
            and type(bound.get('memory_mib')) is int and bound['memory_mib'] > 0,
            'Invalid complete owner build')
    require(bound == context(directory, bound['compiler']['path'], bound['optimization'],
                             bound['jobs'], bound['memory_mib']),
            'Owner native compilation inputs changed')
    require(record.get('artifacts') == artifacts(target / 'model'), 'Owner native objects or archives changed')
    require(PREFIX + '__ALL.a' in record['artifacts'], 'Complete owner model archive missing')
    require(isinstance(record.get('runtime_objects'), list) and record['runtime_objects']
            and all(name in record['artifacts'] for name in record['runtime_objects']),
            'Complete owner native runtime missing')
    generated = generator.checked_generation(directory)['generated']
    extra = {str(path.relative_to(target)): common.sha(path)
             for path in (target / 'model').rglob('*.cpp')
             if str(path.relative_to(target)) not in generated}
    require(record.get('additional_sources') == extra, 'Make-generated C++ input sources changed')
    return record


def build(directory, compiler, optimization, jobs, memory_mib):
    directory = Path(directory).resolve(strict=True)
    target = generator.output(directory)
    model = target / 'model'
    require(type(jobs) is int and jobs > 0 and type(memory_mib) is int and memory_mib > 0,
            'Native compiler jobs and memory budget must be positive integers')
    bound = context(directory, compiler, optimization, jobs, memory_mib)
    require(not (target / 'build.json').exists() and not artifacts(model),
            'Existing compiler outputs require identity verification or explicit diagnosis')
    source_before = generator.checked_generation(directory)['generated']

    def guard():
        require(context(directory, compiler, optimization, jobs, memory_mib) == bound,
                'Owner native compilation source/tool inputs changed')

    def execute(name, command, memory, seconds):
        guard()
        log = target / (name + '.log')
        print(name + ' start', flush=True)
        with log.open('w') as stream:
            sys.stdout.flush()
            sys.stderr.flush()
            saved = os.dup(1), os.dup(2)
            try:
                os.dup2(stream.fileno(), 1)
                os.dup2(stream.fileno(), 2)
                code = run_bounded(command, memory, seconds)
            finally:
                sys.stdout.flush()
                sys.stderr.flush()
                os.dup2(saved[0], 1)
                os.dup2(saved[1], 2)
                for fd in saved:
                    os.close(fd)
        require(code == 0, 'Native model stage failed; inspect ' + str(log))
        guard()
        text = log.read_text(errors='replace')
        print('\n'.join(text.splitlines()[-2:]), flush=True)
        log.unlink()
        return text

    command = [bound['make']['path'], '-C', str(model), '-f', PREFIX + '.mk', '-j' + str(jobs),
        'CXX=' + bound['compiler']['path'], 'OPT_FAST=' + optimization,
        'OPT_SLOW=-O0', 'OPT_GLOBAL=' + optimization,
        'VM_USER_CFLAGS=-std=c++17 -g0']
    # The generated makefiles retain the generator's -O1 CFLAGS. Override that
    # variable explicitly so it cannot silently defeat OPT_FAST/OPT_SLOW.
    # Verilator's initialization/settle-only translation units need no optimizer.
    # Calling the resolved Apple compiler directly bypasses the /usr/bin
    # launcher that normally supplies the SDK. Bind and pass it explicitly.
    if bound['sdk'] is not None:
        command.append('CXXFLAGS=' + shlex.join(['-isysroot', bound['sdk']['path']]))
    variables = execute('native-model-options', [*command, 'debug-make'], 2048, 120)
    names = []
    for variable in ('VM_GLOBAL_FAST', 'VM_GLOBAL_SLOW'):
        match = re.findall(r'^' + variable + r':(.*)$', variables, flags=re.M)
        require(len(match) == 1, 'Native runtime object list is missing or ambiguous')
        names.extend(match[0].split())
    require(names and 'verilated' in names and
            all(re.fullmatch('[A-Za-z_][A-Za-z0-9_]*', name) for name in names),
            'Invalid native runtime source list')
    runtime = sorted({name + '.o' for name in names})
    execute('compile-native-model', [*command, PREFIX + '__ALL.a', *runtime], memory_mib, 7200)
    compiled = artifacts(model)
    require(PREFIX + '__ALL.a' in compiled and all(name in compiled for name in runtime),
            'Native model compilation produced incomplete archives/runtime')
    # All native generator outputs must still match. Make's generated includer
    # sources are additional compiler inputs, so bind them separately.
    extra = {str(path.relative_to(target)): common.sha(path) for path in model.rglob('*.cpp')
             if str(path.relative_to(target)) not in source_before}
    common.write_json(target / 'build.json', dict(schema='opengpu-owner-verilator-build-v1',
        context=bound, runtime_objects=runtime, artifacts=compiled, additional_sources=extra))
    checked_build(directory)
    for path in model.rglob('*'):
        if (path.is_file() and path.suffix in ('.d', '.gch', '.pch')
                and str(path.relative_to(target)) not in source_before):
            path.unlink()
    print('Complete full112 native model compilation PASS; original executors/probes remain required',
          flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--owner-output', required=True, type=Path)
    parser.add_argument('--cxx', default=os.environ.get('CXX') or 'c++')
    parser.add_argument('--optimization', choices=('-O0', '-O1'), default='-O1')
    parser.add_argument('--jobs', type=int, default=min(8, os.cpu_count() or 1))
    parser.add_argument('--memory-mib', type=int, default=24576)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    def interrupted(signum, _frame):
        raise InterruptedError('owner native build interrupted by signal ' + str(signum))
    previous = {sig: signal.signal(sig, interrupted) for sig in (signal.SIGINT, signal.SIGTERM)}
    try:
        if args.check:
            checked_build(args.owner_output)
            print('Complete owner native object/archive/tool identity PASS')
        else:
            build(args.owner_output, args.cxx, args.optimization, args.jobs, args.memory_mib)
    finally:
        for sig, handler in previous.items():
            signal.signal(sig, handler)


if __name__ == '__main__':
    main()
