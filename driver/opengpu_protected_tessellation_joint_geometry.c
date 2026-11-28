// SPDX-License-Identifier: GPL-2.0
/* Independent GNQ7..9/v3 -> GGA1/GNG1 and directory/GNT7..9/GVS1/GND1 owner BUILD.
 * No discovery, queue, ioctl or address getter is enabled here. */
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include <linux/string.h>
#include "opengpu_protected_tessellation_joint_geometry.h"
#include "opengpu_protected_tessellation_joint_abi.h"
#include "opengpu_tessellation_vertex_source_abi.h"
#include "opengpu_tessellation_job_validate.h"
#include "opengpu_tessellation_uniform_sequence_abi.h"
#include "opengpu_tessellation_uniform_snapshot.h"
#include "opengpu_native_uniform_snapshot.h"
#include "opengpu_native_advanced_blend_gfs.h"
#include "opengpu_typed_fragment_state_abi.h"
#include "opengpu_compute_texture_format_info.h"
#include "opengpu_native_binned_abi.h"
#include "opengpu_private_snapshot.h"
#include "opengpu_native_geometry_abi.h"
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_BYTES==512u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_VERSION==3u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_ISOLINE_MAGIC==0x474e5107u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_TRIANGLE_MAGIC==0x474e5108u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_QUAD_MAGIC==0x474e5109u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_MAGIC==0x47474101u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_VERSION==1u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_BYTES==64u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_BYTES==1024u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_VERSION_WORD==36u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_BASE_WORD==37u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_LIMIT_WORD==38u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_LIMIT_BITS==33u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_COUNT_WORD==40u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_ENTRY_BYTES_WORD==41u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_GEOMETRY_BYTES_WORD==42u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_RESERVED_ZERO_WORD==43u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ROOT_RESERVED_ZERO_COUNT==85u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_MAGIC_WORD==0u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_BYTES_WORD==1u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_VERSION_WORD==2u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_ENTRY_BYTES_WORD==3u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_COUNT_WORD==4u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_CONTEXT_WORD==5u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_PASS_WORD==6u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_GENERATION_WORD==7u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_RESERVED_ZERO_WORD==8u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_HEADER_RESERVED_ZERO_COUNT==8u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_SEQUENCE_INDEX_WORD==0u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_STATE_HANDLE_WORD==1u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_DOMAIN_WORD==2u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_FLAGS_WORD==3u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_CONTEXT_WORD==4u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_PASS_WORD==5u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_GENERATION_WORD==6u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_COMMAND_ID_WORD==7u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_GS_TASK_ID_BASE_WORD==8u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_FIRST_INPUT_PRIMITIVE_ID_WORD==9u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_FIRST_OUTPUT_SEQUENCE_WORD==10u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_FIRST_OUTPUT_SEQUENCE_BITS==64u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_GNG_BASE_WORD==12u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_GNG_LIMIT_WORD==13u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_GNG_LIMIT_BITS==33u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_GNG_BYTES_WORD==15u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_EVALUATION_COMPONENTS_WORD==16u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_EVALUATION_COMPONENTS_BITS==8u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_EVALUATION_COMPONENTS_COUNT==64u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_FEEDBACK_RESERVED_ZERO_WORD==80u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_FEEDBACK_RESERVED_ZERO_COUNT==100u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_RASTER_DISCARD_WORD==180u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_RESERVED_ZERO0_WORD==181u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_RESERVED_ZERO0_COUNT==43u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_RESERVED_ZERO1_WORD==226u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_RESERVED_ZERO1_COUNT==30u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_INPUT_SEQUENCE_WORD==224u,"review joint geometry wire implementation on schema change");
_Static_assert(OPENGPU_JOINT_GEOMETRY_ENTRY_INPUT_SEQUENCE_BITS==64u,"review joint geometry wire implementation on schema change");
#define JF(n) OPENGPU_TYPED_FRAGMENT_STATE_##n##_WORD
#define JW(n) OPENGPU_NATIVE_BINNED_FRAME_FRAME_##n##_WORD
struct jg_layout {
    const unsigned char *rules;
    u32 magic,version,state,bases,control_grid,control_helpers,control_private,control_arg,control_pc,control_local;
    u32 input_components,input_vertices,output_components,output_vertices,patch_components,task_id,spacing;
    u32 evaluation_grid,evaluation_helpers,evaluation_private,evaluation_arg,evaluation_pc,evaluation_local;
    u32 tes_outputs,selections,selection_first,vertex_stride,old_uniform,varyings,viewport;
};
#define JT_LAYOUT(P,R,M,V) { .rules=R,.magic=M,.version=V, \
 .state=P##_TEMPLATE_SOURCE_STATE_WORD,.bases=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_BASES0_WORD, \
 .control_grid=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_GRIDSIZE0_WORD, \
 .control_helpers=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_INVOCATIONS_HELPERS_WORD, \
 .control_private=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_INVOCATIONS_PRIVATEBASE_WORD, \
 .control_arg=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_KERNARGADDRESS_WORD, \
 .control_pc=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_KERNELPC_WORD, \
 .control_local=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_LOCALSIZE0_WORD, \
 .input_components=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD, \
 .input_vertices=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD, \
 .output_components=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_OUTPUTCOMPONENTS_WORD, \
 .output_vertices=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_OUTPUTVERTICES_WORD, \
 .patch_components=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_PATCHCOMPONENTS_WORD, \
 .task_id=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_TASKID_WORD, \
 .spacing=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_SPACING_WORD, \
 .evaluation_grid=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_GRIDSIZE0_WORD, \
 .evaluation_helpers=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_INVOCATIONS_HELPERS_WORD, \
 .evaluation_private=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_INVOCATIONS_PRIVATEBASE_WORD, \
 .evaluation_arg=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_KERNARGADDRESS_WORD, \
 .evaluation_pc=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_KERNELPC_WORD, \
 .evaluation_local=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_LOCALSIZE0_WORD, \
 .tes_outputs=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_OUTPUTCOMPONENTS_WORD, \
 .selections=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_PATCHINPUTCOUNT_WORD, \
 .selection_first=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_PATCHINPUTS0_COMPONENT_WORD, \
 .vertex_stride=P##_TEMPLATE_STRIDE_WORD,.old_uniform=P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_UNIFORMRESOURCES_WORD, \
 .varyings=P##_TEMPLATE_SOURCE_VIEWPORT_VARYINGSLOTS_WORD,.viewport=P##_TEMPLATE_SOURCE_VIEWPORT_VIEWPORT_EXTENTX_WORD }
