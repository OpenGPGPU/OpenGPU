/* SPDX-License-Identifier: MIT */
#include "pipe_opengpu.h"
#include "opengpu_shader_resources.h"
#include <drm/drm.h>
#include <drm/drm_fourcc.h>
#include <drm/drm_mode.h>
#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#define PIPE_FS_SLOT 2u
#define PIPE_KERNARG_SLOT 3u
/* Shader bindings are cache-line aligned, with a bounded 512-instruction
 * sandbox for runtime address generation. Preserve legacy 1024-byte slots. */
#define PIPE_FS_GEM_BYTES 2048u
#define PIPE_KERNARG_BYTES 1024u
#define PIPE_CS_SLOT 1u
#define PIPE_CS_KERNARG_SLOT 2u
#define PIPE_CS_GEM_BYTES 64u
#define PIPE_CS_KERNARG_BYTES 64u
#define PIPE_TEX_SLOT 1u
#define PIPE_VB_SLOT 4u
#define PIPE_VS_SLOT 5u
#define PIPE_VS_KERNARG_SLOT 6u
#define PIPE_VS_GEM_BYTES 2048u
/* Vertex uniforms: mat4, scale, padding, second mat4, within a 256-byte
 * tail following the sixteen input/output arrays of the selected batch. */
#define PIPE_VS_MATRIX_BYTES 64u
#define PIPE_VS_UNIFORM_BYTES 256u

struct pipe_opengpu_screen {
    int fd;
    uint64_t caps;
    uint32_t shader_config;
    struct pipe_opengpu_resource *resources;
};

struct pipe_opengpu_resource {
    struct opengpu_buffer buffer;
    uint32_t width;
    uint32_t height;
    uint32_t pitch;
    uint32_t fb_id;
    struct pipe_opengpu_resource *next;
};

struct pipe_opengpu_fence {
    int fd;
    uint32_t handle;
};

struct pipe_opengpu_context {
    struct pipe_opengpu_screen *screen;
    uint32_t context_id;
    struct pipe_opengpu_resource *color;
    struct pipe_opengpu_resource *depth;
    uint32_t fb_width;
    uint32_t fb_height;
    uint32_t sample_mode;
    int depth_load;
    struct opengpu_buffer shader;
    struct opengpu_buffer kernarg;
    struct opengpu_buffer vs_shader;
    struct opengpu_buffer vs_kernarg;
    struct pipe_opengpu_resource *texture;
    struct pipe_opengpu_resource *vertex_buffer;
    uint32_t vertex_stride;
    uint32_t vertex_bytes;
    int fs_bound;
    int cs_bound;
    int tex_bound;
    int vs_bound;
    int vb_bound;
};

static struct pipe_opengpu_fence *alloc_fence(int fd);
static int finish_or_hand_off(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              struct pipe_opengpu_fence **out_fence);
static int apply_depth_submit(struct pipe_opengpu_context *ctx,
                              struct drm_opengpu_submit *submit);

static struct pipe_opengpu_screen *screen_from_fd(int fd)
{
    struct pipe_opengpu_screen *screen;

    screen = calloc(1, sizeof(*screen));
    if (!screen) {
        errno = ENOMEM;
        close(fd);
        return NULL;
    }
    if (opengpu_capabilities(fd, &screen->caps)) {
        int saved = errno;

        close(fd);
        free(screen);
        errno = saved;
        return NULL;
    }
    if ((screen->caps & OPENGPU_CAP_SHADER_CONFIG_INFO) &&
        opengpu_shader_config(fd, &screen->shader_config)) {
        int saved = errno;

        close(fd);
        free(screen);
        errno = saved;
        return NULL;
    }
    if ((screen->caps & OPENGPU_CAP_SHADER_CONFIG_INFO) &&
        !opengpu_shader_resources_valid(screen->shader_config,
          (uint32_t)((screen->caps & OPENGPU_CAP_FRAGMENT_BATCH_MASK) >>
                     OPENGPU_CAP_FRAGMENT_BATCH_SHIFT))) {
        close(fd);
        free(screen);
        errno = EPROTO;
        return NULL;
    }
    screen->fd = fd;
    return screen;
}

struct pipe_opengpu_screen *pipe_opengpu_screen_create(const char *path)
{
    int fd = opengpu_open(path);

    if (fd < 0)
        return NULL;
    return screen_from_fd(fd);
}

struct pipe_opengpu_screen *pipe_opengpu_screen_create_fd(int fd)
{
    int owned;

    if (fd < 0) {
        errno = EINVAL;
        return NULL;
    }
    owned = dup(fd);
    if (owned < 0)
        return NULL;
    return screen_from_fd(owned);
}

void pipe_opengpu_screen_destroy(struct pipe_opengpu_screen *screen)
{
    if (!screen)
        return;
    if (screen->fd >= 0)
        close(screen->fd);
    free(screen);
}

int pipe_opengpu_screen_fd(const struct pipe_opengpu_screen *screen)
{
    return screen ? screen->fd : -1;
}

uint64_t pipe_opengpu_screen_capabilities(const struct pipe_opengpu_screen *screen)
{
    return screen ? screen->caps : 0;
}

uint32_t pipe_opengpu_screen_shader_config(const struct pipe_opengpu_screen *screen)
{
    return screen ? screen->shader_config : 0;
}

uint32_t pipe_opengpu_screen_fs_bank_stride(const struct pipe_opengpu_screen *screen)
{
    return screen ? opengpu_shader_fragment_bank_stride(screen->shader_config) : 0;
}

static uint32_t fragment_uniform_offset(const struct pipe_opengpu_screen *screen)
{
    return opengpu_shader_fragment_uniform_offset(screen->shader_config);
}

static uint32_t vertex_uniform_offset(const struct pipe_opengpu_screen *screen)
{
    return opengpu_shader_vertex_uniform_offset(screen->shader_config);
}

static uint32_t vertex_kernarg_bytes(const struct pipe_opengpu_screen *screen)
{
    return opengpu_shader_vertex_kernarg_bytes(screen->shader_config);
}

static uint32_t fragment_kernarg_bytes(const struct pipe_opengpu_screen *screen)
{
    uint32_t span = opengpu_shader_resource_banks(screen->shader_config) *
                    pipe_opengpu_screen_fs_bank_stride(screen);
    return span > PIPE_KERNARG_BYTES ? span : PIPE_KERNARG_BYTES;
}

struct pipe_opengpu_context *pipe_opengpu_context_create(
    struct pipe_opengpu_screen *screen)
{
    struct pipe_opengpu_context *ctx;

    if (!screen) {
        errno = EINVAL;
        return NULL;
    }
    ctx = calloc(1, sizeof(*ctx));
    if (!ctx) {
        errno = ENOMEM;
        return NULL;
    }
    ctx->screen = screen;
    if (opengpu_context_create(screen->fd, &ctx->context_id)) {
        free(ctx);
        return NULL;
    }
    return ctx;
}

