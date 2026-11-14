/* SPDX-License-Identifier: MIT */
#include "opengpu_native_tess_constants.h"
#include "opengpu_internal.h"
#include "pipe/p_context.h"
#include "util/u_inlines.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
struct retained {struct pipe_resource *resource;
struct pipe_opengpu_resource *copy;
struct pipe_opengpu_uniform_view view;
};
struct opengpu_native_tess_constants {
    struct pipe_context adapter;
    struct pipe_context *source;
    struct retained buffers[2][13];
    int error;
};
static void release(struct opengpu_native_tess_constants *c,struct retained *v){
    pipe_resource_reference(&v->resource,NULL);
    if(v->copy)pipe_opengpu_resource_destroy(opengpu_screen_gpu(c->source->screen),v->copy);
    memset(v,0,sizeof(*v));
}
int opengpu_native_tess_constant_set(struct opengpu_native_tess_constants *c,unsigned stage,
    unsigned slot,int take,const struct pipe_constant_buffer *buffer){
    struct retained next={0};
    int ret=-1;
    if(!c){errno=EINVAL;
    return -1;
    }
    if((stage!=4&&stage!=5)||slot>12||(take!=0&&take!=1)){errno=EINVAL;
    goto done;
    }
    unsigned maximum=slot?16384u:4096u;
    if(buffer&&(buffer->buffer||buffer->user_buffer)){
        if((buffer->buffer&&buffer->user_buffer)||!buffer->buffer_size||buffer->buffer_size>maximum||
            (buffer->buffer_size&3)||(buffer->buffer_offset&3)){errno=EINVAL;
            goto done;
            }
        if(buffer->user_buffer){
            if(slot){errno=EINVAL;
            goto done;
            }
            next.copy=pipe_opengpu_resource_create(opengpu_screen_gpu(c->source->screen),buffer->buffer_size);
            if(!next.copy)goto done;
            void *mapping=pipe_opengpu_resource_map(next.copy);
            if(!mapping){errno=ENOMEM;
            goto done;
            }
            memcpy(mapping,(const unsigned char *)buffer->user_buffer+buffer->buffer_offset,buffer->buffer_size);
            next.view=(struct pipe_opengpu_uniform_view){next.copy,0,buffer->buffer_size};
        }else{
            struct pipe_resource *r=buffer->buffer;
            if(r->screen!=c->source->screen||r->target!=PIPE_BUFFER||buffer->buffer_offset>r->width0||
                buffer->buffer_size>r->width0-buffer->buffer_offset){errno=EINVAL;
                goto done;
                }
            pipe_resource_reference(&next.resource,r);
            next.view=(struct pipe_opengpu_uniform_view){opengpu_resource(r)->gpu,buffer->buffer_offset,buffer->buffer_size};
        }
        /* Same real screen/member/view checker as original graphics sources,
         * without querying old96/GUC1 or implying GUR3 availability. */
        struct pipe_opengpu_uniform_view views[2][13]={0};
        unsigned required[2][13]={{0}};
        struct pipe_opengpu_native_uniform_plan plan;
        views[stage-4][slot]=next.view;
        required[stage-4][slot]=buffer->buffer_size;
        if(pipe_opengpu_native_uniform_plan(opengpu_screen_gpu(c->source->screen),views,required,&plan))goto done;
    }else if(buffer&&(buffer->buffer_offset||buffer->buffer_size)){errno=EINVAL;
    goto done;
    }
    release(c,&c->buffers[stage-4][slot]);
    c->buffers[stage-4][slot]=next;
    memset(&next,0,sizeof(next));
    ret=0;
done:
    {int saved=errno;
    release(c,&next);
    if(take&&buffer&&buffer->buffer){struct pipe_resource *owned=buffer->buffer;
    pipe_resource_reference(&owned,NULL);
    }
    if(ret)c->error=saved?saved:EINVAL;
    errno=saved;
    return ret;
    }
}
static void set(struct pipe_context *pipe,enum pipe_shader_type stage,unsigned slot,bool take,const struct pipe_constant_buffer *buffer){
    struct opengpu_native_tess_constants *c=(void *)pipe;
    if(stage==PIPE_SHADER_TESS_CTRL||stage==PIPE_SHADER_TESS_EVAL)
        (void)opengpu_native_tess_constant_set(c,stage==PIPE_SHADER_TESS_CTRL?4:5,slot,take,buffer);
    else if(c->source->set_constant_buffer)c->source->set_constant_buffer(c->source,stage,slot,take,buffer);
    else {if(take&&buffer&&buffer->buffer){struct pipe_resource *owned=buffer->buffer;
    pipe_resource_reference(&owned,NULL);
    }c->error=ENOTSUP;
    }
}
struct opengpu_native_tess_constants *opengpu_native_tess_constants_create(struct pipe_context *source){
    if(!source||!source->screen){errno=EINVAL;
    return NULL;
    }
    struct opengpu_native_tess_constants *c=calloc(1,sizeof(*c));
    if(!c)return NULL;
    c->source=source;
    c->adapter.screen=source->screen;
    c->adapter.set_constant_buffer=set;
    return c;
}
struct pipe_context *opengpu_native_tess_constants_pipe(struct opengpu_native_tess_constants *c){return c?&c->adapter:NULL;
}
struct pipe_context *opengpu_native_tess_constants_source(struct opengpu_native_tess_constants *c){return c?c->source:NULL;}
void opengpu_native_tess_constants_destroy(struct opengpu_native_tess_constants *c){
    if(!c)return;
    for(unsigned s=0;s<2;s++)for(unsigned i=0;i<13;i++)release(c,&c->buffers[s][i]);
    free(c);
}
int opengpu_native_tess_constant_sources(struct opengpu_native_tess_constants *c,const unsigned required[2][13],
    struct drm_opengpu_native_uniform_stage out[2]){
    struct pipe_opengpu_uniform_view views[2][13];
    if(!c||!required||!out){errno=EINVAL;
    return -1;
    }if(c->error){errno=c->error;
    return -1;
    }
    for(unsigned s=0;s<2;s++)for(unsigned i=0;i<13;i++)views[s][i]=c->buffers[s][i].view;
    return pipe_opengpu_native_uniform_stages_checked(opengpu_screen_gpu(c->source->screen),views,required,out);
}
