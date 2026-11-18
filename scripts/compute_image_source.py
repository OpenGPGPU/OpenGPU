"""Independent original-image view, upload and returned-byte contracts.

Expected bytes follow the ESSL load/store/atomic and format rules, without interpreting
native words or trusting a descriptor as authority for an original view.
"""
import struct
from compute_image_cases import parameters as legacy_parameters, coordinates
from compute_contended_image_cases import CASES as CONTENDED_CASES, parameters as contended_parameters
from compute_mixed_atomic_cases import CASES as MIXED_CASES, parameters as mixed_parameters
from compute_owner_protocol import uint, required_flags
from compute_owner_oracle import allocation_window
from compute_indirect_oracle import executable_physical

SCHEMA = 'opengpu-compute-image-oracle-v1'
CONTENDED_SCHEMA = 'opengpu-compute-contended-image-oracle-v1'


def parameters(case):
    return mixed_parameters(case) if case in MIXED_CASES else contended_parameters(case) if case in CONTENDED_CASES else legacy_parameters(case)


def require(value, message):
    if not value:
        raise ValueError('typed image: '+message)


def source_read_fault_plan(memory, description, *, owner_profile):
    """Choose only the original recipe's source allocation, never GPU results."""
    from owner_resource_contract import IMAGE_FLOAT_PROFILE
    require(owner_profile==IMAGE_FLOAT_PROFILE and
            description.get('sourceCase')=='image-atomic-buffer-r32f',
            'source fault requires the original float buffer exchange and qualified owner')
    recipe(memory, description)
    image=description['images'][0]
    first,size=uint(image['allocationPhysical']),uint(image['allocationBytes'])
    require(first%64==0 and size%64==0 and size>0 and first+size<=1<<32,
            'source fault allocation is not a complete physical line interval')
    return struct.pack('<8sQQ',b'OGCFS001',first,first+size)


def source_read_fault_proof(value, held=None):
    physical=uint(value.get('physicalReadFaults'))
    injected=uint(value.get('injectedReadFaults'))
    require(physical>0 and physical==injected and uint(value.get('physicalWriteFaults'))==0,
            'failed source execution lacks exact injected read faults')
    proof=(physical,injected)
    require(held is None or proof==source_read_fault_proof(held),
            'source fault history changed during retirement')
    return proof


def image_permission_fault_proof(value, held=None):
    proof=tuple(uint(value.get(k)) for k in ('physicalReadFaults','injectedReadFaults','physicalWriteFaults'))
    require(proof==(0,0,0),'image permission failure encountered physical errors')
    require(held is None or proof==image_permission_fault_proof(held),
            'permission failure changed physical error history')
    return proof


def verify_source_read_fault(initial, returned, description):
    """A cold source-read failure must preserve every original physical byte."""
    verify_failed_image(initial,returned,description)


def verify_image_permission_fault(initial, returned, description, *, permission):
    require(permission in ('read','write'),'unknown image permission denial')
    verify_failed_image(initial,returned,description,permission=permission)


def verify_failed_image(initial, returned, description, *, permission=None):
    require(description.get('sourceCase')=='image-atomic-buffer-r32f', 'wrong source-fault result recipe')
    recipe(initial,description,permission=permission)
    require(initial.ranges==returned.ranges, 'source-fault result replaced original physical layout')
    for begin,end,_ in initial.ranges:
        for address in range(begin,end,4096):
            size=min(4096,end-address)
            require(initial.read(address,size)==returned.read(address,size),
                    'source fault changed an original image/output/argument/descriptor/PT byte')


