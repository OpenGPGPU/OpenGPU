#!/usr/bin/env python3
"""H7 semantic layer for one actual guest. No compiler, guest or model runner."""
import argparse
from bisect import bisect_right
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re

A=Path(__file__).resolve().parent
VIEW_BYTES=134217728
CHECK_WORDS=8256
STORAGE_VA_BASE=0xcfffc000
STORAGE_VA_STRIDE=VIEW_BYTES+4096

def need(ok,why):
    if not ok:raise ValueError(why)

def golden():
    return json.loads((A/'golden-programs.json').read_text())['programs']

def serial_checks(text,common):
    text=text.replace('\r','');lines=text.splitlines();programs=golden()
    need('gallium-glsl:'not in text,'application require() failure')
    need('GLSL_LINUX_PREFLIGHT'not in text,'preflight is not actual Linux/DRM execution')
    observed=[s for s in lines if s.startswith('GLSL_LINUX_PROGRAM ')]
    need(observed==[x['actual_marker']for x in programs],'five actual guest compiler metadata records differ/missing/duplicated')
    alloc='GLSL_LINUX_ALLOC full4x128MiB=true independent12x16KiB=true ownedDefault4KiB=true'
    need(lines.count(alloc)==1,'missing/duplicate actual full allocation marker')
    need(lines.count('OPENGPU_QUAL_LAYOUT publicSSBO=4 publicUBO=12 defaultUpload=internal PFNs=COMPLETE')==1,'missing/duplicate complete pagemap layout')
    bindings=re.findall(r'^OPENGPU_QUAL_BIND parent=([\w-]+) nativeSlot=(\d+) apiBinding=(\d+)$',text,re.M)
    expected=[(p['name'],str(35+i),str(p['resources']['native.'+str(i)+'.binding']))for p in programs for i in range(4)]
    need(bindings==expected,'runtime reflection differs from actual CPU exports or is partial')
    groups=common.parse_pfn_groups(text);need(len(groups)==1,'H7 must report exactly one full public layout')
    objects=common.pfn_objects(groups[0]);expected_keys={('ssbo',i)for i in range(4)}|{('ubo',i)for i in range(24,36)}
    need(set(objects)==expected_keys,'missing/extra H7 public resource labels')
    handles={};intervals=[];public_pages=0
    for key,runs in objects.items():
        size=VIEW_BYTES if key[0]=='ssbo' else 16384
        pages=sum(r['pages']for r in runs);need(pages*4096==size,'public complete logical backing size differs')
        handle=runs[0]['handle'];need(handle>0 and handle not in handles,'public allocations share a handle');handles[handle]=key
        public_pages+=pages
        for r in runs:intervals.append((r['physical'],r['physical']+r['pages']*4096,key))
    intervals.sort()
    need(all(a[1]<=b[0]for a,b in zip(intervals,intervals[1:])),'independent public allocations alias physical pages')
    launches=re.findall(r'^GLSL_LINUX_LAUNCH name=([\w-]+) localItems=(\d+) groups=(\d+) error=(\d+) ns=(\d+)$',text,re.M)
    expected_launches=[(p['name'],str(p['metadata_local_items']),'1'if p['name']=='selected-fault'else'2','5'if p['name']=='selected-fault'else'0')for p in programs]
    need([x[:4]for x in launches]==expected_launches,'actual launches do not match five exact parents/fault')
    need(all(int(x[4])>0 for x in launches),'actual launch timer absent')
    ordered=[alloc,
        'OPENGPU_QUAL_LAYOUT publicSSBO=4 publicUBO=12 defaultUpload=internal PFNs=COMPLETE',
        'GLSL_LINUX_UPLOAD epoch=1 fullBackings=4 bytesPerBacking=134217728 touchedBytes=33024 lineEnvelopes=516',
        'GLSL_LINUX_LAUNCH name=epoch1-p ',
        'GLSL_LINUX_PRODUCER epoch=1 checkedWords=8256 full13CB=true full4x128MiB=true PASS',
        'GLSL_LINUX_LAUNCH name=epoch1-c ',
        'GLSL_LINUX_CONSUMER epoch=1 checkedWords=8256 originalGEMs=true full13CB=true PASS',
        'GLSL_LINUX_LAUNCH name=selected-fault ',
        'GLSL_LINUX_FAULT selectedOddLanes=true originalAPIBuffer=0 nativeReadMask=8 nativeWriteMask=1 checkedWords=8256 unchanged=true PASS',
        'GLSL_LINUX_UPLOAD epoch=3 fullBackings=4 bytesPerBacking=134217728 touchedBytes=33024 lineEnvelopes=516',
        'GLSL_LINUX_LAUNCH name=epoch3-p ',
        'GLSL_LINUX_PRODUCER epoch=3 checkedWords=8256 full13CB=true full4x128MiB=true PASS',
        'GLSL_LINUX_LAUNCH name=epoch3-c ',
        'GLSL_LINUX_CONSUMER epoch=3 checkedWords=8256 originalGEMs=true full13CB=true PASS',
        'GLSL_LINUX_RUNTIME computeParents=5 metadataGroups=9 metadataPhysicalWarps=72 healthyItemExecutions=1012 resultItems=506 lastWarpActive=13 ESSLFrontend=310 CAP_COMPUTE=0 PASS',
        'OPENGPU_QUAL_INIT app=h7 status=PASS',
        'OPENGPU_QUAL_INIT done powering off']
    cursor=-1
    for marker in ordered:
        positions=[m.start()for m in re.finditer(re.escape(marker),text)]
        need(len(positions)==1 and positions[0]>cursor,'missing/duplicate/out-of-order actual marker '+marker)
        cursor=positions[0]
    return dict(programs=programs,objects=objects,publicRuns=groups[0],
        summary=dict(actualParents=5,actualCompilerPrograms=5,metadataGroups=9,metadataPhysicalWarps=72,
            boundedCheckedWordsPerParent=CHECK_WORDS,healthyItemExecutions=1012,publicAllocations=16,
            publicPfnPages=public_pages,fullSsboBytes=4*VIEW_BYTES,fullUboSourceBytes=12*16384,
            publicUboPfnsAreSnapshotPfns=False,completeGuestNativeWordsSerialized=False))

