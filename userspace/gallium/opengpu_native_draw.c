/* SPDX-License-Identifier: MIT */
#include "opengpu_native_draw.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include <errno.h>
#include <string.h>

#define D(name) OPENGPU_NATIVE_BINNED_DRAW_DRAW_##name##_WORD
#define I(name) D(VERTEX_GEOMETRY_VERTEX_INPUT_##name)
#define F(name) OPENGPU_TYPED_FRAGMENT_STATE_##name##_WORD
#define C(c, name) (((c)->words[OPENGPU_NATIVE_BINNED_HOST_##name##_WORD] & \
    OPENGPU_NATIVE_BINNED_HOST_##name##_MASK) >> OPENGPU_NATIVE_BINNED_HOST_##name##_SHIFT)
#define BINDING(n, field) I(BINDINGS##n##_##field)
#define BINDINGS(field) { BINDING(0,field),BINDING(1,field),BINDING(2,field),BINDING(3,field), \
    BINDING(4,field),BINDING(5,field),BINDING(6,field),BINDING(7,field), \
    BINDING(8,field),BINDING(9,field),BINDING(10,field),BINDING(11,field), \
    BINDING(12,field),BINDING(13,field),BINDING(14,field),BINDING(15,field) }
static const unsigned binding_stride[16]=BINDINGS(STRIDE), binding_divisor[16]=BINDINGS(DIVISOR);
_Static_assert(I(ATTRIBUTES15_BINDINGINDEX)==I(ATTRIBUTES0_BINDINGINDEX)+15*9,"attribute wire stride");

static void envelope(uint32_t *words, unsigned magic, unsigned bytes)
{
    words[0]=magic;words[1]=bytes;words[2]=1;
    words[3]=16;words[4]=16;words[5]=16;words[6]=4;words[7]=4;
}
static uint32_t fp32(float value)
{ uint32_t bits;memcpy(&bits,&value,4);return bits; }
static unsigned component_bytes(unsigned format)
{
    switch (format) {
    case 3:case 4:case 7:case 10:case 11:case 16:case 17:return 2;
    case 5:case 6:case 8:case 9:case 14:case 15:return 1;
    default:return 4;
    }
}
int opengpu_native_job_encode(unsigned context, unsigned pass, unsigned generation,
    unsigned draw_count, struct opengpu_native_binned_job *output)
{
    if (!output || !context) { errno=EINVAL;return -1; }
    struct opengpu_native_binned_job result={0};
    envelope(result.words,OPENGPU_NATIVE_BINNED_JOB_MAGIC,OPENGPU_NATIVE_BINNED_JOB_BYTES);
    result.words[OPENGPU_NATIVE_BINNED_JOB_JOB_DRAWCOUNT_WORD]=draw_count;
    result.words[OPENGPU_NATIVE_BINNED_JOB_JOB_DRAWSTRIDE_WORD]=OPENGPU_NATIVE_BINNED_DRAW_BYTES;
    result.words[OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_CONTEXT_WORD]=context;
    result.words[OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_PASS_WORD]=pass;
    result.words[OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_GENERATION_WORD]=generation;
    *output=result;return 0;
}