def captured_page_tables(memory, data):
    """Collect original Sv32 pages after strict READY validation.

    Only valid original PTE words may change to zero during cleanup. Retiring
    private leaves must be revoked; ASID-0 identity leaves need no revocation.
    """
    require(data[:8]==b'OGCMP003','recovery needs the original context-aware capture')
    cursor=8
    def take(fmt):
        nonlocal cursor
        size=struct.calcsize(fmt)
        require(cursor+size<=len(data),'truncated recovery capture')
        values=struct.unpack_from(fmt,data,cursor);cursor+=size;return values
    _,_,_,count=take('<QIII')
    require(count==122,'wrong recovery discovery count')
    for _ in range(count):take('<II')
    count,=take('<I');require(count<=4096,'oversized recovery write grants')
    for _ in range(count):take('<QQ')
    count,=take('<I');require(count==1,'wrong recovery inactive-context count')
    inactive=[take('<II') for _ in range(count)]
    count,=take('<I');require(count<=65536,'oversized recovery MMIO trace')
    satps=set()
    for _ in range(count):
        kind,address,value,_=take('<IIII')
        if kind==1 and address in (0x14c,0x150) and value&0x80000000:satps.add(value)
    require(cursor==len(data) and satps,'incomplete recovery context capture')
    pages=set();revocations=set()
    def words(base):
        require(base%4096==0,'unaligned original PTE page')
        return struct.unpack('<1024I',memory.read(base,4096))
    for satp in satps:
        asid=(satp>>22)&511;root=(satp&0x3fffff)<<12
        pages.add(root)
        for index,link in enumerate(words(root)):
            if not link&1:continue
            require(not link&0x20,'global source PTE')
            if link&15!=1:
                require(asid==0 and link&15==7 and link>>10==index*1024,
                        'unexpected source root leaf')
                continue
            child=(link>>10)<<12;pages.add(child)
            for entry,leaf in enumerate(words(child)):
                if not leaf&1:continue
                require(leaf&3==3 and not leaf&0x20,'invalid original source leaf')
                if asid:revocations.add(child+4*entry)
    for context,satp in inactive:
        require(context>0 and satp&0x80000000 and (satp>>22)&511 and
                not any(words((satp&0x3fffff)<<12)), 'inactive source context is not empty')
    require(revocations,'recovery lacks original private mappings')
    return pages,revocations


def verify_cleaned_memory(before, cleaned, tables):
    """Independently check actual cleanup without accepting a replaced RAM."""
    pages,revocations=tables
    require(pages and revocations and all(a%4==0 and a&~4095 in pages for a in revocations),
            'cleanup lacks original private page-table authority')
    require(before.ranges==cleaned.ranges,'cleanup replaced original RAM layout')
    for begin,end,_ in before.ranges:
        for address in range(begin,end,4096):
            count=min(4096,end-address)
            old,new=before.read(address,count),cleaned.read(address,count)
            if old==new:continue
            require(address%4==0 and count%4==0,'cleanup changed a partial allocation word')
            for offset in range(0,count,4):
                a,b=struct.unpack_from('<I',old,offset)[0],struct.unpack_from('<I',new,offset)[0]
                if a==b:continue
                physical=address+offset
                require(physical&~4095 in pages and a&1 and b==0,
                        'cleanup changed a non-PTE byte or did not clear an original valid word')
    for physical in revocations:
        require(struct.unpack('<I',cleaned.read(physical,4))[0]&1==0,
                'cleanup kept an original private translation reachable')


