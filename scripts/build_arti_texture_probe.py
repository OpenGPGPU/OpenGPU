#!/usr/bin/env python3
"""Measure texture-word reuse within each vector instruction on an exact replay.

The probe observes state only. It estimates 4/8/16-word LRU hits and quad/vector
address reuse; these are not cycle-speedup predictions or host benchmarks.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
PROBE = r'''#include <set>
#include <vector>
#include <algorithm>
namespace texture_reuse {
struct View { unsigned parent,vector,lane,slot,w0,w1,h0,h1,sampler,issued,received; uint32_t addr[4]; };
struct Counts { uint64_t vectors=0, requests=0, vector_reuse=0, quad_reuse=0, lru_hits[3]={}, fetch=0, issue_blocked=0, waiting_response=0; };
using Key=std::pair<unsigned,unsigned>;
static std::map<Key,Counts> totals;
static View before[4];static Key keys[4];static std::set<uint32_t> vector_seen[4],quad_seen[4];static std::vector<uint32_t> lru[4][3];static unsigned quad[4];
static void observe() { View v[4]={@BINDINGS@};for(unsigned i=0;i<4;i++)before[i]=v[i]; }
static void retire() {
 View after[4]={@BINDINGS@};
 for(unsigned i=0;i<4;i++) {
  auto &a=after[i];auto &b=before[i];
  if(b.parent==0 && a.parent!=0 && a.vector) {
   keys[i]={a.slot?a.w1:a.w0,a.slot?a.h1:a.h0};totals[keys[i]].vectors++;
   vector_seen[i].clear();quad_seen[i].clear();quad[i]=a.lane/4;for(auto &l:lru[i])l.clear();
  }
  if(!b.vector || b.sampler!=5)continue;
  auto &c=totals[keys[i]];c.fetch++;
  unsigned leaders=0;for(unsigned j=0;j<4;j++){bool leader=true;for(unsigned k=0;k<j;k++)if(b.addr[k]==b.addr[j])leader=false;if(leader)leaders|=1u<<j;}
  unsigned issued=a.issued & ~b.issued;
  if((leaders & ~b.issued) && !issued)c.issue_blocked++;
  if(!(leaders & ~b.issued) && b.received!=15)c.waiting_response++;
  if(quad[i]!=b.lane/4){quad_seen[i].clear();quad[i]=b.lane/4;}
  for(unsigned j=0;j<4;j++)if(issued & (1u<<j)) {
   uint32_t addr=b.addr[j];c.requests++;
   if(!vector_seen[i].insert(addr).second)c.vector_reuse++;
   if(!quad_seen[i].insert(addr).second)c.quad_reuse++;
   unsigned caps[3]={4,8,16};
   for(unsigned k=0;k<3;k++) {auto &l=lru[i][k];auto p=std::find(l.begin(),l.end(),addr);if(p!=l.end()){c.lru_hits[k]++;l.erase(p);}l.insert(l.begin(),addr);if(l.size()>caps[k])l.pop_back();}
  }
 }
}
struct Reporter {~Reporter(){for(auto &p:totals){auto &c=p.second;fprintf(stderr,"TEXTURE_REUSE width=%u height=%u vectors=%llu requests=%llu vector_reuse=%llu quad_reuse=%llu lru4=%llu lru8=%llu lru16=%llu fetch=%llu issue_blocked=%llu response_wait=%llu\n",p.first.first,p.first.second,(unsigned long long)c.vectors,(unsigned long long)c.requests,(unsigned long long)c.vector_reuse,(unsigned long long)c.quad_reuse,(unsigned long long)c.lru_hits[0],(unsigned long long)c.lru_hits[1],(unsigned long long)c.lru_hits[2],(unsigned long long)c.fetch,(unsigned long long)c.issue_blocked,(unsigned long long)c.waiting_response);}}};static Reporter reporter;
}

'''


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", type=Path)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    model, archive, out = (p.resolve() for p in (args.model, args.archive, args.output))
    if out.exists():
        parser.error("Choose a fresh output directory to preserve prior evidence")
    header = (model / "dut.h").read_text()
    if "_texUnit_sampler_cacheLookupPending" in header:
        parser.error("Cached samplers also set tapIssued for hits; this observer requires an uncached sampler")
    out.mkdir(parents=True)
    cycle = out / "cycle"
    subprocess.run([sys.executable, str(ROOT / "scripts/build_arti_cycle_probe.py"),
                    str(model), str(archive), str(cycle)], check=True)
    prefixes = sorted(re.findall(
        r"uint8_t (host_host_core_rp_parallelFrag_worker(?:_[123])?)_state =", header))
    assert len(prefixes) == 4
    fields = ["texUnit_state", "texUnit_vectorModeReg", "texUnit_laneReg", "execSlot",
              "slotTexWidth_0", "slotTexWidth_1", "slotTexHeight_0", "slotTexHeight_1",
              "texUnit_sampler_state", "texUnit_sampler_tapIssued", "texUnit_sampler_tapReceived",
              *[f"texUnit_sampler_tapAddrs_{i}" for i in range(4)]]
    for prefix in prefixes:
        for field in fields:
            assert re.search(r"\b" + prefix + "_" + field + r"\b", header)
    bindings = ",".join("{" + ",".join("g_rtl->" + p + "_" + f for f in fields[:11]) +
                        ",{" + ",".join("g_rtl->" + p + "_" + f for f in fields[11:]) + "}}"
                        for p in prefixes)
    wrapper = (cycle / "wrapper.cpp").read_text()
    marker = "static void tick_with(void (*combo_fn)(void), void (*eval_fn)(void))"
    assert wrapper.count(marker) == 1
    wrapper = wrapper.replace(marker, PROBE.replace("@BINDINGS@", bindings) + "\n" + marker)
    assert wrapper.count("  cycle_probe::observe();") == 1
    assert wrapper.count("  cycle_probe::retire();") == 1
    wrapper = wrapper.replace("  cycle_probe::observe();", "  cycle_probe::observe();\n  texture_reuse::observe();")
    wrapper = wrapper.replace("  cycle_probe::retire();", "  cycle_probe::retire();\n  texture_reuse::retire();")
    source = out / "wrapper.cpp"
    source.write_text(wrapper)
    trace_source = ROOT.parent / "FlashSim/scripts/arti_trace_model.cpp"
    obj = out / "probe.o"
    subprocess.run(["c++", "-std=c++17", "-O1", "-w", "-fbracket-depth=4096", "-I", str(model),
                    f'-DARTI_WRAPPER_SOURCE="{source}"', "-c", str(trace_source), "-o", str(obj)], check=True)
    subprocess.run(["c++", "-O2", str(cycle / "main.cpp"), str(obj), str(archive),
                    "-o", str(out / "replay")], check=True)
    report = dict(archive_sha256=digest(archive), header_sha256=digest(model / "dut.h"),
                  wrapper_sha256=digest(source), builder_sha256=digest(Path(__file__)),
                  replay_sha256=digest(out / "replay"), trace_source_sha256=digest(trace_source),
                  baseline_replay=str(cycle / "replay"),
                  scope="Observation-only; compare replay outputs and all architectural counts to control. Per-vector LRU estimates do not establish an RTL speedup. No host timing claim.")
    (out / "manifest.json").write_text(json.dumps(report, indent=2) + "\n")


if __name__ == "__main__":
    main()
