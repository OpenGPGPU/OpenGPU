/* SPDX-License-Identifier: MIT */
/* Tiny enabled-path compute storage ioctl smoke for an opt-in ARTI model. */
#include <drm/drm.h>
#include <linux/sync_file.h>
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
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
static int wait_fence(int fd, uint32_t handle)
{
    struct timespec now; struct drm_syncobj_wait wait={0};
    struct drm_syncobj_handle export={.handle=handle,.flags=DRM_SYNCOBJ_HANDLE_TO_FD_FLAGS_EXPORT_SYNC_FILE};
    struct sync_file_info info={0}; int ret;
    if (clock_gettime(CLOCK_MONOTONIC,&now)<0) return -1;
    wait.handles=(uintptr_t)&handle; wait.count_handles=1;
    wait.flags=DRM_SYNCOBJ_WAIT_FLAGS_WAIT_ALL|DRM_SYNCOBJ_WAIT_FLAGS_WAIT_FOR_SUBMIT;
    wait.timeout_nsec=(int64_t)now.tv_sec*1000000000LL+now.tv_nsec+300000000000LL;
    if (ioctl(fd,DRM_IOCTL_SYNCOBJ_WAIT,&wait)<0 ||
        ioctl(fd,DRM_IOCTL_SYNCOBJ_HANDLE_TO_FD,&export)<0) return -1;
    ret=ioctl(export.fd,SYNC_IOC_FILE_INFO,&info); close(export.fd);
    if (ret<0) return ret;
    if (info.status!=1) { fprintf(stderr,"compute fence status=%d\n",info.status); errno=EIO; return -1; }
    return 0;
}
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
int main(void)
{
    struct buffer shader={0}, kernarg={0}, backing={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_storage submit={0};
    uint32_t *code, *result, *data; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if ((features.value & (OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) !=
        (OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE | OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) {
        fprintf(stderr,"compute storage or word atomic driver feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,8192,&backing)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; data=backing.map;
    memset(code,0,128); memset(result,0,64); memset(data,0,64); result[2]=7;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    code[n++]=0xc100f057u; /* vsetivli zero,1,e32,m1 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u; /* v2 = binding 0 */
    code[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u; /* v3 = 7 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(4u<<7)|0x57u; /* v4 = offset 0 */
    code[n++]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu; /* capture v3 */
    code[n++]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu; /* store */
    code[n++]=(15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(5u<<7)|0x2bu; /* load v5 */
    code[n++]=0x02006027u|(1u<<15)|(5u<<7); /* vse32 v5,(x1) */
    code[n++]=0x00808493u; /* addi x9,x1,8 */
    code[n++]=0x00100513u; /* addi x10,x0,1 */
    code[n++]=(10u<<20)|(9u<<15)|(2u<<12)|(11u<<7)|0x2fu; /* amoadd.w */
    code[n++]=0x00b0a623u; /* sw x11,12(x1) */
    code[n++]=0x30500073u;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    CHECK(bind_buffer(fd,context.id,13,&kernarg,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    errno=0;
    if (ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit)!=-1 || errno!=EINVAL) {
        fprintf(stderr,"writable kernarg alias was accepted\n"); return 1;
    }
    CHECK(bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    if (getenv("OPENGPU_TEST_STORAGE_FAULT")) {
        struct drm_opengpu_fault fault={0};
        submit.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ;
        puts("storage smoke: submitting denied write"); fflush(stdout);
        CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit));
        errno=0;
        if (wait_fence(fd,sync.handle)!=-1 || errno!=EIO) {
            fprintf(stderr,"read-only storage write did not fail\n"); return 1;
        }
        CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_FAULT,&fault));
        if ((fault.flags & (OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR)) !=
            (OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) || fault.error>=0 ||
            data[0]!=0 || result[0]!=0 || result[2]!=7) {
            fprintf(stderr,"denied write: flags=%x error=%d data=%x result=%x atomic=%x\n",
                    fault.flags,fault.error,data[0],result[0],result[2]); return 1;
        }
        submit.bindings[0].access=OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE;
        puts("storage smoke: denied write failed, retrying writable"); fflush(stdout);
    }
    puts("storage smoke: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit));
    puts("storage smoke: waiting for completion"); fflush(stdout);
    CHECK(wait_fence(fd,sync.handle));
    if (result[0]!=7 || data[0]!=7 || result[2]!=8 || result[3]!=7) {
        fprintf(stderr,"storage load=%08x backing=%08x atomic=%08x old=%08x\n",
                result[0],data[0],result[2],result[3]); return 1;
    }
    if (getenv("OPENGPU_TEST_STORAGE_FAULT"))
        puts("OPENGPU compute storage ioctl smoke: denied write and recovery PASS");
    else
        puts("OPENGPU compute storage ioctl smoke: store, load and atomic PASS");
    close(fd); return 0;
}
