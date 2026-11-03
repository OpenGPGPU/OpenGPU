/* Actual compute feature-query ioctl under a small hardware-capability matrix. */
#include <assert.h>
#include <errno.h>
#include <stdbool.h>
#include <stdint.h>
#include "opengpu_drm.h"
#include "gpu_abi.h"
#include "opengpu_compute_resource_abi.h"
#include "opengpu_compute_features.h"
struct opengpu_device { struct { bool compute_texture_transport,compute_storage_transport,compute_image_transport,compute_counter_transport; u32 capabilities,shader_config,compute_resource_flags; struct opengpu_compute_local_limits compute_local_limits; } hw; };
struct drm_device { struct opengpu_device *dev; };
struct drm_file { int unused; };
static struct opengpu_device *dev_get_drvdata(struct opengpu_device *d) { return d; }
#include "feature_query_under_test.inc"
int main(void)
{
    struct opengpu_device gpu={0};struct drm_device drm={.dev=&gpu};struct drm_file file={0};
    struct drm_opengpu_param p={.param=OPENGPU_PARAM_DRIVER_FEATURES};
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
    p.pad=1;assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==-EINVAL);
    p.pad=0;p.param=0xffffffffu;assert(opengpu_compute_get_param_ioctl(&drm,&p,&file)==-EINVAL);
    return 0;
}
