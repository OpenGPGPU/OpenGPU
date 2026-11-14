/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_DRM_H
#define OPENGPU_DRM_H

#include <drm/drm.h>
#include "opengpu_formats.h"

/* Render-node-safe replacement for the core dumb-buffer ioctls.  The core
 * MODE_CREATE_DUMB and MODE_MAP_DUMB commands intentionally lack
 * DRM_RENDER_ALLOW, so render nodes cannot use them. */
struct drm_opengpu_create_buffer {
    __u32 width;
    __u32 height;
    __u32 bpp;
    __u32 handle;
    __u32 pitch;
    __u32 size;
    __u64 offset;
};

/* Userspace draw record copied into per-job kernel DMA storage after
 * validation. shader_pc/kernarg are binding-relative offsets, never raw
 * device addresses; core-backed submission remains capability-gated. */
struct drm_opengpu_draw {
    __s32 v0[4];
    __s32 v1[4];
    __s32 v2[4];
    __u32 c0[3];
    __u32 c1[3];
    __u32 c2[3];
    __s32 d0;
    __s32 d1;
    __s32 d2;
    __u32 shader_pc;
    __u32 kernarg;
    __u32 uv0[2];
    __u32 uv1[2];
    __u32 uv2[2];
    __u32 state;
    __u32 sampler;
    __u32 kernarg_bank_stride;
    __u32 blend_config;
    __u32 stencil_config;
    __u32 stencil_ref;
    /* Half-open scissor in scanout pixels. Meaningful only with
     * OPENGPU_DRAW_STATE_SCISSOR; otherwise both words must be zero. */
    __u32 scissor_min;
    __u32 scissor_max;
};

/* Vertex-core form of the same 40-word command record. All addresses are
 * offsets into the matching submission bindings and are relocated only after
 * validation. Both formats use the fixed 32-byte vertex layout. Format 1
 * derives D24 depth from the clipped vertex shader output. */
#define OPENGPU_VERTEX_FORMAT_CLIP_DEPTH 1u
struct drm_opengpu_vertex_draw {
    __u32 vertex_buffer;
    __u32 vertex_count;
    __u32 vertex_stride;
    __u32 vertex_shader_pc;
    __u32 vertex_kernarg;
    __u32 vertex_kernarg_bank_stride;
    __u32 vertex_format;
    /* With STATE_VIEWPORT, reserved0[0] packs signed 16-bit x/y origins and
     * reserved0[1] packs unsigned 16-bit width/height. Otherwise both are
     * zero. With STATE_DEPTH_RANGE, reserved0[2] and [3] contain D24 near
     * and far; otherwise both are zero. On devices advertising constant
     * blend, reserved0[4] holds packed RGBA8 when a constant factor is used;
     * otherwise it is zero. Remaining words are reserved. */
    __u32 reserved0[17];
    __u32 fragment_shader_pc;
    __u32 fragment_kernarg;
    __u32 reserved1[6];
    __u32 state;
    __u32 sampler;
    __u32 fragment_kernarg_bank_stride;
    __u32 blend_config;
    __u32 stencil_config;
    __u32 stencil_ref;
    /* Half-open scissor in scanout pixels. Meaningful only with
     * OPENGPU_DRAW_STATE_SCISSOR; otherwise both words must be zero. */
    __u32 scissor_min;
    __u32 scissor_max;
};

/* Per-draw state override. Resource addresses and extents remain job-owned.
 * Depth and stencil comparisons share the hardware encoding: 0 LESS,
 * 1 LEQUAL, 2 GREATER, 3 GEQUAL, 4 EQUAL, 5 NOTEQUAL, 6 ALWAYS, 7 NEVER. */
#define OPENGPU_DRAW_STATE_OVERRIDE       (1u << 0)
#define OPENGPU_DRAW_STATE_DEPTH_TEST     (1u << 1)
#define OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT 4u
#define OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK  (0x7u << 4)
#define OPENGPU_DRAW_STATE_DEPTH_WRITE    (1u << 7)
#define OPENGPU_DRAW_STATE_CULL_SHIFT     8u
#define OPENGPU_DRAW_STATE_CULL_MASK      (0x3u << 8)
#define OPENGPU_DRAW_STATE_TEX_ENABLE     (1u << 10)
#define OPENGPU_DRAW_STATE_TEX_CLAMP      (1u << 11)
#define OPENGPU_DRAW_STATE_MAX_MIP_SHIFT  12u
#define OPENGPU_DRAW_STATE_MAX_MIP_MASK   (0xfu << 12)
#define OPENGPU_DRAW_STATE_BLEND_ENABLE   (1u << 16)
#define OPENGPU_DRAW_STATE_STENCIL_TEST   (1u << 17)
#define OPENGPU_DRAW_STATE_SCISSOR        (1u << 18)
/* Bits 19..28 belong to trusted, kernel-generated fragment usage metadata. */
#define OPENGPU_DRAW_STATE_VIEWPORT       (1u << 29)
#define OPENGPU_DRAW_STATE_VIEWPORT_FLIP_Y (1u << 30)
#define OPENGPU_DRAW_STATE_DEPTH_RANGE    (1u << 31)
#define OPENGPU_DRAW_STATE_VALID_MASK     0xe007ffffu

/* Draw-record word 33: sampler state and the rasterization center mode. */
#define OPENGPU_DRAW_SAMPLER_LOD_BIAS_MASK 0x1fu
/* Opt-in base-level nearest filtering; query the driver feature first. */
#define OPENGPU_DRAW_SAMPLER_NEAREST       (1u << 5)
/* Opt-in half-integer pixel centers; query the driver feature first. */
#define OPENGPU_DRAW_RASTER_PIXEL_CENTER   (1u << 6)
/* Invert the V wrap mode relative to the U mode in draw-record state. */
#define OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP   (1u << 7)
#define OPENGPU_DRAW_SAMPLER_MIN_LOD_SHIFT 8u
#define OPENGPU_DRAW_SAMPLER_MIN_LOD_MASK  (0xfu << 8)
/* For a clamped vertex-draw axis, vertices are signed Q16.16 plus 2^31.
 * Interpolation removes that bias and clamps negative results to zero. */
