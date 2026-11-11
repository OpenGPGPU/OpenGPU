/* Exercise the real viewport admission and command packing; no GPU runs. */
#include <assert.h>
#include <math.h>
#include <string.h>
#include "../gallium/opengpu_context.c"

int main(void)
{
    struct opengpu_context ctx={0};
    struct pipe_viewport_state state={0};
    struct opengpu_viewport v={0};
    struct drm_opengpu_vertex_draw draw={0};
    bool enabled=true;
    ctx.width=ctx.height=8;
    assert(!prepare_viewport(&ctx,false,&v,&enabled) && !enabled);
    state.scale[0]=state.scale[1]=4; state.scale[2]=0.5f;
    state.translate[0]=state.translate[1]=4; state.translate[2]=0.5f;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(ctx.viewport_set && ctx.depth_range_supported && ctx.viewport_y_positive);
    assert(!prepare_viewport(&ctx,false,&v,&enabled) && !enabled);
    emit_viewport(&draw,&v,enabled);
    assert(!draw.state && !draw.reserved0[0] && !draw.reserved0[1]);

    state.scale[0]=state.scale[1]=2; state.translate[0]=4; state.translate[1]=3;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(prepare_viewport(&ctx,false,&v,&enabled)==-1 && errno==EOPNOTSUPP);
    assert(!prepare_viewport(&ctx,true,&v,&enabled) && enabled);
    assert(v.x==2 && v.y==1 && v.width==4 && v.height==4 && !v.flip_y);
    draw.state=OPENGPU_DRAW_STATE_OVERRIDE;
    emit_viewport(&draw,&v,enabled);
    assert(draw.state==(OPENGPU_DRAW_STATE_OVERRIDE|OPENGPU_DRAW_STATE_VIEWPORT));
    assert(draw.reserved0[0]==0x00010002u && draw.reserved0[1]==0x00040004u);

    state.scale[0]=2.5f; state.scale[1]=-1.5f;
    state.translate[0]=4.5f; state.translate[1]=2.5f;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(!ctx.viewport_y_positive);
    assert(!prepare_viewport(&ctx,true,&v,&enabled) && enabled);
    assert(v.x==2 && v.y==1 && v.width==5 && v.height==3 && v.flip_y);
    memset(&draw,0,sizeof(draw)); emit_viewport(&draw,&v,enabled);
    assert(draw.state==(OPENGPU_DRAW_STATE_VIEWPORT|OPENGPU_DRAW_STATE_VIEWPORT_FLIP_Y));
    assert(draw.reserved0[0]==0x00010002u && draw.reserved0[1]==0x00030005u);

    state.scale[0]=32767.5f; state.scale[1]=32767.5f;
    state.translate[0]=-0.5f; state.translate[1]=65534.5f;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(!prepare_viewport(&ctx,true,&v,&enabled) && enabled);
    assert(v.x==INT16_MIN && v.y==INT16_MAX && v.width==UINT16_MAX && v.height==UINT16_MAX);
    memset(&draw,0,sizeof(draw)); emit_viewport(&draw,&v,enabled);
    assert(draw.reserved0[0]==0x7fff8000u && draw.reserved0[1]==0xffffffffu);

    state.scale[0]=1; state.translate[0]=32769;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(prepare_viewport(&ctx,true,&v,&enabled)==-1);
    state.translate[0]=1.25f;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(prepare_viewport(&ctx,true,&v,&enabled)==-1);
    state.scale[0]=-1; state.translate[0]=0;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(prepare_viewport(&ctx,true,&v,&enabled)==-1);
    state.scale[0]=NAN;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(prepare_viewport(&ctx,true,&v,&enabled)==-1);
    state.scale[0]=0; state.translate[0]=INFINITY;
    set_viewport_states(&ctx.base,0,1,&state);
    assert(prepare_viewport(&ctx,true,&v,&enabled)==1 && !enabled);
    puts("Gallium viewport admission and packing: PASS");
    return 0;
}
