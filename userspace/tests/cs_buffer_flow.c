/* SPDX-License-Identifier: MIT */
/* Real frontend/callback/lib/extracted kernel composition with a separate CPU
 * instruction/record oracle. OS, MMU and device replies are modeled, not RTL. */
#define main storage_callback_main
#define OPENGPU_CS_REAL_TGSI_DUMP
#include "cs_callback_flow.c"
#undef main
#include "cs_glsl_frontend.h"
#include "cs_buffer_kernel.h"
#include "opengpu_buffer_views.h"
#include "opengpu_native_texture_abi.h"
#include "opengpu_texture_image_binding_abi.h"
#include "opengpu_texture_image_level_abi.h"
#include "opengpu_shader_image_abi.h"
#include "opengpu_uniform_abi.h"

struct buffer_job {
    struct cs_buffer_kernel_input *input;
    struct cs_buffer_kernel_result records;
    const char *capture;
};
static const char *buffer_artifacts;
static struct buffer_job buffer_jobs[N];
#ifdef OPENGPU_CS_SHARED_PUBLICATION
static uint32_t shared_publication_seed;
static void shared_publication_execute(struct buffer_job *j);
#endif
static unsigned buffer_submissions, kernel_admissions, buffer_executions;
static unsigned actual_txf, actual_txq, actual_image_load, actual_image_store, actual_image_size;
static const enum pipe_format all_sampler_formats[30] = {
    PIPE_FORMAT_R8_UNORM, PIPE_FORMAT_R16_FLOAT, PIPE_FORMAT_R32_FLOAT,
    PIPE_FORMAT_R8_SINT, PIPE_FORMAT_R16_SINT, PIPE_FORMAT_R32_SINT,
    PIPE_FORMAT_R8_UINT, PIPE_FORMAT_R16_UINT, PIPE_FORMAT_R32_UINT,
    PIPE_FORMAT_R8G8_UNORM, PIPE_FORMAT_R16G16_FLOAT, PIPE_FORMAT_R32G32_FLOAT,
    PIPE_FORMAT_R8G8_SINT, PIPE_FORMAT_R16G16_SINT, PIPE_FORMAT_R32G32_SINT,
    PIPE_FORMAT_R8G8_UINT, PIPE_FORMAT_R16G16_UINT, PIPE_FORMAT_R32G32_UINT,
    PIPE_FORMAT_R32G32B32_FLOAT, PIPE_FORMAT_R32G32B32_SINT, PIPE_FORMAT_R32G32B32_UINT,
    PIPE_FORMAT_R8G8B8A8_UNORM, PIPE_FORMAT_R16G16B16A16_FLOAT, PIPE_FORMAT_R32G32B32A32_FLOAT,
    PIPE_FORMAT_R8G8B8A8_SINT, PIPE_FORMAT_R16G16B16A16_SINT, PIPE_FORMAT_R32G32B32A32_SINT,
    PIPE_FORMAT_R8G8B8A8_UINT, PIPE_FORMAT_R16G16B16A16_UINT, PIPE_FORMAT_R32G32B32A32_UINT,
};
static uint32_t get32(const void *p, unsigned word)
{
    const uint8_t *b = (const uint8_t *)p + word * 4;
    return b[0] | (uint32_t)b[1] << 8 | (uint32_t)b[2] << 16 | (uint32_t)b[3] << 24;
}
static uint64_t get64(const void *p, unsigned word)
{ return get32(p, word) | (uint64_t)get32(p, word + 1) << 32; }
static FILE *buffer_export(const char *name, const char *suffix)
{
    char path[4096]; int n = snprintf(path, sizeof(path), "%s/%s.%s", buffer_artifacts, name, suffix);
    assert(n > 0 && (size_t)n < sizeof(path)); FILE *f = fopen(path, "wx"); assert(f); return f;
}
static void export_bytes(const char *name, const char *suffix, const void *data, uint64_t bytes)
{
    FILE *f = buffer_export(name, suffix);
    assert(fwrite(data, 1, (size_t)bytes, f) == bytes && !fclose(f));
}
static void export_data(const struct buffer_job *j, const char *phase)
{
    for (unsigned slot = 3; slot <= 42; slot++) {
        const struct drm_opengpu_resource *b = &j->input->bindings[slot];
        if (!b->handle) continue;
        char suffix[80];
        assert(snprintf(suffix, sizeof(suffix), "%s.slot%u.prefix", phase, slot) > 0);
        export_bytes(j->capture, suffix, bos[b->handle].data + b->offset, 256);
        assert(snprintf(suffix, sizeof(suffix), "%s.slot%u.tail", phase, slot) > 0);
        export_bytes(j->capture, suffix, bos[b->handle].data + b->offset + b->size - 64, 64);
    }
}
static void export_records(struct buffer_job *j, const struct drm_opengpu_compute_uniform_resources *gum)
{
    if (!buffer_artifacts || (kernel_admissions != 1 && (!gum || uniform_submissions))) return;
    j->capture = gum ? "buffers-uniforms" : "buffers";
    struct drm_opengpu_compute_image_resources command = j->input->command;
    assert(command.texture_count == 32 && j->records.retained_objects == (gum ? 54u : 41u));
    command.textures = 0; /* The table bytes are exported separately, never a host pointer. */
    export_bytes(j->capture, "command", &command, sizeof(command));
    export_bytes(j->capture, "views", (const void *)(uintptr_t)j->input->command.textures,
        command.texture_count * sizeof(struct drm_opengpu_compute_texture_view));
    if (gum) { struct drm_opengpu_compute_uniform_resources copy = *gum; copy.resources.textures = 0;
        export_bytes(j->capture, "gum", &copy, sizeof(copy)); }
    export_bytes(j->capture, "shader", j->records.code, j->records.code_bytes);
    export_bytes(j->capture, "textures", j->records.textures, j->records.texture_bytes);
    export_bytes(j->capture, "images", j->records.images, j->records.image_bytes);
    export_bytes(j->capture, "storage", j->records.storage, j->records.storage_bytes);
    if (j->records.uniform_bytes) export_bytes(j->capture, "uniforms", j->records.uniforms, j->records.uniform_bytes);
    FILE *f = buffer_export(j->capture, "meta");
    fprintf(f, "format=1\nGPU_execution=0\ncontext=%u\nretained=%u\ntexture.root=%u\nimage.root=%u\nstorage.root=%u\nuniform.root=%u\n",
        j->records.context, j->records.retained_objects, j->records.texture_root, j->records.image_root,
        j->records.storage_root, j->records.uniform_root);
    for (unsigned slot = 1; slot <= 42; slot++) {
        const struct drm_opengpu_resource *b = &j->input->bindings[slot];
        if (!b->handle) continue;
        fprintf(f, "slot.%u.handle=%u\nslot.%u.offset=%llu\nslot.%u.bytes=%llu\nslot.%u.va=%llu\nslot.%u.GEMbytes=%llu\n",
            slot, b->handle, slot, (unsigned long long)b->offset, slot, (unsigned long long)b->size,
            slot, (unsigned long long)j->records.binding_va[slot], slot, (unsigned long long)bos[b->handle].size);
    }
    assert(!ferror(f) && !fclose(f)); export_data(j, "before");
}
static const void *texture_record(struct buffer_job *j, uint64_t address, unsigned bytes)
{
    assert(address >= j->records.texture_root && address - j->records.texture_root <= j->records.texture_bytes &&
        bytes <= j->records.texture_bytes - (address - j->records.texture_root));
    return (const uint8_t *)j->records.textures + address - j->records.texture_root;
}
static const void *uniform_record(struct buffer_job *j, uint64_t address, unsigned bytes)
{
    assert(address >= j->records.uniform_root && address - j->records.uniform_root <= j->records.uniform_bytes &&
        bytes <= j->records.uniform_bytes - (address - j->records.uniform_root));
    return (const uint8_t *)j->records.uniforms + address - j->records.uniform_root;
}
static void uniform_views(struct buffer_job *j, struct cs_vm_view views[13])
{
    if (!j->records.uniform_bytes) return;
    const void *root = uniform_record(j, j->records.uniform_root, 64);
    assert(get32(root, 0) == OPENGPU_UNIFORM_ROOT_MAGIC && get32(root, 1) == 64 && get32(root, 2) == 1 &&
        get32(root, 3) == j->records.context && get32(root, 6) == 2);
    unsigned active = get32(root, 7); assert(!(active & ~4095u));
    for (unsigned slot = 0; slot < 13; slot++) {
        if (slot ? !(active & (1u << (slot - 1))) : !get32(root, 14)) continue;
        unsigned index = slot ? (get32(root, slot <= 6 ? 8 : 9) >> (5 * ((slot - 1) % 6))) & 31 : 31;
        uint64_t address = slot ? (uint64_t)get32(root, 10) + index * 64 : get32(root, 13);
        if (slot) assert(index < 24 && address + 64 <= get64(root, 11));
        const void *binding = uniform_record(j, address, 64);
        uint64_t base = get32(binding, 8), limit = get64(binding, 9);
        assert(get32(binding, 0) == OPENGPU_UNIFORM_BINDING_MAGIC && get32(binding, 1) == 64 &&
            get32(binding, 2) == 1 && get32(binding, 3) == j->records.context && get32(binding, 6) == index &&
            get32(binding, 7) == 4 && get32(binding, 14) == 1 && limit > base &&
            base >= get32(binding, 11) && limit <= get64(binding, 12));
        assert(limit - base <= (slot ? 16384u : 4096u));
        views[slot] = (struct cs_vm_view) {(uint32_t *)uniform_record(j, base, (unsigned)(limit - base)), limit - base, 1};
    }
}
static void *data_address(struct buffer_job *j, uint64_t address, unsigned bytes)
{
    if (j->input->with_counter_source) {
        const struct drm_opengpu_compute_counter_source *s = &j->input->counter_source.source;
        assert(j->records.counters && j->records.counter_bytes == 64);
        uint64_t base = get32(j->records.counters, 10), limit = get64(j->records.counters, 11);
        if (address >= base && address <= limit && bytes <= limit - address) {
            assert(s->offset + address - base <= bos[s->handle].size &&
                bytes <= bos[s->handle].size - s->offset - (address - base));
            return bos[s->handle].data + s->offset + address - base;
        }
    }
    for (unsigned slot = 3; slot <= 42; slot++) {
        const struct drm_opengpu_resource *b = &j->input->bindings[slot];
        uint64_t base = j->records.binding_va[slot];
        if (b->handle && address >= base && address - base <= b->size && bytes <= b->size - (address - base)) {
            assert(b->offset + address - base <= bos[b->handle].size &&
                bytes <= bos[b->handle].size - b->offset - (address - base));
            return bos[b->handle].data + b->offset + address - base;
        }
    }
    assert(!"actual kernel record escaped every retained binding"); return NULL;
}
static uint32_t texture_value(void *opaque, unsigned unit, uint32_t index, unsigned component, int query)
{
    struct buffer_job *j = opaque;
    const void *root = j->records.textures;
    assert(root && get32(root, 0) == OPENGPU_NATIVE_TEXTURE_ROOT_MAGIC && get32(root, 3) == j->records.context);
    assert(unit < get32(root, 7));
    const void *b = texture_record(j, get32(root, 8) + (uint64_t)unit * 64, 64);
    assert(get32(b, 0) == OPENGPU_NATIVE_TEXTURE_BINDING_MAGIC && get32(b, 7) == unit && get32(b, 11) == 5);
    const void *image = texture_record(j, get32(root, 11) + (uint64_t)get32(b, 8) * 128, 128);
    assert(get32(image, 0) == OPENGPU_TEXTURE_IMAGE_BINDING_MAGIC && get32(image, 7) == 5);
    const void *level = texture_record(j, get32(image, 19), 128);
    assert(get32(level, 0) == OPENGPU_TEXTURE_IMAGE_LEVEL_MAGIC && get32(level, 7) == 5 && !get32(level, 8));
    assert(!get32(root, 14) && !get64(root, 16)); /* samplerless actual table */
    uint32_t count = get32(image, 28);
    assert(count == get32(level, 25) && get64(image, 29) == get64(level, 26));
    if (query) { actual_txq++; return component ? 0 : count; }
    actual_txf++;
    unsigned format=get32(image,8),rgba=format==OPENGPU_TEXTURE_FORMAT_RGBA32UI;
    assert((rgba||format==OPENGPU_TEXTURE_FORMAT_R32UI) && get32(b,10)==1 && index<count);
    unsigned stride=rgba?16:4;
    uint64_t address = (uint64_t)get32(level, 12) + (uint64_t)index * stride;
    assert(address >= get32(level, 9) && address + stride <= get64(level, 10));
    unsigned selected = get32(image, 23) ? get32(image, 24 + component) : component;
    if(selected==5)return 1;
    if(selected==4)return 0;
    uint32_t value;
    if(rgba){assert(selected<4);memcpy(&value,data_address(j,address+selected*4,4),4);return value;}
    memcpy(&value, data_address(j, address, 4), 4);
    return selected == 0 ? value : selected == 3 ? 1 : 0;
}
static void image_value(void *opaque, unsigned unit, unsigned controls, uint32_t index,
                        uint32_t rgba[4], int write, int query)
{
    struct buffer_job *j = opaque;
    assert(unit < 4 && j->records.images && j->records.image_bytes == 1024);
    const void *image = (const uint8_t *)j->records.images + unit * 256;
    assert(get32(image, 0) == OPENGPU_SHADER_IMAGE_MAGIC && get32(image, 3) == j->records.context &&
        get32(image, 6) == unit && get32(image, 7) && get32(image, 8) && (get32(image, 9) & 4));
    assert((controls & 7) == 5 && (controls >> 3 & 3) == 1 && (controls & 32) &&
        (controls >> 6) == get32(image, 13) && get32(image, 20) == 5);
    assert(get32(image, 13) == OPENGPU_TEXTURE_FORMAT_R32UI);
    if (query) { actual_image_size++; rgba[0] = get32(image, 38); return; }
    assert(get32(image, write ? 11 : 10) && index < get32(image, 38));
    uint64_t address = (uint64_t)get32(image, 25) + (uint64_t)index * 4;
    assert(address + 4 <= get64(image, 23));
    if (write) { actual_image_store++; memcpy(data_address(j, address, 4), rgba, 4); }
    else { actual_image_load++; memcpy(rgba, data_address(j, address, 4), 4); rgba[1] = rgba[2] = 0; rgba[3] = 1; }
}
static int submit_buffers(unsigned long request, void *arg)
{
    struct cs_buffer_kernel_input *in = calloc(1, sizeof(*in)); assert(in);
    if (request == DRM_IOCTL_OPENGPU_COMPUTE_STORAGE) {
        const struct drm_opengpu_compute_storage *r = arg;
        in->command.launch = r->launch;
        memcpy(in->command.bindings, r->bindings, sizeof(r->bindings)); in->storage_only = 1;
    } else if (request == DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES) {
        in->command = *(struct drm_opengpu_compute_image_resources *)arg; in->with_images = 1;
    } else if (request == DRM_IOCTL_OPENGPU_COMPUTE_RESOURCES) {
        const struct drm_opengpu_compute_resources *r = arg;
        in->command.launch = r->launch; in->command.textures = r->views; in->command.texture_count = r->view_count;
        memcpy(in->command.bindings, r->bindings, sizeof(r->bindings));
    } else {
        const struct drm_opengpu_compute_uniform_resources *r;
        if (request == DRM_IOCTL_OPENGPU_COMPUTE_COUNTER_SOURCE_RESOURCES) {
            in->counter_source = *(const struct drm_opengpu_compute_counter_source_resources *)arg;
            in->with_counter_source = 1; r = &in->counter_source.resources;
        } else r = arg;
        if (!in->with_counter_source || r->active_mask || r->defaults.handle) in->uniforms = r;
        in->command.launch = r->resources.launch;
        in->command.textures = r->resources.textures; in->command.texture_count = r->resources.texture_count;
        memcpy(in->command.bindings, r->resources.bindings, sizeof(r->resources.bindings));
        memcpy(in->command.images, r->resources.images, sizeof(r->resources.images));
        in->with_images = !!(r->resources.resource_flags & OPENGPU_COMPUTE_COUNTER_RESOURCE_IMAGES);
    }
    unsigned context = in->command.launch.context_id, sync = in->command.launch.out_syncobj;
    assert(contexts[context].live && live_sync[sync] && !jobs[sync].live && !buffer_jobs[sync].input);
    in->shared_base=limit_shared_base;in->shared_bytes=limit_shared_bytes;in->shared_lanes=limit_shared_lanes;
    memcpy(in->bindings, contexts[context].b, sizeof(in->bindings));
    for (unsigned h = 1; h < next_bo; h++) if (bos[h].handle)
        in->memory[h] = (struct cs_buffer_bo) {h, bos[h].size, bos[h].data};
    struct cs_buffer_kernel_result records;
    int ret = cs_buffer_kernel_begin(in, &records);
    if (ret) { free(in); errno = -ret; return -1; }
    kernel_admissions++;
    struct buffer_job prepared = {.input = in, .records = records};
    export_records(&prepared, in->uniforms);
    if (in->uniforms) {
        for (unsigned i = 0; i < 13; i++) {
            const struct drm_opengpu_compute_uniform_view *v = i ? &in->uniforms->blocks[i - 1] : &in->uniforms->defaults;
            if (!v->handle) continue;
            uniform_snapshots++;
            if (uniform_mutate_after_snapshot) memset(bos[v->handle].data + v->offset, 0x99, v->bytes);
        }
        uniform_submissions++;
    }
    /* Kernel snapshots, not the user pointers, outlive this ioctl call. */
    in->uniforms = NULL; in->command.textures = 0;
    buffer_jobs[sync] = prepared;
    jobs[sync] = (struct job) {.live = 1, .kind = 4, .context = context, .status = status_error ? -EIO : 0};
    const unsigned code_handle = in->bindings[in->command.launch.shader_slot].handle;
    retain_job(&jobs[sync], code_handle);
    for (unsigned slot = 2; slot <= 42; slot++) if (in->bindings[slot].handle)
        retain_job(&jobs[sync], in->bindings[slot].handle);
    if (in->with_counter_source) retain_job(&jobs[sync], in->counter_source.source.handle);
    last_compute_context = context; computes++; buffer_submissions++;
    return 0;
}
static void execute_buffers(unsigned sync, int execute)
{
    struct buffer_job *j = &buffer_jobs[sync]; assert(j->input);
    if (execute) {
        struct cs_vm_view storage[4] = {{0}}, uniforms[13] = {{0}};
        uniform_views(j, uniforms);
        for (unsigned b = 0; b < 4; b++) {
            const void *r = (const uint8_t *)j->records.storage + b * 64;
            if (!get32(r, 7)) continue;
            uint64_t base = get32(r, 10), limit = get64(r, 11);
            unsigned slot = j->input->command.bindings[b].slot;
            assert(base == j->records.binding_va[slot] && limit - base == j->input->bindings[slot].size);
            storage[b] = (struct cs_vm_view) {data_address(j, base, 4), limit - base,
                j->input->command.bindings[b].access};
        }
        struct cs_vm_view counters = {0};
        if (j->records.counter_bytes) {
            const void *r = j->records.counters;
            assert(j->records.counter_bytes == 64 && get32(r, 3) == j->records.context &&
                get32(r, 7) && get32(r, 8) && get32(r, 9));
            uint64_t base = get32(r, 10), limit = get64(r, 11);
            assert(limit > base && limit - base <= 32 && !(base & 3) && !((limit - base) & 3));
            counters = (struct cs_vm_view) {data_address(j, base, 4), limit - base, 3};
        }
        const struct cs_vm_resources resources = {j, texture_value, image_value};
        const uint32_t group[3] = {0};
        assert(j->input->command.launch.grid[0] == 1 && j->input->command.launch.grid[1] == 1 &&
            j->input->command.launch.grid[2] == 1);
#ifdef OPENGPU_CS_SHARED_PUBLICATION
        if(j->input->command.launch.local[0]==128)shared_publication_execute(j);
        else
#endif
        cs_vm_group_resources_counters_checked(j->records.code, (unsigned)j->records.code_bytes / 4,
            j->input->command.launch.grid, group, j->input->command.launch.local, storage, uniforms, NULL, &resources,
            counters.data ? &counters : NULL);
        buffer_executions++;
    }
    if (j->capture) export_data(j, "after");
    cs_buffer_kernel_finish(&j->records, !execute);
    free(j->input); memset(j, 0, sizeof(*j));
}
static struct pipe_resource *new_buffer(struct pipe_screen *s, unsigned bytes)
{
    struct pipe_resource r = {.target = PIPE_BUFFER, .format = PIPE_FORMAT_R8_UNORM,
        .width0 = bytes, .height0 = 1, .depth0 = 1, .array_size = 1,
        .bind = PIPE_BIND_SHADER_BUFFER | PIPE_BIND_SAMPLER_VIEW | PIPE_BIND_SHADER_IMAGE};
    sparse_next = 1;
    struct pipe_resource *out = s->resource_create(s, &r); assert(out);
    return out;
}
static uint32_t sample(unsigned generation, unsigned unit, unsigned item)
{ return 0x713a2b09u ^ generation * 0x1020304u ^ unit * 0x718293u ^ item * 0x9137u; }

