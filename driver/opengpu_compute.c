// SPDX-License-Identifier: GPL-2.0
/* Contexts, resource validation and immutable render/compute job preparation. */
#include <linux/dma-resv.h>
#include <linux/fs.h>
#include <linux/idr.h>
#include <linux/module.h>
#include <linux/overflow.h>
#include <linux/slab.h>
#include <linux/stddef.h>
#include <linux/uaccess.h>
#include <linux/vmalloc.h>

#include <drm/drm_device.h>
#include <drm/drm_exec.h>
#include <drm/drm_file.h>
#include <drm/drm_gem.h>
#include <drm/drm_gem_dma_helper.h>
#include <drm/drm_syncobj.h>
#include <drm/gpu_scheduler.h>

#include "opengpu_depth_validator.h"
#include "opengpu_render_extent.h"
#include "opengpu_compute_resource_abi.h"
#include "opengpu_compute_texture_bound_view.h"
#include "opengpu_compute_uniform_records.h"
#include "opengpu_compute_counter_source.h"
#include "opengpu_compute_indirect_source.h"
#include "opengpu_compute_image_descriptor.h"
#include "opengpu_storage_descriptor.h"
#include "opengpu_storage_slot.h"
#include "opengpu_device.h"
#include "opengpu_scheduler.h"
#include "opengpu_drm.h"
#include "opengpu_cache_range.h"
#include "opengpu_drm_device.h"
#include "opengpu_kernarg_va.h"
#include "opengpu_resolve_validator.h"
#include "opengpu_shader_validator.h"
#include "opengpu_staging_validator.h"
#include "opengpu_shader_resources.h"
#include "opengpu_shader_builder.h"

static const struct file_operations opengpu_compute_fops;
static u32 opengpu_fragment_batch_capacity(struct opengpu_device *gpu);

static void opengpu_fill_test_command(struct opengpu_device *gpu)
{
    struct gpu_draw_record *r = gpu->compute.cmd.cpu;
    s32 clip_x_extent = (s32)(0x20000u * 16u / gpu->width);
    s32 clip_y_extent = (s32)(0x20000u * 16u / gpu->height);

    BUILD_BUG_ON(sizeof(*r) != GPU_DRAW_WORDS * sizeof(u32));

    memset(gpu->compute.cmd.cpu, 0, sizeof(*r));
    /* The explicit legacy test draws a 16x16 triangle even when scanout is
     * larger. Avoid clearing the entire depth plane through simulated cycles;
     * the CPU initializes it before the first draw instead. Small modes
     * continue to exercise the hardware clear engine. D24S8: stencil 0
     * under the far depth. The test caller holds the compute lock. */
    if (gpu->width > 16 || gpu->height > 16 ||
        opengpu_hw_clear(gpu,
                         lower_32_bits(gpu->compute.depth.dma),
                         (u32)gpu->compute.depth.size, 0x00ffffffu)) {
        u32 *word = gpu->compute.depth.cpu;
        size_t n;

        for (n = 0; n < gpu->compute.depth.size / sizeof(u32); n++)
            word[n] = 0x00ffffffu;
    }
    if (gpu->compute.kernarg.cpu)
        memset(gpu->compute.kernarg.cpu, 0, gpu->compute.kernarg.size);

    r->v0[0] = -0x10000; r->v0[1] = -0x10000;
    r->v0[2] = 0; r->v0[3] = 0x10000;
    r->v1[0] = -0x10000 + clip_x_extent; r->v1[1] = -0x10000;
    r->v1[2] = 0; r->v1[3] = 0x10000;
    r->v2[0] = -0x10000; r->v2[1] = -0x10000 + clip_y_extent;
    r->v2[2] = 0; r->v2[3] = 0x10000;

    r->c0[0] = 255; r->c0[1] = 0; r->c0[2] = 0;
    r->c1[0] = 255; r->c1[1] = 0; r->c1[2] = 0;
    r->c2[0] = 255; r->c2[1] = 0; r->c2[2] = 0;
    r->d0 = 0x10;
    r->d1 = 0x10;
    r->d2 = 0x10;
    /* Pass-through blend config: exercises the word-35 decode on every
     * self-test boot without changing the rendered colour. */
    r->blend_config = GPU_BLEND_CONFIG(GPU_BLEND_FACTOR_ONE,
                                       GPU_BLEND_FACTOR_ZERO,
                                       GPU_BLEND_EQ_ADD);
    if (gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE) {
        r->shader_pc = lower_32_bits(gpu->compute.shader.dma);
        r->kernarg = lower_32_bits(gpu->compute.kernarg.dma);
        r->kernarg_bank_stride = gpu->compute.kernarg.size /
            opengpu_shader_resource_banks(gpu->hw.shader_config);
    }
}

static int opengpu_init_test_shader(struct opengpu_device *gpu)
{
    u32 batch = opengpu_fragment_batch_capacity(gpu);
    u32 stride, vector_length;
    u32 *program;
    int ret;

    if (!batch)
        return -EINVAL;
    stride = 4 * batch;
    vector_length = gpu->hw.shader_config ?
        opengpu_shader_resource_lanes(gpu->hw.shader_config) : min(batch, 4u);
    ret = opengpu_buffer_alloc(gpu, &gpu->compute.shader, 64);
    if (ret)
        return ret;
    ret = opengpu_buffer_alloc(gpu, &gpu->compute.kernarg,
                               opengpu_shader_resource_banks(gpu->hw.shader_config) *
                               ALIGN(GPU_KERNARG_UNIFORM_OFF(stride),
                                     GPU_KERNARG_BANK_ALIGN));
    if (ret) {
        opengpu_buffer_free(gpu, &gpu->compute.shader);
        return ret;
    }

    program = gpu->compute.shader.cpu;
    memset(program, 0, gpu->compute.shader.size);
    if ((gpu->hw.capabilities & GPU_CAP_RUNTIME_SHADER_ABI) ?
        !opengpu_fragment_runtime_passthrough_shader(program, 16) :
        !opengpu_fragment_passthrough_shader(program, 16, batch, vector_length)) {
        opengpu_buffer_free(gpu, &gpu->compute.kernarg);
        opengpu_buffer_free(gpu, &gpu->compute.shader);
        return -EINVAL;
    }
    return 0;
}

static int opengpu_submit_test(struct opengpu_device *gpu)
{
    struct opengpu_job job = {
        .cmd = gpu->compute.cmd.dma,
        .cmd_count = 1,
        .color = gpu->compute.color.dma,
        .depth = gpu->compute.depth.dma,
        .stride = gpu->stride,
        .depth_test = true,
        .depth_func = GPU_DEPTH_FUNC_LESS,
        .depth_write = true,
        .cull_mode = GPU_CULL_NONE,
    };
    struct opengpu_buffer descriptor = {0};
    struct opengpu_vm *vm = NULL;
    bool vm_ready = false;
    u32 descriptor_va;
    struct dma_fence *fence = NULL;
    int ret;

    if (gpu->hw.capabilities & GPU_CAP_VERTEX_CORE)
        return -EOPNOTSUPP;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_RENDER))
        return -EOPNOTSUPP;
    opengpu_fill_test_command(gpu);
    /* Context ASIDs have no identity map. Map every probe resource the same
     * way scheduled render jobs do (code, kernarg, command, FB, descriptor),
     * then submit by VA — physical addresses fault once the private VM is
     * active. */
    if (gpu->mmu.enabled && (gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE)) {
        struct opengpu_kernarg_va_plan plan;
        struct gpu_draw_record *record = gpu->compute.cmd.cpu;
        size_t fb_bytes = (size_t)gpu->stride * gpu->height;

        vm = kzalloc(sizeof(*vm), GFP_KERNEL);
        if (!vm)
            return -ENOMEM;
        ret = opengpu_mmu_vm_create(gpu, vm);
        if (ret)
            goto out_vm;
        vm_ready = true;

        ret = opengpu_code_va_plan(gpu->compute.shader.dma,
                                   gpu->compute.shader.size, 1,
                                   OPENGPU_MMU_PAGE_SIZE, &plan);
        if (ret)
            goto out_vm;
        ret = opengpu_mmu_vm_map(gpu, vm, (dma_addr_t)plan.va_page,
                                 (dma_addr_t)plan.pa_page, (size_t)plan.span,
                                 OPENGPU_MMU_POLICY_CACHED);
        if (ret)
            goto out_vm;
        record->shader_pc += (u32)plan.va -
            lower_32_bits(gpu->compute.shader.dma);

        if (gpu->compute.kernarg.dma && gpu->compute.kernarg.size) {
            ret = opengpu_kernarg_va_plan(gpu->compute.kernarg.dma,
                                          gpu->compute.kernarg.size, 1,
                                          OPENGPU_MMU_PAGE_SIZE, &plan);
            if (ret)
                goto out_vm;
            ret = opengpu_mmu_vm_map(gpu, vm, (dma_addr_t)plan.va_page,
                                     (dma_addr_t)plan.pa_page,
                                     (size_t)plan.span,
                                     OPENGPU_MMU_POLICY_UNCACHED);
            if (ret)
                goto out_vm;
            record->kernarg = (u32)plan.va;
        }

        ret = opengpu_command_va_plan(gpu->compute.cmd.dma,
                                      gpu->compute.cmd.size, 1,
                                      OPENGPU_MMU_PAGE_SIZE, &plan);
        if (ret)
            goto out_vm;
        ret = opengpu_mmu_vm_map(gpu, vm, (dma_addr_t)plan.va_page,
                                 (dma_addr_t)plan.pa_page, (size_t)plan.span,
                                 OPENGPU_MMU_POLICY_UNCACHED);
        if (ret)
            goto out_vm;
        job.cmd = (u32)plan.va;

        if (job.color && fb_bytes) {
            ret = opengpu_framebuffer_va_plan(job.color, fb_bytes, 1,
                                              OPENGPU_MMU_PAGE_SIZE, &plan);
            if (ret)
                goto out_vm;
            ret = opengpu_mmu_vm_map(gpu, vm, (dma_addr_t)plan.va_page,
                                     (dma_addr_t)plan.pa_page,
                                     (size_t)plan.span,
                                     OPENGPU_MMU_POLICY_CACHED);
            if (ret)
                goto out_vm;
            job.color = (u32)plan.va;
        }
        if (job.depth && fb_bytes) {
            ret = opengpu_framebuffer_va_plan(job.depth, fb_bytes, 2,
                                              OPENGPU_MMU_PAGE_SIZE, &plan);
            if (ret)
                goto out_vm;
            ret = opengpu_mmu_vm_map(gpu, vm, (dma_addr_t)plan.va_page,
                                     (dma_addr_t)plan.pa_page,
                                     (size_t)plan.span,
                                     OPENGPU_MMU_POLICY_CACHED);
            if (ret)
                goto out_vm;
            job.depth = (u32)plan.va;
        }
        dma_wmb();
    }
    ret = opengpu_buffer_alloc(gpu, &descriptor, 64);
    if (ret)
        goto out_vm;
    descriptor_va = lower_32_bits(descriptor.dma);
    if (vm_ready) {
        struct opengpu_kernarg_va_plan plan;

        ret = opengpu_render_va_plan(descriptor.dma, descriptor.size, 1,
                                     OPENGPU_MMU_PAGE_SIZE, &plan);
        if (ret)
            goto out_descriptor;
        ret = opengpu_mmu_vm_map(gpu, vm, (dma_addr_t)plan.va_page,
                                 (dma_addr_t)plan.pa_page, (size_t)plan.span,
                                 OPENGPU_MMU_POLICY_UNCACHED);
        if (ret)
            goto out_descriptor;
        descriptor_va = (u32)plan.va;
    }
    ret = opengpu_hw_render_async(
        gpu, &job, descriptor.cpu, descriptor_va,
        (u32)descriptor.size, NULL, 0, false, 0, 0, 0, NULL,
        vm_ready ? vm : NULL, &fence);
    if (ret)
        goto out_descriptor;
    ret = opengpu_hw_fence_wait(gpu, fence, OPENGPU_DRAW_WAIT_MS + 100,
                                false);
    if (ret < 0)
        opengpu_hw_abort(gpu, ret);
    dma_fence_put(fence);
out_descriptor:
out_vm:
    if (vm_ready) {
        if (opengpu_mmu_vm_destroy(gpu, vm))
            return ret ? ret : -EIO; /* Keep the linked VM container live. */
    }
    opengpu_buffer_free(gpu, &descriptor);
    kfree(vm);
    return ret;
}

static int opengpu_wait_fence(struct opengpu_device *gpu,
                              struct dma_fence **fence, bool interruptible);

static int opengpu_wait_fence(struct opengpu_device *gpu,
                              struct dma_fence **fence, bool interruptible)
{
    int ret;

    if (!*fence)
        return 0;
    ret = opengpu_hw_fence_wait(gpu, *fence, OPENGPU_DRAW_WAIT_MS + 100,
                                interruptible);
    /* An interrupted or timed-out quiesce does not retire the job. Keep
     * the fence so a later bind or unbind cannot modify its address space.
     * Teardown is the only noninterruptible caller and releases its reference. */
    if (!interruptible || dma_fence_is_signaled(*fence)) {
        dma_fence_put(*fence);
        *fence = NULL;
    }
    return ret;
}

/* Jobs hold the context until their free-job callback has revoked private VM
 * windows. A finished fence alone does not mean that callback has run. */
static void opengpu_context_release(struct kref *ref)
{
    struct opengpu_render_context *context =
        container_of(ref, struct opengpu_render_context, refcount);
    u32 i;

    if (opengpu_mmu_vm_destroy(context->gpu, &context->vm))
        return; /* Quarantine the container and bindings with its live VM. */
    for (i = 0; i < ARRAY_SIZE(context->bindings); i++) {
        if (!context->bindings[i])
            continue;
        drm_gem_object_put(context->bindings[i]->object);
        kfree(context->bindings[i]);
    }
    kfree(context);
}

void opengpu_context_get(struct opengpu_render_context *context)
{
    kref_get(&context->refcount);
}

void opengpu_context_put(struct opengpu_render_context *context)
{
    kref_put(&context->refcount, opengpu_context_release);
}

static void opengpu_context_free(struct opengpu_file *render_file,
                                 struct opengpu_render_context *context)
{
    drm_sched_entity_destroy(&context->entity);
    opengpu_wait_fence(render_file->gpu, &context->last_fence, false);
    /* Queued jobs killed by the scheduler can retire on another workqueue,
     * even after the bounded wait expires. Their references keep this VM and
     * its bindings alive until the final job has finished cleanup. */
    opengpu_context_put(context);
}

static struct opengpu_render_context *
opengpu_context_lookup(struct opengpu_file *render_file, u32 id)
{
    if (!id)
        return NULL;
    return idr_find(&render_file->contexts, id);
}

static int opengpu_context_quiesce(struct opengpu_file *render_file,
                                   struct opengpu_render_context *context)
{
    int ret;

    ret = opengpu_wait_fence(render_file->gpu, &context->last_fence, true);
    return ret;
}

int opengpu_compute_drm_open(struct drm_device *drm, struct drm_file *file)
{
    struct opengpu_file *render_file;

    render_file = kzalloc(sizeof(*render_file), GFP_KERNEL);
    if (!render_file)
        return -ENOMEM;
    render_file->gpu = dev_get_drvdata(drm->dev);
    mutex_init(&render_file->lock);
    idr_init(&render_file->contexts);
    file->driver_priv = render_file;
    return 0;
}

