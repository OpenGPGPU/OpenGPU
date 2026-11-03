/* SPDX-License-Identifier: MIT */
/* One checked storage atomic add and its old value, through Linux submission. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
static uint32_t storage(unsigned fn,unsigned op,unsigned rd,unsigned a,unsigned b)
{ return fn<<26 | 1u<<25 | b<<20 | a<<15 | op<<12 | rd<<7 | 0x2bu; }
static uint32_t vecimm(unsigned rd,unsigned value)
{ return 23u<<26 | 1u<<25 | value<<15 | 3u<<12 | rd<<7 | 0x57u; }
int main(void)
{
    struct buffer shader={0},kernarg={0},backing={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_storage submit={0};
    uint32_t *code,*result,*words; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if (!(features.value & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS)) {
        fprintf(stderr,"storage atomic feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,4096,&backing)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; words=backing.map;
    memset(code,0,128); memset(result,0,64); memset(words,0,4096);
    words[0]=10; words[1]=0x12345678u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,4096));
    code[n++]=0xc100f057u; code[n++]=vecimm(2,0); code[n++]=vecimm(3,7);
    code[n++]=storage(24,0,0,3,2); code[n++]=storage(25,1,5,2,2);
    code[n++]=0x0330000fu; code[n++]=0x02006027u|(1u<<15)|(5u<<7);
    code[n++]=0x30500073u;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    puts("storage atomic: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit));
    CHECK(wait_fence_timeout(fd,sync.handle,900000));
    if (result[0]!=10 || words[0]!=17 || words[1]!=0x12345678u) {
        fprintf(stderr,"old=%x backing=%x neighbor=%x\n",result[0],words[0],words[1]); return 1;
    }
    puts("OPENGPU storage atomic smoke: add and old value PASS"); close(fd); return 0;
}