static void reject_launch(struct pipe_context *p, const struct pipe_grid_info *grid)
{
    unsigned before = buffer_submissions, queued = kernel_admissions, binds = binding_attempts, requests = resource_request_attempts;
    p->launch_grid(p, grid);
    assert(buffer_submissions == before && kernel_admissions == queued);
    assert(binding_attempts == binds && resource_request_attempts == requests);
}

static void binding_failures(struct pipe_context *p, void *cs, const struct pipe_grid_info *grid)
{
    for (unsigned i = 0; i < 40; i++) {
        p->bind_compute_state(p, NULL); p->bind_compute_state(p, cs);
        unsigned before = buffer_submissions;
        fail_request = DRM_IOCTL_OPENGPU_RESOURCE_BIND; fail_nth = 3 + i; fail_seen = 0;
        p->launch_grid(p, grid);
        assert(buffer_submissions == before && live_contexts() == 1);
        fail_request = fail_nth = fail_seen = 0;
        p->launch_grid(p, grid); assert(buffer_submissions == before + 1);
        p->memory_barrier(p, PIPE_BARRIER_ALL);
    }
    /* A timeout does not cancel/retire the producer or delete its namespace.
     * Repeated explicit calls cannot replay it while the original fence is
     * unsignaled. Only a later modeled signal allows publication+teardown. */
    unsigned before = buffer_submissions, old_canceled = canceled, old_invalidates = invalidates;
    wait_error = 1;
    p->launch_grid(p, grid); assert(buffer_submissions == before + 1 && live_contexts() == 2);
    assert(canceled == old_canceled && invalidates == old_invalidates);
    p->launch_grid(p, grid); assert(buffer_submissions == before + 1 && live_contexts() == 2);
    wait_error = 0;
    p->launch_grid(p, grid); /* resolves old fence, publishes, destroys; no new dispatch */
    assert(buffer_submissions == before + 1 && live_contexts() == 1 && canceled == old_canceled);
    p->launch_grid(p, grid); assert(buffer_submissions == before + 2);
    /* Accepted invalidate timeout keeps pending refs/fence too. */
    before = buffer_submissions; wait_error = 1;
    p->memory_barrier(p, PIPE_BARRIER_ALL); assert(live_contexts() == 2);
    p->launch_grid(p, grid); assert(buffer_submissions == before && live_contexts() == 2);
    wait_error = 0;
    p->launch_grid(p, grid); assert(buffer_submissions == before && live_contexts() == 1);
    p->launch_grid(p, grid); assert(buffer_submissions == before + 1);
    p->memory_barrier(p, PIPE_BARRIER_ALL);
    puts("40 partial-bind failures + unsignaled producer/invalidate quarantine + explicit retirement/fresh requests PASS");
}

