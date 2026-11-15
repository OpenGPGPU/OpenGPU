"""Supplemental original-word compiler semantics, never DUT/GPU evidence.

Only the bounded straight-line ISA emitted by the two FS backends is modeled.
Source formulas below are independent of producer-predicted output metadata.
"""
import struct
from native_fs_cpu_oracle import bits, floating, signed


def uniform_value(logical, offset):
    assert logical in (*range(12), 31) and offset % 4 == 0
    assert 0 <= offset < (4096 if logical == 31 else 16384)
    return (((13 if logical == 31 else logical+1)*0x1020304) ^ (offset*17+3)) & 0xffffffff


def execute(code, memory, base):
    x=[0]*32;v=[[0]*16 for _ in range(32)];x[8]=base;x[9]=128;vl=0
    for pc,w in enumerate(code):
        op,d,f3,a,z,fn=w&127,w>>7&31,w>>12&7,w>>15&31,w>>20&31,w>>26
        if w==0x30500073:
            assert pc==len(code)-1;return
        if w==0x0330000f:continue
        if op==0x37:x[d]=w&0xfffff000
        elif op==0x13 and f3==0:x[d]=(x[a]+signed(w>>20,12))&0xffffffff
        elif op==0x13 and f3==1 and not w>>25:x[d]=(x[a]<<z)&0xffffffff
        elif op==0x33 and f3==0:
            assert w>>25 in (0,1)
            x[d]=(x[a]*x[z] if w>>25 else x[a]+x[z])&0xffffffff
        elif op==0x0b and w>>25==7 and f3==0:x[d]=uniform_value(x[a],x[z])
        elif op==0x57 and f3==7:
            assert w==0x01007557;vl=16;x[d]=vl
        elif op in (7,0x27) and f3==6:
            assert w>>20==0x20 and vl
            for lane in range(vl):
                at=x[a]+lane*4;assert at%4==0 and 0<=at<=len(memory)-4
                if op==7:v[d][lane]=struct.unpack_from('<I',memory,at)[0]
                else:struct.pack_into('<I',memory,at,v[d][lane])
        elif op==0x57 and vl:
            for lane in range(vl):
                lhs=v[z][lane];rhs=v[a][lane] if f3 in (0,1) else x[a] if f3==4 else signed(a,5)&0xffffffff
                if fn==23 and not w&(1<<25):value=rhs if v[0][lane] else lhs
                elif fn==0x19 and f3==3:value=int(lhs!=rhs)
                elif fn==23 and f3==4:value=rhs
                elif fn==0 and f3 in (0,3,4):value=lhs+rhs
                elif fn==0 and f3==1:value=bits(floating(lhs)+floating(rhs))
                elif fn==0x24 and f3==1:value=bits(floating(lhs)*floating(rhs))
                elif fn==0x12 and f3==1 and a in (2,3):value=bits(float(lhs if a==2 else signed(lhs)))
                elif fn==0x12 and f3==1 and a in (6,7):value=int(floating(lhs))
                elif fn==9 and f3 in (0,3):value=lhs&rhs
                elif fn==10 and f3 in (0,3):value=lhs|rhs
                elif fn==11 and f3 in (0,3):value=lhs^rhs
                elif fn==0x25 and f3 in (0,3):value=lhs<<(rhs&31)
                elif fn==0x28 and f3 in (0,3):value=lhs>>(rhs&31)
                elif fn==0x29 and f3 in (0,3):value=signed(lhs)>>(rhs&31)
                else:raise AssertionError('unsupported word %08x at %d'%(w,pc))
                v[d][lane]=value&0xffffffff
        else:raise AssertionError('unsupported word %08x at %d'%(w,pc))
        x[0]=0
    raise AssertionError('missing CEASE')


def verify(path,name,samples):
    data=path.read_bytes();assert data[:8]==b'OGNSF002'
    h=struct.unpack_from('<42I',data,8);assert len(data)==176+4*h[14]
    code=struct.unpack_from('<%dI'%h[14],data,176)
    first,stride=h[2],512;memory=bytearray(b'\xcd'*(h[4]+64));expected=bytearray(memory)
    def put(dest,array,lane,value):struct.pack_into('<I',dest,array*stride+lane*4,value)
    for lane in range(128):
        primitive=5+lane*7;sample=lane%samples
        px=.5 if samples==1 else .75 if sample&1 else .25
        py=.5 if samples==1 else .75 if (sample&1 if samples==2 else sample&2) else .25
        mask=(1<<sample) if h[1]==9 else (1<<samples)-1
        helper=lane%3==0;values=[lane*32+c for c in (11,13,17,19)]
        for dest in (memory,expected):
            put(dest,72,lane,primitive);put(dest,4,lane,int(helper)<<1);put(dest,5,lane,mask)
            for c,value in enumerate(values):put(dest,66+c,lane,value)
            if h[1]==9:
                put(dest,73,lane,sample);put(dest,74,lane,bits(px));put(dest,75,lane,bits(py))
        if name=='primitive':colors={0:(primitive,0,0,1)}
        elif name=='sample':colors={0:tuple(map(bits,(float(primitive),float(sample),px,py)))}
        elif name=='mask':colors={0:(primitive,mask,0,1)}
        elif name=='helper':colors={0:(primitive,int(helper),0,1)}
        elif name=='static-sample':colors={0:(primitive,0,0,1)}
        elif name=='flat-mrt':colors={0:values,1:tuple(v+primitive for v in values),2:tuple(v^7 for v in values),3:values}
        elif name=='depth-mask':colors={0:tuple(map(bits,(float(primitive),.25,.5,1.0)))}
        elif name=='uniforms':
            value=primitive^uniform_value(31,4092)
            for block in range(12):value^=uniform_value(block,16380)
            colors={0:(value,0,0,1)}
        else:raise AssertionError(name)
        for mrt,components in colors.items():
            for c,value in enumerate(components):put(expected,first+4*mrt+c,lane,value)
        control=1|(sum(1<<i for i in colors)<<2)
        if name=='mask':control|=64|((mask&3)<<7)
        if name=='depth-mask':
            put(expected,first+16,lane,bits(.5));control|=2|64|((1<<sample)<<7)
        put(expected,first+17,lane,control)
    for base in range(0,128,16):execute(code,memory,base)
    assert memory==expected,(name,samples,next(i for i,(a,b) in enumerate(zip(memory,expected)) if a!=b))
