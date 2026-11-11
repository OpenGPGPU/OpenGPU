/* Real Gallium vertex-binding callback with reference-counted fake resources. */
#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include "../gallium/opengpu_context.c"

static unsigned destroyed;
static void resource_destroyed(struct pipe_screen *screen, struct pipe_resource *resource)
{ (void)screen; (void)resource; destroyed++; }

static struct pipe_resource resource(struct pipe_screen *screen, unsigned refs)
{
    struct pipe_resource r={0}; r.screen=screen; r.reference.count=refs;
    r.target=PIPE_BUFFER; r.width0=64; return r;
}

int main(void)
{
    struct pipe_screen screen={0};
    struct opengpu_context ctx={0};
    struct pipe_resource a,b,c;
    struct pipe_vertex_buffer incoming[2]={{0}};
    uint8_t bytes[4]={1,2,3,4};
    screen.resource_destroy=resource_destroyed;
    ctx.base.screen=&screen;

    /* Cross-slot aliases remain alive while the old last references are
     * replaced; acquired incoming references are held before any release. */
    a=resource(&screen,1); b=resource(&screen,1);
    ctx.vb[0].buffer.resource=&a; ctx.vb[1].buffer.resource=&b;
    incoming[0].buffer.resource=&b; incoming[1].buffer.resource=&a;
    set_vertex_buffers(&ctx.base,0,2,0,false,incoming);
    assert(!destroyed && a.reference.count==1 && b.reference.count==1);
    assert(ctx.vb[0].buffer.resource==&b && ctx.vb[1].buffer.resource==&a);
    set_vertex_buffers(&ctx.base,0,1,1,false,NULL);
    assert(destroyed==2 && !ctx.vb[0].buffer.resource && !ctx.vb[1].buffer.resource);

    /* A take-ownership update consumes the caller's references exactly once. */
    destroyed=0; a=resource(&screen,2); b=resource(&screen,2);
    ctx.vb[0].buffer.resource=&a; ctx.vb[1].buffer.resource=&b;
    incoming[0].buffer.resource=&b; incoming[1].buffer.resource=&a;
    set_vertex_buffers(&ctx.base,0,2,0,true,incoming);
    assert(!destroyed && a.reference.count==1 && b.reference.count==1);
    set_vertex_buffers(&ctx.base,0,0,2,false,NULL);
    assert(destroyed==2);

    /* An incoming array may also alias the existing slot itself. */
    incoming[0]=(struct pipe_vertex_buffer){0};
    incoming[0].is_user_buffer=1; incoming[0].buffer.user=bytes;
    set_vertex_buffers(&ctx.base,0,1,0,false,incoming);
    set_vertex_buffers(&ctx.base,0,1,0,false,&ctx.vb[0]);
    assert(ctx.vb[0].is_user_buffer && ctx.vb[0].buffer.user==bytes);
    set_vertex_buffers(&ctx.base,0,1,0,false,NULL);
    assert(!ctx.vb[0].is_user_buffer && !ctx.vb[0].buffer.resource);

    /* Reject invalid ranges without wrapping or disturbing existing slots;
     * release a bounded incoming take-ownership reference. */
    destroyed=0; a=resource(&screen,1); c=resource(&screen,1);
    ctx.vb[0].buffer.resource=&a; incoming[0]=(struct pipe_vertex_buffer){0};
    incoming[0].buffer.resource=&c;
    set_vertex_buffers(&ctx.base,UINT_MAX,1,0,false,incoming);
    assert(!destroyed && ctx.vb[0].buffer.resource==&a);
    set_vertex_buffers(&ctx.base,OPENGPU_VB_SLOTS,1,0,true,incoming);
    assert(destroyed==1 && ctx.vb[0].buffer.resource==&a);
    set_vertex_buffers(&ctx.base,0,0,1,false,NULL);
    assert(destroyed==2);
    puts("Gallium vertex buffer ownership and rebinding: PASS");
    return 0;
}
