/* SPDX-License-Identifier: MIT */
#include "opengpu_native_sample_graphics.h"
#include "../driver/opengpu_native_frame_internal.h"
#include "../driver/opengpu_shader_resources.h"
#include <errno.h>
#include <string.h>

/* Source contract, not captured evidence. Kept identical to the ordered
 * SAMPLE_WORDS registry in scripts/owner_resource_contract.py by CPU tests. */
static const struct opengpu_native_sample_word expected[OPENGPU_NATIVE_SAMPLE_DISCOVERY_WORDS] = {
    {0x360u, 0x47435201u},
    {0x364u, 0x10u},
    {0x368u, 0x1fffffu},
    {0x36cu, 0x2u},
    {0x370u, 0x10u},
    {0x374u, 0x8u},
    {0x378u, 0x80u},
    {0x37cu, 0x80u},
    {0x380u, 0xffffffffu},
    {0x384u, 0x4000u},
    {0x388u, 0x10u},
    {0x38cu, 0x8u},
    {0x390u, 0x10000000u},
    {0x394u, 0x20u},
    {0x398u, 0x20000u},
    {0x39cu, 0x4u},
    {0x160u, 0x474e4305u},
    {0x164u, 0x8u},
    {0x168u, 0x474e4a02u},
    {0x16cu, 0x8000200u},
    {0x170u, 0xc2100800u},
    {0x174u, 0x8000800u},
    {0x178u, 0xe4908000u},
    {0x17cu, 0x8813db02u},
    {0x340u, 0x47434901u},
    {0x344u, 0x8u},
    {0x348u, 0x7fu},
    {0x34cu, 0xeu},
    {0x350u, 0xcu},
    {0x354u, 0x4u},
    {0x358u, 0xffffffffu},
    {0x35cu, 0x80u},
    {0x240u, 0x47515601u},
    {0x244u, 0x8u},
    {0x248u, 0xb0a0908u},
    {0x24cu, 0x47514f01u},
    {0x250u, 0x40u},
    {0x254u, 0x8u},
    {0x258u, 0x1001001u},
    {0x25cu, 0x1fu},
    {0x260u, 0x47464301u},
    {0x264u, 0x8u},
    {0x268u, 0x1003010cu},
    {0x26cu, 0x47544601u},
    {0x270u, 0x200u},
    {0x274u, 0x4400408u},
    {0x278u, 0x45u},
    {0x27cu, 0xffu},
    {0x280u, 0x47515001u},
    {0x284u, 0x8u},
    {0x288u, 0x10040du},
    {0x28cu, 0x47504f01u},
    {0x290u, 0x40u},
    {0x294u, 0x204008u},
    {0x298u, 0x8c88u},
    {0x29cu, 0x3fu},
    {0x3a0u, 0x47554301u},
    {0x3a4u, 0x10u},
    {0x3a8u, 0x1u},
    {0x3acu, 0x7fu},
    {0x3b0u, 0x3u},
    {0x3b4u, 0xcu},
    {0x3b8u, 0x18u},
    {0x3bcu, 0x4000u},
    {0x3c0u, 0x400u},
    {0x3c4u, 0x400u},
    {0x3c8u, 0x100u},
    {0x3ccu, 0x40u},
    {0x3d0u, 0xe00000bu},
    {0x3d4u, 0x1c00002bu},
    {0x3d8u, 0x1e00002bu},
    {0x3dcu, 0x1fu},
    {0x4c0u, 0x47414201u},
    {0x4c4u, 0x8u},
    {0x4c8u, 0x8u},
    {0x4ccu, 0x7fffu},
    {0x4d0u, 0xf00001ffu},
    {0x4d4u, 0xfu},
    {0x4d8u, 0x7u},
    {0x4dcu, 0x0u},
    {0x4e0u, 0x474e5001u},
    {0x4e4u, 0x8u},
    {0x4e8u, 0x200u},
    {0x4ecu, 0x800u},
    {0x4f0u, 0x800u},
    {0x4f4u, 0x200u},
    {0x4f8u, 0xfu},
    {0x4fcu, 0x0u},
    {0x300u, 0x47534c01u},
    {0x304u, 0x8u},
    {0x308u, 0x1fu},
    {0x30cu, 0x20u},
    {0x310u, 0x100000u},
    {0x314u, 0x4051918u},
    {0x318u, 0x320u},
    {0x31cu, 0x138u},
    {0x500u, 0x47475301u},
    {0x504u, 0x8u},
    {0x508u, 0x1fu},
    {0x50cu, 0x474e4a02u},
    {0x510u, 0x474e4603u},
    {0x514u, 0x474e5902u},
    {0x518u, 0xff840206u},
    {0x51cu, 0x4001000u},
    {0x520u, 0x47535301u},
    {0x524u, 0x8u},
    {0x528u, 0x1u},
    {0x52cu, 0x1fu},
    {0x530u, 0xc0u},
    {0x534u, 0x4d041004u},
    {0x538u, 0x7ffu},
    {0x53cu, 0x7u},
};