int opengpu_native_draw_encode_checked(const struct opengpu_native_draw_request *r,
    const struct opengpu_native_runtime *runtime,unsigned context_id,struct opengpu_native_draw_encoding *output)
{
    struct opengpu_native_draw_encoding e={0};
    struct opengpu_native_runtime actual;
    if (!r || !output || !runtime || !r->context || !r->sources ||
        !r->state_count || r->state_handle>=r->state_count || !r->width || !r->height ||
        r->sample_mode>2 || r->topology>6 || !r->instances) { errno=EINVAL;return -1; }
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(r->context->screen);
    if (!context_id) { errno=ENOTSUP;return -1; }
    actual=*runtime;
    const struct opengpu_native_graphics_sources *s=r->sources;
    if (memcmp(&actual,&s->runtime,sizeof(actual))) { errno=EPROTO;return -1; }
    const struct opengpu_native_uniform_layout *vs=&s->layout[0],*fs=&s->layout[1];
    if (vs->stage!=0 || fs->stage!=1 || vs->capacity!=actual.shader.capacity || fs->capacity!=vs->capacity ||
        vs->attributes>actual.shader.attributes || fs->attributes || vs->colors ||
        vs->varyings!=fs->varyings || vs->varyings>actual.shader.varyings || fs->colors>actual.shader.colors ||
        (s->varying_mask[1]&~s->varying_mask[0]) ||
        (s->color_mask>>fs->colors) || !s->color_mask ||
        r->width>C(&actual.frame,FRAME_WIDTH) || r->height>C(&actual.frame,FRAME_HEIGHT) ||
        (1u<<r->sample_mode)>C(&actual.frame,SAMPLES)) { errno=EINVAL;return -1; }
    if ((vs->varyings<16 && ((s->varying_mask[0]|s->varying_mask[1])>>(vs->varyings*4)))) {
        errno=EINVAL;return -1;
    }
    envelope(e.draw.words,OPENGPU_NATIVE_BINNED_DRAW_MAGIC,OPENGPU_NATIVE_BINNED_DRAW_BYTES);
    uint32_t *d=e.draw.words,*f=e.state.words;
    d[D(VERSION)]=1;d[D(STATE)]=r->state_handle;d[D(TOKEN)]=r->token;
    d[D(RASTER_VERSION)]=1;d[D(RASTER_FRONTFACECCW)]=1;
    d[D(RASTER_LINEWIDTH)]=fp32(1);d[D(RASTER_POINTSIZE)]=fp32(1);
    d[D(RASTER_SAMPLEMODE)]=r->sample_mode;d[D(RASTER_SCREENW)]=r->width;d[D(RASTER_SCREENH)]=r->height;
    d[D(VERTEX_GEOMETRY_TOPOLOGY)]=r->topology;
    d[D(VERTEX_GEOMETRY_FLATMASK)]=(uint32_t)s->varying_mask[1];
    d[D(VERTEX_GEOMETRY_FLATMASK)+1]=(uint32_t)(s->varying_mask[1]>>32);
    d[D(VERTEX_GEOMETRY_VERTEX_ATTRIBUTESLOTS)]=vs->attributes;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGSLOTS)]=vs->varyings;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGMASK)]=(uint32_t)s->varying_mask[0];
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGMASK)+1]=(uint32_t)(s->varying_mask[0]>>32);
    d[D(VERTEX_GEOMETRY_VERTEX_VERSION)]=1;
    d[D(VERTEX_VIEWPORT_VERSION)]=1;d[D(VERTEX_VIEWPORT_EXTENTX)]=r->width;
    d[D(VERTEX_VIEWPORT_EXTENTY)]=r->height;d[D(VERTEX_VIEWPORT_FARDEPTH)]=fp32(1);
    d[I(VERSION)]=2;d[I(COUNT)]=r->count;d[I(INSTANCES)]=r->instances;
    d[I(FIRSTVERTEX)]=r->first_vertex;d[I(BASEINSTANCE)]=r->base_instance;
    d[I(IDENTITY_CONTEXT)]=context_id;d[I(IDENTITY_PASS)]=r->pass;d[I(IDENTITY_GENERATION)]=r->generation;
    unsigned used=0;
    for (unsigned i=0;i<16;i++) {
        const struct opengpu_native_draw_attribute *a=&r->attributes[i];
        if (i>=vs->attributes) {
            if (a->enabled) { errno=EINVAL;return -1; }
            continue;
        }
        if (a->enabled>1 || a->binding>=actual.shader.bindings || a->components>4 ||
            (a->enabled && !a->components) || a->format>OPENGPU_NATIVE_BINNED_INPUT_FORMAT_FIXED16_16 ||
            (a->enabled && a->format>=20 && a->format<=23 && a->components!=4)) {
            errno=EINVAL;return -1;
        }
        unsigned base=I(ATTRIBUTES0_BINDINGINDEX)+i*9;
        d[base]=a->binding;d[base+1]=a->components;
        memcpy(&d[base+2],a->constant,sizeof(a->constant));
        d[base+6]=a->enabled;d[base+7]=a->format;d[base+8]=a->offset;
        if (a->enabled) {
            const struct opengpu_native_draw_binding *binding=&r->bindings[a->binding];
            const unsigned bytes=component_bytes(a->format),mask=bytes-1;
            if (((binding->view.offset+a->offset)&mask) || (binding->stride&mask)) {
                errno=EINVAL;return -1;
            }
            if (r->count) {
                uint64_t index=binding->divisor ? (uint64_t)r->base_instance+(r->instances-1)/binding->divisor :
                    (uint64_t)r->first_vertex+r->count-1;
                unsigned element=(a->format>=20 && a->format<=23) ? 4 : a->components*bytes;
                if (index>UINT32_MAX || index*binding->stride+a->offset+element>binding->view.bytes) {
                    errno=EINVAL;return -1;
                }
            }
            used|=1u<<a->binding;
        }
    }
    for (unsigned i=0;i<16;i++) if (used&(1u<<i)) {
        if (pipe_opengpu_native_source_view(screen,&r->bindings[i].view,&e.plan.bindings[i])) return -1;
        d[binding_stride[i]]=r->bindings[i].stride;d[binding_divisor[i]]=r->bindings[i].divisor;
    }
    const uint64_t scratch_bytes=(7u+vs->attributes*4u+vs->varyings*4u)*(uint64_t)vs->capacity*4;
    if (r->vertex_scratch.bytes<scratch_bytes || (r->vertex_scratch.offset&63) ||
        (r->vertex_scratch.bytes&63)) { errno=EINVAL;return -1; }
    if (pipe_opengpu_native_source_view(screen,&r->vertex_scratch,&e.plan.vertex_scratch)) return -1;
    f[F(HEADER_MAGIC)]=OPENGPU_TYPED_FRAGMENT_STATE_MAGIC;f[F(HEADER_BYTES)]=OPENGPU_TYPED_FRAGMENT_STATE_BYTES;
    f[F(HEADER_VERSION)]=1;f[F(HEADER_CAPACITY)]=4;f[F(HEADER_CONTEXT)]=context_id;
    f[F(HEADER_PASS)]=r->pass;f[F(HEADER_GENERATION)]=r->generation;f[F(HEADER_HANDLE)]=r->state_handle;
    f[F(VERSION)]=1;f[F(BLEND_VERSION)]=1;f[F(TESTS_VERSION)]=1;
    f[F(ENGINECOUNT)]=C(&actual.frame,ENGINES);f[F(FRAGMENT_VERSION)]=1;
    f[F(FRAGMENT_IDENTITY_CONTEXT)]=context_id;f[F(FRAGMENT_IDENTITY_PASS)]=r->pass;
    f[F(FRAGMENT_IDENTITY_GENERATION)]=r->generation;f[F(FRAGMENT_SAMPLEMODE)]=r->sample_mode;
    f[F(FRAGMENT_VARYINGSLOTS)]=fs->varyings;f[F(FRAGMENT_COLORSLOTS)]=fs->colors;
    f[F(FRAGMENT_COLORMASK)]=s->color_mask;
    for (unsigned i=0;i<4;i++) {
        if (r->color_types[i]>2 || (i>=fs->colors && r->color_types[i])) { errno=EINVAL;return -1; }
        f[F(FRAGMENT_COLORTYPES0)+i]=r->color_types[i];
    }
    e.plan.draw_index=r->draw_index;e.plan.state_handle=r->state_handle;
    e.plan.vertex_program=s->programs[0];e.plan.fragment_program=s->programs[1];
    e.plan.vertex_uniforms=s->uniforms[0];e.plan.fragment_uniforms=s->uniforms[1];
    *output=e;return 0;
}

