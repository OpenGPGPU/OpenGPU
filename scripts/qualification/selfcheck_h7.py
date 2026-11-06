#!/usr/bin/env python3
"""Tiny synthetic metadata/PTE fixtures only. No guest, BO, model or compiler."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import time

import check_h7 as h7

def serial_fixture():
    programs=h7.golden()
    lines=[p['actual_marker'] for p in programs]
    lines.append('GLSL_LINUX_ALLOC full4x128MiB=true independent12x16KiB=true ownedDefault4KiB=true')
    for i in range(16):
        kind,binding,pages,pa=('ssbo',i,32768,0x80000000+i*h7.VIEW_BYTES) if i<4 else ('ubo',24+i-4,4,0xa0000000+(i-4)*16384)
        lines.append(f'OPENGPU_QUAL_PFN kind={kind} binding={binding} handle={i+1} page=0 pages={pages} physical={pa:#x}')
    lines.extend(f"OPENGPU_QUAL_BIND parent={p['name']} nativeSlot={35+i} apiBinding={p['resources']['native.'+str(i)+'.binding']}" for p in programs for i in range(4))
    lines.append('OPENGPU_QUAL_LAYOUT publicSSBO=4 publicUBO=12 defaultUpload=internal PFNs=COMPLETE')
    for n,p in enumerate(programs):
        if n in (0,3):
            lines.append(f'GLSL_LINUX_UPLOAD epoch={1 if n==0 else 3} fullBackings=4 bytesPerBacking=134217728 touchedBytes=33024 lineEnvelopes=516')
        fault=p['name']=='selected-fault'
        lines.append(f"GLSL_LINUX_LAUNCH name={p['name']} localItems={p['metadata_local_items']} groups={1 if fault else 2} error={5 if fault else 0} ns=1000")
        if fault:lines.append('GLSL_LINUX_FAULT selectedOddLanes=true originalAPIBuffer=0 nativeReadMask=8 nativeWriteMask=1 checkedWords=8256 unchanged=true PASS')
        elif p['name'].endswith('-p'):lines.append(f"GLSL_LINUX_PRODUCER epoch={1 if n==0 else 3} checkedWords=8256 full13CB=true full4x128MiB=true PASS")
        else:lines.append(f"GLSL_LINUX_CONSUMER epoch={1 if n==1 else 3} checkedWords=8256 originalGEMs=true full13CB=true PASS")
    lines.extend(['GLSL_LINUX_RUNTIME computeParents=5 metadataGroups=9 metadataPhysicalWarps=72 healthyItemExecutions=1012 resultItems=506 lastWarpActive=13 ESSLFrontend=310 CAP_COMPUTE=0 PASS','OPENGPU_QUAL_INIT app=h7 status=PASS','OPENGPU_QUAL_INIT done powering off'])
    return '\n'.join(lines)+'\n'

class SyntheticSnapshot:
    """Formula page tables avoid allocating hundreds of MB of fake backings."""
    def __init__(self,n,p,objects):
        self.ordinal=n;self.program=p;self.objects=objects
        self.submit_cycle=100*n;self.b_cycle=100*n+1;self.pop_cycle=100*n+90;self.satp=0x80400000+n
        local=(8,4,4) if p['metadata_local_items']==128 else (5,5,5)
        grid=(1,1,1) if p['name']=='selected-fault' else (2,1,1)
        self.staging={0xc8:0,0xcc:0xb0000000,0xd0:0xb0800000,0x440:0x101,0x444:0xb1800000,0x420:0x101,0x448:n,0x44c:n,0x450:n,0x454:0xb0800000,0x458:0xb0801000,0x45c:0}
        self.staging.update(zip((0xd4,0xd8,0xdc),grid));self.staging.update(zip((0xe0,0xe4,0xe8),local))
        fault=p['name']=='selected-fault';self.completion={'id':n,'opcode':0,'success':not fault,'status':1 if fault else 0,'word':0}
        self.uniform_flags=0x243;self.storage_flags=0x2cf;self.shift_storage=False;self.alias_uniform=False
        self.tables={0:[self.leaf(0xb1800000+i*4096)[1]for i in range(50)]}
    def leaf(self,va):
        for i in range(4):
            start=h7.STORAGE_VA_BASE+i*h7.STORAGE_VA_STRIDE
            if start<=va<start+h7.VIEW_BYTES:
                api=self.program['resources']['native.'+str(i)+'.binding']
                pa=self.objects[('ssbo',api)][0]['physical']+(va-start)+(4096 if self.shift_storage else 0)
                return pa,((pa>>12)<<10)|self.storage_flags
        if 0xb1800000<=va<0xb1800000+204800:
            pa=(0x80000000 if self.alias_uniform else 0xc1000000+self.ordinal*0x100000)+(va-0xb1800000)
            return pa,((pa>>12)<<10)|self.uniform_flags
        raise ValueError('synthetic unmapped address')
    def translate(self,va,write=False):
        pa,pte=self.leaf(va)
        h7.need(pte&(4 if write else 2),'synthetic permission denied')
        return pa

def observer_fixture(parsed):
    snapshots=[SyntheticSnapshot(n,p,parsed['objects'])for n,p in enumerate(parsed['programs'],1)]
    transactions=[dict(kind='lower_ar',cycle=s.submit_cycle+2,finished_cycle=s.submit_cycle+3,response_cycle=s.submit_cycle+3,address=0xc1000000+s.ordinal*0x100000,size=5,length=0,burst=1)for s in snapshots]
    for s in snapshots:
        p=s.program;fault=p['name']=='selected-fault'
        for i in range(4):
            api=p['resources']['native.'+str(i)+'.binding']
            for kind,active in (('lower_ar',p['resources']['reads']>>i&1),('lower_aw',not fault and p['resources']['writes']>>i&1)):
                if not active:continue
                for offset in (0,h7.VIEW_BYTES-32):
                    pa=s.objects[('ssbo',api)][0]['physical']+offset
                    transactions.append(dict(kind=kind,cycle=s.submit_cycle+4,finished_cycle=s.submit_cycle+5,response_cycle=s.submit_cycle+6,strobed_ranges=[[pa,pa+32]]if kind=='lower_aw'else [],address=pa,size=5,length=0,burst=1))
    return dict(snapshots=snapshots,transactions=transactions)

def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--common',type=Path,required=True);ap.add_argument('--out',type=Path,required=True);a=ap.parse_args()
    start=time.monotonic();common=h7.load_common(a.common);checks=[]
    def reject(name,fn):
        try:fn()
        except ValueError as e:checks.append(dict(name=name,result='REJECTED',reason=str(e)))
        else:raise AssertionError('negative fixture accepted: '+name)
    text=serial_fixture();parsed=h7.serial_checks(text,common)
    checks.append(dict(name='full public sizes/reflection/exact parent and output markers',result='ACCEPTED_SYNTHETIC'))
    serial_bad={
        'missing exact app PASS':text.replace('OPENGPU_QUAL_INIT app=h7 status=PASS\n',''),
        'fault reported successful':text.replace('groups=1 error=5','groups=1 error=0'),
        'wrong compiler word count':text.replace('words=473','words=472',1),
        'wrong reflected API binding':text.replace('nativeSlot=35 apiBinding=1','nativeSlot=35 apiBinding=0',1),
        'incomplete full allocation':text.replace('page=0 pages=32768','page=0 pages=32767',1),
        'source physical aliases another BO':text.replace('physical=0x88000000','physical=0x80000000',1),
        'missing PFN complete marker':text.replace('OPENGPU_QUAL_LAYOUT publicSSBO=4 publicUBO=12 defaultUpload=internal PFNs=COMPLETE\n',''),
        'duplicate exact output marker':text+'OPENGPU_QUAL_INIT app=h7 status=PASS\n',
        'preflight in place of guest':text+'GLSL_LINUX_PREFLIGHT fiveRealPrograms=PASS\n',
        'late application mismatch':text+'gallium-glsl: actual output mismatch\n'}
    for name,bad in serial_bad.items():reject(name,lambda bad=bad:h7.serial_checks(bad,common))
    observed=observer_fixture(parsed);jobs=h7.join_jobs(parsed,observed,common)
    h7.need(len(jobs)==5 and all(j['joinedStoragePages']==131072 and j['readonlyUniformPages']==50 for j in jobs),'positive join fixture totals')
    checks.append(dict(name='five full formula PTE/PFN joins and R-only uniform AR/R',result='ACCEPTED_SYNTHETIC',storagePageComparisons=5*131072,privateUniformPages=250))
    def bad_observer(name,mutate):
        bad=observer_fixture(parsed);mutate(bad);reject(name,lambda:h7.join_jobs(parsed,bad,common))
    bad_observer('wrong real SUBMIT local size',lambda o:o['snapshots'][0].staging.update({0xe0:7}))
    bad_observer('unexpected failed healthy parent',lambda o:o['snapshots'][0].completion.update(success=False,status=1))
    bad_observer('missing actual parent snapshot',lambda o:o['snapshots'].pop())
    bad_observer('PTE differs from actual public PFN',lambda o:setattr(o['snapshots'][0],'shift_storage',True))
    bad_observer('storage page unexpectedly global',lambda o:setattr(o['snapshots'][0],'storage_flags',0x2ef))
    bad_observer('uniform RWX old module regression',lambda o:setattr(o['snapshots'][0],'uniform_flags',0x2cf))
    bad_observer('uniform snapshot aliases public source',lambda o:setattr(o['snapshots'][0],'alias_uniform',True))
    bad_observer('private uniform has another writable VA alias',lambda o:o['snapshots'][0].tables.update({1:[o['snapshots'][0].tables[0][0]|4]}))
    bad_observer('no accepted private uniform AR/R',lambda o:o['transactions'].clear())
    bad_observer('unexpected write to R-only uniform',lambda o:o['transactions'][0].update(kind='lower_aw'))
    def bad_public(name,mutate,index=0):
        bad=observer_fixture(parsed);mutate(bad);s=bad['snapshots'][index]
        reject(name,lambda:h7.storage_traffic(s,s.program,parsed['objects'],bad['transactions'],common))
    bad_public('no public write responses',lambda o:o.update(transactions=[t for t in o['transactions']if t['kind']!='lower_aw']))
    bad_public('public AW without actual successful B',lambda o:next(t for t in o['transactions']if t['kind']=='lower_aw').update(response_cycle=None))
    bad_public('public AW response after POP',lambda o:next(t for t in o['transactions']if t['kind']=='lower_aw').update(response_cycle=10000))
    bad_public('public AW endpoints have no actual strobed bytes',lambda o:[t.update(strobed_ranges=[])for t in o['transactions']if t['kind']=='lower_aw'])
    bad_public('fault parent accepted a public write address',lambda o:o['transactions'].append(dict(kind='lower_aw',cycle=304,finished_cycle=305,response_cycle=306,strobed_ranges=[[0x88000000,0x88000020]],address=0x88000000,size=5,length=0,burst=1)),index=2)
    result=dict(status='PASS_SYNTHETIC_FORMAT_AND_JOIN_CHECKS',actualGuestRun=False,actualObserverLogParsed=False,checks=checks,elapsed_seconds=time.monotonic()-start,commonSha256=hashlib.sha256(a.common.read_bytes()).hexdigest(),h7Sha256=hashlib.sha256(Path(h7.__file__).read_bytes()).hexdigest())
    with a.out.open('x')as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps(dict(status=result['status'],cases=len(checks),actualGuestRun=False,elapsed_seconds=result['elapsed_seconds'])))

if __name__=='__main__':main()
