/* SPDX-License-Identifier: MIT */
#include <stddef.h>
#include "gpu_abi.h"

_Static_assert(sizeof(struct gpu_tile_job_record) == GPU_TILE_JOB_BYTES,
               "tile descriptor must occupy exactly four cache lines");
#define WORD(field, index) \
    _Static_assert(offsetof(struct gpu_tile_job_record, field) == 4u * (index), #field)
WORD(magic, 0); WORD(words, 1); WORD(context, 2); WORD(pass_id, 3); WORD(generation, 4);
WORD(cmd_base, 5); WORD(cmd_count, 6); WORD(cmd_limit_lo, 7); WORD(cmd_limit_hi, 8);
WORD(render_width, 9); WORD(render_height, 10); WORD(origin_xy, 11); WORD(tile_info, 12);
WORD(color_base, 13); WORD(color_limit_lo, 14); WORD(color_limit_hi, 15); WORD(color_pitch, 16);
WORD(color_ops, 17); WORD(color_clear, 18);
WORD(depth_base, 19); WORD(depth_limit_lo, 20); WORD(depth_limit_hi, 21); WORD(depth_pitch, 22);
WORD(depth_ops, 23); WORD(depth_clear, 24);
WORD(resolve_base, 25); WORD(resolve_limit_lo, 26); WORD(resolve_limit_hi, 27); WORD(resolve_pitch, 28);
WORD(draw_state, 29); WORD(stencil_ops, 30); WORD(stencil_masks, 31); WORD(blend_config, 32);
WORD(tex_base, 33); WORD(tex_size, 34); WORD(sampler_config, 35); WORD(tex_format, 36);
WORD(reserved, 37);
_Static_assert(GPU_TILE_JOB_WORDS * 4u == GPU_TILE_JOB_BYTES, "descriptor word count");
int main(void) { return 0; }
