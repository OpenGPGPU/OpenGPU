#!/usr/bin/env python3
"""Run the six original GNJ2 scenes on one exact, prepared 104-word owner DUT."""
import argparse
import fcntl
import json
from pathlib import Path

import qualify_native_alignment_sequence as sequence
from owner_resource_contract import PROFILE, FULL_STAGE, FULL_WORDS

common, flash, owner = sequence.common, sequence.flash, sequence.owner
ROOT = Path(__file__).resolve().parents[1]
FIXTURE_MANIFEST = 'layer-product-fixture-manifest.json'
FIXTURE_RECORD = 'layer-product-fixtures.json'


def check_full_manifest(manifest, *, rtl_emitted=True):
    owner.check_manifest(manifest, rtl_emitted=rtl_emitted)
    if manifest['profile'] != PROFILE or manifest['ownerResourceStage'] != FULL_STAGE:
        raise RuntimeError('GNJ2 product execution requires the exact geometry-compute owner factory')


def checked_output(path):
    out = sequence.checked_owner_output(path)
    check_full_manifest(json.loads((out / 'owner-manifest.json').read_text()))
    return out


def validate_rows(rows, product):
    width, height = common.frame_size(product, True)
    if not isinstance(rows, list) or any(type(row) is not dict for row in rows):
        raise RuntimeError('Layered product execution rows must be objects')
    if [row.get('scene') for row in rows] != common.SCENES:
        raise RuntimeError('All six original GNJ2 product scenes must complete in order')
    expected = dict(profile=PROFILE, ownerResourceStage=FULL_STAGE, computeUnits=2,
        nativeEngines=4, nativeMaxWidth=2048, nativeMaxHeight=2048,
        resourceDiscoveryWords=104, computeFeatureFlags=0xfffff,
        nativeJobVersion=2, nativeFrameVersion=3, nativeCompletionVersion=2,
        frameWidth=width, frameHeight=height, trafficDrained=True,
        imageVerificationRequired=True, clockQualified=False, fpsQualified=False,
        cycleScopeVersion=2, serviceCycleScope='post-submit-to-irq',
        frameCycleScope='reset-through-verified-retirement', submittedCommands=1,
        successfulCompletions=1, retiredCompletions=1,
        completionCountSource='observed-command-retirement',
        drawCompletionCountAvailable=False, primitiveCompletionCountAvailable=False,
        liveProfilePreflight=True, idleBeforeSubmit=True,
        shaderInputSource='native-layered-product-hand-encoded')
    for row in rows:
        if (any(type(row.get(k)) is not type(v) or row[k] != v for k, v in expected.items())
                or type(row.get('samples')) is not int
                or row['samples'] != (4 if row['scene'] == 'msaa-4x' else 1)
                or any(type(row.get(k)) is not int or not 0 < row[k] < 2**64
                       for k in ('serviceCycles', 'gpuCycles'))
                or any(type(row.get(k)) is not int or not 0 <= row[k] < 2**64
                       for k in ('readBusBytes', 'writeBusBytes', 'enabledWriteBytes'))
                or not isinstance(row.get('phaseWorkerCycles'), list)
                or len(row['phaseWorkerCycles']) != 6
                or any(type(v) is not int or not 0 <= v < 2**64 for v in row['phaseWorkerCycles'])):
            raise RuntimeError('Incomplete or mismatched layered owner execution metrics')
        if (any(type(row.get(k)) is not int or not 0 < row[k] < 2**64
                for k in ('gpuCyclesAtServiceBegin', 'gpuCyclesAtIrq', 'frameCycles'))
                or row['gpuCyclesAtIrq'] - row['gpuCyclesAtServiceBegin'] != row['serviceCycles']
                or not row['gpuCyclesAtIrq'] <= row['gpuCycles'] < row['frameCycles']):
            raise RuntimeError('Layered service and reset-through-retirement intervals differ')
        if (not isinstance(row.get('gnx'), list)
                or len(row['gnx']) != 12 or any(type(v) is not int for v in row['gnx'])
                or row['gnx'] != [0x474e5801, 0, 0, 0, 0, 0, 7, 8, 9, 0, 0, 0]):
            raise RuntimeError('Layered product coarse completion has fault or identity mismatch')
        words = row.get('resourceDiscovery')
        if (not isinstance(words, list) or len(words) != len(FULL_WORDS)
                or any(type(w) is not dict or set(w) != {'address', 'value'}
                       or type(w['address']) is not int or type(w['value']) is not int
                       for w in words) or words != FULL_WORDS):
            raise RuntimeError('Actual layered owner discovery is incomplete or differs from its factory')
        registers = row.get('profileRegisters')
        if (not isinstance(registers, list) or len(registers) != 5
                or any(type(r) is not dict or set(r) != {'address', 'value'}
                       or type(r['address']) is not int or r['address'] != address
                       or type(r['value']) is not int or not 0 <= r['value'] < 2**32
                       for r, address in zip(registers, (0, 0x60, 0x68, 0x320, 0x158)))):
            raise RuntimeError('Layered live profile register observations are incomplete')
        device, caps, pointer, quota, shader = (r['value'] for r in registers)
        required_caps = (1 << 6) | (1 << 22) | (1 << 23) | (1 << 25) | (1 << 29)
        lanes, warps, banks, cus = (shader >> shift & 255 for shift in (0, 8, 16, 24))
        if (device & 0xffff < 2 or caps & required_caps != required_caps
                or pointer != 0x160 or not quota or (lanes, warps, cus) != (16, 8, 2)
                or banks < 2 or banks & 1 or lanes * warps != (caps >> 8 & 255)):
            raise RuntimeError('Layered live profile contradicts the complete two-CU contract')


