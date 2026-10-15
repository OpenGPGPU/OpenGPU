/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GALLIUM_INTERNAL_H
#define OPENGPU_GALLIUM_INTERNAL_H

#include "pipe_opengpu.h"
#include "pipe/p_screen.h"
#include "pipe/p_state.h"

static inline int opengpu_format(enum pipe_format format)
{
    switch (format) {
    case PIPE_FORMAT_R8G8B8A8_UNORM: return OPENGPU_FORMAT_RGBA8;
    case PIPE_FORMAT_B8G8R8A8_UNORM: return OPENGPU_FORMAT_BGRA8;
    case PIPE_FORMAT_R8G8B8X8_UNORM: return OPENGPU_FORMAT_RGBX8;
    case PIPE_FORMAT_B8G8R8X8_UNORM: return OPENGPU_FORMAT_BGRX8;
    default: return -1;
    }
}

struct opengpu_resource {
    struct pipe_resource base;
    struct pipe_opengpu_resource *gpu;
};

struct opengpu_resource *opengpu_resource(struct pipe_resource *resource);
struct pipe_opengpu_screen *opengpu_screen_gpu(struct pipe_screen *screen);
struct pipe_context *opengpu_pipe_context_create(struct pipe_screen *screen,
                                                 void *priv, unsigned flags);

#endif