void pipe_opengpu_context_destroy(struct pipe_opengpu_context *ctx)
{
    if (!ctx)
        return;
    if (ctx->tex_bound)
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_TEX_SLOT);
    if (ctx->vb_bound)
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_VB_SLOT);
    if (ctx->vs_bound) {
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_VS_SLOT);
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_VS_KERNARG_SLOT);
    }
    if (ctx->fs_bound) {
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_FS_SLOT);
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_KERNARG_SLOT);
    }
    if (ctx->cs_bound) {
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_CS_SLOT);
        opengpu_unbind(ctx->screen->fd, ctx->context_id, PIPE_CS_KERNARG_SLOT);
    }
    if (ctx->shader.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->shader);
    if (ctx->kernarg.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->kernarg);
    if (ctx->vs_shader.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->vs_shader);
    if (ctx->vs_kernarg.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->vs_kernarg);
    if (ctx->context_id)
        opengpu_context_destroy(ctx->screen->fd, ctx->context_id);
    free(ctx);
}

/* PRIME can return an existing GEM handle. Track wrappers so only the last
 * resource closes that handle, including export/reimport of our own buffers. */
static int resource_handle_live(struct pipe_opengpu_screen *screen, uint32_t handle)
{
    struct pipe_opengpu_resource *r;
    for (r = screen->resources; r; r = r->next)
        if (r->buffer.handle == handle)
            return 1;
    return 0;
}

static void track_resource(struct pipe_opengpu_screen *screen,
                           struct pipe_opengpu_resource *res)
{
    res->next = screen->resources;
    screen->resources = res;
}

struct pipe_opengpu_resource *pipe_opengpu_resource_create(
    struct pipe_opengpu_screen *screen, uint32_t bytes)
{
    struct pipe_opengpu_resource *res;

    if (!screen || !bytes) {
        errno = EINVAL;
        return NULL;
    }
    res = calloc(1, sizeof(*res));
    if (!res) {
        errno = ENOMEM;
        return NULL;
    }
    if (opengpu_buffer_create(screen->fd, bytes, &res->buffer)) {
        free(res);
        return NULL;
    }
    track_resource(screen, res);
    return res;
}

struct pipe_opengpu_resource *pipe_opengpu_resource_create_2d(
    struct pipe_opengpu_screen *screen, uint32_t width, uint32_t height)
{
    struct pipe_opengpu_resource *res;
    struct drm_opengpu_create_buffer create = { .bpp = 32 };
    void *mapping;
    uint32_t alloc_width = width, alloc_height = height;
    int saved;

    if (!screen || !width || !height) {
        errno = EINVAL;
        return NULL;
    }
    res = calloc(1, sizeof(*res));
    if (!res) {
        errno = ENOMEM;
        return NULL;
    }
    /* The render ABI validates every colour allocation against the device
     * mode stride and height. Keep the logical surface size requested by
     * Wayland, but pad the backing GEM so small GTK surfaces can render. */
    {
        uint32_t mode_width, mode_height;
        if (!opengpu_display_size(screen->fd, &mode_width, &mode_height)) {
            if (alloc_width < mode_width)
                alloc_width = mode_width;
            if (alloc_height < mode_height)
                alloc_height = mode_height;
        }
    }
    create.width = alloc_width;
    create.height = alloc_height;
    if (ioctl(screen->fd, DRM_IOCTL_OPENGPU_CREATE_BUFFER, &create) < 0) {
        free(res);
        return NULL;
    }
    mapping = mmap(NULL, create.size, PROT_READ | PROT_WRITE, MAP_SHARED,
                   screen->fd, create.offset);
    if (mapping == MAP_FAILED)
        goto fail;
    res->buffer.handle = create.handle;
    res->buffer.size = create.size;
    res->buffer.map = mapping;
    res->width = width;
    res->height = height;
    res->pitch = create.pitch;
    track_resource(screen, res);
    return res;
fail:
    saved = errno;
    { struct drm_gem_close close = { .handle = create.handle };
      ioctl(screen->fd, DRM_IOCTL_GEM_CLOSE, &close); }
    free(res);
    errno = saved;
    return NULL;
}

struct pipe_opengpu_resource *pipe_opengpu_resource_create_linear(
    struct pipe_opengpu_screen *screen, uint32_t width, uint32_t height)
{
    struct pipe_opengpu_resource *res;
    uint64_t bytes;

    if (!screen || !width || !height) {
        errno = EINVAL;
        return NULL;
    }
    bytes = (uint64_t)width * height * 4u;
    if (bytes > 0x7fffffffu) {
        errno = EINVAL;
        return NULL;
    }
    res = pipe_opengpu_resource_create(screen, (uint32_t)bytes);
    if (!res)
        return NULL;
    res->width = width;
    res->height = height;
    res->pitch = width * 4u;
    return res;
}

struct pipe_opengpu_resource *pipe_opengpu_resource_from_handle(
    struct pipe_opengpu_screen *screen, uint32_t width, uint32_t height,
    uint32_t pitch, uint32_t handle, int is_fd)
{
    struct pipe_opengpu_resource *res;
    struct drm_prime_handle prime = { .fd = (int32_t)handle };
    uint64_t bytes = (uint64_t)pitch * height;
    off_t size;
    int saved;

    /* Only linear, zero-offset DMA-BUF images are supported. Obtain the real
     * allocation size before mapping, rather than trusting image metadata. */
    if (!screen || !is_fd || !width || !height ||
        (uint64_t)width * 4 > pitch || bytes > UINT32_MAX) {
        errno = EINVAL;
        return NULL;
    }
    size = lseek(prime.fd, 0, SEEK_END);
    if (size < 0 || (uint64_t)size < bytes || (uint64_t)size > UINT32_MAX) {
        errno = EINVAL;
        return NULL;
    }
    res = calloc(1, sizeof(*res));
    if (!res)
        return NULL;
    if (ioctl(screen->fd, DRM_IOCTL_PRIME_FD_TO_HANDLE, &prime) < 0) {
        free(res);
        return NULL;
    }
    res->buffer.map = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED,
                          prime.fd, 0);
    if (res->buffer.map == MAP_FAILED) {
        saved = errno;
        if (!resource_handle_live(screen, prime.handle)) {
            struct drm_gem_close close_handle = { .handle = prime.handle };
            ioctl(screen->fd, DRM_IOCTL_GEM_CLOSE, &close_handle);
        }
        free(res);
        errno = saved;
        return NULL;
    }
    res->buffer.handle = prime.handle;
    res->buffer.size = size;
    res->width = width;
    res->height = height;
    res->pitch = pitch;
    track_resource(screen, res);
    return res;
}

