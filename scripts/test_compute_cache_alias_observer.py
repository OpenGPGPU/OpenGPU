#!/usr/bin/env python3
"""Synthetic full-geometry/handshake controls, not actual cache acceptance."""
from copy import deepcopy
import json
import os
from pathlib import Path
import shlex
import subprocess
import tempfile
import unittest

from compute_cache_alias_observation import checked_observation,checked_uc_observation,CYCLES,UC_CYCLES,UC_COUNTS

ROOT=Path(__file__).resolve().parents[1]


def fixture_header():
    # Generated only in a temporary CPU test directory. Actual model headers
    # are separately compiled in the bounded complete-model ABI check.
    cu='GpuComputeUnit__DOT__core__DOT__vectorDataCache__DOT__'
    l2='GpuHostSystemAxi__DOT__system__DOT__l2__DOT__slices_'
    out=['struct FakeCuRoot {']
    out += ['uint8_t '+cu+'valid_%u_%u=0;'%(way,index) for way in range(2) for index in range(64)]
    out += ['std::array<uint32_t,64> '+cu+'tags_%u_ext__DOT__Memory{};'%way for way in range(2)]
    out += ['};','struct FakeRoot {']
    for bank in range(4):
        for way in range(4):
            base=l2+str(bank)+'__DOT__'
            out += ['std::array<uint32_t,4> '+base+'valid_%u{};'%way,
                    'std::array<uint32_t,256> '+base+'tags_%u__DOT__memory__DOT__mem{};'%way]
    for index in range(2):out+=['uintptr_t GpuHostSystemAxi__DOT__system__DOT__computeUnits_%u__DOT__handle___05FV=0;'%index]
    out += ['};',r'''
struct FakeCu {
 FakeCuRoot *rootp=nullptr;void *context=reinterpret_cast<void*>(uintptr_t(0x1234));
 void *contextp()const{return context;}const char *modelName()const{return "VGpuComputeUnit";}
 uint8_t io_l1Invalidate_valid=0,io_l1Invalidate_ready=0,io_l1InvalidateDone_valid=0,io_l1InvalidateDone_ready=0;
 uint32_t io_l1Invalidate_bits_lineAddress=0,io_l1InvalidateDone_bits_lineAddress=0;
};
struct FakeTop {
 FakeRoot *rootp=nullptr;void *context=reinterpret_cast<void*>(uintptr_t(0x1234));
 void *contextp()const{return context;}
 uint8_t io_m_axi_awvalid=0,io_m_axi_awready=0,io_m_axi_bvalid=0,io_m_axi_bready=0,io_m_axi_bresp=0;
 uint8_t io_m_axi_arvalid=0,io_m_axi_arready=0,io_m_axi_rvalid=0,io_m_axi_rready=0,io_m_axi_rresp=0,io_m_axi_rlast=0;
 uint32_t io_m_axi_awid=0,io_m_axi_bid=0,io_m_axi_arid=0,io_m_axi_rid=0;
 uint8_t io_m_axi_awlen=0,io_m_axi_awsize=0,io_m_axi_arlen=0,io_m_axi_arsize=0;
 uint64_t io_m_axi_awaddr=0,io_m_axi_araddr=0;
};
struct Fixture {
 FakeCuRoot roots[2]{};FakeCu cus[2]{};FakeRoot root{};FakeTop top{};
 Fixture();
};
inline void set_handles(Fixture &f) {
 f.root.GpuHostSystemAxi__DOT__system__DOT__computeUnits_0__DOT__handle___05FV=reinterpret_cast<uintptr_t>(&f.cus[0]);
 f.root.GpuHostSystemAxi__DOT__system__DOT__computeUnits_1__DOT__handle___05FV=reinterpret_cast<uintptr_t>(&f.cus[1]);
}
Fixture::Fixture() {for(unsigned i=0;i<2;++i)cus[i].rootp=&roots[i];top.rootp=&root;set_handles(*this);}
inline void set_l1(FakeCuRoot &r,unsigned way,unsigned index,uint32_t tag,bool valid) {
 assert(way<2 && index<64);
''']
    for way in range(2):
        out += ['if(way==%u){switch(index){'%way]
        out += ['case %u:r.%svalid_%u_%u=valid;break;'%(index,cu,way,index) for index in range(64)]
        out += ['}r.'+cu+'tags_%u_ext__DOT__Memory[index]=tag;}'%way]
    out += ['}',r'''inline void set_l2(FakeRoot &r,unsigned bank,unsigned way,unsigned index,uint32_t tag,bool valid) {
 assert(bank<4 && way<4 && index<128);switch(4*bank+way){''']
    for bank in range(4):
        for way in range(4):
            base=l2+str(bank)+'__DOT__'
            out += ['case %u:{auto &bits=r.%svalid_%u[index>>5];'%(4*bank+way,base,way),
                    'bits=(bits&~(1u<<(index&31)))|(uint32_t(valid)<<(index&31));',
                    'r.'+base+'tags_%u__DOT__memory__DOT__mem[index]=tag;break;}'%way]
    out+=['}}']
    return '\n'.join(out)


