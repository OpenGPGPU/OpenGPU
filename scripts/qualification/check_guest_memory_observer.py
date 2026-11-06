#!/usr/bin/env python3
"""Explicit small CPU checks. No RTL elaboration, model, guest, or Mesa link.

Run in one CPU / 768MiB with a read-only source checkout and a fresh --out.
--arti-root selects the real generator; optional Mesa paths add app syntax-only
validation using that configured Mesa's actual definitions and include paths.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import sys
from types import SimpleNamespace

ROOT = Path(__file__).resolve().parents[2]


def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--out', type=Path, required=True)
    p.add_argument('--arti-root', type=Path, required=True)
    p.add_argument('--mesa-source', type=Path)
    p.add_argument('--mesa-build', type=Path)
    p.add_argument('--drm-include', type=Path)
    a = p.parse_args()
    if any((a.mesa_source, a.mesa_build, a.drm_include)) and not all((a.mesa_source, a.mesa_build, a.drm_include)):
        p.error('all three Mesa/DRM paths are needed for syntax validation')
    a.out.mkdir(parents=True, exist_ok=False)
    sys.path[:0] = [str(a.arti_root / 'src'), str(ROOT / 'scripts')]
    from arti.protocols import render_axi4_model
    from build_arti_native_compute_model import observed_wrapper
    before, commands, post, error = {}, [], {}, None
    env = os.environ.copy()
    for k in ('ARTI_NATIVE_MEMORY_PROOF', 'ARTI_NATIVE_RAM_BASE', 'ARTI_NATIVE_RAM_END'):
        env.pop(k, None)
    env.update(ASAN_OPTIONS='detect_leaks=1:abort_on_error=1', UBSAN_OPTIONS='halt_on_error=1:print_stacktrace=1')

    def pin(path):
        path = Path(path).resolve(strict=True)
        before[str(path)] = sha(path)

    def run(label, argv, expected=0, override=None):
        argv = list(map(str, argv))
        commands.append({'label': label, 'argv': argv, 'expected_exit': expected})
        (a.out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        with (a.out / (label + '.log')).open('w') as f:
            result = subprocess.run(argv, env={**env, **(override or {})}, stdout=f,
                                    stderr=subprocess.STDOUT, timeout=60)
        commands[-1]['actual_exit'] = result.returncode
        (a.out / 'commands.json').write_text(json.dumps(commands, indent=2) + '\n')
        if result.returncode != expected:
            raise RuntimeError(label + ' exit=' + str(result.returncode))

    try:
        for path in (ROOT / 'scripts/qualification').iterdir():
            if path.is_file():
                pin(path)
        for name in ('scripts/build_arti_native_compute_model.py', 'scripts/set_arti_guest_extent.py',
                     'userspace/examples/gallium_compute_glsl.c', 'userspace/examples/qualification_pagemap.h',
                     'src/main/scala/opengpu/system/GpuHostSystemAxi.scala',
                     'src/test/scala/opengpu/system/EmitNativeLinuxResourceGuest.scala'):
            pin(ROOT / name)
        for path in (a.arti_root / 'src/arti').rglob('*.py'):
            pin(path)
        for tool in ('gcc', 'g++'):
            run(tool + '-version', [tool, '--version'])
        common = ['-O2', '-Wall', '-Wextra', '-Werror', '-UNDEBUG', '-MMD']
        for sanitizer in (False, True):
            suffix = '-san' if sanitizer else ''
            san = ['-fsanitize=address,undefined', '-fno-omit-frame-pointer'] if sanitizer else []
            for stem, cc, standard in (('guest_memory_observer_test', 'g++', '-std=c++17'),
                                      ('qualification_pagemap_test', 'gcc', '-std=c11')):
                source = ROOT / ('scripts/qualification/' + stem + ('.cpp' if cc == 'g++' else '.c'))
                binary = a.out / (stem + suffix)
                run('compile-' + stem + suffix, [cc, standard] + common + san +
                    ['-MF', binary.with_suffix('.d'), source, '-o', binary])
                run('run-' + stem + suffix, [binary])

        # Render the actual ARTI generator against the pinned ResourceGuest
        # public pin declaration shape. This is a CPU code-generation/adapter
        # check with synthetic signals, not an emitted hardware model.
        main_source = (ROOT / 'src/main/scala/opengpu/system/GpuHostSystemAxi.scala').read_text()
        declared = re.findall(r'val ((?:[sm]_axi_)\w+) = (Input|Output)\(([^\n]+)\)', main_source)
        if not declared:
            raise RuntimeError('actual host pin declarations not found')
        ports = []
        for name, direction, typ in declared:
            width = 256 if name in ('m_axi_wdata', 'm_axi_rdata') else 32
            if 'Bool()' in typ or 'Clock()' in typ:
                width = 1
            numeric = re.fullmatch(r'UInt\((\d+)\.W\)', typ)
            if numeric:
                width = int(numeric[1])
            ports.append(SimpleNamespace(name='io_' + name, direction=direction.lower(), width=width))
        port_map = {x.name: x for x in ports}
        mapping = {s.upper(): 'io_s_axi_' + s for s in
                   ('aclk','aresetn','awaddr','awlen','awsize','awburst','awvalid','awready','wdata','wstrb',
                    'wlast','wvalid','wready','bresp','bvalid','bready','araddr','arlen','arsize','arburst',
                    'arvalid','arready','rdata','rresp','rlast','rvalid','rready')}
        signature = SimpleNamespace(module_name='GpuHostSystemAxi', ports=ports)
        config = SimpleNamespace(data_width=32, timeout_cycles=1000, display_enabled=False)
        original = render_axi4_model(config, signature, mapping, port_map, [{'name': 'io_m_irq'}])
        wrapped = observed_wrapper(original)
        (a.out / 'actual-generator.original.cpp').write_text(original)
        (a.out / 'actual-generator.observed.cpp').write_text(wrapped)
        start, end = wrapped.index('static void tick(void)'), wrapped.index('// Advance the model')
        tick = wrapped[start:end]
        hook = 'if (g_rtl->io_s_axi_aresetn) native_memory_observer_edge();'
        if not (tick.index('io_s_axi_aclk = 0') < tick.index(hook) < tick.index('_bresp.pop_front()') < tick.index('io_s_axi_aclk = 1')):
            raise RuntimeError('observer is not before actual ARTI capture and edge')
        for malformed in (original.replace('    g_rtl->io_s_axi_aclk = 0;\n    g_rtl->eval();',''), original + '\nstatic void tick(void)'):
            try:
                observed_wrapper(malformed)
            except ValueError:
                pass
            else:
                raise RuntimeError('changed generator anchor was accepted')
        adapter = (ROOT / 'scripts/qualification/guest_memory_observer_adapter.inc').read_text()
        if 'g_mem_write_cb' in adapter or re.search(r'g_rtl->\w+\s*=(?!=)', adapter) or 'tick(' in adapter:
            raise RuntimeError('qualification adapter drives hardware or memory')
        fields = []
        for port in ports:
            if port.name in ('io_m_axi_wdata','io_m_axi_rdata'):
                fields.append('uint32_t ' + port.name + '[8]{};')
            else:
                fields.append('uint64_t ' + port.name + '=0;')
        synthetic = '''#include <memory>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <string>
struct Pins { FIELDS } pins;
static Pins *g_rtl=&pins;
static int read_ram(uint64_t address,uint8_t *out,unsigned bytes,uint64_t) {
    uint64_t base=address&~UINT64_C(4095); if(base!=0x80000000 && base!=0x80001000) return -1;
    std::memset(out,0,bytes); uint32_t pte=base==0x80000000?0x20000401:0x240002cf;
    if(std::getenv("BAD_ROOT")) pte=base==0x80000000?0x02c00001:pte;
    if(!(address&4095)) for(unsigned i=0;i<4;i++) out[i]=uint8_t(pte>>(8*i));
    return 0;
}
static int (*g_mem_read_cb)(uint64_t,uint8_t *,unsigned,uint64_t)=read_ram;
#include "guest_memory_observer_adapter.inc"
static void write_reg(uint32_t a,uint32_t value) {
    pins.io_s_axi_awaddr=a; pins.io_s_axi_awsize=2; pins.io_s_axi_awburst=1;
    pins.io_s_axi_awvalid=pins.io_s_axi_awready=1; pins.io_s_axi_wdata=value;
    pins.io_s_axi_wstrb=15; pins.io_s_axi_wlast=1; pins.io_s_axi_wvalid=pins.io_s_axi_wready=1;
    native_memory_observer_edge(); pins.io_s_axi_awvalid=pins.io_s_axi_wvalid=0;
    pins.io_s_axi_bvalid=pins.io_s_axi_bready=1;
    if(a==0x11c && std::getenv("BAD_B")) pins.io_s_axi_bresp=2;
    native_memory_observer_edge(); pins.io_s_axi_bvalid=0;
}
int main(int argc,char **) {
    native_memory_observer_init();
    if(argc==1) { pins.io_m_axi_arvalid=1; native_memory_observer_edge(); return 0; }
    write_reg(0x14c,0x80480000); write_reg(0x150,0x80480000); write_reg(0xc8,0); write_reg(0x11c,1);
    pins.io_m_axi_arvalid=1; pins.io_m_axi_arready=1; pins.io_m_axi_arid=8;
    pins.io_m_axi_araddr=0x90000000; pins.io_m_axi_arsize=5; pins.io_m_axi_arlen=1; pins.io_m_axi_arburst=1;
    native_memory_observer_edge(); pins.io_m_axi_arvalid=0;
    pins.io_m_axi_rvalid=pins.io_m_axi_rready=1; pins.io_m_axi_rid=8;
    native_memory_observer_edge(); pins.io_m_axi_rlast=1; native_memory_observer_edge(); pins.io_m_axi_rvalid=0;
    write_reg(0x130,1);
    if(std::getenv("PENDING")) { pins.io_m_axi_arvalid=1; pins.io_m_axi_arready=0; }
    native_memory_observer_edge(); return 0;
}
'''.replace('FIELDS', '\n'.join(fields))
        fixture = a.out / 'adapter-synthetic.cpp'
        fixture.write_text(synthetic)
        binary = a.out / 'adapter-synthetic'
        run('compile-adapter-synthetic', ['g++','-std=c++17'] + common + ['-I' + str(ROOT / 'scripts/qualification'),
            '-MF', binary.with_suffix('.d'), fixture, '-o', binary])
        run('adapter-default-disabled', [binary])
        for case, extra, expected in (('good',{},0),('held',{'PENDING':'1'},1),
                                      ('error-b',{'BAD_B':'1'},1),('bad-root',{'BAD_ROOT':'1'},1)):
            directory = a.out / ('proof-' + case)
            run('adapter-' + case,[binary,'enabled'],expected,{'ARTI_NATIVE_MEMORY_PROOF':str(directory),
                'ARTI_NATIVE_RAM_BASE':'0x40000000','ARTI_NATIVE_RAM_END':'0xc0000000',**extra})
            events = [json.loads(x) for x in (directory / 'events.jsonl').read_text().splitlines()]
            terminal = [x for x in events if x['kind']=='terminal']
            if case=='good':
                if len(terminal)!=1 or terminal[0]['observer_read_bytes']!=8192 or terminal[0]['dut_read_bytes']!=64 or terminal[0]['private_submits']!=1:
                    raise RuntimeError('adapter accounting differs')
                if (directory / 'submit-1-root.bin').stat().st_size!=4096 or (directory / 'submit-1-l1-0.bin').stat().st_size!=4096:
                    raise RuntimeError('snapshot data size differs')
            elif terminal:
                raise RuntimeError('failed adapter wrote COMPLETE')
        if a.mesa_source:
            database = a.mesa_build / 'compile_commands.json'
            pin(database)
            entry = next(x for x in json.loads(database.read_text()) if x['file'].endswith('/tgsi_parse.c'))
            raw = entry.get('arguments') or shlex.split(entry['command'])
            config = []
            for x in raw:
                if x.startswith(('-D','-U')):
                    config.append(x)
                elif x.startswith('-I'):
                    inc = Path(x[2:]); config.append('-I' + str(inc if inc.is_absolute() else Path(entry['directory']) / inc))
            includes = [ROOT / 'userspace/tests',ROOT / 'userspace/gallium',ROOT / 'userspace',ROOT / 'driver',a.drm_include,
                a.mesa_source / 'src/gallium/include',a.mesa_source / 'src/gallium/auxiliary',a.mesa_source / 'src',a.mesa_source / 'include']
            run('actual-app-syntax-only',['gcc','-std=c11','-O2','-Wall','-Wextra','-Werror','-Wno-unused-parameter','-Wno-sign-compare',
                '-UNDEBUG','-fsyntax-only','-MMD','-MF',a.out / 'app-syntax.d'] + config + ['-I'+str(x) for x in includes] +
                [ROOT / 'userspace/examples/gallium_compute_glsl.c'])
    except BaseException as e:
        error = repr(e)
        raise
    finally:
        actual = {}
        for dep in a.out.glob('*.d'):
            for item in shlex.split(dep.read_text().replace('\\\n',' ').split(':',1)[1]):
                path = Path(item).resolve(strict=True)
                actual[str(path)] = sha(path)
        for path in before:
            post[path] = sha(path)
        changed = [x for x in before if before[x]!=post[x]]
        (a.out / 'source-before.json').write_text(json.dumps(before,indent=2,sort_keys=True)+'\n')
        (a.out / 'source-post.json').write_text(json.dumps(post,indent=2,sort_keys=True)+'\n')
        (a.out / 'actual-MMD-inputs.json').write_text(json.dumps(actual,indent=2,sort_keys=True)+'\n')
        (a.out / 'result.json').write_text(json.dumps({'status':'FAIL' if error or changed else 'PASS_CPU_ONLY',
            'error':error,'changed_inputs':changed,'actual_MMD_inputs':len(actual),
            'RTL':False,'guest':False,'app_link':False,'scope':'synthetic observer/pagemap + actual ARTI generator and optional actual app syntax'},indent=2)+'\n')
        if changed:
            raise RuntimeError('source changed during CPU checks')
    print(a.out / 'result.json')


if __name__=='__main__':
    main()
