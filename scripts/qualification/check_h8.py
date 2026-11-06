#!/usr/bin/env python3
"""Actual H8 terminal/PFN/PTE/accepted-AXI join; never starts hardware or guest."""
import argparse,hashlib,importlib.util,json,re,sys
from pathlib import Path
import check_guest_common as common
import h8_original_run as h8_run
A=Path(__file__).resolve().parent;GOLDEN=A/'h8-golden-programs.json'
NAMES=['producer','textures-0','textures-1','textures-2','images','fault'];VIEW=128*1024**2;TEX=4*1024**2
PLAN=[(1,n)for n in NAMES]+[(2,n)for n in NAMES[:-1]]
def need(ok,why):common.need(ok,why)
def bo_bytes(n):return TEX if 8<=n<40 else VIEW
def golden():
    return json.loads(GOLDEN.read_text())['programs']

def serial_checks(text):
    text=text.replace('\r','');lines=text.splitlines();g=golden()
    need('buffer-glsl:'not in text and 'BUFFER_GLSL_MISMATCH'not in text,'application failure')
    need('BUFFER_GLSL_PREFLIGHT'not in text,'CPU preflight is not a guest run')
    need([s for s in lines if s.startswith('BUFFER_GLSL_QUALIFICATION ')]==['BUFFER_GLSL_QUALIFICATION items=16 local=8,2,1 fullBOs=true'],'exact16-item qualification marker required')
    actual=[s for s in lines if s.startswith('BUFFER_GLSL_PROGRAM ')]
    expected=[]
    for name in NAMES:
        d=g[name];expected.append('BUFFER_GLSL_PROGRAM name=%s words=%u storage=%x/%x textures=%08x images=%x/%x/%x local=8,2,1 GPU=UNRUN'%(name,d['words'],d['reads'],d['writes'],d['textures'],d['imageReads'],d['imageWrites'],d['imageQueries']))
    need(actual==expected,'actual six compiler metadata records differ')
    launches=re.findall(r'^BUFFER_GLSL_LAUNCH name=([\w-]+) epoch=(\d+) expectedError=(\d+) PASS$',text,re.M)
    need(launches==[(n,str(e),'5'if n=='fault'else'0')for e,n in PLAN],'eleven actual launch/fault/reuse records differ')
    checks=re.findall(r'^BUFFER_GLSL_CHECK stage=([\w-]+) BOs=(\d+) words=(\d+) exact=true$',text,re.M)
    expected_checks=[(n,'41'if e==2 and n!='producer'else'40','84624'if e==2 and n!='producer'else'82560')for e,n in PLAN]
    need(checks==expected_checks,'eleven complete prefix/tail/guard checks missing/wrong')
    markers=[s for s in lines if s.startswith(('BUFFER_GLSL_CHECK ','BUFFER_GLSL_LAUNCH '))]
    need(len(markers)==22,'extra/partial check or launch record')
    need(all(markers[2*i].startswith('BUFFER_GLSL_CHECK stage='+n+' ')and markers[2*i+1].startswith('BUFFER_GLSL_LAUNCH name='+n+' epoch='+str(e)+' ')for i,(e,n)in enumerate(PLAN)),'check/launch order differs')
    final='BUFFER_GLSL_RUNTIME parents=11 shaderPrograms=6 full32textures=true full4SSBO=true full4independentImages=true full13CB=true faultFreshReuse=PASS CAP_COMPUTE=0 PASS'
    need(lines.count(final)==1 and text.index(final)>text.index('BUFFER_GLSL_LAUNCH name=images epoch=2'),'final actual app success missing/out of order')
    need('GLSL_LINUX_RUNTIME 'not in text,'H7 and H8 mixed')
    groups=common.parse_pfn_groups(text);need(len(groups)==2,'two completed actual H8 public layouts required')
    objects=[];handles=[]
    for epoch,group in enumerate(groups,1):
        o=common.pfn_objects(group);count=40 if epoch==1 else 41
        need(set(o)=={('buffer',i)for i in range(count)}|{('ubo-source',i)for i in range(24,36)},'public BO labels incomplete/extra')
        allocated={};ranges=[]
        for key,runs in o.items():
            size=bo_bytes(key[1])if key[0]=='buffer'else 16384
            need(sum(r['pages']for r in runs)*4096==size,'full public allocation shrunk/partial')
            handle=runs[0]['handle'];need(handle>0 and handle not in allocated,'independent public allocations share handle');allocated[handle]=key
            ranges.extend((r['physical'],r['physical']+r['pages']*4096)for r in runs)
        ranges.sort();need(all(a[1]<=b[0]for a,b in zip(ranges,ranges[1:])),'independent public BO physical allocations overlap')
        objects.append(o);handles.append(allocated)
    for key,runs in objects[0].items():need(objects[1][key]==runs,'retained public BO source PFNs/handles changed unexpectedly')
    need(objects[1][('buffer',40)][0]['handle']not in handles[0],'old image owner reused as replacement')
    return dict(golden=g,objects=objects,groups=groups)

