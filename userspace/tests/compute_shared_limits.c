/* Exercise the production DRM and winsys queries, including partial failures. */
#include <assert.h>
#include <errno.h>
#include <stdint.h>
#include <stddef.h>
#include "opengpu_drm.h"

static uint64_t values[5];
static unsigned requests[5], calls, fail_call;
static int fake_ioctl(int fd, unsigned long request, struct drm_opengpu_param *param)
{
    unsigned slot;
    assert(fd == 7 && request == DRM_IOCTL_OPENGPU_GET_PARAM && !param->pad);
    switch (param->param) {
    case OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS: slot = 0; break;
    case OPENGPU_PARAM_COMPUTE_LOCAL_AXIS: slot = 1; break;
    case OPENGPU_PARAM_COMPUTE_SHARED_BASE: slot = 2; break;
    case OPENGPU_PARAM_COMPUTE_SHARED_BYTES: slot = 3; break;
    case OPENGPU_PARAM_COMPUTE_SHARED_LANES: slot = 4; break;
    default: assert(0); return -1;
    }
    assert(calls < 5);
    requests[calls++] = param->param;
    if (calls == fail_call) { errno = ENOTTY; return -1; }
    param->value = values[slot];
    return 0;
}
#define ioctl fake_ioctl
#include "shared_limits_under_test.inc"
struct pipe_opengpu_screen { int fd; uint64_t driver_features; };
#include "pipe_shared_limits_under_test.inc"

static void reset(void)
{
    values[0] = values[1] = 128;
    values[2] = 0x10000000; values[3] = 16384; values[4] = 16;
    calls = fail_call = 0;
}

int main(void)
{
    uint32_t base, bytes, lanes;
    struct pipe_opengpu_screen screen = {7, OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE};
    reset();
    assert(!opengpu_compute_shared_limits(7, &base, &bytes, &lanes));
    assert(base == 0x10000000 && bytes == 16384 && lanes == 16 && calls == 3);
    assert(requests[0] == OPENGPU_PARAM_COMPUTE_SHARED_BASE &&
           requests[1] == OPENGPU_PARAM_COMPUTE_SHARED_BYTES &&
           requests[2] == OPENGPU_PARAM_COMPUTE_SHARED_LANES);
    /* Base zero is valid, as is an exclusive 4 GiB end. */
    reset(); values[2] = 0;
    assert(!opengpu_compute_shared_limits(7, &base, &bytes, &lanes) && !base);
    reset(); values[2] = 0xffffc000u;
    assert(!opengpu_compute_shared_limits(7, &base, &bytes, &lanes) && base == 0xffffc000u);
    reset(); values[2] = values[3] = values[4] = 0;
    assert(!opengpu_compute_shared_limits(7, &base, &bytes, &lanes) && !base && !bytes && !lanes);
    for (unsigned failure = 1; failure <= 3; ++failure) {
        reset(); fail_call = failure; base = 7; bytes = 9; lanes = 11;
        assert(opengpu_compute_shared_limits(7, &base, &bytes, &lanes) == -1 && errno == ENOTTY);
        assert(calls == failure && base == 7 && bytes == 9 && lanes == 11);
    }
    const uint64_t invalid[][3] = {
        {UINT64_C(0x100000000), 16384, 16},
        {0, UINT64_C(0x100000000), 16},
        {0, 16384, UINT64_C(0x100000000)},
        {0x10000001, 16384, 16}, {0x10000000, 16385, 16},
        {0xffffc004u, 16384, 16},
        {0, 16384, 0}, {0, 0, 16}, {0x10000000, 0, 0},
    };
    for (unsigned i = 0; i < sizeof(invalid) / sizeof(invalid[0]); ++i) {
        reset();
        for (unsigned j = 0; j < 3; ++j) values[j + 2] = invalid[i][j];
        base = 7; bytes = 9; lanes = 11;
        assert(opengpu_compute_shared_limits(7, &base, &bytes, &lanes) == -1 && errno == EPROTO);
        assert(base == 7 && bytes == 9 && lanes == 11);
    }
    reset();
    assert(opengpu_compute_shared_limits(7, NULL, &bytes, &lanes) == -1 && errno == EINVAL);
    assert(opengpu_compute_shared_limits(7, &base, NULL, &lanes) == -1 && errno == EINVAL);
    assert(opengpu_compute_shared_limits(7, &base, &bytes, NULL) == -1 && errno == EINVAL);
    assert(opengpu_compute_shared_limits(7, &base, &base, &lanes) == -1 && errno == EINVAL);
    assert(opengpu_compute_shared_limits(7, &base, &bytes, &base) == -1 && errno == EINVAL);
    assert(opengpu_compute_shared_limits(7, &base, &bytes, &bytes) == -1 && errno == EINVAL && !calls);

    reset();
    assert(!pipe_opengpu_screen_compute_shared_limits(&screen, &base, &bytes, &lanes));
    assert(base == 0x10000000 && bytes == 16384 && lanes == 16 && calls == 5);
    assert(requests[0] == OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS &&
           requests[1] == OPENGPU_PARAM_COMPUTE_LOCAL_AXIS);
    for (unsigned failure = 1; failure <= 5; ++failure) {
        reset(); fail_call = failure; base = 7; bytes = 9; lanes = 11;
        assert(pipe_opengpu_screen_compute_shared_limits(&screen, &base, &bytes, &lanes) == -1);
        assert(errno == ENOTTY && calls == failure && base == 7 && bytes == 9 && lanes == 11);
    }
    reset(); base = 7; bytes = 9; lanes = 11; screen.driver_features = 0;
    assert(pipe_opengpu_screen_compute_shared_limits(&screen, &base, &bytes, &lanes) == -1 &&
           errno == ENOTSUP && !calls && base == 7 && bytes == 9 && lanes == 11);
    screen.driver_features = OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE;
    assert(pipe_opengpu_screen_compute_shared_limits(NULL, &base, &bytes, &lanes) == -1 &&
           errno == EINVAL && !calls);
    assert(pipe_opengpu_screen_compute_shared_limits(&screen, &base, &base, &lanes) == -1 &&
           errno == EINVAL && !calls);
    reset(); values[2] = values[3] = values[4] = 0;
    assert(pipe_opengpu_screen_compute_shared_limits(&screen, &base, &bytes, &lanes) == -1 &&
           errno == ENOTSUP && base == 7 && bytes == 9 && lanes == 11);
    reset(); values[4] = 129;
    assert(pipe_opengpu_screen_compute_shared_limits(&screen, &base, &bytes, &lanes) == -1 &&
           errno == EPROTO && base == 7 && bytes == 9 && lanes == 11);
    reset(); values[3] = 60;
    assert(pipe_opengpu_screen_compute_shared_limits(&screen, &base, &bytes, &lanes) == -1 &&
           errno == EPROTO && base == 7 && bytes == 9 && lanes == 11);
    /* A smaller discovered profile stays real; no hard-coded 16KiB fallback. */
    reset(); values[0] = values[1] = 8; values[2] = 0x18000000; values[3] = 512; values[4] = 4;
    assert(!pipe_opengpu_screen_compute_shared_limits(&screen, &base, &bytes, &lanes) &&
           base == 0x18000000 && bytes == 512 && lanes == 4);
    return 0;
}
