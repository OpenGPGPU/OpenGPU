// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "opengpu_native_input_snapshot.h"
#include "opengpu_native_geometry_abi.h"
#include "opengpu_private_snapshot.h"
#include "opengpu_native_binned_abi.h"

#define I(name) OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_VERTEX_INPUT_##name##_WORD
#define V(name) OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_VERTEX_##name##_WORD
#define G(name) OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERTEX_GEOMETRY_##name##_WORD
#define PAGE 4096u
struct input_words {u32 base,limit,allocation_base,allocation_limit,stride,divisor;};
#define B(n) {I(BINDINGS##n##_BASE),I(BINDINGS##n##_LIMIT),I(BINDINGS##n##_ALLOCATIONBASE), \
    I(BINDINGS##n##_ALLOCATIONLIMIT),I(BINDINGS##n##_STRIDE),I(BINDINGS##n##_DIVISOR)}
static const struct input_words binding_words[16]={
    B(0),B(1),B(2),B(3),B(4),B(5),B(6),B(7),B(8),B(9),B(10),B(11),B(12),B(13),B(14),B(15)
};
#undef B
static const struct input_words index_words={I(INDICES_BASE),I(INDICES_LIMIT),
    I(INDICES_ALLOCATIONBASE),I(INDICES_ALLOCATIONLIMIT),0,0};
static const struct input_words indirect_words={G(INDIRECT_VIEW_BASE),G(INDIRECT_VIEW_LIMIT),
    G(INDIRECT_VIEW_ALLOCATIONBASE),G(INDIRECT_VIEW_ALLOCATIONLIMIT),0,0};
static const struct input_words scratch_words={V(SCRATCH_BASE),V(SCRATCH_LIMIT),
    V(SCRATCH_ALLOCATIONBASE),V(SCRATCH_ALLOCATIONLIMIT),0,0};
struct attribute_words {u32 enabled,binding,components,format,offset;};
#define A(n) {I(ATTRIBUTES##n##_ENABLED),I(ATTRIBUTES##n##_BINDINGINDEX), \
    I(ATTRIBUTES##n##_COMPONENTS),I(ATTRIBUTES##n##_FORMAT),I(ATTRIBUTES##n##_OFFSET)}
static const struct attribute_words attribute_words[16]={
    A(0),A(1),A(2),A(3),A(4),A(5),A(6),A(7),A(8),A(9),A(10),A(11),A(12),A(13),A(14),A(15)
};
#undef A
struct input_view {
    const struct drm_opengpu_compute_uniform_view *source;
    const struct input_words *words;
    u64 first_line,line_end,page,pages,delta;
    u32 resource;
    bool writable;
};
static u32 input_word(const void *raw,u32 word)
{
    const unsigned char *b=(const unsigned char *)raw+4u*word;
    return (u32)b[0]|(u32)b[1]<<8|(u32)b[2]<<16|(u32)b[3]<<24;
}
static bool input_empty(const struct drm_opengpu_compute_uniform_view *v)
{return !v->handle && !v->offset && !v->bytes;}
static bool zero_words(const void *raw,u32 first,u32 last)
{
    for(u32 word=first;word<=last;word++)if(input_word(raw,word))return false;
    return true;
}
static u32 element_size(u32 format)
{
    if(format==3 || format==4 || format==7 || format==10 || format==11 || format==16 || format==17)return 2;
    if(format==5 || format==6 || format==8 || format==9 || format==14 || format==15)return 1;
    return 4;
}
static int resolve_input(struct input_view *v,opengpu_native_frame_resolve resolve,void *context)
{
    struct opengpu_native_frame_backing backing={0};
    const struct drm_opengpu_compute_uniform_view *s=v->source;
    u64 end,physical,physical_end;
    int ret;
    if(!s->handle || !s->bytes || s->offset>~(u64)0-s->bytes)return -EINVAL;
    end=s->offset+s->bytes;
    ret=resolve(context,s->handle,&backing);if(ret)return ret;
    if(!backing.bytes || ((backing.physical|backing.bytes)&(PAGE-1u)) ||
        backing.bytes>(1ULL<<32) || backing.physical>(1ULL<<32)-backing.bytes || end>backing.bytes)return -EINVAL;
    physical=backing.physical+s->offset;physical_end=physical+s->bytes;
    v->first_line=physical&~63ULL;v->line_end=(physical_end+63u)&~63ULL;
    if(v->line_end>backing.physical+backing.bytes)return -EINVAL;
    v->page=physical&~(u64)(PAGE-1u);
    v->pages=((physical_end+PAGE-1u)&~(u64)(PAGE-1u))-v->page;
    v->delta=physical-v->page;
    if((u64)(size_t)v->pages!=v->pages)return -EINVAL;
    return 0;
}
static int input_relocate(struct opengpu_private_snapshot *owner,u32 segment,u64 offset,u32 word,
    const struct input_view *view,u64 addend,bool limit)
{
    return opengpu_private_snapshot_relocate_resource(owner,segment,(size_t)(offset+4u*word),
        view->resource,addend,limit?OPENGPU_PRIVATE_SNAPSHOT_LIMIT33:OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
static int native_input_patch(struct opengpu_private_snapshot *owner,
    u32 segment,u64 offset,const void *raw,const struct drm_opengpu_native_draw_plan *plan,u32 capacity,
    opengpu_native_frame_resolve resolve,void *context,bool geometry)
{
    struct input_view *views;
    u32 used=0,count=0,index_format,mode,version,attributes,varyings,vs_version;
    bool work;
    int ret=-EINVAL;
    if(!owner || !raw || !plan || !resolve || !capacity || capacity>255 || (offset&63u) ||
        offset>~(u64)0-OPENGPU_NATIVE_BINNED_DRAW_BYTES ||
        (u64)(size_t)(offset+OPENGPU_NATIVE_BINNED_DRAW_BYTES)!=offset+OPENGPU_NATIVE_BINNED_DRAW_BYTES ||
        offset+OPENGPU_NATIVE_BINNED_DRAW_BYTES>(1ULL<<32))return -EINVAL;
    for(u32 word=0;word<OPENGPU_NATIVE_BINNED_DRAW_WORDS;word++) {
        u32 copied;
        if(opengpu_private_snapshot_read32(owner,segment,(size_t)(offset+word*4u),&copied) ||
            copied!=input_word(raw,word))return -EINVAL;
    }
    if(input_word(raw,0)!=(geometry?OPENGPU_NATIVE_GEOMETRY_DRAW_MAGIC:OPENGPU_NATIVE_BINNED_DRAW_MAGIC) || input_word(raw,1)!=OPENGPU_NATIVE_BINNED_DRAW_BYTES ||
        input_word(raw,2)!=(geometry?2u:1u) || input_word(raw,3)!=16 || input_word(raw,4)!=16 ||
        input_word(raw,5)!=16 || input_word(raw,6)!=4 || input_word(raw,7)!=4 ||
        input_word(raw,OPENGPU_NATIVE_BINNED_DRAW_DRAW_VERSION_WORD)!=1)return -EINVAL;
    attributes=input_word(raw,V(ATTRIBUTESLOTS));varyings=input_word(raw,V(VARYINGSLOTS));
    vs_version=input_word(raw,V(VERSION));version=input_word(raw,I(VERSION));
    index_format=input_word(raw,I(INDEXFORMAT));mode=input_word(raw,G(INDIRECT_MODE));
    work=mode!=0 || (input_word(raw,I(COUNT)) && input_word(raw,I(INSTANCES)));
    if(attributes>16 || varyings>16 || (vs_version!=1 && vs_version!=2) ||
        (version!=1 && version!=2) || index_format>3 || mode>2 || input_word(raw,I(RESTART))>1 ||
        (input_word(raw,I(RESTART)) && !index_format) || (mode==1 && index_format) || (mode==2 && !index_format))return -EINVAL;
    /* Every address and alignment hole in the owned input binding region is
     * zero; only the generated stride/divisor words are caller metadata. */
    for(u32 word=I(BINDINGS0_ALLOCATIONBASE);word<=I(BINDINGS15_STRIDE);word++) {
        bool metadata=false;
        for(u32 b=0;b<16;b++)if(word==binding_words[b].stride || word==binding_words[b].divisor)metadata=true;
        if(!metadata && input_word(raw,word))return -EINVAL;
    }
    if(!zero_words(raw,I(INDICES_ALLOCATIONBASE),I(INDICES_LIMIT)+1) ||
        !zero_words(raw,V(SCRATCH_ALLOCATIONBASE),V(SCRATCH_LIMIT)+1) ||
        !zero_words(raw,G(INDIRECT_VIEW_BASE),G(INDIRECT_VIEW_ALLOCATIONLIMIT)+1))return -EINVAL;
    for(u32 a=0;a<16;a++) {
        const struct attribute_words *w=&attribute_words[a];
        u32 enabled=input_word(raw,w->enabled),binding=input_word(raw,w->binding),
            components=input_word(raw,w->components),format=input_word(raw,w->format);
        if(enabled>1 || binding>15 || components>7 || format>31)return -EINVAL;
        if(enabled) {
            const struct drm_opengpu_compute_uniform_view *v=&plan->bindings[binding];
            u32 size=element_size(format),stride=input_word(raw,binding_words[binding].stride);
            if(a>=attributes || !components || components>4 || format>(version==2?24u:6u) ||
                (format>=20 && format<=23 && components!=4) ||
                ((v->offset+input_word(raw,w->offset))&(size-1u)) || (stride&(size-1u)))return -EINVAL;
            used|=1u<<binding;
        }
    }
    if(!index_format && !input_empty(&plan->indices))return -EINVAL;
    if(!mode && !input_empty(&plan->indirect))return -EINVAL;
    if(!mode && work && !index_format && (u64)input_word(raw,I(FIRSTVERTEX))+input_word(raw,I(COUNT))>(1ULL<<32))return -EINVAL;
    views=kvzalloc(19u*sizeof(*views),GFP_KERNEL);if(!views)return -ENOMEM;
    for(u32 i=0;i<19;i++) {
        const struct drm_opengpu_compute_uniform_view *source=i<16?&plan->bindings[i]:
            i==16?&plan->indices:i==17?&plan->indirect:&plan->vertex_scratch;
        bool needed=i<16?!!(used&(1u<<i)):i==16?!!index_format:i==17?!!mode:true;
        if(!needed) {if(!input_empty(source))goto done;continue;}
        if(input_empty(source)) {if(work)goto done;continue;}
        struct input_view *v=&views[count++];
        v->source=source;v->words=i<16?&binding_words[i]:i==16?&index_words:i==17?&indirect_words:&scratch_words;
        v->writable=i==18;
        ret=resolve_input(v,resolve,context);if(ret)goto done;ret=-EINVAL;
        if(i==16) {
            u32 size=1u<<(index_format-1u);
            if(source->offset&(size-1u))goto done;
            if(!mode && work && ((u64)input_word(raw,I(FIRSTINDEX))+input_word(raw,I(COUNT)))*size>source->bytes)goto done;
        }
        if(i==17 && ((source->offset&3u) || source->bytes<(mode==1?16u:20u)))goto done;
        if(i==18 && (((source->offset|source->bytes)&63u) ||
            (u64)(7u+4u*attributes+4u*varyings+(vs_version==2))*capacity*4u>source->bytes))goto done;
    }
    for(u32 a=0;a<16;a++)if(input_word(raw,attribute_words[a].enabled) && !mode && work) {
        const struct attribute_words *w=&attribute_words[a];u32 b=input_word(raw,w->binding),format=input_word(raw,w->format);
        u32 divisor=input_word(raw,binding_words[b].divisor),stride=input_word(raw,binding_words[b].stride);
        u64 index;
        if(divisor)index=(u64)input_word(raw,I(BASEINSTANCE))+(input_word(raw,I(INSTANCES))-1u)/divisor;
        else if(index_format)continue; /* Actual index values are checked by hardware. */
        else index=(u64)input_word(raw,I(FIRSTVERTEX))+input_word(raw,I(COUNT))-1u;
        u32 bytes=(format>=20 && format<=23)?4u:element_size(format)*input_word(raw,w->components);
        if(index>0xffffffffu || index*stride+input_word(raw,w->offset)+bytes>plan->bindings[b].bytes)goto done;
    }
    for(u32 i=0;i<count;i++)for(u32 j=0;j<i;j++)
        if((views[i].writable || views[j].writable) && views[i].first_line<views[j].line_end &&
            views[j].first_line<views[i].line_end)goto done;
    for(u32 i=0;i<count;i++) {
        struct input_view *v=&views[i];const struct input_words *w=v->words;
        ret=opengpu_private_snapshot_resource(owner,(size_t)v->pages,&v->resource);if(ret)goto done;
        ret=opengpu_private_snapshot_resource_map(owner,v->resource,0,v->page,(size_t)v->pages,v->writable);if(ret)goto done;
        ret=input_relocate(owner,segment,offset,w->base,v,v->delta,false);
        if(!ret)ret=input_relocate(owner,segment,offset,w->limit,v,v->delta+v->source->bytes,true);
        if(!ret)ret=input_relocate(owner,segment,offset,w->allocation_base,v,v->first_line-v->page,false);
        if(!ret)ret=input_relocate(owner,segment,offset,w->allocation_limit,v,v->line_end-v->page,true);
        if(ret)goto done;
    }
    ret=0;
done:
    kvfree(views);return ret;
}
int opengpu_native_input_snapshot_patch(struct opengpu_private_snapshot *owner,
    u32 segment,u64 offset,const void *raw,const struct drm_opengpu_native_draw_plan *plan,u32 capacity,
    opengpu_native_frame_resolve resolve,void *context)
{
    return native_input_patch(owner,segment,offset,raw,plan,capacity,resolve,context,false);
}
int opengpu_native_input_snapshot_patch_geometry(struct opengpu_private_snapshot *owner,
    u32 segment,u64 offset,const void *raw,const struct drm_opengpu_native_draw_plan *plan,u32 capacity,
    opengpu_native_frame_resolve resolve,void *context)
{
    return native_input_patch(owner,segment,offset,raw,plan,capacity,resolve,context,true);
}
#undef PAGE
#undef I
#undef V
#undef G
