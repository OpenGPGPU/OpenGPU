#!/usr/bin/env python3
"""Seal/check/execute one reviewed ResourceGuest boot; never builds or retries.

The process-group supervisor is also used by the private kernel relink payload.
Preparation records explicit caller inputs, not a historical model/module pin.
"""
import argparse
from collections import Counter
import hashlib, importlib.util, json, os, re, signal, subprocess, sys, time
from pathlib import Path

RUNNER = Path(__file__).resolve()
sys.dont_write_bytecode = True
def sha(p):
    digest=hashlib.sha256()
    with Path(p).open('rb')as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()

def need(ok,why):
    if not ok:raise RuntimeError(why)

def record(directory,name,value):
    path=directory/name;temporary=directory/(name+'.tmp')
    temporary.write_text(json.dumps(value,indent=2)+'\n');temporary.replace(path)

def process_snapshot():
    rows=[]
    for line in subprocess.check_output(['/bin/ps','-axo','pid=,ppid=,pgid=,lstart=,rss=,comm='],text=True,env=dict(os.environ,TZ='UTC',LC_ALL='C',LANG='C')).splitlines():
        c=line.split(None,9)
        if len(c)!=10:continue
        try:rows.append(dict(pid=int(c[0]),ppid=int(c[1]),pgid=int(c[2]),birth=' '.join(c[3:8]),rss=int(c[8])*1024,comm=c[9]))
        except ValueError:pass
    return rows

def input_changes(c):
    changes=[]
    for row in c['files']:
        p=Path(row['path'])
        if not p.is_file() or str(p.resolve())!=row['realpath'] or p.stat().st_size!=row['bytes'] or p.stat().st_mode&0o7777!=row['mode'] or sha(p)!=row['sha256']:changes.append(row['path'])
    for directory,names in c['directory_members'].items():
        if sorted(str(p.relative_to(directory))for p in Path(directory).rglob('*')if p.is_file())!=names:changes.append('directory:'+directory)
    return changes

def actual_argv(c):
    p=c['paths'];out=Path(c['output'])
    return [p['qemu'],'-machine','virt','-accel','tcg,thread=single','-cpu','cortex-a53','-smp','1','-m','3072M',
        '-L',p['firmware'],'-display','none','-monitor','none','-serial','file:'+str(out/'serial.log'),
        '-kernel',p['kernel'],'-initrd',p['initramfs'],'-dtb',p['dtb'],'-no-reboot',
        '-append','console=ttyAMA0 cma=1536M@0x80000000 rdinit=/init opengpu.qual='+c['app']]

def actual_environment(c):
    env=dict(PATH='/usr/bin:/bin:/usr/sbin:/sbin',LANG='C',LC_ALL='C',TZ='UTC',
        ARTI_NATIVE_MEMORY_PROOF=str(Path(c['output'])/'proof'),ARTI_NATIVE_RAM_BASE='0x40000000',ARTI_NATIVE_RAM_END='0x100000000',
        OMP_NUM_THREADS='1')
    if c['app']=='h7':env['ARTI_NATIVE_REQUIRE_TWO_CUS']='1'
    return env

