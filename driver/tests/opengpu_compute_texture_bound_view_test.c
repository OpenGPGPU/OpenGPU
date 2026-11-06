/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <string.h>
#include "../opengpu_compute_texture_bound_view.h"
int main(void)
{
    struct opengpu_compute_texture_image_config image = {
        .format=3, .width=4, .height=4, .depth=1, .layers=1, .max_level=2,
    };
    struct opengpu_compute_texture_sampler_config sampler = {0};
    struct opengpu_compute_texture_level_config in[2] = {
        { .level=2, .base=512, .width=1, .height=1, .depth=1, .layers=1, .row_pitch=4, .block_bytes=4 },
        { .level=0, .base=0, .width=4, .height=4, .depth=1, .layers=1, .row_pitch=16, .block_bytes=4 },
    }, out_levels[2], before_levels[2];
    struct opengpu_compute_texture_table_entry out, before;
    unsigned char table[128 + 2304];
    assert(opengpu_compute_texture_bound_view(&out,out_levels,2,&image,&sampler,in,2,0x10000,1024));
    assert(out.present && out.image.allocation_base==0x10000 && out.image.allocation_limit==0x10400);
    assert(out.levels==out_levels && out_levels[0].base==0x10200 && out_levels[1].base==0x10000);
    assert(opengpu_build_compute_texture_tables(table,sizeof(table),0x8000,7,11,13,&out,1));
    memcpy(&before,&out,sizeof(out)); memcpy(before_levels,out_levels,sizeof(out_levels));
    in[0].base=1024;
    assert(!opengpu_compute_texture_bound_view(&out,out_levels,2,&image,&sampler,in,2,0x10000,1024));
    assert(!memcmp(&out,&before,sizeof(out)) && !memcmp(out_levels,before_levels,sizeof(out_levels)));
    in[0].base=1023;
    assert(!opengpu_compute_texture_bound_view(&out,out_levels,2,&image,&sampler,in,2,0x10000,1024));
    in[0].base=512; in[0].level=0;
    assert(!opengpu_compute_texture_bound_view(&out,out_levels,2,&image,&sampler,in,2,0x10000,1024));
    in[0].level=2;
    assert(!opengpu_compute_texture_bound_view(&out,out_levels,1,&image,&sampler,in,2,0x10000,1024));
    assert(!opengpu_compute_texture_bound_view(&out,out_levels,2,&image,&sampler,in,2,0xfffffc00u,1025));
    assert(opengpu_compute_texture_bound_view(&out,out_levels,2,&image,&sampler,in,2,0xfffffc00u,1024));
    assert(out.image.allocation_limit==(1ULL<<32) && out_levels[0].base==0xfffffe00u);
    for (u32 target=0; target<4; ++target) {
        struct opengpu_compute_texture_image_config im=image;
        struct opengpu_compute_texture_level_config level=in[1];
        im.target=level.target=target;
        im.layers=level.layers=target==1 ? 3 : 1;
        im.depth=level.depth=target==3 ? 4 : 1;
        level.layer_pitch=target==1 || target==2 ? 64 : 0;
        level.slice_pitch=target==3 ? 64 : 0;
        assert(opengpu_compute_texture_bound_view(&out,out_levels,2,&im,&sampler,&level,1,0x10000,1024));
        assert(opengpu_build_compute_texture_tables(table,sizeof(table),0x8000,7,11,13,&out,1));
    }
    assert(opengpu_compute_texture_bound_view(&out,NULL,0,&image,&sampler,NULL,0,0x10000,1024));
    assert(out.present && out.level_count==0);
    image.target=OPENGPU_TEXTURE_BUFFER_TARGET; image.max_level=0;
    image.width=image.height=1; image.buffer_texels=65536; image.buffer_bytes=262144;
    in[1].target=OPENGPU_TEXTURE_BUFFER_TARGET; in[1].width=in[1].height=1;
    in[1].row_pitch=0; in[1].buffer_texels=65536; in[1].buffer_bytes=262144;
    assert(opengpu_compute_texture_bound_view(&out,out_levels,2,&image,NULL,&in[1],1,0x10000,262144));
    sampler.min_filter=0xffffffffu;
    assert(opengpu_compute_texture_bound_view(&out,out_levels,2,&image,&sampler,&in[1],1,0x10000,262144));
    assert(!out.sampler.min_filter);
    return 0;
}
