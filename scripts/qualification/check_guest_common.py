#!/usr/bin/env python3
"""Offline checks of actual ResourceGuest logs/tables. Never runs a guest/model.

Accepted events expose only word0 of each 32-byte data beat. This code validates
transactions/addresses/permissions, never claims complete payload reconstruction.
"""
from collections import Counter,deque,defaultdict
from dataclasses import dataclass,field
import hashlib,importlib.util,json,re,struct,subprocess
from pathlib import Path

RAM=(0x40000000,0x100000000);CMA=(0x80000000,0xe0000000)

def digest(path):
    h=hashlib.sha256()
    with Path(path).open('rb')as f:
        for block in iter(lambda:f.read(1048576),b''):h.update(block)
    return h.hexdigest()

def need(ok,why):
    if not ok:raise ValueError(why)
def span(address,size,bounds=RAM):
    need(size>0 and bounds[0]<=address<bounds[1] and size<=bounds[1]-address,'physical span outside actual bounds')

def check_original_run(launch_result_path,serial,stderr,proof,dtb,original_terminal,app):
    """Bind immutable evidence to the one original supervised QEMU attempt.

    Exit0 is necessary process evidence. Actual boot/app/PTE checks remain
    separate and no semantic PASS is inferred from this record.
    """
    result=json.loads(Path(launch_result_path).read_text())
    need(result['status']=='EXITED_ZERO_UNVERIFIED' and result['app']==app and not result['changed_inputs'],'original attempt failed/changed/mixed')
    need(result['semantic_pass'] is False and result['automatic_retry'] is False and result['shared_model_lock_released'],'wrong original result contract/lock')
    logs={}
    for row in result['immutable_logs']:
        p=Path(row['path']).resolve();need(p not in logs,'duplicate immutable log')
        need(p.stat().st_size==row['bytes'] and digest(p)==row['sha256'],'original log bytes changed');logs[p]=row
    need(all(Path(p).resolve()in logs for p in(serial,stderr,original_terminal)),'CLI evidence does not belong to original attempt')
    terminal=json.loads(Path(original_terminal).read_text())
    need(terminal==result['original_terminal'] and terminal['exit_code']==0 and terminal['stop']is None and not terminal['remaining'] and terminal.get('coordinator_error')is None and terminal['automatic_retry']is False and terminal['semantic_pass']is False,'original process/owned drain failure')
    processes=[p for p in logs if p.name=='original-process.json'];need(len(processes)==1,'original process record absent')
    process=json.loads(processes[0].read_text());need(process['PID']>0 and process['PGID']==process['PID'] and process['birth'],'original process identity incomplete')
    config_ref=result['config'];need(digest(config_ref['path'])==config_ref['sha256'],'original configuration changed')
    config_path=Path(config_ref['path']);config=json.loads(config_path.read_text());need(config['app']==app and process['argv']==config['argv'] and process['environment']==config['environment'],'actual argv/environment differs from reviewed original config')
    index_path=config_path.parent/'INDEX.json';need(digest(index_path)==result['launch_index_sha256'],'original reviewed launch INDEX changed')
    index=json.loads(index_path.read_text());need(index['app']==app,'launch INDEX selects another app')
    need(any(row['path']=='config.json'and row['sha256']==config_ref['sha256']for row in index['files']),'original configuration is not in approved launch INDEX')
    argv=process['argv'];env=process['environment']
    def arg(name):need(argv.count(name)==1,'duplicate/missing actual '+name);return argv[argv.index(name)+1]
    need(arg('-m')=='3072M' and Path(arg('-dtb')).resolve()==Path(dtb).resolve(),'wrong original RAM/DTB argv')
    need(arg('-serial')=='file:'+str(Path(serial).resolve()),'wrong original serial argv')
    need(Path(argv[0]).resolve()==Path(config['paths']['qemu']).resolve() and all(Path(arg(flag)).resolve()==Path(config['paths'][key]).resolve()for flag,key in[('-kernel','kernel'),('-initrd','initramfs')]),'original executable/kernel/initramfs argv differs from approved config')
    need(arg('-append')=='console=ttyAMA0 cma=1536M@0x80000000 rdinit=/init opengpu.qual='+app,'wrong original selected app/boot argv')
    need(env.get('ARTI_NATIVE_MEMORY_PROOF')==str(Path(proof).resolve()) and env.get('ARTI_NATIVE_RAM_BASE')=='0x40000000' and env.get('ARTI_NATIVE_RAM_END')=='0x100000000','observer env does not bind actual proof/RAM')
    need(env.get('ARTI_NATIVE_REQUIRE_TWO_CUS')==('1'if app=='h7'else None),'wrong workload-specific two-CU observer gate')
    authority=result['actual_dtb'];need(Path(authority['path']).resolve()==Path(dtb).resolve() and digest(dtb)==authority['sha256'],'actual DTB changed/mismatched')
    need(Path(result['proof_directory']).resolve()==Path(proof).resolve(),'wrong actual proof directory')
    manifest_ref=result['proof_manifest'];need(digest(manifest_ref['path'])==manifest_ref['sha256'],'proof manifest changed')
    manifest=json.loads(Path(manifest_ref['path']).read_text());need(Path(manifest['directory']).resolve()==Path(proof).resolve(),'proof manifest labels another run')
    paths=set()
    for row in manifest['files']:
        p=Path(row['path']).resolve();need(p.is_relative_to(Path(proof).resolve()) and p not in paths,'proof path escape/duplicate')
        need(p.stat().st_size==row['bytes'] and digest(p)==row['sha256'],'actual proof bytes changed');paths.add(p)
    need(paths=={p.resolve()for p in Path(proof).rglob('*')if p.is_file()},'actual proof membership changed')
    need(len(paths)==manifest_ref['files'],'proof file count differs')
    approval=result['root_approval'];need(digest(approval['path'])==approval['sha256'],'original root action approval changed')
    authority=json.loads(Path(approval['path']).read_text());need(authority['action']=='ONE_RESOURCE_GUEST_BOOT_NO_BUILD' and authority['app']==app and authority['index_sha256']==result['launch_index_sha256'] and all(authority[k]==config[k]for k in('outer_max_seconds','owned_RSS_cap_bytes')),'root approval does not authorize this original app/INDEX/budget')
    return dict(originalPid=process['PID'],originalBirth=process['birth'],launchIndexSha256=result['launch_index_sha256'],immutableLogs=len(logs),proofFiles=len(paths),actualArgvAndObserverBoundsBound=True)
