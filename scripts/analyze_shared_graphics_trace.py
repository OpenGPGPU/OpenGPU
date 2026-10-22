#!/usr/bin/env python3
"""Analyze shared graphics event traces and their recorded resource layouts."""
import argparse,csv,json,collections,hashlib,re
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('trace_directory',type=Path)
args=parser.parse_args()
root=args.trace_directory
if not root.is_dir():
    parser.error(f'Trace directory does not exist: {root}')
paths=sorted(root.glob('cu-*-cycles.csv'))
if not paths:
    parser.error(f'No cycle traces in {root}')
results=[]
for path in paths:
    cus=int(path.name.split('-')[1]); totals=collections.Counter();phase=[0]*6
    samples=0;peak=0
    prefix=path.name.removesuffix('-cycles.csv')
    metadata_path=root/f'{prefix}-metadata.json'
    if metadata_path.exists():
        config=json.loads(metadata_path.read_text())
        assert config['pixel_checks_passed'] is True, f'Incomplete pixel qualification: {metadata_path}'
        assert config['cus']==cus
        axi_bytes=config['axi_data_bytes'];side=config['side'];alu_loops=config['alu_loops']
    else:
        # Historical traces came only from the documented 16-lane/eight-warp
        # fixture. New traces carry their actual resource layout explicitly.
        match=re.fullmatch(r'cu-(\d+)(?:-axi-(\d+))?(?:-side-(\d+)-alu-(\d+))?',prefix)
        assert match, f'Missing layout metadata for trace: {prefix}'
        axi_bytes=int(match[2] or 8);side=int(match[3] or 32);alu_loops=int(match[4] or 0)
        stride=4*16*8
        config=dict(cus=cus,lanes=16,warps=8,bank_count=2*cus,
                    bank_stride=(9*stride+32+63)&~63,kernarg=0x10000,
                    color=0x30000,depth=0x40000,commands=0x2000,
                    program=0x4000,descriptor=0x1000)
    workers=config.get('fragment_workers',cus)
    assert type(workers) is int and 1<=workers<=127, 'Invalid fragment worker count'
    assert config['bank_count']==2*workers, 'Scratch bank count must match fragment workers'
    for row in csv.DictReader(path.open()):
        duration=int(row['duration']);bits=int(row['events_hex'],16)
        assert duration>0, "Trace intervals must have positive duration"
        phases=[(bits>>(8*i))&255 for i in range(6)]
        assert sum(phases)==workers, "Each fragment worker must occupy exactly one phase per cycle"
        outstanding=(bits>>48)&255
        samples+=duration;peak=max(peak,outstanding)
        for i,count in enumerate(phases):phase[i]+=duration*count
        for i,name in enumerate(('idle','write','launch','run','read','emit')):
            if phases[i]:totals[name+'_any']+=duration
        for bit,name in ((56,'staging_blocked'),(57,'output_blocked'),(58,'om_stall'),(59,'raster_stall'),(60,'bvalid'),(61,'rvalid'),(62,'awvalid'),(63,'arvalid')):
            if bits&(1<<bit):totals[name]+=duration
        if outstanding:totals['staging_outstanding_any']+=duration
        if not phases[3] and (phases[1] or phases[4]):totals['staging_without_shader_run']+=duration
    assert samples>0, 'Empty cycle trace cannot establish worker accounting'
    memory=collections.Counter()
    mp=root/f'{prefix}-memory.csv'
    for row in csv.DictReader(mp.open()):
        addr=int(row['address_hex'],16);kind='other'
        if config.get('texture_extent',0)>0 and config['texture']<=addr<config['texture']+config['texture_extent']**2*4:kind='texture'
        elif config['kernarg']<=addr<config['kernarg']+config['bank_count']*config['bank_stride']:kind='staging_or_shader_data'
        elif config['color']<=addr<config['color']+side*side*4:kind='color'
        elif config['depth']<=addr<config['depth']+side*side*4:kind='depth'
        elif config['commands']<=addr<config['commands']+config.get('command_bytes',160):kind='commands'
        elif config['program']<=addr<config['program']+4096:kind='instructions'
        elif config['descriptor']<=addr<config['descriptor']+64:kind='descriptor'
        elif config.get('vertex_stage'):
            if config['vertex_program']<=addr<config['vertex_program']+4096:kind='vertex_instructions'
            elif config['vertex_buffer']<=addr<config['vertex_buffer']+3*32:kind='vertex_input'
            elif config['vertex_kernarg']<=addr<config['vertex_kernarg']+2*config['vertex_bank_stride']:kind='vertex_staging'
        memory[kind+'_'+row['operation']]+=1
    r=dict(cus=cus,fragment_workers=workers,axi_data_bytes=axi_bytes,side=side,alu_loops=alu_loops,traced_cycles=samples,phase_worker_cycles=phase,peak_staging_outstanding=peak,cycle_events=dict(totals),lower_memory_transactions=dict(memory),trace_sha256={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in (path,mp)})
    r['configuration']=config
    if metadata_path.exists():
        r['trace_sha256'][metadata_path.name]=hashlib.sha256(metadata_path.read_bytes()).hexdigest()
        pixel_path=root/f'{prefix}-pixels.csv'
        assert pixel_path.is_file(), f'Missing pixel evidence: {pixel_path}'
        r['trace_sha256'][pixel_path.name]=hashlib.sha256(pixel_path.read_bytes()).hexdigest()
        shader=b''.join(int(word,16).to_bytes(4,'little') for word in config['shader_words'])
        r['shader_sha256']=hashlib.sha256(shader).hexdigest()
        if config.get('vertex_stage'):
            vertex_shader=b''.join(int(word,16).to_bytes(4,'little') for word in config['vertex_shader_words'])
            r['vertex_shader_sha256']=hashlib.sha256(vertex_shader).hexdigest()
    results.append(r)
print(json.dumps(results,indent=2))
