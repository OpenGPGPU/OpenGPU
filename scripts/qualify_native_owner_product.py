#!/usr/bin/env python3
"""Run six original scenes on one separately prepared, complete 96-word owner DUT."""
import argparse
import fcntl
import json
from pathlib import Path

import qualify_native_alignment_sequence as sequence

common, flash, owner = sequence.common, sequence.flash, sequence.owner
ROOT = Path(__file__).resolve().parents[1]


def validate_rows(rows, product):
    width, height = common.frame_size(product, True)
    if [r.get('scene') for r in rows] != common.SCENES:
        raise RuntimeError('All six original owner product scenes must complete in order')
    expected = dict(profile=owner.PROFILE, computeUnits=2, nativeEngines=4,
                    nativeMaxWidth=2048, nativeMaxHeight=2048,
                    resourceDiscoveryWords=96, computeFeatureFlags=0x3ffff,
                    frameWidth=width, frameHeight=height, trafficDrained=True,
                    imageVerificationRequired=True, clockQualified=False, fpsQualified=False)
    expected.update(cycleScopeVersion=2, serviceCycleScope='post-submit-to-irq',
                    frameCycleScope='reset-through-verified-retirement',
                    submittedCommands=1, successfulCompletions=1, retiredCompletions=1,
                    completionCountSource='observed-command-retirement',
                    drawCompletionCountAvailable=False, primitiveCompletionCountAvailable=False,
                    liveProfilePreflight=True,
                    idleBeforeSubmit=True, shaderInputSource='native-product-hand-encoded')
    for row in rows:
        if (any(type(row.get(k)) is not type(v) or row[k] != v for k, v in expected.items())
                or row.get('samples') != (4 if row['scene'] == 'msaa-4x' else 1)
                or type(row.get('samples')) is not int
                or any(type(row.get(k)) is not int or not 0 < row[k] < 2**64
                       for k in ('serviceCycles', 'gpuCycles'))
                or any(type(row.get(k)) is not int or not 0 <= row[k] < 2**64
                       for k in ('readBusBytes', 'writeBusBytes', 'enabledWriteBytes'))
                or not isinstance(row.get('phaseWorkerCycles'), list)
                or len(row['phaseWorkerCycles']) != 6
                or any(type(v) is not int or not 0 <= v < 2**64 for v in row['phaseWorkerCycles'])):
            raise RuntimeError('Incomplete or mismatched full owner execution metrics')
        intervals = ('gpuCyclesAtServiceBegin', 'gpuCyclesAtIrq', 'frameCycles')
        if (any(type(row.get(k)) is not int or not 0 < row[k] < 2**64 for k in intervals)
                or row['gpuCyclesAtIrq'] - row['gpuCyclesAtServiceBegin'] != row['serviceCycles']
                or not row['gpuCyclesAtIrq'] <= row['gpuCycles'] < row['frameCycles']):
            raise RuntimeError('Owner service and reset-through-retirement intervals differ')
        expected_gnx = [0x474e5801, 0, 0, 0, 0, 0, 7, 8, 9, 0, 0, 0]
        if (not isinstance(row.get('gnx'), list) or len(row['gnx']) != 12
                or any(type(v) is not int for v in row['gnx']) or row['gnx'] != expected_gnx):
            raise RuntimeError('Owner completion lacks complete original GNX fault and identity observations')
        registers = row.get('profileRegisters')
        if (not isinstance(registers, list) or len(registers) != 5
                or any(type(r) is not dict or set(r) != {'address', 'value'}
                       or type(r['address']) is not int or r['address'] != address
                       or type(r['value']) is not int or not 0 <= r['value'] < 2**32
                       for r, address in zip(registers, (0, 0x60, 0x68, 0x320, 0x158)))):
            raise RuntimeError('Owner live profile register observations are incomplete')
        device, caps, pointer, quota, shader = (r['value'] for r in registers)
        required_caps = (1 << 6) | (1 << 22) | (1 << 23) | (1 << 25) | (1 << 29)
        lanes, warps, banks, cus = (shader >> shift & 255 for shift in (0, 8, 16, 24))
        if (device & 0xffff < 2 or caps & required_caps != required_caps
                or pointer != 0x160 or not quota or (lanes, warps, cus) != (16, 8, 2)
                or banks < 2 or banks & 1 or lanes * warps != (caps >> 8 & 255)):
            raise RuntimeError('Owner live profile contradicts the complete two-CU contract')


