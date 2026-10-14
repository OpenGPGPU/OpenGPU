/* SPDX-License-Identifier: MIT */
#ifndef OPENGPU_RETRY_H
#define OPENGPU_RETRY_H

#include <errno.h>
#include <stdint.h>
#include <time.h>

/* Retry only transient command-slot contention. Use elapsed monotonic time,
 * including time spent in the ioctl, rather than counting sleeps. */
static inline int opengpu_retry_busy(int (*operation)(void *), void *data,
                                      int64_t timeout_ms)
{
    struct timespec start, now;
    const struct timespec delay = { .tv_sec = 0, .tv_nsec = 10000000 };
    int ret;

    if (timeout_ms < 0) {
        errno = EINVAL;
        return -1;
    }
    if (clock_gettime(CLOCK_MONOTONIC, &start))
        return -1;
    for (;;) {
        ret = operation(data);
        if (ret >= 0 || errno != EBUSY)
            return ret;
        if (clock_gettime(CLOCK_MONOTONIC, &now))
            return -1;
        if ((now.tv_sec - start.tv_sec) * INT64_C(1000000000) +
            now.tv_nsec - start.tv_nsec >= timeout_ms * INT64_C(1000000)) {
            errno = ETIMEDOUT;
            return -1;
        }
        if (nanosleep(&delay, NULL))
            return -1;
    }
}

#endif
