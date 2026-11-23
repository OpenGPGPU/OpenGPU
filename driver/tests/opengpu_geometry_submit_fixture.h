/* Original linked geometry draw shared by the ioctl and live DUT fixtures. */
#ifndef OPENGPU_GEOMETRY_SUBMIT_FIXTURE_H
#define OPENGPU_GEOMETRY_SUBMIT_FIXTURE_H

#include "../opengpu_native_frame_internal.h"
#include "../opengpu_texture_image_format_abi.h"

#define J(field) OPENGPU_NATIVE_LAYERED_JOB_##field##_WORD
#define D(field) OPENGPU_NATIVE_BINNED_DRAW_DRAW_##field##_WORD
#define V(field) D(VERTEX_GEOMETRY_VERTEX_##field)
#define I(field) V(INPUT_##field)
#define F(field) OPENGPU_TYPED_FRAGMENT_STATE_##field##_WORD
#define G(field) OPENGPU_NATIVE_GEOMETRY_PROGRAM_##field##_WORD

enum {
    GEOMETRY_CONTEXT = 7, GEOMETRY_PASS = 11, GEOMETRY_GENERATION = 13,
    GEOMETRY_WIDTH = 4, GEOMETRY_HEIGHT = 4, GEOMETRY_SAMPLES = 4,
    GEOMETRY_LAYERS = 2, GEOMETRY_LAYER_PITCH = 4096, GEOMETRY_COMPLETION_BYTES = 16384
};

struct geometry_submit_fixture {
    struct native_test_record record;
    struct drm_opengpu_native_layered_draw_plan plan;
    struct drm_opengpu_native_completion_view completion;
    struct opengpu_native_frame_request frame;
    struct opengpu_native_geometry_program metadata;
    struct drm_opengpu_native_program_view *programs[3];
    u8 original_code[3][2048];
    u8 *source_code[3];
};

struct geometry_published {
    u32 job, draw, frame, state, geometry, completion, program[3];
};

static size_t geometry_read_output(const char *path, void *bytes, size_t capacity)
{
    FILE *file = fopen(path, "rb");
    assert(file);
    size_t size = fread(bytes, 1, capacity, file);
    assert(fgetc(file) == EOF && !ferror(file));
    assert(!fclose(file));
    return size;
}

