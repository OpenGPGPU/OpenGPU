/* SPDX-License-Identifier: MIT */
#include "opengpu_tess_frontend.h"
#include "opengpu_native_geometry_emit.h"
#include "tgsi/tgsi_parse.h"
#include <cassert>
#include <cstdio>
#include <string>
/* This fixture consumes actual original Mesa tokens. It is not an ISA peer,
 * and a successful frontend link alone is not GS machine-code qualification. */
static void run(const char *directory,const char *out,const char *name) {
    std::string paths[5];const char *files[5];
    const char *suffix[]={"vert","tesc","tese","geom","frag"};
    for(unsigned i=0;i<5;i++) {paths[i]=std::string(directory)+"/"+((i==3&&std::string(name)!="geometry")||(i==2&&std::string(name)=="geometry-matrix")?name:"graphics60")+"."+suffix[i];files[i]=paths[i].c_str();}
    std::string prefix=std::string(out)+"/"+name+"/actual";
    void *p=opengpu_tess_frontend_compile(files,prefix.c_str());assert(p);
    unsigned blocks=0;
    for(unsigned stage:{0u,4u,5u,3u,1u}) {
        assert(opengpu_tess_frontend_tokens(p,stage));
        assert(opengpu_tess_frontend_blocks(p,stage)==12);blocks+=12;
        for(unsigned i=0;i<12;i++)assert(opengpu_tess_frontend_binding(p,stage,i)<72);
    }
    assert(blocks==60);
    auto *tokens=static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(p,3));
    tgsi_parse_context parse={};assert(tgsi_parse_init(&parse,tokens)==TGSI_PARSE_OK);
    unsigned instructions=0;
    while(!tgsi_parse_end_of_tokens(&parse)) {
        tgsi_parse_token(&parse);
        if(parse.FullToken.Token.Type==TGSI_TOKEN_TYPE_INSTRUCTION)instructions++;
    }
    tgsi_parse_free(&parse);assert(instructions>0);
    opengpu_native_tess_input_map input={},output={};
    assert(!opengpu_tess_frontend_geometry_maps(p,&input,&output));
    opengpu_native_geometry_program native={};
    assert(!opengpu_native_geometry_compile(tokens,tgsi_num_tokens(tokens),&input,&output,&native));
    assert(native.layout.input_vertices==2&&native.layout.invocations==(std::string(name)=="geometry-invocation"?32u:1u)&&native.layout.max_vertices==2);
    assert(native.layout.input_components==(std::string(name)=="geometry-matrix"?10u:8u)&&native.layout.output_components==8&&native.layout.output_topology==1);
    assert(native.layout.input_varyings==(std::string(name)=="geometry-matrix"?15u:1u)&&native.layout.output_varyings==1);
    assert(native.layout.builtin_output_mask==(std::string(name)=="geometry-invocation"?3u:0u));
    for(unsigned i=0;i<64;i++){
        unsigned source=std::string(name)=="geometry-matrix"?(i<4?i:i<10?4+4*(13+(i-4)/3)+(i-4)%3:0):(i<8?i:0);
        assert(native.layout.input_sources[i]==source);
    }
    for(unsigned i=0;i<68;i++){
        assert(native.layout.output_sources[i].valid==(i<8?1u:0u));
        assert(native.layout.output_sources[i].source==(i<8?i:0));
    }
    assert(native.words>19&&native.words<=512&&native.constant_bytes[0]==(std::string(name)=="geometry"?0u:4096u));
    for(unsigned i=1;i<13;i++)assert(native.constant_bytes[i]==16384);
    std::string path=std::string(out)+"/"+name+"/native-gs.words";
    FILE *raw=fopen(path.c_str(),"wb");assert(raw);
    for(unsigned i=0;i<native.words;i++)for(unsigned j=0;j<4;j++)assert(fputc((native.code[i]>>(8*j))&255,raw)!=EOF);
    assert(!fclose(raw));
    printf("ACTUAL_NATIVE_GS words=%u input=%u output=%u invocations=%u maxVertices=%u\n",native.words,
        native.layout.input_components,native.layout.output_components,native.layout.invocations,native.layout.max_vertices);
    printf("Actual linked GS TGSI inventory: %u original instructions; no ISA execution\n",instructions);
    opengpu_tess_frontend_destroy(p);
}

int main(int argc,char **argv){assert(argc==3);setvbuf(stdout,nullptr,_IONBF,0);for(const char *name:{"geometry","geometry-runtime","geometry-invocation","geometry-conditional","geometry-matrix"})run(argv[1],argv[2],name);}