def storage_traffic(snapshot,program,objects,transactions,common):
    """Join accepted addresses and successful responses; no write payload claim."""
    intervals=sorted((r['physical'],r['physical']+r['pages']*4096,api)for api in range(4)for r in objects[('ssbo',api)])
    starts=[x[0]for x in intervals];counts=Counter();endpoints=set()
    endpoint_pa={}
    for i in range(4):
        api=program['resources']['native.'+str(i)+'.binding'];base=STORAGE_VA_BASE+i*STORAGE_VA_STRIDE
        for label,offset in (('head',0),('tail',VIEW_BYTES-4)):endpoint_pa[(api,label)]=snapshot.translate(base+offset)
    for t in transactions:
        if not(snapshot.submit_cycle<=t['cycle']<=snapshot.pop_cycle):continue
        hit=set();seen_endpoints=set()
        for n in range(t['length']+1):
            pa,size=common.beat(t,n);i=bisect_right(starts,pa)-1
            if i>=0 and pa<intervals[i][1]:
                begin,end,api=intervals[i];need(pa+size<=end,'one accepted beat straddles a public backing')
                hit.add(api)
                for key,value in endpoint_pa.items():
                    if key[0]==api and pa<=value and value+4<=pa+size:seen_endpoints.add(key)
        if hit:
            need(t['kind']in ('lower_ar','lower_aw'),'unexpected accepted public transaction')
            need(t.get('response_cycle')is not None and t['cycle']<=t['response_cycle']<=snapshot.pop_cycle,'public AR/AW lacks successful R/B by parent POP')
            if t['kind']=='lower_aw':
                seen_endpoints={key for key in seen_endpoints if any(a<=endpoint_pa[key] and endpoint_pa[key]+4<=b for a,b in t['strobed_ranges'])}
            for api in hit:counts[(t['kind'],api)]+=1
            endpoints.update((t['kind'],api,label)for api,label in seen_endpoints)
    fault=program['name']=='selected-fault';reads=program['resources']['reads'];writes=program['resources']['writes']
    for i in range(4):
        api=program['resources']['native.'+str(i)+'.binding']
        if reads>>i&1:need(counts[('lower_ar',api)]>0,'no actual successful public read for reflected native binding')
        if fault:need(counts[('lower_aw',api)]==0,'faulting parent accepted a public storage write address')
        elif writes>>i&1:
            need(counts[('lower_aw',api)]>0,'no actual successful public write address for reflected native binding')
            need(all(('lower_aw',api,label)in endpoints for label in ('head','tail')),'healthy parent lacks actual full-view head/tail write address responses')
        if not fault and reads>>i&1:need(all(('lower_ar',api,label)in endpoints for label in ('head','tail')),'consumer lacks actual full-view head/tail read responses')
    return dict(acceptedReadAddressBurstsByApi={str(i):counts[('lower_ar',i)]for i in range(4)},
        acceptedWriteAddressBurstsByApi={str(i):counts[('lower_aw',i)]for i in range(4)},
        allMatchedSuccessfulRorBBeforePop=True,headTailAddressAndWriteStrobeCoverageRequired=not fault,
        completeWritePayloadReconstructed=False,scope='accepted public address ranges and successful responses; application verifies bounded data')

