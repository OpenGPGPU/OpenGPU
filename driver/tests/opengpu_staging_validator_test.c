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
    struct opengpu_staging_range submission[] = {
        {0x1000, 0x100}, {0x1100, 0x80}, {0, 0}, {0x2000, 0x40}
    };
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

    assert(opengpu_dma_ranges_pairwise_disjoint(NULL, 0));
    assert(!opengpu_dma_ranges_pairwise_disjoint(NULL, 1));
    assert(opengpu_dma_ranges_pairwise_disjoint(submission, 4));
    submission[3].address = 0x117f;
    assert(!opengpu_dma_ranges_pairwise_disjoint(submission, 4));
    submission[3].address = 0x1180;
    assert(opengpu_dma_ranges_pairwise_disjoint(submission, 4));
    submission[3].address = UINT64_MAX;
    submission[3].bytes = 2;
    assert(!opengpu_dma_ranges_pairwise_disjoint(submission, 4));
    submission[3].bytes = 0;
    assert(opengpu_dma_ranges_pairwise_disjoint(submission, 4));
    for (i = 0; i < 4; i++) {
        unsigned j, k;

        for (j = i + 1; j < 4; j++) {
            for (k = 0; k < 4; k++) {
                submission[k].address = 0x1000 + k * 0x100;
                submission[k].bytes = 0x40;
            }
            submission[j].address = submission[i].address + 0x3f;
            assert(!opengpu_dma_ranges_pairwise_disjoint(submission, 4));
            submission[j].address++;
            assert(opengpu_dma_ranges_pairwise_disjoint(submission, 4));
        }
    }
    for (i = 0; i < 512; i++) {
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
    puts("staging DMA ranges: 512 widened-reference comparisons, pairwise boundaries, aliases, wrap and null cases PASS");
    return 0;
}
