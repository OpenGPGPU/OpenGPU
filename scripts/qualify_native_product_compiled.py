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
          'scripts/native_frame_fixture.hpp', 'scripts/qualify_native_product_compiled.py']
HIERARCHY_PROFILES = ('baseline', 'fp32')


def hierarchy_plan(files, profile):
    if profile not in HIERARCHY_PROFILES:
        raise RuntimeError('Unknown native simulation hierarchy profile: ' + profile)
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
    return dict(profile=profile, modules=modules)


def hierarchy_control(plan):
    return '`verilator_config\n' + ''.join('hier_block -module "' + n + '"\n' for n in plan['modules'])


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


def check_profile(manifest, product):
    expected = (1280, 720) if product else (32, 32)
    if (manifest.get('version') != 1 or manifest.get('product') != product
            or (manifest.get('frameWidth'), manifest.get('frameHeight')) != expected
            or manifest.get('scenes') != SCENES or not manifest.get('rtlEmitted')
            or any(manifest.get(k) != v for k, v in
                   {'computeUnits': 4, 'lanes': 16, 'warps': 8, 'axiDataBytes': 32}.items())):
        raise RuntimeError('Incomplete or mismatched native product profile')


def guard_prepared(out, product):
    record = json.loads((out / 'preparation.json').read_text())
    if record['exit_code'] != 0 or record['source_sha256'] != rtl_inputs():
        raise RuntimeError('Prepared RTL/fixtures do not match the current source')
    manifest = json.loads((out / 'fixture-manifest.json').read_text())
    check_profile(manifest, product)
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


def guard_build(out, prepared, hierarchy_profile='baseline'):
    build = json.loads((out / 'build.json').read_text())
    if (build['exit_code'] != 0 or build['executable_sha256'] != sha(out / 'native_product_driver')
            or build['context']['rtl_sha256'] != prepared['rtl_sha256']
            or build['context']['profile_manifest_sha256'] != prepared['manifest_sha256']
            or build['context']['driver_sha256'] != {n: sha(ROOT / n) for n in DRIVER}
            or build['context'].get('hierarchy') != hierarchy_plan(prepared['rtl_sha256'], hierarchy_profile)):
        raise RuntimeError('Native model build does not match the current RTL/profile/driver')
    return build


