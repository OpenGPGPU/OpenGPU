/* SPDX-License-Identifier: MIT */
#include <assert.h>
#include <stdint.h>
#include <stdio.h>
#include "../opengpu_cache_range.h"

static void valid(uint64_t address, uint64_t bytes, uint32_t start, uint32_t size)
{
    struct opengpu_cache_range out = {0, 0};
    assert(opengpu_cache_invalidate_range(address, bytes, &out) == 1);
    assert(out.address == start && out.bytes == size);
}

static void invalid(uint64_t address, uint64_t bytes)
{
    struct opengpu_cache_range out = {123, 456};
    assert(opengpu_cache_invalidate_range(address, bytes, &out) == -1);
    assert(out.address == 123 && out.bytes == 456);
}

int main(void)
{
    struct opengpu_cache_range out = {123, 456};
    const uint64_t limit = 1ull << 32;
    unsigned i;

    assert(opengpu_cache_invalidate_range(0, 1, NULL) == -1);
    assert(opengpu_cache_invalidate_range(UINT64_MAX, 0, &out) == 0);
    assert(out.address == 0 && out.bytes == 0);
    valid(0, 1, 0, 64);
    valid(63, 1, 0, 64);
    valid(63, 2, 0, 128);
    valid(64, 64, 64, 64);
    valid(limit - 1, 1, 0xffffffc0u, 64);
    valid(64, limit - 64, 64, 0xffffffc0u);
    invalid(limit, 1);
    invalid(limit - 1, 2);
    invalid(0, limit);
    invalid(1, limit - 1);
    invalid(0, UINT64_MAX - 30);
    invalid(UINT64_MAX - 1, 3);

    /* Small deterministic boundary samples against a widened reference. */
    for (i = 0; i < 256; i++) {
        uint64_t address = (i * 37u) % 8192u;
        uint64_t bytes = (i * 19u) % 257u + 1;
        uint64_t start = address / 64 * 64;
        uint64_t end = (address + bytes + 63) / 64 * 64;
        valid(address, bytes, (uint32_t)start, (uint32_t)(end - start));
    }
    puts("cache invalidation ranges: 256 small cases and 32/64-bit boundaries PASS");
    return 0;
}
