"""Checked cached/UC mappings of one original GEM and independent histories."""
import struct

import compute_uc_cache_alias_cases as source
from compute_cache_alias_source import _outputs
from compute_global_atomic_source import _recipe,_ready
from compute_counter_source import translate
from compute_indirect_oracle import capture
from compute_owner_oracle import verify_byte_changes
from compute_owner_protocol import required_flags,uint

SCHEMA='opengpu-compute-uc-cache-alias-oracle-v1'
STORAGE_BASE=0xcfffc000
CORE={'schema','sourceCase','viewBytes','groups','local','guard','context',
      'codePhysical','codeBytes','shader','kernarg'}
EXTRA={'storageAlias','storageSlot','storageBinding','descriptorPhysical',
       'descriptorRoot','retainedObjects','temporaryMappings'}


def require(ok,message):
    if not ok:raise ValueError('UC cache alias source: '+message)


def recipe(memory,description):
    require(type(description) is dict and set(description)==CORE|EXTRA,
            'foreign or missing same-GEM alias fields')
    required_flags(description,dict(schema=SCHEMA,storageSlot=13,storageBinding=0,
                                   retainedObjects=1,temporaryMappings=2))
    p,kernarg,code=_recipe(memory,{key:description[key] for key in CORE},source,SCHEMA)
    required_flags(description['storageAlias'],description['kernarg'])
    require(set(description['storageAlias'])==set(description['kernarg']),
            'UC storage does not retain the same original GEM and exact view')
    descriptor=uint(description['descriptorPhysical'])
    require(descriptor and not descriptor&4095 and descriptor!=description['codePhysical'] and
            any((a,b)==(descriptor,descriptor+4096) for a,b,_ in memory.ranges) and
            (descriptor+4096<=kernarg or kernarg+8192<=descriptor) and
            descriptor!=description['shader']['allocationPhysical'],
            'storage descriptors do not occupy a private original allocation')
    return p,kernarg,code


def ready(memory,description,data,code_words,profile):
    recipe(memory,description)
    _ready(memory,{key:description[key] for key in CORE},data,code_words,profile,source,SCHEMA,True)
    _,_,_,_,_,staged=capture(data,0,profile)
    satp=staged[0x14c];physical=description['descriptorPhysical'];root=uint(description['descriptorRoot'])
    context=description['context'];kernarg=description['kernarg']['allocationPhysical']
    require([staged.get(0x420+4*n) for n in range(7)]==[257,root,root+256,0,context,0,0],
            'original checked storage command or root differs')
    require(translate(memory,satp,root,15)==physical,'private storage table translation differs')
    expected=(0x47534201,64,1,context,0,0,0,1,1,1,
              STORAGE_BASE,STORAGE_BASE+8192,0,STORAGE_BASE,STORAGE_BASE+8192,0)
    require(memory.read(physical,64)==struct.pack('<16I',*expected) and
            memory.read(physical+64,192)==bytes(192),'same-GEM UC descriptor or unused rows differ')
    for offset in (0,4095,4096,8191,source.parameters(description['sourceCase'])['target_offset']):
        require(translate(memory,satp,STORAGE_BASE+offset,15,cache_policy=2)==kernarg+offset and
                translate(memory,satp,0x80800000+offset,15,cache_policy=0)==kernarg+offset,
                'cached/UC aliases lost their exact shared physical word or cache policy')


def returned_outputs(initial,returned,description):
    return _outputs(initial,returned,description,recipe_function=recipe)[0]


def verify_fault(initial,returned,description,mode,*,target_attempts):
    require(mode=='accepted-write','foreign UC cache alias fault mode')
    outputs,count=_outputs(initial,returned,description,failed=mode,
        target_attempts=target_attempts,recipe_function=recipe)
    verify_byte_changes(initial,returned,outputs)
    return count
