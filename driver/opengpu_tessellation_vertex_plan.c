// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/string.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "opengpu_tessellation_vertex_plan.h"
#include "opengpu_tessellation_vertex_source_abi.h"
#include "opengpu_private_snapshot.h"

static u32 outer_word(const void *raw,u32 n)
{
    const u8 *p=(const u8 *)raw+(size_t)n*4;
    return (u32)p[0]|(u32)p[1]<<8|(u32)p[2]<<16|(u32)p[3]<<24;
}
static void outer_put(void *raw,u32 n,u32 v)
{
    u8 *p=(u8 *)raw+(size_t)n*4;
    for(u32 i=0;i<4;i++)p[i]=(u8)(v>>(8*i));
}
static bool outer_map(const void *raw,size_t bytes,bool bound)
{
    u32 ranges,invocations=0,last_range=0,last_draw=0;
    u64 last_id=0;
    bool closed=true;
    if(!raw || bytes<64 || bytes>(1ULL<<32) || outer_word(raw,0)!=OPENGPU_VERTEX_OUTER_MAP_MAGIC ||
        outer_word(raw,1)!=bytes || outer_word(raw,2)!=2 || outer_word(raw,3)!=64 ||
        outer_word(raw,10)!=16 || outer_word(raw,11)!=16 || outer_word(raw,12)!=16 ||
        outer_word(raw,13)!=8 || outer_word(raw,14)!=2 || outer_word(raw,15))return false;
    ranges=outer_word(raw,6);
    if(64+(u64)ranges*64!=bytes || !outer_word(raw,4) || !outer_word(raw,5) || !ranges)return false;
    for(u32 i=0;i<ranges;i++) {
        const u8 *r=(const u8 *)raw+64+(size_t)i*64;
        u32 flags=outer_word(r,4),ordinal=outer_word(r,5),instances=outer_word(r,7),per=outer_word(r,9);
        u64 id=outer_word(r,2)|((u64)outer_word(r,3)<<32);
        u64 limit=outer_word(r,13)|((u64)outer_word(r,14)<<32);
        if(outer_word(r,0)!=i || outer_word(r,1)>=outer_word(raw,4) || (flags&~7u) ||
            outer_word(r,15) || (u64)instances*per!=outer_word(r,11) ||
            (u64)outer_word(r,6)+instances>(1ULL<<32) ||
            (u64)outer_word(r,8)+per>(1ULL<<32) ||
            (u64)outer_word(r,10)+outer_word(r,11)>(1ULL<<32))return false;
        if(!outer_word(r,11) && (outer_word(r,6) || instances || outer_word(r,8) || per))return false;
        if(closed) {
            if(flags&OPENGPU_VERTEX_OUTER_CONTINUATION || !(flags&OPENGPU_VERTEX_OUTER_FIRST) || ordinal)return false;
            for(u32 j=0;j<i;j++) {
                const u8 *old=(const u8 *)raw+64+(size_t)j*64;
                if(outer_word(old,2)==(u32)id && outer_word(old,3)==(u32)(id>>32))return false;
            }
            invocations++;last_id=id;last_draw=outer_word(r,1);
        }else if(id!=last_id || outer_word(r,1)!=last_draw || flags&OPENGPU_VERTEX_OUTER_FIRST ||
            !(flags&OPENGPU_VERTEX_OUTER_CONTINUATION) || (u64)last_range+1!=ordinal)return false;
        last_range=ordinal;closed=!!(flags&OPENGPU_VERTEX_OUTER_LAST);
        if(bound) {
            if((outer_word(r,12)&63u) || limit!=(u64)outer_word(r,12)+512 || limit>(1ULL<<32))return false;
        }else if(outer_word(r,12) || limit)return false;
    }
    return closed && invocations==outer_word(raw,5);
}
bool opengpu_tessellation_vertex_outer_map_source_canonical(const void *raw,size_t bytes)
{return outer_map(raw,bytes,false);}
bool opengpu_tessellation_vertex_outer_map_canonical(const void *raw,size_t bytes)
{return outer_map(raw,bytes,true);}

