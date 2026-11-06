/* SPDX-License-Identifier: MIT */
/* Actual app destroy body + Gallium view callbacks, modeled OS/GEM only.
 * No frontend execution or shader/DRM device work is substituted here. */
#define main existing_callback_corpus_main
#define OPENGPU_CS_REAL_TGSI_DUMP
#include "cs_callback_flow.c"
#undef main
#include "../examples/gallium_buffer_glsl_contract.h"
static struct {
    struct pipe_context *ctx;
    struct pipe_sampler_view *views[32];
    void *state[GB_PROGRAMS];
} app;
/* Builder extracts the unchanged function from the actual app source. */
#include "buffer-app-destroy.inc"
int main(void)
{
    features=OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE|OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE|
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE;
    struct pipe_screen *s=opengpu_drm_screen_create(7,NULL); assert(s);
    struct pipe_resource *r=buffer(s,4u*1024u*1024u); assert(r);
    for(unsigned epoch=0;epoch<2;epoch++) {
        app.ctx=s->context_create(s,NULL,0); assert(app.ctx);
        for(unsigned b=0;b<32;b++) {
            struct pipe_sampler_view t={.target=PIPE_BUFFER,.format=PIPE_FORMAT_R32_UINT,
                .swizzle_r=0,.swizzle_g=1,.swizzle_b=2,.swizzle_a=3};
            t.u.buf.size=r->width0;
            app.views[b]=app.ctx->create_sampler_view(app.ctx,r,&t); assert(app.views[b]);
        }
        app.ctx->set_sampler_views(app.ctx,PIPE_SHADER_COMPUTE,0,32,0,false,app.views);
        destroy_context(); assert(!app.ctx);
        /* This ordinary cleanup step dereferences the freed context under
         * ASan with the old app body; the repaired body makes it idempotent. */
        for(unsigned b=0;b<32;b++) pipe_sampler_view_reference(&app.views[b],NULL);
        assert(p_atomic_read(&r->reference.count)==1);
    }
    pipe_resource_reference(&r,NULL); s->destroy(s); no_leaks();
    puts("BUFFER_APP_LIFECYCLE twoContexts=PASS ownedViews64=PASS lastViewDropsBeforeContextFree=PASS heldBO=PASS noLeaks=PASS GPU=UNRUN");
    return 0;
}
