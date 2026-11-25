#!/usr/bin/env python3
"""Exact production MMIO bodies + actual submit/owner/MMU, CPU transport only."""
from pathlib import Path
import argparse,os,runpy,shlex,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]

def write_bodies(out):
    runpy.run_path(str(ROOT/'scripts/test_native_submit.py'))['write_bodies'](out)
    hw=(ROOT/'driver/opengpu_hw.c').read_text()
    device=(ROOT/'driver/opengpu_device.h').read_text()
    reset_config=[line for line in device.splitlines() if line.startswith('#define OPENGPU_RESET_WAIT_MS ')]
    if len(reset_config)!=1:
        raise ValueError('original reset timeout definition is missing or repeated')
    functions={
        'fault_record':'static void opengpu_hw_record_fault_locked(',
        'job_id':'static u32 opengpu_next_job_id(',
        'complete':'static void opengpu_hw_complete(',
        'reset_request':'static void opengpu_hw_unified_reset_request(',
        'abort':'void opengpu_hw_abort(',
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
        'joint_profile':'int opengpu_hw_protected_tessellation_joint_profile(',
        'joint_submit':'int opengpu_hw_protected_tessellation_joint_async(',
    }
    for name,start in functions.items():
        first=hw.index(start)
        # A declaration may precede the actual definition. Never extract it.
        while hw.find(';',first,hw.index('{',first))!=-1:
            first=hw.index(start,first+len(start))
        last=hw.index('\n}\n',first)+2
        if name == 'submit':
            first = hw.index('struct opengpu_hw_joint_submission {')
        prefix=reset_config[0]+'\n' if name=='reset_request' else ''
        (out/('native_mmio_'+name+'_under_test.inc')).write_text(prefix+hw[first:last])
    first=hw.index('struct opengpu_fence {');last=hw.index('static u32 opengpu_reg_read(',first)
    (out/'native_mmio_fence_under_test.inc').write_text(hw[first:last])
    first=device.index('struct opengpu_fault_snapshot {')
    last=device.index('\n};',first)+3
    (out/'native_mmio_fault_under_test.inc').write_text(device[first:last])
    first=device.index('struct opengpu_tessellation_scope {')
    last=device.index('\n};',first)+3
    (out/'native_mmio_joint_scope_under_test.inc').write_text(device[first:last])

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
static uint64_t translate(const Memory &m,uint32_t satp,uint32_t va,uint32_t access=3) {
  auto upper=pa_word(m,uint64_t(satp&0xfffff)<<12|(uint64_t(va>>22)*4));
  require((upper&15)==1,"context root is not a real next-level table");
  auto leaf=pa_word(m,(uint64_t(upper>>10)<<12)+((va>>12)&1023)*4);
  require((leaf&15)==access,"private leaf does not have the exact expected permissions");
  return (uint64_t(leaf>>10)<<12)|(va&4095);
}
#ifdef OPENGPU_TEST_JOINT_CAPTURE
static void joint_capture(const std::filesystem::path &base) {
  auto memory=Memory::read((base/"joint.memory").string());
  auto capture=JointCapture::read((base/"joint.capture").string());
  auto retirement=Retirement::read((base/"joint.retire").string(),true);
  auto rejects=[](auto run) {
    try {run();}catch(const std::runtime_error &) {return;}
    throw std::runtime_error("invalid joint capture or retirement was accepted");
  };
  rejects([&]{Capture::read((base/"joint.capture").string(),true);});
  rejects([&]{Retirement::read((base/"joint.retire").string());});
  PtePages pte;pte.observe(memory,capture);
  verify_readonly(memory,memory,capture.writable);
  std::map<uint32_t,uint32_t> regs;
  for(const auto &action:capture.trace)if(action.kind==Kind::write)regs[action.address]=action.value;
  auto va=regs.at(GPU_REG_UCMD_SOURCE),satp=regs.at(GPU_REG_UCMD_VECTOR_SATP);
  auto word=[&](uint32_t address){return pa_word(memory,translate(memory,satp,address));};
  require(word(va)==0x474e5105 && word(va+17*4)==1 && word(va+31*4)==1 &&
    regs.at(GPU_REG_UCMD_PATTERN)==0x474a0102,"joint command lost its actual GNQ owner or triangle tag");
  require(word(word(va+16*4))==0x474e5408,"joint command lost its original private GNT8");
  std::map<uint32_t,uint32_t> before_pop;
  for(const auto &action:retirement.actions) {
    if(action.kind==Kind::write && action.address==GPU_REG_UCMD_COMPLETION_POP)break;
    if(action.kind==Kind::check)before_pop[action.address]=action.value;
  }
  require(before_pop.at(0x544)==2 && before_pop.at(0x54c)==3 &&
    before_pop.at(GPU_REG_UCMD_COMPLETION_BYTES_HI)==1,
    "driver retirement lost observed high words before POP");
  auto foreign=capture;
  for(auto &action:foreign.trace)if(action.kind==Kind::write && action.address==GPU_REG_UCMD_PATTERN &&
    action.value==0x474a0102)action.value|=4;
  rejects([&]{foreign.validate();});
  foreign=capture;
  for(auto &check:foreign.checks)if(check.address==joint_detail_address(5))++check.expected;
  rejects([&]{foreign.validate();});
  retirement.actions.erase(std::remove_if(retirement.actions.begin(),retirement.actions.end(),
    [](const auto &a){return a.address==0x54c;}),retirement.actions.end());
  rejects([&]{retirement.validate(true);});
  auto fault=JointCapture::read((base/"joint-fault.capture").string());
  auto fault_memory=Memory::read((base/"joint-fault.memory").string());
  Retirement::read((base/"joint-fault.retire").string(),true);
  require(!capture.write_fault && fault.write_fault,"only an explicit joint capture may request a fault");
  uint32_t fault_satp=0;
  for(const auto &a:fault.trace)
    if(a.kind==Kind::write && a.address==GPU_REG_UCMD_VECTOR_SATP)fault_satp=a.value;
  require(fault.write_fault->physical==translate(fault_memory,fault_satp,fault.write_fault->address,7),
    "fault target lost its original writable Sv32 mapping");
  auto unexpected=fault;
  unexpected.write_fault.reset();
  rejects([&]{unexpected.validate();});
  unexpected=fault;
  unexpected.write_fault->physical+=1;
  rejects([&]{unexpected.validate();});
  unexpected=fault;
  unexpected.write_fault->address+=64;
  rejects([&]{unexpected.validate();});
  auto read_fault=JointCapture::read((base/"joint-uniform-fault.capture").string());
  Retirement::read((base/"joint-uniform-fault.retire").string(),true);
  require(read_fault.uniform_read_fault && !read_fault.write_fault && !read_fault.cancel_read,
    "uniform read fault lost its distinct capture format");
  auto wrong_fault=read_fault;
  wrong_fault.uniform_read_fault=false;
  rejects([&]{wrong_fault.validate();});
  wrong_fault=read_fault;
  for(auto &check:wrong_fault.checks)if(check.address==joint_detail_address(7))check.expected=1;
  rejects([&]{wrong_fault.validate();});
  auto cancel=JointCapture::read((base/"joint-cancel.capture").string());
  require(cancel.cancel_read && !cancel.write_fault && cancel.checks.empty(),
    "cancel capture contains a predicted completion or write fault");
  const auto request=OwnerResetTrace::read(base/"joint-cancel.request",ResetPhase::request_with_irq,cancel.command_id);
  const auto ack=OwnerResetTrace::read(base/"joint-cancel.retire",ResetPhase::retire,cancel.command_id);
  auto premature=request;
  std::swap(premature.actions[2],premature.actions[3]);
  rejects([&]{premature.validate(ResetPhase::request_with_irq,cancel.command_id);});
  auto pop=ack;
  pop.actions[2].address=GPU_REG_UCMD_COMPLETION_POP;
  rejects([&]{pop.validate(ResetPhase::retire,cancel.command_id);});
}
#endif
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
#ifdef OPENGPU_TEST_JOINT_CAPTURE
  joint_capture(base);
#endif
  std::cout<<"actual C capture parser/physical owner checks: PASS\n";
}
''')
    local=['-DOPENGPU_TEST_JOINT_CAPTURE'] if executor_root==ROOT else []
    subprocess.run(shlex.split(os.environ.get('CXX','c++'))+local+['-std=c++17','-O1','-Wall','-Wextra','-Werror',
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