def check_static_dtb(dtb,dtc,guest_profile_path):
    spec=importlib.util.spec_from_file_location('qualification_guest_profile',guest_profile_path)
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    decoded=subprocess.check_output([str(dtc),'-I','dtb','-O','dts',str(dtb)],text=True)
    return module.check_dtb(decoded)
def check_boot(serial_text,app):
    need(app in ('h7','h8'),'one app required')
    lines=serial_text.replace('\r','').splitlines()
    cmd=[s.split('Kernel command line:',1)[1].strip()for s in lines if 'Kernel command line:'in s]
    need(len(cmd)==1,'one actual kernel command line required')
    tokens=cmd[0].split()
    need([s for s in tokens if s.startswith('opengpu.qual=')]==['opengpu.qual='+app],'wrong/duplicate actual selected app')
    need([s for s in tokens if s.startswith('cma=')]==['cma=1536M@0x80000000'],'wrong actual CMA argument')
    need([s for s in tokens if s.startswith('rdinit=')]==['rdinit=/init'],'wrong actual init')
    reservations=re.findall(r'cma: Reserved\s+(\d+)\s+MiB at\s+(0x[0-9a-fA-F]+)',serial_text)
    need(reservations==[('1536','0x0000000080000000')] or (len(reservations)==1 and int(reservations[0][0])==1536 and int(reservations[0][1],16)==CMA[0]),'missing/wrong real CMA reservation')
    started=re.findall(r'^OPENGPU_QUAL_INIT app=(\w+) ramBase=(0x\w+) ramEnd=(0x\w+) pagemap=1 started$',serial_text.replace('\r',''),re.M)
    need(started==[(app,'0x40000000','0x100000000')],'missing/wrong init start')
    terminal=re.findall(r'^OPENGPU_QUAL_INIT app=(\w+) status=(\w+)$',serial_text.replace('\r',''),re.M)
    need(terminal==[(app,'PASS')],'selected init failed/missing/mixed')
    need(not re.search(r'Kernel panic|Oops:|BUG:|Unable to handle kernel|Out of memory: Killed process',serial_text),'kernel terminal failure')
    return dict(app=app,actualKernelCmdline=cmd[0],actualCmaBase=CMA[0],actualCmaBytes=CMA[1]-CMA[0])
