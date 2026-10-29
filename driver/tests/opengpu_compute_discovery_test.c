// SPDX-License-Identifier: MIT
#include <assert.h>
#include "../opengpu_compute_features.h"

struct device { unsigned reads; u32 magic, words, flags; };
static u32 read_reg(void *context, u32 address)
{
    struct device *d = context;
    d->reads++;
    switch (address) {
    case OPENGPU_COMPUTE_RESOURCE_REG_MAGIC: return d->magic;
    case OPENGPU_COMPUTE_RESOURCE_REG_WORDS: return d->words;
    case OPENGPU_COMPUTE_RESOURCE_REG_FLAGS: return d->flags;
    default: assert(0); return 0;
    }
}

int main(void)
{
    struct device d = {0, OPENGPU_COMPUTE_RESOURCE_MAGIC, 16, 15};
    u32 flags = ~0u;
    assert(opengpu_read_compute_features(0x47550001, 4, &d, read_reg, &flags));
    assert(d.reads == 0 && flags == 0); /* unmapped legacy registers untouched */
    assert(!opengpu_read_compute_features(0x47550002, 0x39f, &d, read_reg, &flags));
    assert(d.reads == 0 && flags == 0);
    assert(opengpu_read_compute_features(0x47550002, 0x3a0, &d, read_reg, &flags));
    assert(d.reads == 3 && flags == 15);
    d.reads = 0; d.magic = 0; flags = ~0u;
    assert(!opengpu_read_compute_features(0x47550002, 0x1000, &d, read_reg, &flags));
    assert(d.reads == 1 && flags == 0);
    d.reads = 0; d.magic = OPENGPU_COMPUTE_RESOURCE_MAGIC; d.words = 15;
    assert(!opengpu_read_compute_features(0x47550002, 0x1000, &d, read_reg, &flags));
    assert(d.reads == 2 && flags == 0);
    d.reads = 0; d.words = 16; d.flags = 7;
    assert(opengpu_read_compute_features(0x47550002, 0x1000, &d, read_reg, &flags));
    assert(d.reads == 3 && !(flags & OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS));
    return 0;
}
