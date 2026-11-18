#!/usr/bin/env python3
"""Original Linux admission and independent CPU controls, never DUT evidence."""
from copy import deepcopy
from contextlib import redirect_stdout
import argparse
import io
import json
import os
from pathlib import Path
import shlex
import struct
import resource
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import sys

import compute_uc_cache_alias_cases as source
from compute_global_atomic_cases import bound_words as original_words
from owner_resource_contract import IMAGE_FLOAT_PROFILE
from test_compute_host import write_bodies
from test_compute_cache_alias_source import returned as cached_returned
from compute_owner_oracle import verify
import compute_uc_cache_alias_source as contract

ROOT=Path(__file__).resolve().parents[1]


def native_source_controls(build,profile_wire,request):
    """Reject mixed modes in the actual original Linux C caller."""
    from compute_global_host_inputs import Inputs
    guard=Inputs(request,ROOT);commands=json.loads((build/'commands.json').read_text())
    guard.prepare(build,[(c['label'],c['argv']) for c in commands[:-1]],commands[-1]['argv'])
    guard.before_link();guard.built(commands,build/'compute_global_host')
    resource.setrlimit(resource.RLIMIT_CORE,(0,0))
    case=source.CASES[0];inputs=build/'inputs'
    options=(
        ('--uc-cache-alias-source','--capture-only'),
        ('--cached-kernarg','--uc-cache-alias-source'),
        ('--cached-kernarg','--uc-cache-alias-source','--capture-only','--cache-alias-source'),
        ('--cached-kernarg','--uc-cache-alias-source','--capture-only','--cache-alias-live'),
        ('--cached-kernarg','--uc-cache-alias-source','--capture-only','--expect-global-fault','read'),
        ('--cached-kernarg','--uc-cache-alias-source','--capture-only','--expect-cache-alias-write-error'),
        ('--cached-kernarg','--uc-cache-alias-source','--capture-only','--uc-cache-alias-source'),
        ('--cached-kernarg','--uc-cache-alias-source','--capture-only','--cached-kernarg'),
    )
    root=build/'uc-source-option-controls';root.mkdir()
    for index,flags in enumerate(options):
        local=root/str(index);local.mkdir()
        command=[str(build/'compute_global_host'),str(local),str(profile_wire),
                 str(inputs/(case+'.bin')),str(inputs/(case+'.kernarg')),case,*flags]
        result=subprocess.run(command,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=10)
        assert result.returncode and b'Assertion' in result.stderr,(flags,result.stderr)
        assert not (local/'READY').exists(),'mixed UC source options reached admission'
    guard.unchanged(full=True)
    print('original Linux UC source option controls: 8 rejections PASS; no DUT execution',flush=True)


def returned(case,**kwargs):
    before,after,d=cached_returned(case,**kwargs)
    code=struct.pack('<%uI'%len(source.bound_words(case)),*source.bound_words(case))
    for memory in (before,after):
        memory.data[0x10000]=bytearray(code+bytes(4096-len(code)))
        memory.data[0x30000]=bytearray(code+b'\xf9'*(4096-len(code)))
        memory.data[0x50000]=bytearray(4096)
        memory.ranges=[(first,first+len(data),0) for first,data in sorted(memory.data.items())]
    d.update(schema=contract.SCHEMA,codeBytes=len(code),storageAlias=deepcopy(d['kernarg']),
        storageSlot=13,storageBinding=0,descriptorPhysical=0x50000,descriptorRoot=0xc0003000,
        retainedObjects=1,temporaryMappings=2)
    d['shader']['bytes']=len(code)
    return before,after,d


