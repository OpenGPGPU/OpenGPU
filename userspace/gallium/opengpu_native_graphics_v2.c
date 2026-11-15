/* SPDX-License-Identifier: MIT */
#include "opengpu_native_graphics_v2.h"
#include "opengpu_native_graphics_internal.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include "tgsi/tgsi_parse.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
struct opengpu_native_graphics_program_v2 {
    struct pipe_context *context;
    struct pipe_opengpu_screen *screen;
    unsigned references;
    struct opengpu_native_graphics_source_description_v2 description;
    struct pipe_opengpu_resource *code[2];
};
struct opengpu_native_graphics_source_v2 {
    struct opengpu_native_graphics_program_v2 *program;
    struct opengpu_native_uniform_lease uniforms;
    struct opengpu_native_graphics_source_description_v2 description;
};
void opengpu_native_graphics_program_destroy_v2(struct opengpu_native_graphics_program_v2 *p)
{
    if(!p || --p->references)return;
    for(unsigned s=0;s<2;s++)if(p->code[s])pipe_opengpu_resource_destroy(p->screen,p->code[s]);
    free(p);
}
static int token_extent(const void *tokens,unsigned words)
{
    struct tgsi_header h;
    if(!tokens || words<3 || words>4098)return -1;
    memcpy(&h,tokens,4);return h.HeaderSize!=2 || h.BodySize!=words-2 ? -1:0;
}
/* Called only AFTER the independent emitter has validated all bounded packets.
 * Semantic kind, not register offset, distinguishes PRIMID from GENERIC. */
static int fragment_layout(const void *tokens,unsigned *varyings,unsigned *colors)
{
    struct tgsi_parse_context parse;unsigned v=0,c=0;
    if(tgsi_parse_init(&parse,tokens)!=TGSI_PARSE_OK)return -1;
    while(!tgsi_parse_end_of_tokens(&parse)){
        tgsi_parse_token(&parse);
        if(parse.FullToken.Token.Type!=TGSI_TOKEN_TYPE_DECLARATION)continue;
        const struct tgsi_full_declaration *d=&parse.FullToken.FullDeclaration;
        if(!d->Declaration.Semantic)continue;
        unsigned *n=NULL,limit=0;
        if(d->Declaration.File==TGSI_FILE_INPUT && d->Semantic.Name==TGSI_SEMANTIC_GENERIC){n=&v;limit=16;}
        if(d->Declaration.File==TGSI_FILE_OUTPUT && d->Semantic.Name==TGSI_SEMANTIC_COLOR){n=&c;limit=4;}
        if(n){
            unsigned count=d->Range.Last-d->Range.First+1;
            if(d->Semantic.Index>=limit || count>limit-d->Semantic.Index){tgsi_parse_free(&parse);return -1;}
            unsigned end=d->Semantic.Index+count;if(*n<end)*n=end;
        }
    }
    tgsi_parse_free(&parse);*varyings=v;*colors=c;return 0;
}
struct opengpu_native_graphics_program_v2 *opengpu_native_graphics_program_create_v2(
    struct pipe_context *pipe,const void *vs,unsigned vn,const void *fs,unsigned fn,
    const struct opengpu_native_fragment_reflection_v2 *reflection)
{
    if(!pipe || !reflection || token_extent(vs,vn) || token_extent(fs,fn)){errno=EINVAL;return NULL;}
    struct opengpu_native_graphics_program_v2 *p=calloc(1,sizeof(*p));if(!p)return NULL;
    p->references=1;p->context=pipe;p->screen=opengpu_screen_gpu(pipe->screen);
    struct opengpu_native_graphics_source_description_v2 *d=&p->description;
    struct opengpu_native_fragment_reflection_v2 reflected=*reflection;
    struct opengpu_native_fragment_config_v2 config={128,16,4,1,1};
    if(opengpu_native_sample_context_runtime(pipe,&d->runtime,&d->context_id))goto fail;
    if(d->runtime.limits.capacity!=128 ||
        opengpu_native_uniform_compile(vs,0,128,&d->vertex_layout,&d->vertex) ||
        opengpu_native_fragment_uniform_compile_v2(fs,fn,&reflected,&config,&d->fragment)){
        errno=ENOTSUP;goto fail;
    }
    unsigned varyings,colors;
    if(fragment_layout(fs,&varyings,&colors)){errno=EINVAL;goto fail;}
    if(varyings<d->vertex_layout.varyings)varyings=d->vertex_layout.varyings;
    d->vertex_layout.varyings=varyings;config.varying_slots=varyings;config.color_slots=colors;
    if(opengpu_native_uniform_emit(vs,&d->vertex_layout,&d->vertex) ||
        opengpu_native_fragment_uniform_compile_v2(fs,fn,&reflected,&config,&d->fragment) ||
        (d->fragment.metadata.varying_mask & ~d->vertex.varying_mask)){
        errno=EINVAL;goto fail;
    }
    for(unsigned s=0;s<2;s++){
        unsigned bytes=(s?d->fragment.words:d->vertex.words)*4;
        const void *code=s?(const void *)d->fragment.code:(const void *)d->vertex.code;
        p->code[s]=pipe_opengpu_resource_create(p->screen,bytes);if(!p->code[s])goto fail;
        void *map=pipe_opengpu_resource_map(p->code[s]);if(!map)goto fail;
        memcpy(map,code,bytes);
        d->programs[s].handle=pipe_opengpu_resource_handle(p->code[s]);d->programs[s].bytes=bytes;
    }
    return p;
fail:{int saved=errno;opengpu_native_graphics_program_destroy_v2(p);errno=saved;return NULL;}
}
struct opengpu_native_graphics_source_v2 *opengpu_native_graphics_source_create_v2(
    struct pipe_context *pipe,struct opengpu_native_graphics_program_v2 *p)
{
    struct opengpu_native_sample_runtime r;unsigned context;
    if(!pipe || !p || p->context!=pipe){errno=EINVAL;return NULL;}
    if(opengpu_native_sample_context_runtime(pipe,&r,&context))return NULL;
    if(context!=p->description.context_id || memcmp(&r,&p->description.runtime,sizeof(r))){errno=EPROTO;return NULL;}
    struct opengpu_native_graphics_source_v2 *s=calloc(1,sizeof(*s));if(!s)return NULL;
    unsigned required[2][13];
    memcpy(required[0],p->description.vertex.constant_bytes,sizeof(required[0]));
    memcpy(required[1],p->description.fragment.metadata.constant_bytes,sizeof(required[1]));
    if(opengpu_native_sample_uniform_lease_capture(pipe,required,&s->uniforms)){free(s);return NULL;}
    s->description=p->description;
    memcpy(s->description.uniforms,s->uniforms.stages,sizeof(s->description.uniforms));
    s->program=p;p->references++;return s;
}
void opengpu_native_graphics_source_destroy_v2(struct opengpu_native_graphics_source_v2 *s)
{
    if(!s)return;
    opengpu_native_sample_uniform_lease_release(&s->uniforms);
    opengpu_native_graphics_program_destroy_v2(s->program);free(s);
}
int opengpu_native_graphics_source_describe_v2(const struct opengpu_native_graphics_source_v2 *s,
    struct opengpu_native_graphics_source_description_v2 *out)
{
    if(!s || !out){errno=EINVAL;return -1;}*out=s->description;return 0;
}

int opengpu_native_graphics_source_matches_v2(const struct opengpu_native_graphics_source_v2 *s,struct pipe_context *pipe)
{ return !s || s->program->context!=pipe ? -1:0; }
