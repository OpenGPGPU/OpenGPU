/* SPDX-License-Identifier: MIT */
/* Two virtual resource windows over one local GEM buffer. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
int main(void)
{
    struct buffer shader={0}, kernarg={0}, shared={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_texture_view view={0};
    struct drm_opengpu_compute_resources submit={0};
    uint32_t *code, *result, *pixels; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd);CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if ((features.value & (OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_ALIAS)) !=
        (OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES |
         OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_ALIAS)) {
        fprintf(stderr,"combined compute resources or alias feature missing\n");return 1;
    }
    CHECK(create_buffer(fd,128,&shader));CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,8192,&shared));CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map;result=kernarg.map;pixels=shared.map;
    memset(code,0,128);memset(result,0,64);memset(pixels,0,8192);pixels[5]=0x12345678u;
    code[n++]=0x00100313u; /* x6=1, texture coordinates (1,1) */
    for(unsigned pair=0;pair<8;++pair){unsigned reg=pair==0?6:0;
        code[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu;}
    code[n++]=(5u<<25)|(7u<<7)|0x0bu; /* old texel into x7 */
    code[n++]=0x0070a023u; /* publish old texel */
    code[n++]=0x0000000bu; /* both warps observed the old value */
    code[n++]=0xc100f057u; /* one vector lane per warp */
    code[n++]=0x5e003157u; /* v2=binding 0 */
    code[n++]=0x5e03b1d7u; /* v3=7 */
    code[n++]=0x01400193u; /* x3=20, pixel (1,1) */
    code[n++]=0x5e01c257u; /* v4=x3 */
    code[n++]=0x3a01802bu;code[n++]=0x4241002bu; /* capture and write */
    code[n++]=0x0330000fu;code[n++]=0x0000000bu; /* fence and workgroup barrier */
    for(unsigned pair=0;pair<8;++pair){unsigned reg=pair==0?6:0;
        code[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu;}
    code[n++]=(5u<<25)|(10u<<7)|0x0bu; /* refetch into x10 */
    code[n++]=0x00a0a223u;code[n++]=0x30500073u;
    if(n!=32){fprintf(stderr,"unexpected shader length %u\n",n);return 1;}
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,33,&shared,OPENGPU_RESOURCE_COMPUTE_TEXTURE,8192));
    CHECK(bind_buffer(fd,context.id,35,&shared,OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    view.slot=33;view.format=14;view.width=4;view.height=4;view.depth=1;view.layers=1;view.level_count=1;
    view.levels[0].width=4;view.levels[0].height=4;view.levels[0].depth=1;
    view.levels[0].layers=1;view.levels[0].row_pitch=16;view.levels[0].block_bytes=4;
    submit.launch.context_id=context.id;submit.launch.shader_slot=1;submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=8;submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle;submit.views=(uintptr_t)&view;submit.view_count=1;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){35,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    puts("resources alias: submitting two-warp exchange");fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES,&submit));CHECK(wait_fence_timeout(fd,sync.handle,600000));
    if(result[0]!=0x12345678u || result[1]!=7 || pixels[5]!=7) {
        fprintf(stderr,"resources alias: old=%x new=%x backing=%x\n",result[0],result[1],pixels[5]);return 1;
    }
    puts("OPENGPU resources alias ioctl smoke: two warps PASS");close(fd);return 0;
}
