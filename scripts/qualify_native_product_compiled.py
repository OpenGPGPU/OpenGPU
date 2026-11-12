#!/usr/bin/env python3
"""Explicit local, complete native product execution with an in-process AXI driver."""
import argparse
from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
TOP = 'GpuHostSystemAxi'
SCENES = ['desktop-composition', 'textured-3d', 'opaque-overdraw',
          'transparent-layers', 'small-triangles', 'msaa-4x']
DRIVER = ['scripts/native_product_driver.cpp', 'scripts/native_frame_axi.hpp',
          'scripts/native_frame_fixture.hpp', 'scripts/native_host_simulation.hpp', 'scripts/native_execution_progress.hpp',
          'scripts/qualify_native_product_compiled.py']
HIERARCHY_PROFILES = ('flat', 'baseline', 'fp32')
SHARED_MODEL = 'sequential-shared-model'
FRESH_MODELS = 'fresh-model-per-scene'
PRODUCT_JAVA_HEAP = '24G'


def compare_reference(rows, reference, scene_execution):
    # Retained Scala reports predate the label and use one shared model.
    reference_mode = reference.get('scene_execution', {}).get('mode', SHARED_MODEL)
    if reference_mode != scene_execution['mode']:
        raise RuntimeError('Reference and native scene execution modes differ')
    baseline = reference['scenes']
    if [s.get('id') for s in baseline] != SCENES:
        raise RuntimeError('Reference must contain all six native scenes in order')
    fields = ['serviceCycles', 'gpuCycles', 'readBusBytes', 'writeBusBytes',
              'enabledWriteBytes', 'phaseWorkerCycles']
    for row, prior in zip(rows, baseline):
        if any(row[k] != prior[k] for k in fields):
            raise RuntimeError('Native/reference cycle or traffic counters differ: ' + row['scene'])


def hierarchy_plan(files, profile):
    if profile not in HIERARCHY_PROFILES:
        raise RuntimeError('Unknown native simulation hierarchy profile: ' + profile)
    if profile != 'flat' and assertion_layer_hashes(files):
        raise RuntimeError('Generated bound checks access private state; use the flat hierarchy profile')
    available = {Path(name).stem for name in files if name.endswith('.sv')}
    families = ['GpuComputeUnit', 'Rgba8BlendFmaEngine']
    if profile == 'fp32':
        # Keep format-selecting arithmetic inside its FP32 wrappers so library
        # boundaries do not expose general-format inputs or inhibit pruning.
        families += ['ClipFloatArithmetic', 'ClipFloatStreamingAddMul',
                     'Fp32FragmentPreparation', 'Fp32PrimitiveRasterEngine']
    modules = []
    for family in families:
        matches = sorted(n for n in available if (profile == 'fp32' or n == family)
                         and re.fullmatch(re.escape(family) + r'(?:_\d+)?', n))
        if not matches:
            raise RuntimeError('Native hierarchy family is absent from emitted RTL: ' + family)
        modules.extend(matches)
    return dict(profile=profile, modules=[] if profile == 'flat' else modules)


def hierarchy_control(plan):
    return '`verilator_config\n' + ''.join('hier_block -module "' + n + '"\n' for n in plan['modules'])


def assertion_layer_hashes(files):
    return {name: files[name] for name in sorted(files)
            if re.fullmatch(r'verification/assert/layers-[^/]+\.sv', name)}


