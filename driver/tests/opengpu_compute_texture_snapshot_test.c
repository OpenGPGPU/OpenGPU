#include <assert.h>
#include <stddef.h>
#include "../opengpu_compute_texture_snapshot.h"

int main(void)
{
    struct opengpu_compute_texture_root_config root = {
        .context = 7, .pass = 11, .generation = 13, .count = 4,
        .bindings_base = 0x1000, .bindings_limit = 0x1100,
        .images_base = 0x2000, .images_limit = 0x2200,
        .samplers_base = 0x3000, .samplers_limit = 0x3200,
    };
    struct opengpu_compute_texture_binding_config binding = {
        .context = 7, .pass = 11, .generation = 13, .unit = 3,
        .image_handle = 3, .sampler_handle = 4,
    };
    struct opengpu_compute_texture_image_config image = {
        .context = 7, .pass = 11, .generation = 13, .handle = 3,
        .format = 3, .max_level = 2, .width = 4, .height = 4, .depth = 1, .layers = 1,
        .allocation_base = 0x10000, .allocation_limit = 0x11000,
        .levels_base = 0x4000, .levels_limit = 0x4180,
    };
    struct opengpu_compute_texture_sampler_config sampler = {
        .context = 7, .pass = 11, .generation = 13, .handle = 4,
    };
    struct opengpu_compute_texture_packet_config packet = {
        .context = 7, .pass = 11, .generation = 13, .level = 1,
    };
    struct opengpu_compute_texture_level_config levels[3] = {0};
    u32 addresses[3] = {0x4000, 0x4080, 0x4100};
    struct opengpu_compute_texture_snapshot s = {
        .root = &root, .binding = &binding, .image = &image, .sampler = &sampler,
        .packet = &packet, .levels = levels, .level_addresses = addresses,
        .root_address = 0x800, .binding_address = 0x10c0, .image_address = 0x2180,
        .sampler_address = 0x3100, .packet_address = 0x8000,
        .level_count = 2, .selected_level = 1, .operation = OPENGPU_COMPUTE_TEXTURE_PACKET_FETCH,
    };
    for (u32 i = 0; i < 3; ++i) {
        levels[i].context = 7; levels[i].pass = 11; levels[i].generation = 13;
        levels[i].handle = i; levels[i].level = i; levels[i].width = 4u >> i;
        levels[i].height = 4u >> i; levels[i].depth = 1; levels[i].layers = 1;
        levels[i].allocation_base = 0x10000; levels[i].allocation_limit = 0x11000;
        levels[i].base = 0x10000 + i * 0x200; levels[i].row_pitch = levels[i].width * 4;
        levels[i].block_bytes = 4;
    }
    assert(opengpu_validate_compute_texture_snapshot(&s));
    s.packet_address = 0xffffffc0u;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    s.packet_address = 0x8000;
    s.operation = OPENGPU_COMPUTE_TEXTURE_CAPTURED_FETCH; s.packet = NULL;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    s.operation = OPENGPU_COMPUTE_TEXTURE_SAMPLE;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    sampler.min_filter = 2; s.level_count = 3;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    s.level_count = 2;
    assert(!opengpu_validate_compute_texture_snapshot(&s));
    sampler.min_filter = 0; s.packet = &packet; s.operation = OPENGPU_COMPUTE_TEXTURE_PACKET_FETCH;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    for (unsigned i = 0; i < 20; ++i) {
        struct opengpu_compute_texture_root_config r = root;
        struct opengpu_compute_texture_binding_config b = binding;
        struct opengpu_compute_texture_image_config im = image;
        struct opengpu_compute_texture_sampler_config sa = sampler;
        struct opengpu_compute_texture_packet_config p = packet;
        struct opengpu_compute_texture_level_config ls[3] = {levels[0], levels[1], levels[2]};
        u32 as[3] = {addresses[0], addresses[1], addresses[2]};
        struct opengpu_compute_texture_snapshot bad = s;
        bad.root = &r; bad.binding = &b; bad.image = &im; bad.sampler = &sa;
        bad.packet = &p; bad.levels = ls; bad.level_addresses = as;
        if (i == 0) bad.root_address = 0xffffffc0u;
        if (i == 1) bad.binding_address += 64;
        if (i == 2) bad.image_address += 128;
        if (i == 3) bad.sampler_address += 64;
        if (i == 4) as[1] += 128;
        if (i == 5) b.unit = 4;
        if (i == 6) b.context++;
        if (i == 7) im.generation++;
        if (i == 8) sa.pass++;
        if (i == 9) p.context++;
        if (i == 10) p.level = 0;
        if (i == 11) p.face = 8;
        if (i == 12) bad.packet_address++;
        if (i == 13) bad.level_count = 1;
        if (i == 14) ls[0].level = 1;
        if (i == 15) b.image_handle = 0xffffffffu;
        if (i == 16) sa.compare_enable = true;
        if (i == 17) bad.operation = (enum opengpu_compute_texture_operation)99;
        if (i == 18) bad.level_addresses = NULL;
        if (i == 19) bad.packet = NULL;
        assert(!opengpu_validate_compute_texture_snapshot(&bad));
    }
    {
        struct opengpu_compute_texture_root_config r = root;
        struct opengpu_compute_texture_binding_config b = binding;
        struct opengpu_compute_texture_snapshot edge = s;
        r.count = 32; r.bindings_base = 0xfffff800u; r.bindings_limit = 1ULL << 32;
        r.images_base = 0xfffffe00u; r.images_limit = 1ULL << 32;
        r.samplers_base = 0xfffffe00u; r.samplers_limit = 1ULL << 32;
        b.unit = 31; edge.root = &r; edge.binding = &b;
        edge.root_address = 0xffffff80u; edge.binding_address = 0xffffffc0u;
        edge.image_address = 0xffffff80u; edge.sampler_address = 0xffffff00u;
        edge.packet_address = 0xffffffc0u;
        assert(opengpu_validate_compute_texture_snapshot(&edge));
        edge.image_address = 0;
        assert(!opengpu_validate_compute_texture_snapshot(&edge));
    }
    {
        struct opengpu_compute_texture_image_config depth = image;
        struct opengpu_compute_texture_sampler_config cmp = sampler;
        struct opengpu_compute_texture_level_config ls[2] = {levels[0], levels[1]};
        struct opengpu_compute_texture_snapshot shadow = s;
        depth.format = 49; cmp.compare_enable = true;
        for (unsigned i = 0; i < 2; ++i) {
            ls[i].block_bytes = 2; ls[i].row_pitch = ls[i].width * 2;
        }
        shadow.image = &depth; shadow.sampler = &cmp; shadow.levels = ls;
        shadow.operation = OPENGPU_COMPUTE_TEXTURE_SAMPLE; shadow.shadow = true;
        assert(opengpu_validate_compute_texture_snapshot(&shadow));
        shadow.shadow = false;
        assert(!opengpu_validate_compute_texture_snapshot(&shadow));
        shadow.operation = OPENGPU_COMPUTE_TEXTURE_CAPTURED_FETCH;
        assert(!opengpu_validate_compute_texture_snapshot(&shadow));
    }
    root.size_only = true; root.samplers_base = 0; root.samplers_limit = 0;
    s.operation = OPENGPU_COMPUTE_TEXTURE_SIZE; s.sampler = NULL; s.packet = NULL;
    s.levels = &levels[1]; s.level_addresses = &addresses[1]; s.level_count = 1;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    s.operation = OPENGPU_COMPUTE_TEXTURE_CAPTURED_FETCH;
    assert(!opengpu_validate_compute_texture_snapshot(&s));
    /* A buffer-only root has no GXS table or GXS backing. No sampler pointer,
     * sampler handle or unused sampler config participates in admission. */
    root.size_only=false; root.buffer_only=true;
    image.target=binding.target=levels[1].target=OPENGPU_TEXTURE_BUFFER_TARGET;
    image.width=image.height=image.depth=image.layers=1;
    image.base_level=image.max_level=levels[1].level=0;
    image.buffer_texels=levels[1].buffer_texels=65536;
    image.buffer_bytes=levels[1].buffer_bytes=262144;
    image.allocation_base=levels[1].allocation_base=levels[1].base=0x10000;
    image.allocation_limit=levels[1].allocation_limit=0x50000;
    levels[1].width=levels[1].height=levels[1].depth=levels[1].layers=1;
    levels[1].row_pitch=0; levels[1].handle=0; s.level_addresses=addresses;
    s.selected_level=0; binding.sampler_handle=0xffffffffu; s.sampler_address=1;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    sampler.min_filter=0xffffffffu; sampler.generation++;
    s.sampler=&sampler;
    assert(opengpu_validate_compute_texture_snapshot(&s));
    s.operation=OPENGPU_COMPUTE_TEXTURE_SAMPLE;
    assert(!opengpu_validate_compute_texture_snapshot(&s));
    s.operation=OPENGPU_COMPUTE_TEXTURE_CAPTURED_FETCH;
    binding.target=0;
    assert(!opengpu_validate_compute_texture_snapshot(&s));
    binding.target=OPENGPU_TEXTURE_BUFFER_TARGET; image.generation++;
    assert(!opengpu_validate_compute_texture_snapshot(&s));
    assert(!opengpu_validate_compute_texture_snapshot(NULL));
    return 0;
}
