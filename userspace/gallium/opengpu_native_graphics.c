/* SPDX-License-Identifier: MIT */
#include "opengpu_native_graphics.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>

struct opengpu_native_graphics_program {
    struct pipe_context *context;
    struct opengpu_native_runtime runtime;
    struct opengpu_native_uniform_layout layout[2];
    struct opengpu_native_uniform_program shader[2];
    struct pipe_opengpu_resource *code[2];
};

void opengpu_native_graphics_program_destroy(struct opengpu_native_graphics_program *program)
{
    if (!program) return;
    for (unsigned stage=0;stage<2;stage++)
        if (program->code[stage]) pipe_opengpu_resource_destroy(
            opengpu_screen_gpu(program->context->screen),program->code[stage]);
    free(program);
}

struct opengpu_native_graphics_program *opengpu_native_graphics_program_create_checked(
    struct pipe_context *pipe, const void *vertex_tokens, const void *fragment_tokens,
    const struct opengpu_native_runtime *runtime)
{
    if (!pipe || !vertex_tokens || !fragment_tokens || !runtime) { errno=EINVAL;return NULL; }
    struct opengpu_native_graphics_program *program=calloc(1,sizeof(*program));
    if (!program) return NULL;
    program->context=pipe;
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(pipe->screen);
    const void *tokens[2]={vertex_tokens,fragment_tokens};
    program->runtime=*runtime;
    for (unsigned stage=0;stage<2;stage++) {
        if (opengpu_native_uniform_compile(tokens[stage],stage,program->runtime.shader.capacity,
                &program->layout[stage],&program->shader[stage])) { errno=ENOTSUP;goto fail; }
        const struct opengpu_native_uniform_layout *layout=&program->layout[stage];
        if (layout->attributes>program->runtime.shader.attributes ||
            layout->varyings>program->runtime.shader.varyings ||
            layout->colors>program->runtime.shader.colors) { errno=ENOTSUP;goto fail; }
    }
    /* Both stages use the same varying prefix. Re-emit with that layout;
     * changing metadata after emission would shift FS color/control arrays. */
    unsigned varyings=program->layout[0].varyings>program->layout[1].varyings ?
        program->layout[0].varyings:program->layout[1].varyings;
    for (unsigned stage=0;stage<2;stage++) if (program->layout[stage].varyings!=varyings) {
        program->layout[stage].varyings=varyings;
        if (opengpu_native_uniform_emit(tokens[stage],&program->layout[stage],&program->shader[stage])) {
            errno=ENOTSUP;goto fail;
        }
    }
    /* This bounded frontend consumes flat generic components. The producer
     * cannot silently replace a missing VS definition with staging contents. */
    if (program->shader[1].varying_mask & ~program->shader[0].varying_mask) { errno=EINVAL;goto fail; }
    for (unsigned stage=0;stage<2;stage++) {
        unsigned bytes=program->shader[stage].words*4;
        program->code[stage]=pipe_opengpu_resource_create(screen,bytes);
        if (!program->code[stage]) goto fail;
        void *mapping=pipe_opengpu_resource_map(program->code[stage]);
        if (!mapping) goto fail;
        memcpy(mapping,program->shader[stage].code,bytes);
    }
    return program;
fail:
    { int saved=errno;opengpu_native_graphics_program_destroy(program);errno=saved;return NULL; }
}

int opengpu_native_graphics_program_sources_checked(struct pipe_context *pipe,
    const struct opengpu_native_graphics_program *program, const struct opengpu_native_runtime *runtime,
    int (*uniforms)(struct pipe_context *,const unsigned [2][13],struct drm_opengpu_native_uniform_stage [2]),
    struct opengpu_native_graphics_sources *output)
{
    struct opengpu_native_graphics_sources result={0};
    unsigned required[2][13];
    if (!pipe || !program || !output || !runtime || !uniforms || pipe!=program->context) { errno=EINVAL;return -1; }
    result.runtime=*runtime;
    if (memcmp(&result.runtime,&program->runtime,sizeof(result.runtime))) { errno=EPROTO;return -1; }
    for (unsigned stage=0;stage<2;stage++) {
        result.layout[stage]=program->layout[stage];
        result.programs[stage].handle=pipe_opengpu_resource_handle(program->code[stage]);
        result.programs[stage].bytes=program->shader[stage].words*4;
        memcpy(required[stage],program->shader[stage].constant_bytes,sizeof(required[stage]));
        result.varying_mask[stage]=program->shader[stage].varying_mask;
    }
    result.color_mask=program->shader[1].color_mask;
    if (uniforms(pipe,required,result.uniforms)) return -1;
    *output=result;
    return 0;
}

struct opengpu_native_graphics_program *opengpu_native_graphics_program_create(
    struct pipe_context *pipe,const void *vs,const void *fs)
{
    struct opengpu_native_runtime runtime;
    if(!pipe || !vs || !fs){errno=EINVAL;return NULL;}
    if(opengpu_native_runtime_query(pipe_opengpu_screen_fd(opengpu_screen_gpu(pipe->screen)),&runtime))return NULL;
    return opengpu_native_graphics_program_create_checked(pipe,vs,fs,&runtime);
}
int opengpu_native_graphics_program_sources(struct pipe_context *pipe,
    const struct opengpu_native_graphics_program *program,struct opengpu_native_graphics_sources *out)
{
    struct opengpu_native_runtime runtime;
    if(!pipe || !program || !out || pipe!=program->context){errno=EINVAL;return -1;}
    if(opengpu_native_runtime_query(pipe_opengpu_screen_fd(opengpu_screen_gpu(pipe->screen)),&runtime))return -1;
    return opengpu_native_graphics_program_sources_checked(pipe,program,&runtime,opengpu_native_graphics_uniform_sources,out);
}