def check_native(stderr_text,require_two_cus=False):
    pairs=re.findall(r'^ARTI_NATIVE_GCR1 offset=(0x[0-9a-f]+) actual=(0x[0-9a-f]+) status=(-?\d+)$',stderr_text,re.M)
    expected={0x360:0x47435201,0x36c:2,0x370:16,0x374:8,0x378:128,0x37c:128,0x384:16384,0x394:32}
    need(len(pairs)==8 and {int(a,16):int(v,16)for a,v,s in pairs}==expected and all(int(s)==0 for a,v,s in pairs),'actual GCR1 profile mismatch')
    flags=re.findall(r'^ARTI_NATIVE_GCR1 flags=(0x[0-9a-f]+) fullServices=(\w+)$',stderr_text,re.M)
    need(len(flags)==1 and flags[0][1]=='true' and int(flags[0][0],16)&0x7f0==0x7f0,'full services absent')
    peak=re.findall(r'^ARTI_NATIVE_COMPUTE_OBSERVER actualProfile=(\w+) peakActiveCUs=(\d+) status=(\w+)$',stderr_text,re.M)
    if require_two_cus:need(peak==[('true','2','PASS')],'required actual global two-CU peak absent')
    elif peak:need(len(peak)==1 and peak[0][0]=='true' and peak[0][2]=='PASS','native profile observer failure')
    need('ARTI_NATIVE_MEMORY_OBSERVER status=FAIL'not in stderr_text,'memory observer failed')
    need(len(re.findall(r'^ARTI_NATIVE_MEMORY_OBSERVER status=COMPLETE_DRAINED ',stderr_text,re.M))==1,'missing unique observer drained stderr marker')
    return dict(actualProfile=True,fullServices=True,actualPhysicalCus=2,peakActiveCUs=int(peak[0][1])if peak else None,twoCuPeakRequired=require_two_cus)

def valid_leaf(p):
    return bool(p&1 and p&(2|8) and not(p&4 and not p&2) and not p>>30 and (p>>8&3)<=2 and p&64 and (not p&4 or p&128))
def words(data):need(len(data)==4096,'wrong table size');return struct.unpack('<1024I',data)
@dataclass
class Snapshot:
    ordinal:int
    staging:dict
    root:tuple
    tables:dict
    table_phys:dict
    satp:int
    submit_cycle:int=0
    b_cycle:int=0
    pop_cycle:int=0
    completion:dict=field(default_factory=dict)
    def leaf(self,va):
        need(0<=va<1<<32,'VA overflow')
        region=va>>22;link=self.root[region]
        need(link&0x3ff==1 and not link>>30,'missing/malformed root link')
        need(region in self.tables and self.table_phys[region]==(link>>10)<<12,'missing actual table')
        pte=self.tables[region][va>>12&1023];need(valid_leaf(pte),'unmapped/malformed leaf')
        return (((pte>>10)&0xfffff)<<12)|(va&4095),pte
    def translate(self,va,write=False):
        pa,pte=self.leaf(va);need(pte&(4 if write else 2),'PTE permission denied');return pa

def load_snapshot(directory,n):
    directory=Path(directory);meta=json.loads((directory/f'submit-{n}.json').read_text())
    need(meta['ordinal']==n and meta['status']=='PRE_SUBMIT_SNAPSHOT_REQUIRES_LATER_B_AND_TERMINAL','snapshot ordinal/status')
    satp=meta['vector_satp'];need(satp==meta['instruction_satp'] and satp>>31 and satp>>22&511 and not satp&(3<<20),'private SATP identity')
    root=words((directory/f'submit-{n}-root.bin').read_bytes());owned={(satp&0xfffff)<<12};span(next(iter(owned)),4096)
    need(len(meta['tables'])<=384,'per-VM table capacity exceeded')
    tables={};physical={}
    for row in meta['tables']:
        region,pa=row['region'],row['physical'];need(region not in tables and pa not in owned,'duplicate/cyclic page table');span(pa,4096);owned.add(pa)
        need(root[region]&0x3ff==1 and root[region]>>10<<12==pa,'root/table PA mismatch')
        tables[region]=words((directory/f'submit-{n}-l1-{region}.bin').read_bytes());physical[region]=pa
    need({i for i,p in enumerate(root)if p}==set(tables),'missing/extra full root links')
    for table in tables.values():
        for p in table:
            if p:
                need(valid_leaf(p),'invalid actual leaf');pa=(p>>10&0xfffff)<<12;span(pa,4096);need(pa not in owned,'shader leaf aliases table page')
    return Snapshot(n,{int(a):int(v)for a,v in meta['staging']},root,tables,physical,satp)