def simulation_inputs(rtl):
    """Keep generated checks bound even when CIRCT omits layers from filelist.f."""
    rtl = rtl.resolve()
    inputs = []
    seen = set()
    listed = [rtl / line.strip() for line in (rtl / 'filelist.f').read_text().splitlines()
              if line.strip() and not line.lstrip().startswith(('#', '//'))]
    layers = sorted((rtl / 'verification/assert').glob('layers-*.sv'))
    if any(p.name.endswith('_Verification_Assert.sv') for p in listed) and not layers:
        raise RuntimeError('Generated assertion modules have no bind layers')
    directories = {rtl}
    for source in [*listed, *layers]:
        source = source.resolve()
        if not source.is_relative_to(rtl) or not source.is_file():
            raise RuntimeError('Missing or external emitted simulation source: ' + str(source))
        if source in seen:
            continue
        seen.add(source)
        inputs.append(source)
        directories.add(source.parent)
        # Include sibling checks and parent Verification layers. Their bytes
        # are already covered by the prepared RTL hash tree.
        directories.update(p for p in source.parents if p.is_relative_to(rtl))
    return inputs, sorted(directories)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def write_json(path, value):
    temporary = path.with_suffix(path.suffix + '.tmp')
    temporary.write_text(json.dumps(value, indent=2) + '\n')
    temporary.replace(path)


def rtl_inputs():
    files = {ROOT / 'build.sbt', *list((ROOT / 'src').rglob('*.scala'))}
    files.update(p for p in (ROOT / 'project').glob('*')
                 if p.is_file() and p.suffix in {'.scala', '.sbt', '.properties'})
    macros = ROOT / 'depends/asap7_sram_0p0/generated/verilog'
    for name in ['srambank_64x4x32_6t122.v', 'srambank_64x4x64_6t122.v']:
        if not (macros / name).is_file():
            raise RuntimeError('Initialize the pinned asap7_sram_0p0 submodule first')
    files.update(macros.glob('*.v'))
    return {str(p.relative_to(ROOT)): sha(p) for p in sorted(files)}


def tree_hashes(directory):
    return {str(p.relative_to(directory)): sha(p)
            for p in sorted(directory.rglob('*')) if p.is_file()}


def check_profile(manifest, product, api_capacity=False):
    expected = (1280, 720) if product else (32, 32)
    if (manifest.get('version') != 1 or manifest.get('product') != product
            or (manifest.get('frameWidth'), manifest.get('frameHeight')) != expected
            or manifest.get('scenes') != SCENES or not manifest.get('rtlEmitted')
            or any(manifest.get(k) != v for k, v in
                   {'computeUnits': 4, 'lanes': 16, 'warps': 8, 'axiDataBytes': 32}.items())):
        raise RuntimeError('Incomplete or mismatched native product profile')
    if manifest.get('apiCapacity', False) is not api_capacity:
        raise RuntimeError('Native API-capacity profile differs')
    if api_capacity:
        expected_display = (1280, 720) if product else (640, 480)
        if ((manifest.get('screenWidth'), manifest.get('screenHeight')) != expected_display
                or any(manifest.get(k) != v for k, v in
                       {'nativeMaxWidth': 2048, 'nativeMaxHeight': 2048,
                        'pagedCapacity': 65536, 'engines': 4, 'payloadBytes': 524288}.items())):
            raise RuntimeError('Incomplete combined product/API-capacity profile')


def guard_prepared(out, product, api_capacity=False):
    record = json.loads((out / 'preparation.json').read_text())
    if record['exit_code'] != 0 or record['source_sha256'] != rtl_inputs():
        raise RuntimeError('Prepared RTL/fixtures do not match the current source')
    manifest = json.loads((out / 'fixture-manifest.json').read_text())
    check_profile(manifest, product, api_capacity)
    if (record['manifest_sha256'] != sha(out / 'fixture-manifest.json')
            or record['rtl_sha256'] != tree_hashes(out / 'rtl')
            or record['fixture_sha256'] != tree_hashes(out / 'fixtures')):
        raise RuntimeError('Prepared RTL or fixture bytes changed')
    return record


def validate_metrics(rows, product):
    width, height = (1280, 720) if product else (32, 32)
    if [r.get('scene') for r in rows] != SCENES:
        raise RuntimeError('All six native scenes must complete in order')
    for row in rows:
        if ((row.get('frameWidth'), row.get('frameHeight')) != (width, height)
                or not row.get('trafficDrained') or row.get('serviceCycles', 0) <= 0
                or row.get('imageVerificationRequired') is not True
                or row.get('gpuCycles', 0) <= 0 or row.get('clockQualified') is not False
                or row.get('fpsQualified') is not False or len(row.get('phaseWorkerCycles', [])) != 6):
            raise RuntimeError('Incomplete native execution metrics')


