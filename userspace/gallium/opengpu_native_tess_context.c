/* SPDX-License-Identifier: MIT */
#include "opengpu_native_tess_context.h"
#include "pipe/p_context.h"
#include "pipe/p_state.h"
#include "tgsi/tgsi_parse.h"
#include <errno.h>
#include <stdlib.h>
#include <string.h>
struct state {unsigned refs,stage,words,external;
struct tgsi_token *tokens;
    struct state *next;
    struct opengpu_native_tess_context *owner;
    };
struct opengpu_native_tess_context {
    struct opengpu_native_tess_constants *constants;
    struct pipe_context *source;
    struct state *states,*bound[2];
    unsigned capacity,patch_vertices,levels[6];
    int error;
};
static void retain(struct state *s){if(s)s->refs++;
}
static void drop(struct state *s){
    if(s&&!--s->refs){
        struct state **p=&s->owner->states;
        while(*p!=s)p=&(*p)->next;
        *p=s->next;
        free(s->tokens);
        free(s);
    }
}
static struct state *owned(struct opengpu_native_tess_context *c,void *pointer){
    for(struct state *s=c->states;s;s=s->next)if(s==pointer)return s;
    return NULL;
}
static struct opengpu_native_tess_context *context(struct pipe_context *p){return p->priv;
}
static void *create(struct pipe_context *pipe,const struct pipe_shader_state *shader,unsigned stage)
{
    struct opengpu_native_tess_context *c=context(pipe);
    struct opengpu_native_tess_layout reflection;
    if(!shader||shader->type!=PIPE_SHADER_IR_TGSI||!shader->tokens||shader->stream_output.num_outputs){errno=EINVAL;
    return NULL;
    }
    unsigned n=tgsi_num_tokens(shader->tokens);
    if(opengpu_native_tess_uniform_reflect(shader->tokens,n,stage,c->capacity,c->patch_vertices,&reflection)){errno=ENOTSUP;
    return NULL;
    }
    struct state *s=calloc(1,sizeof(*s));
    if(!s)return NULL;
    s->tokens=malloc(n*sizeof(*s->tokens));
    if(!s->tokens){free(s);
    return NULL;
    }
    memcpy(s->tokens,shader->tokens,n*sizeof(*s->tokens));
    s->stage=stage;
    s->words=n;
    s->refs=1;
    s->external=1;
    s->owner=c;
    s->next=c->states;
    c->states=s;
    return s;
}
static void *create_tcs(struct pipe_context *p,const struct pipe_shader_state *s){return create(p,s,4);
}
static void *create_tes(struct pipe_context *p,const struct pipe_shader_state *s){return create(p,s,5);
}
static void bind(struct pipe_context *pipe,void *shader,unsigned stage)
{
    struct opengpu_native_tess_context *c=context(pipe);
    struct state *s=owned(c,shader);
    if(shader&&(!s||!s->external||s->stage!=stage)){c->error=EINVAL;
    return;
    }
    retain(s);
    drop(c->bound[stage-4]);
    c->bound[stage-4]=s;
}
static void bind_tcs(struct pipe_context *p,void *s){bind(p,s,4);
}
static void bind_tes(struct pipe_context *p,void *s){bind(p,s,5);
}
static void destroy_shader(struct pipe_context *p,void *pointer,unsigned stage){
    struct opengpu_native_tess_context *c=context(p);
    struct state *s=owned(c,pointer);
    if(!s||s->stage!=stage||!s->external){c->error=EINVAL;
    return;
    }
    s->external=0;
    drop(s);
}
static void delete_tcs(struct pipe_context *p,void *s){destroy_shader(p,s,4);
}
static void delete_tes(struct pipe_context *p,void *s){destroy_shader(p,s,5);
}
static void patch_vertices(struct pipe_context *pipe,uint8_t vertices)
{
    struct opengpu_native_tess_context *c=context(pipe);
    if(!vertices||vertices>32){c->error=EINVAL;
    return;
    }c->patch_vertices=vertices;
}
static void tess_state(struct pipe_context *pipe,const float outer[4],const float inner[2])
{
    struct opengpu_native_tess_context *c=context(pipe);
    if(!outer||!inner){c->error=EINVAL;
    return;
    }
    memcpy(c->levels,outer,16);
    memcpy(c->levels+4,inner,8);
}
struct opengpu_native_tess_context *opengpu_native_tess_context_create(struct pipe_context *source,unsigned capacity)
{
    if(!source||!source->screen||!capacity||capacity>128){errno=EINVAL;
    return NULL;
    }
    struct opengpu_native_tess_context *c=calloc(1,sizeof(*c));
    if(!c)return NULL;
    c->source=source;
    c->capacity=capacity;
    c->patch_vertices=3;
    for(unsigned i=0;i<6;i++)c->levels[i]=0x3f800000;
    c->constants=opengpu_native_tess_constants_create(source);
    if(!c->constants){free(c);
    return NULL;
    }
    struct pipe_context *p=opengpu_native_tess_constants_pipe(c->constants);
    p->priv=c;
    p->create_tcs_state=create_tcs;
    p->create_tes_state=create_tes;
    p->bind_tcs_state=bind_tcs;
    p->bind_tes_state=bind_tes;
    p->delete_tcs_state=delete_tcs;
    p->delete_tes_state=delete_tes;
    p->set_patch_vertices=patch_vertices;
    p->set_tess_state=tess_state;
    return c;
}
struct pipe_context *opengpu_native_tess_context_pipe(struct opengpu_native_tess_context *c)
{return c?opengpu_native_tess_constants_pipe(c->constants):NULL;
}
struct opengpu_native_tess_constants *opengpu_native_tess_context_constants(struct opengpu_native_tess_context *c)
{return c?c->constants:NULL;
}
void opengpu_native_tess_context_destroy(struct opengpu_native_tess_context *c)
{
    if(!c)return;
    drop(c->bound[0]);
    drop(c->bound[1]);
    while(c->states)drop(c->states);
    opengpu_native_tess_constants_destroy(c->constants);
    free(c);
}
struct opengpu_native_tess_program_pair *opengpu_native_tess_context_program(struct opengpu_native_tess_context *c)
{
    struct opengpu_native_tess_layout layout[2];
    const void *tokens[2];
    unsigned words[2];
    if(!c||c->error||!c->bound[0]||!c->bound[1]){errno=c&&c->error?c->error:EINVAL;
    return NULL;
    }
    for(unsigned i=0;i<2;i++){
        tokens[i]=c->bound[i]->tokens;
        words[i]=c->bound[i]->words;
        if(opengpu_native_tess_uniform_reflect(tokens[i],words[i],i+4,c->capacity,c->patch_vertices,&layout[i])){errno=ENOTSUP;
        return NULL;
        }
    }
    /* The linked output map belongs to TCS. TES may only read a subset and
     * therefore cannot independently repack away unused position/components. */
    layout[1].input_position=layout[0].output_position;
    layout[1].input_components=layout[0].output_components;
    layout[1].output_vertices=layout[0].output_vertices;
    layout[1].patch_components=layout[0].patch_components;
    return opengpu_native_tess_program_create(c->source,tokens,words,layout);
}
int opengpu_native_tess_context_state(struct opengpu_native_tess_context *c,unsigned *vertices,unsigned levels[6])
{
    if(!c||!vertices||!levels||c->error){errno=c&&c->error?c->error:EINVAL;
    return -1;
    }
    *vertices=c->patch_vertices;
    memcpy(levels,c->levels,sizeof(c->levels));
    return 0;
}
