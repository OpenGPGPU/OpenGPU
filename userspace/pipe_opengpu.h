/* SPDX-License-Identifier: MIT */
/* Thin Gallium-shaped winsys over userspace/opengpu.h. Not Mesa: no NIR,
 * softpipe, or upstream pipe headers. See docs/GALLIUM_SPIKE.md. */
#ifndef PIPE_OPENGPU_H
#define PIPE_OPENGPU_H

#include "opengpu.h"
#include <stddef.h>
#include <stdint.h>

struct pipe_opengpu_screen;
struct pipe_opengpu_context;
struct pipe_opengpu_resource;
struct pipe_opengpu_fence;

struct pipe_opengpu_screen *pipe_opengpu_screen_create(const char *path);
/* dup(fd) and take ownership of the duplicate. Mesa's loader keeps its own fd. */
struct pipe_opengpu_screen *pipe_opengpu_screen_create_fd(int fd);
void pipe_opengpu_screen_destroy(struct pipe_opengpu_screen *screen);
/* Winsys calls, including final ownership transfer/recovery, are externally
 * serialized. No background worker or general multi-context concurrency is
 * provided. Failed final namespace deletion keeps a process-reachable owner;
 * later screen/compute entry polls it before admitting another compute job. */
int pipe_opengpu_screen_fd(const struct pipe_opengpu_screen *screen);
uint64_t pipe_opengpu_screen_capabilities(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_clip_depth(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_sampler_nearest(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_pixel_center(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_sampler_axis_wrap(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_biased_clamp(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_mirrored_repeat(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_viewport(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_depth_range(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_constant_blend(const struct pipe_opengpu_screen *screen);
uint32_t pipe_opengpu_screen_shader_config(const struct pipe_opengpu_screen *screen);
/* Queries only the actual discovered pair, gated by checked storage support. */
int pipe_opengpu_screen_compute_limits(const struct pipe_opengpu_screen *screen,
                                       uint32_t *items, uint32_t *axis);
/* Checked storage support and actual GCR1 discovery are both required. */
int pipe_opengpu_screen_compute_shared_limits(const struct pipe_opengpu_screen *screen,
    uint32_t *base, uint32_t *bytes, uint32_t *lanes);
int pipe_opengpu_screen_compute_uniforms(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_compute_storage_atomics(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_compute_counter_source(const struct pipe_opengpu_screen *screen);
/* Versioned native transports only; these are not GL/GLES capabilities. */
int pipe_opengpu_screen_compute_buffer_textures(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_compute_buffer_images(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_screen_compute_image_backings(const struct pipe_opengpu_screen *screen);
uint32_t pipe_opengpu_screen_fs_bank_stride(const struct pipe_opengpu_screen *screen);

struct pipe_opengpu_context *pipe_opengpu_context_create(
    struct pipe_opengpu_screen *screen);
/* Void final destroy transfers an unresolved context to its screen exactly
 * once. The caller relinquishes the pointer even if recovery is deferred. */
void pipe_opengpu_context_destroy(struct pipe_opengpu_context *ctx);
/* Failure retains the poisoned context, original failed-wait fence and BO refs.
 * Keep this pointer for explicit cleanup after safe signal/publication; a
 * successful namespace deletion alone never proves hardware retirement. */
int pipe_opengpu_context_destroy_checked(struct pipe_opengpu_context *ctx);

struct pipe_opengpu_resource *pipe_opengpu_resource_create(
    struct pipe_opengpu_screen *screen, uint32_t bytes);
/* 2D 32-bit dumb GEM (native pitch). Sized for render and KMS present.
 * Legacy native word order until resource_set_format selects another. */
struct pipe_opengpu_resource *pipe_opengpu_resource_create_2d(
    struct pipe_opengpu_screen *screen, uint32_t width, uint32_t height);
/* Tightly packed 32-bit pixels, pitch = width * 4. The sampler walks mip 0 with
 * no row padding, so a texture cannot use a dumb-buffer pitch. */
struct pipe_opengpu_resource *pipe_opengpu_resource_create_linear(
    struct pipe_opengpu_screen *screen, uint32_t width, uint32_t height);
struct pipe_opengpu_resource *pipe_opengpu_resource_from_handle(
    struct pipe_opengpu_screen *screen, uint32_t width, uint32_t height,
    uint32_t pitch, uint32_t handle, int is_fd);
/* Returns a new caller-owned close-on-exec DMA-BUF fd, or -1. */
int pipe_opengpu_resource_export_fd(struct pipe_opengpu_screen *screen,
                                   struct pipe_opengpu_resource *res);
/* Choose the memory byte order; existing low-level callers default to native. */
int pipe_opengpu_resource_set_format(struct pipe_opengpu_screen *screen,
                                      struct pipe_opengpu_resource *res, uint32_t format);
void *pipe_opengpu_resource_map(struct pipe_opengpu_resource *res);
uint64_t pipe_opengpu_resource_size(const struct pipe_opengpu_resource *res);
uint32_t pipe_opengpu_resource_width(const struct pipe_opengpu_resource *res);
uint32_t pipe_opengpu_resource_height(const struct pipe_opengpu_resource *res);
uint32_t pipe_opengpu_resource_pitch(const struct pipe_opengpu_resource *res);
uint32_t pipe_opengpu_resource_handle(const struct pipe_opengpu_resource *res);
void pipe_opengpu_resource_destroy(struct pipe_opengpu_screen *screen,
                                   struct pipe_opengpu_resource *res);

/* Active OpenGPU KMS mode, or the preferred mode before a modeset. */
int pipe_opengpu_screen_display_size(struct pipe_opengpu_screen *screen,
                                     uint32_t *width, uint32_t *height);

/* Colour target for clear / draw_vbo. Width*height*4*(1<<sample_mode) must
 * fit the GEM when MSAA is enabled. For pitched 2D resources, width/height
 * must match the resource and sample_mode 0 uses resource pitch as stride. */
void pipe_opengpu_set_framebuffer(struct pipe_opengpu_context *ctx,
                                  struct pipe_opengpu_resource *color,
                                  uint32_t width, uint32_t height);

/* Multiple colour attachments (MRT): up to four colour buffers. */
void pipe_opengpu_set_framebuffer_mrt(struct pipe_opengpu_context *ctx,
                                      struct pipe_opengpu_resource *colors[4],
                                      unsigned nr_colors,
                                      uint32_t width, uint32_t height);

/* Optional persistent depth GEM (D24S8). Pass depth=NULL for the driver's
 * private cleared plane. load!=0 sets OPENGPU_SUBMIT_DEPTH_LOAD. */
void pipe_opengpu_set_depth(struct pipe_opengpu_context *ctx,
                            struct pipe_opengpu_resource *depth, int load);

/* Sample mode 0/1/2 (1x/2x/4x). Affects colour stride and submit.sample_mode. */
int pipe_opengpu_set_sample_mode(struct pipe_opengpu_context *ctx,
                                 uint32_t sample_mode);

/* Bind a validator-admitted FS binary (+ empty kernarg). Pass code=NULL for
 * fixed-function draws (no fragment core). */
int pipe_opengpu_bind_fs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes);

/* Bind a validator-admitted compute shader (+ uncached kernarg GEM). */
int pipe_opengpu_bind_cs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes);

/* Kernarg mapping after a successful bind_cs (64 bytes for the corpus). */
void *pipe_opengpu_cs_kernarg_map(struct pipe_opengpu_context *ctx);

/* Gallium launch_grid → opengpu_compute. */
int pipe_opengpu_launch_grid(struct pipe_opengpu_context *ctx,
                             const uint32_t grid[3], const uint32_t local[3],
                             struct pipe_opengpu_fence **out_fence);

struct pipe_opengpu_storage_view {
    struct pipe_opengpu_resource *resource;
    uint64_t offset, bytes;
    uint32_t access;
};
struct pipe_opengpu_uniform_view {
    struct pipe_opengpu_resource *resource;
    uint64_t offset, bytes;
};
/* Borrowed source views. The eventual native submission must independently
 * snapshot data after producer fences and retain its own job references. */
struct pipe_opengpu_native_uniform_plan {
    struct pipe_opengpu_uniform_view bindings[24], defaults[2];
    uint32_t count, stage_mask[24], active_mask[2], mapping[2][12];
};
/* Structural admission and aggregate remapping only; this does not discover
 * native submission support or publish GPU-visible records. Unused bindings
 * acquire no slot. Failure leaves *out untouched. */
int pipe_opengpu_native_uniform_plan(struct pipe_opengpu_screen *screen,
    const struct pipe_opengpu_uniform_view views[2][13],
    const unsigned required_bytes[2][13], struct pipe_opengpu_native_uniform_plan *out);
struct pipe_opengpu_buffer_texture {
    struct pipe_opengpu_resource *resource;
    uint64_t binding_bytes; /* complete logical BO, never a shortened view */
    struct drm_opengpu_compute_texture_view view;
};
struct pipe_opengpu_buffer_image {
    struct pipe_opengpu_resource *resource;
    uint64_t binding_bytes;
    struct drm_opengpu_compute_image_view view;
};
/* Atomic counter resource: slot 0..3 maps to one counter view. */
struct pipe_opengpu_counter_view {
    struct pipe_opengpu_resource *resource;
    uint64_t offset, bytes;
    uint32_t slot, count;
};
/* Synchronous buffer resources: indexed arrays retain all32/4/4 slots. A
 * partial bind or failed accepted wait poisons this private context. */
int pipe_opengpu_launch_grid_buffers(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view storage[4],
    const struct pipe_opengpu_buffer_texture textures[32],
    const struct pipe_opengpu_buffer_image images[4],
    const struct pipe_opengpu_uniform_view uniforms[13], uint32_t timeout_ms);
/* Compute launch with an atomic counter bound to slot 0..3. */
int pipe_opengpu_launch_grid_counter_resources(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view storage[4],
    const struct pipe_opengpu_buffer_texture textures[32],
    const struct pipe_opengpu_buffer_image images[4],
    const struct pipe_opengpu_uniform_view uniforms[13],
    const struct pipe_opengpu_counter_view counter, uint32_t timeout_ms);
/* Independent original GEM, all four SSBO windows preserved. counter.slot is
 * zero; its count is 1..8 and offset/count must fit the logical bound bytes. */
int pipe_opengpu_launch_grid_counter_source_resources(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view storage[4],
    const struct pipe_opengpu_buffer_texture textures[32],
    const struct pipe_opengpu_buffer_image images[4],
    const struct pipe_opengpu_uniform_view uniforms[13],
    const struct pipe_opengpu_counter_view counter, uint32_t timeout_ms);
/* Publish completed uncached compute writes with real invalidate fences.
 * Retained original BOs survive Gallium unbind until publication or teardown.
 * This is neither writeback nor permission to race CPU/cross-context writers. */
int pipe_opengpu_compute_barrier(struct pipe_opengpu_context *ctx, uint32_t timeout_ms);
/* Slot0 is the default block; slots1..12 are program-local UBOs. Sources
 * remain original GEMs: the kernel waits producers and takes a fresh snapshot. */
int pipe_opengpu_uniform_view_validate(const struct pipe_opengpu_screen *screen,
    const struct pipe_opengpu_uniform_view *view, unsigned slot);
int pipe_opengpu_launch_grid_uniforms(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view storage[4],
    const struct pipe_opengpu_uniform_view uniforms[13], uint32_t timeout_ms);
/* True kernel binding granularity, distinct from descriptor alignment. */
uint32_t pipe_opengpu_screen_storage_alignment(const struct pipe_opengpu_screen *screen);
int pipe_opengpu_storage_view_validate(const struct pipe_opengpu_screen *screen,
                                       const struct pipe_opengpu_storage_view *view);
/* Synchronous checked-storage compute. Exact kernel offsets/sizes only; no
 * rounding authority. On an accepted-job wait failure the context is poisoned
 * for this path and must be destroyed, not reused with CPU-written kernargs. */
int pipe_opengpu_launch_grid_storage(struct pipe_opengpu_context *ctx,
    const uint32_t grid[3], const uint32_t local[3],
    const struct pipe_opengpu_storage_view views[4], uint32_t timeout_ms);

/* Fill colour GEM with a 32-bit pattern. Offset/bytes must be 64-byte aligned. */
int pipe_opengpu_clear(struct pipe_opengpu_context *ctx, uint32_t pattern,
                       struct pipe_opengpu_fence **out_fence);

/* Fill an arbitrary GEM range. Offset and bytes must be 64-byte aligned. */
int pipe_opengpu_fill(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_resource *dst,
                      uint64_t offset, uint64_t bytes, uint32_t pattern,
                      struct pipe_opengpu_fence **out_fence);

/* Whole-buffer blit (64-byte aligned offset/bytes). Gallium resource_copy. */
int pipe_opengpu_blit(struct pipe_opengpu_context *ctx,
                      struct pipe_opengpu_resource *dst,
                      struct pipe_opengpu_resource *src,
                      uint64_t dst_offset, uint64_t src_offset, uint64_t bytes,
                      struct pipe_opengpu_fence **out_fence);

/* 2D cache-line copy. Width and both strides are multiples of 64. */
int pipe_opengpu_strided_blit(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_resource *dst,
                              struct pipe_opengpu_resource *src,
                              uint64_t dst_offset, uint64_t src_offset,
                              uint32_t width_bytes, uint32_t height,
                              uint32_t dst_stride, uint32_t src_stride,
                              struct pipe_opengpu_fence **out_fence);

/* Drop L2 lines so a later GPU read sees CPU writes (64-byte aligned). */
int pipe_opengpu_invalidate(struct pipe_opengpu_context *ctx,
                            struct pipe_opengpu_resource *res,
                            uint64_t offset, uint64_t bytes,
                            struct pipe_opengpu_fence **out_fence);

/* MSAA resolve (sample_mode 0/1/2). Source/dest GEMs must not overlap. */
int pipe_opengpu_resolve(struct pipe_opengpu_context *ctx,
                         struct pipe_opengpu_resource *dst,
                         struct pipe_opengpu_resource *src,
                         uint32_t width, uint32_t height,
                         uint32_t src_stride, uint32_t dst_stride,
                         uint32_t sample_mode,
                         struct pipe_opengpu_fence **out_fence);

/* Bind a texture GEM on slot 1 (width/height base; size covers the mip chain).
 * Pass tex=NULL to unbind. Own the GEM for the bind lifetime. */
/* Detach a previously bound texture; leave the binding intact on failure. */
int pipe_opengpu_unbind_texture(struct pipe_opengpu_context *ctx);
int pipe_opengpu_bind_texture(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_resource *tex,
                              uint32_t width, uint32_t height, uint32_t size,
                              uint32_t flags);

/* Bind a validator-admitted VS binary (+ empty vertex kernarg). */
int pipe_opengpu_bind_vs(struct pipe_opengpu_context *ctx,
                         const void *code, size_t bytes);

/* Copy a column-major mat4 and the Q16.16 scale into the vertex kernarg
 * uniform area and drop stale GPU lines before the next vertex launch. */
int pipe_opengpu_write_vs_matrix(struct pipe_opengpu_context *ctx,
                                 const float matrix[16]);

/* Copy both mat4s. projection is applied second and lives at byte 512.
 * modelview is applied first and lives at byte 640. */
int pipe_opengpu_write_vs_matrices(struct pipe_opengpu_context *ctx,
                                   const float projection[16],
                                   const float modelview[16]);

/* Copy one packed opacity word into every advertised fragment kernarg bank.
 * The offset and bank stride follow the discovered batch configuration. */
int pipe_opengpu_write_fs_uniform(struct pipe_opengpu_context *ctx,
                                  uint32_t word);

/* Copy up to eight float uniforms into every bank's uniform tail. */
int pipe_opengpu_write_fs_block(struct pipe_opengpu_context *ctx,
                                const float *words, unsigned nfloats);

/* Legacy eight-fragment ABI. Query the screen for new draw bindings. */
#define PIPE_OPENGPU_FS_BANK_STRIDE 320u

/* Print the lane-0 position, clip result, uniform words and the probes
 * vertex_mvp.S leaves in the depth and UV slices. Coherent DMA makes the
 * shader's stores visible after the draw fence. */
void pipe_opengpu_log_vs_kernarg(struct pipe_opengpu_context *ctx);

/* Bind a vertex-buffer GEM (fixed 32-byte vertex format). Pass vb=NULL to
 * unbind. */
int pipe_opengpu_set_vertex_buffer(struct pipe_opengpu_context *ctx,
                                   struct pipe_opengpu_resource *vb,
                                   uint32_t stride, uint32_t bytes);

/* One triangle via drm_opengpu_draw → opengpu_render (Gallium draw_vbo map). */
int pipe_opengpu_draw_vbo(struct pipe_opengpu_context *ctx,
                          const struct drm_opengpu_draw *draw,
                          struct pipe_opengpu_fence **out_fence);

/* Vertex-core draw via drm_opengpu_vertex_draw + OPENGPU_SUBMIT_VERTEX_CORE. */
int pipe_opengpu_draw_vertex(struct pipe_opengpu_context *ctx,
                             const struct drm_opengpu_vertex_draw *draw,
                             struct pipe_opengpu_fence **out_fence);
/* Explicit opt-in: private depth scratch has undefined initial contents when
 * every fixed-function consumer is disabled. Attached depth and older drivers
 * retain their usual clear/load behavior. Raw shader callers use the original
 * entry point unless they also accept undefined private depth contents. */
int pipe_opengpu_draw_vertex_discard_depth(struct pipe_opengpu_context *ctx,
                             const struct drm_opengpu_vertex_draw *draw,
                             struct pipe_opengpu_fence **out_fence);

/* Renderer-owned fragment banks: the first nine SoA slices are transient
 * scratch and must not be observed outside this draw. Uniforms are preserved.
 * Also request private-depth discard when its existing conditions allow it.
 * Older hardware/drivers use full staging. Other entry points keep their ABI. */
int pipe_opengpu_draw_vertex_transient(struct pipe_opengpu_context *ctx,
                             const struct drm_opengpu_vertex_draw *draw,
                             struct pipe_opengpu_fence **out_fence);

/* Program the OpenGPU CRTC with a 1x pitched colour GEM matching the active or
 * advertised mode. Registers an FB on first use and keeps it alive. */
int pipe_opengpu_present(struct pipe_opengpu_screen *screen,
                         struct pipe_opengpu_resource *color);

int pipe_opengpu_fence_finish(struct pipe_opengpu_context *ctx,
                              struct pipe_opengpu_fence *fence,
                              int64_t timeout_ms);
void pipe_opengpu_fence_reference(struct pipe_opengpu_fence **dst,
                                   struct pipe_opengpu_fence *src);

int pipe_opengpu_query(struct pipe_opengpu_context *ctx,
                       struct drm_opengpu_query *command);

#endif
