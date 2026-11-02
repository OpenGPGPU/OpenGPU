#include <stdio.h>
#include "opengpu_compute_texture_records.h"
int main(void) {
    struct opengpu_compute_texture_level_config l = {0};
    unsigned block; u32 out[32];
    while (scanf("%u %u %u %u %u %u %u %llu %llu %llu %llu %u %u",
          &l.target,&l.width,&l.height,&l.depth,&l.layers,&l.allocation_base,&l.base,
          &l.allocation_limit,&l.row_pitch,&l.slice_pitch,&l.layer_pitch,&block,&l.block_bytes) == 13) {
        l.block_4x4 = block != 0;
        printf("%d\n", opengpu_encode_compute_texture_level(out, &l));
    }
    return 0;
}
