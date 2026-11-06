/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdlib.h>
#include <string.h>
#include "../opengpu_compute_texture_tables.h"
static u32 get(const unsigned char *p, unsigned index)
{ p += 4 * index; return (u32)p[0] | (u32)p[1]<<8 | (u32)p[2]<<16 | (u32)p[3]<<24; }
int main(void)
{
    const u32 count = 32, base = 0x10000;
    const unsigned long long bytes = opengpu_compute_texture_table_bytes(count);
    const unsigned image_off = 128 + 64 * count;
    const unsigned sampler_off = image_off + 128 * count;
    const unsigned level_off = sampler_off + 64 * count;
    struct opengpu_compute_texture_table_entry entries[32] = {0};
    struct opengpu_compute_texture_level_config levels[2] = {
        {.level=2, .width=1, .height=1, .depth=1, .layers=1, .allocation_base=0x80000,
         .allocation_limit=0x81000, .base=0x80400, .row_pitch=4, .block_bytes=4},
        {.level=0, .width=4, .height=4, .depth=1, .layers=1, .allocation_base=0x80000,
         .allocation_limit=0x81000, .base=0x80000, .row_pitch=16, .block_bytes=4},
    };
    unsigned char *dst = malloc((size_t)bytes), *saved = malloc((size_t)bytes);
    assert(dst && saved && bytes == 128 + 2304 * count);
    entries[0].present = true;
    entries[0].image = (struct opengpu_compute_texture_image_config) {
        .format=3, .width=4, .height=4, .depth=1, .layers=1, .max_level=2,
        .allocation_base=0x80000, .allocation_limit=0x81000,
    };
    entries[0].levels=levels; entries[0].level_count=2;
    entries[31]=entries[0];
    memset(dst, 0xaa, (size_t)bytes);
    assert(opengpu_build_compute_texture_tables(dst, bytes, base, 7, 11, 13, entries, count));
    assert(get(dst,0)==0x47585201u && get(dst,3)==7 && get(dst,4)==11 && get(dst,5)==13);
    assert(get(dst,7)==32 && get(dst,8)==base+128 && get(dst,11)==base+image_off);
    assert(get(dst+128,0)==0x47584201u && get(dst+128,7)==0 && get(dst+128,8)==0);
    assert(get(dst+128+64,0)==0 && get(dst+128+31*64,7)==31);
    assert(get(dst+image_off+31*128,0)==0x47584901u);
    assert(get(dst+image_off+31*128,19)==base+level_off+31*2048);
    assert(get(dst+sampler_off+31*64,0)==0x47585301u && get(dst+sampler_off+31*64,6)==31);
    assert(get(dst+level_off,0)==0x47584c01u && get(dst+level_off,8)==0);
    assert(get(dst+level_off+128,0)==0 && get(dst+level_off+256,8)==2);
    memcpy(saved,dst,(size_t)bytes);
    levels[0].level=0;
    assert(!opengpu_build_compute_texture_tables(dst, bytes, base, 7,11,13,entries,count));
    assert(!memcmp(dst,saved,(size_t)bytes));
    levels[0].level=2; levels[0].width=2;
    assert(!opengpu_build_compute_texture_tables(dst, bytes, base, 7,11,13,entries,count));
    assert(!memcmp(dst,saved,(size_t)bytes)); levels[0].width=1;
    entries[31].sampler.min_filter=6;
    assert(!opengpu_build_compute_texture_tables(dst, bytes, base, 7,11,13,entries,count));
    assert(!memcmp(dst,saved,(size_t)bytes)); entries[31].sampler.min_filter=0;
    assert(!opengpu_build_compute_texture_tables(dst, bytes-1, base, 7,11,13,entries,count));
    assert(!opengpu_build_compute_texture_tables(dst, bytes, base+1, 7,11,13,entries,count));
    assert(!opengpu_build_compute_texture_tables(dst, bytes, 0xfffff000u, 7,11,13,entries,count));
    assert(!opengpu_build_compute_texture_tables(dst, bytes, base, 7,11,13,entries,0));
    assert(!memcmp(dst,saved,(size_t)bytes));
    {
        struct opengpu_compute_texture_table_entry entry=entries[0];
        struct opengpu_compute_texture_level_config level=levels[1];
        entry.levels=&level; entry.level_count=1;
        for (u32 target=0; target<4; ++target) {
            entry.image.target=target; level.target=target;
            entry.image.layers=level.layers=target==1 ? 3 : 1;
            entry.image.depth=level.depth=target==3 ? 4 : 1;
            level.slice_pitch=target==3 ? 64 : 0;
            level.layer_pitch=(target==1 || target==2) ? 64 : 0;
            assert(opengpu_build_compute_texture_tables(dst,bytes,base,7,11,13,&entry,1));
            assert(get(dst+128,11)==target);
        }
    }
    {
        static const unsigned char bytes[64] = {1, 2, 3, 4, 2, 2, 2, 4, 4, 1, 1, 2, 2, 4, 4, 2, 2, 4, 4, 8, 8, 4, 4, 8, 8, 16, 16, 4, 2, 4, 4, 8, 6, 12, 8, 16, 1, 2, 3, 4, 3, 3, 6, 6, 12, 12, 3, 4, 4, 2, 4, 4, 4, 8, 8, 8, 8, 8, 16, 16, 8, 8, 16, 16};
        static const unsigned char blocks[64] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        struct opengpu_compute_texture_table_entry entry=entries[0];
        struct opengpu_compute_texture_level_config level=levels[1];
        entry.levels=&level; entry.level_count=1; entry.image.width=entry.image.height=1;
        entry.image.max_level=0; level.width=level.height=1;
        for (u32 format=0; format<64; ++format) {
            entry.image.format=format; level.block_bytes=bytes[format];
            level.block_4x4=blocks[format] != 0; level.row_pitch=bytes[format];
            assert(opengpu_build_compute_texture_tables(dst,opengpu_compute_texture_table_bytes(1),base,7,11,13,&entry,1));
            assert(get(dst+128+64,8)==format);
        }
    }
    {
        struct opengpu_compute_texture_level_config buffer = {
            .target=5,.width=1,.height=1,.depth=1,.layers=1,.block_bytes=4,
            .allocation_base=0x80000,.allocation_limit=0xc0000,.base=0x80000,
            .buffer_texels=65536,.buffer_bytes=262144,
        };
        entries[31].image=(struct opengpu_compute_texture_image_config) {
            .target=5,.format=OPENGPU_TEXTURE_FORMAT_RGBA8,.width=1,.height=1,.depth=1,.layers=1,
            .allocation_base=0x80000,.allocation_limit=0xc0000,.buffer_texels=65536,.buffer_bytes=262144,
        };
        entries[31].levels=&buffer; entries[31].level_count=1;
        entries[31].sampler.min_filter=0xffffffffu;
        assert(opengpu_build_compute_texture_tables(dst,bytes,base,7,11,13,entries,count));
        assert(get(dst,14)==base+sampler_off && get(dst+sampler_off,0)==0x47585301u);
        for (u32 i=0;i<64;++i) assert(!dst[sampler_off+31*64+i]);
        entries[0].present=false;
        assert(opengpu_build_compute_texture_tables(dst,bytes,base,7,11,13,entries,count));
        assert(!get(dst,14) && !get(dst,16) && !get(dst,17));
        memcpy(saved,dst,(size_t)bytes); entries[0].present=true;
        entries[0].sampler.min_filter=6;
        assert(!opengpu_build_compute_texture_tables(dst,bytes,base,7,11,13,entries,count));
        assert(!memcmp(saved,dst,(size_t)bytes)); entries[0].sampler.min_filter=0;
    }
    assert(opengpu_build_compute_texture_tables(dst, bytes, (u32)((1ULL<<32)-opengpu_compute_texture_table_bytes(1)),7,11,13,entries,1));
    free(saved); free(dst); return 0;
}