static void producer_tokens(struct builder *b)
{
    begin(b, PIPE_SHADER_COMPUTE);
    append_decl(b, TGSI_FILE_TEMPORARY, 0, 1, -1);
    append_decl(b, TGSI_FILE_SYSTEM_VALUE, 0, 0, TGSI_SEMANTIC_THREAD_ID);
    append_decl(b, TGSI_FILE_BUFFER, 0, 0, -1);
    immediate(b, 2, 0x12345000u, 0, 0);
    insn(b, TGSI_OPCODE_SHL, TGSI_FILE_TEMPORARY, 0, 1, S(0, 0), I(0));
    insn(b, TGSI_OPCODE_UADD, TGSI_FILE_TEMPORARY, 1, 1, S(0, 0), I(1));
    insn(b, TGSI_OPCODE_STORE, TGSI_FILE_BUFFER, 0, 1, T(0, 0), T(1, 0));
    insn(b, TGSI_OPCODE_END, 0, 0, 0, I(0), I(0));
}
static void *producer_state(struct pipe_context *p)
{
    struct builder b; producer_tokens(&b);
    struct pipe_compute_state st = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = b.t};
    void *cs = p->create_compute_state(p, &st); assert(cs); return cs;
}

static void orphan_recovery(void)
{
    const struct pipe_grid_info grid = {.work_dim = 1, .block = {4, 1, 1}, .grid = {1, 1, 1}};
    struct pipe_screen *s = opengpu_drm_screen_create(7, NULL); assert(s);
    struct pipe_context *producer = s->context_create(s, NULL, 0), *consumer = s->context_create(s, NULL, 0);
    assert(producer && consumer);
    struct pipe_resource *source = new_buffer(s, 512), *output = new_buffer(s, 512);
    struct pipe_shader_buffer inputs[2] = {{.buffer = source, .buffer_size = 64},
        {.buffer = output, .buffer_size = 64}};
    struct builder b; begin(&b, PIPE_SHADER_COMPUTE);
    append_decl(&b, TGSI_FILE_TEMPORARY, 0, 1, -1);
    append_decl(&b, TGSI_FILE_SYSTEM_VALUE, 0, 0, TGSI_SEMANTIC_THREAD_ID);
    append_decl(&b, TGSI_FILE_BUFFER, 0, 1, -1); immediate(&b, 2, 0, 0, 0);
    insn(&b, TGSI_OPCODE_SHL, TGSI_FILE_TEMPORARY, 0, 1, S(0, 0), I(0));
    insn(&b, TGSI_OPCODE_LOAD, TGSI_FILE_TEMPORARY, 1, 1, src(TGSI_FILE_BUFFER, 0, 0), T(0, 0));
    insn(&b, TGSI_OPCODE_STORE, TGSI_FILE_BUFFER, 1, 1, T(0, 0), T(1, 0));
    insn(&b, TGSI_OPCODE_END, 0, 0, 0, I(0), I(0));
    struct pipe_compute_state state = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = b.t};
    void *read = consumer->create_compute_state(consumer, &state); assert(read);
    consumer->bind_compute_state(consumer, read);
    consumer->set_shader_buffers(consumer, PIPE_SHADER_COMPUTE, 0, 2, inputs, 2);
    consumer->launch_grid(consumer, &grid); consumer->memory_barrier(consumer, PIPE_BARRIER_ALL);
    void *write = producer_state(producer); producer->bind_compute_state(producer, write);
    producer->set_shader_buffers(producer, PIPE_SHADER_COMPUTE, 0, 1, inputs, 1);
    unsigned before = buffer_submissions, cancel = canceled;
    wait_error = 1; producer->launch_grid(producer, &grid);
    assert(buffer_submissions == before + 1 && jobs[next_sync - 1].live);
    unsigned sync = next_sync - 1, binds = binding_attempts, invalidate = invalidates, namespaces = next_context;
    errno = EDOM; producer->destroy(producer); assert(errno == EDOM);
    assert(jobs[sync].live && canceled == cancel &&
        bos[pipe_opengpu_resource_handle(opengpu_resource(source)->gpu)].data);
    consumer->launch_grid(consumer, &grid); consumer->memory_barrier(consumer, PIPE_BARRIER_ALL);
    assert(!s->context_create(s, NULL, 0));
    assert(buffer_submissions == before + 1 && binding_attempts == binds && invalidates == invalidate &&
        next_context == namespaces && canceled == cancel && jobs[sync].live);
    wait_error = 0;
    consumer->memory_barrier(consumer, PIPE_BARRIER_ALL); /* also reaps when cs_gpu is NULL */
    assert(!jobs[sync].live && buffer_submissions == before + 1 && invalidates == invalidate + 1);
    assert(last_invalidate.handle == pipe_opengpu_resource_handle(opengpu_resource(source)->gpu) &&
        last_invalidate.bytes == 64 && canceled == cancel);
    consumer->launch_grid(consumer, &grid); assert(buffer_submissions == before + 2);
    const uint32_t *values = pipe_opengpu_resource_map(opengpu_resource(output)->gpu);
    for (unsigned i = 0; i < 4; i++) assert(values[i] == 0x12345000u + i);
    consumer->memory_barrier(consumer, PIPE_BARRIER_ALL); consumer->destroy(consumer);
    pipe_resource_reference(&source, NULL); pipe_resource_reference(&output, NULL); s->destroy(s); no_leaks();
    puts("Final Gallium destructor -> screen-owned orphan; unsignaled launch/barrier/create reject, original fence publication then fresh consumer PASS");
}