def marker(product, tile_log2):
    width, height = common.frame_size(product, True)
    return (f'NATIVE_LAYERED_PRODUCT PASS profile={PROFILE} scenes=6 tile={1 << tile_log2} '
            f'frame={width}x{height} computeUnits=2 clockQualified=false fpsQualified=false')


def verify_marker(log, product, tile_log2):
    width, height = common.frame_size(product, True)
    expected = [f'NATIVE_LAYERED_PRODUCT_IMAGE scene={name} tile={1 << tile_log2} '
        f'frame={width}x{height} exactImagesSamplesResolvesGuardsAndGNY2=true' for name in common.SCENES]
    lines = log.read_text().splitlines()
    if ([line for line in lines if line.startswith('NATIVE_LAYERED_PRODUCT_IMAGE ')] != expected
            or [line for line in lines if line.startswith('NATIVE_LAYERED_PRODUCT PASS')]
            != [marker(product, tile_log2)]):
        raise RuntimeError('Complete independent layered image/sample/resolve/guard/GNY2 verification is missing')


class Runner(sequence.OwnerRunner):
    def driver_path(self):
        return ROOT / 'scripts/native_layered_product_driver.cpp'

    def driver_sources(self):
        return [name for name in flash.DRIVER if name != 'scripts/native_product_driver.cpp'] + [
            'scripts/native_layered_product_driver.cpp', 'scripts/native_layered_product_fixture.hpp',
            'driver/opengpu_native_layered_abi.h',
            'scripts/qualify_native_alignment_sequence.py', 'scripts/qualify_native_layered_product.py']

    def extra_driver_dependencies(self):
        return [ROOT / name for name in (
            'scripts/native_layered_product_fixture.hpp', 'driver/opengpu_native_layered_abi.h')]

    def fixture_manifest(self):
        manifest = json.loads((self.out / FIXTURE_MANIFEST).read_text())
        check_full_manifest(manifest, rtl_emitted=False)
        width, height = common.frame_size(self.product, True)
        expected = dict(workloadProduct=self.product, workloadWidth=width, workloadHeight=height,
                        workloadTileLog2=self.tile_log2, scenes=common.SCENES)
        if any(type(manifest.get(k)) is not type(v) or manifest[k] != v for k, v in expected.items()):
            raise RuntimeError('Layered fixtures differ from the selected six original recipes')

    def fixture_inputs(self):
        files = common.tree_hashes(self.out / 'fixtures')
        expected = {'suite.txt'} | {name + suffix for name in common.SCENES
                                  for suffix in ('.memory', '.commands')}
        if (set(files) != expected
                or (self.out / 'fixtures/suite.txt').read_text().splitlines() != common.SCENES):
            raise RuntimeError('Incomplete or reordered layered fixture set')
        return dict(fixture_sha256=files, manifest_sha256=common.sha(self.out / FIXTURE_MANIFEST),
                    driver_sha256={name: common.sha(ROOT / name) for name in self.driver_sources()})

    def full_owner(self):
        record = owner.prepared(self.out)
        check_full_manifest(json.loads((self.out / 'owner-manifest.json').read_text()))
        return record

    def prepared(self):
        record = self.full_owner()
        self.fixture_manifest()
        if json.loads((self.out / FIXTURE_RECORD).read_text()) != self.fixture_inputs():
            raise RuntimeError('Layered fixtures or driver changed after preparation')
        return record

    def prepare(self):
        record = self.full_owner()
        for name in ('fixtures', FIXTURE_MANIFEST, FIXTURE_RECORD):
            if (self.out / name).exists():
                raise RuntimeError('Layered preparation requires fresh fixtures')
        mode = '--product' if self.product else '--smoke'
        width, height = common.frame_size(self.product, True)
        log = self.out / 'layer-product-prepare.log'
        self.execute('prepare-layered-product-fixtures', self.sbt(
            'opengpu.system.PrepareNativeLayeredProductFixtures ' + mode + ' --output '
            + json.dumps(str(self.out)) + ' --tile-log2 ' + str(self.tile_log2), '3G'), log)
        common.verify_prepare_completion(log, f'NATIVE_LAYERED_PRODUCT_FIXTURES scenes=6 '
            f'frame={width}x{height} words=104 rtlEmitted=false gpuExecution=false')
        if self.full_owner() != record:
            raise RuntimeError('Complete owner changed during layered fixture preparation')
        self.fixture_manifest()
        common.write_json(self.out / FIXTURE_RECORD, self.fixture_inputs())
        self.prepared()

    def run(self):
        for name in ('execution.json', 'qualification.json'):
            (self.out / name).unlink(missing_ok=True)
        prepared = self.prepared()
        self.checked_build(prepared)
        directory = self.out / 'product-output'
        if directory.exists():
            raise RuntimeError('Layered execution requires a fresh output directory')
        directory.mkdir()
        if self.scene_execution['mode'] == common.SHARED_MODEL:
            log = directory / 'execution.log'
            self.execute('execute-layered-six-scenes', [str(self.out / self.executable_name),
                         str(self.out / 'fixtures'), str(directory)], log)
            rows = [json.loads(line) for line in log.read_text().splitlines() if line.strip()]
        else:
            rows = self.run_parallel_scenes(self.out / self.executable_name, directory)
        validate_rows(rows, self.product)
        self.prepared()
        self.checked_build(prepared)
        common.write_json(self.out / 'execution.json', dict(exit_code=0, rows=rows,
            output_sha256=common.tree_hashes(directory), scene_execution=self.scene_execution,
            preparation_sha256=common.sha(self.out / 'preparation.json'),
            build_sha256=common.sha(self.out / 'build.json')))

    def verify(self):
        (self.out / 'qualification.json').unlink(missing_ok=True)
        prepared = self.prepared()
        self.checked_build(prepared)
        path, directory = self.out / 'execution.json', self.out / 'product-output'
        record = json.loads(path.read_text())
        if (type(record.get('exit_code')) is not int or record['exit_code'] != 0
                or record.get('output_sha256') != common.tree_hashes(directory)
                or record.get('scene_execution') != self.scene_execution
                or record.get('preparation_sha256') != common.sha(self.out / 'preparation.json')
                or record.get('build_sha256') != common.sha(self.out / 'build.json')):
            raise RuntimeError('Layered outputs or executing model changed before verification')
        validate_rows(record['rows'], self.product)
        identity = common.sha(path)
        log = self.out / 'layer-product-verify.log'
        mode = '--product' if self.product else '--smoke'
        self.execute('verify-layered-six-scenes', self.sbt(
            'opengpu.system.VerifyNativeLayeredProduct ' + mode + ' --output '
            + json.dumps(str(directory)) + ' --tile-log2 ' + str(self.tile_log2), '3G'), log)
        verify_marker(log, self.product, self.tile_log2)
        self.prepared()
        self.checked_build(prepared)
        if common.sha(path) != identity or common.tree_hashes(directory) != record['output_sha256']:
            raise RuntimeError('Layered execution changed during independent verification')
        common.write_json(self.out / 'qualification.json', dict(passed=True, profile=PROFILE,
            ownerResourceStage=FULL_STAGE, execution_sha256=identity, tileLog2=self.tile_log2,
            product=self.product, clockQualified=False, fpsQualified=False,
            scope='Six original GNJ2 native scenes on one complete geometry-compute owner DUT. '
                  'Exact images, samples, resolves, guards and complete GNY2. '
                  'No complete API, physical timing or FPS claim.'))
        print(marker(self.product, self.tile_log2), flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true')
    mode.add_argument('--product', action='store_true')
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--flashsim-dir', required=True, type=Path)
    parser.add_argument('--tile-log2', choices=(3, 4), type=int, default=3)
    parser.add_argument('--stage', choices=('all', 'prepare', 'generate', 'build', 'run', 'verify'), default='all')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--scene-jobs', type=int, default=1)
    parser.add_argument('--shards', type=int, default=32)
    parser.add_argument('--reshard-dir', type=Path)
    parser.add_argument('--reshard-tool', type=Path)
    parser.add_argument('--precompiled-header', action='store_true')
    parser.add_argument('--shard-opt', choices=('-O0', '-O1', '-O2', '-O3'), default='-O2')
    parser.add_argument('--conservative-alias', action='store_true')
    args = parser.parse_args()
    if args.jobs < 1 or args.shards < 1 or not 1 <= args.scene_jobs <= 6:
        parser.error('jobs/shards must be positive; scene-jobs must be 1..6')
    try:
        out = checked_output(args.output)
    except RuntimeError as error:
        parser.error(str(error))
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = Runner(out, args.product, args.flashsim_dir, args.scene_jobs, args.shards,
            reshard=args.reshard_dir, reshard_tool=args.reshard_tool,
            precompiled_header=args.precompiled_header, shard_opt=args.shard_opt,
            conservative_alias=args.conservative_alias,
            api_capacity=True, two_cu_vga=True, tile_log2=args.tile_log2)
        try:
            for stage in ('prepare', 'build', 'run', 'verify'):
                if args.stage in ('all', stage):
                    if stage == 'build':
                        runner.build(args.jobs)
                    else:
                        getattr(runner, stage)()
            if args.stage == 'generate':
                runner.generate()
        except BaseException as error:
            runner.state('stopped-for-audit', error=str(error), automatic_retry=False)
            raise


if __name__ == '__main__':
    main()
