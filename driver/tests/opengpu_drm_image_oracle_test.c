#include <assert.h>
#include <stdio.h>
#include "opengpu_drm_image_oracle.h"

int main(void)
{
    /* The64x64 count is also checked against every pixel of a native RTL
     * replay using the actual C shader, including discarded helper quads. */
    assert(opengpu_test_fragment_pixels(64, 64, 8) == 1009);
    assert(opengpu_test_fragment_pixels(16, 16, 8) == 61);
    assert(opengpu_test_fragment_pixels(8, 8, 8) == 15);
    assert(opengpu_test_fragment_pixels(4, 4, 8) == 3);
    assert(opengpu_test_fragment_pixels(64, 64, 4) == 0);
    assert(opengpu_test_fragment_pixels(64, 64, 4096) == 2015);
    assert(opengpu_test_fragment_pixels(64, 64, 0) == 0);
    assert(opengpu_test_fragment_pixels(64, 64, 6) == 0);
    puts("DRM fragment image oracle tests passed");
    return 0;
}