int opengpu_compute_get_param_ioctl(struct drm_device *drm, void *data,
                                    struct drm_file *file)
{
    struct drm_opengpu_param *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);

    if (args->pad)
        return -EINVAL;
    switch (args->param) {
    case OPENGPU_PARAM_CAPABILITIES:
        args->value = gpu->hw.capabilities;
        break;
    case OPENGPU_PARAM_SHADER_CONFIG:
        args->value = gpu->hw.shader_config;
        break;
    case OPENGPU_PARAM_COMPUTE_LOCAL_ITEMS:
        args->value = (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            gpu->hw.compute_local_limits.discovered ?
            gpu->hw.compute_local_limits.items : 0;
        break;
    case OPENGPU_PARAM_COMPUTE_LOCAL_AXIS:
        args->value = (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            gpu->hw.compute_local_limits.discovered ?
            gpu->hw.compute_local_limits.axis : 0;
        break;
    case OPENGPU_PARAM_COMPUTE_SHARED_BASE:
        args->value = (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            gpu->hw.compute_shared_limits.discovered ?
            gpu->hw.compute_shared_limits.base : 0;
        break;
    case OPENGPU_PARAM_COMPUTE_SHARED_BYTES:
        args->value = (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            gpu->hw.compute_shared_limits.discovered ?
            gpu->hw.compute_shared_limits.bytes : 0;
        break;
    case OPENGPU_PARAM_COMPUTE_SHARED_LANES:
        args->value = (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            gpu->hw.compute_shared_limits.discovered ?
            gpu->hw.compute_shared_limits.lanes : 0;
        break;
    case OPENGPU_PARAM_DRIVER_FEATURES:
        args->value = OPENGPU_DRIVER_FEATURE_DISCARD_PRIVATE_DEPTH |
                      OPENGPU_DRIVER_FEATURE_TRANSIENT_FRAGMENT_STAGING;
        if (gpu->hw.clip_depth)
            args->value |= OPENGPU_DRIVER_FEATURE_CLIP_DEPTH;
        if (gpu->hw.sampler_nearest)
            args->value |= OPENGPU_DRIVER_FEATURE_SAMPLER_NEAREST;
        if (gpu->hw.pixel_center)
            args->value |= OPENGPU_DRIVER_FEATURE_PIXEL_CENTER;
        if (gpu->hw.sampler_axis_wrap)
            args->value |= OPENGPU_DRIVER_FEATURE_SAMPLER_AXIS_WRAP;
        if (gpu->hw.sampler_biased_clamp)
            args->value |= OPENGPU_DRIVER_FEATURE_BIASED_CLAMP;
        if (gpu->hw.sampler_mirrored_repeat)
            args->value |= OPENGPU_DRIVER_FEATURE_MIRRORED_REPEAT;
        if (gpu->hw.viewport)
            args->value |= OPENGPU_DRIVER_FEATURE_VIEWPORT;
        if (gpu->hw.depth_range)
            args->value |= OPENGPU_DRIVER_FEATURE_DEPTH_RANGE;
        if (gpu->hw.constant_blend)
            args->value |= OPENGPU_DRIVER_FEATURE_CONSTANT_BLEND;
        if (gpu->hw.compute_resource_flags & OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS)
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_WORD_ATOMICS;
        if (gpu->hw.compute_texture_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE;
        if (gpu->hw.compute_storage_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BINDING_ALIAS |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS;
        if (gpu->hw.compute_storage_transport && gpu->hw.compute_texture_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_ALIAS |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE_STORAGE_RANGE_ALIAS;
        if (gpu->hw.compute_image_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_FENCE |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BARRIER |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_ALIAS |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_VIEW_RANGE_ALIAS;
        if (gpu->hw.compute_image_transport && gpu->hw.compute_texture_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURED_IMAGES |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_ALIAS |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_TEXTURE_RANGE_ALIAS;
        if (gpu->hw.compute_image_transport && gpu->hw.compute_storage_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS)) {
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_ALIAS |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_RANGE_ALIAS;
            if (gpu->hw.compute_texture_transport)
                args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE;
        }
        if (gpu->hw.compute_image_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            (gpu->hw.compute_resource_flags & OPENGPU_COMPUTE_RESOURCE_IMAGE_ATOMICS))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_ATOMICS;
        if (gpu->hw.compute_storage_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            (gpu->hw.compute_resource_flags & OPENGPU_COMPUTE_RESOURCE_STORAGE_ATOMICS))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_ATOMICS;
        if (gpu->hw.compute_counter_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            (gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS |
                           OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE;
        if (gpu->hw.compute_indirect_transport && gpu->hw.compute_counter_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            (gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES;
        if (gpu->hw.compute_uniform_transport &&
            (gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) &&
            (gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS;
        if (opengpu_compute_buffer_texture_supported(gpu->hw.compute_resource_flags,
                gpu->hw.capabilities, gpu->hw.compute_texture_transport))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE;
        if (opengpu_compute_buffer_images_supported(gpu->hw.compute_resource_flags,
                gpu->hw.capabilities, gpu->hw.compute_image_transport))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES;
        if (opengpu_compute_image_backings_supported(gpu->hw.capabilities,
                gpu->hw.compute_storage_transport, gpu->hw.compute_image_transport))
            args->value |= OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS;
        break;
    default:
        return -EINVAL;
    }
    return 0;
}

int opengpu_compute_get_fault_ioctl(struct drm_device *drm, void *data,
                                    struct drm_file *file)
{
    struct drm_opengpu_fault *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_fault_snapshot fault;

    if (args->pad[0] || args->pad[1])
        return -EINVAL;
    opengpu_hw_get_fault(gpu, &fault);
    memset(args, 0, sizeof(*args));
    args->sequence = fault.sequence;
    args->bytes_processed = fault.bytes_processed;
    args->expected_bytes = fault.expected_bytes;
    args->error = fault.error;
    args->command_id = fault.command_id;
    args->opcode = fault.opcode;
    args->status = fault.status;
    args->flags = fault.flags;
    args->expected_command_id = fault.expected_command_id;
    args->expected_opcode = fault.expected_opcode;
    return 0;
}

/* Native query engines are not yet connected to Linux submission. */
int opengpu_compute_query_ioctl(struct drm_device *drm, void *data,
                                struct drm_file *file)
{
    (void)drm;
    (void)data;
    (void)file;
    return -EOPNOTSUPP;
}

void opengpu_compute_drm_postclose(struct drm_device *drm,
                                   struct drm_file *file)
{
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    int id = 0;

    if (!render_file)
        return;
    while ((context = idr_get_next(&render_file->contexts, &id))) {
        idr_remove(&render_file->contexts, id);
        opengpu_context_free(render_file, context);
    }
    idr_destroy(&render_file->contexts);
    kfree(render_file);
    file->driver_priv = NULL;
}

int opengpu_compute_context_create_ioctl(struct drm_device *drm, void *data,
                                         struct drm_file *file)
{
    struct drm_opengpu_context *args = data;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_device *gpu = render_file->gpu;
    struct opengpu_render_context *context;
    int ret;

    if (args->flags || args->id)
        return -EINVAL;
    context = kzalloc(sizeof(*context), GFP_KERNEL);
    if (!context)
        return -ENOMEM;
    kref_init(&context->refcount);
    context->gpu = gpu;
    {
        struct drm_gpu_scheduler *scheduler = &gpu->compute.scheduler;

        ret = drm_sched_entity_init(&context->entity,
                                    DRM_SCHED_PRIORITY_NORMAL,
                                    &scheduler, 1, NULL);
    }
    if (ret)
        goto err_context;

    /* Every context owns an address space.  Without the MMU control path
     * (Bare) the VM stays disabled and jobs use the ASID-0 identity map. */
    ret = opengpu_mmu_vm_create(gpu, &context->vm);
    if (ret && ret != -EOPNOTSUPP)
        goto err_entity;

    mutex_lock(&render_file->lock);
    ret = idr_alloc(&render_file->contexts, context, 1, 0, GFP_KERNEL);
    if (ret >= 0) {
        context->id = ret;
        args->id = ret;
        ret = 0;
    }
    mutex_unlock(&render_file->lock);
    if (!ret)
        return 0;

    if (opengpu_mmu_vm_destroy(gpu, &context->vm)) {
        drm_sched_entity_destroy(&context->entity);
        return ret; /* The quarantined VM still links this context. */
    }
err_entity:
    drm_sched_entity_destroy(&context->entity);
err_context:
    kfree(context);
    return ret;
}

int opengpu_compute_context_destroy_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file)
{
    struct drm_opengpu_context *args = data;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;

    if (args->flags || !args->id)
        return -EINVAL;
    mutex_lock(&render_file->lock);
    context = idr_remove(&render_file->contexts, args->id);
    mutex_unlock(&render_file->lock);
    if (!context)
        return -ENOENT;
    opengpu_context_free(render_file, context);
    return 0;
}

static bool opengpu_is_power_of_two(u32 value)
{
    return value && !(value & (value - 1));
}

/* Drop the shared-L2 lines covering a resource binding.  The binding is the
 * driver's upload boundary (the caller has finished writing the buffer before
 * it binds it), so one invalidate per bind is enough; the context is quiesced
 * first so no job is in flight and the commands order cleanly. */
static int opengpu_binding_invalidate(
    struct opengpu_device *gpu,
    const struct opengpu_resource_binding *binding)
{
    struct opengpu_cache_range range;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS) || !binding->size)
        return 0;
    ret = opengpu_cache_invalidate_range((u64)binding->dma,
                                          binding->size, &range);
    if (ret <= 0)
        return ret < 0 ? -ERANGE : 0;
    return opengpu_hw_invalidate_sync(gpu, range.address, range.bytes,
                                      true);
}

/* Map a binding's physical range into a context VM's private address space
 * and cache the resulting virtual address on the binding.  The VA is a fixed
 * window keyed by binding slot, so two contexts can map different physical
 * kernargs at the same virtual address and are isolated by their ASID. */
static int opengpu_binding_map_kernarg(struct opengpu_device *gpu,
                                       struct opengpu_render_context *context,
                                       struct opengpu_resource_binding *binding,
                                       u32 slot, const struct opengpu_resource_binding *old)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_kernarg_va_plan(binding->dma, binding->size, slot,
                                  OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_replace(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             OPENGPU_MMU_POLICY_UNCACHED,
                             old ? old->va : 0, old && old->va ? old->size : 0);
    if (ret)
        return ret;
    binding->va = (dma_addr_t)plan.va;
    return 0;
}

/* Four legacy SSBO windows and four independent image-only backing windows. */
static int opengpu_binding_map_storage(struct opengpu_device *gpu,
                                       struct opengpu_render_context *context,
                                       struct opengpu_resource_binding *binding,
                                       u32 slot, const struct opengpu_resource_binding *old)
{
    struct opengpu_kernarg_va_plan plan;
    unsigned index;
    int ret;
    if (!opengpu_storage_backing_index(slot, &index)) return -EINVAL;
    ret = index < 4 ?
        opengpu_storage_va_plan(binding->dma, binding->size, index,
                                OPENGPU_MMU_PAGE_SIZE, &plan) :
        opengpu_image_backing_va_plan(binding->dma, binding->size, index - 4,
                                      OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret) return ret;
    ret = opengpu_mmu_vm_replace(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             OPENGPU_MMU_POLICY_UNCACHED,
                             old ? old->va : 0, old && old->va ? old->size : 0);
    if (ret) return ret;
    binding->va = (dma_addr_t)plan.va;
    return 0;
}

/* Map a texture binding's physical range into a context VM's private address
 * space, so two contexts can sample different physical textures at the same
 * virtual window and stay isolated by their ASID.  The fixed-function texture
 * client already translates through the shared page tables; this only picks
 * the VA. */
static int opengpu_binding_map_texture(struct opengpu_device *gpu,
                                       struct opengpu_render_context *context,
                                       struct opengpu_resource_binding *binding,
                                       u32 slot, u32 policy,
                                       const struct opengpu_resource_binding *old)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_texture_va_plan(binding->dma, binding->size, slot,
                                  OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_replace(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             policy, old ? old->va : 0, old && old->va ? old->size : 0);
    if (ret)
        return ret;
    binding->va = (dma_addr_t)plan.va;
    return 0;
}

/* Map a per-job command snapshot into the context's VM at a fixed window and
 * return the virtual address the command-buffer client must fetch.  The window
 * is shared by every context, so an ASID switch picks the right physical
 * snapshot.  Mapped uncached: the command port bypasses L2 for coherence, and
 * the translator takes the page's policy.  Must run once the previous job has
 * drained (the VA is remapped per job). */
static int opengpu_binding_map_command(struct opengpu_device *gpu,
                                       struct opengpu_render_context *context,
                                       dma_addr_t dma, size_t size,
                                       dma_addr_t *out_va)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_command_va_plan(dma, size, 1, OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_map(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             OPENGPU_MMU_POLICY_UNCACHED);
    if (ret)
        return ret;
    *out_va = (dma_addr_t)plan.va;
    return 0;
}

/* Map the per-job render descriptor into the context VM at its own window,
 * separate from the command window so the two never alias. */
static int opengpu_binding_map_render(struct opengpu_device *gpu,
                                      struct opengpu_render_context *context,
                                      dma_addr_t dma, size_t size,
                                      dma_addr_t *out_va)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_render_va_plan(dma, size, 1, OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_map(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             OPENGPU_MMU_POLICY_UNCACHED);
    if (ret)
        return ret;
    *out_va = (dma_addr_t)plan.va;
    return 0;
}

/* Map a vertex buffer binding into the context VM, so the vertex shader reads
 * it through a virtual address under the context's ASID. */
static int opengpu_binding_map_vertex(struct opengpu_device *gpu,
                                      struct opengpu_render_context *context,
                                      struct opengpu_resource_binding *binding,
                                      u32 slot, const struct opengpu_resource_binding *old)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_vertex_va_plan(binding->dma, binding->size, slot,
                                 OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_replace(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             OPENGPU_MMU_POLICY_CACHED,
                             old ? old->va : 0, old && old->va ? old->size : 0);
    if (ret)
        return ret;
    binding->va = (dma_addr_t)plan.va;
    return 0;
}

/* Map the colour or depth plane into the context VM, so the output merger
 * reads and writes the render target through the context's ASID. */
static int opengpu_binding_map_framebuffer(struct opengpu_device *gpu,
                                           struct opengpu_render_context *context,
                                           dma_addr_t dma, size_t size,
                                           u32 slot, dma_addr_t *out_va)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_framebuffer_va_plan(dma, size, slot,
                                      OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_map(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             /* Scanout/QEMU reads physical RAM directly;
                              * keep render-target writes visible outside the
                              * GPU cache at each completion. */
                             OPENGPU_MMU_POLICY_UNCACHED);
    if (ret)
        return ret;
    *out_va = (dma_addr_t)plan.va;
    return 0;
}

/* Map a snapshot code buffer into the context VM's private code window, so
 * instruction fetch translates under this ASID instead of running from the
 * shared identity map.  Remapped per job, after the previous job drained. */
static int opengpu_binding_map_code(struct opengpu_device *gpu,
                                    struct opengpu_render_context *context,
                                    dma_addr_t dma, size_t size, u32 slot,
                                    dma_addr_t *out_va)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_code_va_plan(dma, size, slot, OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_map(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             OPENGPU_MMU_POLICY_CACHED);
    if (ret)
        return ret;
    *out_va = (dma_addr_t)plan.va;
    return 0;
}

/* Map a fill/blit/strided/resolve buffer into the context VM so those DMA
 * engines translate under VECTOR_SATP instead of relying on VA==PA through the
 * shared identity map. Slot 0 is source; slot 1 is destination. Line-invalidate
 * submits physical addresses (L2 host-invalidate ignores satp) under the
 * context ASID; resolve keeps a separate physical invalidate base in
 * UCMD_PATTERN. */
static int opengpu_binding_map_dma(struct opengpu_device *gpu,
                                   struct opengpu_render_context *context,
                                   dma_addr_t dma, size_t size, u32 slot,
                                   dma_addr_t *out_va)
{
    struct opengpu_kernarg_va_plan plan;
    int ret;

    ret = opengpu_dma_va_plan(dma, size, slot, OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret)
        return ret;
    ret = opengpu_mmu_vm_map(gpu, &context->vm, (dma_addr_t)plan.va_page,
                             (dma_addr_t)plan.pa_page, (size_t)plan.span,
                             OPENGPU_MMU_POLICY_CACHED);
    if (ret)
        return ret;
    *out_va = (dma_addr_t)plan.va;
    return 0;
}

/* Global (identity) uncached mapping, the fallback when a private VM mapping
 * is not possible; directly-read bindings are also invalidated here when the
 * MMU cannot take a policy split (no MMU, or table capacity). */
static int opengpu_binding_map_uncached(
    struct opengpu_device *gpu,
    const struct opengpu_resource_binding *binding)
{
    int ret;

    ret = opengpu_mmu_set_range_policy(gpu, binding->dma, binding->size,
                                       OPENGPU_MMU_POLICY_UNCACHED);
    if (ret == -EOPNOTSUPP || ret == -ENOSPC || ret == -ENOMEM)
        ret = opengpu_binding_invalidate(gpu, binding);
    return ret;
}

/* Translate a contiguous DMA range into the context VM. Bare jobs retain the
 * physical address, while an enabled VM must never fall back to identity. */
static int opengpu_job_map_dma_range(struct opengpu_sched_job *job,
                                     dma_addr_t dma, size_t size, u32 slot,
                                     dma_addr_t *out)
{
    if (!out)
        return -EINVAL;
    if (!job->vm) {
        *out = dma;
        return 0;
    }
    if (!job->context || !dma || !size)
        return -EINVAL;

    return opengpu_binding_map_dma(job->gpu, job->context, dma, size, slot,
                                   out);
}

int opengpu_compute_resource_bind_ioctl(struct drm_device *drm, void *data,
                                        struct drm_file *file)
{
    struct drm_opengpu_resource *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct opengpu_resource_binding *binding, *old;
    struct drm_gem_dma_object *dma_object;
    struct drm_gem_object *object;
    u64 end, dma_end, texture_bytes, backing_bytes;
    u32 mip_level, mip_width, mip_height, level;
    bool replaced = false;
    unsigned storage_index = 0;
    int ret;

    BUILD_BUG_ON(sizeof(struct drm_opengpu_vertex_draw) !=
                 sizeof(struct gpu_vert_draw_record));

    if (!args->context_id || !args->slot ||
        args->slot > (args->type == OPENGPU_RESOURCE_COMPUTE_TEXTURE ?
                      OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS :
                      args->type == OPENGPU_RESOURCE_COMPUTE_STORAGE ?
                      OPENGPU_MAX_RESOURCE_BINDINGS : OPENGPU_MAX_RESOURCE_SLOTS) ||
        !args->handle || args->type < OPENGPU_RESOURCE_SHADER ||
        args->type > OPENGPU_RESOURCE_COMPUTE_STORAGE || !args->size ||
        (args->type != OPENGPU_RESOURCE_COMPUTE_TEXTURE && (args->offset & 3)) || args->pad ||
        check_add_overflow(args->offset, args->size, &end))
        return -EINVAL;
    if (args->type == OPENGPU_RESOURCE_COMPUTE_STORAGE &&
        (!opengpu_storage_backing_index(args->slot, &storage_index) ||
         (args->offset & 255) || args->size > OPENGPU_STORAGE_DESCRIPTOR_MAX_BYTES))
        return -EINVAL;
    if (args->type == OPENGPU_RESOURCE_COMPUTE_STORAGE && storage_index >= 4 &&
        !opengpu_compute_image_backings_supported(gpu->hw.capabilities,
            gpu->hw.compute_storage_transport, gpu->hw.compute_image_transport))
        return -EOPNOTSUPP;
    if ((args->type == OPENGPU_RESOURCE_SHADER ||
         args->type == OPENGPU_RESOURCE_VERTEX_SHADER ||
         args->type == OPENGPU_RESOURCE_COMPUTE_SHADER) &&
        ((args->offset & 63) || (args->size & 63) ||
         args->size > OPENGPU_SHADER_MAX_INSTRUCTIONS * sizeof(u32)))
        return -EINVAL;
    if ((args->type == OPENGPU_RESOURCE_KERNARG ||
         args->type == OPENGPU_RESOURCE_VERTEX_KERNARG ||
         args->type == OPENGPU_RESOURCE_COMPUTE_KERNARG) &&
        ((args->offset & (GPU_KERNARG_BANK_ALIGN - 1)) ||
         (args->size & (GPU_KERNARG_BANK_ALIGN - 1))))
        return -EINVAL;
    if (args->type == OPENGPU_RESOURCE_VERTEX_BUFFER &&
        args->size < GPU_VERTEX_STRIDE_BYTES)
        return -EINVAL;
    if (args->type == OPENGPU_RESOURCE_TEXTURE) {
        mip_level = (args->flags & OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK) >>
                    OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT;
        if (!args->width || !args->height ||
            args->width > 0x3fff || args->height > 0x3fff ||
            (args->flags & ~(OPENGPU_RESOURCE_TEXTURE_CLAMP |
                             OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK |
                             OPENGPU_RESOURCE_TEXTURE_FORMAT_MASK |
                             OPENGPU_RESOURCE_UNCACHED)) ||
            mip_level >= 32 - __builtin_clz(max(args->width, args->height)) ||
            (!(args->flags & OPENGPU_RESOURCE_TEXTURE_CLAMP) &&
             (!opengpu_is_power_of_two(args->width) ||
              !opengpu_is_power_of_two(args->height))))
            return -EINVAL;
        if (((args->flags & OPENGPU_RESOURCE_TEXTURE_FORMAT_MASK) >>
             OPENGPU_RESOURCE_TEXTURE_FORMAT_SHIFT) > OPENGPU_FORMAT_MAX ||
            ((args->flags & OPENGPU_RESOURCE_TEXTURE_FORMAT_MASK) &&
             !(gpu->hw.capabilities & GPU_CAP_NATIVE_PIXEL_FORMATS)))
            return -EINVAL;
        texture_bytes = 0;
        mip_width = args->width;
        mip_height = args->height;
        for (level = 0; level <= mip_level; level++) {
            u64 level_bytes;

            if (check_mul_overflow((u64)mip_width, (u64)mip_height,
                                   &level_bytes) ||
                check_mul_overflow(level_bytes, 4ull, &level_bytes) ||
                check_add_overflow(texture_bytes, level_bytes,
                                   &texture_bytes))
                return -EINVAL;
            mip_width = max(1u, mip_width >> 1);
            mip_height = max(1u, mip_height >> 1);
        }
        if (texture_bytes > args->size)
            return -EINVAL;
    } else if (args->width || args->height ||
               (args->flags & ~OPENGPU_RESOURCE_UNCACHED)) {
        return -EINVAL;
    }

    /* Word accesses retain the exact view limit, but transport a complete
     * 64-byte line. Prove that enclosing tail belongs to the same GEM object
     * before publishing either a PTE or a binding. */
    backing_bytes = args->type == OPENGPU_RESOURCE_COMPUTE_STORAGE ?
                    (args->size + 63ull) & ~63ull : args->size;
    object = drm_gem_object_lookup(file, args->handle);
    if (!object)
        return -ENOENT;
    if (end > object->size || backing_bytes > object->size - args->offset) {
        ret = -EINVAL;
        goto out_object;
    }
    dma_object = to_drm_gem_dma_obj(object);
    if (check_add_overflow((u64)dma_object->dma_addr, args->offset, &end) ||
        check_add_overflow(end, backing_bytes - 1, &dma_end) ||
        upper_32_bits(dma_end)) {
        ret = -ERANGE;
        goto out_object;
    }
    binding = kzalloc(sizeof(*binding), GFP_KERNEL);
    if (!binding) {
        ret = -ENOMEM;
        goto out_object;
    }
    binding->object = object;
    binding->dma = end;
    binding->offset = args->offset;
    binding->size = args->size;
    binding->type = args->type;
    binding->width = args->width;
    binding->height = args->height;
    binding->flags = args->flags;

    mutex_lock(&render_file->lock);
    context = opengpu_context_lookup(render_file, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    ret = opengpu_context_quiesce(render_file, context);
    if (ret)
        goto out_file;
    old = context->bindings[args->slot - 1];
    if (args->type == OPENGPU_RESOURCE_COMPUTE_STORAGE && storage_index < 4) {
        u32 paired = (args->slot <= 16 ? 35 : 13) + storage_index;
        struct opengpu_resource_binding *other = context->bindings[paired - 1];
        if (other && other->type == OPENGPU_RESOURCE_COMPUTE_STORAGE) {
            ret = -EBUSY;
            goto out_file;
        }
    }
    /* A compute kernarg is mapped privately into the context's VM and reached
     * through a virtual address, so contexts with different physical kernargs
     * are isolated at the same VA.  Fragment and vertex kernargs use the same
     * window planner: staging and shader data loads both translate under
     * VECTOR_SATP. Textures, vertex buffers and the render descriptor/command
     * snapshot are mapped privately too. A VM mapping failure rejects the bind
     * instead of exposing the physical address through the identity map.
     * Shaders are snapshotted. */
    if ((args->type == OPENGPU_RESOURCE_COMPUTE_KERNARG ||
         args->type == OPENGPU_RESOURCE_KERNARG ||
         args->type == OPENGPU_RESOURCE_VERTEX_KERNARG) &&
        context->vm.enabled) {
        ret = opengpu_binding_map_kernarg(gpu, context, binding, args->slot, old);
        if (ret)
            goto out_file;
        replaced = true;
    } else if (args->type == OPENGPU_RESOURCE_COMPUTE_STORAGE) {
        if (!context->vm.enabled) { ret = -EOPNOTSUPP; goto out_file; }
        ret = opengpu_binding_map_storage(gpu, context, binding, args->slot, old);
        if (ret) goto out_file;
        replaced = true;
    } else if ((args->type == OPENGPU_RESOURCE_TEXTURE ||
                args->type == OPENGPU_RESOURCE_COMPUTE_TEXTURE) &&
               context->vm.enabled) {
        /* ARTI compositors reuse GBM texture objects after another GPU
         * context rendered into them. Keep these mappings uncached so the
         * texture client observes the producer's latest pixels instead of a
         * stale shared-L2 line. */
        u32 policy = OPENGPU_MMU_POLICY_UNCACHED;

        if (!(args->flags & OPENGPU_RESOURCE_UNCACHED)) {
            /* Drop CPU-written cache lines before publishing the new PTEs, so
             * a failed invalidate cannot leave an unowned mapping behind. */
            ret = opengpu_binding_invalidate(gpu, binding);
            if (ret)
                goto out_file;
        }
        ret = opengpu_binding_map_texture(gpu, context, binding, args->slot,
                                          policy, old);
        if (ret)
            goto out_file;
        replaced = true;
    } else if (args->type == OPENGPU_RESOURCE_VERTEX_BUFFER &&
               context->vm.enabled) {
        /* Vertex-buffer staging shares VECTOR_SATP with shader data loads;
         * keep the bind-time invalidate for the CPU upload. */
        ret = opengpu_binding_invalidate(gpu, binding);
        if (ret)
            goto out_file;
        ret = opengpu_binding_map_vertex(gpu, context, binding, args->slot, old);
        if (ret)
            goto out_file;
        replaced = true;
    } else if ((args->type == OPENGPU_RESOURCE_TEXTURE ||
                args->type == OPENGPU_RESOURCE_COMPUTE_TEXTURE) &&
                (args->flags & OPENGPU_RESOURCE_UNCACHED)) {
        ret = opengpu_binding_map_uncached(gpu, binding);
        if (ret)
            goto out_file;
    } else if (args->type != OPENGPU_RESOURCE_SHADER &&
               args->type != OPENGPU_RESOURCE_VERTEX_SHADER &&
               args->type != OPENGPU_RESOURCE_COMPUTE_SHADER &&
               args->type != OPENGPU_RESOURCE_KERNARG &&
               args->type != OPENGPU_RESOURCE_VERTEX_KERNARG &&
               args->type != OPENGPU_RESOURCE_COMPUTE_KERNARG) {
        ret = opengpu_binding_invalidate(gpu, binding);
        if (ret)
            goto out_file;
    }
    /* Mapped resources atomically replace and revoke the old mapping above.
     * A snapshotted shader has no private mapping, so revoke the old one now. */
    if (!replaced && old && context->vm.enabled && old->va) {
        ret = opengpu_mmu_vm_unmap(gpu, &context->vm, old->va, old->size);
        if (ret) {
            if (binding->va &&
                opengpu_mmu_vm_unmap(gpu, &context->vm, binding->va,
                                     binding->size))
                WRITE_ONCE(gpu->hw.wedged, true);
            goto out_file;
        }
    }
    context->bindings[args->slot - 1] = binding;
    mutex_unlock(&render_file->lock);
    if (old) {
        drm_gem_object_put(old->object);
        kfree(old);
    }
    return 0;

out_file:
    mutex_unlock(&render_file->lock);
    kfree(binding);
out_object:
    drm_gem_object_put(object);
    return ret;
}

int opengpu_compute_resource_unbind_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file)
{
    struct drm_opengpu_resource *args = data;
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct opengpu_resource_binding *binding;
    int ret;

    if (!args->context_id || !args->slot ||
        args->slot > OPENGPU_MAX_RESOURCE_BINDINGS || args->handle ||
        args->type || args->offset || args->size || args->width ||
        args->height || args->flags || args->pad)
        return -EINVAL;
    mutex_lock(&render_file->lock);
    context = opengpu_context_lookup(render_file, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    binding = context->bindings[args->slot - 1];
    if (!binding) {
        ret = -ENOENT;
        goto out_file;
    }
    ret = opengpu_context_quiesce(render_file, context);
    if (ret)
        goto out_file;
    if (context->vm.enabled && binding->va) {
        ret = opengpu_mmu_vm_unmap(render_file->gpu, &context->vm,
                                   binding->va, binding->size);
        if (ret)
            goto out_file;
    }
    context->bindings[args->slot - 1] = NULL;
    mutex_unlock(&render_file->lock);
    drm_gem_object_put(binding->object);
    kfree(binding);
    return 0;

out_file:
    mutex_unlock(&render_file->lock);
    return ret;
}

static struct opengpu_resource_binding *
opengpu_binding_lookup(struct opengpu_render_context *context, u32 slot,
                       u32 type)
{
    struct opengpu_resource_binding *binding;
    unsigned index;

    if (type == OPENGPU_RESOURCE_COMPUTE_STORAGE &&
        !opengpu_storage_slot_index(slot, &index))
        return NULL;
    if (!slot || slot > OPENGPU_MAX_RESOURCE_BINDINGS ||
        (slot > OPENGPU_MAX_RESOURCE_SLOTS && type != OPENGPU_RESOURCE_COMPUTE_TEXTURE &&
         type != OPENGPU_RESOURCE_COMPUTE_STORAGE))
        return NULL;
    binding = context->bindings[slot - 1];
    return binding && binding->type == type ? binding : NULL;
}

/* New backing slots are accepted only for GIU image views, never GSB/counters. */
static struct opengpu_resource_binding *
opengpu_image_binding_lookup(struct opengpu_render_context *context, u32 slot)
{
    struct opengpu_resource_binding *binding;
    unsigned index;
    if (!opengpu_image_backing_slot_index(slot, &index))
        return opengpu_binding_lookup(context, slot, OPENGPU_RESOURCE_COMPUTE_STORAGE);
    binding = context->bindings[slot - 1];
    return binding && binding->type == OPENGPU_RESOURCE_COMPUTE_STORAGE ? binding : NULL;
}

static int opengpu_resolve_bindings(
    struct opengpu_device *gpu,
    struct opengpu_render_context *context,
    const struct drm_opengpu_submit *args,
    struct opengpu_resource_binding **shader,
    struct opengpu_resource_binding **kernarg,
    struct opengpu_resource_binding **texture,
    struct opengpu_resource_binding **vertex_buffer,
    struct opengpu_resource_binding **vertex_shader,
    struct opengpu_resource_binding **vertex_kernarg)
{
    bool vertex_submit = args->flags & OPENGPU_SUBMIT_VERTEX_CORE;

    if (!!args->shader_slot != !!args->kernarg_slot)
        return -EINVAL;
    if (args->shader_slot &&
        !(gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE))
        return -EOPNOTSUPP;
    if (!args->shader_slot &&
        (gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE))
        return -EOPNOTSUPP;
    if (vertex_submit &&
        !(gpu->hw.capabilities & GPU_CAP_VERTEX_CORE))
        return -EOPNOTSUPP;
    if (vertex_submit &&
        (!args->vertex_buffer_slot || !args->vertex_shader_slot ||
         !args->vertex_kernarg_slot))
        return -EINVAL;
    if (!vertex_submit &&
        (args->vertex_buffer_slot || args->vertex_shader_slot ||
         args->vertex_kernarg_slot))
        return -EINVAL;
    *shader = args->shader_slot ?
        opengpu_binding_lookup(context, args->shader_slot,
                               OPENGPU_RESOURCE_SHADER) : NULL;
    *kernarg = args->kernarg_slot ?
        opengpu_binding_lookup(context, args->kernarg_slot,
                               OPENGPU_RESOURCE_KERNARG) : NULL;
    *texture = args->texture_slot ?
        opengpu_binding_lookup(context, args->texture_slot,
                               OPENGPU_RESOURCE_TEXTURE) : NULL;
    *vertex_buffer = args->vertex_buffer_slot ?
        opengpu_binding_lookup(context, args->vertex_buffer_slot,
                               OPENGPU_RESOURCE_VERTEX_BUFFER) : NULL;
    *vertex_shader = args->vertex_shader_slot ?
        opengpu_binding_lookup(context, args->vertex_shader_slot,
                               OPENGPU_RESOURCE_VERTEX_SHADER) : NULL;
    *vertex_kernarg = args->vertex_kernarg_slot ?
        opengpu_binding_lookup(context, args->vertex_kernarg_slot,
                               OPENGPU_RESOURCE_VERTEX_KERNARG) : NULL;
    if ((args->shader_slot && (!*shader || !*kernarg)) ||
        (args->texture_slot && !*texture) ||
        (vertex_submit &&
         (!*vertex_buffer || !*vertex_shader || !*vertex_kernarg)))
        return -EINVAL;
    if (context->vm.enabled &&
        ((*kernarg && !(*kernarg)->va) ||
         (*texture && !(*texture)->va) ||
         (*vertex_buffer && !(*vertex_buffer)->va) ||
         (*vertex_kernarg && !(*vertex_kernarg)->va)))
        return -EIO;
    return 0;
}

static u32 opengpu_fragment_batch_capacity(struct opengpu_device *gpu)
{
    return (gpu->hw.capabilities & GPU_CAP_FRAGMENT_BATCH_MASK) >>
           GPU_CAP_FRAGMENT_BATCH_SHIFT;
}

/* flw is admitted for every shader profile from the same capability: the
 * compute CUs and the graphics shader CU are both built with the FP backend
 * whenever bit22 is advertised, and neither can retire flw without it. */
static bool opengpu_shader_scalar_fpu_enabled(struct opengpu_device *gpu)
{
    return !!(gpu->hw.capabilities & GPU_CAP_COMPUTE_SCALAR_FPU);
}

static bool opengpu_validate_shader(
    struct opengpu_device *gpu, struct opengpu_buffer *shader,
    u64 kernarg_size, u32 entry, bool texture_enabled, u32 *input_read_mask,
    u32 *output_skip)
{
    const u32 *program;
    u64 available;
    u32 words, reads = 0x1ff, writes = 0x1ff;
    bool valid;

    if ((entry & 3) || entry >= shader->size)
        return false;
    available = shader->size - entry;
    words = min_t(u64, available / sizeof(u32),
                  OPENGPU_SHADER_MAX_INSTRUCTIONS);
    program = (const u32 *)((const u8 *)shader->cpu + entry);
    valid = opengpu_shader_validate_words_profile_runtime_io_usage(
        program, words, kernarg_size,
        opengpu_fragment_batch_capacity(gpu), 6, 9, true, texture_enabled,
        false, opengpu_shader_scalar_fpu_enabled(gpu),
        (gpu->hw.capabilities & GPU_CAP_RUNTIME_SHADER_ABI) ?
            opengpu_fragment_batch_capacity(gpu) : 0,
        (input_read_mask || output_skip) ? &reads : NULL,
        output_skip ? &writes : NULL);
    if (input_read_mask)
        *input_read_mask = valid ? reads & 0x3fu : 0x3fu;
    if (output_skip) {
        *output_skip = 0;
        if (valid) {
            if (!(writes & (1u << 7))) {
                *output_skip |= 1u;
                if (!(reads & (1u << 7)))
                    *output_skip |= 4u;
            }
            if (!(writes & (1u << 8))) {
                *output_skip |= 2u;
                if (!(reads & (1u << 8)))
                    *output_skip |= 8u;
            }
        }
    }
    return valid;
}

static bool opengpu_validate_vertex_shader(
    struct opengpu_device *gpu, struct opengpu_buffer *shader,
    u64 kernarg_size, u32 entry)
{
    const u32 *program;
    u64 available;
    u32 words;

    if ((entry & 3) || entry >= shader->size)
        return false;
    available = shader->size - entry;
    words = min_t(u64, available / sizeof(u32),
                  OPENGPU_SHADER_MAX_INSTRUCTIONS);
    program = (const u32 *)((const u8 *)shader->cpu + entry);
    return opengpu_shader_validate_words_profile_runtime(
        program, words, kernarg_size,
        opengpu_fragment_batch_capacity(gpu), 8, 16, false, false,
        false, opengpu_shader_scalar_fpu_enabled(gpu),
        (gpu->hw.capabilities & GPU_CAP_RUNTIME_SHADER_ABI) ?
            opengpu_fragment_batch_capacity(gpu) : 0);
}

static bool opengpu_validate_kernarg_binding(
    struct opengpu_device *gpu, struct opengpu_buffer *shader,
    struct opengpu_resource_binding *kernarg, u32 entry, u32 offset,
    u32 bank_stride, u32 slices, bool vertex, bool texture_enabled,
    u32 *input_read_mask, u32 *output_skip)
{
    u64 address, minimum, span, shader_kernarg_size;
    u32 banks = vertex ? GPU_KERNARG_BANKS :
                opengpu_shader_resource_banks(gpu->hw.shader_config);

    minimum = (u64)slices * 4ull *
              opengpu_fragment_batch_capacity(gpu);
    if (!minimum || offset > kernarg->size)
        return false;
    /* Parallel fragment workers need disjoint complete banks. Zero stride
     * remains valid for the legacy single-worker/vertex contracts only. */
    if (!vertex && banks > GPU_KERNARG_BANKS && !bank_stride)
        return false;
    span = minimum;
    shader_kernarg_size = kernarg->size - offset;
    if (bank_stride) {
        if ((offset & (GPU_KERNARG_BANK_ALIGN - 1)) ||
            (bank_stride & (GPU_KERNARG_BANK_ALIGN - 1)) ||
            bank_stride < minimum ||
            check_mul_overflow((u64)bank_stride,
                               (u64)banks, &span))
            return false;
        shader_kernarg_size = bank_stride;

    }
    if (check_add_overflow((u64)entry, 4ull, &address) ||
        address > shader->size ||
        check_add_overflow((u64)offset, span, &address) ||
        address > kernarg->size)
        return false;
    return vertex ?
        opengpu_validate_vertex_shader(gpu, shader,
                                       shader_kernarg_size, entry) :
        opengpu_validate_shader(gpu, shader, shader_kernarg_size,
                                entry, texture_enabled, input_read_mask, output_skip);
}

static int opengpu_validate_commands(struct opengpu_device *gpu,
                                     struct opengpu_buffer *commands,
                                     const struct drm_opengpu_submit *args,
                                     struct opengpu_buffer *shader,
                                     struct opengpu_resource_binding *kernarg,
                                     struct opengpu_resource_binding *texture)
{
    struct gpu_draw_record *records = commands->cpu;
    u32 i, j;

    BUILD_BUG_ON(sizeof(struct drm_opengpu_draw) !=
                 sizeof(struct gpu_draw_record));
    BUILD_BUG_ON(offsetof(struct gpu_draw_record, scissor_min) !=
                 38u * sizeof(u32));
    for (i = 0; i < args->command_count; i++) {
        struct gpu_draw_record *record = &records[i];
        u32 input_reads = 0x3f, output_skip = 0;

        if (record->v0[3] <= 0 || record->v1[3] <= 0 ||
            record->v2[3] <= 0)
            return -EINVAL;
        for (j = 0; j < ARRAY_SIZE(record->c0); j++) {
            if (record->c0[j] > 255 || record->c1[j] > 255 ||
                record->c2[j] > 255)
                return -EINVAL;
        }
        if (!(record->state & OPENGPU_DRAW_STATE_SCISSOR) &&
            (record->scissor_min || record->scissor_max))
            return -EINVAL;
        if (record->state & ~OPENGPU_DRAW_STATE_VALID_MASK)
            return -EINVAL;
        /* The fixed triangle record has no viewport fields. */
        if (record->state & (OPENGPU_DRAW_STATE_VIEWPORT |
                             OPENGPU_DRAW_STATE_VIEWPORT_FLIP_Y |
                             OPENGPU_DRAW_STATE_DEPTH_RANGE))
            return -EINVAL;
        if (record->sampler & ~OPENGPU_DRAW_SAMPLER_VALID_MASK)
            return -EINVAL;
        /* The biased-coordinate ABI is defined only for vertex records. */
        if (record->sampler & (OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_MASK |
                               OPENGPU_DRAW_SAMPLER_MIRROR_MASK))
            return -EOPNOTSUPP;
        if ((record->sampler & OPENGPU_DRAW_SAMPLER_NEAREST) && !gpu->hw.sampler_nearest)
            return -EOPNOTSUPP;
        if ((record->sampler & OPENGPU_DRAW_RASTER_PIXEL_CENTER) && !gpu->hw.pixel_center)
            return -EOPNOTSUPP;
        if ((record->sampler & OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP) && !gpu->hw.sampler_axis_wrap)
            return -EOPNOTSUPP;
        if (record->blend_config & ~OPENGPU_DRAW_BLEND_VALID_MASK)
            return -EINVAL;
        if (record->stencil_config & ~OPENGPU_DRAW_STENCIL_VALID_MASK)
            return -EINVAL;
        if (record->stencil_ref & ~OPENGPU_DRAW_STENCIL_REF_VALID_MASK)
            return -EINVAL;
        if (!(record->state & OPENGPU_DRAW_STATE_OVERRIDE)) {
            if (record->state || record->sampler || record->blend_config ||
                record->stencil_config || record->stencil_ref)
                return -EINVAL;
        } else {
            u32 depth_func = (record->state &
                OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK) >>
                OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT;
            u32 cull = (record->state & OPENGPU_DRAW_STATE_CULL_MASK) >>
                OPENGPU_DRAW_STATE_CULL_SHIFT;
            u32 max_mip = (record->state &
                OPENGPU_DRAW_STATE_MAX_MIP_MASK) >>
                OPENGPU_DRAW_STATE_MAX_MIP_SHIFT;
            u32 bound_max_mip = texture ?
                (texture->flags & OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK) >>
                OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT : 0;
            u32 min_mip = (record->sampler &
                OPENGPU_DRAW_SAMPLER_MIN_LOD_MASK) >>
                OPENGPU_DRAW_SAMPLER_MIN_LOD_SHIFT;
            bool stencil = record->state & OPENGPU_DRAW_STATE_STENCIL_TEST;

            if (depth_func > GPU_DEPTH_FUNC_NEVER ||
                cull > GPU_CULL_FRONT ||
                ((record->state & OPENGPU_DRAW_STATE_TEX_ENABLE) &&
                 !texture) ||
                ((record->sampler & ~OPENGPU_DRAW_RASTER_PIXEL_CENTER) && !texture) ||
                max_mip > bound_max_mip || min_mip > max_mip)
                return -EINVAL;
            /* Stencil words are only meaningful with the test enabled. */
            if (!stencil && (record->stencil_config || record->stencil_ref))
                return -EINVAL;
            if (record->blend_config) {
                u32 src = (record->blend_config &
                    OPENGPU_DRAW_BLEND_SRC_MASK) >>
                    OPENGPU_DRAW_BLEND_SRC_SHIFT;
                u32 dst = (record->blend_config &
                    OPENGPU_DRAW_BLEND_DST_MASK) >>
                    OPENGPU_DRAW_BLEND_DST_SHIFT;
                u32 eq = (record->blend_config &
                    OPENGPU_DRAW_BLEND_EQ_MASK) >>
                    OPENGPU_DRAW_BLEND_EQ_SHIFT;

                if (!(record->blend_config & OPENGPU_DRAW_BLEND_PRESENT) ||
                    src > GPU_BLEND_FACTOR_MAX || dst > GPU_BLEND_FACTOR_MAX ||
                    eq > GPU_BLEND_EQ_MAX_VAL)
                    return -EINVAL;
            }
        }
        if (!shader) {
            if (record->shader_pc || record->kernarg ||
                (record->sampler & ~OPENGPU_DRAW_RASTER_PIXEL_CENTER) ||
                record->kernarg_bank_stride)
                return -EINVAL;
            continue;
        }
        if (!opengpu_validate_kernarg_binding(
                gpu, shader, kernarg, record->shader_pc, record->kernarg,
                record->kernarg_bank_stride, 9, false, texture != NULL,
                (args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) ?
                    &input_reads : NULL,
                ((args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) &&
                 (gpu->hw.capabilities & GPU_CAP_FRAGMENT_OUTPUT_USAGE)) ?
                    &output_skip : NULL))
            return -EINVAL;
        if ((args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) &&
            (gpu->hw.capabilities & GPU_CAP_FRAGMENT_INPUT_USAGE))
            record->state |= ((~input_reads) & 0x3fu) <<
                             GPU_DRAW_FRAGMENT_INPUT_SKIP_SHIFT;
        if ((args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) &&
            (gpu->hw.capabilities & GPU_CAP_FRAGMENT_OUTPUT_USAGE))
            record->state |= output_skip << GPU_DRAW_FRAGMENT_OUTPUT_SKIP_SHIFT;
        record->shader_pc += lower_32_bits(shader->dma);
        /* Staging and shader data loads translate under VECTOR_SATP. */
        record->kernarg += lower_32_bits(kernarg->va ?
            kernarg->va : kernarg->dma);
    }
    return 0;
}

static int opengpu_validate_vertex_commands(
    struct opengpu_device *gpu, struct opengpu_buffer *commands,
    const struct drm_opengpu_submit *args,
    struct opengpu_resource_binding *vertex_buffer,
    struct opengpu_buffer *vertex_shader,
    struct opengpu_resource_binding *vertex_kernarg,
    struct opengpu_buffer *fragment_shader,
    struct opengpu_resource_binding *fragment_kernarg,
    struct opengpu_resource_binding *texture)
{
    struct gpu_vert_draw_record *records = commands->cpu;
    u32 i, j;

    BUILD_BUG_ON(sizeof(struct drm_opengpu_vertex_draw) !=
                 sizeof(struct gpu_vert_draw_record));
    BUILD_BUG_ON(offsetof(struct gpu_vert_draw_record, scissor_min) !=
                 38u * sizeof(u32));
    for (i = 0; i < args->command_count; i++) {
        struct gpu_vert_draw_record *record = &records[i];
        u32 input_reads = 0x3f, output_skip = 0;
        u32 blend_src = (record->blend_config & OPENGPU_DRAW_BLEND_SRC_MASK) >>
                        OPENGPU_DRAW_BLEND_SRC_SHIFT;
        u32 blend_dst = (record->blend_config & OPENGPU_DRAW_BLEND_DST_MASK) >>
                        OPENGPU_DRAW_BLEND_DST_SHIFT;
        bool constant_blend = (blend_src >= GPU_BLEND_FACTOR_CONSTANT_COLOR &&
                               blend_src <= GPU_BLEND_FACTOR_CONSTANT_MAX) ||
                              (blend_dst >= GPU_BLEND_FACTOR_CONSTANT_COLOR &&
                               blend_dst <= GPU_BLEND_FACTOR_CONSTANT_MAX);
        u64 address, bytes;

        if (record->vert_count < 3 || record->vert_count % 3 ||
            record->vert_stride != GPU_VERTEX_STRIDE_BYTES ||
            record->vert_attr_format > OPENGPU_VERTEX_FORMAT_CLIP_DEPTH)
            return -EINVAL;
        if (record->vert_attr_format == OPENGPU_VERTEX_FORMAT_CLIP_DEPTH &&
            !gpu->hw.clip_depth)
            return -EOPNOTSUPP;
        if ((record->state & OPENGPU_DRAW_STATE_VIEWPORT_FLIP_Y) &&
            !(record->state & OPENGPU_DRAW_STATE_VIEWPORT))
            return -EINVAL;
        if (record->state & OPENGPU_DRAW_STATE_VIEWPORT) {
            if (!gpu->hw.viewport)
                return -EOPNOTSUPP;
            if (!(record->reserved0[1] & 0xffffu) ||
                !(record->reserved0[1] >> 16))
                return -EINVAL;
        } else if (record->reserved0[0] || record->reserved0[1]) {
            return -EINVAL;
        }
        if (record->state & OPENGPU_DRAW_STATE_DEPTH_RANGE) {
            if (!gpu->hw.depth_range)
                return -EOPNOTSUPP;
            if (record->vert_attr_format != OPENGPU_VERTEX_FORMAT_CLIP_DEPTH ||
                ((record->reserved0[2] | record->reserved0[3]) & 0xff000000u))
                return -EINVAL;
        } else if (record->reserved0[2] || record->reserved0[3]) {
            return -EINVAL;
        }
        if (constant_blend && !gpu->hw.constant_blend)
            return -EOPNOTSUPP;
        if (!constant_blend && record->reserved0[4])
            return -EINVAL;
        for (j = 5; j < ARRAY_SIZE(record->reserved0); j++)
            if (record->reserved0[j])
                return -EINVAL;
        for (j = 0; j < ARRAY_SIZE(record->reserved1); j++)
            if (record->reserved1[j])
                return -EINVAL;
        if (!(record->state & OPENGPU_DRAW_STATE_SCISSOR) &&
            (record->scissor_min || record->scissor_max))
            return -EINVAL;
        if (record->state & ~OPENGPU_DRAW_STATE_VALID_MASK ||
            record->sampler & ~OPENGPU_DRAW_SAMPLER_VALID_MASK)
            return -EINVAL;
        if (record->sampler & OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_MASK) {
            bool clamp_u = !!(record->state & OPENGPU_DRAW_STATE_TEX_CLAMP);
            bool clamp_v = clamp_u ^ !!(record->sampler & OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP);

            if (!gpu->hw.sampler_biased_clamp)
                return -EOPNOTSUPP;
            if (!(record->state & OPENGPU_DRAW_STATE_OVERRIDE) ||
                !(record->state & OPENGPU_DRAW_STATE_TEX_ENABLE) ||
                ((record->sampler & OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U) && !clamp_u) ||
                ((record->sampler & OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_V) && !clamp_v))
                return -EINVAL;
        }
        if (record->sampler & OPENGPU_DRAW_SAMPLER_MIRROR_MASK) {
            bool clamp_u = !!(record->state & OPENGPU_DRAW_STATE_TEX_CLAMP);
            bool clamp_v = clamp_u ^ !!(record->sampler & OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP);

            if (!gpu->hw.sampler_mirrored_repeat)
                return -EOPNOTSUPP;
            if (!(record->state & OPENGPU_DRAW_STATE_OVERRIDE) ||
                !(record->state & OPENGPU_DRAW_STATE_TEX_ENABLE) ||
                ((record->sampler & OPENGPU_DRAW_SAMPLER_MIRROR_U) &&
                 (!clamp_u || (record->sampler & OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_U))) ||
                ((record->sampler & OPENGPU_DRAW_SAMPLER_MIRROR_V) &&
                 (!clamp_v || (record->sampler & OPENGPU_DRAW_SAMPLER_BIASED_CLAMP_V))))
                return -EINVAL;
        }
        if ((record->sampler & OPENGPU_DRAW_SAMPLER_NEAREST) && !gpu->hw.sampler_nearest)
            return -EOPNOTSUPP;
        if ((record->sampler & OPENGPU_DRAW_RASTER_PIXEL_CENTER) && !gpu->hw.pixel_center)
            return -EOPNOTSUPP;
        if ((record->sampler & OPENGPU_DRAW_SAMPLER_WRAP_V_FLIP) && !gpu->hw.sampler_axis_wrap)
            return -EOPNOTSUPP;
        if (record->blend_config & ~OPENGPU_DRAW_BLEND_VALID_MASK ||
            record->stencil_config & ~OPENGPU_DRAW_STENCIL_VALID_MASK ||
            record->stencil_ref & ~OPENGPU_DRAW_STENCIL_REF_VALID_MASK)
            return -EINVAL;
        if ((args->flags & OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH) &&
            (!(record->state & OPENGPU_DRAW_STATE_OVERRIDE) ||
             (record->state & (OPENGPU_DRAW_STATE_DEPTH_TEST |
                               OPENGPU_DRAW_STATE_DEPTH_WRITE |
                               OPENGPU_DRAW_STATE_STENCIL_TEST))))
            return -EINVAL;
        if (!(record->state & OPENGPU_DRAW_STATE_OVERRIDE)) {
            if (record->state || record->sampler || record->blend_config ||
                record->stencil_config || record->stencil_ref)
                return -EINVAL;
        } else {
            u32 depth_func = (record->state &
                OPENGPU_DRAW_STATE_DEPTH_FUNC_MASK) >>
                OPENGPU_DRAW_STATE_DEPTH_FUNC_SHIFT;
            u32 cull = (record->state & OPENGPU_DRAW_STATE_CULL_MASK) >>
                OPENGPU_DRAW_STATE_CULL_SHIFT;
            u32 max_mip = (record->state &
                OPENGPU_DRAW_STATE_MAX_MIP_MASK) >>
                OPENGPU_DRAW_STATE_MAX_MIP_SHIFT;
            u32 bound_max_mip = texture ?
                (texture->flags & OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK) >>
                OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT : 0;
            u32 min_mip = (record->sampler &
                OPENGPU_DRAW_SAMPLER_MIN_LOD_MASK) >>
                OPENGPU_DRAW_SAMPLER_MIN_LOD_SHIFT;
            bool stencil = record->state & OPENGPU_DRAW_STATE_STENCIL_TEST;

            if (depth_func > GPU_DEPTH_FUNC_NEVER ||
                cull > GPU_CULL_FRONT ||
                ((record->state & OPENGPU_DRAW_STATE_TEX_ENABLE) &&
                 !texture) ||
                ((record->sampler & ~OPENGPU_DRAW_RASTER_PIXEL_CENTER) && !texture) ||
                max_mip > bound_max_mip || min_mip > max_mip)
                return -EINVAL;
            /* Stencil words are only meaningful with the test enabled. */
            if (!stencil && (record->stencil_config || record->stencil_ref))
                return -EINVAL;
            if (record->blend_config) {
                u32 src = (record->blend_config &
                    OPENGPU_DRAW_BLEND_SRC_MASK) >>
                    OPENGPU_DRAW_BLEND_SRC_SHIFT;
                u32 dst = (record->blend_config &
                    OPENGPU_DRAW_BLEND_DST_MASK) >>
                    OPENGPU_DRAW_BLEND_DST_SHIFT;
                u32 eq = (record->blend_config &
                    OPENGPU_DRAW_BLEND_EQ_MASK) >>
                    OPENGPU_DRAW_BLEND_EQ_SHIFT;

                if (!(record->blend_config & OPENGPU_DRAW_BLEND_PRESENT) ||
                    src > GPU_BLEND_FACTOR_CONSTANT_MAX || dst > GPU_BLEND_FACTOR_CONSTANT_MAX ||
                    eq > GPU_BLEND_EQ_MAX_VAL)
                    return -EINVAL;
            }
        }
        if (check_mul_overflow((u64)(record->vert_count - 1),
                               (u64)record->vert_stride, &bytes) ||
            check_add_overflow(bytes, (u64)GPU_VERTEX_STRIDE_BYTES,
                               &bytes) ||
            check_add_overflow((u64)record->vert_buffer_base, bytes,
                               &address) ||
            address > vertex_buffer->size)
            return -EINVAL;
        if (!opengpu_validate_kernarg_binding(
                gpu, vertex_shader, vertex_kernarg,
                record->vert_shader_pc, record->vert_kernarg,
                record->vert_kernarg_bank_stride, 16, true, false, NULL, NULL))
            return -EINVAL;
        if (!opengpu_validate_kernarg_binding(
                gpu, fragment_shader, fragment_kernarg,
                record->frag_shader_pc, record->frag_kernarg,
                record->frag_kernarg_bank_stride, 9, false,
                texture != NULL,
                (args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) ?
                    &input_reads : NULL,
                ((args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) &&
                 (gpu->hw.capabilities & GPU_CAP_FRAGMENT_OUTPUT_USAGE)) ?
                    &output_skip : NULL))
            return -EINVAL;
        if ((args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) &&
            (gpu->hw.capabilities & GPU_CAP_FRAGMENT_INPUT_USAGE))
            record->state |= ((~input_reads) & 0x3fu) <<
                             GPU_DRAW_FRAGMENT_INPUT_SKIP_SHIFT;
        if ((args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) &&
            (gpu->hw.capabilities & GPU_CAP_FRAGMENT_OUTPUT_USAGE))
            record->state |= output_skip << GPU_DRAW_FRAGMENT_OUTPUT_SKIP_SHIFT;
        /* Vertex-buffer and kernarg staging share VECTOR_SATP with shader
         * data loads. Prefer the private VA when the bind mapped one. */
        record->vert_buffer_base += lower_32_bits(vertex_buffer->va ?
            vertex_buffer->va : vertex_buffer->dma);
        record->vert_shader_pc += lower_32_bits(vertex_shader->dma);
        record->vert_kernarg += lower_32_bits(vertex_kernarg->va ?
            vertex_kernarg->va : vertex_kernarg->dma);
        record->frag_shader_pc += lower_32_bits(fragment_shader->dma);
        record->frag_kernarg += lower_32_bits(fragment_kernarg->va ?
            fragment_kernarg->va : fragment_kernarg->dma);
    }
    return 0;
}

static int opengpu_prepare_submit_objects(
    struct drm_exec *exec, struct drm_gem_object *command,
    struct drm_gem_object *color,
    struct drm_gem_object *depth,
    struct opengpu_resource_binding *shader,
    struct opengpu_resource_binding *kernarg,
    struct opengpu_resource_binding *texture,
    struct opengpu_resource_binding *vertex_buffer,
    struct opengpu_resource_binding *vertex_shader,
    struct opengpu_resource_binding *vertex_kernarg)
{
    int ret;

    drm_exec_until_all_locked(exec) {
        ret = drm_exec_lock_obj(exec, command);
        if (!ret)
            ret = drm_exec_prepare_obj(exec, color, 1);
        if (!ret && depth)
            ret = drm_exec_prepare_obj(exec, depth, 1);
        if (!ret && shader)
            ret = drm_exec_prepare_obj(exec, shader->object, 1);
        if (!ret && kernarg)
            ret = drm_exec_prepare_obj(exec, kernarg->object, 1);
        if (!ret && texture)
            ret = drm_exec_prepare_obj(exec, texture->object, 1);
        if (!ret && vertex_buffer)
            ret = drm_exec_prepare_obj(exec, vertex_buffer->object, 1);
        if (!ret && vertex_shader)
            ret = drm_exec_prepare_obj(exec, vertex_shader->object, 1);
        if (!ret && vertex_kernarg)
            ret = drm_exec_prepare_obj(exec, vertex_kernarg->object, 1);
        drm_exec_retry_on_contention(exec);
        if (ret)
            return ret;
    }
    return 0;
}

static int opengpu_wait_reservation(struct drm_gem_object *object,
                                    enum dma_resv_usage usage)
{
    long wait;

    if (!object)
        return 0;
    wait = dma_resv_wait_timeout(object->resv, usage, true,
                                 MAX_SCHEDULE_TIMEOUT);
    if (wait < 0)
        return wait;
    return wait ? 0 : -ETIMEDOUT;
}

static bool opengpu_strided_range_end(u64 start, u32 width, u32 height,
                                      u32 stride, u64 *end);

/* Record a run-time private VM mapping so the scheduler can revoke it when the
 * job retires. Mapping happens under submit_lock after the previous job
 * drained, and the ordered free-job worker revokes it before the next run-job
 * worker maps the same fixed window. */
int opengpu_job_track_mapping(struct opengpu_sched_job *job,
                              dma_addr_t va, size_t size)
{
    if (!job->vm || !job->context || !va || !size)
        return 0;
    if (job->temporary_mapping_count >= OPENGPU_MAX_TEMP_MAPPINGS) {
        if (opengpu_mmu_vm_unmap(job->gpu, &job->context->vm, va, size))
            WRITE_ONCE(job->gpu->hw.wedged, true);
        return -ENOSPC;
    }
    job->temporary_mappings[job->temporary_mapping_count].va = va;
    job->temporary_mappings[job->temporary_mapping_count].size = size;
    job->temporary_mapping_count++;
    return 0;
}

struct dma_fence *opengpu_job_run(struct opengpu_sched_job *job)
{
    /* The submitting context's address space; NULL for bring-up self-tests. */
    const struct opengpu_vm *vm = job->vm;
    struct dma_fence *fence;
    int ret;

    if (job->type == OPENGPU_SCHED_TESSELLATION)
        return opengpu_tessellation_job_run(job);
    if (job->type == OPENGPU_SCHED_COMPUTE) {
        /* Run the kernel from a private code mapping so instruction fetch
         * translates under this ASID.  Identity leaves are not executable, so
         * a VM job whose code cannot be mapped fails rather than running from
         * the physical address; a Bare job (no VM) keeps the physical PC. */
        if (vm && job->context && job->shader.dma) {
            dma_addr_t code_va = 0;

            ret = opengpu_binding_map_code(job->gpu, job->context,
                    job->shader.dma, job->shader.size, 1, &code_va);
            if (ret)
                return ERR_PTR(ret);
            ret = opengpu_job_track_mapping(job, code_va, job->shader.size);
            if (ret)
                return ERR_PTR(ret);
            job->kernel.kernel_pc = code_va +
                (job->kernel.kernel_pc - job->shader.dma);
        }
        if (job->kernel.texture_enabled) {
            struct opengpu_kernarg_va_plan plan;
            dma_addr_t root = job->texture_records.dma;

            /* The producer serializes records for this exact VA in a private
             * job-owned allocation; an arbitrary root cannot be substituted. */
            if (!job->texture_records.cpu || !root || job->texture_records.size < 128)
                return ERR_PTR(-EINVAL);
            if (vm) {
                if (!job->context)
                    return ERR_PTR(-EINVAL);
                ret = opengpu_command_va_plan(root, job->texture_records.size,
                                               2, OPENGPU_MMU_PAGE_SIZE, &plan);
                if (ret)
                    return ERR_PTR(ret);
                if (upper_32_bits(plan.va) || job->kernel.texture.root != plan.va)
                    return ERR_PTR(-EINVAL);
                ret = opengpu_mmu_vm_map(job->gpu, &job->context->vm,
                        (dma_addr_t)plan.va_page, (dma_addr_t)plan.pa_page,
                        (size_t)plan.span, OPENGPU_MMU_POLICY_UNCACHED);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, (dma_addr_t)plan.va,
                                                job->texture_records.size);
                if (ret)
                    return ERR_PTR(ret);
            } else if (upper_32_bits(root) || job->kernel.texture.root != root) {
                return ERR_PTR(-EINVAL);
            }
        }
        if (job->kernel.storage_enabled) {
            struct opengpu_kernarg_va_plan plan = { 0 };
            dma_addr_t base = job->storage_records.dma;
            size_t bytes = job->storage_records.size;
            u64 address = base;

            if (!job->storage_records.cpu || !base || bytes < 64 ||
                bytes > 256 || (bytes & 63))
                return ERR_PTR(-EINVAL);
            if (vm) {
                if (!job->context)
                    return ERR_PTR(-EINVAL);
                ret = opengpu_command_va_plan(base, bytes, 3,
                                               OPENGPU_MMU_PAGE_SIZE, &plan);
                if (ret)
                    return ERR_PTR(ret);
                address = plan.va;
            }
            if (upper_32_bits(address) || job->kernel.storage.base != address ||
                job->kernel.storage.limit != address + bytes ||
                address + bytes > (1ULL << 32))
                return ERR_PTR(-EINVAL);
            if (vm) {
                ret = opengpu_mmu_vm_map(job->gpu, &job->context->vm,
                        (dma_addr_t)plan.va_page, (dma_addr_t)plan.pa_page,
                        (size_t)plan.span, OPENGPU_MMU_POLICY_UNCACHED);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, (dma_addr_t)plan.va, bytes);
                if (ret)
                    return ERR_PTR(ret);
            }
        }
        if (job->kernel.image_enabled) {
            struct opengpu_kernarg_va_plan plan = { 0 };
            dma_addr_t base = job->image_records.dma;
            size_t bytes = job->image_records.size;
            u64 address = base;

            if (!job->image_records.cpu || !base || bytes < 256 ||
                bytes > 1024 || (bytes & 255))
                return ERR_PTR(-EINVAL);
            if (vm) {
                if (!job->context)
                    return ERR_PTR(-EINVAL);
                ret = opengpu_command_va_plan(base, bytes, 4,
                                               OPENGPU_MMU_PAGE_SIZE, &plan);
                if (ret)
                    return ERR_PTR(ret);
                address = plan.va;
            }
            if (upper_32_bits(address) || job->kernel.image.base != address ||
                job->kernel.image.limit != address + bytes ||
                address + bytes > (1ULL << 32))
                return ERR_PTR(-EINVAL);
            if (vm) {
                ret = opengpu_mmu_vm_map(job->gpu, &job->context->vm,
                        (dma_addr_t)plan.va_page, (dma_addr_t)plan.pa_page,
                        (size_t)plan.span, OPENGPU_MMU_POLICY_UNCACHED);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, (dma_addr_t)plan.va, bytes);
                if (ret)
                    return ERR_PTR(ret);
            }
        }
        if (job->kernel.counter_enabled) {
            struct opengpu_kernarg_va_plan plan = { 0 };
            dma_addr_t base = job->counter_records.dma;
            size_t bytes = job->counter_records.size;
            u64 address = base;

            if (!job->counter_records.cpu || !base || bytes != 64)
                return ERR_PTR(-EINVAL);
            if (vm) {
                if (!job->context)
                    return ERR_PTR(-EINVAL);
                ret = opengpu_command_va_plan(base, bytes, 5,
                                               OPENGPU_MMU_PAGE_SIZE, &plan);
                if (ret)
                    return ERR_PTR(ret);
                address = plan.va;
            }
            if (upper_32_bits(address) || job->kernel.counter.base != address ||
                job->kernel.counter.limit != address + bytes ||
                address + bytes > (1ULL << 32))
                return ERR_PTR(-EINVAL);
            if (vm) {
                ret = opengpu_mmu_vm_map(job->gpu, &job->context->vm,
                        (dma_addr_t)plan.va_page, (dma_addr_t)plan.pa_page,
                        (size_t)plan.span, OPENGPU_MMU_POLICY_UNCACHED);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, (dma_addr_t)plan.va, bytes);
                if (ret)
                    return ERR_PTR(ret);
            }
        }
        if (job->counter_source.object) {
            const struct opengpu_resource_binding *source = &job->counter_source;
            struct opengpu_kernarg_va_plan plan;

            if (!vm || !vm->enabled || !job->context || !job->kernel.counter_enabled ||
                !source->size || source->size > 32 || (source->size & 3) ||
                (source->dma & 3))
                return ERR_PTR(-EINVAL);
            ret = opengpu_command_va_plan(source->dma, source->size,
                    OPENGPU_COMPUTE_COUNTER_SOURCE_SLOT, OPENGPU_MMU_PAGE_SIZE, &plan);
            if (ret || plan.va != source->va)
                return ERR_PTR(ret ? ret : -EINVAL);
            /* The translated uncached AMO reads authoritative lower memory
             * and acknowledges its write before the job can complete. */
            ret = opengpu_mmu_vm_map(job->gpu, &job->context->vm,
                    (dma_addr_t)plan.va_page, (dma_addr_t)plan.pa_page,
                    (size_t)plan.span, OPENGPU_MMU_POLICY_UNCACHED);
            if (ret)
                return ERR_PTR(ret);
            ret = opengpu_job_track_mapping(job, source->va, source->size);
            if (ret)
                return ERR_PTR(ret);
        }
        if (job->kernel.uniform_enabled) {
            struct opengpu_kernarg_va_plan plan;
            struct opengpu_buffer *snapshot = &job->uniform_snapshot;

            if (!vm || !vm->enabled || !job->context || !snapshot->cpu || !snapshot->dma ||
                (snapshot->dma & 255) || snapshot->size < 4096 ||
                snapshot->size > OPENGPU_COMPUTE_UNIFORM_SNAPSHOT_MAX_BYTES ||
                (snapshot->size & 4095))
                return ERR_PTR(-EINVAL);
            ret = opengpu_command_va_plan(snapshot->dma, snapshot->size,
                    OPENGPU_COMPUTE_UNIFORM_SNAPSHOT_SLOT, OPENGPU_MMU_PAGE_SIZE, &plan);
            if (ret)
                return ERR_PTR(ret);
            if (upper_32_bits(plan.va) || job->kernel.uniform.root != plan.va)
                return ERR_PTR(-EINVAL);
            /* Uniform records and copied data are immutable GPU inputs. */
            ret = opengpu_mmu_vm_map_snapshot(job->gpu, &job->context->vm,
                    (dma_addr_t)plan.va_page, (dma_addr_t)plan.pa_page,
                    (size_t)plan.span, OPENGPU_MMU_POLICY_UNCACHED);
            if (ret)
                return ERR_PTR(ret);
            ret = opengpu_job_track_mapping(job, (dma_addr_t)plan.va, snapshot->size);
            if (ret)
                return ERR_PTR(ret);
        }
        if (job->kernel.indirect_enabled) {
            struct opengpu_kernarg_va_plan plan;
            struct opengpu_buffer *snapshot = &job->indirect_snapshot;

            if (!vm || !vm->enabled || !job->context || !snapshot->cpu ||
                !snapshot->dma || (snapshot->dma & 4095) ||
                (snapshot->size != 4096 && snapshot->size != 8192))
                return ERR_PTR(-EINVAL);
            ret = opengpu_command_va_plan(snapshot->dma, snapshot->size,
                OPENGPU_COMPUTE_INDIRECT_SNAPSHOT_SLOT, OPENGPU_MMU_PAGE_SIZE, &plan);
            if (ret || plan.va != job->kernel.indirect_base ||
                snapshot->size != job->kernel.indirect_bytes)
                return ERR_PTR(ret ? ret : -EINVAL);
            ret = opengpu_mmu_vm_map_snapshot(job->gpu, &job->context->vm,
                (dma_addr_t)plan.va_page, (dma_addr_t)plan.pa_page,
                (size_t)plan.span, OPENGPU_MMU_POLICY_UNCACHED);
            if (ret)
                return ERR_PTR(ret);
            ret = opengpu_job_track_mapping(job, (dma_addr_t)plan.va, snapshot->size);
            if (ret)
                return ERR_PTR(ret);
        }
        ret = opengpu_hw_compute_async(job->gpu, &job->kernel, &job->shader,
                                       &job->texture_records, &job->storage_records,
                                       &job->image_records, &job->counter_records,
                                       &job->uniform_snapshot,
                                       &job->indirect_snapshot,
                                       &job->events, vm, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    if (job->type == OPENGPU_SCHED_BLIT) {
        dma_addr_t source, destination;

        ret = opengpu_job_map_dma_range(
            job, job->dma_source, job->dma_bytes, 0, &source);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_track_mapping(job, source, job->dma_bytes);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_map_dma_range(
            job, job->dma_destination, job->dma_bytes, 1, &destination);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_track_mapping(job, destination, job->dma_bytes);
        if (ret)
            return ERR_PTR(ret);

        ret = opengpu_hw_blit_async(job->gpu,
                                    lower_32_bits(source),
                                    lower_32_bits(destination),
                                    job->dma_bytes, &job->events, vm, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    if (job->type == OPENGPU_SCHED_FILL) {
        dma_addr_t destination;

        ret = opengpu_job_map_dma_range(
            job, job->dma_destination, job->dma_bytes, 1, &destination);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_track_mapping(job, destination, job->dma_bytes);
        if (ret)
            return ERR_PTR(ret);

        ret = opengpu_hw_clear_async(job->gpu,
                                     lower_32_bits(destination),
                                     job->dma_bytes, job->fill_pattern,
                                     &job->events, vm, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    if (job->type == OPENGPU_SCHED_STRIDED_BLIT) {
        u64 source_end = 0, destination_end = 0;
        size_t source_span = job->dma_bytes;
        size_t destination_span = job->dma_bytes;
        dma_addr_t source, destination;

        if (!opengpu_strided_range_end(job->dma_source, job->dma_bytes,
                                       job->dma_height, job->dma_source_stride,
                                       &source_end) &&
            source_end > job->dma_source)
            source_span = (size_t)(source_end - job->dma_source);
        if (!opengpu_strided_range_end(job->dma_destination, job->dma_bytes,
                                       job->dma_height,
                                       job->dma_destination_stride,
                                       &destination_end) &&
            destination_end > job->dma_destination)
            destination_span = (size_t)(destination_end - job->dma_destination);
        ret = opengpu_job_map_dma_range(
            job, job->dma_source, source_span, 0, &source);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_track_mapping(job, source, source_span);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_map_dma_range(
            job, job->dma_destination, destination_span, 1, &destination);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_track_mapping(job, destination, destination_span);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_hw_strided_blit_async(
            job->gpu, lower_32_bits(source),
            lower_32_bits(destination), job->dma_bytes,
            job->dma_height, job->dma_source_stride,
            job->dma_destination_stride, &job->events, vm, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    if (job->type == OPENGPU_SCHED_RESOLVE) {
        u64 source_end = 0, destination_end = 0;
        size_t source_span = job->dma_bytes;
        size_t destination_span = job->dma_bytes;
        dma_addr_t source, destination;
        u32 invalidate_phys = lower_32_bits(job->dma_source);

        if (!opengpu_strided_range_end(job->dma_source, job->dma_bytes,
                                       job->dma_height, job->dma_source_stride,
                                       &source_end) &&
            source_end > job->dma_source)
            source_span = (size_t)(source_end - job->dma_source);
        if (!opengpu_strided_range_end(job->dma_destination, job->dma_bytes,
                                       job->dma_height,
                                       job->dma_destination_stride,
                                       &destination_end) &&
            destination_end > job->dma_destination)
            destination_span = (size_t)(destination_end - job->dma_destination);
        /* Engine addresses may be private VAs; the pre-resolve L2 invalidate
         * still uses the physical source base via UCMD_PATTERN. */
        ret = opengpu_job_map_dma_range(
            job, job->dma_source, source_span, 0, &source);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_track_mapping(job, source, source_span);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_map_dma_range(
            job, job->dma_destination, destination_span, 1, &destination);
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_job_track_mapping(job, destination, destination_span);
        if (ret)
            return ERR_PTR(ret);

        ret = opengpu_hw_resolve_async(
            job->gpu, lower_32_bits(source),
            lower_32_bits(destination), job->dma_bytes,
            job->dma_height, job->dma_source_stride,
            job->dma_destination_stride, job->dma_sample_mode,
            &job->events, vm, invalidate_phys, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    if (job->type == OPENGPU_SCHED_INVALIDATE) {
        /* Line invalidate is physically tagged in L2 and ignores satp; keep
         * the submitting context's VM active rather than restoring ASID-0. */
        ret = opengpu_hw_invalidate_async(
            job->gpu, lower_32_bits(job->dma_source), job->dma_bytes,
            &job->events, vm, &fence);
        return ret ? ERR_PTR(ret) : fence;
    }
    /* Command-buffer fetches bypass the shared L2 in RTL, so the CPU-written
     * command snapshot needs no invalidate here. Shader instruction fetch
     * still ignores per-page policy and stays cached — drop those lines once
     * under submit_lock before the draw doorbells. */
    {
        const struct opengpu_buffer *snapshots[2];
        unsigned int snapshot_count = 0;
        bool clear_depth =
            opengpu_depth_clear_uses_hw(
                job->gpu->hw.capabilities & GPU_CAP_CLEAR_ENGINE,
                job->depth_clear_dma, job->depth_clear_bytes) &&
            !job->depth_load && !job->depth_discard;

        /* Reach this job's command snapshot through the context's VM.  The
         * mapping is overwritten per job, so it must happen here, after the
         * scheduler's one-credit policy has let the previous job drain; the
         * command client then resolves it under this context's ASID. */
        if (vm && job->context && job->commands.dma) {
            dma_addr_t command_va = 0;

            ret = opengpu_binding_map_command(job->gpu, job->context,
                                              job->commands.dma,
                                              job->commands.size,
                                              &command_va);
            if (ret)
                return ERR_PTR(ret);
            ret = opengpu_job_track_mapping(job, command_va,
                                            job->commands.size);
            if (ret)
                return ERR_PTR(ret);
            job->hw.cmd = (u32)command_va;
        }

        if (job->shader.cpu)
            snapshots[snapshot_count++] = &job->shader;
        if (job->vertex_shader.cpu)
            snapshots[snapshot_count++] = &job->vertex_shader;

        /* Bind snapshot code only after the preceding job has drained. The
         * fragment and vertex stages share the instruction VM; kernarg and
         * vertex-buffer staging share VECTOR_SATP with CU data loads. */
        if (vm && job->context) {
            dma_addr_t code_va = 0, vertex_code_va = 0;
            u32 shader_delta = 0, vertex_delta = 0;
            u32 i;

            if (job->shader.cpu) {
                ret = opengpu_binding_map_code(job->gpu, job->context,
                    job->shader.dma, job->shader.size, 1, &code_va);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, code_va,
                                                job->shader.size);
                if (ret)
                    return ERR_PTR(ret);
                shader_delta = (u32)code_va - (u32)job->shader.dma;
            }
            if (job->vertex_shader.cpu) {
                ret = opengpu_binding_map_code(job->gpu, job->context,
                    job->vertex_shader.dma, job->vertex_shader.size, 2,
                    &vertex_code_va);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, vertex_code_va,
                                                job->vertex_shader.size);
                if (ret)
                    return ERR_PTR(ret);
                vertex_delta = (u32)vertex_code_va - (u32)job->vertex_shader.dma;
            }
            if (job->vertex_shader.cpu) {
                struct gpu_vert_draw_record *records = job->commands.cpu;

                for (i = 0; i < job->hw.cmd_count; i++) {
                    records[i].vert_shader_pc += vertex_delta;
                    records[i].frag_shader_pc += shader_delta;
                }
            } else if (job->shader.cpu) {
                struct gpu_draw_record *records = job->commands.cpu;

                for (i = 0; i < job->hw.cmd_count; i++)
                    records[i].shader_pc += shader_delta;
            }
            dma_wmb();
        }

        /* Reach the colour and depth planes through the context VM too, so the
         * output merger translates render-target traffic under this ASID.  The
         * windows are remapped per job; the previous job has drained. */
        if (vm && job->context) {
            size_t fb_bytes = (size_t)job->hw.stride *
                (job->hw.render_height ? job->hw.render_height : job->gpu->height);
            dma_addr_t color_va = 0, depth_va = 0;

            if (job->hw.color && fb_bytes) {
                ret = opengpu_binding_map_framebuffer(job->gpu, job->context,
                    job->hw.color, fb_bytes, 1, &color_va);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, color_va, fb_bytes);
                if (ret)
                    return ERR_PTR(ret);
                job->hw.color = (u32)color_va;
            }
            if (job->hw.color2 && fb_bytes) {
                ret = opengpu_binding_map_framebuffer(job->gpu, job->context,
                    job->hw.color2, fb_bytes, 1, &color_va);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, color_va, fb_bytes);
                if (ret)
                    return ERR_PTR(ret);
                job->hw.color2 = (u32)color_va;
            }
            if (job->hw.color3 && fb_bytes) {
                ret = opengpu_binding_map_framebuffer(job->gpu, job->context,
                    job->hw.color3, fb_bytes, 1, &color_va);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, color_va, fb_bytes);
                if (ret)
                    return ERR_PTR(ret);
                job->hw.color3 = (u32)color_va;
            }
            if (job->hw.color4 && fb_bytes) {
                ret = opengpu_binding_map_framebuffer(job->gpu, job->context,
                    job->hw.color4, fb_bytes, 1, &color_va);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, color_va, fb_bytes);
                if (ret)
                    return ERR_PTR(ret);
                job->hw.color4 = (u32)color_va;
            }
            if (job->hw.depth && fb_bytes) {
                ret = opengpu_binding_map_framebuffer(job->gpu, job->context,
                    job->hw.depth, fb_bytes, 2, &depth_va);
                if (ret)
                    return ERR_PTR(ret);
                ret = opengpu_job_track_mapping(job, depth_va, fb_bytes);
                if (ret)
                    return ERR_PTR(ret);
                job->hw.depth = (u32)depth_va;
            }
        }

        /* A draw is always a unified render command; the legacy START path was
         * retired, so an unmappable descriptor is a hard failure. */
        dma_addr_t descriptor_va = 0;

        if (vm && job->context && job->render_desc.dma &&
            (job->gpu->hw.capabilities & GPU_CAP_UNIFIED_RENDER)) {
            ret = opengpu_binding_map_render(job->gpu, job->context,
                                             job->render_desc.dma,
                                             job->render_desc.size,
                                             &descriptor_va);
            if (!ret)
                ret = opengpu_job_track_mapping(job, descriptor_va,
                                                job->render_desc.size);
        } else {
            dev_err(job->gpu->dev,
                    "render job requires a unified render command and a VM\n");
            ret = -EIO;
        }
        if (ret)
            return ERR_PTR(ret);
        ret = opengpu_hw_render_async(
            job->gpu, &job->hw, job->render_desc.cpu,
            (u32)descriptor_va, (u32)job->render_desc.size,
            snapshots, snapshot_count, clear_depth,
            lower_32_bits(job->depth_clear_dma), job->depth_clear_bytes,
            0x00ffffffu, &job->events, vm, &fence);
    }
    if (ret)
        return ERR_PTR(ret);
    return fence;
}

int opengpu_compute_drm_ioctl(struct drm_device *drm, void *data,
                              struct drm_file *file)
{
    struct drm_opengpu_submit *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *command_object;
    struct drm_gem_object *color_object;
    struct drm_gem_object *color_object2 = NULL;
    struct drm_gem_object *color_object3 = NULL;
    struct drm_gem_object *color_object4 = NULL;
    struct drm_gem_object *depth_object = NULL;
    struct drm_gem_dma_object *command_dma;
    struct drm_gem_dma_object *color_dma;
    struct drm_gem_dma_object *color_dma2;
    struct drm_gem_dma_object *color_dma3;
    struct drm_gem_dma_object *color_dma4;
    struct drm_gem_dma_object *depth_dma = NULL;
    struct opengpu_depth_desc depth_desc;
    dma_addr_t depth_address = 0;
    struct opengpu_resource_binding *shader, *kernarg, *texture;
    struct opengpu_resource_binding *vertex_buffer, *vertex_shader;
    struct opengpu_resource_binding *vertex_kernarg;
    struct opengpu_resource_binding *resources[6];
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    u64 command_end;
    size_t command_bytes;
    size_t color_required;
    u32 i, j;
    u32 sample_mode, max_mode;
    struct opengpu_render_extent extent;
    int ret;

    ret = opengpu_render_extent_resolve(
        args->render_width, args->render_height, gpu->width, gpu->height,
        gpu->max_width, gpu->max_height,
        !!(gpu->hw.capabilities & GPU_CAP_RENDER_EXTENTS), &extent);
    if (ret)
        return ret;

    /* The legacy unified render descriptor contains one colour address.
     * Do not accept additional handles until their descriptors, VM mappings,
     * reservations and shader outputs are connected end to end. */
    if (args->color_handle2 || args->color_handle3 || args->color_handle4)
        return -EOPNOTSUPP;

    sample_mode = args->sample_mode & OPENGPU_MSAA_MODE_MASK;
    max_mode = (gpu->hw.capabilities & GPU_CAP_MSAA_MAX_MODE_MASK) >>
               GPU_CAP_MSAA_MAX_MODE_SHIFT;

    if ((args->flags & ~(OPENGPU_SUBMIT_TEST_FENCE_DELAY |
                         OPENGPU_SUBMIT_VERTEX_CORE |
                         OPENGPU_SUBMIT_DEPTH_LOAD |
                         OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH |
                         OPENGPU_SUBMIT_COLOR_FORMAT_MASK |
                         OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING)) ||
        ((args->flags & OPENGPU_SUBMIT_COLOR_FORMAT_MASK) >>
         OPENGPU_SUBMIT_COLOR_FORMAT_SHIFT) > OPENGPU_FORMAT_MAX ||
        ((args->flags & OPENGPU_SUBMIT_COLOR_FORMAT_MASK) &&
         !(gpu->hw.capabilities & GPU_CAP_NATIVE_PIXEL_FORMATS)) ||
        ((args->flags & OPENGPU_SUBMIT_DEPTH_LOAD) &&
         !args->depth_handle) ||
        ((args->flags & OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH) &&
         (!(args->flags & OPENGPU_SUBMIT_VERTEX_CORE) ||
          args->depth_handle || (args->flags & OPENGPU_SUBMIT_DEPTH_LOAD))) ||
        ((args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) &&
         !(gpu->hw.capabilities & GPU_CAP_FRAGMENT_INPUT_USAGE)) ||
        !args->context_id || !args->command_handle || !args->color_handle ||
        args->command_handle == args->color_handle ||
        args->color_handle == args->color_handle2 ||
        args->color_handle == args->color_handle3 ||
        args->color_handle == args->color_handle4 ||
        (args->color_handle2 && args->color_handle2 == args->color_handle3) ||
        (args->color_handle2 && args->color_handle2 == args->color_handle4) ||
        (args->color_handle3 && args->color_handle3 == args->color_handle4) ||
        !args->command_count || args->command_count > OPENGPU_MAX_COMMANDS ||
        (args->command_offset & 3) ||
        (args->sample_mode & ~OPENGPU_MSAA_MODE_MASK) ||
        sample_mode > max_mode ||
        (sample_mode && !(gpu->hw.capabilities & GPU_CAP_MSAA)) ||
        args->shader_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->kernarg_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->texture_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->vertex_buffer_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->vertex_shader_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->vertex_kernarg_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        (args->stride & 3) ||
        args->stride < (u64)extent.width * (1u << sample_mode) * 4u ||
        check_mul_overflow((size_t)args->stride, (size_t)extent.height,
                           &color_required) ||
        check_mul_overflow((size_t)args->command_count,
                           sizeof(struct gpu_draw_record), &command_bytes) ||
        check_add_overflow(args->command_offset, (u64)command_bytes,
                           &command_end))
        return -EINVAL;

    command_object = drm_gem_object_lookup(file, args->command_handle);
    if (!command_object)
        return -ENOENT;
    color_object = drm_gem_object_lookup(file, args->color_handle);
    if (!color_object) {
        ret = -ENOENT;
        goto out_command_object;
    }
    if (args->color_handle2) {
        color_object2 = drm_gem_object_lookup(file, args->color_handle2);
        if (!color_object2) {
            ret = -ENOENT;
            goto out_color_object4;
        }
    }
    if (args->color_handle3) {
        color_object3 = drm_gem_object_lookup(file, args->color_handle3);
        if (!color_object3) {
            ret = -ENOENT;
            goto out_color_object4;
        }
    }
    if (args->color_handle4) {
        color_object4 = drm_gem_object_lookup(file, args->color_handle4);
        if (!color_object4) {
            ret = -ENOENT;
            goto out_color_object4;
        }
    }
    if (command_end > command_object->size ||
        color_required > color_object->size ||
        (color_object2 && color_required > color_object2->size) ||
        (color_object3 && color_required > color_object3->size) ||
        (color_object4 && color_required > color_object4->size)) {
        ret = -EINVAL;
        goto out_color_object4;
    }
    command_dma = to_drm_gem_dma_obj(command_object);
    color_dma = to_drm_gem_dma_obj(color_object);
    color_dma2 = color_object2 ? to_drm_gem_dma_obj(color_object2) : NULL;
    color_dma3 = color_object3 ? to_drm_gem_dma_obj(color_object3) : NULL;
    color_dma4 = color_object4 ? to_drm_gem_dma_obj(color_object4) : NULL;
    if (!command_dma->vaddr) {
        ret = -EINVAL;
        goto out_color_object4;
    }
    if (args->depth_handle) {
        if (args->depth_handle == args->command_handle ||
            args->depth_handle == args->color_handle ||
            args->depth_handle == args->color_handle2 ||
            args->depth_handle == args->color_handle3 ||
            args->depth_handle == args->color_handle4) {
            ret = -EINVAL;
            goto out_color_object4;
        }
        depth_object = drm_gem_object_lookup(file, args->depth_handle);
        if (!depth_object) {
            ret = -ENOENT;
            goto out_color_object4;
        }
        depth_dma = to_drm_gem_dma_obj(depth_object);
        depth_desc.offset = args->depth_offset;
        depth_desc.stride = args->stride;
        depth_desc.height = extent.height;
        ret = opengpu_depth_validate(&depth_desc, depth_object->size,
                                     NULL);
        if (ret)
            goto out_color_object4;
        depth_address = depth_dma->dma_addr + args->depth_offset;
    }
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_color_object4;
        }
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    ret = opengpu_resolve_bindings(
        gpu, context, args, &shader, &kernarg, &texture,
        &vertex_buffer, &vertex_shader, &vertex_kernarg);
    if (ret)
        goto out_file;
    resources[0] = shader;
    resources[1] = kernarg;
    resources[2] = texture;
    resources[3] = vertex_buffer;
    resources[4] = vertex_shader;
    resources[5] = vertex_kernarg;
    if (command_object == color_object ||
        command_object == color_object2 ||
        command_object == color_object3 ||
        command_object == color_object4) {
        ret = -EINVAL;
        goto out_file;
    }
    for (i = 0; i < ARRAY_SIZE(resources); i++) {
        if (!resources[i])
            continue;
        if (resources[i]->object == command_object ||
            resources[i]->object == color_object ||
            resources[i]->object == color_object2 ||
            resources[i]->object == color_object3 ||
            resources[i]->object == color_object4 ||
            resources[i]->object == depth_object) {
            ret = -EINVAL;
            goto out_file;
        }
        for (j = 0; j < i; j++) {
            if (resources[j] &&
                resources[i]->object == resources[j]->object) {
                ret = -EINVAL;
                goto out_file;
            }
        }
    }

    /* Reject distinct GEM wrappers of the same known underlying allocation,
     * even when different devices mapped that backing at different IOVAs. */
    {
        struct drm_gem_object *objects[ARRAY_SIZE(resources) + 6];
        unsigned n = 0;

        objects[n++] = command_object;
        objects[n++] = color_object;
        if (color_object2)
            objects[n++] = color_object2;
        if (color_object3)
            objects[n++] = color_object3;
        if (color_object4)
            objects[n++] = color_object4;
        if (depth_object)
            objects[n++] = depth_object;
        for (i = 0; i < ARRAY_SIZE(resources); i++) {
            if (resources[i])
                objects[n++] = resources[i]->object;
        }
        ret = opengpu_gem_backings_disjoint(objects, n);
        if (ret)
            goto out_file;
    }

    /* Object identity alone cannot detect imports with overlapping DMA
     * addresses. Keep the existing exclusive-resource rule for those aliases
     * as well. Separate IOVAs for the same backing pages need provenance
     * validation by the importer and are not proven by this range check. */
    {
        struct opengpu_staging_range ranges[ARRAY_SIZE(resources) + 6];
        unsigned n = 0;

        ranges[n].address = command_dma->dma_addr;
        ranges[n++].bytes = command_object->size;
        ranges[n].address = color_dma->dma_addr;
        ranges[n++].bytes = color_object->size;
        if (color_dma2) {
            ranges[n].address = color_dma2->dma_addr;
            ranges[n++].bytes = color_object2->size;
        }
        if (color_dma3) {
            ranges[n].address = color_dma3->dma_addr;
            ranges[n++].bytes = color_object3->size;
        }
        if (color_dma4) {
            ranges[n].address = color_dma4->dma_addr;
            ranges[n++].bytes = color_object4->size;
        }
        if (depth_object) {
            ranges[n].address = depth_dma->dma_addr;
            ranges[n++].bytes = depth_object->size;
        }
        for (i = 0; i < ARRAY_SIZE(resources); i++) {
            if (!resources[i])
                continue;
            ranges[n].address = resources[i]->dma;
            ranges[n++].bytes = resources[i]->size;
        }
        if (!opengpu_dma_ranges_pairwise_disjoint(ranges, n)) {
            ret = -EINVAL;
            goto out_file;
        }
    }

    if (args->flags & OPENGPU_SUBMIT_TRANSIENT_FRAGMENT_STAGING) {
        struct opengpu_staging_range scratch;
        struct opengpu_staging_range others[ARRAY_SIZE(resources) + 3];
        unsigned n = 0;

        if (!kernarg) {
            ret = -EINVAL;
            goto out_file;
        }
        scratch.address = kernarg->dma;
        scratch.bytes = kernarg->size;
        for (i = 0; i < ARRAY_SIZE(resources); i++) {
            if (!resources[i] || resources[i] == kernarg)
                continue;
            others[n].address = resources[i]->dma;
            others[n++].bytes = resources[i]->size;
        }
        others[n].address = command_dma->dma_addr;
        others[n++].bytes = command_object->size;
        others[n].address = color_dma->dma_addr;
        others[n++].bytes = color_object->size;
        if (depth_object) {
            others[n].address = depth_dma->dma_addr;
            others[n++].bytes = depth_object->size;
        }
        if (!opengpu_staging_disjoint(&scratch, others, n)) {
            ret = -EINVAL;
            goto out_file;
        }
    }

    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    ret = opengpu_prepare_submit_objects(&exec, command_object, color_object,
                                         depth_object,
                                         shader, kernarg, texture,
                                         vertex_buffer, vertex_shader,
                                         vertex_kernarg);
    if (ret)
        goto out_exec;
    /* Commands are snapshotted by the CPU, so a previous writer must finish
     * before validation. Device-side BO dependencies remain asynchronous. */
    ret = opengpu_wait_reservation(command_object, DMA_RESV_USAGE_WRITE);
    if (!ret && shader)
        ret = opengpu_wait_reservation(shader->object,
                                       DMA_RESV_USAGE_WRITE);
    if (!ret && vertex_shader)
        ret = opengpu_wait_reservation(vertex_shader->object,
                                       DMA_RESV_USAGE_WRITE);
    if (ret)
        goto out_exec;

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    sched_job->vm = context->vm.enabled ? &context->vm : NULL;
    sched_job->depth_discard =
        !!(args->flags & OPENGPU_SUBMIT_DISCARD_PRIVATE_DEPTH);
    ret = opengpu_buffer_alloc(gpu, &sched_job->commands, command_bytes);
    if (ret)
        goto out_job;
    ret = opengpu_buffer_alloc(gpu, &sched_job->render_desc, 64);
    if (ret)
        goto out_job;
    if (shader) {
        struct drm_gem_dma_object *shader_dma;

        shader_dma = to_drm_gem_dma_obj(shader->object);
        if (!shader_dma->vaddr) {
            ret = -EINVAL;
            goto out_job;
        }
        ret = opengpu_buffer_alloc(gpu, &sched_job->shader, shader->size);
        if (ret)
            goto out_job;
        memcpy(sched_job->shader.cpu,
               (const u8 *)shader_dma->vaddr + shader->offset,
               shader->size);
    }
    if (vertex_shader) {
        struct drm_gem_dma_object *vertex_shader_dma;

        vertex_shader_dma = to_drm_gem_dma_obj(vertex_shader->object);
        if (!vertex_shader_dma->vaddr) {
            ret = -EINVAL;
            goto out_job;
        }
        ret = opengpu_buffer_alloc(gpu, &sched_job->vertex_shader,
                                   vertex_shader->size);
        if (ret)
            goto out_job;
        memcpy(sched_job->vertex_shader.cpu,
               (const u8 *)vertex_shader_dma->vaddr +
                   vertex_shader->offset,
               vertex_shader->size);
    }
    if (depth_object) {
        sched_job->depth_user = true;
        sched_job->depth_load =
            !!(args->flags & OPENGPU_SUBMIT_DEPTH_LOAD);
        sched_job->depth_clear_dma = depth_address;
        sched_job->depth_clear_bytes =
            (size_t)args->stride * extent.height;
        sched_job->depth_clear_cpu = depth_dma->vaddr ?
            (u8 *)depth_dma->vaddr + args->depth_offset : NULL;
    } else {
        ret = opengpu_buffer_alloc(gpu, &sched_job->depth,
                                   (size_t)args->stride * extent.height);
        if (ret)
            goto out_job;
        sched_job->depth_clear_dma = sched_job->depth.dma;
        sched_job->depth_clear_bytes = sched_job->depth.size;
        sched_job->depth_clear_cpu = sched_job->depth.cpu;
    }

    memcpy(sched_job->commands.cpu,
           (u8 *)command_dma->vaddr + args->command_offset,
           command_bytes);
    if (args->flags & OPENGPU_SUBMIT_VERTEX_CORE)
        ret = opengpu_validate_vertex_commands(
            gpu, &sched_job->commands, args, vertex_buffer,
            &sched_job->vertex_shader, vertex_kernarg,
            &sched_job->shader, kernarg, texture);
    else
        ret = opengpu_validate_commands(
            gpu, &sched_job->commands, args,
            shader ? &sched_job->shader : NULL, kernarg, texture);
    if (ret)
        goto out_job;
    /* Clear-capable hardware clears the depth plane immediately before its
     * draw. Older devices and sub-cache-line ranges use the coherent CPU
     * fallback. A persistent
     * attachment submitted with OPENGPU_SUBMIT_DEPTH_LOAD keeps its stored
     * contents instead. D24S8: the clear leaves stencil 0 under the far depth
     * so stencil counters start from a defined state. The explicit private
     * discard opt-in leaves this scratch plane undefined after all command
     * records have been checked to disable its fixed-function consumers. */
    if (!sched_job->depth_load && !sched_job->depth_discard &&
        !opengpu_depth_clear_uses_hw(
            gpu->hw.capabilities & GPU_CAP_CLEAR_ENGINE,
            sched_job->depth_clear_dma, sched_job->depth_clear_bytes)) {
        u32 *word = (u32 *)sched_job->depth_clear_cpu;
        size_t n;

        if (!word) {
            ret = -EINVAL;
            goto out_job;
        }
        for (n = 0; n < sched_job->depth_clear_bytes / sizeof(u32); n++)
            word[n] = 0x00ffffffu;
        dma_wmb();
    }
    /* Per-draw stencil/blend state rides the command records; the job-level
     * global defaults stay disabled so legacy records keep their meaning. */
    sched_job->hw = (struct opengpu_job) {
        .cmd = sched_job->commands.dma,
        .cmd_count = args->command_count,
        .color = color_dma->dma_addr,
        .color2 = color_dma2 ? color_dma2->dma_addr : 0,
        .color3 = color_dma3 ? color_dma3->dma_addr : 0,
        .color4 = color_dma4 ? color_dma4->dma_addr : 0,
        .depth = sched_job->depth_user ? depth_address
                                       : sched_job->depth.dma,
        .stride = args->stride,
        .render_width = extent.width,
        .render_height = extent.height,
        .depth_test = true,
        .depth_func = GPU_DEPTH_FUNC_LESS,
        .depth_write = true,
        .cull_mode = GPU_CULL_NONE,
        .sample_mode = sample_mode,
        .stencil_test = false,
        .stencil_config = 0,
        .stencil_ref_masks = 0,
        .blend_config = 0,
        .texture = texture ? (texture->va ? texture->va : texture->dma) : 0,
        .texture_width = texture ? texture->width : 0,
        .texture_height = texture ? texture->height : 0,
        .pixel_formats = ((args->flags & OPENGPU_SUBMIT_COLOR_FORMAT_MASK) >>
                          OPENGPU_SUBMIT_COLOR_FORMAT_SHIFT) |
            (texture ? (((texture->flags & OPENGPU_RESOURCE_TEXTURE_FORMAT_MASK) >>
                         OPENGPU_RESOURCE_TEXTURE_FORMAT_SHIFT) << 4) : 0),
        .texture_config = texture ? GPU_TEX_ENABLE |
            (texture->flags & OPENGPU_RESOURCE_TEXTURE_CLAMP ?
             GPU_TEX_WRAP_CLAMP : 0) |
            (((texture->flags & OPENGPU_RESOURCE_TEXTURE_MAX_MIP_MASK) >>
              OPENGPU_RESOURCE_TEXTURE_MAX_MIP_SHIFT) <<
             GPU_TEX_MAX_MIP_SHIFT) : 0,
        .completion_delay_ms =
            args->flags & OPENGPU_SUBMIT_TEST_FENCE_DELAY ? 50 : 0,
    };

    {
        struct opengpu_sched_dep deps[6];
        u32 dep_count = 0;
        u64 fence_seqno = 0;

        deps[dep_count++] = (struct opengpu_sched_dep) {
            color_object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE,
        };
        if (depth_object)
            deps[dep_count++] = (struct opengpu_sched_dep) {
                depth_object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE,
            };
        if (kernarg)
            deps[dep_count++] = (struct opengpu_sched_dep) {
                kernarg->object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE,
            };
        if (texture)
            deps[dep_count++] = (struct opengpu_sched_dep) {
                texture->object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ,
            };
        if (vertex_buffer)
            deps[dep_count++] = (struct opengpu_sched_dep) {
                vertex_buffer->object, DMA_RESV_USAGE_WRITE,
                DMA_RESV_USAGE_READ,
            };
        if (vertex_kernarg)
            deps[dep_count++] = (struct opengpu_sched_dep) {
                vertex_kernarg->object, DMA_RESV_USAGE_READ,
                DMA_RESV_USAGE_WRITE,
            };
        ret = opengpu_sched_job_submit(render_file, file, context, sched_job,
                                       args->in_syncobj, out_sync, deps,
                                       dep_count, &fence_seqno);
        if (ret)
            goto out_job;
        dev_info(gpu->dev,
                 "context %u queued %u draw(s), fence %llu on GEM handle %u\n",
                 context->id, args->command_count, fence_seqno,
                 args->color_handle);
    }
    sched_job = NULL;
    ret = 0;
    goto out_exec;

out_job:
    if (sched_job) {
        opengpu_buffer_free(gpu, &sched_job->render_desc);
        opengpu_buffer_free(gpu, &sched_job->depth);
        opengpu_buffer_free(gpu, &sched_job->vertex_shader);
        opengpu_buffer_free(gpu, &sched_job->shader);
        opengpu_buffer_free(gpu, &sched_job->commands);
        kfree(sched_job);
    }
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
out_color_object4:
    if (color_object4)
        drm_gem_object_put(color_object4);
    if (color_object3)
        drm_gem_object_put(color_object3);
    if (color_object2)
        drm_gem_object_put(color_object2);
    drm_gem_object_put(color_object);
    if (depth_object)
        drm_gem_object_put(depth_object);
out_command_object:
    drm_gem_object_put(command_object);
    return ret;
}

static int opengpu_command_events_init(
    struct opengpu_device *gpu, struct opengpu_command_events *events,
    u32 flags, u32 wait_event, u32 signal_event)
{
    const u32 known = OPENGPU_COMMAND_WAIT_EVENT |
                      OPENGPU_COMMAND_SIGNAL_EVENT;

    if ((flags & ~known) || (wait_event & ~0xffffu) ||
        (signal_event & ~0xffffu) ||
        (!(flags & OPENGPU_COMMAND_WAIT_EVENT) && wait_event) ||
        (!(flags & OPENGPU_COMMAND_SIGNAL_EVENT) && signal_event))
        return -EINVAL;
    if (flags && !(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    events->flags = 0;
    if (flags & OPENGPU_COMMAND_WAIT_EVENT)
        events->flags |= GPU_UCMD_FLAG_WAIT_EVENT;
    if (flags & OPENGPU_COMMAND_SIGNAL_EVENT)
        events->flags |= GPU_UCMD_FLAG_SIGNAL_EVENT;
    events->wait_event = wait_event;
    events->signal_event = signal_event;
    return 0;
}

/* Virtual mappings used by queued DMA jobs can hide physical aliases. Check
 * both known GEM backing identity and the validated DMA bounding ranges before
 * mapping either endpoint into its private GPU window. */
static int opengpu_blit_validate_aliases(struct drm_gem_object *source,
                                          struct drm_gem_object *destination,
                                          u64 source_address, u64 source_end,
                                          u64 destination_address,
                                          u64 destination_end)
{
    struct drm_gem_object *objects[] = { source, destination };
    int ret;

    ret = opengpu_gem_backings_disjoint(objects, ARRAY_SIZE(objects));
    if (ret)
        return ret;
    if (source_address < destination_end && destination_address < source_end)
        return -EINVAL;
    return 0;
}

int opengpu_compute_blit_ioctl(struct drm_device *drm, void *data,
                               struct drm_file *file)
{
    struct drm_opengpu_blit *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *source_object = NULL;
    struct drm_gem_object *destination_object = NULL;
    struct drm_gem_dma_object *source_dma, *destination_dma;
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    struct opengpu_command_events events;
    u64 source_end, destination_end;
    u64 source_address, destination_address;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_BLIT_ENGINE))
        return -EOPNOTSUPP;
    ret = opengpu_command_events_init(gpu, &events, args->flags,
                                      args->wait_event,
                                      args->signal_event);
    if (ret)
        return ret;
    if (!args->context_id || !args->source_handle ||
        !args->destination_handle ||
        args->source_handle == args->destination_handle || !args->bytes ||
        args->bytes > U32_MAX || (args->source_offset & 63) ||
        (args->destination_offset & 63) || (args->bytes & 63) ||
        check_add_overflow(args->source_offset, args->bytes, &source_end) ||
        check_add_overflow(args->destination_offset, args->bytes,
                           &destination_end))
        return -EINVAL;

    source_object = drm_gem_object_lookup(file, args->source_handle);
    if (!source_object)
        return -ENOENT;
    destination_object = drm_gem_object_lookup(file,
                                                args->destination_handle);
    if (!destination_object) {
        ret = -ENOENT;
        goto out_source;
    }
    if (source_end > source_object->size ||
        destination_end > destination_object->size) {
        ret = -EINVAL;
        goto out_destination;
    }
    source_dma = to_drm_gem_dma_obj(source_object);
    destination_dma = to_drm_gem_dma_obj(destination_object);
    if (check_add_overflow((u64)source_dma->dma_addr,
                           args->source_offset, &source_address) ||
        check_add_overflow((u64)destination_dma->dma_addr,
                           args->destination_offset,
                           &destination_address) ||
        check_add_overflow(source_address, args->bytes, &source_end) ||
        check_add_overflow(destination_address, args->bytes,
                           &destination_end) ||
        source_address > U32_MAX || destination_address > U32_MAX ||
        source_end > (1ull << 32) || destination_end > (1ull << 32)) {
        ret = -ERANGE;
        goto out_destination;
    }
    ret = opengpu_blit_validate_aliases(source_object, destination_object,
                                        source_address, source_end,
                                        destination_address, destination_end);
    if (ret)
        goto out_destination;
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_destination;
        }
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    drm_exec_until_all_locked(&exec) {
        ret = drm_exec_prepare_obj(&exec, source_object, 1);
        if (!ret)
            ret = drm_exec_prepare_obj(&exec, destination_object, 1);
        drm_exec_retry_on_contention(&exec);
        if (ret)
            goto out_exec;
    }

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    sched_job->vm = context->vm.enabled ? &context->vm : NULL;
    sched_job->type = OPENGPU_SCHED_BLIT;
    sched_job->dma_source = source_address;
    sched_job->dma_destination = destination_address;
    sched_job->dma_bytes = args->bytes;
    sched_job->events = events;
    {
        const struct opengpu_sched_dep deps[] = {
            { source_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
            { destination_object, DMA_RESV_USAGE_READ,
              DMA_RESV_USAGE_WRITE },
        };

        ret = opengpu_sched_job_submit(render_file, file, context, sched_job,
                                       args->in_syncobj, out_sync, deps,
                                       ARRAY_SIZE(deps), NULL);
    }
    if (ret)
        goto out_job;
    sched_job = NULL;
    ret = 0;
    goto out_exec;

out_job:
    kfree(sched_job);
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
out_destination:
    drm_gem_object_put(destination_object);
out_source:
    drm_gem_object_put(source_object);
    return ret;
}

int opengpu_compute_fill_ioctl(struct drm_device *drm, void *data,
                               struct drm_file *file)
{
    struct drm_opengpu_fill *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *destination_object;
    struct drm_gem_dma_object *destination_dma;
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    struct opengpu_command_events events;
    u64 destination_end, destination_address;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_CLEAR_ENGINE))
        return -EOPNOTSUPP;
    ret = opengpu_command_events_init(gpu, &events, args->flags,
                                      args->wait_event,
                                      args->signal_event);
    if (ret)
        return ret;
    if (!args->context_id || !args->destination_handle || !args->bytes ||
        args->bytes > U32_MAX || (args->destination_offset & 63) ||
        (args->bytes & 63) ||
        check_add_overflow(args->destination_offset, args->bytes,
                           &destination_end))
        return -EINVAL;

    destination_object = drm_gem_object_lookup(file,
                                                args->destination_handle);
    if (!destination_object)
        return -ENOENT;
    if (destination_end > destination_object->size) {
        ret = -EINVAL;
        goto out_destination;
    }
    destination_dma = to_drm_gem_dma_obj(destination_object);
    if (check_add_overflow((u64)destination_dma->dma_addr,
                           args->destination_offset,
                           &destination_address) ||
        check_add_overflow(destination_address, args->bytes,
                           &destination_end) ||
        destination_address > U32_MAX ||
        destination_end > (1ull << 32)) {
        ret = -ERANGE;
        goto out_destination;
    }
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_destination;
        }
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    drm_exec_until_all_locked(&exec) {
        ret = drm_exec_prepare_obj(&exec, destination_object, 1);
        drm_exec_retry_on_contention(&exec);
        if (ret)
            goto out_exec;
    }

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    sched_job->vm = context->vm.enabled ? &context->vm : NULL;
    sched_job->type = OPENGPU_SCHED_FILL;
    sched_job->dma_destination = destination_address;
    sched_job->dma_bytes = args->bytes;
    sched_job->fill_pattern = args->pattern;
    sched_job->events = events;
    {
        const struct opengpu_sched_dep deps[] = {
            { destination_object, DMA_RESV_USAGE_READ,
              DMA_RESV_USAGE_WRITE },
        };

        ret = opengpu_sched_job_submit(render_file, file, context, sched_job,
                                       args->in_syncobj, out_sync, deps,
                                       ARRAY_SIZE(deps), NULL);
    }
    if (ret)
        goto out_job;
    sched_job = NULL;
    ret = 0;
    goto out_exec;

out_job:
    kfree(sched_job);
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
out_destination:
    drm_gem_object_put(destination_object);
    return ret;
}

static bool opengpu_strided_range_end(u64 start, u32 width, u32 height,
                                      u32 stride, u64 *end)
{
    u64 row_offset;

    return check_mul_overflow((u64)(height - 1), (u64)stride,
                              &row_offset) ||
           check_add_overflow(start, row_offset, end) ||
           check_add_overflow(*end, (u64)width, end);
}

int opengpu_compute_strided_blit_ioctl(struct drm_device *drm, void *data,
                                       struct drm_file *file)
{
    struct drm_opengpu_strided_blit *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *source_object = NULL;
    struct drm_gem_object *destination_object = NULL;
    struct drm_gem_dma_object *source_dma, *destination_dma;
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    struct opengpu_command_events events;
    u64 source_end, destination_end;
    u64 source_address, destination_address;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_STRIDED_ENGINE))
        return -EOPNOTSUPP;
    ret = opengpu_command_events_init(gpu, &events, args->flags,
                                      args->wait_event,
                                      args->signal_event);
    if (ret)
        return ret;
    if (!args->context_id || !args->source_handle ||
        !args->destination_handle ||
        args->source_handle == args->destination_handle ||
        !args->width_bytes ||
        !args->height || (args->source_offset & 63) ||
        (args->destination_offset & 63) || (args->width_bytes & 63) ||
        (args->source_stride & 63) || (args->destination_stride & 63) ||
        args->source_stride < args->width_bytes ||
        args->destination_stride < args->width_bytes ||
        opengpu_strided_range_end(args->source_offset, args->width_bytes,
                                  args->height, args->source_stride,
                                  &source_end) ||
        opengpu_strided_range_end(args->destination_offset,
                                  args->width_bytes, args->height,
                                  args->destination_stride,
                                  &destination_end))
        return -EINVAL;

    source_object = drm_gem_object_lookup(file, args->source_handle);
    if (!source_object)
        return -ENOENT;
    destination_object = drm_gem_object_lookup(file,
                                                args->destination_handle);
    if (!destination_object) {
        ret = -ENOENT;
        goto out_source;
    }
    if (source_end > source_object->size ||
        destination_end > destination_object->size) {
        ret = -EINVAL;
        goto out_destination;
    }
    source_dma = to_drm_gem_dma_obj(source_object);
    destination_dma = to_drm_gem_dma_obj(destination_object);
    if (check_add_overflow((u64)source_dma->dma_addr,
                           args->source_offset, &source_address) ||
        check_add_overflow((u64)destination_dma->dma_addr,
                           args->destination_offset,
                           &destination_address) ||
        opengpu_strided_range_end(source_address, args->width_bytes,
                                  args->height, args->source_stride,
                                  &source_end) ||
        opengpu_strided_range_end(destination_address, args->width_bytes,
                                  args->height, args->destination_stride,
                                  &destination_end) ||
        source_address > U32_MAX || destination_address > U32_MAX ||
        source_end > (1ull << 32) || destination_end > (1ull << 32)) {
        ret = -ERANGE;
        goto out_destination;
    }
    ret = opengpu_blit_validate_aliases(source_object, destination_object,
                                        source_address, source_end,
                                        destination_address, destination_end);
    if (ret)
        goto out_destination;
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_destination;
        }
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    drm_exec_until_all_locked(&exec) {
        ret = drm_exec_prepare_obj(&exec, source_object, 1);
        if (!ret)
            ret = drm_exec_prepare_obj(&exec, destination_object, 1);
        drm_exec_retry_on_contention(&exec);
        if (ret)
            goto out_exec;
    }

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    sched_job->vm = context->vm.enabled ? &context->vm : NULL;
    sched_job->type = OPENGPU_SCHED_STRIDED_BLIT;
    sched_job->dma_source = source_address;
    sched_job->dma_destination = destination_address;
    sched_job->dma_bytes = args->width_bytes;
    sched_job->dma_height = args->height;
    sched_job->dma_source_stride = args->source_stride;
    sched_job->dma_destination_stride = args->destination_stride;
    sched_job->events = events;
    {
        const struct opengpu_sched_dep deps[] = {
            { source_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
            { destination_object, DMA_RESV_USAGE_READ,
              DMA_RESV_USAGE_WRITE },
        };

        ret = opengpu_sched_job_submit(render_file, file, context, sched_job,
                                       args->in_syncobj, out_sync, deps,
                                       ARRAY_SIZE(deps), NULL);
    }
    if (ret)
        goto out_job;
    sched_job = NULL;
    ret = 0;
    goto out_exec;

out_job:
    kfree(sched_job);
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
out_destination:
    drm_gem_object_put(destination_object);
out_source:
    drm_gem_object_put(source_object);
    return ret;
}

int opengpu_compute_resolve_ioctl(struct drm_device *drm, void *data,
                                  struct drm_file *file)
{
    struct drm_opengpu_resolve *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *source_object = NULL;
    struct drm_gem_object *destination_object = NULL;
    struct drm_gem_dma_object *source_dma, *destination_dma;
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    struct opengpu_command_events events;
    struct opengpu_resolve_desc desc;
    struct opengpu_resolve_layout layout;
    u64 source_address, destination_address, source_end, destination_end;
    u64 source_relative_end, destination_relative_end;
    bool same_backing;
    u32 max_sample_mode;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_MSAA))
        return -EOPNOTSUPP;
    max_sample_mode = (gpu->hw.capabilities & GPU_CAP_MSAA_MAX_MODE_MASK) >>
                      GPU_CAP_MSAA_MAX_MODE_SHIFT;
    ret = opengpu_command_events_init(gpu, &events, args->flags,
                                      args->wait_event,
                                      args->signal_event);
    if (ret)
        return ret;
    if (!args->context_id || !args->source_handle ||
        !args->destination_handle)
        return -EINVAL;

    source_object = drm_gem_object_lookup(file, args->source_handle);
    if (!source_object)
        return -ENOENT;
    destination_object = drm_gem_object_lookup(file,
                                                args->destination_handle);
    if (!destination_object) {
        ret = -ENOENT;
        goto out_source;
    }

    source_dma = to_drm_gem_dma_obj(source_object);
    destination_dma = to_drm_gem_dma_obj(destination_object);
    /* Overlap is meaningful only in the absolute address space: the source and
     * destination are separate GEM objects whose relative offsets commonly
     * both start at zero.  Translate first, then validate the whole address
     * space; object containment is checked against each backing allocation. */
    if (check_add_overflow((u64)source_dma->dma_addr, args->source_offset,
                           &source_address) ||
        check_add_overflow((u64)destination_dma->dma_addr,
                           args->destination_offset, &destination_address)) {
        ret = -ERANGE;
        goto out_destination;
    }
    desc.source_offset = source_address;
    desc.destination_offset = destination_address;
    desc.width = args->width;
    desc.height = args->height;
    desc.source_stride = args->source_stride;
    desc.destination_stride = args->destination_stride;
    desc.sample_mode = args->sample_mode;
    ret = opengpu_resolve_validate(&desc, 1ull << 32, 1ull << 32,
                                   max_sample_mode, &layout);
    if (ret)
        goto out_destination;

    source_end = layout.source_end;
    destination_end = layout.destination_end;
    if (check_add_overflow(args->source_offset, layout.source_bytes,
                           &source_relative_end) ||
        check_add_overflow(args->destination_offset, layout.destination_bytes,
                           &destination_relative_end) ||
        source_relative_end > source_object->size ||
        destination_relative_end > destination_object->size ||
        source_end > (1ull << 32) || destination_end > (1ull << 32)) {
        ret = -ERANGE;
        goto out_destination;
    }
    ret = opengpu_gem_backings_same(source_object, destination_object,
                                    &same_backing);
    if (ret)
        goto out_destination;
    if (same_backing && args->source_offset < destination_relative_end &&
        args->destination_offset < source_relative_end) {
        ret = -EINVAL;
        goto out_destination;
    }
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_destination;
        }
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    drm_exec_until_all_locked(&exec) {
        ret = drm_exec_prepare_obj(&exec, source_object, 1);
        if (!ret)
            ret = drm_exec_prepare_obj(&exec, destination_object, 1);
        drm_exec_retry_on_contention(&exec);
        if (ret)
            goto out_exec;
    }

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    /* Resolve engine traffic uses the context VM; pre-resolve invalidate stays
     * on the physical source address. */
    sched_job->vm = context->vm.enabled ? &context->vm : NULL;
    sched_job->type = OPENGPU_SCHED_RESOLVE;
    sched_job->dma_source = source_address;
    sched_job->dma_destination = destination_address;
    sched_job->dma_bytes = args->width;
    sched_job->dma_height = args->height;
    sched_job->dma_source_stride = args->source_stride;
    sched_job->dma_destination_stride = args->destination_stride;
    sched_job->dma_sample_mode = args->sample_mode;
    sched_job->events = events;
    {
        const struct opengpu_sched_dep deps[] = {
            { source_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
            { destination_object, DMA_RESV_USAGE_READ,
              DMA_RESV_USAGE_WRITE },
        };

        ret = opengpu_sched_job_submit(render_file, file, context, sched_job,
                                       args->in_syncobj, out_sync, deps,
                                       ARRAY_SIZE(deps), NULL);
    }
    if (ret)
        goto out_job;
    sched_job = NULL;
    ret = 0;
    goto out_exec;

out_job:
    kfree(sched_job);
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
out_destination:
    drm_gem_object_put(destination_object);
out_source:
    drm_gem_object_put(source_object);
    return ret;
}

int opengpu_compute_invalidate_ioctl(struct drm_device *drm, void *data,
                                     struct drm_file *file)
{
    struct drm_opengpu_invalidate *args = data;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct drm_gem_object *object;
    struct drm_gem_dma_object *dma;
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    struct opengpu_command_events events;
    u64 address, end;
    int ret;

    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    if (args->pad)
        return -EINVAL;
    ret = opengpu_command_events_init(gpu, &events, args->flags,
                                      args->wait_event,
                                      args->signal_event);
    if (ret)
        return ret;
    if (!args->context_id || !args->handle || !args->bytes ||
        (args->offset & 63) || (args->bytes & 63) ||
        check_add_overflow(args->offset, args->bytes, &end))
        return -EINVAL;

    object = drm_gem_object_lookup(file, args->handle);
    if (!object)
        return -ENOENT;
    if (end > object->size) {
        ret = -EINVAL;
        goto out_object;
    }
    dma = to_drm_gem_dma_obj(object);
    if (check_add_overflow((u64)dma->dma_addr, args->offset, &address) ||
        address > U32_MAX ||
        check_add_overflow(address, args->bytes, &end) ||
        end > (1ull << 32)) {
        ret = -ERANGE;
        goto out_object;
    }
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync) {
            ret = -ENOENT;
            goto out_object;
        }
    }

    mutex_lock(&render_file->lock);
    context = idr_find(&render_file->contexts, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    drm_exec_until_all_locked(&exec) {
        ret = drm_exec_prepare_obj(&exec, object, 1);
        drm_exec_retry_on_contention(&exec);
        if (ret)
            goto out_exec;
    }

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    /* Addresses stay physical; satp is unused by the invalidate engine, so
     * keep the context VM rather than forcing the ASID-0 identity map. */
    sched_job->vm = context->vm.enabled ? &context->vm : NULL;
    sched_job->type = OPENGPU_SCHED_INVALIDATE;
    sched_job->dma_source = address;
    sched_job->dma_bytes = args->bytes;
    sched_job->events = events;
    {
        const struct opengpu_sched_dep deps[] = {
            { object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_READ },
        };

        ret = opengpu_sched_job_submit(render_file, file, context, sched_job,
                                       args->in_syncobj, out_sync, deps,
                                       ARRAY_SIZE(deps), NULL);
    }
    if (ret)
        goto out_job;
    sched_job = NULL;
    ret = 0;
    goto out_exec;

out_job:
    kfree(sched_job);
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
out_object:
    drm_gem_object_put(object);
    return ret;
}

/* These arrays live in kvmalloc memory, not on the kernel stack. User metadata
 * is copied once before the context lock and never consulted again. */
struct opengpu_compute_texture_prepare {
    struct drm_opengpu_compute_texture_view views[OPENGPU_COMPUTE_TEXTURE_MAX_UNITS];
    struct opengpu_compute_texture_table_entry entries[OPENGPU_COMPUTE_TEXTURE_MAX_UNITS];
    struct opengpu_compute_texture_level_config input[OPENGPU_COMPUTE_TEXTURE_MAX_UNITS][OPENGPU_COMPUTE_TEXTURE_MAX_LEVELS];
    struct opengpu_compute_texture_level_config resolved[OPENGPU_COMPUTE_TEXTURE_MAX_UNITS][OPENGPU_COMPUTE_TEXTURE_MAX_LEVELS];
    struct opengpu_sched_dep deps[1 + OPENGPU_COMPUTE_TEXTURE_MAX_UNITS];
    u32 dep_count;
};

static int opengpu_compute_prepare_texture_views(
    struct opengpu_compute_texture_prepare *p, u32 count,
    struct opengpu_render_context *context, struct opengpu_resource_binding *kernarg,
    const struct opengpu_device *gpu)
{
    const struct opengpu_staging_range write = { kernarg->dma, kernarg->size };
    u32 i, j, k;
    int ret;

    memset(p->entries, 0, sizeof(p->entries));
    p->deps[0] = (struct opengpu_sched_dep) {
        kernarg->object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE,
    };
    p->dep_count = 1;
    for (i = 0; i < count; ++i) {
        const struct drm_opengpu_compute_texture_view *v = &p->views[i];
        struct opengpu_compute_texture_image_config image = {0};
        struct opengpu_compute_texture_sampler_config sampler = {0};
        struct opengpu_resource_binding *binding;
        struct opengpu_staging_range texture;
        u64 gpu_address;
        bool seen = false, buffer = v->target == OPENGPU_TEXTURE_BUFFER_TARGET;
        u32 buffer_count = 0;

        if (!v->slot)
            continue;
        if (buffer && !opengpu_compute_buffer_texture_supported(
                gpu->hw.compute_resource_flags, gpu->hw.capabilities,
                gpu->hw.compute_texture_transport))
            return -EOPNOTSUPP;
        if (v->slot > OPENGPU_MAX_COMPUTE_TEXTURE_RESOURCE_SLOTS || v->pad ||
            (!buffer && v->compare_enable > 1) ||
            v->level_count > OPENGPU_COMPUTE_TEXTURE_MAX_LEVELS)
            return -EINVAL;
        binding = opengpu_binding_lookup(context, v->slot, OPENGPU_RESOURCE_COMPUTE_TEXTURE);
        if (!binding || (context->vm.enabled && !binding->va))
            return -EINVAL;
        ret = opengpu_gem_backings_proven_disjoint(kernarg->object, binding->object);
        if (ret)
            return ret;
        texture = (struct opengpu_staging_range) { binding->dma, binding->size };
        if (kernarg->object->resv == binding->object->resv ||
            !opengpu_staging_disjoint(&write, &texture, 1))
            return -EINVAL;
        for (j = 1; j < p->dep_count; ++j) {
            if (p->deps[j].object == binding->object) { seen = true; break; }
            /* Different wrappers sharing one reservation need separate object
             * references but only one reservation fence; reject until supported. */
            if (p->deps[j].object->resv == binding->object->resv)
                return -EINVAL;
        }
        if (!seen)
            p->deps[p->dep_count++] = (struct opengpu_sched_dep) {
                binding->object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ,
            };
        gpu_address = context->vm.enabled ? binding->va : binding->dma;
        if (buffer && (v->level_count != 1 ||
            !opengpu_buffer_view_count(gpu_address, binding->size, v->levels[0].offset,
                v->levels[0].buffer_bytes, opengpu_texture_buffer_format_bytes(v->format), &buffer_count) ||
            v->buffer_texels != buffer_count || v->levels[0].buffer_texels != buffer_count))
            return -EINVAL;
        image.target=v->target; image.format=v->format; image.width=buffer ? 1u : v->width;
        image.height=v->height; image.depth=v->depth; image.layers=v->layers;
        image.base_level=v->base_level; image.max_level=v->max_level;
        if (buffer) {
            image.buffer_texels=buffer_count; image.buffer_bytes=v->levels[0].buffer_bytes;
        }
        image.swizzle_version=v->swizzle_version;
        for (k = 0; k < 4; ++k) image.swizzle[k]=v->swizzle[k];
        sampler.min_filter=v->min_filter; sampler.mag_filter=v->mag_filter;
        sampler.wrap_s=v->wrap_s; sampler.wrap_t=v->wrap_t; sampler.wrap_r=v->wrap_r;
        sampler.min_lod_bits=v->min_lod_bits; sampler.max_lod_bits=v->max_lod_bits;
        sampler.compare_enable=v->compare_enable; sampler.compare_function=v->compare_function;
        for (k = 0; k < v->level_count; ++k) {
            const struct drm_opengpu_compute_texture_level *l = &v->levels[k];
            struct opengpu_compute_texture_level_config *dst = &p->input[i][k];
            if (l->offset > U32_MAX || l->pad ||
                (l->flags & ~OPENGPU_COMPUTE_TEXTURE_LEVEL_BLOCK_4X4))
                return -EINVAL;
            *dst = (struct opengpu_compute_texture_level_config) {0};
            dst->target=v->target; dst->level=l->level; dst->base=(u32)l->offset;
            dst->width=buffer ? 1u : l->width; dst->height=l->height; dst->depth=l->depth; dst->layers=l->layers;
            dst->row_pitch=buffer ? 0u : l->row_pitch; dst->slice_pitch=l->slice_pitch; dst->layer_pitch=l->layer_pitch;
            dst->block_bytes=l->block_bytes;
            dst->block_4x4=!!(l->flags & OPENGPU_COMPUTE_TEXTURE_LEVEL_BLOCK_4X4);
            if (buffer) { dst->buffer_texels=l->buffer_texels; dst->buffer_bytes=l->buffer_bytes; }
        }
        if (upper_32_bits(gpu_address) ||
            !opengpu_compute_texture_bound_view(&p->entries[i], p->resolved[i],
                OPENGPU_COMPUTE_TEXTURE_MAX_LEVELS, &image, &sampler, p->input[i],
                v->level_count, (u32)gpu_address, binding->size))
            return -EINVAL;
    }
    return 0;
}

struct opengpu_compute_storage_prepare {
    struct drm_opengpu_compute_storage_binding bindings[4];
    u32 records[4][OPENGPU_STORAGE_DESCRIPTOR_WORDS];
    struct opengpu_sched_dep deps[5 + OPENGPU_COMPUTE_TEXTURE_MAX_UNITS];
    struct opengpu_staging_range ranges[5];
    struct drm_gem_object *range_objects[5];
    u32 range_count, dep_count;
};

struct opengpu_compute_image_prepare {
    struct drm_opengpu_compute_image_view views[OPENGPU_COMPUTE_IMAGE_UNITS];
    u32 records[OPENGPU_COMPUTE_IMAGE_UNITS][OPENGPU_COMPUTE_IMAGE_DESCRIPTOR_WORDS];
    struct opengpu_sched_dep deps[1 + OPENGPU_COMPUTE_IMAGE_UNITS + 4 + OPENGPU_COMPUTE_TEXTURE_MAX_UNITS];
    struct opengpu_staging_range ranges[1 + OPENGPU_COMPUTE_IMAGE_UNITS];
    struct drm_gem_object *range_objects[1 + OPENGPU_COMPUTE_IMAGE_UNITS];
    u32 range_count, dep_count;
};

struct opengpu_compute_counter_prepare {
    struct drm_opengpu_compute_counter_binding binding;
    struct drm_opengpu_compute_counter_source source_view;
    struct opengpu_resource_binding source;
    u32 record[OPENGPU_STORAGE_DESCRIPTOR_WORDS];
    struct opengpu_sched_dep deps[2 + 4 + OPENGPU_COMPUTE_IMAGE_UNITS + OPENGPU_COMPUTE_TEXTURE_MAX_UNITS];
    u32 dep_count;
};

/* A private immutable copy avoids exposing new writable VM windows or taking
 * uniform bytes from a changed resource binding after admission. */
struct opengpu_compute_uniform_prepare {
    struct drm_opengpu_compute_uniform_view views[OPENGPU_COMPUTE_UNIFORM_BLOCKS + 1];
    struct opengpu_resource_binding sources[OPENGPU_COMPUTE_UNIFORM_BLOCKS + 1];
    struct opengpu_sched_dep deps[2 + 4 + OPENGPU_COMPUTE_IMAGE_UNITS +
        OPENGPU_COMPUTE_TEXTURE_MAX_UNITS + OPENGPU_COMPUTE_UNIFORM_BLOCKS + 1];
    u32 active_mask, offsets[OPENGPU_COMPUTE_UNIFORM_BLOCKS + 1];
    u32 snapshot_bytes, dep_count;
};

struct opengpu_compute_indirect_prepare {
    struct drm_opengpu_compute_indirect_source view;
    struct opengpu_resource_binding source;
    struct opengpu_indirect_source_plan plan;
    u32 max_groups[3];
    /* One more unique object than the complete uniform/counter active set. */
    struct opengpu_sched_dep deps[3 + 4 + OPENGPU_COMPUTE_IMAGE_UNITS +
        OPENGPU_COMPUTE_TEXTURE_MAX_UNITS + OPENGPU_COMPUTE_UNIFORM_BLOCKS + 1];
    u32 dep_count;
};

/* Track each bound range separately: an object may have several ranges, and
 * each must be checked against every different object's DMA window. The
 * kernarg is entry zero and must remain disjoint even from the same object. */
static int opengpu_compute_record_range(struct drm_gem_object *object,
    const struct opengpu_staging_range *range, struct drm_gem_object **objects,
    struct opengpu_staging_range *ranges, u32 *count)
{
    u32 i;
    for (i = 0; i < *count; ++i) {
        int ret;
        if (i && objects[i] == object) continue;
        ret = opengpu_gem_backings_proven_disjoint(objects[i], object);
        if (ret) return ret;
        if (objects[i]->resv == object->resv ||
            !opengpu_staging_disjoint(range, &ranges[i], 1)) return -EINVAL;
    }
    objects[*count] = object;
    ranges[(*count)++] = *range;
    return 0;
}

/* Retain one GEM reference and the strongest reservation usage per object. */
static void opengpu_compute_add_dep(struct opengpu_sched_dep *deps, u32 *count,
                                    struct drm_gem_object *object, bool write)
{
    u32 i;
    for (i = 1; i < *count; ++i) {
        if (deps[i].object != object) continue;
        if (write) {
            deps[i].wait_usage = DMA_RESV_USAGE_READ;
            deps[i].signal_usage = DMA_RESV_USAGE_WRITE;
        }
        return;
    }
    deps[(*count)++] = (struct opengpu_sched_dep) {
        object, write ? DMA_RESV_USAGE_READ : DMA_RESV_USAGE_WRITE,
        write ? DMA_RESV_USAGE_WRITE : DMA_RESV_USAGE_READ,
    };
}

/* Bind locally proven backings and retain each object's strongest usage. */
static int opengpu_compute_prepare_images(
    struct opengpu_compute_image_prepare *p,
    struct opengpu_render_context *context, struct opengpu_resource_binding *shader,
    struct opengpu_resource_binding *kernarg, u32 context_id,
    const struct opengpu_device *gpu)
{
    const struct opengpu_staging_range shader_range = { shader->dma, shader->size };
    u32 i;
    int ret;

    if (!context->vm.enabled) return -EOPNOTSUPP;
    ret = opengpu_gem_backings_proven_disjoint(shader->object, kernarg->object);
    if (ret) return ret;
    if (shader->object->resv == kernarg->object->resv) return -EINVAL;
    p->dep_count = 1;
    p->deps[0] = (struct opengpu_sched_dep) { kernarg->object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE };
    p->ranges[0] = (struct opengpu_staging_range) { kernarg->dma, kernarg->size };
    p->range_objects[0] = kernarg->object; p->range_count = 1;
    for (i = 0; i < OPENGPU_COMPUTE_IMAGE_UNITS; ++i) {
        const struct drm_opengpu_compute_image_view *v = &p->views[i];
        struct opengpu_compute_image_descriptor d = {0};
        struct opengpu_resource_binding *b;
        struct opengpu_staging_range range;
        bool write, buffer = v->target == OPENGPU_TEXTURE_BUFFER_TARGET;
        u32 buffer_count = 0;

        if (!v->slot) {
            if (memchr_inv(v, 0, sizeof(*v))) return -EINVAL;
            p->records[i][0]=0x47495501u; p->records[i][1]=256;
            p->records[i][2]=1; p->records[i][3]=context_id; p->records[i][6]=i;
            continue;
        }
        if (buffer && !opengpu_compute_buffer_images_supported(
                gpu->hw.compute_resource_flags, gpu->hw.capabilities,
                gpu->hw.compute_image_transport))
            return -EOPNOTSUPP;
        if (v->slot > OPENGPU_MAX_RESOURCE_BINDINGS || v->pad ||
            (v->flags & ~(OPENGPU_COMPUTE_IMAGE_READ | OPENGPU_COMPUTE_IMAGE_WRITE |
                          OPENGPU_COMPUTE_IMAGE_BY_SIZE | OPENGPU_COMPUTE_IMAGE_LAYERED)) ||
            !(v->flags & (OPENGPU_COMPUTE_IMAGE_READ | OPENGPU_COMPUTE_IMAGE_WRITE)) ||
            v->offset > U32_MAX)
            return -EINVAL;
        if (v->slot >= 39 &&
            !opengpu_compute_image_backings_supported(gpu->hw.capabilities,
                gpu->hw.compute_storage_transport, gpu->hw.compute_image_transport))
            return -EOPNOTSUPP;
        b = opengpu_image_binding_lookup(context, v->slot);
        if (!b || !b->va || upper_32_bits(b->va)) return -EINVAL;
        if (buffer && (!opengpu_buffer_view_count(b->va, b->size, v->offset, v->buffer_bytes,
                opengpu_texture_buffer_format_bytes(v->storage_format), &buffer_count) ||
            v->buffer_texels != buffer_count)) return -EINVAL;
        ret = opengpu_gem_backings_proven_disjoint(shader->object, b->object);
        if (ret) return ret;
        range = (struct opengpu_staging_range) { b->dma, b->size };
        if (shader->object->resv == b->object->resv ||
            !opengpu_staging_disjoint(&range, &shader_range, 1)) return -EINVAL;
        ret = opengpu_compute_record_range(b->object, &range, p->range_objects,
                                           p->ranges, &p->range_count);
        if (ret) return ret;
        d.context=context_id; d.unit=i;
        d.storage_format=v->storage_format; d.image_format=v->image_format;
        d.level=d.base_level=d.max_level=v->level; d.layer=v->layer;
        d.readable=!!(v->flags & OPENGPU_COMPUTE_IMAGE_READ);
        d.writable=!!(v->flags & OPENGPU_COMPUTE_IMAGE_WRITE);
        d.compatible_by_size=!!(v->flags & OPENGPU_COMPUTE_IMAGE_BY_SIZE);
        d.layered=!!(v->flags & OPENGPU_COMPUTE_IMAGE_LAYERED);
        d.view.target=v->target; d.view.level=v->level; d.view.base=(u32)v->offset;
        d.view.width=buffer ? 1u : v->width; d.view.height=v->height; d.view.depth=v->depth;
        d.view.layers=v->layers; d.view.row_pitch=buffer ? 0u : v->row_pitch;
        d.view.slice_pitch=v->slice_pitch; d.view.layer_pitch=v->layer_pitch;
        d.view.block_bytes=buffer ? opengpu_texture_buffer_format_bytes(v->storage_format) :
            opengpu_compute_image_format_bytes(v->storage_format);
        if (buffer) { d.view.buffer_texels=buffer_count; d.view.buffer_bytes=v->buffer_bytes; }
        if (!opengpu_build_compute_image_descriptor(p->records[i], &d, (u32)b->va, b->size))
            return -EINVAL;
        write=d.writable;
        opengpu_compute_add_dep(p->deps, &p->dep_count, b->object, write);
    }
    return 0;
}

static int opengpu_compute_prepare_storage(
    struct opengpu_compute_storage_prepare *p,
    struct opengpu_render_context *context, struct opengpu_resource_binding *shader,
    struct opengpu_resource_binding *kernarg, u32 context_id)
{
    u32 i;
    int ret;
    const struct opengpu_staging_range shader_range = { shader->dma, shader->size };
    if (!context->vm.enabled)
        return -EOPNOTSUPP;
    p->dep_count = 1;
    p->deps[0] = (struct opengpu_sched_dep) {
        kernarg->object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE,
    };
    p->ranges[0] = (struct opengpu_staging_range) { kernarg->dma, kernarg->size };
    p->range_objects[0] = kernarg->object; p->range_count = 1;
    ret = opengpu_gem_backings_proven_disjoint(shader->object, kernarg->object);
    if (ret) return ret;
    if (shader->object->resv == kernarg->object->resv) return -EINVAL;
    for (i = 0; i < 4; ++i) {
        const struct drm_opengpu_compute_storage_binding *v = &p->bindings[i];
        struct opengpu_resource_binding *b;
        struct opengpu_storage_descriptor d = {0};
        struct opengpu_staging_range range;
        bool write;
        if (!v->slot && !v->access) continue;
        if (!v->slot || v->slot > OPENGPU_MAX_RESOURCE_BINDINGS || !v->access ||
            (v->access & ~(OPENGPU_COMPUTE_STORAGE_READ | OPENGPU_COMPUTE_STORAGE_WRITE)))
            return -EINVAL;
        b = opengpu_binding_lookup(context, v->slot, OPENGPU_RESOURCE_COMPUTE_STORAGE);
        if (!b || !b->va || (b->va & 255) || (b->dma & 63))
            return -EINVAL;
        ret = opengpu_gem_backings_proven_disjoint(shader->object, b->object);
        if (ret) return ret;
        range = (struct opengpu_staging_range) { b->dma, b->size };
        if (shader->object->resv == b->object->resv ||
            !opengpu_staging_disjoint(&range, &shader_range, 1)) return -EINVAL;
        ret = opengpu_compute_record_range(b->object, &range, p->range_objects,
                                           p->ranges, &p->range_count);
        if (ret) return ret;
        d.context = context_id; d.binding = i; d.base = (u32)b->va;
        d.limit = (u64)b->va + b->size;
        /* resource_bind proved this complete-line tail is in the GEM; only
         * the allocation envelope is rounded, never the shader's view. */
        d.allocation_base = (u32)b->va;
        d.allocation_limit = (d.limit + 63ull) & ~63ull;
        d.readable = !!(v->access & OPENGPU_COMPUTE_STORAGE_READ);
        d.writable = !!(v->access & OPENGPU_COMPUTE_STORAGE_WRITE);
        if (upper_32_bits(b->va) || !opengpu_encode_storage_descriptor(p->records[i], &d))
            return -EINVAL;
        write = d.writable;
        opengpu_compute_add_dep(p->deps, &p->dep_count, b->object, write);
    }
    return 0;
}

/* Local views of the same GEM object share one reservation regardless of
 * range. Unrelated overlapping or unprovable backings remain rejected. */
static int opengpu_compute_combine_resources(
    struct opengpu_compute_storage_prepare *storage,
    struct opengpu_compute_texture_prepare *textures, u32 count,
    struct opengpu_render_context *context)
{
    u32 i, j;
    for (i = 0; i < count; ++i) {
        struct opengpu_resource_binding *b;
        struct opengpu_staging_range texture;
        int ret;
        if (!textures->views[i].slot) continue;
        b = opengpu_binding_lookup(context, textures->views[i].slot,
                                   OPENGPU_RESOURCE_COMPUTE_TEXTURE);
        if (!b) return -EINVAL;
        texture = (struct opengpu_staging_range) {b->dma, b->size};
        for (j = 1; j < storage->range_count; ++j) {
            if (storage->range_objects[j] == b->object)
                continue;
            ret = opengpu_gem_backings_proven_disjoint(storage->range_objects[j], b->object);
            if (ret) return ret;
            if (storage->range_objects[j]->resv == b->object->resv ||
                !opengpu_staging_disjoint(&texture, &storage->ranges[j], 1))
                return -EINVAL;
        }
    }
    for (i = 1; i < textures->dep_count; ++i) {
        for (j = 1; j < storage->dep_count; ++j)
            if (storage->deps[j].object == textures->deps[i].object)
                break;
        if (j == storage->dep_count)
            storage->deps[storage->dep_count++] = textures->deps[i];
    }
    return 0;
}

/* Local views of one GEM object share a reservation even when their ranges
 * differ. The image dependency already covers texture reads and any writes. */
static int opengpu_compute_combine_textured_images(
    struct opengpu_compute_image_prepare *images,
    struct opengpu_compute_texture_prepare *textures, u32 count,
    struct opengpu_render_context *context)
{
    u32 i, j;
    for (i = 0; i < count; ++i) {
        struct opengpu_resource_binding *b;
        struct opengpu_staging_range range;
        int ret;
        if (!textures->views[i].slot) continue;
        b = opengpu_binding_lookup(context, textures->views[i].slot,
                                   OPENGPU_RESOURCE_COMPUTE_TEXTURE);
        if (!b) return -EINVAL;
        range = (struct opengpu_staging_range) { b->dma, b->size };
        for (j = 1; j < images->range_count; ++j) {
            if (images->range_objects[j] == b->object)
                continue;
            ret = opengpu_gem_backings_proven_disjoint(images->range_objects[j], b->object);
            if (ret) return ret;
            if (images->range_objects[j]->resv == b->object->resv ||
                !opengpu_staging_disjoint(&range, &images->ranges[j], 1))
                return -EINVAL;
        }
    }
    for (i = 1; i < textures->dep_count; ++i) {
        for (j = 1; j < images->dep_count; ++j)
            if (images->deps[j].object == textures->deps[i].object)
                break;
        if (j == images->dep_count)
            images->deps[images->dep_count++] = textures->deps[i];
    }
    return 0;
}

/* Different local views of the same GEM object share one reservation. Other
 * cross-client backings must be proven disjoint. */
static int opengpu_compute_check_image_storage(
    const struct opengpu_compute_image_prepare *images,
    const struct opengpu_compute_storage_prepare *storage)
{
    u32 i, j;
    for (i = 1; i < images->range_count; ++i) {
        for (j = 1; j < storage->range_count; ++j) {
            int ret;
            if (images->range_objects[i] == storage->range_objects[j])
                continue;
            ret = opengpu_gem_backings_proven_disjoint(
                images->range_objects[i], storage->range_objects[j]);
            if (ret) return ret;
            if (images->range_objects[i]->resv == storage->range_objects[j]->resv ||
                !opengpu_staging_disjoint(&images->ranges[i], &storage->ranges[j], 1))
                return -EINVAL;
        }
    }
    return 0;
}

/* Storage may already retain an exact texture alias. Merge it once and
 * promote the read dependency to write when storage is writable. */
static void opengpu_compute_merge_image_storage_deps(
    struct opengpu_compute_image_prepare *images,
    const struct opengpu_compute_storage_prepare *storage)
{
    u32 i, j;
    for (i = 1; i < storage->dep_count; ++i) {
        for (j = 1; j < images->dep_count; ++j)
            if (images->deps[j].object == storage->deps[i].object)
                break;
        if (j == images->dep_count)
            images->deps[images->dep_count++] = storage->deps[i];
        else if (storage->deps[i].signal_usage == DMA_RESV_USAGE_WRITE)
            images->deps[j] = storage->deps[i];
    }
}

static int opengpu_counter_check_disjoint(struct opengpu_resource_binding *counter,
    struct drm_gem_object *object, const struct opengpu_staging_range *range)
{
    const struct opengpu_staging_range counter_range = { counter->dma, counter->size };
    int ret = opengpu_gem_backings_proven_disjoint(counter->object, object);

    if (ret) return ret;
    if (counter->object->resv == object->resv ||
        !opengpu_staging_disjoint(&counter_range, range, 1)) return -EINVAL;
    return 0;
}

static void opengpu_compute_counter_deps(struct opengpu_compute_counter_prepare *p,
    struct opengpu_resource_binding *kernarg,
    const struct opengpu_compute_texture_prepare *textures,
    const struct opengpu_compute_storage_prepare *storage,
    const struct opengpu_compute_image_prepare *images)
{
    if (images) {
        p->dep_count = images->dep_count;
        memcpy(p->deps, images->deps, p->dep_count * sizeof(p->deps[0]));
    } else if (storage) {
        p->dep_count = storage->dep_count;
        memcpy(p->deps, storage->deps, p->dep_count * sizeof(p->deps[0]));
    } else if (textures) {
        p->dep_count = textures->dep_count;
        memcpy(p->deps, textures->deps, p->dep_count * sizeof(p->deps[0]));
    } else {
        p->dep_count = 1;
        p->deps[0] = (struct opengpu_sched_dep) {
            kernarg->object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE,
        };
    }
}

/* A mutable source borrows local GEM pages, not a permanent storage window.
 * Its WRITE reservation owns the whole allocation through safe retirement. */
static int opengpu_compute_prepare_counter_source(struct opengpu_compute_counter_prepare *p,
    struct drm_file *file, struct opengpu_render_context *context,
    struct opengpu_resource_binding *shader, struct opengpu_resource_binding *kernarg,
    u32 context_id, const struct opengpu_compute_texture_prepare *textures,
    const struct opengpu_compute_storage_prepare *storage,
    const struct opengpu_compute_image_prepare *images)
{
    const struct drm_opengpu_compute_counter_source *v = &p->source_view;
    struct opengpu_counter_source_plan plan;
    struct opengpu_storage_descriptor d = { 0 };
    struct opengpu_resource_binding allocation;
    struct drm_gem_dma_object *dma;
    u32 i;
    int ret;

    if (!context->vm.enabled) return -EOPNOTSUPP;
    p->source.object = drm_gem_object_lookup(file, v->handle);
    if (!p->source.object) return -ENOENT;
    if (p->source.object->import_attach) return -EOPNOTSUPP;
    dma = to_drm_gem_dma_obj(p->source.object);
    ret = opengpu_compute_counter_source_plan(dma->dma_addr, p->source.object->size,
                                              v->offset, v->count, &plan);
    if (ret) return ret;
    allocation = (struct opengpu_resource_binding) {
        .object=p->source.object, .dma=dma->dma_addr, .size=p->source.object->size,
    };
    opengpu_compute_counter_deps(p, kernarg, textures, storage, images);
    for (i = 0; i <= p->dep_count; ++i) {
        struct drm_gem_object *other = i == p->dep_count ? shader->object : p->deps[i].object;
        struct opengpu_staging_range range = {
            to_drm_gem_dma_obj(other)->dma_addr, other->size,
        };
        ret = opengpu_counter_check_disjoint(&allocation, other, &range);
        if (ret) return ret;
    }
    p->source.dma = plan.data_dma; p->source.va = plan.mapping.va;
    p->source.size = plan.bytes; p->source.offset = v->offset;
    d.context = context_id; d.base = (u32)plan.mapping.va;
    d.limit = plan.mapping.va + plan.bytes;
    d.allocation_base = (u32)plan.allocation_base;
    d.allocation_limit = plan.allocation_limit;
    d.readable = d.writable = true;
    if (!opengpu_encode_atomic_counter_descriptor(p->record, &d)) return -EINVAL;
    opengpu_compute_add_dep(p->deps, &p->dep_count, p->source.object, true);
    return 0;
}

/* Retain and fence the entire backing; reject aliases until ordering is proven. */
static int opengpu_compute_prepare_counter(struct opengpu_compute_counter_prepare *p,
    struct opengpu_render_context *context, struct opengpu_resource_binding *shader,
    struct opengpu_resource_binding *kernarg, u32 context_id,
    const struct opengpu_compute_texture_prepare *textures, u32 texture_count,
    const struct opengpu_compute_storage_prepare *storage,
    const struct opengpu_compute_image_prepare *images)
{
    const struct drm_opengpu_compute_counter_binding *v = &p->binding;
    struct opengpu_storage_descriptor d = { 0 };
    struct opengpu_resource_binding *b;
    struct opengpu_staging_range range;
    u64 end, bytes;
    u32 i;
    int ret;

    if (!context->vm.enabled) return -EOPNOTSUPP;
    ret = opengpu_gem_backings_proven_disjoint(shader->object, kernarg->object);
    if (ret) return ret;
    if (shader->object->resv == kernarg->object->resv) return -EINVAL;
    if (!v->slot || v->slot > OPENGPU_MAX_RESOURCE_BINDINGS ||
        !v->count || v->count > 8 || (v->offset & 3)) return -EINVAL;
    b = opengpu_binding_lookup(context, v->slot, OPENGPU_RESOURCE_COMPUTE_STORAGE);
    bytes = (u64)v->count * 4;
    if (!b || !b->va || upper_32_bits(b->va) || (b->va & 63) ||
        (b->dma & 63) || (b->size & 63) || v->offset > b->size ||
        bytes > b->size - v->offset) return -EINVAL;
    end = (u64)b->va + b->size;
    if (end > (1ULL << 32) || end < b->va) return -EINVAL;
    range = (struct opengpu_staging_range) { shader->dma, shader->size };
    ret = opengpu_counter_check_disjoint(b, shader->object, &range);
    if (ret) return ret;
    range = (struct opengpu_staging_range) { kernarg->dma, kernarg->size };
    ret = opengpu_counter_check_disjoint(b, kernarg->object, &range);
    if (ret) return ret;
    if (storage) for (i = 1; i < storage->range_count; ++i) {
        ret = opengpu_counter_check_disjoint(b, storage->range_objects[i], &storage->ranges[i]);
        if (ret) return ret;
    }
    if (images) for (i = 1; i < images->range_count; ++i) {
        ret = opengpu_counter_check_disjoint(b, images->range_objects[i], &images->ranges[i]);
        if (ret) return ret;
    }
    if (textures) for (i = 0; i < texture_count; ++i) {
        struct opengpu_resource_binding *t;
        if (!textures->views[i].slot) continue;
        t = opengpu_binding_lookup(context, textures->views[i].slot,
                                   OPENGPU_RESOURCE_COMPUTE_TEXTURE);
        if (!t) return -EINVAL;
        range = (struct opengpu_staging_range) { t->dma, t->size };
        ret = opengpu_counter_check_disjoint(b, t->object, &range);
        if (ret) return ret;
    }
    d.context = context_id; d.binding = 0;
    d.base = (u32)((u64)b->va + v->offset); d.limit = (u64)d.base + bytes;
    d.allocation_base = (u32)b->va; d.allocation_limit = end;
    d.readable = d.writable = true;
    if (!opengpu_encode_atomic_counter_descriptor(p->record, &d)) return -EINVAL;
    opengpu_compute_counter_deps(p, kernarg, textures, storage, images);
    opengpu_compute_add_dep(p->deps, &p->dep_count, b->object, true);
    return 0;
}

/* Capture per-file sources and merge the exact reservations later waited and
 * fenced. Only read-only aliases are permitted, never a current job writer. */
static int opengpu_compute_prepare_uniforms(struct opengpu_compute_uniform_prepare *p,
    struct drm_file *file, struct opengpu_render_context *context,
    struct opengpu_resource_binding *shader, struct opengpu_resource_binding *kernarg,
    const struct opengpu_compute_texture_prepare *textures,
    const struct opengpu_compute_storage_prepare *storage,
    const struct opengpu_compute_image_prepare *images,
    const struct opengpu_compute_counter_prepare *counter)
{
    const struct opengpu_sched_dep *deps;
    const struct opengpu_staging_range shader_range = { shader->dma, shader->size };
    u32 count, i, j, cursor = 4096;
    int ret;

    if (!context->vm.enabled)
        return -EOPNOTSUPP;
    if ((p->active_mask & ~0xfffu) ||
        (!p->active_mask && !p->views[OPENGPU_COMPUTE_UNIFORM_BLOCKS].handle))
        return -EINVAL;
    if (counter) { deps = counter->deps; count = counter->dep_count; }
    else if (images) { deps = images->deps; count = images->dep_count; }
    else if (storage) { deps = storage->deps; count = storage->dep_count; }
    else if (textures) { deps = textures->deps; count = textures->dep_count; }
    else {
        p->deps[0] = (struct opengpu_sched_dep) {
            kernarg->object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE,
        };
        deps = p->deps; count = 1;
    }
    if (deps != p->deps)
        memcpy(p->deps, deps, count * sizeof(p->deps[0]));
    p->dep_count = count;
    for (i = 0; i < ARRAY_SIZE(p->views); ++i) {
        const struct drm_opengpu_compute_uniform_view *v = &p->views[i];
        struct opengpu_resource_binding *source = &p->sources[i];
        struct opengpu_resource_binding allocation;
        struct drm_gem_dma_object *dma;
        bool defaults = i == OPENGPU_COMPUTE_UNIFORM_BLOCKS;
        bool active = defaults ? !!v->handle : !!(p->active_mask & (1u << i));
        u64 end;

        if (!active) {
            if (v->handle || v->bytes || v->offset)
                return -EINVAL;
            continue;
        }
        if (!v->handle || !v->bytes || (v->offset & 3) || (v->bytes & 3) ||
            v->bytes > (defaults ? OPENGPU_COMPUTE_UNIFORM_DEFAULT_BYTES :
                                  OPENGPU_COMPUTE_UNIFORM_BLOCK_BYTES) ||
            check_add_overflow(v->offset, (u64)v->bytes, &end))
            return -EINVAL;
        source->object = drm_gem_object_lookup(file, v->handle);
        if (!source->object)
            return -ENOENT;
        /* An imported mapping is not an immutable local CPU source contract. */
        if (source->object->import_attach)
            return -EOPNOTSUPP;
        if (end > source->object->size)
            return -EINVAL;
        dma = to_drm_gem_dma_obj(source->object);
        if (!dma->vaddr || check_add_overflow((u64)dma->dma_addr, v->offset, &end))
            return -EINVAL;
        source->dma = end; source->offset = v->offset; source->size = v->bytes;
        if (check_add_overflow(end, source->size, &end))
            return -EINVAL;
        allocation = (struct opengpu_resource_binding) {
            .object = source->object, .dma = dma->dma_addr, .size = source->object->size,
        };
        if (check_add_overflow((u64)allocation.dma, allocation.size, &end))
            return -EINVAL;
        ret = opengpu_counter_check_disjoint(&allocation, shader->object, &shader_range);
        if (ret)
            return ret;
        /* Different reservations cannot synchronize overlapping allocations,
         * including another read-only texture's prior producer. Only a known
         * same-object READ alias may share its reservation and DMA range. */
        for (j = 0; j < count; ++j) {
            struct drm_gem_object *object = p->deps[j].object;
            struct drm_gem_dma_object *other = to_drm_gem_dma_obj(object);
            const struct opengpu_staging_range range = { other->dma_addr, object->size };

            if (source->object != object || p->deps[j].signal_usage == DMA_RESV_USAGE_WRITE) {
                ret = opengpu_counter_check_disjoint(&allocation, object, &range);
                if (ret)
                    return ret;
            }
        }
        if (storage) for (j = 1; j < storage->range_count; ++j) {
            ret = opengpu_counter_check_disjoint(&allocation, storage->range_objects[j], &storage->ranges[j]);
            if (ret) return ret;
        }
        if (images) for (j = 1; j < images->range_count; ++j) {
            ret = opengpu_counter_check_disjoint(&allocation, images->range_objects[j], &images->ranges[j]);
            if (ret) return ret;
        }
        for (j = 0; j < i; ++j) {
            struct opengpu_resource_binding *other = &p->sources[j];
            struct opengpu_staging_range range;

            if (!other->object || other->object == source->object)
                continue;
            range = (struct opengpu_staging_range) {
                to_drm_gem_dma_obj(other->object)->dma_addr, other->object->size,
            };
            ret = opengpu_counter_check_disjoint(&allocation, other->object, &range);
            if (ret) return ret;
        }
        opengpu_compute_add_dep(p->deps, &p->dep_count, source->object, false);
        cursor = (cursor + (defaults ? 63u : 255u)) & ~(defaults ? 63u : 255u);
        p->offsets[i] = cursor;
        cursor += (v->bytes + 63u) & ~63u;
    }
    p->snapshot_bytes = (cursor + 4095u) & ~4095u;
    return p->snapshot_bytes <= OPENGPU_COMPUTE_UNIFORM_SNAPSHOT_MAX_BYTES ? 0 : -EINVAL;
}

/* Called with every source reservation locked, after producer/input waits. */
static int opengpu_compute_snapshot_uniforms(const struct opengpu_compute_uniform_prepare *p,
    struct opengpu_sched_job *job, struct opengpu_render_context *context,
    u32 context_id, u32 private_base, u64 private_bytes)
{
    struct opengpu_compute_uniform_root_config root = { .context = context_id,
        .active_mask = p->active_mask, .default_present = !!p->sources[12].object };
    struct opengpu_kernarg_va_plan plan;
    u32 words[16], i;
    u64 address, private_limit = (u64)private_base + private_bytes;
    int ret;

    if (!context->vm.enabled || !private_bytes || private_limit > (1ULL << 32))
        return -EINVAL;
    ret = opengpu_buffer_alloc(job->gpu, &job->uniform_snapshot, p->snapshot_bytes);
    if (ret) return ret;
    if (job->uniform_snapshot.dma & 255)
        return -EINVAL;
    ret = opengpu_command_va_plan(job->uniform_snapshot.dma, p->snapshot_bytes,
            OPENGPU_COMPUTE_UNIFORM_SNAPSHOT_SLOT, OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret) return ret;
    address = plan.va;
    if (upper_32_bits(address) || address + p->snapshot_bytes > (1ULL << 32) ||
        (address < private_limit && address + p->snapshot_bytes > private_base))
        return -EINVAL;
    memset(job->uniform_snapshot.cpu, 0, p->snapshot_bytes);
    if (p->active_mask) {
        root.bindings_base = (u32)address + 64;
        root.bindings_limit = address + 64 + 24 * 64;
    }
    if (root.default_present)
        root.default_view = (u32)address + 64 + 24 * 64;
    for (i = 0; i < OPENGPU_COMPUTE_UNIFORM_BLOCKS; ++i)
        if (p->active_mask & (1u << i)) root.mapping[i] = i;
    if (!opengpu_encode_compute_uniform_root(words, &root))
        return -EINVAL;
    opengpu_compute_texture_store_words(job->uniform_snapshot.cpu, words, 16);
    for (i = 0; i < ARRAY_SIZE(p->sources); ++i) {
        const struct opengpu_resource_binding *source = &p->sources[i];
        bool defaults = i == OPENGPU_COMPUTE_UNIFORM_BLOCKS;
        struct opengpu_compute_uniform_binding_config binding = { .context = context_id,
            .index = defaults ? OPENGPU_UNIFORM_DEFAULT_BLOCK : i };

        if (!source->object) continue;
        binding.base = (u32)address + p->offsets[i];
        binding.limit = (u64)binding.base + source->size;
        binding.allocation_base = binding.base;
        binding.allocation_limit = (binding.limit + 63) & ~63ULL;
        if (!opengpu_encode_compute_uniform_binding(words, &binding))
            return -EINVAL;
        opengpu_compute_texture_store_words((u8 *)job->uniform_snapshot.cpu +
            (defaults ? 64 + 24 * 64 : 64 + i * 64), words, 16);
        memcpy((u8 *)job->uniform_snapshot.cpu + p->offsets[i],
            (const u8 *)to_drm_gem_dma_obj(source->object)->vaddr + source->offset,
            source->size);
    }
    job->kernel.uniform_enabled = true;
    job->kernel.uniform = (struct opengpu_compute_uniform_command_context) {
        .version = 1, .root = (u32)address, .context = context_id,
        .private_base = private_base, .private_limit = private_limit,
    };
    return 0;
}

/* The source is intentionally independent even of read-only peers: distinct
 * reservations cannot synchronize overlapping allocations. */
static int opengpu_compute_prepare_indirect(struct opengpu_compute_indirect_prepare *p,
    struct drm_file *file, struct opengpu_render_context *context,
    struct opengpu_resource_binding *shader,
    const struct opengpu_sched_dep *deps, u32 dep_count)
{
    struct drm_gem_dma_object *dma;
    struct opengpu_resource_binding allocation;
    u32 i;
    int ret;

    if (!context->vm.enabled) return -EOPNOTSUPP;
    if (dep_count >= ARRAY_SIZE(p->deps)) return -EINVAL;
    p->source.object = drm_gem_object_lookup(file, p->view.handle);
    if (!p->source.object) return -ENOENT;
    if (p->source.object->import_attach) return -EOPNOTSUPP;
    dma = to_drm_gem_dma_obj(p->source.object);
    if (!dma->vaddr) return -EINVAL;
    ret = opengpu_compute_indirect_source_plan(dma->dma_addr,
        p->source.object->size, p->view.offset, &p->plan);
    if (ret) return ret;
    allocation = (struct opengpu_resource_binding) {
        .object=p->source.object, .dma=dma->dma_addr, .size=p->source.object->size,
    };
    for (i = 0; i <= dep_count; ++i) {
        struct drm_gem_object *other = i == dep_count ? shader->object : deps[i].object;
        const struct opengpu_staging_range range = {
            to_drm_gem_dma_obj(other)->dma_addr, other->size,
        };
        ret = opengpu_counter_check_disjoint(&allocation, other, &range);
        if (ret) return ret;
    }
    memcpy(p->deps, deps, dep_count * sizeof(p->deps[0]));
    p->dep_count = dep_count;
    opengpu_compute_add_dep(p->deps, &p->dep_count, p->source.object, false);
    p->source.offset = p->view.offset;
    return 0;
}

/* A successful wait alone does not report a producer's fence error. The
 * locked iterator includes signaled fences and remains stable across the wait.
 * No submit_lock (or other completion lock) is held by this admission path. */
static int opengpu_compute_wait_indirect(struct drm_gem_object *object)
{
    struct dma_resv_iter cursor;
    struct dma_fence *fence;
    int ret = opengpu_wait_reservation(object, DMA_RESV_USAGE_WRITE);
    if (ret) return ret;
    dma_resv_for_each_fence(&cursor, object->resv, DMA_RESV_USAGE_WRITE, fence) {
        ret = dma_fence_get_status(fence);
        if (ret <= 0) return ret ? ret : -ETIMEDOUT;
    }
    return 0;
}

static int opengpu_compute_snapshot_indirect(const struct opengpu_compute_indirect_prepare *p,
    struct opengpu_sched_job *job)
{
    struct opengpu_kernarg_va_plan plan;
    u64 total;
    int ret = opengpu_buffer_alloc(job->gpu, &job->indirect_snapshot, p->plan.bytes);
    if (ret) return ret;
    if (job->indirect_snapshot.dma & 4095) return -EINVAL;
    ret = opengpu_command_va_plan(job->indirect_snapshot.dma, p->plan.bytes,
        OPENGPU_COMPUTE_INDIRECT_SNAPSHOT_SLOT, OPENGPU_MMU_PAGE_SIZE, &plan);
    if (ret) return ret;
    memset(job->indirect_snapshot.cpu, 0, p->plan.bytes);
    /* Same coherent local-GEM/finished-producer publication contract as uniform
     * snapshots. The producer fence supplies completed GPU writes; dma_rmb is
     * CPU ordering, not a substitute for an L2 writeback or a producer fence. */
    dma_rmb();
    memcpy((u8 *)job->indirect_snapshot.cpu + p->plan.offset,
        (const u8 *)to_drm_gem_dma_obj(p->source.object)->vaddr + p->source.offset,
        OPENGPU_COMPUTE_INDIRECT_COUNT_BYTES);
    ret = opengpu_compute_indirect_counts(
        (const u8 *)job->indirect_snapshot.cpu + p->plan.offset,
        p->max_groups, job->kernel.grid, &total);
    if (ret) return ret;
    job->kernel.indirect_enabled = true;
    job->kernel.indirect_base = (u32)plan.va;
    job->kernel.indirect_bytes = p->plan.bytes;
    job->kernel.indirect_offset = p->plan.offset;
    return 0;
}

static int opengpu_compute_launch_extended(struct drm_device *drm,
    const struct drm_opengpu_compute *args, struct drm_file *file,
    struct opengpu_compute_texture_prepare *textures, u32 texture_count,
    struct opengpu_compute_storage_prepare *storage,
    struct opengpu_compute_image_prepare *images,
    struct opengpu_compute_counter_prepare *counter,
    struct opengpu_compute_uniform_prepare *uniforms,
    struct opengpu_compute_indirect_prepare *indirect)
{
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_file *render_file = file->driver_priv;
    struct opengpu_render_context *context;
    struct opengpu_resource_binding *shader, *kernarg;
    struct drm_gem_dma_object *shader_dma;
    struct opengpu_sched_job *sched_job = NULL;
    struct drm_syncobj *out_sync = NULL;
    struct drm_exec exec;
    struct opengpu_command_events events;
    const u32 *program;
    u64 available;
    u32 words, i, local_items;
    int ret;

    if (indirect) {
        if (!gpu->hw.compute_indirect_transport)
            return -EOPNOTSUPP;
        if (!counter || !counter->source_view.handle)
            return -EINVAL;
    }
    if (uniforms && (!gpu->hw.compute_uniform_transport ||
        !(gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE)))
        return -EOPNOTSUPP;
    if (counter && (!gpu->hw.compute_counter_transport ||
        !(gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE)))
        return -EOPNOTSUPP;
    if (images && !gpu->hw.compute_image_transport)
        return -EOPNOTSUPP;
    if (storage && !gpu->hw.compute_storage_transport)
        return -EOPNOTSUPP;
    if (textures && !gpu->hw.compute_texture_transport)
        return -EOPNOTSUPP;
    if (!(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    ret = opengpu_command_events_init(gpu, &events, args->flags,
                                      args->wait_event,
                                      args->signal_event);
    if (ret)
        return ret;
    if (!args->context_id || !args->shader_slot || !args->kernarg_slot ||
        args->shader_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        args->kernarg_slot > OPENGPU_MAX_RESOURCE_SLOTS ||
        (args->shader_offset & 3) ||
        (args->kernarg_offset & 3) ||
        (indirect ? !!(args->grid[0] | args->grid[1] | args->grid[2]) :
            (!args->grid[0] || !args->grid[1] || !args->grid[2])) ||
        !opengpu_compute_local_size_valid(&gpu->hw.compute_local_limits,
                                          args->local, &local_items))
        return -EINVAL;
    if (args->out_syncobj) {
        out_sync = drm_syncobj_find(file, args->out_syncobj);
        if (!out_sync)
            return -ENOENT;
    }

    mutex_lock(&render_file->lock);
    context = opengpu_context_lookup(render_file, args->context_id);
    if (!context) {
        ret = -ENOENT;
        goto out_file;
    }
    shader = opengpu_binding_lookup(context, args->shader_slot,
                                    OPENGPU_RESOURCE_COMPUTE_SHADER);
    kernarg = opengpu_binding_lookup(context, args->kernarg_slot,
                                     OPENGPU_RESOURCE_COMPUTE_KERNARG);
    if (!shader || !kernarg || shader->object == kernarg->object ||
        args->shader_offset >= shader->size ||
        args->kernarg_offset >= kernarg->size) {
        ret = -EINVAL;
        goto out_file;
    }
    {
        struct drm_gem_object *objects[] = {shader->object, kernarg->object};
        const struct opengpu_staging_range ranges[] = {
            {shader->dma, shader->size}, {kernarg->dma, kernarg->size},
        };

        ret = opengpu_gem_backings_disjoint(objects, ARRAY_SIZE(objects));
        if (ret)
            goto out_file;
        if (!opengpu_dma_ranges_pairwise_disjoint(ranges, ARRAY_SIZE(ranges))) {
            ret = -EINVAL;
            goto out_file;
        }
    }
    if (context->vm.enabled && !kernarg->va) {
        ret = -EIO;
        goto out_file;
    }
    if (textures) {
        /* A foreign shader may alias writable kernarg pages under another DMA
         * address, so the new resource-bearing path requires proof too. */
        ret = opengpu_gem_backings_proven_disjoint(shader->object, kernarg->object);
        if (!ret)
            ret = opengpu_compute_prepare_texture_views(textures, texture_count, context, kernarg, gpu);
        if (ret)
            goto out_file;
    }
    if (storage) {
        ret = opengpu_compute_prepare_storage(storage, context, shader, kernarg,
                                               args->context_id);
        if (ret) goto out_file;
    }
    if (images) {
        ret = opengpu_compute_prepare_images(images, context, shader, kernarg,
                                              args->context_id, gpu);
        if (ret) goto out_file;
    }
    if (storage && images) {
        ret = opengpu_compute_check_image_storage(images, storage);
        if (ret) goto out_file;
    }
    if (storage && textures) {
        ret = opengpu_compute_combine_resources(storage, textures, texture_count, context);
        if (ret) goto out_file;
    }
    if (images && textures) {
        ret = opengpu_compute_combine_textured_images(images, textures, texture_count, context);
        if (ret) goto out_file;
    }
    if (images && storage)
        opengpu_compute_merge_image_storage_deps(images, storage);
    if (counter) {
        ret = counter->source_view.handle ?
            opengpu_compute_prepare_counter_source(counter, file, context, shader, kernarg,
                    args->context_id, textures, storage, images) :
            opengpu_compute_prepare_counter(counter, context, shader, kernarg,
                    args->context_id, textures, texture_count, storage, images);
        if (ret) goto out_file;
    }
    if (uniforms) {
        ret = opengpu_compute_prepare_uniforms(uniforms, file, context, shader,
                                                kernarg, textures, storage, images, counter);
        if (ret) goto out_file;
    }
    if (indirect) {
        ret = opengpu_compute_prepare_indirect(indirect, file, context, shader,
            uniforms ? uniforms->deps : counter->deps,
            uniforms ? uniforms->dep_count : counter->dep_count);
        if (ret) goto out_file;
    }
    shader_dma = to_drm_gem_dma_obj(shader->object);
    if (!shader_dma->vaddr) {
        ret = -EINVAL;
        goto out_file;
    }

    drm_exec_init(&exec, DRM_EXEC_INTERRUPTIBLE_WAIT |
                         DRM_EXEC_IGNORE_DUPLICATES, 0);
    drm_exec_until_all_locked(&exec) {
        ret = drm_exec_prepare_obj(&exec, shader->object, 1);
        if (indirect) {
            for (i = 0; !ret && i < indirect->dep_count; ++i)
                if (indirect->deps[i].object->resv != shader->object->resv)
                    ret = drm_exec_prepare_obj(&exec, indirect->deps[i].object, 1);
        } else if (uniforms) {
            for (i = 0; !ret && i < uniforms->dep_count; ++i)
                if (uniforms->deps[i].object->resv != shader->object->resv)
                    ret = drm_exec_prepare_obj(&exec, uniforms->deps[i].object, 1);
        } else if (counter) {
            for (i = 0; !ret && i < counter->dep_count; ++i)
                if (counter->deps[i].object->resv != shader->object->resv)
                    ret = drm_exec_prepare_obj(&exec, counter->deps[i].object, 1);
        } else if (images) {
            for (i = 0; !ret && i < images->dep_count; ++i)
                if (images->deps[i].object->resv != shader->object->resv)
                    ret = drm_exec_prepare_obj(&exec, images->deps[i].object, 1);
        } else if (storage) {
            for (i = 0; !ret && i < storage->dep_count; ++i)
                if (storage->deps[i].object->resv != shader->object->resv)
                    ret = drm_exec_prepare_obj(&exec, storage->deps[i].object, 1);
        } else if (textures) {
            for (i = 0; !ret && i < textures->dep_count; ++i)
                if (textures->deps[i].object->resv != shader->object->resv)
                    ret = drm_exec_prepare_obj(&exec, textures->deps[i].object, 1);
        } else if (!ret) {
            ret = drm_exec_prepare_obj(&exec, kernarg->object, 1);
        }
        drm_exec_retry_on_contention(&exec);
        if (ret)
            goto out_exec;
    }
    ret = opengpu_wait_reservation(shader->object, DMA_RESV_USAGE_WRITE);
    if (ret)
        goto out_exec;
    if (uniforms) {
        /* Reservations stay locked through copy and read-fence publication.
         * Each unique source waits on its actual producer reservation. */
        for (i = 0; i < uniforms->dep_count; ++i) {
            u32 source;

            if (uniforms->deps[i].signal_usage != DMA_RESV_USAGE_READ)
                continue;
            for (source = 0; source < ARRAY_SIZE(uniforms->sources); ++source)
                if (uniforms->sources[source].object == uniforms->deps[i].object)
                    break;
            if (source == ARRAY_SIZE(uniforms->sources))
                continue;
            ret = opengpu_wait_reservation(uniforms->deps[i].object, DMA_RESV_USAGE_WRITE);
            if (ret)
                goto out_exec;
        }
    }

    if (indirect) {
        ret = opengpu_compute_wait_indirect(indirect->source.object);
        if (ret) goto out_exec;
    }

    sched_job = kzalloc(sizeof(*sched_job), GFP_KERNEL);
    if (!sched_job) {
        ret = -ENOMEM;
        goto out_exec;
    }
    sched_job->gpu = gpu;
    sched_job->vm = context->vm.enabled ? &context->vm : NULL;
    sched_job->type = OPENGPU_SCHED_COMPUTE;
    if (indirect) {
        ret = opengpu_compute_snapshot_indirect(indirect, sched_job);
        if (ret) goto out_job;
    }
    ret = opengpu_buffer_alloc(gpu, &sched_job->shader, shader->size);
    if (ret)
        goto out_job;
    memcpy(sched_job->shader.cpu,
           (const u8 *)shader_dma->vaddr + shader->offset, shader->size);

    available = shader->size - args->shader_offset;
    words = min_t(u64, available / sizeof(u32),
                  OPENGPU_SHADER_MAX_INSTRUCTIONS);
    program = (const u32 *)((const u8 *)sched_job->shader.cpu +
                            args->shader_offset);
    if (!opengpu_compute_shader_validate_words_discovered_resources(
            program, words, kernarg->size - args->kernarg_offset, local_items,
            !!(gpu->hw.capabilities & GPU_CAP_COMPUTE_SCALAR_FPU),
            !!(gpu->hw.compute_resource_flags & OPENGPU_COMPUTE_RESOURCE_WORD_ATOMICS),
            !!textures, !!storage, !!images,
            !!(gpu->hw.compute_resource_flags & OPENGPU_COMPUTE_RESOURCE_IMAGE_ATOMICS),
            !!(gpu->hw.compute_resource_flags & OPENGPU_COMPUTE_RESOURCE_STORAGE_ATOMICS),
            !!counter, !!uniforms,
            gpu->hw.compute_shared_limits.discovered ? gpu->hw.compute_shared_limits.base : 0,
            gpu->hw.compute_shared_limits.discovered ? gpu->hw.compute_shared_limits.bytes : 0,
            gpu->hw.compute_shared_limits.discovered ? gpu->hw.compute_shared_limits.lanes : 0,
            opengpu_compute_buffer_images_supported(gpu->hw.compute_resource_flags,
                gpu->hw.capabilities, gpu->hw.compute_image_transport))) {
        ret = -EINVAL;
        goto out_job;
    }
    sched_job->kernel.kernel_pc = sched_job->shader.dma +
                                  args->shader_offset;
    /* A privately-mapped kernarg is reached through the VM virtual address,
     * which the CU MMU translates under this context's ASID. */
    sched_job->kernel.kernarg = kernarg->va ?
        kernarg->va + args->kernarg_offset :
        kernarg->dma + args->kernarg_offset;
    if (uniforms) {
        ret = opengpu_compute_snapshot_uniforms(uniforms, sched_job, context,
                args->context_id, (u32)sched_job->kernel.kernarg,
                kernarg->size - args->kernarg_offset);
        if (ret)
            goto out_job;
    }
    if (textures) {
        struct opengpu_kernarg_va_plan plan;
        u64 bytes = opengpu_compute_texture_table_bytes(texture_count);
        u64 root;

        ret = opengpu_buffer_alloc(gpu, &sched_job->texture_records, bytes);
        if (ret)
            goto out_job;
        root = sched_job->texture_records.dma;
        if (context->vm.enabled) {
            ret = opengpu_command_va_plan(root, bytes, 2, OPENGPU_MMU_PAGE_SIZE, &plan);
            if (ret)
                goto out_job;
            root = plan.va;
        }
        if (upper_32_bits(root) ||
            !opengpu_build_compute_texture_tables(sched_job->texture_records.cpu,
                bytes, (u32)root, args->context_id, 0, 0, textures->entries, texture_count)) {
            ret = -EINVAL;
            goto out_job;
        }
        sched_job->kernel.texture_enabled = true;
        sched_job->kernel.texture = (struct opengpu_compute_texture_command_context) {
            .version=1, .root=(u32)root, .context=args->context_id,
        };
    }
    if (storage) {
        struct opengpu_kernarg_va_plan plan;
        u64 root;
        ret = opengpu_buffer_alloc(gpu, &sched_job->storage_records, sizeof(storage->records));
        if (ret) goto out_job;
        ret = opengpu_command_va_plan(sched_job->storage_records.dma,
                sizeof(storage->records), 3, OPENGPU_MMU_PAGE_SIZE, &plan);
        if (ret) goto out_job;
        root = plan.va;
        if (upper_32_bits(root) || (root & 63)) { ret = -EINVAL; goto out_job; }
        for (i = 0; i < 4; ++i)
            opengpu_compute_texture_store_words(
                (u8 *)sched_job->storage_records.cpu + i * 64,
                storage->records[i], OPENGPU_STORAGE_DESCRIPTOR_WORDS);
        sched_job->kernel.storage_enabled = true;
        sched_job->kernel.storage = (struct opengpu_storage_command_context) {
            .version = 1, .base = (u32)root, .limit = root + sizeof(storage->records),
            .context = args->context_id,
        };
    }
    if (images) {
        struct opengpu_kernarg_va_plan plan;
        u64 root;
        ret = opengpu_buffer_alloc(gpu, &sched_job->image_records, sizeof(images->records));
        if (ret) goto out_job;
        ret = opengpu_command_va_plan(sched_job->image_records.dma,
                sizeof(images->records), 4, OPENGPU_MMU_PAGE_SIZE, &plan);
        if (ret) goto out_job;
        root = plan.va;
        if (upper_32_bits(root) || (root & 63)) { ret = -EINVAL; goto out_job; }
        for (i = 0; i < OPENGPU_COMPUTE_IMAGE_UNITS; ++i)
            opengpu_compute_texture_store_words(
                (u8 *)sched_job->image_records.cpu + i * 256,
                images->records[i], OPENGPU_COMPUTE_IMAGE_DESCRIPTOR_WORDS);
        sched_job->kernel.image_enabled = true;
        sched_job->kernel.image = (struct opengpu_image_command_context) {
            .version=1, .base=(u32)root, .limit=root+sizeof(images->records),
            .context=args->context_id,
        };
    }
    if (counter) {
        struct opengpu_kernarg_va_plan plan;
        u64 root;
        sched_job->counter_source = counter->source;
        ret = opengpu_buffer_alloc(gpu, &sched_job->counter_records, sizeof(counter->record));
        if (ret) goto out_job;
        ret = opengpu_command_va_plan(sched_job->counter_records.dma,
                sizeof(counter->record), 5, OPENGPU_MMU_PAGE_SIZE, &plan);
        if (ret) goto out_job;
        root = plan.va;
        if (upper_32_bits(root) || (root & 63)) { ret = -EINVAL; goto out_job; }
        opengpu_compute_texture_store_words(sched_job->counter_records.cpu,
                counter->record, OPENGPU_STORAGE_DESCRIPTOR_WORDS);
        sched_job->kernel.counter_enabled = true;
        sched_job->kernel.counter = (struct opengpu_atomic_counter_command_context) {
            .version=1, .base=(u32)root, .limit=root+sizeof(counter->record),
            .context=args->context_id,
        };
    }
    sched_job->events = events;
    if (!indirect)
        memcpy(sched_job->kernel.grid, args->grid, sizeof(sched_job->kernel.grid));
    memcpy(sched_job->kernel.local, args->local,
           sizeof(sched_job->kernel.local));

    {
        const struct opengpu_sched_dep deps[] = {
            { kernarg->object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE },
        };

        ret = opengpu_sched_job_submit(render_file, file, context, sched_job,
                                       indirect || uniforms || (counter && counter->source_view.handle) ? 0 : args->in_syncobj, out_sync,
                                       indirect ? indirect->deps : uniforms ? uniforms->deps : counter ? counter->deps : images ? images->deps : storage ? storage->deps : textures ? textures->deps : deps,
                                       indirect ? indirect->dep_count : uniforms ? uniforms->dep_count : counter ? counter->dep_count : images ? images->dep_count : storage ? storage->dep_count : textures ? textures->dep_count : ARRAY_SIZE(deps), NULL);
    }
    if (ret)
        goto out_job;
    sched_job = NULL;
    ret = 0;
    goto out_exec;

out_job:
    if (sched_job) {
        opengpu_buffer_free(gpu, &sched_job->shader);
        opengpu_buffer_free(gpu, &sched_job->texture_records);
        opengpu_buffer_free(gpu, &sched_job->storage_records);
        opengpu_buffer_free(gpu, &sched_job->image_records);
        opengpu_buffer_free(gpu, &sched_job->counter_records);
        opengpu_buffer_free(gpu, &sched_job->uniform_snapshot);
        opengpu_buffer_free(gpu, &sched_job->indirect_snapshot);
        kfree(sched_job);
    }
out_exec:
    drm_exec_fini(&exec);
out_file:
    mutex_unlock(&render_file->lock);
    if (out_sync)
        drm_syncobj_put(out_sync);
    return ret;
}

static int opengpu_compute_launch_common(struct drm_device *drm,
    const struct drm_opengpu_compute *args, struct drm_file *file,
    struct opengpu_compute_texture_prepare *textures, u32 texture_count,
    struct opengpu_compute_storage_prepare *storage,
    struct opengpu_compute_image_prepare *images)
{
    return opengpu_compute_launch_extended(drm, args, file, textures, texture_count,
                                           storage, images, NULL, NULL, NULL);
}

int opengpu_compute_launch_ioctl(struct drm_device *drm, void *data,
                                 struct drm_file *file)
{
    return opengpu_compute_launch_common(drm, data, file, NULL, 0, NULL, NULL);
}

int opengpu_compute_textures_ioctl(struct drm_device *drm, void *data,
                                   struct drm_file *file)
{
    const struct drm_opengpu_compute_textures *args = data;
    struct opengpu_compute_texture_prepare *textures;
    int ret;

    if (args->pad || !args->views || !args->view_count ||
        args->view_count > OPENGPU_COMPUTE_TEXTURE_MAX_UNITS)
        return -EINVAL;
    textures = kvzalloc(sizeof(*textures), GFP_KERNEL);
    if (!textures)
        return -ENOMEM;
    if (copy_from_user(textures->views, u64_to_user_ptr(args->views),
                       args->view_count * sizeof(textures->views[0]))) {
        ret = -EFAULT;
    } else {
        ret = opengpu_compute_launch_common(drm, &args->launch, file,
                                            textures, args->view_count, NULL, NULL);
    }
    kvfree(textures);
    return ret;
}

int opengpu_compute_storage_ioctl(struct drm_device *drm, void *data,
                                  struct drm_file *file)
{
    const struct drm_opengpu_compute_storage *args = data;
    struct opengpu_compute_storage_prepare *storage;
    int ret;
    storage = kzalloc(sizeof(*storage), GFP_KERNEL);
    if (!storage) return -ENOMEM;
    memcpy(storage->bindings, args->bindings, sizeof(storage->bindings));
    ret = opengpu_compute_launch_common(drm, &args->launch, file, NULL, 0, storage, NULL);
    kfree(storage);
    return ret;
}

int opengpu_compute_resources_ioctl(struct drm_device *drm, void *data,
                                    struct drm_file *file)
{
    const struct drm_opengpu_compute_resources *args = data;
    struct opengpu_compute_texture_prepare *textures;
    struct opengpu_compute_storage_prepare *storage;
    int ret;
    if (args->pad || !args->views || !args->view_count ||
        args->view_count > OPENGPU_COMPUTE_TEXTURE_MAX_UNITS)
        return -EINVAL;
    textures = kvzalloc(sizeof(*textures), GFP_KERNEL);
    storage = kzalloc(sizeof(*storage), GFP_KERNEL);
    if (!textures || !storage) { ret = -ENOMEM; goto out; }
    memcpy(storage->bindings, args->bindings, sizeof(storage->bindings));
    if (copy_from_user(textures->views, u64_to_user_ptr(args->views),
                       args->view_count * sizeof(textures->views[0]))) {
        ret = -EFAULT;
    } else {
        ret = opengpu_compute_launch_common(drm, &args->launch, file,
                                            textures, args->view_count, storage, NULL);
    }
out:
    kvfree(textures);
    kfree(storage);
    return ret;
}

int opengpu_compute_images_ioctl(struct drm_device *drm, void *data,
                                 struct drm_file *file)
{
    const struct drm_opengpu_compute_images *args = data;
    struct opengpu_compute_image_prepare *images;
    int ret;

    images = kzalloc(sizeof(*images), GFP_KERNEL);
    if (!images) return -ENOMEM;
    memcpy(images->views, args->views, sizeof(images->views));
    ret = opengpu_compute_launch_common(drm, &args->launch, file, NULL, 0, NULL, images);
    kfree(images);
    return ret;
}

int opengpu_compute_textured_images_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file)
{
    const struct drm_opengpu_compute_textured_images *args = data;
    struct opengpu_compute_texture_prepare *textures;
    struct opengpu_compute_image_prepare *images;
    int ret;

    if (args->pad || !args->textures || !args->texture_count ||
        args->texture_count > OPENGPU_COMPUTE_TEXTURE_MAX_UNITS)
        return -EINVAL;
    textures = kvzalloc(sizeof(*textures), GFP_KERNEL);
    images = kzalloc(sizeof(*images), GFP_KERNEL);
    if (!textures || !images) { ret = -ENOMEM; goto out; }
    memcpy(images->views, args->images, sizeof(images->views));
    if (copy_from_user(textures->views, u64_to_user_ptr(args->textures),
                       args->texture_count * sizeof(textures->views[0]))) {
        ret = -EFAULT;
    } else {
        ret = opengpu_compute_launch_common(drm, &args->launch, file,
                                            textures, args->texture_count, NULL, images);
    }
out:
    kvfree(textures);
    kfree(images);
    return ret;
}

int opengpu_compute_image_resources_ioctl(struct drm_device *drm, void *data,
                                          struct drm_file *file)
{
    const struct drm_opengpu_compute_image_resources *args = data;
    struct opengpu_compute_texture_prepare *textures = NULL;
    struct opengpu_compute_storage_prepare *storage = NULL;
    struct opengpu_compute_image_prepare *images = NULL;
    int ret;
    if (args->pad || args->texture_count > OPENGPU_COMPUTE_TEXTURE_MAX_UNITS ||
        (!!args->texture_count != !!args->textures)) return -EINVAL;
    if (args->texture_count) textures = kvzalloc(sizeof(*textures), GFP_KERNEL);
    storage = kzalloc(sizeof(*storage), GFP_KERNEL);
    images = kzalloc(sizeof(*images), GFP_KERNEL);
    if ((args->texture_count && !textures) || !storage || !images) {
        ret = -ENOMEM; goto out;
    }
    memcpy(storage->bindings, args->bindings, sizeof(storage->bindings));
    memcpy(images->views, args->images, sizeof(images->views));
    if (textures && copy_from_user(textures->views, u64_to_user_ptr(args->textures),
                                   args->texture_count * sizeof(textures->views[0]))) {
        ret = -EFAULT;
    } else {
        ret = opengpu_compute_launch_common(drm, &args->launch, file,
                                            textures, args->texture_count, storage, images);
    }
out:
    kvfree(textures);
    kfree(storage);
    kfree(images);
    return ret;
}

int opengpu_compute_counter_resources_ioctl(struct drm_device *drm, void *data,
                                            struct drm_file *file)
{
    const struct drm_opengpu_compute_counter_resources *args = data;
    struct opengpu_compute_texture_prepare *textures = NULL;
    struct opengpu_compute_storage_prepare *storage = NULL;
    struct opengpu_compute_image_prepare *images = NULL;
    struct opengpu_compute_counter_prepare *counter = NULL;
    int ret;

    if ((args->resource_flags & ~(OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE |
                                  OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES)) ||
        args->texture_count > OPENGPU_COMPUTE_TEXTURE_MAX_UNITS ||
        (!!args->texture_count != !!args->textures) ||
        (!(args->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE) &&
         memchr_inv(args->bindings, 0, sizeof(args->bindings))) ||
        (!(args->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES) &&
         memchr_inv(args->images, 0, sizeof(args->images)))) return -EINVAL;
    if (args->texture_count) textures = kvzalloc(sizeof(*textures), GFP_KERNEL);
    if (args->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE)
        storage = kzalloc(sizeof(*storage), GFP_KERNEL);
    if (args->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES)
        images = kzalloc(sizeof(*images), GFP_KERNEL);
    counter = kzalloc(sizeof(*counter), GFP_KERNEL);
    if ((args->texture_count && !textures) ||
        ((args->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE) && !storage) ||
        ((args->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES) && !images) ||
        !counter) { ret = -ENOMEM; goto out; }
    counter->binding = args->counter;
    if (storage) memcpy(storage->bindings, args->bindings, sizeof(storage->bindings));
    if (images) memcpy(images->views, args->images, sizeof(images->views));
    if (textures && copy_from_user(textures->views, u64_to_user_ptr(args->textures),
                                   args->texture_count * sizeof(textures->views[0]))) {
        ret = -EFAULT;
    } else {
        ret = opengpu_compute_launch_extended(drm, &args->launch, file,
                    textures, args->texture_count, storage, images, counter, NULL, NULL);
    }
out:
    kvfree(textures);
    kfree(storage);
    kfree(images);
    kfree(counter);
    return ret;
}

static int opengpu_compute_uniform_resources_submit(struct drm_device *drm,
    const struct drm_opengpu_compute_uniform_resources *args, struct drm_file *file,
    const struct drm_opengpu_compute_counter_source *source,
    struct opengpu_compute_indirect_prepare *indirect)
{
    const struct drm_opengpu_compute_counter_resources *resources = &args->resources;
    struct opengpu_device *gpu = dev_get_drvdata(drm->dev);
    struct opengpu_compute_texture_prepare *textures = NULL;
    struct opengpu_compute_storage_prepare *storage = NULL;
    struct opengpu_compute_image_prepare *images = NULL;
    struct opengpu_compute_counter_prepare *counter = NULL;
    struct opengpu_compute_uniform_prepare *uniforms = NULL;
    struct dma_fence *input;
    bool use_uniforms = !source || args->active_mask || args->defaults.handle;
    bool use_counter = source || (resources->resource_flags & OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS);
    u32 i;
    int ret;

    if (args->version != OPENGPU_COMPUTE_UNIFORM_VERSION || args->pad[0] || args->pad[1] ||
        (args->active_mask & ~0xfffu) ||
        (resources->resource_flags & ~(OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE |
            OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES |
            (source ? 0 : OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS))) ||
        (!use_uniforms && (memchr_inv(args->blocks, 0, sizeof(args->blocks)) ||
                           memchr_inv(&args->defaults, 0, sizeof(args->defaults)))) ||
        resources->texture_count > OPENGPU_COMPUTE_TEXTURE_MAX_UNITS ||
        (!!resources->texture_count != !!resources->textures) ||
        (!(resources->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE) &&
         memchr_inv(resources->bindings, 0, sizeof(resources->bindings))) ||
        (!(resources->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES) &&
         memchr_inv(resources->images, 0, sizeof(resources->images))) ||
        (!(resources->resource_flags & OPENGPU_COMPUTE_UNIFORM_RESOURCE_COUNTERS) &&
         memchr_inv(&resources->counter, 0, sizeof(resources->counter))))
        return -EINVAL;
    if ((indirect && !gpu->hw.compute_indirect_transport) ||
        (use_uniforms && !gpu->hw.compute_uniform_transport) ||
        (use_counter && !gpu->hw.compute_counter_transport) ||
        !(gpu->hw.capabilities & GPU_CAP_PROTECTED_VM_STATE) ||
        !(gpu->hw.capabilities & GPU_CAP_UNIFIED_COMMANDS))
        return -EOPNOTSUPP;
    /* Capture the binary input fence once, before the CPU snapshot. Enqueue
     * does not re-read a syncobj that another thread may have replaced. */
    if (resources->launch.in_syncobj) {
        ret = drm_syncobj_find_fence(file, resources->launch.in_syncobj, 0, 0, &input);
        if (ret) return ret;
        ret = opengpu_hw_fence_wait(gpu, input, OPENGPU_DRAW_WAIT_MS + 100, true);
        dma_fence_put(input);
        if (ret) return ret;
    }
    if (use_uniforms) uniforms = kzalloc(sizeof(*uniforms), GFP_KERNEL);
    if (resources->texture_count) textures = kvzalloc(sizeof(*textures), GFP_KERNEL);
    if (resources->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE)
        storage = kzalloc(sizeof(*storage), GFP_KERNEL);
    if (resources->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES)
        images = kzalloc(sizeof(*images), GFP_KERNEL);
    if (use_counter)
        counter = kzalloc(sizeof(*counter), GFP_KERNEL);
    if ((use_uniforms && !uniforms) || (resources->texture_count && !textures) ||
        ((resources->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_STORAGE) && !storage) ||
        ((resources->resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES) && !images) ||
        (use_counter && !counter)) {
        ret = -ENOMEM;
        goto out;
    }
    if (uniforms) {
        uniforms->active_mask = args->active_mask;
        memcpy(uniforms->views, args->blocks, sizeof(args->blocks));
        uniforms->views[OPENGPU_COMPUTE_UNIFORM_BLOCKS] = args->defaults;
    }
    if (storage) memcpy(storage->bindings, resources->bindings, sizeof(storage->bindings));
    if (images) memcpy(images->views, resources->images, sizeof(images->views));
    if (counter) {
        if (source) counter->source_view = *source;
        else counter->binding = resources->counter;
    }
    if (textures && copy_from_user(textures->views, u64_to_user_ptr(resources->textures),
            resources->texture_count * sizeof(textures->views[0])))
        ret = -EFAULT;
    else
        ret = opengpu_compute_launch_extended(drm, &resources->launch, file,
            textures, resources->texture_count, storage, images, counter, uniforms, indirect);
out:
    if (counter && counter->source.object)
        drm_gem_object_put(counter->source.object);
    if (uniforms) for (i = 0; i < ARRAY_SIZE(uniforms->sources); ++i)
        if (uniforms->sources[i].object) drm_gem_object_put(uniforms->sources[i].object);
    kfree(uniforms);
    kvfree(textures);
    kfree(storage);
    kfree(images);
    kfree(counter);
    return ret;
}

int opengpu_compute_uniform_resources_ioctl(struct drm_device *drm, void *data,
                                            struct drm_file *file)
{
    return opengpu_compute_uniform_resources_submit(drm, data, file, NULL, NULL);
}

static int opengpu_compute_counter_source_resources_submit(struct drm_device *drm,
    const struct drm_opengpu_compute_counter_source_resources *args, struct drm_file *file,
    struct opengpu_compute_indirect_prepare *indirect)
{
    if (args->version != OPENGPU_COMPUTE_COUNTER_SOURCE_VERSION ||
        memchr_inv(args->pad, 0, sizeof(args->pad)) || !args->source.handle ||
        !args->source.count || args->source.count > 8 || (args->source.offset & 3))
        return -EINVAL;
    return opengpu_compute_uniform_resources_submit(drm, &args->resources, file, &args->source, indirect);
}

int opengpu_compute_counter_source_resources_ioctl(struct drm_device *drm, void *data,
                                                   struct drm_file *file)
{
    return opengpu_compute_counter_source_resources_submit(drm, data, file, NULL);
}

int opengpu_compute_indirect_resources_ioctl(struct drm_device *drm, void *data,
                                             struct drm_file *file)
{
    const struct drm_opengpu_compute_indirect_resources *args = data;
    const struct drm_opengpu_compute *launch = &args->resources.resources.resources.launch;
    struct opengpu_compute_indirect_prepare *indirect;
    int ret;

    if (args->version != OPENGPU_COMPUTE_INDIRECT_VERSION ||
        memchr_inv(args->pad, 0, sizeof(args->pad)) || args->source.pad ||
        !args->source.handle || (args->source.offset & 3) ||
        !args->max_groups[0] || !args->max_groups[1] || !args->max_groups[2] ||
        launch->grid[0] || launch->grid[1] || launch->grid[2])
        return -EINVAL;
    indirect = kzalloc(sizeof(*indirect), GFP_KERNEL);
    if (!indirect) return -ENOMEM;
    indirect->view = args->source;
    memcpy(indirect->max_groups, args->max_groups, sizeof(indirect->max_groups));
    ret = opengpu_compute_counter_source_resources_submit(drm, &args->resources, file, indirect);
    if (indirect->source.object)
        drm_gem_object_put(indirect->source.object);
    kfree(indirect);
    return ret;
}

static int opengpu_self_test(struct opengpu_device *gpu)
{
    u32 x, y, rgb = 0;
    int ret;

    ret = opengpu_submit_test(gpu);
    if (ret) {
        dev_err(gpu->dev, "OPENGPU DRIVER FAIL: draw did not complete\n");
        return ret;
    }

    for (y = 0; y < gpu->height; y++) {
        u32 *row = gpu->compute.color.cpu + y * gpu->stride;

        for (x = 0; x < gpu->width; x++) {
            rgb = (row[x] >> 8) & 0x00ffffffu;
            if (rgb == 0x00ff0000u) {
                dev_info(gpu->dev,
                         "OPENGPU DRIVER PASS: draw submitted and read back\n");
                return 0;
            }
        }
    }

    dev_err(gpu->dev,
            "OPENGPU DRIVER FAIL: expected red (0xff0000) got 0x%06x\n",
            rgb);
    return -EIO;
}

static ssize_t opengpu_compute_read(struct file *file, char __user *buf,
                                    size_t count, loff_t *offset)
{
    struct miscdevice *misc = file->private_data;
    struct opengpu_compute *compute;
    ssize_t ret;

    compute = container_of(misc, struct opengpu_compute, misc);
    if (*offset >= compute->color.size)
        return 0;
    if (count > compute->color.size - *offset)
        count = compute->color.size - *offset;

    mutex_lock(&compute->lock);
    if (copy_to_user(buf, compute->color.cpu + *offset, count)) {
        ret = -EFAULT;
    } else {
        *offset += count;
        ret = count;
    }
    mutex_unlock(&compute->lock);
    return ret;
}

static long opengpu_compute_ioctl(struct file *file, unsigned int cmd,
                                  unsigned long arg)
{
    struct miscdevice *misc = file->private_data;
    struct opengpu_compute *compute;
    struct opengpu_device *gpu;
    int ret;

    if (cmd != OPENGPU_IOCTL_SUBMIT)
        return -ENOTTY;

    compute = container_of(misc, struct opengpu_compute, misc);
    gpu = container_of(compute, struct opengpu_device, compute);
    mutex_lock(&compute->lock);
    ret = opengpu_self_test(gpu);
    mutex_unlock(&compute->lock);
    return ret;
}

static const struct file_operations opengpu_compute_fops = {
    .owner = THIS_MODULE,
    .read = opengpu_compute_read,
    .unlocked_ioctl = opengpu_compute_ioctl,
    .compat_ioctl = opengpu_compute_ioctl,
    .llseek = default_llseek,
};

int opengpu_compute_init(struct opengpu_device *gpu)
{
    struct opengpu_compute *compute = &gpu->compute;
    int ret;

    mutex_init(&compute->lock);
    ret = opengpu_buffer_alloc(gpu, &compute->cmd,
                               max_t(size_t, sizeof(struct gpu_draw_record),
                                     256));
    if (ret)
        return ret;
    ret = opengpu_buffer_alloc(gpu, &compute->color,
                               (size_t)gpu->stride * gpu->height);
    if (ret)
        goto err_cmd;
    ret = opengpu_buffer_alloc(gpu, &compute->depth,
                               (size_t)gpu->stride * gpu->height);
    if (ret)
        goto err_color;
    /* Enable Sv32 translation with an identity map so the CU MMUs are live
     * without changing any existing physical address. */
    ret = opengpu_mmu_init(gpu);
    if (ret)
        goto err_mmu;
    if (gpu->hw.capabilities & GPU_CAP_FRAGMENT_CORE) {
        ret = opengpu_init_test_shader(gpu);
        if (ret)
            goto err_shader;
    }

    ret = opengpu_sched_init(gpu);
    if (ret)
        goto err_shader;
    compute->misc = (struct miscdevice) {
        .minor = MISC_DYNAMIC_MINOR,
        .name = OPENGPU_COMPUTE_NAME,
        .mode = 0666,
        .fops = &opengpu_compute_fops,
        .parent = gpu->dev,
    };
    ret = misc_register(&compute->misc);
    if (ret)
        goto err_scheduler;
    return 0;

err_scheduler:
    opengpu_sched_fini(gpu);
err_shader:
err_mmu:
    if (opengpu_mmu_fini(gpu))
        return ret ? ret : -EIO;
    opengpu_buffer_free(gpu, &compute->kernarg);
    opengpu_buffer_free(gpu, &compute->shader);
    opengpu_buffer_free(gpu, &compute->depth);
err_color:
    opengpu_buffer_free(gpu, &compute->color);
err_cmd:
    opengpu_buffer_free(gpu, &compute->cmd);
    return ret;
}

void opengpu_compute_fini(struct opengpu_device *gpu)
{
    misc_deregister(&gpu->compute.misc);
    opengpu_sched_fini(gpu);
    if (opengpu_mmu_fini(gpu))
        return; /* Global walkers may still name the retained backing. */
    opengpu_buffer_free(gpu, &gpu->compute.kernarg);
    opengpu_buffer_free(gpu, &gpu->compute.shader);
    opengpu_buffer_free(gpu, &gpu->compute.depth);
    opengpu_buffer_free(gpu, &gpu->compute.color);
    opengpu_buffer_free(gpu, &gpu->compute.cmd);
}
