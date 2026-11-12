/* SPDX-License-Identifier: MIT */
#include "pipe_opengpu.h"
#include "opengpu_shader_resources.h"
#include "opengpu_pixel_format.h"
#include "opengpu_buffer_texture.h"
#include "opengpu_kernarg_va.h"
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
#define PIPE_CS_GEM_BYTES 2048u
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
    uint64_t driver_features;
    uint32_t shader_config;
    struct pipe_opengpu_resource *resources;
    struct pipe_opengpu_context *orphans;
    struct pipe_opengpu_screen *terminal_next;
    unsigned terminal_owned;
};

struct pipe_opengpu_resource {
    struct opengpu_buffer buffer;
    uint32_t width;
    uint32_t height;
    uint32_t pitch;
    uint32_t fb_id;
    uint32_t format;
    struct pipe_opengpu_resource *next;
    unsigned references;
};

struct pipe_opengpu_fence {
    int fd;
    uint32_t handle;
};

struct pipe_opengpu_context {
    struct pipe_opengpu_screen *screen;
    uint32_t context_id;
    struct pipe_opengpu_resource *color[4];
    unsigned nr_colors;
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
    int cs_storage_failed;
    unsigned cs_storage_bound;
    uint32_t cs_textures_bound;
    unsigned cs_images_bound;
    struct {
        struct pipe_opengpu_resource *resource;
        uint64_t bytes;
    } cs_pending_writes[8];
    unsigned cs_pending_count;
    struct pipe_opengpu_fence *cs_failure_fence;
    uint32_t cs_timeout_ms;
    struct pipe_opengpu_context *orphan_next;
    unsigned orphaned;
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
static int publish_compute_writes(struct pipe_opengpu_context *ctx, uint32_t timeout_ms);
static int reap_terminal_screens(void);
static int reap_screen_contexts(struct pipe_opengpu_screen *screen);
static void abandon_or_retain_screen(struct pipe_opengpu_screen *screen);
/* As with screen->resources, these owners require externally serialized winsys
 * calls. There is no background worker or general concurrent-context promise. */
static struct pipe_opengpu_screen *terminal_screens;

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
    if (opengpu_driver_features(fd, &screen->driver_features)) {
        int saved = errno;

        close(fd);
        free(screen);
        errno = saved;
        return NULL;
    }
    screen->fd = fd;
    return screen;
}

struct pipe_opengpu_screen *pipe_opengpu_screen_create(const char *path)
{
    int fd;

    if (reap_terminal_screens()) return NULL;
    fd = opengpu_open(path);

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
    if (reap_terminal_screens()) return NULL;
    owned = dup(fd);
    if (owned < 0)
        return NULL;
    return screen_from_fd(owned);
}

void pipe_opengpu_screen_destroy(struct pipe_opengpu_screen *screen)
{
    abandon_or_retain_screen(screen);
}

int pipe_opengpu_screen_fd(const struct pipe_opengpu_screen *screen)
{
    return screen ? screen->fd : -1;
}

uint64_t pipe_opengpu_screen_capabilities(const struct pipe_opengpu_screen *screen)
{
    return screen ? screen->caps : 0;
}

int pipe_opengpu_screen_clip_depth(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_CLIP_DEPTH);
}

int pipe_opengpu_screen_sampler_nearest(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_SAMPLER_NEAREST);
}

int pipe_opengpu_screen_pixel_center(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_PIXEL_CENTER);
}

int pipe_opengpu_screen_sampler_axis_wrap(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_SAMPLER_AXIS_WRAP);
}

int pipe_opengpu_screen_biased_clamp(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_BIASED_CLAMP);
}

int pipe_opengpu_screen_mirrored_repeat(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_MIRRORED_REPEAT);
}

int pipe_opengpu_screen_viewport(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_VIEWPORT);
}

int pipe_opengpu_screen_depth_range(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_DEPTH_RANGE);
}

int pipe_opengpu_screen_constant_blend(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_CONSTANT_BLEND);
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
    if (reap_screen_contexts(screen)) return NULL;
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

static void release_context_storage(struct pipe_opengpu_context *ctx)
{
    pipe_opengpu_fence_reference(&ctx->cs_failure_fence, NULL);
    if (ctx->shader.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->shader);
    if (ctx->kernarg.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->kernarg);
    if (ctx->vs_shader.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->vs_shader);
    if (ctx->vs_kernarg.handle)
        opengpu_buffer_destroy(ctx->screen->fd, &ctx->vs_kernarg);
    for (unsigned i = 0; i < ctx->cs_pending_count; i++)
        pipe_opengpu_resource_destroy(ctx->screen, ctx->cs_pending_writes[i].resource);
    free(ctx);
}

static int finish_context_destroy(struct pipe_opengpu_context *ctx)
{
    if (!ctx)
        return 0;
    /* Namespace deletion is not producer completion. A failed accepted wait
     * retains its original syncobj and every pending BO until that fence has
     * really signaled and publication completed. Never replay the shader. */
    if (ctx->cs_failure_fence) {
        if (opengpu_sync_wait(ctx->cs_failure_fence->fd, ctx->cs_failure_fence->handle, 0))
            return -1;
        pipe_opengpu_fence_reference(&ctx->cs_failure_fence, NULL);
    }
    if (ctx->cs_pending_count && publish_compute_writes(ctx, ctx->cs_timeout_ms ? ctx->cs_timeout_ms : 30000))
        return -1;
    /* The kernel retains queued-job VM/GEM references beyond this successful
     * namespace deletion if its asynchronous free-job callback is pending. */
    if (ctx->context_id && opengpu_context_destroy(ctx->screen->fd, ctx->context_id)) {
        ctx->cs_storage_failed = 1;
        return -1;
    }
    release_context_storage(ctx);
    return 0;
}

int pipe_opengpu_context_destroy_checked(struct pipe_opengpu_context *ctx)
{
    if (ctx && ctx->orphaned) { errno = EINVAL; return -1; }
    return finish_context_destroy(ctx);
}

void pipe_opengpu_context_destroy(struct pipe_opengpu_context *ctx)
{
    int saved = errno;
    if (ctx && !ctx->orphaned && finish_context_destroy(ctx)) {
        ctx->orphaned = 1;
        ctx->orphan_next = ctx->screen->orphans;
        ctx->screen->orphans = ctx;
    }
    errno = saved;
}

static int reap_orphans(struct pipe_opengpu_screen *screen)
{
    while (screen->orphans) {
        struct pipe_opengpu_context *ctx = screen->orphans;
        struct pipe_opengpu_context *next = ctx->orphan_next;
        if (finish_context_destroy(ctx)) return -1;
        screen->orphans = next;
    }
    return 0;
}

