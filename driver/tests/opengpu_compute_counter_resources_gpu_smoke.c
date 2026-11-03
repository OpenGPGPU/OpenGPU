/* SPDX-License-Identifier: MIT */
/* One-lane Linux/RTL counter, texture, storage and image coexistence. */
#include "opengpu_compute_smoke_util.h"
#include "opengpu_compute_counter_resources_program.h"
#include "../../userspace/opengpu.h"

#define CHECK(x) do { if ((x)<0) { perror(#x); return 1; } } while (0)
#define EXPECT_EINVAL(x) do { \
    errno=0; \
    if ((x)!=-1 || errno!=EINVAL) { \
        fprintf(stderr,"%s was not rejected with EINVAL\n",#x); return 1; \
    } \
} while (0)

static int check_result(const uint32_t *result, const uint32_t *texture,
                        const uint32_t *image, const uint32_t *storage,
                        const uint32_t *counter, uint32_t old, uint32_t current)
{
    if (result[0]!=0x12345678u || result[1]!=7 || result[2]!=9 || result[3]!=old ||
        image[0]!=7 || image[1]!=0x87654321u || storage[0]!=9 ||
        storage[1]!=0xabcdef01u || texture[5]!=0x12345678u ||
        counter[0]!=0xa5a5a5a5u || counter[1]!=current || counter[2]!=0xa5a5a5a5u)
        return 0;
    for (unsigned i=4; i<16; ++i) if (result[i]!=0xa5a5a5a5u) return 0;
    for (unsigned i=3; i<16; ++i) if (counter[i]!=0xa5a5a5a5u) return 0;
    return 1;
}

