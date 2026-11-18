#!/usr/bin/env python3
"""Build the original RV32/Linux caller; live mode requires external DUT receipts.

--capture-only performs original production admission/capture without executing
instructions. It cannot generate execution or cleanup success receipts.
"""
import argparse
import json
import os
from pathlib import Path
import shlex
import subprocess
import struct

from compute_global_atomic_cases import CASES, initial_kernarg, words
from test_compute_host import write_bodies

ROOT = Path(__file__).resolve().parents[1]


def build(out, *, sanitize, guard=None):
    out.mkdir(parents=True, exist_ok=False)
    kernel = out / 'kernel'
    kernel.mkdir()
    write_bodies(kernel)
    cc = shlex.split(os.environ.get('CC', 'cc'))
    flags = ['-std=c11', '-O1', '-g0', '-Wall', '-Wextra', '-Werror',
             '-Wno-unused-function', '-Wno-unused-parameter', '-D_POSIX_C_SOURCE=200809L']
    if sanitize:
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    commands, objects = [], []

    def run(argv, label):
        argv = list(map(str, argv))
        commands.append(dict(argv=argv, cwd=str(out), label=label))
        (out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        with (out / (label + '.log')).open('w') as log:
            done = subprocess.run(argv, cwd=out, stdout=log, stderr=subprocess.STDOUT)
        if done.returncode:
            raise RuntimeError(label + ' failed:\n' + (out / (label + '.log')).read_text()[-12000:])

    units = (
        ('driver/tests/opengpu_compute_host_fixture.c',
         [ROOT / 'driver/tests/compute_submit_stubs', ROOT / 'driver/tests/mmu_stubs', ROOT / 'driver', kernel], []),
        ('userspace/opengpu.c', [ROOT / 'driver', ROOT / 'userspace'],
         ['-D' + name + '=global_fixture_' + name for name in ('ioctl', 'mmap', 'munmap', 'close')]),
        ('userspace/tests/compute_global_host.c', [ROOT / 'driver', ROOT / 'userspace'], []),
    )
    plans=[]
    for relative, includes, extra in units:
        obj = out / (Path(relative).stem + '.o')
        argv=cc + flags + ['-I' + str(path) for path in includes] + extra +\
            ['-MD', '-MF', obj.with_suffix('.d'), '-c', ROOT / relative, '-o', obj]
        plans.append(('compile-'+obj.stem,argv))
        objects.append(obj)
    binary = out / 'compute_global_host'
    link=cc + flags + [*objects,'-Wl,-t','-o',binary]
    if guard is not None:guard.prepare(out,plans,link)
    for label,argv in plans:run(argv,label)
    if guard is not None:guard.before_link()
    run(link, 'link-original-global-caller')
    if guard is not None:guard.built(commands,binary)
    return binary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--profile-wire', type=Path, required=True)
    parser.add_argument('--case', choices=('all', *CASES), default='all')
    parser.add_argument('--session', type=Path)
    parser.add_argument('--capture-only', action='store_true')
    parser.add_argument('--sanitize', action='store_true')
    parser.add_argument('--bound-inputs',type=Path)
    parser.add_argument('--global-atomic-matrix',action='store_true')
    parser.add_argument('--global-recovery-matrix',action='store_true')
    parser.add_argument('--physical-base',type=int)
    args = parser.parse_args()
    if args.case == 'all' and not args.capture_only:
        parser.error('all-case mode is CPU capture only; live mode requires one exact original case')
    if args.session is not None and args.case == 'all':
        parser.error('a live session identifies one original source case')
    if args.global_atomic_matrix and (args.case!=CASES[0] or args.session is None):
        parser.error('global matrix requires its exact first source and private session')
    if args.global_recovery_matrix and (args.case!=CASES[0] or args.session is None or
            args.global_atomic_matrix or args.capture_only):
        parser.error('global recovery requires its distinct live first-source matrix')
    if args.physical_base is not None and (not args.capture_only or args.global_recovery_matrix or
            args.global_atomic_matrix or not 0<args.physical_base<=0xfffff000 or args.physical_base%4096):
        parser.error('explicit capture allocator requires a page-aligned bounded CPU-only source')
    if not args.capture_only and (args.bound_inputs is None or not args.sanitize):
        parser.error('live source requires guarded inputs and sanitized compilation')
    if args.bound_inputs is not None and not args.sanitize:
        parser.error('bound native compilation requires ASan/UBSan')
    guard=None
    if args.bound_inputs is not None:
        from compute_global_host_inputs import Inputs
        guard=Inputs(args.bound_inputs,ROOT)
    out = args.build.resolve()
    binary = build(out, sanitize=args.sanitize,guard=guard)
    inputs = out / 'inputs'
    inputs.mkdir()
    profile = args.profile_wire.resolve(strict=True)
    # Preserve the observed discovery wire independently of a caller's later
    # path changes. Live qualification must bind its bytes to the actual DUT.
    wire = profile.read_bytes()
    if wire[:8] != b'OGCP0003':
        raise ValueError('the original complete factory discovery wire is required')
    frozen_profile = inputs / 'profile.bin'
    frozen_profile.write_bytes(wire)
    env = dict(os.environ, UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1',
               ASAN_OPTIONS='detect_leaks=1:abort_on_error=1')
    selected = CASES if args.case == 'all' or args.global_atomic_matrix else (args.case,)
    if args.global_recovery_matrix:
        from compute_global_recovery import PHASES, check_source_launch
        selected=PHASES
    for stage in selected:
        case=stage['case'] if args.global_recovery_matrix else stage
        code = words(case)
        program, initial = inputs / (case + '.bin'), inputs / (case + '.kernarg')
        program.write_bytes(struct.pack('<%uI' % len(code), *code))
        initial.write_bytes(initial_kernarg(case))
        directory = ((args.session.resolve()/('round-'+str(stage['index'])) if args.global_recovery_matrix else
                      args.session.resolve()/case if args.global_atomic_matrix else args.session.resolve())
                     if args.session is not None else out / 'captures' / case)
        directory.mkdir(parents=True, exist_ok=args.session is not None)
        if guard is not None:guard.unchanged()
        label='round-'+str(stage['index']) if args.global_recovery_matrix else case
        log_path=out/('actual-compute-capture-'+label+'.log')
        command=[str(binary),str(directory),str(frozen_profile),str(program),str(initial),case]
        if args.global_recovery_matrix:
            if stage['mode']!='healthy':command+=['--expect-global-fault',stage['mode']]
            command+=['--physical-base',str(stage['base'])]
            check_source_launch(command,stage['index'],out,args.session.resolve(),frozen_profile)
        else:
            if args.capture_only:command+=['--capture-only']
            if args.physical_base is not None:command+=['--physical-base',str(args.physical_base)]
        with log_path.open('w') as log:
            done=subprocess.run(command,stdout=log,stderr=subprocess.STDOUT,env=env)
        if done.returncode:raise RuntimeError(case+' failed:\n'+log_path.read_text(errors='replace')[-8000:])
        if guard is not None:guard.unchanged()
        print('original RV32/Linux '+case+': '+('CPU capture PASS; no GPU execution' if args.capture_only else
              'external DUT lifecycle completed'),flush=True)
    if profile.read_bytes() != wire:
        raise ValueError('original discovery input changed during source capture')
    if guard is not None:guard.unchanged(full=True)
    print('%u original RV32/Linux source captures completed%s' %
          (len(selected), ' (CPU admission only; no GPU execution)' if args.capture_only else ''))


if __name__ == '__main__':
    main()
