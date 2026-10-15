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
    puts("shader resource discovery and legacy fallback tests passed");
    return 0;
}