static int reap_terminal_screens(void)
{
    int saved = errno;
    while (terminal_screens) {
        struct pipe_opengpu_screen *screen = terminal_screens;
        if (reap_orphans(screen)) return -1;
        terminal_screens = screen->terminal_next;
        close(screen->fd);
        free(screen);
    }
    errno = saved;
    return 0;
}

static int reap_screen_contexts(struct pipe_opengpu_screen *screen)
{
    int saved = errno;
    if (screen->terminal_owned) { errno = EIO; return -1; }
    if (reap_terminal_screens() || reap_orphans(screen)) return -1;
    errno = saved;
    return 0;
}

static void abandon_or_retain_screen(struct pipe_opengpu_screen *screen)
{
    int saved = errno, failure = 0;
    if (!screen || screen->terminal_owned) return;
    struct pipe_opengpu_context **link = &screen->orphans;
    while (*link) {
        struct pipe_opengpu_context *ctx = *link;
        /* End this screen's use, not its job's hardware lifetime or visibility.
         * The real ioctl removes the namespace; job context/GEM krefs retain
         * VM/bindings until free-job (compute.c context_free/release and
         * scheduler.c submit/free_job), including delayed cleanup. */
        if (ctx->context_id && opengpu_context_destroy(screen->fd, ctx->context_id)) {
            if (!failure) failure = errno;
            link = &ctx->orphan_next;
            continue;
        }
        *link = ctx->orphan_next;
        release_context_storage(ctx);
    }
    if (screen->orphans) {
        /* A dup close cannot prove last-file teardown. Keep an explicit
         * process owner and its live fd for later safe poll/publication. */
        screen->terminal_owned = 1;
        screen->terminal_next = terminal_screens;
        terminal_screens = screen;
        errno = failure;
        return;
    }
    close(screen->fd);
    free(screen);
    errno = saved;
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
    res->references = 1;
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
    uint64_t pixels, allocation;

    if (!screen || !width || !height) {
        errno = EINVAL;
        return NULL;
    }
    /* The 32-bit dimensions multiply safely in 64 bits, but multiplying
     * that product by four can wrap before a byte-size check. */
    pixels = (uint64_t)width * height;
    if (pixels > 0x7fffffffu / 4u) {
        errno = EINVAL;
        return NULL;
    }
    /* Texture uploads and producer readback invalidate complete 64-byte
     * cache lines. Keep pitch tightly packed but own the final partial line. */
    allocation = (pixels * 4u + 63u) & ~UINT64_C(63);
    res = pipe_opengpu_resource_create(screen, (uint32_t)allocation);
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
    if (__atomic_sub_fetch(&res->references, 1, __ATOMIC_ACQ_REL))
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

int pipe_opengpu_resource_set_format(struct pipe_opengpu_screen *screen,
                                      struct pipe_opengpu_resource *res, uint32_t format)
{
    if (!screen || !res || format > OPENGPU_FORMAT_MAX ||
        (format && !(screen->caps & OPENGPU_CAP_NATIVE_PIXEL_FORMATS)) ||
        (res->fb_id && res->format != format)) {
        errno = EINVAL;
        return -1;
    }
    res->format = format;
    return 0;
}

void pipe_opengpu_set_framebuffer(struct pipe_opengpu_context *ctx,
                                  struct pipe_opengpu_resource *color,
                                  uint32_t width, uint32_t height)
{
    if (!ctx)
        return;
    memset(ctx->color, 0, sizeof(ctx->color));
    ctx->color[0] = color;
    ctx->nr_colors = color ? 1 : 0;
    ctx->fb_width = width;
    ctx->fb_height = height;
}

void pipe_opengpu_set_framebuffer_mrt(struct pipe_opengpu_context *ctx,
                                      struct pipe_opengpu_resource *colors[4],
                                      unsigned nr_colors,
                                      uint32_t width, uint32_t height)
{
    unsigned i;

    if (!ctx || nr_colors > 4)
        return;
    memset(ctx->color, 0, sizeof(ctx->color));
    for (i = 0; i < nr_colors; i++)
        ctx->color[i] = colors[i];
    ctx->nr_colors = nr_colors;
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

int pipe_opengpu_screen_compute_limits(const struct pipe_opengpu_screen *screen,
                                       uint32_t *items, uint32_t *axis)
{
    uint32_t n, a;
    if (!screen || !items || !axis || items == axis) { errno = EINVAL; return -1; }
    if (!(screen->driver_features & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE)) {
        errno = ENOTSUP; return -1;
    }
    if (opengpu_compute_local_limits(screen->fd, &n, &a)) return -1;
    if (!n || !a || n > 255 || a > n) { errno = EPROTO; return -1; }
    *items = n; *axis = a;
    return 0;
}

int pipe_opengpu_screen_compute_shared_limits(const struct pipe_opengpu_screen *screen,
    uint32_t *base, uint32_t *bytes, uint32_t *lanes)
{
    uint32_t address, capacity, width, items, axis;
    if (!base || !bytes || !lanes || base == bytes || base == lanes || bytes == lanes) {
        errno = EINVAL; return -1;
    }
    if (pipe_opengpu_screen_compute_limits(screen, &items, &axis) ||
        opengpu_compute_shared_limits(screen->fd, &address, &capacity, &width))
        return -1;
    if (!capacity || !width) { errno = ENOTSUP; return -1; }
    if (width > items || capacity < (uint64_t)width * 4u) {
        errno = EPROTO; return -1;
    }
    *base = address; *bytes = capacity; *lanes = width;
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
    if (ctx->cs_storage_failed) { errno = EIO; return -1; }
    if (reap_screen_contexts(ctx->screen)) return -1;
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
        goto fail;
    memcpy(ctx->shader.map, code, bytes);
    memset(ctx->kernarg.map, 0, PIPE_CS_KERNARG_BYTES);
    binding.context_id = ctx->context_id;
    binding.slot = PIPE_CS_SLOT;
    binding.handle = ctx->shader.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_SHADER;
    binding.size = PIPE_CS_GEM_BYTES;
    if (opengpu_bind(fd, &binding)) goto fail;
    binding.slot = PIPE_CS_KERNARG_SLOT;
    binding.handle = ctx->kernarg.handle;
    binding.type = OPENGPU_RESOURCE_COMPUTE_KERNARG;
    binding.size = PIPE_CS_KERNARG_BYTES;
    binding.flags = OPENGPU_RESOURCE_UNCACHED;
    if (opengpu_bind(fd, &binding)) {
        opengpu_unbind(fd, ctx->context_id, PIPE_CS_SLOT);
        goto fail;
    }
    ctx->cs_bound = 1;
    ctx->cs_storage_failed = 0;
    return 0;
fail: {
    int saved = errno;
    if (ctx->shader.handle) opengpu_buffer_destroy(fd, &ctx->shader);
    if (ctx->kernarg.handle) opengpu_buffer_destroy(fd, &ctx->kernarg);
    memset(&ctx->shader, 0, sizeof(ctx->shader));
    memset(&ctx->kernarg, 0, sizeof(ctx->kernarg));
    errno = saved;
    return -1;
}
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
    if (ctx->cs_storage_failed) { errno = EIO; return -1; }
    if (reap_screen_contexts(ctx->screen)) return -1;
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

uint32_t pipe_opengpu_screen_storage_alignment(const struct pipe_opengpu_screen *screen)
{
    if (!screen || !(screen->driver_features & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE)) return 0;
    return screen->driver_features & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS ? 256 : 4096;
}

int pipe_opengpu_storage_view_validate(const struct pipe_opengpu_screen *screen,
                                       const struct pipe_opengpu_storage_view *v)
{
    const struct pipe_opengpu_resource *resource;
    uint64_t size;
    uint32_t align = pipe_opengpu_screen_storage_alignment(screen);
    if (!screen || !v || !align) { errno = ENOTSUP; return -1; }
    if (!v->resource) {
        if (v->offset || v->bytes || v->access) { errno = EINVAL; return -1; }
        return 0;
    }
    for (resource = screen->resources; resource && resource != v->resource; resource = resource->next) {}
    if (!resource) { errno = EINVAL; return -1; }
    size = pipe_opengpu_resource_size(resource);
    if (!v->access || (v->access & ~(OPENGPU_COMPUTE_STORAGE_READ |
            OPENGPU_COMPUTE_STORAGE_WRITE)) || (v->offset & (align - 1)) ||
        !v->bytes || (align == 4096 && (v->bytes & 63)) || v->bytes > 0x8000000ull ||
        v->offset > size || v->bytes > size - v->offset) {
        errno = EINVAL; return -1;
    }
    return 0;
}

int pipe_opengpu_screen_compute_uniforms(const struct pipe_opengpu_screen *screen)
{
    return screen && (screen->driver_features & OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS);
}

int pipe_opengpu_screen_compute_buffer_textures(const struct pipe_opengpu_screen *screen)
{
    const uint64_t need=OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE |
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE;
    return screen && (screen->driver_features & need)==need;
}

int pipe_opengpu_screen_compute_buffer_images(const struct pipe_opengpu_screen *screen)
{
    const uint64_t need=OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES |
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES;
    return screen && (screen->driver_features & need)==need;
}

int pipe_opengpu_screen_compute_image_backings(const struct pipe_opengpu_screen *screen)
{
    return pipe_opengpu_screen_compute_buffer_images(screen) &&
        (screen->driver_features & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS);
}

int pipe_opengpu_uniform_view_validate(const struct pipe_opengpu_screen *screen,
    const struct pipe_opengpu_uniform_view *v, unsigned slot)
{
    const struct pipe_opengpu_resource *resource;
    uint64_t size;
    if (!screen || !v || slot > 12) { errno = EINVAL; return -1; }
    if (!pipe_opengpu_screen_compute_uniforms(screen)) { errno = ENOTSUP; return -1; }
    if (!v->resource) {
        if (v->offset || v->bytes) { errno = EINVAL; return -1; }
        return 0;
    }
    for (resource = screen->resources; resource && resource != v->resource; resource = resource->next) {}
    if (!resource) { errno = EINVAL; return -1; }
    size = pipe_opengpu_resource_size(resource);
    if (!v->bytes || (v->offset & 3) || (v->bytes & 3) ||
        v->bytes > (slot ? 16384u : 4096u) ||
        v->offset > size || v->bytes > size - v->offset) { errno = EINVAL; return -1; }
    return 0;
}

static int remember_compute_write(struct pipe_opengpu_context *ctx,
    struct pipe_opengpu_resource *resource, uint64_t bytes)
{
    for (unsigned i = 0; i < ctx->cs_pending_count; i++)
        if (ctx->cs_pending_writes[i].resource->buffer.handle == resource->buffer.handle) {
            if (ctx->cs_pending_writes[i].bytes < bytes) ctx->cs_pending_writes[i].bytes = bytes;
            return 0;
        }
    if (ctx->cs_pending_count == 8) { errno = EOVERFLOW; return -1; }
    __atomic_add_fetch(&resource->references, 1, __ATOMIC_RELAXED);
    ctx->cs_pending_writes[ctx->cs_pending_count].resource = resource;
    ctx->cs_pending_writes[ctx->cs_pending_count++].bytes = bytes;
    return 0;
}

static int publish_compute_writes(struct pipe_opengpu_context *ctx, uint32_t timeout_ms)
{
    for (unsigned i = 0; i < ctx->cs_pending_count; i++) {
        struct pipe_opengpu_resource *resource = ctx->cs_pending_writes[i].resource;
        struct pipe_opengpu_fence *fence = NULL;
        uint64_t bytes = ctx->cs_pending_writes[i].bytes;
        /* Invalidate, never write back. These services bind data uncached;
         * accepted stores have already drained before this publication step.
         * The enclosing cache line may use GEM padding, not extend a view. */
        if (bytes > UINT64_MAX - 63 || ((bytes + 63) & ~UINT64_C(63)) > resource->buffer.size) {
            ctx->cs_storage_failed = 1;
            errno = EINVAL;
            return -1;
        }
        bytes = (bytes + 63) & ~UINT64_C(63);
        if (pipe_opengpu_invalidate(ctx, resource, 0, bytes, &fence) ||
            pipe_opengpu_fence_finish(ctx, fence, timeout_ms)) {
            int saved = errno;
            if (fence) { ctx->cs_failure_fence = fence; fence = NULL; }
            pipe_opengpu_fence_reference(&fence, NULL);
            ctx->cs_storage_failed = 1;
            errno = saved;
            return -1; /* Keep *all* pending refs until safe teardown. */
        }
        pipe_opengpu_fence_reference(&fence, NULL);
    }
    for (unsigned i = 0; i < ctx->cs_pending_count; i++)
        pipe_opengpu_resource_destroy(ctx->screen, ctx->cs_pending_writes[i].resource);
    memset(ctx->cs_pending_writes, 0, sizeof(ctx->cs_pending_writes));
    ctx->cs_pending_count = 0;
    return 0;
}

int pipe_opengpu_compute_barrier(struct pipe_opengpu_context *ctx, uint32_t timeout_ms)
{
    if (!ctx || !timeout_ms) { errno = EINVAL; return -1; }
    if (ctx->cs_storage_failed) { errno = EIO; return -1; }
    if (reap_screen_contexts(ctx->screen)) return -1;
    return publish_compute_writes(ctx, timeout_ms);
}

static int live_buffer(const struct pipe_opengpu_screen *screen,
                       const struct pipe_opengpu_resource *resource, uint64_t bytes)
{
    const struct pipe_opengpu_resource *r;
    for (r = screen->resources; r && r != resource; r = r->next) {}
    return r && bytes && bytes <= r->buffer.size;
}

int pipe_opengpu_launch_grid_buffers(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view storage[4],
    const struct pipe_opengpu_buffer_texture textures[32],
    const struct pipe_opengpu_buffer_image images[4],
    const struct pipe_opengpu_uniform_view uniforms[13], uint32_t timeout_ms)
{
    struct drm_opengpu_compute_image_resources command = {0};
    struct drm_opengpu_compute_uniform_resources gum = {0};
    struct drm_opengpu_compute_texture_view views[32] = {{0}};
    struct drm_opengpu_compute *launch = uniforms ? &gum.resources.launch : &command.launch;
    uint32_t items, axis, texture_count = 0, image_mask = 0;
    uint64_t product = 1;
    struct pipe_opengpu_fence *fence = NULL;
    int ret, saved, accepted = 0;
    if (!ctx || !ctx->cs_bound || !grid || !local || !storage || !textures || !images || !timeout_ms) {
        errno = EINVAL; return -1;
    }
    if (ctx->cs_storage_failed) { errno = EIO; return -1; }
    if (reap_screen_contexts(ctx->screen)) return -1;
    ctx->cs_timeout_ms = timeout_ms;
    if (pipe_opengpu_screen_compute_limits(ctx->screen, &items, &axis)) return -1;
    for (unsigned i = 0; i < 3; i++) {
        if (!local[i] || local[i] > axis || product > items / local[i]) goto invalid;
        product *= local[i];
    }
    for (unsigned i = 0; i < 4; i++) {
        const struct pipe_opengpu_buffer_image *v = &images[i];
        struct drm_opengpu_compute_image_view expected;
        if (pipe_opengpu_storage_view_validate(ctx->screen, &storage[i])) return -1;
        if (!v->resource) {
            static const struct drm_opengpu_compute_image_view empty;
            if (v->binding_bytes || memcmp(&v->view, &empty, sizeof(empty))) goto invalid;
            continue;
        }
        if (!pipe_opengpu_screen_compute_buffer_images(ctx->screen) ||
            !(ctx->screen->driver_features & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS)) {
            errno = ENOTSUP; return -1;
        }
        if (!live_buffer(ctx->screen, v->resource, v->binding_bytes) ||
            v->binding_bytes > OPENGPU_STORAGE_VA_MAX_BYTES ||
            !opengpu_buffer_image_view_init(&expected, 39 + i, v->view.flags,
                v->view.storage_format, v->view.image_format, v->binding_bytes,
                v->view.offset, v->view.buffer_bytes) ||
            memcmp(&expected, &v->view, sizeof(expected))) goto invalid;
        command.images[i] = v->view; image_mask |= 1u << i;
    }
    for (unsigned i = 0; i < 32; i++) {
        const struct pipe_opengpu_buffer_texture *v = &textures[i];
        struct drm_opengpu_compute_texture_view expected;
        if (!v->resource) {
            static const struct drm_opengpu_compute_texture_view empty;
            if (v->binding_bytes || memcmp(&v->view, &empty, sizeof(empty))) goto invalid;
            continue;
        }
        if (!pipe_opengpu_screen_compute_buffer_textures(ctx->screen)) { errno = ENOTSUP; return -1; }
        /* A complete logical BO must fit the existing window. Do not bind a
         * shortened prefix or clamp a buffer view to hide this Linux limit. */
        if (!live_buffer(ctx->screen, v->resource, v->binding_bytes) ||
            v->binding_bytes > OPENGPU_TEXTURE_VA_STRIDE ||
            !opengpu_buffer_texture_view_init(&expected, 3 + i, v->view.format,
                v->binding_bytes, v->view.levels[0].offset, v->view.levels[0].buffer_bytes) ||
            v->view.swizzle_version > 1) goto invalid;
        expected.swizzle_version = v->view.swizzle_version;
        for (unsigned c = 0; c < 4; c++) {
            if (v->view.swizzle[c] > (v->view.swizzle_version ? 5u : 0u)) goto invalid;
            expected.swizzle[c] = v->view.swizzle[c];
        }
        if (memcmp(&expected, &v->view, sizeof(expected))) goto invalid;
        views[i] = v->view; texture_count = i + 1;
    }
    {
        uint64_t combined = image_mask ?
            (texture_count ? OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE :
                             OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE) :
            OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES;
        if (!(ctx->screen->driver_features & combined)) { errno = ENOTSUP; return -1; }
    }
    if (uniforms) {
        unsigned any = 0;
        if (!pipe_opengpu_screen_compute_uniforms(ctx->screen)) { errno = ENOTSUP; return -1; }
        for (unsigned i = 0; i < 13; i++) {
            const struct pipe_opengpu_uniform_view *v = &uniforms[i];
            struct drm_opengpu_compute_uniform_view *out = i ? &gum.blocks[i - 1] : &gum.defaults;
            if (pipe_opengpu_uniform_view_validate(ctx->screen, v, i)) return -1;
            if (!v->resource) continue;
            out->handle = v->resource->buffer.handle; out->offset = v->offset; out->bytes = (uint32_t)v->bytes;
            if (i) gum.active_mask |= 1u << (i - 1);
            any = 1;
        }
        if (!any) goto invalid;
        gum.version = OPENGPU_COMPUTE_UNIFORM_VERSION;
    }
    if (!grid[0] || !grid[1] || !grid[2]) return 0;
    if (pipe_opengpu_compute_barrier(ctx, timeout_ms)) return -1;
    /* All input/feature/range checks precede the first mapping mutation. The
     * caller discards this private context on any partial bind/submit failure. */
    for (unsigned i = 0; i < 40; i++) {
        struct drm_opengpu_resource binding = {.context_id = ctx->context_id};
        struct pipe_opengpu_resource *resource;
        uint32_t bit, *mask;
        if (i < 32) {
            resource = textures[i].resource; binding.slot = 3 + i;
            binding.type = OPENGPU_RESOURCE_COMPUTE_TEXTURE; binding.size = textures[i].binding_bytes;
            bit = UINT32_C(1) << i; mask = &ctx->cs_textures_bound;
        } else if (i < 36) {
            unsigned j = i - 32;
            resource = storage[j].resource; binding.slot = 35 + j;
            binding.type = OPENGPU_RESOURCE_COMPUTE_STORAGE;
            binding.offset = storage[j].offset; binding.size = storage[j].bytes;
            bit = 1u << j; mask = &ctx->cs_storage_bound;
            if (resource) command.bindings[j] = (struct drm_opengpu_compute_storage_binding) {binding.slot, storage[j].access};
        } else {
            unsigned j = i - 36;
            resource = images[j].resource; binding.slot = 39 + j;
            binding.type = OPENGPU_RESOURCE_COMPUTE_STORAGE; binding.size = images[j].binding_bytes;
            bit = 1u << j; mask = &ctx->cs_images_bound;
        }
        if (resource) {
            binding.handle = resource->buffer.handle;
            if (opengpu_bind(ctx->screen->fd, &binding)) goto poisoned;
            *mask |= bit;
        } else if (*mask & bit) {
            if (opengpu_unbind(ctx->screen->fd, ctx->context_id, binding.slot)) goto poisoned;
            *mask &= ~bit;
        }
    }
    memcpy(ctx->kernarg.map, grid, 12);
    fence = alloc_fence(ctx->screen->fd);
    if (!fence) goto poisoned;
    launch->context_id = ctx->context_id; launch->shader_slot = PIPE_CS_SLOT;
    launch->kernarg_slot = PIPE_CS_KERNARG_SLOT;
    memcpy(launch->grid, grid, sizeof(launch->grid)); memcpy(launch->local, local, sizeof(launch->local));
    launch->out_syncobj = fence->handle;
    command.textures = (uintptr_t)views; command.texture_count = texture_count;
    if (uniforms) {
        memcpy(gum.resources.bindings, command.bindings, sizeof(command.bindings));
        memcpy(gum.resources.images, command.images, sizeof(command.images));
        gum.resources.textures = command.textures; gum.resources.texture_count = texture_count;
        gum.resources.resource_flags = OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE |
            (image_mask ? OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES : 0);
        ret = opengpu_compute_uniform_resources(ctx->screen->fd, &gum);
    } else if (image_mask) ret = opengpu_compute_image_resources(ctx->screen->fd, &command);
    else {
        struct drm_opengpu_compute_resources request = {.launch = *launch,
            .views = command.textures, .view_count = texture_count};
        memcpy(request.bindings, command.bindings, sizeof(request.bindings));
        ret = opengpu_compute_resources(ctx->screen->fd, &request);
    }
    accepted = !ret;
    if (!ret) {
        for (unsigned i = 0; i < 4; i++) {
            if (storage[i].resource && (storage[i].access & OPENGPU_COMPUTE_STORAGE_WRITE) &&
                remember_compute_write(ctx, storage[i].resource, storage[i].offset + storage[i].bytes)) goto poisoned;
            if (images[i].resource && (images[i].view.flags & OPENGPU_COMPUTE_IMAGE_WRITE) &&
                remember_compute_write(ctx, images[i].resource, images[i].binding_bytes)) goto poisoned;
        }
        ret = pipe_opengpu_fence_finish(ctx, fence, timeout_ms);
    }
    if (ret) goto poisoned;
    pipe_opengpu_fence_reference(&fence, NULL);
    return 0;
invalid:
    errno = EINVAL;
    return -1;
poisoned:
    saved = errno;
    if (accepted && fence) { ctx->cs_failure_fence = fence; fence = NULL; }
    pipe_opengpu_fence_reference(&fence, NULL);
    ctx->cs_storage_failed = 1;
    errno = saved;
    return -1;
}

/* Compute launch with an atomic counter bound to slot 0..3. Mirrors
 * pipe_opengpu_launch_grid_buffers validation and resource binding, then
 * submits through DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_RESOURCES so the counter
 * view is disjoint from every other job resource. */
int pipe_opengpu_launch_grid_counter_resources(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view storage[4],
    const struct pipe_opengpu_buffer_texture textures[32],
    const struct pipe_opengpu_buffer_image images[4],
    const struct pipe_opengpu_uniform_view uniforms[13],
    const struct pipe_opengpu_counter_view counter, uint32_t timeout_ms)
{
    struct drm_opengpu_compute_counter_resources command = {0};
    struct drm_opengpu_compute_uniform_resources gum = {0};
    struct drm_opengpu_compute_texture_view views[32] = {{0}};
    struct drm_opengpu_compute *launch = uniforms ? &gum.resources.launch : &command.launch;
    uint32_t items, axis, texture_count = 0, image_mask = 0, resource_flags = 0;
    uint64_t product = 1;
    struct pipe_opengpu_fence *fence = NULL;
    int ret, saved, accepted = 0;

    if (!ctx || !ctx->cs_bound || !grid || !local || !storage || !textures || !images || !timeout_ms) {
        errno = EINVAL; return -1;
    }
    if (ctx->cs_storage_failed) { errno = EIO; return -1; }
    if (reap_screen_contexts(ctx->screen)) return -1;
    ctx->cs_timeout_ms = timeout_ms;
    if (pipe_opengpu_screen_compute_limits(ctx->screen, &items, &axis)) return -1;
    for (unsigned i = 0; i < 3; i++) {
        if (!local[i] || local[i] > axis || product > items / local[i]) goto invalid;
        product *= local[i];
    }
    if (counter.resource) {
        if (counter.slot > 3 || !live_buffer(ctx->screen, counter.resource, counter.bytes) ||
            counter.bytes > OPENGPU_STORAGE_VA_MAX_BYTES || counter.offset & 3u) goto invalid;
        command.counter.offset = counter.offset;
        command.counter.slot = counter.slot;
        command.counter.count = counter.count;
        resource_flags |= OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS;
    }
    for (unsigned i = 0; i < 4; i++) {
        const struct pipe_opengpu_buffer_image *v = &images[i];
        struct drm_opengpu_compute_image_view expected;
        if (pipe_opengpu_storage_view_validate(ctx->screen, &storage[i])) return -1;
        if (!v->resource) {
            static const struct drm_opengpu_compute_image_view empty;
            if (v->binding_bytes || memcmp(&v->view, &empty, sizeof(empty))) goto invalid;
            continue;
        }
        if (!pipe_opengpu_screen_compute_buffer_images(ctx->screen) ||
            !(ctx->screen->driver_features & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS)) {
            errno = ENOTSUP; return -1;
        }
        if (!live_buffer(ctx->screen, v->resource, v->binding_bytes) ||
            v->binding_bytes > OPENGPU_STORAGE_VA_MAX_BYTES ||
            !opengpu_buffer_image_view_init(&expected, 39 + i, v->view.flags,
                v->view.storage_format, v->view.image_format, v->binding_bytes,
                v->view.offset, v->view.buffer_bytes) ||
            memcmp(&expected, &v->view, sizeof(expected))) goto invalid;
        command.images[i] = v->view; image_mask |= 1u << i;
    }
    for (unsigned i = 0; i < 32; i++) {
        const struct pipe_opengpu_buffer_texture *v = &textures[i];
        struct drm_opengpu_compute_texture_view expected;
        if (!v->resource) {
            static const struct drm_opengpu_compute_texture_view empty;
            if (v->binding_bytes || memcmp(&v->view, &empty, sizeof(empty))) goto invalid;
            continue;
        }
        if (!pipe_opengpu_screen_compute_buffer_textures(ctx->screen)) { errno = ENOTSUP; return -1; }
        if (!live_buffer(ctx->screen, v->resource, v->binding_bytes) ||
            v->binding_bytes > OPENGPU_TEXTURE_VA_STRIDE ||
            !opengpu_buffer_texture_view_init(&expected, 3 + i, v->view.format,
                v->binding_bytes, v->view.levels[0].offset, v->view.levels[0].buffer_bytes) ||
            v->view.swizzle_version > 1) goto invalid;
        expected.swizzle_version = v->view.swizzle_version;
        for (unsigned c = 0; c < 4; c++) {
            if (v->view.swizzle[c] > (v->view.swizzle_version ? 5u : 0u)) goto invalid;
            expected.swizzle[c] = v->view.swizzle[c];
        }
        if (memcmp(&expected, &v->view, sizeof(expected))) goto invalid;
        views[i] = v->view; texture_count = i + 1;
    }
    {
        uint64_t combined = image_mask ?
            (texture_count ? OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE :
                             OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE) :
            OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES;
        if (!(ctx->screen->driver_features & combined)) { errno = ENOTSUP; return -1; }
    }
    if (counter.resource) resource_flags |= OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS;
    if (uniforms) {
        unsigned any = 0;
        if (!pipe_opengpu_screen_compute_uniforms(ctx->screen)) { errno = ENOTSUP; return -1; }
        for (unsigned i = 0; i < 13; i++) {
            const struct pipe_opengpu_uniform_view *v = &uniforms[i];
            struct drm_opengpu_compute_uniform_view *out = i ? &gum.blocks[i - 1] : &gum.defaults;
            if (pipe_opengpu_uniform_view_validate(ctx->screen, v, i)) return -1;
            if (!v->resource) continue;
            out->handle = v->resource->buffer.handle; out->offset = v->offset; out->bytes = (uint32_t)v->bytes;
            if (i) gum.active_mask |= 1u << (i - 1);
            any = 1;
        }
        if (!any) goto invalid;
        gum.version = OPENGPU_COMPUTE_UNIFORM_VERSION;
        gum.resources.resource_flags = resource_flags |
            (image_mask ? OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES : 0);
    } else {
        command.resource_flags = resource_flags |
            (image_mask ? OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES : 0);
    }
    if (!grid[0] || !grid[1] || !grid[2]) return 0;
    if (pipe_opengpu_compute_barrier(ctx, timeout_ms)) return -1;
    for (unsigned i = 0; i < 40; i++) {
        struct drm_opengpu_resource binding = {.context_id = ctx->context_id};
        struct pipe_opengpu_resource *resource;
        uint32_t bit, *mask;
        if (i < 32) {
            resource = textures[i].resource; binding.slot = 3 + i;
            binding.type = OPENGPU_RESOURCE_COMPUTE_TEXTURE; binding.size = textures[i].binding_bytes;
            bit = UINT32_C(1) << i; mask = &ctx->cs_textures_bound;
        } else if (i < 36) {
            unsigned j = i - 32;
            resource = storage[j].resource; binding.slot = 35 + j;
            binding.type = OPENGPU_RESOURCE_COMPUTE_STORAGE;
            binding.offset = storage[j].offset; binding.size = storage[j].bytes;
            bit = 1u << j; mask = &ctx->cs_storage_bound;
            if (resource) command.bindings[j] = (struct drm_opengpu_compute_storage_binding) {binding.slot, storage[j].access};
        } else {
            unsigned j = i - 36;
            resource = images[j].resource; binding.slot = 39 + j;
            binding.type = OPENGPU_RESOURCE_COMPUTE_STORAGE; binding.size = images[j].binding_bytes;
            bit = 1u << j; mask = &ctx->cs_images_bound;
        }
        if (resource) {
            binding.handle = resource->buffer.handle;
            if (opengpu_bind(ctx->screen->fd, &binding)) goto poisoned;
            *mask |= bit;
        } else if (*mask & bit) {
            if (opengpu_unbind(ctx->screen->fd, ctx->context_id, binding.slot)) goto poisoned;
            *mask &= ~bit;
        }
    }
    memcpy(ctx->kernarg.map, grid, 12);
    fence = alloc_fence(ctx->screen->fd);
    if (!fence) goto poisoned;
    launch->context_id = ctx->context_id; launch->shader_slot = PIPE_CS_SLOT;
    launch->kernarg_slot = PIPE_CS_KERNARG_SLOT;
    memcpy(launch->grid, grid, sizeof(launch->grid)); memcpy(launch->local, local, sizeof(launch->local));
    launch->out_syncobj = fence->handle;
    command.textures = (uintptr_t)views; command.texture_count = texture_count;
    if (uniforms) {
        memcpy(gum.resources.bindings, command.bindings, sizeof(command.bindings));
        memcpy(gum.resources.images, command.images, sizeof(command.images));
        gum.resources.textures = command.textures; gum.resources.texture_count = texture_count;
        ret = opengpu_compute_uniform_resources(ctx->screen->fd, &gum);
    } else {
        ret = opengpu_compute_counter_resources(ctx->screen->fd, &command);
    }
    accepted = !ret;
    if (!ret) {
        for (unsigned i = 0; i < 4; i++) {
            if (storage[i].resource && (storage[i].access & OPENGPU_COMPUTE_STORAGE_WRITE) &&
                remember_compute_write(ctx, storage[i].resource, storage[i].offset + storage[i].bytes)) goto poisoned;
            if (images[i].resource && (images[i].view.flags & OPENGPU_COMPUTE_IMAGE_WRITE) &&
                remember_compute_write(ctx, images[i].resource, images[i].binding_bytes)) goto poisoned;
        }
        ret = pipe_opengpu_fence_finish(ctx, fence, timeout_ms);
    }
    if (ret) goto poisoned;
    pipe_opengpu_fence_reference(&fence, NULL);
    return 0;
invalid:
    errno = EINVAL;
    return -1;
poisoned:
    saved = errno;
    if (accepted && fence) { ctx->cs_failure_fence = fence; fence = NULL; }
    pipe_opengpu_fence_reference(&fence, NULL);
    ctx->cs_storage_failed = 1;
    errno = saved;
    return -1;
}

static int launch_grid_resources(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view views[4],
    const struct pipe_opengpu_uniform_view uniforms[13], uint32_t timeout_ms)
{
    struct drm_opengpu_compute_storage command = {0};
    struct drm_opengpu_compute_uniform_resources gum = {0};
    struct drm_opengpu_compute *launch = uniforms ? &gum.resources.launch : &command.launch;
    struct drm_opengpu_compute_storage_binding *bindings = uniforms ? gum.resources.bindings : command.bindings;
    struct pipe_opengpu_fence *fence;
    uint32_t items, axis;
    uint64_t product = 1;
    unsigned i;
    int ret, saved;
    if (!ctx || !ctx->cs_bound || !grid || !local || !views || !timeout_ms) {
        errno = EINVAL; return -1;
    }
    if (ctx->cs_storage_failed) { errno = EIO; return -1; }
    if (reap_screen_contexts(ctx->screen)) return -1;
    ctx->cs_timeout_ms = timeout_ms;
    if (pipe_opengpu_screen_compute_limits(ctx->screen, &items, &axis)) return -1;
    for (i = 0; i < 3; i++) {
        if (!local[i] || local[i] > axis || product > items / local[i]) {
            errno = EINVAL; return -1;
        }
        product *= local[i];
    }
    for (i = 0; i < 4; i++)
        if (pipe_opengpu_storage_view_validate(ctx->screen, &views[i])) return -1;
    if (uniforms) {
        unsigned any = 0;
        for (i = 0; i < 13; i++) {
            const struct pipe_opengpu_uniform_view *v = &uniforms[i];
            struct drm_opengpu_compute_uniform_view *out = i ? &gum.blocks[i - 1] : &gum.defaults;
            if (pipe_opengpu_uniform_view_validate(ctx->screen, v, i)) return -1;
            if (!v->resource) continue;
            out->handle = pipe_opengpu_resource_handle(v->resource);
            out->offset = v->offset; out->bytes = (uint32_t)v->bytes;
            if (i) gum.active_mask |= 1u << (i - 1);
            any = 1;
        }
        if (!any) { errno = EINVAL; return -1; }
        gum.version = OPENGPU_COMPUTE_UNIFORM_VERSION;
        gum.resources.resource_flags = OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE;
    }
    if (!grid[0] || !grid[1] || !grid[2]) return 0;
    if (pipe_opengpu_compute_barrier(ctx, timeout_ms)) return -1;
    for (i = 0; i < 4; i++) {
        const struct pipe_opengpu_storage_view *v = &views[i];
        if (v->resource) {
            struct drm_opengpu_resource binding = {0};
            binding.context_id = ctx->context_id; binding.slot = 35 + i;
            binding.handle = pipe_opengpu_resource_handle(v->resource);
            binding.type = OPENGPU_RESOURCE_COMPUTE_STORAGE;
            binding.offset = v->offset; binding.size = v->bytes;
            if (opengpu_bind(ctx->screen->fd, &binding)) return -1;
            ctx->cs_storage_bound |= 1u << i;
            bindings[i].slot = binding.slot;
            bindings[i].access = v->access;
        } else if (ctx->cs_storage_bound & (1u << i)) {
            if (opengpu_unbind(ctx->screen->fd, ctx->context_id, 35 + i)) return -1;
            ctx->cs_storage_bound &= ~(1u << i);
        }
    }
    /* Every preceding call completed synchronously. A failed wait poisons the
     * context before another CPU update can race an accepted job. */
    memcpy(ctx->kernarg.map, grid, 12);
    fence = alloc_fence(ctx->screen->fd);
    if (!fence) return -1;
    launch->context_id = ctx->context_id;
    launch->shader_slot = PIPE_CS_SLOT;
    launch->kernarg_slot = PIPE_CS_KERNARG_SLOT;
    memcpy(launch->grid, grid, sizeof(launch->grid));
    memcpy(launch->local, local, sizeof(launch->local));
    launch->out_syncobj = fence->handle;
    ret = uniforms ? opengpu_compute_uniform_resources(ctx->screen->fd, &gum) :
                     opengpu_compute_storage(ctx->screen->fd, &command);
    if (!ret) {
        for (i = 0; i < 4; i++)
            if (views[i].resource && (views[i].access & OPENGPU_COMPUTE_STORAGE_WRITE) &&
                remember_compute_write(ctx, views[i].resource, views[i].offset + views[i].bytes)) {
                ctx->cs_storage_failed = 1;
                ctx->cs_failure_fence = fence; fence = NULL;
                pipe_opengpu_fence_reference(&fence, NULL);
                return -1;
            }
        ret = pipe_opengpu_fence_finish(ctx, fence, timeout_ms);
        if (ret) {
            ctx->cs_storage_failed = 1;
            ctx->cs_failure_fence = fence; fence = NULL;
        }
    }
    saved = errno;
    pipe_opengpu_fence_reference(&fence, NULL);
    errno = saved;
    return ret;
}

int pipe_opengpu_launch_grid_storage(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view views[4], uint32_t timeout_ms)
{
    return launch_grid_resources(ctx, grid, local, views, NULL, timeout_ms);
}

int pipe_opengpu_launch_grid_uniforms(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view views[4],
    const struct pipe_opengpu_uniform_view uniforms[13], uint32_t timeout_ms)
{
    if (!uniforms) { errno = EINVAL; return -1; }
    return launch_grid_resources(ctx, grid, local, views, uniforms, timeout_ms);
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
        ctx->depth->buffer.handle == ctx->color[0]->buffer.handle) {
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
    if (ctx->color[0] && ctx->color[0]->pitch) {
        /* Pitched 2D targets carry the row bytes (including MSAA width). */
        return ctx->color[0]->pitch;
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
    unsigned i;

    if (!ctx->fb_width || !ctx->fb_height || !ctx->nr_colors ||
        stride > UINT32_MAX || color_bytes(ctx) > UINT32_MAX) {
        errno = EINVAL;
        return -1;
    }
    for (i = 0; i < ctx->nr_colors; i++) {
        struct pipe_opengpu_resource *c = ctx->color[i];
        if (!c || c->buffer.size < color_bytes(ctx)) {
            errno = EINVAL;
            return -1;
        }
        if (c->pitch &&
            (ctx->fb_width != c->width ||
             ctx->fb_height != c->height ||
             (ctx->sample_mode &&
              c->pitch < ctx->fb_width * (1u << ctx->sample_mode) * 4u))) {
            errno = EINVAL;
            return -1;
        }
    }
    submit->color_handle = ctx->color[0]->buffer.handle;
    submit->color_handle2 = ctx->nr_colors > 1 ? ctx->color[1]->buffer.handle : 0;
    submit->color_handle3 = ctx->nr_colors > 2 ? ctx->color[2]->buffer.handle : 0;
    submit->color_handle4 = ctx->nr_colors > 3 ? ctx->color[3]->buffer.handle : 0;
    submit->stride = stride;
    submit->sample_mode = ctx->sample_mode;
    submit->render_width = ctx->fb_width;
    submit->render_height = ctx->fb_height;
    submit->flags |= ctx->color[0]->format << OPENGPU_SUBMIT_COLOR_FORMAT_SHIFT;
    return 0;
}

int pipe_opengpu_clear(struct pipe_opengpu_context *ctx, uint32_t pattern,
                       struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_fill fill = { 0 };
    struct pipe_opengpu_fence *fence;
    uint64_t bytes;

    if (!ctx || !ctx->nr_colors || !ctx->fb_width || !ctx->fb_height) {
        errno = EINVAL;
        return -1;
    }
    if (color_stride_bytes(ctx) > UINT32_MAX) {
        errno = EINVAL;
        return -1;
    }
    bytes = color_bytes(ctx);
    if ((bytes & 63u) || bytes > ctx->color[0]->buffer.size) {
        errno = EINVAL;
        return -1;
    }
    fence = alloc_fence(ctx->screen->fd);
    if (!fence)
        return -1;
    fill.context_id = ctx->context_id;
    fill.destination_handle = ctx->color[0]->buffer.handle;
    fill.pattern = opengpu_pixel_encode(pattern, ctx->color[0]->format);
    fill.bytes = bytes;
    fill.out_syncobj = fence->handle;
    if (opengpu_fill(ctx->screen->fd, &fill)) {
        pipe_opengpu_fence_reference(&fence, NULL);
        return -1;
    }
    return finish_or_hand_off(ctx, fence, out_fence);
}

/* Subtraction keeps a wrapped offset + length from passing admission. */
static int transfer_range_fits(const struct pipe_opengpu_resource *resource,
                               uint64_t offset, uint64_t bytes)
{
    return offset <= resource->buffer.size &&
           bytes <= resource->buffer.size - offset;
}

int pipe_opengpu_fill(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_resource *dst,
                      uint64_t offset, uint64_t bytes, uint32_t pattern,
                      struct pipe_opengpu_fence **out_fence)
{
    struct drm_opengpu_fill fill = { 0 };
    struct pipe_opengpu_fence *fence;

    if (!ctx || !dst || !bytes || (offset & 63u) || (bytes & 63u) ||
        !transfer_range_fits(dst, offset, bytes)) {
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
        !transfer_range_fits(dst, dst_offset, bytes) ||
        !transfer_range_fits(src, src_offset, bytes) ||
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
    uint64_t src_span, dst_span;

    if (!ctx || !dst || !src || !width_bytes || !height ||
        dst->buffer.handle == src->buffer.handle ||
        (width_bytes & 63u) || (dst_stride & 63u) || (src_stride & 63u) ||
        (dst_offset & 63u) || (src_offset & 63u) ||
        dst_stride < width_bytes || src_stride < width_bytes ||
        !(ctx->screen->caps & OPENGPU_CAP_STRIDED_ENGINE)) {
        errno = EINVAL;
        return -1;
    }
    /* All operands here are 32-bit and width is no larger than stride, so
     * each span is at most UINT32_MAX squared and fits in uint64_t. */
    src_span = (uint64_t)(height - 1u) * src_stride + width_bytes;
    dst_span = (uint64_t)(height - 1u) * dst_stride + width_bytes;
    if (!transfer_range_fits(src, src_offset, src_span) ||
        !transfer_range_fits(dst, dst_offset, dst_span)) {
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
        !transfer_range_fits(res, offset, bytes)) {
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

    if (!ctx || !draw || !ctx->nr_colors || !ctx->fb_width) {
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

static int draw_vertex(struct pipe_opengpu_context *ctx,
                        const struct drm_opengpu_vertex_draw *draw,
                        struct pipe_opengpu_fence **out_fence,
                        int discard_private_depth, int transient_fragment_staging)
{
    struct opengpu_buffer commands = { 0 };
    struct drm_opengpu_submit submit = { 0 };
    struct pipe_opengpu_fence *fence = NULL;
    int fd, status = -1;

    if (!ctx || !draw || !ctx->nr_colors || !ctx->fb_width) {
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
    if (transient_fragment_staging &&
        (ctx->screen->caps & OPENGPU_CAP_FRAGMENT_INPUT_USAGE) &&
        (ctx->screen->driver_features &
         OPENGPU_DRIVER_FEATURE_TRANSIENT_FRAGMENT_STAGING))
        submit.flags |= OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING;
    if (discard_private_depth && (ctx->screen->driver_features &
         OPENGPU_DRIVER_FEATURE_DISCARD_PRIVATE_DEPTH) && !ctx->depth &&
        (draw->state & OPENGPU_DRAW_STATE_OVERRIDE) &&
        !(draw->state & (OPENGPU_DRAW_STATE_DEPTH_TEST |
                         OPENGPU_DRAW_STATE_DEPTH_WRITE |
                         OPENGPU_DRAW_STATE_STENCIL_TEST)))
        submit.flags |= OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH;
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
    if (transient_fragment_staging && getenv("OPENGPU_TRACE_DRAWS"))
        fprintf(stderr, "opengpu: fragment staging transient=%u flags=0x%x\n",
                !!(submit.flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING),
                submit.flags);
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
    return draw_vertex(ctx, draw, out_fence, 0, 0);
}

int pipe_opengpu_draw_vertex_discard_depth(struct pipe_opengpu_context *ctx,
                             const struct drm_opengpu_vertex_draw *draw,
                             struct pipe_opengpu_fence **out_fence)
{
    return draw_vertex(ctx, draw, out_fence, 1, 0);
}

int pipe_opengpu_draw_vertex_transient(struct pipe_opengpu_context *ctx,
                             const struct drm_opengpu_vertex_draw *draw,
                             struct pipe_opengpu_fence **out_fence)
{
    return draw_vertex(ctx, draw, out_fence, 1, 1);
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
    switch (color->format) {
    case OPENGPU_FORMAT_NATIVE: fb.pixel_format = DRM_FORMAT_RGBA8888; break;
    case OPENGPU_FORMAT_RGBA8: fb.pixel_format = DRM_FORMAT_ABGR8888; break;
    case OPENGPU_FORMAT_RGBX8: fb.pixel_format = DRM_FORMAT_XBGR8888; break;
    case OPENGPU_FORMAT_BGRA8: fb.pixel_format = DRM_FORMAT_ARGB8888; break;
    case OPENGPU_FORMAT_BGRX8: fb.pixel_format = DRM_FORMAT_XRGB8888; break;
    default: errno = EINVAL; return -1;
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

int pipe_opengpu_query(struct pipe_opengpu_context *ctx,
                       struct drm_opengpu_query *command)
{
    (void)ctx;
    if (!command) { errno = EINVAL; return -1; }
    return opengpu_query(ctx->screen->fd, command);
}