static const struct jg_layout jg_layouts[]={
 JT_LAYOUT(OPENGPU_NATIVE_TESSELLATION_SEQUENCE,opengpu_tessellation_isoline_word_rules,0x474e5407u,7),
 JT_LAYOUT(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE,opengpu_tessellation_triangle_word_rules,0x474e5408u,8),
 JT_LAYOUT(OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE,opengpu_tessellation_quad_word_rules,0x474e5409u,9),
};
#undef JT_LAYOUT
static u32 jg_get(const void *raw,u32 w)
{
    const u8 *p=(const u8 *)raw+(size_t)w*4;
    return (u32)p[0]|(u32)p[1]<<8|(u32)p[2]<<16|(u32)p[3]<<24;
}
static void jg_put(void *raw,u32 w,u32 v)
{
    u8 *p=(u8 *)raw+(size_t)w*4;
    for(u32 i=0;i<4;i++)p[i]=(u8)(v>>(8*i));
}
static u64 jg_wide(const void *raw,u32 w)
{return jg_get(raw,w)|((u64)jg_get(raw,w+1)<<32);}
static bool jg_zero(const void *raw,size_t bytes)
{
    const u8 *p=raw;
    for(size_t i=0;i<bytes;i++)if(p[i])return false;
    return true;
}
bool opengpu_protected_tessellation_joint_geometry_profile_valid(
    const struct opengpu_protected_tessellation_joint_geometry_profile *p)
{
    static const char provider[48]="two-cu-vga60-owner-v3-five-stage";
    return p && p->available && p->version==1 && !memcmp(p->provider,provider,sizeof(provider)) &&
        p->owner_resource_version==3 && p->payload_bytes==524288 && p->max_payload_bytes==69632 &&
        p->payload_owners==5 && p->payload_ports==12 && p->image_producer_pins==34 && p->single_domain_total_pins==37 && p->domains==7 && p->compute_units==2 &&
        p->lanes==16 && p->warps==8 && p->engines==4 &&
        p->owned_vertex_source && p->patch_zero_initialized && p->owned_patch_reads &&
        p->owned_tessellation_info && p->geometry_inputs && p->geometry_route && p->geometry_uniforms &&
        p->joint_directory_route && p->whole_owner_retirement && p->scalar_fpu &&
        !p->advanced_blend && !p->sample_inputs;
}
static bool jg_view(u32 base,u64 limit,u64 bytes)
{return !(base&63u) && limit==(u64)base+bytes && limit<=(1ULL<<32);}
bool opengpu_protected_tessellation_joint_geometry_job_canonical(const void *raw,size_t bytes,bool bound)
{
    u32 n,stride;
    u64 extent,directory;
    if(!raw || bytes!=512 || jg_get(raw,0)<0x474e5107u || jg_get(raw,0)>0x474e5109u)return false;
    const u32 header[]={512,3,16,16,16,4,4};
    for(u32 i=0;i<7;i++)if(jg_get(raw,i+1)!=header[i])return false;
    unsigned char rules[128];memcpy(rules,opengpu_tess_joint_word_rules,128);
    const unsigned char geometry_rules[]={32,32,32,1,32,32,32};memcpy(rules+36,geometry_rules,7);
    if(!opengpu_tessellation_canonical_words_n(raw,bytes,rules,128))return false;
    n=jg_get(raw,17);stride=jg_get(raw,20);
    if(!n) {
        if(!jg_zero((const u8 *)raw+36*4,7*4))return false;
    }else {
        if(jg_get(raw,36)!=1 || jg_get(raw,40)!=n || jg_get(raw,41)!=1024 ||
            (u64)jg_get(raw,42)!=64+(u64)n*1024)return false;
        if(bound?!jg_view(jg_get(raw,37),jg_wide(raw,38),jg_get(raw,42)):
            (jg_get(raw,37) || jg_wide(raw,38)))return false;
    }
    if(bound) {
        if(!jg_view(jg_get(raw,21),jg_wide(raw,22),2048))return false;
    }else if(jg_get(raw,21) || jg_wide(raw,22))return false;
    if(!n)return !jg_get(raw,16) && !jg_wide(raw,18) && !stride && jg_zero((const u8 *)raw+27*4,9*4);
    extent=(u64)(n-1)*stride+2048;
    directory=64+(u64)n*64;
    if(stride<2048 || (stride&63u) || extent>(1ULL<<32) || directory>0xffffffffu ||
        jg_get(raw,27)!=2 || jg_get(raw,31)!=n || jg_get(raw,32)!=directory || jg_get(raw,33)!=64 ||
        !jg_get(raw,34) || !jg_get(raw,35) || jg_get(raw,35)>n)return false;
    return bound?(jg_view(jg_get(raw,16),jg_wide(raw,18),extent) &&
        jg_view(jg_get(raw,28),jg_wide(raw,29),directory)):
        (!jg_get(raw,16) && !jg_wide(raw,18) && !jg_get(raw,28) && !jg_wide(raw,29));
}