#define OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U (1u << 12)
#define OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_V (1u << 13)
#define OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_MASK \
    (OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U | OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_V)
/* Vertex Q16.16 coordinates are biased by 2^31 (an even period count). */
#define OPENGPU_DRAW_SAMPLER_MIRROR_U      (1u << 14)
#define OPENGPU_DRAW_SAMPLER_MIRROR_V      (1u << 15)
#define OPENGPU_DRAW_SAMPLER_MIRROR_MASK \
    (OPENGPU_DRAW_SAMPLER_MIRROR_U | OPENGPU_DRAW_SAMPLER_MIRROR_V)
#define OPENGPU_DRAW_SAMPLER_VALID_MASK    0xffffu

/* Per-draw GL-style blend config (draw-record word 35).  Present overrides
 * the legacy source-over OPENGPU_DRAW_STATE_BLEND_ENABLE. */
#define OPENGPU_DRAW_BLEND_PRESENT        (1u << 0)
#define OPENGPU_DRAW_BLEND_SRC_SHIFT      4u
#define OPENGPU_DRAW_BLEND_SRC_MASK       (0xfu << 4)
#define OPENGPU_DRAW_BLEND_DST_SHIFT      8u
#define OPENGPU_DRAW_BLEND_DST_MASK       (0xfu << 8)
#define OPENGPU_DRAW_BLEND_EQ_SHIFT       12u
#define OPENGPU_DRAW_BLEND_EQ_MASK        (0x7u << 12)
#define OPENGPU_DRAW_BLEND_VALID_MASK     0x7ff1u
/* Valid on vertex draws only when CONSTANT_BLEND is advertised. */
#define OPENGPU_DRAW_BLEND_CONSTANT_COLOR      11u
#define OPENGPU_DRAW_BLEND_INV_CONSTANT_COLOR  12u
#define OPENGPU_DRAW_BLEND_CONSTANT_ALPHA      13u
#define OPENGPU_DRAW_BLEND_INV_CONSTANT_ALPHA  14u

/* Per-draw stencil config (draw-record word 36): the func shares the
 * depth-func encoding, ops use the GL 3-bit encoding. */
/* Stencil operation codes: 0 KEEP, 1 ZERO, 2 REPLACE, 3 INCR, 4 DECR,
 * 5 INVERT, 6 INCR_WRAP, 7 DECR_WRAP. */
#define OPENGPU_DRAW_STENCIL_FUNC_SHIFT   0u
#define OPENGPU_DRAW_STENCIL_FUNC_MASK    (0x7u << 0)
#define OPENGPU_DRAW_STENCIL_FAIL_SHIFT   3u
#define OPENGPU_DRAW_STENCIL_FAIL_MASK    (0x7u << 3)
#define OPENGPU_DRAW_STENCIL_ZFAIL_SHIFT  6u
#define OPENGPU_DRAW_STENCIL_ZFAIL_MASK   (0x7u << 6)
#define OPENGPU_DRAW_STENCIL_ZPASS_SHIFT  9u
#define OPENGPU_DRAW_STENCIL_ZPASS_MASK   (0x7u << 9)
#define OPENGPU_DRAW_STENCIL_VALID_MASK   0xfffu

/* Per-draw stencil reference and masks (draw-record word 37). */
#define OPENGPU_DRAW_STENCIL_REF_SHIFT    0u
#define OPENGPU_DRAW_STENCIL_REF_MASK     0xffu
#define OPENGPU_DRAW_STENCIL_RMASK_SHIFT  8u
#define OPENGPU_DRAW_STENCIL_RMASK_MASK   (0xffu << 8)
#define OPENGPU_DRAW_STENCIL_WMASK_SHIFT  16u
#define OPENGPU_DRAW_STENCIL_WMASK_MASK   (0xffu << 16)
#define OPENGPU_DRAW_STENCIL_REF_VALID_MASK 0xffffffu

struct drm_opengpu_context {
    __u32 id;
    __u32 flags;
};

struct drm_opengpu_param {
    __u32 param;
    __u32 pad;
    __u64 value;
};

#define OPENGPU_PARAM_CAPABILITIES 0u
/* Same packed lane/warp/fragment-bank/graphics-CU bytes as SHADER_CONFIG.
 * Returns zero on older hardware without OPENGPU_CAP_SHADER_CONFIG_INFO. */
#define OPENGPU_PARAM_SHADER_CONFIG 1u
/* Available software submission features (including hardware-gated paths). Older
 * drivers return EINVAL for this query; callers must then use zero features. */
#define OPENGPU_PARAM_DRIVER_FEATURES 2u
/* Effective direct-compute submission limits. Both return zero when unified
 * compute is unavailable or the hardware limit is unknown. Older drivers
 * return EINVAL. Each local axis must fit LOCAL_AXIS and the product must fit
 * LOCAL_ITEMS. */
#define OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS 3u
#define OPENGPU_PARAM_COMPUTE_LOCAL_AXIS 4u
/* Immutable GCR1 shared-SRAM discovery, used by checked compute admission.
 * All three are zero when the isolated shared-memory contract is unavailable.
 * BASE may be zero for an available range; BYTES and LANES must be nonzero.
 * These describe hardware, not general shader/API shared-memory support. */
#define OPENGPU_PARAM_COMPUTE_SHARED_BASE 5u
#define OPENGPU_PARAM_COMPUTE_SHARED_BYTES 6u
#define OPENGPU_PARAM_COMPUTE_SHARED_LANES 7u
/* Zero until the protected native route is available; see native_layout.h. */
#define OPENGPU_PARAM_NATIVE_LAYOUT 8u
/* Eight individually queried, validated GNC words. Upper value bits are zero;
 * all return zero until DRIVER_FEATURE_NATIVE_BINNED is available. */
