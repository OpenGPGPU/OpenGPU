#!/usr/bin/env python3
"""Fabricated CPU histories and guarded Linux captures; no DUT residency claim."""
from contextlib import redirect_stdout
from copy import deepcopy
import io
from pathlib import Path
import struct
import sys
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import compute_cache_alias_cases as source
import compute_cache_alias_source as contract
from compute_atomic_order import result
from compute_global_atomic_source import recipe as original_recipe, ready as original_ready, CACHED_SCHEMA, SCHEMA
from compute_owner_oracle import verify
from owner_resource_contract import IMAGE_FLOAT_PROFILE
from test_compute_atomic_order import Memory
from test_compute_global_atomic_source import fixture as original_fixture, HISTORIES

ROOT=Path(__file__).resolve().parents[1]


def fixture(case):
    old,_,description=original_fixture(case)
    code=struct.pack('<%uI'%len(source.bound_words(case)),*source.bound_words(case))
    before=Memory({**old.data,0x10000:bytearray(code+bytes(4096-len(code))),
        0x20000:bytearray(source.initial_kernarg(case)),
        0x30000:bytearray(code+b'\xf9'*(4096-len(code))),
        0x80000:bytearray(b'previous cleaned physical RAM'*128)})
    description=deepcopy(description);description.update(schema=contract.SCHEMA,codeBytes=len(code))
    description['shader']['bytes']=len(code)
    return before,description


def returned(case,*,failed=None,admitted=4,schedule=None,committed=True):
    before,d=fixture(case);after=deepcopy(before);p=source.parameters(case)
    def write(offset,value):struct.pack_into('<I',after.data[0x20000],offset,value)
    value=p['seed'];write(source.READY_OFFSET,2)
    for group in (0,1):write(source.WARM_OFFSET+4*group,value)
    if failed is not None and committed:
        event=source.events(case)[2*failed]
        value=result(event['operation'],value,event['operand'],event['compare'])
    groups=[g for g in range(admitted) if g!=failed]
    if schedule is None:
        schedule=[(g,kind) for g in groups for kind in
                  (('warm',) if g>=2 else ())+('first','second','refetch')]
    calls=source.events(case)
    for group,kind in schedule:
        if kind=='warm':write(source.WARM_OFFSET+4*group,value)
        elif kind=='refetch':write(source.REFETCH_OFFSET+4*group,value)
        else:
            index=2*group+int(kind=='second');write(source.OUTPUT_OFFSET+4*index,value)
            event=calls[index];value=result(event['operation'],value,event['operand'],event['compare'])
    write(p['target_offset'],value)
    return before,after,d


