/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GALLIUM_BUFFER_VIEWS_H
#define OPENGPU_GALLIUM_BUFFER_VIEWS_H

#include "pipe/p_state.h"
#include "opengpu_buffer_texture.h"

/* EXT_texture_buffer table texbo.1. This table does not include SNORM,
 * legacy BGRA or general image formats, and does not advertise an API cap. */
static inline int opengpu_buffer_sampler_format(enum pipe_format format)
{
    switch (format) {
    case PIPE_FORMAT_R8_UNORM: return OPENGPU_TEXTURE_FORMAT_R8;
    case PIPE_FORMAT_R16_FLOAT: return OPENGPU_TEXTURE_FORMAT_R16F;
    case PIPE_FORMAT_R32_FLOAT: return OPENGPU_TEXTURE_FORMAT_R32F;
    case PIPE_FORMAT_R8_SINT: return OPENGPU_TEXTURE_FORMAT_R8I;
    case PIPE_FORMAT_R16_SINT: return OPENGPU_TEXTURE_FORMAT_R16I;
    case PIPE_FORMAT_R32_SINT: return OPENGPU_TEXTURE_FORMAT_R32I;
    case PIPE_FORMAT_R8_UINT: return OPENGPU_TEXTURE_FORMAT_R8UI;
    case PIPE_FORMAT_R16_UINT: return OPENGPU_TEXTURE_FORMAT_R16UI;
    case PIPE_FORMAT_R32_UINT: return OPENGPU_TEXTURE_FORMAT_R32UI;
    case PIPE_FORMAT_R8G8_UNORM: return OPENGPU_TEXTURE_FORMAT_RG8;
    case PIPE_FORMAT_R16G16_FLOAT: return OPENGPU_TEXTURE_FORMAT_RG16F;
    case PIPE_FORMAT_R32G32_FLOAT: return OPENGPU_TEXTURE_FORMAT_RG32F;
    case PIPE_FORMAT_R8G8_SINT: return OPENGPU_TEXTURE_FORMAT_RG8I;
    case PIPE_FORMAT_R16G16_SINT: return OPENGPU_TEXTURE_FORMAT_RG16I;
    case PIPE_FORMAT_R32G32_SINT: return OPENGPU_TEXTURE_FORMAT_RG32I;
    case PIPE_FORMAT_R8G8_UINT: return OPENGPU_TEXTURE_FORMAT_RG8UI;
    case PIPE_FORMAT_R16G16_UINT: return OPENGPU_TEXTURE_FORMAT_RG16UI;
    case PIPE_FORMAT_R32G32_UINT: return OPENGPU_TEXTURE_FORMAT_RG32UI;
    case PIPE_FORMAT_R32G32B32_FLOAT: return OPENGPU_TEXTURE_FORMAT_RGB32F;
    case PIPE_FORMAT_R32G32B32_SINT: return OPENGPU_TEXTURE_FORMAT_RGB32I;
    case PIPE_FORMAT_R32G32B32_UINT: return OPENGPU_TEXTURE_FORMAT_RGB32UI;
    case PIPE_FORMAT_R8G8B8A8_UNORM: return OPENGPU_TEXTURE_FORMAT_RGBA8;
    case PIPE_FORMAT_R16G16B16A16_FLOAT: return OPENGPU_TEXTURE_FORMAT_RGBA16F;
    case PIPE_FORMAT_R32G32B32A32_FLOAT: return OPENGPU_TEXTURE_FORMAT_RGBA32F;
    case PIPE_FORMAT_R8G8B8A8_SINT: return OPENGPU_TEXTURE_FORMAT_RGBA8I;
    case PIPE_FORMAT_R16G16B16A16_SINT: return OPENGPU_TEXTURE_FORMAT_RGBA16I;
    case PIPE_FORMAT_R32G32B32A32_SINT: return OPENGPU_TEXTURE_FORMAT_RGBA32I;
    case PIPE_FORMAT_R8G8B8A8_UINT: return OPENGPU_TEXTURE_FORMAT_RGBA8UI;
    case PIPE_FORMAT_R16G16B16A16_UINT: return OPENGPU_TEXTURE_FORMAT_RGBA16UI;
    case PIPE_FORMAT_R32G32B32A32_UINT: return OPENGPU_TEXTURE_FORMAT_RGBA32UI;
    default: return -1;
    }
}

/* Image formats are a separate 13-format contract. In particular RGBA8_SNORM
 * is image-capable without becoming a buffer sampler storage format. */
