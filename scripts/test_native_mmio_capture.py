#!/usr/bin/env python3
"""Exact production MMIO bodies + actual submit/owner/MMU, CPU transport only."""
from pathlib import Path
import argparse,os,runpy,shlex,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]

def write_bodies(out):
    runpy.run_path(str(ROOT/'scripts/test_native_submit.py'))['write_bodies'](out)
    hw=(ROOT/'driver/opengpu_hw.c').read_text()
    functions={
        'fault_record':'static void opengpu_hw_record_fault_locked(',
        'job_id':'static u32 opengpu_next_job_id(',
        'reset_pending':'static bool opengpu_hw_unified_reset_pending(',
        'result_error':'static int opengpu_hw_unified_result_error(',
        'drain':'static bool opengpu_hw_unified_drain(',
        'irq':'static irqreturn_t opengpu_irq_handler(',
        'enable_irqs':'static void opengpu_hw_enable_irqs(',
        'quiesce':'static int opengpu_hw_unified_quiesce_timeout(',
        'kick':'static void opengpu_hw_unified_kick(',
        'set_satp':'int opengpu_hw_set_satp(',
        'activate_locked':'static int opengpu_hw_activate_vm_locked(',
        'busy':'static bool opengpu_hw_execution_busy(',
        'wait_idle':'int opengpu_hw_wait_idle_locked(',
        'activate':'int opengpu_hw_activate_vm(',
        'enable':'int opengpu_hw_enable_mmu(',
        'flush':'int opengpu_hw_flush_tlbs(',
        'disable':'int opengpu_hw_disable_mmu_locked(',
        'flush_asid':'int opengpu_hw_flush_tlb_asid(',
        'submit':'static int opengpu_hw_unified_submit_locked(',
        'invalidate':'int opengpu_hw_invalidate_buffer_locked(',
    }
    for name,start in functions.items():
        first=hw.index(start)
        # A declaration may precede the actual definition. Never extract it.
        while hw.find(';',first,hw.index('{',first))!=-1:
            first=hw.index(start,first+len(start))
        last=hw.index('\n}\n',first)+2
        (out/('native_mmio_'+name+'_under_test.inc')).write_text(hw[first:last])
    first=hw.index('struct opengpu_fence {');last=hw.index('static u32 opengpu_reg_read(',first)
    (out/'native_mmio_fence_under_test.inc').write_text(hw[first:last])
    device=(ROOT/'driver/opengpu_device.h').read_text();first=device.index('struct opengpu_fault_snapshot {')
    last=device.index('\n};',first)+3
    (out/'native_mmio_fault_under_test.inc').write_text(device[first:last])