/* Every nested array, callback and permission is captured before callbacks. */
struct jg_capture {
    struct opengpu_protected_tessellation_joint_geometry_source s;
    struct opengpu_native_job_copy copy;
    void *owned[12];
    u32 *geometry_index,*geometry_draw_first,*geometry_invocation_first;
    u32 *plan_index,*state_index,*draw_index,*range_index;
};
static void jg_free(struct jg_capture *c)
{
    for(u32 i=0;i<12;i++)kvfree(c->owned[i]);
    kvfree(c->geometry_index);kvfree(c->geometry_draw_first);kvfree(c->geometry_invocation_first);
    kvfree(c->plan_index);kvfree(c->state_index);kvfree(c->draw_index);kvfree(c->range_index);
}
static int jg_capture(struct jg_capture *c)
{
    struct opengpu_protected_tessellation_joint_geometry_source *s=&c->s;
    if(s->sequences_bytes>(1ULL<<32) || s->states_bytes>(1ULL<<32) || s->draws_bytes>(1ULL<<32) ||
        (u64)s->draw_count*2048!=s->draws_bytes || (u64)s->state_plan_count*512!=s->states_bytes ||
        s->draw_count!=s->draw_plan_count || s->range_count!=s->plan_count ||
        s->invocation_count>s->range_count || s->geometry_plan_count!=s->range_count || !s->resource_count || s->resource_count>27)return -EINVAL;
    const u64 sizes[]={512,2048,s->sequences_bytes,s->states_bytes,s->draws_bytes,
        (u64)s->draw_plan_count*sizeof(*s->draw_plans),(u64)s->invocation_count*sizeof(*s->invocations),
        (u64)s->range_count*sizeof(*s->ranges),(u64)s->plan_count*sizeof(*s->plans),
        (u64)s->state_plan_count*sizeof(*s->state_plans),(u64)s->resource_count*sizeof(*s->resources),
        (u64)s->geometry_plan_count*sizeof(*s->geometry_plans)};
    const void *inputs[]={s->job,s->frame,s->sequences,s->states,s->draws,s->draw_plans,
        s->invocations,s->ranges,s->plans,s->state_plans,s->resources,s->geometry_plans};
    for(u32 i=0;i<12;i++) {
        if(sizes[i]>(u64)~(size_t)0 || sizes[i]>(1ULL<<32))return -ERANGE;
        if(sizes[i]) {
            if(!inputs[i])return -EINVAL;
            c->owned[i]=kvzalloc((size_t)sizes[i],GFP_KERNEL);
            if(!c->owned[i])return -ENOMEM;
            memcpy(c->owned[i],inputs[i],(size_t)sizes[i]);
        }else if(inputs[i])return -EINVAL;
    }
    s->job=c->owned[0];s->frame=c->owned[1];s->sequences=c->owned[2];s->states=c->owned[3];
    s->draws=c->owned[4];s->draw_plans=c->owned[5];s->invocations=c->owned[6];s->ranges=c->owned[7];
    s->plans=c->owned[8];s->state_plans=c->owned[9];s->resources=c->owned[10];s->geometry_plans=c->owned[11];
    return 0;
}
static struct opengpu_tessellation_vertex_outer_source_v2 jg_outer(const struct jg_capture *c)
{
    const struct opengpu_protected_tessellation_joint_geometry_source *s=&c->s;
    return (struct opengpu_tessellation_vertex_outer_source_v2){.version=2,.context=s->context,.pass=s->pass,
        .generation=s->generation,.draws=s->draws,.draws_bytes=s->draws_bytes,.plans=s->draw_plans,
        .draw_count=s->draw_count,.plan_count=s->draw_plan_count,.invocations=s->invocations,.ranges=s->ranges,
        .invocation_count=s->invocation_count,.range_count=s->range_count,
        .profile={1,2,16,8,true,s->profile.scalar_fpu,true}};
}
static int jg_indices(u32 count,u32 **output)
{
    if((u64)count*sizeof(**output)>(u64)~(size_t)0)return -ERANGE;
    if(!count)return 0;
    *output=kvzalloc((size_t)count*sizeof(**output),GFP_KERNEL);
    return *output?0:-ENOMEM;
}
static void jg_profiles(const void *d,const struct jg_layout *l,const struct jg_capture *c,
    struct opengpu_tessellation_program_profile *tc,struct opengpu_tessellation_program_profile *te)
{
    *tc=(struct opengpu_tessellation_program_profile){.version=3,.stage=4,.lanes=16,.warps=8,
        .input_vertices=jg_get(d,l->input_vertices),.input_components=jg_get(d,l->input_components),
        .output_vertices=jg_get(d,l->output_vertices),.output_components=jg_get(d,l->output_components),
        .patch_components=jg_get(d,l->patch_components),.available=true,.scalar_fpu=c->s.profile.scalar_fpu,
        .owned_tessellation_info=true};
    *te=(struct opengpu_tessellation_program_profile){.version=3,.stage=5,.lanes=16,.warps=8,
        .output_components=jg_get(d,l->tes_outputs),.available=true,.scalar_fpu=c->s.profile.scalar_fpu,
        .owned_patch_reads=true,.owned_tessellation_info=true,
        .tes_patch_layout={tc->input_vertices,tc->input_components,tc->output_vertices,tc->output_components,tc->patch_components}};
}
static bool jg_nan(u32 n)
{return (n&0x7f800000u)==0x7f800000u && (n&0x7fffffu);}
static int jg_sequence(struct jg_capture *c,u32 index,u32 domain)
{
    const struct opengpu_protected_tessellation_joint_geometry_source *s=&c->s;
    const struct jg_layout *l=&jg_layouts[domain];
    const u8 *d=(const u8 *)s->sequences+(size_t)index*jg_get(s->job,20);
    const struct opengpu_protected_tessellation_joint_geometry_plan *p=&s->plans[c->plan_index[index]-1];
    const struct opengpu_tessellation_vertex_outer_range_v2 *r=&s->ranges[c->range_index[index]-1];
    const struct drm_opengpu_native_draw_plan *dp=&s->draw_plans[c->draw_index[r->original_draw_index]-1];
    struct opengpu_tessellation_program_profile tc,te;
    if(!opengpu_tessellation_vertex_sequence_source_canonical(d,2048,domain) ||
        !jg_zero(d+496*4,13*4) || jg_get(d,184)!=s->context || jg_get(d,185)!=s->generation || jg_get(d,186)!=s->pass ||
        jg_get(d,191) || jg_get(d,190) || jg_get(d,l->spacing)>2 || jg_get(d,l->state)!=p->state_handle ||
        p->state_handle!=dp->state_handle || jg_get(d,16)!=r->patch_count || jg_get(d,l->task_id)!=r->task_id_base)return -EINVAL;
    jg_profiles(d,l,c,&tc,&te);
    if(!opengpu_tessellation_program_profile_valid(&tc) || !opengpu_tessellation_program_profile_valid(&te))return -EINVAL;
    const struct opengpu_tessellation_patch_layout layout={tc.input_vertices,tc.input_components,tc.output_vertices,tc.output_components,tc.patch_components};
    if(memcmp(&layout,&r->layout,sizeof(layout)))return -EINVAL;
    for(u32 i=0;i<8;i++) {
        u32 base=i*16<tc.output_vertices?i*16:0;
        if(jg_get(d,l->bases+i)!=base)return -EINVAL;
    }
    for(u32 i=0;i<3;i++)if(jg_get(d,l->control_grid+i)!=1 || jg_get(d,l->evaluation_grid+i)!=1 ||
        jg_get(d,l->control_local+i)!=(i?1:tc.output_vertices) || jg_get(d,l->evaluation_local+i)!=1)return -EINVAL;
    if(!jg_zero(d+l->control_helpers*4,16) || !jg_zero(d+l->evaluation_helpers*4,16) ||
        !jg_zero(d+l->control_private*4,8) || !jg_zero(d+l->evaluation_private*4,8) ||
        jg_get(d,l->control_arg) || jg_get(d,l->evaluation_arg) || jg_get(d,l->control_pc) || jg_get(d,l->evaluation_pc))return -EINVAL;
    u32 varyings=jg_get(d,l->varyings),exports=te.output_components;
    if(varyings>16 || (varyings<16 && (jg_wide(d,182)>>(varyings*4))))return -EINVAL;
    const u8 *state=(const u8 *)s->states+(size_t)p->state_handle*512;
    if(varyings!=jg_get(state,JF(FRAGMENT_VARYINGSLOTS)))return -EINVAL;
    if(jg_get(d,31)!=jg_get(s->frame,JW(IMAGE_PIXELS_COLORS_SAMPLEMODE)))return -EINVAL;
    for(u32 i=0;i<68;i++)if(jg_get(d,44+2*i) && jg_get(d,43+2*i)>=exports)return -EINVAL;
    if(jg_get(d,189) && jg_get(d,188)>=exports)return -EINVAL;
    u32 viewport=l->viewport,raster=jg_get(d,39),factor=jg_get(d,29),units=jg_get(d,30),point=jg_get(d,27),line=jg_get(d,26);
    if((jg_get(d,viewport)|jg_get(d,viewport+1))&0x80000000u || jg_get(d,viewport+4)!=1 ||
        jg_nan(jg_get(d,viewport+2)) || jg_nan(jg_get(d,viewport+3)) ||
        (raster==1?(jg_get(d,28)||factor||units):raster!=2 || (factor&0x7f800000u)==0x7f800000u ||
         (units&0x7f800000u)==0x7f800000u) || jg_get(d,24)>2 || jg_get(d,31)>2 ||
        (point&&((point>>31)||jg_nan(point))) || (line&&((line>>31)||jg_nan(line))))return -EINVAL;
    return 0;
}
/* Source-side equivalents of TypedFragmentStateLayout.errors and
 * FragmentShaderDescriptor.errors for the exact 16x8, GS-linked ABI5/7
 * route. Address-dependent checks are made on the later bound graph; source
 * scratch/PC/GUR fields must remain zero until their real relocations. */
