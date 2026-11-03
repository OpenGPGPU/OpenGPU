/* SPDX-License-Identifier: MIT */
/* Image and storage views in separate VM windows share one local object. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
static uint32_t image(unsigned fn, unsigned pair, unsigned rd, unsigned a, unsigned b)
{ return fn<<26 | 1u<<25 | b<<20 | a<<15 | pair<<12 | rd<<7 | 0x2bu; }
static uint32_t vecimm(unsigned rd, unsigned value)
{ return 23u<<26 | 1u<<25 | value<<15 | 3u<<12 | rd<<7 | 0x57u; }
static int bind_range(int fd, uint32_t ctx, uint32_t slot, struct buffer *b,
                      uint32_t type, uint64_t offset, uint64_t size)
{
    struct drm_opengpu_resource r={.context_id=ctx,.slot=slot,.handle=b->handle,
        .type=type,.offset=offset,.size=size};
    return ioctl(fd,DRM_IOCTL_OPENGPU_RESOURCE_BIND,&r);
}
int main(void)
{
    struct buffer shader={0}, kernarg={0}, backing={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_image_resources submit={0};
    uint64_t required=OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_RANGE_ALIAS |
        OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BINDING_ALIAS |
        OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_RANGE_ALIAS;
    uint32_t *code,*result,*words; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if ((features.value & required)!=required) { fprintf(stderr,"multiview features missing\n");return 1; }
    CHECK(create_buffer(fd,256,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,8192,&backing)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; words=backing.map;
    memset(code,0,256); memset(result,0,64); memset(words,0,8192); words[1024+6]=0x12345678u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,256));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_range(fd,context.id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,0,8192));
    CHECK(bind_range(fd,context.id,14,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,4096,4096));
    code[n++]=0xc100f057u; code[n++]=vecimm(2,0); code[n++]=vecimm(3,7);
    code[n++]=0x000011b7u; code[n++]=0x01418193u; /* x3=4116 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<15)|(4u<<12)|(4u<<7)|0x57u; /* v4=x3 */
    code[n++]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu;
    code[n++]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu; code[n++]=0x0330000fu;
    code[n++]=vecimm(8,1); code[n++]=0x01400193u; /* x3=20 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<15)|(4u<<12)|(10u<<7)|0x57u;
    code[n++]=(15u<<26)|(1u<<25)|(10u<<20)|(8u<<15)|(11u<<7)|0x2bu;
    code[n++]=0x0330000fu; code[n++]=0x02006027u|(1u<<15)|(11u<<7);
    code[n++]=0x3a800513u;
    code[n++]=(23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u;
    code[n++]=vecimm(6,1); code[n++]=image(8,0,0,2,3); code[n++]=image(8,1,0,2,2);
    code[n++]=image(9,0,5,6,6); code[n++]=0x0330000fu; code[n++]=0x00408493u;
    code[n++]=0x02006027u|(9u<<15)|(5u<<7); code[n++]=vecimm(7,9);
    code[n++]=image(8,0,0,8,3); code[n++]=image(8,1,0,2,2);
    code[n++]=image(8,2,0,7,2); code[n++]=image(8,3,0,2,2);
    code[n++]=image(10,0,0,6,6); code[n++]=0x0330000fu;
    code[n++]=(15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(12u<<7)|0x2bu;
    code[n++]=0x0330000fu; code[n++]=0x00808493u; code[n++]=0x02006027u|(9u<<15)|(12u<<7);
    code[n++]=image(8,0,0,2,3); code[n++]=image(8,1,0,2,2); code[n++]=image(9,0,13,6,6);
    code[n++]=0x0330000fu; code[n++]=0x00c08493u; code[n++]=0x02006027u|(9u<<15)|(13u<<7);
    code[n++]=0x30500073u;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1; submit.launch.out_syncobj=sync.handle;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){13,3};
    submit.bindings[1]=(struct drm_opengpu_compute_storage_binding){14,3};
    submit.images[0]=(struct drm_opengpu_compute_image_view){.slot=13,.offset=4096,
        .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
        .storage_format=14,.image_format=14,.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16};
    submit.images[1]=submit.images[0]; submit.images[1].slot=14; submit.images[1].offset=0;
    puts("multiview object: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES,&submit));
    CHECK(wait_fence_timeout(fd,sync.handle,900000));
    if (result[0]!=7 || result[1]!=7 || result[2]!=9 || result[3]!=9 ||
        words[1024+5]!=9 || words[1024+6]!=0x12345678u) {
        fprintf(stderr,"results=%x,%x,%x,%x backing=%x guard=%x\n",
                result[0],result[1],result[2],result[3],words[1024+5],words[1024+6]);return 1;
    }
    puts("OPENGPU multiview object smoke: image and storage windows PASS");close(fd);return 0;
}
