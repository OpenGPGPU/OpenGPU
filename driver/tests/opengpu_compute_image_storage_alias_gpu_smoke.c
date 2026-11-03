/* SPDX-License-Identifier: MIT */
/* Same-slot storage/image visibility in both directions under fences. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
static uint32_t image(unsigned fn, unsigned pair, unsigned rd, unsigned a, unsigned b)
{ return fn<<26 | 1u<<25 | b<<20 | a<<15 | pair<<12 | rd<<7 | 0x2bu; }
int main(void)
{
    struct buffer shader={0}, kernarg={0}, backing={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_image_resources submit={0};
    uint32_t *code,*result,*words; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if (!(features.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_ALIAS)) {
        fprintf(stderr,"image/storage same-slot alias feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,4096,&backing)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; words=backing.map;
    memset(code,0,128); memset(result,0,64); memset(words,0,4096); words[1]=0x12345678u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,4096));
    code[n++]=0xc100f057u;
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u; /* v2=0 */
    code[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u; /* v3=7 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(4u<<7)|0x57u; /* v4=0 */
    code[n++]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu;
    code[n++]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu; /* storage store */
    code[n++]=0x0330000fu;
    code[n++]=0x3a800513u;
    code[n++]=(23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u;
    code[n++]=image(8,0,0,2,3); code[n++]=image(8,1,0,2,2);
    code[n++]=image(9,0,5,2,2); code[n++]=0x0330000fu;
    code[n++]=0x02006027u|(1u<<15)|(5u<<7); /* image load result */
    code[n++]=(23u<<26)|(1u<<25)|(9u<<15)|(3u<<12)|(7u<<7)|0x57u; /* v7=9 */
    code[n++]=image(8,2,0,7,2); code[n++]=image(8,3,0,2,2);
    code[n++]=image(10,0,0,2,2); code[n++]=0x0330000fu;
    code[n++]=(15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(6u<<7)|0x2bu; /* storage load */
    code[n++]=0x0330000fu; code[n++]=0x00408493u;
    code[n++]=0x02006027u|(9u<<15)|(6u<<7); code[n++]=0x30500073u;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,
        OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    submit.images[0]=(struct drm_opengpu_compute_image_view){.slot=13,
        .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
        .storage_format=14,.image_format=14,.width=4,.height=1,.depth=1,.layers=1,.row_pitch=16};
    puts("image/storage alias: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES,&submit));
    CHECK(wait_fence_timeout(fd,sync.handle,900000));
    if (result[0]!=7 || result[1]!=9 || words[0]!=9 || words[1]!=0x12345678u) {
        fprintf(stderr,"image=%x storage=%x backing=%x neighbor=%x\n",result[0],result[1],words[0],words[1]);return 1;
    }
    puts("OPENGPU image/storage alias smoke: both directions PASS");close(fd);return 0;
}