static bool jg_fragment_semantics(const void *f,
    const struct opengpu_protected_tessellation_joint_geometry_source *s)
{
    u32 payload=jg_get(f,JF(VERSION)),blend=jg_get(f,JF(BLEND_VERSION));
    u32 abi=jg_get(f,JF(FRAGMENT_VERSION));
    u32 varyings=jg_get(f,JF(FRAGMENT_VARYINGSLOTS)),colors=jg_get(f,JF(FRAGMENT_COLORSLOTS));
    u64 centroid=jg_wide(f,JF(FRAGMENT_CENTROIDMASK));
    if(jg_get(f,JF(TESTS_VERSION))!=1 ||
        !((payload==1 && blend!=2 && blend!=3) || (payload==2 && blend==2) ||
          (s->profile.advanced_blend && payload==3 && blend==3)))return false;
    if(jg_get(f,JF(ENGINECOUNT))!=s->profile.engines || varyings>16 || colors>4 ||
        (jg_get(f,JF(FRAGMENT_COLORMASK))>>colors) || jg_get(f,JF(FRAGMENT_SAMPLEMODE))>2 ||
        (varyings<16 && (centroid>>(4*varyings))))return false;
    if(jg_get(f,JF(FRAGMENT_SAMPLEMODE))!=jg_get(s->frame,JW(IMAGE_PIXELS_COLORS_SAMPLEMODE)))return false;
    const u32 enabled[]={JW(IMAGE_PIXELS_COLORS_COLORS0_ENABLED),JW(IMAGE_PIXELS_COLORS_COLORS1_ENABLED),
        JW(IMAGE_PIXELS_COLORS_COLORS2_ENABLED),JW(IMAGE_PIXELS_COLORS_COLORS3_ENABLED)};
    const u32 formats[]={JW(IMAGE_PIXELS_COLORS_COLORS0_FORMAT),JW(IMAGE_PIXELS_COLORS_COLORS1_FORMAT),
        JW(IMAGE_PIXELS_COLORS_COLORS2_FORMAT),JW(IMAGE_PIXELS_COLORS_COLORS3_FORMAT)};
    for(u32 i=0;i<colors;i++) {
        u32 type=jg_get(f,JF(FRAGMENT_COLORTYPES0)+i),format=jg_get(s->frame,formats[i]);
        bool bad_type=type==3 || (jg_get(s->frame,enabled[i]) &&
            (format>=36 || type!=opengpu_compute_texture_formats[format].kind));
        if(bad_type)return false;
    }
    u64 prefix=((abi==5?12u:15u)+4u*varyings+4u*colors)*128u*4u;
    if(prefix>jg_get(s->frame,JW(SCRATCHSTRIDE)))return false;
    const u32 shared[]={JF(BLEND_ENABLE),JF(BLEND_SRCRGB),JF(BLEND_DSTRGB),
        JF(BLEND_SRCALPHA),JF(BLEND_DSTALPHA),JF(BLEND_EQUATIONRGB),JF(BLEND_EQUATIONALPHA)};
    if(blend==2 || blend==3) {
        for(u32 i=0;i<sizeof(shared)/sizeof(shared[0]);i++)if(jg_get(f,shared[i]))return false;
        if(blend==2)for(u32 i=0;i<4;i++) {
            u32 w=jg_get(f,JF(BLEND_BUFFERS0)+i);
            if((w>>27) || ((w>>1)&15)>14 || ((w>>5)&15)>14 ||
                ((w>>9)&15)>14 || ((w>>13)&15)>14 ||
                ((w>>17)&7)>4 || ((w>>20)&7)>4)return false;
        }
    } else {
        for(u32 i=0;i<4;i++)if(jg_get(f,JF(BLEND_BUFFERS0)+i))return false;
        if(jg_get(f,JF(BLEND_ENABLE)) && (blend!=1 || jg_get(f,JF(BLEND_SRCRGB))>14 ||
            jg_get(f,JF(BLEND_DSTRGB))>14 || jg_get(f,JF(BLEND_SRCALPHA))>14 ||
            jg_get(f,JF(BLEND_DSTALPHA))>14 || jg_get(f,JF(BLEND_EQUATIONRGB))>4 ||
            jg_get(f,JF(BLEND_EQUATIONALPHA))>4))return false;
    }
    return opengpu_native_advanced_blend_gfs_precheck(s->frame,2048,f,512,s->profile.advanced_blend);
}
/* Immutable linked GS source equality uses retained ORIGINAL views only.
 * Runtime command tags and per-invocation FIRST seeds are separate ownership. */
