#include <assert.h>
#include <math.h>
#include <stddef.h>
#include <stdint.h>

#include "opengpu_raster_capabilities.h"

static void test_line_width_range(void)
{
    struct opengpu_line_width_range range;
    const uint32_t gri1_word = 0x01014001u;

    assert(opengpu_decode_line_width_range(gri1_word, &range) == 0);
    assert(range.minimum == 1u);
    assert(range.maximum == 64u);
    assert(range.granularity == 1u);
    assert(range.multisample_same == 1);
    assert(opengpu_line_width_as_float(range.minimum) == 1.0f);
    assert(opengpu_line_width_as_float(range.maximum) == 64.0f);

    assert(opengpu_decode_line_width_range(0x01014000u, &range) == -1);
    assert(opengpu_decode_line_width_range(0x01000101u, &range) == -1);
    assert(opengpu_decode_line_width_range(0x01000001u, &range) == -1);
    assert(opengpu_decode_line_width_range(0x00004001u, &range) == -1);
    assert(opengpu_decode_line_width_range(gri1_word, NULL) == -1);

    assert(opengpu_decode_line_width_range(0x00020001u, &range) == -1);
    assert(opengpu_decode_line_width_range(0x00020201u, &range) == 0);
    assert(range.minimum == 1u);
    assert(range.maximum == 2u);
    assert(range.granularity == 2u);
}

int main(void)
{
    test_line_width_range();
    return 0;
}
