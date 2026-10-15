/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_SHADER_RESOURCES_H
#define OPENGPU_SHADER_RESOURCES_H

#include "gpu_abi.h"

static inline u32 opengpu_shader_resource_lanes(u32 config)
{
    return (config >> GPU_SHADER_CONFIG_LANES_SHIFT) & GPU_SHADER_CONFIG_FIELD_MASK;
}

static inline u32 opengpu_shader_resource_warps(u32 config)
{
    return (config >> GPU_SHADER_CONFIG_WARPS_SHIFT) & GPU_SHADER_CONFIG_FIELD_MASK;
}

static inline u32 opengpu_shader_resource_banks(u32 config)
{
    return config ? (config >> GPU_SHADER_CONFIG_BANKS_SHIFT) &
                    GPU_SHADER_CONFIG_FIELD_MASK : GPU_KERNARG_BANKS;
}

static inline u32 opengpu_shader_resource_cus(u32 config)
{
    return (config >> GPU_SHADER_CONFIG_CUS_SHIFT) & GPU_SHADER_CONFIG_FIELD_MASK;
}

/* Staging layouts follow the advertised batch, independently of bank count.
 * A zero descriptor is the historical four-lane/two-warp graphics ABI. */
static inline u32 opengpu_shader_resource_batch(u32 config)
{
    return config ? opengpu_shader_resource_lanes(config) *
                    opengpu_shader_resource_warps(config) : 8u;
}

static inline u32 opengpu_shader_array_stride(u32 config)
{
    return 4u * opengpu_shader_resource_batch(config);
}

static inline u32 opengpu_shader_fragment_uniform_offset(u32 config)
{
    return 9u * opengpu_shader_array_stride(config);
}

static inline u32 opengpu_shader_fragment_bank_stride(u32 config)
{
    /* Retain the 32-byte uniform contract and align independent banks. */
    return (opengpu_shader_fragment_uniform_offset(config) + 32u + 63u) & ~63u;
}

static inline u32 opengpu_shader_vertex_uniform_offset(u32 config)
{
    return 16u * opengpu_shader_array_stride(config);
}

static inline u32 opengpu_shader_vertex_kernarg_bytes(u32 config)
{
    return opengpu_shader_vertex_uniform_offset(config) + 256u;
}

static inline int opengpu_shader_resources_valid(u32 config, u32 batch)
{
    u32 lanes = opengpu_shader_resource_lanes(config);
    u32 warps = opengpu_shader_resource_warps(config);
    u32 banks = opengpu_shader_resource_banks(config);

    return config && lanes && warps && opengpu_shader_resource_cus(config) &&
           banks >= 2 && !(banks & 1) && lanes * warps == batch;
}

#endif