static bool jg_geometry_same_source(const struct opengpu_joint_geometry_plan *a,const struct opengpu_joint_geometry_plan *b)
{
    return a->state_handle==b->state_handle && a->raster_discard==b->raster_discard &&
        !memcmp(&a->program,&b->program,sizeof(a->program)) && !memcmp(&a->uniforms,&b->uniforms,sizeof(a->uniforms)) &&
        !memcmp(a->gng,b->gng,sizeof(a->gng)) && !memcmp(a->evaluation_components,b->evaluation_components,sizeof(a->evaluation_components));
}
static bool jg_geometry_same_seeds(const struct opengpu_joint_geometry_plan *a,const struct opengpu_joint_geometry_plan *b)
{
    return a->gs_task_id_base==b->gs_task_id_base && a->first_input_primitive_id==b->first_input_primitive_id &&
        a->input_sequence==b->input_sequence && a->first_output_sequence==b->first_output_sequence;
}
static int jg_geometry_validate(const struct jg_capture *,u32,u32);
static int jg_preflight(struct jg_capture *c,u32 *domain)
{
    struct opengpu_protected_tessellation_joint_geometry_source *s=&c->s;
    u32 n=jg_get(s->job,17),stride=jg_get(s->job,20);
    int ret;
    if(!opengpu_protected_tessellation_joint_geometry_job_canonical(s->job,512,false) ||
        jg_get(s->job,24)!=s->context || jg_get(s->job,25)!=s->generation || jg_get(s->job,26)!=s->pass ||
        n!=s->range_count || n!=s->plan_count || s->draw_count!=s->draw_plan_count ||
        (u64)s->draw_count*2048!=s->draws_bytes || (u64)s->state_plan_count*512!=s->states_bytes ||
        (n?((u64)(n-1)*stride+2048!=s->sequences_bytes):s->sequences_bytes!=0) ||
        s->sequences_bytes>(1ULL<<32) || s->states_bytes>(1ULL<<32) || s->draws_bytes>(1ULL<<32) ||
        !s->resource_count || s->resource_count>27 ||
        !opengpu_tessellation_canonical_words(s->frame,2048,opengpu_tessellation_frame_word_rules))return -EINVAL;
    const u32 header[]={0x474e4601u,2048,1,16,16,16,4,4};
    for(u32 i=0;i<8;i++)if(jg_get(s->frame,i)!=header[i])return -EINVAL;
    if(!n && (s->draw_count || s->invocation_count || s->state_plan_count || s->states_bytes))return -EINVAL;
    if(n && (!s->state_plan_count || jg_get(s->job,34)!=s->draw_count || jg_get(s->job,35)!=s->invocation_count))return -EINVAL;
    *domain=jg_get(s->job,0)-0x474e5107u;
    ret=jg_indices(n,&c->plan_index);
    if(!ret)ret=jg_indices(n,&c->range_index);
    if(!ret)ret=jg_indices(s->state_plan_count,&c->state_index);
    if(!ret)ret=jg_indices(s->draw_count,&c->draw_index);
    if(!ret)ret=jg_indices(n,&c->geometry_index);
    if(!ret)ret=jg_indices(s->draw_count,&c->geometry_draw_first);
    if(!ret)ret=jg_indices(s->invocation_count,&c->geometry_invocation_first);
    if(ret)return ret;
    for(u32 i=0;i<s->state_plan_count;i++) {
        const struct opengpu_protected_tessellation_state_plan *p=&s->state_plans[i];
        if(p->reserved || p->state_handle>=s->state_plan_count || c->state_index[p->state_handle])return -EINVAL;
        c->state_index[p->state_handle]=i+1;
        const u8 *f=(const u8 *)s->states+(size_t)p->state_handle*512;
        u32 abi=jg_get(f,JF(FRAGMENT_VERSION));
        if(!opengpu_tessellation_canonical_words_n(f,512,opengpu_tessellation_fragment_state_word_rules,128) ||
            jg_get(f,0)!=0x47465301u || jg_get(f,1)!=512 || jg_get(f,2)!=1 || jg_get(f,3)!=4 ||
            jg_get(f,JF(HEADER_HANDLE))!=p->state_handle || jg_get(f,JF(HEADER_CONTEXT))!=s->context ||
            jg_get(f,JF(HEADER_PASS))!=s->pass || jg_get(f,JF(HEADER_GENERATION))!=s->generation ||
            jg_get(f,JF(FRAGMENT_IDENTITY_CONTEXT))!=s->context || jg_get(f,JF(FRAGMENT_IDENTITY_PASS))!=s->pass ||
            jg_get(f,JF(FRAGMENT_IDENTITY_GENERATION))!=s->generation ||
            (abi!=5 && abi!=7) || (abi==7 && !s->profile.sample_inputs) ||
            jg_get(f,JF(FRAGMENT_SHADERPC)) || !jg_zero(f+40*4,8*4) || !jg_zero(f+120*4,4*4) ||
            jg_get(f,JF(FRAGMENT_UNIFORMS_VERSION)) || jg_get(f,JF(FRAGMENT_UNIFORMS_ADDRESS)) ||
            jg_get(f,JF(FRAGMENT_RESOURCES_VERSION)) || jg_get(f,JF(FRAGMENT_RESOURCES_ADDRESS)) ||
            jg_get(f,JF(IMAGE_VERSION)) || jg_get(f,JF(IMAGE_ADDRESS)) ||
            !jg_fragment_semantics(f,s))return -EINVAL;
    }
    for(u32 i=0;i<s->draw_count;i++) {
        const struct drm_opengpu_native_draw_plan *p=&s->draw_plans[i];
        if(p->draw_index>=s->draw_count || c->draw_index[p->draw_index] || p->state_handle>=s->state_plan_count)return -EINVAL;
        c->draw_index[p->draw_index]=i+1;
        const struct opengpu_protected_tessellation_state_plan *fs=&s->state_plans[c->state_index[p->state_handle]-1];
        if(memcmp(&p->fragment_program,&fs->fragment_program,sizeof(p->fragment_program)) ||
            memcmp(&p->fragment_uniforms,&fs->fragment_uniforms,sizeof(p->fragment_uniforms)))return -EINVAL;
    }
    for(u32 i=0;i<n;i++) {
        const struct opengpu_joint_geometry_plan *g=&s->geometry_plans[i];
        if(g->sequence_index>=n || c->geometry_index[g->sequence_index])return -EINVAL;
        c->geometry_index[g->sequence_index]=i+1;
    }
    for(u32 i=0;i<n;i++) {
        u32 p=s->plans[i].sequence_index,r=s->ranges[i].sequence_index;
        if(p>=n || r>=n || c->plan_index[p] || c->range_index[r] ||
            s->plans[i].state_handle>=s->state_plan_count || s->ranges[i].original_draw_index>=s->draw_count)return -EINVAL;
        c->plan_index[p]=i+1;c->range_index[r]=i+1;
    }
    if(n) {
        struct opengpu_tessellation_vertex_outer_source_v2 outer=jg_outer(c);
        ret=opengpu_tessellation_vertex_outer_precheck(&outer);
        if(ret)return ret;
    }
    for(u32 i=0;i<n;i++) {
        ret=jg_sequence(c,i,*domain);
        if(!ret)ret=jg_geometry_validate(c,i,*domain);
        if(!ret) {
            const struct opengpu_tessellation_vertex_outer_range_v2 *r=&s->ranges[c->range_index[i]-1];
            const struct opengpu_joint_geometry_plan *g=&s->geometry_plans[c->geometry_index[i]-1];
            if((r->flags&OPENGPU_VERTEX_OUTER_FIRST) && g->first_input_primitive_id)return -EINVAL;
            u32 *draw=&c->geometry_draw_first[r->original_draw_index],*invocation=&c->geometry_invocation_first[r->invocation_index];
            if(*draw && !jg_geometry_same_source(g,&s->geometry_plans[c->geometry_index[*draw-1]-1]))ret=-EINVAL;
            if(*invocation && !jg_geometry_same_seeds(g,&s->geometry_plans[c->geometry_index[*invocation-1]-1]))ret=-EINVAL;
            if(!*draw)*draw=i+1;
            if(!*invocation)*invocation=i+1;
        }
        if(ret)return ret;
        if(i+1<n && !jg_zero((const u8 *)s->sequences+(size_t)i*stride+2048,stride-2048))return -EINVAL;
    }
    return 0;
}

/* Aggregate ORIGINAL physical cache-line roles, including every catalog view.
 * A global resolve cache pins each handle to one immutable allocation result. */