def join_jobs(parsed,observed):
    jobs=observed['snapshots'];need(len(jobs)==11,'exactly eleven actual private compute SUBMIT snapshots required')
    out=[];previous=0;contexts=[]
    for s,(epoch,name)in zip(jobs,PLAN):
        st=s.staging;d=parsed['golden'][name];o=parsed['objects'][epoch-1];n=NAMES.index(name)
        need(st.get(0xc8)==0 and tuple(st.get(a)for a in(0xd4,0xd8,0xdc))==(1,1,1)and tuple(st.get(a)for a in(0xe0,0xe4,0xe8))==(8,2,1),'actual opcode/grid/local differs')
        need(s.submit_cycle>previous and s.b_cycle>=s.submit_cycle and s.pop_cycle>s.b_cycle,'synchronous accepted SUBMIT/B/POP ordering');previous=s.pop_cycle
        fault=name=='fault';comp=s.completion
        need(comp['opcode']==0 and comp['success']is(not fault) and comp['status']==(1 if fault else 0),'wrong actual completion identity/status')
        ssbo=[39,1,2,3]if epoch==2 and name=='producer'else[0,4,2,3]if epoch==2 else[0,1,2,3]
        joined=0;public=[]
        for native,api in enumerate(d['nativeBinding']):
            bo=ssbo[api];joined+=common.join_view(s,0xcfffc000+native*(VIEW+4096),bo_bytes(bo),o[('buffer',bo)],write=True)
            if not fault:
                public.append(dict(kind='ssbo',native=native,apiBinding=api,bo=bo,
                    traffic=common.public_traffic(s,o[('buffer',bo)],observed['transactions'],read=bool(d['reads']&(1<<native)),write=True,require_tail=True)))
        textures=[]
        for t in range(32):
            if d['textures']&(1<<t):
                common.join_view(s,0xa0000000+(3+t)*TEX,TEX,o[('buffer',8+t)]);textures.append(t)
                if not fault:public.append(dict(kind='sampler',native=t,bo=8+t,traffic=common.public_traffic(s,o[('buffer',8+t)],observed['transactions'],read=True,require_tail=True)))
        if name=='images':
            for i,bo in enumerate([1,5,6,40]if epoch==2 else[4,5,6,7]):
                va=0xc7ffb000 if i==3 else 0x87ffd000+i*(VIEW+4096)
                common.join_view(s,va,VIEW,o[('buffer',bo)],write=True)
                public.append(dict(kind='image',native=i,bo=bo,traffic=common.public_traffic(s,o[('buffer',bo)],observed['transactions'],read=True,write=True,require_tail=True,head_offset=64 if epoch==2 and i==0 else 0)))
        uniform=None
        if name=='producer':
            u=common.private_uniform(s,parsed['groups'][epoch-1],204800);traffic=common.uniform_traffic(s,u,observed['transactions']);contexts.append(u['context'])
            uniform=dict(root=u['root'],context=u['context'],passId=u['passId'],generation=u['generation'],bytes=u['bytes'],readonlyPages=len(u['pages']),traffic=traffic,publicSourcePfnsUsedAsSnapshot=False,completePayloadReconstructed=False)
        else:need(st.get(0x440)==0,'non-uniform command inherited GUM1 enable')
        if fault:need(not any(t['kind']=='lower_aw'and s.submit_cycle<=t['cycle']<=s.pop_cycle for t in observed['transactions']),'bounds fault issued a GPU write')
        out.append(dict(epoch=epoch,name=name,ordinal=s.ordinal,satp=s.satp,submitCycle=s.submit_cycle,popCycle=s.pop_cycle,completion=comp,joinedStoragePages=joined,fullSamplerSlots=textures,independentImagesJoined=name=='images',publicTraffic=public,uniform=uniform))
    # idr_alloc starts at1; namespace deletion legitimately permits the same
    # numeric ID for a newly created context. Actual post-fault success/drain
    # and the pinned application's destroy/create path establish reuse.
    need(len(contexts)==2,'two actual uniform publications required')
    return out

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    for name in ['serial','stderr','proof','dtb','dtc','guest-profile','original-terminal','launch-result','out']:ap.add_argument('--'+name,type=Path,required=True)
    ap.add_argument('--expected-common-sha',required=True)
    a=ap.parse_args();need(common.digest(Path(common.__file__))==a.expected_common_sha,'common helper differs from frozen expected SHA');terminal=json.loads(a.original_terminal.read_text())
    need(terminal['exit_code']==0 and terminal['stop']is None and not terminal['remaining'],'original process failed/timed out/or has live descendants')
    original=h8_run.check_original_run(a.launch_result,a.serial,a.stderr,a.proof,a.dtb,a.original_terminal,'h8')
    serial=a.serial.read_text();stderr=a.stderr.read_text();dtb=common.check_static_dtb(a.dtb,a.dtc,a.guest_profile);boot=common.check_boot(serial,'h8');native=common.check_native(stderr,False)
    parsed=serial_checks(serial);observed=common.check_observer(a.proof);jobs=join_jobs(parsed,observed)
    result=dict(status='PASS_BOUNDED_H8_16_ITEM_ACTUAL_GUEST_CHAIN',qualificationItems=16,qualificationLocal=[8,2,1],original128ActualGuestPassed=False,evidenceCode=dict(originalRunSha256=common.digest(Path(h8_run.__file__)),checkerSha256=common.digest(__file__),commonSha256=a.expected_common_sha,goldenSha256=common.digest(GOLDEN)),original=original,dtb=dtb,boot=boot,native=native,parents=jobs,observerTerminal=observed['terminal'],
        scope='single-workgroup serial buffer chain; no two-CU concurrency claim, complete GUR/GUB/AXI payload reconstruction, general formats/GL or full H8 qualification')
    with a.out.open('x')as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps(dict(status=result['status'],parents=len(jobs),fullH8Complete=False)))
if __name__=='__main__':main()
