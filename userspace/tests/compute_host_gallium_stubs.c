/* Unreachable generic Gallium helpers; never execute shader instructions. */
#include <assert.h>
#include <stdlib.h>
#include "pipe/p_context.h"
#include "pipe/p_state.h"
#include "util/u_upload_mgr.h"
/* Unused generic graphics upload helpers: never substitute the compute path. */
struct u_upload_mgr *u_upload_create_default(struct pipe_context *p){(void)p;return (struct u_upload_mgr *)calloc(1,1);}
void u_upload_destroy(struct u_upload_mgr *p){free(p);}
void u_upload_alloc(struct u_upload_mgr *u,unsigned m,unsigned n,unsigned a,unsigned *o,struct pipe_resource **r,void **p)
{(void)u;(void)m;(void)n;(void)a;(void)o;(void)r;(void)p;assert(!"unexpected graphics uploader");}
void u_upload_unmap(struct u_upload_mgr *u){(void)u;}
void u_default_buffer_subdata(struct pipe_context *p,struct pipe_resource *r,unsigned u,unsigned o,unsigned n,const void *d)
{(void)p;(void)r;(void)u;(void)o;(void)n;(void)d;assert(!"unexpected generic upload");}
void u_default_texture_subdata(struct pipe_context *p,struct pipe_resource *r,unsigned l,unsigned u,const struct pipe_box *b,const void *d,unsigned s,unsigned z)
{(void)p;(void)r;(void)l;(void)u;(void)b;(void)d;(void)s;(void)z;assert(!"unexpected generic texture upload");}
void u_default_transfer_flush_region(struct pipe_context *p,struct pipe_transfer *t,const struct pipe_box *b)
{(void)p;(void)t;(void)b;assert(!"unexpected generic flush");}
void u_default_clear_buffer(struct pipe_context *p,struct pipe_resource *r,unsigned o,unsigned n,const void *v,int s)
{(void)p;(void)r;(void)o;(void)n;(void)v;(void)s;assert(!"unexpected generic clear");}
void util_resource_copy_region(struct pipe_context *p,struct pipe_resource *d,unsigned dl,unsigned x,unsigned y,unsigned z,
                               struct pipe_resource *s,unsigned sl,const struct pipe_box *b)
{(void)p;(void)d;(void)dl;(void)x;(void)y;(void)z;(void)s;(void)sl;(void)b;assert(!"unexpected generic copy");}
void util_clear_render_target(struct pipe_context *p,struct pipe_surface *s,const union pipe_color_union *c,
                              unsigned x,unsigned y,unsigned w,unsigned h)
{(void)p;(void)s;(void)c;(void)x;(void)y;(void)w;(void)h;assert(!"unexpected generic render-target clear");}