struct jg_backing {u32 handle;struct opengpu_native_frame_backing backing;};
struct jg_span {u64 first,end,logical_first,logical_end;u32 role;bool write;};
struct jg_alias {
    struct jg_capture *capture;
    struct jg_backing *backings;
    struct jg_span *spans;
    size_t count,backing_count,capacity;
};
static int jg_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *out)
{
    struct jg_alias *a=opaque;
    struct opengpu_native_frame_backing b={0};
    int ret;
    for(size_t i=0;i<a->backing_count;i++)if(a->backings[i].handle==handle){*out=a->backings[i].backing;return 0;}
    if(!handle || a->backing_count>=a->capacity)return -EINVAL;
    ret=a->capture->copy.resolve(a->capture->copy.context,handle,&b);
    if(ret)return ret;
    if(!b.bytes || ((b.physical|b.bytes)&4095u) || b.bytes>(1ULL<<32) || b.physical>(1ULL<<32)-b.bytes)return -EINVAL;
    a->backings[a->backing_count++]=(struct jg_backing){handle,b};*out=b;
    return 0;
}
static int jg_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    struct jg_alias *a=opaque;
    return a->capture->copy.copy(a->capture->copy.context,v,out);
}
static int jg_span(struct jg_alias *a,u32 handle,u64 offset,u64 bytes,u32 role,bool write)
{
    struct opengpu_native_frame_backing b;
    int ret;
    if(!handle)return offset || bytes?-EINVAL:0;
    if(!bytes || offset>~(u64)0-bytes || a->count>=a->capacity)return -EINVAL;
    ret=jg_resolve(a,handle,&b);
    if(ret)return ret;
    if(offset+bytes>b.bytes)return -EINVAL;
    a->spans[a->count++]=(struct jg_span){(b.physical+offset)&~63ULL,
        (b.physical+offset+bytes+63u)&~63ULL,b.physical+offset,b.physical+offset+bytes,role,write};
    return 0;
}
static int jg_program_spans(struct jg_alias *a,const struct drm_opengpu_native_program_view *code,
    const struct drm_opengpu_native_uniform_stage *uniforms)
{
    int ret=jg_span(a,code->handle,code->offset,code->bytes,0,false);
    if(ret)return ret;
    for(u32 i=0;i<13;i++) {
        const struct drm_opengpu_compute_uniform_view *v=i<12?&uniforms->blocks[i]:&uniforms->defaults;
        ret=jg_span(a,v->handle,v->offset,v->bytes,0,false);
        if(ret)return ret;
    }
    return 0;
}
static void jg_swap(struct jg_span *a,struct jg_span *b)
{struct jg_span tmp=*a;*a=*b;*b=tmp;}
static bool jg_less(const struct jg_span *a,const struct jg_span *b)
{return a->first<b->first || (a->first==b->first && a->end<b->end);}
static void jg_sift(struct jg_span *s,size_t root,size_t n)
{
    while(root<n/2) {
        size_t child=root*2+1;
        if(child+1<n && jg_less(s+child,s+child+1))child++;
        if(!jg_less(s+root,s+child))break;
        jg_swap(s+root,s+child);root=child;
    }
}
static int jg_alias(struct jg_alias *a)
{
    const struct opengpu_protected_tessellation_joint_geometry_source *s=&a->capture->s;
    int ret;
    u64 max=(u64)s->draw_count*33+(u64)s->plan_count*28+(u64)s->state_plan_count*14+s->resource_count+(u64)s->geometry_plan_count*14;
    if(!max || max>(u64)~(size_t)0/sizeof(*a->spans) || max>(u64)~(size_t)0/sizeof(*a->backings))return -ERANGE;
    a->capacity=(size_t)max;
    a->spans=kvzalloc(a->capacity*sizeof(*a->spans),GFP_KERNEL);
    a->backings=kvzalloc(a->capacity*sizeof(*a->backings),GFP_KERNEL);
    if(!a->spans || !a->backings)return -ENOMEM;
    for(u32 i=0;i<s->draw_count;i++) {
        const struct drm_opengpu_native_draw_plan *p=&s->draw_plans[i];
        ret=jg_program_spans(a,&p->vertex_program,&p->vertex_uniforms);
        if(ret)return ret;
        for(u32 j=0;j<18;j++) {
            const struct drm_opengpu_compute_uniform_view *v=j<16?&p->bindings[j]:j==16?&p->indices:&p->indirect;
            ret=jg_span(a,v->handle,v->offset,v->bytes,0,false);
            if(ret)return ret;
        }
        const struct drm_opengpu_compute_uniform_view *v=&p->vertex_scratch;
        if(!v->handle)return -EINVAL;
        ret=jg_span(a,v->handle,v->offset,v->bytes,1,true);
        if(ret)return ret;
    }
    for(u32 i=0;i<s->plan_count;i++) {
        const struct opengpu_protected_tessellation_joint_geometry_plan *p=&s->plans[i];
        ret=jg_program_spans(a,&p->control_program,&p->control_uniforms);
        if(!ret)ret=jg_program_spans(a,&p->evaluation_program,&p->evaluation_uniforms);
        if(ret)return ret;
    }
    for(u32 i=0;i<s->state_plan_count;i++) {
        ret=jg_program_spans(a,&s->state_plans[i].fragment_program,&s->state_plans[i].fragment_uniforms);
        if(ret)return ret;
    }
    for(u32 i=0;i<s->geometry_plan_count;i++) {
        const struct opengpu_joint_geometry_plan *p=&s->geometry_plans[i];
        ret=jg_program_spans(a,&p->program,&p->uniforms);if(ret)return ret;
    }
    for(u32 i=0;i<s->resource_count;i++) {
        const struct opengpu_native_frame_resource *p=&s->resources[i];
        ret=jg_span(a,p->gem_handle,p->offset,p->bytes,2,!!(p->access&OPENGPU_NATIVE_FRAME_RESOURCE_WRITE));
        if(ret)return ret;
    }
    /* Source, VS scratch and frame roles must not overlap after physical
     * cache-line rounding, except for an identical serial VS scratch view. */
    for(size_t i=a->count/2;i;i--)jg_sift(a->spans,i-1,a->count);
    for(size_t i=a->count;i>1;i--){jg_swap(a->spans,a->spans+i-1);jg_sift(a->spans,0,i-1);}
    u64 source_end=0,writer_end=0,frame_end=0,vs_first=0,vs_end=0,vs_logical_first=0,vs_logical_end=0;
    for(size_t i=0;i<a->count;i++) {
        const struct jg_span *v=&a->spans[i];
        if(v->role==0) {
            if(writer_end>v->first)return -EINVAL;
            if(source_end<v->end)source_end=v->end;
        }else if(v->role==1) {
            if(source_end>v->first || frame_end>v->first ||
                (vs_end>v->first && (vs_first!=v->first || vs_end!=v->end ||
                 vs_logical_first!=v->logical_first || vs_logical_end!=v->logical_end)))return -EINVAL;
            vs_first=v->first;vs_end=v->end;vs_logical_first=v->logical_first;vs_logical_end=v->logical_end;
        }else {
            if(vs_end>v->first || (v->write && source_end>v->first))return -EINVAL;
            if(frame_end<v->end)frame_end=v->end;
        }
        if(v->write && writer_end<v->end)writer_end=v->end;
    }
    return 0;
}

static int jg_reloc(struct opengpu_private_snapshot *o,u32 from,u64 offset,u32 word,u32 to,u64 addend,bool limit)
{
    if(offset>(u64)~(size_t)0-4u*word)return -ERANGE;
    return opengpu_private_snapshot_relocate(o,from,(size_t)offset+4u*word,to,addend,
        limit?OPENGPU_PRIVATE_SNAPSHOT_LIMIT33:OPENGPU_PRIVATE_SNAPSHOT_VA32);
}
/* Metadata-only cross-stage proof. The actual scalar validator and GUR2
 * encoder run again when the captured GNG graph is appended. */