class CacheObserver(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        temporary=tempfile.TemporaryDirectory(prefix='cache-observer-',dir=ROOT/'.artifacts')
        cls.addClassCleanup(temporary.cleanup);out=Path(temporary.name)
        (out/'cache_observer_fixture.inc').write_text(fixture_header())
        command=shlex.split(os.environ.get('CXX','c++'))+['-std=c++17','-O1','-Wall','-Wextra','-Werror',
            '-fsanitize=address,undefined','-fno-omit-frame-pointer','-I'+str(ROOT/'scripts'),'-I'+str(out),
            str(ROOT/'scripts/test_compute_cache_alias_observer.cpp'),'-o',str(out/'test')]
        result=subprocess.run(command,capture_output=True,text=True)
        if result.returncode:raise AssertionError(result.stderr)
        result=subprocess.run([str(out/'test')],capture_output=True,text=True,
            env=dict(os.environ,ASAN_OPTIONS='halt_on_error=1:abort_on_error=1',UBSAN_OPTIONS='halt_on_error=1'))
        if result.returncode:raise AssertionError(result.stderr)
        records=[json.loads(line) for line in result.stdout.splitlines()]
        if len(records)!=4:raise AssertionError(result.stdout)
        cls.records,cls.uc_records=records[:2],records[2:]

    def check(self,record,failed,uncached=False):
        return (checked_uc_observation if uncached else checked_observation)(record,
            command_id=42,phase=0 if failed else 1,target_line=0x20000,
            accepted_error=failed,target_attempts=3 if failed else 8,start_cycle=0,end_cycle=100)

    def test_uc_requires_early_l2_drop_and_every_authoritative_read_and_nonallocating_retirement(self):
        for record,failed in zip(self.uc_records,(True,False)):
            self.assertIs(self.check(record,failed,True),record)
            for key in (*UC_CYCLES,*UC_COUNTS):
                missing=dict(record);del missing[key]
                with self.assertRaises(ValueError):self.check(missing,failed,True)
                for value in (None,True,'1',0):
                    with self.assertRaises(ValueError):self.check(dict(record,**{key:value}),failed,True)
            for key in UC_COUNTS:
                with self.assertRaises(ValueError):self.check(dict(record,**{key:record[key]-1}),failed,True)
            for change in ({'preAtomicL2InvalidatedCycle':record['warmCycle']},
                    {'preAtomicL2InvalidatedCycle':record['invalidateCycle']+1},
                    {'firstAuthoritativeReadRequestCycle':record['invalidateAckCycle']},
                    {'firstAuthoritativeReadResponseCycle':record['firstTargetWriteCycle']}):
                with self.assertRaises(ValueError):self.check(dict(record,**change),failed,True)

    def test_cached_and_uc_observations_cannot_substitute_for_each_other(self):
        for index,failed in enumerate((True,False)):
            with self.assertRaises(ValueError):self.check(self.uc_records[index],failed)
            with self.assertRaises(ValueError):self.check(self.records[index],failed,True)
            with self.assertRaises(ValueError):self.check(dict(self.uc_records[index],foreign=True),failed,True)

    def test_sanitized_complete_geometry_preedge_hook_and_actual_port_shapes(self):
        for record,failed in zip(self.records,(True,False)):
            self.assertIs(self.check(record,failed),record)

    def test_every_cycle_and_proof_field_is_required_and_cannot_be_retyped(self):
        for record,failed in zip(self.records,(True,False)):
            for key in record:
                wrong=dict(record);del wrong[key]
                with self.assertRaises(ValueError):self.check(wrong,failed)
                wrong=dict(record);wrong[key]=None
                with self.assertRaises(ValueError):self.check(wrong,failed)
                if type(record[key]) in (int,bool):
                    wrong=dict(record);wrong[key]=str(record[key])
                    with self.assertRaises(ValueError):self.check(wrong,failed)
            with self.assertRaises(ValueError):self.check(dict(record,foreign=True),failed)

    def test_rejects_missing_cu_ack_drain_refetch_and_wrong_target(self):
        for record,failed in zip(self.records,(True,False)):
            changes=({'warmL1Mask':1},{'warmL2':False},{'invalidateMask':1},{'invalidateAckMask':1},
                {'targetLine':0x20040},{'targetWrites':2},{'targetWriteResponses':0},{'refetchedL1Mask':0},
                {'refetchedL2':False},{'commandId':43},{'phase':2},{'acceptedWriteError':not failed})
            for change in changes:
                with self.assertRaises(ValueError):self.check(dict(record,**change),failed)
            for key in CYCLES:
                if not failed and key in ('writeErrorCycle','l2InvalidatedCycle','refetchRequestCycle','refetchResponseCycle'):
                    value=1
                else:value=0
                with self.assertRaises(ValueError):self.check(dict(record,**{key:value}),failed)

    def test_error_refetch_and_healthy_clock_history_remain_distinct(self):
        failed,healthy=self.records
        for left,right in (('writeErrorCycle','firstTargetWriteCycle'),('l2InvalidatedCycle','writeErrorCycle'),
                ('refetchRequestCycle','writeErrorCycle'),('refetchResponseCycle','refetchRequestCycle'),
                ('refillCycle','refetchResponseCycle'),('firstTargetWriteCycle','invalidateAckCycle')):
            wrong=deepcopy(failed);wrong[left]=wrong[right]
            with self.assertRaises(ValueError):self.check(wrong,True)
        for key in ('writeErrorCycle','l2InvalidatedCycle','refetchRequestCycle','refetchResponseCycle'):
            with self.assertRaises(ValueError):self.check(dict(healthy,**{key:7}),False)
        for wrong in (True,1,2,4,0):
            with self.assertRaises(ValueError):checked_observation(failed,command_id=42,phase=0,target_line=0x20000,
                accepted_error=True,target_attempts=wrong,start_cycle=0,end_cycle=100)


if __name__=='__main__':unittest.main()