def join_jobs(parsed,observed,common):
    jobs=[s for s in observed['snapshots']if s.staging.get(0x440)==0x101 and s.staging.get(0x420)==0x101]
    need(len(jobs)==5,'five real private full uniform+storage SUBMIT snapshots required')
    out=[];previous_pop=0
    for s,p in zip(jobs,parsed['programs']):
        st=s.staging;name=p['name'];fault=name=='selected-fault';items=p['metadata_local_items']
        local=(8,4,4)if items==128 else(5,5,5);grid=(1,1,1)if fault else(2,1,1)
        need(st.get(0xc8)==0,'actual parent is not direct compute opcode0')
        need(tuple(st.get(x)for x in(0xd4,0xd8,0xdc))==grid,'actual SUBMIT grid differs from authored parent')
        need(tuple(st.get(x)for x in(0xe0,0xe4,0xe8))==local,'actual SUBMIT local dimensions differ')
        need(s.submit_cycle>previous_pop and s.b_cycle>=s.submit_cycle and s.pop_cycle>s.b_cycle,'synchronous parent ownership/completion order')
        previous_pop=s.pop_cycle
        completion=s.completion
        need(completion['opcode']==0 and completion['success'] is (not fault),'actual completion success/opcode mismatch')
        need(completion['status']==(1 if fault else 0),'actual completion is not the expected execution-fault/success status')
        joined_pages=0
        for i in range(4):
            api=p['resources']['native.'+str(i)+'.binding'];runs=parsed['objects'][('ssbo',api)]
            va=STORAGE_VA_BASE+i*STORAGE_VA_STRIDE
            joined_pages+=common.join_view(s,va,VIEW_BYTES,runs,write=True)
            for page in range(VIEW_BYTES//4096):
                _,pte=s.leaf(va+page*4096)
                need(pte&0xc7==0xc7 and not pte&0x30 and pte>>8&3==2,'storage PTE must be private RW uncached')
        uniform=common.private_uniform(s,parsed['publicRuns'],expected_bytes=204800)
        traffic=common.uniform_traffic(s,uniform,observed['transactions'])
        public_traffic=storage_traffic(s,p,parsed['objects'],observed['transactions'],common)
        out.append(dict(name=name,submitOrdinal=s.ordinal,actualSatp=s.satp,actualCodePc=st.get(0xcc),
            actualGrid=grid,actualLocal=local,completion=completion,joinedStoragePages=joined_pages,
            uniformRoot=uniform['root'],uniformSnapshotBytes=uniform['bytes'],readonlyUniformPages=len(uniform['pages']),
            uniformTraffic=traffic,publicStorageTraffic=public_traffic,publicUboSourcePfnsJoinedAsSnapshot=False,completeGurGubPayloadReconstructed=False))
    return out

def load_common(path):
    spec=importlib.util.spec_from_file_location('resource_guest_common',path)
    module=importlib.util.module_from_spec(spec)
    import sys
    sys.modules[spec.name]=module;spec.loader.exec_module(module);return module

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ['serial','stderr','proof','dtb','dtc','guest-profile','common','original-terminal','launch-result','out']:
        ap.add_argument('--'+name,type=Path,required=True)
    ap.add_argument('--expected-common-sha',required=True)
    a=ap.parse_args();common_sha=hashlib.sha256(a.common.read_bytes()).hexdigest()
    need(common_sha==a.expected_common_sha,'sealed common helper changed')
    common=load_common(a.common)
    terminal=json.loads(a.original_terminal.read_text())
    need(terminal['exit_code']==0 and terminal['stop']is None and not terminal['remaining'],'original QEMU exit/stop/drain failed')
    original_run=common.check_original_run(a.launch_result,a.serial,a.stderr,a.proof,a.dtb,a.original_terminal,'h7')
    serial=a.serial.read_text();stderr=a.stderr.read_text()
    dtb=common.check_static_dtb(a.dtb,a.dtc,a.guest_profile)
    boot=common.check_boot(serial,'h7');native=common.check_native(stderr,require_two_cus=True)
    parsed=serial_checks(serial,common);observed=common.check_observer(a.proof);jobs=join_jobs(parsed,observed,common)
    result=dict(status='PASS_BOUNDED_H7_ACTUAL_LINUX_CHAIN',originalRun=original_run,dtb=dtb,boot=boot,native=native,
        verifier=dict(h7Sha256=hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),commonSha256=common_sha,goldenSha256=hashlib.sha256((A/'golden-programs.json').read_bytes()).hexdigest()),
        application=parsed['summary'],parents=jobs,observerTerminal=observed['terminal'],
        scope='two-CU 16x8 explicit chain; not four-CU product/full H7/full GL or descriptor payload reconstruction')
    with a.out.open('x')as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps(dict(status=result['status'],parents=len(jobs),fullH7Complete=False),indent=2))

if __name__=='__main__':main()