def prepare(draft, packet):
    need(not packet.exists(), 'choose a fresh launch packet')
    c=json.loads(draft.read_text())
    need(c['app']in ('h7','h8'), 'one explicit app required')
    c['paths']={name:str(Path(value).resolve(strict=True))for name,value in c['paths'].items()}
    for name in ('qemu','kernel','initramfs','dtb','dtc','guest_profile','firmware'):
        need(name in c['paths'], 'missing explicit input: '+name)
    c['output']=str(Path(c['output']).resolve());c['shared_model_lock']=str(Path(c['shared_model_lock']).resolve())
    need(not Path(c['output']).exists(), 'output already exists')
    c.update(model_count=1,guest_cpu_count=1,native_thread_count=1,automatic_retries=0)
    need(0<c['outer_max_seconds']<=1800 and 0<c['owned_RSS_cap_bytes']<=7*1024**3, 'guest budget exceeds 1800s/7GiB')
    directories={Path(c['paths']['firmware'])}|{Path(p).resolve(strict=True)for p in c.pop('input_directories',[])}
    files={RUNNER,Path(sys.executable).resolve()}|{Path(p).resolve(strict=True)for p in c.pop('input_files',[])}
    for value in c['paths'].values():
        path=Path(value)
        if path.is_file():files.add(path)
        else:need(path in directories, 'non-file input must be an explicit directory')
    c['directory_members']={}
    for path in sorted(directories):
        need(path.is_dir() and not packet.is_relative_to(path) and not Path(c['output']).is_relative_to(path), 'output overlaps frozen input directory')
        members=sorted(p for p in path.rglob('*')if p.is_file())
        c['directory_members'][str(path)]=[str(p.relative_to(path))for p in members];files.update(members)
    c['files']=[dict(path=str(p),realpath=str(p.resolve()),bytes=p.stat().st_size,mode=p.stat().st_mode&0o7777,sha256=sha(p))for p in sorted(files)]
    c['argv']=actual_argv(c);c['environment']=actual_environment(c)
    packet.mkdir(parents=True)
    record(packet,'config.json',c)
    record(packet,'INDEX.json',dict(app=c['app'],files=[dict(path='config.json',sha256=sha(packet/'config.json'))],runner=dict(path=str(RUNNER),sha256=sha(RUNNER))))
    print(json.dumps(dict(status='SEALED_UNARMED',packet=str(packet),index_sha256=sha(packet/'INDEX.json'),argv=c['argv'],environment=c['environment']),indent=2))

def checked(packet,index_sha):
    need(sha(packet/'INDEX.json')==index_sha, 'exact reviewed launch INDEX differs')
    index=json.loads((packet/'INDEX.json').read_text())
    need(index['runner']==dict(path=str(RUNNER),sha256=sha(RUNNER)), 'reviewed supervisor source differs')
    need(index['files']==[dict(path='config.json',sha256=sha(packet/'config.json'))], 'sealed launch config changed/missing')
    c=json.loads((packet/'config.json').read_text())
    need(c['app']in ('h7','h8')and index['app']==c['app'], 'wrong selected app')
    need(c['model_count']==c['guest_cpu_count']==c['native_thread_count']==1 and c['automatic_retries']==0, 'resource/count contract differs')
    need(0<c['outer_max_seconds']<=1800 and 0<c['owned_RSS_cap_bytes']<=7*1024**3, 'guest budget exceeds 1800s/7GiB')
    need(not input_changes(c), 'frozen launch inputs changed')
    pinned={row['path']for row in c['files']}
    need(len(pinned)==len(c['files']) and {str(RUNNER),str(Path(sys.executable).resolve())}<=pinned, 'duplicate/missing tool pins')
    need(all(value in pinned for value in c['paths'].values()if Path(value).is_file()), 'launch file is not pinned')
    firmware=c['paths']['firmware']
    need(firmware in c['directory_members'] and all(str(Path(firmware)/name)in pinned for name in c['directory_members'][firmware]), 'firmware membership is unpinned')
    need(c['argv']==actual_argv(c) and c['environment']==actual_environment(c), 'exact QEMU argv/environment differs')
    need(not Path(c['output']).exists() and not(packet/'CLAIM.json').exists(), 'single guest attempt already claimed/output present')
    need(not Path(c['shared_model_lock']).exists(), 'shared model is claimed; no overlapping guests')
    spec=importlib.util.spec_from_file_location('actual_guest_profile',c['paths']['guest_profile'])
    need(spec is not None and spec.loader is not None,'guest profile must be a Python source module')
    profile=importlib.util.module_from_spec(spec);spec.loader.exec_module(profile)
    decoded=subprocess.check_output([c['paths']['dtc'],'-I','dtb','-O','dts',c['paths']['dtb']],text=True,stderr=subprocess.PIPE)
    profile.check_dtb(decoded)
    return c

def logical_bytes(directory):
    total=0
    for path in Path(directory).rglob('*'):
        try:
            if path.is_file():total+=path.stat().st_size
        except FileNotFoundError:pass  # Linker temporary files may disappear.
    return total

