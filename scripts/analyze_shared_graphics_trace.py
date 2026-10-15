#!/usr/bin/env python3
"""Analyze the fixed WideSharedGraphicsSpec scene's packed CSV event traces."""
import csv,json,sys,collections,hashlib,re
from pathlib import Path
root=Path(sys.argv[1])
results=[]
for path in sorted(root.glob('cu-*-cycles.csv')):
    cus=int(path.name.split('-')[1]); totals=collections.Counter();phase=[0]*6
    samples=0;peak=0
    for row in csv.DictReader(path.open()):
        duration=int(row['duration']);bits=int(row['events_hex'],16)
        phases=[(bits>>(8*i))&255 for i in range(6)]
        outstanding=(bits>>48)&255
        samples+=duration;peak=max(peak,outstanding)
        for i,count in enumerate(phases):phase[i]+=duration*count
        for i,name in enumerate(('idle','write','launch','run','read','emit')):
            if phases[i]:totals[name+'_any']+=duration
        for bit,name in ((56,'staging_blocked'),(57,'output_blocked'),(58,'om_stall'),(59,'raster_stall'),(60,'bvalid'),(61,'rvalid'),(62,'awvalid'),(63,'arvalid')):
            if bits&(1<<bit):totals[name]+=duration
        if outstanding:totals['staging_outstanding_any']+=duration
        if not phases[3] and (phases[1] or phases[4]):totals['staging_without_shader_run']+=duration
    assert sum(phase) == cus * samples, "each worker must occupy exactly one phase per cycle"
    memory=collections.Counter()
    prefix=path.name.removesuffix('-cycles.csv')
    match=re.fullmatch(r'cu-(\d+)(?:-axi-(\d+))?(?:-side-(\d+)-alu-(\d+))?',prefix)
    assert match, f'Unexpected trace prefix: {prefix}'
    axi_bytes=int(match[2] or 8)
    side=int(match[3] or 32)
    alu_loops=int(match[4] or 0)
    mp=root/f'{prefix}-memory.csv'
    for row in csv.DictReader(mp.open()):
        addr=int(row['address_hex'],16);kind='other'
        if 0x10000<=addr<0x10000+cus*2*4672:kind='staging_or_shader_data'
        elif 0x30000<=addr<0x30000+side*side*4:kind='color'
        elif 0x40000<=addr<0x40000+side*side*4:kind='depth'
        elif 0x2000<=addr<0x20a0:kind='commands'
        elif 0x4000<=addr<0x5000:kind='instructions'
        elif 0x1000<=addr<0x1040:kind='descriptor'
        memory[kind+'_'+row['operation']]+=1
    r=dict(cus=cus,axi_data_bytes=axi_bytes,side=side,alu_loops=alu_loops,traced_cycles=samples,phase_worker_cycles=phase,peak_staging_outstanding=peak,cycle_events=dict(totals),lower_memory_transactions=dict(memory),trace_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (path,mp)})
    results.append(r)
print(json.dumps(results,indent=2))