class CacheAliasBytes(unittest.TestCase):
    def test_all_original_cases_accept_independent_literal_read_and_atomic_histories(self):
        for case in source.CASES:
            before,d=fixture(case);after=deepcopy(before)
            old,final=HISTORIES[source.parameters(case)['pair']]
            struct.pack_into('<8I',after.data[0x20000],source.OUTPUT_OFFSET,*old)
            struct.pack_into('<I',after.data[0x20000],source.parameters(case)['target_offset'],final)
            struct.pack_into('<I',after.data[0x20000],source.READY_OFFSET,2)
            seed=source.parameters(case)['seed']
            struct.pack_into('<4I',after.data[0x20000],source.WARM_OFFSET,seed,seed,old[4],old[6])
            struct.pack_into('<4I',after.data[0x20000],source.REFETCH_OFFSET,old[2],old[4],old[6],final)
            contract.recipe(before,d);verify(before,after,d)
            events,order=contract.history_events(case,range(4))
            self.assertEqual((len(events),sum(e['kind']=='atomic' for e in events)),(14,8))
            self.assertEqual(len(order),14)
            with self.assertRaises(ValueError):verify(before,before,d)
            for schema in (SCHEMA,CACHED_SCHEMA):
                with self.assertRaises(ValueError):contract.recipe(before,dict(d,schema=schema))
            with self.assertRaises(ValueError):original_recipe(before,d)

    def test_source_ordered_interleavings_and_every_physical_guard(self):
        for case in source.CASES:
            groups=(0,1,2,3)
            orders=[[(g,k) for g in reversed(groups) for k in
                     (('warm',) if g>=2 else ())+('first','second','refetch')],
                    [(g,'warm') for g in (2,3)]+[(g,'first') for g in groups]+
                    [(g,'second') for g in reversed(groups)]+[(g,'refetch') for g in groups]]
            for order in orders:
                before,after,d=returned(case,schedule=order);verify(before,after,d)
            offsets=(*range(128,160,4),source.READY_OFFSET,*range(256,272,4),
                     *range(320,336,4),source.parameters(case)['target_offset'])
            before,after,d=returned(case)
            for offset in offsets:
                bad=deepcopy(after);struct.pack_into('<I',bad.data[0x20000],offset,0xdeadbeef)
                with self.assertRaises(ValueError):verify(before,bad,d)
            for address,offset in ((0x10000,0),(0x20000,127),(0x20000,160),
                    (0x20000,191),(0x20000,196),(0x20000,272),(0x20000,336),
                    (0x30000,4095),(0x40000,0),(0x80000,17)):
                bad=deepcopy(after);bad.data[address][offset]^=1
                with self.assertRaises(ValueError):verify(before,bad,d)
            bad=Memory({**after.data,0x90000:bytearray(4096)})
            with self.assertRaises(ValueError):verify(before,bad,d)

    def test_committed_failure_keeps_warm_observations_survivor_order_and_previous_ram(self):
        for case in source.CASES:
            for admitted in (2,3,4):
                for failed in (0,1):
                    groups=[g for g in range(admitted) if g!=failed]
                    schedules=[None,[(g,k) for g in reversed(groups) for k in
                        (('warm',) if g>=2 else ())+('first','second','refetch')],
                        [(g,'warm') for g in groups if g>=2]+[(g,'first') for g in groups]+
                        [(g,'second') for g in reversed(groups)]+[(g,'refetch') for g in groups]]
                    for schedule in schedules:
                        before,after,d=returned(case,failed=failed,admitted=admitted,schedule=schedule)
                        attempts=1+2*len(groups)
                        self.assertEqual(contract.verify_fault(before,after,d,'accepted-write',
                            target_attempts=attempts),2*len(groups))
                    for offset in (source.READY_OFFSET,source.WARM_OFFSET,source.WARM_OFFSET+4,
                            source.OUTPUT_OFFSET+8*failed,source.REFETCH_OFFSET+4*failed,160):
                        bad=deepcopy(after);bad.data[0x20000][offset]^=1
                        with self.assertRaises(ValueError):contract.verify_fault(before,bad,d,'accepted-write',
                            target_attempts=attempts)
                    bad=deepcopy(after);bad.data[0x80000][0]^=1
                    with self.assertRaises(ValueError):contract.verify_fault(before,bad,d,'accepted-write',
                        target_attempts=attempts)
                    _,rolled_back,_=returned(case,failed=failed,admitted=admitted,committed=False)
                    with self.assertRaises(ValueError):contract.verify_fault(before,rolled_back,d,'accepted-write',
                        target_attempts=attempts)
                    stale=deepcopy(after)
                    struct.pack_into('<I',stale.data[0x20000],source.REFETCH_OFFSET+4*groups[-1],source.parameters(case)['seed'])
                    with self.assertRaises(ValueError):contract.verify_fault(before,stale,d,'accepted-write',
                        target_attempts=attempts)
                    for wrong in (None,True,0,2,4,6,8):
                        with self.assertRaises(ValueError):contract.verify_fault(before,after,d,'accepted-write',
                            target_attempts=wrong)
                    for mode in (None,'read','write','healthy'):
                        with self.assertRaises(ValueError):contract.verify_fault(before,after,d,mode,
                            target_attempts=attempts)

    def test_rejects_refetch_before_own_second_amo_and_lost_barrier(self):
        case=source.CASES[0]
        order=[(0,'first'),(0,'refetch'),(0,'second')]+[(g,k) for g in (1,2,3)
            for k in (('warm',) if g>=2 else ())+('first','second','refetch')]
        before,after,d=returned(case,schedule=order)
        with self.assertRaises(ValueError):verify(before,after,d)
        before,after,d=returned(case)
        for count in (0,1,3,0xffffffff):
            bad=deepcopy(after);struct.pack_into('<I',bad.data[0x20000],source.READY_OFFSET,count)
            with self.assertRaises(ValueError):verify(before,bad,d)

    def test_ordinary_reads_may_observe_another_callers_later_write(self):
        case='global-atomic-max-cas-base'
        order=[(0,'first'),(0,'second'),(0,'refetch'),(1,'first'),(1,'second'),
            (2,'warm'),(2,'first'),(1,'refetch'),(2,'second'),(2,'refetch'),
            (3,'warm'),(3,'first'),(3,'second'),(3,'refetch')]
        before,after,d=returned(case,schedule=order)
        self.assertEqual(struct.unpack('<I',after.read(0x20000+source.REFETCH_OFFSET+4,4))[0],9)
        verify(before,after,d)

    def test_cached_ready_keeps_both_page_policies_code_and_source_modes_separate(self):
        for case in source.CASES:
            before,d=fixture(case);satp=0x80400050
            before.data[0x50000]=bytearray(4096);before.data[0x51000]=bytearray(4096)
            struct.pack_into('<I',before.data[0x50000],(0x80800000>>22)*4,0x51<<10|1)
            struct.pack_into('<2I',before.data[0x51000],0,0x20<<10|0xcf,0x21<<10|0xcf)
            staged={0xcc:0xc0400000,0xd0:0x80800000,0x14c:satp,0x150:satp,
                **{0xd4+4*n:v for n,v in enumerate((4,1,1))},**{0xe0+4*n:v for n,v in enumerate((16,1,1))}}
            code=['%08x'%w for w in source.bound_words(case)];profile=dict(ownerProfile=IMAGE_FLOAT_PROFILE)
            with patch('compute_global_atomic_source.profile_bytes'),\
                    patch('compute_global_atomic_source.capture',return_value=(None,None,[(0x20000,0x22000)],[],None,staged)),\
                    patch('compute_global_atomic_source.executable_physical',side_effect=lambda _m,_s,va:0x30000+va-0xc0400000):
                contract.ready(before,d,b'CPU fixture',code,profile)
                for cached in (False,True):
                    with self.assertRaises(ValueError):original_ready(before,d,b'',code,profile,cached=cached)
                for page in (0,1):
                    for bit in (1,2,4,8,0x10,0x40,0x80,0x100,0x200):
                        old=bytes(before.data[0x51000][4*page:4*page+4])
                        struct.pack_into('<I',before.data[0x51000],4*page,struct.unpack('<I',old)[0]^bit)
                        with self.assertRaises(ValueError):contract.ready(before,d,b'',code,profile)
                        before.data[0x51000][4*page:4*page+4]=old
                changed=list(code);changed[0]='00000000'
                with self.assertRaises(ValueError):contract.ready(before,d,b'',changed,profile)

    def test_original_producer_emits_every_derived_source_with_distinct_cpu_only_flags(self):
        import compute_global_host_producer as producer
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='warm-source-argv-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);profile=out/'profile.bin';profile.write_bytes(b'OGCP0003CPU fixture')
            def build(path,**_kwargs):path.mkdir();return path/'original-caller'
            argv=['producer','--build',str(out/'build'),'--profile-wire',str(profile),'--case','all',
                  '--capture-only','--cached-kernarg','--cache-alias-source']
            with patch.object(sys,'argv',argv),patch.object(producer,'build',side_effect=build),\
                    patch.object(producer.subprocess,'run',return_value=SimpleNamespace(returncode=0)) as run,\
                    redirect_stdout(io.StringIO()):producer.main()
            self.assertEqual(len(run.call_args_list),24)
            for case,call in zip(source.CASES,run.call_args_list):
                command=call.args[0];self.assertEqual(command[5],case)
                self.assertEqual(command[6:],['--cached-kernarg','--cache-alias-source','--capture-only'])
                self.assertEqual(Path(command[3]).read_bytes(),struct.pack('<%uI'%len(source.words(case)),*source.words(case)))
                self.assertEqual(Path(command[4]).read_bytes(),source.initial_kernarg(case))
            for missing in ('--capture-only','--cached-kernarg'):
                with patch.object(sys,'argv',[v for v in argv if v!=missing]),\
                        redirect_stdout(io.StringIO()),patch('sys.stderr',io.StringIO()),self.assertRaises(SystemExit):
                    producer.main()


if __name__=='__main__':unittest.main()
