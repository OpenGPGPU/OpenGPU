/* SPDX-License-Identifier: MIT */
#include "opengpu_native_joint_build_source.h"
#include "opengpu_native_joint_internal.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include "util/u_inlines.h"
#include "opengpu_tessellation_job_validate.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>

#define D(n) OPENGPU_NATIVE_BINNED_DRAW_DRAW_##n##_WORD
#define I(n) D(VERTEX_GEOMETRY_VERTEX_INPUT_##n)
#define F(n) OPENGPU_TYPED_FRAGMENT_STATE_##n##_WORD
#define C(c,n) (((c)->words[OPENGPU_NATIVE_BINNED_HOST_##n##_WORD]&OPENGPU_NATIVE_BINNED_HOST_##n##_MASK)>>OPENGPU_NATIVE_BINNED_HOST_##n##_SHIFT)
static const unsigned binding_stride[16]={I(BINDINGS0_STRIDE),I(BINDINGS1_STRIDE),I(BINDINGS2_STRIDE),I(BINDINGS3_STRIDE),I(BINDINGS4_STRIDE),I(BINDINGS5_STRIDE),I(BINDINGS6_STRIDE),I(BINDINGS7_STRIDE),I(BINDINGS8_STRIDE),I(BINDINGS9_STRIDE),I(BINDINGS10_STRIDE),I(BINDINGS11_STRIDE),I(BINDINGS12_STRIDE),I(BINDINGS13_STRIDE),I(BINDINGS14_STRIDE),I(BINDINGS15_STRIDE)};
static const unsigned binding_divisor[16]={I(BINDINGS0_DIVISOR),I(BINDINGS1_DIVISOR),I(BINDINGS2_DIVISOR),I(BINDINGS3_DIVISOR),I(BINDINGS4_DIVISOR),I(BINDINGS5_DIVISOR),I(BINDINGS6_DIVISOR),I(BINDINGS7_DIVISOR),I(BINDINGS8_DIVISOR),I(BINDINGS9_DIVISOR),I(BINDINGS10_DIVISOR),I(BINDINGS11_DIVISOR),I(BINDINGS12_DIVISOR),I(BINDINGS13_DIVISOR),I(BINDINGS14_DIVISOR),I(BINDINGS15_DIVISOR)};
struct opengpu_native_joint_build_source {
    struct opengpu_native_joint_source *source;
    struct opengpu_native_joint_build_description d;
    struct opengpu_native_joint_description program;
    uint32_t job[128];struct opengpu_native_frame_encoding frame;
    uint32_t *draws,*sequences,*states;
    struct drm_opengpu_native_draw_plan *draw_plans;
    struct opengpu_native_joint_invocation *invocations;
    struct opengpu_native_joint_range *ranges;
    struct opengpu_native_joint_build_plan *plans;
    struct opengpu_native_joint_state_plan *state_plans;
    struct pipe_resource **holds;size_t held,hold_capacity;
    struct pipe_context *context;
    bool driver_profile;
};
static uint32_t fp32(float f){uint32_t u;memcpy(&u,&f,4);return u;}
static void envelope(uint32_t *w,uint32_t magic,unsigned bytes)
{w[0]=magic;w[1]=bytes;w[2]=1;w[3]=w[4]=w[5]=16;w[6]=w[7]=4;}
static bool empty(const struct opengpu_native_joint_view *v)
{return !v->resource&&!v->offset&&!v->bytes;}
static int hold(struct opengpu_native_joint_build_source *s,const struct opengpu_native_joint_view *v,
    struct drm_opengpu_compute_uniform_view *out)
{
    struct drm_opengpu_compute_uniform_view result={0};
    if(empty(v)){*out=result;return 0;}
    if(!v->resource || v->resource->screen!=s->context->screen || !v->bytes ||
        v->offset>UINT64_MAX-v->bytes || s->held==s->hold_capacity)return -1;
    /* Hold the actual original before deriving its handle/window. No file or
     * caller-supplied integer handle is an original-object authority. */
    pipe_resource_reference(&s->holds[s->held],v->resource);s->held++;
    struct pipe_opengpu_uniform_view view={opengpu_resource(v->resource)->gpu,v->offset,v->bytes};
    if(pipe_opengpu_native_source_view(opengpu_screen_gpu(s->context->screen),&view,&result))return -1;
    *out=result;return 0;
}
static int gem(struct opengpu_native_joint_build_source *s,const struct opengpu_native_joint_view *v,
    struct opengpu_native_frame_gem *out)
{
    struct drm_opengpu_compute_uniform_view p;
    if(hold(s,v,&p))return -1;
    *out=(struct opengpu_native_frame_gem){p.handle,p.offset,p.bytes};return 0;
}
void opengpu_native_joint_build_source_destroy(struct opengpu_native_joint_build_source *s)
{
    if(!s)return;
    for(size_t i=0;i<s->held;i++)pipe_resource_reference(&s->holds[i],NULL);
    opengpu_native_joint_source_destroy(s->source);
    free(s->holds);free(s->draws);free(s->sequences);free(s->states);free(s->draw_plans);
    free(s->invocations);free(s->ranges);free(s->plans);free(s->state_plans);free(s);
}
int opengpu_native_joint_build_source_describe(const struct opengpu_native_joint_build_source *s,
    struct opengpu_native_joint_build_description *out)
{if(!s||!out){errno=EINVAL;return -1;}*out=s->d;return 0;}
static unsigned component_bytes(unsigned f)
{
    switch(f){case 3:case 4:case 7:case 10:case 11:case 16:case 17:return 2;
    case 5:case 6:case 8:case 9:case 14:case 15:return 1;default:return 4;}
}
static int draw(struct opengpu_native_joint_build_source *s,const struct opengpu_native_joint_catalog_draw *r,unsigned index)
{
    const struct opengpu_native_uniform_layout *vs=&s->program.vertex_layout;
    const struct opengpu_native_fragment_metadata_v2 *fs=&s->program.fragment.metadata;
    uint32_t *d=s->draws+(size_t)index*512;struct drm_opengpu_native_draw_plan *p=s->draw_plans+index;
    if(r->state_handle>=s->d.state_count || r->indexed>1 || r->index_format>3 ||
        (!r->indexed&&(r->index_format||r->first_index||!empty(&r->indices))))return -1;
    envelope(d,OPENGPU_NATIVE_BINNED_DRAW_MAGIC,2048);
    d[D(VERSION)]=1;d[D(STATE)]=r->state_handle;d[D(TOKEN)]=r->token;
    d[D(RASTER_VERSION)]=1;d[D(RASTER_FRONTFACECCW)]=1;
    d[D(RASTER_LINEWIDTH)]=d[D(RASTER_POINTSIZE)]=fp32(1);
    d[D(RASTER_SAMPLEMODE)]=s->frame.tile.sample_mode;
    d[D(RASTER_SCREENW)]=s->frame.frame.words[OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_SCREENWIDTH_WORD];
    d[D(RASTER_SCREENH)]=s->frame.frame.words[OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_SCREENHEIGHT_WORD];
    /* Original input draw is PATCHES provenance. Geometry assembly is not
     * used as the TES output topology or as a shader/GS permission. */
    d[D(VERTEX_GEOMETRY_TOPOLOGY)]=4;
    d[D(VERTEX_GEOMETRY_FLATMASK)]=(uint32_t)fs->flat_mask;
    d[D(VERTEX_GEOMETRY_FLATMASK)+1]=(uint32_t)(fs->flat_mask>>32);
    d[D(VERTEX_GEOMETRY_VERTEX_ATTRIBUTESLOTS)]=vs->attributes;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGSLOTS)]=vs->varyings;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGMASK)]=(uint32_t)s->program.vertex.varying_mask;
    d[D(VERTEX_GEOMETRY_VERTEX_VARYINGMASK)+1]=(uint32_t)(s->program.vertex.varying_mask>>32);
    d[D(VERTEX_GEOMETRY_VERTEX_VERSION)]=1;
    d[D(VERTEX_VIEWPORT_VERSION)]=1;d[D(VERTEX_VIEWPORT_EXTENTX)]=d[D(RASTER_SCREENW)];
    d[D(VERTEX_VIEWPORT_EXTENTY)]=d[D(RASTER_SCREENH)];d[D(VERTEX_VIEWPORT_FARDEPTH)]=fp32(1);
    d[I(VERSION)]=2;d[I(COUNT)]=r->count;d[I(INSTANCES)]=r->instances;
    d[I(FIRSTVERTEX)]=r->first_vertex;d[I(BASEVERTEX)]=(uint32_t)r->base_vertex;d[I(BASEINSTANCE)]=r->base_instance;
    d[I(IDENTITY_CONTEXT)]=s->d.context;d[I(IDENTITY_PASS)]=s->d.pass;d[I(IDENTITY_GENERATION)]=s->d.generation;
    unsigned used=0;
    for(unsigned a=0;a<16;a++){
        const struct opengpu_native_draw_attribute *v=r->attributes+a;
        if(a>=vs->attributes){if(v->enabled)return -1;continue;}
        if(v->enabled>1||v->binding>=16||v->components>4||(v->enabled&&!v->components)||v->format>OPENGPU_NATIVE_BINNED_INPUT_FORMAT_FIXED16_16||
            (v->enabled&&v->format>=20&&v->format<=23&&v->components!=4))return -1;
        unsigned base=I(ATTRIBUTES0_BINDINGINDEX)+a*9;
        d[base]=v->binding;d[base+1]=v->components;memcpy(d+base+2,v->constant,16);
        d[base+6]=v->enabled;d[base+7]=v->format;d[base+8]=v->offset;
        if(v->enabled){
            const struct opengpu_native_joint_binding *b=r->bindings+v->binding;
            unsigned bytes=component_bytes(v->format),element=v->format>=20&&v->format<=23?4:v->components*bytes;
            if(b->view.offset>UINT64_MAX-v->offset || (b->view.offset+v->offset)&(bytes-1) || b->stride&(bytes-1))return -1;
            if(r->count && r->instances && (!r->indexed||b->divisor)){
                uint64_t at=b->divisor?(uint64_t)r->base_instance+(r->instances-1)/b->divisor:(uint64_t)r->first_vertex+r->count-1;
                if(at>UINT32_MAX||at*b->stride+v->offset+element>b->view.bytes)return -1;
            }
            used|=1u<<v->binding;
        }
    }
    for(unsigned b=0;b<16;b++){
        if(!(used&(1u<<b))){if(!empty(&r->bindings[b].view)||r->bindings[b].stride||r->bindings[b].divisor)return -1;continue;}
        if(hold(s,&r->bindings[b].view,p->bindings+b))return -1;
        d[binding_stride[b]]=r->bindings[b].stride;
        d[binding_divisor[b]]=r->bindings[b].divisor;
    }
    if(r->indexed){
        if(!r->index_format)return -1;
        unsigned bytes=1u<<(r->index_format-1);
        if( ((r->indices.offset)&(bytes-1)) || ((uint64_t)r->first_index+r->count)*bytes>r->indices.bytes ||
            hold(s,&r->indices,&p->indices))return -1;
        d[I(INDEXFORMAT)]=r->index_format;d[I(FIRSTINDEX)]=r->first_index;
    }
    uint64_t needed=(7u+4u*vs->attributes+4u*vs->varyings)*512ULL;
    if(r->scratch.bytes<needed || (r->scratch.offset&63) || (r->scratch.bytes&63) || hold(s,&r->scratch,&p->vertex_scratch))return -1;
    p->draw_index=index;p->state_handle=r->state_handle;p->vertex_program=s->program.code[0];p->fragment_program=s->program.code[3];
    p->vertex_uniforms=s->program.uniforms[0];p->fragment_uniforms=s->program.uniforms[3];return 0;
}
static int frame(struct opengpu_native_joint_build_source *s,const struct opengpu_native_joint_frame *r,
    const struct opengpu_native_joint_runtime *runtime)
{
    struct opengpu_native_frame_request q={.width=r->width,.height=r->height,.samples=r->samples,
        .color_mask=r->color_mask,.tile_log2=r->tile_log2,.context=s->d.context,.pass=s->d.pass,
        .generation=s->d.generation,.state_count=s->d.state_count?s->d.state_count:1,
        .scratch_stride=s->program.fragment.metadata.prefix_bytes,.spill_count=r->spill_count};
    for(unsigned i=0;i<6;i++){
        const struct opengpu_native_joint_attachment *a=r->attachments+i;
        struct opengpu_native_frame_attachment *b=q.attachments+i;
        b->format=a->format;b->load_op=a->load_op;b->channel_mask=a->channel_mask;memcpy(b->clear,a->clear,16);
        if(gem(s,&a->image.view,&b->image.gem)||gem(s,&a->resolve.view,&b->resolve.gem))return -1;
        b->image.row_pitch=a->image.row_pitch;b->image.store=a->image.store;
        b->resolve.row_pitch=a->resolve.row_pitch;b->resolve.store=a->resolve.store;
    }
    if(gem(s,&r->primitives,&q.primitives)||gem(s,&r->directory,&q.directory))return -1;
    for(unsigned i=0;i<9;i++)if(gem(s,r->pool+i,q.pool+i))return -1;
    for(unsigned i=0;i<4;i++)if(gem(s,r->scratch+i,q.scratch+i))return -1;
    const struct opengpu_native_binned_host_caps *c=&runtime->frame;
    struct opengpu_native_frame_limits limits={C(c,FRAME_WIDTH),C(c,FRAME_HEIGHT),C(c,SAMPLES),
        C(c,COLORS),C(c,ENGINES),C(c,STORAGE_BYTES),C(c,TILE8),C(c,TILE16),2};
    /* Generate GNF1 directly with the existing wide frame.version=2. This is
     * neither a copied GNF3 nor a fabricated GNC2/3 observation. */
    return opengpu_native_frame_encode_joint(&q,&limits,&s->frame);
}
static void state(struct opengpu_native_joint_build_source *s,unsigned index,unsigned engines)
{
    uint32_t *f=s->states+(size_t)index*128;
    const struct opengpu_native_fragment_metadata_v2 *m=&s->program.fragment.metadata;
    f[F(HEADER_MAGIC)]=OPENGPU_TYPED_FRAGMENT_STATE_MAGIC;f[F(HEADER_BYTES)]=512;f[F(HEADER_VERSION)]=1;
    f[F(HEADER_CAPACITY)]=4;f[F(HEADER_CONTEXT)]=s->d.context;f[F(HEADER_PASS)]=s->d.pass;
    f[F(HEADER_GENERATION)]=s->d.generation;f[F(HEADER_HANDLE)]=index;
    f[F(VERSION)]=1;f[F(BLEND_VERSION)]=1;f[F(TESTS_VERSION)]=1;f[F(ENGINECOUNT)]=engines;
    f[F(FRAGMENT_VERSION)]=m->abi_version;f[F(FRAGMENT_IDENTITY_CONTEXT)]=s->d.context;
    f[F(FRAGMENT_IDENTITY_PASS)]=s->d.pass;f[F(FRAGMENT_IDENTITY_GENERATION)]=s->d.generation;
    f[F(FRAGMENT_SAMPLEMODE)]=s->frame.tile.sample_mode;f[F(FRAGMENT_VARYINGSLOTS)]=m->varying_slots;
    f[F(FRAGMENT_COLORSLOTS)]=m->color_slots;f[F(FRAGMENT_COLORMASK)]=m->output_color_mask;
    for(unsigned i=0;i<4;i++)f[F(FRAGMENT_COLORTYPES0)+i]=m->color_types[i];
    f[F(FRAGMENT_ALLOWDEPTHOVERRIDE)]=m->writes_depth;f[F(FRAGMENT_CENTROIDMASK)]=(uint32_t)m->centroid_mask;
    f[F(FRAGMENT_CENTROIDMASK)+1]=(uint32_t)(m->centroid_mask>>32);
    s->state_plans[index]=(struct opengpu_native_joint_state_plan){.state_handle=index,
        .fragment_program=s->program.code[3],.fragment_uniforms=s->program.uniforms[3]};
}
struct sequence_layout {unsigned bases,grid,local,input_components,input_vertices,output_components,output_vertices,
    patch_components,task,point,spacing,egrid,elocal,exports,varyings,viewport,clockwise;};