def check_capture_parser(out,executor_root,env):
    # Use the same parser and physical-memory view as the real DUT executor.
    # The C control peer above supplies no rendered image or GPU qualification.
    source=out/'check.cpp'
    source.write_text(r'''#include "native_owner_capture.hpp"
#include <filesystem>
#include <iostream>
using namespace native_owner;
static uint32_t pa_word(const Memory &m,uint64_t address) {
  require(std::any_of(m.ranges.begin(),m.ranges.end(),[&](const auto &r){return address>=r.start && address+4<=r.end;}),"PTE/record escapes real allocation");
  uint32_t value=0;for(unsigned n=0;n<4;n++)value|=uint32_t(m.byte(address+n))<<(n*8);return value;
}
static uint64_t translate(const Memory &m,uint32_t satp,uint32_t va,bool executable=false) {
  auto upper=pa_word(m,uint64_t(satp&0xfffff)<<12|(uint64_t(va>>22)*4));
  require((upper&15)==1,"context root is not a real next-level table");
  auto leaf=pa_word(m,(uint64_t(upper>>10)<<12)+((va>>12)&1023)*4);
  require((leaf&15)==(executable?11u:3u),"private leaf is not exact R/NX or R/X without W");
  return (uint64_t(leaf>>10)<<12)|(va&4095);
}
int main(int argc,char **argv) {
  require(argc==2,"missing actual C fixture directory");const std::filesystem::path base=argv[1];
  auto memory=Memory::read((base/"union.memory").string());std::set<uint32_t> ids,roots;PtePages pte;
  for(unsigned n=0;n<2;n++) {
    auto capture=Capture::read((base/("record-"+std::to_string(n)+".capture")).string(),true);
    auto retired=Retirement::read((base/("record-"+std::to_string(n)+".retire")).string());
    pte.observe(memory,capture);
    require(capture.discovery.size()==101 && ids.insert(capture.command_id).second,"driver lost raw profile or command identity");
    verify_readonly(memory,memory,capture.writable);std::map<uint32_t,uint32_t> regs;unsigned invalidates=0;
    for(const auto &a:capture.trace)if(a.kind==Kind::write){regs[a.address]=a.value;
      if(a.address==GPU_REG_UCMD_SUBMIT && regs[GPU_REG_UCMD_OPCODE]==GPU_UCMD_OP_INVALIDATE)invalidates++;}
    require(invalidates>0 && regs[GPU_REG_UCMD_VECTOR_SATP]==regs[GPU_REG_UCMD_INSTRUCTION_SATP],"capture omitted actual cache invalidation or SATP pair");
    uint32_t satp=regs[GPU_REG_UCMD_VECTOR_SATP],job=regs[GPU_REG_UCMD_SOURCE];
    require(roots.insert(job).second && pa_word(memory,translate(memory,satp,job))==0x474e4a01,"root GNJ does not use original exported PTEs");
    auto word=[&](uint32_t va){return pa_word(memory,translate(memory,satp,va));};
    uint32_t draws=word(job+64),uniforms=word(draws+507*4);
    require(word(draws)==0x474e4401 && word(uniforms)==0x47555201,"actual GND/GUR roots were not rebuilt");
    uint32_t defaults=word(word(uniforms+13*4)+8*4);
    require(word(defaults)==(n?0x55667788u:0x11223344u),"private defaults did not preserve both original snapshots");
    bool legacy=false,raw=false,hi=false;for(const auto &a:retired.actions)if(a.kind!=Kind::write){
      if(a.address==GPU_REG_STATUS)legacy=a.value==GPU_STATUS_DONE && a.mask==0xffffffffu;
      if(a.address==0x188)raw=a.value==0x12345678 && a.mask==0xffffffffu;
      if(a.address==GPU_REG_UCMD_COMPLETION_BYTES_HI)hi=a.value==1 && a.mask==0xffffffffu;}
    require(legacy && raw && hi,"real IRQ drain lost legacy status, raw GNX or 64-bit telemetry");
  }
  std::ifstream done(base/"cleanup.done",std::ios::binary);magic(done,"OGCLD001");
  const auto batches=read_le(done,4);require(batches && done.peek()==EOF,"actual owner cleanup lost final batch count");
  unsigned cleared=0;
  for(unsigned n=0;n<batches;n++) {
    const auto batch=Cleanup::read((base/("cleanup-"+std::to_string(n)+".trace")).string());
    pte.validate_scope(batch,&memory);
    for(const auto &a:batch.actions)if(a.kind==Kind::pte_clear){pte.clear(memory,a);++cleared;}
  }
  require(cleared,"actual cleanup cleared no PTEs");pte.revoked(memory);
  std::cout<<"actual C capture parser/physical owner checks: PASS\n";
}
''')
    subprocess.run(shlex.split(os.environ.get('CXX','c++'))+['-std=c++17','-O1','-Wall','-Wextra','-Werror',
        '-I'+str(executor_root/'scripts'),str(source),'-o',str(out/'check')],env=env,check=True)
    subprocess.run([str(out/'check'),str(out)],env=env,check=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--executor-root',type=Path,help='checkout containing native_owner_capture.hpp; use its actual executor parser too')
    args=parser.parse_args()
    parent=ROOT/'.artifacts';parent.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='native-mmio-',dir=parent) as directory:
        out=Path(directory);write_bodies(out)
        flags=['-D_POSIX_C_SOURCE=200809L','-std=c11','-O1','-Wall','-Wextra','-Werror']
        if os.environ.get('OPENGPU_TEST_SANITIZERS')=='1':flags+=['-fsanitize=address,undefined','-fno-omit-frame-pointer']
        includes=[out,ROOT/'driver',ROOT/'driver/tests',ROOT/'driver/tests/private_snapshot_stubs',
                  ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs']
        env=dict(os.environ,TMPDIR=directory)
        subprocess.run(shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(p) for p in includes]+[
            str(ROOT/'driver/tests/opengpu_native_mmio_test.c'),str(ROOT/'driver/opengpu_native_frame.c'),'-o',str(out/'test')],env=env,check=True)
        subprocess.run([str(out/'test'),str(out)],env=env,check=True)
        if args.executor_root:check_capture_parser(out,args.executor_root.resolve(strict=True),env)
