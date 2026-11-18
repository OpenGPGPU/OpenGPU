"""Original same-GEM alias bindings and independent mixed atomic byte oracle."""
import re
import struct
from compute_mixed_atomic_cases import parameters,operations
from compute_atomic_order import linearization
from compute_owner_protocol import required_flags,uint,profile_bytes
from compute_owner_oracle import allocation_window
from compute_counter_source import translate
from compute_indirect_oracle import capture,executable_physical
from owner_resource_contract import IMAGE_FLOAT_PROFILE

SCHEMA='opengpu-compute-mixed-atomic-oracle-v1'
STRIDE=128*1024*1024+4096


def require(value,message):
    if not value:raise ValueError('mixed atomic: '+message)


def recipe(memory,description):
    require(type(description) is dict and set(description)=={
        'schema','sourceCase','groups','local','viewBytes','guard','context','targetHandle','storageBindings',
        'kernargPhysical','kernargBytes','imagePhysical','imageRoot','storagePhysical','storageRoot',
        'codePhysical','codeBytes','target','output','image'},'original alias record fields differ')
    p=parameters(description['sourceCase'])
    required_flags(description,dict(schema=SCHEMA,groups=2,local=4,viewBytes=8192,guard=0xdeadbeef))
    require(0<uint(description['context'])<512 and 0<uint(description['targetHandle'])<512,
            'invalid original context or target handle')
    bindings=description['storageBindings']
    require(type(bindings) is list and all(type(b) is int for b in bindings) and sorted(bindings)==[0,2],
            'actual target/output storage reflection differs')
    target_window=allocation_window(memory,description['target'],p['storage_bytes'],0)
    output=allocation_window(memory,description['output'],8192,2)
    target=uint(description['target']['allocationPhysical'])
    require(description['target']['offset']==p['storage_offset'] and description['target']['allocationBytes']==8192 and
        description['output']['offset']==0 and description['output']['allocationBytes']==8192 and
        (target+8192<=output or output+8192<=target),'original shared target/output views differ or alias')
    expected=bytearray(b'\x6b'*8192);struct.pack_into('<I',expected,p['target_offset'],p['seed'])
    require(memory.read(target,8192)==expected and memory.read(output,8192)==struct.pack('<I',0xdeadbeef)*2048,
            'original target/observed uploads or guards differ')
    image=description['image']
    require(type(image) is dict and set(image)=={'apiBinding','nativeUnit','handle','allocationPhysical',
        'allocationBytes','bindingBytes','view'},'original image alias fields differ')
    required_flags(image,dict(apiBinding=3,nativeUnit=0,handle=description['targetHandle'],
        allocationPhysical=target,allocationBytes=8192,bindingBytes=8192))
    view=dict(offset=p['image_offset'],rowPitch=p['image_bytes'],slicePitch=0,layerPitch=0,
        slot=39,flags=3,target=5,storageFormat=p['native'],imageFormat=p['native'],level=0,
        width=p['image_bytes']//4,height=1,depth=1,layers=1,layer=0)
    required_flags(image.get('view'),view)
    require(set(image['view'])==set(view),'foreign image alias view fields')
    require(target_window+4*p['storage_index']==target+p['image_offset']+4*p['image_coordinate']==target+p['target_offset'],
            'original image/SSBO words are not the same physical target')
    code,size=uint(description['codePhysical']),uint(description['codeBytes'])
    require(code>0 and code%4==0 and 0<size<=2048 and size%4==0,'invalid original executable extent')
    memory.read(code,size)
    return p,target,output,image


def returned_outputs(initial,returned,description):
    p,target,output,_=recipe(initial,description)
    events,predecessors,_=operations(p['case'])
    old_bytes=returned.read(output,64);old=struct.unpack('<16I',old_bytes)
    final=returned.read(target+p['target_offset'],4)
    try:linearization(p['seed'],int.from_bytes(final,'little'),events,old,predecessors)
    except ValueError as error:
        raise ValueError('no legal mixed atomic serialization: '+p['case']+
            ' old='+','.join('%08x'%word for word in old)+' final=%08x'%int.from_bytes(final,'little')) from error
    return [(output,old_bytes),(target+p['target_offset'],final)]


def merged_windows(windows):
    result=[]
    for first,end in sorted(windows):
        require(first<end,'empty original writable view')
        if result and first<=result[-1][1]:result[-1]=(result[-1][0],max(end,result[-1][1]))
        else:result.append((first,end))
    return result


def ready(memory,description,data,code_words,profile):
    require(profile.get('ownerProfile')==IMAGE_FLOAT_PROFILE,'complete float factory required')
    profile_bytes(profile)
    p,target,output,image=recipe(memory,description)
    _,args,writable,inactive,actions,r=capture(data,0,profile)
    require(args is None and [r.get(0xd4+4*n) for n in range(3)]==[2,1,1] and
        [r.get(0xe0+4*n) for n in range(3)]==[4,1,1],'original mixed grid differs')
    staged={};submissions=[];last=-1
    for index,(kind,address,value,mask) in enumerate(actions):
        if kind==1:
            require(mask==0,'masked original MMIO write');staged[address]=value
            if address==0x11c:
                require(value==1,'invalid original publication');submissions.append(dict(staged));last=index
    require(last==len(actions)-1 and submissions and submissions[-1].get(0xc8)==0 and
        all(s.get(0xc8)==5 for s in submissions[:-1]),'mixed source requires one kernel after publication invalidates')
    satp=r.get(0x14c,0)
    require(satp==r.get(0x150) and not r.get(0x4a0,0) and not r.get(0x440,0),
            'instruction/data owner or unrelated counter/uniform authority differs')
    arg,size=uint(description['kernargPhysical']),uint(description['kernargBytes'])
    require(size>0 and any(a<=arg<arg+size<=b for a,b,_ in memory.ranges) and
        translate(memory,satp,r.get(0xd0,0),15)==arg,'original kernarg mapping differs')
    expected=merged_windows(((arg,arg+size),(output,output+8192),
        (target+p['storage_offset'],target+p['storage_offset']+p['storage_bytes']),
        (target+p['image_offset'],target+p['image_offset']+p['image_bytes'])))
    require(writable==expected,'writable grants exceed the union of original alias views')
    require(len(inactive)==1,'one separate original inactive context required')
    context,empty=inactive[0];empty_root=(empty&0x3fffff)<<12
    require(0<context<512 and context!=description['context'] and empty&0x80000000 and
        (empty>>22)&511 and (empty>>22)&511!=(satp>>22)&511 and empty_root!=(satp&0x3fffff)<<12 and
        any(a==empty_root and b==empty_root+4096 for a,b,_ in memory.ranges) and
        memory.read(empty_root,4096)==bytes(4096),'original inactive context is not empty and isolated')
    require(code_words and all(re.fullmatch('[0-9a-f]{8}',word) for word in code_words),
            'actual compiled mixed code words required')
    code=b''.join(struct.pack('<I',int(word,16)) for word in code_words)
    require(len(code)==description['codeBytes'] and memory.read(description['codePhysical'],len(code))==code,
            'original compiled mixed code snapshot differs')
    for offset in range(0,len(code),4):
        require(memory.read(executable_physical(memory,satp,r.get(0xcc,0)+offset),4)==code[offset:offset+4],
                'actual executable mapping differs from original Mesa code')
    root,physical=uint(description['imageRoot']),uint(description['imagePhysical'])
    require([r.get(0x3e0+4*n) for n in range(7)]==[257,root,root+1024,0,description['context'],0,0] and
        translate(memory,satp,root,15)==physical and translate(memory,satp,root+1023,15)==physical+1023,
        'actual image table/context differs')
    image_base=0x87ffd000
    for unit in range(4):
        wanted=[0]*64;wanted[:7]=[0x47495501,256,1,description['context'],0,0,unit]
        if unit==0:
            wanted[7:26]=[1,1,4,1,1,p['native'],p['native'],0,0,0,0,0,0,5,0,
                           image_base,image_base+8192,0,image_base+p['image_offset']]
            wanted[26:30]=[1,1,1,1];wanted[37:40]=[4,p['image_bytes']//4,p['image_bytes']]
        require(memory.read(physical+256*unit,256)==struct.pack('<64I',*wanted),
                'GIU1 alias format, permissions, bounds or unused unit differs')
    require(translate(memory,satp,image_base,15)==target and
        translate(memory,satp,image_base+8191,15)==target+8191,'original image backing mapping differs')
    root,physical=uint(description['storageRoot']),uint(description['storagePhysical'])
    require([r.get(0x420+4*n) for n in range(7)]==[257,root,root+256,0,description['context'],0,0] and
        translate(memory,satp,root,15)==physical,'actual storage table/context differs')
    storage_target=None
    for unit,binding in enumerate(description['storageBindings']):
        offset=p['storage_offset'] if binding==0 else 0
        size=p['storage_bytes'] if binding==0 else 8192
        base=0xcfffc000+unit*STRIDE+(offset&4095)
        wanted=(0x47534201,64,1,description['context'],0,0,unit,1,1,1,
                base,base+size,0,base,(base+size+63)&~63,0)
        require(memory.read(physical+64*unit,64)==struct.pack('<16I',*wanted),
                'GSB1 original alias/output bounds or permissions differ')
        expected=(target+offset) if binding==0 else output
        require(translate(memory,satp,base,15)==expected and
            translate(memory,satp,base+size-1,15)==expected+size-1,'original storage backing mapping differs')
        if binding==0:storage_target=base+4*p['storage_index']
    require(memory.read(physical+128,128)==bytes(128),'unused storage unit has authority')
    require(translate(memory,satp,storage_target,15)==
        translate(memory,satp,image_base+p['image_offset']+4*p['image_coordinate'],15)==target+p['target_offset'],
        'actual image and storage private addresses do not reach the same original word')