def beat(a,n):
    size=1<<a['size'];base=a['address'] if not a['burst']or n==0 else (a['address']&~(size-1))+n*size
    return base,size-(base&(size-1))
def check_observer(proof_dir):
    """Replay accepted channels, successful R/B and snapshot->SUBMIT->POP joins.

    Returns snapshots and actual accepted AR/AW bursts with response validation.
    Snapshot .completion comes from real MMIO completion reads, not app claims.
    """
    proof_dir=Path(proof_dir);reads=defaultdict(deque);aw=deque();wd=deque();br=defaultdict(deque);ma=deque();mw=deque();mb=deque();mr=deque()
    stage={};submits=deque();snapshots=[];transactions=[];counts=Counter();last_cycle=0;config=None;terminal=None;completion=None
    with(proof_dir/'events.jsonl').open()as f:
        for raw in f:
            e=json.loads(raw);kind=e['kind']
            need(terminal is None,'event after terminal')
            if kind=='configuration':need(config is None and not counts,'configuration must be first');config=e;need((e['ram_base'],e['ram_end'])==RAM and e['table_limit']==384,'observer actual RAM/table config');continue
            need(config is not None,'configuration absent')
            if kind=='terminal':terminal=e;continue
            cycle=e['cycle'];need(cycle>=last_cycle,'event cycle regression');last_cycle=cycle
            if kind in('lower_ar','lower_aw'):
                need(0<=e['size']<=5 and 0<=e['length']<=255 and e['burst']in(0,1),'AXI shape')
                for b in range(e['length']+1):span(*beat(e,b))
                a=dict(e,beat=0,finished_cycle=None,response_cycle=None,strobed_ranges=[]);transactions.append(a)
                if kind=='lower_ar':reads[e['id']].append(a);counts['ar']+=1
                else:aw.append(a);counts['aw']+=1
            elif kind=='lower_r':
                q=reads[e['reply_id']];need(q and e['response']==0,'unsolicited/error R');a=q[0]
                need(all(e[k]==a[k]for k in('address','id','length','size','burst')),'R address/ID differs from accepted AR')
                need(bool(e['last'])==(a['beat']==a['length']),'RLAST');counts['r']+=1;counts['dut_read_bytes']+=beat(a,a['beat'])[1];a['beat']+=1
                if a['beat']>a['length']:a['finished_cycle']=cycle;a['response_cycle']=cycle;q.popleft()
            elif kind=='lower_w':wd.append(e);counts['w']+=1
            elif kind=='lower_b':
                q=br[e['reply_id']];need(q and e['response']==0,'unsolicited/error B');q.popleft()['response_cycle']=cycle;counts['b']+=1
            elif kind=='mmio_aw':ma.append(e)
            elif kind=='mmio_w':mw.append(e)
            elif kind=='mmio_write':
                need(ma and mw,'MMIO write not from accepted AW/W');a,w=ma.popleft(),mw.popleft()
                need(e['address']==a['address'] and e['write_word']==w['write_word'] and w['strobe']==15 and w['write_last'],'MMIO AW/W pairing')
                address,value=e['address'],e['write_word'];stage[address]=value;counts['mmio_writes']+=1;item={'address':address,'submit':None};mb.append(item)
                if address==0x11c and value&1:
                    op=stage.get(0xc8,0);submit={'id':stage.get(0xc4,0)&255,'opcode':op,'cycle':cycle,'snapshot':None};submits.append(submit)
                    satp=stage.get(0x14c,0)
                    if op in(0,14):
                        if satp>>22&511:
                            s=load_snapshot(proof_dir,len(snapshots)+1);need(s.staging==stage,'snapshot staging differs from accepted MMIO');s.submit_cycle=cycle
                            snapshots.append(s);submit['snapshot']=s;item['submit']=s;counts['private_submits']+=1
                        else:counts['global_submits']+=1
                if address==0x130 and value&1:
                    need(submits and completion is not None,'POP without command/completion read');s=submits.popleft();word=completion
                    actual={'id':word&255,'opcode':(word>>8&7)|(word>>16&1)<<3,'status':word>>11&15,'success':bool(word>>15&1),'word':word}
                    need(actual['id']==s['id'] and actual['opcode']==s['opcode'],'completion actual ID/opcode mismatch')
                    if s['snapshot']:s['snapshot'].pop_cycle=cycle;s['snapshot'].completion=actual
                    completion=None;counts['pops']+=1
            elif kind=='mmio_b':
                need(mb and e['response']==0,'unsolicited/error MMIO B');item=mb.popleft();need(e['address']==item['address'],'MMIO B order')
                if item['submit']:item['submit'].b_cycle=cycle
                counts['mmio_b']+=1
            elif kind=='mmio_ar':mr.append(e);counts['mmio_reads']+=1
            elif kind=='mmio_r':
                need(mr and e['response']==0 and e['last'],'unsolicited/error MMIO R');a=mr.popleft();need(e['address']==a['address'],'MMIO R order');counts['mmio_r']+=1
                if e['address']==0x124:completion=e['reply_word']
            else:raise ValueError('unknown observer event '+kind)
            while aw and wd:
                a,w=aw[0],wd.popleft();pa,size=beat(a,a['beat']);lane=pa&31;mask=((1<<size)-1)<<lane
                need(not w['strobe']&~mask and bool(w['write_last'])==(a['beat']==a['length']),'WSTRB/WLAST');counts['dut_write_bytes']+=w['strobe'].bit_count();a['beat']+=1
                for bit in range(32):
                    if w['strobe']&(1<<bit):
                        byte=(pa&~31)+bit
                        if a['strobed_ranges'] and a['strobed_ranges'][-1][1]==byte:a['strobed_ranges'][-1][1]+=1
                        else:a['strobed_ranges'].append([byte,byte+1])
                if a['beat']>a['length']:a['finished_cycle']=cycle;br[a['id']].append(a);aw.popleft()
    need(terminal and terminal['status']=='COMPLETE_DRAINED','missing COMPLETE_DRAINED')
    need(not any(reads.values())and not aw and not wd and not any(br.values())and not ma and not mw and not mb and not mr and not submits,'outstanding accepted work at terminal')
    for key in ('ar','r','aw','w','b','dut_read_bytes','dut_write_bytes','mmio_writes','mmio_b','mmio_reads','mmio_r','private_submits','global_submits','pops'):
        need(terminal[key]==counts[key],'observer terminal count mismatch '+key)
    need(len(snapshots)==terminal['private_submits'] and all(s.b_cycle and s.pop_cycle and s.completion for s in snapshots),'unconfirmed snapshot B/POP')
    return dict(snapshots=snapshots,transactions=transactions,terminal=terminal,counts=dict(counts),scope='accepted addresses and word0 only; no complete AXI payload reconstruction')

