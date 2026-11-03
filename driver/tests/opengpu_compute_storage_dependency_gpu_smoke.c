/* SPDX-License-Identifier: MIT */
/* Queue a tiny shared-storage handoff across two independent contexts. */
#include "opengpu_compute_smoke_util.h"

#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)

int main(void)
{
    struct buffer shader[2]={0}, kernarg[2]={0}, backing={0};
    struct drm_opengpu_context context[2]={0};
    struct drm_syncobj_create sync[2]={0};
    struct drm_opengpu_compute_storage submit[2]={0};
    struct drm_opengpu_param features={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    uint32_t *code, *producer, *consumer, *data; unsigned i, n;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_PARAM,&features));
    if ((features.value & (OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) !=
        (OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE | OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS)) {
        fprintf(stderr,"compute storage or word atomic driver feature missing\n"); return 1;
    }
    CHECK(create_buffer(fd,64,&backing)); data=backing.map; memset(data,0,64);
    for (i=0;i<2;i++) {
        CHECK(create_buffer(fd,128,&shader[i])); CHECK(create_buffer(fd,64,&kernarg[i]));
        memset(shader[i].map,0,128); memset(kernarg[i].map,0,64);
        CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context[i]));
        CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync[i]));
        CHECK(bind_buffer(fd,context[i].id,1,&shader[i],OPENGPU_RESOURCE_COMPUTE_SHADER,128));
        CHECK(bind_buffer(fd,context[i].id,2,&kernarg[i],OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
        CHECK(bind_buffer(fd,context[i].id,13,&backing,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
        submit[i].launch.context_id=context[i].id;
        submit[i].launch.shader_slot=1; submit[i].launch.kernarg_slot=2;
        submit[i].launch.grid[0]=submit[i].launch.grid[1]=submit[i].launch.grid[2]=1;
        submit[i].launch.local[0]=submit[i].launch.local[1]=submit[i].launch.local[2]=1;
        submit[i].launch.out_syncobj=sync[i].handle;
        submit[i].bindings[0]=(struct drm_opengpu_compute_storage_binding){13,
            OPENGPU_COMPUTE_STORAGE_READ | (i==0 ? OPENGPU_COMPUTE_STORAGE_WRITE : 0)};
    }
    producer=kernarg[0].map; consumer=kernarg[1].map; producer[2]=7;
    code=shader[0].map; n=0;
    code[n++]=0xc100f057u; /* vsetivli zero,1,e32,m1 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u; /* binding 0 */
    code[n++]=(23u<<26)|(1u<<25)|(7u<<15)|(3u<<12)|(3u<<7)|0x57u; /* value 7 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(4u<<7)|0x57u; /* offset 0 */
    code[n++]=(14u<<26)|(1u<<25)|(3u<<15)|0x2bu; /* capture */
    code[n++]=(16u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|0x2bu; /* store */
    code[n++]=(15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(5u<<7)|0x2bu; /* load */
    code[n++]=0x02006027u|(1u<<15)|(5u<<7); /* vse32 v5,(x1) */
    code[n++]=0x00808493u; code[n++]=0x00100513u;
    code[n++]=(10u<<20)|(9u<<15)|(2u<<12)|(11u<<7)|0x2fu; /* amoadd.w */
    code[n++]=0x00b0a623u; code[n++]=0x30500073u;
    code=shader[1].map; n=0;
    code[n++]=0xc100f057u;
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(2u<<7)|0x57u; /* binding 0 */
    code[n++]=(23u<<26)|(1u<<25)|(3u<<12)|(4u<<7)|0x57u; /* offset 0 */
    code[n++]=(15u<<26)|(1u<<25)|(4u<<20)|(2u<<15)|(5u<<7)|0x2bu; /* load */
    code[n++]=0x02006027u|(1u<<15)|(5u<<7); code[n++]=0x30500073u;
    puts("storage handoff: queueing producer and consumer"); fflush(stdout);
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit[0]));
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_COMPUTE_STORAGE,&submit[1]));
    puts("storage handoff: both queued; waiting for consumer"); fflush(stdout);
    CHECK(wait_fence(fd,sync[1].handle)); CHECK(wait_fence(fd,sync[0].handle));
    if (consumer[0]!=7 || data[0]!=7 || producer[0]!=7 || producer[2]!=8 || producer[3]!=7) {
        fprintf(stderr,"consumer=%x backing=%x producer=%x atomic=%x old=%x\n",
                consumer[0],data[0],producer[0],producer[2],producer[3]); return 1;
    }
    puts("OPENGPU compute storage ioctl smoke: cross-context handoff PASS");
    close(fd); return 0;
}
