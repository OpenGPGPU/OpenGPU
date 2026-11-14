// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "opengpu_native_graph_alias.h"

enum alias_role { ALIAS_INPUT,ALIAS_VERTEX_SCRATCH,ALIAS_FRONTEND,ALIAS_IMAGE,
                  ALIAS_FRAGMENT_SCRATCH,ALIAS_ROLES };
struct alias_span {u64 first,end;enum alias_role role;};
static int alias_view(struct alias_span *spans,size_t *count,u32 handle,u64 offset,u64 bytes,
    enum alias_role role,opengpu_native_frame_resolve resolve,void *context)
{
    struct opengpu_native_frame_backing backing={0};
    u64 first,end;
    int ret;
    if(!handle)return offset || bytes?-EINVAL:0;
    if(!bytes || offset>~(u64)0-bytes)return -EINVAL;
    ret=resolve(context,handle,&backing);if(ret)return ret;
    if(!backing.bytes || ((backing.physical|backing.bytes)&4095u) ||
       backing.bytes>(1ULL<<32) || backing.physical>(1ULL<<32)-backing.bytes ||
       offset+bytes>backing.bytes)return -EINVAL;
    first=backing.physical+offset;end=first+bytes;
    spans[(*count)++]=(struct alias_span){first&~63ULL,(end+63u)&~63ULL,role};
    return 0;
}
static void alias_swap(struct alias_span *a,struct alias_span *b)
{struct alias_span temporary=*a;*a=*b;*b=temporary;}
static void alias_sift(struct alias_span *spans,size_t root,size_t count)
{
    while(root<count/2){
        size_t child=root*2+1;
        if(child+1<count && spans[child].first<spans[child+1].first)child++;
        if(spans[root].first>=spans[child].first)break;
        alias_swap(&spans[root],&spans[child]);root=child;
    }
}
static void alias_sort(struct alias_span *spans,size_t count)
{
    for(size_t i=count/2;i;i--)alias_sift(spans,i-1,count);
    for(size_t i=count;i>1;i--){alias_swap(&spans[0],&spans[i-1]);alias_sift(spans,0,i-1);}
}
static bool alias_conflict(enum alias_role a,enum alias_role b)
{
    /* Component frame validation handles all GNF-to-GNF pairs. Draw sources
     * are fully consumed before fragment replay: WindowPrimitiveBinReplayPass
     * never seals an early tile and ends bins only after producer completion.
     * VertexBinPipeline drains its source before the next draw completion.
     * Preserve those legal reuse cases; GNC2 separately forbids VS/image alias. */
    if(a>=ALIAS_FRONTEND && b>=ALIAS_FRONTEND)return false;
    if(a==ALIAS_FRONTEND || b==ALIAS_FRONTEND)return true;
    if((a==ALIAS_VERTEX_SCRATCH && (b==ALIAS_INPUT || b==ALIAS_IMAGE)) ||
       (b==ALIAS_VERTEX_SCRATCH && (a==ALIAS_INPUT || a==ALIAS_IMAGE)))return true;
    return false;
}
int opengpu_native_graph_alias_check(const struct opengpu_native_job_source *s,
                                    opengpu_native_frame_resolve resolve,void *context)
{
    struct alias_span *spans;
    u64 maximum,end[ALIAS_ROLES]={0};
    size_t count=0;
    u32 i,j;
    int ret=0;
    if(!s || !resolve || (s->plan_count && !s->plans) ||
       (s->resource_count && !s->resources))return -EINVAL;
    maximum=(u64)s->plan_count*19u+s->resource_count;
    if(maximum>(u64)(~(size_t)0)/sizeof(*spans))return -ERANGE;
    if(!maximum)return 0;
    spans=kvzalloc((size_t)maximum*sizeof(*spans),GFP_KERNEL);if(!spans)return -ENOMEM;
    for(i=0;i<s->plan_count;i++){
        const struct drm_opengpu_native_draw_plan *p=&s->plans[i];
        for(j=0;j<19;j++){
            const struct drm_opengpu_compute_uniform_view *v=j<16?&p->bindings[j]:
                j==16?&p->indices:j==17?&p->indirect:&p->vertex_scratch;
            ret=alias_view(spans,&count,v->handle,v->offset,v->bytes,
                j==18?ALIAS_VERTEX_SCRATCH:ALIAS_INPUT,resolve,context);
            if(ret)goto done;
        }
    }
    for(i=0;i<s->resource_count;i++){
        const struct opengpu_native_frame_resource *v=&s->resources[i];
        enum alias_role role;
        switch(v->role){
        case OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE:
        case OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE:role=ALIAS_IMAGE;break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_PRIMITIVES:
        case OPENGPU_NATIVE_FRAME_RESOURCE_POOL:
        case OPENGPU_NATIVE_FRAME_RESOURCE_DIRECTORY:role=ALIAS_FRONTEND;break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_SCRATCH:role=ALIAS_FRAGMENT_SCRATCH;break;
        default:ret=-EINVAL;goto done;
        }
        ret=alias_view(spans,&count,v->gem_handle,v->offset,v->bytes,role,resolve,context);
        if(ret)goto done;
    }
    alias_sort(spans,count);
    for(size_t n=0;n<count;n++){
        const struct alias_span *span=&spans[n];
        for(j=0;j<ALIAS_ROLES;j++)if(alias_conflict(span->role,j) && end[j]>span->first){ret=-EINVAL;goto done;}
        if(end[span->role]<span->end)end[span->role]=span->end;
    }
done:
    kvfree(spans);return ret;
}