#define SL(P,CW) {P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_BASES0_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_GRIDSIZE0_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_KERNEL_LOCALSIZE0_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTCOMPONENTS_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_INPUTVERTICES_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_OUTPUTCOMPONENTS_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_OUTPUTVERTICES_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_LAYOUT_PATCHCOMPONENTS_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_CONTEXT_TASKID_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_POINTMODE_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_CONTROL_PATCH_SPACING_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_GRIDSIZE0_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_EVALUATIONKERNEL_LOCALSIZE0_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_SYSTEM_PATCH_OUTPUTCOMPONENTS_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_VARYINGSLOTS_WORD,\
 P##_TEMPLATE_SOURCE_VIEWPORT_VIEWPORT_EXTENTX_WORD,CW}
static const struct sequence_layout layouts[]={
    SL(OPENGPU_NATIVE_TESSELLATION_SEQUENCE,0),
    SL(OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE,214),
    SL(OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE,214)};
#undef SL
static int sequence(struct opengpu_native_joint_build_source *s,const struct opengpu_native_joint_range_request *r,
    const struct opengpu_native_joint_catalog_draw *draws)
{
    unsigned n=r->sequence_index;
    const struct opengpu_native_tess_layout *tc=&s->program.tess[0].layout,*te=&s->program.tess[1].layout;
    if(n>=s->d.range_count || r->draw_index>=s->d.draw_count || r->invocation_index>=s->d.invocation_count ||
        r->flags&~7u || (r->patch_count?(!r->patches_per_instance||!r->instances):
            (r->first_ordinal||r->patches_per_instance||r->first_instance||r->instances)) ||
        (uint64_t)r->patches_per_instance*r->instances!=r->patch_count ||
        (uint64_t)r->task_id_base+r->patch_count>(1ULL<<32) ||
        s->invocations[r->invocation_index].draw_index!=r->draw_index ||
        s->invocations[r->invocation_index].patch_vertices!=tc->input_vertices)return -1;
    const struct opengpu_native_joint_catalog_draw *source=draws+r->draw_index;
    if((uint64_t)r->first_ordinal+(uint64_t)r->patches_per_instance*tc->input_vertices>source->count ||
        (uint64_t)r->first_instance+r->instances>source->instances)return -1;
    struct opengpu_native_joint_range *range=s->ranges+n;
    if(range->slice_version)return -1;
    *range=(struct opengpu_native_joint_range){.sequence_index=n,.invocation_index=r->invocation_index,
        .original_draw_index=r->draw_index,.range_ordinal=r->range_ordinal,.flags=r->flags,
        .task_id_base=r->task_id_base,.patch_count=r->patch_count,.slice_version=1,
        .first_ordinal=r->first_ordinal,.patches_per_instance=r->patches_per_instance,
        .first_instance=r->first_instance,.instances=r->instances,
        .layout={tc->input_vertices,tc->input_components,tc->output_vertices,tc->output_components,tc->patch_components}};
    memcpy(range->components,s->program.vertex_components,sizeof(range->components));
    uint32_t *w=s->sequences+(size_t)n*512;const struct sequence_layout *l=layouts+s->d.domain;
    w[0]=0x474e5407u+s->d.domain;w[1]=2048;w[2]=7+s->d.domain;w[16]=r->patch_count;
    w[25]=1;w[26]=w[27]=fp32(1);w[31]=s->frame.tile.sample_mode;
    w[37]=s->frame.frame.words[OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_SCREENHEIGHT_WORD];
    w[38]=s->frame.frame.words[OPENGPU_NATIVE_BINNED_FRAME_FRAME_BINS_PRODUCER_SCREENWIDTH_WORD];w[39]=1;
    w[40]=source->state_handle;w[41]=source->token;w[42]=source->base_instance;
    for(unsigned i=0;i<68;i++){w[43+2*i]=s->program.fragment_components[i].component;w[44+2*i]=s->program.fragment_components[i].valid;}
    w[182]=(uint32_t)s->program.fragment.metadata.flat_mask;w[183]=(uint32_t)(s->program.fragment.metadata.flat_mask>>32);
    w[184]=s->d.context;w[185]=s->d.generation;w[186]=s->d.pass;w[187]=r->first_instance;
    for(unsigned i=0;i<8;i++)w[l->bases+i]=i*16<tc->output_vertices?i*16:0;
    for(unsigned i=0;i<3;i++){w[l->grid+i]=w[l->egrid+i]=1;w[l->local+i]=i?1:tc->output_vertices;w[l->elocal+i]=1;}
    w[l->input_components]=tc->input_components;w[l->input_vertices]=tc->input_vertices;
    w[l->output_components]=tc->output_components;w[l->output_vertices]=tc->output_vertices;
    w[l->patch_components]=tc->patch_components;w[l->task]=r->task_id_base;
    w[l->point]=te->point_mode;w[l->spacing]=te->spacing;if(l->clockwise)w[l->clockwise]=te->clockwise;
    w[l->exports]=te->output_components;w[l->varyings]=s->program.fragment.metadata.varying_slots;
    w[l->viewport]=w[38];w[l->viewport+1]=w[37];w[l->viewport+2]=fp32(1);w[l->viewport+4]=1;
    s->plans[n]=(struct opengpu_native_joint_build_plan){.sequence_index=n,.state_handle=source->state_handle,
        .control_program=s->program.code[1],.evaluation_program=s->program.code[2],
        .control_uniforms=s->program.uniforms[1],.evaluation_uniforms=s->program.uniforms[2]};
    return 0;
}
static int coverage(struct opengpu_native_joint_build_source *s,const struct opengpu_native_joint_catalog_draw *draws)
{
    unsigned cursor=0;
    for(unsigned i=0;i<s->d.invocation_count;i++){
        const struct opengpu_native_joint_invocation *v=s->invocations+i;
        if(v->draw_index>=s->d.draw_count || v->patch_vertices!=s->program.tess[0].layout.input_vertices)return -1;
        for(unsigned j=0;j<i;j++)if(s->invocations[j].invocation_id==v->invocation_id)return -1;
        const struct opengpu_native_joint_catalog_draw *d=draws+v->draw_index;
        uint64_t patches=d->count/v->patch_vertices,total=patches*d->instances,seen=0;
        unsigned ordinal=0;
        do{
            if(cursor>=s->d.range_count)return -1;
            const struct opengpu_native_joint_range *r=s->ranges+cursor;
            if(r->sequence_index!=cursor || r->invocation_index!=i || r->range_ordinal!=ordinal ||
                r->first_ordinal%v->patch_vertices)return -1;
            if(!total){
                /* Real zero-work invocation: one FIRST|LAST GNT/GVS/GND,
                 * not the no-invocation GNQ publication. */
                if(r->flags!=3 || r->patch_count || r->first_ordinal || r->patches_per_instance ||
                    r->first_instance || r->instances)return -1;
            }else{
                uint64_t first=(uint64_t)r->first_instance*patches+r->first_ordinal/v->patch_vertices;
                bool last=seen+r->patch_count==total;
                unsigned flags=(ordinal?4u:1u)|(last?2u:0u);
                if(first!=seen || r->flags!=flags || !r->patch_count ||
                    (r->instances>1&&(r->first_ordinal||r->patches_per_instance!=patches)) ||
                    r->patch_count>total-seen)return -1;
                seen+=r->patch_count;
            }
            cursor++;ordinal++;
        }while(seen<total);
    }
    return cursor==s->d.range_count?0:-1;
}
struct opengpu_native_joint_build_source *opengpu_native_joint_build_source_create(
    const struct opengpu_native_joint_build_request *request)
{
    if(!request){errno=EINVAL;return NULL;}
    const struct opengpu_native_joint_build_request r=*request;
    struct opengpu_native_joint_catalog_draw *draws=NULL;
    struct opengpu_native_joint_range_request *ranges=NULL;
    struct opengpu_native_joint_build_source *s=NULL;
    if(!r.context||!r.source || (r.draw_count&&!r.draws) || (r.invocation_count&&!r.invocations) ||
        (r.range_count&&!r.ranges) || r.invocation_count>r.range_count || (uint64_t)r.draw_count*2048>UINT32_MAX ||
        (uint64_t)r.range_count*2048>UINT32_MAX || 64ULL+64ULL*r.range_count>UINT32_MAX || (uint64_t)r.state_count*512>UINT32_MAX ||
        (r.range_count?(!r.draw_count||!r.invocation_count||!r.state_count):(r.draw_count||r.invocation_count||r.state_count)) ||
        (uint64_t)r.draw_count*18+27>SIZE_MAX/sizeof(struct pipe_resource *))goto fail;
    s=calloc(1,sizeof(*s));if(!s)goto fail;s->context=r.context;
    s->hold_capacity=(size_t)r.draw_count*18+27;
    s->holds=calloc(s->hold_capacity,sizeof(*s->holds));
#define ALLOC(P,N) do{size_t count_=(N);(P)=calloc(count_?count_:1,sizeof(*(P)));if(!(P))goto fail;}while(0)
    ALLOC(draws,r.draw_count);ALLOC(ranges,r.range_count);ALLOC(s->invocations,r.invocation_count);
    ALLOC(s->draws,(size_t)r.draw_count*512);ALLOC(s->sequences,(size_t)r.range_count*512);
    ALLOC(s->states,(size_t)r.state_count*128);ALLOC(s->draw_plans,r.draw_count);
    ALLOC(s->ranges,r.range_count);ALLOC(s->plans,r.range_count);ALLOC(s->state_plans,r.state_count);
#undef ALLOC
    if(!s->holds)goto fail;
    /* All pointer targets are copied before original-object/view callbacks. */
    if(r.draw_count)memcpy(draws,r.draws,r.draw_count*sizeof(*draws));
    if(r.range_count)memcpy(ranges,r.ranges,r.range_count*sizeof(*ranges));
    if(r.invocation_count)memcpy(s->invocations,r.invocations,r.invocation_count*sizeof(*s->invocations));
    if(opengpu_native_joint_source_matches(r.source,r.context))goto fail;
    s->source=opengpu_native_joint_source_retain(r.source);if(!s->source)goto fail;
    if(opengpu_native_joint_source_describe(s->source,&s->program))goto fail;
    struct opengpu_native_joint_runtime runtime;unsigned context;
    if(opengpu_native_joint_source_runtime(r.context,&runtime,&context,&s->driver_profile) ||
        context!=s->program.context_id)goto fail;
    const struct opengpu_native_tess_layout *tc=&s->program.tess[0].layout,*te=&s->program.tess[1].layout;
    if(tc->version!=3||te->version!=3||tc->stage!=4||te->stage!=5||tc->capacity!=128||te->capacity!=128||
        !te->owned_patch_reads||!te->owned_tessellation_info||s->program.tess[1].selection_count||
        !opengpu_native_fragment_metadata_valid_v2(&s->program.fragment.metadata))goto fail;
    unsigned domain;
    switch(te->domain){case PIPE_PRIM_LINES:domain=0;break;case PIPE_PRIM_TRIANGLES:domain=1;break;
        case PIPE_PRIM_QUADS:domain=2;break;default:goto fail;}
    s->d=(struct opengpu_native_joint_build_description){.version=1,.context=context,.pass=r.pass,.generation=r.generation,
        .domain=domain,.draw_count=r.draw_count,.invocation_count=r.invocation_count,.range_count=r.range_count,
        .state_count=r.state_count,.job=s->job,.draws=r.draw_count?s->draws:NULL,.sequences=r.range_count?s->sequences:NULL,.states=r.state_count?s->states:NULL,
        .draw_plans=r.draw_count?s->draw_plans:NULL,.invocations=r.invocation_count?s->invocations:NULL,.ranges=r.range_count?s->ranges:NULL,.plans=r.range_count?s->plans:NULL,
        .state_plans=r.state_count?s->state_plans:NULL,.frame=&s->frame};
    if(frame(s,&r.frame,&runtime))goto fail;
    for(unsigned i=0;i<r.state_count;i++)state(s,i,runtime.engines);
    for(unsigned i=0;i<r.draw_count;i++)if(draw(s,draws+i,i))goto fail;
    for(unsigned i=0;i<r.range_count;i++)if(sequence(s,ranges+i,draws))goto fail;
    if(coverage(s,draws))goto fail;
    envelope(s->job,0x474e5104u+domain,512);s->job[2]=2;s->job[17]=r.range_count;s->job[20]=r.range_count?2048:0;
    s->job[24]=context;s->job[25]=r.generation;s->job[26]=r.pass;s->job[27]=r.range_count?2:0;
    s->job[31]=r.range_count;s->job[32]=r.range_count?64+64*r.range_count:0;s->job[33]=r.range_count?64:0;s->job[34]=r.draw_count;s->job[35]=r.invocation_count;
    free(draws);free(ranges);return s;
fail:
    free(draws);free(ranges);opengpu_native_joint_build_source_destroy(s);if(!errno)errno=EINVAL;return NULL;
}