def verify_marker(log, product):
    expected = 'NATIVE_COMPILED_PRODUCT PASS scenes=6 fullExecutionAndStorage=true exactStoredImagesAndGuards=true clockQualified=false fpsQualified=false'
    text = log.read_text()
    width, height = (1280, 720) if product else (32, 32)
    images = re.findall(r'NATIVE_COMPILED_IMAGE id=([a-z0-9-]+) frame=(\d+)x(\d+) exactStoredImagesAndGuards=true', text)
    if expected not in text or images != [(n, str(width), str(height)) for n in SCENES]:
        raise RuntimeError('Complete independent stored-image verification is missing')


def guard_build(out, prepared, hierarchy_profile='flat', driver_files=None,
                executable_name='native_product_driver', driver_root=None):
    if driver_files is None:
        driver_files = DRIVER
    driver_root = ROOT if driver_root is None else Path(driver_root).resolve(strict=True)
    build = json.loads((out / 'build.json').read_text())
    if (build['exit_code'] != 0 or build['executable_sha256'] != sha(out / executable_name)
            or build['context']['rtl_sha256'] != prepared['rtl_sha256']
            or build['context']['profile_manifest_sha256'] != prepared['manifest_sha256']
            or build['context']['driver_sha256'] != {n: sha(driver_root / n) for n in driver_files}
            or build['context'].get('assertion_layers') != assertion_layer_hashes(prepared['rtl_sha256'])
            or build['context'].get('hierarchy') != hierarchy_plan(prepared['rtl_sha256'], hierarchy_profile)):
        raise RuntimeError('Native model build does not match the current RTL/profile/driver')
    return build


