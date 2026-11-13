/* SPDX-License-Identifier: MIT */
#define OPENGPU_FLOW_HANDLES 512
/* The composed fixture executes decoded native counter words itself; the
 * standalone driver's illustrative device increment must not run as well. */
#define OPENGPU_FLOW_REAL_WORD_EXECUTION 1
#define main original_uniform_kernel_fixture_main
#include "../../driver/tests/opengpu_compute_uniform_flow_fixture.c"
#undef main
#include "cs_buffer_kernel.h"

struct kernel_state {
    struct opengpu_device gpu;
    struct opengpu_render_context context;
    struct opengpu_file render;
    struct drm_device drm;
    struct drm_file file;
    struct dma_resv reservations[512];
    struct drm_gem_object objects[512];
    struct opengpu_resource_binding bindings[43];
    struct opengpu_sched_job *job;
};

static void cleanup_state(struct kernel_state *s)
{
    release_fence(&output_sync.fence);
    release_fence(&s->context.last_fence);
    for (unsigned h = 1; h < 512; h++) if (s->objects[h].refs) {
        assert(s->objects[h].refs == 1 && !s->reservations[h].locked);
        release_fence(&s->reservations[h].last);
    }
    assert(s->context.refs == 1 && !buffers && !s->render.lock);
    free(s);
}