def parse_pfn_groups(text):
    groups=[];current=[]
    pattern=r'^OPENGPU_QUAL_PFN kind=([\w-]+) binding=(\d+) handle=(\d+) page=(\d+) pages=(\d+) physical=(0x[0-9a-fA-F]+)$'
    for line in text.replace('\r','').splitlines():
        m=re.fullmatch(pattern,line)
        if m:current.append(dict(kind=m[1],binding=int(m[2]),handle=int(m[3]),page=int(m[4]),pages=int(m[5]),physical=int(m[6],16)))
        elif line.startswith(('BUFFER_GLSL_PAGEMAP ','OPENGPU_QUAL_LAYOUT ')):
            need(current,'empty PFN group');groups.append(current);current=[]
    need(not current,'partial PFN records without completed layout marker');return groups

def pfn_objects(records):
    objects={}
    for r in records:objects.setdefault((r['kind'],r['binding']),[]).append(r)
    for key,runs in objects.items():
        page=0;handle=runs[0]['handle']
        for r in runs:
            need(r['handle']==handle and r['page']==page and r['pages']>0 and not r['physical']&4095,'PFN identity/hole/overlap');span(r['physical'],r['pages']*4096,CMA);page+=r['pages']
    return objects

def join_view(snapshot,va,byte_count,runs,write=False):
    need(not va&4095 and not byte_count&4095,'full-page view expected');pages=0
    for r in runs:
        for p in range(r['pages']):
            need(snapshot.translate(va+(r['page']+p)*4096,write)==r['physical']+p*4096,'actual PTE/public PFN mismatch');pages+=1
    need(pages*4096==byte_count,'public allocation/view size mismatch');return pages

