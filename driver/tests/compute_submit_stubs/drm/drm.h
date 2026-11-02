#ifndef TEST_DRM_H
#define TEST_DRM_H
#include <stdint.h>
typedef uint32_t __u32; typedef int32_t __s32; typedef uint64_t __u64;
typedef uint64_t __aligned_u64 __attribute__((aligned(8)));
#define DRM_COMMAND_BASE 0x40
#define DRM_IOWR(a,b) 0
#define DRM_IOW(a,b) 0
#endif
