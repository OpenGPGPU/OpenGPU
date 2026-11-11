/* Exercise real winsys transfer admission with mocked submissions and fences. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include "../pipe_opengpu.c"

static unsigned created, destroyed, submitted, failures;
static struct drm_opengpu_fill last_fill;
static struct drm_opengpu_blit last_blit;
static struct drm_opengpu_strided_blit last_strided;
static struct drm_opengpu_invalidate last_invalidate;

int opengpu_sync_create(int fd, uint32_t *handle)
{
    (void)fd;
    *handle = ++created;
    return 0;
}

int opengpu_sync_destroy(int fd, uint32_t handle)
{
    (void)fd; (void)handle;
    destroyed++;
    return 0;
}

int opengpu_sync_wait_success(int fd, uint32_t handle, int64_t timeout)
{
    (void)fd; (void)handle; (void)timeout;
    return 0;
}

int opengpu_fill(int fd, struct drm_opengpu_fill *command)
{
    (void)fd;
    last_fill = *command; submitted++;
    return 0;
}

int opengpu_blit(int fd, struct drm_opengpu_blit *command)
{
    (void)fd;
    last_blit = *command; submitted++;
    return 0;
}

int opengpu_strided_blit(int fd, struct drm_opengpu_strided_blit *command)
{
    (void)fd;
    last_strided = *command; submitted++;
    return 0;
}

int opengpu_invalidate(int fd, struct drm_opengpu_invalidate *command)
{
    (void)fd;
    last_invalidate = *command; submitted++;
    return 0;
}

#define REJECT(call) do { \
    unsigned before_created = created, before_submitted = submitted; \
    struct pipe_opengpu_fence *fence = NULL; \
    int result; \
    errno = 0; \
    result = (call); \
    if (result != -1 || errno != EINVAL || fence || \
        created != before_created || submitted != before_submitted) { \
        fprintf(stderr, "invalid transfer was not rejected before submission: %s\n", #call); \
        failures++; \
    } \
    if (fence) pipe_opengpu_fence_reference(&fence, NULL); \
} while (0)

int main(void)
{
    const uint64_t near_wrap = UINT64_MAX - 63u;
    const uint32_t max_stride = UINT32_MAX - 63u;
    const uint64_t huge_span = (uint64_t)(UINT32_MAX - 1u) * max_stride + 64u;
    const uint64_t wrap_huge_span = UINT64_C(0) - huge_span;
    struct pipe_opengpu_screen screen = { .fd = 3, .caps = OPENGPU_CAP_STRIDED_ENGINE };
    struct pipe_opengpu_resource src = { .buffer = { .handle = 1, .size = 512 } };
    struct pipe_opengpu_resource dst = { .buffer = { .handle = 2, .size = 512 } };
    struct pipe_opengpu_context context = { .screen = &screen, .context_id = 7 };
    struct pipe_opengpu_fence *fence = NULL;

    /* Each wraps a 64-bit end address back into the tiny fake GEM. The real
     * public functions must reject it before creating a fence or submitting. */
    REJECT(pipe_opengpu_fill(&context, &dst, near_wrap, 64, 0, &fence));
    REJECT(pipe_opengpu_invalidate(&context, &src, near_wrap, 64, &fence));
    REJECT(pipe_opengpu_blit(&context, &dst, &src, near_wrap, 0, 64, &fence));
    REJECT(pipe_opengpu_blit(&context, &dst, &src, 0, near_wrap, 64, &fence));
    REJECT(pipe_opengpu_blit(&context, &dst, &src, 64, 64, near_wrap, &fence));
    REJECT(pipe_opengpu_strided_blit(&context, &dst, &src, near_wrap, 0,
                                      64, 2, 64, 64, &fence));
    REJECT(pipe_opengpu_strided_blit(&context, &dst, &src, 0, near_wrap,
                                      64, 2, 64, 64, &fence));
    REJECT(pipe_opengpu_strided_blit(&context, &dst, &src, wrap_huge_span, wrap_huge_span,
                                      64, UINT32_MAX, max_stride, max_stride, &fence));
    REJECT(pipe_opengpu_fill(&context, &dst, 512, 64, 0, &fence));
    REJECT(pipe_opengpu_invalidate(&context, &src, 448, 128, &fence));
    REJECT(pipe_opengpu_strided_blit(&context, &dst, &src, 448, 0,
                                      64, 2, 64, 64, &fence));
    if (failures)
        return 1;

    /* End exactly at the allocation boundary must remain valid. */
    assert(!pipe_opengpu_fill(&context, &dst, 448, 64, 0x12345678, &fence));
    assert(last_fill.destination_offset == 448 && last_fill.bytes == 64 &&
           last_fill.pattern == 0x12345678);
    pipe_opengpu_fence_reference(&fence, NULL);
    assert(!pipe_opengpu_invalidate(&context, &src, 448, 64, &fence));
    assert(last_invalidate.offset == 448 && last_invalidate.bytes == 64);
    pipe_opengpu_fence_reference(&fence, NULL);
    assert(!pipe_opengpu_blit(&context, &dst, &src, 448, 448, 64, &fence));
    assert(last_blit.source_offset == 448 && last_blit.destination_offset == 448 &&
           last_blit.bytes == 64);
    pipe_opengpu_fence_reference(&fence, NULL);
    assert(!pipe_opengpu_strided_blit(&context, &dst, &src, 384, 384,
                                       64, 2, 64, 64, &fence));
    assert(last_strided.source_offset == 384 && last_strided.destination_offset == 384 &&
           last_strided.width_bytes == 64 && last_strided.height == 2);
    pipe_opengpu_fence_reference(&fence, NULL);
    dst.buffer.size = near_wrap;
    assert(!pipe_opengpu_fill(&context, &dst, near_wrap - 64, 64, 0, &fence));
    assert(last_fill.destination_offset == near_wrap - 64 && last_fill.bytes == 64);
    pipe_opengpu_fence_reference(&fence, NULL);
    assert(created == 5 && submitted == 5 && destroyed == 5);
    puts("winsys transfer ranges: PASS");
    return 0;
}
