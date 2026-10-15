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

static inline int opengpu_shader_resources_valid(u32 config, u32 batch)
{
    u32 lanes = opengpu_shader_resource_lanes(config);
    u32 warps = opengpu_shader_resource_warps(config);
    u32 banks = opengpu_shader_resource_banks(config);

    return config && lanes && warps && opengpu_shader_resource_cus(config) &&
           banks >= 2 && !(banks & 1) && lanes * warps == batch;
}

#endif
