/* SPDX-License-Identifier: MIT */
#include "opengpu_native_tess_program.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
struct opengpu_native_tess_program_pair {
    struct pipe_context *pipe;
    struct opengpu_native_tess_program shaders[2];
    struct pipe_opengpu_resource *code[2];
};
void opengpu_native_tess_program_destroy(struct opengpu_native_tess_program_pair *p){
    if(!p)return;
    for(unsigned i=0;i<2;i++)if(p->code[i])pipe_opengpu_resource_destroy(opengpu_screen_gpu(p->pipe->screen),p->code[i]);
    free(p);
}
struct opengpu_native_tess_program_pair *opengpu_native_tess_program_create(struct pipe_context *pipe,
    const void *tokens[2],const unsigned words[2],const struct opengpu_native_tess_layout layout[2]){
    if(!pipe||!pipe->screen||!tokens||!words||!layout||layout[0].stage!=4||layout[1].stage!=5||
        layout[0].output_components!=layout[1].input_components||layout[0].output_position!=layout[1].input_position||
        layout[0].output_vertices!=layout[1].output_vertices||layout[0].patch_components!=layout[1].patch_components||
        layout[0].capacity!=layout[1].capacity){errno=EINVAL;
        return NULL;
        }
    struct opengpu_native_tess_program_pair *p=calloc(1,sizeof(*p));
    if(!p)return NULL;
    p->pipe=pipe;
    for(unsigned i=0;i<2;i++){
        if(opengpu_native_tess_uniform_emit(tokens[i],words[i],layout+i,p->shaders+i)){errno=ENOTSUP;
        goto fail;
        }
        p->code[i]=pipe_opengpu_resource_create(opengpu_screen_gpu(pipe->screen),4*p->shaders[i].words);
        if(!p->code[i])goto fail;
        void *data=pipe_opengpu_resource_map(p->code[i]);
        if(!data)goto fail;
        memcpy(data,p->shaders[i].code,4*p->shaders[i].words);
    }return p;
fail:{int saved=errno;
opengpu_native_tess_program_destroy(p);
errno=saved;
return NULL;
}
}
int opengpu_native_tess_program_sources(struct opengpu_native_tess_program_pair *p,
    struct opengpu_native_tess_constants *constants,struct opengpu_native_tess_program_sources *out){
    struct opengpu_native_tess_program_sources value={0};
    unsigned required[2][13];
    if(!p||!constants||!out||opengpu_native_tess_constants_source(constants)!=p->pipe){errno=EINVAL;
    return -1;
    }
    for(unsigned i=0;i<2;i++){
        value.code[i].handle=pipe_opengpu_resource_handle(p->code[i]);
        value.code[i].bytes=4*p->shaders[i].words;
        value.shaders[i]=p->shaders[i];
        memcpy(required[i],p->shaders[i].constant_bytes,sizeof(required[i]));
    }
    if(opengpu_native_tess_constant_sources(constants,required,value.uniforms))return -1;
    *out=value;
    return 0;
}
