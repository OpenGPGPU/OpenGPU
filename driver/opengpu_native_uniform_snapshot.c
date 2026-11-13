// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/string.h>
#include "opengpu_native_uniform_snapshot.h"
#include "opengpu_private_snapshot.h"
#include "opengpu_uniform_abi.h"

#define ROOT_BYTES OPENGPU_UNIFORM_ROOT_BYTES
#define BINDING_BYTES OPENGPU_UNIFORM_BINDING_BYTES
#define TABLE_START (2u * ROOT_BYTES)
#define TABLE_END (TABLE_START + OPENGPU_UNIFORM_BINDINGS * BINDING_BYTES)
#define DEFAULT_START TABLE_END
#define DATA_START (DEFAULT_START + 2u * BINDING_BYTES)

struct uniform_copy {
    struct drm_opengpu_compute_uniform_view view;
    u32 offset, stage_mask;
};
static bool empty_view(const struct drm_opengpu_compute_uniform_view *view)
{ return !view->offset && !view->handle && !view->bytes; }
static bool valid_source(const struct drm_opengpu_compute_uniform_view *view,bool defaults)
{
    return view->handle && view->bytes && !(view->offset & 3) && !(view->bytes & 3) &&
        view->bytes <= (defaults ? 4096u : OPENGPU_UNIFORM_BLOCK_BYTES) &&
        view->offset <= ~(u64)0 - view->bytes;
}
static void word(u8 *data,u32 offset,u32 value)
{
    data[offset]=(u8)value;data[offset+1]=(u8)(value>>8);
    data[offset+2]=(u8)(value>>16);data[offset+3]=(u8)(value>>24);
}
static void identity(u8 *data,u32 offset,u32 magic,u32 context,u32 pass,u32 generation)
{
    word(data,offset,magic);word(data,offset+4,64);word(data,offset+8,1);
    word(data,offset+12,context);word(data,offset+16,pass);word(data,offset+20,generation);
}
static int relocate(struct opengpu_private_snapshot *owner,u32 segment,u32 offset,
    u32 target,bool limit)
{
    return opengpu_private_snapshot_relocate(owner,segment,offset,segment,target,
        limit ? OPENGPU_PRIVATE_SNAPSHOT_LIMIT33 : OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
static int binding_relocations(struct opengpu_private_snapshot *owner,u32 segment,
    u32 record,const struct uniform_copy *copy)
{
    int ret=relocate(owner,segment,record+4*OPENGPU_UNIFORM_BINDING_BASE_WORD,copy->offset,false);
    if(!ret)ret=relocate(owner,segment,record+4*OPENGPU_UNIFORM_BINDING_LIMIT_WORD,copy->offset+copy->view.bytes,true);
    if(!ret)ret=relocate(owner,segment,record+4*OPENGPU_UNIFORM_BINDING_ALLOCATIONBASE_WORD,copy->offset,false);
    if(!ret)ret=relocate(owner,segment,record+4*OPENGPU_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD,
                         (copy->offset+copy->view.bytes+63u)&~63u,true);
    return ret;
}
int opengpu_native_uniform_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_uniform_stage stages[2],u32 context,u32 pass,u32 generation,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_native_uniform_roots *output)
{
    struct uniform_copy copies[OPENGPU_UNIFORM_BINDINGS]={0},defaults[2]={0};
    struct opengpu_native_uniform_roots result={0};
    u32 mapping[2][OPENGPU_UNIFORM_BLOCKS_PER_STAGE]={{0}},count=0,bytes=DATA_START;
    u8 *data;
    int ret=0;
    if(!owner || !stages || !output || !copy_source)return -EINVAL;
    for(u32 stage=0;stage<2;++stage){
        const struct drm_opengpu_native_uniform_stage *s=&stages[stage];
        if(s->pad || (s->active_mask&~0xfffu))return -EINVAL;
        for(u32 block=0;block<OPENGPU_UNIFORM_BLOCKS_PER_STAGE;++block){
            const struct drm_opengpu_compute_uniform_view *v=&s->blocks[block];
            u32 slot;
            if(!(s->active_mask&(1u<<block))){if(!empty_view(v))return -EINVAL;continue;}
            if(!valid_source(v,false))return -EINVAL;
            for(slot=0;slot<count;++slot)if(copies[slot].view.handle==v->handle &&
                copies[slot].view.offset==v->offset && copies[slot].view.bytes==v->bytes)break;
            if(slot==count){
                if(count>=OPENGPU_UNIFORM_BINDINGS)return -EINVAL;
                copies[slot].view=*v;copies[slot].offset=(bytes+255u)&~255u;
                bytes=copies[slot].offset+v->bytes;++count;
            }
            copies[slot].stage_mask|=1u<<stage;mapping[stage][block]=slot;
        }
        if(!empty_view(&s->defaults)){
            if(!valid_source(&s->defaults,true))return -EINVAL;
            defaults[stage].view=s->defaults;defaults[stage].offset=(bytes+255u)&~255u;
            defaults[stage].stage_mask=1u<<stage;bytes=defaults[stage].offset+s->defaults.bytes;
        }
        result.present[stage]=s->active_mask || defaults[stage].view.bytes;
        result.root_offset[stage]=stage*ROOT_BYTES;
    }
    if(!result.present[0] && !result.present[1]){*output=result;return 0;}
    bytes=(bytes+63u)&~63u;
    data=kzalloc(bytes,GFP_KERNEL);if(!data)return -ENOMEM;
    for(u32 stage=0;stage<2 && !ret;++stage){
        u32 base=stage*ROOT_BYTES,low=0,high=0;
        identity(data,base,OPENGPU_UNIFORM_ROOT_MAGIC,context,pass,generation);
        word(data,base+4*OPENGPU_UNIFORM_ROOT_STAGE_WORD,stage);
        word(data,base+4*OPENGPU_UNIFORM_ROOT_ACTIVEMASK_WORD,stages[stage].active_mask);
        for(u32 block=0;block<OPENGPU_UNIFORM_BLOCKS_PER_STAGE;++block){
            if(block<6)low|=mapping[stage][block]<<(5*block);
            else high|=mapping[stage][block]<<(5*(block-6));
        }
        word(data,base+4*OPENGPU_UNIFORM_ROOT_MAPLOW_WORD,low);
        word(data,base+4*OPENGPU_UNIFORM_ROOT_MAPHIGH_WORD,high);
        word(data,base+4*OPENGPU_UNIFORM_ROOT_DEFAULTPRESENT_WORD,defaults[stage].view.bytes!=0);
    }
    for(u32 slot=0;slot<count+2u && !ret;++slot){
        const bool is_default=slot>=count;
        const struct uniform_copy *copy=is_default ? &defaults[slot-count] : &copies[slot];
        u32 record=is_default ? DEFAULT_START+(slot-count)*BINDING_BYTES : TABLE_START+slot*BINDING_BYTES;
        if(!copy->view.bytes)continue;
        identity(data,record,OPENGPU_UNIFORM_BINDING_MAGIC,context,pass,generation);
        word(data,record+4*OPENGPU_UNIFORM_BINDING_BINDINGINDEX_WORD,is_default?31:slot);
        word(data,record+4*OPENGPU_UNIFORM_BINDING_STAGEMASK_WORD,copy->stage_mask);
        word(data,record+4*OPENGPU_UNIFORM_BINDING_READONLY_WORD,1);
        ret=copy_source(copy_context,&copy->view,data+copy->offset);
    }
    if(!ret)ret=opengpu_private_snapshot_append(owner,data,bytes,&result.segment);
    kfree(data);if(ret)return ret;
    for(u32 stage=0;stage<2 && !ret;++stage){
        u32 base=stage*ROOT_BYTES;
        if(stages[stage].active_mask){
            ret=relocate(owner,result.segment,base+4*OPENGPU_UNIFORM_ROOT_BINDINGSBASE_WORD,TABLE_START,false);
            if(!ret)ret=relocate(owner,result.segment,base+4*OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD,TABLE_END,true);
        }
        if(!ret && defaults[stage].view.bytes)
            ret=relocate(owner,result.segment,base+4*OPENGPU_UNIFORM_ROOT_DEFAULTVIEW_WORD,DEFAULT_START+stage*BINDING_BYTES,false);
    }
    for(u32 slot=0;slot<count && !ret;++slot)
        ret=binding_relocations(owner,result.segment,TABLE_START+slot*BINDING_BYTES,&copies[slot]);
    for(u32 stage=0;stage<2 && !ret;++stage)if(defaults[stage].view.bytes)
        ret=binding_relocations(owner,result.segment,DEFAULT_START+stage*BINDING_BYTES,&defaults[stage]);
    if(!ret)*output=result;
    return ret;
}
