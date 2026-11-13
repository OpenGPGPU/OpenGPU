// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/string.h>
#include "opengpu_geometry_uniform_snapshot.h"
#include "opengpu_geometry_uniform_abi.h"
#include "opengpu_private_snapshot.h"

#define GS_ROOT_BYTES OPENGPU_GEOMETRY_UNIFORM_ROOT_BYTES
#define GS_BINDING_BYTES OPENGPU_GEOMETRY_UNIFORM_BINDING_BYTES
#define GS_TABLE_START GS_ROOT_BYTES
#define GS_TABLE_END (GS_TABLE_START + OPENGPU_GEOMETRY_UNIFORM_BINDINGS * GS_BINDING_BYTES)
#define GS_DEFAULT_START GS_TABLE_END
#define GS_DATA_START (GS_DEFAULT_START + GS_BINDING_BYTES)

struct geometry_uniform_copy {
    struct drm_opengpu_compute_uniform_view view;
    u32 offset;
};
static bool gs_empty_view(const struct drm_opengpu_compute_uniform_view *v)
{ return !v->offset && !v->handle && !v->bytes; }
static bool gs_valid_view(const struct drm_opengpu_compute_uniform_view *v,bool defaults)
{
    return v->handle && v->bytes && !(v->offset & 3) && !(v->bytes & 3) &&
        v->bytes <= (defaults ? OPENGPU_GEOMETRY_UNIFORM_DEFAULT_COMPONENTS * 4u :
                               OPENGPU_GEOMETRY_UNIFORM_BLOCK_BYTES) &&
        v->offset <= ~(u64)0 - v->bytes;
}
static void gs_word(u8 *data,u32 offset,u32 value)
{
    data[offset]=(u8)value;data[offset+1]=(u8)(value>>8);
    data[offset+2]=(u8)(value>>16);data[offset+3]=(u8)(value>>24);
}
static void gs_identity(u8 *data,u32 offset,u32 magic,u32 context,u32 pass,u32 generation)
{
    gs_word(data,offset,magic);gs_word(data,offset+4,64);
    gs_word(data,offset+8,OPENGPU_GEOMETRY_UNIFORM_VERSION);
    gs_word(data,offset+12,context);gs_word(data,offset+16,pass);gs_word(data,offset+20,generation);
}
static int gs_relocate(struct opengpu_private_snapshot *owner,u32 segment,u32 offset,u32 target,bool limit)
{
    return opengpu_private_snapshot_relocate(owner,segment,offset,segment,target,
        limit ? OPENGPU_PRIVATE_SNAPSHOT_LIMIT33 : OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
static int gs_binding_relocations(struct opengpu_private_snapshot *owner,u32 segment,
    u32 record,const struct geometry_uniform_copy *copy)
{
    int ret=gs_relocate(owner,segment,record+4*OPENGPU_GEOMETRY_UNIFORM_BINDING_BASE_WORD,copy->offset,false);
    if(!ret)ret=gs_relocate(owner,segment,record+4*OPENGPU_GEOMETRY_UNIFORM_BINDING_LIMIT_WORD,
        copy->offset+copy->view.bytes,true);
    if(!ret)ret=gs_relocate(owner,segment,record+4*OPENGPU_GEOMETRY_UNIFORM_BINDING_ALLOCATIONBASE_WORD,copy->offset,false);
    if(!ret)ret=gs_relocate(owner,segment,record+4*OPENGPU_GEOMETRY_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD,
        (copy->offset+copy->view.bytes+63u)&~63u,true);
    return ret;
}
int opengpu_geometry_uniform_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct drm_opengpu_native_uniform_stage *stage,u32 context,u32 pass,u32 generation,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,struct opengpu_geometry_uniform_snapshot *output)
{
    struct geometry_uniform_copy copies[OPENGPU_GEOMETRY_UNIFORM_BLOCKS_PER_STAGE]={0},defaults={0};
    struct opengpu_geometry_uniform_snapshot result={0};
    u32 mapping[OPENGPU_GEOMETRY_UNIFORM_BLOCKS_PER_STAGE]={0},count=0,bytes=GS_DATA_START;
    u8 *data;int ret=0;
    if(!owner || !stage || !output || !copy_source || stage->pad || (stage->active_mask&~0xfffu))return -EINVAL;
    for(u32 block=0;block<OPENGPU_GEOMETRY_UNIFORM_BLOCKS_PER_STAGE;++block){
        const struct drm_opengpu_compute_uniform_view *v=&stage->blocks[block];u32 slot;
        if(!(stage->active_mask&(1u<<block))){if(!gs_empty_view(v))return -EINVAL;continue;}
        if(!gs_valid_view(v,false))return -EINVAL;
        for(slot=0;slot<count;++slot)if(copies[slot].view.handle==v->handle &&
            copies[slot].view.offset==v->offset && copies[slot].view.bytes==v->bytes)break;
        if(slot==count){
            copies[slot].view=*v;copies[slot].offset=(bytes+255u)&~255u;
            bytes=copies[slot].offset+v->bytes;++count;
        }
        mapping[block]=slot;
    }
    if(!gs_empty_view(&stage->defaults)){
        if(!gs_valid_view(&stage->defaults,true))return -EINVAL;
        defaults.view=stage->defaults;defaults.offset=(bytes+255u)&~255u;bytes=defaults.offset+defaults.view.bytes;
    }
    result.present=stage->active_mask || defaults.view.bytes;
    if(!result.present){*output=result;return 0;}
    bytes=(bytes+63u)&~63u;
    data=kvzalloc(bytes,GFP_KERNEL);if(!data)return -ENOMEM;
    gs_identity(data,0,OPENGPU_GEOMETRY_UNIFORM_ROOT_MAGIC,context,pass,generation);
    gs_word(data,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_STAGE_WORD,OPENGPU_GEOMETRY_UNIFORM_STAGE);
    gs_word(data,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_ACTIVEMASK_WORD,stage->active_mask);
    u32 low=0,high=0;
    for(u32 block=0;block<OPENGPU_GEOMETRY_UNIFORM_BLOCKS_PER_STAGE;++block){
        if(block<6)low|=mapping[block]<<(5*block);else high|=mapping[block]<<(5*(block-6));
    }
    gs_word(data,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_MAPLOW_WORD,low);
    gs_word(data,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_MAPHIGH_WORD,high);
    gs_word(data,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_DEFAULTPRESENT_WORD,defaults.view.bytes!=0);
    for(u32 slot=0;slot<count+1 && !ret;++slot){
        bool is_default=slot==count;
        const struct geometry_uniform_copy *copy=is_default?&defaults:&copies[slot];
        u32 record=is_default?GS_DEFAULT_START:GS_TABLE_START+slot*GS_BINDING_BYTES;
        if(!copy->view.bytes)continue;
        gs_identity(data,record,OPENGPU_GEOMETRY_UNIFORM_BINDING_MAGIC,context,pass,generation);
        gs_word(data,record+4*OPENGPU_GEOMETRY_UNIFORM_BINDING_BINDINGINDEX_WORD,is_default?31u:slot);
        gs_word(data,record+4*OPENGPU_GEOMETRY_UNIFORM_BINDING_STAGEMASK_WORD,OPENGPU_GEOMETRY_UNIFORM_STAGE_MASK);
        gs_word(data,record+4*OPENGPU_GEOMETRY_UNIFORM_BINDING_READONLY_WORD,1);
        ret=copy_source(copy_context,&copy->view,data+copy->offset);
    }
    if(!ret)ret=opengpu_private_snapshot_append(owner,data,bytes,&result.segment);
    kvfree(data);if(ret)return ret;
    if(stage->active_mask){
        ret=gs_relocate(owner,result.segment,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_BINDINGSBASE_WORD,GS_TABLE_START,false);
        if(!ret)ret=gs_relocate(owner,result.segment,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_BINDINGSLIMIT_WORD,GS_TABLE_END,true);
    }
    if(!ret && defaults.view.bytes)
        ret=gs_relocate(owner,result.segment,4*OPENGPU_GEOMETRY_UNIFORM_ROOT_DEFAULTVIEW_WORD,GS_DEFAULT_START,false);
    for(u32 slot=0;slot<count && !ret;++slot)
        ret=gs_binding_relocations(owner,result.segment,GS_TABLE_START+slot*GS_BINDING_BYTES,&copies[slot]);
    if(!ret && defaults.view.bytes)ret=gs_binding_relocations(owner,result.segment,GS_DEFAULT_START,&defaults);
    if(!ret)*output=result;
    return ret;
}