int main(void)
{
    struct buffer shader={0}, kernarg={0}, texture={0}, image={0}, storage={0}, counter={0};
    struct drm_opengpu_context context={0};
    struct drm_syncobj_create sync={0};
    struct drm_opengpu_compute_texture_view view={0};
    struct drm_opengpu_compute_counter_resources submit={0};
    uint32_t *code,*result,*texels,*pixels,*words,*counters;
    uint64_t features=0;
    const uint64_t required=OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS |
                            OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE;
    int fd=open("/dev/dri/card0",O_RDWR|O_CLOEXEC);
    CHECK(fd); CHECK(opengpu_driver_features(fd,&features));
    if ((features & required)!=required) {
        fprintf(stderr,"counter or combined compute resources unavailable\n"); return 1;
    }
    CHECK(create_buffer(fd,256,&shader)); CHECK(create_buffer(fd,64,&kernarg));
    CHECK(create_buffer(fd,4096,&texture)); CHECK(create_buffer(fd,4096,&image));
    CHECK(create_buffer(fd,4096,&storage)); CHECK(create_buffer(fd,64,&counter));
    CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_CONTEXT_CREATE,&context));
    CHECK(ioctl(fd,DRM_IOCTL_SYNCOBJ_CREATE,&sync));
    code=shader.map; result=kernarg.map; texels=texture.map;
    pixels=image.map; words=storage.map; counters=counter.map;
    memset(code,0,256); memcpy(code,opengpu_counter_resources_program,sizeof(opengpu_counter_resources_program));
    memset(texels,0,4096); memset(pixels,0,4096); memset(words,0,4096);
    for (unsigned i=0;i<16;++i) result[i]=counters[i]=0xa5a5a5a5u;
    texels[5]=0x12345678u; pixels[1]=0x87654321u; words[1]=0xabcdef01u; counters[1]=41;
    CHECK(bind_buffer(fd,context.id,1,&shader,OPENGPU_RESOURCE_COMPUTE_SHADER,256));
    CHECK(bind_buffer(fd,context.id,2,&kernarg,OPENGPU_RESOURCE_COMPUTE_KERNARG,64));
    CHECK(bind_buffer(fd,context.id,3,&texture,OPENGPU_RESOURCE_COMPUTE_TEXTURE,4096));
    CHECK(bind_buffer(fd,context.id,13,&image,OPENGPU_RESOURCE_COMPUTE_STORAGE,4096));
    CHECK(bind_buffer(fd,context.id,14,&storage,OPENGPU_RESOURCE_COMPUTE_STORAGE,4096));
    CHECK(bind_buffer(fd,context.id,15,&counter,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));
    view.slot=3; view.format=14; view.width=4; view.height=4; view.depth=1;
    view.layers=1; view.level_count=1; view.levels[0].width=4; view.levels[0].height=4;
    view.levels[0].depth=1; view.levels[0].layers=1; view.levels[0].row_pitch=16;
    view.levels[0].block_bytes=4;
    submit.launch.context_id=context.id; submit.launch.shader_slot=1; submit.launch.kernarg_slot=2;
    submit.launch.grid[0]=submit.launch.grid[1]=submit.launch.grid[2]=1;
    submit.launch.local[0]=submit.launch.local[1]=submit.launch.local[2]=1;
    submit.launch.out_syncobj=sync.handle;
    submit.textures=(uintptr_t)&view; submit.texture_count=1;
    submit.resource_flags=OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE|OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES;
    submit.counter=(struct drm_opengpu_compute_counter_binding){.offset=4,.slot=15,.count=1};
    submit.bindings[0]=(struct drm_opengpu_compute_storage_binding){14,
        OPENGPU_COMPUTE_STORAGE_READ|OPENGPU_COMPUTE_STORAGE_WRITE};
    submit.images[0]=(struct drm_opengpu_compute_image_view){.slot=13,
        .flags=OPENGPU_COMPUTE_IMAGE_READ|OPENGPU_COMPUTE_IMAGE_WRITE,
        .storage_format=14,.image_format=14,.width=4,.height=1,.depth=1,.layers=1,.row_pitch=16};

    submit.counter.slot=13; EXPECT_EINVAL(opengpu_compute_counter_resources(fd,&submit));
    submit.counter.slot=14; EXPECT_EINVAL(opengpu_compute_counter_resources(fd,&submit));
    submit.counter.slot=15;
    CHECK(bind_buffer(fd,context.id,15,&texture,OPENGPU_RESOURCE_COMPUTE_STORAGE,4096));
    EXPECT_EINVAL(opengpu_compute_counter_resources(fd,&submit));
    CHECK(bind_buffer(fd,context.id,15,&counter,OPENGPU_RESOURCE_COMPUTE_STORAGE,64));

    puts("counter resources: submitting"); fflush(stdout);
    CHECK(opengpu_compute_counter_resources(fd,&submit));
    CHECK(wait_fence(fd,sync.handle));
    if (!check_result(result,texels,pixels,words,counters,41,42)) {
        fprintf(stderr,"counter/resources result mismatch: %x %x %x %x counter=%u\n",
                result[0],result[1],result[2],result[3],counters[1]); return 1;
    }
    code[OPENGPU_COUNTER_RESOURCE_INDEX_WORD] |= 1u<<15;
    CHECK(opengpu_compute_counter_resources(fd,&submit));
    errno=0;
    if (wait_fence(fd,sync.handle)!=-1 || errno!=EIO) {
        fprintf(stderr,"out-of-range counter did not fail its fence\n"); return 1;
    }
    {
        struct drm_opengpu_fault fault={0};
        CHECK(ioctl(fd,DRM_IOCTL_OPENGPU_GET_FAULT,&fault));
        if ((fault.flags & (OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR)) !=
            (OPENGPU_FAULT_VALID|OPENGPU_FAULT_COMPLETION_ERROR) || fault.error>=0 ||
            !check_result(result,texels,pixels,words,counters,41,42)) {
            fprintf(stderr,"counter/resource fault or guard mismatch\n"); return 1;
        }
    }
    code[OPENGPU_COUNTER_RESOURCE_INDEX_WORD]=opengpu_counter_resources_program[OPENGPU_COUNTER_RESOURCE_INDEX_WORD];
    CHECK(opengpu_compute_counter_resources(fd,&submit));
    CHECK(wait_fence(fd,sync.handle));
    if (!check_result(result,texels,pixels,words,counters,42,43)) {
        fprintf(stderr,"counter/resources recovery mismatch\n"); return 1;
    }
    puts("OPENGPU counter resources smoke: texture, storage, image, fault and recovery PASS");
    close(fd); return 0;
}
