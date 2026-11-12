/* Actual compute feature-query ioctl under a small hardware-capability matrix. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include "opengpu_drm.h"
#include "gpu_abi.h"
#include "opengpu_compute_resource_abi.h"
#include "opengpu_compute_features.h"
struct opengpu_device { struct { bool clip_depth,sampler_nearest,pixel_center,sampler_axis_wrap,viewport,depth_range,constant_blend,compute_texture_transport,compute_storage_transport,compute_image_transport,compute_counter_transport,compute_uniform_transport,compute_indirect_transport; u32 capabilities,shader_config,compute_resource_flags; struct opengpu_compute_local_limits compute_local_limits; struct opengpu_compute_shared_limits compute_shared_limits; } hw; };
struct drm_device { struct opengpu_device *dev; };
struct drm_file { int unused; };
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *d) { return d; }
#include "feature_query_under_test.inc"
int main(void)
{
    struct opengpu_device gpu={0};struct drm_device drm={.dev=&gpu};struct drm_file file={0};
    struct drm_opengpu_param p={.param=OPENGPU_PARAM_DRIVER_FEATURES};
    for (unsigned clip=0; clip<2; ++clip) {
        gpu.hw.clip_depth=clip;
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value & OPENGPU_DRIVER_FEATURE_CLIP_DEPTH)==clip);
    }
    gpu.hw.clip_depth=false;
    for (unsigned nearest=0; nearest<2; ++nearest) {
        gpu.hw.sampler_nearest=nearest;
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value & OPENGPU_DRIVER_FEATURE_SAMPLER_NEAREST)==nearest);
    }
    gpu.hw.sampler_nearest=false;
    for (unsigned center=0; center<2; ++center) {
        gpu.hw.pixel_center=center;
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value & OPENGPU_DRIVER_FEATURE_PIXEL_CENTER)==center);
    }
    gpu.hw.pixel_center=false;
    for (unsigned axis=0; axis<2; ++axis) {
        gpu.hw.sampler_axis_wrap=axis;
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value & OPENGPU_DRIVER_FEATURE_SAMPLER_AXIS_WRAP)==axis);
    }
    gpu.hw.sampler_axis_wrap=false;
    for (unsigned viewport=0; viewport<2; ++viewport) {
        gpu.hw.viewport=viewport;
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value & OPENGPU_DRIVER_FEATURE_VIEWPORT)==viewport);
    }
    gpu.hw.viewport=false;
    for (unsigned range=0; range<2; ++range) {
        gpu.hw.depth_range=range;
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value & OPENGPU_DRIVER_FEATURE_DEPTH_RANGE)==range);
    }
    gpu.hw.depth_range=false;
    for (unsigned constant=0; constant<2; ++constant) {
        gpu.hw.constant_blend=constant;
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value & OPENGPU_DRIVER_FEATURE_CONSTANT_BLEND)==constant);
    }
    gpu.hw.constant_blend=false;
    for(unsigned storage=0;storage<2;storage++)for(unsigned texture=0;texture<2;texture++)
        for(unsigned unified=0;unified<2;unified++)for(unsigned image=0;image<2;image++)
        for(unsigned atomic=0;atomic<2;atomic++) for(unsigned storage_atomic=0;storage_atomic<2;storage_atomic++) {
            gpu.hw.compute_storage_transport=storage;gpu.hw.compute_texture_transport=texture;
            gpu.hw.compute_image_transport=image;
            gpu.hw.compute_resource_flags=(atomic?OPENGPU_COMPUTE_RESOURCE_IMAGE_ATOMICS:0) |
                (storage_atomic?OPENGPU_COMPUTE_RESOURCE_STORAGE_ATOMICS:0);
            gpu.hw.capabilities=unified?GPU_CAP_UNIFIED_COMMANDS:0;
            p.value=~0u;assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0);
            bool expected=storage && texture && unified;
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES)==expected);
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES)==(image && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_RANGE_ALIAS)==(image && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BINDING_ALIAS)==(storage && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS)==(storage && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_ATOMICS)==(image && unified && atomic));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS)==(storage && unified && storage_atomic));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_FENCE)==(image && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BARRIER)==(image && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_ALIAS)==(image && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURED_IMAGES)==(image && texture && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_ALIAS)==(image && texture && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_RANGE_ALIAS)==(image && texture && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_RANGE_ALIAS)==(storage && texture && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE)==(image && storage && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_ALIAS)==(image && storage && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_RANGE_ALIAS)==(image && storage && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE)==(image && storage && texture && unified));
            assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_ALIAS)==expected);
        }
    for (unsigned counter=0; counter<2; ++counter)
        for (unsigned unified=0; unified<2; ++unified)
            for (unsigned protected_vm=0; protected_vm<2; ++protected_vm) {
                gpu.hw.compute_counter_transport=counter;
                gpu.hw.capabilities=(unified?GPU_CAP_UNIFIED_COMMANDS:0) |
                    (protected_vm?GPU_CAP_PROTECTED_VM_STATE:0);
                assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0);
                assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS) ==
                       (counter && unified && protected_vm));
                assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE) ==
                       (counter && unified && protected_vm));
            }
    for (unsigned uniform=0; uniform<2; ++uniform)
        for (unsigned unified=0; unified<2; ++unified)
            for (unsigned protected_vm=0; protected_vm<2; ++protected_vm) {
                gpu.hw.compute_uniform_transport=uniform;
                gpu.hw.capabilities=(unified?GPU_CAP_UNIFIED_COMMANDS:0) |
                    (protected_vm?GPU_CAP_PROTECTED_VM_STATE:0);
                assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0);
                assert(!!(p.value & OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS) ==
                       (uniform && unified && protected_vm));
            }
    for(unsigned indirect=0;indirect<2;indirect++)for(unsigned counter=0;counter<2;counter++)
        for(unsigned unified=0;unified<2;unified++)for(unsigned protected_vm=0;protected_vm<2;protected_vm++) {
            gpu.hw.compute_indirect_transport=indirect;gpu.hw.compute_counter_transport=counter;
            gpu.hw.capabilities=(unified?GPU_CAP_UNIFIED_COMMANDS:0)|(protected_vm?GPU_CAP_PROTECTED_VM_STATE:0);
            assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
            assert(!!(p.value&OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES)==
                (indirect&&counter&&unified&&protected_vm));
        }
    /* Version bits alone, original service bits alone and missing transport
     * or VM ownership are all insufficient. Preserve the unrelated flags. */
    for(unsigned mask=0;mask<256;mask++) {
        bool texture=mask&1, image=mask&2, unified=mask&4, vm=mask&8;
        bool tex_commands=mask&16, img_commands=mask&32;
        bool tex_version=mask&64, img_version=mask&128;
        gpu.hw.compute_texture_transport=texture;gpu.hw.compute_image_transport=image;
        gpu.hw.capabilities=(unified?GPU_CAP_UNIFIED_COMMANDS:0)|(vm?GPU_CAP_PROTECTED_VM_STATE:0);
        gpu.hw.compute_resource_flags=(tex_commands?OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS:0)|
            (img_commands?OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS:0)|
            (tex_version?OPENGPU_COMPUTE_RESOURCE_BUFFER_TEXTURE_V1:0)|
            (img_version?OPENGPU_COMPUTE_RESOURCE_BUFFER_IMAGE_SIZE_V1:0);
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value&OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE)==
            (texture&&unified&&vm&&tex_commands&&tex_version));
        assert(!!(p.value&OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES)==
            (image&&unified&&vm&&img_commands&&img_version));
        assert(!!(p.value&OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE)==(texture&&unified));
        assert(!!(p.value&OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES)==(image&&unified));
    }
    for (unsigned mask=0; mask<16; ++mask) {
        bool storage=mask&1, image=mask&2, unified=mask&4, vm=mask&8;
        gpu.hw.compute_storage_transport=storage;
        gpu.hw.compute_image_transport=image;
        gpu.hw.capabilities=(unified?GPU_CAP_UNIFIED_COMMANDS:0)|(vm?GPU_CAP_PROTECTED_VM_STATE:0);
        assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
        assert(!!(p.value&OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS)==
            (storage&&image&&unified&&vm));
        assert(!!(p.value&OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE)==
            (storage&&image&&unified));
    }
    gpu.hw.compute_local_limits=(struct opengpu_compute_local_limits){128,128,true};
    gpu.hw.capabilities=GPU_CAP_UNIFIED_COMMANDS;
    p.param=OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS;
    assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0 && p.value==128);
    p.param=OPENGPU_PARAM_COMPUTE_LOCAL_AXIS;
    assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0 && p.value==128);
    gpu.hw.compute_local_limits=(struct opengpu_compute_local_limits){8,8,true};
    assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0 && p.value==8);
    gpu.hw.compute_local_limits.discovered=false;
    assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0 && p.value==0);
    gpu.hw.compute_local_limits.discovered=true;
    gpu.hw.capabilities=0;
    assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0 && p.value==0);
    p.param=OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS;
    assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==0 && p.value==0);
    /* Discovery, rather than any graphics lane/warp configuration, supplies
     * the entire shared range. Unavailable tuples are uniformly zero. */
    for (unsigned unified=0; unified<2; ++unified)
        for (unsigned discovered=0; discovered<2; ++discovered) {
            const u32 params[] = {OPENGPU_PARAM_COMPUTE_SHARED_BASE,
                OPENGPU_PARAM_COMPUTE_SHARED_BYTES, OPENGPU_PARAM_COMPUTE_SHARED_LANES};
            const u32 values[] = {0x10000000, 16384, 16};
            gpu.hw.capabilities=unified ? GPU_CAP_UNIFIED_COMMANDS : 0;
            gpu.hw.compute_shared_limits=(struct opengpu_compute_shared_limits){
                values[0], values[1], values[2], discovered};
            for (unsigned i=0; i<3; ++i) {
                p.param=params[i]; p.value=~0ull;
                assert(!opengpu_compute_get_param_ioctl(&drm,&p,&file));
                assert(p.value==(unified && discovered ? values[i] : 0));
            }
        }
    p.pad=1;assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==-EINVAL);
    p.pad=0;p.param=0xffffffffu;assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==-EINVAL);
    return 0;
}
