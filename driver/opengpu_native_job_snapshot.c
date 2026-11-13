// SPDX-License-Identifier: GPL-2.0
/* Rebuild a raw GNJ graph using only typed, retained original-GEM sources. */
#include <linux/errno.h>
#include <linux/string.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "opengpu_native_job_snapshot.h"
#include "opengpu_native_input_snapshot.h"
#include "opengpu_native_graph_alias.h"
#include "opengpu_native_program_snapshot.h"
#include "opengpu_native_uniform_snapshot.h"
#include "opengpu_native_advanced_blend_gfs.h"
#include "opengpu_native_binned_abi.h"
#include "opengpu_typed_fragment_state_abi.h"
#include "opengpu_private_snapshot.h"
#define J(n) OPENGPU_NATIVE_BINNED_JOB_##n##_WORD
#define D(n) OPENGPU_NATIVE_BINNED_DRAW_DRAW_##n##_WORD
#define F(n) OPENGPU_TYPED_FRAGMENT_STATE_##n##_WORD
#define FW(n) OPENGPU_NATIVE_BINNED_FRAME_FRAME_##n##_WORD
static u32 get(const void *record,u32 word)
{
    const unsigned char *p=(const unsigned char *)record+4u*word;
    return (u32)p[0]|((u32)p[1]<<8)|((u32)p[2]<<16)|((u32)p[3]<<24);
}
static void put(void *record,u32 word,u32 value)
{
    unsigned char *p=(unsigned char *)record+4u*word;
    p[0]=value;p[1]=value>>8;p[2]=value>>16;p[3]=value>>24;
}
static bool header(const void *record,u32 magic,u32 bytes)
{
    const u32 expected[]={magic,bytes,1,16,16,16,4,4};
    u32 i;
    for(i=0;i<8;++i)if(get(record,i)!=expected[i])return false;
    for(;i<16;++i)if(get(record,i))return false;
    return true;
}
static bool identity(const void *record,u32 context_word,u32 pass_word,u32 generation_word,
                     const struct opengpu_native_job_source *s)
{
    return get(record,context_word)==s->context && get(record,pass_word)==s->pass &&
        get(record,generation_word)==s->generation;
}
static int reloc(struct opengpu_private_snapshot *o,u32 from,u64 offset,u32 word,
                 u32 to,u64 addend,bool limit)
{
    if(offset>(u64)(~(size_t)0)-4u*word)return -ERANGE;
    return opengpu_private_snapshot_relocate(o,from,(size_t)offset+4u*word,to,addend,
        limit?OPENGPU_PRIVATE_SNAPSHOT_LIMIT33:OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
int opengpu_native_job_snapshot_build(struct opengpu_private_snapshot *owner,
    const struct opengpu_native_job_source *s,const struct opengpu_native_job_copy *copy,u32 *out)
{
    const struct drm_opengpu_native_draw_plan **plans=NULL;
    u32 *first=NULL,count,stride,state_count,i,draw_segment=0,state_segment,frame_segment,job_segment;
    unsigned char *draws=NULL,*states=NULL;
    int ret=-EINVAL;
    if(!owner || !s || !copy || !copy->copy || !copy->resolve || !out ||
        !s->job || !s->frame || !s->states || !s->states_bytes || (s->states_bytes%512) ||
        (u64)s->states_bytes>(1ULL<<32) || !s->capacity || s->capacity>255 || !s->engines || s->engines>4 ||
        !header(s->job,OPENGPU_NATIVE_BINNED_JOB_MAGIC,512))return -EINVAL;
    count=get(s->job,J(JOB_DRAWCOUNT));stride=get(s->job,J(JOB_DRAWSTRIDE));
    state_count=s->states_bytes/512;
    if(stride<2048 || (stride&63) || s->plan_count!=count ||
        (count && (!s->draws || !s->plans || (u64)(count-1)*stride+2048>s->draws_bytes)) ||
        (!count && s->draws_bytes) || (u64)s->draws_bytes>(1ULL<<32) ||
        !identity(s->job,J(JOB_IDENTITY_CONTEXT),J(JOB_IDENTITY_PASS),J(JOB_IDENTITY_GENERATION),s))return -EINVAL;
    for(i=16;i<128;++i)
        if(i!=J(JOB_DRAWCOUNT) && i!=J(JOB_DRAWSTRIDE) && i!=J(JOB_IDENTITY_CONTEXT) &&
           i!=J(JOB_IDENTITY_PASS) && i!=J(JOB_IDENTITY_GENERATION) && get(s->job,i))return -EINVAL;
    ret=opengpu_native_graph_alias_check(s,copy->resolve,copy->context);
    if(ret)return ret;
    ret=-EINVAL;
    if((u64)count*sizeof(*plans)>(u64)(~(size_t)0) ||
        (u64)state_count*sizeof(*first)>(u64)(~(size_t)0))return -ERANGE;
    states=kvzalloc(s->states_bytes,GFP_KERNEL);
    first=kvzalloc((size_t)state_count*sizeof(*first),GFP_KERNEL);
    if(!states || !first){ret=-ENOMEM;goto done;}
    memcpy(states,s->states,s->states_bytes);
    if(count){
        draws=kvzalloc(s->draws_bytes,GFP_KERNEL);
        plans=kvzalloc((size_t)count*sizeof(*plans),GFP_KERNEL);
        if(!draws || !plans){ret=-ENOMEM;goto done;}
        memcpy(draws,s->draws,s->draws_bytes);
    }
    for(i=0;i<state_count;++i){
        unsigned char *f=states+(size_t)i*512;
        if(get(f,0)!=OPENGPU_TYPED_FRAGMENT_STATE_MAGIC || get(f,1)!=512 || get(f,2)!=1 ||
            get(f,3)!=4 || get(f,F(HEADER_HANDLE))!=i ||
            !identity(f,F(HEADER_CONTEXT),F(HEADER_PASS),F(HEADER_GENERATION),s) ||
            !identity(f,F(FRAGMENT_IDENTITY_CONTEXT),F(FRAGMENT_IDENTITY_PASS),F(FRAGMENT_IDENTITY_GENERATION),s) ||
            get(f,F(FRAGMENT_SHADERPC)) || get(f,F(FRAGMENT_SAMPLER_BASE)) ||
            get(f,F(FRAGMENT_UNIFORMS_VERSION)) || get(f,F(FRAGMENT_UNIFORMS_ADDRESS)) ||
            get(f,F(FRAGMENT_RESOURCES_VERSION)) || get(f,F(FRAGMENT_RESOURCES_ADDRESS)) ||
            get(f,F(IMAGE_VERSION)) || get(f,F(IMAGE_ADDRESS)) ||
            !opengpu_native_advanced_blend_gfs_precheck(s->frame,2048,f,512,s->advanced_blend))goto done;
    }
    for(i=0;i<count;++i){
        const struct drm_opengpu_native_draw_plan *p=&s->plans[i];
        unsigned char *d,*f;
        if(p->draw_index>=count || plans[p->draw_index] || p->state_handle>=state_count)goto done;
        plans[p->draw_index]=p;d=draws+(size_t)p->draw_index*stride;f=states+(size_t)p->state_handle*512;
        if(!header(d,OPENGPU_NATIVE_BINNED_DRAW_MAGIC,2048) || get(d,D(STATE))!=p->state_handle ||
            !identity(d,D(VERTEX_GEOMETRY_VERTEX_INPUT_IDENTITY_CONTEXT),D(VERTEX_GEOMETRY_VERTEX_INPUT_IDENTITY_PASS),
                D(VERTEX_GEOMETRY_VERTEX_INPUT_IDENTITY_GENERATION),s) ||
            get(d,D(VERTEX_GEOMETRY_VERTEX_SHADERPC)) || get(d,D(VERTEX_GEOMETRY_VERTEX_SAMPLER_BASE)) ||
            get(d,D(VERTEX_GEOMETRY_VERTEX_UNIFORMS_VERSION)) || get(d,D(VERTEX_GEOMETRY_VERTEX_UNIFORMS_ADDRESS)) ||
            get(d,D(VERTEX_GEOMETRY_VERTEX_RESOURCES_VERSION)) || get(d,D(VERTEX_GEOMETRY_VERTEX_RESOURCES_ADDRESS)) ||
            get(d,D(VERTEX_GEOMETRY_VERTEX_VARYINGSLOTS))!=get(f,F(FRAGMENT_VARYINGSLOTS)))goto done;
        if(first[p->state_handle]){
            const struct drm_opengpu_native_draw_plan *previous=&s->plans[first[p->state_handle]-1];
            if(memcmp(&previous->fragment_program,&p->fragment_program,sizeof(p->fragment_program)) ||
                memcmp(&previous->fragment_uniforms,&p->fragment_uniforms,sizeof(p->fragment_uniforms)))goto done;
        }else first[p->state_handle]=i+1;
        if(p->vertex_uniforms.active_mask || p->vertex_uniforms.defaults.handle)
            put(d,D(VERTEX_GEOMETRY_VERTEX_UNIFORMS_VERSION),1);
        if(p->fragment_uniforms.active_mask || p->fragment_uniforms.defaults.handle)
            put(f,F(FRAGMENT_UNIFORMS_VERSION),1);
    }
    ret=opengpu_private_snapshot_append(owner,states,s->states_bytes,&state_segment);
    if(ret)goto done;
    if(count){ret=opengpu_private_snapshot_append(owner,draws,s->draws_bytes,&draw_segment);if(ret)goto done;}
    for(i=0;i<count;++i){
        const struct drm_opengpu_native_draw_plan *p=plans[i];
        const unsigned char *d=draws+(size_t)i*stride,*f=states+(size_t)p->state_handle*512;
        struct drm_opengpu_native_uniform_stage stages[2]={p->vertex_uniforms,p->fragment_uniforms};
        struct opengpu_native_uniform_roots roots;
        struct opengpu_native_program_snapshot program;
        struct opengpu_native_program_profile profile={.capacity=s->capacity,.scalar_fpu=s->scalar_fpu};
        ret=opengpu_native_uniform_snapshot_append(owner,stages,s->context,s->pass,s->generation,copy->copy,copy->context,&roots);
        if(ret)goto done;
        profile.stage=0;profile.version=get(d,D(VERTEX_GEOMETRY_VERTEX_VERSION));
        profile.attributes=get(d,D(VERTEX_GEOMETRY_VERTEX_ATTRIBUTESLOTS));
        profile.varyings=get(d,D(VERTEX_GEOMETRY_VERTEX_VARYINGSLOTS));
        profile.staging_bytes=p->vertex_scratch.bytes;profile.owned_uniforms=roots.present[0];
        ret=opengpu_native_program_snapshot_append(owner,&p->vertex_program,&profile,copy->copy,copy->context,&program);
        if(!ret)ret=reloc(owner,draw_segment,(u64)i*stride,D(VERTEX_GEOMETRY_VERTEX_SHADERPC),program.segment,program.entry_offset,false);
        if(!ret && roots.present[0])ret=reloc(owner,draw_segment,(u64)i*stride,
            D(VERTEX_GEOMETRY_VERTEX_UNIFORMS_ADDRESS),roots.segment,roots.root_offset[0],false);
        if(ret)goto done;
        /* The input patch verifies these exact CPU bytes already in the array. */
        ret=opengpu_native_input_snapshot_patch(owner,draw_segment,(u64)i*stride,d,p,s->capacity,copy->resolve,copy->context);
        if(ret)goto done;
        if(&s->plans[first[p->state_handle]-1]!=p)continue;
        profile.stage=1;profile.version=get(f,F(FRAGMENT_VERSION));profile.attributes=0;
        profile.varyings=get(f,F(FRAGMENT_VARYINGSLOTS));profile.colors=get(f,F(FRAGMENT_COLORSLOTS));
        profile.staging_bytes=get(s->frame,FW(SCRATCHSTRIDE));profile.owned_uniforms=roots.present[1];
        ret=opengpu_native_program_snapshot_append(owner,&p->fragment_program,&profile,copy->copy,copy->context,&program);
        if(!ret)ret=reloc(owner,state_segment,(u64)p->state_handle*512,F(FRAGMENT_SHADERPC),program.segment,program.entry_offset,false);
        if(!ret && roots.present[1])ret=reloc(owner,state_segment,(u64)p->state_handle*512,
            F(FRAGMENT_UNIFORMS_ADDRESS),roots.segment,roots.root_offset[1],false);
        if(ret)goto done;
    }
    ret=opengpu_native_frame_snapshot_append_complete(owner,s->frame,state_segment,s->states_bytes,
        s->resources,s->resource_count,s->engines,s->context,s->pass,s->generation,
        copy->resolve,copy->context,&frame_segment);
    if(ret)goto done;
    ret=opengpu_private_snapshot_append(owner,s->job,512,&job_segment);
    if(!ret && count)ret=reloc(owner,job_segment,0,J(JOB_DRAWBASE),draw_segment,0,false);
    if(!ret && count)ret=reloc(owner,job_segment,0,J(JOB_DRAWLIMIT),draw_segment,s->draws_bytes,true);
    if(!ret)ret=reloc(owner,job_segment,0,J(JOB_FRAMEBASE),frame_segment,0,false);
    if(!ret)ret=reloc(owner,job_segment,0,J(JOB_FRAMELIMIT),frame_segment,2048,true);
    if(!ret)*out=job_segment;
done:
    kvfree(plans);kvfree(first);kvfree(states);kvfree(draws);
    return ret;
}
#undef J
#undef D
#undef F
#undef FW