def trace(case,group,*,ready=True):
    """Decode real words with explicit CPU reply tokens, not GPU arithmetic."""
    code=source.words(case);x=[0]*32;x[1]=0x80800000;x[2]=group
    v=[None]*32;pc=0;calls=[];reads=[];stores={};params=None;polls=0;target=0;vl=0
    def signed(value,bits):return value-(1<<bits) if value&(1<<(bits-1)) else value
    for _ in range(2000):
        assert 0<=pc<len(code);w=code[pc];next_pc=pc+1
        op=w&127;rd=(w>>7)&31;f=(w>>12)&7;a=(w>>15)&31;b=(w>>20)&31
        if w==0x30500073:return calls,reads,stores,polls
        if w==0x0330000f:pass
        elif op==0x37:x[rd]=w&0xfffff000
        elif op==0x13:
            assert f==0;x[rd]=(x[a]+signed(w>>20,12))&0xffffffff
        elif op==0x63:
            offset=(w>>31)<<12|((w>>7)&1)<<11|((w>>25)&63)<<5|((w>>8)&15)<<1
            assert f in (0,1) and offset>0 and not offset&3
            if (x[a]==x[b])==(f==0):next_pc=pc+offset//4
        elif op==0x07:
            assert (w&0xff000000)==0x02000000 and f==6 and vl==1
            assert x[a]-x[1]==source.parameters(case)['target_offset']
            reads.append(x[a]-x[1]);v[rd]=0x51000000+len(reads)
        elif op==0x23:
            offset=((w>>25)<<5)|((w>>7)&31)
            assert f==2 and a==1 and offset%4==0;stores[offset]=x[b]
        elif op==0x57:
            if w==0xc100f057:vl=1
            elif w>>26==23:
                assert f==4 and vl==1;v[rd]=x[a]
            else:
                assert w>>26==16 and f==2 and a==0 and v[b] is not None;x[rd]=v[b]
        elif op==0x2b:
            assert vl==1 and w&(1<<25)
            function=w>>26
            if function==24:
                assert not rd and not f;params=(v[a],v[b])
            else:
                assert function in (25,26) and params is not None and v[a]==0
                operation=(function-25)*8+f;offset=v[b]
                if offset==source.READY_OFFSET:
                    assert operation==1 and params[1]==0
                    if params[0]==0:
                        polls+=1;v[rd]=2 if ready and polls==64 else 0
                    else:
                        assert group<2 and params[0]==1;v[rd]=0
                else:
                    assert offset==source.parameters(case)['target_offset']
                    calls.append((operation,*params,offset));v[rd]=0xa1000000+target;target+=1
        else:raise AssertionError(hex(w))
        x[0]=0;pc=next_pc
    raise AssertionError('source did not terminate within its explicit CPU bound')


