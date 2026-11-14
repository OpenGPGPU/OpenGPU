/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_FS_GLSL_FRONTEND_H
#define OPENGPU_FS_GLSL_FRONTEND_H
#ifdef __cplusplus
extern "C" {
#endif
void *native_fs_frontend_compile(const char *path, const char *dump_prefix);
const void *native_fs_frontend_tokens(void *handle);
unsigned native_fs_frontend_token_words(void *handle);
void native_fs_frontend_destroy(void *handle);
#ifdef __cplusplus
}
#endif
#endif
