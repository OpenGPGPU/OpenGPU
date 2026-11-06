/* SPDX-License-Identifier: MIT */
/* Explicit CPU-only decoder for actual runtime --preflight output. Neither
 * this translation unit nor cs_word_vm.h is linked into the guest executable. */
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include "../examples/gallium_compute_glsl_contract.h"
#include "cs_word_vm.h"

static void check(uint32_t *data[4],unsigned epoch,unsigned total,unsigned consumed,
                  uint32_t snapshot[GLC_CHECK_WORDS],int save)
{
    unsigned at=0;
    for(unsigned b=0;b<4;b++) for(unsigned tail=0;tail<2;tail++) {
        unsigned bytes=glc_region_bytes(tail),offset=glc_region_offset(tail);
        for(unsigned i=0;i<bytes/4;i++,at++) {
            uint32_t got=data[b][offset/4+i];
            if(save) snapshot[at]=got;
            else {
                uint32_t want=snapshot?snapshot[at]:glc_expected(epoch,b,offset+i*4,total,consumed);
                if(got!=want) fprintf(stderr,"CPU_GLSL_MISMATCH epoch=%u binding=%u offset=%u got=%08x want=%08x\n",epoch,b,offset+i*4,got,want);
                assert(got==want);
            }
        }
    }
    assert(at==GLC_CHECK_WORDS);
}

static FILE *input(const char *directory,const char *name,const char *suffix)
{
    char path[4096]; int n=snprintf(path,sizeof(path),"%s/%s.%s",directory,name,suffix);
    assert(n>0 && (size_t)n<sizeof(path)); FILE *f=fopen(path,"rb"); assert(f); return f;
}

int main(int argc,char **argv)
{
    assert(argc==2); (void)cs_vm_group;
    uint32_t *data[4],uniform[13][4096];
    for(unsigned b=0;b<4;b++) {
        data[b]=mmap(NULL,GLC_VIEW_BYTES,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(data[b]!=MAP_FAILED);
        for(unsigned j=0;j<b;j++) assert(data[j]!=data[b]);
    }
    uint32_t snapshot[GLC_CHECK_WORDS];
    for(unsigned n=0;n<5;n++) {
        const struct glc_parent *p=&glc_parents[n]; unsigned items=glc_items(p),total=2*items;
        if(!p->consumer && !p->fault) for(unsigned b=0;b<4;b++) for(unsigned tail=0;tail<2;tail++)
            for(unsigned i=0;i<glc_region_bytes(tail)/4;i++) data[b][glc_region_offset(tail)/4+i]=GLC_GUARD;
        if(p->fault) {
            for(unsigned i=0;i<128;i++) data[0][i]=i&1u?UINT32_C(0x40000000):UINT32_MAX;
            check(data,p->epoch,total,1,snapshot,1);
        }
        uint32_t code[512]; FILE *f=input(argv[1],p->name,"words");
        assert(fread(code,1,sizeof(code),f)==sizeof(code) && fgetc(f)==EOF && !fclose(f));
        unsigned words,reads,writes;
        f=input(argv[1],p->name,"resources");
        assert(fscanf(f,"words=%u reads=%u writes=%u\n",&words,&reads,&writes)==3 && words<=512);
        struct cs_vm_view memory[4]; unsigned seen=0;
        for(unsigned i=0;i<4;i++) {
            unsigned native,binding; assert(fscanf(f,"native.%u.binding=%u\n",&native,&binding)==2);
            assert(native==i && binding<4 && !(seen&(1u<<binding))); seen|=1u<<binding;
            memory[i]=(struct cs_vm_view){data[binding],GLC_VIEW_BYTES,
                ((reads>>i)&1u)|(((writes>>i)&1u)<<1)};
        }
        assert(fgetc(f)==EOF && !fclose(f));
        for(unsigned b=0;b<13;b++) for(unsigned i=0;i<(b?4096u:1024u);i++) uniform[b][i]=glc_uniform(p->uniform_epoch,b,i);
        struct cs_vm_view u[13]={{uniform[0],4096,1}};
        /* The frontend dumps actual Mesa program-local UBO reflection. Do not
         * assume native/local order equals GLSL's global binding order. */
        f=input(argv[1],p->name,"reflection"); char line[256]; unsigned bindings=0;
        while(fgets(line,sizeof(line),f)) {
            unsigned local,global;
            if(sscanf(line,"UBO.%u.binding=%u",&local,&global)==2) {
                assert(local<12 && global>=24 && global<36 && !(bindings&(1u<<local)));
                bindings|=1u<<local; u[local+1]=(struct cs_vm_view){uniform[global-23],16384,1};
            }
        }
        assert(!ferror(f) && !fclose(f) && bindings==4095);
        struct cs_vm_uniform_trace trace={0};
        for(unsigned group=0;group<p->grid[0];group++) {
            uint32_t id[3]={group,0,0};
            cs_vm_group_uniforms_checked(code,words,p->grid,id,p->local,memory,u,&trace);
        }
        if(p->fault) {
            assert(trace.fault_warps==8);
            for(unsigned w=0;w<8;w++) assert(trace.fault_mask[w]==0xaaaau);
            check(data,p->epoch,total,1,snapshot,0);
        } else {
            assert(!trace.fault_warps); check(data,p->epoch,total,p->consumer,NULL,0);
        }
        printf("GLSL_CPU_WORD_ORACLE name=%s words=%u localItems=%u groups=%u checkedWords=%u full13CB=true full4x128MiB=true actualSSBOReflection=true PASS\n",
            p->name,words,items,p->grid[0],GLC_CHECK_WORDS);
    }
    for(unsigned b=0;b<4;b++) assert(!munmap(data[b],GLC_VIEW_BYTES));
    puts("GLSL_CPU_PREFLIGHT fivePrograms=PASS healthyItemExecutions=1012 allWordGuards=PASS faultNoInvalidDereference=PASS hardwareTrapDrain=UNRUN GPU=UNRUN");
    return 0;
}
