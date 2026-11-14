/* SPDX-License-Identifier: MIT */
#include "fs_glsl_frontend.h"
#include "opengpu_native_fs_emit.h"
#include "opengpu_shader_validator.h"
#include "opengpu_native_sample_transport_abi.h"
#include "tgsi/tgsi_parse.h"
#include <assert.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
int main(int argc,char **argv)
{
    if(argc!=4){fprintf(stderr,"usage: native-fs-compile source.frag output.ognsf 6|7\n");return 2;}
    unsigned version=!strcmp(argv[3],"6")?6:!strcmp(argv[3],"7")?7:0;
    if(!version)return 2;
    void *f=native_fs_frontend_compile(argv[1],argv[2]);if(!f)return 3;
    struct opengpu_native_fs_config c={version,128,16,4,1,version==7};
    struct opengpu_native_fs_program p;
    const void *tokens=native_fs_frontend_tokens(f);unsigned count=native_fs_frontend_token_words(f);
    if(opengpu_native_fs_emit(tokens,count,&c,&p)){native_fs_frontend_destroy(f);return 4;}
    /* Cross-check the public capability's max input arrays, varying/MRT/sample
     * fields. The consumer must separately check actual MMIO and owner gates. */
    assert((OPENGPU_NATIVE_SAMPLE_TRANSPORT_LIMITS&255)==4);
    assert(((OPENGPU_NATIVE_SAMPLE_TRANSPORT_LIMITS>>8)&255)==c.varying_slots);
    assert(((OPENGPU_NATIVE_SAMPLE_TRANSPORT_LIMITS>>16)&255)==c.color_slots);
    assert(((OPENGPU_NATIVE_SAMPLE_TRANSPORT_LIMITS>>24)&255)==77);
    assert(p.metadata.input_arrays==6+4*c.varying_slots+2+2*(version==7)+3);
    assert(p.metadata.array_stride==4*c.thread_capacity);
    assert(p.metadata.prefix_bytes==(p.metadata.input_arrays+4*c.color_slots+2)*p.metadata.array_stride);
    assert(opengpu_native_shader_validate_words_sample_inputs(p.code,p.words,p.metadata.prefix_bytes,
        128,1,version,0,16,4,true,false,version==7,true));
    assert(!opengpu_native_shader_validate_words_sample_inputs(p.code,p.words,p.metadata.prefix_bytes-1,
        128,1,version,0,16,4,true,false,version==7,true));
    assert(!opengpu_native_shader_validate_words_sample_inputs(p.code,p.words,p.metadata.prefix_bytes,
        128,1,version,0,16,4,true,false,version==7,false));
    if(version==7)assert(!opengpu_native_shader_validate_words_sample_inputs(p.code,p.words,p.metadata.prefix_bytes,
        128,1,version,0,16,4,true,false,false,true));
    struct opengpu_native_fs_program sentinel;memset(&sentinel,0xa5,sizeof(sentinel));
    struct opengpu_native_fs_program rejected=sentinel;
    c.sample_transport=0;
    assert(opengpu_native_fs_emit(tokens,count,&c,&rejected));assert(!memcmp(&sentinel,&rejected,sizeof(rejected)));
    c.sample_transport=1;c.thread_capacity=16;
    assert(opengpu_native_fs_emit(tokens,count,&c,&rejected));
    c.thread_capacity=128;c.abi_version=5;
    assert(opengpu_native_fs_emit(tokens,count,&c,&rejected));
    c.abi_version=version;
    for(unsigned end=0;end<count;end++)assert(opengpu_native_fs_emit(tokens,end,&c,&rejected));
    if(version==7){
        c.geometry_transport=0;assert(opengpu_native_fs_emit(tokens,count,&c,&rejected));
        c.geometry_transport=1;
    }
    /* Mutate original Mesa packet heads, never hand-author a substitute IR. */
    uint32_t *bad=malloc(count*4);assert(bad);
    memcpy(bad,tokens,count*4);bad[0]^=1;
    assert(opengpu_native_fs_emit(bad,count,&c,&rejected));
    memcpy(bad,tokens,count*4);bad[1]=5; /* compute processor, not fragment */
    assert(opengpu_native_fs_emit(bad,count,&c,&rejected));
    assert(!memcmp(&sentinel,&rejected,sizeof(rejected)));free(bad);
    /* Repeat an original Mesa instruction before its original END. This
     * preserves real declarations and operand encodings while forcing the
     * independent 512-word emitter ceiling; no replacement TGSI is authored. */
    const uint32_t *original=tokens;unsigned first=0,first_words=0,end=0;
    for(unsigned at=2;at<count;){
        struct tgsi_token token;memcpy(&token,original+at,4);unsigned n=token.NrTokens;
        if(token.Type==TGSI_TOKEN_TYPE_IMMEDIATE){struct tgsi_immediate i;memcpy(&i,original+at,4);n=i.NrTokens;}
        if(token.Type==TGSI_TOKEN_TYPE_INSTRUCTION){
            struct tgsi_instruction i;memcpy(&i,original+at,4);n=1+i.NrTokens;
            if(i.Opcode==TGSI_OPCODE_END)end=at;
            else if(!first){first=at;first_words=n;}
        }
        at+=n;
    }
    assert(first&&first_words&&end&&first_words<=4);
    unsigned repeated_count=count+300*first_words;assert(repeated_count<=4098);
    bad=malloc(repeated_count*4);assert(bad);memcpy(bad,original,end*4);
    for(unsigned i=0;i<300;i++)memcpy(bad+end+i*first_words,original+first,first_words*4);
    memcpy(bad+end+300*first_words,original+end,(count-end)*4);
    struct tgsi_header header;memcpy(&header,bad,4);header.BodySize=repeated_count-2;memcpy(bad,&header,4);
    assert(opengpu_native_fs_emit(bad,repeated_count,&c,&rejected));
    assert(!memcmp(&sentinel,&rejected,sizeof(rejected)));free(bad);
    if(opengpu_native_fs_write(argv[2],&p)){native_fs_frontend_destroy(f);return 5;}
    printf("NATIVE_FS_MESA_CPU abi=%u words=%u inputs=%u prefix=%u sysvals=%u colors=%u depth=%u mask=%u gpuExecution=false\n",
        version,p.words,p.metadata.input_arrays,p.metadata.prefix_bytes,p.metadata.used_sysvals,
        p.metadata.output_color_mask,p.metadata.writes_depth,p.metadata.writes_sample_mask);
    native_fs_frontend_destroy(f);return 0;
}