class UcCacheAlias(unittest.TestCase):
    def test_all_original_callers_operands_endpoints_and_full_bounded_poll_are_in_the_words(self):
        operations=('exchange','add','xor','or','and','signed-min','signed-max',
                    'unsigned-min','unsigned-max','compare-swap')
        for case in source.CASES:
            self.assertLessEqual(len(source.bound_words(case)),512)
            self.assertFalse(any(w&127==0x2f for w in source.words(case)))
            for group in range(4):
                calls,reads,stores,polls=trace(case,group)
                expected=[(operations.index(e['operation']),e['operand'],e['compare'],source.parameters(case)['target_offset'])
                          for e in source.events(case)[2*group:2*group+2]]
                self.assertEqual(calls,expected);self.assertEqual(polls,64)
                self.assertEqual(reads,[source.parameters(case)['target_offset']]*2)
                self.assertEqual(set(stores),{source.WARM_OFFSET+4*group,source.REFETCH_OFFSET+4*group,
                    source.OUTPUT_OFFSET+8*group,source.OUTPUT_OFFSET+8*group+4})
                self.assertEqual([stores[source.OUTPUT_OFFSET+8*group+i*4] for i in range(2)],
                    [0xa1000000,0xa1000001])
                calls,reads,stores,polls=trace(case,group,ready=False)
                self.assertFalse(calls);self.assertEqual(polls,64)
                self.assertEqual(set(stores),{source.WARM_OFFSET+4*group} if group<2 else set())

    def test_distinct_recipe_ordered_histories_and_every_physical_guard(self):
        for case in source.CASES:
            before,after,d=returned(case);contract.recipe(before,d);verify(before,after,d)
            with self.assertRaises(ValueError):verify(before,before,d)
            for change in ({'schema':'opengpu-compute-cache-alias-oracle-v1'},
                    {'retainedObjects':2},{'temporaryMappings':1},{'storageSlot':14}):
                with self.assertRaises(ValueError):verify(before,after,dict(d,**change))
            for field in ('handle','allocationPhysical','allocationBytes','offset','bytes'):
                bad=deepcopy(d);bad['storageAlias'][field]+=1
                with self.assertRaises(ValueError):verify(before,after,bad)
            for address,offset in ((0x10000,0),(0x30000,4095),(0x40000,0),(0x50000,0),
                    (0x80000,0),(0x20000,192),(0x20000,256),(0x20000,320),
                    (0x20000,128),(0x20000,source.parameters(case)['target_offset'])):
                wrong=deepcopy(after);wrong.data[address][offset]^=1
                with self.assertRaises(ValueError):verify(before,wrong,d)
            for failed in (0,1):
                for admitted in (2,3,4):
                    before,after,d=returned(case,failed=failed,admitted=admitted)
                    attempts=1+2*(admitted-1)
                    self.assertEqual(contract.verify_fault(before,after,d,'accepted-write',target_attempts=attempts),
                                     2*(admitted-1))
                    wrong=deepcopy(after);wrong.data[0x50000][0]^=1
                    with self.assertRaises(ValueError):contract.verify_fault(before,wrong,d,'accepted-write',target_attempts=attempts)

    def test_capture_mode_and_source_are_explicit_for_all_original_cases(self):
        import compute_global_host_producer as producer
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='uc-cache-argv-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);profile=out/'profile.bin';profile.write_bytes(b'OGCP0003explicit CPU fixture')
            def build(path,**_kwargs):path.mkdir();return path/'compute_global_host'
            argv=['producer','--build',str(out/'build'),'--profile-wire',str(profile),'--case','all',
                  '--capture-only','--cached-kernarg','--uc-cache-alias-source']
            with patch.object(sys,'argv',argv),patch.object(producer,'build',side_effect=build),\
                    patch.object(producer.subprocess,'run',return_value=SimpleNamespace(returncode=0)) as run,\
                    redirect_stdout(io.StringIO()):producer.main()
            self.assertEqual(len(run.call_args_list),24)
            for case,call in zip(source.CASES,run.call_args_list):
                self.assertEqual(call.args[0][5:],
                    [case,'--cached-kernarg','--uc-cache-alias-source','--capture-only'])
            for option in ('--cached-kernarg','--capture-only'):
                with patch.object(sys,'argv',[a for a in argv if a!=option]),\
                        patch('sys.stderr',io.StringIO()),self.assertRaises(SystemExit):producer.main()
            with patch.object(sys,'argv',argv+['--cache-alias-source']),\
                    patch('sys.stderr',io.StringIO()),self.assertRaises(SystemExit):producer.main()

    def test_original_linux_same_gem_policies_reservations_and_shader_admission(self):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='uc-cache-admission-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);write_bodies(out,full_profile=IMAGE_FLOAT_PROFILE)
            programs=[source.bound_words(case) for case in source.CASES]
            arrays='static const uint32_t programs[24][1024]={\n'+''.join(
                '{'+','.join('0x%08xu'%word for word in code)+'},\n' for code in programs)+'};\n'
            arrays+='static const unsigned lengths[24]={'+','.join(str(len(code)) for code in programs)+'};\n'
            code=original_words(source.CASES[0])
            arrays+='static const uint32_t raw[]={'+','.join('0x%08xu'%word for word in code)+'};\n'
            test=out/'test.c'
            test.write_text('#define OPENGPU_COMPUTE_HOST_TEST_MAIN 1\n#define OPENGPU_COMPUTE_HOST_TEST_NO_MAIN 1\n'+
                '#include "'+str(ROOT/'driver/tests/opengpu_compute_host_fixture.c')+'"\n'+arrays+r'''
static void run(unsigned mode,unsigned program) {
 struct compute_host_session *s=compute_host_open(test_profile,ARRAY_SIZE(test_profile));assert(s);
 uint32_t context;assert(!compute_host_context_create(s,&context));
 void *shader=compute_host_original_allocate(s,1,4096);
 assert(shader && compute_host_original_allocate(s,2,8192) && compute_host_original_allocate(s,3,8192));
 const bool word=mode==7 || mode==8;
 const uint32_t *code=word?raw:programs[program];unsigned bytes=word?sizeof(raw):lengths[program]*4;
 memcpy(shader,code,bytes);
 struct drm_opengpu_resource b={.context_id=context,.slot=1,.handle=1,
  .type=OPENGPU_RESOURCE_COMPUTE_SHADER,.size=bytes};assert(!compute_host_bind(s,&b));
 b=(struct drm_opengpu_resource){.context_id=context,.slot=2,.handle=2,
  .type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=8192,
  .flags=mode==2?0:mode==10?OPENGPU_RESOURCE_UNCACHED:OPENGPU_RESOURCE_CACHED};
 assert(!compute_host_bind(s,&b));
 bool separate=mode==3 || mode==4 || mode==5 || mode==8;
 uint64_t saved_dma=s->render.originals[3]->gem.dma_addr;
 struct dma_resv *saved_resv=s->render.originals[3]->gem.base.resv;
 if(mode==4)s->render.originals[3]->gem.dma_addr=s->render.originals[2]->gem.dma_addr;
 if(mode==5)s->render.originals[3]->gem.base.resv=s->render.originals[2]->gem.base.resv;
 b=(struct drm_opengpu_resource){.context_id=context,.slot=13,.handle=mode==6?1:separate?3:2,
  .type=OPENGPU_RESOURCE_COMPUTE_STORAGE,.size=mode==6?4096:8192,
  .flags=mode==1?0:OPENGPU_RESOURCE_UNCACHED};assert(!compute_host_bind(s,&b));
 struct opengpu_resource_binding *arg=s->context->bindings[1],*storage=s->context->bindings[12];
 const uint32_t offsets[]={0,4095,4096,8191};
 for(unsigned i=0;i<ARRAY_SIZE(offsets);i++){
  uint32_t flags;assert(translate(&s->context->vm,arg->va+offsets[i],&flags)==arg->dma+offsets[i]);
  assert(flags==((arg->flags&OPENGPU_RESOURCE_CACHED)?0xcfu:0x2cfu));
  if(mode!=6){assert(translate(&s->context->vm,storage->va+offsets[i],&flags)==storage->dma+offsets[i]);assert(flags==0x2cfu);}
 }
 struct drm_opengpu_compute_storage request={.launch={.context_id=context,.shader_slot=1,.kernarg_slot=2,
  .grid={4,1,1},.local={16,1,1},.out_syncobj=1}};
 request.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,
  OPENGPU_COMPUTE_STORAGE_READ|(mode==9?0:OPENGPU_COMPUTE_STORAGE_WRITE)};
 struct opengpu_compute_storage_prepare prepared={0};prepared.bindings[0]=request.bindings[0];
 int setup=opengpu_compute_prepare_storage(&prepared,s->context,s->context->bindings[0],arg,context);
 bool admitted=mode==0 || mode==3 || mode==8 || mode==9;
 if(admitted || mode==7){
  assert(!setup && prepared.kernarg_alias==(!separate));
  assert(prepared.dep_count==(separate?2u:1u) && prepared.range_count==2);
  assert(prepared.deps[0].object==arg->object && prepared.deps[0].wait_usage==DMA_RESV_USAGE_READ &&
   prepared.deps[0].signal_usage==DMA_RESV_USAGE_WRITE);
 }else assert(setup==-EINVAL);
 int result=compute_host_submit_storage(s,&request);
 if(admitted){
  assert(!result);struct compute_host_snapshot snapshot;assert(!compute_host_snapshot_get(s,&snapshot));
  assert(snapshot.storage_bytes==256 && snapshot.retained_objects==(separate?2u:1u));
  assert(snapshot.code_bytes==bytes && !memcmp(snapshot.code,code,bytes));
  assert(!compute_host_cancel_unexecuted(s));
  assert(compute_host_destroy(s)==-ECANCELED); /* consume the real cancelled fence */
 }else assert(result==-EINVAL && !s->head);
 s->render.originals[3]->gem.dma_addr=saved_dma;s->render.originals[3]->gem.base.resv=saved_resv;
 assert(!compute_host_destroy(s) && !live_allocations && !fixture_buffers && !compute_running);
}
int main(void){
 for(unsigned program=0;program<24;program++)run(0,program);
 for(unsigned mode=1;mode<=10;mode++)run(mode,0);
 puts("original Linux UC/cached same-GEM admission: 24 typed programs and 10 policy/identity/reservation controls PASS; no DUT execution");
 return 0;
}
''')
            flags=['-std=c11','-D_POSIX_C_SOURCE=200809L','-O1','-Wall','-Wextra','-Werror',
                   '-Wno-unused-function','-fsanitize=address,undefined','-fno-omit-frame-pointer']
            includes=[out,ROOT/'driver',ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs']
            command=shlex.split(os.environ.get('CC','cc'))+flags+['-I'+str(path) for path in includes]
            built=subprocess.run([*command,str(test),'-o',str(out/'test')],capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stderr)
            ran=subprocess.run([str(out/'test')],capture_output=True,text=True,
                env=dict(os.environ,ASAN_OPTIONS='halt_on_error=1:abort_on_error=1',UBSAN_OPTIONS='halt_on_error=1'))
            self.assertEqual(ran.returncode,0,ran.stdout+ran.stderr)


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--native-build',type=Path)
    parser.add_argument('--profile-wire',type=Path)
    parser.add_argument('--bound-inputs',type=Path)
    args=parser.parse_args()
    if args.native_build is None:
        if args.profile_wire or args.bound_inputs:parser.error('native options require --native-build')
        unittest.main(argv=[sys.argv[0]])
    else:
        if not args.profile_wire or not args.bound_inputs:parser.error('native controls require profile and bound inputs')
        native_source_controls(args.native_build.resolve(strict=True),args.profile_wire.resolve(strict=True),
                               args.bound_inputs.resolve(strict=True))
