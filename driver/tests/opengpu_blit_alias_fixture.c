/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include "opengpu_backing_identity.h"

typedef uint32_t u32;
typedef uint64_t u64;
#define U32_MAX UINT32_MAX
#define GPU_CAP_BLIT_ENGINE 1u
#define GPU_CAP_STRIDED_ENGINE 2u
#define ARRAY_SIZE(a) (sizeof(a) / sizeof((a)[0]))
#define check_add_overflow(a, b, out) __builtin_add_overflow((a), (b), (out))
#define check_mul_overflow(a, b, out) __builtin_mul_overflow((a), (b), (out))
struct drm_gem_object { u64 size; const void *backing; };
struct drm_gem_dma_object { struct drm_gem_object base; u64 dma_addr; };
struct opengpu_device { struct { u32 capabilities; } hw; };
struct drm_device { void *dev; };
struct drm_file { void *driver_priv; };
struct opengpu_file { int unused; };
struct opengpu_render_context;
struct opengpu_sched_job;
struct drm_syncobj;
struct drm_exec { int unused; };
struct opengpu_command_events { int unused; };
struct drm_opengpu_blit {
    u32 context_id, source_handle, destination_handle, flags, wait_event, signal_event;
    u64 source_offset, destination_offset, bytes;
};
struct drm_opengpu_strided_blit {
    u32 context_id, source_handle, destination_handle, flags, wait_event, signal_event;
    u64 source_offset, destination_offset;
    u32 width_bytes, height, source_stride, destination_stride;
};
static struct drm_gem_dma_object source_dma, destination_dma;
static struct opengpu_device device = { .hw.capabilities = 3 };
static void *dev_get_drvdata(void *dev) { (void)dev; return &device; }
static int opengpu_command_events_init(struct opengpu_device *gpu,
    struct opengpu_command_events *events, u32 flags, u32 wait, u32 signal)
{
    (void)gpu; (void)events; (void)flags; (void)wait; (void)signal;
    return 0;
}
static struct drm_gem_object *drm_gem_object_lookup(struct drm_file *file, u32 handle)
{
    (void)file;
    if (handle == 1) return &source_dma.base;
    if (handle == 2) return &destination_dma.base;
    return NULL;
}
static struct drm_gem_dma_object *to_drm_gem_dma_obj(struct drm_gem_object *obj)
{
    return (struct drm_gem_dma_object *)obj;
}
static void drm_gem_object_put(struct drm_gem_object *obj) { (void)obj; }
static enum opengpu_backing_kind step(const void *obj, const void **next)
{
    const struct drm_gem_object *gem = obj;
    *next = gem->backing;
    return OPENGPU_BACKING_FOREIGN;
}
static int opengpu_gem_backings_disjoint(struct drm_gem_object *const *objects,
                                          unsigned int count)
{
    struct opengpu_backing_identity a, b;
    assert(count == 2);
    if (!opengpu_backing_identity_resolve(objects[0], step, &a) ||
        !opengpu_backing_identity_resolve(objects[1], step, &b) ||
        opengpu_backing_identity_equal(&a, &b))
        return -EINVAL;
    return 0;
}
#include "blit_alias_under_test.inc"

int main(void)
{
    int backing_a, backing_b;
    struct drm_device drm = {0};
    struct drm_file file = {0};
    struct drm_opengpu_blit linear = {
        .context_id = 1, .source_handle = 1, .destination_handle = 2,
        .bytes = 128,
    };
    struct drm_opengpu_strided_blit strided = {
        .context_id = 1, .source_handle = 1, .destination_handle = 2,
        .width_bytes = 64, .height = 2,
        .source_stride = 64, .destination_stride = 64,
    };
    source_dma = (struct drm_gem_dma_object){{256, &backing_a}, 0x1000};
    destination_dma = (struct drm_gem_dma_object){{256, &backing_b}, 0x1040};
    assert(opengpu_compute_blit_ioctl(&drm, &linear, &file) == -EINVAL);
    assert(opengpu_compute_strided_blit_ioctl(&drm, &strided, &file) == -EINVAL);
    destination_dma.dma_addr = 0x1080;
    assert(opengpu_compute_blit_ioctl(&drm, &linear, &file) == 0);
    assert(opengpu_compute_strided_blit_ioctl(&drm, &strided, &file) == 0);
    destination_dma.dma_addr = 0x4000;
    destination_dma.base.backing = &backing_a;
    assert(opengpu_compute_blit_ioctl(&drm, &linear, &file) == -EINVAL);
    assert(opengpu_compute_strided_blit_ioctl(&drm, &strided, &file) == -EINVAL);
    destination_dma.base.backing = &backing_b;
    source_dma.dma_addr = 0xffffffc0ull;
    assert(opengpu_compute_blit_ioctl(&drm, &linear, &file) == -ERANGE);
    assert(opengpu_compute_strided_blit_ioctl(&drm, &strided, &file) == -ERANGE);
    source_dma.dma_addr = 0x1000;
    linear.destination_handle = 1;
    strided.destination_handle = 1;
    assert(opengpu_compute_blit_ioctl(&drm, &linear, &file) == -EINVAL);
    assert(opengpu_compute_strided_blit_ioctl(&drm, &strided, &file) == -EINVAL);
    puts("blit aliases: overlap, adjacent, backing identity and bounds PASS");
    return 0;
}
