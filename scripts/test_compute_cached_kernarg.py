#!/usr/bin/env python3
"""CPU controls for explicit production cached kernargs, not DUT evidence."""
from copy import deepcopy
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest
import sys
from types import SimpleNamespace
from contextlib import redirect_stdout
import io
from unittest.mock import patch

from compute_counter_source import translate
from compute_global_atomic_cases import CASES, bound_words
from compute_global_atomic_source import CACHED_SCHEMA, SCHEMA, ready
from compute_owner_oracle import verify
from owner_resource_contract import IMAGE_FLOAT_PROFILE
from test_compute_global_atomic_source import fixture
from test_compute_host import write_bodies

ROOT=Path(__file__).resolve().parents[1]


class CachedKernarg(unittest.TestCase):
    def setUp(self):
        (ROOT/'.artifacts').mkdir(exist_ok=True)

    def test_producer_forwards_selected_cache_mode_to_every_original_source(self):
        import compute_global_host_producer as producer
        with tempfile.TemporaryDirectory(prefix='cached-argv-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);profile=out/'profile.bin';profile.write_bytes(b'OGCP0003CPU fixture')
            def build(path,**_kwargs):path.mkdir();return path/'original-caller'
            for cached in (False,True):
                build_dir=out/('cached' if cached else 'uncached')
                argv=['producer','--build',str(build_dir),'--profile-wire',str(profile),'--case','all','--capture-only']
                if cached:argv+=['--cached-kernarg']
                with patch.object(sys,'argv',argv),patch.object(producer,'build',side_effect=build),\
                        patch.object(producer.subprocess,'run',return_value=SimpleNamespace(returncode=0)) as run,\
                        redirect_stdout(io.StringIO()):producer.main()
                self.assertEqual(len(run.call_args_list),24)
                for case,call in zip(CASES,run.call_args_list):
                    command=call.args[0]
                    self.assertEqual(command[5],case)
                    self.assertEqual(command[6:],(['--cached-kernarg'] if cached else [])+['--capture-only'])

    def test_real_linux_bind_maps_both_pages_and_rejects_foreign_flags_without_replacement(self):
        with tempfile.TemporaryDirectory(prefix='cached-kernarg-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);write_bodies(out,full_profile=IMAGE_FLOAT_PROFILE)
            source=out/'cached.c'
            source.write_text('#define OPENGPU_COMPUTE_HOST_TEST_MAIN 1\n#define OPENGPU_COMPUTE_HOST_TEST_NO_MAIN 1\n'+
                '#include "'+str(ROOT/'driver/tests/opengpu_compute_host_fixture.c')+'"\n'+r'''
int main(void) {
 struct compute_host_session *s=compute_host_open(test_profile,ARRAY_SIZE(test_profile));assert(s);
 uint32_t context;assert(!compute_host_context_create(s,&context));
 assert(compute_host_original_allocate(s,1,8192));uint64_t physical,bytes;
 assert(!compute_host_original_physical(s,1,&physical,&bytes) && bytes==8192);
 struct drm_opengpu_resource b={.context_id=context,.slot=2,.handle=1,
  .type=OPENGPU_RESOURCE_COMPUTE_KERNARG,.size=8192};
 const u32 modes[]={0,OPENGPU_RESOURCE_CACHED,OPENGPU_RESOURCE_UNCACHED,OPENGPU_RESOURCE_CACHED};
 for(unsigned n=0;n<ARRAY_SIZE(modes);n++) {
  b.flags=modes[n];assert(!compute_host_bind(s,&b));
  struct opengpu_resource_binding *binding=s->context->bindings[1];assert(binding);
  const u32 offsets[]={0,4095,4096,8191};
  for(unsigned i=0;i<ARRAY_SIZE(offsets);i++) {
   u32 flags;assert(translate(&s->context->vm,binding->va+offsets[i],&flags)==physical+offsets[i]);
   assert(flags==((modes[n]&OPENGPU_RESOURCE_CACHED)?0xcfu:0x2cfu));
  }
 }
 struct opengpu_resource_binding *saved=s->context->bindings[1];
 for(unsigned type=OPENGPU_RESOURCE_SHADER;type<=OPENGPU_RESOURCE_COMPUTE_STORAGE;type++) {
  if(type==OPENGPU_RESOURCE_COMPUTE_KERNARG)continue;
  b.type=type;b.flags=OPENGPU_RESOURCE_CACHED;
  assert(compute_host_bind(s,&b)==-EINVAL && s->context->bindings[1]==saved);
 }
 b.type=OPENGPU_RESOURCE_COMPUTE_KERNARG;b.flags=OPENGPU_RESOURCE_CACHED|OPENGPU_RESOURCE_UNCACHED;
 assert(compute_host_bind(s,&b)==-EINVAL && s->context->bindings[1]==saved);
 b.flags=OPENGPU_RESOURCE_CACHED;s->context->vm.enabled=false;
 assert(compute_host_bind(s,&b)==-EOPNOTSUPP && s->context->bindings[1]==saved);
 s->context->vm.enabled=true;
 u32 flags;assert(translate(&s->context->vm,saved->va,&flags)==physical && flags==0xcf);
 assert(!compute_host_destroy(s) && !live_allocations && !fixture_buffers && !compute_running);
 return 0;
}
''')
            command=shlex.split(os.environ.get('CC','cc'))+['-std=c11','-D_POSIX_C_SOURCE=200809L',
                '-O1','-Wall','-Wextra','-Werror','-Wno-unused-function','-fsanitize=address,undefined',
                '-fno-omit-frame-pointer']
            command+=['-I'+str(p) for p in (out,ROOT/'driver',ROOT/'driver/tests/mmu_stubs',ROOT/'driver/tests/compute_submit_stubs')]
            built=subprocess.run([*command,str(source),'-o',str(out/'test')],capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stderr)
            ran=subprocess.run([str(out/'test')],capture_output=True,text=True,
                env=dict(os.environ,ASAN_OPTIONS='halt_on_error=1:abort_on_error=1',UBSAN_OPTIONS='halt_on_error=1'))
            self.assertEqual(ran.returncode,0,ran.stderr)

    def test_cached_and_uncached_leaf_permissions_are_exact_and_explicit(self):
        before,_,_=fixture(CASES[0]);satp=0x80400050
        before.data[0x50000]=bytearray(4096);before.data[0x51000]=bytearray(4096)
        struct.pack_into('<I',before.data[0x50000],(0x80800000>>22)*4,0x51<<10|1)
        for policy in (0,2):
            struct.pack_into('<2I',before.data[0x51000],0,(0x20<<10)|(policy<<8)|0xcf,(0x21<<10)|(policy<<8)|0xcf)
            for offset in (0,4095,4096,8191):
                self.assertEqual(translate(before,satp,0x80800000+offset,15,cache_policy=policy),0x20000+offset)
                with self.assertRaises(ValueError):translate(before,satp,0x80800000+offset,15,cache_policy=2-policy)
        for policy in (True,1,-1,3):
            with self.assertRaises(ValueError):translate(before,satp,0x80800000,15,cache_policy=policy)

    def test_all_original_words_literal_results_and_allocation_guards_remain_required(self):
        for case in CASES:
            before,after,d=fixture(case);d['schema']=CACHED_SCHEMA
            verify(before,after,d)
            with self.assertRaises(ValueError):verify(before,before,d)
            for address,offset in ((0x10000,0),(0x20000,128),(0x20000,160),(0x30000,0),(0x40000,4095)):
                bad=deepcopy(after);bad.data[address][offset]^=1
                with self.assertRaises(ValueError):verify(before,bad,d)
            bad=deepcopy(before);bad.data[0x10000][0]^=1
            with self.assertRaises(ValueError):verify(bad,after,d)

    def test_ready_pins_selected_policy_and_retains_exact_original_code(self):
        for case in CASES:
            before,_,d=fixture(case);d['schema']=CACHED_SCHEMA;satp=0x80400050
            before.data[0x50000]=bytearray(4096);before.data[0x51000]=bytearray(4096)
            struct.pack_into('<I',before.data[0x50000],(0x80800000>>22)*4,0x51<<10|1)
            struct.pack_into('<2I',before.data[0x51000],0,0x20<<10|0xcf,0x21<<10|0xcf)
            staged={0xcc:0xc0400000,0xd0:0x80800000,0x14c:satp,0x150:satp,
                **{0xd4+4*n:v for n,v in enumerate((4,1,1))},**{0xe0+4*n:v for n,v in enumerate((16,1,1))}}
            code=['%08x'%word for word in bound_words(case)];profile=dict(ownerProfile=IMAGE_FLOAT_PROFILE)
            with patch('compute_global_atomic_source.profile_bytes'),\
                    patch('compute_global_atomic_source.capture',return_value=(None,None,[(0x20000,0x22000)],[],None,staged)),\
                    patch('compute_global_atomic_source.executable_physical',side_effect=lambda _m,_s,va:0x30000+va-0xc0400000):
                ready(before,d,b'explicit CPU capture',code,profile,cached=True)
                with self.assertRaises(ValueError):ready(before,d,b'',code,profile)
                wrong=dict(d,schema=SCHEMA)
                with self.assertRaises(ValueError):ready(before,wrong,b'',code,profile,cached=True)
                for bit in (1,2,4,8,0x10,0x40,0x80,0x100,0x200):
                    old=before.data[0x51000][0:4]
                    struct.pack_into('<I',before.data[0x51000],0,struct.unpack('<I',old)[0]^bit)
                    with self.assertRaises(ValueError):ready(before,d,b'',code,profile,cached=True)
                    before.data[0x51000][0:4]=old


if __name__=='__main__':unittest.main()