static void final_screen_ownership(void)
{
    /* Model namespace deletion separately from hardware/free-job completion.
     * The actual kernel-body fixture independently proves this distinction. */
    for (unsigned failure = 0; failure < 2; failure++) {
        struct pipe_screen *s = opengpu_drm_screen_create(7, NULL); assert(s);
        struct pipe_opengpu_screen *gpu = opengpu_screen_gpu(s);
        struct pipe_opengpu_context *empty = failure ? pipe_opengpu_context_create(gpu) : NULL;
        struct pipe_opengpu_context *p = pipe_opengpu_context_create(gpu); assert(p && (!failure || empty));
        struct builder b; producer_tokens(&b); struct opengpu_cs_program code;
        assert(!opengpu_cs_emit(b.t, &code) && !pipe_opengpu_bind_cs(p, code.code, code.words * 4));
        struct pipe_resource *resource = new_buffer(s, 512);
        unsigned handle = pipe_opengpu_resource_handle(opengpu_resource(resource)->gpu);
        const struct pipe_opengpu_storage_view views[4] = {{.resource = opengpu_resource(resource)->gpu,
            .bytes = 64, .access = OPENGPU_COMPUTE_STORAGE_WRITE}};
        const uint32_t grid[3] = {1, 1, 1}, local[3] = {4, 1, 1};
        unsigned before = buffer_submissions, cancel = canceled, close_count = render_closes, invalidate = invalidates;
        wait_error = 1;
        assert(pipe_opengpu_launch_grid_storage(p, grid, local, views, 10) < 0 && errno == ETIME);
        unsigned sync = next_sync - 1, context = jobs[sync].context;
        assert(jobs[sync].live && buffer_submissions == before + 1);
        unsigned binds = binding_attempts, allocated = next_bo, syncs = next_sync;
        assert(pipe_opengpu_bind_cs(p, code.code, code.words * 4) < 0 && errno == EIO);
        assert(pipe_opengpu_launch_grid(p, grid, local, NULL) < 0 && errno == EIO);
        assert(binding_attempts == binds && next_bo == allocated && next_sync == syncs &&
            buffer_submissions == before + 1 && jobs[sync].live);
        if (failure) {
            fail_request = DRM_IOCTL_OPENGPU_CONTEXT_DESTROY; fail_nth = 1; fail_seen = 0;
            pipe_opengpu_context_destroy(empty);
            fail_request = fail_nth = fail_seen = 0;
        }
        errno = ERANGE; pipe_opengpu_context_destroy(p); assert(errno == ERANGE);
        unsigned calls = ioctl_calls;
        pipe_opengpu_context_destroy(p); assert(ioctl_calls == calls); /* transfer only once */
        assert(pipe_opengpu_context_destroy_checked(p) < 0 && errno == EINVAL);
        pipe_resource_reference(&resource, NULL);
        assert(bos[handle].handle && bos[handle].maps && bos[handle].jobs && bos[handle].binds);
        defer_namespace_destroy = 1;
        if (failure) { fail_request = DRM_IOCTL_OPENGPU_CONTEXT_DESTROY; fail_nth = 1; fail_seen = 0; }
        s->destroy(s); /* no remaining caller-owned screen/resource */
        assert(buffer_submissions == before + 1 && canceled == cancel && invalidates == invalidate && jobs[sync].live);
        if (!failure) {
            assert(render_closes == close_count + 1 && !contexts[context].live && contexts[context].closing);
            assert(!bos[handle].handle && !bos[handle].maps && bos[handle].jobs && bos[handle].binds && bos[handle].data);
            /* Closing the owned duplicate was a no-op in this OS model. Only
             * this later explicit device/free-job event releases the backing. */
            finish_job(sync, 1);
            assert(!bos[handle].data && !contexts[context].closing);
        } else {
            assert(render_closes == close_count && contexts[context].live && bos[handle].handle && bos[handle].maps);
            fail_request = fail_nth = fail_seen = 0;
            binds = binding_attempts; unsigned namespaces = next_context;
            assert(!opengpu_drm_screen_create(7, NULL)); /* terminal owner still reachable */
            assert(render_closes == close_count && binding_attempts == binds && next_context == namespaces && jobs[sync].live);
            wait_error = 0;
            struct pipe_screen *fresh = opengpu_drm_screen_create(7, NULL); assert(fresh);
            assert(!jobs[sync].live && !bos[handle].data && invalidates == invalidate + 1 &&
                render_closes == close_count + 1 && buffer_submissions == before + 1 && canceled == cancel);
            struct pipe_context *usable = fresh->context_create(fresh, NULL, 0); assert(usable);
            usable->destroy(usable); fresh->destroy(fresh);
        }
        wait_error = defer_namespace_destroy = 0; no_leaks();
    }
    puts("Final screen: explicit namespace revoke preserves live job backing; revoke failure keeps reachable FD/context owner until original-fence recovery PASS");
}

