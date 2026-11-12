/* Exercise the real private-pipe texture binding state with a mocked ioctl. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include "../pipe_opengpu.c"

static unsigned unbind_calls, bind_calls;
static int fail_unbind, fail_bind;
static struct drm_opengpu_resource last_binding;

int opengpu_unbind(int fd, uint32_t context_id, uint32_t slot)
{
    assert(fd == 3 && context_id == 7 && slot == PIPE_TEX_SLOT);
    unbind_calls++;
    if (fail_unbind) { errno = EIO; return -1; }
    return 0;
}

int opengpu_bind(int fd, const struct drm_opengpu_resource *resource)
{
    assert(fd == 3 && resource->context_id == 7 && resource->slot == PIPE_TEX_SLOT);
    bind_calls++;
    last_binding = *resource;
    if (fail_bind) { errno = EIO; return -1; }
    return 0;
}

int main(void)
{
    struct pipe_opengpu_screen screen = {.fd = 3};
    struct pipe_opengpu_context ctx = {.screen = &screen, .context_id = 7};
    struct pipe_opengpu_resource first = {.buffer = {.handle = 1, .size = 512}};
    struct pipe_opengpu_resource second = {.buffer = {.handle = 2, .size = 512}};

    errno = 0;
    assert(pipe_opengpu_unbind_texture(NULL) == -1 && errno == EINVAL);
    assert(!pipe_opengpu_unbind_texture(&ctx) && !unbind_calls);
    assert(!pipe_opengpu_bind_texture(&ctx, &first, 8, 8, 256, 0));
    assert(ctx.tex_bound && ctx.texture == &first && bind_calls == 1 && last_binding.handle == 1);

    fail_unbind = 1; errno = 0;
    assert(pipe_opengpu_bind_texture(&ctx, &second, 8, 8, 256, 0) == -1 && errno == EIO);
    assert(ctx.tex_bound && ctx.texture == &first && unbind_calls == 1 && bind_calls == 1);
    fail_unbind = 0;
    assert(!pipe_opengpu_bind_texture(&ctx, &second, 8, 8, 256, 0));
    assert(ctx.tex_bound && ctx.texture == &second && unbind_calls == 2 && bind_calls == 2 &&
           last_binding.handle == 2);

    fail_unbind = 1; errno = 0;
    assert(pipe_opengpu_unbind_texture(&ctx) == -1 && errno == EIO);
    assert(ctx.tex_bound && ctx.texture == &second && unbind_calls == 3);
    fail_unbind = 0;
    assert(!pipe_opengpu_unbind_texture(&ctx));
    assert(!ctx.tex_bound && !ctx.texture && unbind_calls == 4);
    assert(!pipe_opengpu_unbind_texture(&ctx) && unbind_calls == 4);

    fail_bind = 1; errno = 0;
    assert(pipe_opengpu_bind_texture(&ctx, &first, 8, 8, 256, 0) == -1 && errno == EIO);
    assert(!ctx.tex_bound && !ctx.texture && bind_calls == 3);
    fail_bind = 0;
    assert(!pipe_opengpu_bind_texture(&ctx, &first, 8, 8, 256, 0));
    assert(ctx.tex_bound && ctx.texture == &first && bind_calls == 4);
    puts("winsys texture unbind and retry: PASS");
    return 0;
}
