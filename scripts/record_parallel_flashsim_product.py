#!/usr/bin/env python3
"""Run additional scenes or record completed scenes from a live batch."""
import argparse
import hashlib
import json
import signal
import subprocess
import time
from pathlib import Path

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash
import qualify_parallel_flashsim_product as parallel


def _metric(name, target, product):
    lines = (target / 'execution.log').read_text().splitlines()
    if len(lines) != 1:
        raise RuntimeError('Scene returned missing or unexpected metrics: ' + name)
    row = json.loads(lines[0]); size = (1280, 720) if product else (32, 32)
    if (row.get('scene') != name or (row.get('frameWidth'), row.get('frameHeight')) != size
            or not row.get('trafficDrained') or row.get('serviceCycles', 0) <= 0
            or row.get('gpuCycles', 0) <= 0 or row.get('imageVerificationRequired') is not True
            or row.get('clockQualified') is not False or row.get('fpsQualified') is not False
            or len(row.get('phaseWorkerCycles', [])) != 6):
        raise RuntimeError('Incomplete scene metrics: ' + name)
    parallel._one_metric(target, name, row)
    return row


def _names(names):
    if not names or len(names) != len(set(names)) or not set(names).issubset(common.SCENES):
        raise ValueError('Select distinct native scene names')
    return names


def run_extra(current, original, output, names, jobs):
    names = _names(names)
    if not 1 <= jobs <= len(names):
        raise ValueError('Jobs must be between one and the scene count')
    output = output.resolve()
    if any(output == p.resolve() or output.is_relative_to(p.resolve()) for p in (current.out, original.out)):
        raise RuntimeError('Additional scene output must be separate from the inputs')
    prepared, old, build, identity = parallel.guard_model(current, original)
    output.mkdir(parents=True, exist_ok=False)
    started = time.time(); pending = list(names); running = {}; rows = {}; previous = None

    def state(stage, **fields):
        common.write_json(output / 'state.json', dict(stage=stage, started=started,
                          updated=time.time(), **fields))

    try:
        while pending or running:
            while pending and len(running) < jobs:
                name = pending.pop(0); target = output / 'scenes' / name
                target.mkdir(parents=True)
                command = [str(original.out / original.executable_name), str(original.out / 'fixtures'),
                           str(target), '--scene', name]
                with (target / 'execution.log').open('w') as stream:
                    child = subprocess.Popen(command, cwd=common.ROOT, stdout=stream, stderr=subprocess.STDOUT)
                running[name] = (child, target, command)
            status = (tuple(running), tuple(rows))
            if status != previous:
                state('running', completed=list(rows), children=[dict(scene=name, pid=child.pid, argv=command)
                      for name, (child, _, command) in running.items()])
                previous = status
            for name, (child, target, command) in list(running.items()):
                code = child.poll()
                if code is None:
                    continue
                if code:
                    raise RuntimeError(f'Additional scene {name} failed with exit {code}')
                row = _metric(name, target, current.product)
                rows[name] = dict(metrics=row, files=common.tree_hashes(target), argv=command, exit_code=code)
                del running[name]
            if running:
                time.sleep(0.1)
        if parallel.guard_model(current, original) != (prepared, old, build, identity):
            raise RuntimeError('Model or source inputs changed while scenes ran')
        model = dict(**identity, source_sha256=old['source_sha256'], rtl_sha256=old['rtl_sha256'],
                     fixture_sha256=old['fixture_sha256'], build_context=build['context'])
        common.write_json(output / 'extra-execution.json', dict(
            status='pass', original=str(original.out), model=model, scenes=rows,
            started=started, finished=time.time(),
            scope='Additional fresh-model scenes; independent image verification remains required.'))
        state('complete', completed=list(rows))
    except BaseException as error:
        for child, _, _ in running.values():
            if child.poll() is None:
                child.terminate()
        for child, _, _ in running.values():
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill(); child.wait()
        state('stopped-for-audit', completed=list(rows), error=str(error), automatic_retry=False)
        raise