int opengpu_native_graphics_submit(struct pipe_context *pipe,
    const struct opengpu_native_submit_sources *source, uint64_t *fence_sequence)
{
    struct opengpu_native_runtime actual;
    if (!pipe || !source || !fence_sequence || !source->runtime || !source->job || !source->frame ||
        !source->state_count || !source->states || !source->out_syncobj ||
        (source->draw_count && (!source->draws || !source->plans)) ||
        (uint64_t)source->draw_count*OPENGPU_NATIVE_BINNED_DRAW_BYTES>SIZE_MAX ||
        (uint64_t)source->state_count*OPENGPU_TYPED_FRAGMENT_STATE_BYTES>SIZE_MAX ||
        (uint64_t)source->draw_count*OPENGPU_NATIVE_BINNED_DRAW_BYTES>UINT64_C(0x100000000) ||
        (uint64_t)source->state_count*OPENGPU_TYPED_FRAGMENT_STATE_BYTES>UINT64_C(0x100000000) ||
        source->frame->resource_count>OPENGPU_NATIVE_FRAME_MAX_RESOURCES) { errno=EINVAL;return -1; }
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(pipe->screen);
    unsigned context_id=opengpu_native_graphics_context_id(pipe);
    if (!context_id) { errno=ENOTSUP;return -1; }
    if (opengpu_native_runtime_query(pipe_opengpu_screen_fd(screen),&actual)) return -1;
    if (memcmp(&actual,source->runtime,sizeof(actual))) { errno=EPROTO;return -1; }
    const uint32_t *job=source->job->words;
    if (job[OPENGPU_NATIVE_BINNED_JOB_JOB_IDENTITY_CONTEXT_WORD]!=context_id ||
        job[OPENGPU_NATIVE_BINNED_JOB_JOB_DRAWCOUNT_WORD]!=source->draw_count) { errno=EINVAL;return -1; }
    const struct drm_opengpu_submit_native_binned command={
        .version=1,.context_id=context_id,.in_syncobj=source->in_syncobj,.out_syncobj=source->out_syncobj,
        .job=(uintptr_t)source->job,.frame=(uintptr_t)&source->frame->frame,
        .draws=(uintptr_t)source->draws,.draws_bytes=(uint64_t)source->draw_count*OPENGPU_NATIVE_BINNED_DRAW_BYTES,
        .states=(uintptr_t)source->states,.states_bytes=(uint64_t)source->state_count*OPENGPU_TYPED_FRAGMENT_STATE_BYTES,
        .plans=(uintptr_t)source->plans,.plan_count=source->draw_count,.plan_stride=sizeof(*source->plans),
        .resources=(uintptr_t)source->frame->resources,.resource_count=source->frame->resource_count,
        .resource_stride=sizeof(*source->frame->resources)};
    return opengpu_native_submit(pipe_opengpu_screen_fd(screen),&command,fence_sequence);
}

int opengpu_native_draw_encode(const struct opengpu_native_draw_request *r,
    struct opengpu_native_draw_encoding *out)
{
    struct opengpu_native_runtime runtime;
    if(!r || !out || !r->context || !r->sources || !r->state_count ||
        r->state_handle>=r->state_count || !r->width || !r->height || r->sample_mode>2 ||
        r->topology>6 || !r->instances){errno=EINVAL;return -1;}
    if(opengpu_native_runtime_query(pipe_opengpu_screen_fd(opengpu_screen_gpu(r->context->screen)),&runtime))return -1;
    return opengpu_native_draw_encode_checked(r,&runtime,opengpu_native_graphics_context_id(r->context),out);
}
