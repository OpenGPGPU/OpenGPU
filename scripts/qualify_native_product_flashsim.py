#!/usr/bin/env python3
"""Qualify the complete native six-scene host with an assertion-enabled FlashSim model."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

import qualify_native_product_compiled as common

ROOT = Path(__file__).resolve().parents[1]
DRIVER = common.DRIVER + [
    'scripts/qualify_native_product_flashsim.py', 'scripts/build_native_flashsim.py',
    'scripts/flashsim_native/VGpuHostSystemAxi.h', 'scripts/flashsim_native/verilated.h']


def generator_inputs(flashsim):
    """Fingerprint the executable generator, its Python sources and CIRCT libraries."""
    package = flashsim / 'flashsim'
    if not (package / '__main__.py').is_file() or not (flashsim / 'pyproject.toml').is_file():
        raise RuntimeError('A FlashSim source checkout is required')
    sources = sorted(package.rglob('*.py')) + [flashsim / 'pyproject.toml']
    environment = {k: v for k, v in os.environ.items()
                   if k.startswith('FLASHSIM_') or k in ('CIRCT_VERILOG', 'PYTHONPATH')}
    probe = ('import json; from flashsim.toolchain import find_bin; '
             'p=find_bin("circt-verilog"); print(json.dumps(str(p.resolve()) if p else None))')
    found = json.loads(subprocess.check_output([sys.executable, '-c', probe], cwd=flashsim, text=True))
    if not found:
        raise RuntimeError('FlashSim needs the CIRCT circt-verilog frontend')
    tool = Path(found)
    library_dir = tool.parent.parent / 'lib'
    libraries = sorted(p for p in library_dir.rglob('*') if p.is_file() and
                       (p.name.endswith('.dylib') or '.so' in p.name)) if library_dir.is_dir() else []
    return dict(source={str(p.relative_to(flashsim)): common.sha(p) for p in sources},
                python=str(Path(sys.executable).resolve()), python_sha256=common.sha(sys.executable),
                circt_verilog=str(tool), circt_sha256=common.sha(tool),
                circt_libraries={str(p.relative_to(library_dir)): common.sha(p) for p in libraries},
                environment=environment)


def verify_flashsim_sources(flashsim, rtl):
    """Require FlashSim to consume the same emitted files and bind layers as the reference."""
    expected, _ = common.simulation_inputs(rtl)
    probe = ('import json,sys; from pathlib import Path; '
             'from flashsim.circt_frontend import verilog_sources; '
             'print(json.dumps([str(p.resolve()) for p in verilog_sources(Path(sys.argv[1]), assertions=True)]))')
    actual = json.loads(subprocess.check_output(
        [sys.executable, '-c', probe, str(rtl / (common.TOP + '.sv'))], cwd=flashsim, text=True))
    if len(actual) != len(set(actual)) or set(actual) != {str(p) for p in expected}:
        raise RuntimeError('FlashSim source list does not match the emitted RTL and assertion layers')


def generated_sources(directory):
    header = directory / 'dut.h'
    shards = sorted(directory.glob('dut_*.cpp'))
    if not header.is_file() or not shards:
        raise RuntimeError('FlashSim did not produce a split native model')
    match = None
    with header.open() as stream:
        for line in stream:
            match = re.search(r'\bassertion_count\s*=\s*(\d+)\s*;', line)
            if match:
                break
    if not match or int(match.group(1)) <= 0:
        raise RuntimeError('FlashSim model has no emitted assertion checks')
    return dict(assertions=int(match.group(1)), files={p.name: common.sha(p) for p in [header, *shards]})


class Runner(common.Runner):
    def __init__(self, out, product, flashsim, scene_jobs=1, shards=32):
        super().__init__(out, product, 'flat', scene_jobs)
        self.flashsim = flashsim.resolve()
        if shards < 1:
            raise ValueError('--shards must be positive')
        self.shards = shards

    def driver_sources(self):
        return DRIVER

    def backend(self):
        return 'flashsim'

    def checked_build(self, prepared):
        record = json.loads((self.out / 'build.json').read_text())
        context = record['context']
        if (record.get('exit_code') != 0 or context.get('backend') != 'flashsim'
                or context['shards'] != self.shards
                or context['rtl_sha256'] != prepared['rtl_sha256']
                or context['profile_manifest_sha256'] != prepared['manifest_sha256']
                or context['assertion_layers'] != common.assertion_layer_hashes(prepared['rtl_sha256'])
                or context['driver_sha256'] != {n: common.sha(ROOT / n) for n in self.driver_sources()}
                or context['generator'] != generator_inputs(self.flashsim)
                or context['generated'] != generated_sources(self.out / 'flashsim-dut')
                or record['generation_sha256'] != common.sha(self.out / 'flashsim-dut/generation.json')
                or record['builder_sha256'] != common.sha(self.out / 'flashsim-build/native-flashsim-build.json')
                or record['executable_sha256'] != common.sha(self.out / self.executable_name)):
            raise RuntimeError('FlashSim model does not match the current RTL, checks, generator or driver')
        return record

    def build(self, jobs):
        # An interrupted or failed rebuild must not leave a reusable success record.
        (self.out / 'build.json').unlink(missing_ok=True)
        (self.out / 'qualification.json').unlink(missing_ok=True)
        prepared = self.prepared()
        layers = common.assertion_layer_hashes(prepared['rtl_sha256'])
        if not layers:
            raise RuntimeError('The prepared host has no assertion bind layers')
        rtl, dut, build = self.out / 'rtl', self.out / 'flashsim-dut', self.out / 'flashsim-build'
        generator = generator_inputs(self.flashsim)
        driver = {n: common.sha(ROOT / n) for n in self.driver_sources()}
        verify_flashsim_sources(self.flashsim, rtl)
        # FlashSim's own MLIR cache tracks RTL bytes but not the compiler or
        # Python generator. Reuse it only when our saved identity and the
        # MLIR itself both match; otherwise force a fresh CIRCT import.
        dut.mkdir(exist_ok=True)
        generation_path, mlir = dut / 'generation.json', dut / 'hw.mlir'
        generation_context = dict(generator=generator, rtl_sha256=prepared['rtl_sha256'])
        try:
            cached = json.loads(generation_path.read_text())
            reuse = (cached['context'] == generation_context and
                     cached['mlir_sha256'] == common.sha(mlir))
        except (OSError, ValueError, KeyError):
            reuse = False
        if not reuse:
            for suffix in ('', '.top', '.assertions', '.assertion-sources.json'):
                (dut / ('hw.mlir' + suffix)).unlink(missing_ok=True)
        generation_path.unlink(missing_ok=True)
        compile_model = ('import sys; from pathlib import Path; from flashsim.compile import compile_file; '
            'compile_file(Path(sys.argv[1]),Path(sys.argv[2]),frontend="circt",'
            'assertions=True,split=int(sys.argv[3]))')
        self.execute('flashsim-generate', [sys.executable, '-c', compile_model,
            str(rtl / (common.TOP + '.sv')), str(dut / 'dut.h'), str(self.shards)],
            self.out / 'flashsim-generate.log', cwd=self.flashsim)
        generated = generated_sources(dut)
        self.prepared()
        if generator != generator_inputs(self.flashsim):
            raise RuntimeError('FlashSim generator or toolchain changed during model generation')
        common.write_json(generation_path, dict(context=generation_context, mlir_sha256=common.sha(mlir)))
        self.execute('flashsim-build', [sys.executable, str(ROOT / 'scripts/build_native_flashsim.py'),
            '--dut-dir', str(dut), '--build-dir', str(build), '--jobs', str(jobs)],
            self.out / 'flashsim-build.log')
        builder = json.loads((build / 'native-flashsim-build.json').read_text())
        executable = build / self.executable_name
        expected_shards = {name: digest for name, digest in generated['files'].items()
                           if name != 'dut.h'}
        if (builder['executable_sha256'] != common.sha(executable)
                or builder['dut_header_sha256'] != generated['files']['dut.h']
                or builder['dut_sources'] != expected_shards):
            raise RuntimeError('FlashSim builder does not match the generated model')
        self.prepared()
        if (generated != generated_sources(dut) or generator != generator_inputs(self.flashsim)
                or driver != {n: common.sha(ROOT / n) for n in self.driver_sources()}):
            raise RuntimeError('FlashSim model inputs changed during compilation')
        shutil.copyfile(executable, self.out / self.executable_name)
        os.chmod(self.out / self.executable_name, executable.stat().st_mode & 0o777)
        context = dict(backend='flashsim', shards=self.shards,
            rtl_sha256=prepared['rtl_sha256'], assertion_layers=layers,
            profile_manifest_sha256=prepared['manifest_sha256'],
            driver_sha256=driver,
            generator=generator, generated=generated)
        common.write_json(self.out / 'build.json', dict(exit_code=0, context=context,
            generation_sha256=common.sha(generation_path),
            builder_sha256=common.sha(build / 'native-flashsim-build.json'),
            executable_sha256=common.sha(self.out / self.executable_name)))
        self.checked_build(prepared)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument('--smoke', action='store_true')
    mode.add_argument('--product', action='store_true')
    parser.add_argument('--flashsim-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=['all', 'prepare', 'build', 'run', 'verify'], default='all')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--scene-jobs', type=int, default=1)
    parser.add_argument('--shards', type=int, default=32,
                        help='number of FlashSim C++ method shards (default: 32)')
    parser.add_argument('--reference', type=Path)
    args = parser.parse_args()
    if args.jobs <= 0 or args.shards <= 0 or not 1 <= args.scene_jobs <= len(common.SCENES):
        parser.error('--jobs and --shards must be positive; --scene-jobs must be between 1 and 6')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = Runner(out, args.product, args.flashsim_dir, args.scene_jobs, args.shards)
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
