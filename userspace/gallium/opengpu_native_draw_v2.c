/* SPDX-License-Identifier: MIT */
#include "opengpu_native_draw_v2.h"
#include "opengpu_native_graphics_internal.h"
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
int opengpu_native_draw_encode_v2(const struct opengpu_native_draw_request_v2 *request,
    struct opengpu_native_draw_encoding_v2 *output)
{
    struct opengpu_native_draw_encoding e={0};
    struct opengpu_native_draw_encoding_v2 result={0};
    struct opengpu_native_graphics_source_description_v2 storage;
    if(!request || !output){errno=EINVAL;return -1;}
    /* Freeze caller metadata before any mapping/view callback. */
    const struct opengpu_native_draw_request_v2 captured=*request,*r=&captured;
    if(!r->context || !r->source || !r->state_count || r->state_handle>=r->state_count ||
        !r->width || !r->height || r->sample_mode>2 || r->topology>6 || !r->instances ||
        opengpu_native_graphics_source_matches_v2(r->source,r->context) ||
        opengpu_native_graphics_source_describe_v2(r->source,&storage)){errno=EINVAL;return -1;}
    const struct opengpu_native_graphics_source_description_v2 *s=&storage;
    const struct opengpu_native_uniform_layout *vs=&s->vertex_layout;
    const struct opengpu_native_fragment_metadata_v2 *fs=&s->fragment.metadata;
    struct opengpu_native_sample_runtime actual;unsigned context_id;
    if(opengpu_native_sample_context_runtime(r->context,&actual,&context_id))return -1;
    if(context_id!=s->context_id || memcmp(&actual,&s->runtime,sizeof(actual)) ||
        vs->stage || vs->capacity!=128 || vs->attributes>16 || vs->colors ||
        !opengpu_native_fragment_metadata_valid_v2(fs) || vs->varyings!=fs->varying_slots ||
        (fs->varying_mask & ~s->vertex.varying_mask) ||
        r->width>C(&actual.limits.frame,FRAME_WIDTH) || r->height>C(&actual.limits.frame,FRAME_HEIGHT) ||
        (1u<<r->sample_mode)>C(&actual.limits.frame,SAMPLES)){errno=EINVAL;return -1;}
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(r->context->screen);
    envelope(e.draw.words,OPENGPU_NATIVE_BINNED_DRAW_MAGIC,OPENGPU_NATIVE_BINNED_DRAW_BYTES);
    uint32_t *d=e.draw.words,*f=e.state.words;
    d[D(VERSION)]=1;d[D(STATE)]=r->state_handle;d[D(TOKEN)]=r->token;
    d[D(RASTER_VERSION)]=1;d[D(RASTER_FRONTFACECCW)]=1;
    d[D(RASTER_LINEWIDTH)]=fp32(1);d[D(RASTER_POINTSIZE)]=fp32(1);
    d[D(RASTER_SAMPLEMODE)]=r->sample_mode;d[D(RASTER_SCREENW)]=r->width;d[D(RASTER_SCREENH)]=r->height;
    d[D(VERTEX_GEOMETRY_TOPOLOGY)]=r->topology;
    d[D(VERTEX_GEOMETRY_FLATMASK)]=(uint32_t)fs->flat_mask;
    d[D(VERTEX_GEOMETRY_FLATMASK)+1]=(uint32_t)(fs->flat_mask>>32);
    d[D(VERTEX_GEOMETRY_VERTEX_ATTRIBUTESLOTS)]=vs->attributes;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGSLOTS)]=vs->varyings;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGMASK)]=(uint32_t)s->vertex.varying_mask;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGMASK)+1]=(uint32_t)(s->vertex.varying_mask>>32);
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
        if (a->enabled>1 || a->binding>=16 || a->components>4 ||
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
    f[F(ENGINECOUNT)]=C(&actual.limits.frame,ENGINES);f[F(FRAGMENT_VERSION)]=fs->abi_version;
    f[F(FRAGMENT_IDENTITY_CONTEXT)]=context_id;f[F(FRAGMENT_IDENTITY_PASS)]=r->pass;
    f[F(FRAGMENT_IDENTITY_GENERATION)]=r->generation;f[F(FRAGMENT_SAMPLEMODE)]=r->sample_mode;
    f[F(FRAGMENT_VARYINGSLOTS)]=fs->varying_slots;f[F(FRAGMENT_COLORSLOTS)]=fs->color_slots;
    f[F(FRAGMENT_COLORMASK)]=fs->output_color_mask;
    for (unsigned i=0;i<4;i++) {
        f[F(FRAGMENT_COLORTYPES0)+i]=fs->color_types[i];
    }
    f[F(FRAGMENT_ALLOWDEPTHOVERRIDE)]=fs->writes_depth;
    f[F(FRAGMENT_CENTROIDMASK)]=(uint32_t)fs->centroid_mask;
    f[F(FRAGMENT_CENTROIDMASK)+1]=(uint32_t)(fs->centroid_mask>>32);
    e.plan.draw_index=r->draw_index;e.plan.state_handle=r->state_handle;
    e.plan.vertex_program=s->programs[0];e.plan.fragment_program=s->programs[1];
    e.plan.vertex_uniforms=s->uniforms[0];e.plan.fragment_uniforms=s->uniforms[1];
    if(opengpu_native_sample_ordinary_plan(&actual,&e.plan,&result.plan)){errno=EINVAL;return -1;}
    result.draw=e.draw;result.state=e.state;result.fragment_prefix_bytes=fs->prefix_bytes;
    *output=result;return 0;
}