class Progress:
    """Observations never turn an idle interval into a hang or success verdict."""
    def __init__(self,directory):
        self.directory=directory;self.offset=0;self.partial=b'';self.counts=Counter();self.submits=0;self.pops=0;self.terminal=None
    def observe(self):
        events=self.directory/'proof/events.jsonl'
        if events.is_file():
            with events.open('rb')as f:
                f.seek(self.offset);chunk=f.read(16*1024*1024);self.offset=f.tell()
            parts=(self.partial+chunk).split(b'\n');self.partial=parts.pop()
            for line in parts:
                m=re.search(rb'"kind"\s*:\s*"([^"\\]+)"',line)
                if not m:continue
                kind=m[1].decode('ascii');self.counts[kind]+=1
                if kind=='mmio_write':
                    event=json.loads(line)
                    if event.get('address')==0x11c and event.get('write_word',0)&1:self.submits+=1
                    if event.get('address')==0x130 and event.get('write_word',0)&1:self.pops+=1
                elif kind=='terminal':self.terminal=json.loads(line)
        files={}
        for name in ('serial.log','stdout.log','stderr.log','proof/events.jsonl'):
            path=self.directory/name
            if path.is_file():
                s=path.stat();files[name]=dict(bytes=s.st_size,mtime_ns=s.st_mtime_ns)
        serial=self.directory/'serial.log';tail=''
        if serial.is_file():
            with serial.open('rb')as f:f.seek(max(0,serial.stat().st_size-4096));tail=f.read().decode('utf-8','replace')
        return dict(files=files,serial_tail=tail,accepted_event_counts=dict(self.counts),accepted_submits=self.submits,accepted_pops=self.pops,
            event_bytes_consumed=self.offset,event_bytes_not_yet_counted=max(0,events.stat().st_size-self.offset)if events.is_file()else 0,
            private_snapshot_files=len(list((self.directory/'proof').glob('submit-*.json'))),observer_terminal_seen=self.terminal,
            quiet_intervals_never_imply_hang=True,progress_is_not_semantic_pass=True)