int opengpu_native_joint_build_source_submit(const struct opengpu_native_joint_build_source *s,
    unsigned in_syncobj,unsigned out_syncobj,uint64_t *fence_sequence)
{
    if(!s || !fence_sequence){errno=EINVAL;return -1;}
    if(!s->driver_profile){errno=ENOTSUP;return -1;}
    const struct opengpu_native_joint_build_description *d=&s->d;
    struct opengpu_protected_tessellation_joint_plan *plans=NULL;
    struct opengpu_protected_tessellation_state_plan *states=NULL;
    struct opengpu_tessellation_vertex_outer_invocation_v2 *invocations=NULL;
    struct opengpu_tessellation_vertex_outer_range_v2 *ranges=NULL;
    int ret=-1;
    if(d->range_count){
        plans=calloc(d->range_count,sizeof(*plans));
        ranges=calloc(d->range_count,sizeof(*ranges));
        states=calloc(d->state_count,sizeof(*states));
        invocations=calloc(d->invocation_count,sizeof(*invocations));
        if(!plans || !ranges || !states || !invocations){errno=ENOMEM;goto done;}
    }
    for(unsigned i=0;i<d->range_count;i++){
        const struct opengpu_native_joint_build_plan *p=&d->plans[i];
        const struct opengpu_native_joint_range *r=&d->ranges[i];
        plans[i]=(struct opengpu_protected_tessellation_joint_plan){
            .sequence_index=p->sequence_index,.state_handle=p->state_handle,
            .control_program=p->control_program,.evaluation_program=p->evaluation_program,
            .control_uniforms=p->control_uniforms,.evaluation_uniforms=p->evaluation_uniforms,
        };
        ranges[i]=(struct opengpu_tessellation_vertex_outer_range_v2){
            .sequence_index=r->sequence_index,.invocation_index=r->invocation_index,
            .original_draw_index=r->original_draw_index,.range_ordinal=r->range_ordinal,
            .flags=r->flags,.task_id_base=r->task_id_base,.patch_count=r->patch_count,
            .slice={r->slice_version,r->first_ordinal,r->patches_per_instance,r->first_instance,r->instances},
            .layout={r->layout.input_vertices,r->layout.input_components,r->layout.output_vertices,
                r->layout.output_components,r->layout.patch_components},
        };
        for(unsigned j=0;j<64;j++){
            ranges[i].components[j].kind=r->components[j].kind;
            ranges[i].components[j].component=r->components[j].component;
        }
    }
    for(unsigned i=0;i<d->invocation_count;i++){
        invocations[i]=(struct opengpu_tessellation_vertex_outer_invocation_v2){
            .invocation_id=d->invocations[i].invocation_id,
            .original_draw_index=d->invocations[i].draw_index,
            .input_patch_vertices=d->invocations[i].patch_vertices,
        };
    }
    for(unsigned i=0;i<d->state_count;i++){
        states[i]=(struct opengpu_protected_tessellation_state_plan){
            .state_handle=d->state_plans[i].state_handle,.fragment_program=d->state_plans[i].fragment_program,
            .fragment_uniforms=d->state_plans[i].fragment_uniforms,
        };
    }
    const struct drm_opengpu_submit_tessellation_joint submit={
        .version=1,.context_id=d->context,.in_syncobj=in_syncobj,.out_syncobj=out_syncobj,
        .draw_count=d->draw_count,.invocation_count=d->invocation_count,
        .range_count=d->range_count,.state_count=d->state_count,.resource_count=d->frame->resource_count,
        .job=(uintptr_t)d->job,.frame=(uintptr_t)&d->frame->frame,
        .sequences=(uintptr_t)d->sequences,.sequences_bytes=(uint64_t)d->range_count*2048,
        .states=(uintptr_t)d->states,.draws=(uintptr_t)d->draws,.draw_plans=(uintptr_t)d->draw_plans,
        .invocations=(uintptr_t)invocations,.ranges=(uintptr_t)ranges,
        .plans=(uintptr_t)plans,.state_plans=(uintptr_t)states,.resources=(uintptr_t)d->frame->resources,
    };
    int fd=pipe_opengpu_screen_fd(opengpu_screen_gpu(s->context->screen));
    ret=opengpu_tessellation_joint_submit(fd,&submit,fence_sequence);
done:
    free(plans);free(ranges);free(states);free(invocations);
    return ret;
}