static int jg_geometry_validate(const struct jg_capture *c,u32 index,u32 domain)
{
    const struct opengpu_protected_tessellation_joint_geometry_source *s=&c->s;
    const struct opengpu_joint_geometry_plan *g=&s->geometry_plans[c->geometry_index[index]-1];
    const struct opengpu_protected_tessellation_joint_geometry_plan *p=&s->plans[c->plan_index[index]-1];
    const u8 *d=(const u8 *)s->sequences+(size_t)index*jg_get(s->job,20);
    const void *f=(const u8 *)s->states+(size_t)p->state_handle*512;
    u32 n[256];for(u32 i=0;i<256;i++)n[i]=jg_get(g->gng,i);
    u32 exports=jg_get(d,jg_layouts[domain].tes_outputs);
    if(g->state_handle!=p->state_handle || g->command_id>=32 || g->raster_discard ||
        !opengpu_native_geometry_program_encoding_valid(g->gng) || n[3]!=s->context || n[4]!=s->pass || n[5]!=s->generation ||
        n[6]!=(jg_get(d,220+(domain!=0))?1u:domain?3u:2u) || !n[7] || n[7]>32 || n[8]>2 || !n[9] || n[9]>256 || n[10]>64 || n[11]>64 ||
        n[11]+(n[12]&1u)+((n[12]>>1)&1u)>64 || n[9]*(n[11]+(n[12]&1u)+((n[12]>>1)&1u))>1024 ||
        n[13]>16 || n[14]!=jg_get(f,JF(FRAGMENT_VARYINGSLOTS)) || n[15]!=1 || !jg_zero(n+16,9*4))return -EINVAL;
    u64 flat=n[25]|(u64)n[26]<<32;
    if(n[14]<16 && (flat>>(n[14]*4)))return -EINVAL;
    for(u32 i=0;i<64;i++) {
        u32 v=g->evaluation_components[i],source=n[32+i];
        if(i<n[10]) {
            if((v&~255u) || !(v&128u) || (v&127u)>=64 || (v&127u)>=exports || source>=4+4*n[13])return -EINVAL;
            for(u32 j=0;j<i;j++)if(n[32+j]==source && g->evaluation_components[j]!=v)return -EINVAL;
        }else if(v || source)return -EINVAL;
    }
    for(u32 i=0;i<68;i++) {
        u32 valid=n[96+2*i],src=n[97+2*i];
        if(valid?(i>=4+4*n[14] || src>=n[11]):src!=0)return -EINVAL;
    }
    bool uniforms=g->uniforms.active_mask || g->uniforms.defaults.handle;
    return uniforms && !s->profile.geometry_uniforms?-EOPNOTSUPP:0;
}
static int jg_geometry_append(struct opengpu_private_snapshot *owner,const struct jg_capture *c,
    const struct opengpu_native_job_copy *copy,u32 *output)
{
    const struct opengpu_protected_tessellation_joint_geometry_source *s=&c->s;
    if(!s->geometry_plan_count)return -EINVAL;
    u64 bytes=64+(u64)s->geometry_plan_count*1024;
    if(bytes>0xffffffffu || bytes>(u64)~(size_t)0)return -ERANGE;
    u8 *raw=kvzalloc((size_t)bytes,GFP_KERNEL);if(!raw)return -ENOMEM;
    const u32 header[]={OPENGPU_JOINT_GEOMETRY_MAGIC,(u32)bytes,1,1024,s->geometry_plan_count,s->context,s->pass,s->generation};
    for(u32 i=0;i<8;i++)jg_put(raw,i,header[i]);
    u32 domain=jg_get(s->job,0)-0x474e5107u;
    for(u32 i=0;i<s->geometry_plan_count;i++) {
        const struct opengpu_joint_geometry_plan *g=&s->geometry_plans[c->geometry_index[i]-1];
        u8 *e=raw+64+(size_t)i*1024;
        u32 fields[]={i,g->state_handle,domain,0,s->context,s->pass,s->generation,g->command_id,
            g->gs_task_id_base,g->first_input_primitive_id,(u32)g->first_output_sequence,(u32)(g->first_output_sequence>>32),0,0,0,1024};
        for(u32 j=0;j<16;j++)jg_put(e,j,fields[j]);
        for(u32 j=0;j<64;j++)jg_put(e,16+j,g->evaluation_components[j]);
        jg_put(e,180,g->raster_discard);jg_put(e,224,(u32)g->input_sequence);jg_put(e,225,(u32)(g->input_sequence>>32));
    }
    u32 table;int ret=opengpu_private_snapshot_append(owner,raw,(size_t)bytes,&table);kvfree(raw);
    if(ret)return ret;
    for(u32 i=0;i<s->geometry_plan_count;i++) {
        const struct opengpu_joint_geometry_plan *g=&s->geometry_plans[c->geometry_index[i]-1];
        u32 segment;u64 offset=64+(u64)i*1024;
        struct opengpu_geometry_source source={.metadata=g->gng,.metadata_bytes=1024,.program=&g->program,.uniforms=&g->uniforms,
            .context=s->context,.pass=s->pass,.generation=s->generation,.topology=jg_get(g->gng,6)==1?0:domain?4:1,
            .vertex_varyings=jg_get(g->gng,13),.fragment_varyings=jg_get(g->gng,14),.geometry_enabled=s->profile.geometry_inputs,
            .scalar_fpu=s->profile.scalar_fpu};
        ret=opengpu_geometry_snapshot_append(owner,&source,copy->copy,copy->context,&segment);
        if(!ret)ret=jg_reloc(owner,table,offset,12,segment,0,false);
        if(!ret)ret=jg_reloc(owner,table,offset,13,segment,1024,true);
        if(ret)return ret;
    }
    *output=table;return 0;
}