static void query_levels_only(struct pipe_context *p, void *restore, struct pipe_resource *native_zero)
{
    struct builder b; begin(&b, PIPE_SHADER_COMPUTE);
    append_decl(&b, TGSI_FILE_TEMPORARY, 0, 0, -1);
    append_decl(&b, TGSI_FILE_BUFFER, 0, 0, -1);
    append_decl(&b, TGSI_FILE_SAMPLER, 31, 31, -1);
    struct tgsi_full_declaration d = tgsi_default_full_declaration();
    d.Declaration.File = TGSI_FILE_SAMPLER_VIEW; d.Range.First = d.Range.Last = 31;
    d.SamplerView.Resource = TGSI_TEXTURE_BUFFER;
    d.SamplerView.ReturnTypeX = d.SamplerView.ReturnTypeY = d.SamplerView.ReturnTypeZ = d.SamplerView.ReturnTypeW = TGSI_RETURN_TYPE_UINT;
    assert(tgsi_build_full_declaration(&d, b.t + b.h->HeaderSize + b.h->BodySize, b.h,
        TOKENS - b.h->HeaderSize - b.h->BodySize));
    immediate(&b, 0, 0, 0, 0);
    struct tgsi_full_instruction q = tgsi_default_full_instruction();
    q.Instruction.Opcode = TGSI_OPCODE_TXQ; q.Instruction.Texture = 1;
    q.Instruction.NumDstRegs = 1; q.Instruction.NumSrcRegs = 2;
    q.Texture.Texture = TGSI_TEXTURE_BUFFER; q.Texture.ReturnType = TGSI_RETURN_TYPE_SINT;
    q.Dst[0].Register.File = TGSI_FILE_TEMPORARY; q.Dst[0].Register.WriteMask = 8;
    q.Src[0].Register = I(0); q.Src[1].Register = src(TGSI_FILE_SAMPLER, 31, 0);
    assert(tgsi_build_full_instruction(&q, b.t + b.h->HeaderSize + b.h->BodySize, b.h,
        TOKENS - b.h->HeaderSize - b.h->BodySize));
    insn(&b, TGSI_OPCODE_STORE, TGSI_FILE_BUFFER, 0, 1, I(0), T(0, 3));
    insn(&b, TGSI_OPCODE_END, 0, 0, 0, I(0), I(0));
    struct pipe_compute_state st = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = b.t};
    void *cs = p->create_compute_state(p, &st); assert(cs); p->bind_compute_state(p, cs);
    const struct pipe_grid_info grid = {.work_dim = 1, .block = {1, 1, 1}, .grid = {1, 1, 1}};
    unsigned queries = actual_txq, fetches = actual_txf, before = buffer_submissions;
    p->launch_grid(p, &grid);
    assert(buffer_submissions == before + 1 && actual_txq == queries + 1 && actual_txf == fetches);
    uint32_t value; memcpy(&value, pipe_opengpu_resource_map(opengpu_resource(native_zero)->gpu), 4); assert(value == 1);
    p->memory_barrier(p, PIPE_BARRIER_ALL);
    p->bind_compute_state(p, restore); p->delete_compute_state(p, cs);
    puts("Actual TXQ.w-only program still queries kernel metadata and stores one accessible level PASS");
}

static void ordinary_producer(struct pipe_screen *s, struct pipe_context *p, void *consumer,
    const struct pipe_grid_info *grid, struct pipe_resource *texture,
    struct pipe_sampler_view *samplers[32], const struct pipe_image_view image_views[4],
    const struct pipe_shader_buffer storage[4], struct pipe_resource *ssbo[4])
{
    void *producer = producer_state(p);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 0, 32, false, NULL);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 0, 4, NULL);
    struct pipe_shader_buffer view[4] = {{.buffer = texture, .buffer_offset = 256, .buffer_size = 64}};
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, view, 1);
    p->bind_compute_state(p, producer);
    unsigned before = buffer_submissions, old_invalidates = invalidates;
    p->launch_grid(p, grid); assert(buffer_submissions == before + 1);
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, NULL, 0);
    /* A real program switch publishes the old SSBO writes before deleting its
     * private namespace. A different buffer view then consumes those bytes. */
    p->bind_compute_state(p, consumer);
    assert(invalidates == old_invalidates + 1 && last_invalidate.bytes == 320 &&
        last_invalidate.handle == pipe_opengpu_resource_handle(opengpu_resource(texture)->gpu));
    struct pipe_sampler_view t = *samplers[31]; t.u.buf.offset = 256;
    struct pipe_sampler_view *changed = p->create_sampler_view(p, texture, &t); assert(changed);
    struct pipe_sampler_view *views[32]; memcpy(views, samplers, sizeof(views)); views[31] = changed;
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 32, 0, false, views);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 4, 0, image_views);
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, storage, 15);
    p->memory_barrier(p, PIPE_BARRIER_IMAGE | PIPE_BARRIER_TEXTURE | PIPE_BARRIER_SHADER_BUFFER);
    p->launch_grid(p, grid); assert(buffer_submissions == before + 2);
    for (unsigned b = 0; b < 4; b++) {
        const uint32_t *data = pipe_opengpu_resource_map(opengpu_resource(ssbo[b])->gpu);
        for (unsigned item = 0; item < 4; item++) {
            uint32_t expected[] = {0x12345000u + item, sample(10, 0, item), 65536,
                (128u * 1024u * 1024u - 64) / 4};
            for (unsigned c = 0; c < 4; c++) assert(data[item * 4 + c] == (expected[c] ^ b * 0x10203u));
        }
    }
    p->memory_barrier(p, PIPE_BARRIER_ALL);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 31, 1, 0, false, &samplers[31]);
    pipe_sampler_view_reference(&changed, NULL);
    p->delete_compute_state(p, producer);

    /* No Gallium view or caller ref remains. Only the winsys pending-write
     * reference can keep this original handle/mapping valid for the barrier. */
    producer = producer_state(p);
    struct pipe_resource *only = new_buffer(s, 512);
    unsigned handle = pipe_opengpu_resource_handle(opengpu_resource(only)->gpu);
    view[0] = (struct pipe_shader_buffer) {.buffer = only, .buffer_size = 64};
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 0, 32, false, NULL);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 0, 4, NULL);
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, view, 1);
    p->bind_compute_state(p, producer); p->launch_grid(p, grid);
    pipe_resource_reference(&only, NULL);
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, NULL, 0);
    assert(bos[handle].handle && bos[handle].maps && bos[handle].data);
    p->memory_barrier(p, PIPE_BARRIER_ALL);
    assert(!bos[handle].handle && !bos[handle].maps);
    p->delete_compute_state(p, producer); assert(!bos[handle].data);
    p->bind_compute_state(p, consumer);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 32, 0, false, samplers);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 4, 0, image_views);
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, storage, 15);
    puts("Actual storage-only producer -> unbind/view change -> buffer consumer; sole pending-BO retain/publication PASS");
}

