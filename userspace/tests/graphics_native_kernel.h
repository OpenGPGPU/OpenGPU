/* SPDX-License-Identifier: MIT */
#ifndef GRAPHICS_NATIVE_KERNEL_H
#define GRAPHICS_NATIVE_KERNEL_H
#include "opengpu_native_submit_fixture.h"
#include "native_graphics_uniform_source.h"
#include "graphics_native_owner_ipc.h"

/* Explicit qualification only. The session uses the actual Linux submit,
 * scheduler, private owner and MMU bodies. GEM storage comes directly from its
 * original allocator and is the same pointer used by Gallium's OS model.
 * Source observations happen inside the real post-producer copy callback.
 * This header does not advertise a device capability or execute a GPU. */
struct native_kernel_profile *graphics_native_profile_read(const char *path);
#ifdef OPENGPU_NATIVE_MMIO
int graphics_native_host_execute(struct native_kernel_session *session,const char *directory,
    struct native_graphics_source_record records[2]);
#endif
void graphics_native_source_observer(void *record,uint32_t handle,
    uint64_t offset,uint32_t bytes,const void *stable_original_bytes);
int graphics_native_source_write(struct native_kernel_session *session,FILE *stream,
    struct native_graphics_source_record *record);
#endif