int pipe_opengpu_resource_export_fd(struct pipe_opengpu_screen *screen,
                                   struct pipe_opengpu_resource *res)
{
    struct drm_prime_handle prime = { 0 };
    if (!screen || !res) {
        errno = EINVAL;
        return -1;
    }
    prime.handle = res->buffer.handle;
    prime.flags = DRM_RDWR;
    if (ioctl(screen->fd, DRM_IOCTL_PRIME_HANDLE_TO_FD, &prime) < 0)
        return -1;
    if (fcntl(prime.fd, F_SETFD, FD_CLOEXEC) < 0) {
        int saved = errno;
        close(prime.fd);
        errno = saved;
        return -1;
    }
    return prime.fd;
}

void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *res)
{
    return res ? res->buffer.map : NULL;
}

uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *res)
{
    return res ? res->buffer.size : 0;
}

uint32_t pipe_opengpu_resource_width(const struct pipe_opengpu_resource *res)
{
    return res ? res->width : 0;
}

uint32_t pipe_opengpu_resource_height(const struct pipe_opengpu_resource *res)
{
    return res ? res->height : 0;
}

uint32_t pipe_opengpu_resource_pitch(const struct pipe_opengpu_resource *res)
{
    return res ? res->pitch : 0;
}

uint32_t pipe_opengpu_resource_handle(const struct pipe_opengpu_resource *res)
{
    return res ? res->buffer.handle : 0;
}

void pipe_opengpu_resource_destroy(struct pipe_opengpu_screen *screen,
                                   struct pipe_opengpu_resource *res)
{
    if (!screen || !res)
        return;
    if (res->fb_id)
        ioctl(screen->fd, DRM_IOCTL_MODE_RMFB, &res->fb_id);
    {
        struct pipe_opengpu_resource **link = &screen->resources;
        while (*link && *link != res)
            link = &(*link)->next;
        if (*link)
            *link = res->next;
    }
    if (res->buffer.map)
        munmap(res->buffer.map, res->buffer.size);
    if (res->buffer.handle && !resource_handle_live(screen, res->buffer.handle)) {
        struct drm_gem_close close_handle = { .handle = res->buffer.handle };
        ioctl(screen->fd, DRM_IOCTL_GEM_CLOSE, &close_handle);
    }
    free(res);
}

int pipe_opengpu_screen_display_size(struct pipe_opengpu_screen *screen,
                                     uint32_t *width, uint32_t *height)
{
    if (!screen || !width || !height) {
        errno = EINVAL;
        return -1;
    }
    return opengpu_display_size(screen->fd, width, height);
}

void pipe_opengpu_set_framebuffer(struct pipe_opengpu_context *ctx,
                                  struct pipe_opengpu_resource *color,
                                  uint32_t width, uint32_t height)
{
    if (!ctx)
        return;
    ctx->color = color;
    ctx->fb_width = width;
    ctx->fb_height = height;
}

void pipe_opengpu_set_depth(struct pipe_opengpu_context *ctx,
                            struct pipe_opengpu_resource *depth, int load)
{
    if (!ctx)
        return;
    ctx->depth = depth;
    ctx->depth_load = depth ? !!load : 0;
}

int pipe_opengpu_set_sample_mode(struct pipe_opengpu_context *ctx,
                                 uint32_t sample_mode)
{
    uint32_t max_mode;

    if (!ctx || sample_mode > 2u) {
        errno = EINVAL;
        return -1;
    }
    if (sample_mode != 0) {
        if (!(ctx->screen->caps & OPENGPU_CAP_MSAA)) {
            errno = EOPNOTSUPP;
            return -1;
        }
        max_mode = (uint32_t)((ctx->screen->caps & OPENGPU_CAP_MSAA_MAX_MODE_MASK) >>
                              OPENGPU_CAP_MSAA_MAX_MODE_SHIFT);
        if (sample_mode > max_mode) {
            errno = EINVAL;
            return -1;
        }
    }
    ctx->sample_mode = sample_mode;
    return 0;
}

int pipe_opengpu_bind_fs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes)
{
    struct drm_opengpu_resource binding = { 0 };
    int fd;
    uint32_t shader_bytes;

    if (!ctx) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    if (ctx->cs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_KERNARG_SLOT);
        ctx->cs_bound = 0;
    }
    if (ctx->fs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_FS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_KERNARG_SLOT);
        ctx->fs_bound = 0;
    }
    if (ctx->shader.handle) {
        opengpu_buffer_destroy(fd, &ctx->shader);
        memset(&ctx->shader, 0, sizeof(ctx->shader));
    }
    if (ctx->kernarg.handle) {
        opengpu_buffer_destroy(fd, &ctx->kernarg);
        memset(&ctx->kernarg, 0, sizeof(ctx->kernarg));
    }
    if (!code) {
        if (ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) {
            errno = EINVAL;
            return -1;
        }
        return 0;
    }
    if (!(ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) ||
        bytes == 0 || bytes > PIPE_FS_GEM_BYTES || (bytes & 3)) {
        errno = EINVAL;
        return -1;
    }
    shader_bytes = bytes <= 1024u ? 1024u : (uint32_t)((bytes + 63u) & ~63u);
    if (opengpu_buffer_create(fd, shader_bytes, &ctx->shader) ||
        opengpu_buffer_create(fd, fragment_kernarg_bytes(ctx->screen), &ctx->kernarg))
        return -1;
    memset(ctx->shader.map, 0, shader_bytes);
    memcpy(ctx->shader.map, code, bytes);
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_FS_SLOT;
    binding.handle = ctx->shader.handle;
    binding.type = OPENGPU_RESOURCE_SHADER;
    binding.size = shader_bytes;
    if (opengpu_bind(fd, &binding))
        return -1;
    binding.slot = PIPE_KERNARG_SLOT;
    binding.handle = ctx->kernarg.handle;
    binding.type = OPENGPU_RESOURCE_KERNARG;
    binding.size = fragment_kernarg_bytes(ctx->screen);
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        return -1;
    ctx->fs_bound = 1;
    return 0;
}

