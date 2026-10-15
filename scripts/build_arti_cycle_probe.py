#!/usr/bin/env python3
"""Build a cycle-only trace replayer around an explicitly selected four-CU DUT.

Direct register observations avoid new RTL and do not affect model outputs.
Probe replay wall times are diagnostic only, never benchmark candidates.
"""
from pathlib import Path
import argparse,hashlib,json,re,shutil,subprocess
root=Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('model',type=Path)
parser.add_argument('archive',type=Path)
parser.add_argument('output',type=Path)
parser.add_argument('--qemu-archive',action='store_true',
                    help='also create a separate QEMU archive with the diagnostic probe')
parser.add_argument('--profile-runtime',action='store_true',
                    help='link an instrumented training archive; replay times remain diagnostic')
parser.add_argument('--trace-source',type=Path,
                    default=root.parent/'FlashSim/scripts/arti_trace_model.cpp')
args=parser.parse_args()
model=args.model.resolve();archive=args.archive.resolve();out=args.output.resolve()
if not archive.is_file() or not (model/'dut.h').is_file() or not args.trace_source.is_file():
 parser.error('model header, archive and trace source must exist')
if out.exists():parser.error('choose a fresh output directory to preserve previous evidence')
out.mkdir(parents=True)
build_manifest=archive.parent/'manifest.json'
if build_manifest.is_file():
 recorded=json.loads(build_manifest.read_text()).get('header_sha256')
 if recorded and recorded!=hashlib.sha256((model/'dut.h').read_bytes()).hexdigest():
  parser.error('archive build manifest does not match the selected model header')
header=(model/'dut.h').read_text();wrapper=(model/'arti_rtl_model.cpp').read_text()
prefixes=re.findall(r'uint8_t (host_host_core_rp_parallelFrag_worker(?:_[123])?)_state =',header)
assert len(prefixes)==4 and len(set(prefixes))==4
prefixes.sort();bindings=[]
for p in prefixes:
 for suffix in ('state','execSlot','execCount','slotShaderPc_0','slotShaderPc_1','slotTexWidth_0','slotTexWidth_1','slotTexHeight_0','slotTexHeight_1','texUnit_sampler_state','texUnit_state','texUnit_minLevelReg','texUnit_maxLevelReg'):
  assert re.search(r'\b'+p+'_'+suffix+r'\b',header)
 field=lambda s:'g_rtl->'+p+'_'+s
 bindings.append('{'+','.join(field(s) for s in ('state','execSlot','execCount','slotShaderPc_0','slotShaderPc_1','slotTexWidth_0','slotTexWidth_1','slotTexHeight_0','slotTexHeight_1','texUnit_sampler_state','texUnit_state','texUnit_minLevelReg','texUnit_maxLevelReg'))+'}')