static void malformed_buffers(const void *tokens)
{
    const struct tgsi_token *source = tokens;
    struct tgsi_header header; memcpy(&header, source, 4);
    unsigned words = header.HeaderSize + header.BodySize;
    struct tgsi_token *copy = malloc(words * 4); assert(copy);
    unsigned view = 0, image = 0, fetch = 0, store = 0, query = 0;
    for (unsigned pos = 2; pos < words;) {
        struct tgsi_token token; memcpy(&token, source + pos, 4);
        unsigned n = token.NrTokens;
        if (token.Type == TGSI_TOKEN_TYPE_DECLARATION) {
            struct tgsi_declaration d; memcpy(&d, source + pos, 4);
            if (d.File == TGSI_FILE_SAMPLER_VIEW && !view) view = pos;
            if (d.File == TGSI_FILE_IMAGE && !image) image = pos;
        } else if (token.Type == TGSI_TOKEN_TYPE_INSTRUCTION) {
            struct tgsi_instruction i; memcpy(&i, source + pos, 4); n++;
            if (i.Opcode == TGSI_OPCODE_TXF && !fetch) fetch = pos;
            if (i.Opcode == TGSI_OPCODE_STORE && i.Memory && !store) store = pos;
            if (i.Opcode == TGSI_OPCODE_RESQ && !query) query = pos;
        }
        assert(n); pos += n;
    }
    assert(view && image && fetch && store && query);
    for (unsigned bad = 0; bad < 14; bad++) {
        memcpy(copy, source, words * 4);
        if (bad < 3) {
            struct tgsi_declaration_sampler_view v; memcpy(&v, copy + view + 2, 4);
            if (!bad) v.Resource = TGSI_TEXTURE_2D;
            else if (bad == 1) v.ReturnTypeX = TGSI_RETURN_TYPE_UNKNOWN;
            else {
                struct tgsi_declaration_range r; memcpy(&r, copy + view + 1, 4); r.Last = 32;
                memcpy(copy + view + 1, &r, 4);
            }
            memcpy(copy + view + 2, &v, 4);
        } else if (bad < 6) {
            struct tgsi_declaration_image v; memcpy(&v, copy + image + 2, 4);
            if (bad == 3) v.Format = PIPE_FORMAT_NONE;
            else if (bad == 4) {
                struct tgsi_declaration_range r; memcpy(&r, copy + image + 1, 4); r.Last = 4;
                memcpy(copy + image + 1, &r, 4);
            } else v.Padding = 1;
            memcpy(copy + image + 2, &v, 4);
        } else if (bad < 9) {
            struct tgsi_instruction_texture t; memcpy(&t, copy + fetch + 1, 4);
            if (bad == 6) t.NumOffsets = 1;
            else if (bad == 7) t.Texture = TGSI_TEXTURE_2D;
            else t.ReturnType = TGSI_RETURN_TYPE_SINT;
            memcpy(copy + fetch + 1, &t, 4);
        } else if (bad == 9) {
            struct tgsi_src_register r; memcpy(&r, copy + fetch + 4, 4); r.Indirect = 1;
            memcpy(copy + fetch + 4, &r, 4);
        } else if (bad == 10 || bad == 13) {
            struct tgsi_instruction i; memcpy(&i, copy + fetch, 4);
            if (bad == 10) i.NumSrcRegs = 3; else i.Opcode = TGSI_OPCODE_TEX;
            memcpy(copy + fetch, &i, 4);
        } else if (bad == 11) {
            struct tgsi_instruction_memory m; memcpy(&m, copy + store + 1, 4);
            /* COHERENT has an explicit real-image publication contract;
             * VOLATILE remains unsupported and must not be erased. */
            m.Qualifier = TGSI_MEMORY_VOLATILE; memcpy(copy + store + 1, &m, 4);
        } else {
            struct tgsi_dst_register d; memcpy(&d, copy + query + 2, 4); d.WriteMask = 4;
            memcpy(copy + query + 2, &d, 4);
        }
        struct opengpu_cs_program out, untouched; memset(&out, 0x73, sizeof(out)); untouched = out;
        assert(opengpu_cs_emit(copy, &out) < 0 && !memcmp(&out, &untouched, sizeof(out)));
    }
    free(copy);
    puts("14 actual TGSI buffer packet/type/extent/resource/qualifier negatives preserve output PASS");
}

static void old_feature_screens(const void *tokens)
{
    uint64_t full = features;
    const uint64_t missing[] = {OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE, OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE,
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE, OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES,
        OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES, OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS};
    for (unsigned i = 0; i <= 6; i++) {
        features = i == 6 ? 0 : full & ~missing[i];
        struct pipe_screen *s = opengpu_drm_screen_create(7, NULL); assert(s);
        struct pipe_context *p = s->context_create(s, NULL, 0); assert(p);
        unsigned binds = binding_attempts, queues = resource_request_attempts;
        struct pipe_compute_state st = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = tokens};
        assert(!p->create_compute_state(p, &st));
        assert(binding_attempts == binds && resource_request_attempts == queues);
        assert(!s->get_param(s, PIPE_CAP_COMPUTE) && !s->get_param(s, PIPE_CAP_TEXTURE_BUFFER_OBJECTS));
        p->destroy(p); s->destroy(s); no_leaks();
    }
    features = full;
    puts("Old/absent transport+bits28/29/30 fail before shader binding or resource submit PASS");
}

