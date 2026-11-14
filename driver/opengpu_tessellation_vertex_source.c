// SPDX-License-Identifier: GPL-2.0
#include <linux/errno.h>
#include <linux/slab.h>
#include <linux/vmalloc.h>
#include "opengpu_tessellation_vertex_source.h"
#include "opengpu_tessellation_vertex_source_abi.h"
#include "opengpu_native_input_snapshot.h"
#include "opengpu_native_uniform_snapshot.h"
#include "opengpu_private_snapshot.h"

static u32 gvs_word(const void *raw,u32 n)
{
    const u8 *b=(const u8 *)raw+4u*n;
    return (u32)b[0]|(u32)b[1]<<8|(u32)b[2]<<16|(u32)b[3]<<24;
}
static void gvs_put(void *raw,u32 n,u32 value)
{
    u8 *b=(u8 *)raw+4u*n;
    for(u32 i=0;i<4;i++)b[i]=(u8)(value>>(8u*i));
}
static u64 gvs_wide(const void *raw,u32 n)
{return gvs_word(raw,n)|((u64)gvs_word(raw,n+1)<<32);}
static bool gvs_rules(const void *raw,const unsigned char *rules,u32 count)
{
    for(u32 n=0;n<count;n++) {
        u32 v=gvs_word(raw,n),bits=rules[n]&127u;
        if(!bits) {if(v)return false;}
        else if(bits<32) {
            u32 mask=(1u<<bits)-1u,expected=v&mask;
            if((rules[n]&128u) && (v&(1u<<(bits-1u))))expected|=~mask;
            if(v!=expected)return false;
        }
    }
    return true;
}
static bool gvs_layout(const struct opengpu_tessellation_patch_layout *l)
{
    return l->input_vertices && l->input_vertices<=32 && l->output_vertices && l->output_vertices<=32 &&
        l->input_components<=64 && l->output_components<=64 && l->patch_components<=120 &&
        l->input_vertices*l->input_components<=2048 &&
        l->output_vertices*l->output_components+l->patch_components<=2048;
}
static bool gvs_ranges(u32 count,u32 task,const struct opengpu_tessellation_vertex_slice *s,
    const struct opengpu_tessellation_patch_layout *l)
{
    if(s->version!=1)return false;
    if(!count)return !s->first_ordinal && !s->patches_per_instance && !s->first_instance && !s->instances;
    return s->patches_per_instance && s->instances &&
        (u64)s->patches_per_instance*s->instances==count &&
        (u64)task+count-1<=0xffffffffu &&
        (u64)s->first_ordinal+(u64)s->patches_per_instance*l->input_vertices<=(1ULL<<32) &&
        (u64)s->first_instance+s->instances<=(1ULL<<32);
}
static bool gvs_component(u32 kind,u32 component,u32 abi)
{return kind==0?component<4:kind==1?component<64:kind==2?abi==2 && !component:false;}
bool opengpu_tessellation_vertex_source_canonical(const void *raw,size_t bytes)
{
    static const u32 header[]={0x47565301,512,1,16,16,16,64,32,16,8,2};
    struct opengpu_tessellation_patch_layout l;
    struct opengpu_tessellation_vertex_slice slice;
    u32 abi;
    u64 draw_end,code_end;
    if(!raw || bytes!=512 || !gvs_rules(raw,opengpu_gvs_word_rules,128))return false;
    for(u32 i=0;i<11;i++)if(gvs_word(raw,i)!=header[i])return false;
    l=(struct opengpu_tessellation_patch_layout){gvs_word(raw,40),gvs_word(raw,41),gvs_word(raw,42),gvs_word(raw,43),gvs_word(raw,44)};
    slice=(struct opengpu_tessellation_vertex_slice){gvs_word(raw,32),gvs_word(raw,33),gvs_word(raw,34),gvs_word(raw,35),gvs_word(raw,36)};
    abi=gvs_word(raw,45);draw_end=gvs_wide(raw,25);code_end=gvs_wide(raw,28);
    if(!gvs_layout(&l) || !gvs_ranges(gvs_word(raw,22),gvs_word(raw,21),&slice,&l) || (abi!=1 && abi!=2) ||
        (gvs_word(raw,24)&63u) || draw_end!=(u64)gvs_word(raw,24)+2048 || draw_end>(1ULL<<32) ||
        (gvs_word(raw,27)&3u) || (code_end&3u) || code_end<=gvs_word(raw,27) || code_end>(1ULL<<32) ||
        code_end-gvs_word(raw,27)>2048 || (gvs_word(raw,30)&3u) ||
        gvs_word(raw,30)<gvs_word(raw,27) || gvs_word(raw,30)>=code_end)return false;
    for(u32 i=0;i<64;i++) {
        u32 map=gvs_word(raw,64+i);
        if(i>=l.input_components) {if(map)return false;}
        else if(!gvs_component(map&3u,map>>2,abi))return false;
    }
    return true;
}
bool opengpu_tessellation_vertex_sequence_canonical(const void *raw,size_t bytes,u32 domain)
{
    static const u32 magic[]={0x474e5407,0x474e5408,0x474e5409};
    const unsigned char *rules;
    u64 end;
    if(!raw || bytes!=2048 || domain>2)return false;
    rules=domain==0?opengpu_gvs_isoline_word_rules:domain==1?opengpu_gvs_triangle_word_rules:opengpu_gvs_quad_word_rules;
    end=gvs_wide(raw,510);
    /* New source mode exclusively owns VS->patch and dynamic TES reads.
     * Neither old DMA patch input nor static selected-patch transport can be
     * present beside it. Keep the complete checked GUR3 tail at 499..508. */
    for(u32 i=17;i<=23;i++)if(gvs_word(raw,i))return false;
    for(u32 i=domain==0?237u:238u;i<=(domain==0?482u:483u);i++)if(gvs_word(raw,i))return false;
    if(gvs_word(raw,domain==0?491u:492u))return false;
    return gvs_word(raw,0)==magic[domain] && gvs_word(raw,1)==2048 && gvs_word(raw,2)==7+domain &&
        gvs_rules(raw,rules,512) && !(gvs_word(raw,509)&63u) && end==(u64)gvs_word(raw,509)+512 && end<=(1ULL<<32);
}

