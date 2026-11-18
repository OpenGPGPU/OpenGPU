"""Same-texel ESSL atomic sources with bounded, invocation-ordered histories."""
from pathlib import Path
from compute_image_cases import FORMATS, TARGETS, coordinates

PAIRS=('swap-add','xor-or','and-min','max-cas','cas-pair')
DEFINITIONS=tuple(
    ('image-contended-'+target+'-'+fmt+'-'+pair,target,fmt,pair)
    for target in TARGETS for fmt in ('r32ui','r32i') for pair in PAIRS)+tuple(
    ('image-contended-'+target+'-r32f-swap',target,'r32f','swap') for target in TARGETS)
CASES=tuple(row[0] for row in DEFINITIONS)


def parameters(case):
    try:
        _,target,fmt,pair=next(row for row in DEFINITIONS if row[0]==case)
    except StopIteration:
        raise ValueError('unknown original contended image source') from None
    native,size,bits,kind,components=FORMATS[fmt]
    seed=(0x3f800000 if fmt=='r32f' else 0 if pair=='cas-pair' else
          (0x7fffffff if kind=='sint' else 0xffffffff) if pair=='and-min' else
          (0x80000000 if kind=='sint' else 0x7fffffff) if pair=='max-cas' else 0xfffffff0)
    return dict(case=case,source=case+'.comp',target=target,shader=target,mode='atomic',
        format=fmt,native=native,bytes=size,bits=bits,kind=kind,components=components,
        suffix=dict(buffer='Buffer',**{'2d':'2D','array':'2DArray','cube':'Cube','3d':'3D'})[target],
        groups=2,local=4,pair=pair,seed=seed)


def steps(p):
    def op(builtin,operation,base,stride=0,compare=None):
        return dict(builtin=builtin,operation=operation,operand=(base,stride),compare=compare)
    signed=p['kind']=='sint'
    if p['pair']=='swap':
        return (op('Exchange','exchange',0x3f400000,1),)
    if p['pair']=='swap-add':
        return (op('Exchange','exchange',0x80000000,17),op('Add','add',0x7ffffff0))
    if p['pair']=='xor-or':
        return (op('Xor','xor',0x5555aaaa),op('Or','or',15))
    if p['pair']=='and-min':
        return (op('And','and',0xfffffff3),
                op('Min','signed-min' if signed else 'unsigned-min',0x80000007))
    if p['pair']=='max-cas':
        maximum=0x7fffff00 if signed else 0x80000020
        return (op('Max','signed-max' if signed else 'unsigned-max',maximum),
                op('CompSwap','compare-swap',0x12345670,1,maximum))
    if p['pair']=='cas-pair':
        return (op('CompSwap','compare-swap',1,1,0),
                op('CompSwap','compare-swap',0xa5a5a5a5,0,11))
    raise ValueError('unknown original image atomic sequence')


def operations(case):
    sequence=steps(parameters(case));events=[];predecessors=[]
    for invocation in range(8):
        for index,step in enumerate(sequence):
            base,stride=step['operand']
            events.append(dict(operation=step['operation'],operand=(base+stride*invocation)&0xffffffff,
                compare=step['compare'] if step['compare'] is not None else 0))
            predecessors.append(1<<(len(events)-2) if index else 0)
    return events,tuple(predecessors)


def coordinate(p,destination=False):
    x,y,z=coordinates(p,destination)
    first='int(g)+%d'%x if destination else str(x)
    return first if p['target']=='buffer' else ('ivec2(%s,%d)'%(first,y)
        if p['target']=='2d' else 'ivec3(%s,%d,%d)'%(first,y,z))


def write_sources(out):
    out=Path(out)
    for case in CASES:
        p=parameters(case);floating=p['format']=='r32f';signed=p['kind']=='sint'
        prefix='' if floating else 'i' if signed else 'u'
        scalar='float' if floating else 'int' if signed else 'uint'
        sequence=steps(p);body='void main(){uint g=gl_GlobalInvocationID.x;uint o=g*%du;'%len(sequence)
        for index,step in enumerate(sequence):
            base,stride=step['operand']
            operand='(0x%08xu+g*%du)'%(base,stride) if stride else '0x%08xu'%base
            raw=([operand] if step['compare'] is None else ['0x%08xu'%step['compare'],operand])
            convert='uintBitsToFloat' if floating else scalar
            arguments=','.join(convert+'('+value+')' for value in raw)
            body+='highp %s previous%d=imageAtomic%s(original,%s,%s);'%(scalar,index,step['builtin'],
                coordinate(p),arguments)
            body+='observed.data[o+%du]=%s(previous%d);'%(index,'floatBitsToUint' if floating else 'uint',index)
        body+='memoryBarrierImage();imageStore(destination,%s,%svec4(previous%d));}\n'%(
            coordinate(p,True),prefix,len(sequence)-1)
        extension='#extension GL_EXT_texture_buffer : require\n' if p['target']=='buffer' else ''
        (out/p['source']).write_text('#version 310 es\n#extension GL_OES_shader_image_atomic : require\n'+
            extension+'precision highp int;\n'+('precision highp float;\n' if floating else '')+
            'layout(local_size_x=4) in;\n'+
            'layout(%s,binding=3) coherent uniform highp %simage%s original;\n'%(p['format'],prefix,p['suffix'])+
            'layout(%s,binding=1) writeonly uniform highp %simage%s destination;\n'%(p['format'],prefix,p['suffix'])+
            'layout(std430,binding=2) buffer Output {uint data[];} observed;\n'+body)
