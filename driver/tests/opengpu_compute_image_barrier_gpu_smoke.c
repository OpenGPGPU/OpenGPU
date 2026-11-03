/* SPDX-License-Identifier: MIT */
/* Two image-writing warps synchronize before reading the producer's texel. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
static uint32_t image(unsigned fn, unsigned pair, unsigned rd, unsigned a, unsigned b)
{ return fn<<26 | 1u<<25 | b<<20 | a<<15 | pair<<12 | rd<<7 | 0x2bu; }
int main(void)
{
    struct buffer shader={0}, kernarg={0}, backing={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_images submit={0};
    uint32_t *code,*result,*pixels; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if (!(features.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BARRIER)) {
        fprintf(stderr,"compute image barrier feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,4096,&backing)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; pixels=backing.map;
    memset(code,0,128); memset(result,0,64); memset(pixels,0,4096); pixels[8]=0x12345678u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,4096));
    code[n++]=0xc100f057u; /* one active lane per warp */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u; /* v2=0 */
    code[n++]=0x3a800513u; /* R32UI, unsigned, 2D, qualified */
    code[n++]=(23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u;
    code[n++]=image(8,0,0,2,3); code[n++]=image(8,1,0,2,2);
    code[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(4u<<7)|0x57u;
    code[n++]=image(8,2,0,4,2); code[n++]=image(8,3,0,2,2);
    code[n++]=image(10,0,0,1,2); /* warp-local lane zero writes texel 0 or 4 */
    code[n++]=0x0000000bu;
    code[n++]=image(9,0,5,2,2); /* both observe texel 0 */
    code[n++]=0x00241293u; code[n++]=0x005082b3u; /* warp-local output address */
    code[n++]=0x0202e2a7u; code[n++]=0x0000000bu; code[n++]=0x30500073u;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=8; submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle;
    submit.views[0]=(struct drm_opengpu_compute_image_view){.slot=13,
        .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
        .storage_format=14,.image_format=14,.width=8,.height=1,.depth=1,.layers=1,.row_pitch=32};
    puts("image barrier: submitting two-warp exchange"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_IMAGES,&submit));
    puts("image barrier: waiting for completion"); fflush(stdout);
    CHECK(wait_fence_timeout(fd,sync.handle,600000));
    if (pixels[0]!=7 || pixels[4]!=7 || pixels[8]!=0x12345678u || result[0]!=7 || result[4]!=7) {
        fprintf(stderr,"image barrier: pixels=%x,%x guard=%x observed=%x,%x\n",
            pixels[0],pixels[4],pixels[8],result[0],result[4]); return 1;
    }
    puts("OPENGPU compute image barrier smoke: two warps PASS");close(fd);return 0;
}