enum { GVS_ORIGINALS=35 };
struct gvs_original {u32 handle;struct opengpu_native_frame_backing backing;};
struct gvs_capture {
    struct opengpu_tessellation_vertex_source source;
    u8 draw[2048],root[512];
    struct opengpu_native_job_copy callbacks;
    struct gvs_original original[GVS_ORIGINALS];
    u32 original_count;
};
static bool gvs_empty(const struct drm_opengpu_compute_uniform_view *v)
{return !v->handle && !v->offset && !v->bytes;}
static int gvs_resolve(void *opaque,u32 handle,struct opengpu_native_frame_backing *backing)
{
    struct gvs_capture *c=opaque;
    struct opengpu_native_frame_backing b={0};
    int ret;
    for(u32 i=0;i<c->original_count;i++)if(c->original[i].handle==handle) {*backing=c->original[i].backing;return 0;}
    if(!handle || c->original_count==GVS_ORIGINALS)return -EINVAL;
    ret=c->callbacks.resolve(c->callbacks.context,handle,&b);
    if(ret)return ret;
    if(!b.bytes || ((b.physical|b.bytes)&4095u) || b.bytes>(1ULL<<32) || b.physical>(1ULL<<32)-b.bytes)return -EINVAL;
    c->original[c->original_count++]=(struct gvs_original){handle,b};*backing=b;
    return 0;
}
static int gvs_span(struct gvs_capture *c,const struct drm_opengpu_compute_uniform_view *v,u64 *first,u64 *end)
{
    struct opengpu_native_frame_backing b;
    int ret;
    if(!v->handle || !v->bytes || v->offset>~(u64)0-v->bytes)return -EINVAL;
    ret=gvs_resolve(c,v->handle,&b);
    if(ret)return ret;
    if(v->offset+v->bytes>b.bytes)return -EINVAL;
    *first=(b.physical+v->offset)&~63ULL;
    *end=(b.physical+v->offset+v->bytes+63u)&~63ULL;
    return 0;
}
static int gvs_copy(void *opaque,const struct drm_opengpu_compute_uniform_view *v,void *out)
{
    struct gvs_capture *c=opaque;
    return c->callbacks.copy(c->callbacks.context,v,out);
}
static int gvs_alias(struct gvs_capture *c)
{
    const struct opengpu_tessellation_vertex_plan *p=&c->source.plan;
    struct drm_opengpu_compute_uniform_view code={.handle=p->program.handle,.offset=p->program.offset,.bytes=p->program.bytes};
    const struct drm_opengpu_compute_uniform_view *views[31];
    u64 scratch,limit,first,end;
    u32 count=0;
    int ret=gvs_span(c,&p->scratch,&scratch,&limit);
    if(ret)return ret;
    views[count++]=&code;
    for(u32 i=0;i<12;i++)views[count++]=&p->uniforms.blocks[i];
    views[count++]=&p->uniforms.defaults;
    for(u32 i=0;i<16;i++)views[count++]=&p->bindings[i];
    views[count++]=&p->indices;
    for(u32 i=0;i<count;i++) {
        if(gvs_empty(views[i]))continue;
        ret=gvs_span(c,views[i],&first,&end);
        if(ret)return ret;
        if(first<limit && scratch<end)return -EINVAL;
    }
    return 0;
}
static bool gvs_draw(struct gvs_capture *c)
{
    const struct opengpu_tessellation_vertex_source *s=&c->source;
    static const u32 header[]={0x474e4401,2048,1,16,16,16,4,4};
    const void *d=c->draw;
    u32 abi=gvs_word(d,346),varyings=gvs_word(d,345);
    if(!gvs_rules(d,opengpu_gvs_draw_word_rules,512))return false;
    for(u32 i=0;i<8;i++)if(gvs_word(d,i)!=header[i])return false;
    if(gvs_word(d,314)!=s->context || gvs_word(d,315)!=s->generation || gvs_word(d,316)!=s->pass ||
        gvs_word(d,35)>16 || varyings>16 || (abi!=1 && abi!=2) ||
        gvs_word(d,342) || gvs_word(d,506) || gvs_word(d,507))return false;
    if((u64)s->slice.first_ordinal+(u64)s->slice.patches_per_instance*s->layout.input_vertices>gvs_word(d,311) ||
        (u64)s->slice.first_instance+s->slice.instances>gvs_word(d,325))return false;
    for(u32 i=0;i<64;i++) {
        const struct opengpu_tessellation_vertex_component *m=&s->components[i];
        if(i>=s->layout.input_components) {if(m->kind || m->component)return false;continue;}
        if(!gvs_component(m->kind,m->component,abi))return false;
        if(m->kind==1 && (m->component>=4u*varyings || !(gvs_word(d,343+m->component/32)&(1u<<(m->component%32)))))return false;
    }
    return true;
}
static bool gvs_unsupported(struct gvs_capture *c)
{
    const struct opengpu_tessellation_vertex_plan *p=&c->source.plan;
    /* Preserve provenance in the typed plan, but never parse indirect commands
     * on the CPU or turn sampler/texture metadata into raw-address authority. */
    for(u32 word=328;word<=335;word++)if(gvs_word(c->draw,word))return true;
    for(u32 word=480;word<=483;word++)if(gvs_word(c->draw,word))return true;
    return !gvs_empty(&p->indirect) || !gvs_empty(&p->sampler) || !gvs_empty(&p->texture_root) ||
        gvs_word(c->draw,496) || gvs_word(c->draw,497) ||
        gvs_word(c->draw,498) || gvs_word(c->draw,499) || gvs_word(c->draw,500) || gvs_word(c->draw,501) ||
        gvs_word(c->draw,502) || gvs_word(c->draw,503) || gvs_word(c->draw,504) || gvs_word(c->draw,505);
}
int opengpu_tessellation_vertex_source_append(struct opengpu_private_snapshot *owner,
    const struct opengpu_tessellation_vertex_source *source,const struct opengpu_native_job_copy *copy,u32 *output)
{
    struct gvs_capture *c;
    struct drm_opengpu_native_draw_plan plan={0};
    struct drm_opengpu_native_uniform_stage stages[2]={0};
    struct opengpu_native_uniform_roots uniforms;
    struct opengpu_native_program_profile program={0};
    struct opengpu_native_program_snapshot code;
    u32 draw,root;
    int ret;
    if(!owner || !source || !copy || !copy->copy || !copy->resolve || !output)return -EINVAL;
    /* Unavailable fails before source lookup/copy/owner append. This is not a
     * production probe and callers cannot manufacture hardware availability. */
    if(!source->profile.available)return -EOPNOTSUPP;
    if(source->profile.version!=1 || source->profile.compute_units!=2 || source->profile.lanes!=16 ||
        source->profile.warps!=8 || !source->profile.patch_zero_initialized || !source->draw || source->draw_bytes!=2048 ||
        !gvs_layout(&source->layout) || !gvs_ranges(source->patch_count,source->task_id_base,&source->slice,&source->layout))return -EINVAL;
    ret=opengpu_private_snapshot_build_context(owner,source->context);
    if(ret)return ret;
    c=kvzalloc(sizeof(*c),GFP_KERNEL);
    if(!c)return -ENOMEM;
    c->source=*source;c->callbacks=*copy;
    memcpy(c->draw,source->draw,2048);
    if(!gvs_draw(c)) {ret=-EINVAL;goto done;}
    if(gvs_unsupported(c)) {ret=-EOPNOTSUPP;goto done;}
    ret=gvs_alias(c);
    if(ret)goto done;
    /* This ES3.2 source contract selects unsupported-for-PATCHES/query-false
     * restart policy. Global enable must neither reject a legal PATCHES draw
     * nor send restart tokens to this source. Other primitive paths keep
     * their existing policy; a future query-true implementation is distinct. */
    gvs_put(c->draw,326,0);
    plan.vertex_program=c->source.plan.program;plan.vertex_uniforms=c->source.plan.uniforms;
    memcpy(plan.bindings,c->source.plan.bindings,sizeof(plan.bindings));
    plan.indices=c->source.plan.indices;plan.vertex_scratch=c->source.plan.scratch;
    stages[0]=c->source.plan.uniforms;
    ret=opengpu_native_uniform_snapshot_append(owner,stages,c->source.context,c->source.pass,c->source.generation,gvs_copy,c,&uniforms);
    if(ret)goto done;
    program=(struct opengpu_native_program_profile){.staging_bytes=c->source.plan.scratch.bytes,.capacity=128,.stage=0,
        .version=gvs_word(c->draw,346),.attributes=gvs_word(c->draw,35),.varyings=gvs_word(c->draw,345),
        .scalar_fpu=c->source.profile.scalar_fpu,.owned_uniforms=uniforms.present[0]};
    ret=opengpu_native_program_snapshot_append(owner,&c->source.plan.program,&program,gvs_copy,c,&code);
    if(ret)goto done;
    if(uniforms.present[0])gvs_put(c->draw,506,1);
    ret=opengpu_private_snapshot_append(owner,c->draw,2048,&draw);
    if(!ret)ret=opengpu_native_input_snapshot_patch(owner,draw,0,c->draw,&plan,128,gvs_resolve,c);
    if(!ret)ret=opengpu_private_snapshot_relocate(owner,draw,342*4,code.segment,code.entry_offset,OPENGPU_PRIVATE_SNAPSHOT_VA32);
    if(!ret && uniforms.present[0])ret=opengpu_private_snapshot_relocate(owner,draw,507*4,uniforms.segment,uniforms.root_offset[0],OPENGPU_PRIVATE_SNAPSHOT_VA32);
    if(ret)goto done;
    {
        const struct opengpu_tessellation_vertex_source *s=&c->source;
        const u32 header[]={0x47565301,512,1,16,16,16,64,32,16,8,2};
        for(u32 i=0;i<11;i++)gvs_put(c->root,i,header[i]);
        gvs_put(c->root,16,s->context);gvs_put(c->root,17,s->pass);gvs_put(c->root,18,s->generation);
        gvs_put(c->root,19,s->sequence_index);gvs_put(c->root,20,s->draw_index);gvs_put(c->root,21,s->task_id_base);gvs_put(c->root,22,s->patch_count);
        gvs_put(c->root,32,1);gvs_put(c->root,33,s->slice.first_ordinal);gvs_put(c->root,34,s->slice.patches_per_instance);
        gvs_put(c->root,35,s->slice.first_instance);gvs_put(c->root,36,s->slice.instances);
        gvs_put(c->root,40,s->layout.input_vertices);gvs_put(c->root,41,s->layout.input_components);
        gvs_put(c->root,42,s->layout.output_vertices);gvs_put(c->root,43,s->layout.output_components);gvs_put(c->root,44,s->layout.patch_components);
        gvs_put(c->root,45,program.version);
        for(u32 i=0;i<s->layout.input_components;i++)gvs_put(c->root,64+i,s->components[i].kind|(s->components[i].component<<2));
    }
    ret=opengpu_private_snapshot_append(owner,c->root,512,&root);
    if(!ret)ret=opengpu_private_snapshot_relocate(owner,root,24*4,draw,0,OPENGPU_PRIVATE_SNAPSHOT_VA32);
    if(!ret)ret=opengpu_private_snapshot_relocate(owner,root,25*4,draw,2048,OPENGPU_PRIVATE_SNAPSHOT_LIMIT33);
    if(!ret)ret=opengpu_private_snapshot_relocate(owner,root,27*4,code.segment,0,OPENGPU_PRIVATE_SNAPSHOT_VA32);
    if(!ret)ret=opengpu_private_snapshot_relocate(owner,root,28*4,code.segment,c->source.plan.program.bytes,OPENGPU_PRIVATE_SNAPSHOT_LIMIT33);
    if(!ret)ret=opengpu_private_snapshot_relocate(owner,root,30*4,code.segment,code.entry_offset,OPENGPU_PRIVATE_SNAPSHOT_VA32);
    if(!ret)*output=root;
done:
    kvfree(c);
    return ret;
}
