/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GL_MODE_H
#define OPENGPU_GL_MODE_H

#include <drm/drm.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_mode.h>
#include <errno.h>
#include <stdint.h>
#include <sys/ioctl.h>
#include <stdio.h>
#include <stdlib.h>
#include <xf86drmMode.h>

/* Native GLES qualification may select a smaller advertised KMS mode without
 * changing the elaborated maximum. With no request, use the preferred mode. */
static inline int opengpu_gl_choose_mode(const drmModeConnector *connector,
                                        drmModeModeInfo *mode)
{
    const char *request = getenv("OPENGPU_GL_MODE");
    unsigned width, height;
    char trailing;
    int i;

    if (!connector || !mode || connector->count_modes <= 0)
        goto invalid;
    if (!request || !*request) {
        *mode = connector->modes[0];
        return 0;
    }
    if (sscanf(request, "%ux%u%c", &width, &height, &trailing) != 2)
        goto invalid;
    for (i = 0; i < connector->count_modes; i++) {
        if (connector->modes[i].hdisplay == width &&
            connector->modes[i].vdisplay == height) {
            *mode = connector->modes[i];
            return 0;
        }
    }
invalid:
    errno = EINVAL;
    return -1;
}

/* Switch the hardware viewport before creating the EGL surface. Rendering a
 * small buffer first would still use the previous scanout dimensions. The
 * temporary framebuffer is owned by this DRM fd and released on its close. */
static inline int opengpu_gl_activate_mode(int fd, const drmModeConnector *connector,
                                          uint32_t crtc, const drmModeModeInfo *mode)
{
    struct drm_mode_create_dumb dumb = { .bpp = 32 };
    uint32_t handles[4] = {0}, pitches[4] = {0}, offsets[4] = {0};
    uint32_t framebuffer, connector_id = connector->connector_id;

    dumb.width = mode->hdisplay;
    dumb.height = mode->vdisplay;
    if (ioctl(fd, DRM_IOCTL_MODE_CREATE_DUMB, &dumb) < 0)
        return -1;
    handles[0] = dumb.handle;
    pitches[0] = dumb.pitch;
    if (drmModeAddFB2(fd, dumb.width, dumb.height, DRM_FORMAT_RGBA8888,
                      handles, pitches, offsets, &framebuffer, 0))
        return -1;
    return drmModeSetCrtc(fd, crtc, framebuffer, 0, 0, &connector_id, 1,
                          (drmModeModeInfo *)mode);
}

/* The native fixtures' eight-pixel edge probes were specified at 320x240.
 * Scale those probes as well as their already proportional interior samples. */
static inline unsigned opengpu_gl_probe_x(const drmModeModeInfo *mode, unsigned pixel)
{
    unsigned value = pixel * mode->hdisplay / 320u;
    return value ? value : 1u;
}
static inline unsigned opengpu_gl_probe_y(const drmModeModeInfo *mode, unsigned pixel)
{
    unsigned value = pixel * mode->vdisplay / 240u;
    return value ? value : 1u;
}

#endif
