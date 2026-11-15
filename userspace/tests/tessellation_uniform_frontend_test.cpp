/* SPDX-License-Identifier: MIT */
#include "opengpu_tess_frontend.h"
#include "opengpu_native_tess_uniform_emit.h"
#ifdef OPENGPU_TESS_BYTE_BASELINE
extern "C" void tessellation_old_bytes_equal(const void *,unsigned,const opengpu_native_tess_layout *,
    const opengpu_native_tess_input_map *,const opengpu_native_tess_program *);
#endif
#include "tgsi/tgsi_parse.h"
#include <cstring>
#include <cassert>
#include <cstdio>
#include <string>
extern "C" void tessellation_resource_test(void *);
static void run(const char *directory,const char *prefix,const char *name) { const char *suffix[]={"vert","tesc","tese",nullptr,"frag"};
    std::string paths[5];const char *files[5]={};
    for(unsigned i=0;i<5;i++)if(suffix[i]) {paths[i]=std::string(directory)+"/"+name+"."+suffix[i];files[i]=paths[i].c_str();}
    void *p=opengpu_tess_frontend_compile(files,prefix);assert(p);
    for(unsigned stage:{0u,4u,5u,1u}) {
        assert(opengpu_tess_frontend_tokens(p,stage));
        printf("TESS_REFLECTION stage=%u blocks=%u defaults=%u\n",stage,
            opengpu_tess_frontend_blocks(p,stage),opengpu_tess_frontend_components(p,stage));
        for(unsigned i=0;i<opengpu_tess_frontend_blocks(p,stage);i++)
            printf("  logical=%u binding=%u\n",i,opengpu_tess_frontend_binding(p,stage,i));
    }
    {
        opengpu_native_uniform_layout l={};opengpu_native_uniform_program native={};
        const void *vs=opengpu_tess_frontend_tokens(p,0);
        assert(opengpu_native_uniform_compile(vs,0,128,&l,&native)); /* old exactFRAG rejects */
        assert(!opengpu_native_vertex_tess_compile(vs,128,&l,&native));
        assert(l.stage==0&&l.capacity==128&&native.words<=512);
    }
    for(unsigned stage:{4u,5u}) {
        opengpu_native_tess_layout l={}; l.version=1;l.stage=stage;l.capacity=128;
        l.input_vertices=2;l.output_vertices=2;l.input_components=stage==4?4:8;
        l.output_components=8;l.input_position=1;l.output_position=1;l.domain=1;l.spacing=2;
        opengpu_native_tess_program binary={};
        auto *tokens=static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(p,stage));
        assert(!opengpu_native_tess_uniform_emit(tokens,tgsi_num_tokens(tokens),&l,&binary));
#ifdef OPENGPU_TESS_BYTE_BASELINE
        tessellation_old_bytes_equal(tokens,tgsi_num_tokens(tokens),&l,nullptr,&binary);
#endif
        assert(binary.words<=512&&binary.constant_bytes[0]==4096&&binary.constant_bytes[1]==16384);
        if(stage==4)assert(binary.barriers==2);
        else assert(binary.selection_count==8&&binary.output_mask==255);
        printf("ACTUAL_TESS_NATIVE stage=%u words=%u selections=%u\n",stage,binary.words,binary.selection_count);
        std::string path=prefix;path=path.substr(0,path.find_last_of('/'))+(stage==4?"/native-tcs.words":"/native-tes.words");
        FILE *f=fopen(path.c_str(),"wb");assert(f);
        for(unsigned i=0;i<binary.words;i++)for(unsigned byte=0;byte<4;byte++)assert(fputc((binary.code[i]>>(8*byte))&255,f)!=EOF);
        assert(!fclose(f));
        auto saved=binary;l.stage=stage==4?5:4;
        assert(opengpu_native_tess_uniform_emit(tokens,tgsi_num_tokens(tokens),&l,&binary));
        assert(!memcmp(&saved,&binary,sizeof(binary)));
        l.stage=stage;l.version=2;assert(opengpu_native_tess_uniform_emit(tokens,tgsi_num_tokens(tokens),&l,&binary));
        assert(!memcmp(&saved,&binary,sizeof(binary)));
    }
    tessellation_resource_test(p);
    assert(!opengpu_tess_frontend_tokens(p,2));opengpu_tess_frontend_destroy(p);
    puts("Actual ESSL320 linked tess frontend CPU PASS; ISA/GPU not executed");
}
/* Reflection-only five-stage capacity case. It does not claim GS machine
 * emission, original views for all60 references, or execution of any stage. */
static void graphics60(const char *directory) {
    const char *suffix[]={"vert","tesc","tese","geom","frag"};
    std::string paths[5];const char *files[5];
    for(unsigned i=0;i<5;i++){paths[i]=std::string(directory)+"/graphics60."+suffix[i];files[i]=paths[i].c_str();}
    void *p=opengpu_tess_frontend_compile(files,nullptr);assert(p);
    unsigned total=0;
    for(unsigned stage:{0u,4u,5u,3u,1u}){
        unsigned blocks=opengpu_tess_frontend_blocks(p,stage);assert(blocks==12);total+=blocks;
        for(unsigned i=0;i<blocks;i++)assert(opengpu_tess_frontend_binding(p,stage,i)<72);
    }
    assert(total==60);opengpu_tess_frontend_destroy(p);
    puts("Actual five-stage ESSL reflection60 blocks/API72 namespace CPU PASS; no GS ISA/execute claim");
}
int main(int argc,char **argv) {
    setvbuf(stdout,nullptr,_IONBF,0);
    assert(argc==3);
    graphics60(argv[1]);
    for(const char *name:{"uniform","full","conditional"}) {
        std::string prefix=std::string(argv[2])+"/"+name+"/actual";
        run(argv[1],prefix.c_str(),name);
    }
}