#define OPENGPU_PARAM_NATIVE_CAPS_BASE 0x100u
#define OPENGPU_PARAM_NATIVE_CAPS_WORDS 8u
#define OPENGPU_DRIVER_FEATURE_DISCARD_PRIVATE_DEPTH (1u << 0)
#define OPENGPU_DRIVER_FEATURE_TRANSIENT_FRAGMENT_STAGING (1u << 1)
/* Relaxed 32-bit AMOs/CAS within a compute submission's bound kernarg. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS (1u << 2)
/* Compute-texture submission; see its bounded resource and ordering contract. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE (1u << 3)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE (1u << 4)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES (1u << 5)
/* Exact same local GEM byte range may be shared by compute texture and storage. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_ALIAS (1u << 6)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES (1u << 7)
/* Canonical fence rw,rw is admitted in compute-image programs. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_FENCE (1u << 8)
/* Converged workgroup barriers are admitted in compute-image programs. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BARRIER (1u << 9)
/* One local storage binding may back multiple compute image units. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_ALIAS (1u << 10)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURED_IMAGES (1u << 11)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE (1u << 12)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE (1u << 13)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_ATOMICS (1u << 14)
/* One local storage slot may back both a storage binding and image views. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_ALIAS (1u << 15)
/* Exact same local GEM range may back image and texture views. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_ALIAS (1u << 16)
/* Distinct ranges of the same local GEM object may alias. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_RANGE_ALIAS (1u << 17)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_RANGE_ALIAS (1u << 18)
/* Image and storage views may use distinct slots/ranges of one local GEM. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_RANGE_ALIAS (1u << 19)
/* Image views may use distinct slots/ranges of one local GEM. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_RANGE_ALIAS (1u << 20)
/* Storage bindings may use distinct slots/ranges of one local GEM. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BINDING_ALIAS (1u << 21)
/* Checked storage word atomics are available to compute jobs. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS (1u << 22)
#define OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS (1u << 23)
/* Direct, version-1 immutable compute uniform publications. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS (1u << 24)
/* Independent mutable local-GEM counter data, without a storage binding slot. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE (1u << 25)
/* Producer-fenced immutable argument snapshots, not live GPU argument reads. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES (1u << 26)
/* Storage offsets may be 256-byte aligned and sizes retain exact byte bounds. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS (1u << 27)
/* Versioned target5 transports. These do not advertise GL/GLES capabilities. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE (1u << 28)
/* Includes target5 GIU views and metadata-only image size (function27). */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES (1u << 29)
/* Four additional private 128 MiB backings, usable only by image views.
 * This is a driver VA allocation capability, not a new hardware image unit. */
#define OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS (1u << 30)
/* Vertex format 1, with default GLES depth range. Query before submitting it. */
#define OPENGPU_DRIVER_FEATURE_CLIP_DEPTH (1ull << 31)
#define OPENGPU_DRIVER_FEATURE_SAMPLER_NEAREST (1ull << 32)
#define OPENGPU_DRIVER_FEATURE_PIXEL_CENTER (1ull << 33)
#define OPENGPU_DRIVER_FEATURE_SAMPLER_AXIS_WRAP (1ull << 34)
#define OPENGPU_DRIVER_FEATURE_VIEWPORT (1ull << 35)
#define OPENGPU_DRIVER_FEATURE_DEPTH_RANGE (1ull << 36)
#define OPENGPU_DRIVER_FEATURE_CONSTANT_BLEND (1ull << 37)
#define OPENGPU_DRIVER_FEATURE_BIASED_CLAMP (1ull << 38)
#define OPENGPU_DRIVER_FEATURE_MIRRORED_REPEAT (1ull << 39)
/* Protected native descriptors, code/uniform snapshots and whole-job preflight.
 * This bit is withheld until the production submission path is available. */
#define OPENGPU_DRIVER_FEATURE_NATIVE_BINNED (1ull << 40)
/* Reserved for separately qualified GNJ2/GNF3/GNY2 protected geometry transport.
 * Never inferred from bit40, GUC1 or the presence of an unused software module. */
#define OPENGPU_DRIVER_FEATURE_NATIVE_GEOMETRY (1ull << 42)
#define OPENGPU_SHADER_CONFIG_LANES_SHIFT 0u
#define OPENGPU_SHADER_CONFIG_WARPS_SHIFT 8u
#define OPENGPU_SHADER_CONFIG_BANKS_SHIFT 16u
#define OPENGPU_SHADER_CONFIG_CUS_SHIFT   24u
#define OPENGPU_SHADER_CONFIG_FIELD_MASK  0xffu
#define OPENGPU_CAP_FRAGMENT_CORE (1u << 0)
/* Bit1 was the host-memory job ring, retired; it is reserved and never set. */
#define OPENGPU_CAP_VERTEX_CORE (1u << 2)
#define OPENGPU_CAP_CLEAR_ENGINE (1u << 3)
#define OPENGPU_CAP_BLIT_ENGINE (1u << 4)
#define OPENGPU_CAP_STRIDED_ENGINE (1u << 5)
#define OPENGPU_CAP_UNIFIED_COMMANDS (1u << 6)
/* MSAA (bit7) is the backend selected by GPU_CAP_FRAGMENT_CORE (bit0): both
 * set is programmable MSAA, bit0 clear is fixed-function MSAA. Bits 17:16
 * carry the max sample mode. */
#define OPENGPU_CAP_MSAA (1u << 7)
#define OPENGPU_CAP_MSAA_MAX_MODE_SHIFT 16u
#define OPENGPU_CAP_MSAA_MAX_MODE_MASK \
    (0x3u << OPENGPU_CAP_MSAA_MAX_MODE_SHIFT)
#define OPENGPU_CAP_FRAGMENT_BATCH_SHIFT 8u
#define OPENGPU_CAP_FRAGMENT_BATCH_MASK \
    (0xffu << OPENGPU_CAP_FRAGMENT_BATCH_SHIFT)
/* Persistent depth attachment (bit19). When advertised, a submission may carry
 * its own depth attachment (depth_handle/depth_offset) and preserve it across
 * submissions with OPENGPU_SUBMIT_DEPTH_LOAD. */