struct outer_capture {
    struct opengpu_tessellation_vertex_outer_source_v2 source;
    struct opengpu_native_job_copy copy;
    u8 *draws,*map;
    struct drm_opengpu_native_draw_plan *plans;
    struct opengpu_tessellation_vertex_outer_invocation_v2 *invocations;
    struct opengpu_tessellation_vertex_outer_range_v2 *ranges;
    u32 *draw_index,*range_index;
};
static void outer_free(struct outer_capture *c)
{
    if(!c)return;
    kvfree(c->draws);kvfree(c->map);kvfree(c->plans);kvfree(c->invocations);
    kvfree(c->ranges);kvfree(c->draw_index);kvfree(c->range_index);kvfree(c);
}
static bool outer_bytes(u32 count,size_t width,size_t *bytes)
{
    u64 n=(u64)count*width;
    if(n>(u64)~(size_t)0)return false;
    *bytes=(size_t)n;return true;
}
static int outer_capture_all(struct outer_capture *c)
{
    const struct opengpu_tessellation_vertex_outer_source_v2 *s=&c->source;
    size_t plans,invocations,ranges,di,ri;
    if(s->version!=2 || s->reserved[0] || s->reserved[1] || !s->draw_count ||
        s->plan_count!=s->draw_count || !s->invocation_count || !s->range_count || s->invocation_count>s->range_count ||
        !s->draws || !s->plans || !s->invocations || !s->ranges ||
        (u64)s->draw_count*2048!=s->draws_bytes || s->draws_bytes>(1ULL<<32) ||
        64+(u64)s->range_count*64>0xffffffffu ||
        !outer_bytes(s->plan_count,sizeof(*c->plans),&plans) ||
        !outer_bytes(s->invocation_count,sizeof(*c->invocations),&invocations) ||
        !outer_bytes(s->range_count,sizeof(*c->ranges),&ranges) ||
        !outer_bytes(s->draw_count,sizeof(u32),&di) || !outer_bytes(s->range_count,sizeof(u32),&ri))return -EINVAL;
    c->draws=kvzalloc(s->draws_bytes,GFP_KERNEL);c->plans=kvzalloc(plans,GFP_KERNEL);
    c->invocations=kvzalloc(invocations,GFP_KERNEL);c->ranges=kvzalloc(ranges,GFP_KERNEL);
    c->draw_index=kvzalloc(di,GFP_KERNEL);c->range_index=kvzalloc(ri,GFP_KERNEL);
    c->map=kvzalloc(64+(size_t)s->range_count*64,GFP_KERNEL);
    if(!c->draws || !c->plans || !c->invocations || !c->ranges || !c->draw_index || !c->range_index || !c->map)return -ENOMEM;
    memcpy(c->draws,s->draws,s->draws_bytes);memcpy(c->plans,s->plans,plans);
    memcpy(c->invocations,s->invocations,invocations);memcpy(c->ranges,s->ranges,ranges);
    return 0;
}
static bool outer_draw(const struct outer_capture *c,u32 index,const struct drm_opengpu_native_draw_plan *p)
{
    const u8 *d=c->draws+(size_t)index*2048;
    static const u32 header[]={0x474e4401,2048,1,16,16,16,4,4};
    for(u32 i=0;i<512;i++) {
        u32 bits=opengpu_gvs_draw_word_rules[i]&127u,v=outer_word(d,i);
        if(!bits) {if(v)return false;}
        else if(bits<32) {
            u32 mask=(1u<<bits)-1,expected=v&mask;
            if((opengpu_gvs_draw_word_rules[i]&128u) && (v&(1u<<(bits-1))))expected|=~mask;
            if(v!=expected)return false;
        }
    }
    for(u32 i=0;i<8;i++)if(outer_word(d,i)!=header[i])return false;
    return outer_word(d,29)==p->state_handle && outer_word(d,314)==c->source.context &&
        outer_word(d,315)==c->source.generation && outer_word(d,316)==c->source.pass &&
        outer_word(d,35)<=16 && outer_word(d,345)<=16 &&
        (outer_word(d,346)==1 || outer_word(d,346)==2) && !outer_word(d,342) &&
        !outer_word(d,506) && !outer_word(d,507);
}
static bool outer_unsupported(const u8 *d,const struct drm_opengpu_native_draw_plan *p)
{
    if(p->indirect.handle || p->indirect.offset || p->indirect.bytes)return true;
    for(u32 w=328;w<=335;w++)if(outer_word(d,w))return true;
    for(u32 w=480;w<=483;w++)if(outer_word(d,w))return true;
    for(u32 w=496;w<=505;w++)if(outer_word(d,w))return true;
    return false;
}
static int outer_validate(struct outer_capture *c)
{
    const struct opengpu_tessellation_vertex_outer_source_v2 *s=&c->source;
    u32 invocation=0,ordinal=0;
    u64 instance=0,patch=0;
    for(u32 i=0;i<s->plan_count;i++) {
        u32 d=c->plans[i].draw_index;
        if(d>=s->draw_count || c->draw_index[d] || !outer_draw(c,d,&c->plans[i]))return -EINVAL;
        if(c->plans[i].vertex_program.pad || c->plans[i].fragment_program.pad ||
            c->plans[i].vertex_uniforms.pad || c->plans[i].fragment_uniforms.pad ||
            (c->plans[i].vertex_uniforms.active_mask&~0xfffu) ||
            (c->plans[i].fragment_uniforms.active_mask&~0xfffu))return -EINVAL;
        if(outer_unsupported(c->draws+(size_t)d*2048,&c->plans[i]))return -EOPNOTSUPP;
        c->draw_index[d]=i+1;
    }
    for(u32 i=0;i<s->draw_count;i++)if(!c->draw_index[i])return -EINVAL;
    for(u32 i=0;i<s->invocation_count;i++) {
        const struct opengpu_tessellation_vertex_outer_invocation_v2 *v=&c->invocations[i];
        if(v->original_draw_index>=s->draw_count || !v->input_patch_vertices ||
            v->input_patch_vertices>32 || v->reserved[0] || v->reserved[1])return -EINVAL;
        for(u32 j=0;j<i;j++)if(c->invocations[j].invocation_id==v->invocation_id)return -EINVAL;
    }
    for(u32 i=0;i<s->range_count;i++) {
        u32 n=c->ranges[i].sequence_index;
        if(n>=s->range_count || c->range_index[n])return -EINVAL;
        c->range_index[n]=i+1;
    }
    for(u32 n=0;n<s->range_count;n++) {
        const struct opengpu_tessellation_vertex_outer_range_v2 *r=&c->ranges[c->range_index[n]-1];
        const struct opengpu_tessellation_vertex_outer_invocation_v2 *v;
        const u8 *d;
        u64 per,instances,count,first,end;
        u32 flags;
        if(invocation>=s->invocation_count || r->invocation_index!=invocation || r->range_ordinal!=ordinal || r->reserved)return -EINVAL;
        v=&c->invocations[invocation];d=c->draws+(size_t)v->original_draw_index*2048;
        per=outer_word(d,311)/v->input_patch_vertices;instances=outer_word(d,325);
        count=(u64)r->slice.patches_per_instance*r->slice.instances;
        flags=(ordinal?OPENGPU_VERTEX_OUTER_CONTINUATION:OPENGPU_VERTEX_OUTER_FIRST);
        if(r->original_draw_index!=v->original_draw_index || r->layout.input_vertices!=v->input_patch_vertices ||
            r->slice.version!=1 || r->slice.first_ordinal%v->input_patch_vertices || count!=r->patch_count ||
            (u64)r->task_id_base+r->patch_count>(1ULL<<32))return -EINVAL;
        first=r->slice.first_ordinal/v->input_patch_vertices;
        end=first+r->slice.patches_per_instance;
        if(!per || !instances) {
            if(ordinal || count || r->slice.first_ordinal || r->slice.patches_per_instance ||
                r->slice.first_instance || r->slice.instances)return -EINVAL;
            flags|=OPENGPU_VERTEX_OUTER_LAST;
        }else {
            if(!count || r->slice.first_instance!=instance || first!=patch || end>per ||
                instance+r->slice.instances>instances ||
                (r->slice.instances>1 && (first || r->slice.patches_per_instance!=per)))return -EINVAL;
            instance+=r->slice.instances-1;patch=end;
            if(patch==per){instance++;patch=0;}
            if(instance==instances)flags|=OPENGPU_VERTEX_OUTER_LAST;
        }
        if(r->flags!=flags)return -EINVAL;
        {
            u8 *m=c->map+64+(size_t)n*64;
            const u32 words[]={n,r->original_draw_index,(u32)v->invocation_id,(u32)(v->invocation_id>>32),flags,
                ordinal,r->slice.first_instance,r->slice.instances,(u32)first,r->slice.patches_per_instance,
                r->task_id_base,r->patch_count};
            for(u32 w=0;w<12;w++)outer_put(m,w,words[w]);
        }
        if(flags&OPENGPU_VERTEX_OUTER_LAST){invocation++;ordinal=0;instance=patch=0;}
        else {if(ordinal==0xffffffffu)return -EINVAL;ordinal++;}
    }
    if(invocation!=s->invocation_count)return -EINVAL;
    {
        const u32 header[]={OPENGPU_VERTEX_OUTER_MAP_MAGIC,64+s->range_count*64,2,64,
            s->draw_count,s->invocation_count,s->range_count,s->context,s->pass,s->generation,16,16,16,8,2,0};
        for(u32 i=0;i<16;i++)outer_put(c->map,i,header[i]);
    }
    return opengpu_tessellation_vertex_outer_map_source_canonical(c->map,64+(size_t)s->range_count*64)?0:-EINVAL;
}
int opengpu_tessellation_vertex_outer_append(struct opengpu_private_snapshot *owner,
    const struct opengpu_tessellation_vertex_outer_source_v2 *source,
    const struct opengpu_native_job_copy *copy,u32 *output)
{
    struct outer_capture *c;
    u32 map;
    int ret;
    if(!owner || !source || !copy || !copy->copy || !copy->resolve || !output)return -EINVAL;
    if(!source->profile.available)return -EOPNOTSUPP;
    if(source->profile.version!=1 || source->profile.compute_units!=2 || source->profile.lanes!=16 ||
        source->profile.warps!=8 || !source->profile.patch_zero_initialized)return -EINVAL;
    ret=opengpu_private_snapshot_build_context(owner,source->context);
    if(ret)return ret;
    c=kvzalloc(sizeof(*c),GFP_KERNEL);if(!c)return -ENOMEM;
    c->source=*source;c->copy=*copy;
    ret=outer_capture_all(c);if(ret)goto done;
    ret=outer_validate(c);if(ret)goto done;
    ret=opengpu_private_snapshot_append(owner,c->map,64+(size_t)c->source.range_count*64,&map);
    if(ret)goto done;
    for(u32 n=0;n<c->source.range_count;n++) {
        const struct opengpu_tessellation_vertex_outer_range_v2 *r=&c->ranges[c->range_index[n]-1];
        const struct drm_opengpu_native_draw_plan *p=&c->plans[c->draw_index[r->original_draw_index]-1];
        struct opengpu_tessellation_vertex_source s={0};
        u32 root;
        s.draw=c->draws+(size_t)r->original_draw_index*2048;s.draw_bytes=2048;
        s.context=c->source.context;s.pass=c->source.pass;s.generation=c->source.generation;
        s.sequence_index=n;s.draw_index=r->original_draw_index;s.task_id_base=r->task_id_base;s.patch_count=r->patch_count;
        s.slice=r->slice;s.layout=r->layout;memcpy(s.components,r->components,sizeof(s.components));s.profile=c->source.profile;
        s.plan.program=p->vertex_program;s.plan.uniforms=p->vertex_uniforms;
        memcpy(s.plan.bindings,p->bindings,sizeof(s.plan.bindings));s.plan.indices=p->indices;
        s.plan.indirect=p->indirect;s.plan.scratch=p->vertex_scratch;
        ret=opengpu_tessellation_vertex_source_append(owner,&s,&c->copy,&root);
        if(!ret)ret=opengpu_private_snapshot_relocate(owner,map,64+(size_t)n*64+48,root,0,OPENGPU_PRIVATE_SNAPSHOT_VA32);
        if(!ret)ret=opengpu_private_snapshot_relocate(owner,map,64+(size_t)n*64+52,root,512,OPENGPU_PRIVATE_SNAPSHOT_LIMIT33);
        if(ret)goto done;
    }
    *output=map;
done:
    outer_free(c);return ret;
}
