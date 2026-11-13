// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/string.h>
#include "opengpu_geometry_snapshot.h"
#include "opengpu_geometry_program_snapshot.h"
#include "opengpu_geometry_uniform_snapshot.h"
#include "opengpu_native_geometry_abi.h"
#include "opengpu_private_snapshot.h"
#define GNG(n) OPENGPU_NATIVE_GEOMETRY_PROGRAM_##n##_WORD
static u32 gng_get(const void *raw,u32 word)
{ return opengpu_native_geometry_word_at(raw,word); }
static void gng_put(void *raw,u32 word,u32 value)
{
    u8 *p=(u8 *)raw+4u*word;
    p[0]=(u8)value;p[1]=(u8)(value>>8);p[2]=(u8)(value>>16);p[3]=(u8)(value>>24);
}
static bool gng_topology(u32 vertices,u32 topology)
{
    return (vertices==1 && topology==0) || (vertices==2 && topology>=1 && topology<=3) ||
        (vertices==3 && topology>=4 && topology<=6) || (vertices==4 && (topology==10 || topology==11)) ||
        (vertices==6 && (topology==12 || topology==13));
}
static bool gng_metadata_valid(const struct opengpu_geometry_source *s)
{
    const void *p=s->metadata;
    u32 invocations,vertices,input,output,builtins,vertex_slots,fragment_slots,total;
    u64 flat;
    if(!opengpu_native_geometry_program_encoding_valid(p))return false;
    if(gng_get(p,GNG(IDENTITY_CONTEXT))!=s->context || gng_get(p,GNG(IDENTITY_PASS))!=s->pass ||
        gng_get(p,GNG(IDENTITY_GENERATION))!=s->generation)return false;
    for(u32 word=GNG(PC);word<=GNG(UNIFORMS_ADDRESS);word++)if(gng_get(p,word))return false;
    invocations=gng_get(p,GNG(INVOCATIONS));vertices=gng_get(p,GNG(MAXVERTICES));
    input=gng_get(p,GNG(INPUTCOMPONENTS));output=gng_get(p,GNG(OUTPUTCOMPONENTS));
    builtins=gng_get(p,GNG(BUILTINOUTPUTMASK));
    total=output+(builtins&1u)+((builtins>>1)&1u);
    vertex_slots=gng_get(p,GNG(VERTEXVARYINGSLOTS));fragment_slots=gng_get(p,GNG(FRAGMENTVARYINGSLOTS));
    if(!invocations || invocations>32 || !vertices || vertices>256 || input>64 || total>64 ||
        vertices*total>1024 || gng_get(p,GNG(OUTPUTTOPOLOGY))>2 ||
        gng_get(p,GNG(PROGRAMVERSION))!=1 || vertex_slots>16 || fragment_slots>16 ||
        vertex_slots!=s->vertex_varyings || fragment_slots!=s->fragment_varyings ||
        !gng_topology(gng_get(p,GNG(INPUTVERTICES)),s->topology))return false;
    flat=gng_get(p,GNG(FLATMASK))|(u64)gng_get(p,GNG(FLATMASK)+1)<<32;
    if(fragment_slots<16 && (flat>>(fragment_slots*4u)))return false;
    for(u32 i=0;i<64;i++){
        u32 source=gng_get(p,GNG(INPUTSOURCES0)+i);
        if(i<input ? source>=4u+vertex_slots*4u : source!=0)return false;
    }
    for(u32 i=0;i<68;i++){
        u32 valid=gng_get(p,GNG(OUTPUTSOURCES0_VALID)+2*i),source=gng_get(p,GNG(OUTPUTSOURCES0_SOURCE)+2*i);
        if(valid ? (i>=4u+fragment_slots*4u || source>=output || source>=64) : source!=0)return false;
    }
    return true;
}
static int gng_relocate(struct opengpu_private_snapshot *owner,u32 from,u32 word,u32 target,u32 addend,bool limit)
{
    return opengpu_private_snapshot_relocate(owner,from,word*4u,target,addend,
        limit?OPENGPU_PRIVATE_SNAPSHOT_LIMIT33:OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
int opengpu_geometry_snapshot_append(struct opengpu_private_snapshot *owner,
    const struct opengpu_geometry_source *s,
    int (*copy_source)(void *,const struct drm_opengpu_compute_uniform_view *,void *),
    void *copy_context,u32 *output)
{
    struct opengpu_geometry_uniform_snapshot uniforms;
    struct opengpu_native_program_snapshot code;
    struct opengpu_geometry_program_profile profile;
    struct opengpu_geometry_source captured;
    struct drm_opengpu_native_program_view program_view;
    struct drm_opengpu_native_uniform_stage uniform_stage;
    u8 metadata[1024];u32 segment;int ret;
    if(!owner || !s || !output || !copy_source || !s->geometry_enabled || !s->metadata ||
        s->metadata_bytes!=sizeof(metadata) || !s->program || !s->uniforms)return -EINVAL;
    captured=*s;memcpy(metadata,s->metadata,sizeof(metadata));
    program_view=*s->program;uniform_stage=*s->uniforms;
    captured.metadata=metadata;captured.program=&program_view;captured.uniforms=&uniform_stage;s=&captured;
    if(!gng_metadata_valid(s))return -EINVAL;
    ret=opengpu_geometry_uniform_snapshot_append(owner,s->uniforms,s->context,s->pass,s->generation,
        copy_source,copy_context,&uniforms);
    if(ret)return ret;
    profile=(struct opengpu_geometry_program_profile){.version=1,.geometry_enabled=true,
        .scalar_fpu=s->scalar_fpu,.owned_uniforms=uniforms.present};
    ret=opengpu_geometry_program_snapshot_append(owner,s->program,&profile,copy_source,copy_context,&code);
    if(ret)return ret;
    gng_put(metadata,GNG(UNIFORMS_VERSION),uniforms.present?2u:0u);
    ret=opengpu_private_snapshot_append(owner,metadata,sizeof(metadata),&segment);
    if(!ret)ret=gng_relocate(owner,segment,GNG(PC),code.segment,code.entry_offset,false);
    if(!ret)ret=gng_relocate(owner,segment,GNG(CODE_BASE),code.segment,0,false);
    if(!ret)ret=gng_relocate(owner,segment,GNG(CODE_LIMIT),code.segment,s->program->bytes,true);
    if(!ret)ret=gng_relocate(owner,segment,GNG(CODE_ALLOCATIONBASE),code.segment,0,false);
    if(!ret)ret=gng_relocate(owner,segment,GNG(CODE_ALLOCATIONLIMIT),code.segment,(s->program->bytes+63u)&~63u,true);
    if(!ret && uniforms.present)ret=gng_relocate(owner,segment,GNG(UNIFORMS_ADDRESS),uniforms.segment,uniforms.root_offset,false);
    if(!ret)*output=segment;
    return ret;
}
#undef GNG