static inline int opengpu_buffer_image_format(enum pipe_format format)
{
    switch (format) {
    case PIPE_FORMAT_R32G32B32A32_FLOAT: return OPENGPU_TEXTURE_FORMAT_RGBA32F;
    case PIPE_FORMAT_R32G32B32A32_UINT: return OPENGPU_TEXTURE_FORMAT_RGBA32UI;
    case PIPE_FORMAT_R32G32B32A32_SINT: return OPENGPU_TEXTURE_FORMAT_RGBA32I;
    case PIPE_FORMAT_R16G16B16A16_FLOAT: return OPENGPU_TEXTURE_FORMAT_RGBA16F;
    case PIPE_FORMAT_R16G16B16A16_UINT: return OPENGPU_TEXTURE_FORMAT_RGBA16UI;
    case PIPE_FORMAT_R16G16B16A16_SINT: return OPENGPU_TEXTURE_FORMAT_RGBA16I;
    case PIPE_FORMAT_R32_FLOAT: return OPENGPU_TEXTURE_FORMAT_R32F;
    case PIPE_FORMAT_R32_UINT: return OPENGPU_TEXTURE_FORMAT_R32UI;
    case PIPE_FORMAT_R32_SINT: return OPENGPU_TEXTURE_FORMAT_R32I;
    case PIPE_FORMAT_R8G8B8A8_UINT: return OPENGPU_TEXTURE_FORMAT_RGBA8UI;
    case PIPE_FORMAT_R8G8B8A8_SINT: return OPENGPU_TEXTURE_FORMAT_RGBA8I;
    case PIPE_FORMAT_R8G8B8A8_UNORM: return OPENGPU_TEXTURE_FORMAT_RGBA8;
    case PIPE_FORMAT_R8G8B8A8_SNORM: return OPENGPU_TEXTURE_FORMAT_RGBA8_SNORM;
    default: return -1;
    }
}

static inline int opengpu_gallium_buffer_sampler_init(
    struct drm_opengpu_compute_texture_view *out, unsigned slot,
    const struct pipe_resource *resource, const struct pipe_sampler_view *view)
{
    struct drm_opengpu_compute_texture_view native;
    int format;
    if (!resource || !view || resource->target != PIPE_BUFFER ||
        view->target != PIPE_BUFFER || (format=opengpu_buffer_sampler_format(view->format)) < 0 ||
        view->swizzle_r > PIPE_SWIZZLE_1 || view->swizzle_g > PIPE_SWIZZLE_1 ||
        view->swizzle_b > PIPE_SWIZZLE_1 || view->swizzle_a > PIPE_SWIZZLE_1 ||
        !opengpu_buffer_texture_view_init(&native,slot,(unsigned)format,
            resource->width0,view->u.buf.offset,view->u.buf.size)) return 0;
    /* The six native swizzles have the same component/constant semantics. */
    _Static_assert(PIPE_SWIZZLE_X==0 && PIPE_SWIZZLE_Y==1 && PIPE_SWIZZLE_Z==2 &&
        PIPE_SWIZZLE_W==3 && PIPE_SWIZZLE_0==4 && PIPE_SWIZZLE_1==5,"native swizzle ABI");
    native.swizzle_version=1;
    native.swizzle[0]=view->swizzle_r;native.swizzle[1]=view->swizzle_g;
    native.swizzle[2]=view->swizzle_b;native.swizzle[3]=view->swizzle_a;
    if (!out) return 0;
    *out=native;return 1;
}

static inline int opengpu_gallium_buffer_image_init(
    struct drm_opengpu_compute_image_view *out, unsigned slot,
    const struct pipe_image_view *view)
{
    struct drm_opengpu_compute_image_view native;
    int format;
    unsigned storage,flags=0;
    if (!view || !view->resource || view->resource->target != PIPE_BUFFER ||
        (format=opengpu_buffer_image_format(view->format)) < 0 ||
        !(view->access & PIPE_IMAGE_ACCESS_READ_WRITE) ||
        (view->access & ~PIPE_IMAGE_ACCESS_READ_WRITE) ||
        (view->shader_access & ~PIPE_IMAGE_ACCESS_READ_WRITE)) return 0;
    if (view->access & PIPE_IMAGE_ACCESS_READ) flags|=OPENGPU_COMPUTE_IMAGE_READ;
    if (view->access & PIPE_IMAGE_ACCESS_WRITE) flags|=OPENGPU_COMPUTE_IMAGE_WRITE;
    storage=(unsigned)format;
    if (format==OPENGPU_TEXTURE_FORMAT_RGBA8_SNORM) {
        /* A Gallium image view carries an untyped BO and its image format,
         * without the GL texture's original storage format. Use a legal
         * same-size byte container, not an extra sampler format. */
        storage=OPENGPU_TEXTURE_FORMAT_RGBA8;flags|=OPENGPU_COMPUTE_IMAGE_BY_SIZE;
    }
    if (!opengpu_buffer_image_view_init(&native,slot,flags,storage,(unsigned)format,
            view->resource->width0,view->u.buf.offset,view->u.buf.size) || !out) return 0;
    *out=native;return 1;
}
#endif
