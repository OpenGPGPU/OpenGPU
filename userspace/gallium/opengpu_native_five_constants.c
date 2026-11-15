/* SPDX-License-Identifier: MIT */
#include "opengpu_native_five_internal.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include "util/u_inlines.h"
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <limits.h>
struct backing {unsigned refs;struct pipe_resource *resource;struct pipe_opengpu_resource *copy;struct pipe_opengpu_uniform_view view;};
struct opengpu_native_five_constants {struct pipe_context sink;struct pipe_context *source;struct backing *slots[5][13];int error;};
struct opengpu_native_five_bindings {struct pipe_opengpu_screen *screen;struct backing *slots[5][13];};
static void drop(struct pipe_opengpu_screen *screen,struct backing *b){
    if(!b||--b->refs)return;
    pipe_resource_reference(&b->resource,NULL);
    if(b->copy)pipe_opengpu_resource_destroy(screen,b->copy);
    free(b);
}
static int stage_index(enum pipe_shader_type stage){switch(stage){case PIPE_SHADER_VERTEX:return 0;case PIPE_SHADER_TESS_CTRL:return 1;case PIPE_SHADER_TESS_EVAL:return 2;case PIPE_SHADER_FRAGMENT:return 4;case PIPE_SHADER_GEOMETRY:return 3;default:return -1;}}
static void set(struct pipe_context *pipe,enum pipe_shader_type stage,unsigned slot,bool take,const struct pipe_constant_buffer *cb){
    struct opengpu_native_five_constants *c=(void *)pipe;int s=stage_index(stage),error=EINVAL;
    struct pipe_opengpu_screen *screen=opengpu_screen_gpu(c->source->screen);struct backing *b=NULL;
    if(s<0||slot>12)goto done;
    if(cb&&(cb->buffer||cb->user_buffer)){
        unsigned max=slot?16384:4096;
        if((cb->buffer&&cb->user_buffer)||!cb->buffer_size||cb->buffer_size>max||((cb->buffer_size|cb->buffer_offset)&3))goto done;
        b=calloc(1,sizeof(*b));if(!b){error=ENOMEM;goto done;}b->refs=1;
        if(cb->user_buffer){
            if(slot||(uintptr_t)cb->user_buffer>UINTPTR_MAX-cb->buffer_offset||
                (uintptr_t)cb->user_buffer+cb->buffer_offset>UINTPTR_MAX-cb->buffer_size)goto done;
            b->copy=pipe_opengpu_resource_create(screen,cb->buffer_size);if(!b->copy){error=errno;goto done;}
            void *map=pipe_opengpu_resource_map(b->copy);if(!map){error=errno;goto done;}
            memcpy(map,(const unsigned char *)cb->user_buffer+cb->buffer_offset,cb->buffer_size);
            b->view=(struct pipe_opengpu_uniform_view){b->copy,0,cb->buffer_size};
        }else{
            struct pipe_resource *r=cb->buffer;
            if(r->screen!=c->source->screen||r->target!=PIPE_BUFFER||cb->buffer_offset>r->width0||cb->buffer_size>r->width0-cb->buffer_offset)goto done;
            pipe_resource_reference(&b->resource,r);b->view=(struct pipe_opengpu_uniform_view){opengpu_resource(r)->gpu,cb->buffer_offset,cb->buffer_size};
        }
        struct pipe_opengpu_uniform_view views[2][13]={0};unsigned need[2][13]={{0}};struct pipe_opengpu_native_uniform_plan plan;
        views[0][slot]=b->view;need[0][slot]=cb->buffer_size;if(pipe_opengpu_native_uniform_plan(screen,views,need,&plan))goto done;
    }else if(cb&&(cb->buffer_offset||cb->buffer_size))goto done;
    drop(screen,c->slots[s][slot]);c->slots[s][slot]=b;b=NULL;error=0;
done:drop(screen,b);if(take&&cb&&cb->buffer){struct pipe_resource *r=cb->buffer;pipe_resource_reference(&r,NULL);}if(error)c->error=error;
}
struct opengpu_native_five_constants *opengpu_native_five_constants_create(struct pipe_context *source){
    if(!source||!source->screen){errno=EINVAL;return NULL;}
    struct opengpu_native_five_constants *c=calloc(1,sizeof(*c));if(c){c->source=source;c->sink.screen=source->screen;c->sink.set_constant_buffer=set;}return c;
}
struct pipe_context *opengpu_native_five_constants_pipe(struct opengpu_native_five_constants *c){return c?&c->sink:NULL;}
void opengpu_native_five_constants_destroy(struct opengpu_native_five_constants *c){if(!c)return;for(unsigned s=0;s<5;s++)for(unsigned i=0;i<13;i++)drop(opengpu_screen_gpu(c->source->screen),c->slots[s][i]);free(c);}
void opengpu_native_five_release(struct opengpu_native_five_bindings *b){if(!b)return;for(unsigned s=0;s<5;s++)for(unsigned i=0;i<13;i++)drop(b->screen,b->slots[s][i]);free(b);}
struct opengpu_native_five_bindings *opengpu_native_five_capture(struct opengpu_native_five_constants *c,struct pipe_context *source,
    const unsigned required[5][13],struct drm_opengpu_native_uniform_stage out[5]){
    if(!c||c->source!=source||!required||!out||c->error){errno=EINVAL;return NULL;}
    struct opengpu_native_five_bindings *b=calloc(1,sizeof(*b));if(!b)return NULL;b->screen=opengpu_screen_gpu(source->screen);
    struct drm_opengpu_native_uniform_stage result[5]={0};
    /* Independent GUR1 VS/FS, GUR3 TC/TE and GUR2 GS tables, each <=24 mappings. This is original-view planning, not serialized-root authorization. */
    const unsigned pairs[3][2]={{0,4},{1,2},{3,5}};
    for(unsigned p=0;p<3;p++){
        struct pipe_opengpu_uniform_view views[2][13]={0};unsigned needs[2][13];struct drm_opengpu_native_uniform_stage stages[2];
        for(unsigned i=0;i<2;i++){unsigned s=pairs[p][i];if(s==5){memset(needs[i],0,sizeof(needs[i]));continue;}memcpy(needs[i],required[s],sizeof(needs[i]));
            for(unsigned k=0;k<13;k++)if(required[s][k]){
                struct backing *v=c->slots[s][k];if(!v){errno=EINVAL;goto fail;}
                if(v->refs==UINT_MAX){errno=EOVERFLOW;goto fail;}
                v->refs++;b->slots[s][k]=v;views[i][k]=v->view;
            }
        }
        if(pipe_opengpu_native_uniform_stages_checked(b->screen,views,needs,stages))goto fail;
        for(unsigned i=0;i<2;i++)if(pairs[p][i]<5)result[pairs[p][i]]=stages[i];
    }
    memcpy(out,result,sizeof(result));return b;
fail:{int error=errno;opengpu_native_five_release(b);errno=error;return NULL;}
}