def witness_original(current, original, run, output, names):
    names = _names(names); run = run.resolve(); output = output.resolve()
    if not run.is_relative_to((original.out / 'runs').resolve()):
        raise RuntimeError('Scene directory is not owned by the original run')
    if output == original.out.resolve() or output.is_relative_to(original.out.resolve()) or output.exists():
        raise RuntimeError('Witness must be a new file outside the original run')
    prepared, old, build, identity = parallel.guard_model(current, original)
    state_bytes = (original.out / 'state.json').read_bytes(); state = json.loads(state_bytes)
    if (state.get('product') != current.product or state.get('two_cu_vga', False) != current.two_cu_vga
            or state.get('scene_execution') != original.scene_execution
            or state.get('stage') not in ('execute-six-scenes-parallel', 'execute-six-scenes-parallel-terminal')
            or (state.get('directory') and Path(state['directory']).resolve() != run)
            or not set(names).issubset(state.get('completed', []))
            or set(names) & {child.get('scene') for child in state.get('children', [])}):
        raise RuntimeError('Original controller has not completed the requested scenes')
    rows = {}
    for name in names:
        target = run / 'scenes' / name
        if target.is_symlink() or not target.resolve().is_relative_to((run / 'scenes').resolve()):
            raise RuntimeError('Original scene path is not owned by the run: ' + name)
        row = _metric(name, target, current.product)
        rows[name] = dict(metrics=row, directory=str(target), files=common.tree_hashes(target))
    if (parallel.guard_model(current, original) != (prepared, old, build, identity)
            or any(item['files'] != common.tree_hashes(Path(item['directory'])) for item in rows.values())):
        raise RuntimeError('Model or completed scene bytes changed while recording')
    common.write_json(output, dict(status='pass', original=str(original.out), run_directory=str(run),
        controller_state=state, controller_state_sha256=hashlib.sha256(state_bytes).hexdigest(),
        scenes=rows, recorded=time.time(), **identity,
        scope='Completed original scenes; independent image verification remains required.'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('extra', 'witness'))
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true'); mode.add_argument('--product', action='store_true')
    parser.add_argument('--two-cu-vga', action='store_true')
    parser.add_argument('--api-capacity', action='store_true')
    parser.add_argument('--prepared-output', type=Path, required=True)
    parser.add_argument('--original-output', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--run-directory', type=Path)
    parser.add_argument('--scene', action='append', choices=common.SCENES, required=True)
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--flashsim-dir', type=Path, required=True)
    parser.add_argument('--original-driver-checkout', type=Path,
                        help='GPU checkout containing the original build driver files')
    parser.add_argument('--reshard-dir', type=Path); parser.add_argument('--reshard-tool', type=Path)
    parser.add_argument('--scene-jobs', type=int, default=2); parser.add_argument('--shards', type=int, default=32)
    parser.add_argument('--precompiled-header', action='store_true')
    parser.add_argument('--conservative-alias', action='store_true')
    parser.add_argument('--shard-opt', choices=['-O0', '-O1', '-O2', '-O3'], default='-O2')
    args = parser.parse_args()
    if args.shards < 1 or not 2 <= args.scene_jobs <= len(common.SCENES):
        parser.error('--shards must be positive and --scene-jobs must be between 2 and 6')
    if (args.action == 'witness') != (args.run_directory is not None):
        parser.error('--run-directory is required only for witness')
    current_out, original_out = args.prepared_output.resolve(), args.original_output.resolve()
    if current_out == original_out:
        parser.error('preparation and original directories must differ')
    values = (args.product, args.flashsim_dir, args.scene_jobs, args.shards,
              args.reshard_dir, args.reshard_tool, args.precompiled_header, args.shard_opt,
              args.conservative_alias)
    current = flash.Runner(current_out, *values, api_capacity=args.api_capacity, two_cu_vga=args.two_cu_vga)
    original = flash.Runner(original_out, *values, driver_root=args.original_driver_checkout,
                            api_capacity=args.api_capacity, two_cu_vga=args.two_cu_vga)
    def terminate(_signum, _frame):
        raise KeyboardInterrupt('recording terminated')
    previous = signal.signal(signal.SIGTERM, terminate)
    try:
        if args.action == 'extra':
            run_extra(current, original, args.output, args.scene, args.jobs)
        else:
            witness_original(current, original, args.run_directory, args.output, args.scene)
    finally:
        signal.signal(signal.SIGTERM, previous)


if __name__ == '__main__':
    main()
