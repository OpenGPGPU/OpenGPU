/* SPDX-License-Identifier: MIT */
#include "opengpu_pixel_format.h"
#include "gpu_abi.h"
#include <assert.h>
#include <stddef.h>
#include <stdio.h>

_Static_assert(OPENGPU_FORMAT_NATIVE == GPU_FORMAT_NATIVE, "native ABI");
_Static_assert(OPENGPU_FORMAT_RGBA8 == GPU_FORMAT_RGBA8, "RGBA ABI");
_Static_assert(OPENGPU_FORMAT_BGRA8 == GPU_FORMAT_BGRA8, "BGRA ABI");
_Static_assert(OPENGPU_FORMAT_RGBX8 == GPU_FORMAT_RGBX8, "RGBX ABI");
_Static_assert(OPENGPU_FORMAT_BGRX8 == GPU_FORMAT_BGRX8, "BGRX ABI");
_Static_assert(sizeof(struct gpu_job_record) == 64, "descriptor ABI size");
_Static_assert(offsetof(struct gpu_job_record, pixel_formats) == 52, "format word");

int main(void)
{
    const uint32_t rgba = GPU_PIXEL_RGBA(17, 83, 149, 211);
    const unsigned char bytes[][4] = {
        {211, 149, 83, 17}, {17, 83, 149, 211}, {149, 83, 17, 211},
        {17, 83, 149, 255}, {149, 83, 17, 255},
    };
    for (unsigned format = 0; format <= OPENGPU_FORMAT_MAX; format++) {
        uint32_t pattern = opengpu_pixel_encode(rgba, format);
        for (unsigned byte = 0; byte < 4; byte++)
            assert(((pattern >> (byte * 8)) & 255u) == bytes[format][byte]);
    }
    puts("native/Mesa clear byte orders and descriptor ABI: PASS");
    return 0;
}
