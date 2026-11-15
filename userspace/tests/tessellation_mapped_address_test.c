/* SPDX-License-Identifier: MIT */
/* Test-only literal address blocks from actual shared emitter helpers.
 * These are not ESSL programs, shader interpretation or GPU qualification. */
#ifndef OPENGPU_TESS_MAPPED_SOURCE
#define OPENGPU_TESS_MAPPED_SOURCE "../gallium/opengpu_native_tess_uniform_emit.c"
#endif
#include OPENGPU_TESS_MAPPED_SOURCE
#include <assert.h>
#include <stdio.h>
static void dump(const struct builder *b,const char *directory,const char *name)
{
    assert(!b->failed&&b->p.words&&b->p.words<=512);
    char path[4096];int n=snprintf(path,sizeof(path),"%s/%s.words",directory,name);
    assert(n>0&&(size_t)n<sizeof(path));FILE *f=fopen(path,"wb");assert(f);
    for(unsigned i=0;i<b->p.words;i++)for(unsigned j=0;j<4;j++)assert(fputc(b->p.code[i]>>(8*j)&255,f)!=EOF);
    assert(!fclose(f));
}
int main(int argc,char **argv)
{
    assert(argc==2);
    for(unsigned width=1;width<=4;width++){
        struct builder b={0};mapped_descriptor(&b,20,width,31,43);
        char name[16];assert(snprintf(name,sizeof(name),"width%u",width)>0);dump(&b,argv[1],name);
    }
    for(unsigned pp=0;pp<=1;pp++){
        struct opengpu_native_tess_packing pin={0},pout={0};struct builder b={0};
        b.p.layout=(struct opengpu_native_tess_layout){.version=3,.stage=4,.output_vertices=32,.output_components=10,.patch_components=6,.owned_tessellation_info=1};
        b.packing[0]=&pin;b.packing[1]=&pout;b.addr_defined=3;
        unsigned first=pp?4:2,semantic=pp?TGSI_SEMANTIC_PATCH:TGSI_SEMANTIC_GENERIC,index=pp?0:2;
        b.output[!pp][first]=(struct semantic){7,semantic,index};
        b.p.output_map.version=1;b.p.output_map.count=1;
        b.p.output_map.spans[0]=(struct opengpu_native_tess_input_span){first,2,!pp,semantic,index,7,0};
        struct opengpu_native_tess_packed_slot *slot=pp?pout.patch:pout.vertex;
        slot[index]=(struct opengpu_native_tess_packed_slot){7,pp?0:4};slot[index+1]=(struct opengpu_native_tess_packed_slot){7,pp?3:7};
        struct tgsi_full_dst_register r={0};r.Register.Index=first;r.Register.Dimension=!pp;r.Register.Indirect=1;r.Indirect.Index=0;
        r.Dimension.Indirect=!pp;r.Dimension.Index=pp?0:1;r.DimIndirect.Index=1;
        assert(!dynamic_store(&b,&r,pp?0:4,pp,0));
        if(!pp)assert(b.p.output_mask==((UINT64_C(1)<<4)|(UINT64_C(1)<<7)));
        dump(&b,argv[1],pp?"store-patch":"store-vertex");
        if(!pp){
            assert(!dynamic_store(&b,&r,5,0,0)&&!dynamic_store(&b,&r,6,0,0));
            assert(b.p.output_mask==UINT64_C(0x3f0)); /* All xyz of both legal columns. */
        }
    }
    return 0;
}
