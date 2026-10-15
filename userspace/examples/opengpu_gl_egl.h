/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_GL_EGL_H
#define OPENGPU_GL_EGL_H

#include <EGL/egl.h>
#include <stdlib.h>

/* eglChooseConfig sorts by channel sizes, not by the GBM window's FourCC.
 * EGL_NATIVE_VISUAL_ID is ignored as a choose criterion in Mesa, so inspect
 * all matching configs explicitly before creating the window surface. */
static inline EGLBoolean opengpu_gl_choose_config(EGLDisplay display,
    const EGLint *attributes, EGLint visual, EGLConfig *config, EGLint *count)
{
    EGLConfig *configs;
    EGLint capacity = 0, matches = 0;
    EGLBoolean found = EGL_FALSE;

    *count = 0;
    if (!eglChooseConfig(display, attributes, NULL, 0, &capacity) || capacity <= 0)
        return EGL_FALSE;
    configs = calloc((size_t)capacity, sizeof(*configs));
    if (!configs)
        return EGL_FALSE;
    if (eglChooseConfig(display, attributes, configs, capacity, &matches)) {
        for (EGLint i = 0; i < matches; i++) {
            EGLint native_visual = 0;
            if (eglGetConfigAttrib(display, configs[i], EGL_NATIVE_VISUAL_ID,
                                   &native_visual) && native_visual == visual) {
                *config = configs[i];
                *count = 1;
                found = EGL_TRUE;
                break;
            }
        }
    }
    free(configs);
    return found;
}
#endif
