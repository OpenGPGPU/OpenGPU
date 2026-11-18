"""Original same-GEM image/SSBO atomic sources; no compiler or DUT results."""
from pathlib import Path
from compute_contended_image_cases import PAIRS, parameters as image_parameters, steps as image_steps

ORDERS=('storage-first','image-first')
# All windows refer to one original 8-KiB GEM. The two API views may have
# different starts, but their selected aligned words must be identical.
VIEWS={
    'same':dict(storage_offset=0,storage_bytes=64,storage_index=0,image_offset=0,image_bytes=64,image_coordinate=0),
    'shifted':dict(storage_offset=4080,storage_bytes=64,storage_index=7,image_offset=4096,image_bytes=52,image_coordinate=3),
    'endpoint':dict(storage_offset=8160,storage_bytes=32,storage_index=7,image_offset=8140,image_bytes=52,image_coordinate=12),
}
DEFINITIONS=tuple(
    ('mixed-atomic-'+fmt+'-'+pair+'-'+order+'-'+view,fmt,pair,order,view)
    for fmt in ('r32ui','r32i') for pair in PAIRS for order in ORDERS for view in VIEWS)+tuple(
    ('mixed-atomic-r32f-swap-swap-'+order+'-'+view,'r32f','swap-swap',order,view)
    for order in ORDERS for view in VIEWS)
CASES=tuple(row[0] for row in DEFINITIONS)


def parameters(case):
    try:_,fmt,pair,order,view=next(row for row in DEFINITIONS if row[0]==case)
    except StopIteration:raise ValueError('unknown original mixed atomic source') from None
    p=image_parameters('image-contended-buffer-'+fmt+'-'+('swap' if fmt=='r32f' else pair))
    p.update(case=case,source=case+'.comp',pair=pair,order=order,layout=view,allocation_bytes=8192,**VIEWS[view])
    p['target_offset']=p['storage_offset']+4*p['storage_index']
    return p


def steps(p):
    if p['format']=='r32f':
        sequence=(dict(builtin='Exchange',operation='exchange',operand=(0x3f400000,1),compare=None),
                  dict(builtin='Exchange',operation='exchange',operand=(0x40000000,17),compare=None))
    else:sequence=image_steps(p)
    first='storage' if p['order']=='storage-first' else 'image'
    return tuple(dict(step,domain=first if index==0 else 'image' if first=='storage' else 'storage')
                 for index,step in enumerate(sequence))


def operations(case):
    sequence=steps(parameters(case));events=[];predecessors=[];domains=[]
    for invocation in range(8):
        for index,step in enumerate(sequence):
            base,stride=step['operand']
            events.append(dict(operation=step['operation'],operand=(base+stride*invocation)&0xffffffff,
                compare=step['compare'] if step['compare'] is not None else 0))
            predecessors.append(1<<(2*invocation) if index else 0)
            domains.append(step['domain'])
    return events,tuple(predecessors),tuple(domains)


def write_sources(out):
    out=Path(out)
    for case in CASES:
        p=parameters(case);floating=p['format']=='r32f';signed=p['kind']=='sint'
        prefix='' if floating else 'i' if signed else 'u'
        image_scalar='float' if floating else 'int' if signed else 'uint'
        storage_scalar='int' if signed else 'uint'
        body='void main(){uint g=gl_GlobalInvocationID.x;uint o=g*2u;'
        for index,step in enumerate(steps(p)):
            image=step['domain']=='image'
            scalar=image_scalar if image else storage_scalar
            base,stride=step['operand']
            operand='(0x%08xu+g*%du)'%(base,stride) if stride else '0x%08xu'%base
            raw=([operand] if step['compare'] is None else ['0x%08xu'%step['compare'],operand])
            convert='uintBitsToFloat' if floating and image else scalar
            arguments=','.join(convert+'('+value+')' for value in raw)
            call=('imageAtomic'+step['builtin']+'(original,%d,'%p['image_coordinate'] if image else
                  'atomic'+step['builtin']+'(target.data[%du],'%p['storage_index'])
            body+='highp %s previous%d=%s%s);'%(scalar,index,call,arguments)
            body+='observed.data[o+%du]=%s(previous%d);'%(index,
                'floatBitsToUint' if floating and image else 'uint',index)
            if index==0:body+='memoryBarrierBuffer();memoryBarrierImage();'
        body+='memoryBarrierBuffer();memoryBarrierImage();}\n'
        (out/p['source']).write_text('#version 310 es\n#extension GL_OES_shader_image_atomic : require\n'+
            '#extension GL_EXT_texture_buffer : require\nprecision highp int;\n'+
            ('precision highp float;\n' if floating else '')+'layout(local_size_x=4) in;\n'+
            'layout(%s,binding=3) coherent uniform highp %simageBuffer original;\n'%(p['format'],prefix)+
            'layout(std430,binding=0) coherent buffer Target {%s data[];} target;\n'%storage_scalar+
            'layout(std430,binding=2) buffer Output {uint data[];} observed;\n'+body)
