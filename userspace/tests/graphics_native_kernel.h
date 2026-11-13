/* SPDX-License-Identifier: MIT */
#ifndef GRAPHICS_NATIVE_KERNEL_H
#define GRAPHICS_NATIVE_KERNEL_H
#include "opengpu_native_submit_fixture.h"
#include "native_graphics_uniform_source.h"

/* Explicit qualification only. The session uses the actual Linux submit,
 * scheduler, private owner and MMU bodies. GEM storage comes directly from its
 * original allocator and is the same pointer used by Gallium's OS model.
 * Source observations happen inside the real post-producer copy callback.
 * This header does not advertise a device capability or execute a GPU. */
void graphics_native_source_observer(void *record,uint32_t handle,
    uint64_t offset,uint32_t bytes,const void *stable_original_bytes);
int graphics_native_source_write(struct native_kernel_session *session,FILE *stream,
    struct native_graphics_source_record *record);
#endif