static struct geometry_submit_fixture *geometry_prepare_draw(struct native_kernel_session *s,
    const struct opengpu_native_binned_host_caps *caps, const char *const paths[4])
{
    enum { lanes = 128, vs_scratch = 79 * lanes * 4, fs_scratch = 95 * lanes * 4 };
    struct geometry_submit_fixture *fixture = calloc(1, sizeof(*fixture));
    assert(fixture && caps && s->context.id == GEOMETRY_CONTEXT);
    struct native_test_record *r = &fixture->record;
    struct drm_opengpu_native_layered_draw_plan *plan = &fixture->plan;
    fixture->programs[0] = &plan->draw.vertex_program;
    fixture->programs[1] = &plan->geometry_program;
    fixture->programs[2] = &plan->draw.fragment_program;
    test_next_handle = 0;
    for (unsigned stage = 0; stage < 3; stage++) {
        size_t size = geometry_read_output(paths[stage], fixture->original_code[stage],
            sizeof(fixture->original_code[stage]));
        assert(size && !(size & 3u));
        u32 handle = ++test_next_handle;
        u8 *allocation = native_kernel_original_allocate(s, handle, 4096);
        assert(allocation);
        fixture->source_code[stage] = allocation + 64;
        memcpy(fixture->source_code[stage], fixture->original_code[stage], size);
        *fixture->programs[stage] = (struct drm_opengpu_native_program_view){
            .handle = handle, .offset = 64, .bytes = size};
    }
    struct opengpu_native_geometry_program *metadata = &fixture->metadata;
    assert(geometry_read_output(paths[3], metadata, sizeof(*metadata)) == sizeof(*metadata));
    assert(opengpu_native_geometry_program_encoding_valid(metadata));
    assert(opengpu_native_geometry_word_at(metadata, G(FLATMASK)) == 0 &&
        opengpu_native_geometry_word_at(metadata, G(FLATMASK) + 1) == 0x70000000);
    memcpy(plan->geometry_metadata, metadata, sizeof(*metadata));

    struct opengpu_native_frame_request *frame = &fixture->frame;
    *frame = (struct opengpu_native_frame_request){
        .caps = caps, .width = GEOMETRY_WIDTH, .height = GEOMETRY_HEIGHT,
        .samples = GEOMETRY_SAMPLES, .color_mask = 15, .tile_log2 = 3,
        .context = GEOMETRY_CONTEXT, .pass = GEOMETRY_PASS, .generation = GEOMETRY_GENERATION,
        .state_count = 1, .scratch_stride = fs_scratch};
    uint64_t source_pitch[6] = {0}, resolve_pitch[6] = {0};
    for (unsigned mrt = 0; mrt < 4; mrt++) {
        const unsigned texel = OPENGPU_TEXTURE_FORMAT_RGBA32F_BYTES;
        const u32 row_pitch = GEOMETRY_WIDTH * texel;
        struct opengpu_native_frame_attachment *a = &frame->attachments[mrt];
        a->format = OPENGPU_TEXTURE_FORMAT_RGBA32F;
        a->load_op = 1;
        a->channel_mask = 15;
        a->image = (struct opengpu_native_frame_image){
            .gem = test_gem(s, GEOMETRY_LAYER_PITCH + GEOMETRY_HEIGHT * GEOMETRY_SAMPLES * row_pitch),
            .row_pitch = row_pitch * GEOMETRY_SAMPLES, .store = 1};
        a->resolve = (struct opengpu_native_frame_image){
            .gem = test_gem(s, GEOMETRY_LAYER_PITCH + GEOMETRY_HEIGHT * row_pitch),
            .row_pitch = row_pitch, .store = 1};
        source_pitch[mrt] = resolve_pitch[mrt] = GEOMETRY_LAYER_PITCH;
        frame->scratch[mrt] = test_gem(s, fs_scratch);
    }
    frame->primitives = test_gem(s, 4096);
    frame->directory = test_gem(s, 64);
    frame->pool[0] = test_gem(s, 4096);
    assert(!opengpu_native_frame_encode_gnc5(frame, source_pitch, resolve_pitch, &r->encoded));
    r->encoded.frame.words[OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_LAYERS_WORD] = GEOMETRY_LAYERS;

    const u32 job[128] = {OPENGPU_NATIVE_LAYERED_JOB_MAGIC, 512, 2, 16, 16, 16, 4, 4,
        [J(JOB_DRAWCOUNT)] = 1, [J(JOB_DRAWSTRIDE)] = 2048, [J(JOB_IDENTITY_CONTEXT)] = GEOMETRY_CONTEXT,
        [J(JOB_IDENTITY_PASS)] = GEOMETRY_PASS, [J(JOB_IDENTITY_GENERATION)] = GEOMETRY_GENERATION,
        [J(COMPLETION_WRITABLE)] = 1};
    const u32 draw[512] = {OPENGPU_NATIVE_GEOMETRY_DRAW_MAGIC, 2048, 2, 16, 16, 16, 4, 4,
        [D(VERSION)] = 1, [D(RASTER_VERSION)] = 1, [D(RASTER_SAMPLEMODE)] = 2,
        [D(RASTER_SCREENW)] = GEOMETRY_WIDTH, [D(RASTER_SCREENH)] = GEOMETRY_HEIGHT,
        [D(RASTER_FRONTFACECCW)] = 1, [D(RASTER_LINEWIDTH)] = 0x3f800000,
        [D(RASTER_POINTSIZE)] = 0x3f800000, [D(VERTEX_VIEWPORT_VERSION)] = 1,
        [D(VERTEX_VIEWPORT_EXTENTX)] = GEOMETRY_WIDTH, [D(VERTEX_VIEWPORT_EXTENTY)] = GEOMETRY_HEIGHT,
        [D(VERTEX_VIEWPORT_FARDEPTH)] = 0x3f800000, [D(VERTEX_GEOMETRY_TOPOLOGY)] = 4,
        [V(ATTRIBUTESLOTS)] = 2, [V(VARYINGSLOTS)] = 16,
        [V(VARYINGMASK)] = 0x77777777, [V(VARYINGMASK) + 1] = 0x77777777, [V(VERSION)] = 1,
        [I(ATTRIBUTES0_COMPONENTS)] = 4, [I(ATTRIBUTES0_ENABLED)] = 1,
        [I(ATTRIBUTES1_COMPONENTS)] = 3, [I(ATTRIBUTES1_ENABLED)] = 1,
        [I(ATTRIBUTES1_OFFSET)] = 16, [I(BINDINGS0_STRIDE)] = 32, [I(COUNT)] = 3,
        [I(INSTANCES)] = 1, [I(VERSION)] = 2, [I(IDENTITY_CONTEXT)] = GEOMETRY_CONTEXT,
        [I(IDENTITY_PASS)] = GEOMETRY_PASS, [I(IDENTITY_GENERATION)] = GEOMETRY_GENERATION,
        [OPENGPU_NATIVE_GEOMETRY_DRAW_GEOMETRY_VERSION_WORD] = 1};
    const u32 state[128] = {OPENGPU_TYPED_FRAGMENT_STATE_MAGIC, 512, 1, 4,
        [F(HEADER_CONTEXT)] = GEOMETRY_CONTEXT, [F(HEADER_PASS)] = GEOMETRY_PASS,
        [F(HEADER_GENERATION)] = GEOMETRY_GENERATION, [F(BLEND_VERSION)] = 1,
        [F(TESTS_VERSION)] = 1, [F(VERSION)] = 1, [F(ENGINECOUNT)] = 4,
        [F(FRAGMENT_IDENTITY_CONTEXT)] = GEOMETRY_CONTEXT,
        [F(FRAGMENT_IDENTITY_PASS)] = GEOMETRY_PASS,
        [F(FRAGMENT_IDENTITY_GENERATION)] = GEOMETRY_GENERATION, [F(FRAGMENT_VERSION)] = 7,
        [F(FRAGMENT_VARYINGSLOTS)] = 16, [F(FRAGMENT_COLORSLOTS)] = 4,
        [F(FRAGMENT_COLORMASK)] = 15, [F(FRAGMENT_SAMPLEMODE)] = 2};
    memcpy(r->job, job, sizeof(job));
    memcpy(r->draw, draw, sizeof(draw));
    memcpy(r->state, state, sizeof(state));

    /* Clipping the first vertex produces intersections at edge midpoints. */
    const float vertices[3][8] = {
        {-2, -1, 0, 1, 0.25f, 0.5f, 0.75f, 0},
        {0, -1, 0, 1, 1.25f, 1.5f, 1.75f, 0},
        {0, 0, 0, 1, 2.25f, 2.5f, 2.75f, 0}};
    u32 vertex_handle = ++test_next_handle;
    void *vertex = native_kernel_original_allocate(s, vertex_handle, 4096);
    assert(vertex);
    memcpy(vertex, vertices, sizeof(vertices));
    plan->draw.bindings[0] = (struct drm_opengpu_compute_uniform_view){
        .handle = vertex_handle, .bytes = sizeof(vertices)};
    plan->draw.vertex_scratch = (struct drm_opengpu_compute_uniform_view){
        .handle = test_gem(s, vs_scratch).handle, .bytes = vs_scratch};
    fixture->completion.view = (struct drm_opengpu_compute_uniform_view){
        .handle = test_gem(s, GEOMETRY_COMPLETION_BYTES).handle, .bytes = GEOMETRY_COMPLETION_BYTES};
    r->submit = (struct drm_opengpu_submit_native_binned){
        .version = 2, .context_id = GEOMETRY_CONTEXT, .out_syncobj = 2,
        .job = (uintptr_t)r->job, .frame = (uintptr_t)&r->encoded.frame, .draws = (uintptr_t)r->draw,
        .draws_bytes = sizeof(r->draw), .states = (uintptr_t)r->state, .states_bytes = sizeof(r->state),
        .plans = (uintptr_t)plan, .plan_count = 1, .plan_stride = sizeof(*plan),
        .resources = (uintptr_t)r->encoded.resources, .resource_count = r->encoded.resource_count,
        .resource_stride = sizeof(r->encoded.resources[0]),
        .reserved = {(uintptr_t)&fixture->completion, 0}, .fence_seqno = 0xfeed};
    return fixture;
}