#define OPENGPU_CAP_PERSISTENT_DEPTH (1u << 19)
#define OPENGPU_CAP_HW_VBLANK (1u << 21)
/* Compute shaders may use flw and RVV .vf operands (bit22). */
#define OPENGPU_CAP_COMPUTE_SCALAR_FPU (1u << 22)
#define OPENGPU_CAP_SHADER_CONFIG_INFO (1u << 23)
/* Launch x9 = lanes * warps; VLMAX shaders calculate staging strides at runtime. */
#define OPENGPU_CAP_RUNTIME_SHADER_ABI (1u << 24)
/* Descriptor-selected framebuffer and sampler byte orders. */
#define OPENGPU_CAP_NATIVE_PIXEL_FORMATS (1u << 25)
#define OPENGPU_CAP_RENDER_EXTENTS (1u << 26)
#define OPENGPU_CAP_FRAGMENT_INPUT_USAGE (1u << 27)
#define OPENGPU_CAP_FRAGMENT_OUTPUT_USAGE (1u << 28)


/* Sample mode field width (0 = 1x, 1 = 2x, 2 = 4x). */
#define OPENGPU_MSAA_MODE_MASK 0x3u

/* Device-global snapshot of the most recent unified-command fault. Sequence
 * zero means that no fault has been observed since driver initialization.
 * The status field is the raw GPU_UCMD_* result value. */
struct drm_opengpu_fault {
    __u64 sequence;
    __u64 bytes_processed;
    __u64 expected_bytes;
    __s32 error;
    __u32 command_id;
    __u32 opcode;
    __u32 status;
    __u32 flags;
    __u32 expected_command_id;
    __u32 expected_opcode;
    __u32 pad[2];
};

#define OPENGPU_FAULT_VALID               (1u << 0)
#define OPENGPU_FAULT_TIMEOUT             (1u << 1)
#define OPENGPU_FAULT_ABORTED             (1u << 2)
#define OPENGPU_FAULT_COMPLETION_ERROR    (1u << 3)
#define OPENGPU_FAULT_COMMAND_ID_MISMATCH (1u << 4)
#define OPENGPU_FAULT_OPCODE_MISMATCH     (1u << 5)
#define OPENGPU_FAULT_SUCCESS_MISMATCH    (1u << 6)
#define OPENGPU_FAULT_BYTES_MISMATCH      (1u << 7)
/* A safe unified-command reset was issued to recover from this fault, and
 * the drain never completed within the driver's reset window (the device is
 * wedged until it is reloaded or the fabric is reset). */
#define OPENGPU_FAULT_RESET_ISSUED        (1u << 8)
#define OPENGPU_FAULT_RESET_TIMEOUT       (1u << 9)
#define OPENGPU_FAULT_FLAGS_MASK          0x3ffu

enum drm_opengpu_resource_type {
    OPENGPU_RESOURCE_SHADER = 1,
    OPENGPU_RESOURCE_KERNARG = 2,
    OPENGPU_RESOURCE_TEXTURE = 3,
    OPENGPU_RESOURCE_VERTEX_BUFFER = 4,
    OPENGPU_RESOURCE_VERTEX_SHADER = 5,
    OPENGPU_RESOURCE_VERTEX_KERNARG = 6,
    OPENGPU_RESOURCE_COMPUTE_SHADER = 7,
    OPENGPU_RESOURCE_COMPUTE_KERNARG = 8,
    /* Raw backing for texture reads; view and sampler metadata belong to a future
     * compute texture submission, not to this binding. */
    OPENGPU_RESOURCE_COMPUTE_TEXTURE = 9,
    /* Slots 13–16 or 35–38 carry up to 128 MiB each. Feature-gated slots
     * 39–42 provide four independent image-only backings of the same size. */
    OPENGPU_RESOURCE_COMPUTE_STORAGE = 10,
};

struct drm_opengpu_resource {
    __u32 context_id;
    __u32 slot;
    __u32 handle;
    __u32 type;
    __u64 offset;
    __u64 size;
    __u32 width;
    __u32 height;
    __u32 flags;
    __u32 pad;
};

#define OPENGPU_RESOURCE_TEXTURE_FORMAT_SHIFT 8u
#define OPENGPU_RESOURCE_TEXTURE_FORMAT_MASK (7u << OPENGPU_RESOURCE_TEXTURE_FORMAT_SHIFT)
#define OPENGPU_RESOURCE_TEXTURE_CLAMP (1u << 0)
#define OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT 4u
#define OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK \
    (0xfu << OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT)
/* Request uncached access for translated resources (compute kernarg/texture).
 * Accepted for all resource types; Bare graphics bindings fall back to
 * bind-time invalidation, and shaders are snapshotted. CPU writes to those
 * fallback bindings require rebinding or explicit cache invalidation. */
#define OPENGPU_RESOURCE_UNCACHED (1u << 16)
#define OPENGPU_MAX_RESOURCE_SLOTS 16u
/* Slots 17–34 are reserved for compute texture backing. Together with the
 * lower slots this allows 32 distinct textures beside shader and kernarg. */
#define OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS 34u
#define OPENGPU_MAX_RESOURCE_BINDINGS 42u

struct drm_opengpu_submit {
    __u32 context_id;
    __u32 command_handle;
    __u32 color_handle;
    __u32 color_handle2;
    __u32 color_handle3;
    __u32 color_handle4;
    __u32 stride;
    __u64 command_offset;
    __u32 command_count;
    __u32 flags;
    __u32 shader_slot;
    __u32 kernarg_slot;
    __u32 texture_slot;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 vertex_buffer_slot;
    __u32 vertex_shader_slot;
    __u32 vertex_kernarg_slot;
    /* bits 1:0 sample mode (OPENGPU_MSAA_MODE_MASK); reserved bits must be 0. */
    __u32 sample_mode;
    /* Persistent depth attachment. Zero depth_handle selects the driver's
     * private depth plane, freshly cleared per submission (legacy behaviour).
     * A nonzero handle binds that GEM's byte range [depth_offset,
     * depth_offset + stride * height) as the depth/stencil plane; it must be
     * word-aligned, large enough, and a different GEM object than the command
     * and colour handles. Without OPENGPU_SUBMIT_DEPTH_LOAD the region is
     * cleared to the D24S8 far value before the draw; with it the stored
     * contents are kept so a render pass can continue across submissions. */
    __u32 depth_handle;
    __u64 depth_offset;
    /* Render-target dimensions, independent of the scanout mode. Both zero
     * retain the legacy scanout-sized viewport; otherwise both are required.
     * Appended fields preserve older ioctl callers (DRM zero-fills them). */
    __u32 render_width;
    __u32 render_height;
};