probe=r'''
#include <map>
#include <tuple>
namespace cycle_probe {
struct View { unsigned state,slot,lanes,pc0,pc1,w0,w1,h0,h1,sampler,texture,min_level,max_level; };
struct Counts { uint64_t phases[6]={}, sampler[9]={}, texture[11]={}, fixed_lod_gradient=0,batches=0,lanes=0; };
using Key=std::tuple<unsigned,unsigned,unsigned>;
static Counts batches[4];static Key keys[4];static bool active[4]={};
static std::map<Key,Counts> totals;
static uint64_t ticks, phase_any[6]={}, staging_without_run, worker_batches[4]={}, worker_run[4]={}; static unsigned peak_run;
static View view[4];
static void observe() {
 View current[4]={@BINDINGS@};unsigned phases[6]={};ticks++;
 for(unsigned i=0;i<4;i++) {
  auto &v=current[i];view[i]=v;
  if(v.state>=6)abort();phases[v.state]++;
  if(v.state==1 && !active[i]) {
   batches[i]=Counts{};active[i]=true;
   keys[i]=Key(v.slot?v.pc1:v.pc0,v.slot?v.w1:v.w0,v.slot?v.h1:v.h0);
   batches[i].lanes=v.lanes;
  }
  if(active[i]) {
   batches[i].phases[v.state]++;
   if(v.state==3) {if(v.sampler>=9)abort();batches[i].sampler[v.sampler]++;if(v.texture>=11)abort();batches[i].texture[v.texture]++;if(v.texture>0 && v.texture<9 && v.min_level==v.max_level)batches[i].fixed_lod_gradient++;}
  }
 }
 for(unsigned i=0;i<6;i++)if(phases[i])phase_any[i]++;
 if(phases[3]>peak_run)peak_run=phases[3];
 for(unsigned i=0;i<4;i++)if(current[i].state==3)worker_run[i]++;
 if(!phases[3] && (phases[1]||phases[4]))staging_without_run++;
}
static void retire() {
 unsigned after[4]={@STATES@};
 for(unsigned i=0;i<4;i++)if(active[i] && view[i].state==5 && after[i]!=5) {
  auto &c=totals[keys[i]];auto &b=batches[i];c.batches++;c.lanes+=b.lanes;worker_batches[i]++;
  for(unsigned s=0;s<6;s++)c.phases[s]+=b.phases[s];
  for(unsigned s=0;s<9;s++)c.sampler[s]+=b.sampler[s];for(unsigned s=0;s<11;s++)c.texture[s]+=b.texture[s];c.fixed_lod_gradient+=b.fixed_lod_gradient;active[i]=false;
 }
}
struct Reporter { ~Reporter() {
 fprintf(stderr,"CYCLE_PROBE_GLOBAL ticks=%llu staging_without_run=%llu phase_any=",(unsigned long long)ticks,(unsigned long long)staging_without_run);
 for(auto n:phase_any)fprintf(stderr,"%llu,",(unsigned long long)n);fprintf(stderr,"\n");
 fprintf(stderr,"CYCLE_PROBE_WORKERS peak_run=%u\n",peak_run);
 for(unsigned i=0;i<4;i++)fprintf(stderr,"CYCLE_PROBE_WORKER id=%u run=%llu batches=%llu\n",i,(unsigned long long)worker_run[i],(unsigned long long)worker_batches[i]);
 for(auto &pair:totals) {
  auto &k=pair.first;auto &c=pair.second;
  fprintf(stderr,"CYCLE_PROBE pc=%u texture=%ux%u batches=%llu lanes=%llu states=",std::get<0>(k),std::get<1>(k),std::get<2>(k),(unsigned long long)c.batches,(unsigned long long)c.lanes);
  for(auto n:c.phases)fprintf(stderr,"%llu,",(unsigned long long)n);fprintf(stderr," sampler=");
  for(auto n:c.sampler)fprintf(stderr,"%llu,",(unsigned long long)n);fprintf(stderr," texture_unit=");for(auto n:c.texture)fprintf(stderr,"%llu,",(unsigned long long)n);fprintf(stderr," fixed_lod_gradient=%llu\n",(unsigned long long)c.fixed_lod_gradient);
 }
}};static Reporter reporter;
}
'''.replace('@BINDINGS@',','.join(bindings)).replace('@STATES@',','.join('g_rtl->'+p+'_state' for p in prefixes))
marker='static void tick_with(void (*combo_fn)(void), void (*eval_fn)(void))'
assert wrapper.count(marker)==1
wrapper=wrapper.replace(marker,probe+'\n'+marker)
marker='  g_rtl->tick_nba();\n  eval_fn();'
assert wrapper.count(marker)==1
wrapper=wrapper.replace(marker,'  cycle_probe::observe();\n'+marker+'\n  cycle_probe::retire();')
(out/'wrapper.cpp').write_text(wrapper)
source=args.trace_source.resolve()
obj=out/'probe.o'
subprocess.run(['c++','-std=c++17','-O1','-w','-fbracket-depth=4096','-I',str(model),f'-DARTI_WRAPPER_SOURCE="{out/"wrapper.cpp"}"','-c',str(source),'-o',str(obj)],check=True)
(out/'main.cpp').write_text('#include <cstdlib>\nextern "C" int arti_trace_replay();\nint main(int n,char**v){if(n!=2)return 2;setenv("ARTI_MODEL_REPLAY",v[1],1);return arti_trace_replay();}\n')
assert archive.is_file()
subprocess.run(['c++','-O2',str(out/'main.cpp'),str(obj),str(archive),*(['-fprofile-instr-generate'] if args.profile_runtime else []),'-o',str(out/'replay')],check=True)
def sha(p):
 with p.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
probe_archive=None
if args.qemu_archive:
 members=subprocess.check_output(['ar','t',str(archive)],text=True).splitlines()
 if members.count('arti_rtl_model.o')!=1:
  parser.error('archive must contain exactly one arti_rtl_model.o wrapper')
 probe_archive=out/'libarti_rtl_model.a'
 shutil.copy2(archive,probe_archive)
 replacement=out/'arti_rtl_model.o';shutil.copy2(obj,replacement)
 subprocess.run(['ar','r',str(probe_archive),str(replacement)],check=True)
(out/'manifest.json').write_text(json.dumps(dict(prefixes=prefixes,archive_sha256=sha(archive),wrapper_sha256=sha(out/'wrapper.cpp'),header_sha256=sha(model/'dut.h'),probe_source_sha256=sha(Path(__file__)),trace_source_sha256=sha(source),replayer_sha256=sha(out/'replay'),profile_runtime=args.profile_runtime,qemu_archive_sha256=sha(probe_archive) if probe_archive else None,texture_unit_states=['idle','grad','max_pair','max','mul','add','max2','lod','frac','sample','commit'],scope='Exact architectural phase counts on replay; completed batches only; not simulator performance timing'),indent=2)+'\n')
