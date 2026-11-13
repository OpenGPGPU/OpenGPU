#!/usr/bin/env python3
"""Real Mesa to prepared-owner capture integration; CPU protocol only, no GPU.

The input profile is explicitly the OS fixture's test profile. This test never
supplies a held or retired receipt, and never authorizes owner release. It stops
the isolated producer while both real owners are retained, after independently
parsing the actual capture/memory and checking private RX program bytes.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import runpy
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
GPU = next(parent for parent in (ROOT, *ROOT.parents) if (parent / '.git').is_dir())


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-kernel-root', type=Path, required=True)
    parser.add_argument('--native-kernel-revision', default='HEAD', help='committed native source revision copied into the temporary build')
    parser.add_argument('--owner-revision', required=True, help='revision containing the canonical owner capture parser')
    parser.add_argument('--image', required=True, help='ARM64 Linux tool image with GCC/G++ matching the Mesa objects')
    parser.add_argument('--sanitize', action='store_true')
    args = parser.parse_args()
    kernel_source = args.native_kernel_root.resolve(strict=True)
    kernel_revision = subprocess.check_output(['git', 'rev-parse', '--verify', args.native_kernel_revision + '^{commit}'],
                                              cwd=kernel_source, text=True).strip()
    revision = subprocess.check_output(['git', 'rev-parse', '--verify', args.owner_revision + '^{commit}'],
                                       cwd=ROOT, text=True).strip()
    with tempfile.TemporaryDirectory(prefix='g', dir=GPU / '.artifacts') as temporary:
        out = Path(temporary)
        # Build only this immutable committed copy; another worktree may rebase
        # while the isolated compiler runs. The source copy is deleted on exit.
        kernel = out / 'kernel'
        kernel.mkdir()
        archive = out / 'kernel.tar'
        subprocess.run(['git', 'archive', '--format=tar', '--output=' + str(archive),
                        kernel_revision, 'driver', 'scripts'], cwd=kernel_source, check=True)
        subprocess.run(['tar', '-xf', archive, '-C', kernel], check=True)
        archive.unlink()
        (out / 'ipc').mkdir()
        generated = out / 'generated'
        generated.mkdir()
        runpy.run_path(str(kernel / 'scripts/test_native_mmio_capture.py'))['write_bodies'](generated)
        generator = '#define OPENGPU_NATIVE_MMIO 1\n#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN unused_component_main\n'
        generator += '#include ' + json.dumps(str(kernel / 'driver/tests/opengpu_native_submit_fixture.c')) + '\n'
        generator += '''int main(int argc,char **argv) {
            assert(argc==2);struct native_kernel_profile_word words[96],regs[5];
            native_test_profile_words(words,regs);
            /* Explicit CPU test data, never an actual-DUT probe substitute.
             * The minimal kernel unit profile omits Gallium's pixel/batch caps. */
            u32 config=0;for(unsigned i=0;i<5;i++)if(regs[i].address==GPU_REG_SHADER_CONFIG)config=regs[i].value;
            for(unsigned i=0;i<5;i++)if(regs[i].address==GPU_REG_CAPABILITIES) {
              regs[i].value|=GPU_CAP_NATIVE_PIXEL_FORMATS;
              regs[i].value=(regs[i].value&~GPU_CAP_FRAGMENT_BATCH_MASK)|
                (((config&255u)*((config>>8)&255u))<<GPU_CAP_FRAGMENT_BATCH_SHIFT);
            }
            FILE *f=fopen(argv[1],"wx");assert(f);
            fwrite("OGPF0001",1,8,f);native_mmio_le(f,96,4);
            for(unsigned i=0;i<96;i++){native_mmio_le(f,words[i].address,4);native_mmio_le(f,words[i].value,4);}
            native_mmio_le(f,5,4);
            for(unsigned i=0;i<5;i++){native_mmio_le(f,regs[i].address,4);native_mmio_le(f,regs[i].value,4);}
            assert(!fclose(f));return 0;}
        '''
        (out / 'test-profile.c').write_text(generator)
        mesa = Path(os.environ.get('OPENGPU_MESA_DIR', GPU / 'depends/mesa'))
        private = out / 'src/compiler/nir/nir_lower_io.c'
        private.parent.mkdir(parents=True)
        shutil.copy2(mesa / 'src/compiler/nir/nir_lower_io.c', private)
        from apply_mesa_uniform_index_patch import BEFORE, AFTER
        if hashlib.sha256(private.read_bytes()).hexdigest() == BEFORE:
            subprocess.run(['patch', '-p1', '-i', ROOT / 'scripts/patches/mesa-22.3.6-checked-uniform-index.patch'],
                           cwd=out, check=True, stdout=subprocess.DEVNULL)
        if hashlib.sha256(private.read_bytes()).hexdigest() != AFTER:
            raise ValueError('unknown private Mesa lowering source')
        shutil.copy2(private, out / 'nir_lower_io.c')
        flags = ['-std=c11', '-O1', '-Wall', '-Wextra', '-Werror', '-D_POSIX_C_SOURCE=200809L']
        if args.sanitize:
            flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
        includes = ['/out/generated', kernel / 'driver', kernel / 'driver/tests',
                    kernel / 'driver/tests/private_snapshot_stubs', kernel / 'driver/tests/mmu_stubs',
                    kernel / 'driver/tests/compute_submit_stubs']
        compile_profile = ['gcc', *flags, *['-I' + str(path) for path in includes],
                           '/out/test-profile.c', kernel / 'driver/opengpu_native_frame.c', '-o', '/out/test-profile']
        acceptance = ['python3', ROOT / 'scripts/test_gallium_graphics_uniforms.py', '--build', '/out/build',
                      '--mesa-nir-lower-io', '/out/nir_lower_io.c', '--native-kernel-root', kernel]
        if args.sanitize:
            acceptance.append('--sanitize')
        launch = 'import os,subprocess\n'
        launch += 'subprocess.run(' + repr(list(map(str, compile_profile))) + ',check=True)\n'
        launch += 'subprocess.run(["/out/test-profile","/out/ipc/profile.bin"],check=True)\n'
        launch += 'os.environ.update(OPENGPU_NATIVE_HOST_DIRECTORY="/out/ipc",OPENGPU_NATIVE_HOST_PROFILE="/out/ipc/profile.bin")\n'
        launch += 'subprocess.run(' + repr(list(map(str, acceptance))) + ',check=True)\n'
        (out / 'launch.py').write_text(launch)
        name = 'gpu-h7-' + out.name
        command = ['docker', 'run', '--rm', '--name', name, '--memory', '2g', '--cpus', '2',
                   '-v', str(GPU) + ':' + str(GPU) + ':ro', '-v', temporary + ':/out:rw',
                   '-e', 'TMPDIR=/out', '-e', 'CC=gcc', '-e', 'CXX=g++',
                   '-e', 'OPENGPU_MESA_DIR=' + str(mesa),
                   '-e', 'OPENGPU_MESA_BUILD=' + os.environ.get('OPENGPU_MESA_BUILD', str(GPU / 'depends/mesa-build')),
                   '-e', 'OPENGPU_DRM_INCLUDE=' + os.environ.get('OPENGPU_DRM_INCLUDE', str(GPU / 'depends/aarch64-root/include')),
                   '-e', 'OPENGPU_MESA_LINK_LIBS=' + os.environ.get('OPENGPU_MESA_LINK_LIBS', '-lz -lexpat'),
                   '-e', 'LD_LIBRARY_PATH=' + os.environ.get('OPENGPU_MESA_LIBRARY_PATH', str(GPU / 'depends/aarch64-root/lib')),
                   args.image, 'python3', '/out/launch.py']
        process = None
        try:
            with (out / 'producer.log').open('w') as log:
                process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
                deadline = time.monotonic() + 900
                while not (out / 'ipc/ready.json').exists():
                    if process.poll() is not None:
                        raise RuntimeError('Mesa producer exited before retaining both owners')
                    if time.monotonic() > deadline:
                        raise TimeoutError('Mesa capture producer did not become ready')
                    time.sleep(0.1)
                if process.poll() is not None:
                    raise RuntimeError('Mesa producer did not retain live owners')
                canonical_parse(out, revision, args.sanitize)
                from native_graphics_owner_bridge import ready_files, strict_json
                from native_graphics_uniform_oracle import expected_color
                _, _, sources = ready_files(out / 'ipc')
                colors = [expected_color(strict_json(source)) for source in sources]
                if not all(all(color) for color in colors) or colors[0] == colors[1]:
                    raise ValueError('independent original sources did not distinguish both snapshots')
                for forbidden in ('record-0.held.bin', 'record-0.retire', 'record-1.capture', 'response.bin'):
                    if (out / 'ipc' / forbidden).exists():
                        raise ValueError('CPU capture test advanced into unobserved completion')
                if process.poll() is not None:
                    raise RuntimeError('producer released owners during capture inspection')
        except BaseException:
            for path in [out / 'producer.log', *sorted((out / 'build').glob('*.log'))]:
                if path.exists():
                    content = path.read_text(errors='replace')
                    if path.name == 'producer.log' or any(word in content for word in ('error:', 'Assertion', 'runtime error:')):
                        print(path.name, content[-10000:])
            raise
        finally:
            if process is not None and process.poll() is None:
                # No held/retired observation or release authorization is sent.
                # Stop the isolated OS model, not a running physical device.
                subprocess.run(['docker', 'stop', '-t', '1', name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                process.wait(timeout=20)
        print('Actual Mesa prepared-owner capture CPU integration PASS; no GPU execution or observed retirement')


def canonical_parse(out, revision, sanitize):
    for relative in ('scripts/native_owner_capture.hpp', 'scripts/native_frame_fixture.hpp',
                     'scripts/native_frame_axi.hpp', 'driver/gpu_abi.h', 'driver/opengpu_formats.h'):
        path = out / relative
        path.parent.mkdir(exist_ok=True)
        path.write_bytes(subprocess.check_output(['git', 'show', revision + ':' + relative], cwd=ROOT))
    source = out / 'parse.cpp'
    source.write_text('''#include "scripts/native_owner_capture.hpp"
        #include <iterator>
        #include <iostream>
        using namespace native_owner;
        static uint32_t word(const Memory &m,uint32_t address) {
          uint32_t value=0;for(unsigned i=0;i<4;i++)value|=uint32_t(m.byte(address+i))<<(8*i);return value;
        }
        static uint32_t physical(const Memory &m,uint32_t satp,uint32_t va,bool code=false) {
          uint32_t root=(satp&0x3fffffu)<<12;
          uint32_t pde=word(m,root+((va>>22)&1023)*4);require((pde&15)==1,"non-table private PDE");
          uint32_t pte=word(m,((pde>>10)<<12)+((va>>12)&1023)*4);
          require((pte&15)==(code?11u:3u),"incorrect private RX/RO PTE");return ((pte>>10)<<12)|(va&4095);
        }
        int main(int argc,char **argv) {
          require(argc==5,"capture parser arguments");auto m=Memory::read(argv[1]);auto c=Capture::read(argv[2],true);
          verify_readonly(m,m,c.writable);uint32_t satp=0,job=0;
          for(const auto &a:c.trace)if(a.kind==Kind::write) {
            if(a.address==GPU_REG_UCMD_VECTOR_SATP)satp=a.value;
            if(a.address==GPU_REG_UCMD_SOURCE)job=a.value;
          }
          require(satp&GPU_SATP_ENABLE,"missing protected SATP");
          auto read=[&](uint32_t va){return word(m,physical(m,satp,va));};
          require(read(job)==0x474e4a01u,"private GNJ magic");
          uint32_t draw=read(job+16*4),frame=read(job+21*4),state=read(frame+292*4);
          const uint32_t pcs[]={read(draw+342*4),read(state+54*4)};
          for(unsigned stage=0;stage<2;stage++) {
            std::ifstream input(argv[3+stage],std::ios::binary);require(bool(input),"missing actual code GEM bytes");
            std::vector<uint8_t> bytes((std::istreambuf_iterator<char>(input)),{});
            require(bytes.size()==(stage?301u:308u)*4,"unexpected actual Mesa program size");
            for(size_t i=0;i<bytes.size();i++)require(m.byte(physical(m,satp,pcs[stage]+i,true))==bytes[i],"private code differs from real Mesa upload");
          }
          std::cout<<"Canonical capture/PTE/private Mesa code CPU checks PASS\\n";
        }
    ''')
    flags = ['-std=c++17', '-O1', '-Wall', '-Wextra', '-Werror']
    if sanitize:
        flags += ['-fsanitize=address,undefined', '-fno-omit-frame-pointer']
    env = dict(os.environ, TMPDIR=str(out))
    subprocess.run(['c++', *flags, str(source), '-o', str(out / 'parse')], check=True, env=env)
    subprocess.run([out / 'parse', out / 'ipc/union.memory', out / 'ipc/record-0.capture',
                    out / 'build/glsl/native-real-vs.words', out / 'build/glsl/native-real-fs.words'], check=True, env=env)


if __name__ == '__main__':
    main()