/* Ordered whole-cache-line copy. Source and destination ranges are validated
 * GEM-relative offsets and must not overlap. Source and destination must use
 * distinct known backing objects, including imported aliases. Completion uses
 * the same scheduler/syncobj fence model as rendering. */
struct drm_opengpu_blit {
    __u32 context_id;
    __u32 source_handle;
    __u32 destination_handle;
    __u32 flags;
    __u64 source_offset;
    __u64 destination_offset;
    __u64 bytes;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered whole-cache-line fill. The destination is a validated GEM-relative
 * range. Completion follows the render/blit scheduler and syncobj model. */
struct drm_opengpu_fill {
    __u32 context_id;
    __u32 destination_handle;
    __u32 pattern;
    __u32 flags;
    __u64 destination_offset;
    __u64 bytes;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered two-dimensional cache-line copy. Width and both strides are
 * multiples of 64; each stride is at least width. Source and destination
 * bounding ranges must not overlap; known backing objects must be distinct. */
struct drm_opengpu_strided_blit {
    __u32 context_id;
    __u32 source_handle;
    __u32 destination_handle;
    __u32 flags;
    __u64 source_offset;
    __u64 destination_offset;
    __u32 width_bytes;
    __u32 height;
    __u32 source_stride;
    __u32 destination_stride;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered MSAA resolve. The source is a multisample colour buffer and the
 * destination is a single-sample colour buffer; both are validated
 * GEM-relative ranges that must not overlap, including known aliases of one
 * backing. Disjoint ranges within one backing remain valid. sample_mode is
 * 0/1/2 (1x/2x/4x)
 * and must not exceed the device's advertised maximum. Completion follows the
 * render/blit scheduler and syncobj model, and the destination fence gates
 * scanout. */
struct drm_opengpu_resolve {
    __u32 context_id;
    __u32 source_handle;
    __u32 destination_handle;
    __u32 flags;
    __u64 source_offset;
    __u64 destination_offset;
    __u32 width;
    __u32 height;
    __u32 source_stride;
    __u32 destination_stride;
    __u32 sample_mode;
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Ordered L2 line invalidate over a validated GEM-relative range.  The range is
 * 64-byte aligned and the operation issues no memory traffic: it drops the
 * corresponding shared-L2 lines (and snoops their L1 holders) so a later GPU
 * read observes what a non-coherent agent (the CPU) wrote to the same range.
 * Completion follows the render/blit scheduler and syncobj model. */
struct drm_opengpu_invalidate {
    __u32 context_id;
    __u32 handle;
    __u32 flags;
    __u32 in_syncobj;
    __u64 offset;
    __u64 bytes;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
    __u32 pad;
};

/* Ordered general-compute launch. The program and kernarg addresses are
 * binding-relative offsets; the kernel validates and relocates both before
 * submitting the launch through the unified command path. */
struct drm_opengpu_compute {
    __u32 context_id;
    __u32 shader_slot;
    __u32 kernarg_slot;
    __u32 flags;
    __u64 shader_offset;
    __u64 kernarg_offset;
    __u32 grid[3];
    __u32 local[3];
    __u32 in_syncobj;
    __u32 out_syncobj;
    __u32 wait_event;
    __u32 signal_event;
};

/* Resource metadata is copied once at ioctl entry. Level offsets are relative
 * to the raw OPENGPU_RESOURCE_COMPUTE_TEXTURE binding, never GPU pointers. */
#define OPENGPU_COMPUTE_TEXTURE_MAX_UNITS 32u
#define OPENGPU_COMPUTE_TEXTURE_MAX_LEVELS 16u
#define OPENGPU_COMPUTE_TEXTURE_LEVEL_BLOCK_4X4 (1u << 0)
struct drm_opengpu_compute_texture_level {
    __aligned_u64 offset;
    union { __aligned_u64 row_pitch; __aligned_u64 buffer_bytes; };
    __aligned_u64 slice_pitch, layer_pitch;
    __u32 level;
    union { __u32 width; __u32 buffer_texels; };
    __u32 height, depth, layers, block_bytes, flags, pad;
};
struct drm_opengpu_compute_texture_view {
    __u32 slot, target, format;
    union { __u32 width; __u32 buffer_texels; };
    __u32 height, depth, layers, base_level, max_level;
    __u32 swizzle_version, swizzle[4];
    __u32 min_filter, mag_filter, wrap_s, wrap_t, wrap_r;
    __u32 min_lod_bits, max_lod_bits, compare_enable, compare_function;
    __u32 level_count, pad;
    struct drm_opengpu_compute_texture_level levels[OPENGPU_COMPUTE_TEXTURE_MAX_LEVELS];
};
/* For target5 only, buffer_texels replaces width and buffer_bytes replaces the
 * single level's row_pitch. The byte range is binding-relative offset+bytes;
 * height/depth/layers are1, all levels/pitches/flags/pad are0. The kernel derives
 * the count from the complete range and format, then checks both counts. No
 * sampler field participates. Other targets retain their original meanings,
 * structure sizes, offsets and ioctl numbers. */
struct drm_opengpu_compute_textures {
    struct drm_opengpu_compute launch;
    __aligned_u64 views; /* userspace array indexed by texture unit; slot zero is unbound */
    __u32 view_count, pad;
};

/* Four native storage bindings use COMPUTE_STORAGE resources in slots 13–16
 * or 35–38 (each pair shares a private VM window). A zero slot and
 * zero access leave a binding unbound. Each nonzero binding describes its
 * complete bound range; descriptors are generated and owned by the kernel. */
#define OPENGPU_COMPUTE_STORAGE_READ 1u
#define OPENGPU_COMPUTE_STORAGE_WRITE 2u
struct drm_opengpu_compute_storage_binding { __u32 slot, access; };
struct drm_opengpu_compute_storage {
    struct drm_opengpu_compute launch;
    struct drm_opengpu_compute_storage_binding bindings[4];
};

/* Submit storage and texture views together; views are copied at entry. */
struct drm_opengpu_compute_resources {
    struct drm_opengpu_compute launch;
    struct drm_opengpu_compute_storage_binding bindings[4];
    __aligned_u64 views;
    __u32 view_count, pad;
};

/* Image units index these fixed views. Backings use COMPUTE_STORAGE slots and
 * are retained through completion. IMAGE_BACKINGS additionally permits slots
 * 39–42 beside all four SSBOs and 32 textures. These are not new SSBO bindings.
 * The offset is relative to the binding. */
#define OPENGPU_COMPUTE_IMAGE_READ (1u << 0)
#define OPENGPU_COMPUTE_IMAGE_WRITE (1u << 1)
#define OPENGPU_COMPUTE_IMAGE_BY_SIZE (1u << 2)
#define OPENGPU_COMPUTE_IMAGE_LAYERED (1u << 3)
struct drm_opengpu_compute_image_view {
    __aligned_u64 offset;
    union { __aligned_u64 row_pitch; __aligned_u64 buffer_bytes; };
    __aligned_u64 slice_pitch, layer_pitch;
    __u32 slot, flags, target, storage_format, image_format, level;
    union { __u32 width; __u32 buffer_texels; };
    __u32 height, depth, layers, layer, pad;
};
/* target5 uses the same range/count aliases as a texture level. Only level0,
 * nonlayered binding and the existing image-format/access policies apply. */
struct drm_opengpu_compute_images {
    struct drm_opengpu_compute launch;
    struct drm_opengpu_compute_image_view views[4];
};
/* Submit texture and image views in one job. Same-object aliases require the
 * matching IMAGE_TEXTURE_ALIAS or IMAGE_TEXTURE_RANGE_ALIAS feature bit. */
struct drm_opengpu_compute_textured_images {
    struct drm_opengpu_compute launch;
    struct drm_opengpu_compute_image_view images[4];
    __aligned_u64 textures;
    __u32 texture_count, pad;
};

/* Image, storage and texture views may share a local object under their
 * respective alias feature bits; other backings must be disjoint. */
struct drm_opengpu_compute_image_resources {
    struct drm_opengpu_compute launch;
    struct drm_opengpu_compute_storage_binding bindings[4];
    struct drm_opengpu_compute_image_view images[4];
    __aligned_u64 textures;
    __u32 texture_count, pad;
};

/* One counter view, disjoint from every other job resource. Optional image
 * and storage arrays are enabled by flags; texture views are copied at entry. */
#define OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE (1u << 0)
#define OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES (1u << 1)
struct drm_opengpu_compute_counter_binding {
    __aligned_u64 offset;
    __u32 slot, count;
};
struct drm_opengpu_compute_counter_resources {
    struct drm_opengpu_compute launch;
    struct drm_opengpu_compute_counter_binding counter;
    struct drm_opengpu_compute_storage_binding bindings[4];
    struct drm_opengpu_compute_image_view images[4];
    __aligned_u64 textures;
    __u32 texture_count, resource_flags;
};

/* Uniform sources are local GEM handles, not permanent resource slots or GPU
 * pointers. The driver waits producer fences and copies the indicated bytes
 * into a job-owned immutable allocation. CPU writes must not race submission.
 * Blocks map logical indices 0..11; this is not an API binding namespace.
 * Version 1 submits a direct kernel and retains the existing shader sandbox. */
#define OPENGPU_COMPUTE_UNIFORM_VERSION 1u
#define OPENGPU_COMPUTE_UNIFORM_BLOCKS 12u
#define OPENGPU_COMPUTE_UNIFORM_BLOCK_BYTES 16384u
#define OPENGPU_COMPUTE_UNIFORM_DEFAULT_BYTES 4096u
#define OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS (1u << 2)
struct drm_opengpu_compute_uniform_view {
    __aligned_u64 offset;
    __u32 handle, bytes;
};
struct drm_opengpu_compute_uniform_resources {
    struct drm_opengpu_compute_counter_resources resources;
    __u32 version, active_mask;
    struct drm_opengpu_compute_uniform_view blocks[OPENGPU_COMPUTE_UNIFORM_BLOCKS];
    struct drm_opengpu_compute_uniform_view defaults;
    __u32 pad[2];
};

/* Protected native GNJ submission. All record pointers name userspace bytes
 * copied exactly once into unexportable kernel storage. Packet GPU addresses
 * for metadata, code and uniforms must be zero; only the kernel chooses and
 * patches private addresses. Source GEM producers must complete before copying
 * program/uniform bytes; those GEM references and writable resources survive
 * the job's completion, translation drain and private mapping revocation.
 *
 * This reserves the ABI shape; support requires DRIVER_FEATURE_NATIVE_BINNED.
 * It is independent of GLES/KHR feature advertisement. Source uniform windows
 * need four-byte alignment; copied UBO data is privately realigned to 256 bytes.
 * CPU writes must not race the initial copy. Later CPU changes do not mutate
 * already copied metadata, code or uniform bytes.
 */
#define OPENGPU_NATIVE_SUBMIT_VERSION 1u
#define OPENGPU_NATIVE_UNIFORM_BLOCKS 12u
#define OPENGPU_NATIVE_VERTEX_BINDINGS 16u
struct drm_opengpu_native_uniform_stage {
    __u32 active_mask, pad;
    struct drm_opengpu_compute_uniform_view blocks[OPENGPU_NATIVE_UNIFORM_BLOCKS];
    struct drm_opengpu_compute_uniform_view defaults;
};
struct drm_opengpu_native_program_view {
    __aligned_u64 offset;
    __u32 handle, bytes, entry_offset, pad;
};
/* One exact GND ordinal and its referenced GFS state. Linked logical UBO block
 * order is per-stage, never an application global binding index. Reusing a GFS
 * handle requires identical FS program/uniform plans. A producer may instead
 * supply distinct state handles; the native state capacity is not reduced.
 * Input/scratch views name original local GEM objects, not mutable context
 * binding slots. Unused views must be entirely zero.
 */
struct drm_opengpu_native_draw_plan {
    __u32 draw_index, state_handle;
    struct drm_opengpu_native_program_view vertex_program, fragment_program;
    struct drm_opengpu_native_uniform_stage vertex_uniforms, fragment_uniforms;
    struct drm_opengpu_compute_uniform_view bindings[OPENGPU_NATIVE_VERTEX_BINDINGS];
    struct drm_opengpu_compute_uniform_view indices, indirect, vertex_scratch;
};
/* Explicit protected GNJ2/GNF3/GNY2 graph. The version-1 plan stays 792
 * bytes and all its reserved/header rules are unchanged. Geometry metadata is
 * an inline one-time copy, not a nested userspace pointer or raw GPU address.
 * An ordinary GND1 must supply an entirely zero geometry extension. */
#define OPENGPU_NATIVE_LAYERED_SUBMIT_VERSION 2u
struct drm_opengpu_native_completion_view {
    struct drm_opengpu_compute_uniform_view view;
    __u32 reserved[2];
};
struct drm_opengpu_native_layered_draw_plan {
    struct drm_opengpu_native_draw_plan draw;
    struct drm_opengpu_native_program_view geometry_program;
    struct drm_opengpu_native_uniform_stage geometry_uniforms;
    __u32 geometry_metadata[256];
};
struct drm_opengpu_submit_native_binned {
    __u32 version, context_id, flags, in_syncobj, out_syncobj, pad;
    __aligned_u64 job, frame, draws, draws_bytes, states, states_bytes, plans;
    __u32 plan_count, plan_stride;
    __aligned_u64 fence_seqno;
    /* Array of opengpu_native_frame_resource from native_frame_abi.h. Roles
     * choose generated fields; caller-selected relocation offsets are forbidden. */
    __aligned_u64 resources;
    __u32 resource_count, resource_stride;
    /* Version 1: both zero. Version 2: reserved[0] points to a
     * drm_opengpu_native_completion_view (24 bytes) for exact writable GNY2;
     * reserved[1] remains zero. No publication is allowed until the actual
     * GNC5/native-GS/layered route is independently discovered and checked. */
    __aligned_u64 reserved[2];
};

/* Version 1 keeps the original counter GEM mutable until job retirement. The
 * nested legacy counter binding and COUNTERS flag must be zero. Uniform views
 * may all be zero; otherwise every nested version-1 uniform rule applies. */
#define OPENGPU_COMPUTE_COUNTER_SOURCE_VERSION 1u
struct drm_opengpu_compute_counter_source {
    __aligned_u64 offset;
    __u32 handle, count;
};
struct drm_opengpu_compute_counter_source_resources {
    struct drm_opengpu_compute_uniform_resources resources;
    struct drm_opengpu_compute_counter_source source;
    __u32 version, pad[3];
};

/* Version 1 retains the counter-source resource contract and shader sandbox.
 * The driver waits producer fences, copies exactly three little-endian u32
 * counts once, checks that private copy, then submits it through GCI1. CPU
 * writes must not race the copy. Later source changes cannot affect the job.
 * launch.grid must be zero; local is still explicit. For nonempty work every
 * count must fit max_groups and their product must fit u64. Any zero count
 * preserves all three u32 values and submits a hardware no-op. max_groups must
 * always have three nonzero axes; it grants no new shader memory authority.
 * This is a bounded snapshot transport, not a live GLES indirect-buffer ABI. */
#define OPENGPU_COMPUTE_INDIRECT_VERSION 1u
struct drm_opengpu_compute_indirect_source {
    __aligned_u64 offset;
    __u32 handle, pad;
};
struct drm_opengpu_compute_indirect_resources {
    struct drm_opengpu_compute_counter_source_resources resources;
    struct drm_opengpu_compute_indirect_source source;
    __u32 max_groups[3];
    __u32 version, pad[2];
};

/* Hardware event controls shared by compute and DMA submissions. Event words
 * encode ID in bits 7:0 and generation in bits 15:8. */
#define OPENGPU_COMMAND_WAIT_EVENT   (1u << 0)
#define OPENGPU_COMMAND_SIGNAL_EVENT (1u << 1)
#define OPENGPU_COMMAND_EVENT(id, generation) \
    (((id) & 0xffu) | (((generation) & 0xffu) << 8))

#define OPENGPU_MAX_COMMANDS 64u

/* Verification-only: defer fence signaling after hardware completion so the
 * KMS implicit-sync wait is deterministically exercised under QEMU. */
#define OPENGPU_SUBMIT_TEST_FENCE_DELAY (1u << 0)
#define OPENGPU_SUBMIT_VERTEX_CORE      (1u << 1)
/* Keep the bound depth attachment's stored contents instead of clearing it at
 * the start of the submission (depth_handle must be nonzero). */
#define OPENGPU_SUBMIT_DEPTH_LOAD       (1u << 2)
/* Opt in to undefined initial contents of the private depth scratch plane.
 * Only vertex-core submissions without an attached depth plane are admitted,
 * and every record must override state with depth test/write and stencil off.
 * Query OPENGPU_PARAM_DRIVER_FEATURES before using this flag. */
#define OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH (1u << 3)
/* The first nine fragment SoA slices are transient renderer scratch: the
 * caller must not observe their contents after the draw or use them as input
 * to a later job. Shader reads and draw output remain defined; uniforms at
 * slice9+ retain their usual contract. The driver may omit proven-unused
 * staging. The kernarg DMA range must not overlap other draw resources.
 * Query both the driver feature and FRAGMENT_INPUT_USAGE capability.
 * Zero/default flags retain full staging and CPU-visible scratch contents. */
#define OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING (1u << 7)
#define OPENGPU_SUBMIT_COLOR_FORMAT_SHIFT 4u
#define OPENGPU_SUBMIT_COLOR_FORMAT_MASK (7u << OPENGPU_SUBMIT_COLOR_FORMAT_SHIFT)

#define DRM_OPENGPU_SUBMIT 0x00
#define DRM_OPENGPU_CONTEXT_CREATE 0x01
#define DRM_OPENGPU_CONTEXT_DESTROY 0x02
#define DRM_OPENGPU_RESOURCE_BIND 0x03
#define DRM_OPENGPU_RESOURCE_UNBIND 0x04
#define DRM_OPENGPU_GET_PARAM 0x05
#define DRM_OPENGPU_BLIT 0x06
#define DRM_OPENGPU_FILL 0x07
#define DRM_OPENGPU_STRIDED_BLIT 0x08
#define DRM_OPENGPU_COMPUTE 0x09
#define DRM_OPENGPU_GET_FAULT 0x0a
#define DRM_OPENGPU_RESOLVE 0x0b
#define DRM_OPENGPU_INVALIDATE 0x0c
#define DRM_OPENGPU_CREATE_BUFFER 0x0d
#define DRM_OPENGPU_COMPUTE_TEXTURES 0x0e
#define DRM_OPENGPU_COMPUTE_STORAGE 0x0f
#define DRM_OPENGPU_COMPUTE_RESOURCES 0x10
#define DRM_OPENGPU_COMPUTE_IMAGES 0x11
#define DRM_OPENGPU_COMPUTE_TEXTURED_IMAGES 0x12
#define DRM_OPENGPU_COMPUTE_IMAGE_RESOURCES 0x13
#define DRM_OPENGPU_COMPUTE_COUNTER_RESOURCES 0x14
#define DRM_OPENGPU_COMPUTE_UNIFORM_RESOURCES 0x15
#define DRM_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES 0x16
#define DRM_OPENGPU_COMPUTE_INDIRECT_RESOURCES 0x17
#define DRM_OPENGPU_QUERY 0x18
#define DRM_OPENGPU_SUBMIT_NATIVE_BINNED 0x19
#define DRM_IOCTL_OPENGPU_SUBMIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_SUBMIT, \
             struct drm_opengpu_submit)
#define DRM_IOCTL_OPENGPU_CONTEXT_CREATE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_CONTEXT_CREATE, \
             struct drm_opengpu_context)
#define DRM_IOCTL_OPENGPU_CONTEXT_DESTROY \
    DRM_IOW(DRM_COMMAND_BASE + DRM_OPENGPU_CONTEXT_DESTROY, \
            struct drm_opengpu_context)
#define DRM_IOCTL_OPENGPU_RESOURCE_BIND \
    DRM_IOW(DRM_COMMAND_BASE + DRM_OPENGPU_RESOURCE_BIND, \
            struct drm_opengpu_resource)
#define DRM_IOCTL_OPENGPU_RESOURCE_UNBIND \
    DRM_IOW(DRM_COMMAND_BASE + DRM_OPENGPU_RESOURCE_UNBIND, \
            struct drm_opengpu_resource)
#define DRM_IOCTL_OPENGPU_GET_PARAM \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_GET_PARAM, \
             struct drm_opengpu_param)
#define DRM_IOCTL_OPENGPU_BLIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_BLIT, \
             struct drm_opengpu_blit)