int pipe_opengpu_bind_cs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes)
{
    struct drm_opengpu_resource binding = { 0 };
    int fd;

    if (!ctx || !code || bytes == 0 || bytes > PIPE_CS_GEM_BYTES || (bytes & 3)) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    if (ctx->tex_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_TEX_SLOT);
        ctx->tex_bound = 0;
        ctx->texture = NULL;
    }
    if (ctx->fs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_FS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_KERNARG_SLOT);
        ctx->fs_bound = 0;
    }
    if (ctx->cs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_KERNARG_SLOT);
        ctx->cs_bound = 0;
    }
    if (ctx->shader.handle) {
        opengpu_buffer_destroy(fd, &ctx->shader);
        memset(&ctx->shader, 0, sizeof(ctx->shader));
    }
    if (ctx->kernarg.handle) {
        opengpu_buffer_destroy(fd, &ctx->kernarg);
        memset(&ctx->kernarg, 0, sizeof(ctx->kernarg));
    }
    if (opengpu_buffer_create(fd, PIPE_CS_GEM_BYTES, &ctx->shader) ||
        opengpu_buffer_create(fd, PIPE_CS_KERNARG_BYTES, &ctx->kernarg))
        return -1;
    memcpy(ctx->shader.map, code, bytes);
    memset(ctx->kernarg.map, 0, PIPE_CS_KERNARG_BYTES);
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_CS_SLOT;
    binding.handle = ctx->shader.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_SHADER;
    binding.size = PIPE_CS_GEM_BYTES;
    if (opengpu_bind(fd, &binding))
        return -1;
    binding.slot = PIPE_CS_KERNARG_SLOT;
    binding.handle = ctx->kernarg.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_KERNARG;
    binding.size = PIPE_CS_KERNARG_BYTES;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        return -1;
    ctx->cs_bound = 1;
    return 0;
}

void *pipe_opengpu_cs_kernarg_map(struct pipe_opengpu_context *ctx)
{
    if (!ctx || !ctx->cs_bound)
        return NULL;
    return ctx->kernarg.map;
}