def source_view(p, role, unit, *, permission=None):
    """Reconstruct the recipe's original allocation and exposed source view."""
    require(permission is None or (type(permission) is str and permission in ('read','write') and
            p['mode']=='atomic' and p['target']=='buffer' and p['format']=='r32f'),
            'permission denial requires the original buffer float exchange')
    target = dict(buffer=5, **{'2d': 0, 'array': 1, 'cube': 2, '3d': 3})[p['target']]
    v = dict(offset=48, rowPitch=13*p['bytes']+3, slicePitch=0, layerPitch=0,
        slot=39+unit, flags=(2 if role else 3 if p['mode'].startswith('atomic') else 1)|4, target=target,
        storageFormat=3 if target==5 and p['format']=='rgba8_snorm' else p['native'],
        imageFormat=p['native'], level=0, width=13, height=1, depth=1, layers=1, layer=0)
    if not role and permission is not None:v['flags']=2 if permission=='read' else 1
    if target==5:
        if p['format']!='rgba8_snorm':v['flags']&=~4
        return 8192, v
    height=26 if target==2 else 10
    depth=(2 if p['mode']=='single' else 6) if target==3 else 1
    layers=(1 if p['mode']=='single' else 4) if target==1 else 1
    planes=6 if target==2 else depth if target==3 else layers
    row0=(26*p['bytes']+63)//64*64
    row=(13*p['bytes']+63)//64*64
    plane=row*(height//2)
    offset=row0*height*planes
    size=offset+plane*(depth//2 if target==3 else planes)
    v.update(offset=offset, rowPitch=row, level=1, height=height//2,
        depth=depth//2 if target==3 else 1, layers=layers,
        slicePitch=plane if target==3 else 0, layerPitch=plane if target in (1,2) else 0)
    if p['mode']=='plane':
        v['layer']=(4 if target==2 else 2) if role else 1
    elif target:
        v['flags'] |= 8
        if target==1:
            first=0 if p['mode']=='single' else 1
            v['offset']+=first*plane
            v['layers']=1 if p['mode']=='single' else 3
        elif target==3:
            first=1 if p['mode'].endswith('bounds') else 0 if p['mode']=='single' or role else 1
            v['offset']+=first*plane
            v['depth']=1 if p['mode']=='single' else 3-first
    return size, v


def initial_bytes(p, count):
    size, bits, components=p['bytes'],p['bits'],p['components']
    require(count%size==0, 'upload allocation is not a complete texel multiple')
    result=bytearray(count)
    for texel in range(count//size):
        spatial=texel^(texel>>4)^(texel>>8)^(texel>>12)
        for component in range(components):
            ordinal=texel*components+component
            if p['kind']=='float':
                value=(0x3f000000|((ordinal*0x9e3779+0x13579)&0x7fffff)) if bits==32 else (
                    0x3800|((ordinal*73+19)&1023))
            elif p['kind']=='unorm':
                value=255 if (spatial>>component)&1 else 0
            elif p['kind']=='snorm':
                value=(0,127,129,128)[(spatial>>(2*component))&3]
            else:
                value=(ordinal*0x10203+0x8391)&((1<<bits)-1)
            first=texel*size+component*(bits//8)
            result[first:first+bits//8]=value.to_bytes(bits//8,'little')
    if p.get('pair') is not None:
        _,view=source_view(p,0,0)
        address=texel_address(dict(allocationPhysical=0,view=view),p,0,0)
        struct.pack_into('<I',result,address,p['seed'])
    return bytes(result)


def recipe(memory, description, *, permission=None):
    require(type(description) is dict,'original view record must be an object')
    p=parameters(description.get('sourceCase'))
    contended=p.get('pair') is not None
    require(type(description) is dict and set(description)=={
        'schema','sourceCase','groups','local','viewBytes','guard','context',
        'kernargPhysical','kernargBytes','imagePhysical','imageRoot',
        'storagePhysical','storageRoot','output','images'}|({'codePhysical','codeBytes'} if contended else set()),
        'original view record fields differ')
    required_flags(description,dict(schema=CONTENDED_SCHEMA if contended else SCHEMA,
        groups=2,local=4,viewBytes=8192,guard=0xdeadbeef))
    if contended:
        code,size=uint(description['codePhysical']),uint(description['codeBytes'])
        require(code>0 and code%4==0 and size>0 and size%4==0,'invalid original executable snapshot')
        memory.read(code,size)
    context=uint(description['context'])
    require(0<context<512, 'invalid original context')
    output=allocation_window(memory,description['output'],8192,2)
    require(description['output']['offset']==0 and description['output']['allocationBytes']==8192 and
        memory.read(output,8192)==struct.pack('<I',0xdeadbeef)*2048, 'original output/guard upload differs')
    images=description['images']
    require(type(images) is list and len(images)==2, 'original read/write images required')
    seen=set();ranges=[(output,output+8192)]
    for role,image in enumerate(images):
        require(type(image) is dict and set(image)=={'apiBinding','nativeUnit','handle',
            'allocationPhysical','allocationBytes','bindingBytes','view'}, 'original image fields differ')
        required_flags(image,dict(apiBinding=1 if role else 3))
        unit=uint(image['nativeUnit']);handle=uint(image['handle'])
        require(unit<2 and unit not in seen and 0<handle<512, 'native image unit/handle differs')
        seen.add(unit)
        binding,v=source_view(p,role,unit,permission=permission)
        require(type(image['view']) is dict and all(type(value) is int for value in image['view'].values()) and
            type(image['bindingBytes']) is int, 'view fields must be integers')
        require(image['bindingBytes']==binding and image['view']==v, 'original format/mip/pitch/layer/access view differs')
        first,size=uint(image['allocationPhysical']),uint(image['allocationBytes'])
        require(not (first&4095) and size==(binding+4095)//4096*4096 and
            (first,first+size) in [(a,b) for a,b,_ in memory.ranges], 'image is not its original complete GEM')
        require(memory.read(first,size)==(b'\xa7'*size if role else initial_bytes(p,size)),
            'original image upload/padding differs')
        ranges.append((first,first+size))
    require(images[0]['handle']!=images[1]['handle'], 'source/destination handles alias')
    ranges.sort()
    require(all(a[1]<=b[0] for a,b in zip(ranges,ranges[1:])), 'original image/output allocations alias')
    return p,output,images


def texel_address(image, p, role, invocation):
    x,y,z=coordinates(p,bool(role))
    if role or p.get('pair') is None:x+=invocation
    v=image['view'];first=image['allocationPhysical']+v['offset']
    if v['target']==5:
        return first+x*p['bytes']
    first+=y*v['rowPitch']+x*p['bytes']
    if v['target']:
        first+=(z if v['flags']&8 else v['layer'])*(v['slicePitch'] if v['target']==3 else v['layerPitch'])
    return first


def boundary_coordinates(p, invocation):
    """Independent integer coordinates for the original upper/lower accesses."""
    target=p['shader']
    if target=='buffer':upper=(invocation*13,0,0);axes=1
    elif target=='2d':upper=((invocation%2)*13,(invocation//2)*5,0);axes=2
    else:
        upper=((invocation%2)*13,((invocation//2)%2)*(13 if target=='cube' else 5),
            (invocation//4)*dict(array=3,cube=6,**{'3d':2})[target]);axes=3
    return upper,tuple(value-1 if axis<axes else 0 for axis,value in enumerate(upper))


def boundary_address(image,p,coordinate):
    """An invalid coordinate has no physical texel; never wrap into a backing."""
    x,y,z=coordinate;v=image['view']
    width=13; height=1 if p['shader']=='buffer' else 13 if p['shader']=='cube' else 5
    planes=dict(array=3,cube=6,**{'3d':2}).get(p['shader'],1)
    if not (0<=x<width and 0<=y<height and 0<=z<planes):return None
    return image['allocationPhysical']+v['offset']+x*p['bytes']+y*v['rowPitch']+z*(
        v['slicePitch'] if p['shader']=='3d' else v['layerPitch'])


def boundary_exchange_value(p,invocation,access):
    return (0x3f400000 if p['format']=='r32f' else 0x87650000)+17*invocation+access


def loaded_words(p, raw):
    words=[];step=p['bits']//8
    for c in range(p['components']):
        data=raw[c*step:(c+1)*step]
        if p['kind']=='float':
            value=struct.unpack('<f' if step==4 else '<e',data)[0]
            word=struct.unpack('<I',struct.pack('<f',value))[0]
        elif p['kind'] in ('unorm','snorm'):
            integer=int.from_bytes(data,'little',signed=p['kind']=='snorm')
            value=max(-1,integer/127) if p['kind']=='snorm' else integer/255
            word=struct.unpack('<I',struct.pack('<f',value))[0]
        else:
            word=int.from_bytes(data,'little',signed=p['kind']=='sint')&0xffffffff
        words.append(word)
    if len(words)==1:
        words += [0,0,1 if p['kind'] in ('uint','sint') else 0x3f800000]
    return struct.pack('<4I',*words)


def atomic_values(p, initial, invocation):
    """Original ESSL RMW semantics on one lane's independent texel."""
    if p['format']=='r32f':
        # These are the exact encodings copied by five float exchanges. No
        # floating arithmetic or NaN bitcast with unspecified GLSL results.
        values=[(invocation&1)<<31,0x3f400000+invocation,0xbf800000+invocation,
                0x7f800000|((invocation&1)<<31),0x40000000+(invocation<<20)]
        return [initial,*values[:-1]],values[-1]
    old=[initial]
    value=0x80000000+invocation*17
    old.append(value);value=(value+0x7ffffff0)&0xffffffff
    old.append(value);value^=0x5555aaaa
    old.append(value);value|=15
    old.append(value);value&=0xfffffff3
    old.append(value)
    def ordered(word):
        return word-0x100000000 if p['kind']=='sint' and word&0x80000000 else word
    if ordered(value)>ordered(0x7fffff00):value=0x7fffff00
    old.append(value)
    if ordered(value)<ordered(0x80000020):value=0x80000020
    old.append(value)
    value=0x12345670+invocation
    old.append(value)
    if value==0x12345670+invocation:value=0xdeadb000+invocation
    old.append(value)
    if value==0x87654321:value=0xa5a5a5a5
    return old,value


def returned_outputs(initial, returned, description):
    p,output,images=recipe(initial,description)
    if p.get('pair') is not None:
        from compute_contended_image_cases import operations, steps
        from compute_atomic_order import linearization
        events,predecessors=operations(p['case'])
        count=len(steps(p));source=texel_address(images[0],p,0,0)
        observed=returned.read(output,4*len(events))
        old=struct.unpack('<%dI'%len(events),observed)
        final=returned.read(source,4)
        require(initial.read(source,4)==struct.pack('<I',p['seed']),'original shared texel seed differs')
        linearization(p['seed'],int.from_bytes(final,'little'),events,old,predecessors)
        results=[(output,observed),(source,final)]
        for invocation in range(8):
            destination=texel_address(images[1],p,1,invocation)
            results.append((destination,struct.pack('<I',old[invocation*count+count-1])))
        return results
    observed=bytearray();results=[]
    for invocation in range(8):
        if p['mode'].endswith('bounds'):
            for access,coordinate in enumerate(boundary_coordinates(p,invocation)):
                source=boundary_address(images[0],p,coordinate)
                destination=boundary_address(images[1],p,coordinate)
                require((source is None)==(destination is None),'boundary view extents differ')
                raw=initial.read(source,p['bytes']) if source is not None else bytes(p['bytes'])
                if p['mode']=='atomic-bounds':
                    observed.extend(raw)
                    stored=boundary_exchange_value(p,invocation,access).to_bytes(4,'little')
                    if source is not None:results.extend(((source,stored),(destination,stored)))
                else:
                    observed.extend(loaded_words(p,raw) if source is not None else bytes(16))
                    if destination is not None:results.append((destination,raw))
            continue
        raw=initial.read(texel_address(images[0],p,0,invocation),p['bytes'])
        if p['mode']=='atomic':
            old,value=atomic_values(p,int.from_bytes(raw,'little'),invocation)
            observed.extend(struct.pack('<%uI'%len(old),*old))
            stored=value.to_bytes(4,'little')
            results.extend((texel_address(image,p,role,invocation),stored) for role,image in enumerate(images))
            continue
        observed.extend(loaded_words(p,raw))
        stored=bytes(129 if byte==128 else byte for byte in raw) if p['kind']=='snorm' else raw
        results.append((texel_address(images[1],p,1,invocation),stored))
    return [(output,bytes(observed)),*results]


def ready(memory, description, data, *, owner_profile=None, permission=None, code_words=None):
    """Bind full112 READY to original views, exact write windows and GIU1/PTEs."""
    from compute_counter_source import translate
    from owner_resource_contract import SAMPLE_PROFILE, SAMPLE_FACTORY_CONTRACTS, FULL_COMPUTE_SCHEMA, IMAGE_FLOAT_PROFILE
    from compute_owner_protocol import profile_bytes
    owner_profile=SAMPLE_PROFILE if owner_profile is None else owner_profile
    require(type(owner_profile) is str and owner_profile in SAMPLE_FACTORY_CONTRACTS,
            'image source requires a known complete sample factory')
    stage,expected_words=SAMPLE_FACTORY_CONTRACTS[owner_profile]
    p,output,images=recipe(memory,description,permission=permission)
    if p['mode'].startswith('atomic') and p['format']=='r32f':
        require(owner_profile==IMAGE_FLOAT_PROFILE,'R32F exchange requires its qualified float owner')
    require(data[:8]==b'OGCMP003', 'full112 capture required')
    cursor=8
    def take(fmt):
        nonlocal cursor
        size=struct.calcsize(fmt)
        require(cursor+size<=len(data), 'truncated capture')
        values=struct.unpack_from(fmt,data,cursor);cursor+=size
        return values
    budget,hold,command,count=take('<QIII')
    require(0<budget<=1000000000 and 8<=hold<=4096 and 0<command<256 and count==122,
        'capture bounds/discovery differ')
    discovery=[take('<II') for _ in range(count)]
    require(discovery[:112]==[(w['address'],w['value']) for w in expected_words], 'exact qualified final112 discovery required')
    profile_bytes(dict(schema=FULL_COMPUTE_SCHEMA,ownerProfile=owner_profile,ownerResourceStage=stage,
        ownerResourceVersion=2,fullResourceQualification=False,resetCount=1,sourceQualificationRequired=True,
        gpuExecution=False,clockQualified=False,fpsQualified=False,
        words=[dict(address=a,value=v) for a,v in discovery[:112]],
        registers=[dict(address=a,value=v) for a,v in discovery[112:]]))
    arg,argbytes=uint(description['kernargPhysical']),uint(description['kernargBytes'])
    require(argbytes and any(a<=arg<arg+argbytes<=b for a,b,_ in memory.ranges), 'kernarg outside original allocation')
    expected=[(arg,arg+argbytes),(output,output+8192)]
    for image in images:
        v=image['view'];first=image['allocationPhysical']+v['offset']
        if not v['flags']&2:continue
        if v['target']==5:
            expected.append((first,first+13*p['bytes']))
        else:
            step=v['slicePitch'] if v['target']==3 else v['layerPitch']
            planes=(v['layers'] if v['target']==1 else 6 if v['target']==2 else v['depth'] if v['target']==3 else 1) if v['flags']&8 else 1
            if not v['flags']&8:first+=v['layer']*step
            expected.extend((first+z*step+y*v['rowPitch'],first+z*step+y*v['rowPitch']+13*p['bytes'])
                for z in range(planes) for y in range(v['height']))
    expected.sort()
    require(all(a[1]<=b[0] for a,b in zip(expected,expected[1:])), 'source writable ranges alias')
    count,=take('<I');require(0<count<=4096, 'invalid writable interval count')
    require([take('<QQ') for _ in range(count)]==expected, 'write grant includes read-only/padding/unselected/tail bytes')
    count,=take('<I');require(count==1, 'original separate inactive context required')
    inactive_context,inactive_satp=take('<II')
    context=description['context']
    require(0<inactive_context<512 and inactive_context!=context, 'inactive context aliases source owner')
    count,=take('<I');require(0<count<=65536, 'invalid MMIO action count')
    staged={};submissions=[];last=-1;satps=set()
    for action in range(count):
        kind,address,value,mask=take('<IIII')
        if kind==1:
            require(mask==0, 'masked source MMIO write')
            staged[address]=value
            if address in (0x14c,0x150) and value&0x80000000:satps.add(value)
            if address==0x11c:
                require(value==1, 'one command publication required')
                submissions.append(dict(staged));last=action
    require(cursor==len(data) and submissions and last==count-1 and submissions[-1].get(0xc8)==0 and
        all(s.get(0xc8)==5 for s in submissions[:-1]), 'one final kernel after publication invalidates required')
    r=submissions[-1]
    require([r.get(0xd4+4*i) for i in range(3)]==[2,1,1] and
        [r.get(0xe0+4*i) for i in range(3)]==[4,1,1], 'original image grid differs')
    satp=r.get(0x14c,0)
    require(r.get(0x150)==satp, 'instruction/data owners differ')
    inactive_root=(inactive_satp&0x3fffff)<<12;asid=(inactive_satp>>22)&511
    require(inactive_satp&0x80000000 and asid and
        all(((active>>22)&511)!=asid and ((active&0x3fffff)<<12)!=inactive_root for active in satps),
        'inactive owner aliases an active ASID/root')
    require(any(a==inactive_root and b==inactive_root+4096 for a,b,_ in memory.ranges) and
        not any(a<inactive_root+4096 and inactive_root<b for a,b in expected) and
        memory.read(inactive_root,4096)==bytes(4096), 'inactive context root is not original and empty')
    require(translate(memory,satp,r.get(0xd0,0),15)==arg, 'original kernarg mapping differs')
    require(not r.get(0x4a0,0) and not r.get(0x440,0), 'unexpected counter/uniform authority')
    if p.get('pair') is not None:
        import re
        require(code_words and all(re.fullmatch('[0-9a-f]{8}',word) for word in code_words),
                'missing or invalid original image code words')
        code=b''.join(struct.pack('<I',int(word,16)) for word in code_words)
        require(len(code)==description['codeBytes'] and
            memory.read(description['codePhysical'],len(code))==code,'original image code snapshot differs')
        for offset in range(0,len(code),4):
            address=executable_physical(memory,satp,r.get(0xcc,0)+offset)
            require(memory.read(address,4)==code[offset:offset+4],'actual image executable differs from Mesa code')
    table,physical=uint(description['imageRoot']),uint(description['imagePhysical'])
    require([r.get(0x3e0+4*i) for i in range(7)]==[257,table,table+1024,0,context,0,0] and
        translate(memory,satp,table,15)==physical and translate(memory,satp,table+1023,15)==physical+1023,
        'original private GIU1 table/context differs')
    for unit in range(4):
        actual=struct.unpack('<64I',memory.read(physical+unit*256,256))
        image=next((i for i in images if i['nativeUnit']==unit),None)
        wanted=[0]*64;wanted[:7]=[0x47495501,256,1,context,0,0,unit]
        if image is not None:
            v=image['view'];base=0xc7ffb000 if unit==3 else 0x87ffd000+unit*(128*1024*1024+4096)
            wanted[7:26]=[1,1,4,bool(v['flags']&1),bool(v['flags']&2),v['storageFormat'],v['imageFormat'],
                bool(v['flags']&4),v['level'],v['level'],v['level'],bool(v['flags']&8),v['layer'],v['target'],v['level'],
                base,base+image['bindingBytes'],0,base+v['offset']]
            wanted[26:30]=[1 if v['target']==5 else v['width'],v['height'],v['depth'],v['layers']]
            wanted[30]=0 if v['target']==5 else v['rowPitch']
            wanted[32]=v['slicePitch'];wanted[34]=v['layerPitch'];wanted[37]=p['bytes']
            if v['target']==5:wanted[38:40]=[13,v['rowPitch']]
            require(translate(memory,satp,base,15)==image['allocationPhysical'] and
                translate(memory,satp,base+image['bindingBytes']-1,15)==image['allocationPhysical']+image['bindingBytes']-1,
                'original image backing/PTE permissions differ')
        require(actual==tuple(wanted), 'GIU1 format/mip/pitch/layer/access/bounds differ')
    table,physical=uint(description['storageRoot']),uint(description['storagePhysical'])
    require([r.get(0x420+4*i) for i in range(7)]==[257,table,table+256,0,context,0,0] and
        translate(memory,satp,table,15)==physical, 'original output descriptor context differs')
    base=0xcfffc000
    wanted=(0x47534201,64,1,context,0,0,0,1,1,1,base,base+8192,0,base,base+8192,0)
    require(memory.read(physical,64)==struct.pack('<16I',*wanted) and
        translate(memory,satp,base,15)==output and translate(memory,satp,base+8191,15)==output+8191,
        'original sparse SSBO binding/output mapping differs')
    for unit in range(1,4):
        wanted=(0,)*16
        require(memory.read(physical+64*unit,64)==struct.pack('<16I',*wanted), 'unused storage unit has authority')
