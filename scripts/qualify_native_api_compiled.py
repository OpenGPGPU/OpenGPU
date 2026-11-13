#!/usr/bin/env python3
"""Explicit local full-capacity native boundary-frame qualification."""
import argparse
from datetime import datetime, timezone
import errno
import fcntl
import json
import os
from pathlib import Path
import re
import subprocess
import time

import qualify_native_product_compiled as common

ROOT = Path(__file__).resolve().parents[1]
SCENES = ['native-api-extent-1x', 'native-api-extent-4x']
DRIVER = ['scripts/native_api_driver.cpp', 'scripts/native_api_frame_fixture.hpp',
          'scripts/native_host_simulation.hpp', 'scripts/native_execution_progress.hpp', 'scripts/native_frame_fixture.hpp',
          'scripts/native_frame_axi.hpp', 'scripts/qualify_native_product_compiled.py',
          'scripts/qualify_native_api_compiled.py']


def check_profile(manifest, product=True):
    width, height = (640, 480) if product else (32, 32)
    expected = dict(version=1, product=product, apiExtent=True, frameWidth=width, frameHeight=height,
        screenWidth=640, screenHeight=480, computeUnits=4, lanes=16, warps=8,
        engines=4, axiDataBytes=32, pagedCapacity=65536, payloadBytes=524288,
        realSramModels=True, rtlEmitted=True, scenes=SCENES)
    if any(manifest.get(k) != v for k, v in expected.items()):
        raise RuntimeError('Incomplete or mismatched native API execution/storage profile')


def guard_prepared(out, product=True):
    record = json.loads((out / 'preparation.json').read_text())
    if record['exit_code'] != 0 or record['source_sha256'] != common.rtl_inputs():
        raise RuntimeError('Native API preparation does not match the current source')
    check_profile(json.loads((out / 'fixture-manifest.json').read_text()), product)
    if (record['manifest_sha256'] != common.sha(out / 'fixture-manifest.json')
            or record['rtl_sha256'] != common.tree_hashes(out / 'rtl')
            or record['fixture_sha256'] != common.tree_hashes(out / 'fixtures')):
        raise RuntimeError('Native API RTL or fixture bytes changed')
    return record


def validate_frame(row, name, samples, product=True):
    width, height = (640, 480) if product else (32, 32)
    if (not isinstance(row, dict) or row.get('scene') != name
            or (row.get('frameWidth'), row.get('frameHeight'), row.get('samples')) != (width, height, samples)
            or row.get('trafficDrained') is not True or row.get('serviceCycles', 0) <= 0
            or row.get('gpuCycles', 0) <= 0 or row.get('imageVerificationRequired') is not True
            or row.get('clockQualified') is not False or row.get('fpsQualified') is not False
            or len(row.get('phaseWorkerCycles', [])) != 6):
        raise RuntimeError('Incomplete native API execution metrics for ' + name)


def validate_metrics(rows, product=True):
    if [r.get('scene') for r in rows] != SCENES:
        raise RuntimeError('Both native API frames must complete in order')
    for row, name, samples in zip(rows, SCENES, [1, 4]):
        validate_frame(row, name, samples, product)


def verify_marker(log, product=True):
    text = log.read_text()
    images = re.findall(r'NATIVE_API_COMPILED_IMAGE id=([a-z0-9-]+) frame=(\d+)x(\d+) exactStoredImagesAndGuards=true', text)
    expected = 'NATIVE_COMPILED_API PASS frames=2 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
    width, height = ('640', '480') if product else ('32', '32')
    if expected not in text or images != [(name, width, height) for name in SCENES]:
        raise RuntimeError('Complete independent native API stored-image verification is missing')


