// SPDX-License-Identifier: MIT
#include <assert.h>
#include <string.h>
#include "../opengpu_compute_image_descriptor.h"
#include "../opengpu_shader_image.h"

int main(void)
{
    struct opengpu_compute_image_descriptor d = {
        .context=17, .unit=2, .readable=true, .writable=true,
        .storage_format=OPENGPU_TEXTURE_FORMAT_R32UI,
        .image_format=OPENGPU_TEXTURE_FORMAT_R32UI,
        .view={.target=0, .width=4, .height=3, .depth=1, .layers=1,
               .row_pitch=16, .block_bytes=4, .base=64},
    };
    u32 words[64], saved[64];
    struct opengpu_shader_image wire;
    assert(opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    for (unsigned i=0;i<64;i++) opengpu_image_write_word(&wire,i,words[i]);
    assert(opengpu_shader_image_encoding_valid(&wire));
    assert(words[3]==17 && words[6]==2 && words[9]==4 && words[10]==1 && words[11]==1);
    assert(words[22]==0x10000 && words[23]==0x11000 && words[24]==0 && words[25]==0x10040);
    assert(words[26]==4 && words[27]==3 && words[30]==16 && words[37]==4);
    memcpy(saved,words,sizeof(words));
    d.image_format=OPENGPU_TEXTURE_FORMAT_RGBA8UI;
    assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    assert(!memcmp(words,saved,sizeof(words)));
    d.compatible_by_size=true;
    assert(opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.image_format=OPENGPU_TEXTURE_FORMAT_RGBA16F;
    assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.image_format=OPENGPU_TEXTURE_FORMAT_R32UI;
    d.view.base=4095; assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.view.base=64; d.view.row_pitch=15; assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.view.row_pitch=16; d.view.block_4x4=true; assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.view.block_4x4=false; d.unit=4; assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.unit=2; assert(!opengpu_build_compute_image_descriptor(words,&d,0xfffff000u,8192));
    d.view.base=0; assert(opengpu_build_compute_image_descriptor(words,&d,0xfffff000u,4096));
    assert(words[23]==0 && words[24]==1);
    d.view.target=1; d.view.layers=3; d.view.layer_pitch=48; d.layer=2;
    assert(opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.layer=3; assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.layered=true; assert(opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.view.layer_pitch=47; assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    d.view.layer_pitch=48; d.layered=false; d.layer=0; d.readable=false; d.writable=false;
    assert(!opengpu_build_compute_image_descriptor(words,&d,0x10000,4096));
    {
        static const u32 formats[] = {
            OPENGPU_TEXTURE_FORMAT_RGBA32F, OPENGPU_TEXTURE_FORMAT_RGBA16F,
            OPENGPU_TEXTURE_FORMAT_R32F, OPENGPU_TEXTURE_FORMAT_RGBA32UI,
            OPENGPU_TEXTURE_FORMAT_RGBA16UI, OPENGPU_TEXTURE_FORMAT_RGBA8UI,
            OPENGPU_TEXTURE_FORMAT_R32UI, OPENGPU_TEXTURE_FORMAT_RGBA32I,
            OPENGPU_TEXTURE_FORMAT_RGBA16I, OPENGPU_TEXTURE_FORMAT_RGBA8I,
            OPENGPU_TEXTURE_FORMAT_R32I, OPENGPU_TEXTURE_FORMAT_RGBA8,
            OPENGPU_TEXTURE_FORMAT_RGBA8_SNORM,
        };
        d.readable=true; d.view.target=0; d.view.layers=1; d.view.layer_pitch=0;
        d.view.width=1; d.view.height=1;
        for (unsigned i=0; i<sizeof(formats)/sizeof(formats[0]); ++i) {
            d.storage_format=formats[i];
            d.view.block_bytes=opengpu_compute_image_format_bytes(formats[i]);
            d.view.row_pitch=d.view.block_bytes;
            for (unsigned j=0; j<sizeof(formats)/sizeof(formats[0]); ++j) {
                bool same_size = d.view.block_bytes == opengpu_compute_image_format_bytes(formats[j]);
                bool same_class = d.view.block_bytes != 4 ||
                    opengpu_compute_image_r32(formats[i]) == opengpu_compute_image_r32(formats[j]);
                d.image_format=formats[j]; d.compatible_by_size=false;
                assert(opengpu_build_compute_image_descriptor(words,&d,0x10000,4096) == (same_size && same_class));
                d.compatible_by_size=true;
                assert(opengpu_build_compute_image_descriptor(words,&d,0x10000,4096) == same_size);
            }
        }
    }
    return 0;
}
