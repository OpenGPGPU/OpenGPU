/* The compute callback fixture does not execute the graphics draw/ZS paths. */
#include <assert.h>
#include "util/format/u_format.h"
#include "util/u_surface.h"

#ifndef OPENGPU_CS_REAL_MESA_FORMATS
const struct util_format_description *util_format_description(enum pipe_format format)
{
    (void)format;
    assert(!"unexpected graphics format lookup");
    return NULL;
}

const struct util_format_unpack_description *util_format_unpack_description(enum pipe_format format)
{
    (void)format;
    assert(!"unexpected graphics unpack lookup");
    return NULL;
}
#endif

void util_clear_depth_stencil(struct pipe_context *pipe, struct pipe_surface *dst,
                              unsigned clear_flags, double depth, unsigned stencil,
                              unsigned x, unsigned y, unsigned width, unsigned height)
{
    (void)pipe; (void)dst; (void)clear_flags; (void)depth; (void)stencil;
    (void)x; (void)y; (void)width; (void)height;
    assert(!"unexpected graphics depth/stencil clear");
}
