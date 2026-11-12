#!/usr/bin/env python3
"""Verify completed independent FlashSim scenes against current-source RTL.

The original run and the additional scene batch remain untouched. A separate
result is written only after the current-source stored-image oracle passes.
"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash


def _one_metric(target, name, expected):
    lines = (target / 'execution.log').read_text().splitlines()
    if len(lines) != 1 or json.loads(lines[0]) != expected:
        raise RuntimeError('Scene log does not match its recorded metrics: ' + name)
    progress = json.loads((target / 'progress.json').read_text())
    if (progress.get('state') != 'scene-complete' or progress.get('scene') != name
            or progress.get('serviceCycles') != expected.get('serviceCycles')
            or progress.get('gpuCycles') != expected.get('gpuCycles')):
        raise RuntimeError('Scene completion record is missing or changed: ' + name)
    memory = target / (name + '.memory')
    if not memory.is_file() or memory.is_symlink():
        raise RuntimeError('Scene memory is missing or is a symlink: ' + name)
    return memory


def guard_model(current, original):
    if current.product != original.product:
        raise RuntimeError('Preparation and execution profiles differ')
    prepared = current.prepared()
    old_path = original.out / 'preparation.json'
    old = json.loads(old_path.read_text())
    if old.get('exit_code') != 0:
        raise RuntimeError('Original preparation did not succeed')
    for key in ('rtl_sha256', 'fixture_sha256', 'manifest_sha256'):
        if old[key] != prepared[key]:
            raise RuntimeError('Preparations differ: ' + key)
    if (old['rtl_sha256'] != common.tree_hashes(original.out / 'rtl')
            or old['fixture_sha256'] != common.tree_hashes(original.out / 'fixtures')
            or old['manifest_sha256'] != common.sha(original.out / 'fixture-manifest.json')):
        raise RuntimeError('Original prepared inputs changed')
    common.check_profile(json.loads((original.out / 'fixture-manifest.json').read_text()), current.product)
    build = original.checked_build(prepared)
    identity = dict(preparation_sha256=common.sha(old_path),
                    build_sha256=common.sha(original.out / 'build.json'),
                    executable_sha256=common.sha(original.out / original.executable_name))
    return prepared, old, build, identity


def guard_batches(current, original, witness_path, extra_path):
    witness_path, extra_path = witness_path.resolve(), extra_path.resolve()
    prepared, old, build, identity = guard_model(current, original)
    witness = json.loads(witness_path.read_text())
    extra = json.loads(extra_path.read_text())
    if (witness.get('status') != 'pass' or extra.get('status') != 'pass'
            or Path(witness['original']).resolve() != original.out.resolve()
            or Path(extra['original']).resolve() != original.out.resolve()
            or any(witness.get(k) != v or extra['model'].get(k) != v
                   for k, v in identity.items())):
        raise RuntimeError('Scene batch model identity differs')
    model = extra['model']
    if (model.get('source_sha256') != old['source_sha256']
            or model.get('rtl_sha256') != old['rtl_sha256']
            or model.get('fixture_sha256') != old['fixture_sha256']
            or model.get('build_context') != build['context']):
        raise RuntimeError('Additional scenes do not use the original model inputs')
    state = witness['controller_state']
    state_hash = hashlib.sha256((json.dumps(state, indent=2) + '\n').encode()).hexdigest()
    if (state_hash != witness['controller_state_sha256'] or state.get('product') != current.product
            or state.get('scene_execution') != original.scene_execution
            or state.get('stage') not in ('execute-six-scenes-parallel', 'execute-six-scenes-parallel-terminal')):
        raise RuntimeError('Original controller completion witness is invalid')
    run = Path(witness['run_directory']).resolve()
    if (not run.is_relative_to((original.out / 'runs').resolve())
            or (state.get('directory') and Path(state['directory']).resolve() != run)):
        raise RuntimeError('Original scene directory is not owned by the run')
    first, later = witness['scenes'], extra['scenes']
    if (not first or not later or set(first) & set(later)
            or set(first) | set(later) != set(common.SCENES)
            or not set(first).issubset(state.get('completed', []))
            or set(first) & {child.get('scene') for child in state.get('children', [])}):
        raise RuntimeError('The batches do not contain six distinct completed scenes')
    memories, rows = {}, {}
    for name in common.SCENES:
        if name in first:
            item = first[name]
            target = run / 'scenes' / name
            if Path(item['directory']).resolve() != target.resolve():
                raise RuntimeError('Original scene path differs: ' + name)
        else:
            item = later[name]
            target = extra_path.parent / 'scenes' / name
            command = [str(original.out / original.executable_name), str(original.out / 'fixtures'),
                       str(target), '--scene', name]
            if item.get('exit_code') != 0 or item.get('argv') != command:
                raise RuntimeError('Additional scene command or exit status differs: ' + name)
        owner = run / 'scenes' if name in first else extra_path.parent / 'scenes'
        if (target.is_symlink() or not target.resolve().is_relative_to(owner.resolve())
                or item['files'] != common.tree_hashes(target)):
            raise RuntimeError('Scene output bytes changed: ' + name)
        rows[name] = item['metrics']
        memories[name] = str(_one_metric(target, name, rows[name]))
    ordered = [rows[name] for name in common.SCENES]
    common.validate_metrics(ordered, current.product)
    execution = dict(mode='independently-orchestrated-fresh-models',
                     original_scene_jobs=original.scene_execution['max_workers'],
                     original_scenes=[name for name in common.SCENES if name in first],
                     additional_scenes=[name for name in common.SCENES if name in later])
    return dict(prepared=prepared, identity=identity, scenes=ordered, memories=memories, execution=execution,
                witness_sha256=common.sha(witness_path), extra_sha256=common.sha(extra_path))


def verify(current, original, witness_path, extra_path, output):
    output = output.resolve()
    if any(output == p.resolve() or output.is_relative_to(p.resolve())
           for p in (current.out, original.out, extra_path.parent)):
        raise RuntimeError('Qualification output must be separate from all inputs')
    before = guard_batches(current, original, witness_path, extra_path)
    output.mkdir(parents=True, exist_ok=False)
    for name, memory in before['memories'].items():
        os.link(memory, output / (name + '.memory'))
    image_hashes = {name: common.sha(output / (name + '.memory')) for name in common.SCENES}
    verifier = {str(p): common.sha(p) for p in
                (Path(__file__).resolve(), Path(flash.__file__).resolve(), Path(common.__file__).resolve())}
    mode = '--product' if current.product else '--smoke'
    log = output / 'verify.log'
    current.execute('verify-parallel-stored-images',
        current.sbt('opengpu.system.VerifyCompiledNativeProduct ' + mode +
                    ' --output ' + json.dumps(str(output)), '6G' if current.product else '3G'), log)
    common.verify_marker(log, current.product)
    if (before != guard_batches(current, original, witness_path, extra_path)
            or image_hashes != {name: common.sha(output / (name + '.memory')) for name in common.SCENES}
            or verifier != {p: common.sha(Path(p)) for p in verifier}):
        raise RuntimeError('Qualification inputs changed during verification')
    common.write_json(output / 'qualification-parallel.json', dict(
        passed=True, product=current.product, simulator_backend='flashsim',
        source_sha256=before['prepared']['source_sha256'], scenes=before['scenes'],
        scene_execution=before['execution'],
        bound_assertion_layer_sha256=common.assertion_layer_hashes(before['prepared']['rtl_sha256']),
        model=before['identity'], witness_sha256=before['witness_sha256'],
        extra_sha256=before['extra_sha256'], image_sha256=image_hashes,
        verifier_sha256=verifier, verify_log_sha256=common.sha(log),
        exact_stored_images_and_guards=True, traffic_drained=True,
        whole_host_clock_qualified=False, FPS_qualified=False,
        scope='Independent fresh-model scene executions, reverified on current-source byte-identical RTL and fixtures; no clock/FPS or full H0-H8 claim.'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true'); mode.add_argument('--product', action='store_true')
    parser.add_argument('--prepared-output', type=Path, required=True)
    parser.add_argument('--original-output', type=Path, required=True)
    parser.add_argument('--witness', type=Path, required=True)
    parser.add_argument('--extra-record', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--flashsim-dir', type=Path, required=True)
    parser.add_argument('--original-driver-checkout', type=Path,
                        help='GPU checkout containing the original build driver files')
    parser.add_argument('--reshard-dir', type=Path)
    parser.add_argument('--reshard-tool', type=Path)
    parser.add_argument('--scene-jobs', type=int, default=2)
    parser.add_argument('--shards', type=int, default=32)
    parser.add_argument('--precompiled-header', action='store_true')
    parser.add_argument('--conservative-alias', action='store_true')
    parser.add_argument('--shard-opt', choices=['-O0', '-O1', '-O2', '-O3'], default='-O2')
    args = parser.parse_args()
    if args.shards < 1 or not 2 <= args.scene_jobs <= len(common.SCENES):
        parser.error('--shards must be positive and --scene-jobs must be between 2 and 6')
    current_out, old_out = args.prepared_output.resolve(), args.original_output.resolve()
    if current_out == old_out:
        parser.error('preparation and original directories must differ')
    values = (args.product, args.flashsim_dir, args.scene_jobs, args.shards,
              args.reshard_dir, args.reshard_tool, args.precompiled_header, args.shard_opt,
              args.conservative_alias)
    current = flash.Runner(current_out, *values)
    original = flash.Runner(old_out, *values, driver_root=args.original_driver_checkout)
    # The original batch may still be running other scenes. Its completed
    # scene directories are fingerprinted before and after the oracle instead
    # of taking the live run's exclusive lock.
    with (current_out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        verify(current, original, args.witness.resolve(), args.extra_record.resolve(), args.output)


if __name__ == '__main__':
    main()
