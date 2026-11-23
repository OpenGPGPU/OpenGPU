/* Build and retain one original VS/GS/ABI7 draw through real driver MMIO. */
#define _POSIX_C_SOURCE 200809L
#define OPENGPU_NATIVE_MMIO 1
#define OPENGPU_NATIVE_PROFILE_STORAGE_WORDS 117
#define OPENGPU_NATIVE_CAPTURE_MAGIC "OGCAP002"
#define OPENGPU_NATIVE_CAPTURE_DISCOVERY_WORDS 117
#define OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN geometry_legacy_fixture_entry
#include "../driver/tests/opengpu_native_submit_fixture.c"
#undef OPENGPU_NATIVE_SUBMIT_FIXTURE_MAIN
#include "../driver/opengpu_native_sample_transport_features.h"
#include "../driver/tests/opengpu_geometry_submit_fixture.h"
#include "native_sample_live_transport.h"
#include "native_geometry_expected_profile.inc"

static u32 geometry_profile_read(void *opaque, u32 address)
{
    const struct native_kernel_profile *profile = opaque;
    for (unsigned i = 0; i < OPENGPU_NATIVE_PROFILE_STORAGE_WORDS; i++)
        if (profile->words[i].address == address)
            return profile->words[i].value;
    return 0;
}

static void geometry_install_profile(struct native_kernel_session *s, const char *path)
{
    struct native_kernel_profile *profile = calloc(1, sizeof(*profile));
    live_require(profile != NULL, "geometry profile allocation");
    live_profile_words(path, geometry_expected_profile, profile->words);
    const u32 id = geometry_profile_read(profile, GPU_REG_ID);
    const u32 flags = geometry_profile_read(profile, OPENGPU_COMPUTE_RESOURCE_REG_FLAGS);
    profile->capabilities = geometry_profile_read(profile, GPU_REG_CAPABILITIES);
    bool layered = false, sample = false;
    live_require((id & 0xffff0000u) == 0x47550000u && geometry_profile_read(profile, 0x320) == 1048576 &&
        opengpu_read_native_sample_transport_limits(id, flags, profile->capabilities, 0x540,
            profile, geometry_profile_read, &profile->limits, &layered, &sample) &&
        layered && sample, "float geometry profile rejected by production discovery");
    s->profile = profile;
    s->gpu.hw.capabilities = profile->capabilities;
    s->gpu.hw.native_sources.available = profile->limits.available;
    s->gpu.hw.native_sources.capacity = profile->limits.capacity;
    s->gpu.hw.native_sources.engines = profile->limits.engines;
    s->gpu.hw.native_sources.frame = profile->limits.frame;
    s->gpu.hw.native_layered_transport = layered;
    s->gpu.hw.native_sample_transport = sample;
}

static void geometry_seed_image(struct native_kernel_session *s, u32 handle, unsigned char value)
{
    struct native_original *original = original_handle(s, handle);
    live_require(original != NULL, "image GEM absent");
    memset(original->backing.cpu, value, original->backing.size);
}

static void geometry_buffer_json(FILE *f, struct native_kernel_session *s, u32 handle,
    u64 offset, u64 bytes)
{
    u64 physical, allocation;
    live_require(!native_kernel_original_physical(s, handle, &physical, &allocation) &&
        offset <= allocation && bytes <= allocation - offset, "original GEM window");
    fprintf(f, "{\"physical\":%llu,\"allocationBytes\":%llu,\"offset\":%llu,\"bytes\":%llu}",
        (unsigned long long)physical, (unsigned long long)allocation,
        (unsigned long long)offset, (unsigned long long)bytes);
}

