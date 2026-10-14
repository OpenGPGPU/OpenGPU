/* SPDX-License-Identifier: MIT */
#define _POSIX_C_SOURCE 200809L
#include <assert.h>
#include <errno.h>
#include <time.h>
static long elapsed_ms;
static int sleeps, calls, busy_count, terminal_error, interrupted;
static int test_clock(clockid_t id, struct timespec *now)
{
    assert(id == CLOCK_MONOTONIC);
    now->tv_sec = elapsed_ms / 1000;
    now->tv_nsec = elapsed_ms % 1000 * 1000000;
    return 0;
}
static int test_sleep(const struct timespec *delay, struct timespec *remaining)
{
    (void)remaining;
    sleeps++;
    elapsed_ms += delay->tv_nsec / 1000000;
    if (interrupted) { errno = EINTR; return -1; }
    return 0;
}
#define clock_gettime test_clock
#define nanosleep test_sleep
#include "../opengpu_retry.h"
static int operation(void *data)
{
    assert(data == &calls);
    calls++;
    elapsed_ms += 3; /* Include time spent inside the ioctl. */
    if (calls <= busy_count) { errno = EBUSY; return -1; }
    if (terminal_error) { errno = terminal_error; return -1; }
    return 0;
}
static void reset(void)
{
    elapsed_ms = sleeps = calls = busy_count = terminal_error = interrupted = 0;
}
int main(void)
{
    reset(); busy_count = 2;
    assert(opengpu_retry_busy(operation, &calls, 100) == 0);
    assert(calls == 3 && sleeps == 2);
    reset(); terminal_error = EIO;
    assert(opengpu_retry_busy(operation, &calls, 100) == -1 && errno == EIO);
    assert(calls == 1 && sleeps == 0);
    reset(); busy_count = 100;
    assert(opengpu_retry_busy(operation, &calls, 25) == -1 && errno == ETIMEDOUT);
    assert(calls == 3 && sleeps == 2 && elapsed_ms == 29);
    reset(); busy_count = 100;
    assert(opengpu_retry_busy(operation, &calls, 0) == -1 && errno == ETIMEDOUT);
    assert(calls == 1 && sleeps == 0);
    reset(); busy_count = 100; interrupted = 1;
    assert(opengpu_retry_busy(operation, &calls, 100) == -1 && errno == EINTR);
    assert(calls == 1);
    return 0;
}
