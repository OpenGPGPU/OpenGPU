#!/usr/bin/env python3
"""Observe FILL slot/translation/AXI cycles without regenerating the DUT.

Builds baseline and probe executables from the same archive. Run each as
`BASELINE BYTES VM` / `PROBE BYTES VM` (VM=0 bare, VM=1 Sv32), require matching
results after removing the probe counters, and validate bytes/canaries before
interpreting the counters. Probe elapsed times are diagnostic only.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import shutil
import subprocess


def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('model', type=Path)
    parser.add_argument('archive', type=Path)
    parser.add_argument('output', type=Path)
    args = parser.parse_args()
    model, archive, out = (p.resolve() for p in (args.model, args.archive, args.output))
    header = model / 'dut.h'
    if not header.is_file() or not archive.is_file():
        parser.error('model header and archive must exist')
    build_manifest = archive.parent / 'manifest.json'
    if build_manifest.is_file():
        recorded = json.loads(build_manifest.read_text()).get('header_sha256')
        if recorded and recorded != sha(header):
            parser.error('archive manifest does not match this header')
    fields = header.read_text()
    slots = re.findall(r'uint8_t (system_fillEngine_pending_\d+) =', fields)
    slots.sort(key=lambda name: int(name.rsplit('_', 1)[1]))
    if not slots:
        parser.error('expected named FillEngine slot state is missing')
    if out.exists():
        parser.error('select a fresh output directory to retain prior evidence')
    out.mkdir(parents=True)
    names = ['ticks', 'fill_active', 'all_slots_busy_with_work',
             'translation_queue_full', 'axi_aw', 'axi_w', 'axi_b',
             'issued_bytes', 'ack_bytes', 'walk_cycles', 'misses',
             'peak_axi_outstanding', 'slots_empty_with_work', 'abort_cycles',
             'reserved0', 'reserved1', *[f'slots{i}' for i in range(len(slots)+1)]]
    probe = r'''
namespace fill_probe {
static uint64_t c[@COUNT@]={};static unsigned remaining,copied,state;static int inFlight;
static void observe(){
 c[0]++;auto*d=g_rtl;remaining=d->system_fillEngine_remainingBytes;copied=d->system_fillEngine_copiedBytes;state=d->system_fillClient_translator_state;
 unsigned slots=@SLOTS@;
 if(d->system_fillEngine_active){c[1]++;c[16+slots]++;if(remaining&&slots==@N@)c[2]++;if(remaining&&!slots)c[12]++;}
 bool full=d->system_fillClient_translator_pending_enq_ptr_value==d->system_fillClient_translator_pending_deq_ptr_value&&d->system_fillClient_translator_pending_maybe_full;
 c[3]+=full;
 bool aw=d->io_m_axi_awvalid&&d->io_m_axi_awready,w=d->io_m_axi_wvalid&&d->io_m_axi_wready,b=d->io_m_axi_bvalid&&d->io_m_axi_bready;
 c[4]+=aw;c[5]+=w;c[6]+=b;inFlight+=int(aw)-int(b);if(inFlight>int(c[11]))c[11]=inFlight;
 if(state==1||state==2)c[9]++;
}
static void retire(){auto*d=g_rtl;auto r=d->system_fillEngine_remainingBytes,p=d->system_fillEngine_copiedBytes;
 if(r<remaining)c[7]+=remaining-r;if(p>copied)c[8]+=p-copied;
 if(!state&&d->system_fillClient_translator_state==1)c[10]++;
 if(d->system_fillEngine_aborting)c[13]++;
}
}
extern "C" void fill_probe_reset(){memset(fill_probe::c,0,sizeof(fill_probe::c));fill_probe::inFlight=0;}
extern "C" unsigned fill_probe_count(){return @COUNT@;}
extern "C" void fill_probe_get(uint64_t*out,unsigned n){if(n>@COUNT@)abort();memcpy(out,fill_probe::c,n*sizeof(uint64_t));}
'''.replace('@COUNT@', str(len(names))).replace('@N@', str(len(slots))).replace('@SLOTS@', '+'.join('d->'+s for s in slots))
    wrapper = (model / 'arti_rtl_model.cpp').read_text()
    anchor = 'static void tick_with(void (*combo_fn)(void), void (*eval_fn)(void))'
    edge = '  g_rtl->tick_nba();\n  eval_fn();'
    if wrapper.count(anchor) != 1 or wrapper.count(edge) != 1:
        parser.error('tick wrapper layout changed; audit probe insertion points')
    wrapper = wrapper.replace(anchor, probe+'\n'+anchor)
    wrapper = wrapper.replace(edge, '  fill_probe::observe();\n'+edge+'\n  fill_probe::retire();')
    (out / 'wrapper.cpp').write_text(wrapper)
    obj = out / 'arti_rtl_model.o'
    subprocess.run(['c++', '-std=c++17', '-O1', '-fPIC', '-fPIE', '-w',
                    '-fbracket-depth=4096', '-I', str(model), '-c',
                    str(out / 'wrapper.cpp'), '-o', str(obj)], check=True)
    instrumented = out / 'libarti_rtl_model.a'
    shutil.copy2(archive, instrumented)
    subprocess.run(['ar', 'r', str(instrumented), str(obj)], check=True)
    fixture = Path(__file__).with_name('arti_fill_bench.cpp').resolve()
    root = Path(__file__).resolve().parents[1]
    for name, lib, flags in [('baseline', archive, []), ('probe', instrumented, ['-DFILL_PROBE'])]:
        subprocess.run(['c++', '-std=c++17', '-O1', *flags, '-I'+str(model),
                        '-I'+str(root / 'driver'), str(fixture), str(lib),
                        '-o', str(out / name)], check=True)
    (out / 'manifest.json').write_text(json.dumps(dict(
        archive_sha256=sha(archive), header_sha256=sha(header),
        wrapper_sha256=sha(out / 'wrapper.cpp'), probe_archive_sha256=sha(instrumented),
        fixture_sha256=sha(fixture), builder_sha256=sha(Path(__file__)),
        baseline_sha256=sha(out / 'baseline'), probe_sha256=sha(out / 'probe'),
        slot_count=len(slots), counter_names=names,
        scope='Observation-only wrapper; require full baseline equivalence. No RTL or default archive changes. No host timing claims.'), indent=2)+'\n')


if __name__ == '__main__':
    main()
