#!/usr/bin/env python3
"""Small real CPU-process and synthetic capture checks; never starts QEMU/RTL."""
import argparse
import contextlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
sys.dont_write_bytecode = True
import resource_guest_once as tool
import check_guest_common as common
import run_pagemap_kernel as kernel


def main():
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--out',type=Path,required=True)
    args=ap.parse_args();root=args.out.resolve();root.mkdir(parents=True,exist_ok=False);cases=[]
    fixtures=[
        ('zero',"import time;time.sleep(.1)",5,256*1024**2,None),
        ('nonzero',"import time;time.sleep(.1);raise SystemExit(7)",5,256*1024**2,None),
        ('timeout',"import time;time.sleep(10)",.1,256*1024**2,'outer_wall_timeout'),
        ('rss',"import time;time.sleep(10)",5,1,'owned_RSS_cap'),
        ('left-child',"import subprocess,sys,time;subprocess.Popen([sys.executable,'-c','import time;time.sleep(10)']);time.sleep(.1)",5,256*1024**2,'unexpected_descendants_after_original_leader'),
        ('logical-size',"import time;time.sleep(10)",5,256*1024**2,'logical_output_cap')]
    for name,code,seconds,rss,stop in fixtures:
        config=dict(app='SYNTHETIC_CPU_ONLY',output=str(root/name),argv=[sys.executable,'-c',code],
                    environment=dict(PATH=os.defpath,LANG='C',LC_ALL='C',TZ='UTC'),
                    outer_max_seconds=seconds,owned_RSS_cap_bytes=rss)
        if name=='logical-size':
            data=root/'logical-input';data.mkdir();(data/'ten-bytes').write_bytes(b'0123456789')
            config.update(logical_output_directory=str(data),logical_output_cap_bytes=1)
        terminal=tool.supervise(config)
        tool.need(terminal['stop']==stop and not terminal['remaining'], 'supervisor fixture failed: '+name)
        if name=='zero':tool.need(terminal['exit_code']==0,'lost original zero exit')
        elif name in ('nonzero','left-child'):tool.need(terminal['exit_code']==(7 if name=='nonzero'else 0),'lost original leader exit')
        else:tool.need(terminal['exit_code']<0,'capped process was not stopped')
        identity=json.loads((root/name/'original-process.json').read_text())
        tool.need(identity['PID']==identity['PGID']and identity['birth'],'missing original identity')
        cases.append(name)
    inputs=root/'synthetic-inputs';inputs.mkdir();firmware=inputs/'firmware';firmware.mkdir();(firmware/'blob').write_bytes(b'CPU ONLY')
    paths={name:str(inputs/name)for name in('qemu','kernel','initramfs','dtb','dtc','guest_profile')}
    for name in('qemu','kernel','initramfs','dtb'):(inputs/name).write_bytes(b'SYNTHETIC NOT BOOTABLE')
    (inputs/'qemu').write_text('#!'+sys.executable+'''\nimport os,sys,time
from pathlib import Path
time.sleep(.1)
serial=sys.argv[sys.argv.index('-serial')+1]
Path(serial.removeprefix('file:')).write_text('SYNTHETIC CPU ONLY: NO LINUX OR GPU\\n')
proof=Path(os.environ['ARTI_NATIVE_MEMORY_PROOF']);proof.mkdir()
(proof/'synthetic-only').write_bytes(b'NOT AN OBSERVER TRACE')
print('SYNTHETIC CPU EXECUTABLE, NOT QEMU')
''');(inputs/'qemu').chmod(0o755)
    (inputs/'dtc').write_text('#!'+sys.executable+'\nprint("SYNTHETIC_DTB_ONLY")\n');(inputs/'dtc').chmod(0o755)
    paths['guest_profile']=str(inputs/'guest_profile.py')
    (inputs/'guest_profile.py').write_text('def check_dtb(text):\n    if text.strip()!="SYNTHETIC_DTB_ONLY": raise ValueError("bad synthetic DTB")\n')
    paths['firmware']=str(firmware)
    draft=root/'draft.json';draft.write_text(json.dumps(dict(app='h7',paths=paths,output=str(root/'NEVER_RUN_QEMU'),
        shared_model_lock=str(root/'shared-lock'),outer_max_seconds=5,owned_RSS_cap_bytes=256*1024**2)))
    packet=root/'launch'
    with contextlib.redirect_stdout(io.StringIO()):tool.prepare(draft,packet)
    index_sha=tool.sha(packet/'INDEX.json');tool.checked(packet,index_sha);cases.append('prepare-check-no-CLAIM')
    before=Path(paths['kernel']).read_bytes();Path(paths['kernel']).write_bytes(before+b'changed')
    try:tool.checked(packet,index_sha)
    except RuntimeError:cases.append('input-drift-rejected')
    else:raise AssertionError('changed input accepted')
    Path(paths['kernel']).write_bytes(before)
    refused=subprocess.run([sys.executable,str(Path(tool.__file__)),'--run','--packet',str(packet),'--approved-sha',index_sha],capture_output=True,text=True)
    tool.need(refused.returncode!=0 and 'one-boot review record required'in refused.stderr,'missing review was accepted')
    tool.need(not(packet/'CLAIM.json').exists()and not(root/'NEVER_RUN_QEMU').exists()and not(root/'shared-lock').exists(),'preclaim rejection created a boot attempt')
    cases.append('missing-review-preclaim-rejected')
    review=root/'SYNTHETIC-review.json'
    subprocess.run([sys.executable,str(Path(tool.__file__)),'--review','--packet',str(packet),
                    '--approved-sha',index_sha,'--approval-file',str(review)],check=True,capture_output=True,text=True)
    argv=[sys.executable,str(Path(tool.__file__)),'--run','--packet',str(packet),'--approved-sha',index_sha,
          '--approval-file',str(review),'--approval-sha',tool.sha(review)]
    executed=subprocess.run(argv,capture_output=True,text=True)
    tool.need(executed.returncode==0,'synthetic end-to-end capture failed: '+executed.stderr)
    run=root/'NEVER_RUN_QEMU'
    common.check_original_run(run/'launch-result.json',run/'serial.log',run/'stderr.log',run/'proof',
                              Path(paths['dtb']),run/'original-terminal.json','h7')
    cases.append('synthetic-original-capture-binds-to-frozen-common')
    try:common.check_boot((run/'serial.log').read_text(),'h7')
    except ValueError:cases.append('synthetic-capture-does-not-prove-Linux')
    else:raise AssertionError('CPU capture mistaken for actual boot')
    (run/'proof/unexpected').write_bytes(b'EXTRA')
    try:common.check_original_run(run/'launch-result.json',run/'serial.log',run/'stderr.log',run/'proof',Path(paths['dtb']),run/'original-terminal.json','h7')
    except ValueError:cases.append('proof-membership-drift-rejected')
    else:raise AssertionError('extra proof file accepted')
    (run/'proof/unexpected').unlink()
    kernel.compare_globals('alpha T 0 4\nbeta D 10 8\n','beta D 200 8\nalpha T 300 40\n')
    cases.append('mock-nm-global-names-types-positive')
    for label,bad in [('name','gamma T 0 4\nbeta D 10 8\n'),('type','alpha D 0 4\nbeta D 10 8\n')]:
        try:kernel.compare_globals('alpha T 0 4\nbeta D 10 8\n',bad)
        except RuntimeError:cases.append('mock-nm-'+label+'-mismatch-rejected')
        else:raise AssertionError('nm global mismatch accepted')
    baseline,private=root/'ABI-original',root/'ABI-private'
    for directory in (baseline,private):
        (directory/'include/config').mkdir(parents=True)
        for name,data in [('include/config/kernel.release',b'7.2.0\n'),('.config',b'CONFIG_SYNTHETIC=y\n'),('.vmlinux.export.o',b'SYNTHETIC EXPORT ABI')]:
            (directory/name).write_bytes(data)
    (baseline/'Module.symvers').write_bytes(b'SYNTHETIC CRC\n')
    plan=dict(original_build=str(baseline),cwd=str(private),input_sha256={str(baseline/'Module.symvers'):tool.sha(baseline/'Module.symvers')})
    kernel.final_abi(plan);cases.append('cached-ABI-positive')
    (private/'.vmlinux.export.o').write_bytes(b'CHANGED EXPORT ABI')
    try:kernel.final_abi(plan)
    except RuntimeError:cases.append('cached-export-ABI-mismatch-rejected')
    else:raise AssertionError('changed cached export ABI accepted')
    result=dict(status='PASS_CPU_SUPERVISOR_AND_CAPTURE_FORMAT',cases=cases,actualGuestExecuted=False,
                actualKernelCompiled=False,actualModelBuilt=False,supervisorSha256=tool.sha(Path(tool.__file__)))
    tool.record(root,'result.json',result);print(json.dumps(result,indent=2))


if __name__=='__main__':main()
