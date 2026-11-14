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


def loaded_libraries(tool):
    """Resolve startup dependencies, including libraries reached through rpaths."""
    if sys.platform == 'darwin':
        env = dict(os.environ, DYLD_PRINT_LIBRARIES='1')
        result = subprocess.run([str(tool), '--version'], env=env, text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
        paths = {}
        for line in result.stderr.splitlines():
            if match := re.match(r'^dyld\[\d+\]:\s+<([^>]+)>\s+(/.*)$', line):
                paths[match.group(2)] = match.group(1)
        if not paths:
            raise RuntimeError('Could not identify the loaded CIRCT libraries')
        return paths
    if sys.platform.startswith('linux'):
        result = subprocess.run(['ldd', str(tool)], text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, check=True)
        paths = {}
        for line in result.stdout.splitlines():
            if 'not found' in line:
                raise RuntimeError('CIRCT has an unresolved dynamic library')
            match = re.search(r'(?:=>\s+|^\s*)(/\S+)', line)
            if match:
                paths[match.group(1)] = None
        if not paths:
            raise RuntimeError('Could not identify the loaded CIRCT libraries')
        return paths
    raise RuntimeError('CIRCT dynamic-library discovery is unsupported on this platform')


def dynamic_libraries(tool, environment, loaded):
    """Pin the CIRCT checkout and explicitly configured dynamic loader inputs."""
    for name in loaded:
        if not Path(name).is_file() and not (
                sys.platform == 'darwin' and name.startswith(('/usr/lib/', '/System/Library/'))):
            raise RuntimeError('Loaded CIRCT library cannot be fingerprinted: ' + name)
    directories = {tool.parent.parent / 'lib'}
    if environment.get('FLASHSIM_CIRCT'):
        checkout = Path(environment['FLASHSIM_CIRCT'])
        directories.add(checkout / 'lib')
        if checkout.is_dir():
            directories.update(p / 'lib' for p in checkout.iterdir() if p.is_dir())
    for name in ('DYLD_LIBRARY_PATH', 'DYLD_FALLBACK_LIBRARY_PATH', 'LD_LIBRARY_PATH'):
        if name in environment:
            for component in environment[name].split(os.pathsep):
                if not component:
                    raise RuntimeError(name + ' contains an unpinned empty search path')
                directory = Path(component).resolve(strict=True)
                if not directory.is_dir():
                    raise RuntimeError(name + ' contains a non-directory')
                directories.add(directory)
    files = {Path(p).absolute() for p in loaded if Path(p).is_file()}
    for directory in directories:
        if directory.is_dir():
            files.update(p.absolute() for p in directory.rglob('*') if p.is_file() and
                         (p.name.endswith('.dylib') or '.so' in p.name))
    for name in ('DYLD_INSERT_LIBRARIES', 'LD_PRELOAD'):
        if name in environment:
            for item in re.split(r'[:\s]+', environment[name]):
                if not item:
                    raise RuntimeError(name + ' contains an unpinned empty library')
                library = Path(item).absolute()
                if not library.is_file():
                    raise RuntimeError(name + ' contains a non-file')
                files.add(library)
    return {str(p): {'target': str(p.resolve()), 'sha256': common.sha(p)}
            for p in sorted(files)}


def generator_inputs(flashsim):
    """Fingerprint the executable generator, its Python sources and CIRCT libraries."""
    package = flashsim / 'flashsim'
    if not (package / '__main__.py').is_file() or not (flashsim / 'pyproject.toml').is_file():
        raise RuntimeError('A FlashSim source checkout is required')
    allowed_dyld = {'DYLD_LIBRARY_PATH', 'DYLD_FALLBACK_LIBRARY_PATH', 'DYLD_INSERT_LIBRARIES'}
    unsupported = sorted(k for k in os.environ if
                         (k.startswith('DYLD_') and k not in allowed_dyld) or k == 'LD_AUDIT')
    if unsupported:
        raise RuntimeError('Unpinned dynamic loader settings: ' + ', '.join(unsupported))
    sources = sorted(package.rglob('*.py')) + [flashsim / 'pyproject.toml']
    environment = {k: v for k, v in os.environ.items()
                   if k.startswith(('FLASHSIM_', 'CIRCT_')) or k in (
                       'CIRCT_VERILOG', 'PYTHONPATH', 'DYLD_LIBRARY_PATH',
                       'DYLD_FALLBACK_LIBRARY_PATH', 'DYLD_INSERT_LIBRARIES',
                       'LD_LIBRARY_PATH', 'LD_PRELOAD')}
    probe = ('import json; from flashsim.toolchain import find_bin; '
             'p=find_bin("circt-verilog"); print(json.dumps(str(p.resolve()) if p else None))')
    found = json.loads(subprocess.check_output([sys.executable, '-c', probe], cwd=flashsim, text=True))
    if not found:
        raise RuntimeError('FlashSim needs the CIRCT circt-verilog frontend')
    tool = Path(found)
    with tool.open('rb') as executable:
        if executable.read(2) == b'#!':
            raise RuntimeError('CIRCT_VERILOG must name the compiler executable, not a wrapper')
    loaded = loaded_libraries(tool)
    return dict(source={str(p.relative_to(flashsim)): common.sha(p) for p in sources},
                python=str(Path(sys.executable).resolve()), python_sha256=common.sha(sys.executable),
                circt_verilog=str(tool), circt_sha256=common.sha(tool),
                circt_libraries=dynamic_libraries(tool, environment, loaded),
                loaded_library_ids=loaded,
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


def generated_sources(directory, expected_shards=None):
    header = directory / 'dut.h'
    shards = sorted(directory.glob('dut_*.cpp'))
    if not header.is_file() or not shards:
        raise RuntimeError('FlashSim did not produce a split native model')
    numbered = {p.name for p in shards if re.fullmatch(r'dut_\d+\.cpp', p.name)}
    if expected_shards is not None and numbered != {
            f'dut_{number}.cpp' for number in range(expected_shards)}:
        raise RuntimeError('FlashSim generated an incomplete or unexpected shard set')
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
    def __init__(self, out, product, flashsim, scene_jobs=1, shards=32,
                 reshard=None, reshard_tool=None, precompiled_header=False, shard_opt='-O2',
                 conservative_alias=False, driver_root=None, api_capacity=False, two_cu_vga=False,
                 alignment_v2=False):
        super().__init__(out, product, 'flat', scene_jobs, api_capacity, two_cu_vga, alignment_v2)
        self.flashsim = flashsim.resolve()
        if shards < 1:
            raise ValueError('--shards must be positive')
        self.shards = shards
        if (reshard is None) != (reshard_tool is None):
            raise ValueError('--reshard-dir and --reshard-tool must be supplied together')
        self.reshard = reshard.resolve() if reshard else None
        self.reshard_tool = reshard_tool.resolve() if reshard_tool else None
        self.precompiled_header = precompiled_header
        self.conservative_alias = conservative_alias
        self.driver_root = Path(driver_root).resolve() if driver_root is not None else ROOT
        if shard_opt not in ('-O0', '-O1', '-O2', '-O3'):
            raise ValueError('invalid shard optimization level')
        self.shard_opt = shard_opt

    def driver_sources(self):
        return DRIVER

    def driver_path(self):
        return ROOT / 'scripts/native_product_driver.cpp'

    def extra_driver_dependencies(self):
        return []

    def backend(self):
        return 'flashsim'

    def checked_reshard(self, generated):
        if self.reshard is None:
            return None
        tool = self.reshard_tool / 'flashsim/reshard.py'
        emitter = self.reshard_tool / 'flashsim/emit.py'
        if not tool.is_file() or not emitter.is_file():
            raise RuntimeError('reshard tool checkout is incomplete')
        command = [sys.executable, '-m', 'flashsim.reshard', 'verify',
                   '--source', str(self.out / 'flashsim-dut'), '--output', str(self.reshard)]
        env = dict(os.environ, PYTHONPATH=str(self.reshard_tool))
        subprocess.run(command, cwd=self.reshard_tool, env=env, check=True)
        manifest_path = self.reshard / 'flashsim-reshard.json'
        manifest = json.loads(manifest_path.read_text())
        derived = generated_sources(self.reshard, self.shards)
        if (manifest['input_sha256'] != generated['files'] or
                manifest['output_sha256'] != derived['files'] or
                derived['assertions'] != generated['assertions'] or
                manifest['tool_sha256'] != common.sha(tool) or
                manifest['emitter_sha256'] != common.sha(emitter)):
            raise RuntimeError('resharded sources do not match the generated model or tool')
        return dict(manifest_sha256=common.sha(manifest_path), tool_sha256=common.sha(tool),
                    emitter_sha256=common.sha(emitter), derived=derived)

    def checked_build(self, prepared):
        record = json.loads((self.out / 'build.json').read_text())
        context = record['context']
        if (record.get('exit_code') != 0 or context.get('backend') != 'flashsim'
                or context['shards'] != self.shards
                or context.get('precompiled_header', False) != self.precompiled_header
                or context.get('shard_opt', '-O2') != self.shard_opt
                or context.get('conservative_alias', False) != self.conservative_alias
                or context['rtl_sha256'] != prepared['rtl_sha256']
                or context['profile_manifest_sha256'] != prepared['manifest_sha256']
                or context['assertion_layers'] != common.assertion_layer_hashes(prepared['rtl_sha256'])
                or context['driver_sha256'] != {n: common.sha(self.driver_root / n) for n in self.driver_sources()}
                or context['generator'] != generator_inputs(self.flashsim)
                or context['generated'] != generated_sources(self.out / 'flashsim-dut', self.shards)
                or record['generation_sha256'] != common.sha(self.out / 'flashsim-dut/generation.json')
                or record['builder_sha256'] != common.sha(self.out / 'flashsim-build/native-flashsim-build.json')
                or record['executable_sha256'] != common.sha(self.out / self.executable_name)):
            raise RuntimeError('FlashSim model does not match the current RTL, checks, generator or driver')
        if context.get('reshard') != self.checked_reshard(context['generated']):
            raise RuntimeError('FlashSim reshard provenance changed after the build')
        return record

    def generate(self):
        # An interrupted or failed rebuild must not leave a reusable success record.
        (self.out / 'build.json').unlink(missing_ok=True)
        (self.out / 'qualification.json').unlink(missing_ok=True)
        prepared = self.prepared()
        layers = common.assertion_layer_hashes(prepared['rtl_sha256'])
        if not layers:
            raise RuntimeError('The prepared host has no assertion bind layers')
        rtl, dut = self.out / 'rtl', self.out / 'flashsim-dut'
        generator = generator_inputs(self.flashsim)
        verify_flashsim_sources(self.flashsim, rtl)
        # FlashSim's own MLIR cache tracks RTL bytes but not the compiler or
        # Python generator. Reuse it only when our saved identity and the
        # MLIR itself both match; otherwise force a fresh CIRCT import.
        dut.mkdir(exist_ok=True)
        generation_path, mlir = dut / 'generation.json', dut / 'hw.mlir'
        generation_context = dict(generator=generator, rtl_sha256=prepared['rtl_sha256'])
        try:
            cached = json.loads(generation_path.read_text())
            reuse_mlir = (cached['context'] == generation_context and
                          cached['mlir_sha256'] == common.sha(mlir))
        except (OSError, ValueError, KeyError):
            reuse_mlir = False
        try:
            reuse_generated = reuse_mlir and cached['generated'] == generated_sources(dut, self.shards)
        except (OSError, ValueError, KeyError, RuntimeError):
            reuse_generated = False
        if reuse_generated:
            generated = cached['generated']
        else:
            if not reuse_mlir:
                for suffix in ('', '.top', '.assertions', '.assertion-sources.json', '.circt-serial'):
                    (dut / ('hw.mlir' + suffix)).unlink(missing_ok=True)
            generation_path.unlink(missing_ok=True)
            compile_model = ('import sys; from pathlib import Path; from flashsim.compile import compile_file; '
                'compile_file(Path(sys.argv[1]),Path(sys.argv[2]),frontend="circt",'
                'assertions=True,split=int(sys.argv[3]))')
            self.execute('flashsim-generate', [sys.executable, '-c', compile_model,
                str(rtl / (common.TOP + '.sv')), str(dut / 'dut.h'), str(self.shards)],
                self.out / 'flashsim-generate.log', cwd=self.flashsim)
            generated = generated_sources(dut, self.shards)
            self.prepared()
            if generator != generator_inputs(self.flashsim):
                raise RuntimeError('FlashSim generator or toolchain changed during model generation')
            common.write_json(generation_path, dict(context=generation_context,
                mlir_sha256=common.sha(mlir), generated=generated))
        return prepared, layers, generator, generated

    def build(self, jobs):
        if self.driver_root != ROOT:
            raise RuntimeError('A historical driver checkout is only valid for verifying an existing build')
        prepared, layers, generator, generated = self.generate()
        driver = {n: common.sha(ROOT / n) for n in self.driver_sources()}
        dut, build = self.out / 'flashsim-dut', self.out / 'flashsim-build'
        generation_path = dut / 'generation.json'
        reshard = self.checked_reshard(generated)
        build_dut = self.reshard if reshard else dut
        used = reshard['derived'] if reshard else generated
        build_command = [sys.executable, str(ROOT / 'scripts/build_native_flashsim.py'),
            '--dut-dir', str(build_dut), '--build-dir', str(build), '--jobs', str(jobs),
            '--driver', str(self.driver_path())]
        for dependency in self.extra_driver_dependencies():
            build_command.extend(['--driver-dependency', str(dependency)])
        if self.precompiled_header:
            build_command.append('--precompiled-header')
        if self.conservative_alias:
            build_command.append('--conservative-alias')
        if self.shard_opt != '-O2':
            build_command.append('--shard-opt=' + self.shard_opt)
        self.execute('flashsim-build', build_command,
            self.out / 'flashsim-build.log')
        builder = json.loads((build / 'native-flashsim-build.json').read_text())
        executable = build / self.executable_name
        expected_shards = {name: digest for name, digest in used['files'].items()
                           if name != 'dut.h'}
        if (builder['executable_sha256'] != common.sha(executable)
                or builder['dut_header_sha256'] != used['files']['dut.h']
                or builder['dut_sources'] != expected_shards
                or builder.get('shard_opt', '-O2') != self.shard_opt
                or builder.get('precompiled_header', False) != self.precompiled_header
                or builder.get('conservative_alias', False) != self.conservative_alias):
            raise RuntimeError('FlashSim builder does not match the generated model')
        self.prepared()
        if (generated != generated_sources(dut, self.shards) or generator != generator_inputs(self.flashsim)
                or driver != {n: common.sha(ROOT / n) for n in self.driver_sources()}
                or reshard != self.checked_reshard(generated)):
            raise RuntimeError('FlashSim model inputs changed during compilation')
        shutil.copyfile(executable, self.out / self.executable_name)
        os.chmod(self.out / self.executable_name, executable.stat().st_mode & 0o777)
        context = dict(backend='flashsim', shards=self.shards,
            rtl_sha256=prepared['rtl_sha256'], assertion_layers=layers,
            profile_manifest_sha256=prepared['manifest_sha256'],
            driver_sha256=driver,
            generator=generator, generated=generated,
            reshard=reshard, precompiled_header=self.precompiled_header, shard_opt=self.shard_opt,
            conservative_alias=self.conservative_alias)
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
    common.add_profile_arguments(parser)
    parser.add_argument('--api-capacity', action='store_true',
                        help='use the four-engine 2048-axis, 65536-entry native API profile')
    parser.add_argument('--flashsim-dir', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--stage', choices=['all', 'prepare', 'generate', 'build', 'run', 'verify'], default='all')
    parser.add_argument('--jobs', type=int, default=1)
    parser.add_argument('--scene-jobs', type=int, default=1)
    parser.add_argument('--shards', type=int, default=32,
                        help='number of FlashSim C++ method shards (default: 32)')
    parser.add_argument('--reshard-dir', type=Path,
                        help='verified derived DUT sources from flashsim.reshard')
    parser.add_argument('--reshard-tool', type=Path,
                        help='FlashSim checkout that created the reshard manifest')
    parser.add_argument('--precompiled-header', action='store_true')
    parser.add_argument('--conservative-alias', action='store_true',
                        help='compile optimized DUT shards with conservative alias analysis')
    parser.add_argument('--shard-opt', choices=['-O0', '-O1', '-O2', '-O3'], default='-O2',
                        help='DUT optimization level; lower levels are for functional smoke runs')
    parser.add_argument('--reference', type=Path)
    args = parser.parse_args()
    if args.jobs <= 0 or args.shards <= 0 or not 1 <= args.scene_jobs <= len(common.SCENES):
        parser.error('--jobs and --shards must be positive; --scene-jobs must be between 1 and 6')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=True)
    with (out / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        runner = Runner(out, args.product, args.flashsim_dir, args.scene_jobs, args.shards,
                        args.reshard_dir, args.reshard_tool, args.precompiled_header, args.shard_opt,
                        args.conservative_alias, api_capacity=args.api_capacity,
                        two_cu_vga=args.two_cu_vga, alignment_v2=args.alignment_v2)
        try:
            if args.stage == 'generate':
                runner.generate()
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
