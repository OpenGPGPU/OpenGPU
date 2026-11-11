/* Exercise the real Gallium viewport callback's depth-range admission. */
#include <assert.h>
#include <stdio.h>
#include "../gallium/opengpu_context.c"

int main(void)
{
    struct opengpu_context ctx = {0};
    struct pipe_viewport_state viewport = {0};
    viewport.scale[2] = .5f;
    viewport.translate[2] = .5f;
    set_viewport_states(&ctx.base, 0, 1, &viewport);
    assert(ctx.depth_range_supported);
    viewport.scale[2] = .25f;
    set_viewport_states(&ctx.base, 0, 1, &viewport);
    assert(!ctx.depth_range_supported);
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
