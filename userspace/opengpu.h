/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_USERSPACE_H
#define OPENGPU_USERSPACE_H

#include <stdint.h>
#include "../driver/opengpu_drm.h"

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
int opengpu_display_size(int fd, uint32_t *width, uint32_t *height);
int opengpu_context_create(int fd, uint32_t *id);
int opengpu_context_destroy(int fd, uint32_t id);
int opengpu_buffer_create(int fd, uint32_t bytes, struct opengpu_buffer *buffer);
int opengpu_buffer_destroy(int fd, struct opengpu_buffer *buffer);
int opengpu_bind(int fd, const struct drm_opengpu_resource *resource);
int opengpu_unbind(int fd, uint32_t context_id, uint32_t slot);
int opengpu_compute(int fd, struct drm_opengpu_compute *command);
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

#endif
