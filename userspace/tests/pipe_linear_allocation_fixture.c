#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>

struct pipe_opengpu_screen { int unused; };
struct pipe_opengpu_resource { uint32_t width, height, pitch; };
static struct pipe_opengpu_resource resource;
static unsigned allocations;
static uint32_t allocated_bytes;

static struct pipe_opengpu_resource *pipe_opengpu_resource_create(
    struct pipe_opengpu_screen *screen, uint32_t bytes)
{
    assert(screen && bytes);
    allocations++;
    allocated_bytes = bytes;
    return &resource;
}

#include "linear_allocation_under_test.inc"

int main(void)
{
    struct pipe_opengpu_screen screen = {0};
    struct pipe_opengpu_resource *result;

    result = pipe_opengpu_resource_create_linear(&screen, 7, 3);
    assert(result && result->width == 7 && result->height == 3 &&
           result->pitch == 28 && allocated_bytes == 84 && allocations == 1);
    result = pipe_opengpu_resource_create_linear(&screen, 1, 536870911u);
    assert(result && allocated_bytes == 2147483644u && allocations == 2);
    result = pipe_opengpu_resource_create_linear(&screen, 536870911u, 1);
    assert(result && result->pitch == 2147483644u && allocations == 3);
    errno = 0;
    assert(!pipe_opengpu_resource_create_linear(&screen, 1, 536870912u));
    assert(errno == EINVAL && allocations == 3);
    /* 4 * width * height wraps to 1496642348 in uint64_t. */
    errno = 0;
    assert(!pipe_opengpu_resource_create_linear(&screen, 1650831861u, 2793552831u));
    assert(errno == EINVAL && allocations == 3);
    errno = 0;
    assert(!pipe_opengpu_resource_create_linear(&screen, 0, 1));
    assert(errno == EINVAL && allocations == 3);
    puts("linear image allocation bounds: PASS");
}