int cs_buffer_kernel_begin(const struct cs_buffer_kernel_input *in, struct cs_buffer_kernel_result *out)
{
    struct kernel_state *s = calloc(1, sizeof(*s)); assert(s);
    assert(!buffers && !output_sync.fence);
    allocation = mapping = unmaps = queued_count = vm_map_count = 0;
    s->context = (struct opengpu_render_context) {.id = in->command.launch.context_id,
        .vm = {true, 0x80000111}, .refs = 1};
    s->gpu.hw.capabilities = GPU_CAP_UNIFIED_COMMANDS | GPU_CAP_PROTECTED_VM_STATE;
    s->gpu.hw.compute_resource_flags = OPENGPU_COMPUTE_RESOURCE_TEXTURE_COMMANDS |
        OPENGPU_COMPUTE_RESOURCE_IMAGE_COMMANDS | OPENGPU_COMPUTE_RESOURCE_STORAGE_COMMANDS |
        OPENGPU_COMPUTE_RESOURCE_UNIFORM_COMMANDS |
        OPENGPU_COMPUTE_RESOURCE_BUFFER_TEXTURE_V1 | OPENGPU_COMPUTE_RESOURCE_BUFFER_IMAGE_SIZE_V1;
    s->gpu.hw.compute_texture_transport = s->gpu.hw.compute_storage_transport =
        s->gpu.hw.compute_image_transport = s->gpu.hw.compute_uniform_transport = true;
    if (in->with_counter_source) {
        s->gpu.hw.compute_counter_transport = true;
        s->gpu.hw.compute_resource_flags |= OPENGPU_COMPUTE_RESOURCE_ATOMIC_COUNTER_COMMANDS;
    }
    s->gpu.hw.compute_local_limits = (struct opengpu_compute_local_limits) {128, 128, true};
    s->gpu.regs[GPU_REG_UCMD_STATUS / 4] = GPU_UCMD_STATUS_READY;
    s->render.context = &s->context; s->drm.dev = &s->gpu; s->file.driver_priv = &s->render;
    wait_device = &s->gpu;
    uint64_t physical = 0x40000000;
    for (unsigned h = 1; h < 512; h++) if (in->memory[h].handle) {
        assert(in->memory[h].handle == h && in->memory[h].bytes && in->memory[h].data);
        s->objects[h] = (struct drm_gem_object) {.resv = &s->reservations[h], .refs = 1,
            .size = in->memory[h].bytes, .vaddr = in->memory[h].data, .dma_addr = physical};
        s->render.handles[h] = &s->objects[h];
        physical += (in->memory[h].bytes + 8191) & ~UINT64_C(4095);
        assert(physical < UINT64_C(0x100000000));
    }
    memset(out, 0, sizeof(*out));
    for (unsigned slot = 1; slot <= 42; slot++) {
        const struct drm_opengpu_resource *b = &in->bindings[slot];
        if (!b->handle) continue;
        assert(b->slot == slot && b->handle < 512 && s->objects[b->handle].refs);
        struct opengpu_kernarg_va_plan plan;
        u64 dma = s->objects[b->handle].dma_addr + b->offset, va = 0;
        unsigned index;
        if (b->type == OPENGPU_RESOURCE_COMPUTE_KERNARG)
            assert(!opengpu_kernarg_va_plan(dma, b->size, slot, 4096, &plan));
        else if (b->type == OPENGPU_RESOURCE_COMPUTE_TEXTURE)
            assert(!opengpu_texture_va_plan(dma, b->size, slot, 4096, &plan));
        else if (b->type == OPENGPU_RESOURCE_COMPUTE_STORAGE) {
            assert(opengpu_storage_backing_index(slot, &index));
            assert(!(index < 4 ? opengpu_storage_va_plan(dma, b->size, index, 4096, &plan) :
                opengpu_image_backing_va_plan(dma, b->size, index - 4, 4096, &plan)));
        } else { assert(b->type == OPENGPU_RESOURCE_COMPUTE_SHADER); memset(&plan, 0, sizeof(plan)); }
        va = plan.va;
        s->bindings[slot] = (struct opengpu_resource_binding) {.object = &s->objects[b->handle],
            .dma = dma, .va = va, .size = b->size, .offset = b->offset, .type = b->type};
        s->context.bindings[slot - 1] = &s->bindings[slot]; out->binding_va[slot] = va;
    }
    struct drm_opengpu_compute_image_resources request = in->command;
    request.launch.in_syncobj = 0; request.launch.out_syncobj = 2;
    int ret;
    if (in->with_counter_source) {
        struct drm_opengpu_compute_counter_source_resources source = in->counter_source;
        source.resources.resources.launch = request.launch;
        ret = opengpu_compute_counter_source_resources_ioctl(&s->drm, &source, &s->file);
    } else if (in->uniforms) {
        struct drm_opengpu_compute_uniform_resources gum = *in->uniforms;
        gum.resources.launch = request.launch;
        ret = opengpu_compute_uniform_resources_ioctl(&s->drm, &gum, &s->file);
    } else if (in->storage_only) {
        struct drm_opengpu_compute_storage r = {.launch = request.launch};
        memcpy(r.bindings, request.bindings, sizeof(r.bindings));
        ret = opengpu_compute_storage_ioctl(&s->drm, &r, &s->file);
    } else if (in->with_images) ret = opengpu_compute_image_resources_ioctl(&s->drm, &request, &s->file);
    else {
        struct drm_opengpu_compute_resources r = {.launch = request.launch,
            .views = request.textures, .view_count = request.texture_count};
        memcpy(r.bindings, request.bindings, sizeof(r.bindings));
        ret = opengpu_compute_resources_ioctl(&s->drm, &r, &s->file);
    }
    if (ret) { cleanup_state(s); return ret; }
    assert(queued_count == 1 && s->context.refs == 2);
    s->job = queued[0];
    out->private_state = s; out->context = s->context.id;
    out->textures = s->job->texture_records.cpu; out->texture_bytes = s->job->texture_records.size;
    out->images = s->job->image_records.cpu; out->image_bytes = s->job->image_records.size;
    out->storage = s->job->storage_records.cpu; out->storage_bytes = s->job->storage_records.size;
    out->uniforms = s->job->uniform_snapshot.cpu; out->uniform_bytes = s->job->uniform_snapshot.size;
    out->counters = s->job->counter_records.cpu; out->counter_bytes = s->job->counter_records.size;
    out->code = s->job->shader.cpu; out->code_bytes = s->job->shader.size;
    out->texture_root = s->job->kernel.texture.root; out->image_root = s->job->kernel.image.base;
    out->storage_root = s->job->kernel.storage.base; out->uniform_root = s->job->kernel.uniform.root;
    out->counter_root = s->job->kernel.counter.base;
    out->retained_objects = s->job->object_count;
    return 0;
}

void cs_buffer_kernel_finish(struct cs_buffer_kernel_result *out, int cancel)
{
    struct kernel_state *s = out->private_state; assert(s);
    if (cancel) {
        s->job->base.s_fence->finished.error = -ECANCELED;
        s->job->base.s_fence->finished.signaled = true;
        opengpu_sched_free_job(&s->job->base);
    } else finish(&s->gpu, s->job, 0);
    cleanup_state(s);
    memset(out, 0, sizeof(*out));
}
