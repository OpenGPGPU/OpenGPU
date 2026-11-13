/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stddef.h>
#include "../opengpu_native_advanced_blend_features.h"

struct device {
    u32 words[8];
    unsigned int reads;
};
static u32 read_register(void *context, u32 address)
{
    struct device *d = context;
    assert(address >= 0x4c0u && address < 0x4e0u && !(address & 3u));
    ++d->reads;
    return d->words[(address - 0x4c0u) / 4u];
}
int main(void)
{
    /* Literal software contract, independent of the generated encoder. */
    const u32 canonical[8] = { 0x47414201u, 8u, 8u, 0x7fffu,
                              0xf00001ffu, 0x0fu, 7u, 0u };
    const u32 flags = 1u << 15;
    const u32 caps = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE;
    struct device d = { { 0 }, 0 };
    bool transport;
    unsigned int i, bit;

    for (i = 0; i < 8; ++i)
        d.words[i] = canonical[i];
    for (i = 0; i < 2; ++i) {
        transport = true;
        assert(opengpu_read_native_advanced_blend_support(1u + i, 0u, 0u,
                   0u, NULL, NULL, &transport));
        assert(!transport && d.reads == 0);
    }
    transport = true;
    assert(!opengpu_read_native_advanced_blend_support(1u, flags, caps,
                0x1000u, &d, read_register, &transport));
    assert(!transport && d.reads == 0);
    assert(!opengpu_read_native_advanced_blend_support(2u, flags, caps,
                0x4dfu, &d, read_register, &transport));
    assert(!transport && d.reads == 0);
    for (i = 0; i < 2; ++i) {
        const u32 missing = i ? GPU_CAP_PROTECTED_VM_STATE : GPU_CAP_UNIFIED_COMMANDS;
        assert(!opengpu_read_native_advanced_blend_support(2u, flags, caps & ~missing,
                    0x4e0u, &d, read_register, &transport));
        assert(!transport && d.reads == 0);
    }
    assert(!opengpu_read_native_advanced_blend_support(2u, flags, caps,
                0x4e0u, &d, NULL, &transport));
    assert(!transport && d.reads == 0);
    assert(!opengpu_read_native_advanced_blend_support(2u, flags, caps,
                0x4e0u, &d, read_register, NULL));
    assert(d.reads == 0);
    assert(opengpu_read_native_advanced_blend_support(2u, flags, caps,
                0x4e0u, &d, read_register, &transport));
    assert(transport && d.reads == 8);

    /* Includes all unknown/reserved bits, as well as missing required bits. */
    for (i = 0; i < 8; ++i) {
        for (bit = 0; bit < 32; ++bit) {
            d.words[i] = canonical[i] ^ (1u << bit);
            d.reads = 0;
            transport = true;
            assert(!opengpu_read_native_advanced_blend_support(2u, flags, caps,
                        0x4e0u, &d, read_register, &transport));
            assert(!transport && d.reads == i + 1);
        }
        d.words[i] = canonical[i];
    }
    d.reads = 0;
    assert(opengpu_read_native_advanced_blend_support(2u, flags, caps,
                0x4e0u, &d, read_register, &transport));
    assert(transport && d.reads == 8);
    return 0;
}