def supervise(c):
    out=Path(c['output']);out.mkdir();known={};peak=0;stop=None;stop_at=None;requested=[];samples=0
    start=time.monotonic();progress=Progress(out);last_progress=-10;code=None;child=None;group=None;remaining=[];error=None
    previous_handlers={s:signal.getsignal(s)for s in (signal.SIGINT,signal.SIGTERM)}
    for s in previous_handlers:signal.signal(s,lambda n,_:requested.append(n))
    try:
        with (out/'stdout.log').open('wb')as stdout,(out/'stderr.log').open('wb')as stderr,(out/'owned-samples.jsonl').open('w')as samplelog,(out/'progress.jsonl').open('w')as progresslog:
            child=subprocess.Popen(c['argv'],env=c['environment'],cwd=out,stdout=stdout,stderr=stderr,start_new_session=True)
            group=child.pid;rows=process_snapshot();leader=next((r for r in rows if r['pid']==child.pid),None)
            need(leader is not None and leader['pgid']==child.pid,'original process identity unavailable')
            known[leader['pid']]=leader['birth']
            record(out,'original-process.json',dict(coordinator=os.getpid(),PID=child.pid,PGID=group,birth=leader['birth']if leader else None,argv=c['argv'],environment=c['environment']))
            print('RESOURCE_GUEST_ORIGINAL_START',json.dumps(dict(PID=child.pid,PGID=group,birth=leader['birth']if leader else None,app=c['app'])),flush=True)
            while True:
                rows=process_snapshot();code=child.poll();elapsed=time.monotonic()-start
                members=[r for r in rows if r['pgid']==group and not(code is not None and r['pid']==child.pid)]
                anchored=any(known.get(r['pid'])==r['birth']for r in members)
                # ps was captured before poll reaped the original leader. Its
                # observed birth/PGID anchors all same-snapshot descendants,
                # including a child first seen as its parent exits.
                leader_observed=any(r['pid']==child.pid and r['pgid']==group and known.get(r['pid'])==r['birth']for r in rows)
                if code is None or anchored or leader_observed:
                    for r in members:known[r['pid']]=r['birth']
                owned=[r for r in members if known.get(r['pid'])==r['birth']]
                rss=sum(r['rss']for r in owned)+next((r['rss']for r in rows if r['pid']==os.getpid()),0);peak=max(peak,rss);samples+=1
                samplelog.write(json.dumps(dict(elapsed_s=elapsed,rss_bytes=rss,owned=owned))+'\n');samplelog.flush()
                if stop is None:
                    if requested:stop='coordinator_signal_'+str(requested[0])
                    elif rss>=c['owned_RSS_cap_bytes']:stop='owned_RSS_cap'
                    elif elapsed>c['outer_max_seconds']:stop='outer_wall_timeout'
                    elif c.get('logical_output_directory') and logical_bytes(c['logical_output_directory'])>c['logical_output_cap_bytes']:stop='logical_output_cap'
                    elif code is not None and members:stop='unexpected_descendants_after_original_leader'
                    if stop:stop_at=time.monotonic()
                if elapsed-last_progress>=5 or code is not None:
                    try:state=progress.observe()
                    except Exception as e:state=dict(progress_observation_error=repr(e),progress_is_not_semantic_pass=True)
                    state.update(elapsed_s=elapsed,owned_RSS_bytes=rss,original_exit_code=code,stop=stop)
                    record(out,'progress-latest.json',state);progresslog.write(json.dumps(state)+'\n');progresslog.flush();last_progress=elapsed
                if stop:
                    live=next((r for r in process_snapshot()if r['pgid']==group and known.get(r['pid'])==r['birth']),None)
                    if live:
                        try:os.killpg(group,signal.SIGKILL if time.monotonic()-stop_at>=10 else signal.SIGTERM)
                        except ProcessLookupError:pass
                if code is not None and not members:break
                if stop and time.monotonic()-stop_at>=20:break
                time.sleep(.5)
            code=child.poll();remaining=[r for r in process_snapshot()if r['pgid']==group]
    except BaseException as e:
        error=repr(e);stop=stop or 'coordinator_exception'
        if child is not None:
            deadline=time.monotonic()+20
            while True:
                code=child.poll();rows=process_snapshot();members=[r for r in rows if r['pgid']==group]
                pinned=any(known.get(r['pid'])==r['birth']for r in members)
                if code is None or pinned:
                    try:os.killpg(group,signal.SIGKILL)
                    except ProcessLookupError:pass
                if(code is not None and not members)or time.monotonic()>=deadline:break
                time.sleep(.2)
            code=child.poll();remaining=[r for r in process_snapshot()if r['pgid']==group]
    finally:
        for s,handler in previous_handlers.items():signal.signal(s,handler)
    terminal=dict(exit_code=code,stop=stop,remaining=remaining,peak_owned_RSS=peak,elapsed_s=time.monotonic()-start,samples=samples,
        automatic_retry=False,semantic_pass=False,coordinator_error=error)
    record(out,'original-terminal.json',terminal)
    return terminal

