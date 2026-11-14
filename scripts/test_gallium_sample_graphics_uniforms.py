#!/usr/bin/env python3
"""Original Mesa ABI1 graphics on private112; CPU capture only, no live protocol."""
import json
from pathlib import Path
import struct
ROOT=Path(__file__).resolve().parents[1]

def validate_native_metadata(directory,prefix='native'):
    schemas=json.loads((ROOT/'docs/abi/native-layered-graphics-v2.json').read_text())
    old=json.loads((ROOT/'docs/abi/native-binned-graphics-v1.json').read_text())
    state=json.loads((ROOT/'docs/abi/typed-fragment-state-v1.json').read_text())
    values={}
    for kind,schema in [('gnj',schemas['job']),('gnd',old['draw']),('gfs',state),('gnf',schemas['frame'])]:
        raw=(directory/(prefix+'.'+kind)).read_bytes()
        assert len(raw)==schema['bytes']
        words=struct.unpack('<%dI'%(len(raw)//4),raw)
        assert words[:3]==(int(schema['magic'],16),len(raw),schema.get('version',1))
        allowed=[0]*len(words);allowed[:8]=[0xffffffff]*8;fields={}
        for field in schema['fields']:
            bits=field['bits'];index=field['word'];size=(bits+31)//32
            value=sum(words[index+i]<<(32*i) for i in range(size))
            assert value>>bits==0,field['name']
            for i in range(size):allowed[index+i]|=min((1<<max(0,bits-32*i))-1,0xffffffff)
            if field['name'].split('.')[-1] in {'base','limit','allocationBase','allocationLimit','shaderPc','address','frameBase','frameLimit','drawBase','drawLimit'}:
                assert value==0,field['name']
            fields[field['name']]=value
        assert all(word&~mask==0 for word,mask in zip(words,allowed))
        values[kind]=fields
    job,frame,draw,fs=(values[k] for k in ('gnj','gnf','gnd','gfs'))
    assert job['job.drawCount']==1 and job['job.drawStride']==2048 and job['completion.writable']==1
    assert frame['frame.version']==3 and frame['layered.version']==1 and frame['layered.layers']==1
    assert frame['layered.sourcePitch[0]']==256
    assert draw['draw.vertex.geometry.vertex.varyingSlots']==fs['fragment.varyingSlots']==1
    assert draw['draw.vertex.geometry.flatMask']==15 and fs['fragment.version']==1
    assert frame['frame.image.pixels.colors.colors[0].format']==26
    assert job['job.identity.context']==fs['fragment.identity.context']
    return values

if __name__=='__main__':
    from test_gallium_compute_uniforms import main
    main(graphics_uniforms=True,sample_graphics=True)
