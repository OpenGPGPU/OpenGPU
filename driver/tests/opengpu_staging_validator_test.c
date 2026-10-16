/* SPDX-License-Identifier: MIT */
#include "opengpu_staging_validator.h"
#include <assert.h>
#include <stddef.h>
#include <stdio.h>

static bool reference(struct opengpu_staging_range a,
                      struct opengpu_staging_range b)
{
    __uint128_t ae = (__uint128_t)a.address + a.bytes;
    __uint128_t be = (__uint128_t)b.address + b.bytes;

    return a.bytes && ae <= UINT64_MAX &&
        (!b.bytes || (be <= UINT64_MAX &&
                      (ae <= b.address || be <= a.address)));
}

int main(void)
{
    struct opengpu_staging_range a = {100, 20}, b = {120, 1};
    struct opengpu_staging_range resources[] = {{0, 0}, {120, 20}, {99, 1}};
    uint64_t seed = 42;
    unsigned i;

    assert(opengpu_staging_disjoint(&a, &b, 1));
    b.address = 99;
    assert(opengpu_staging_disjoint(&a, &b, 1));
    b.bytes = 2;
    assert(!opengpu_staging_disjoint(&a, &b, 1));
    b = a;
    assert(!opengpu_staging_disjoint(&a, &b, 1));
    assert(opengpu_staging_disjoint(&a, NULL, 0));
    assert(!opengpu_staging_disjoint(&a, NULL, 1));
    assert(!opengpu_staging_disjoint(NULL, &b, 1));
    assert(opengpu_staging_disjoint(&a, resources, 3));
    resources[2].bytes = 2;
    assert(!opengpu_staging_disjoint(&a, resources, 3));
    resources[2].bytes = 1;
    resources[0].address = UINT64_MAX;
    resources[0].bytes = 2;
    assert(!opengpu_staging_disjoint(&a, resources, 3));
    resources[0].bytes = 0;
    assert(opengpu_staging_disjoint(&a, resources, 3));

    for (i = 0; i < 100000; i++) {
        seed = seed * 6364136223846793005ull + 1;
        a.address = seed;
        seed = seed * 6364136223846793005ull + 1;
        a.bytes = seed;
        seed = seed * 6364136223846793005ull + 1;
        b.address = seed;
        seed = seed * 6364136223846793005ull + 1;
        b.bytes = seed;
        if (i % 4 == 0)
            a.bytes = i % 17;
        if (i % 5 == 0)
            b.bytes = i % 19;
        assert(opengpu_staging_disjoint(&a, &b, 1) == reference(a, b));
    }
    a.address = UINT64_MAX - 5;
    a.bytes = 5;
    b.address = 0;
    b.bytes = 1;
    assert(opengpu_staging_disjoint(&a, &b, 1));
    a.bytes = 6;
    assert(!opengpu_staging_disjoint(&a, &b, 1));
    puts("staging DMA ranges: 100000 widened-reference comparisons, boundaries, aliases, wrap and null cases PASS");
    return 0;
}
