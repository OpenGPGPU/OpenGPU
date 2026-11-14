/* SPDX-License-Identifier: MIT */
#include "opengpu_native_sample_program.h"
#include "opengpu_native_graphics_internal.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include <errno.h>
#include <string.h>
static struct opengpu_native_runtime compiler_runtime(const struct opengpu_native_sample_runtime *r)
{
    /* ABI1 is an explicitly supported ordinary program in GNJ2/GNF3. Its
     * source-layout masks remain ABI1/2/3/4, not a renamed ABI6 compiler. */
    return (struct opengpu_native_runtime){.frame=r->limits.frame,.shader={
        .capacity=r->limits.capacity,.attributes=16,.bindings=16,.varyings=16,.colors=4,
        .vertex_versions=OPENGPU_NATIVE_LAYOUT_VERTEX_VERSIONS,
        .fragment_versions=OPENGPU_NATIVE_LAYOUT_FRAGMENT_VERSIONS}};
}
int opengpu_native_sample_context_bootstrap(struct pipe_context *pipe,const struct opengpu_native_sample_runtime *runtime)
{
    if(!pipe || opengpu_native_sample_runtime_validate(runtime)){errno=EPROTO;return -1;}
    struct opengpu_native_runtime c=compiler_runtime(runtime);
    return opengpu_native_sample_cache_install(pipe,runtime,sizeof(*runtime),&c.shader);
}
int opengpu_native_sample_context_runtime(struct pipe_context *pipe,struct opengpu_native_sample_runtime *out,unsigned *id)
{
    struct opengpu_native_sample_runtime value;unsigned context;
    if(!out || !id){errno=EINVAL;return -1;}
    if(opengpu_native_sample_cache_read(pipe,&value,sizeof(value),&context))return -1;
    if(!context || opengpu_native_sample_runtime_validate(&value)){errno=EPROTO;return -1;}
    *out=value;*id=context;return 0;
}
struct opengpu_native_graphics_program *opengpu_native_sample_program_create(struct pipe_context *pipe,const void *vs,const void *fs)
{
    struct opengpu_native_sample_runtime r;unsigned id;
    if(opengpu_native_sample_context_runtime(pipe,&r,&id))return NULL;
    struct opengpu_native_runtime c=compiler_runtime(&r);
    return opengpu_native_graphics_program_create_checked(pipe,vs,fs,&c);
}
int opengpu_native_sample_program_sources(struct pipe_context *pipe,const struct opengpu_native_graphics_program *program,
    struct opengpu_native_graphics_sources *out)
{
    struct opengpu_native_sample_runtime r;unsigned id;
    if(opengpu_native_sample_context_runtime(pipe,&r,&id))return -1;
    struct opengpu_native_runtime c=compiler_runtime(&r);
    return opengpu_native_graphics_program_sources_checked(pipe,program,&c,opengpu_native_sample_uniform_sources,out);
}
int opengpu_native_sample_draw_encode(const struct opengpu_native_draw_request *request,struct opengpu_native_draw_encoding *out)
{
    struct opengpu_native_sample_runtime r;unsigned id;
    if(!request){errno=EINVAL;return -1;}
    if(opengpu_native_sample_context_runtime(request->context,&r,&id))return -1;
    struct opengpu_native_runtime c=compiler_runtime(&r);
    return opengpu_native_draw_encode_checked(request,&c,id,out);
}
int opengpu_native_sample_graphics_submit(struct pipe_context *pipe,const struct opengpu_native_sample_submit_sources *s,uint64_t *sequence)
{
    struct opengpu_native_sample_runtime r;unsigned id;
    if(!pipe || !s || !sequence || !s->runtime || !s->job || !s->frame || !s->states ||
        !s->state_count || !s->out_syncobj || (s->draw_count && (!s->draws || !s->plans)) ||
        (uint64_t)s->draw_count*2048>UINT32_MAX || (uint64_t)s->state_count*512>UINT32_MAX ||
        s->frame->resource_count>OPENGPU_NATIVE_FRAME_MAX_RESOURCES){errno=EINVAL;return -1;}
    if(opengpu_native_sample_context_runtime(pipe,&r,&id))return -1;
    if(opengpu_native_sample_runtime_validate(s->runtime) ||
        memcmp(r.observed,s->runtime->observed,sizeof(r.observed))){errno=EPROTO;return -1;}
    const uint32_t *job=s->job->job.words;
    struct opengpu_native_sample_job_encoding canonical;
    if(opengpu_native_sample_job_encode(&r,id,job[OPENGPU_NATIVE_LAYERED_JOB_JOB_IDENTITY_PASS_WORD],job[OPENGPU_NATIVE_LAYERED_JOB_JOB_IDENTITY_GENERATION_WORD],s->draw_count,&s->job->completion.view,&canonical) ||
        memcmp(&canonical,s->job,sizeof(canonical)) || s->frame->frame.words[0]!=OPENGPU_NATIVE_LAYERED_FRAME_MAGIC ||
        s->frame->frame.words[2]!=3){errno=EINVAL;return -1;}
    for(unsigned i=0;i<s->draw_count;i++){
        struct drm_opengpu_native_layered_draw_plan ordinary;
        if(opengpu_native_sample_ordinary_plan(&r,&s->plans[i].draw,&ordinary) ||
            memcmp(&ordinary,&s->plans[i],sizeof(ordinary))){errno=EINVAL;return -1;}
    }
    const struct drm_opengpu_submit_native_binned command={.version=2,.context_id=id,
        .in_syncobj=s->in_syncobj,.out_syncobj=s->out_syncobj,.job=(uintptr_t)&s->job->job,
        .frame=(uintptr_t)&s->frame->frame,.draws=(uintptr_t)s->draws,.draws_bytes=(uint64_t)s->draw_count*2048,
        .states=(uintptr_t)s->states,.states_bytes=(uint64_t)s->state_count*512,
        .plans=(uintptr_t)s->plans,.plan_count=s->draw_count,.plan_stride=sizeof(*s->plans),
        .resources=(uintptr_t)s->frame->resources,.resource_count=s->frame->resource_count,
        .resource_stride=sizeof(*s->frame->resources),.reserved={(uintptr_t)&s->job->completion,0}};
    return opengpu_native_submit(pipe_opengpu_screen_fd(opengpu_screen_gpu(pipe->screen)),&command,sequence);
}