class Runner(sequence.OwnerRunner):
    def driver_path(self):
        return ROOT / 'scripts/native_product_driver.cpp'

    def driver_sources(self):
        return flash.DRIVER + ['scripts/qualify_native_alignment_sequence.py',
                               'scripts/qualify_native_owner_product.py']

    def run(self):
        for name in ('execution.json', 'qualification.json'):
            (self.out / name).unlink(missing_ok=True)
        prepared = self.prepared()
        self.checked_build(prepared)
        directory = self.out / 'product-output'
        if directory.exists():
            raise RuntimeError('Owner product execution requires a fresh output directory')
        directory.mkdir()
        if self.scene_execution['mode'] == common.SHARED_MODEL:
            log = directory / 'execution.log'
            self.execute('execute-owner-six-scenes', [str(self.out / self.executable_name),
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
        path = self.out / 'execution.json'
        record = json.loads(path.read_text())
        directory = self.out / 'product-output'
        if (type(record.get('exit_code')) is not int or record['exit_code'] != 0
                or record.get('output_sha256') != common.tree_hashes(directory)
                or record.get('scene_execution') != self.scene_execution
                or record.get('preparation_sha256') != common.sha(self.out / 'preparation.json')
                or record.get('build_sha256') != common.sha(self.out / 'build.json')):
            raise RuntimeError('Owner product outputs or executing model changed before verification')
        validate_rows(record['rows'], self.product)
        identity = common.sha(path)
        mode = '--product' if self.product else '--smoke'
        log = self.out / 'product-verify.log'
        self.execute('verify-owner-six-scenes', self.sbt('opengpu.system.VerifyNativeOwnerProduct '
            + mode + ' --output ' + json.dumps(str(directory)) + ' --tile-log2 '
            + str(self.tile_log2), '3G'), log)
        verify_marker(log, self.product, self.tile_log2)
        self.prepared()
        self.checked_build(prepared)
        if common.sha(path) != identity or common.tree_hashes(directory) != record['output_sha256']:
            raise RuntimeError('Owner product execution changed during independent verification')
        common.write_json(self.out / 'qualification.json', dict(passed=True, profile=owner.PROFILE,
            execution_sha256=identity, tileLog2=self.tile_log2, product=self.product,
            clockQualified=False, fpsQualified=False,
            scope='Six original native scenes on the complete owner DUT with exact stored images, '
                  'samples, resolves and read-only guards. No complete API, physical timing or FPS claim.'))
        print(marker(self.product, self.tile_log2), flush=True)


def marker(product, tile_log2):
    width, height = common.frame_size(product, True)
    return (f'NATIVE_OWNER_PRODUCT PASS profile={owner.PROFILE} scenes=6 tile={1 << tile_log2} '
            f'frame={width}x{height} computeUnits=2 clockQualified=false fpsQualified=false')


def verify_marker(log, product, tile_log2):
    width, height = common.frame_size(product, True)
    expected = [f'NATIVE_OWNER_IMAGE scene={name} tile={1 << tile_log2} frame={width}x{height} '
                'exactImagesAndReadOnlyGuards=true' for name in common.SCENES]
    lines = log.read_text().splitlines()
    if ([s for s in lines if s.startswith('NATIVE_OWNER_IMAGE ')] != expected
            or [s for s in lines if s.startswith('NATIVE_OWNER_PRODUCT PASS')] != [marker(product, tile_log2)]):
        raise RuntimeError('Complete independent owner image/sample/resolve/guard verification is missing')


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
    if args.jobs < 1 or args.shards < 1:
        parser.error('--jobs and --shards must be positive')
    try:out=sequence.checked_owner_output(args.output)
    except RuntimeError as error:parser.error(str(error))
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
                    if stage == 'build': runner.build(args.jobs)
                    else: getattr(runner, stage)()
            if args.stage == 'generate': runner.generate()
        except BaseException as error:
            runner.state('stopped-for-audit', error=str(error), automatic_retry=False)
            raise


if __name__ == '__main__':
    main()
