/* SPDX-License-Identifier: MIT */
/* Actual Mesa token consumer. No shader VM, device, queue or GPU success. */
#include "fs_glsl_frontend.h"
#include "graphics_uniform_frontend.h"
#include "opengpu_native_fs_emit.h"
#include "opengpu_native_uniform_emit.h"
#include "tgsi/tgsi_parse.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int reference_fs_emit(const void *,unsigned,const struct opengpu_native_fs_config *,struct opengpu_native_fs_program *);
int reference_uniform_compile(const void *,unsigned,unsigned,struct opengpu_native_uniform_layout *,struct opengpu_native_uniform_program *);
static int legacy(int argc,char **argv)
{
#ifdef RAW_FRONTEND
    if(argc!=5||strcmp(argv[1],"--legacy-raw"))return -1;
    void *handle=native_fs_frontend_compile(argv[2],NULL);assert(handle);
    const void *tokens=native_fs_frontend_tokens(handle);unsigned n=native_fs_frontend_token_words(handle);
    struct opengpu_native_fs_config c={(unsigned)strtoul(argv[3],NULL,0),128,16,4,1,1};
    struct opengpu_native_fs_program p,reference;
    assert(!opengpu_native_fs_emit(tokens,n,&c,&p));assert(!reference_fs_emit(tokens,n,&c,&reference));
    assert(!memcmp(&p,&reference,sizeof(p)));assert(!opengpu_native_fs_write(argv[4],&p));
    c.abi_version=8;assert(opengpu_native_fs_emit(tokens,n,&c,&p));native_fs_frontend_destroy(handle);
#else
    if(argc!=4||strcmp(argv[1],"--legacy-uniform"))return -1;
    void *handle=graphics_uniform_compile(argv[2],argv[3],NULL);assert(handle);
    for(unsigned stage=0;stage<2;stage++){
        struct opengpu_native_uniform_layout l,oldl;struct opengpu_native_uniform_program p,old;
        const void *tokens=graphics_uniform_tokens(handle,stage);
        assert(!opengpu_native_uniform_compile(tokens,stage,128,&l,&p));
        assert(!reference_uniform_compile(tokens,stage,128,&oldl,&old));
        assert(!memcmp(&l,&oldl,sizeof(l))&&!memcmp(&p,&old,sizeof(p)));
    }
    graphics_uniform_destroy(handle);
#endif
    return 0;
}
static void unchanged_reject(const void *tokens,unsigned n,
    const struct opengpu_native_fragment_reflection_v2 *r,const struct opengpu_native_fragment_config_v2 *c)
{
    struct opengpu_native_fragment_program_v2 p,original;memset(&p,0xa5,sizeof(p));original=p;
    assert(opengpu_native_fs_emit_v2(tokens,n,r,c,&p));assert(!memcmp(&p,&original,sizeof(p)));
    assert(opengpu_native_fragment_uniform_compile_v2(tokens,n,r,c,&p));assert(!memcmp(&p,&original,sizeof(p)));
}
static void semantic_negatives(const void *tokens,unsigned n,
    const struct opengpu_native_fragment_reflection_v2 *r,const struct opengpu_native_fragment_config_v2 *c)
{
    uint32_t *copy=malloc(n*4);assert(copy);
    for(unsigned at=2;at<n;){
        struct tgsi_token t;memcpy(&t,(const char *)tokens+at*4,4);
        unsigned count=t.NrTokens;
        if(t.Type==TGSI_TOKEN_TYPE_INSTRUCTION)count++;
        if(t.Type==TGSI_TOKEN_TYPE_DECLARATION){
            struct tgsi_declaration d;memcpy(&d,(const char *)tokens+at*4,4);
            if(d.File==TGSI_FILE_INPUT&&d.Semantic){
                unsigned sem=at+2+d.Dimension+d.Interpolate;
                struct tgsi_declaration_semantic s;memcpy(&s,(const char *)tokens+sem*4,4);
                if(s.Name==TGSI_SEMANTIC_PRIMID){
                    assert(d.Interpolate);struct tgsi_declaration_interp i;
                    memcpy(&i,(const char *)tokens+(at+2+d.Dimension)*4,4);assert(i.Interpolate==TGSI_INTERPOLATE_CONSTANT);
                    memcpy(copy,tokens,n*4);i.Interpolate=TGSI_INTERPOLATE_PERSPECTIVE;
                    memcpy(copy+at+2+d.Dimension,&i,4);unchanged_reject(copy,n,r,c);
                    memcpy(copy,tokens,n*4);s.Index=1;memcpy(copy+sem,&s,4);unchanged_reject(copy,n,r,c);
                }
            }
        }
        assert(count&&count<=n-at);at+=count;
    }
    memcpy(copy,tokens,n*4);((struct tgsi_header *)copy)->BodySize++;
    unchanged_reject(copy,n,r,c);free(copy);
}
int main(int argc,char **argv)
{
    if(argc==4&&!strcmp(argv[1],"--reject-legacy-frontend")){
#ifdef RAW_FRONTEND
        assert(!native_fs_frontend_compile(argv[3],NULL));
#else
        assert(!graphics_uniform_compile(argv[2],argv[3],NULL));
#endif
        return 0;
    }
    if(argc>1&&(!strcmp(argv[1],"--legacy-raw")||!strcmp(argv[1],"--legacy-uniform")))return legacy(argc,argv);
    if(argc==3&&!strcmp(argv[1],"--reject-container")){
        struct opengpu_native_fragment_program_v2 p,old;memset(&p,0xa5,sizeof(p));old=p;
        assert(opengpu_native_fragment_read_v2(argv[2],&p));assert(!memcmp(&p,&old,sizeof(p)));return 0;
    }
    if(argc!=7)return 2;
#ifdef RAW_FRONTEND
    void *handle=native_fs_frontend_compile_no_gs_v2(argv[2],NULL);
    if(!handle)return 3;
    const void *tokens=native_fs_frontend_tokens(handle);unsigned n=native_fs_frontend_token_words(handle);
    struct opengpu_native_fragment_reflection_v2 r;
    assert(!native_fs_frontend_reflection_v2(handle,&r));
#else
    void *handle=graphics_uniform_compile_no_gs_v2(argv[1],argv[2],NULL);
    if(!handle)return 3;
    const void *tokens=graphics_uniform_tokens(handle,1);unsigned n=graphics_uniform_token_words(handle,1);
    struct opengpu_native_fragment_reflection_v2 r;
    assert(!graphics_uniform_reflection_v2(handle,&r));
#endif
    unsigned abi=(unsigned)strtoul(argv[4],NULL,0),static_inputs=(unsigned)strtoul(argv[5],NULL,0),uniforms=(unsigned)strtoul(argv[6],NULL,0);
    assert(r.version==2&&r.essl_version==320&&!r.linked_geometry&&r.static_inputs==static_inputs);
    struct opengpu_native_fragment_config_v2 c={128,16,4,1,1};
    struct opengpu_native_fragment_program_v2 checked,raw,roundtrip;
    if(opengpu_native_fragment_uniform_compile_v2(tokens,n,&r,&c,&checked))return 4;
    assert(checked.metadata.abi_version==abi&&checked.metadata.requires_primitive_owner==1);
    assert(!checked.metadata.requires_geometry_owner&&checked.metadata.requires_uniform_owner==uniforms);
    assert(checked.words&&checked.words<=512);
    int raw_result=opengpu_native_fs_emit_v2(tokens,n,&r,&c,&raw);
    if(uniforms)assert(raw_result);
    else{
        assert(!raw_result);assert(!memcmp(&raw.metadata,&checked.metadata,sizeof(raw.metadata)));
        assert(!opengpu_native_fragment_write_v2(argv[3],&raw));
        assert(!opengpu_native_fragment_read_v2(argv[3],&roundtrip));assert(!memcmp(&raw,&roundtrip,sizeof(raw)));
    }
    char path[4096];assert(snprintf(path,sizeof(path),"%s.uniform",argv[3])>0);
    assert(!opengpu_native_fragment_write_v2(path,&checked));
    assert(!opengpu_native_fragment_read_v2(path,&roundtrip));assert(!memcmp(&checked,&roundtrip,sizeof(checked)));
    struct opengpu_native_fragment_reflection_v2 bad=r;bad.linked_geometry=1;unchanged_reject(tokens,n,&bad,&c);
    bad=r;bad.version=1;unchanged_reject(tokens,n,&bad,&c);
    bad=r;bad.essl_version=310;unchanged_reject(tokens,n,&bad,&c);
    struct opengpu_native_fragment_config_v2 wrong=c;wrong.primitive_transport=0;unchanged_reject(tokens,n,&r,&wrong);
    wrong=c;wrong.thread_capacity=127;unchanged_reject(tokens,n,&r,&wrong);
    wrong=c;wrong.varying_slots=0xffffffffu;unchanged_reject(tokens,n,&r,&wrong);
    wrong=c;wrong.color_slots=0xffffffffu;unchanged_reject(tokens,n,&r,&wrong);
    if(abi==9){wrong=c;wrong.sample_transport=0;unchanged_reject(tokens,n,&r,&wrong);}
    else{
        wrong=c;wrong.sample_transport=0;struct opengpu_native_fragment_program_v2 pixel;
        assert(!opengpu_native_fragment_uniform_compile_v2(tokens,n,&r,&wrong,&pixel));
        assert(!memcmp(&pixel,&checked,sizeof(pixel)));
        if(!uniforms){assert(!opengpu_native_fs_emit_v2(tokens,n,&r,&wrong,&pixel));assert(!memcmp(&pixel,&raw,sizeof(pixel)));}
    }
    bad=r;bad.static_inputs|=0x80000000u;unchanged_reject(tokens,n,&bad,&c);
    unchanged_reject(tokens,n-1,&r,&c);semantic_negatives(tokens,n,&r,&c);
    struct opengpu_native_fs_program legacy;memset(&legacy,0xa5,sizeof(legacy));
    struct opengpu_native_fs_config old={6,128,16,4,1,0};
    if(static_inputs&OPENGPU_FRAGMENT_PRIMITIVE_ID)assert(opengpu_native_fs_emit(tokens,n,&old,&legacy));
    printf("FRAGMENT_V2_CPU abi=%u rawWords=%u uniformWords=%u static=%u uniform=%u shaderExecution=false\n",abi,raw_result?0:raw.words,checked.words,r.static_inputs,uniforms);
#ifdef RAW_FRONTEND
    native_fs_frontend_destroy(handle);
#else
    graphics_uniform_destroy(handle);
#endif
    return 0;
}
