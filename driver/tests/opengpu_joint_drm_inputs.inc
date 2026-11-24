/* Typed driver test inputs shared by admission and MMIO composition. */
struct joint_drm_inputs {
    u32 job[128], draw[512], sequence[512], state[128];
    struct opengpu_native_frame_encoding encoded;
    struct drm_opengpu_native_draw_plan draw_plan;
    struct opengpu_tessellation_vertex_outer_invocation_v2 invocation;
    struct opengpu_tessellation_vertex_outer_range_v2 range;
    struct opengpu_protected_tessellation_joint_plan plan;
    struct opengpu_protected_tessellation_state_plan state_plan;
    struct drm_opengpu_submit_tessellation_joint request;
};

static void joint_drm_inputs_init(struct native_kernel_session *s, struct joint_drm_inputs *inputs,
    u32 domain, u32 code, u32 defaults)
{
    const struct opengpu_native_binned_host_caps caps = {{
        0x474e4303, 8, 0x474e4a01, 0x08000200, 0xc2100800, 0x08000800, 0xe4908000, 0x902,
    }};
    const u32 scratch = 94u * 128u * 4u;
    struct opengpu_native_frame_request frame = {
        .caps = &caps, .width = 4, .height = 4, .samples = 1,
        .color_mask = 1, .context = s->context.id, .pass = 8, .generation = 9,
        .state_count = 1, .scratch_stride = scratch,
    };
    const struct jt_layout *layout = &jt_layouts[domain];
    u32 job_header[] = {0x474e5104 + domain, 512, 2, 16, 16, 16, 4, 4};
    u32 draw_header[] = {0x474e4401, 2048, 1, 16, 16, 16, 4, 4};
    u32 *seq = inputs->sequence, *state = inputs->state;
    u32 tes = test_gem(s, 4096).handle;
    void *code_bytes = original_handle(s, tes)->gem.vaddr;

    memset(inputs, 0, sizeof(*inputs));
    word(code_bytes, 0, 35u << 25 | 0xb);
    word(code_bytes, 4, 36u << 25 | 0xb);
    word(code_bytes, 8, 0x30500073);
    frame.attachments[0].format = 35;
    frame.attachments[0].channel_mask = 15;
    frame.attachments[0].image = (struct opengpu_native_frame_image) {
        .gem = test_gem(s, 1024), .row_pitch = 64, .store = 1,
    };
    frame.primitives = test_gem(s, 4096);
    frame.directory = test_gem(s, 64);
    frame.pool[0] = test_gem(s, 4096);
    for (u32 i = 0; i < 4; i++)
        frame.scratch[i] = test_gem(s, 2 * scratch);
    assert(!opengpu_native_frame_encode(&frame, &inputs->encoded));
    memcpy(inputs->job, job_header, sizeof(job_header));
    inputs->job[17] = inputs->job[31] = inputs->job[34] = inputs->job[35] = 1;
    inputs->job[20] = 2048;
    inputs->job[24] = s->context.id;
    inputs->job[25] = 9;
    inputs->job[26] = 8;
    inputs->job[27] = 2;
    inputs->job[32] = 128;
    inputs->job[33] = 64;
    memcpy(inputs->draw, draw_header, sizeof(draw_header));
    inputs->draw[31] = 1;
    inputs->draw[311] = 4;
    inputs->draw[314] = s->context.id;
    inputs->draw[315] = 9;
    inputs->draw[316] = 8;
    inputs->draw[325] = 3;
    inputs->draw[326] = 1;
    inputs->draw[327] = 2;
    inputs->draw[343] = inputs->draw[344] = 0xffffffffu;
    inputs->draw[346] = 1;
    inputs->draw_plan.vertex_program = (struct drm_opengpu_native_program_view){.handle = code, .bytes = 4};
    inputs->draw_plan.fragment_program = inputs->draw_plan.vertex_program;
    inputs->draw_plan.vertex_uniforms.defaults = (struct drm_opengpu_compute_uniform_view) {
        .handle = defaults, .offset = 4, .bytes = 16,
    };
    inputs->draw_plan.fragment_uniforms = inputs->draw_plan.vertex_uniforms;
    inputs->draw_plan.vertex_scratch = (struct drm_opengpu_compute_uniform_view) {
        .handle = test_gem(s, 4096).handle, .bytes = 4096,
    };
    inputs->invocation = (struct opengpu_tessellation_vertex_outer_invocation_v2) {
        .invocation_id = 0x100000003ull, .input_patch_vertices = 2,
    };
    inputs->range = (struct opengpu_tessellation_vertex_outer_range_v2) {
        .flags = OPENGPU_VERTEX_OUTER_FIRST | OPENGPU_VERTEX_OUTER_LAST,
        .task_id_base = 100, .patch_count = 6, .slice = {1, 0, 2, 0, 3}, .layout = {2, 4, 2, 8, 0},
        .components = {{0, 0}, {0, 1}, {0, 2}, {0, 3}},
    };
    inputs->plan = (struct opengpu_protected_tessellation_joint_plan) {
        .control_program = inputs->draw_plan.vertex_program,
        .evaluation_program = {.handle = tes, .bytes = 12},
        .control_uniforms = inputs->draw_plan.vertex_uniforms,
        .evaluation_uniforms = inputs->draw_plan.fragment_uniforms,
    };
    inputs->state_plan = (struct opengpu_protected_tessellation_state_plan) {
        .fragment_program = inputs->draw_plan.fragment_program,
        .fragment_uniforms = inputs->draw_plan.fragment_uniforms,
    };
    seq[0] = layout->magic;
    seq[1] = 2048;
    seq[2] = layout->version;
    seq[16] = 6;
    seq[37] = seq[38] = 4;
    seq[39] = 1;
    seq[184] = s->context.id;
    seq[185] = 9;
    seq[186] = 8;
    seq[layout->input_vertices] = 2;
    seq[layout->input_components] = 4;
    seq[layout->output_vertices] = 2;
    seq[layout->output_components] = 8;
    seq[layout->task_id] = 100;
    seq[layout->tes_outputs] = 8;
    seq[layout->varyings] = 1;
    seq[layout->viewport] = seq[layout->viewport + 1] = 4;
    seq[layout->viewport + 2] = 0x3f800000;
    seq[layout->viewport + 4] = 1;
    for (u32 i = 0; i < 3; i++) {
        seq[layout->control_grid + i] = seq[layout->evaluation_grid + i] = 1;
        seq[layout->control_local + i] = i ? 1 : 2;
        seq[layout->evaluation_local + i] = 1;
    }
    state[0] = 0x47465301;
    state[1] = 512;
    state[2] = 1;
    state[3] = 4;
    state[4] = s->context.id;
    state[5] = 8;
    state[6] = 9;
    state[27] = 1;
    state[28] = 4;
    state[30] = state[31] = 1;
    state[36] = s->context.id;
    state[37] = 9;
    state[38] = 8;
    state[OPENGPU_TYPED_FRAGMENT_STATE_FRAGMENT_VARYINGSLOTS_WORD] = 1;
    state[56] = 8;
    state[93] = state[94] = 1;
    inputs->request = (struct drm_opengpu_submit_tessellation_joint) {
        .version = 1, .context_id = s->context.id, .out_syncobj = 2,
        .draw_count = 1, .invocation_count = 1, .range_count = 1, .state_count = 1,
        .resource_count = inputs->encoded.resource_count, .job = (uintptr_t)inputs->job,
        .frame = (uintptr_t)&inputs->encoded.frame, .sequences = (uintptr_t)seq,
        .sequences_bytes = 2048, .states = (uintptr_t)state, .draws = (uintptr_t)inputs->draw,
        .draw_plans = (uintptr_t)&inputs->draw_plan, .invocations = (uintptr_t)&inputs->invocation,
        .ranges = (uintptr_t)&inputs->range, .plans = (uintptr_t)&inputs->plan,
        .state_plans = (uintptr_t)&inputs->state_plan,
        .resources = (uintptr_t)inputs->encoded.resources, .fence_seqno = 0xfeed,
    };
}
