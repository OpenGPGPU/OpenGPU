/* The public wrapper publishes no partial result on a failed or invalid query. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stddef.h>
#include "opengpu_drm.h"

static uint64_t values[2];
static int calls, fail_call;
static int fake_ioctl(int fd, unsigned long request, struct drm_opengpu_param *param)
{
    assert(fd == 7 && request == DRM_IOCTL_OPENGPU_GET_PARAM);
    assert(param->param == (calls ? OPENGPU_PARAM_COMPUTE_LOCAL_AXIS : OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS));
    if (++calls == fail_call) { errno = EINVAL; return -1; }
    param->value = values[calls - 1];
    return 0;
}
#define ioctl fake_ioctl
#include "local_limits_under_test.inc"

int main(void)
{
    uint32_t items = 7, axis = 9;
    values[0] = values[1] = 128;
    assert(!opengpu_compute_local_limits(7, &items, &axis) && items == 128 && axis == 128);
    calls = 0; values[0] = values[1] = 8;
    assert(!opengpu_compute_local_limits(7, &items, &axis) && items == 8 && axis == 8);
    calls = 0; values[0] = values[1] = 0;
    assert(!opengpu_compute_local_limits(7, &items, &axis) && !items && !axis);
    calls = 0; fail_call = 1; items = 7; axis = 9;
    assert(opengpu_compute_local_limits(7, &items, &axis) == -1 && errno == EINVAL && items == 7 && axis == 9);
    calls = 0; fail_call = 2;
    assert(opengpu_compute_local_limits(7, &items, &axis) == -1 && errno == EINVAL && items == 7 && axis == 9);
    fail_call = 0; calls = 0; values[0] = UINT64_C(0x100000000); values[1] = 1;
    assert(opengpu_compute_local_limits(7, &items, &axis) == -1 && errno == EPROTO && items == 7 && axis == 9);
    calls = 0; values[0] = 8; values[1] = 9;
    assert(opengpu_compute_local_limits(7, &items, &axis) == -1 && errno == EPROTO && items == 7 && axis == 9);
    calls = 0; values[0] = 8; values[1] = 0;
    assert(opengpu_compute_local_limits(7, &items, &axis) == -1 && errno == EPROTO && items == 7 && axis == 9);
    calls = 0;
    assert(opengpu_compute_local_limits(7, NULL, &axis) == -1 && errno == EINVAL && !calls);
    assert(opengpu_compute_local_limits(7, &items, NULL) == -1 && errno == EINVAL && !calls);
    assert(opengpu_compute_local_limits(7, &items, &items) == -1 && errno == EINVAL && !calls);
    return 0;
}
