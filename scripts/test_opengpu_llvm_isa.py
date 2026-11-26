#!/usr/bin/env python3
"""Qualify the pinned OpenGPU LLVM assembler and Clang inline-assembly path."""
import argparse
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
GPU_ROOT = Path(subprocess.check_output(
    ['git','rev-parse','--path-format=absolute','--git-common-dir'],cwd=ROOT,text=True).strip()).parent
PIN = '2ba9912b981d8819ffecda059cc41872f986bcf3'


def output(command):
    return subprocess.run(command, check=True, capture_output=True, text=True).stdout


def rejected(command):
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode == 0:
        raise SystemExit(f'command unexpectedly passed: {command}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--llvm-source', type=Path)
    parser.add_argument('--llvm-build', type=Path)
    parser.add_argument('--expected-commit', default=PIN)
    parser.add_argument('--allow-missing', action='store_true')
    args = parser.parse_args()
    source_explicit = args.llvm_source is not None or 'OPENGPU_LLVM_SOURCE' in os.environ
    build_explicit = args.llvm_build is not None or 'OPENGPU_LLVM_BUILD' in os.environ
    source = (args.llvm_source or Path(os.environ.get('OPENGPU_LLVM_SOURCE',GPU_ROOT.parent/'llvm-project'))).resolve()
    build = (args.llvm_build or Path(os.environ.get('OPENGPU_LLVM_BUILD',GPU_ROOT.parent/'llvm-build'))).resolve()
    binary = build/'bin'
    tools = {name: binary/name for name in ('clang','llvm-mc','llvm-objdump','llvm-objcopy')}
    mc_test = source/'llvm/test/MC/RISCV/xopengpu-tes-patch.s'
    clang_test = source/'clang/test/CodeGen/RISCV/opengpu-tes-patch-asm.c'
    texture = ROOT/'userspace/shaders/compute_resources.S'
    cache = build/'CMakeCache.txt'
    required = (*tools.values(),mc_test,clang_test,texture,cache,build/'build.ninja')
    missing = [str(path) for path in required if not path.is_file()]
    if missing:
        optional_absent = not source_explicit and not build_explicit and not source.exists() and not build.exists()
        if args.allow_missing and optional_absent:
            print('OpenGPU LLVM ISA smoke skipped; optional toolchain absent')
            return
        raise SystemExit('OpenGPU LLVM ISA smoke inputs missing: '+', '.join(missing))
    source_head = output(['git','-C',str(source),'rev-parse','HEAD']).strip()
    if source_head != args.expected_commit:
        raise SystemExit('OpenGPU LLVM ISA smoke source commit differs from the pin')
    if output(['git','-C',str(source),'status','--porcelain','--untracked-files=no']).strip():
        raise SystemExit('OpenGPU LLVM ISA smoke source fixtures are modified')
    for fixture in (mc_test,clang_test):
        subprocess.run(['git','-C',str(source),'ls-files','--error-unmatch',
                        str(fixture.relative_to(source))],check=True,stdout=subprocess.DEVNULL)
    cache_home = next((line.split('=',1)[1] for line in cache.read_text().splitlines()
                       if line.startswith('CMAKE_HOME_DIRECTORY:INTERNAL=')),None)
    if cache_home is None or Path(cache_home).resolve() != (source/'llvm').resolve():
        raise SystemExit('OpenGPU LLVM ISA smoke build does not match the pinned source')
    version = output([str(tools['clang']),'--version'])
    if args.expected_commit not in version:
        raise SystemExit('OpenGPU LLVM ISA smoke requires the pinned compiler commit')
    artifact = ROOT/'.artifacts'
    artifact.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='llvm-isa-',dir=artifact) as directory:
        work = Path(directory)
        assembly = output([str(tools['llvm-mc']),str(mc_test),'-triple=opengpu','-mcpu=opengpu',
                           '-M','no-aliases','-show-encoding'])
        for encoding in ('[0x2b,0x85,0xc5,0x86]','[0x2b,0x85,0xc5,0x84]',
                         '[0xab,0x0f,0xf0,0x87]','[0x2b,0x80,0x0f,0x86]','[0xab,0x8f,0xff,0x85]'):
            if encoding not in assembly:
                raise SystemExit('OpenGPU fn33 encoding differs: '+encoding)
        obj = work/'tes-patch.o'
        subprocess.run([str(tools['llvm-mc']),str(mc_test),'-filetype=obj','-triple=opengpu',
                        '-mcpu=opengpu','-o',str(obj)],check=True)
        disassembly = output([str(tools['llvm-objdump']),'--triple=opengpu','--mcpu=opengpu',
                              '-M','no-aliases','-d',str(obj)])
        if disassembly.count('opengpu.vtes.patch.read') != 5:
            raise SystemExit('OpenGPU fn33 object roundtrip is incomplete')
        rejected([str(tools['llvm-mc']),str(mc_test),'-triple=riscv32','-mattr=-xopengpu'])
        clang_assembly = ' '.join(output([str(tools['clang']),'-target','opengpu','-mcpu=opengpu',
                                          '-O2','-S','-o','-',str(clang_test)]).split())
        if 'opengpu.vtes.patch.read v10, v11, v12, v0.t' not in clang_assembly:
            raise SystemExit('Clang lost the masked OpenGPU fn33 form')
        clang_obj = work/'clang-tes-patch.o'
        subprocess.run([str(tools['clang']),'-target','opengpu','-mcpu=opengpu','-O2','-c',
                        str(clang_test),'-o',str(clang_obj)],check=True)
        texture_obj = work/'compute-resources.o'
        subprocess.run([str(tools['llvm-mc']),'-filetype=obj','-triple=opengpu','-mcpu=opengpu',
                        str(texture),'-o',str(texture_obj)],check=True)
        texture_binary = work/'compute-resources.bin'
        subprocess.run([str(tools['llvm-objcopy']),'-O','binary','--only-section=.text',
                        str(texture_obj),str(texture_binary)],check=True)
        if not texture_binary.stat().st_size:
            raise SystemExit('OpenGPU texture assembly produced an empty text section')
    print('OpenGPU LLVM ISA smoke PASS: fn33 MC/Clang and texture assembly')


if __name__ == '__main__':
    main()
