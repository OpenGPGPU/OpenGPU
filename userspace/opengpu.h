/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_USERSPACE_H
#define OPENGPU_USERSPACE_H

#include <stdint.h>
#include "opengpu_drm.h"

struct opengpu_buffer {
    uint32_t handle;
    uint64_t size;
    void *map;
};

/* One emulated fragment draw at 64x64 has finished in about 100-220s and
 * has also still been running at 300s, which is the "Timer expired" perror
 * from opengpu_sync_wait. The driver watchdog is longer than that. 900s
 * lets the slow draw retire and still fails a wedged one inside the guest
 * cap. Override at compile time for a shorter host run. */
#ifndef OPENGPU_FENCE_TIMEOUT_MS
#define OPENGPU_FENCE_TIMEOUT_MS 900000
#endif

/* opengpu_open returns a file descriptor. Other functions return 0 on
 * success and -1 with errno set on failure. The caller owns the DRM fd;
 * buffers, contexts and syncobjs have explicit lives. */
int opengpu_open(const char *path);
int opengpu_capabilities(int fd, uint64_t *value);
int opengpu_shader_config(int fd, uint32_t *value);
/* Unknown feature query on older drivers is a successful zero-feature result. */
int opengpu_driver_features(int fd, uint64_t *value);
int opengpu_native_layout(int fd, uint64_t *value);
/* Read the complete frozen GNC block. Reject any nonzero upper word and leave
 * output unchanged if any transport request fails. Semantic validation lives
 * in opengpu_native_runtime_query, before a native submission is assembled. */
int opengpu_native_caps(int fd, uint32_t words[OPENGPU_PARAM_NATIVE_CAPS_WORDS]);
/* Raw transport; the guarded native frontend assembles canonical source plans.
 * Kernel admission owns all validation, producer waits and private snapshots.
 * A failed ioctl does not change the caller's returned sequence number. */
int opengpu_native_submit(int fd, const struct drm_opengpu_submit_native_binned *request,
    uint64_t *fence_sequence);
/* Both limits are zero when unknown or compute is unavailable. Older drivers
 * may return EINVAL or ENOTTY for this query. */
int opengpu_compute_local_limits(int fd, uint32_t *items, uint32_t *axis);
/* GCR1 shared-SRAM base/bytes/lanes, or all zero when unavailable. Results
 * are published together only after every query and range check succeeds.
 * Older drivers may reject the queries; no graphics-config fallback is used. */
int opengpu_compute_shared_limits(int fd, uint32_t *base, uint32_t *bytes,
                                  uint32_t *lanes);
int opengpu_display_size(int fd, uint32_t *width, uint32_t *height);
int opengpu_context_create(int fd, uint32_t *id);
int opengpu_context_destroy(int fd, uint32_t id);
int opengpu_buffer_create(int fd, uint32_t bytes, struct opengpu_buffer *buffer);
int opengpu_buffer_destroy(int fd, struct opengpu_buffer *buffer);
int opengpu_bind(int fd, const struct drm_opengpu_resource *resource);
int opengpu_unbind(int fd, uint32_t context_id, uint32_t slot);
int opengpu_compute(int fd, struct drm_opengpu_compute *command);
int opengpu_compute_textures(int fd, struct drm_opengpu_compute_textures *command);
int opengpu_compute_storage(int fd, struct drm_opengpu_compute_storage *command);
int opengpu_compute_uniform_resources(int fd,
    struct drm_opengpu_compute_uniform_resources *command);
int opengpu_compute_resources(int fd, struct drm_opengpu_compute_resources *command);
int opengpu_compute_images(int fd, struct drm_opengpu_compute_images *command);
int opengpu_compute_textured_images(int fd, struct drm_opengpu_compute_textured_images *command);
int opengpu_compute_image_resources(int fd, struct drm_opengpu_compute_image_resources *command);
int opengpu_compute_counter_resources(int fd, struct drm_opengpu_compute_counter_resources *command);
int opengpu_compute_counter_source_resources(int fd, struct drm_opengpu_compute_counter_source_resources *command);
int opengpu_compute_indirect_resources(int fd, struct drm_opengpu_compute_indirect_resources *command);
int opengpu_render(int fd, struct drm_opengpu_submit *command);
int opengpu_fill(int fd, struct drm_opengpu_fill *command);
int opengpu_blit(int fd, struct drm_opengpu_blit *command);
int opengpu_strided_blit(int fd, struct drm_opengpu_strided_blit *command);
int opengpu_resolve(int fd, struct drm_opengpu_resolve *command);
int opengpu_invalidate(int fd, struct drm_opengpu_invalidate *command);
int opengpu_sync_create(int fd, uint32_t *handle);
int opengpu_sync_destroy(int fd, uint32_t handle);
int opengpu_sync_wait(int fd, uint32_t handle, int64_t timeout_ms);
/* Wait, then require a successful job fence (sync_file status 1). */
int opengpu_sync_wait_success(int fd, uint32_t handle, int64_t timeout_ms);
int opengpu_query(int fd, struct drm_opengpu_query *command);

#endif