def private_uniform(snapshot,public_runs,expected_bytes=204800):
    st=snapshot.staging;need(st.get(0x440)==0x101,'uniform command disabled/version')
    root=st.get(0x444,0);need(0xb1800000<=root and root+expected_bytes<=0xb1c00000 and not root&63,'actual uniform root outside slot6')
    private_base=st.get(0x454,0);private_end=st.get(0x458,0)|(st.get(0x45c,0)<<32)
    need(0<private_end<=1<<32 and private_base<private_end and not(root<private_end and root+expected_bytes>private_base),'uniform snapshot overlaps kernarg private authority')
    need(private_base==st.get(0xd0),'GUM1 private base differs from actual kernarg')
    need(st.get(0x448,0)>0,'missing actual uniform context identity')
    public_intervals=[(r['physical'],r['physical']+r['pages']*4096)for r in public_runs]
    begin=root&~4095;end=(root+expected_bytes+4095)&~4095;pages=set()
    for va in range(begin,end,4096):
        pa,pte=snapshot.leaf(va);span(pa,4096,CMA)
        need(pte&0xff==0x43 and pte>>8&3==2,'slot6 must be private R-only non-X non-global uncached PTE')
        need(not any(a<pa+4096 and pa<b for a,b in public_intervals),'private uniform page aliases a public allocation')
        pages.add(pa)
    need(len(pages)==(end-begin)//4096,'private uniform pages alias each other')
    for table in snapshot.tables.values():
        for pte in table:
            if pte and ((pte>>10)&0xfffff)<<12 in pages:
                need(not pte&(4|8),'private uniform backing has another writable/executable VA alias')
    return dict(root=root,bytes=expected_bytes,pages=pages,context=st[0x448],passId=st.get(0x44c,0),generation=st.get(0x450,0),privateBase=private_base,privateLimit=private_end)

def uniform_traffic(snapshot,uniform,transactions):
    pages=uniform['pages'];read_pages=set();reads=0
    for t in transactions:
        if snapshot.submit_cycle<=t['cycle']<=snapshot.pop_cycle:
            hit={beat(t,n)[0]&~4095 for n in range(t['length']+1)}&pages
            if hit:
                need(t['response_cycle'] is not None and t['response_cycle']<=snapshot.pop_cycle,'uniform accepted access has no successful response before POP')
                need(t['kind']=='lower_ar','GPU attempted write to private readonly uniform snapshot');read_pages|=hit;reads+=1
    need(reads>0,'no actual successful AR/R to private uniform snapshot')
    return dict(actualAcceptedReadBursts=reads,actualReadPages=len(read_pages),descriptorAndPayloadReconstructed=False)

def public_traffic(snapshot,runs,transactions,read=False,write=False,require_tail=False,head_offset=0):
    """Attribute actual AR/R or strobed AW/W/B to this complete public BO.

    Require only workload-specific authored endpoints. Healthy H7/H8 shaders
    explicitly touch the last word; a checked CPU guard alone proves no GPU read.
    head_offset preserves the actual image0 epoch2 checked 64-byte subview.
    """
    first=runs[0]['physical'];last=runs[-1]['physical']+runs[-1]['pages']*4096
    need(0<=head_offset<=4092 and not head_offset&3,'head endpoint must fit first public page')
    regions={'head':(first+head_offset,first+head_offset+4),'tail':(last-4,last)};hits={'read':Counter(),'write':Counter()}
    for t in transactions:
        if not(snapshot.submit_cycle<=t['cycle']<=snapshot.pop_cycle):continue
        direction='read'if t['kind']=='lower_ar'else'write'
        spans=[(p,p+n)for p,n in(beat(t,i)for i in range(t['length']+1))]if direction=='read'else t['strobed_ranges']
        for name,(lo,hi)in regions.items():
            if any(a<=lo and hi<=b for a,b in spans):
                need(t['response_cycle'] is not None and t['response_cycle']<=snapshot.pop_cycle,'public access response missing after accepted address/data')
                hits[direction][name]+=1
    for direction,required in[('read',read),('write',write)]:
        if required:need(hits[direction]['head']>0 and(not require_tail or hits[direction]['tail']>0),'required actual public '+direction+' head/tail absent')
    return {d:dict(v)for d,v in hits.items()}