static void geometry_check_pte(struct native_kernel_session *s, u32 address, u32 flags)
{
    const struct opengpu_buffer *l1 = vm_live_l1(&s->context.vm, address / MMU_SUPERPAGE_SIZE);
    assert(l1);
    u32 leaf = ((const u32 *)l1->cpu)[(address % MMU_SUPERPAGE_SIZE) / 4096];
    assert((leaf & 15) == flags);
}

static struct geometry_published geometry_check_publication(struct native_kernel_session *s,
    const struct geometry_submit_fixture *fixture)
{
    struct opengpu_sched_job *job = native_record(s, 0);
    struct geometry_published va;
    assert(job && !opengpu_private_snapshot_native_layered_job_address(
        job->native_owner, job->native_job_segment, &va.job));
    va.draw = test_read_va(s, va.job + 4 * J(JOB_DRAWBASE));
    va.frame = test_read_va(s, va.job + 4 * J(JOB_FRAMEBASE));
    va.state = test_read_va(s, va.frame + 4 * OPENGPU_NATIVE_BINNED_FRAME_FRAME_STATES_BASE_WORD);
    va.geometry = test_read_va(s, va.draw + 4 * OPENGPU_NATIVE_GEOMETRY_DRAW_GEOMETRY_BASE_WORD);
    va.program[0] = test_read_va(s, va.draw + 4 * V(SHADERPC));
    va.program[1] = test_read_va(s, va.geometry + 4 * G(PC));
    va.program[2] = test_read_va(s, va.state + 4 * F(FRAGMENT_SHADERPC));
    for (unsigned stage = 0; stage < 3; stage++) {
        geometry_check_pte(s, va.program[stage], 11);
        for (unsigned word = 0; word < fixture->programs[stage]->bytes / 4; word++)
            assert(test_read_va(s, va.program[stage] + 4 * word) ==
                opengpu_native_geometry_word_at(fixture->original_code[stage], word));
    }
    geometry_check_pte(s, va.geometry, 3);
    for (unsigned word = 0; word < OPENGPU_NATIVE_GEOMETRY_PROGRAM_WORDS; word++) {
        if (word >= G(PC) && word <= G(UNIFORMS_ADDRESS))
            continue;
        assert(test_read_va(s, va.geometry + 4 * word) ==
            opengpu_native_geometry_word_at(&fixture->metadata, word));
    }
    assert(test_read_va(s, va.geometry + 4 * G(CODE_LIMIT)) ==
        va.program[1] + fixture->programs[1]->bytes);
    assert(test_read_va(s, va.frame + 4 * OPENGPU_NATIVE_LAYERED_FRAME_LAYERED_LAYERS_WORD) ==
        GEOMETRY_LAYERS);
    va.completion = test_read_va(s, va.job + 4 * J(COMPLETION_BASE));
    geometry_check_pte(s, va.completion, 7);
    geometry_check_pte(s, va.completion + GEOMETRY_COMPLETION_BYTES - 4, 7);
    return va;
}

#undef J
#undef D
#undef V
#undef I
#undef F
#undef G
#endif
