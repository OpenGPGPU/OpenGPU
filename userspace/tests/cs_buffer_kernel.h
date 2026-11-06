/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_CS_BUFFER_KERNEL_H
#define OPENGPU_CS_BUFFER_KERNEL_H
#include "opengpu_drm.h"
#include <stdint.h>
struct cs_buffer_bo { unsigned handle; uint64_t bytes; void *data; };
struct cs_buffer_kernel_input {
    struct drm_opengpu_resource bindings[43];
    struct cs_buffer_bo memory[512];
    struct drm_opengpu_compute_image_resources command;
    const struct drm_opengpu_compute_uniform_resources *uniforms;
    unsigned with_images;
    unsigned storage_only;
};
struct cs_buffer_kernel_result {
    void *private_state;
    const void *textures, *images, *storage, *uniforms, *code;
    uint64_t texture_bytes, image_bytes, storage_bytes, uniform_bytes, code_bytes;
    uint32_t texture_root, image_root, storage_root, uniform_root, context;
    uint64_t binding_va[43];
    unsigned retained_objects;
};
/* Real extracted driver ioctl/prepare/scheduler/retirement, with only its
 * kernel primitives/VM/MMIO replies modeled. No hardware execution. */
int cs_buffer_kernel_begin(const struct cs_buffer_kernel_input *, struct cs_buffer_kernel_result *);
void cs_buffer_kernel_finish(struct cs_buffer_kernel_result *, int cancel);
#endif
