#!/usr/bin/env python3
"""Explicit, isolated two-native-CU Linux resource qualification model.

--plan only prints immutable argv; --run additionally requires the qualification
environment flag. Never rebuild QEMU/Linux or overwrite a shared model/DTB.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
from set_arti_guest_extent import patch_extent

ROOT = Path(__file__).resolve().parents[1]
SRAM_FILES = ('srambank_64x4x32_6t122.v', 'srambank_64x4x64_6t122.v')
SRAM_COMMIT = '9f5af0939e8dd3cc1a9693a50b23441691dd7d25'


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def commands(args):
    out = args.output
    rtl, model = out / 'rtl', out / 'model'
    wrapper = out / 'generated/embedded'
    include = args.verilator_root / 'include'
    common = ['c++', '-std=gnu++17', '-O1', '-fPIC', '-I'+str(model), '-I'+str(wrapper),
              '-I'+str(include), '-I'+str(include/'vltstd')]
    result = {
        'emit': ['sbt', '-J-Xmx3G', '-J-XX:ActiveProcessorCount=2', '-J-XX:+ExitOnOutOfMemoryError',
                 'set Test / sources := (Test / sources).value.filter(_.getName == "EmitNativeLinuxResourceGuest.scala")',
                 f'Test/runMain opengpu.system.EmitNativeLinuxResourceGuest {rtl}'],
        'generate_wrapper': [sys.executable, '-m', 'arti.cli', 'generate', str(out / 'arti.yaml'),
                             '--output', str(out / 'generated')],
        'verilate': ['verilator', '--cc', '--no-timing', '--assert', '--threads', '1',
                     '--hierarchical', '--hierarchical-threads', '1', '-Wno-UNOPTFLAT', '-Wno-fatal',
                     '--output-groups', '0', '--output-split', '10000', '--output-split-cfuncs', '200',
                     '-CFLAGS', '-fPIC',
                     '--top-module', 'GpuHostSystemAxi', '--Mdir', str(model), '-f', 'filelist.f',
                     str(out / 'hierarchy.vlt'),
                     *[str(args.sram / 'generated/verilog' / f) for f in SRAM_FILES]],
        'make': [args.make, '-C', str(model), '-f', 'VGpuHostSystemAxi.mk', '-j1',
                 'CXX=c++ -std=gnu++17', 'OPT_FAST=-O1', 'OPT_SLOW=-O0', 'VGpuHostSystemAxi__ALL.a'],
        'compile-wrapper': common + ['-DARTI_MODEL_IDLE_GRACE=64', '-c', str(wrapper/'arti_rtl_model.cpp'),
                                      '-o', str(model/'arti_rtl_model.o')],
        'archive': ['ar', 'rcs', str(model/'libarti_rtl_model.a'), str(model/'arti_rtl_model.o'),
                    *[str(model/(f+'.o')) for f in ('verilated', 'verilated_threads', 'verilated_dpi')]],
        'link_qemu': [sys.executable, str(ROOT / 'scripts/link_arti_model.py'),
                      str(model / 'libarti_rtl_model.a'), str(out / 'qemu-system-aarch64'),
                      '--qemu-build', str(args.qemu_build)],
        'dump_dtb': [str(out / 'qemu-system-aarch64'), '-machine', f'virt,dumpdtb={out / "original.dtb"}',
                     '-accel', 'tcg', '-cpu', 'cortex-a53', '-m', '2048M', '-L', str(args.firmware),
                     '-display', 'none', '-monitor', 'none', '-serial', 'none'],
        'match_extent': [sys.executable, str(ROOT / 'scripts/set_arti_guest_extent.py'),
                         str(out / 'original.dtb'), str(out / 'guest.dtb'),
                         '--width', '16', '--height', '16', '--stride', '64', '--dtc', str(args.dtc)],
    }
    for f in ('verilated', 'verilated_threads', 'verilated_dpi'):
        result['compile-'+f] = common + ['-c', str(include/(f+'.cpp')), '-o', str(model/(f+'.o'))]
    return result


def arti_config(sources):
    # ARTI's bootstrap parser consumes this documented YAML subset, not JSON.
    # It parses the top module from the first source file.
    top = [p for p in sources if p.name == 'GpuHostSystemAxi.sv']
    if len(top) != 1:
        raise ValueError('expected one actual GpuHostSystemAxi source')
    sources = top + [p for p in sources if p != top[0]]
    if any(any(c in str(p) for c in ',\n\r\"') for p in sources):
        raise ValueError('source path is outside the supported YAML subset')
    return ('rtl:\n  top_module: GpuHostSystemAxi\n  source_files: [' +
            ', '.join('"'+str(p)+'"' for p in sources) + ']\n'
            'bridge:\n  mode: qemu-embedded\n  base_address: 0xb000000\n  data_width: 32\n'
            'advanced:\n  timeout_cycles: 1000\ndisplay:\n  enabled: false\n')


def observed_wrapper(source):
    """Observe actual pins/MMIO; keep all resource/memory responses unchanged."""
    init = 'extern "C" void arti_rtl_model_init(void)'
    edge = '    g_rtl->io_s_axi_aclk = 1;\n    g_rtl->eval();'
    pre_edge = '    g_rtl->io_s_axi_aclk = 0;\n    g_rtl->eval();'
    tick = 'static void tick(void)'
    for needle in (init, edge, pre_edge, tick, '#include <cstring>'):
        if source.count(needle) != 1:
            raise ValueError('ARTI wrapper observer insertion point changed: ' + needle)
    source = source.replace('#include <cstring>', '#include <cstring>\n#include <cstdlib>')
    source = source.replace(tick, 'static void native_memory_observer_init();\n'
                            'static void native_memory_observer_edge();\n'
                            'static unsigned g_native_peak_cus;\nstatic bool g_native_profile;\n' + tick)
    source = source.replace(pre_edge, pre_edge + '\n    if (g_rtl->io_s_axi_aresetn) native_memory_observer_edge();')
    source = source.replace(edge, edge + '\n    if (g_rtl->io_performance_activeComputeUnits > g_native_peak_cus)\n'
                            '        g_native_peak_cus = g_rtl->io_performance_activeComputeUnits;')
    source = source.replace(init, 'extern "C" void arti_native_resource_inner_init(void)')
    source += r'''
static void native_resource_summary() {
    const char *required = std::getenv("ARTI_NATIVE_REQUIRE_TWO_CUS");
    if (!required || std::strcmp(required,"1")) return;
    bool ok = g_native_profile && g_native_peak_cus == 2;
    std::fprintf(stderr,"ARTI_NATIVE_COMPUTE_OBSERVER actualProfile=%s peakActiveCUs=%u status=%s\n",
                 g_native_profile?"true":"false",g_native_peak_cus,ok?"PASS":"FAIL");
    std::fflush(stderr);
    if (!ok) std::_Exit(1);
}
extern "C" void arti_rtl_model_init(void) {
    native_memory_observer_init();
    arti_native_resource_inner_init();
    const unsigned registers[]={0x360,0x36c,0x370,0x374,0x378,0x37c,0x384,0x394};
    const uint64_t expected[]={0x47435201,2,16,8,128,128,16384,32};
    g_native_profile=true;
    for(unsigned i=0;i<sizeof(registers)/sizeof(registers[0]);i++) {
        uint64_t data=0;
        int status=arti_rtl_model_read(registers[i],&data,4);
        std::fprintf(stderr,"ARTI_NATIVE_GCR1 offset=0x%x actual=0x%llx status=%d\n",
                     registers[i],(unsigned long long)data,status);
        g_native_profile &= status==0 && data==expected[i];
    }
    uint64_t flags=0;
    g_native_profile &= arti_rtl_model_read(0x368,&flags,4)==0 && (flags&0x7f0)==0x7f0;
    std::fprintf(stderr,"ARTI_NATIVE_GCR1 flags=0x%llx fullServices=%s\n",
                 (unsigned long long)flags,g_native_profile?"true":"false");
    std::atexit(native_resource_summary);
}
'''
    return source + (ROOT / 'scripts/qualification/guest_memory_observer_adapter.inc').read_text()


def check_guest_memory(decoded):
    if len(re.findall(r'(?m)^\tmemory(?:@[0-9a-f]+)?\s*\{', decoded)) != 1:
        raise ValueError('expected exactly one root RAM node')
    nodes = re.findall(r'(?ms)^\tmemory@40000000\s*\{(.*?)^\t\};', decoded)
    if len(nodes) != 1:
        raise ValueError('expected one QEMU virt memory node at0x40000000')
    reg = re.findall(r'\breg\s*=\s*<([^>]+)>;', nodes[0])
    cells = [int(c, 0) for c in reg[0].split()] if len(reg) == 1 else []
    if cells != [0, 0x40000000, 0, 0x80000000]:
        raise ValueError('guest DTB must describe exactly2 GiB RAM below4 GiB')
    # Proposed CMA768M@0x60000000 is wholly in that RAM and below4 GiB.
    return {'ram_base': 0x40000000, 'ram_bytes': 0x80000000,
            'cma_base': 0x60000000, 'cma_bytes': 768 * 1024 * 1024}


def check_guest_dtb(decoded):
    memory = check_guest_memory(decoded)
    patched, _ = patch_extent(decoded, 16, 16, 64)
    if patched != decoded:
        raise ValueError('guest GPU metadata must match the 16x16 model')
    return memory


def build(args):
    if os.environ.get('OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION') != '1':
        raise ValueError('--run requires OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1')
    if args.output.exists():
        raise FileExistsError('choose a fresh output directory')
    sram_head = subprocess.check_output(['git', '-C', str(args.sram), 'rev-parse', 'HEAD'], text=True).strip()
    if sram_head != SRAM_COMMIT:
        raise ValueError('SRAM source does not match the GPU submodule pin')
    for p in [args.qemu_build / 'build.ninja', args.firmware / 'efi-virtio.rom', args.dtc,
              *[args.sram / 'generated/verilog' / f for f in SRAM_FILES]]:
        if not p.is_file():
            raise FileNotFoundError(p)
    args.output.mkdir(parents=True)
    cmd = commands(args)
    state = {'argv': cmd, 'qualification': 'two native CU Linux resource views', 'events': []}
    def save():
        (args.output / 'build-state.json').write_text(json.dumps(state, indent=2) + '\n')
    def run(label, argv, cwd=ROOT, env=None):
        event = {'label': label, 'argv': list(map(str, argv)), 'cwd': str(cwd), 'started': time.time()}
        state['events'].append(event);save()
        with (args.output / (label + '.log')).open('w') as stream:
            p = subprocess.Popen(argv, cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT)
            event['pid']=p.pid;save();event['exit_code']=p.wait()
        event['finished']=time.time();save()
        if event['exit_code']:
            raise RuntimeError(label + ' failed; see its log')
    source_files = subprocess.check_output(['git','-C',str(ROOT),'ls-files','src/main','build.sbt','project'],text=True).splitlines()
    # Include the new, not-yet-committed emitter in the source pin too.
    source_files = sorted(set(source_files + ['src/test/scala/opengpu/system/EmitNativeLinuxResourceGuest.scala',
                                               'scripts/build_arti_native_compute_model.py',
                                               'scripts/link_arti_model.py', 'scripts/set_arti_guest_extent.py',
                                               'scripts/qualification/guest_memory_observer.h',
                                               'scripts/qualification/guest_memory_observer_adapter.inc']))
    state['source_sha256']={f:sha(ROOT/f) for f in source_files}
    state['arti_head']=subprocess.check_output(['git','-C',str(args.arti),'rev-parse','HEAD'],text=True).strip()
    arti_files=subprocess.check_output(['git','-C',str(args.arti),'ls-files','src/arti'],text=True).splitlines()
    state['arti_source_sha256']={f:sha(args.arti/f) for f in arti_files}
    state['sram_sha256']={f:sha(args.sram/'generated/verilog'/f) for f in SRAM_FILES}
    state['sram_head']=sram_head;save()
    run('emit', cmd['emit']) # This JVM exits before Verilator/native compilation.
    rtl=args.output/'rtl';model=args.output/'model'
    sv=sorted(rtl.rglob('*.sv'))
    if not (rtl/'GpuHostSystemAxi.sv').is_file() or not sv:
        raise ValueError('expected the actual host emission')
    state['rtl_sha256']={str(f.relative_to(rtl)):sha(f) for f in sv};save()
    (args.output/'arti.yaml').write_text(arti_config(sv))
    env={**os.environ,'PYTHONPATH':str(args.arti/'src')}
    run('generate-wrapper',cmd['generate_wrapper'],env=env)
    wrapper=args.output/'generated/embedded'
    original=(wrapper/'arti_rtl_model.cpp').read_text()
    (wrapper/'arti_rtl_model.original.cpp').write_text(original)
    shutil.copyfile(ROOT/'scripts/qualification/guest_memory_observer.h', wrapper/'guest_memory_observer.h')
    (wrapper/'arti_rtl_model.cpp').write_text(observed_wrapper(original))
    state['wrapper_sha256']={f.name:sha(f) for f in wrapper.iterdir() if f.is_file()};save()
    (args.output/'hierarchy.vlt').write_text('`verilator_config\nhier_block -module "GpuComputeUnit"\n')
    run('verilate',cmd['verilate'],cwd=rtl)
    run('make',cmd['make'])
    run('compile-wrapper',cmd['compile-wrapper'])
    for f in ('verilated','verilated_threads','verilated_dpi'):
        run('compile-'+f,cmd['compile-'+f])
    # Verilator's __ALL.a target already merges VM_HIER_LIBS (verilated.mk).
    shutil.copy2(model/'VGpuHostSystemAxi__ALL.a',model/'libarti_rtl_model.a')
    run('archive',cmd['archive'])
    run('link-qemu',cmd['link_qemu'])
    run('dump-dtb',cmd['dump_dtb'])
    run('match-extent',cmd['match_extent'])
    decoded=subprocess.check_output([str(args.dtc),'-I','dtb','-O','dts',str(args.output/'guest.dtb')],text=True)
    state['guest_memory']=check_guest_dtb(decoded)
    state['final_sha256']={str(f.relative_to(args.output)):sha(f) for f in
                          (model/'libarti_rtl_model.a',args.output/'qemu-system-aarch64',args.output/'guest.dtb')}
    if any(sha(ROOT/f)!=h for f,h in state['source_sha256'].items()):
        raise ValueError('GPU source changed during native build')
    if any(sha(args.arti/f)!=h for f,h in state['arti_source_sha256'].items()):
        raise ValueError('ARTI source changed during native build')
    if any(sha(args.sram/'generated/verilog'/f)!=h for f,h in state['sram_sha256'].items()):
        raise ValueError('SRAM source changed during native build')
    state['status']='BUILT_NOT_GUEST_EXECUTED';save()
    print(args.output)


def main():
    p=argparse.ArgumentParser(description=__doc__)
    mode=p.add_mutually_exclusive_group(required=True);mode.add_argument('--plan',action='store_true');mode.add_argument('--run',action='store_true')
    p.add_argument('--qemu-build',type=Path,required=True);p.add_argument('--arti',type=Path,required=True)
    p.add_argument('--sram',type=Path,required=True);p.add_argument('--firmware',type=Path,required=True)
    p.add_argument('--dtc',type=Path,required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--verilator-root',type=Path,help='Verilator installation root (default: verilator -V)')
    p.add_argument('--make',default=shutil.which('gmake') or shutil.which('make') or 'make')
    a=p.parse_args()
    if not a.verilator_root:
        version=subprocess.check_output(['verilator','-V'],text=True)
        match=re.search(r'^\s*VERILATOR_ROOT\s*=\s*(.+)$',version,re.M)
        if not match:p.error('cannot identify Verilator headers')
        a.verilator_root=Path(match[1].strip())
    a.verilator_root=a.verilator_root.resolve()
    for k in ('qemu_build','arti','sram','firmware','dtc','output'):setattr(a,k,getattr(a,k).resolve())
    if a.plan:
        print(json.dumps({'commands':commands(a),'run_guard':'OPENGPU_NATIVE_LINUX_RESOURCE_QUALIFICATION=1',
                          'heap_gib':3,'active_processors':2,'native_workers':1,'guest_ram_mib':2048,
                          'source':str(ROOT),'status':'PLAN_ONLY_NO_EMISSION_OR_MODEL'},indent=2));return
    build(a)


if __name__=='__main__':main()