#define DRM_IOCTL_OPENGPU_FILL \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_FILL, \
             struct drm_opengpu_fill)
#define DRM_IOCTL_OPENGPU_STRIDED_BLIT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_STRIDED_BLIT, \
             struct drm_opengpu_strided_blit)
#define DRM_IOCTL_OPENGPU_COMPUTE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE, \
             struct drm_opengpu_compute)
#define DRM_IOCTL_OPENGPU_GET_FAULT \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_GET_FAULT, \
             struct drm_opengpu_fault)
#define DRM_IOCTL_OPENGPU_RESOLVE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_RESOLVE, \
             struct drm_opengpu_resolve)
#define DRM_IOCTL_OPENGPU_INVALIDATE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_INVALIDATE, \
             struct drm_opengpu_invalidate)
#define DRM_IOCTL_OPENGPU_CREATE_BUFFER \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_CREATE_BUFFER, \
             struct drm_opengpu_create_buffer)

#define DRM_IOCTL_OPENGPU_COMPUTE_TEXTURES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_TEXTURES, \
             struct drm_opengpu_compute_textures)

#define DRM_IOCTL_OPENGPU_COMPUTE_STORAGE \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_STORAGE, \
             struct drm_opengpu_compute_storage)

#define DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_RESOURCES, \
             struct drm_opengpu_compute_resources)