int opengpu_protected_tessellation_joint_geometry_build(struct opengpu_private_snapshot *owner,
    const struct opengpu_protected_tessellation_joint_geometry_source *source,
    const struct opengpu_native_job_copy *callbacks,u32 *output)
{
    struct jg_capture captured={0};
    struct jg_alias aliases={.capture=&captured};
    struct opengpu_tessellation_vertex_outer_roots roots={0};
    struct opengpu_native_job_copy copy={jg_copy,jg_resolve,&aliases};
    u32 domain,states=0,sequences=0,frame=0,job=0,geometry=0;
    int ret;
    if(!owner || !source || !callbacks || !callbacks->copy || !callbacks->resolve || !output)return -EINVAL;
    if(!opengpu_protected_tessellation_joint_geometry_profile_valid(&source->profile))return -EOPNOTSUPP;
    captured.s=*source;captured.copy=*callbacks;
    ret=opengpu_private_snapshot_build_context(owner,captured.s.context);
    if(ret)return ret;
    ret=jg_capture(&captured);
    if(!ret)ret=jg_preflight(&captured,&domain);
    if(!ret)ret=jg_alias(&aliases);
    if(ret)goto done;
    struct opengpu_protected_tessellation_joint_geometry_source *s=&captured.s;
    if(s->range_count) {
        struct opengpu_tessellation_vertex_outer_source_v2 outer=jg_outer(&captured);
        ret=opengpu_tessellation_vertex_outer_append_roots(owner,&outer,&copy,&roots);
        if(ret)goto done;
    }
    /* Author presence/identity only in owned captured metadata, before append.
     * Exact uniform encoders still reject invalid plans before success. */
    for(u32 i=0;i<s->state_plan_count;i++) {
        const struct opengpu_protected_tessellation_state_plan *p=&s->state_plans[i];
        if(p->fragment_uniforms.active_mask || p->fragment_uniforms.defaults.handle)
            jg_put((u8 *)s->states+(size_t)p->state_handle*512,JF(FRAGMENT_UNIFORMS_VERSION),1);
    }
    for(u32 n=0;n<s->range_count;n++) {
        const struct opengpu_protected_tessellation_joint_geometry_plan *p=&s->plans[captured.plan_index[n]-1];
        u8 *d=(u8 *)s->sequences+(size_t)n*jg_get(s->job,20);
        bool control=p->control_uniforms.active_mask || p->control_uniforms.defaults.handle;
        bool evaluation=p->evaluation_uniforms.active_mask || p->evaluation_uniforms.defaults.handle;
        if(control || evaluation){jg_put(d,496,s->context);jg_put(d,497,s->pass);jg_put(d,498,s->generation);}
        if(control)jg_put(d,499,3);
        if(evaluation)jg_put(d,504,3);
    }
    if(s->states_bytes) {
        ret=opengpu_private_snapshot_append(owner,s->states,s->states_bytes,&states);
        if(ret)goto done;
    }
    if(s->sequences_bytes) {
        ret=opengpu_private_snapshot_append(owner,s->sequences,s->sequences_bytes,&sequences);
        if(ret)goto done;
    }
    for(u32 i=0;i<s->state_plan_count;i++) {
        const struct opengpu_protected_tessellation_state_plan *p=&s->state_plans[i];
        const void *f=(const u8 *)s->states+(size_t)p->state_handle*512;
        struct drm_opengpu_native_uniform_stage stages[2]={{0},p->fragment_uniforms};
        struct opengpu_native_uniform_roots ur;
        struct opengpu_native_program_snapshot program;
        ret=opengpu_native_uniform_snapshot_append(owner,stages,s->context,s->pass,s->generation,copy.copy,copy.context,&ur);
        if(ret)goto done;
        struct opengpu_native_program_profile profile={.staging_bytes=jg_get(s->frame,JW(SCRATCHSTRIDE)),
            .capacity=128,.stage=1,.version=jg_get(f,JF(FRAGMENT_VERSION)),
            .varyings=jg_get(f,JF(FRAGMENT_VARYINGSLOTS)),.colors=jg_get(f,JF(FRAGMENT_COLORSLOTS)),
            .scalar_fpu=s->profile.scalar_fpu,.owned_uniforms=ur.present[1],
            .geometry_inputs=true,.sample_inputs=jg_get(f,JF(FRAGMENT_VERSION))==7};
        ret=opengpu_native_program_snapshot_append(owner,&p->fragment_program,&profile,copy.copy,copy.context,&program);
        if(!ret)ret=jg_reloc(owner,states,(u64)p->state_handle*512,JF(FRAGMENT_SHADERPC),program.segment,program.entry_offset,false);
        if(!ret && ur.present[1]) {
            ret=jg_reloc(owner,states,(u64)p->state_handle*512,JF(FRAGMENT_UNIFORMS_ADDRESS),ur.segment,ur.root_offset[1],false);
        }
        if(ret)goto done;
    }
    for(u32 n=0;n<s->range_count;n++) {
        const struct opengpu_protected_tessellation_joint_geometry_plan *p=&s->plans[captured.plan_index[n]-1];
        const struct jg_layout *l=&jg_layouts[domain];
        u64 offset=(u64)n*jg_get(s->job,20);
        const void *d=(const u8 *)s->sequences+(size_t)offset;
        struct drm_opengpu_native_uniform_stage stages[2]={p->control_uniforms,p->evaluation_uniforms};
        struct opengpu_tessellation_program_profile tc,te;
        struct opengpu_tessellation_uniform_roots ur;
        struct opengpu_native_program_snapshot program;
        jg_profiles(d,l,&captured,&tc,&te);
        ret=opengpu_tessellation_uniform_snapshot_append(owner,stages,s->context,s->pass,s->generation,copy.copy,copy.context,&ur);
        if(ret)goto done;
        for(u32 k=0;k<2 && !ret;k++) {
            struct opengpu_tessellation_program_profile *profile=k?&te:&tc;
            profile->owned_uniforms=ur.present[k];
            ret=opengpu_tessellation_program_snapshot_append(owner,k?&p->evaluation_program:&p->control_program,
                profile,copy.copy,copy.context,&program);
            if(!ret)ret=jg_reloc(owner,sequences,offset,k?l->evaluation_pc:l->control_pc,program.segment,program.entry_offset,false);
            if(!ret && ur.present[k]) {
                u32 first=k?504:499;
                ret=jg_reloc(owner,sequences,offset,first+1,ur.segment,ur.root_offset[k],false);
                if(!ret)ret=jg_reloc(owner,sequences,offset,first+2,ur.segment,0,false);
                if(!ret)ret=jg_reloc(owner,sequences,offset,first+3,ur.segment,ur.bytes,true);
            }
        }
        if(!ret)ret=jg_reloc(owner,sequences,offset,509,roots.root_segments[n],0,false);
        if(!ret)ret=jg_reloc(owner,sequences,offset,510,roots.root_segments[n],512,true);
        if(ret)goto done;
    }
    ret=s->range_count?jg_geometry_append(owner,&captured,&copy,&geometry):0;
    if(ret)goto done;
    /* Empty no-state frame publication is a distinct helper; no dummy FS. */
    if(!s->states_bytes)ret=opengpu_native_frame_snapshot_append_empty_complete(owner,s->frame,s->resources,
        s->resource_count,s->profile.engines,s->context,s->pass,s->generation,copy.resolve,copy.context,&frame);
    else ret=opengpu_native_frame_snapshot_append_complete(owner,s->frame,states,s->states_bytes,s->resources,
        s->resource_count,s->profile.engines,s->context,s->pass,s->generation,copy.resolve,copy.context,&frame);
    if(ret)goto done;
    ret=opengpu_private_snapshot_append(owner,s->job,512,&job);
    if(!ret && s->range_count)ret=jg_reloc(owner,job,0,16,sequences,0,false);
    if(!ret && s->range_count)ret=jg_reloc(owner,job,0,18,sequences,s->sequences_bytes,true);
    if(!ret)ret=jg_reloc(owner,job,0,21,frame,0,false);
    if(!ret)ret=jg_reloc(owner,job,0,22,frame,2048,true);
    if(!ret && s->range_count)ret=jg_reloc(owner,job,0,28,roots.map_segment,0,false);
    if(!ret && s->range_count)ret=jg_reloc(owner,job,0,29,roots.map_segment,64+(u64)s->range_count*64,true);
    if(!ret && s->range_count)ret=jg_reloc(owner,job,0,37,geometry,0,false);
    if(!ret && s->range_count)ret=jg_reloc(owner,job,0,38,geometry,64+(u64)s->range_count*1024,true);
    if(!ret)*output=job;
done:
    opengpu_tessellation_vertex_outer_roots_release(&roots);
    kvfree(aliases.spans);kvfree(aliases.backings);jg_free(&captured);
    return ret;
}
#undef JF
#undef JW

int opengpu_protected_tessellation_joint_geometry_prepare(struct opengpu_private_snapshot *owner,u32 segment)
{
    int ret=opengpu_private_snapshot_prepare(owner);
    if(!ret)ret=opengpu_private_snapshot_check_prepared_joint_geometry(owner,segment);
    return ret;
}

int opengpu_protected_tessellation_joint_geometry_command_locked(
    const struct opengpu_private_snapshot *owner, struct opengpu_device *gpu,
    const struct opengpu_render_context *context, u32 segment,
    struct opengpu_protected_tessellation_joint_geometry_host_command *command)
{
    struct opengpu_private_snapshot_joint_request request;
    int ret;

    if (!command)
        return -EINVAL;
    ret = opengpu_private_snapshot_joint_geometry_request_locked(owner, gpu, context, segment, &request);
    if (ret)
        return ret;
    if ((request.job_base & (OPENGPU_JOINT_GEOMETRY_HOST_ROOT_ALLOCATION_BYTES - 1u)) ||
        request.job_allocation_base != request.job_base ||
        request.job_limit != (u64)request.job_base + OPENGPU_JOINT_GEOMETRY_HOST_ROOT_BYTES ||
        request.job_allocation_limit != (u64)request.job_base + OPENGPU_JOINT_GEOMETRY_HOST_ROOT_ALLOCATION_BYTES ||
        request.owner_limit <= request.owner_base ||
        request.owner_limit > OPENGPU_JOINT_GEOMETRY_HOST_OWNER_LIMIT_MAXIMUM ||
        request.domain >= OPENGPU_JOINT_GEOMETRY_HOST_DOMAIN_COUNT)
        return -EINVAL;
    *command = (struct opengpu_protected_tessellation_joint_geometry_host_command) {
        .opcode = OPENGPU_JOINT_GEOMETRY_HOST_OPCODE,
        .source = request.job_base,
        .destination = request.owner_base,
        .bytes = OPENGPU_JOINT_GEOMETRY_HOST_ROOT_BYTES,
        .pattern = OPENGPU_JOINT_GEOMETRY_HOST_COMMAND_TAG |
            ((u32)(request.owner_limit >> 32) << OPENGPU_JOINT_GEOMETRY_HOST_PATTERN_OWNER_LIMIT_HIGH_SHIFT) |
            (request.domain << OPENGPU_JOINT_GEOMETRY_HOST_PATTERN_DOMAIN_SHIFT),
        .width = (u32)request.owner_limit,
        .height = request.context,
        .source_stride = request.pass,
        .destination_stride = request.generation,
    };
    return 0;
}
