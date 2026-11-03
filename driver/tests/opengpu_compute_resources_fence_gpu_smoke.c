/* SPDX-License-Identifier: MIT */
/* Two-warp texture-to-storage execution across per-warp fences. */
#include "opengpu_compute_smoke_util.h"
#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
int main(void)
{
    struct buffer shader={0}, kernarg={0}, backing={0}, texture={0};
    struct drm_opengpu_context context={0}; struct drm_syncobj_create sync={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    struct drm_opengpu_compute_texture_view view={0};
    struct drm_opengpu_compute_resources submit={0};
    uint32_t *code, *result, *data, *pixels; unsigned n=0;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if (!(features.value & OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES)) {
        fprintf(stderr,"combined compute resources feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,128,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,8192,&backing)); CHECK(create_buffer(fd,64,&texture));
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context)); CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; data=backing.map; pixels=texture.map;
    memset(code,0,128); memset(result,0,64); memset(data,0,8192); memset(pixels,0,64);
    pixels[5]=0x12345678u;
    code[n++]=0x00100313u; /* x6 = 1: texture coordinates */
    for (unsigned pair=0; pair<8; ++pair) {
        unsigned reg=pair==0 ? 6 : 0;
        code[n++]=(3u<<25)|(reg<<20)|(reg<<15)|(pair<<12)|0x0bu;
    }
    code[n++]=(5u<<25)|(7u<<7)|0x0bu; /* fetch texture 0 into x7 */
    code[n++]=0x0330000fu; /* order this warp's texture fetch */
    code[n++]=0xc100f057u; /* one active vector lane per warp */
    code[n++]=0x5e003157u; /* v2 = binding 0 */
    code[n++]=0x00641293u; /* x5 = local warp base * 64 */
    code[n++]=0x5e02c257u; /* v4 = x5: distinct storage offsets */
    code[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(4u<<12)|(3u<<7)|0x57u; /* v3 = x7 */
    code[n++]=0x3a01802bu; /* capture v3 */
    code[n++]=0x4241002bu; /* storage write */
    code[n++]=0x00040463u; code[n++]=0x00000013u; /* reconverge */
    code[n++]=0x0330000fu; /* order this warp's storage write */
    code[n++]=0x00000013u; /* keep each warp's storage offset */
    code[n++]=0x3e4102abu; /* load this warp's storage word into v5 */
    code[n++]=0x00241293u; code[n++]=0x005082b3u; /* per-warp kernarg address */
    code[n++]=0x0202e2a7u; /* publish observed value */
    code[n++]=0x0330000fu; code[n++]=0x30500073u;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,128));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,33,&texture,OPENGPU_RESOURCE_COMPUTE_TEXTURE,64));
    CHECK(bind_buffer(fd,context.id,35,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,8192));
    view.slot=33; view.format=14; view.width=4; view.height=4; view.depth=1; view.layers=1; view.level_count=1;
    view.levels[0].width=4; view.levels[0].height=4; view.levels[0].depth=1;
    view.levels[0].layers=1; view.levels[0].row_pitch=16; view.levels[0].block_bytes=4;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=8; submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle; submit.views=(uintptr_t)&view; submit.view_count=1;
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){35,OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    puts("resources fence: submitting two-warp exchange"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES,&submit)); CHECK(wait_fence(fd,sync.handle));
    if (data[0]!=0x12345678u || data[64]!=0x12345678u || result[0]!=0x12345678u || result[4]!=0x12345678u) {
        fprintf(stderr,"resources fence: data=%x,%x observed=%x,%x\n",data[0],data[64],result[0],result[4]); return 1;
    }
    puts("OPENGPU resources fence ioctl smoke: two warps PASS"); close(fd); return 0;
}
