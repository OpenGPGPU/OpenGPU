/* SPDX-License-Identifier: MIT */
/* Shared helpers for small compute ioctl smoke tests. */
#ifndef OPENGPU_COMPUTE_SMOKE_UTIL_H
#define OPENGPU_COMPUTE_SMOKE_UTIL_H
#include <drm/drm.h>
#include <linux/sync_file.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>
#include "opengpu_drm.h"

struct buffer { uint32_t handle; uint64_t size; void *map; };
static int create_buffer(int fd, uint32_t bytes, struct buffer *out)
{
    struct drm_opengpu_create_buffer req={.width=(bytes+3)/4,.height=1,.bpp=32};
    if (ioctl(fd,DRM_IOCTL_OPENGPU_CREATE_BUFFER,&req)<0) return -1;
    out->handle=req.handle; out->size=req.size;
    out->map=mmap(NULL,req.size,PROT_READ|PROT_WRITE,MAP_SHARED,fd,req.offset);
    return out->map==MAP_FAILED ? -1 : 0;
}
static int bind_buffer(int fd, uint32_t ctx, uint32_t slot, struct buffer *b,
                       uint32_t type, uint64_t size)
{
    struct drm_opengpu_resource r={.context_id=ctx,.slot=slot,.handle=b->handle,
        .type=type,.size=size};
    return ioctl(fd,DRM_IOCTL_OPENGPU_RESOURCE_BIND,&r);
}
static int wait_fence_timeout(int fd, uint32_t handle, unsigned timeout_ms)
{
    struct timespec now; struct drm_syncobj_wait wait={0};
    struct drm_syncobj_handle export={.handle=handle,.flags=DRM_SYNCOBJ_HANDLE_TO_FD_FLAGS_EXPORT_SYNC_FILE};
    struct sync_file_info info={0}; int ret;
    if (clock_gettime(CLOCK_MONOTONIC,&now)<0) return -1;
    wait.handles=(uintptr_t)&handle; wait.count_handles=1;
    wait.flags=DRM_SYNCOBJ_WAIT_FLAGS_WAIT_ALL|DRM_SYNCOBJ_WAIT_FLAGS_WAIT_FOR_SUBMIT;
    wait.timeout_nsec=(int64_t)now.tv_sec*1000000000LL+now.tv_nsec+(int64_t)timeout_ms*1000000LL;
    if (ioctl(fd,DRM_IOCTL_SYNCOBJ_WAIT,&wait)<0 ||
        ioctl(fd,DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD,&export)<0) return -1;
    ret=ioctl(export.fd,SYNC_IOC_FILE_INFO,&info); close(export.fd);
    if (ret<0) return ret;
    if (info.status!=1) { fprintf(stderr,"compute fence status=%d\n",info.status); errno=EIO; return -1; }
    return 0;
}
static int wait_fence(int fd, uint32_t handle)
{
    return wait_fence_timeout(fd, handle, 300000);
}

#endif /* OPENGPU_COMPUTE_SMOKE_UTIL_H */