static const uint32_t auxiliary[5] = {GPU_REG_ID, GPU_REG_CAPABILITIES,
    GPU_REG_BIN_CAPABILITY_PTR, 0x320, GPU_REG_SHADER_CONFIG};
static bool auxiliary_valid(uint32_t id,uint32_t caps,uint32_t pointer,uint32_t quota,uint32_t shader)
{
    const uint32_t required=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|
        GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS;
    return (id&0xffff0000u)==0x47550000u && (id&0xffffu)>=2 &&
        (caps&required)==required && ((caps&GPU_CAP_FRAGMENT_BATCH_MASK)>>GPU_CAP_FRAGMENT_BATCH_SHIFT)==128 &&
        pointer==GPU_REG_BIN_CAPABILITIES && quota==1048576 &&
        opengpu_shader_resources_valid(shader,128) && opengpu_shader_resource_lanes(shader)==16 &&
        opengpu_shader_resource_warps(shader)==8 && opengpu_shader_resource_cus(shader)==2;
}
static uint32_t lookup(void *opaque, uint32_t address)
{
    const struct opengpu_native_sample_word *words = opaque;
    for (unsigned i=0;i<OPENGPU_NATIVE_SAMPLE_OBSERVATIONS;++i)
        if (words[i].address==address) return words[i].value;
    return 0;
}
int opengpu_native_sample_runtime_decode(const struct opengpu_native_sample_word *words,
    size_t count, struct opengpu_native_sample_runtime *out)
{
    struct opengpu_native_sample_runtime value={0};
    bool layered=false,sample=false;
    if (!words || !out || count!=OPENGPU_NATIVE_SAMPLE_OBSERVATIONS) return -EINVAL;
    for (unsigned i=0;i<OPENGPU_NATIVE_SAMPLE_DISCOVERY_WORDS;++i)
        if (words[i].address!=expected[i].address || words[i].value!=expected[i].value) return -EPROTO;
    for (unsigned i=0;i<5;++i) if(words[112+i].address!=auxiliary[i]) return -EPROTO;
    value.id=words[112].value;value.capabilities=words[113].value;value.shader_config=words[116].value;
    if (!auxiliary_valid(value.id,value.capabilities,words[114].value,words[115].value,value.shader_config) ||
        !opengpu_read_native_sample_transport_limits(value.id,lookup((void *)words,0x368),
            value.capabilities,0x540,(void *)words,lookup,&value.limits,&layered,&sample) ||
        !layered || !sample) return -EPROTO;
    memcpy(value.observed,words,sizeof(value.observed));
    *out=value;return 0;
}
int opengpu_native_sample_runtime_validate(const struct opengpu_native_sample_runtime *runtime)
{
    struct opengpu_native_sample_runtime actual;
    if (!runtime) return -EINVAL;
    int error=opengpu_native_sample_runtime_decode(runtime->observed,OPENGPU_NATIVE_SAMPLE_OBSERVATIONS,&actual);
    if (error) return error;
    const struct opengpu_native_source_limits *a=&runtime->limits,*b=&actual.limits;
    return runtime->id!=actual.id || runtime->capabilities!=actual.capabilities ||
        runtime->shader_config!=actual.shader_config || a->capacity!=b->capacity ||
        a->engines!=b->engines || a->lanes!=b->lanes || a->warps!=b->warps ||
        a->available!=b->available || memcmp(a->frame.words,b->frame.words,sizeof(a->frame.words)) ||
        memcmp(a->geometry_words,b->geometry_words,sizeof(a->geometry_words)) ||
        memcmp(a->sample_words,b->sample_words,sizeof(a->sample_words)) ? -EPROTO : 0;
}
int opengpu_native_sample_runtime_read(uint64_t bytes,void *context,
    int (*read)(void *,uint32_t,uint32_t *),struct opengpu_native_sample_runtime *out)
{
    struct opengpu_native_sample_word words[OPENGPU_NATIVE_SAMPLE_OBSERVATIONS];
    uint32_t id,flags,caps,pointer,quota,shader;int error;
    if (!out || !read || bytes<0x540) return -EINVAL;
    error=read(context,GPU_REG_ID,&id);if(error)return error<0?error:-EIO;
    if ((id&0xffff0000u)!=0x47550000u || (id&0xffffu)<2) return -EPROTO;
    error=read(context,0x368,&flags);if(error)return error<0?error:-EIO;
    if(flags!=0x1fffffu)return -EPROTO;
    error=read(context,GPU_REG_CAPABILITIES,&caps);if(error)return error<0?error:-EIO;
    const uint32_t required=GPU_CAP_UNIFIED_COMMANDS|GPU_CAP_PROTECTED_VM_STATE|
        GPU_CAP_COMPUTE_SCALAR_FPU|GPU_CAP_SHADER_CONFIG_INFO|GPU_CAP_NATIVE_PIXEL_FORMATS;
    if((caps&required)!=required || ((caps&GPU_CAP_FRAGMENT_BATCH_MASK)>>GPU_CAP_FRAGMENT_BATCH_SHIFT)!=128)return -EPROTO;
    error=read(context,GPU_REG_BIN_CAPABILITY_PTR,&pointer);if(error)return error<0?error:-EIO;
    if(pointer!=GPU_REG_BIN_CAPABILITIES)return -EPROTO;
    error=read(context,0x320,&quota);if(error)return error<0?error:-EIO;
    error=read(context,GPU_REG_SHADER_CONFIG,&shader);if(error)return error<0?error:-EIO;
    if(!auxiliary_valid(id,caps,pointer,quota,shader))return -EPROTO;
    for(unsigned i=0;i<OPENGPU_NATIVE_SAMPLE_OBSERVATIONS;++i){
        words[i].address=i<112?expected[i].address:auxiliary[i-112];
        error=read(context,words[i].address,&words[i].value);if(error)return error<0?error:-EIO;
    }
    if(words[112].value!=id || words[113].value!=caps || words[114].value!=pointer ||
       words[115].value!=quota || words[116].value!=shader || lookup(words,0x368)!=flags)return -EPROTO;
    return opengpu_native_sample_runtime_decode(words,OPENGPU_NATIVE_SAMPLE_OBSERVATIONS,out);
}
int opengpu_native_sample_frame_encode(const struct opengpu_native_sample_frame_request *r,
    struct opengpu_native_frame_encoding *out)
{
    if(!r || !out || opengpu_native_sample_runtime_validate(r->runtime) || !r->frame.caps ||
        memcmp(r->frame.caps,&r->runtime->limits.frame,sizeof(*r->frame.caps)))return -EINVAL;
    return opengpu_native_frame_encode_gnc5(&r->frame,r->source_pitch,r->resolve_pitch,out);
}
static bool view_valid(const struct drm_opengpu_compute_uniform_view *view)
{
    return view->handle ? view->bytes && view->offset<=UINT64_MAX-view->bytes :
        !view->offset && !view->bytes;
}
int opengpu_native_sample_job_encode(const struct opengpu_native_sample_runtime *runtime,
    uint32_t context,uint32_t pass,uint32_t generation,uint32_t draws,
    const struct drm_opengpu_compute_uniform_view *completion,struct opengpu_native_sample_job_encoding *out)
{
    struct opengpu_native_sample_job_encoding value={0};
    if(!out || !context || !completion || opengpu_native_sample_runtime_validate(runtime) ||
        !completion->handle || !view_valid(completion) || (completion->offset&63) ||
        completion->bytes!=OPENGPU_NATIVE_LAYERED_COMPLETION_BYTES)return -EINVAL;
    uint32_t *w=value.job.words;
    w[0]=OPENGPU_NATIVE_LAYERED_JOB_MAGIC;w[1]=512;w[2]=2;
    w[3]=w[4]=w[5]=16;w[6]=w[7]=4;
    w[OPENGPU_NATIVE_LAYERED_JOB_JOB_DRAWCOUNT_WORD]=draws;
    w[OPENGPU_NATIVE_LAYERED_JOB_JOB_DRAWSTRIDE_WORD]=2048;
    w[OPENGPU_NATIVE_LAYERED_JOB_JOB_IDENTITY_CONTEXT_WORD]=context;
    w[OPENGPU_NATIVE_LAYERED_JOB_JOB_IDENTITY_PASS_WORD]=pass;
    w[OPENGPU_NATIVE_LAYERED_JOB_JOB_IDENTITY_GENERATION_WORD]=generation;
    w[OPENGPU_NATIVE_LAYERED_JOB_COMPLETION_WRITABLE_WORD]=1;
    value.completion.view=*completion;*out=value;return 0;
}
static bool program_valid(const struct drm_opengpu_native_program_view *p)
{
    return p->handle && !p->pad && p->bytes && p->bytes<=2048 &&
        !((p->offset|p->bytes|p->entry_offset)&3) && p->entry_offset<p->bytes &&
        p->offset<=UINT64_MAX-p->bytes;
}
static bool uniforms_valid(const struct drm_opengpu_native_uniform_stage *stage)
{
    if(stage->pad || stage->active_mask&~0xfffu)return false;
    for(unsigned i=0;i<13;++i){
        const struct drm_opengpu_compute_uniform_view *v=i<12?&stage->blocks[i]:&stage->defaults;
        if(!view_valid(v) || ((v->offset|v->bytes)&3) || v->bytes>(i<12?16384u:4096u) ||
            (i<12 && !!v->handle!=!!(stage->active_mask&(1u<<i))))return false;
    }
    return true;
}
int opengpu_native_sample_ordinary_plan(const struct opengpu_native_sample_runtime *runtime,
    const struct drm_opengpu_native_draw_plan *plan,struct drm_opengpu_native_layered_draw_plan *out)
{
    struct drm_opengpu_native_layered_draw_plan value={0};
    if(!out || !plan || opengpu_native_sample_runtime_validate(runtime) ||
        !program_valid(&plan->vertex_program) || !program_valid(&plan->fragment_program) ||
        !uniforms_valid(&plan->vertex_uniforms) || !uniforms_valid(&plan->fragment_uniforms) ||
        !plan->vertex_scratch.handle || !view_valid(&plan->vertex_scratch) ||
        ((plan->vertex_scratch.offset|plan->vertex_scratch.bytes)&63) ||
        !view_valid(&plan->indices) || !view_valid(&plan->indirect))return -EINVAL;
    for(unsigned i=0;i<16;++i)if(!view_valid(&plan->bindings[i]))return -EINVAL;
    value.draw=*plan;*out=value;return 0;
}