static void typed_frontends(const char *directory)
{
    const char *names[] = {"buffers-signed", "buffers-float", "buffers-branch"};
    for (unsigned i = 0; i < 3; i++) {
        char path[4096], dump[4096];
        assert(snprintf(path, sizeof(path), "%s/%s.comp", directory, names[i]) > 0);
        assert(snprintf(dump, sizeof(dump), "%s/%s", directory, names[i]) > 0);
        void *f = cs_frontend_compile_buffers(path, dump); assert(f);
        struct opengpu_cs_program program, untouched; memset(&program, 0x73, sizeof(program)); untouched = program;
        int ret = opengpu_cs_emit(cs_frontend_tokens(f), &program);
        if (i == 2) assert(ret < 0 && !memcmp(&program, &untouched, sizeof(program)));
        else {
            assert(!ret && program.texture_kind[31] == (i ? 0u : 2u));
            assert(program.image_format[0] == (i ? OPENGPU_TEXTURE_FORMAT_R32F : OPENGPU_TEXTURE_FORMAT_R32I));
            assert(opengpu_compute_shader_validate_words_discovered_resources(program.code, 512, 64, 4,
                false, false, true, true, true, false, false, false, false, 0, 0, 0, true));
        }
        cs_frontend_destroy(f);
    }
    puts("Real signed/float-bit buffer TGSI + discovered validator; branch resource remains rejected PASS");
}
static void combined_uniforms(struct pipe_screen *s, struct pipe_context *p, const char *directory,
    void *restore, const struct pipe_grid_info *grid, struct pipe_resource *ssbo[4])
{
    char path[4096], dump[4096];
    assert(snprintf(path, sizeof(path), "%s/buffers-uniforms.comp", directory) > 0);
    assert(snprintf(dump, sizeof(dump), "%s/buffers-uniforms", directory) > 0);
    void *front = cs_frontend_compile_buffers(path, dump); assert(front);
    struct pipe_compute_state st = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = cs_frontend_tokens(front)};
    void *cs = p->create_compute_state(p, &st); assert(cs);
    struct pipe_resource *blocks[36] = {0}; unsigned offsets[36] = {0}, sizes[36] = {0};
    uint32_t defaults[4] = {0x9317d509u, 0x41, 0x52, 0x63}, mask = defaults[0];
    assert(cs_frontend_default_components(front) == 4);
    cs_frontend_defaults(front, p, defaults, 4);
    for (unsigned i = 0; i < 12; i++) {
        blocks[24 + i] = new_buffer(s, 512); offsets[24 + i] = 16; sizes[24 + i] = 16;
        uint32_t *data = pipe_opengpu_resource_map(opengpu_resource(blocks[24 + i])->gpu);
        data[4] = sample(19, i, 0); mask ^= data[4];
    }
    cs_frontend_ubos(front, p, blocks, offsets, sizes);
    struct pipe_shader_buffer views[4];
    for (unsigned native = 0; native < 4; native++) {
        unsigned api = cs_frontend_storage_binding(front, native); assert(api < 4);
        views[native] = (struct pipe_shader_buffer) {.buffer = ssbo[api], .buffer_size = ssbo[api]->width0};
    }
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, views, 15);
    p->bind_compute_state(p, cs);
    unsigned before = buffer_submissions, snapshots = uniform_snapshots;
    uniform_mutate_after_snapshot = 1; p->launch_grid(p, grid); uniform_mutate_after_snapshot = 0;
    assert(buffer_submissions == before + 1 && uniform_snapshots == snapshots + 13);
    for (unsigned b = 0; b < 4; b++) {
        const uint32_t *data = pipe_opengpu_resource_map(opengpu_resource(ssbo[b])->gpu);
        for (unsigned item = 0; item < 4; item++) {
            const uint32_t expected[] = {sample(9, 31, item), sample(10, 0, item), 65536,
                (128u * 1024u * 1024u - 64) / 4};
            for (unsigned c = 0; c < 4; c++) assert(data[item * 4 + c] == (expected[c] ^ mask ^ b * 0x10203u));
        }
    }
    for (unsigned i = 0; i < 12; i++) {
        const uint8_t *data = pipe_opengpu_resource_map(opengpu_resource(blocks[24 + i])->gpu);
        for (unsigned b = 16; b < 32; b++) assert(data[b] == 0x99);
    }
    p->memory_barrier(p, PIPE_BARRIER_ALL);
    for (unsigned i = 0; i < 13; i++) p->set_constant_buffer(p, PIPE_SHADER_COMPUTE, i, false, NULL);
    p->bind_compute_state(p, restore); p->delete_compute_state(p, cs); cs_frontend_destroy(front);
    for (unsigned i = 0; i < 12; i++) pipe_resource_reference(&blocks[24 + i], NULL);
    puts("32 textures + 4 SSBO + 4 independent images + 13 real immutable GUR/GUB sources/data coexist PASS");
}
static void sparse_rebind(struct pipe_context *p, void *cs, const struct pipe_grid_info *grid,
    struct pipe_sampler_view *samplers[32], const struct pipe_image_view images[4])
{
    /* Keep only the shader's high sampler and its two separated image units.
     * A failed stale-slot unbind must discard the whole private namespace. */
    p->launch_grid(p, grid); p->memory_barrier(p, PIPE_BARRIER_ALL);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 0, 31, false, NULL);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 1, 0, 2, NULL);
    unsigned before = buffer_submissions;
    fail_request = DRM_IOCTL_OPENGPU_RESOURCE_UNBIND; fail_nth = 1; fail_seen = 0;
    p->launch_grid(p, grid); assert(buffer_submissions == before && live_contexts() == 1);
    fail_request = fail_nth = fail_seen = 0;
    p->launch_grid(p, grid); assert(buffer_submissions == before + 1);
    for (unsigned i = 0; i < 31; i++) assert(!contexts[last_compute_context].b[3 + i].handle);
    assert(contexts[last_compute_context].b[34].handle && contexts[last_compute_context].b[39].handle &&
        !contexts[last_compute_context].b[40].handle && !contexts[last_compute_context].b[41].handle &&
        contexts[last_compute_context].b[42].handle);
    p->memory_barrier(p, PIPE_BARRIER_ALL);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 32, 0, false, samplers);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 4, 0, images);
    const unsigned failures[] = {DRM_IOCTL_SYNCOBJ_CREATE, DRM_IOCTL_OPENGPU_COMPUTE_IMAGE_RESOURCES};
    for (unsigned i = 0; i < 2; i++) {
        p->bind_compute_state(p, NULL); p->bind_compute_state(p, cs); before = buffer_submissions;
        fail_request = failures[i]; fail_nth = 1; fail_seen = 0;
        p->launch_grid(p, grid); assert(buffer_submissions == before && live_contexts() == 1);
        fail_request = fail_nth = fail_seen = 0;
        p->launch_grid(p, grid); assert(buffer_submissions == before + 1);
        p->memory_barrier(p, PIPE_BARRIER_ALL);
    }
    puts("Sparse sampler31/image0+3, stale-unbind/sync-create/submit failures rebuild private context PASS");
}
static void first_execution(struct pipe_screen *s, struct pipe_context *p, void *frontend, const char *directory)
{
    struct pipe_resource *textures[32], *ssbo[4], *images[4];
    struct pipe_sampler_view *samplers[32];
    struct pipe_image_view image_views[4] = {{0}};
    struct pipe_shader_buffer storage[4] = {{0}};
    for (unsigned i = 0; i < 32; i++) {
        textures[i] = new_buffer(s, 1048831);
        struct pipe_sampler_view t = {.format = i == 31 ? PIPE_FORMAT_R32_UINT : all_sampler_formats[i % 30], .target = PIPE_BUFFER,
            .swizzle_r = 0, .swizzle_g = 1, .swizzle_b = 2, .swizzle_a = 3};
        unsigned stride = opengpu_texture_buffer_format_bytes((unsigned)opengpu_buffer_sampler_format(t.format));
        t.u.buf.offset = 63; t.u.buf.size = 65536 * stride + stride - 1;
        samplers[i] = p->create_sampler_view(p, textures[i], &t); assert(samplers[i]);
        uint8_t *m = pipe_opengpu_resource_map(opengpu_resource(textures[i])->gpu);
        memset(m, 0x5a, 1048831);
        for (unsigned item = 0; item < 4; item++) { uint32_t v = sample(9, i, item); memcpy(m + 63 + item * 4, &v, 4); }
    }
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 32, 0, false, samplers);
    for (unsigned i = 0; i < 4; i++) {
        ssbo[i] = new_buffer(s, 128u * 1024u * 1024u);
        images[i] = new_buffer(s, 128u * 1024u * 1024u);
        image_views[i] = (struct pipe_image_view) {.resource = images[i], .format = PIPE_FORMAT_R32_UINT,
            .access = i == 3 ? PIPE_IMAGE_ACCESS_WRITE : PIPE_IMAGE_ACCESS_READ,
            .shader_access = i == 3 ? PIPE_IMAGE_ACCESS_WRITE : PIPE_IMAGE_ACCESS_READ};
        image_views[i].u.buf.offset = 63; image_views[i].u.buf.size = images[i]->width0 - 64;
        uint8_t *m = pipe_opengpu_resource_map(opengpu_resource(images[i])->gpu);
        memset(m, 0x69, 256);
        for (unsigned item = 0; item < 4; item++) { uint32_t v = sample(10, i, item); memcpy(m + 63 + item * 4, &v, 4); }
        memset(pipe_opengpu_resource_map(opengpu_resource(ssbo[i])->gpu), 0x77, 256);
    }
    for (unsigned native = 0; native < 4; native++) {
        unsigned api = cs_frontend_storage_binding(frontend, native); assert(api < 4);
        storage[native] = (struct pipe_shader_buffer) {.buffer = ssbo[api], .buffer_size = ssbo[api]->width0};
    }
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 4, 0, image_views);
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, storage, 15);
    struct pipe_compute_state st = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = cs_frontend_tokens(frontend)};
    void *cs = p->create_compute_state(p, &st); assert(cs); p->bind_compute_state(p, cs);
    const struct pipe_grid_info grid = {.work_dim = 1, .block = {4, 1, 1}, .grid = {1, 1, 1}};
    unsigned before = buffer_executions;
    p->launch_grid(p, &grid); assert(buffer_executions == before + 1 && kernel_admissions == buffer_submissions);
    for (unsigned b = 0; b < 4; b++) {
        const uint8_t *data = pipe_opengpu_resource_map(opengpu_resource(ssbo[b])->gpu);
        for (unsigned item = 0; item < 4; item++) {
            uint32_t actual[4]; memcpy(actual, data + item * 16, 16);
            const uint32_t expected[4] = {sample(9, 31, item), sample(10, 0, item), 65536, (128u * 1024u * 1024u - 64) / 4};
            for (unsigned c = 0; c < 4; c++) assert(actual[c] == (expected[c] ^ (b * 0x10203u)));
        }
        for (unsigned byte = 64; byte < 256; byte++) assert(data[byte] == 0x77);
        data = pipe_opengpu_resource_map(opengpu_resource(images[b])->gpu);
        for (unsigned byte = 0; byte < 256; byte++) {
            unsigned want = 0x69;
            if (byte >= 63 && byte < 79) {
                uint32_t value = b == 3 ? sample(9, 31, (byte - 63) / 4) : sample(10, b, (byte - 63) / 4);
                want = value >> ((byte - 63) % 4 * 8) & 255;
            }
            assert(data[byte] == want);
        }
    }
    /* NTT's self-MOV retains an identity swizzle: the conservative source
     * scan preserves every original component fetch in this regression. */
    assert(actual_txf == 16 && actual_txq == 1 && actual_image_load == 4 && actual_image_store == 4 && actual_image_size == 4);
    unsigned old_invalidates = invalidates;
    p->memory_barrier(p, PIPE_BARRIER_IMAGE | PIPE_BARRIER_TEXTURE | PIPE_BARRIER_SHADER_BUFFER);
    assert(invalidates == old_invalidates + 5); /* four SSBOs + image3, deduplicated */
    query_levels_only(p, cs, storage[0].buffer);
    before = buffer_submissions;
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 31, 0, 1, false, NULL);
    reject_launch(p, &grid);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 31, 1, 0, false, &samplers[31]);
    struct pipe_sampler_view wrong = *samplers[31]; wrong.format = PIPE_FORMAT_R32_SINT;
    struct pipe_sampler_view *signed_view = p->create_sampler_view(p, textures[31], &wrong); assert(signed_view);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 31, 1, 0, false, &signed_view);
    reject_launch(p, &grid); /* same storage width, different numeric kind */
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 31, 1, 0, false, &samplers[31]);
    pipe_sampler_view_reference(&signed_view, NULL);
    struct pipe_image_view denied = image_views[3]; denied.access = PIPE_IMAGE_ACCESS_READ;
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 3, 1, 0, &denied); reject_launch(p, &grid);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 3, 1, 0, &image_views[3]);
    denied = image_views[0]; denied.access = PIPE_IMAGE_ACCESS_WRITE;
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 1, 0, &denied); reject_launch(p, &grid);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 1, 0, &image_views[0]);
    /* A valid small view cannot conceal an over-window complete BO. */
    struct pipe_resource *too_large = new_buffer(s, 4u * 1024u * 1024u + 1);
    struct pipe_sampler_view *large_view = p->create_sampler_view(p, too_large, samplers[31]); assert(large_view);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 31, 1, 0, false, &large_view); reject_launch(p, &grid);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 31, 1, 0, false, &samplers[31]);
    pipe_sampler_view_reference(&large_view, NULL); pipe_resource_reference(&too_large, NULL);
    assert(buffer_submissions == before);
    binding_failures(p, cs, &grid);
    ordinary_producer(s, p, cs, &grid, textures[31], samplers, image_views, storage, ssbo);
    combined_uniforms(s, p, directory, cs, &grid, ssbo);
    sparse_rebind(p, cs, &grid, samplers, image_views);
    p->delete_compute_state(p, cs);
    p->set_sampler_views(p, PIPE_SHADER_COMPUTE, 0, 0, 32, false, NULL);
    p->set_shader_images(p, PIPE_SHADER_COMPUTE, 0, 0, 4, NULL);
    p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, NULL, 0);
    for (unsigned i = 0; i < 32; i++) { pipe_sampler_view_reference(&samplers[i], NULL); pipe_resource_reference(&textures[i], NULL); }
    for (unsigned i = 0; i < 4; i++) { pipe_resource_reference(&ssbo[i], NULL); pipe_resource_reference(&images[i], NULL); }
}
#ifdef OPENGPU_CS_TEXTURE_NAMESPACE
#include "cs_texture_namespace_flow.h"
#endif
#ifdef OPENGPU_CS_IMAGE_ORDERING
#include "cs_image_ordering_flow.h"
#endif
#ifdef OPENGPU_CS_ATOMIC_COUNTER
#include "cs_atomic_counter_flow.h"
#endif
#ifdef OPENGPU_CS_SHARED_PUBLICATION
#include "cs_shared_publication_flow.h"
#endif

