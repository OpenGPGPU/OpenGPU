#!/usr/bin/env python3
"""CPU admission of source cache warming; this is not a residency proof."""
import os
from pathlib import Path
import shlex
import struct
import subprocess
import tempfile
import unittest
import compute_cache_alias_cases as source
from compute_global_atomic_cases import events,FUNCT5

ROOT=Path(__file__).resolve().parents[1]


class CacheAliasSources(unittest.TestCase):
    def test_encoded_target_amos_retain_every_original_operand_and_cas_comparison(self):
        inverse={value:key for key,value in FUNCT5.items()}
        for case in source.CASES:
            values={0:0,1:('root',0)};actual=[];barrier=[]
            for word in source.words(case):
                opcode=word&127;rd=word>>7&31;rs1=word>>15&31;rs2=word>>20&31
                if opcode==0x37:values[rd]=word&0xfffff000
                elif opcode==0x13:
                    immediate=word>>20;immediate-=4096 if immediate&2048 else 0
                    base=values.get(rs1)
                    values[rd]=(('root',base[1]+immediate) if isinstance(base,tuple) else
                        (base+immediate)&0xffffffff if isinstance(base,int) else None)
                elif opcode==0x2f:
                    self.assertEqual(values.get(rs1),('root',source.parameters(case)['target_offset'])
                        if values.get(rs1)!=('root',source.READY_OFFSET) else ('root',source.READY_OFFSET))
                    operation=inverse[word>>27];operand=values.get(rs2)
                    if values.get(rs1)==('root',source.READY_OFFSET):barrier.append((operation,operand))
                    else:actual.append(dict(operation=operation,operand=operand,
                        compare=values.get(rd) if operation=='compare-swap' else 0))
                    values[rd]=None
                elif opcode==0x57 and word>>26==0x10 and word>>12&7==2:values[rd]=None
            self.assertEqual(actual,events(case))
            self.assertEqual(barrier,[('add',1)]+[('add',0)]*64+[('add',1)]+[('add',0)]*64)

    def test_both_resident_callers_must_observe_the_barrier_before_target_amos(self):
        for case in source.CASES:
            code=source.words(case);targets={}
            for index,word in enumerate(code):
                if word&127!=0x63 or word>>12&7:continue
                self.assertEqual((word>>15&31,word>>20&31),(12,10))
                displacement=((word>>31)<<12)|((word>>7&1)<<11)|((word>>25&63)<<5)|((word>>8&15)<<1)
                displacement-=8192 if displacement&4096 else 0
                self.assertGreater(displacement,0);self.assertEqual(displacement%4,0)
                target=index+displacement//4;self.assertLess(target,len(code))
                self.assertEqual(code[target-1],source.CEASE)
                self.assertEqual(code[target],0x00008593) # addi x11,x1,0 restores the original root
                self.assertEqual(code[index-1]&127,0x2f)
                targets[target]=targets.get(target,0)+1
            self.assertEqual(sorted(targets.values()),[64,64])

    def test_original_operands_endpoints_and_guarded_uploads_are_retained(self):
        for case in source.CASES:
            p=source.parameters(case);data=source.initial_kernarg(case);code=source.words(case)
            self.assertEqual(len(data),8192);self.assertLessEqual(len(code)*4,4096)
            self.assertEqual(struct.unpack_from('<I',data,p['target_offset'])[0],p['seed'])
            self.assertEqual(struct.unpack_from('<8I',data,128),(source.GUARD,)*8)
            self.assertEqual(struct.unpack_from('<I',data,source.READY_OFFSET)[0],0)
            self.assertEqual(struct.unpack_from('<4I',data,source.WARM_OFFSET),(source.GUARD,)*4)
            self.assertEqual(struct.unpack_from('<4I',data,source.REFETCH_OFFSET),(source.GUARD,)*4)
            self.assertEqual(code.count(0x0205e187),8)
            for event in events(case):self.assertIn(FUNCT5[event['operation']],{w>>27 for w in code if w&127==0x2f})

    def test_all_original_sources_pass_actual_production_shader_admission(self):
        (ROOT/'.artifacts').mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(prefix='cache-alias-admission-',dir=ROOT/'.artifacts') as temporary:
            out=Path(temporary);rows=[]
            for n,case in enumerate(source.CASES):
                code=source.words(case)
                rows.append('static const uint32_t code_%u[]={%s};'%(n,','.join(hex(w)+'u' for w in code)))
            rows.append('static const struct program programs[]={'+','.join(
                '{code_%u,sizeof(code_%u)/sizeof(uint32_t)}'%(n,n) for n in range(24))+'};')
            program='#include <assert.h>\n#include <stdint.h>\n#include "opengpu_shader_validator.h"\nstruct program{const uint32_t *words;unsigned count;};\n'+'\n'.join(rows)+r'''
int main(void) {
 for(unsigned n=0;n<24;n++) {
  const struct program *p=&programs[n];
  assert(opengpu_compute_shader_validate_words_features(p->words,p->count,8192,16,0,1));
  assert(!opengpu_compute_shader_validate_words_features(p->words,p->count,8192,16,0,0));
 }
}
'''
            (out/'test.c').write_text(program)
            built=subprocess.run([*shlex.split(os.environ.get('CC','cc')),'-std=c11','-O1','-Wall','-Wextra','-Werror',
                '-I'+str(ROOT/'driver'),str(out/'test.c'),'-o',str(out/'test')],capture_output=True,text=True)
            self.assertEqual(built.returncode,0,built.stderr)
            ran=subprocess.run([str(out/'test')],capture_output=True,text=True)
            self.assertEqual(ran.returncode,0,ran.stderr)


if __name__=='__main__':unittest.main()
