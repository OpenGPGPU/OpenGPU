#include <assert.h>
#include <stddef.h>
#include "../opengpu_compute_texture_mips.h"

int main(void)
{
    struct opengpu_compute_texture_image_config image = {
        .context = 7, .pass = 11, .generation = 13, .format = 3, .max_level = 2,
        .width = 4, .height = 4, .depth = 1, .layers = 1,
        .allocation_base = 0x10000, .allocation_limit = 0x11000,
        .levels_base = 0x8000, .levels_limit = 0x8180,
    };
    struct opengpu_compute_texture_level_config levels[3] = {0};
    for (u32 i = 0; i < 3; ++i) {
        levels[i].context = 7; levels[i].pass = 11; levels[i].generation = 13;
        levels[i].handle = i; levels[i].level = i; levels[i].width = 4u >> i;
        levels[i].height = 4u >> i; levels[i].depth = 1; levels[i].layers = 1;
        levels[i].allocation_base = 0x10000; levels[i].allocation_limit = 0x11000;
        levels[i].base = 0x10000 + i * 0x200; levels[i].row_pitch = levels[i].width * 4;
        levels[i].block_bytes = 4;
    }
    assert(opengpu_validate_compute_texture_levels(&image, levels, 3, 1, true));
    assert(opengpu_validate_compute_texture_levels(&image, &levels[1], 1, 1, false));
    assert(!opengpu_validate_compute_texture_levels(&image, levels, 2, 1, true));
    assert(!opengpu_validate_compute_texture_levels(&image, levels, 3, 3, true));
    assert(!opengpu_validate_compute_texture_levels(NULL, levels, 3, 1, true));
    assert(!opengpu_validate_compute_texture_levels(&image, NULL, 3, 1, true));
    for (unsigned i = 0; i < 10; ++i) {
        struct opengpu_compute_texture_level_config copy[3] = {levels[0], levels[1], levels[2]};
        if (i == 0) copy[1].context++;
        if (i == 1) copy[1].handle++;
        if (i == 2) copy[1].level++;
        if (i == 3) copy[1].width++;
        if (i == 4) copy[1].height++;
        if (i == 5) copy[1].block_bytes = 8;
        if (i == 6) copy[1].allocation_limit--;
        if (i == 7) copy[1].base = levels[0].base; /* overlap */
        if (i == 8) copy[1].row_pitch = 1;
        if (i == 9) copy[1].target = 1;
        assert(!opengpu_validate_compute_texture_levels(&image, copy, 3, 1, true));
    }
    image.levels_limit = 0x8100;
    assert(!opengpu_validate_compute_texture_levels(&image, levels, 3, 1, true));
    image.levels_limit = 0x8180;
    for (u32 target = 1; target <= 3; ++target) {
        image.target = target; image.layers = target == 1 ? 3 : 1;
        image.depth = target == 3 ? 4 : 1;
        for (u32 i = 0; i < 3; ++i) {
            levels[i].target = target; levels[i].layers = image.layers;
            levels[i].depth = target == 3 ? 4u >> i : 1;
            levels[i].slice_pitch = target == 3 ? levels[i].row_pitch * levels[i].height : 0;
            levels[i].layer_pitch = target == 1 || target == 2 ? levels[i].row_pitch * levels[i].height : 0;
        }
        assert(opengpu_validate_compute_texture_levels(&image, levels, 3, 1, true));
    }
    image.target = 0; image.width = 8; image.height = 4; image.depth = 1;
    image.layers = 1; image.base_level = 1; image.max_level = 3; image.levels_limit = 0x8200;
    for (u32 i = 0; i < 3; ++i) {
        u32 level = i + 1;
        levels[i].target = 0; levels[i].handle = level; levels[i].level = level;
        levels[i].width = 8u >> level; levels[i].height = 4u >> level;
        if (!levels[i].height) levels[i].height = 1;
        levels[i].depth = 1; levels[i].layers = 1; levels[i].row_pitch = levels[i].width * 4;
        levels[i].slice_pitch = 0; levels[i].layer_pitch = 0;
    }
    assert(opengpu_validate_compute_texture_levels(&image, levels, 3, 2, true));
    assert(opengpu_validate_compute_texture_levels(&image, &levels[1], 1, 2, false));
    image.levels_limit = 0x8180;
    assert(!opengpu_validate_compute_texture_levels(&image, levels, 3, 2, true));
    image.levels_limit = 0x8200; image.base_level = 0;
    {
        static const unsigned char bytes[64] = {1, 2, 3, 4, 2, 2, 2, 4, 4, 1, 1, 2, 2, 4, 4, 2, 2, 4, 4, 8, 8, 4, 4, 8, 8, 16, 16, 4, 2, 4, 4, 8, 6, 12, 8, 16, 1, 2, 3, 4, 3, 3, 6, 6, 12, 12, 3, 4, 4, 2, 4, 4, 4, 8, 8, 8, 8, 8, 16, 16, 8, 8, 16, 16};
        static const unsigned char blocks[64] = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
        image.target = 0; image.width = 1; image.height = 1; image.depth = 1;
        image.layers = 1; image.max_level = 0;
        levels[0].target = 0; levels[0].handle = 0; levels[0].level = 0;
        levels[0].width = 1; levels[0].height = 1;
        levels[0].depth = 1; levels[0].layers = 1; levels[0].slice_pitch = 0; levels[0].layer_pitch = 0;
        for (u32 format = 0; format < 64; ++format) {
            image.format = format; levels[0].block_bytes = bytes[format];
            levels[0].block_4x4 = blocks[format] != 0; levels[0].row_pitch = bytes[format];
            assert(opengpu_validate_compute_texture_levels(&image, levels, 1, 0, true));
            levels[0].block_bytes = bytes[format] == 4 ? 8 : 4;
            levels[0].row_pitch = levels[0].block_bytes;
            assert(!opengpu_validate_compute_texture_levels(&image, levels, 1, 0, true));
        }
    }
    return 0;
}
