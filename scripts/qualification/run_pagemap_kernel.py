#!/usr/bin/env python3
"""Run one explicitly reviewed five-stage private-kernel plan; no guest/model."""
import argparse
import json
import os
from pathlib import Path
import shlex
import struct
import subprocess
import sys
import time
sys.dont_write_bytecode = True
import resource_guest_once as guard


def changes(plan):
    return [name for name,value in plan['input_sha256'].items()
            if not Path(name).is_file() or guard.sha(name)!=value]


def compare_globals(old_text,new_text):
    def inventory(text):
        pairs=[]
        for line in text.splitlines():
            if not line.strip():continue
            fields=line.split()
            guard.need(len(fields)>=2 and len(fields[1])==1,'unexpected nm POSIX output')
            pairs.append((fields[0],fields[1]))
        guard.need(pairs,'empty defined-global symbol inventory')
        return sorted(pairs)
    old,new=inventory(old_text),inventory(new_text)
    guard.need(old==new,'task_mmu.o defined-global names/types changed; thin archive index cannot be reused')
    return dict(defined_global_names_types_unchanged=True,count=len(new))


def task_globals(plan):
    argv=[plan['environment']['NM'],'--defined-only','--extern-only','--format=posix']
    old=subprocess.check_output(argv+[str(Path(plan['original_build'])/'fs/proc/task_mmu.o')],text=True)
    new=subprocess.check_output(argv+[str(Path(plan['cwd'])/'fs/proc/task_mmu.o')],text=True)
    result=compare_globals(old,new)
    guard.record(Path(plan['cwd']).parent,'actual-task-global-symbols.json',result)


def final_abi(plan):
    old,new=Path(plan['original_build']),Path(plan['cwd'])
    for relative in ('include/config/kernel.release','.config','.vmlinux.export.o'):
        guard.need((old/relative).read_bytes()==(new/relative).read_bytes(),'kernel release/config/export object changed: '+relative)
    symvers=old/'Module.symvers'
    guard.need(guard.sha(symvers)==plan['input_sha256'][str(symvers)],'cached Module.symvers ABI changed')
    return dict(kernel_release_unchanged=True,configuration_unchanged=True,
                cached_export_object_unchanged=True,cached_module_symvers_unchanged=True)


def mmd(plan):
    directory=Path(plan['cwd'])
    text=(directory/'fs/proc/.task_mmu.o.d').read_text().replace('\\\n',' ')
    paths=[(Path(name)if Path(name).is_absolute()else directory/name).resolve()
           for name in shlex.split(text.split(':',1)[1])]
    guard.need(paths and any(str(p).endswith('/source/fs/proc/task_mmu.c')for p in paths), 'new source missing from actual MMD')
    guard.need(all(str(p)in plan['input_sha256'] and guard.sha(p)==plan['input_sha256'][str(p)]for p in paths), 'actual C dependency is unpinned or changed')
    guard.record(directory.parent,'actual-MMD.json',dict(files=[str(p)for p in paths],all_pinned=True))


def main():
    ap=argparse.ArgumentParser(description=__doc__)
    mode=ap.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check',action='store_true');mode.add_argument('--run',action='store_true');mode.add_argument('--inner',action='store_true')
    ap.add_argument('--plan',type=Path,required=True);ap.add_argument('--approved-sha',required=True)
    args=ap.parse_args();path=args.plan.resolve();plan=json.loads(path.read_text());root=path.parent
    guard.need(guard.sha(path)==args.approved_sha,'reviewed kernel command plan changed')
    guard.need(plan['ordinary_C_objects']==1 and len(plan['stages'])==5,'expected one C object and original five stages')
    guard.need(not changes(plan),'original or private read-only kernel input changed')
    if args.check:print('PRIVATE_KERNEL_PLAN_CHECK PASS; compile/guest UNRUN');return
    if args.inner:
        claim=json.loads((root/'CPU-CLAIM.json').read_text())
        parent=next((r for r in guard.process_snapshot()if r['pid']==os.getppid()),None)
        guard.need(parent and parent['pid']==claim['coordinator']and parent['birth']==claim['birth'],'inner is not original owned child')
        with(root/'CPU-INNER-CLAIM.json').open('x')as stream:json.dump(dict(PID=os.getpid(),PPID=os.getppid()),stream)
        events=[]
        for ordinal,argv in enumerate(plan['stages']):
            guard.need(guard.logical_bytes(plan['cwd'])<=1536*1024**2,'private O logical size cap')
            begin=time.monotonic();code=subprocess.run(argv,cwd=plan['cwd'],env=plan['environment']).returncode
            events.append(dict(argv=argv,exit_code=code,elapsed_s=time.monotonic()-begin))
            guard.record(root,'actual-stages.json',events)
            guard.need(code==0,'kernel stage failed: '+str(ordinal))
            if ordinal==0:
                mmd(plan)
                task_globals(plan)  # Must pass before the original archive is linked.
        guard.need(not changes(plan),'read-only inputs changed during kernel build')
        private=Path(plan['cwd']);image=private/'arch/arm64/boot/Image'
        with(private/'vmlinux').open('rb')as stream:elf=stream.read(64)
        with image.open('rb')as stream:header=stream.read(64)
        guard.need(elf[:6]==b'\x7fELF\x02\x01'and struct.unpack_from('<H',elf,18)[0]==183 and header[56:60]==b'ARM\x64','new kernel format mismatch')
        symbols=subprocess.check_output([plan['environment']['NM'],'-n',str(private/'vmlinux')],text=True)
        guard.need(' qualification_pagemap_test_walk\n'in symbols and ' pagemap_ops\n'in symbols,'private observation hook missing')
        abi=final_abi(plan)
        guard.record(root,'actual-kernel.json',dict(private_observation_kernel_not_stock=True,Image=dict(path=str(image),bytes=image.stat().st_size,sha256=guard.sha(image)),abi=abi,guest_run=False))
        return
    me=next(r for r in guard.process_snapshot()if r['pid']==os.getpid())
    with(root/'CPU-CLAIM.json').open('x')as stream:json.dump(dict(coordinator=me['pid'],birth=me['birth'],plan_sha256=args.approved_sha),stream)
    terminal=guard.supervise(dict(app='PRIVATE_KERNEL_CPU_ONLY',output=str(root/'cpu-run'),
        argv=[sys.executable,str(Path(__file__).resolve()),'--inner','--plan',str(path),'--approved-sha',args.approved_sha],
        environment=plan['environment'],outer_max_seconds=600,owned_RSS_cap_bytes=2*1024**3,
        logical_output_directory=plan['cwd'],logical_output_cap_bytes=1536*1024**2))
    changed=changes(plan)
    ok=terminal['exit_code']==0 and terminal['stop']is None and not terminal['remaining']and not changed
    guard.record(root,'kernel-result.json',dict(status='PRIVATE_KERNEL_BUILT_NOT_GUEST_EXECUTED'if ok else 'FAIL_ORIGINAL_KERNEL_BUILD',original_terminal=terminal,changed_inputs=changed,automatic_retry=False,guest_run=False))
    raise SystemExit(0 if ok else 1)


if __name__=='__main__':main()
