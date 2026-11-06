/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_CS_GLSL_FRONTEND_H
#define OPENGPU_CS_GLSL_FRONTEND_H
#include <stdint.h>
struct pipe_context;
struct pipe_resource;
#ifdef __cplusplus
extern "C" {
#endif
void *cs_frontend_compile(const char *path, const char *dump_prefix);
const void *cs_frontend_tokens(void *handle);
unsigned cs_frontend_default_components(void *handle);
unsigned cs_frontend_storage_binding(void *handle, unsigned native_slot);
void cs_frontend_defaults(void *handle, struct pipe_context *, const uint32_t *, unsigned);
void cs_frontend_ubos(void *handle, struct pipe_context *, struct pipe_resource *const [36],
    const unsigned [36], const unsigned [36]);
void cs_frontend_storage(void *handle, struct pipe_context *, struct pipe_resource *const [4]);
void cs_frontend_destroy(void *handle);
#ifdef __cplusplus
}
#endif
#endif
