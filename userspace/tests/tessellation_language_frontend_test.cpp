/* SPDX-License-Identifier: MIT */
/* Original linked ESSL320 -> actual Mesa TGSI -> native compiler bytes.
 * No shader interpreter, synthetic shader result or public capability enable. */
#include "opengpu_tess_frontend.h"
#include "opengpu_native_tess_uniform_emit.h"
#include "tgsi/tgsi_parse.h"
#include <cassert>
#include <cstdio>
#include <cstring>
#include <string>
extern "C" void tessellation_language_resource_test(void *,const opengpu_native_tess_layout *,const opengpu_native_tess_input_map *);
static void save(const std::string &name,const opengpu_native_tess_program &p){
    FILE *f=fopen(name.c_str(),"wb");assert(f);
    for(unsigned i=0;i<p.words;i++)for(unsigned b=0;b<4;b++)assert(fputc((p.code[i]>>(8*b))&255,f)!=EOF);
    assert(!fclose(f));
}
static void run(const char *directory,const char *out,const char *name){
    const char *suffix[]={"vert","tesc","tese",nullptr,"frag"};
    std::string names[5];const char *files[5]={};
    for(unsigned i=0;i<5;i++)if(suffix[i]){names[i]=std::string(directory)+"/"+name+"."+suffix[i];files[i]=names[i].c_str();}
    const std::string prefix=std::string(out)+"/"+name;
    void *f=opengpu_tess_frontend_compile(files,(prefix+"/actual").c_str());assert(f);
    opengpu_native_tess_layout l[2]={};opengpu_native_tess_input_map maps[2]={};
    const auto *tc=static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(f,4));
    const auto *te=static_cast<const tgsi_token *>(opengpu_tess_frontend_tokens(f,5));
    assert(tc&&te);
    assert(!opengpu_native_tess_language_control_reflect(tc,tgsi_num_tokens(tc),128,2,l));
    assert(!opengpu_native_tess_language_evaluation_reflect(te,tgsi_num_tokens(te),128,l,l+1));
    assert(!opengpu_tess_frontend_control_output_map(f,maps));assert(!opengpu_tess_frontend_input_map(f,maps+1));
    for(unsigned s=0;s<2;s++){
        const auto *tokens=s?te:tc;
        tgsi_parse_context parse;assert(tgsi_parse_init(&parse,tokens)==TGSI_PARSE_OK);
        unsigned primid=0;
        while(!tgsi_parse_end_of_tokens(&parse)){
            tgsi_parse_token(&parse);
            if(parse.FullToken.Token.Type==TGSI_TOKEN_TYPE_DECLARATION){
                const auto &d=parse.FullToken.FullDeclaration;
                if(d.Declaration.Semantic&&d.Semantic.Name==TGSI_SEMANTIC_PRIMID){
                    assert((d.Declaration.File==TGSI_FILE_INPUT||d.Declaration.File==TGSI_FILE_SYSTEM_VALUE)&&!d.Semantic.Index);
                    primid++;
                }
            }
        }
        tgsi_parse_free(&parse);assert(primid==1);
        opengpu_native_tess_program p={},saved;
        assert(!opengpu_native_tess_language_emit(tokens,tgsi_num_tokens(tokens),l+s,maps+s,&p));saved=p;
        assert(l[s].version==3&&l[s].owned_tessellation_info==1&&p.words<=512);
        unsigned reads=0;
        for(unsigned i=0;i<p.words;i++)if((p.code[i]&127)==0x0b&&(p.code[i]>>25)==37){
            assert(i&&p.code[i-1]==(6u<<20|10u<<7|0x13));
            assert(p.code[i]==(37u<<25|10u<<15|10u<<7|0x0b));reads++;
        }
        assert(reads);
        if(s){assert(p.selection_count==0);for(const auto &q:p.selections){opengpu_native_tess_selection zero={};assert(!memcmp(&q,&zero,sizeof(q)));}}
        else assert(p.barriers==2);
        for(unsigned kind=0;kind<5;kind++){
            auto bad=l[s];
            if(kind==0)bad.version=1;
            if(kind==1)bad.version=2;
            if(kind==2)bad.owned_tessellation_info=0;
            if(kind==3)bad.owned_tessellation_info=2;
            if(kind==4)bad.stage=3;
            assert(opengpu_native_tess_language_emit(tokens,tgsi_num_tokens(tokens),&bad,maps+s,&p));
            assert(!memcmp(&saved,&p,sizeof(p)));
        }
        assert(opengpu_native_tess_uniform_emit(tokens,tgsi_num_tokens(tokens),l+s,&p));
        assert(opengpu_native_tess_dynamic_emit(tokens,tgsi_num_tokens(tokens),l+s,maps+s,&p));
        assert(!memcmp(&saved,&p,sizeof(p)));
        printf("ACTUAL_LANGUAGE_NATIVE case=%s stage=%u words=%u selector6Reads=%u patch=%u/%u/%u/%u/%u exports=%u version=3\n",
            name,s+4,p.words,reads,l[0].input_vertices,l[0].input_components,l[0].output_vertices,l[0].output_components,l[0].patch_components,l[1].output_components);
        save(prefix+(s?"/native-tes.words":"/native-tcs.words"),saved);
    }
    tessellation_language_resource_test(f,l,maps);
    opengpu_tess_frontend_destroy(f);
}
int main(int argc,char **argv){
    setvbuf(stdout,nullptr,_IONBF,0);assert(argc==3);
    for(const char *name:{"language","language-conditional","language-full"})run(argv[1],argv[2],name);
    puts("Actual linked ESSL PrimitiveID -> native v3 original source CPU PASS; no CU/DUT execution claim");
}
