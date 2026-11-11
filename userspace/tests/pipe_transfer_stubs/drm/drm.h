/* Compile-only placeholders for unused standard DRM ioctls in this fixture.
 * These layouts are not an ABI and must never be used with a real device. */
#ifndef PIPE_TRANSFER_TEST_DRM_H
#define PIPE_TRANSFER_TEST_DRM_H
#include <stdint.h>
typedef uint32_t __u32;
typedef int32_t __s32;
typedef uint64_t __u64;
typedef uint64_t __aligned_u64 __attribute__((aligned(8)));
#define DRM_COMMAND_BASE 0x40
#define DRM_IOWR(a, b) 0
#define DRM_IOW(a, b) 0
#define DRM_IOCTL_GEM_CLOSE 1
#define DRM_IOCTL_PRIME_FD_TO_HANDLE 2
#define DRM_IOCTL_PRIME_HANDLE_TO_FD 3
#define DRM_RDWR 2
struct drm_gem_close { __u32 handle; };
struct drm_prime_handle { __u32 handle, flags; __s32 fd; };
#endif
