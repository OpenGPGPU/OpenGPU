// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include "opengpu_native_layered_snapshot.h"
#include "opengpu_native_layered_abi.h"
#include "opengpu_private_snapshot.h"
struct layered_range { u64 base,end,page,bytes,delta; };
static int layered_range_raw(u32 handle,u64 offset,u64 bytes,
    opengpu_native_frame_resolve resolve,void *opaque,struct layered_range *out)
{
    struct opengpu_native_frame_backing b={0};
    u64 base,end;int ret;
    if(!handle)return (offset || bytes)?-EINVAL:1;
    if(!bytes || offset>~(u64)0-bytes)return -EINVAL;
    ret=resolve(opaque,handle,&b);if(ret)return ret;
    if(!b.bytes || (b.physical&4095u) || (b.bytes&4095u) || b.bytes>(1ULL<<32) ||
       b.physical>(1ULL<<32)-b.bytes || offset+bytes>b.bytes)return -ERANGE;
    base=b.physical+offset;end=base+bytes;
    *out=(struct layered_range){.base=base&~63ULL,.end=(end+63u)&~63ULL,
        .page=base&~4095ULL,.bytes=((end+4095u)&~4095ULL)-(base&~4095ULL),.delta=base&4095u};
    return 0;
}
static int layered_range(const struct drm_opengpu_compute_uniform_view *v,
    opengpu_native_frame_resolve resolve,void *opaque,struct layered_range *out)
{
    return layered_range_raw(v->handle,v->offset,v->bytes,resolve,opaque,out);
}
static int layered_conflict(const struct layered_range *completion,
    const struct drm_opengpu_compute_uniform_view *view,opengpu_native_frame_resolve resolve,void *opaque)
{
    struct layered_range range;int ret=layered_range(view,resolve,opaque,&range);
    if(ret)return ret==1?0:ret;
    return range.base<completion->end && completion->base<range.end?-EINVAL:0;
}
int opengpu_native_layered_completion_check(const struct opengpu_native_job_source *s,
    opengpu_native_frame_resolve resolve,void *opaque)
{
    struct layered_range completion;u32 i,j,stage;int ret;
    if(!s || !s->layered || !resolve || (s->plan_count && !s->layered_plans) ||
       (s->resource_count && !s->resources) || !s->completion.handle ||
       s->completion.bytes!=OPENGPU_NATIVE_LAYERED_COMPLETION_BYTES || (s->completion.offset&63u))return -EINVAL;
    ret=layered_range(&s->completion,resolve,opaque,&completion);if(ret)return ret;
    for(i=0;i<s->resource_count;++i){
        const struct opengpu_native_frame_resource *r=&s->resources[i];
        struct layered_range range;
        ret=layered_range_raw(r->gem_handle,r->offset,r->bytes,resolve,opaque,&range);
        if(ret)return ret==1?-EINVAL:ret;
        if(range.base<completion.end && completion.base<range.end)return -EINVAL;
    }
    for(i=0;i<s->plan_count;++i){
        const struct drm_opengpu_native_layered_draw_plan *g=&s->layered_plans[i];
        const struct drm_opengpu_native_draw_plan *p=&g->draw;
        const struct drm_opengpu_native_program_view *code[3]={&p->vertex_program,&p->fragment_program,&g->geometry_program};
        const struct drm_opengpu_native_uniform_stage *uniforms[3]={&p->vertex_uniforms,&p->fragment_uniforms,&g->geometry_uniforms};
        for(stage=0;stage<3;++stage){
            struct drm_opengpu_compute_uniform_view v={.handle=code[stage]->handle,.offset=code[stage]->offset,.bytes=code[stage]->bytes};
            ret=layered_conflict(&completion,&v,resolve,opaque);if(ret)return ret;
            for(j=0;j<12;++j){ret=layered_conflict(&completion,&uniforms[stage]->blocks[j],resolve,opaque);if(ret)return ret;}
            ret=layered_conflict(&completion,&uniforms[stage]->defaults,resolve,opaque);if(ret)return ret;
        }
        for(j=0;j<16;++j){ret=layered_conflict(&completion,&p->bindings[j],resolve,opaque);if(ret)return ret;}
        ret=layered_conflict(&completion,&p->indices,resolve,opaque);if(ret)return ret;
        ret=layered_conflict(&completion,&p->indirect,resolve,opaque);if(ret)return ret;
        ret=layered_conflict(&completion,&p->vertex_scratch,resolve,opaque);if(ret)return ret;
    }
    return 0;
}
int opengpu_native_layered_completion_patch(struct opengpu_private_snapshot *owner,
    u32 job,const struct opengpu_native_job_source *s,opengpu_native_frame_resolve resolve,void *opaque)
{
    struct layered_range range;u32 resource;int ret;
    if(!owner || !s || !s->layered || !resolve || !s->completion.handle ||
       s->completion.bytes!=OPENGPU_NATIVE_LAYERED_COMPLETION_BYTES || (s->completion.offset&63u))return -EINVAL;
    ret=layered_range(&s->completion,resolve,opaque,&range);if(ret)return ret;
    ret=opengpu_private_snapshot_resource(owner,(size_t)range.bytes,&resource);
    if(!ret)ret=opengpu_private_snapshot_resource_map(owner,resource,0,range.page,range.bytes,true);
#define PATCH(name,addend,kind) do {if(!ret)ret=opengpu_private_snapshot_relocate_resource(owner,job, \
    4u*OPENGPU_NATIVE_LAYERED_JOB_COMPLETION_##name##_WORD,resource,addend,kind);}while(0)
    PATCH(BASE,range.delta,OPENGPU_PRIVATE_SNAPSHOT_VA32);
    PATCH(LIMIT,range.delta+s->completion.bytes,OPENGPU_PRIVATE_SNAPSHOT_LIMIT33);
    PATCH(ALLOCATIONBASE,range.delta,OPENGPU_PRIVATE_SNAPSHOT_VA32);
    PATCH(ALLOCATIONLIMIT,range.delta+s->completion.bytes,OPENGPU_PRIVATE_SNAPSHOT_LIMIT33);
#undef PATCH
    return ret;
}
