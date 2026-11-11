/* SPDX-License-Identifier: MIT */
/* CPU-only independent decoder. This translation unit is never in the guest
 * executable; the runtime instead submits through the real Gallium callbacks. */
#include <assert.h>
#include <stdio.h>
#include <sys/mman.h>
#include "../examples/gallium_compute_program.h"
#include "opengpu_cs_emit.h"
#include "opengpu_shader_validator.h"
#include "cs_word_vm.h"

static void check(struct cs_vm_view views[4],unsigned total,uint32_t seed,unsigned consumed)
{
    for(unsigned b=0;b<4;b++)for(unsigned tail=0;tail<2;tail++){
        unsigned bytes=tail?GC_TAIL_BYTES:GC_PREFIX_BYTES,offset=tail?GC_VIEW_BYTES-GC_TAIL_BYTES:0;
        for(unsigned word=0;word<bytes/4;word++){
            unsigned absolute=offset+4*word,item=absolute/4;uint32_t want=GC_GUARD;
            if(absolute==GC_VIEW_BYTES-4)item=total-1;
            if((!b || consumed) && (item<total-1 || absolute==GC_VIEW_BYTES-4))want=gc_expected(b,item,seed);
            assert(views[b].data[absolute/4]==want);
        }
    }
}

int main(void)
{
    {
        struct gc_program tgsi;struct opengpu_cs_program code;
        uint32_t args[16]={0},results[16];
        for(unsigned i=0;i<16;i++)results[i]=GC_GUARD;
        struct cs_vm_view m[4]={{args,sizeof(args),3},{results,sizeof(results),3},{0},{0}};
        const uint32_t local[3]={4,1,1},one[3]={1,1,1},two[3]={2,1,1};
        uint32_t group[3]={0,0,0};
        gc_indirect_build(&tgsi);assert(!opengpu_cs_emit(tgsi.tokens,&code));
        assert(!code.reads && code.writes==3 &&
               opengpu_compute_shader_validate_words_storage(code.code,512,12,4,false));
        cs_vm_group(code.code,code.words,one,group,local,m);
        for(unsigned i=0;i<16;i++){
            assert(args[i]==(i==0?2u:i<4?1u:0u));
            assert(results[i]==(i<4?100u+i:GC_GUARD));
        }
        for(unsigned x=0;x<2;x++){group[0]=x;cs_vm_group(code.code,code.words,two,group,local,m);}
        for(unsigned i=0;i<16;i++){
            assert(args[i]==(i==0?2u:i<8?1u:0u));
            assert(results[i]==(i<8?100u+i:GC_GUARD));
        }
        puts("Gallium indirect program CPU word oracle: producer and two groups/guards PASS; GPU=unrun");
    }
    struct cs_vm_view views[4];
    for(unsigned b=0;b<4;b++){
        void *p=mmap(NULL,GC_VIEW_BYTES,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);
        assert(p!=MAP_FAILED);views[b]=(struct cs_vm_view){p,GC_VIEW_BYTES,3};
        for(unsigned prior=0;prior<b;prior++)assert(views[b].data!=views[prior].data);
    }
    for(unsigned epoch=1;epoch<=3;epoch+=2){
        const uint32_t local[3]={epoch==1?8u:5u,epoch==1?4u:5u,epoch==1?4u:5u},grid[3]={2,1,1};
        unsigned items=local[0]*local[1]*local[2],total=2*items;
        uint32_t seed=epoch==1?UINT32_C(0x12345):UINT32_MAX;
        for(unsigned b=0;b<4;b++)for(unsigned tail=0;tail<2;tail++){
            unsigned bytes=tail?GC_TAIL_BYTES:GC_PREFIX_BYTES,offset=tail?GC_VIEW_BYTES-GC_TAIL_BYTES:0;
            for(unsigned word=0;word<bytes/4;word++)views[b].data[offset/4+word]=GC_GUARD;
        }
        for(unsigned consumer=0;consumer<2;consumer++){
            struct gc_program tgsi;struct opengpu_cs_program code;
            gc_build(&tgsi,seed,(int)consumer);assert(!opengpu_cs_emit(tgsi.tokens,&code));
            assert(opengpu_compute_shader_validate_words_storage(code.code,512,12,items,false));
            for(unsigned b=0;b<4;b++)views[b].access=1u|((consumer?b!=0:b==0)?2u:0u);
            for(unsigned x=0;x<2;x++){
                uint32_t group[3]={x,0,0};cs_vm_group(code.code,code.words,grid,group,local,views);
            }
            check(views,total,seed,consumer);
            printf("Gallium runtime CPU word oracle:epoch=%u role=%c words=%u items=%u full4x128MiB/last-word UCMP/prefix+tail guards PASS\n",
                epoch,consumer?'c':'p',code.words,total);
        }
        /* The three-byte negative is intentionally not fabricated by this
         * decoder. Admission/words are identical; real fault execution is a
         * later guest gate, before any result store. */
        assert(gc_offset(0,128)==0 && 4>3 && gc_offset(127,128)==GC_VIEW_BYTES-4);
    }
    for(unsigned b=0;b<4;b++)assert(!munmap(views[b].data,GC_VIEW_BYTES));
    puts("Gallium runtime CPU preflight:4 actual TGSI programs/1012 item executions,independent uint32 oracle PASS; GPU=unrun");
    return 0;
}