class Runner:
    def __init__(self, out, product, hierarchy_profile='baseline'):
        self.out, self.product = out, product
        self.hierarchy_profile = hierarchy_profile

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
        return ['sbt', '-J-Xmx' + heap, '-J-XX:+ExitOnOutOfMemoryError',
                '-Dopengpu.fullHostQualification=1',
                '-Dopengpu.productQualification=' + ('1' if self.product else '0'),
                'Test / runMain ' + main]

    def prepare(self):
        sources = rtl_inputs()
        mode = '--product' if self.product else '--smoke'
        self.execute('prepare', self.sbt('opengpu.system.PrepareCompiledNativeProduct '
                     + mode + ' --output ' + json.dumps(str(self.out)), '6G'), self.out / 'prepare.log')
        if sources != rtl_inputs():
            raise RuntimeError('Source changed during RTL/fixture preparation')
        check_profile(json.loads((self.out / 'fixture-manifest.json').read_text()), self.product)
        write_json(self.out / 'preparation.json', dict(version=1, exit_code=0,
            source_sha256=sources, manifest_sha256=sha(self.out / 'fixture-manifest.json'),
            rtl_sha256=tree_hashes(self.out / 'rtl'), fixture_sha256=tree_hashes(self.out / 'fixtures')))

    def build(self, jobs):
        prepared = guard_prepared(self.out, self.product)
        compiler = os.environ.get('CXX', 'c++')
        if not shutil.which(compiler):
            raise RuntimeError('CXX must name an available C++ compiler executable')
        tools = {name: subprocess.check_output([name, '--version'], text=True)
                 for name in ['verilator', compiler]}
        context = dict(rtl_sha256=prepared['rtl_sha256'],
            hierarchy=hierarchy_plan(prepared['rtl_sha256'], self.hierarchy_profile),
            profile_manifest_sha256=prepared['manifest_sha256'],
            driver_sha256={n: sha(ROOT / n) for n in DRIVER}, tools=tools,
            flags=['--assert', '--hierarchical', '--output-groups', '0',
                   '--output-split', '10000', '--output-split-cfuncs', '200', '-std=c++17', '-O1', '-g0'],
            environment={k: os.environ.get(k, '') for k in ['CXX', 'CXXFLAGS', 'CPPFLAGS', 'LDFLAGS']})
        executable = self.out / 'native_product_driver'
        prior = self.out / 'build.json'
        if prior.is_file() and executable.is_file():
            cached = json.loads(prior.read_text())
            if cached.get('context') == context and cached.get('executable_sha256') == sha(executable):
                self.state('complete-model-cache-hit'); return
        rtl, build = self.out / 'rtl', self.out / 'build'
        build.mkdir(exist_ok=True)
        # CIRCT's file list includes verification modules and the exact SRAM
        # resources written by the same helper as the Scala simulator.
        inputs = []
        for line in (rtl / 'filelist.f').read_text().splitlines():
            path = (rtl / line.strip()).resolve()
            if not path.is_file():
                raise RuntimeError('Missing emitted simulation source: ' + str(path))
            inputs.append(path)
        filelist = self.out / 'verilator-inputs.f'
        filelist.write_text(''.join(json.dumps(str(p), ensure_ascii=False) + '\n' for p in inputs))
        control = self.out / 'hierarchy.vlt'
        expected_control = hierarchy_control(context['hierarchy'])
        control.write_text(expected_control)
        command = ['verilator', '--cc', '--exe', '--assert', '--hierarchical', '--build-jobs', str(jobs),
            '--top-module', TOP, '--prefix', 'V' + TOP, '--Mdir', str(build),
            '--output-groups', '0', '--output-split', '10000', '--output-split-cfuncs', '200',
            '-Wno-fatal', '-Wno-UNOPTFLAT', '-CFLAGS', '-std=c++17 -O1 -g0',
            '-o', str(executable), '-F', str(filelist), str(control), str(ROOT / DRIVER[0])]
        for directory in sorted({rtl, *[p.parent for p in inputs]}):
            command.append('-I' + str(directory))
        self.execute('verilator-generate', command, self.out / 'generate.log')
        self.execute('compile-model', ['make', '-C', str(build), '-f', 'V' + TOP + '.mk',
                     '-j', str(jobs), 'CXX=' + compiler], self.out / 'compile.log')
        guard_prepared(self.out, self.product)
        if context['driver_sha256'] != {n: sha(ROOT / n) for n in DRIVER}:
            raise RuntimeError('Driver source changed during model compilation')
        if control.read_text() != expected_control:
            raise RuntimeError('Hierarchy control changed during model compilation')
        if not executable.is_file():
            raise RuntimeError('Compiler produced no complete native model')
        write_json(prior, dict(context=context, executable_sha256=sha(executable), exit_code=0))

    def run(self):
        prepared = guard_prepared(self.out, self.product)
        guard_build(self.out, prepared, self.hierarchy_profile)
        executable = self.out / 'native_product_driver'
        directory = self.out / 'runs' / (datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S') + '-' + str(os.getpid()))
        directory.mkdir(parents=True)
        self.execute('execute-six-scenes', [str(executable), str(self.out / 'fixtures'), str(directory)], directory / 'execution.log')
        rows = [json.loads(line) for line in (directory / 'execution.log').read_text().splitlines() if line.strip()]
        validate_metrics(rows, self.product)
        guard_prepared(self.out, self.product)
        guard_build(self.out, prepared, self.hierarchy_profile)
        write_json(self.out / 'execution.json', dict(exit_code=0, directory=str(directory), scenes=rows,
            output_sha256=tree_hashes(directory), executable_sha256=sha(executable),
            preparation_sha256=sha(self.out / 'preparation.json'), build_sha256=sha(self.out / 'build.json')))

    def verify(self, reference):
        prepared = guard_prepared(self.out, self.product)
        guard_build(self.out, prepared, self.hierarchy_profile)
        execution = json.loads((self.out / 'execution.json').read_text())
        directory = Path(execution['directory'])
        if (execution['exit_code'] or execution['output_sha256'] != tree_hashes(directory)
                or execution['preparation_sha256'] != sha(self.out / 'preparation.json')
                or execution['build_sha256'] != sha(self.out / 'build.json')
                or execution['executable_sha256'] != sha(self.out / 'native_product_driver')):
            raise RuntimeError('Native output bytes changed before independent verification')
        validate_metrics(execution['scenes'], self.product)
        mode = '--product' if self.product else '--smoke'
        self.execute('verify-all-stored-images', self.sbt('opengpu.system.VerifyCompiledNativeProduct '
                     + mode + ' --output ' + json.dumps(str(directory)), '6G' if self.product else '3G'), self.out / 'verify.log')
        verify_marker(self.out / 'verify.log', self.product)
        if reference:
            baseline = {s['id']: s for s in json.loads(reference.read_text())['scenes']}
            fields = ['serviceCycles', 'gpuCycles', 'readBusBytes', 'writeBusBytes', 'enabledWriteBytes', 'phaseWorkerCycles']
            for row in execution['scenes']:
                if any(row[k] != baseline[row['scene']][k] for k in fields):
                    raise RuntimeError('Compiled/Scala cycle or traffic counters differ: ' + row['scene'])
        guard_prepared(self.out, self.product)
        guard_build(self.out, prepared, self.hierarchy_profile)
        if execution['output_sha256'] != tree_hashes(directory):
            raise RuntimeError('Native output bytes changed during independent verification')
        write_json(self.out / 'qualification.json', dict(passed=True, product=self.product,
            scenes=execution['scenes'], source_sha256=prepared['source_sha256'],
            preparation_sha256=sha(self.out / 'preparation.json'), build_sha256=sha(self.out / 'build.json'),
            execution_sha256=sha(self.out / 'execution.json'), verify_log_sha256=sha(self.out / 'verify.log'),
            exact_stored_images_and_guards=True, traffic_drained=True,
            matched_Scala_reference_sha256=sha(reference) if reference else None,
            whole_host_clock_qualified=False, FPS_qualified=False,
            scope='All six complete native recipes on the shared full-profile RTL and original input/image oracle. '
                  'Development mode is32x32; product mode is explicit720p outsideCI. No concurrentpublication, '
                  'qualifiedwholehostclock/FPS or fullfeature/H0-H8 claim.'))
        self.state('complete-six-scene-qualification', passed=True, FPS_qualified=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true'); mode.add_argument('--product', action='store_true')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=['all', 'prepare', 'build', 'run', 'verify'], default='all')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--reference', type=Path)
    parser.add_argument('--hierarchy-profile', choices=HIERARCHY_PROFILES, default='baseline')
    args = parser.parse_args()
    if args.jobs <= 0:
        parser.error('--jobs must be positive')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = Runner(out, args.product, args.hierarchy_profile)
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