class Runner:
    executable_name = 'native_product_driver'

    def driver_sources(self):
        return DRIVER

    def prepared(self):
        return guard_prepared(self.out, self.product, self.api_capacity)

    def checked_build(self, prepared):
        return guard_build(self.out, prepared, self.hierarchy_profile,
                           self.driver_sources(), self.executable_name)

    def backend(self):
        return 'verilator'

    def __init__(self, out, product, hierarchy_profile='flat', scene_jobs=1, api_capacity=False):
        if not 1 <= scene_jobs <= len(SCENES):
            raise ValueError('--scene-jobs must be between 1 and 6')
        if not isinstance(api_capacity, bool):
            raise ValueError('api_capacity must be boolean')
        self.out, self.product, self.api_capacity = out, product, api_capacity
        self.hierarchy_profile = hierarchy_profile
        self.scene_execution = dict(mode=SHARED_MODEL if scene_jobs == 1 else FRESH_MODELS,
                                    max_workers=scene_jobs)

    def state(self, stage, **fields):
        write_json(self.out / 'state.json', dict(timestamp=datetime.now(timezone.utc).isoformat(),
            pid=os.getpid(), stage=stage, product=self.product, **fields))

    def execute(self, stage, command, log, cwd=ROOT):
        start = time.monotonic()
        with log.open('w') as stream:
            child = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT)
            self.state(stage, child_pid=child.pid, command=command, log=str(log))
            code = child.wait()
        self.state(stage + '-terminal', exit_code=code, elapsed_seconds=time.monotonic() - start)
        if code:
            raise RuntimeError(f'{stage} failed with exit {code}; inspect {log}')

    def sbt(self, main, heap):
        heap = os.environ.get('OPENGPU_QUALIFY_JAVA_HEAP', heap)
        if not re.fullmatch(r'[1-9][0-9]*[kKmMgG]', heap):
            raise ValueError('OPENGPU_QUALIFY_JAVA_HEAP must be a positive size such as 24G')
        command = ['sbt', '-J-Xmx' + heap, '-J-XX:+ExitOnOutOfMemoryError',
                '-Dopengpu.fullHostQualification=1',
                '-Dopengpu.productQualification=' + ('1' if self.product else '0')]
        if self.api_capacity:
            command += ['-Dopengpu.productApiCapacity=1', '-Dopengpu.apiExtentQualification=1']
        return command + ['Test / runMain ' + main]

    def prepare(self):
        sources = rtl_inputs()
        mode = '--product' if self.product else '--smoke'
        self.execute('prepare', self.sbt('opengpu.system.PrepareCompiledNativeProduct '
                     + mode + ' --output ' + json.dumps(str(self.out)),
                     PRODUCT_JAVA_HEAP if self.product else '6G'), self.out / 'prepare.log')
        if sources != rtl_inputs():
            raise RuntimeError('Source changed during RTL/fixture preparation')
        check_profile(json.loads((self.out / 'fixture-manifest.json').read_text()), self.product, self.api_capacity)
        write_json(self.out / 'preparation.json', dict(version=1, exit_code=0,
            source_sha256=sources, manifest_sha256=sha(self.out / 'fixture-manifest.json'),
            rtl_sha256=tree_hashes(self.out / 'rtl'), fixture_sha256=tree_hashes(self.out / 'fixtures')))

    def build(self, jobs):
        prepared = self.prepared()
        compiler = os.environ.get('CXX', 'c++')
        if not shutil.which(compiler):
            raise RuntimeError('CXX must name an available C++ compiler executable')
        tools = {name: subprocess.check_output([name, '--version'], text=True)
                 for name in ['verilator', compiler]}
        hierarchy = hierarchy_plan(prepared['rtl_sha256'], self.hierarchy_profile)
        hierarchy_flags = ['--hierarchical'] if hierarchy['modules'] else []
        context = dict(rtl_sha256=prepared['rtl_sha256'],
            assertion_layers=assertion_layer_hashes(prepared['rtl_sha256']),
            hierarchy=hierarchy,
            profile_manifest_sha256=prepared['manifest_sha256'],
            driver_sha256={n: sha(ROOT / n) for n in self.driver_sources()}, tools=tools,
            flags=['--assert', *hierarchy_flags, '--output-groups', '0',
                   '--output-split', '10000', '--output-split-cfuncs', '200', '-std=c++17', '-O1', '-g0'],
            environment={k: os.environ.get(k, '') for k in ['CXX', 'CXXFLAGS', 'CPPFLAGS', 'LDFLAGS']})
        executable = self.out / self.executable_name
        prior = self.out / 'build.json'
        if prior.is_file() and executable.is_file():
            cached = json.loads(prior.read_text())
            if cached.get('context') == context and cached.get('executable_sha256') == sha(executable):
                self.state('complete-model-cache-hit'); return
        rtl, build = self.out / 'rtl', self.out / 'build'
        build.mkdir(exist_ok=True)
        # Definitions and real SRAM resources are listed; generated bind
        # layers need to be requested separately to activate the checks.
        inputs, directories = simulation_inputs(rtl)
        filelist = self.out / 'verilator-inputs.f'
        filelist.write_text(''.join(json.dumps(str(p), ensure_ascii=False) + '\n' for p in inputs))
        control = self.out / 'hierarchy.vlt'
        expected_control = hierarchy_control(context['hierarchy'])
        control.write_text(expected_control)
        command = ['verilator', '--cc', '--exe', '--assert', *hierarchy_flags, '--build-jobs', str(jobs),
            '--top-module', TOP, '--prefix', 'V' + TOP, '--Mdir', str(build),
            '--output-groups', '0', '--output-split', '10000', '--output-split-cfuncs', '200',
            '-Wno-fatal', '-Wno-UNOPTFLAT', '-CFLAGS', '-std=c++17 -O1 -g0',
            '-o', str(executable), '-F', str(filelist), str(control), str(ROOT / self.driver_sources()[0])]
        for directory in directories:
            command.append('-I' + str(directory))
        self.execute('verilator-generate', command, self.out / 'generate.log')
        self.execute('compile-model', ['make', '-C', str(build), '-f', 'V' + TOP + '.mk',
                     '-j', str(jobs), 'CXX=' + compiler], self.out / 'compile.log')
        self.prepared()
        if context['driver_sha256'] != {n: sha(ROOT / n) for n in self.driver_sources()}:
            raise RuntimeError('Driver source changed during model compilation')
        if control.read_text() != expected_control:
            raise RuntimeError('Hierarchy control changed during model compilation')
        if not executable.is_file():
            raise RuntimeError('Compiler produced no complete native model')
        write_json(prior, dict(context=context, executable_sha256=sha(executable), exit_code=0))

    def run_parallel_scenes(self, executable, directory):
        pending, running, rows = list(SCENES), {}, {}
        previous_status = None
        try:
            while pending or running:
                while pending and len(running) < self.scene_execution['max_workers']:
                    name = pending.pop(0)
                    target = directory / 'scenes' / name
                    target.mkdir(parents=True)
                    log = target / 'execution.log'
                    command = [str(executable), str(self.out / 'fixtures'), str(target), '--scene', name]
                    with log.open('w') as stream:
                        child = subprocess.Popen(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT)
                    running[name] = (child, target)
                status = (tuple(running), tuple(rows))
                if status != previous_status:
                    self.state('execute-six-scenes-parallel', directory=str(directory),
                        scene_execution=self.scene_execution, completed=list(rows),
                        children=[dict(scene=n, child_pid=p.pid, directory=str(t))
                                  for n, (p, t) in running.items()])
                    previous_status = status
                for name, (child, target) in list(running.items()):
                    code = child.poll()
                    if code is None:
                        continue
                    log = target / 'execution.log'
                    if code:
                        raise RuntimeError(f'Native scene {name} failed with exit {code}; inspect {log}')
                    values = [json.loads(line) for line in log.read_text().splitlines() if line.strip()]
                    if len(values) != 1 or values[0].get('scene') != name:
                        raise RuntimeError('Native scene returned missing or unexpected metrics: ' + name)
                    # Same-filesystem links keep the independent oracle's original
                    # flat layout without duplicating full-frame memory files.
                    os.link(target / (name + '.memory'), directory / (name + '.memory'))
                    rows[name] = values[0]
                    del running[name]
                if running:
                    time.sleep(0.1)
        except BaseException:
            # An actual child failure or interrupted batch invalidates this run.
            # Stop only children owned by this batch; retain all failure evidence.
            for child, _ in running.values():
                if child.poll() is None:
                    child.terminate()
            for child, _ in running.values():
                try:
                    child.wait(timeout=5)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait()
            raise
        ordered = [rows[name] for name in SCENES]
        (directory / 'execution.log').write_text(''.join(json.dumps(row) + '\n' for row in ordered))
        self.state('execute-six-scenes-parallel-terminal', exit_code=0,
                   scene_execution=self.scene_execution, completed=SCENES)
        return ordered

    def run(self):
        prepared = self.prepared()
        self.checked_build(prepared)
        executable = self.out / 'native_product_driver'
        directory = self.out / 'runs' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + str(os.getpid()))
        directory.mkdir(parents=True)
        if self.scene_execution['mode'] == SHARED_MODEL:
            self.execute('execute-six-scenes', [str(executable), str(self.out / 'fixtures'), str(directory)], directory / 'execution.log')
            rows = [json.loads(line) for line in (directory / 'execution.log').read_text().splitlines() if line.strip()]
        else:
            rows = self.run_parallel_scenes(executable, directory)
        validate_metrics(rows, self.product)
        self.prepared()
        self.checked_build(prepared)
        write_json(self.out / 'execution.json', dict(exit_code=0, directory=str(directory), scenes=rows,
            simulator_backend=self.backend(),
            scene_execution=self.scene_execution,
            output_sha256=tree_hashes(directory), executable_sha256=sha(executable),
            preparation_sha256=sha(self.out / 'preparation.json'), build_sha256=sha(self.out / 'build.json')))

    def verify(self, reference):
        prepared = self.prepared()
        self.checked_build(prepared)
        execution = json.loads((self.out / 'execution.json').read_text())
        directory = Path(execution['directory'])
        if execution.get('scene_execution', dict(mode=SHARED_MODEL, max_workers=1)) != self.scene_execution:
            raise RuntimeError('Scene execution mode or worker limit differs; use the recorded --scene-jobs')
        if (execution.get('simulator_backend', 'verilator') != self.backend()
                or execution['exit_code'] or execution['output_sha256'] != tree_hashes(directory)
                or execution['preparation_sha256'] != sha(self.out / 'preparation.json')
                or execution['build_sha256'] != sha(self.out / 'build.json')
                or execution['executable_sha256'] != sha(self.out / 'native_product_driver')):
            raise RuntimeError('Native output bytes changed before independent verification')
        validate_metrics(execution['scenes'], self.product)
        reference_sha256 = sha(reference) if reference else None
        if reference:
            compare_reference(execution['scenes'], json.loads(reference.read_text()), self.scene_execution)
        mode = '--product' if self.product else '--smoke'
        self.execute('verify-all-stored-images', self.sbt('opengpu.system.VerifyCompiledNativeProduct '
                     + mode + ' --output ' + json.dumps(str(directory)),
                     PRODUCT_JAVA_HEAP if self.product else '3G'), self.out / 'verify.log')
        verify_marker(self.out / 'verify.log', self.product)
        self.prepared()
        self.checked_build(prepared)
        if execution['output_sha256'] != tree_hashes(directory):
            raise RuntimeError('Native output bytes changed during independent verification')
        if reference and reference_sha256 != sha(reference):
            raise RuntimeError('Native reference changed during independent verification')
        write_json(self.out / 'qualification.json', dict(passed=True, product=self.product,
            api_capacity=self.api_capacity,
            simulator_backend=self.backend(),
            scene_execution=self.scene_execution,
            scenes=execution['scenes'], source_sha256=prepared['source_sha256'],
            bound_assertion_layer_sha256=assertion_layer_hashes(prepared['rtl_sha256']),
            preparation_sha256=sha(self.out / 'preparation.json'), build_sha256=sha(self.out / 'build.json'),
            execution_sha256=sha(self.out / 'execution.json'), verify_log_sha256=sha(self.out / 'verify.log'),
            exact_stored_images_and_guards=True, traffic_drained=True,
            matched_reference_sha256=reference_sha256,
            whole_host_clock_qualified=False, FPS_qualified=False,
            scope='All six complete native recipes on the shared full-profile RTL and original input/image oracle. '
                  'Development mode is32x32; product mode is explicit720p outsideCI. No concurrentpublication, '
                  'qualifiedwholehostclock/FPS or fullfeature/H0-H8 claim.'))
        self.state('complete-six-scene-qualification', passed=True, FPS_qualified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true'); mode.add_argument('--product', action='store_true')
    parser.add_argument('--api-capacity', action='store_true',
                        help='use the four-engine 2048-axis, 65536-entry native API profile')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=['all', 'prepare', 'build', 'run', 'verify'], default='all')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--scene-jobs', type=int, default=1,
                        help='1 keeps sequential shared-model execution; 2-6 use one fresh process per scene')
    parser.add_argument('--reference', type=Path)
    parser.add_argument('--hierarchy-profile', choices=HIERARCHY_PROFILES, default='flat')
    args = parser.parse_args()
    if args.jobs <= 0:
        parser.error('--jobs must be positive')
    if not 1 <= args.scene_jobs <= len(SCENES):
        parser.error('--scene-jobs must be between 1 and 6')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = Runner(out, args.product, args.hierarchy_profile, args.scene_jobs, args.api_capacity)
        try:
            for stage in ['prepare', 'build', 'run', 'verify']:
                if args.stage in ['all', stage]:
                    if stage == 'build': runner.build(args.jobs)
                    elif stage == 'verify': runner.verify(args.reference.resolve() if args.reference else None)
                    else: getattr(runner, stage)()
        except BaseException as error:
            runner.state('stopped-for-audit', error=str(error), automatic_retry=False)
            raise


if __name__ == '__main__':
    main()
