/* SPDX-License-Identifier: MIT */
#include "opengpu_native_five_internal.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include "tgsi/tgsi_parse.h"
#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
struct opengpu_native_five_program {unsigned refs;struct pipe_context *pipe;struct pipe_opengpu_screen *screen;struct opengpu_native_five_description d;struct pipe_opengpu_resource *code[5];};
struct opengpu_native_five_source {struct opengpu_native_five_program *program;struct opengpu_native_five_bindings *bindings;struct opengpu_native_five_description d;};
static unsigned bits(unsigned x){unsigned n=0;for(;x;x>>=1)n+=x&1;return n;}
void opengpu_native_five_program_destroy(struct opengpu_native_five_program *p){
    if(!p||--p->refs)return;
    for(unsigned s=0;s<5;s++)if(p->code[s])pipe_opengpu_resource_destroy(p->screen,p->code[s]);
    free(p);
}
static int declared(const struct opengpu_native_tess_input_map *map,unsigned semantic,unsigned index,unsigned c){
    unsigned found=0;
    for(unsigned i=0;i<map->count;i++){
        const struct opengpu_native_tess_input_span *s=map->spans+i;
        if(s->semantic==semantic&&index>=s->index&&index-s->index<s->slots&&(s->mask&(1u<<c)))found++;
    }return found==1;
}
static int masks_from_tokens(const void *tokens,unsigned *v,unsigned *colors){
    struct tgsi_parse_context p;unsigned varying=0,color=0;
    if(tgsi_parse_init(&p,tokens)!=TGSI_PARSE_OK)return -1;
    while(!tgsi_parse_end_of_tokens(&p)){
        tgsi_parse_token(&p);if(p.FullToken.Token.Type!=TGSI_TOKEN_TYPE_DECLARATION)continue;
        const struct tgsi_full_declaration *d=&p.FullToken.FullDeclaration;if(!d->Declaration.Semantic)continue;
        unsigned end=d->Semantic.Index+d->Range.Last-d->Range.First+1;
        if(d->Declaration.File==TGSI_FILE_INPUT&&d->Semantic.Name==TGSI_SEMANTIC_GENERIC){if(end>16)goto bad;if(varying<end)varying=end;}
        if(d->Declaration.File==TGSI_FILE_OUTPUT&&d->Semantic.Name==TGSI_SEMANTIC_COLOR){if(end>4)goto bad;if(color<end)color=end;}
    }tgsi_parse_free(&p);*v=varying;*colors=color;return 0;
bad:tgsi_parse_free(&p);return -1;
}
struct opengpu_native_five_program *opengpu_native_five_compile_options(struct pipe_context *pipe,const struct opengpu_native_five_input *input,unsigned vertices,
    const struct opengpu_native_five_compile_options *options){
    if(!pipe||!input||!vertices||vertices>32||!options){errno=EINVAL;return NULL;}
    const struct opengpu_native_five_compile_options selected=*options;
    if(selected.version!=1||selected.geometry_pixel_mask_transport>1||selected.reserved[0]||selected.reserved[1]){errno=EINVAL;return NULL;}
    /* Freeze all original words and compiler metadata BEFORE any GEM callback.
     * No later stage is relinked with an unrelated VS or placeholder FS. */
    struct opengpu_native_five_input captured=*input;
    for(unsigned s=0;s<8;s++){
        const struct opengpu_native_tess_input_map *m=captured.maps+s;
        if(m->version!=1||m->count>64){errno=EINVAL;return NULL;}
        for(unsigned i=0;i<m->count;i++){
            const struct opengpu_native_tess_input_span *span=m->spans+i;
            unsigned arrayed=(s==1||s==2||s==3||s==5)&&
                (span->semantic==TGSI_SEMANTIC_POSITION||span->semantic==TGSI_SEMANTIC_GENERIC);
            if(span->per_vertex!=arrayed){errno=EINVAL;return NULL;}
        }
        for(unsigned i=m->count;i<64;i++){
            const struct opengpu_native_tess_input_span zero={0};
            if(memcmp(m->spans+i,&zero,sizeof(zero))){errno=EINVAL;return NULL;}
        }
    }
    void *tokens[5]={0};struct opengpu_native_five_program *p=NULL;
    for(unsigned s=0;s<5;s++){
        struct tgsi_header h;
        if(!input->tokens[s]||input->words[s]<3||input->words[s]>4098)goto fail;
        memcpy(&h,input->tokens[s],4);if(h.HeaderSize!=2||h.BodySize!=input->words[s]-2)goto fail;
        tokens[s]=malloc(input->words[s]*4);if(!tokens[s])goto fail;
        memcpy(tokens[s],input->tokens[s],input->words[s]*4);captured.tokens[s]=tokens[s];
    }
    p=calloc(1,sizeof(*p));if(!p)goto fail;p->refs=1;p->pipe=pipe;p->screen=opengpu_screen_gpu(pipe->screen);
    struct opengpu_native_sample_runtime runtime;
    if(opengpu_native_sample_context_runtime(pipe,&runtime,&p->d.context_id)||runtime.limits.capacity!=128)goto fail;
    struct opengpu_native_tess_packing a,b,c;
    if(opengpu_native_tess_pack(captured.maps+1,&a)||opengpu_native_tess_pack(captured.maps+2,&b)||
        opengpu_native_tess_pack(captured.maps+4,&c)||!c.position)goto fail;
    if(opengpu_native_vertex_tess_compile(tokens[0],128,&p->d.vertex_layout,&p->d.vertex))goto fail;
    struct opengpu_native_tess_layout layout[2];
    if(opengpu_native_tess_linked_reflect(tokens[1],captured.words[1],4,128,vertices,&a,&b,layout)||
       opengpu_native_tess_geometry_reflect(tokens[2],captured.words[2],128,vertices,&b,&c,layout+1))goto fail;
    layout[1].output_vertices=layout[0].output_vertices;layout[1].owned_patch_reads=1;
    layout[1].tes_patch_layout=(struct opengpu_native_tess_patch_layout){vertices,a.vertex_components,
        layout[0].output_vertices,b.vertex_components,b.patch_components};
    if(opengpu_native_tess_linked_emit(tokens[1],captured.words[1],layout,captured.maps+2,&a,&b,p->d.tess)||
       opengpu_native_tess_geometry_emit(tokens[2],captured.words[2],layout+1,captured.maps+3,&b,&c,p->d.tess+1))goto fail;
    if(opengpu_native_geometry_compile(tokens[3],captured.words[3],captured.maps+5,captured.maps+6,&p->d.geometry))goto fail;
    /* A tessellator produces the ordinary domain topology, never adjacency.
     * Point mode emits points; isolines emit lines; triangles/quads triangles. */
    unsigned primitive_vertices=layout[1].point_mode?1u:layout[1].domain==PIPE_PRIM_LINES?2u:3u;
    if(p->d.geometry.layout.input_vertices!=primitive_vertices)goto fail;
    for(unsigned i=0;i<p->d.geometry.layout.input_components;i++){
        unsigned raw=p->d.geometry.layout.input_sources[i],component;
        if(raw<4){if(!c.position||!declared(captured.maps+4,TGSI_SEMANTIC_POSITION,0,raw))goto fail;component=raw;}
        else{
            unsigned slot=(raw-4)/4,ch=(raw-4)%4;
            if(slot>=16||!(c.vertex[slot].mask&(1u<<ch))||
               !declared(captured.maps+4,TGSI_SEMANTIC_GENERIC,slot,ch)||!declared(captured.maps+5,TGSI_SEMANTIC_GENERIC,slot,ch))goto fail;
            component=c.vertex[slot].base+bits(c.vertex[slot].mask&((1u<<ch)-1));
        }
        if(!(p->d.tess[1].output_mask&(UINT64_C(1)<<component)))goto fail;
        p->d.evaluation_components[i]=(struct opengpu_native_joint_mapping){1,component};
    }
    /* Validate original FS packets before inspecting their GENERIC semantics.
     * Final re-emission uses GS->FS prefix, independent of VS->TC. */
    struct opengpu_native_fragment_config_v3 fs={128,16,4,1,!selected.geometry_pixel_mask_transport,selected.geometry_pixel_mask_transport};
    if(opengpu_native_fragment_uniform_compile_v3(tokens[4],captured.words[4],&captured.fragment,&fs,&p->d.fragment)||
        masks_from_tokens(tokens[4],&fs.varying_slots,&fs.color_slots)||
        opengpu_native_fragment_uniform_compile_v3(tokens[4],captured.words[4],&captured.fragment,&fs,&p->d.fragment))goto fail;
    /* Original VS semantic word -> actual packed TCS input component. */
    for(unsigned i=0;i<4*a.position;i++){
        if(!declared(captured.maps,TGSI_SEMANTIC_POSITION,0,i))goto fail;
        p->d.vertex_components[i]=(struct opengpu_native_joint_component){0,i};
    }
    for(unsigned i=0;i<16;i++)for(unsigned ch=0;ch<4;ch++)if(a.vertex[i].mask&(1u<<ch)){
        if(!declared(captured.maps,TGSI_SEMANTIC_GENERIC,i,ch)||!(p->d.vertex.varying_mask&(UINT64_C(1)<<(4*i+ch))))goto fail;
        unsigned dest=a.vertex[i].base+bits(a.vertex[i].mask&((1u<<ch)-1));
        p->d.vertex_components[dest]=(struct opengpu_native_joint_component){1,4*i+ch};
    }
    /* Actual GS export -> FS slot/component, independently of TES->GS. */
    for(unsigned i=0;i<4;i++){
        if(!p->d.geometry.layout.output_sources[i].valid)goto fail;
        p->d.fragment_components[i]=(struct opengpu_native_joint_mapping){1,p->d.geometry.layout.output_sources[i].source};
    }
    for(unsigned i=0;i<16;i++)for(unsigned ch=0;ch<4;ch++){
        uint64_t bit=UINT64_C(1)<<(4*i+ch);
        if(!(p->d.fragment.metadata.varying_mask&bit))continue;
        if(!declared(captured.maps+6,TGSI_SEMANTIC_GENERIC,i,ch)||!declared(captured.maps+7,TGSI_SEMANTIC_GENERIC,i,ch))goto fail;
        unsigned raw=4+4*i+ch;
        if(!p->d.geometry.layout.output_sources[raw].valid)goto fail;
        p->d.fragment_components[raw]=(struct opengpu_native_joint_mapping){1,p->d.geometry.layout.output_sources[raw].source};
    }
    for(unsigned s=0;s<5;s++){
        const uint32_t *code=s==0?p->d.vertex.code:s==4?p->d.fragment.code:s==3?p->d.geometry.code:p->d.tess[s-1].code;
        unsigned words=s==0?p->d.vertex.words:s==4?p->d.fragment.words:s==3?p->d.geometry.words:p->d.tess[s-1].words;
        p->code[s]=pipe_opengpu_resource_create(p->screen,words*4);if(!p->code[s])goto fail;
        void *map=pipe_opengpu_resource_map(p->code[s]);if(!map)goto fail;memcpy(map,code,words*4);
        p->d.code[s].handle=pipe_opengpu_resource_handle(p->code[s]);p->d.code[s].bytes=words*4;
    }
    for(unsigned s=0;s<5;s++)free(tokens[s]);
    return p;
fail:{int error=errno?errno:ENOTSUP;for(unsigned s=0;s<5;s++)free(tokens[s]);opengpu_native_five_program_destroy(p);errno=error;return NULL;}
}
struct opengpu_native_five_program *opengpu_native_five_compile(struct pipe_context *pipe,const struct opengpu_native_five_input *input,unsigned vertices){
    const struct opengpu_native_five_compile_options options={1,0,{0,0}};
    return opengpu_native_five_compile_options(pipe,input,vertices,&options);
}
struct opengpu_native_five_source *opengpu_native_five_source_create(struct opengpu_native_five_program *p,struct opengpu_native_five_constants *constants){
    if(!p||!constants){errno=EINVAL;return NULL;}
    if(p->refs==UINT_MAX){errno=EOVERFLOW;return NULL;}
    struct opengpu_native_five_source *s=calloc(1,sizeof(*s));if(!s)return NULL;s->d=p->d;
    unsigned need[5][13];memcpy(need[0],p->d.vertex.constant_bytes,sizeof(need[0]));
    memcpy(need[1],p->d.tess[0].constant_bytes,sizeof(need[1]));memcpy(need[2],p->d.tess[1].constant_bytes,sizeof(need[2]));
    memcpy(need[3],p->d.geometry.constant_bytes,sizeof(need[3]));
    memcpy(need[4],p->d.fragment.metadata.constant_bytes,sizeof(need[4]));
    s->bindings=opengpu_native_five_capture(constants,p->pipe,need,s->d.uniforms);
    if(!s->bindings){free(s);return NULL;}s->program=p;p->refs++;return s;
}
void opengpu_native_five_source_destroy(struct opengpu_native_five_source *s){if(!s)return;opengpu_native_five_release(s->bindings);opengpu_native_five_program_destroy(s->program);free(s);}
int opengpu_native_five_source_describe(const struct opengpu_native_five_source *s,struct opengpu_native_five_description *out){if(!s||!out){errno=EINVAL;return -1;}*out=s->d;return 0;}

struct opengpu_native_five_program *opengpu_native_five_program_create(struct pipe_context *pipe,const struct opengpu_native_five_link *link,unsigned vertices){
    struct opengpu_native_five_input input={0};
    if(opengpu_native_five_link_capture(link,&input))return NULL;
    return opengpu_native_five_compile(pipe,&input,vertices);
}

struct opengpu_native_five_program *opengpu_native_five_program_create_options(struct pipe_context *pipe,
    const struct opengpu_native_five_link *link,unsigned vertices,const struct opengpu_native_five_compile_options *options){
    struct opengpu_native_five_input input={0};
    if(opengpu_native_five_link_capture(link,&input))return NULL;
    return opengpu_native_five_compile_options(pipe,&input,vertices,options);
}
