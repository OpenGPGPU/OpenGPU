/* Actual linked Mesa vertex/fragment compiler and state-tracker inputs. */
#ifndef OPENGPU_GRAPHICS_UNIFORM_FRONTEND_H
#define OPENGPU_GRAPHICS_UNIFORM_FRONTEND_H
#include "opengpu_native_fragment_v2.h"
#ifdef __cplusplus
extern "C" {
#endif
void *graphics_uniform_compile_no_gs_v2(const char *vs,const char *fs,const char *prefix);
int graphics_uniform_reflection_v2(void *,struct opengpu_native_fragment_reflection_v2 *);
unsigned graphics_uniform_token_words(void *,unsigned stage);
void *graphics_uniform_compile(const char *vertex, const char *fragment, const char *prefix);
const void *graphics_uniform_tokens(void *program, unsigned stage);
unsigned graphics_uniform_blocks(void *program, unsigned stage);
unsigned graphics_uniform_binding(void *program, unsigned stage, unsigned block);
unsigned graphics_uniform_components(void *program, unsigned stage);
unsigned graphics_uniform_color_type(void *program, unsigned color);
struct pipe_context;
struct pipe_resource;
void graphics_uniform_ubos(void *program, struct pipe_context *,
    struct pipe_resource *const [36], const unsigned offsets[36], const unsigned sizes[36]);
void graphics_uniform_defaults(void *program, struct pipe_context *, unsigned stage,
    const unsigned *values, unsigned components);
void graphics_uniform_destroy(void *program);
#ifdef __cplusplus
}
#endif
#endif
