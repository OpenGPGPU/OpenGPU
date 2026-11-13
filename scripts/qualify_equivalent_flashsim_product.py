#!/usr/bin/env python3
"""Reverify a completed FlashSim run against byte-identical current RTL.

The original preparation, build and execution remain immutable. This creates
a separate qualification record only after checking both preparations and
rerunning the stored-image oracle from the current source checkout.
"""
import argparse
import fcntl
import json
from pathlib import Path

import qualify_native_product_compiled as common
import qualify_native_product_flashsim as flash


def guard_equivalence(current, original, reference=None):
    if current.out.resolve() == original.out.resolve():
        raise RuntimeError('Use separate current preparation and original execution directories')
    if (current.product != original.product or current.api_capacity != original.api_capacity
            or current.two_cu_vga != original.two_cu_vga
            or current.scene_execution != original.scene_execution):
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
    common.check_profile(json.loads((original.out / 'fixture-manifest.json').read_text()),
                         current.product, current.api_capacity, current.two_cu_vga)
    original.checked_build(prepared)
    execution_path = original.out / 'execution.json'
    execution = json.loads(execution_path.read_text())
    directory = Path(execution['directory']).resolve()
    if not directory.is_relative_to((original.out / 'runs').resolve()):
        raise RuntimeError('Execution directory is not owned by the original run')
    if (execution.get('simulator_backend') != 'flashsim' or execution.get('exit_code') != 0
            or execution.get('scene_execution') != original.scene_execution
            or execution['output_sha256'] != common.tree_hashes(directory)
            or execution['preparation_sha256'] != common.sha(old_path)
            or execution['build_sha256'] != common.sha(original.out / 'build.json')
            or execution['executable_sha256'] != common.sha(original.out / original.executable_name)):
        raise RuntimeError('Original execution provenance or outputs changed')
    common.validate_metrics(execution['scenes'], current.product, current.two_cu_vga)
    reference_sha = common.sha(reference) if reference else None
    if reference:
        common.compare_reference(execution['scenes'], json.loads(reference.read_text()), original.scene_execution)
    return dict(prepared=prepared, original_preparation_sha256=common.sha(old_path),
                current_preparation_sha256=common.sha(current.out / 'preparation.json'),
                execution_sha256=common.sha(execution_path),
                build_sha256=common.sha(original.out / 'build.json'),
                executable_sha256=execution['executable_sha256'],
                directory=str(directory), scenes=execution['scenes'],
                scene_execution=execution['scene_execution'], reference_sha256=reference_sha)


def verify(current, original, reference=None):
    result = current.out / 'qualification-equivalent.json'
    if result.exists():
        raise RuntimeError('Equivalent qualification already exists; choose a fresh output')
    verifier_files = [Path(__file__).resolve(), Path(flash.__file__).resolve()]
    verifier_hashes = {str(p): common.sha(p) for p in verifier_files}
    before = guard_equivalence(current, original, reference)
    mode = '--product' if current.product else '--smoke'
    log = current.out / 'verify-equivalent.log'
    current.execute('verify-equivalent-stored-images',
        current.sbt('opengpu.system.VerifyCompiledNativeProduct ' + mode +
                    ' --output ' + json.dumps(before['directory']),
                    common.PRODUCT_JAVA_HEAP if current.product else '3G'), log)
    common.verify_marker(log, current.product, current.two_cu_vga)
    after = guard_equivalence(current, original, reference)
    if before != after or verifier_hashes != {str(p): common.sha(p) for p in verifier_files}:
        raise RuntimeError('Qualification inputs changed during verification')
    common.write_json(result, dict(passed=True, product=current.product, api_capacity=current.api_capacity, two_cu_vga=current.two_cu_vga,
        simulator_backend='flashsim', source_sha256=before['prepared']['source_sha256'],
        original_preparation_sha256=before['original_preparation_sha256'],
        current_preparation_sha256=before['current_preparation_sha256'],
        build_sha256=before['build_sha256'], execution_sha256=before['execution_sha256'],
        executable_sha256=before['executable_sha256'],
        bound_assertion_layer_sha256=common.assertion_layer_hashes(before['prepared']['rtl_sha256']),
        scene_execution=before['scene_execution'], scenes=before['scenes'],
        matched_reference_sha256=before['reference_sha256'],
        verify_log_sha256=common.sha(log), verifier_sha256=verifier_hashes,
        exact_stored_images_and_guards=True, traffic_drained=True,
        whole_host_clock_qualified=False, FPS_qualified=False,
        scope='Original FlashSim execution, reverified using current-source byte-identical RTL and fixtures; no clock/FPS or full H0-H8 claim.'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true'); mode.add_argument('--product', action='store_true')
    parser.add_argument('--two-cu-vga', action='store_true')
    parser.add_argument('--api-capacity', action='store_true')
    parser.add_argument('--prepared-output', type=Path, required=True)
    parser.add_argument('--execution-output', type=Path, required=True)
    parser.add_argument('--flashsim-dir', type=Path, required=True)
    parser.add_argument('--original-driver-checkout', type=Path,
                        help='GPU checkout containing the original build driver files')
    parser.add_argument('--reshard-dir', type=Path)
    parser.add_argument('--reshard-tool', type=Path)
    parser.add_argument('--scene-jobs', type=int, default=1)
    parser.add_argument('--shards', type=int, default=32)
    parser.add_argument('--precompiled-header', action='store_true')
    parser.add_argument('--conservative-alias', action='store_true')
    parser.add_argument('--shard-opt', choices=['-O0', '-O1', '-O2', '-O3'], default='-O2')
    parser.add_argument('--reference', type=Path)
    args = parser.parse_args()
    if args.shards < 1 or not 1 <= args.scene_jobs <= len(common.SCENES):
        parser.error('--shards must be positive and --scene-jobs must be between 1 and 6')
    current_out, old_out = args.prepared_output.resolve(), args.execution_output.resolve()
    if current_out == old_out:
        parser.error('preparation and execution directories must differ')
    current = flash.Runner(current_out, args.product, args.flashsim_dir, args.scene_jobs, args.shards,
                           args.reshard_dir, args.reshard_tool, args.precompiled_header, args.shard_opt,
                           args.conservative_alias, api_capacity=args.api_capacity, two_cu_vga=args.two_cu_vga)
    original = flash.Runner(old_out, args.product, args.flashsim_dir, args.scene_jobs, args.shards,
                            args.reshard_dir, args.reshard_tool, args.precompiled_header, args.shard_opt,
                            args.conservative_alias, driver_root=args.original_driver_checkout,
                            api_capacity=args.api_capacity, two_cu_vga=args.two_cu_vga)
    # Lock both roots in a stable order; a live run cannot be requalified.
    first, second = sorted((current_out, old_out))
    with (first / '.lock').open('w') as first_lock, (second / '.lock').open('w') as second_lock:
        fcntl.flock(first_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fcntl.flock(second_lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        verify(current, original, args.reference.resolve() if args.reference else None)


if __name__ == '__main__':
    main()
