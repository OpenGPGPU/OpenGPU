/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include "opengpu_render_extent.h"

int main(void)
{
    struct opengpu_render_extent e;
    assert(!opengpu_render_extent_resolve(280, 124, 640, 480, 640, 480, 1, &e));
    assert(e.width == 280 && e.height == 124);
    assert(!opengpu_render_extent_resolve(14, 10, 640, 480, 640, 480, 1, &e));
    assert(e.width == 14 && e.height == 10);
    assert(!opengpu_render_extent_resolve(0, 0, 640, 480, 640, 480, 1, &e));
    assert(e.width == 640 && e.height == 480);
    /* Offscreen rendering can exceed a small or disabled scanout. */
    assert(!opengpu_render_extent_resolve(280, 124, 64, 64, 640, 480, 1, &e));
    assert(e.width == 280 && e.height == 124);
    assert(!opengpu_render_extent_resolve(280, 124, 0, 0, 640, 480, 1, &e));
    /* Older callers and same-mode callers work on older hardware. */
    assert(!opengpu_render_extent_resolve(0, 0, 640, 480, 640, 480, 0, &e));
    assert(!opengpu_render_extent_resolve(640, 480, 640, 480, 640, 480, 0, &e));
    assert(opengpu_render_extent_resolve(280, 124, 640, 480, 640, 480, 0, &e) == -95);
    assert(opengpu_render_extent_resolve(0, 124, 640, 480, 640, 480, 1, &e) == -22);
    assert(opengpu_render_extent_resolve(280, 0, 640, 480, 640, 480, 1, &e) == -22);
    assert(opengpu_render_extent_resolve(641, 480, 640, 480, 640, 480, 1, &e) == -22);
    assert(opengpu_render_extent_resolve(640, 481, 640, 480, 640, 480, 1, &e) == -22);
    assert(opengpu_render_extent_resolve(0, 0, 0, 0, 640, 480, 1, &e) == -22);
    assert(opengpu_render_extent_resolve(280, 124, 640, 480, 640, 480, 1, 0) == -22);
    return 0;
}
