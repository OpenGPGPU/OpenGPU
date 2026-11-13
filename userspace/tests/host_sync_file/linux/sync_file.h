/* SPDX-License-Identifier: MIT */
/* Host CPU fixture only. Linux sync_file_info wire layout, used solely with
 * cs_fixture_ioctl on non-Linux hosts. Never include this directory in a
 * device build; Linux tests use their real installed UAPI instead. */
#ifndef OPENGPU_HOST_TEST_SYNC_FILE_H
#define OPENGPU_HOST_TEST_SYNC_FILE_H
#include <stdint.h>
struct sync_file_info {
    char name[32];
    int32_t status;
    uint32_t flags, num_fences, pad;
    uint64_t sync_fence_info;
};
_Static_assert(sizeof(struct sync_file_info)==56,"sync_file_info CPU fixture layout");
#define SYNC_IOC_FILE_INFO 0xc0383e04ul
#endif