int pipe_opengpu_launch_grid(struct pipe_opengpu_context *ctx,
                             const uint32_t grid[3], const uint32_t local[3],
                             struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_compute command = { 0 };
    struct pipe_opengpu_fence *fence;

    if (!ctx || !ctx->cs_bound || !grid || !local) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    command.context_id = ctx->context_id;
    command.shader_slot = PIPE_CS_SLOT;
    command.kernarg_slot = PIPE_CS_KERNARG_SLOT;
    command.grid[0] = grid[0];
    command.grid[1] = grid[1];
    command.grid[2] = grid[2];
    command.local[0] = local[0];
    command.local[1] = local[1];
    command.local[2] = local[2];
    command.out_syncobj = fence->handle;
    if (opengpu_compute(ctx->screen->fd, &command)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

static struct pipe_opengpu_fence *alloc_fence(int fd)
{
    struct pipe_opengpu_fence *fence = calloc(1, sizeof(*fence));

    if (!fence) {
        errno = ENOMEM;
        return NULL;
    }
    if (opengpu_sync_create(fd, &fence->handle)) {
        free(fence);
        return NULL;
    }
    fence->fd = fd;
    return fence;
}

static int finish_or_hand_off(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              struct pipe_opengpu_fence **out_fence)
{
    if (out_fence) {
        *out_fence = fence;
        return 0;
    }
    if (pipe_opengpu_fence_finish(ctx, fence, 300000)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    pipe_opengpu_fence_reference(&fence, NULL);
    return 0;
}

static int apply_depth_submit(struct pipe_opengpu_context *ctx,
                              struct drm_opengpu_submit *submit)
{
    uint64_t need;
    uint32_t samples;

    if (!ctx->depth)
        return 0;
    if (!(ctx->screen->caps & OPENGPU_CAP_PERSISTENT_DEPTH) ||
        !ctx->fb_width || !ctx->fb_height ||
        ctx->depth->buffer.handle == ctx->color->buffer.handle) {
        errno = EINVAL;
        return -1;
    }
    samples = 1u << ctx->sample_mode;
    need = (uint64_t)ctx->fb_width * samples * 4u;
    if (need > UINT32_MAX) {
        errno = EINVAL;
        return -1;
    }
    need *= ctx->fb_height;
    if (need > ctx->depth->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    submit->depth_handle = ctx->depth->buffer.handle;
    submit->depth_offset = 0;
    if (ctx->depth_load)
        submit->flags |= OPENGPU_SUBMIT_DEPTH_LOAD;
    return 0;
}

static uint64_t color_stride_bytes(const struct pipe_opengpu_context *ctx)
{
    if (ctx->color && ctx->color->pitch) {
        /* Pitched 2D targets carry the row bytes (including MSAA width). */
        return ctx->color->pitch;
    }
    return (uint64_t)ctx->fb_width * (1u << ctx->sample_mode) * 4u;
}

static uint64_t color_bytes(const struct pipe_opengpu_context *ctx)
{
    return color_stride_bytes(ctx) * ctx->fb_height;
}

static int apply_color_submit(struct pipe_opengpu_context *ctx,
                              struct drm_opengpu_submit *submit)
{
    uint64_t stride = color_stride_bytes(ctx);

    if (!ctx->fb_width || !ctx->fb_height ||
        stride > UINT32_MAX ||
        color_bytes(ctx) > ctx->color->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    if (ctx->color->pitch &&
        (ctx->fb_width != ctx->color->width ||
         ctx->fb_height != ctx->color->height ||
         (ctx->sample_mode &&
          ctx->color->pitch <
              ctx->fb_width * (1u << ctx->sample_mode) * 4u))) {
        errno = EINVAL;
        return -1;
    }
    submit->color_handle = ctx->color->buffer.handle;
    submit->stride = stride;
    submit->sample_mode = ctx->sample_mode;
    return 0;
}

int pipe_opengpu_clear(struct pipe_opengpu_context *ctx, uint32_t pattern,
                       struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_fill fill = { 0 };
    struct pipe_opengpu_fence *fence;
    uint64_t bytes;

    if (!ctx || !ctx->color || !ctx->fb_width || !ctx->fb_height) {
        errno = EINVAL;
        return -1;
    }
    if (color_stride_bytes(ctx) > UINT32_MAX) {
        errno = EINVAL;
        return -1;
    }
    bytes = color_bytes(ctx);
    if ((bytes & 63u) || bytes > ctx->color->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    fill.context_id = ctx->context_id;
    fill.destination_handle = ctx->color->buffer.handle;
    fill.pattern = pattern;
    fill.bytes = bytes;
    fill.out_syncobj = fence->handle;
    if (opengpu_fill(ctx->screen->fd, &fill)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

int pipe_opengpu_fill(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_resource *dst,
                      uint64_t offset, uint64_t bytes, uint32_t pattern,
                      struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_fill fill = { 0 };
    struct pipe_opengpu_fence *fence;

    if (!ctx || !dst || !bytes || (offset & 63u) || (bytes & 63u) ||
        offset + bytes > dst->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    fill.context_id = ctx->context_id;
    fill.destination_handle = dst->buffer.handle;
    fill.pattern = pattern;
    fill.destination_offset = offset;
    fill.bytes = bytes;
    fill.out_syncobj = fence->handle;
    if (opengpu_fill(ctx->screen->fd, &fill)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

int pipe_opengpu_blit(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_resource *dst,
                      struct pipe_opengpu_resource *src,
                      uint64_t dst_offset, uint64_t src_offset, uint64_t bytes,
                      struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_blit blit = { 0 };
    struct pipe_opengpu_fence *fence;

    if (!ctx || !dst || !src || !bytes || (bytes & 63u) ||
        (dst_offset & 63u) || (src_offset & 63u) ||
        dst_offset + bytes > dst->buffer.size ||
        src_offset + bytes > src->buffer.size ||
        dst->buffer.handle == src->buffer.handle) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    blit.context_id = ctx->context_id;
    blit.source_handle = src->buffer.handle;
    blit.destination_handle = dst->buffer.handle;
    blit.source_offset = src_offset;
    blit.destination_offset = dst_offset;
    blit.bytes = bytes;
    blit.out_syncobj = fence->handle;
    if (opengpu_blit(ctx->screen->fd, &blit)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

int pipe_opengpu_strided_blit(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_resource *dst,
                              struct pipe_opengpu_resource *src,
                              uint64_t dst_offset, uint64_t src_offset,
                              uint32_t width_bytes, uint32_t height,
                              uint32_t dst_stride, uint32_t src_stride,
                              struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_strided_blit blit = { 0 };
    struct pipe_opengpu_fence *fence;
    uint64_t src_need, dst_need;

    if (!ctx || !dst || !src || !width_bytes || !height ||
        dst->buffer.handle == src->buffer.handle ||
        (width_bytes & 63u) || (dst_stride & 63u) || (src_stride & 63u) ||
        (dst_offset & 63u) || (src_offset & 63u) ||
        dst_stride < width_bytes || src_stride < width_bytes ||
        !(ctx->screen->caps & OPENGPU_CAP_STRIDED_ENGINE)) {
        errno = EINVAL;
        return -1;
    }
    src_need = src_offset + (uint64_t)(height - 1u) * src_stride + width_bytes;
    dst_need = dst_offset + (uint64_t)(height - 1u) * dst_stride + width_bytes;
    if (src_need > src->buffer.size || dst_need > dst->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    blit.context_id = ctx->context_id;
    blit.source_handle = src->buffer.handle;
    blit.destination_handle = dst->buffer.handle;
    blit.source_offset = src_offset;
    blit.destination_offset = dst_offset;
    blit.width_bytes = width_bytes;
    blit.height = height;
    blit.source_stride = src_stride;
    blit.destination_stride = dst_stride;
    blit.out_syncobj = fence->handle;
    if (opengpu_strided_blit(ctx->screen->fd, &blit)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

int pipe_opengpu_invalidate(struct pipe_opengpu_context *ctx,
                            struct pipe_opengpu_resource *res,
                            uint64_t offset, uint64_t bytes,
                            struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_invalidate invalidate = { 0 };
    struct pipe_opengpu_fence *fence;

    if (!ctx || !res || !bytes || (bytes & 63u) || (offset & 63u) ||
        offset + bytes > res->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    invalidate.context_id = ctx->context_id;
    invalidate.handle = res->buffer.handle;
    invalidate.offset = offset;
    invalidate.bytes = bytes;
    invalidate.out_syncobj = fence->handle;
    if (opengpu_invalidate(ctx->screen->fd, &invalidate)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

int pipe_opengpu_resolve(struct pipe_opengpu_context *ctx,
                         struct pipe_opengpu_resource *dst,
                         struct pipe_opengpu_resource *src,
                         uint32_t width, uint32_t height,
                         uint32_t src_stride, uint32_t dst_stride,
                         uint32_t sample_mode,
                         struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_resolve resolve = { 0 };
    struct pipe_opengpu_fence *fence;
    uint32_t samples;
    uint64_t src_row, dst_row, src_need, dst_need;

    if (!ctx || !dst || !src || !width || !height ||
        dst->buffer.handle == src->buffer.handle ||
        sample_mode > 2u ||
        !(ctx->screen->caps & OPENGPU_CAP_MSAA)) {
        errno = EINVAL;
        return -1;
    }
    {
        uint32_t max_mode =
            (uint32_t)((ctx->screen->caps & OPENGPU_CAP_MSAA_MAX_MODE_MASK) >>
                       OPENGPU_CAP_MSAA_MAX_MODE_SHIFT);
        if (sample_mode > max_mode) {
            errno = EINVAL;
            return -1;
        }
    }
    samples = 1u << sample_mode;
    src_row = (uint64_t)width * samples * 4u;
    dst_row = (uint64_t)width * 4u;
    if (src_stride < src_row || dst_stride < dst_row ||
        (src_stride & 3u) || (dst_stride & 3u)) {
        errno = EINVAL;
        return -1;
    }
    src_need = (uint64_t)(height - 1u) * src_stride + src_row;
    dst_need = (uint64_t)(height - 1u) * dst_stride + dst_row;
    if (src_need > src->buffer.size || dst_need > dst->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    resolve.context_id = ctx->context_id;
    resolve.source_handle = src->buffer.handle;
    resolve.destination_handle = dst->buffer.handle;
    resolve.width = width;
    resolve.height = height;
    resolve.source_stride = src_stride;
    resolve.destination_stride = dst_stride;
    resolve.sample_mode = sample_mode;
    resolve.out_syncobj = fence->handle;
    if (opengpu_resolve(ctx->screen->fd, &resolve)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

int pipe_opengpu_bind_texture(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_resource *tex,
                              uint32_t width, uint32_t height, uint32_t size,
                              uint32_t flags)
{
    struct drm_opengpu_resource binding = { 0 };
    int fd;

    if (!ctx) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    if (ctx->cs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_KERNARG_SLOT);
        ctx->cs_bound = 0;
    }
    if (ctx->tex_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_TEX_SLOT);
        ctx->tex_bound = 0;
        ctx->texture = NULL;
    }
    if (!tex)
        return 0;
    if (!width || !height || !size || size > tex->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_TEX_SLOT;
    binding.handle = tex->buffer.handle;
    binding.type = OPENGPU_RESOURCE_TEXTURE;
    binding.size = size;
    binding.width = width;
    binding.height = height;
    binding.flags = flags;
    if (opengpu_bind(fd, &binding))
        return -1;
    ctx->texture = tex;
    ctx->tex_bound = 1;
    return 0;
}

int pipe_opengpu_bind_vs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes)
{
    struct drm_opengpu_resource binding = { 0 };
    int fd;
    uint32_t shader_bytes;

    if (!ctx || !code || bytes == 0 || bytes > PIPE_VS_GEM_BYTES || (bytes & 3)) {
        errno = EINVAL;
        return -1;
    }
    if (!(ctx->screen->caps & OPENGPU_CAP_VERTEX_CORE)) {
        errno = EOPNOTSUPP;
        return -1;
    }
    fd = ctx->screen->fd;
    if (ctx->vs_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_VS_SLOT);
        opengpu_unbind(fd, ctx->context_id, PIPE_VS_KERNARG_SLOT);
        ctx->vs_bound = 0;
    }
    if (ctx->vs_shader.handle) {
        opengpu_buffer_destroy(fd, &ctx->vs_shader);
        memset(&ctx->vs_shader, 0, sizeof(ctx->vs_shader));
    }
    if (ctx->vs_kernarg.handle) {
        opengpu_buffer_destroy(fd, &ctx->vs_kernarg);
        memset(&ctx->vs_kernarg, 0, sizeof(ctx->vs_kernarg));
    }
    shader_bytes = bytes <= 1024u ? 1024u : (uint32_t)((bytes + 63u) & ~63u);
    if (opengpu_buffer_create(fd, shader_bytes, &ctx->vs_shader) ||
        opengpu_buffer_create(fd, vertex_kernarg_bytes(ctx->screen), &ctx->vs_kernarg))
        return -1;
    memcpy(ctx->vs_shader.map, code, bytes);
    memset(ctx->vs_kernarg.map, 0, vertex_kernarg_bytes(ctx->screen));
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_VS_SLOT;
    binding.handle = ctx->vs_shader.handle;
    binding.type = OPENGPU_RESOURCE_VERTEX_SHADER;
    binding.size = shader_bytes;
    if (opengpu_bind(fd, &binding))
        return -1;
    binding.slot = PIPE_VS_KERNARG_SLOT;
    binding.handle = ctx->vs_kernarg.handle;
    binding.type = OPENGPU_RESOURCE_VERTEX_KERNARG;
    binding.size = vertex_kernarg_bytes(ctx->screen);
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding))
        return -1;
    ctx->vs_bound = 1;
    return 0;
}

int pipe_opengpu_write_vs_matrix(struct pipe_opengpu_context *ctx,
                                 const float matrix[16])
{
    struct drm_opengpu_invalidate invalidate = { 0 };
    struct pipe_opengpu_fence *fence;

    float scale = 65536.0f;

    if (!ctx || !matrix || !ctx->vs_bound || !ctx->vs_kernarg.map ||
        ctx->vs_kernarg.size < vertex_uniform_offset(ctx->screen) + PIPE_VS_UNIFORM_BYTES) {
        errno = EINVAL;
        return -1;
    }
    memcpy((uint8_t *)ctx->vs_kernarg.map + vertex_uniform_offset(ctx->screen), matrix,
           PIPE_VS_MATRIX_BYTES);
    memcpy((uint8_t *)ctx->vs_kernarg.map + (vertex_uniform_offset(ctx->screen) + 64u), &scale,
           sizeof(scale));
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    invalidate.context_id = ctx->context_id;
    invalidate.handle = ctx->vs_kernarg.handle;
    invalidate.offset = vertex_uniform_offset(ctx->screen);
    invalidate.bytes = PIPE_VS_UNIFORM_BYTES;
    invalidate.out_syncobj = fence->handle;
    if (opengpu_invalidate(ctx->screen->fd, &invalidate)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, NULL);
}

int pipe_opengpu_write_vs_matrices(struct pipe_opengpu_context *ctx,
                                   const float projection[16],
                                   const float modelview[16])
{
    struct drm_opengpu_invalidate invalidate = { 0 };
    struct pipe_opengpu_fence *fence;
    float scale = 65536.0f;
    float half = 0.5f;
    float zero = 0.0f;
    uint8_t *uniform;

    if (!ctx || !projection || !modelview || !ctx->vs_bound ||
        !ctx->vs_kernarg.map ||
        ctx->vs_kernarg.size < vertex_uniform_offset(ctx->screen) + PIPE_VS_UNIFORM_BYTES) {
        errno = EINVAL;
        return -1;
    }
    uniform = (uint8_t *)ctx->vs_kernarg.map + vertex_uniform_offset(ctx->screen);
    memcpy(uniform, projection, PIPE_VS_MATRIX_BYTES);
    memcpy(uniform + 64u, &scale,
           sizeof(scale));
    memcpy(uniform + 68, &zero, sizeof(zero));
    memcpy(uniform + 72, &half, sizeof(half));
    memcpy(uniform + 128u, modelview,
           PIPE_VS_MATRIX_BYTES);
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    invalidate.context_id = ctx->context_id;
    invalidate.handle = ctx->vs_kernarg.handle;
    invalidate.offset = vertex_uniform_offset(ctx->screen);
    invalidate.bytes = PIPE_VS_UNIFORM_BYTES;
    invalidate.out_syncobj = fence->handle;
    if (opengpu_invalidate(ctx->screen->fd, &invalidate)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, NULL);
}

int pipe_opengpu_write_fs_uniform(struct pipe_opengpu_context *ctx,
                                  uint32_t word)
{
    /* Replicate the uniform tail into every advertised fragment bank. */
    unsigned i;

    if (!ctx || !ctx->fs_bound || !ctx->kernarg.map ||
        ctx->kernarg.size < fragment_kernarg_bytes(ctx->screen)) {
        errno = EINVAL;
        return -1;
    }
    for (i = 0; i < opengpu_shader_resource_banks(ctx->screen->shader_config); i++) {
        struct drm_opengpu_invalidate invalidate = { 0 };
        struct pipe_opengpu_fence *fence;

        memcpy((uint8_t *)ctx->kernarg.map +
               i * pipe_opengpu_screen_fs_bank_stride(ctx->screen) +
               fragment_uniform_offset(ctx->screen),
               &word, sizeof(word));
        fence = alloc_fence(ctx->screen->fd);
        if (!fence)
            return -1;
        invalidate.context_id = ctx->context_id;
        invalidate.handle = ctx->kernarg.handle;
        invalidate.offset = i * pipe_opengpu_screen_fs_bank_stride(ctx->screen) +
                            (fragment_uniform_offset(ctx->screen) & ~63u);
        invalidate.bytes = 64;
        invalidate.out_syncobj = fence->handle;
        if (opengpu_invalidate(ctx->screen->fd, &invalidate)) {
            pipe_opengpu_fence_reference(&fence, NULL);
            return -1;
        }
        if (finish_or_hand_off(ctx, fence, NULL))
            return -1;
    }
    return 0;
}

int pipe_opengpu_write_fs_block(struct pipe_opengpu_context *ctx,
                                const float *words, unsigned nfloats)
{
    unsigned bytes, bank, off;

    if (!ctx || !words || !nfloats || !ctx->fs_bound || !ctx->kernarg.map ||
        ctx->kernarg.size < fragment_kernarg_bytes(ctx->screen) ||
        nfloats > 8u) {
        errno = EINVAL;
        return -1;
    }
    bytes = nfloats * 4u;
    for (bank = 0; bank < opengpu_shader_resource_banks(ctx->screen->shader_config); bank++) {
        memcpy((uint8_t *)ctx->kernarg.map +
               bank * pipe_opengpu_screen_fs_bank_stride(ctx->screen) +
               fragment_uniform_offset(ctx->screen),
               words, bytes);
        for (off = fragment_uniform_offset(ctx->screen) & ~63u;
             off < fragment_uniform_offset(ctx->screen) + bytes; off += 64u) {
            struct drm_opengpu_invalidate invalidate = { 0 };
            struct pipe_opengpu_fence *fence = alloc_fence(ctx->screen->fd);

            if (!fence)
                return -1;
            invalidate.context_id = ctx->context_id;
            invalidate.handle = ctx->kernarg.handle;
            invalidate.offset = bank * pipe_opengpu_screen_fs_bank_stride(ctx->screen) + off;
            invalidate.bytes = 64;
            invalidate.out_syncobj = fence->handle;
            if (opengpu_invalidate(ctx->screen->fd, &invalidate)) {
                pipe_opengpu_fence_reference(&fence, NULL);
                return -1;
            }
            if (finish_or_hand_off(ctx, fence, NULL))
                return -1;
        }
    }
    return 0;
}

void pipe_opengpu_log_vs_kernarg(struct pipe_opengpu_context *ctx)
{
    const uint32_t *w;
    uint32_t probes[19];
    const unsigned offsets[19] = {0, 96, 256, 288, 320, 352, 384, 512,
        560, 572, 576, 584, 416, 420, 424, 428, 464, 480, 448};
    unsigned i;

    if (!ctx || !ctx->vs_kernarg.map ||
        ctx->vs_kernarg.size < vertex_uniform_offset(ctx->screen) + PIPE_VS_UNIFORM_BYTES)
        return;
    w = ctx->vs_kernarg.map;
    for (i = 0; i < 19; i++) {
        unsigned old = offsets[i];
        unsigned off = old < 512 ?
            (old / 32) * opengpu_shader_array_stride(ctx->screen->shader_config) + old % 32 :
            vertex_uniform_offset(ctx->screen) + old - 512;
        probes[i] = w[off / 4];
    }
    fprintf(stderr,
            "opengpu: kernarg px=%08x pw=%08x cx=%08x cy=%08x cz=%08x cw=%08x "
            "color=%08x m00=%08x m03=%08x m33=%08x scale=%08x inv=%08x "
            "m00ld=%08x m01ld=%08x m02ld=%08x m03ld=%08x f2ld=%08x "
            "partial=%08x rawx=%08x\n",
            probes[0], probes[1], probes[2], probes[3], probes[4], probes[5],
            probes[6], probes[7], probes[8], probes[9], probes[10], probes[11],
            probes[12], probes[13], probes[14], probes[15], probes[16], probes[17],
            probes[18]);
}

int pipe_opengpu_set_vertex_buffer(struct pipe_opengpu_context *ctx,
                                   struct pipe_opengpu_resource *vb,
                                   uint32_t stride, uint32_t bytes)
{
    struct drm_opengpu_resource binding = { 0 };
    int fd;

    if (!ctx) {
        errno = EINVAL;
        return -1;
    }
    if (ctx->vb_bound && ctx->vertex_buffer == vb &&
        ctx->vertex_stride == stride && ctx->vertex_bytes == bytes)
        return 0;
    fd = ctx->screen->fd;
    if (ctx->vb_bound) {
        opengpu_unbind(fd, ctx->context_id, PIPE_VB_SLOT);
        ctx->vb_bound = 0;
        ctx->vertex_buffer = NULL;
        ctx->vertex_stride = 0;
        ctx->vertex_bytes = 0;
    }
    if (!vb)
        return 0;
    if (!(ctx->screen->caps & OPENGPU_CAP_VERTEX_CORE) || !stride || !bytes ||
        bytes > vb->buffer.size || (bytes % stride)) {
        errno = EINVAL;
        return -1;
    }
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_VB_SLOT;
    binding.handle = vb->buffer.handle;
    binding.type = OPENGPU_RESOURCE_VERTEX_BUFFER;
    binding.size = bytes;
    if (opengpu_bind(fd, &binding))
        return -1;
    ctx->vertex_buffer = vb;
    ctx->vertex_stride = stride;
    ctx->vertex_bytes = bytes;
    ctx->vb_bound = 1;
    return 0;
}

int pipe_opengpu_draw_vbo(struct pipe_opengpu_context *ctx,
                          const struct drm_opengpu_draw *draw,
                          struct pipe_opengpu_fence **out_fence)
{
    struct opengpu_buffer commands = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct pipe_opengpu_fence *fence = NULL;
    int fd, status = -1;

    if (!ctx || !draw || !ctx->color || !ctx->fb_width) {
        errno = EINVAL;
        return -1;
    }
    if ((ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) && !ctx->fs_bound) {
        errno = EINVAL;
        return -1;
    }
    if (!(ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) && ctx->fs_bound) {
        errno = EINVAL;
        return -1;
    }
    if ((draw->state & OPENGPU_DRAW_STATE_TEX_ENABLE) && !ctx->tex_bound) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    fence = alloc_fence(fd);
    if (!fence)
        return -1;
    if (opengpu_buffer_create(fd, sizeof(struct drm_opengpu_draw), &commands))
        goto out;
    memcpy(commands.map, draw, sizeof(*draw));
    submit.context_id = ctx->context_id;
    submit.command_handle = commands.handle;
    submit.command_count = 1;
    submit.out_syncobj = fence->handle;
    if (apply_color_submit(ctx, &submit))
        goto out;
    if (ctx->fs_bound) {
        submit.shader_slot = PIPE_FS_SLOT;
        submit.kernarg_slot = PIPE_KERNARG_SLOT;
    }
    if (ctx->tex_bound)
        submit.texture_slot = PIPE_TEX_SLOT;
    if (apply_depth_submit(ctx, &submit))
        goto out;
    if (opengpu_render(fd, &submit))
        goto out;
    if (finish_or_hand_off(ctx, fence, out_fence))
        goto out;
    fence = NULL;
    status = 0;
out:
    if (fence)
        pipe_opengpu_fence_reference(&fence, NULL);
    if (commands.handle)
        opengpu_buffer_destroy(fd, &commands);
    return status;
}

int pipe_opengpu_draw_vertex(struct pipe_opengpu_context *ctx,
                             const struct drm_opengpu_vertex_draw *draw,
                             struct pipe_opengpu_fence **out_fence)
{
    struct opengpu_buffer commands = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct pipe_opengpu_fence *fence = NULL;
    int fd, status = -1;

    if (!ctx || !draw || !ctx->color || !ctx->fb_width) {
        errno = EINVAL;
        return -1;
    }
    if (!(ctx->screen->caps & OPENGPU_CAP_VERTEX_CORE) ||
        !(ctx->screen->caps & OPENGPU_CAP_FRAGMENT_CORE) ||
        !ctx->fs_bound || !ctx->vs_bound || !ctx->vb_bound) {
        errno = EINVAL;
        return -1;
    }
    if ((draw->state & OPENGPU_DRAW_STATE_TEX_ENABLE) && !ctx->tex_bound) {
        errno = EINVAL;
        return -1;
    }
    fd = ctx->screen->fd;
    fence = alloc_fence(fd);
    if (!fence)
        return -1;
    if (opengpu_buffer_create(fd, sizeof(struct drm_opengpu_vertex_draw),
                              &commands))
        goto out;
    memcpy(commands.map, draw, sizeof(*draw));
    submit.context_id = ctx->context_id;
    submit.command_handle = commands.handle;
    submit.command_count = 1;
    submit.flags = OPENGPU_SUBMIT_VERTEX_CORE;
    submit.shader_slot = PIPE_FS_SLOT;
    submit.kernarg_slot = PIPE_KERNARG_SLOT;
    submit.vertex_buffer_slot = PIPE_VB_SLOT;
    submit.vertex_shader_slot = PIPE_VS_SLOT;
    submit.vertex_kernarg_slot = PIPE_VS_KERNARG_SLOT;
    submit.out_syncobj = fence->handle;
    if (apply_color_submit(ctx, &submit))
        goto out;
    if (ctx->tex_bound)
        submit.texture_slot = PIPE_TEX_SLOT;
    if (apply_depth_submit(ctx, &submit))
        goto out;
    if (opengpu_render(fd, &submit))
        goto out;
    if (finish_or_hand_off(ctx, fence, out_fence))
        goto out;
    fence = NULL;
    status = 0;
out:
    if (fence)
        pipe_opengpu_fence_reference(&fence, NULL);
    if (commands.handle)
        opengpu_buffer_destroy(fd, &commands);
    return status;
}

int pipe_opengpu_present(struct pipe_opengpu_screen *screen,
                         struct pipe_opengpu_resource *color)
{
    struct drm_mode_card_res resources = { 0 };
    struct drm_mode_get_connector connector = { 0 };
    struct drm_mode_modeinfo mode = { 0 };
    struct drm_mode_modeinfo modes[8] = { 0 };
    struct drm_mode_fb_cmd2 fb = { .pixel_format = DRM_FORMAT_RGBA8888 };
    struct drm_mode_crtc crtc = { 0 };
    struct drm_mode_crtc current = { 0 };
    uint32_t connector_id = 0, crtc_id = 0;
    uint32_t encoders[8] = { 0 }, framebuffers[8] = { 0 };
    uint32_t connector_encoders[8] = { 0 }, properties[32] = { 0 };
    uint64_t property_values[32] = { 0 };
    uint32_t i;
    int have_mode = 0;

    if (!screen || !color || !color->buffer.handle || !color->pitch ||
        !color->width || !color->height) {
        errno = EINVAL;
        return -1;
    }
    if (ioctl(screen->fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        return -1;
    if (resources.count_connectors != 1 || resources.count_crtcs != 1 ||
        resources.count_encoders > 8 || resources.count_fbs > 8) {
        errno = ENODEV;
        return -1;
    }
    resources.connector_id_ptr = (uintptr_t)&connector_id;
    resources.crtc_id_ptr = (uintptr_t)&crtc_id;
    resources.encoder_id_ptr = (uintptr_t)encoders;
    resources.fb_id_ptr = (uintptr_t)framebuffers;
    if (ioctl(screen->fd, DRM_IOCTL_MODE_GETRESOURCES, &resources) < 0)
        return -1;
    connector.connector_id = connector_id;
    if (ioctl(screen->fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0 ||
        !connector.count_modes || connector.count_modes > 8 ||
        connector.count_encoders > 8 ||
        connector.count_props > 32) {
        errno = ENODEV;
        return -1;
    }
    connector.modes_ptr = (uintptr_t)modes;
    connector.encoders_ptr = (uintptr_t)connector_encoders;
    connector.props_ptr = (uintptr_t)properties;
    connector.prop_values_ptr = (uintptr_t)property_values;
    if (ioctl(screen->fd, DRM_IOCTL_MODE_GETCONNECTOR, &connector) < 0)
        return -1;
    if (!connector.count_modes || connector.count_modes > 8 ||
        connector.count_encoders > 8 || connector.count_props > 32) {
        errno = EAGAIN;
        return -1;
    }

    /* Dynamic KMS advertises the native mode plus smaller alternatives. Use
     * the active CRTC mode when it matches the rendered buffer; otherwise
     * select the advertised mode with the same dimensions. */
    current.crtc_id = crtc_id;
    if (!ioctl(screen->fd, DRM_IOCTL_MODE_GETCRTC, &current) &&
        current.mode_valid && current.mode.hdisplay == color->width &&
        current.mode.vdisplay == color->height) {
        mode = current.mode;
        have_mode = 1;
    } else {
        for (i = 0; i < connector.count_modes; i++) {
            if (modes[i].hdisplay == color->width &&
                modes[i].vdisplay == color->height) {
                mode = modes[i];
                have_mode = 1;
                break;
            }
        }
    }
    if (!have_mode) {
        errno = EINVAL;
        return -1;
    }
    if (!color->fb_id) {
        fb.width = color->width;
        fb.height = color->height;
        fb.pitches[0] = color->pitch;
        fb.handles[0] = color->buffer.handle;
        if (ioctl(screen->fd, DRM_IOCTL_MODE_ADDFB2, &fb) < 0)
            return -1;
        color->fb_id = fb.fb_id;
    }
    crtc.crtc_id = crtc_id;
    crtc.fb_id = color->fb_id;
    crtc.set_connectors_ptr = (uintptr_t)&connector_id;
    crtc.count_connectors = 1;
    crtc.mode_valid = 1;
    crtc.mode = mode;
    return ioctl(screen->fd, DRM_IOCTL_MODE_SETCRTC, &crtc);
}

int pipe_opengpu_fence_finish(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              int64_t timeout_ms)
{
    (void)ctx;
    if (!fence) {
        errno = EINVAL;
        return -1;
    }
    return opengpu_sync_wait_success(fence->fd, fence->handle, timeout_ms);
}

void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst,
                                  struct pipe_opengpu_fence *src)
{
    struct pipe_opengpu_fence *old;

    if (!dst)
        return;
    old = *dst;
    if (old == src)
        return;
    *dst = src;
    if (old) {
        opengpu_sync_destroy(old->fd, old->handle);
        free(old);
    }
}
