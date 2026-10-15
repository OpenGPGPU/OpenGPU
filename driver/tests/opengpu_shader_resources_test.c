/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdio.h>
#include "opengpu_shader_resources.h"

int main(void)
{
    u32 legacy = 4u | (2u << 8) | (2u << 16) | (1u << 24);
    u32 parallel = 4u | (2u << 8) | (8u << 16) | (4u << 24);
    u32 wide = 16u | (8u << 8) | (8u << 16) | (4u << 24);

    assert(opengpu_shader_resource_banks(0) == 2);
    assert(!opengpu_shader_resources_valid(0, 8));
    assert(opengpu_shader_resources_valid(legacy, 8));
    assert(opengpu_shader_resources_valid(parallel, 8));
    assert(opengpu_shader_resources_valid(wide, 128));
    assert(opengpu_shader_resource_lanes(wide) == 16);
    assert(opengpu_shader_resource_warps(wide) == 8);
    assert(opengpu_shader_resource_banks(wide) == 8);
    assert(opengpu_shader_resource_cus(wide) == 4);
    assert(!opengpu_shader_resources_valid(wide, 8));
    assert(!opengpu_shader_resources_valid(wide & ~0xffu, 128));
    assert(!opengpu_shader_resources_valid(wide & ~(0xffu << 8), 128));
    assert(!opengpu_shader_resources_valid(wide & ~(0xffu << 16), 128));
    assert(!opengpu_shader_resources_valid(wide & ~(0xffu << 24), 128));
    assert(!opengpu_shader_resources_valid((wide & ~(0xffu << 16)) | (3u << 16), 128));
    assert(!opengpu_shader_resources_valid((wide & ~(0xffu << 16)) | (1u << 16), 128));
    assert(opengpu_shader_resources_valid((wide & ~(0xffu << 24)) | (255u << 24), 128));
    assert(opengpu_shader_resource_batch(0) == 8);
    assert(opengpu_shader_array_stride(0) == 32);
    assert(opengpu_shader_fragment_uniform_offset(0) == 288);
    assert(opengpu_shader_fragment_bank_stride(0) == 320);
    assert(opengpu_shader_vertex_uniform_offset(0) == 512);
    assert(opengpu_shader_vertex_kernarg_bytes(0) == 768);
    assert(opengpu_shader_array_stride(wide) == 512);
    assert(opengpu_shader_fragment_uniform_offset(wide) == 4608);
    assert(opengpu_shader_fragment_bank_stride(wide) == 4672);
    assert(opengpu_shader_vertex_uniform_offset(wide) == 8192);
    assert(opengpu_shader_vertex_kernarg_bytes(wide) == 8448);
    for (u32 warps = 1; warps <= 8; warps *= 2) {
        u32 cfg = 16u | (warps << 8) | (8u << 16) | (4u << 24);
        u32 uniform = opengpu_shader_fragment_uniform_offset(cfg);
        u32 bank_stride = opengpu_shader_fragment_bank_stride(cfg);
        assert(uniform == 9u * 4u * 16u * warps);
        assert(!(bank_stride & 63u));
        assert(bank_stride >= uniform + 32u);
        assert(bank_stride < uniform + 96u);
        for (u32 bank = 0; bank < 8; bank++)
            assert(bank * bank_stride + uniform + 32u <= (bank + 1) * bank_stride);
    }
    puts("shader resource discovery, wide staging layouts and legacy fallback tests passed");
    return 0;
}