class Runner(common.Runner):
    executable_name = 'native_api_driver'

    def __init__(self, out, product=True, hierarchy_profile='flat', scene_jobs=1, driver_root=None):
        if scene_jobs not in (1, 2):
            raise ValueError('--scene-jobs must be 1 or 2')
        super().__init__(out, product, hierarchy_profile, scene_jobs)
        self.driver_root = Path(driver_root).resolve(strict=True) if driver_root is not None else ROOT

    def mode(self):
        return '--api-extent-product' if self.product else '--smoke'

    def driver_sources(self):
        return DRIVER

    def prepared(self):
        return guard_prepared(self.out, self.product)

    def checked_build(self, prepared):
        return common.guard_build(self.out, prepared, self.hierarchy_profile,
                                  self.driver_sources(), self.executable_name, self.driver_root)

    def require_current_driver(self):
        if self.driver_root != ROOT:
            raise RuntimeError('A historical driver checkout is valid only for verification')

    def build(self, jobs):
        self.require_current_driver()
        return super().build(jobs)

    def sbt(self, main, heap):
        command = super().sbt(main, heap)
        return command[:-1] + ['-Dopengpu.apiExtentQualification=1'] + command[-1:]

    def prepare(self):
        self.require_current_driver()
        sources = common.rtl_inputs()
        for name in ('preparation.json', 'fixture-manifest.json', 'prepare.log'):
            (self.out / name).unlink(missing_ok=True)
        self.execute('prepare-api-host-and-inputs', self.sbt('opengpu.system.PrepareCompiledNativeApi '
            + self.mode() + ' --output ' + json.dumps(str(self.out)),
            common.PRODUCT_JAVA_HEAP if self.product else '6G'), self.out / 'prepare.log')
        if sources != common.rtl_inputs():
            raise RuntimeError('Source changed during native API preparation')
        width, height = (640, 480) if self.product else (32, 32)
        common.verify_prepare_completion(self.out / 'prepare.log',
            f'NATIVE_API_COMPILED_INPUTS frames=2 frame={width}x{height} rtlEmitted=true')
        manifest = json.loads((self.out / 'fixture-manifest.json').read_text())
        check_profile(manifest, self.product)
        if manifest.get('rtlEmitted') is not True:
            raise RuntimeError('Native API preparation must emit RTL, not fixtures only')
        common.write_json(self.out / 'preparation.json', dict(version=1, exit_code=0,
            source_sha256=sources, manifest_sha256=common.sha(self.out / 'fixture-manifest.json'),
            rtl_sha256=common.tree_hashes(self.out / 'rtl'), fixture_sha256=common.tree_hashes(self.out / 'fixtures')))

    def run_parallel_frames(self, executable, directory):
        running, rows, failures, memory_retries = {}, {}, {}, {}

        def launch(name, retry=False):
            target = directory / 'scenes' / name
            try:
                target.mkdir(parents=True, exist_ok=retry)
                command = [str(executable), self.mode(), str(self.out / 'fixtures'), str(target), '--scene', name]
                with (target / 'execution.log').open('w') as stream:
                    child = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
                running[name] = (child, target)
            except (OSError, MemoryError) as error:
                is_memory = isinstance(error, MemoryError) or getattr(error, 'errno', None) == errno.ENOMEM
                if is_memory and not retry:
                    memory_retries[name] = dict(initial_error=str(error))
                else:
                    failures[name] = 'could not start: ' + str(error)

        def report(stage):
            self.state('execute-api-frames-parallel', directory=str(directory), scene_execution=self.scene_execution,
                phase=stage, completed=list(rows), failures=failures, memory_retries=memory_retries,
                children=[dict(scene=n, child_pid=p.pid, directory=str(t)) for n, (p, t) in running.items()])

        def drain():
            while running:
                for name, (child, target) in list(running.items()):
                    code = child.poll()
                    if code is None:
                        continue
                    try:
                        if code:
                            raise RuntimeError(f'exit {code}; inspect {target / "execution.log"}')
                        values = [json.loads(line) for line in (target / 'execution.log').read_text().splitlines() if line.strip()]
                        if len(values) != 1:
                            raise RuntimeError('missing or extra frame metrics')
                        validate_frame(values[0], name, 1 if name == SCENES[0] else 4, self.product)
                        os.link(target / (name + '.memory'), directory / (name + '.memory'))
                        rows[name] = values[0]
                    except (OSError, ValueError, RuntimeError, TypeError, MemoryError) as error:
                        # Retain the other frame's work even if this one fails.
                        failures[name] = str(error)
                    del running[name]
                    report('draining')
                if running:
                    time.sleep(0.1)

        try:
            for name in SCENES:
                launch(name)
            report('parallel')
            drain()
            # A genuine ENOMEM at spawn is retryable once after all other
            # children exit, when their model memory has been released.
            # Do not infer OOM from a signal or retry an unknown failure.
            for name in memory_retries:
                launch(name, retry=True)
                report('serial-memory-retry')
                drain()
                memory_retries[name]['outcome'] = 'completed' if name in rows else 'failed'
                report('serial-memory-retry-complete')
        except BaseException:
            for child, _ in running.values():
                if child.poll() is None:
                    child.terminate()
            for child, _ in running.values():
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill(); child.wait()
            raise
        if memory_retries:
            common.write_json(directory / 'memory-retries.json', memory_retries)
        if failures:
            raise RuntimeError('Native API frame failures: ' + json.dumps(failures, sort_keys=True))
        ordered = [rows[name] for name in SCENES]
        (directory / 'execution.log').write_text(''.join(json.dumps(row) + '\n' for row in ordered))
        return ordered

    def run(self):
        self.require_current_driver()
        prepared = self.prepared(); self.checked_build(prepared)
        executable = self.out / self.executable_name
        directory = self.out / 'runs' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + str(os.getpid()))
        directory.mkdir(parents=True)
        if self.scene_execution['mode'] == common.SHARED_MODEL:
            self.execute('execute-two-api-frames', [str(executable), self.mode(), str(self.out / 'fixtures'), str(directory)], directory / 'execution.log')
            rows = [json.loads(line) for line in (directory / 'execution.log').read_text().splitlines() if line.strip()]
        else:
            rows = self.run_parallel_frames(executable, directory)
        validate_metrics(rows, self.product)
        self.prepared(); self.checked_build(prepared)
        common.write_json(self.out / 'execution.json', dict(exit_code=0, directory=str(directory), scenes=rows,
            scene_execution=self.scene_execution,
            output_sha256=common.tree_hashes(directory), executable_sha256=common.sha(executable),
            preparation_sha256=common.sha(self.out / 'preparation.json'), build_sha256=common.sha(self.out / 'build.json')))

    def verify(self):
        prepared = self.prepared(); build = self.checked_build(prepared)
        execution = json.loads((self.out / 'execution.json').read_text())
        if execution.get('scene_execution', dict(mode=common.SHARED_MODEL, max_workers=1)) != self.scene_execution:
            raise RuntimeError('Native API execution mode does not match the requested verification')
        directory = Path(execution['directory'])
        if (execution['exit_code'] or execution['output_sha256'] != common.tree_hashes(directory)
                or execution['preparation_sha256'] != common.sha(self.out / 'preparation.json')
                or execution['build_sha256'] != common.sha(self.out / 'build.json')
                or execution['executable_sha256'] != common.sha(self.out / self.executable_name)):
            raise RuntimeError('Native API output bytes changed before independent verification')
        validate_metrics(execution['scenes'], self.product)
        self.execute('verify-all-api-stored-images', self.sbt('opengpu.system.VerifyCompiledNativeApi '
            + self.mode() + ' --output ' + json.dumps(str(directory)),
            common.PRODUCT_JAVA_HEAP if self.product else '6G'), self.out / 'verify.log')
        verify_marker(self.out / 'verify.log', self.product)
        self.prepared(); self.checked_build(prepared)
        if execution['output_sha256'] != common.tree_hashes(directory):
            raise RuntimeError('Native API output bytes changed during independent verification')
        width, height = (640, 480) if self.product else (32, 32)
        execution_scope = ('one shared model with reset between frames' if self.scene_execution['mode'] == common.SHARED_MODEL
                           else 'independent fresh models for the two frames')
        common.write_json(self.out / 'qualification.json', dict(passed=True, product=self.product, api_extent=True,
            scenes=execution['scenes'], source_sha256=prepared['source_sha256'],
            scene_execution=self.scene_execution,
            shared_model_reset_exercised=self.scene_execution['mode'] == common.SHARED_MODEL,
            driver_checkout=str(self.driver_root), driver_sha256=build['context']['driver_sha256'],
            bound_assertion_layer_sha256=common.assertion_layer_hashes(prepared['rtl_sha256']),
            preparation_sha256=common.sha(self.out / 'preparation.json'), build_sha256=common.sha(self.out / 'build.json'),
            execution_sha256=common.sha(self.out / 'execution.json'), verify_log_sha256=common.sha(self.out / 'verify.log'),
            exact_stored_images_and_guards=True, traffic_drained=True,
            maximum_extent_rendered=False, whole_host_clock_qualified=False, FPS_qualified=False, H6_accepted=False,
            scope=f'Two complete {width}x{height} native 1x/4x target renders in {execution_scope}, '
                  'with full 4CU/16lane/8warp/4engine execution, real SRAMs, GCR1/GNC3 discovery '
                  'and exclusive last-pixel scissor. Every stored sample/resolve/guard verified. '
                  'Does not qualify the six 720p workloads, 2048x2048 rendering, whole-host clock/FPS, '
                  'all API features or H6 closure.'))
        self.state('complete-two-api-frame-qualification', passed=True, FPS_qualified=False, H6_accepted=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--api-extent-product', action='store_true')
    mode.add_argument('--smoke', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=['all', 'prepare', 'build', 'run', 'verify'], default='all')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--scene-jobs', type=int, choices=[1, 2], default=1)
    parser.add_argument('--original-driver-checkout', type=Path,
                        help='original GPU checkout for verifying an existing model')
    parser.add_argument('--hierarchy-profile', choices=common.HIERARCHY_PROFILES, default='flat')
    args = parser.parse_args()
    if args.jobs <= 0:
        parser.error('--jobs must be positive')
    if args.original_driver_checkout is not None and args.stage != 'verify':
        parser.error('--original-driver-checkout requires --stage verify')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = Runner(out, args.api_extent_product, args.hierarchy_profile, args.scene_jobs,
                        args.original_driver_checkout)
        try:
            for stage in ['prepare', 'build', 'run', 'verify']:
                if args.stage in ['all', stage]:
                    if stage == 'build': runner.build(args.jobs)
                    else: getattr(runner, stage)()
        except BaseException as error:
            runner.state('stopped-for-audit', error=str(error), automatic_retry=False)
            raise


if __name__ == '__main__':
    main()