static void geometry_inputs(struct native_kernel_session *s, const struct geometry_submit_fixture *fixture,
    const struct geometry_published *va)
{
    char path[4096], *temporary;
    live_path(path, "geometry-inputs.json");
    FILE *f = native_mmio_file(path, &temporary);
    live_require(f != NULL, "geometry inputs publication");
    fprintf(f, "{\"schema\":\"opengpu-native-geometry-inputs-v1\","
        "\"ownerProfile\":\"two-cu-vga60-owner-v2-image-float-stage\","
        "\"gpuExecuted\":false,\"width\":%u,\"height\":%u,\"samples\":%u,\"layers\":%u,"
        "\"layerPitch\":%u,\"context\":%u,\"pass\":%u,\"generation\":%u,"
        "\"jobVA\":%u,\"drawVA\":%u,\"frameVA\":%u,\"stateVA\":%u,"
        "\"geometryVA\":%u,\"completionVA\":%u,\"shaderVAs\":[%u,%u,%u],\"programs\":[",
        GEOMETRY_WIDTH, GEOMETRY_HEIGHT, GEOMETRY_SAMPLES, GEOMETRY_LAYERS, GEOMETRY_LAYER_PITCH,
        GEOMETRY_CONTEXT, GEOMETRY_PASS, GEOMETRY_GENERATION, va->job, va->draw, va->frame, va->state,
        va->geometry, va->completion, va->program[0], va->program[1], va->program[2]);
    for (unsigned stage = 0; stage < 3; stage++) {
        const struct drm_opengpu_native_program_view *program = fixture->programs[stage];
        if (stage)
            fputc(',', f);
        geometry_buffer_json(f, s, program->handle, program->offset, program->bytes);
    }
    fputs("],\"vertices\":", f);
    const struct drm_opengpu_compute_uniform_view *vertex = &fixture->plan.draw.bindings[0];
    geometry_buffer_json(f, s, vertex->handle, vertex->offset, vertex->bytes);
    fputs(",\"completion\":", f);
    const struct drm_opengpu_compute_uniform_view *completion = &fixture->completion.view;
    geometry_buffer_json(f, s, completion->handle, completion->offset, completion->bytes);
    fputs(",\"attachments\":[", f);
    for (unsigned mrt = 0; mrt < 4; mrt++) {
        const struct opengpu_native_frame_attachment *a = &fixture->frame.attachments[mrt];
        fprintf(f, "%s{\"imageRowPitch\":%u,\"resolveRowPitch\":%u,\"image\":",
            mrt ? "," : "", a->image.row_pitch, a->resolve.row_pitch);
        geometry_buffer_json(f, s, a->image.gem.handle, 0, a->image.gem.bytes);
        fputs(",\"resolve\":", f);
        geometry_buffer_json(f, s, a->resolve.gem.handle, 0, a->resolve.gem.bytes);
        fputc('}', f);
    }
    fputc(']', f);
    live_maps_json(f, s, native_record(s, 0)->native_owner);
    fputs("}\n", f);
    live_require(!native_mmio_close(f, path, temporary), "geometry inputs publication");
}

int main(int argc, char **argv)
{
    live_require(argc == 7, "usage: geometry-live profile session VS GS FS GNG1");
    live_set_directory(argv[2]);
    struct native_kernel_session *s = native_kernel_session_create(GEOMETRY_CONTEXT);
    live_require(s != NULL, "geometry session create");
    geometry_install_profile(s, argv[1]);
    struct geometry_submit_fixture *fixture = geometry_prepare_draw(s, &s->profile->limits.frame,
        (const char *const *)(argv + 3));
    for (unsigned mrt = 0; mrt < 4; mrt++) {
        const struct opengpu_native_frame_attachment *a = &fixture->frame.attachments[mrt];
        geometry_seed_image(s, a->image.gem.handle, 0x51 + mrt);
        geometry_seed_image(s, a->resolve.gem.handle, 0x71 + mrt);
    }
    live_require(!native_kernel_submit(s, &fixture->record.submit, NULL, NULL) &&
        s->count == 1 && fixture->record.submit.fence_seqno != 0xfeed, "protected geometry ioctl");
    for (unsigned stage = 0; stage < 3; stage++) {
        u32 handle = fixture->programs[stage]->handle;
        live_require(native_kernel_original_refcount(s, handle) > 1 &&
            !native_kernel_original_close(s, handle) && native_kernel_original_refcount(s, handle) > 0,
            "geometry original code lease");
    }
    const struct geometry_published va = geometry_check_publication(s, fixture);
    geometry_inputs(s, fixture, &va);
    live_run_record(s);
    for (unsigned stage = 0; stage < 3; stage++)
        live_require(!native_kernel_original_refcount(s, fixture->programs[stage]->handle),
            "geometry original code still leased after cleanup");
    live_require(!native_kernel_session_destroy(s) && !live_allocations && !fixture_buffers,
        "geometry session destruction");
    free(fixture);
    puts("Original protected geometry draw completed actual retirement and ACK-gated cleanup");
    return 0;
}