#define DRM_IOCTL_OPENGPU_COMPUTE_IMAGES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_IMAGES, \
             struct drm_opengpu_compute_images)
#define DRM_IOCTL_OPENGPU_COMPUTE_TEXTURED_IMAGES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_TEXTURED_IMAGES, \
             struct drm_opengpu_compute_textured_images)

#define DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_IMAGE_RESOURCES, \
             struct drm_opengpu_compute_image_resources)

#define DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_RESOURCES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_COUNTER_RESOURCES, \
             struct drm_opengpu_compute_counter_resources)
#define DRM_IOCTL_OPENGPU_COMPUTE_UNIFORM_RESOURCES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_UNIFORM_RESOURCES, \
             struct drm_opengpu_compute_uniform_resources)
#define DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES, \
             struct drm_opengpu_compute_counter_source_resources)
#define DRM_IOCTL_OPENGPU_COMPUTE_INDIRECT_RESOURCES \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_COMPUTE_INDIRECT_RESOURCES, \
             struct drm_opengpu_compute_indirect_resources)
#define DRM_IOCTL_OPENGPU_SUBMIT_NATIVE_BINNED \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_SUBMIT_NATIVE_BINNED, \
             struct drm_opengpu_submit_native_binned)
#define DRM_IOCTL_OPENGPU_QUERY \
    DRM_IOWR(DRM_COMMAND_BASE + DRM_OPENGPU_QUERY, \
             struct drm_opengpu_query)

/* Reserved query ABI. Linux returns EOPNOTSUPP until native query command,
 * result and allocation ownership are integrated with rendering. */
struct drm_opengpu_query {
    /* Query class: 0=occlusion (samples passing), 1=primitive (primitives emitted). */
    __u32 type;
    /* Backing buffer handle created with CREATE_BUFFER. */
    __u32 handle;
    /* Byte offset within the backing buffer where the 64-bit result is stored. */
    __u32 result_offset;
    /* Query index within the slot (0..3 for four concurrent query slots). */
    __u32 index;
    /* Output: 64-bit query result value. */
    __u64 result;
    /* Output: non-zero when the result is available and stable. */
    __u32 available;
    __u32 pad;
};

#endif /* OPENGPU_DRM_H */
