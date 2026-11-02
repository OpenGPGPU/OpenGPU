/* SPDX-License-Identifier: MIT */
/* Tiny enabled-path compute texture ioctl smoke for an opt-in ARTI model. */
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
    struct buffer shader={0}, kernarg={0}, texture={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_texture_view view={0};
    struct drm_opengpu_compute_textures submit={0};
    uint32_t *code, *pixels, *result; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if ((features.value & (OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) !=
        (OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE | OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) {
        fprintf(stderr,"compute texture or word atomic driver feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,64,&texture)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    puts("texture smoke: resources allocated"); fflush(stdout);
    code=shader.map; pixels=texture.map; result=kernarg.map;
    memset(code,0,128); memset(pixels,0,64); memset(result,0,64);
    pixels[5]=0x12345678u; result[2]=7;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS,
                      &texture,OPENGPU_RESOURCE_COMPUTE_TEXTURE,64));
    puts("texture smoke: resources bound"); fflush(stdout);
    code[n++]=(6u<<25)|(5u<<7)|0x0bu; /* size(unit 0, lod 0) -> x5 */
    code[n++]=0x0050a023u;             /* sw x5,0(x1) */
    code[n++]=0x00100313u;             /* addi x6,x0,1 */
    for (unsigned pair=0; pair<8; ++pair) {
        unsigned reg=pair==0 ? 6 : 0;
        code[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu;
    }
    code[n++]=(5u<<25)|(7u<<7)|0x0bu; /* fetch(unit 0, control 0) -> x7 */
    code[n++]=0x0070a223u;             /* sw x7,4(x1) */
    code[n++]=0x00808493u;             /* addi x9,x1,8 */
    code[n++]=0x00100513u;             /* addi x10,x0,1 */
    code[n++]=(10u<<20)|(9u<<15)|(2u<<12)|(11u<<7)|0x2fu; /* amoadd.w x11,x10,(x9) */
    code[n++]=0x00b0a623u;             /* sw x11,12(x1) */
    code[n++]=0x30500073u;             /* cease */
    view.slot=OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS; view.format=14;       /* R32UI */
    view.width=4; view.height=4; view.depth=1; view.layers=1; view.level_count=1;
    view.levels[0].width=4; view.levels[0].height=4; view.levels[0].depth=1;
    view.levels[0].layers=1; view.levels[0].row_pitch=16; view.levels[0].block_bytes=4;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle; submit.views=(uintptr_t)&view; submit.view_count=1;
    CHECK(bind_buffer(fd,context.id,OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS,
                      &kernarg,OPENGPU_RESOURCE_COMPUTE_TEXTURE,64));
    errno=0;
    if (ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_TEXTURES,&submit)!=-1 || errno!=EINVAL) {
        fprintf(stderr,"writable kernarg alias was accepted\n"); return 1;
    }
    CHECK(bind_buffer(fd,context.id,OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS,
                      &texture,OPENGPU_RESOURCE_COMPUTE_TEXTURE,64));
    puts("texture smoke: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_TEXTURES,&submit));
    puts("texture smoke: waiting for completion"); fflush(stdout);
    CHECK(wait_fence(fd,sync.handle));
    if (result[0]!=4 || result[1]!=0x12345678u || result[2]!=8 || result[3]!=7) {
        fprintf(stderr,"size=%08x texel=%08x atomic=%08x old=%08x\n",
                result[0],result[1],result[2],result[3]); return 1;
    }
    puts("OPENGPU compute texture ioctl smoke: size, fetch and atomic PASS"); close(fd); return 0;
}
