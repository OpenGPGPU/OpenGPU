/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_FS_GLSL_FRONTEND_H
#define OPENGPU_FS_GLSL_FRONTEND_H
#include "opengpu_native_fragment_v2.h"
#ifdef __cplusplus
extern "C" {
#endif
void *native_fs_frontend_compile_no_gs_v2(const char *path,const char *dump_prefix);
int native_fs_frontend_reflection_v2(void *,struct opengpu_native_fragment_reflection_v2 *);
void *native_fs_frontend_compile(const char *path, const char *dump_prefix);
const void *native_fs_frontend_tokens(void *handle);
unsigned native_fs_frontend_token_words(void *handle);
void native_fs_frontend_destroy(void *handle);
#ifdef __cplusplus
}
#endif
#endif