def main():
    ap=argparse.ArgumentParser(description=__doc__);mode=ap.add_mutually_exclusive_group(required=True)
    mode.add_argument('--prepare',action='store_true');mode.add_argument('--check',action='store_true')
    mode.add_argument('--review',action='store_true',help='record the caller\'s explicit review of this exact INDEX; no boot')
    mode.add_argument('--run',action='store_true')
    ap.add_argument('--packet',type=Path,required=True);ap.add_argument('--config',type=Path)
    ap.add_argument('--approved-sha');ap.add_argument('--approval-file',type=Path);ap.add_argument('--approval-sha')
    a=ap.parse_args();packet=a.packet.resolve()
    if a.prepare:
        need(a.config is not None,'--prepare requires explicit --config');prepare(a.config,packet);return
    need(a.approved_sha is not None,'reviewed --approved-sha required');c=checked(packet,a.approved_sha)
    if a.check:print('RESOURCE_GUEST_LAUNCH_CHECK PASS; guest/model/compile UNRUN');return
    if a.review:
        path=a.approval_file or packet/'review.json'
        value=dict(action='ONE_RESOURCE_GUEST_BOOT_NO_BUILD',app=c['app'],index_sha256=a.approved_sha,
                   outer_max_seconds=c['outer_max_seconds'],owned_RSS_cap_bytes=c['owned_RSS_cap_bytes'])
        with path.open('x')as stream:json.dump(value,stream,indent=2);stream.write('\n')
        print(json.dumps(dict(status='CALLER_REVIEW_RECORDED_NOT_BOOTED',approval_file=str(path.resolve()),approval_sha=sha(path))))
        return
    need(a.approval_file and a.approval_sha and sha(a.approval_file)==a.approval_sha,'exact one-boot review record required')
    approval=json.loads(a.approval_file.read_text())
    need(approval['action']=='ONE_RESOURCE_GUEST_BOOT_NO_BUILD' and approval['index_sha256']==a.approved_sha and approval['app']==c['app'],'review differs from app/action/INDEX')
    need(all(approval[k]==c[k]for k in('outer_max_seconds','owned_RSS_cap_bytes')),'reviewed budget differs')
    me=next(r for r in process_snapshot()if r['pid']==os.getpid())
    claim=dict(coordinator=me['pid'],birth=me['birth'],index_sha256=a.approved_sha,approval_sha256=a.approval_sha,app=c['app'])
    lock=Path(c['shared_model_lock'])
    with lock.open('x')as f:json.dump(claim,f,indent=2)
    try:
        with(packet/'CLAIM.json').open('x')as f:json.dump(claim,f,indent=2)
    except BaseException:
        if lock.is_file()and json.loads(lock.read_text())==claim:lock.unlink()
        raise
    terminal=supervise(c);changes=input_changes(c)
    ok=terminal['exit_code']==0 and terminal['stop']is None and not terminal['remaining'] and not changes
    out=Path(c['output']);records=[]
    for relative in ('serial.log','stderr.log','stdout.log','original-process.json','original-terminal.json'):
        p=out/relative
        if p.is_file():records.append(dict(path=str(p),bytes=p.stat().st_size,sha256=sha(p)))
    proof=out/'proof';proof_rows=[dict(path=str(p),bytes=p.stat().st_size,sha256=sha(p))for p in sorted(proof.rglob('*'))if p.is_file()]if proof.is_dir()else []
    record(out,'proof-manifest.json',dict(directory=str(proof),files=proof_rows))
    released=False
    if terminal['exit_code']is not None and not terminal['remaining']and lock.is_file()and json.loads(lock.read_text())==claim:
        record(out,'shared-lock-released.json',dict(claim=claim,original_exit_code=terminal['exit_code'],owned_processes_remaining=[]));lock.unlink();released=True
    final=dict(status='EXITED_ZERO_UNVERIFIED'if ok else 'FAIL_ORIGINAL_GUEST_ATTEMPT',app=c['app'],original_terminal=terminal,changed_inputs=changes,
        launch_index_sha256=a.approved_sha,config=dict(path=str(packet/'config.json'),sha256=sha(packet/'config.json')),root_approval=dict(path=str(a.approval_file.resolve()),sha256=a.approval_sha),
        immutable_logs=records,proof_manifest=dict(path=str(out/'proof-manifest.json'),sha256=sha(out/'proof-manifest.json'),files=len(proof_rows)),shared_model_lock_released=released,
        proof_directory=str(proof),actual_dtb=dict(path=c['paths']['dtb'],sha256=sha(c['paths']['dtb'])),
        selected_app_and_boot_and_observer_and_PFN_PTE_AR_join_validation_required=True,semantic_pass=False,automatic_retry=False)
    record(out,'launch-result.json',final)
    print(json.dumps(dict(status=final['status'],app=c['app'],result=str(out/'launch-result.json'),semantic_pass=False)),flush=True)
    raise SystemExit(0 if ok else 1)

if __name__=='__main__':main()
