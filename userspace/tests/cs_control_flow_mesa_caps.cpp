// SPDX-License-Identifier: MIT
#include <cassert>
#include <cstdlib>
#include <cstring>
#include "main/context.h"
#include "main/extensions.h"
extern "C" {
#include "state_tracker/st_extensions.h"
}

extern "C" void cs_check_mesa_extensions(struct pipe_screen *screen, int gl_compat, int es2)
{
    st_config_options options = {};
    for (unsigned api=0;api<2;api++) {
        auto *ctx = static_cast<gl_context *>(calloc(1, sizeof(gl_context)));
        assert(ctx);
        ctx->API=api ? API_OPENGLES2 : API_OPENGL_COMPAT;
        ctx->Version=api ? es2 : gl_compat;
        _mesa_init_constants(&ctx->Const,ctx->API);
        _mesa_init_extensions(&ctx->Extensions);
        st_init_limits(screen,&ctx->Const,&ctx->Extensions);
        st_init_extensions(screen,&ctx->Const,&ctx->Extensions,&options,ctx->API);
        assert(!ctx->Extensions.ARB_compute_shader);
        GLubyte *extensions=_mesa_make_extension_string(ctx);
        assert(extensions && extensions[0] && !strstr((const char *)extensions,"GL_ARB_compute_shader"));
        free(extensions);
        free(ctx->Const.SpirVExtensions);
        free(ctx);
    }
}

