// SPDX-License-Identifier: GPL-2.0
/* Typed original-GEM views into a job-owned GNF publication. */
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/string.h>
#include <linux/vmalloc.h>
#include "opengpu_private_snapshot.h"
#include "opengpu_native_frame_snapshot.h"
#include "opengpu_native_binned_abi.h"
#include "opengpu_typed_fragment_state_abi.h"

#define W(name) OPENGPU_NATIVE_BINNED_FRAME_##name##_WORD
#define RESOURCE_READ OPENGPU_NATIVE_FRAME_RESOURCE_READ
#define RESOURCE_WRITE OPENGPU_NATIVE_FRAME_RESOURCE_WRITE
#define PAGE 4096u
#define MAX_RESOURCES 27u
struct view_words { u32 base, limit, allocation_base, allocation_limit, read, write, store; };
#define VIEW(name) { W(name##_BASE), W(name##_LIMIT), W(name##_ALLOCATIONBASE), \
    W(name##_ALLOCATIONLIMIT), W(name##_READABLE), W(name##_WRITABLE), W(name##_STORE) }
static const struct view_words images[6] = {
    VIEW(FRAME_IMAGE_VIEWS0), VIEW(FRAME_IMAGE_VIEWS1), VIEW(FRAME_IMAGE_VIEWS2),
    VIEW(FRAME_IMAGE_VIEWS3), VIEW(FRAME_IMAGE_VIEWS4), VIEW(FRAME_IMAGE_VIEWS5)
};
static const struct view_words resolves[6] = {
    VIEW(FRAME_IMAGE_RESOLVES0_DESTINATION), VIEW(FRAME_IMAGE_RESOLVES1_DESTINATION),
    VIEW(FRAME_IMAGE_RESOLVES2_DESTINATION), VIEW(FRAME_IMAGE_RESOLVES3_DESTINATION),
    VIEW(FRAME_IMAGE_RESOLVES4_DESTINATION), VIEW(FRAME_IMAGE_RESOLVES5_DESTINATION)
};
#undef VIEW
static const u32 resolve_enable[6] = {
    W(FRAME_IMAGE_RESOLVES0_ENABLE), W(FRAME_IMAGE_RESOLVES1_ENABLE),
    W(FRAME_IMAGE_RESOLVES2_ENABLE), W(FRAME_IMAGE_RESOLVES3_ENABLE),
    W(FRAME_IMAGE_RESOLVES4_ENABLE), W(FRAME_IMAGE_RESOLVES5_ENABLE)
};
struct range_words { u32 base, limit; };
#define RANGE(name) { W(name##_BASE), W(name##_LIMIT) }
static const struct range_words scratch[4] = {
    RANGE(FRAME_SCRATCH0), RANGE(FRAME_SCRATCH1), RANGE(FRAME_SCRATCH2), RANGE(FRAME_SCRATCH3)
};
#define GFS(name) OPENGPU_TYPED_FRAGMENT_STATE_##name##_WORD
#define GFS_VIEW(name) { GFS(name##_BASE), GFS(name##_LIMIT), GFS(name##_ALLOCATIONBASE), \
    GFS(name##_ALLOCATIONLIMIT), 0, 0, 0 }
static const struct view_words state_scratch[4] = {
    GFS_VIEW(FRAGMENT_SCRATCH), GFS_VIEW(SCRATCH0), GFS_VIEW(SCRATCH1), GFS_VIEW(SCRATCH2)
};
#undef GFS_VIEW
static const struct range_words spills[8] = {
    RANGE(FRAME_BINS_BINS_POOL_SPILLS0), RANGE(FRAME_BINS_BINS_POOL_SPILLS1),
    RANGE(FRAME_BINS_BINS_POOL_SPILLS2), RANGE(FRAME_BINS_BINS_POOL_SPILLS3),
    RANGE(FRAME_BINS_BINS_POOL_SPILLS4), RANGE(FRAME_BINS_BINS_POOL_SPILLS5),
    RANGE(FRAME_BINS_BINS_POOL_SPILLS6), RANGE(FRAME_BINS_BINS_POOL_SPILLS7)
};
#undef RANGE
struct resolved_view {
    const struct opengpu_native_frame_resource *source;
    u64 physical, first_line, line_end, page, pages, delta;
    u32 owner_resource;
};
static u32 get32(const void *bytes, u32 word)
{
    const unsigned char *p=(const unsigned char *)bytes+4u*word;
    return (u32)p[0] | ((u32)p[1]<<8) | ((u32)p[2]<<16) | ((u32)p[3]<<24);
}
static bool zero_range(const void *frame, u32 base, u32 limit)
{
    return !get32(frame,base) && !get32(frame,limit) && !get32(frame,limit+1);
}
static bool zero_view(const void *frame, const struct view_words *v)
{
    return zero_range(frame,v->base,v->limit) &&
           zero_range(frame,v->allocation_base,v->allocation_limit);
}
static bool zero_addresses(const void *frame)
{
    u32 i;
    if (!zero_range(frame,W(FRAME_BINS_BINS_DIRECTORYBASE),W(FRAME_BINS_BINS_DIRECTORYLIMIT)) ||
        !zero_range(frame,W(FRAME_BINS_PRODUCER_ARENA_BASE),W(FRAME_BINS_PRODUCER_ARENA_LIMIT)) ||
        !zero_range(frame,W(FRAME_BINS_BINS_POOL_BASE),W(FRAME_BINS_BINS_POOL_LIMIT)) ||
        get32(frame,W(FRAME_BINS_BINS_POOL_INITIALLIMIT)) ||
        get32(frame,W(FRAME_BINS_BINS_POOL_INITIALLIMIT)+1) ||
        !zero_range(frame,W(FRAME_STATES_BASE),W(FRAME_STATES_LIMIT))) return false;
    for(i=0;i<6;++i)if(!zero_view(frame,&images[i]) || !zero_view(frame,&resolves[i]))return false;
    for(i=0;i<4;++i)if(!zero_range(frame,scratch[i].base,scratch[i].limit))return false;
    for(i=0;i<8;++i)if(!zero_range(frame,spills[i].base,spills[i].limit))return false;
    return true;
}
static int expected_view(const void *frame, const struct view_words *view, u32 *access)
{
    u32 read=get32(frame,view->read),write=get32(frame,view->write);
    if(read>1 || write>1 || get32(frame,view->store)!=write)return -EINVAL;
    *access=read*RESOURCE_READ|write*RESOURCE_WRITE;
    return 0;
}
static int frame_relocate(struct opengpu_private_snapshot *owner,u32 frame,u32 word,
                    u32 resource,u64 addend,bool limit)
{
    return opengpu_private_snapshot_relocate_resource(owner,frame,4u*word,resource,addend,
        limit?OPENGPU_PRIVATE_SNAPSHOT_LIMIT33:OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
static int patch_range(struct opengpu_private_snapshot *owner,u32 frame,
                       struct range_words words,const struct resolved_view *view)
{
    int ret=frame_relocate(owner,frame,words.base,view->owner_resource,view->delta,false);
    if(!ret)ret=frame_relocate(owner,frame,words.limit,view->owner_resource,
                         view->delta+view->source->bytes,true);
    return ret;
}
static int patch_view(struct opengpu_private_snapshot *owner,u32 frame,
                      const struct view_words *words,const struct resolved_view *view)
{
    int ret=patch_range(owner,frame,(struct range_words){words->base,words->limit},view);
    if(!ret)ret=frame_relocate(owner,frame,words->allocation_base,view->owner_resource,
                         view->first_line-view->page,false);
    if(!ret)ret=frame_relocate(owner,frame,words->allocation_limit,view->owner_resource,
                         view->line_end-view->page,true);
    return ret;
}

static int check_state_scratch(const struct opengpu_private_snapshot *owner,u32 states,
                               size_t state_bytes,u32 engines)
{
    size_t offset;
    u32 e,i,value;
    int ret;
    for(offset=0;offset<state_bytes;offset+=OPENGPU_TYPED_FRAGMENT_STATE_BYTES){
        ret=opengpu_private_snapshot_read32(owner,states,offset+4u*GFS(ENGINECOUNT),&value);
        if(ret)return ret;
        if(value!=engines)return -EINVAL;
        for(e=0;e<4;++e){
            const struct view_words *v=&state_scratch[e];
            const u32 words[6]={v->base,v->limit,v->limit+1,v->allocation_base,
                                v->allocation_limit,v->allocation_limit+1};
            for(i=0;i<6;++i){
                ret=opengpu_private_snapshot_read32(owner,states,offset+4u*words[i],&value);
                if(ret)return ret;
                if(value)return -EINVAL;
            }
        }
    }
    return 0;
}
static int patch_state_scratch(struct opengpu_private_snapshot *owner,u32 states,
                               size_t state_bytes,u32 stride,u32 engine,
                               const struct resolved_view *view)
{
    const struct view_words *v=&state_scratch[engine];
    size_t offset;
    u64 addend=view->delta;
    int ret;
    for(offset=0;offset<state_bytes;offset+=OPENGPU_TYPED_FRAGMENT_STATE_BYTES,addend+=stride){
        u32 word=(u32)(offset/4);
        ret=frame_relocate(owner,states,word+v->base,view->owner_resource,addend,false);
        if(!ret)ret=frame_relocate(owner,states,word+v->limit,view->owner_resource,addend+stride,true);
        if(!ret)ret=frame_relocate(owner,states,word+v->allocation_base,view->owner_resource,addend,false);
        if(!ret)ret=frame_relocate(owner,states,word+v->allocation_limit,view->owner_resource,addend+stride,true);
        if(ret)return ret;
    }
    return 0;
}

static int append_frame(struct opengpu_private_snapshot *owner,
    const void *frame,u32 states,size_t state_bytes,
    const struct opengpu_native_frame_resource *plans,u32 count,
    u32 engines,u32 context,u32 pass,u32 generation,
    opengpu_native_frame_resolve resolve,void *resolve_context,u32 *out,bool complete)
{
    struct resolved_view *views;
    const struct resolved_view *roles[7][9]={{0}};
    u32 i,j,segment,spill_count,pool_resource;
    u64 pool_bytes=0,cursor=0;
    int ret=-EINVAL;
    if(!owner || !frame || !out || !resolve || !plans || !count || count>MAX_RESOURCES ||
        !engines || engines>4 || !state_bytes || (state_bytes%512) ||
        (u64)state_bytes>(1ULL<<32) || !zero_addresses(frame) ||
        get32(frame,0)!=OPENGPU_NATIVE_BINNED_FRAME_MAGIC ||
        get32(frame,1)!=OPENGPU_NATIVE_BINNED_FRAME_BYTES ||
        get32(frame,2)!=OPENGPU_NATIVE_BINNED_VERSION)return -EINVAL;
    if(complete){
        int checked=check_state_scratch(owner,states,state_bytes,engines);
        if(checked)return checked;
    }
#define ID(prefix) (get32(frame,W(prefix##_CONTEXT))==context && \
                    get32(frame,W(prefix##_PASS))==pass && \
                    get32(frame,W(prefix##_GENERATION))==generation)
    if(!ID(FRAME_STATES_IDENTITY) || !ID(FRAME_BINS_PRODUCER_ARENA_IDENTITY) ||
       !ID(FRAME_BINS_BINS_POOL_IDENTITY) || !ID(FRAME_IMAGE_PIXELS_COLORS_IDENTITY))return -EINVAL;
#undef ID
    spill_count=get32(frame,W(FRAME_BINS_BINS_POOL_SPILLCOUNT));
    if(spill_count>8 || get32(frame,W(FRAME_BINS_BINS_POOL_VERSION))!=(spill_count?2u:1u) ||
       get32(frame,W(FRAME_BINS_BINS_POOL_PAGESIZELOG2))!=12)return -EINVAL;
    views=kvzalloc((size_t)count*sizeof(*views),GFP_KERNEL);
    if(!views)return -ENOMEM;
    for(i=0;i<count;++i){
        const struct opengpu_native_frame_resource *p=&plans[i];
        struct opengpu_native_frame_backing backing={0};
        struct resolved_view *v=&views[i];
        u64 end,physical_end;
        u32 alignment=64,access=RESOURCE_READ|RESOURCE_WRITE;
        if(p->role<1 || p->role>6 || p->index>8 || roles[p->role][p->index] ||
            !p->gem_handle || !p->bytes || p->bytes>(1ULL<<32))goto done;
        switch(p->role){
        case OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE:
        case OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE: {
            const struct view_words *words;
            if(p->index>=6)goto done;
            words=p->role==OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE?&images[p->index]:&resolves[p->index];
            if(expected_view(frame,words,&access) || !access)goto done;
            if(p->role==OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE &&
               (get32(frame,resolve_enable[p->index])!=1 || access!=RESOURCE_WRITE))goto done;
            alignment=1;break;
        }
        case OPENGPU_NATIVE_FRAME_RESOURCE_POOL:
            if(p->index>spill_count || ((p->offset|p->bytes)&(PAGE-1u)))goto done;
            alignment=PAGE;pool_bytes+=p->bytes;
            if(pool_bytes>(1ULL<<32)-PAGE)goto done;
            break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_SCRATCH:
            if(p->index>=engines)goto done;
            if(get32(frame,W(FRAME_SCRATCHSTRIDE))<64 ||
                (get32(frame,W(FRAME_SCRATCHSTRIDE))&63) ||
                (u64)(state_bytes/512)*get32(frame,W(FRAME_SCRATCHSTRIDE))>p->bytes)goto done;
            break;
        default: if(p->index)goto done;
        }
        if(p->access!=access || ((p->offset|p->bytes)&(alignment-1u)) ||
            check_add_overflow(p->offset,p->bytes,&end))goto done;
        ret=resolve(resolve_context,p->gem_handle,&backing);if(ret)goto done;
        ret=-EINVAL;
        if(!backing.bytes || (backing.physical&(PAGE-1u)) || (backing.bytes&(PAGE-1u)) ||
           backing.bytes>(1ULL<<32) || backing.physical>(1ULL<<32)-backing.bytes ||
           end>backing.bytes)goto done;
        v->source=p;v->physical=backing.physical+p->offset;
        physical_end=v->physical+p->bytes;
        v->first_line=v->physical&~63ULL;v->line_end=(physical_end+63)&~63ULL;
        if(v->line_end>backing.physical+backing.bytes)goto done;
        v->page=v->physical&~(u64)(PAGE-1u);
        v->pages=((physical_end+PAGE-1u)&~(u64)(PAGE-1u))-v->page;
        v->delta=v->physical-v->page;
        for(j=0;j<i;++j)if(((p->access|views[j].source->access)&RESOURCE_WRITE) &&
            v->first_line<views[j].line_end && views[j].first_line<v->line_end)goto done;
        roles[p->role][p->index]=v;
    }
    if(!roles[OPENGPU_NATIVE_FRAME_RESOURCE_PRIMITIVES][0] ||
       !roles[OPENGPU_NATIVE_FRAME_RESOURCE_DIRECTORY][0] || !pool_bytes ||
       (!spill_count && !roles[OPENGPU_NATIVE_FRAME_RESOURCE_POOL][0]))goto done;
    if(roles[OPENGPU_NATIVE_FRAME_RESOURCE_DIRECTORY][0]->source->bytes<
        (u64)get32(frame,W(FRAME_BINS_BINS_TILECOUNT))*64)goto done;
    for(i=1;i<=spill_count;++i)if(!roles[OPENGPU_NATIVE_FRAME_RESOURCE_POOL][i])goto done;
    for(i=0;i<4;++i)if((i<engines)!=!!roles[OPENGPU_NATIVE_FRAME_RESOURCE_SCRATCH][i])goto done;
    for(i=0;i<6;++i){
        u32 access;
        if(expected_view(frame,&images[i],&access) ||
           (!!access!=!!roles[OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE][i]))goto done;
        if(expected_view(frame,&resolves[i],&access) || get32(frame,resolve_enable[i])>1 ||
           (!!access!=!!roles[OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE][i]) ||
           (!!access!=!!get32(frame,resolve_enable[i])))goto done;
    }
    ret=opengpu_private_snapshot_append(owner,frame,OPENGPU_NATIVE_BINNED_FRAME_BYTES,&segment);
    if(ret)goto done;
    ret=opengpu_private_snapshot_resource(owner,(size_t)pool_bytes,&pool_resource);if(ret)goto done;
    for(i=0;i<=spill_count;++i){
        struct resolved_view *v=(struct resolved_view *)roles[OPENGPU_NATIVE_FRAME_RESOURCE_POOL][i];
        if(!v)continue;
        v->owner_resource=pool_resource;v->delta=cursor;
        ret=opengpu_private_snapshot_resource_map(owner,pool_resource,cursor,v->page,v->pages,true);
        if(ret)goto done;
        if(i){ret=patch_range(owner,segment,spills[i-1],v);if(ret)goto done;}
        cursor+=v->source->bytes;
    }
    ret=frame_relocate(owner,segment,W(FRAME_BINS_BINS_POOL_BASE),pool_resource,0,false);
    if(!ret)ret=frame_relocate(owner,segment,W(FRAME_BINS_BINS_POOL_LIMIT),pool_resource,pool_bytes,true);
    if(!ret)ret=frame_relocate(owner,segment,W(FRAME_BINS_BINS_POOL_INITIALLIMIT),pool_resource,
        roles[OPENGPU_NATIVE_FRAME_RESOURCE_POOL][0]?roles[OPENGPU_NATIVE_FRAME_RESOURCE_POOL][0]->source->bytes:0,true);
    if(ret)goto done;
    for(i=0;i<count;++i){
        struct resolved_view *v=&views[i];
        const struct opengpu_native_frame_resource *p=v->source;
        if(p->role==OPENGPU_NATIVE_FRAME_RESOURCE_POOL)continue;
        ret=opengpu_private_snapshot_resource(owner,(size_t)v->pages,&v->owner_resource);if(ret)goto done;
        ret=opengpu_private_snapshot_resource_map(owner,v->owner_resource,0,v->page,v->pages,!!(p->access&RESOURCE_WRITE));if(ret)goto done;
        switch(p->role){
        case OPENGPU_NATIVE_FRAME_RESOURCE_IMAGE:ret=patch_view(owner,segment,&images[p->index],v);break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_RESOLVE:ret=patch_view(owner,segment,&resolves[p->index],v);break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_PRIMITIVES:ret=patch_range(owner,segment,
            (struct range_words){W(FRAME_BINS_PRODUCER_ARENA_BASE),W(FRAME_BINS_PRODUCER_ARENA_LIMIT)},v);break;
        case OPENGPU_NATIVE_FRAME_RESOURCE_DIRECTORY:ret=patch_range(owner,segment,
            (struct range_words){W(FRAME_BINS_BINS_DIRECTORYBASE),W(FRAME_BINS_BINS_DIRECTORYLIMIT)},v);break;
        default:
            ret=patch_range(owner,segment,scratch[p->index],v);
            if(!ret && complete)ret=patch_state_scratch(owner,states,state_bytes,
                get32(frame,W(FRAME_SCRATCHSTRIDE)),p->index,v);
        }
        if(ret)goto done;
    }
    ret=opengpu_private_snapshot_relocate(owner,segment,4u*W(FRAME_STATES_BASE),states,0,OPENGPU_PRIVATE_SNAPSHOT_VA32);
    if(!ret)ret=opengpu_private_snapshot_relocate(owner,segment,4u*W(FRAME_STATES_LIMIT),states,state_bytes,OPENGPU_PRIVATE_SNAPSHOT_LIMIT33);
    if(!ret)*out=segment;
done:
    kvfree(views);
    return ret;
}

int opengpu_native_frame_snapshot_append(struct opengpu_private_snapshot *owner,
    const void *frame,u32 states,size_t state_bytes,
    const struct opengpu_native_frame_resource *plans,u32 count,
    u32 engines,u32 context,u32 pass,u32 generation,
    opengpu_native_frame_resolve resolve,void *resolve_context,u32 *out)
{
    return append_frame(owner,frame,states,state_bytes,plans,count,engines,context,pass,generation,
                        resolve,resolve_context,out,false);
}
int opengpu_native_frame_snapshot_append_complete(struct opengpu_private_snapshot *owner,
    const void *frame,u32 states,size_t state_bytes,
    const struct opengpu_native_frame_resource *plans,u32 count,
    u32 engines,u32 context,u32 pass,u32 generation,
    opengpu_native_frame_resolve resolve,void *resolve_context,u32 *out)
{
    return append_frame(owner,frame,states,state_bytes,plans,count,engines,context,pass,generation,
                        resolve,resolve_context,out,true);
}
#undef RESOURCE_READ
#undef RESOURCE_WRITE
#undef PAGE
#undef W
#undef GFS
