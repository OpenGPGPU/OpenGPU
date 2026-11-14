/* Exercise the real Gallium constant-buffer callback without a GPU. */
#include <assert.h>
#include <limits.h>
#include <stdio.h>
#include "../gallium/opengpu_context.c"

struct pipe_opengpu_resource { uint8_t *map; uint64_t size; };
static unsigned maps, destroyed;
struct opengpu_resource *opengpu_resource(struct pipe_resource *resource)
{ return (struct opengpu_resource *)resource; }
struct pipe_opengpu_screen *opengpu_screen_gpu(struct pipe_screen *screen)
{ (void)screen; return NULL; }
int pipe_opengpu_screen_compute_uniforms(const struct pipe_opengpu_screen *screen)
{ (void)screen; return 0; }
int pipe_opengpu_uniform_view_validate(const struct pipe_opengpu_screen *screen,
    const struct pipe_opengpu_uniform_view *view, unsigned slot)
{ (void)screen; (void)view; (void)slot; return 0; }
/* This fixture deliberately exercises the legacy callback with native layout
 * disabled. Linking the callback must not silently authorize a native plan. */
int pipe_opengpu_native_uniform_plan(struct pipe_opengpu_screen *screen,
    const struct pipe_opengpu_uniform_view views[2][13], const unsigned required[2][13],
    struct pipe_opengpu_native_uniform_plan *out)
{
    (void)screen; (void)views; (void)required; (void)out;
    assert(!"unexpected native graphics constant plan");
    return -1;
}
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *resource)
{ return resource ? resource->size : 0; }
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *resource)
{ maps++; return resource ? resource->map : NULL; }
static void resource_destroyed(struct pipe_screen *screen, struct pipe_resource *resource)
{ (void)screen; (void)resource; destroyed++; }

int main(void)
{
    struct pipe_screen screen={0}, foreign={0};
    struct opengpu_context ctx={0};
    struct opengpu_resource resource={0};
    struct pipe_opengpu_resource gpu={0};
    struct pipe_constant_buffer view={0};
    float values[40];
    unsigned before;

    for (unsigned i=0; i<40; i++) values[i]=(float)i+0.25f;
    screen.resource_destroy=resource_destroyed;
    ctx.base.screen=&screen;
    resource.base.screen=&screen; resource.base.target=PIPE_BUFFER;
    resource.base.width0=sizeof(values); resource.base.reference.count=1;
    resource.gpu=&gpu; gpu.map=(uint8_t *)values; gpu.size=sizeof(values);
    view.buffer=&resource.base; view.buffer_offset=4*sizeof(float);
    view.buffer_size=32*sizeof(float);
    set_constant_buffer(&ctx.base,PIPE_SHADER_VERTEX,0,false,&view);
    assert(ctx.mvp_set && ctx.mvp_b_set && ctx.vs_opacity_set && ctx.vs_alu_set);
    assert(ctx.vs_block_n==20 && ctx.mvp[0]==values[4] && ctx.mvp[15]==values[19]);
    assert(ctx.mvp_b[0]==values[20] && ctx.mvp_b[15]==values[35]);
    assert(ctx.vs_opacity==values[20] && ctx.vs_block[19]==values[23]);
    assert(resource.base.reference.count==1 && maps==1 && !destroyed);

    /* Smaller fragment constants and nonzero user offsets keep exact values. */
    view.buffer_offset=3*sizeof(float); view.buffer_size=3*sizeof(float);
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(ctx.fs_block_n==3 && !ctx.fs_const_set && ctx.fs_block[0]==values[3]);
    view.buffer=NULL; view.user_buffer=values; view.buffer_offset=2*sizeof(float);
    view.buffer_size=4*sizeof(float);
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(ctx.fs_block_n==4 && ctx.fs_const_set && ctx.fs_const[0]==values[2]);
    assert(ctx.fs_const[3]==values[5]);
    set_constant_buffer(&ctx.base,PIPE_SHADER_VERTEX,0,false,&view);
    assert(ctx.vs_block_n==4 && ctx.vs_block[0]==values[2] && ctx.vs_alu_set);
    assert(!ctx.mvp_set && !ctx.mvp_b_set && !ctx.vs_opacity_set);

    /* Reject both logical and GEM bounds before mapping; offsets cannot wrap. */
    view.user_buffer=NULL; view.buffer=&resource.base;
    view.buffer_offset=sizeof(values)-4; view.buffer_size=8; before=maps;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(maps==before && !ctx.fs_const_set && !ctx.fs_block_n && errno==EINVAL);
    view.buffer_offset=UINT_MAX; view.buffer_size=8;
    set_constant_buffer(&ctx.base,PIPE_SHADER_VERTEX,0,false,&view);
    assert(maps==before && !ctx.vs_alu_set && !ctx.vs_block_n);
    view.buffer_offset=0; view.buffer_size=16; gpu.size=8;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(maps==before && !ctx.fs_block_n);
    gpu.size=sizeof(values); resource.base.screen=&foreign;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(maps==before && !ctx.fs_block_n);
    resource.base.screen=&screen; resource.base.target=PIPE_TEXTURE_2D;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(maps==before && !ctx.fs_block_n);
    resource.base.target=PIPE_BUFFER; gpu.map=NULL;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(maps==before+1 && !ctx.fs_block_n);
    gpu.map=(uint8_t *)(UINTPTR_MAX-1);
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(maps==before+2 && !ctx.fs_block_n && errno==EINVAL);
    gpu.map=(uint8_t *)values;

    view.buffer=NULL; view.user_buffer=(void *)(UINTPTR_MAX-1); view.buffer_size=4;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(!ctx.fs_block_n && errno==EINVAL);
    view.user_buffer=NULL; view.buffer_size=4;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,&view);
    assert(!ctx.fs_block_n && errno==EINVAL);

    /* A transferred reference is consumed after the snapshot, even on error. */
    view.buffer=&resource.base; view.buffer_size=16;
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,true,&view);
    assert(destroyed==1 && ctx.fs_const_set && ctx.fs_const[0]==values[0]);
    resource.base.reference.count=1; view.buffer_offset=sizeof(values);
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,true,&view);
    assert(destroyed==2 && !ctx.fs_block_n);
    set_constant_buffer(&ctx.base,PIPE_SHADER_FRAGMENT,0,false,NULL);
    assert(!ctx.fs_block_n && !ctx.fs_const_set);
    puts("Gallium graphics constant buffer snapshots and bounds: PASS");
    return 0;
}
