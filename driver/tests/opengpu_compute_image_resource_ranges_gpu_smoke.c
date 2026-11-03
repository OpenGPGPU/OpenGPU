/* SPDX-License-Identifier: MIT */
/* Overlapping texture, storage and image ranges of one local GEM object. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
static uint32_t image(unsigned fn, unsigned pair, unsigned rd, unsigned a, unsigned b)
{ return fn<<26 | 1u<<25 | b<<20 | a<<15 | pair<<12 | rd<<7 | 0x2bu; }
static int bind_range(int fd, uint32_t ctx, uint32_t slot, struct buffer *b,
                      uint32_t type, uint64_t offset, uint64_t size)
{
    struct drm_opengpu_resource r={.context_id=ctx,.slot=slot,.handle=b->handle,
        .type=type,.offset=offset,.size=size};
    return ioctl(fd,DRM_IOCTL_OPENGPU_RESOURCE_BIND,&r);
}
int main(void)
{
    struct buffer shader={0}, kernarg={0}, shared={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_texture_view view={0};
    struct drm_opengpu_compute_image_resources submit={0};
    uint32_t *code,*result,*pixels; unsigned n=0;
    uint64_t required=OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_ALIAS |
        OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_RANGE_ALIAS |
        OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_RANGE_ALIAS;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if ((features.value & required)!=required) { fprintf(stderr,"range alias features missing\n"); return 1; }
    CHECK(create_buffer(fd,256,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,8192,&shared)); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; pixels=shared.map;
    memset(code,0,256); memset(result,0,64); memset(pixels,0,8192);
    pixels[1024+5]=0x12345678u; pixels[1024+6]=0xabcdef01u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,256));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_range(fd,context.id,33,&shared,OPENGPU_RESOURCE_COMPUTE_TEXTURE,4096,4096));
    CHECK(bind_range(fd,context.id,35,&shared,OPENGPU_RESOURCE_COMPUTE_STORAGE,0,8192));
    code[n++]=0x00100313u;
    for (unsigned pair=0;pair<8;++pair) { unsigned reg=pair==0 ? 6 : 0;
        code[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu; }
    code[n++]=(5u<<25)|(7u<<7)|0x0bu; code[n++]=0x0070a023u;
    code[n++]=0xc100f057u;
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u; /* v2=0 */
    code[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u; /* v3=7 */
    code[n++]=0x000011b7u; code[n++]=0x01418193u; /* x3=4116 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<15)|(4u<<12)|(4u<<7)|0x57u; /* v4=x3 */
    code[n++]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu;
    code[n++]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu; code[n++]=0x0330000fu;
    code[n++]=0x3a800513u;
    code[n++]=(23u<<26)|(1u<<25)|(10u<<15)|(4u<<12)|(3u<<7)|0x57u;
    code[n++]=(23u<<26)|(1u<<25)|(1u<<15)|(3u<<12)|(6u<<7)|0x57u;
    code[n++]=image(8,0,0,2,3); code[n++]=image(8,1,0,2,2);
    code[n++]=image(9,0,5,6,6); code[n++]=0x0330000fu;
    code[n++]=0x00408493u; code[n++]=0x02006027u|(9u<<15)|(5u<<7);
    code[n++]=(23u<<26)|(1u<<25)|(9u<<15)|(3u<<12)|(4u<<7)|0x57u;
    code[n++]=image(8,2,0,4,2); code[n++]=image(8,3,0,2,2);
    code[n++]=image(10,0,0,6,6); code[n++]=0x0330000fu;
    for (unsigned pair=0;pair<8;++pair) { unsigned reg=pair==0 ? 6 : 0;
        code[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu; }
    code[n++]=(5u<<25)|(10u<<7)|0x0bu; code[n++]=0x00a0a423u;
    code[n++]=0x30500073u;
    view.slot=33; view.format=14; view.width=4; view.height=4; view.depth=1;
    view.layers=1; view.level_count=1; view.levels[0].width=4; view.levels[0].height=4;
    view.levels[0].depth=1; view.levels[0].layers=1; view.levels[0].row_pitch=16; view.levels[0].block_bytes=4;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle; submit.textures=(uintptr_t)&view; submit.texture_count=1;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){35,
        OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    submit.images[0]=(struct drm_opengpu_compute_image_view){.slot=35,.offset=4096,
        .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
        .storage_format=14,.image_format=14,.width=4,.height=4,.depth=1,.layers=1,.row_pitch=16};
    puts("image resource ranges: submitting"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES,&submit));
    CHECK(wait_fence_timeout(fd,sync.handle,900000));
    if (result[0]!=0x12345678u || result[1]!=7 || result[2]!=9 ||
        pixels[1024+5]!=9 || pixels[1024+6]!=0xabcdef01u) {
        fprintf(stderr,"old=%x image=%x refetch=%x backing=%x guard=%x\n",
                result[0],result[1],result[2],pixels[1024+5],pixels[1024+6]);return 1;
    }
    puts("OPENGPU image resource ranges smoke: three-client exchange PASS"); close(fd); return 0;
}
