/* Exercise the real winsys depth binding and render request construction.
 * No ioctl or GPU execution is performed. */
#include <assert.h>
#include "../pipe_opengpu.c"

int main(void)
{
    struct pipe_opengpu_screen screen = { .caps = OPENGPU_CAP_PERSISTENT_DEPTH };
    struct pipe_opengpu_resource color = { .buffer = { .handle = 1, .size = 128 } };
    struct pipe_opengpu_resource depth = { .buffer = { .handle = 2, .size = 128 } };
    struct pipe_opengpu_context context = {
        .screen = &screen, .color = { &color }, .nr_colors = 1,
        .fb_width = 5, .fb_height = 4,
    };
    struct drm_opengpu_submit submit = {0};

    assert(!pipe_opengpu_screen_clip_depth(NULL));
    assert(!pipe_opengpu_screen_clip_depth(&screen));
    screen.driver_features = OPENGPU_DRIVER_FEATURE_CLIP_DEPTH;
    assert(pipe_opengpu_screen_clip_depth(&screen));

    pipe_opengpu_set_depth(&context, &depth, 1);
    assert(!apply_depth_submit(&context, &submit));
    assert(submit.depth_handle == 2 && submit.depth_offset == 0);
    assert(submit.flags & OPENGPU_SUBMIT_DEPTH_LOAD);

    submit = (struct drm_opengpu_submit){0};
    pipe_opengpu_set_depth(&context, &depth, 0);
    assert(!apply_depth_submit(&context, &submit));
    assert(submit.depth_handle == 2 && !(submit.flags & OPENGPU_SUBMIT_DEPTH_LOAD));

    pipe_opengpu_set_depth(&context, NULL, 1);
    submit = (struct drm_opengpu_submit){0};
    assert(!apply_depth_submit(&context, &submit));
    assert(!submit.depth_handle && !(submit.flags & OPENGPU_SUBMIT_DEPTH_LOAD));
    puts("Gallium depth submit fixture: PASS");
    return 0;
}
