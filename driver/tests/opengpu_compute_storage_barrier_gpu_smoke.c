/* SPDX-License-Identifier: MIT */
/* Two-warp storage publication and fault recovery across workgroup barriers. */
#include <stdlib.h>
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
int main(void)
{
    struct buffer shader={0}, kernarg={0}, backing={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_storage submit={0};
    uint32_t *code, *result, *data;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if (!(features.value & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE)) {
        fprintf(stderr,"compute storage feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,8192,&backing)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; data=backing.map;
    memset(code,0,128); memset(result,0,64); memset(data,0,8192);
    code[0]=0x00641293u; /* x5 = warp-local base * 64 */
    code[1]=0xc100f057u; /* one active vector lane per warp */
    code[2]=0x5e003157u; /* v2 = binding 0 */
    code[3]=0x5e02c257u; /* v4 = x5: distinct storage offsets */
    code[4]=0x5e03b1d7u; /* v3 = 7 */
    code[5]=0x3a01802bu; /* capture v3 */
    code[6]=0x4241002bu; /* store v3 to storage binding v2, offset v4 */
    code[7]=0x00040463u; code[8]=0x00000013u; /* reconverge */
    code[9]=0x0000000bu; /* workgroup barrier */
    code[10]=0x5e003257u; /* v4 = 0 */
    code[11]=0x3e4102abu; /* load warp zero's storage word into v5 */
    code[12]=0x00241293u; /* x5 = warp-local base * 4 */
    code[13]=0x005082b3u; /* x5 += kernarg base */
    code[14]=0x0202e2a7u; /* each warp publishes its observed word */
    code[15]=0x0000000bu; /* second barrier generation */
    code[16]=0x30500073u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=8; submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    if (getenv("OPENGPU_TEST_STORAGE_BARRIER_FAULT")) {
        /* Warp 0's offset is legal, warp 1's offset 256 is outside this view. */
        CHECK(bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
        puts("storage barrier: submitting one-warp bounds fault"); fflush(stdout);
        CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit));
        errno=0;
        if (wait_fence(fd,sync.handle)!=-1 || errno!=EIO) {
            fprintf(stderr,"out-of-range warp did not fail\n"); return 1;
        }
        /* Rebind reports the completed context error once and releases it. */
        errno=0;
        if (bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,8192)!=-1 || errno!=EIO) {
            fprintf(stderr,"first rebind did not report the completed fault\n"); return 1;
        }
        puts("storage barrier: fault retired, retrying"); fflush(stdout);
    }
    CHECK(bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    puts("storage barrier: submitting two-warp exchange"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit)); CHECK(wait_fence(fd,sync.handle));
    if (data[0]!=7 || data[64]!=7 || result[0]!=7 || result[4]!=7) {
        fprintf(stderr,"storage barrier: data=%x,%x observed=%x,%x\n",data[0],data[64],result[0],result[4]); return 1;
    }
    if (getenv("OPENGPU_TEST_STORAGE_BARRIER_FAULT"))
        puts("OPENGPU storage barrier ioctl smoke: fault and recovery PASS");
    else
        puts("OPENGPU storage barrier ioctl smoke: two warps PASS");
    close(fd); return 0;
}
