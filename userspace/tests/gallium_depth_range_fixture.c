/* Exercise the real Gallium viewport callback's depth-range admission. */
#include <assert.h>
#include <stdio.h>
#include "../gallium/opengpu_context.c"

int main(void)
{
    struct opengpu_context ctx = {0};
    struct pipe_viewport_state viewport = {0};
    struct opengpu_depth_range range = {0};
    struct drm_opengpu_vertex_draw draw = {0};
    bool enabled = true;
    viewport.scale[2] = .5f;
    viewport.translate[2] = .5f;
    set_viewport_states(&ctx.base, 0, 1, &viewport);
    assert(ctx.depth_range_supported);
    assert(!prepare_depth_range(&ctx,false,&range,&enabled) && !enabled);
    emit_depth_range(&draw,&range,enabled);
    assert(!draw.state && !draw.reserved0[2] && !draw.reserved0[3]);
    viewport.scale[2] = .25f;
    set_viewport_states(&ctx.base, 0, 1, &viewport);
    assert(!ctx.depth_range_supported);
    assert(prepare_depth_range(&ctx,false,&range,&enabled)==-1 && errno==EOPNOTSUPP);
    assert(!prepare_depth_range(&ctx,true,&range,&enabled) && enabled);
    assert(range.near==0x400000u && range.far==0xbfffffu);
    emit_depth_range(&draw,&range,enabled);
    assert(draw.state==OPENGPU_DRAW_STATE_DEPTH_RANGE &&
           draw.reserved0[2]==0x400000u && draw.reserved0[3]==0xbfffffu);
    viewport.scale[2] = -.5f;
    set_viewport_states(&ctx.base,0,1,&viewport);
    assert(!prepare_depth_range(&ctx,true,&range,&enabled) && enabled);
    assert(range.near==0xffffffu && !range.far);
    viewport.scale[2] = 0;viewport.translate[2] = .25f;
    set_viewport_states(&ctx.base,0,1,&viewport);
    assert(!prepare_depth_range(&ctx,true,&range,&enabled) && enabled);
    assert(range.near==0x400000u && range.far==0x400000u);
    viewport.scale[2] = NAN;
    set_viewport_states(&ctx.base,0,1,&viewport);
    assert(prepare_depth_range(&ctx,true,&range,&enabled)==-1);
    viewport.scale[2] = .75f;viewport.translate[2] = .5f;
    set_viewport_states(&ctx.base,0,1,&viewport);
    assert(prepare_depth_range(&ctx,true,&range,&enabled)==-1);
    viewport.scale[2] = .5f;
    viewport.translate[2] = .25f;
    set_viewport_states(&ctx.base, 0, 1, &viewport);
    assert(!ctx.depth_range_supported);
    viewport.translate[2] = .5f;
    set_viewport_states(&ctx.base, 1, 1, &viewport);
    set_viewport_states(&ctx.base, 0, 0, &viewport);
    assert(!ctx.depth_range_supported);
    set_viewport_states(&ctx.base, 0, 1, &viewport);
    assert(ctx.depth_range_supported);
    puts("Gallium depth range admission: PASS");
    return 0;
}