int main(int argc, char **argv)
{
    char path[4096], dump[4096]; assert(argc == 2);
    features = OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE | OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS |
        OPENGPU_DRIVER_FEATURE_COMPUTE_TEXTURE | OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_TEXTURE |
        OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGES | OPENGPU_DRIVER_FEATURE_COMPUTE_BUFFER_IMAGES |
        OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_BACKINGS | OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE_TEXTURE |
        OPENGPU_DRIVER_FEATURE_COMPUTE_IMAGE_STORAGE | OPENGPU_DRIVER_FEATURE_COMPUTE_RESOURCES |
        OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS;
    resource_ioctl = submit_buffers; resource_finish = execute_buffers;
    /* Focused leak checking does not initialize Mesa's process-global GLSL
     * compiler singletons. The default path below still tests that frontend. */
    if (!strcmp(argv[1], "--lifecycle-only")) {
        orphan_recovery(); final_screen_ownership(); return 0;
    }
    buffer_artifacts = getenv("OPENGPU_BUFFER_ARTIFACTS");
#ifdef OPENGPU_CS_SHARED_PUBLICATION
    if (getenv("OPENGPU_SHARED_PUBLICATION_QUALIFICATION")) {
        shared_publication_flow(argv[1]);return 0;
    }
#endif
#ifdef OPENGPU_CS_IMAGE_ORDERING
    if (getenv("OPENGPU_IMAGE_ORDERING_QUALIFICATION")) {
        image_ordering_flow(argv[1]);
        return 0;
    }
#endif
#ifdef OPENGPU_CS_ATOMIC_COUNTER
    if (getenv("OPENGPU_ATOMIC_COUNTER_QUALIFICATION")) {
        atomic_counter_flow(argv[1]);
        return 0;
    }
#endif
#ifdef OPENGPU_CS_TEXTURE_NAMESPACE
    if (getenv("OPENGPU_TEXTURE_NAMESPACE_QUALIFICATION")) {
        texture_namespace_flow(argv[1]);
        return 0;
    }
#endif
    assert(snprintf(path, sizeof(path), "%s/buffers.comp", argv[1]) > 0);
    assert(snprintf(dump, sizeof(dump), "%s/buffers", argv[1]) > 0);
    void *front = cs_frontend_compile_buffers(path, dump); assert(front);
    struct opengpu_cs_program program; assert(!opengpu_cs_emit(cs_frontend_tokens(front), &program));
    assert(program.textures == UINT32_C(0x80000000) && program.image_reads == 1 &&
        program.image_writes == 8 && program.image_queries == 8 && program.writes == 15);
    printf("Buffer actual frontend: words%u textures%08x image r%u/w%u/q%u\n",
        program.words, program.textures, program.image_reads, program.image_writes, program.image_queries);
    malformed_buffers(cs_frontend_tokens(front)); typed_frontends(argv[1]);
    struct pipe_screen *s = opengpu_drm_screen_create(7, NULL); assert(s);
    assert(!s->get_param(s, PIPE_CAP_COMPUTE) && !s->get_param(s, PIPE_CAP_TEXTURE_BUFFER_OBJECTS));
    struct pipe_context *p = s->context_create(s, NULL, 0); assert(p);
    first_execution(s, p, front, argv[1]);
    p->destroy(p); s->destroy(s); no_leaks(); old_feature_screens(cs_frontend_tokens(front)); cs_frontend_destroy(front);
    orphan_recovery(); final_screen_ownership();
    puts("Actual GLSL/TGSI/native/callback/winsys/kernel snapshots + CPU result/guards/retained publication PASS (no GPU)");
    return 0;
}
