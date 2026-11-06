/* Production scheduler, MMU and command functions; no shader execution model. */
static int test_owned_fragment_code(void)
{
    unsigned char job[512], frame[2048], draws[6144], states[1536], code[3][20];
    struct opengpu_device gpu = { .hw = { .capabilities = GPU_CAP_UNIFIED_COMMANDS,
        .tessellation_transport = true, .tessellation_engines = 2,
        .tessellation_virtual_images = true } };
    struct opengpu_render_context context = { .gpu = &gpu, .id = 7, .refs = 1 };
    struct opengpu_file file = { .gpu = &gpu }; struct drm_file drm = {0};
    struct dma_resv resv = { .held = 1 }; struct drm_gem_object object = { .resv = &resv };
    struct opengpu_resource_binding backing = { .object = &object,
        .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .va = OPENGPU_STORAGE_VA_BASE,
        .dma = 0x34000000, .size = 0x4000 };
    const struct opengpu_sched_dep dep = { &object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE };
    struct opengpu_tessellation_fragment_code_plan plans[3];
    struct opengpu_tessellation_job_input input = { .job = job, .job_size = sizeof(job),
        .frame = frame, .frame_size = sizeof(frame), .draws = draws, .draws_size = sizeof(draws),
        .states = states, .states_size = sizeof(states) };
    const u64 code_va = OPENGPU_CODE_VA_BASE + OPENGPU_TESS_FRAGMENT_CODE_SLOT * OPENGPU_CODE_VA_STRIDE;
    struct opengpu_sched_job *q;
    mutex_init(&file.lock); mutex_lock(&file.lock);
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu, &context.vm));
    context.bindings[12] = &backing;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, backing.va, backing.dma, backing.size, 0));
#define RESET_CODE() do { owned_uniform_records(job, frame, draws, states, 0, backing.va); \
    for (u32 s = 0; s < 3; ++s) { \
        put(states + s * 512, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD, 0); \
        put(states + s * 512, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD, 0); } \
    make_fragment_code_plans(plans, code); } while (0)
#define SUBMIT_CODE(p, n) opengpu_tessellation_resources_sched_submit(&file, &drm, &context, &input, \
    NULL, 0, NULL, 0, (p), (n), 0, NULL, &dep, 1, NULL)
#define REJECT_CODE(p, n, error) do { int alloc = live_allocations, kv = live_kv, push = pushed, \
    kick = kicks, inv = invalidations, fences = resv.fences; \
    struct dma_fence *held = context.last_fence; \
    assert(SUBMIT_CODE((p), (n)) == (error)); \
    assert(live_allocations == alloc && live_kv == kv && pushed == push && kicks == kick && \
        invalidations == inv && resv.fences == fences && context.last_fence == held && \
        context.refs == 1 && !object.refs); } while (0)
#define RETIRE_CODE() do { busy = 0; opengpu_sched_free_job(&q->base); \
    assert(!object.refs && context.refs == 1 && !live_kv); } while (0)
    for (u32 domain = 0; domain < 3; ++domain) {
        unsigned char expected[1536], expected_code[3][20];
        RESET_CODE();
        /* Keep identical states while selecting each real domain header. */
        static const u32 magic[] = { OPENGPU_NATIVE_TESSELLATION_JOB_MAGIC,
            OPENGPU_NATIVE_TESSELLATION_TRIANGLE_JOB_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_JOB_MAGIC };
        static const u32 draw_magic[] = { OPENGPU_NATIVE_TESSELLATION_SEQUENCE_MAGIC,
            OPENGPU_NATIVE_TESSELLATION_TRIANGLE_SEQUENCE_MAGIC, OPENGPU_NATIVE_TESSELLATION_QUAD_SEQUENCE_MAGIC };
        put(job, 0, magic[domain]);
        for (u32 s = 0; s < 3; ++s) {
            memset(draws + s * 2048, 0, 2048);
            /* Use the canonical domain constructor, not an adapted byte ABI. */
            unsigned char j[512], f[2048], d[6144];
            make_records(j, f, d, context.id, domain, 3, 2048);
            memcpy(draws + s * 2048, d + s * 2048, 2048);
            make_zero_input_draw(draws + s * 2048, domain, 1);
            put(draws + s * 2048, 0, draw_magic[domain]);
            put(draws + s * 2048, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, s);
        }
        memcpy(expected, states, sizeof(states)); memcpy(expected_code, code, sizeof(code));
        for (u32 s = 0; s < 3; ++s)
            put(expected + s * 512, OPENGPU_TESS_STATE_SHADER_PC_WORD, (u32)(code_va + s * 64 + 4));
        assert(!SUBMIT_CODE(plans, 3)); q = last_job;
        assert(q->tess_fragment_code.size == 4096 && q->tess_fragment_code_va == code_va);
        assert(!memcmp(q->tess_states.cpu, expected, sizeof(expected)));
        memset(code, 0, sizeof(code)); memset(plans, 0, sizeof(plans)); memset(states, 0, sizeof(states));
        for (u32 s = 0; s < 3; ++s)
            assert(!memcmp((unsigned char *)q->tess_fragment_code.cpu + s * 64, expected_code[s], 20));
        for (u32 byte = 0; byte < 4096; ++byte)
            if (byte >= 3 * 64 || byte % 64 >= 20)
                assert(!((unsigned char *)q->tess_fragment_code.cpu)[byte]);
        assert(opengpu_sched_run_job(&q->base) == &hardware_fence && busy);
        assert(q->temporary_mapping_count == 5 && context.refs == 2 && object.refs == 1);
        u32 *leaf = vm_l1_find(&context.vm, code_va >> 22)->cpu;
        assert((leaf[(code_va >> 12) & 1023] & 0x3ffu) == 0x24bu); /* V/R/X/A, uncached */
        /* Held completion retains the exact copied bytes and rewritten entry. */
        assert(!memcmp(q->tess_states.cpu, expected, sizeof(expected)));
        RETIRE_CODE();
        assert(!leaf[(code_va >> 12) & 1023]);
    }
    RESET_CODE(); REJECT_CODE(NULL, 0, -EINVAL);
    REJECT_CODE(NULL, 3, -EINVAL);
    REJECT_CODE(plans, 2, -EINVAL);
    plans[1].state_handle = 3; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[1].state_handle = 0; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].code = NULL; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].bytes = 0; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].bytes = 19; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].bytes = OPENGPU_CODE_VA_STRIDE + 4; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].bytes = ~0ULL; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].entry_offset = 20; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].entry_offset = 2; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].entry_offset = 1ULL << 32; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[1].expected_base += 64; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    plans[0].expected_base += 1ULL << 32; REJECT_CODE(plans, 3, -EINVAL); RESET_CODE();
    REJECT_CODE(plans, (u32)(OPENGPU_CODE_VA_STRIDE / 64u + 1u), -EINVAL);
    for (int allocation = 0; allocation < 5; ++allocation) {
        fail_alloc_after = allocation; REJECT_CODE(plans, 3, -ENOMEM); fail_alloc_after = -1;
    }
    fail_kv_after = 0; REJECT_CODE(plans, 3, -ENOMEM); fail_kv_after = -1;
    /* Only the DMA numbers approach 4 GiB; CPU allocations stay tiny. */
    {
        u64 saved_dma = next_dma;
        next_dma = 0xffffa000ULL; /* five descriptor pages, then one code page */
        assert(!SUBMIT_CODE(plans, 3)); q = last_job;
        assert(q->tess_fragment_code.dma == 0xfffff000ULL &&
               (u64)q->tess_fragment_code.dma + q->tess_fragment_code.size == 0x100000000ULL);
        next_dma = saved_dma;
        assert(opengpu_sched_run_job(&q->base) == &hardware_fence); RETIRE_CODE();
        saved_dma = next_dma; next_dma = 0xffffb000ULL;
        REJECT_CODE(plans, 3, -ERANGE); next_dma = saved_dma;
    }
    /* Unreferenced code and state slots may remain unusable; only state0 runs. */
    for (u32 s = 0; s < 3; ++s)
        put(draws + s * 2048, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, 0);
    memset(states + 512, 0xff, 1024); plans[1].code = plans[2].code = NULL;
    assert(!SUBMIT_CODE(plans, 3)); q = last_job;
    assert(opengpu_sched_run_job(&q->base) == &hardware_fence); RETIRE_CODE();
    /* A full existing 4 MiB code window, including its last instruction, fits.
     * This is an address/capacity case, not a large shader simulation. */
    {
        unsigned char *full = malloc(OPENGPU_CODE_VA_STRIDE);
        assert(full); memset(full, 0x96, OPENGPU_CODE_VA_STRIDE);
        plans[0].code = full; plans[0].bytes = OPENGPU_CODE_VA_STRIDE;
        plans[0].entry_offset = OPENGPU_CODE_VA_STRIDE - 4;
        assert(!SUBMIT_CODE(plans, 1)); q = last_job;
        assert(q->tess_fragment_code.size == OPENGPU_CODE_VA_STRIDE &&
            tess_word(q->tess_states.cpu, OPENGPU_TESS_STATE_SHADER_PC_WORD) == code_va + OPENGPU_CODE_VA_STRIDE - 4);
        assert(!memcmp(q->tess_fragment_code.cpu, full, OPENGPU_CODE_VA_STRIDE)); free(full);
        assert(opengpu_sched_run_job(&q->base) == &hardware_fence); RETIRE_CODE();
    }
    RESET_CODE();
    for (u32 s = 0; s < 3; ++s) put(draws + s * 2048, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 0);
    memset(states, 0xff, sizeof(states));
    assert(!SUBMIT_CODE(NULL, 0)); q = last_job; assert(!q->tess_fragment_code.cpu);
    assert(opengpu_sched_run_job(&q->base) == &hardware_fence); RETIRE_CODE();
    RESET_CODE(); input.scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD; gpu.hw.tessellation_feedback_transport = true;
    assert(!SUBMIT_CODE(NULL, 0)); q = last_job; assert(!q->tess_fragment_code.cpu);
    assert(opengpu_sched_run_job(&q->base) == &hardware_fence); RETIRE_CODE();
    input.scope.flags = 0; gpu.hw.tessellation_feedback_transport = false; RESET_CODE();
    /* No RX leaf may be installed when tracking is full, even if a later
     * unmap would fail. This also guards the four data snapshot map calls. */
    assert(!SUBMIT_CODE(plans, 3)); q = last_job;
    q->temporary_mapping_count = OPENGPU_MAX_TEMP_MAPPINGS;
    { int before = kicks; u32 tables = context.vm.l1_count;
      assert(tess_map_fragment_code(q) == -ENOSPC);
      assert(tess_map_snapshot(q, &q->tess_job, OPENGPU_TESS_JOB_SLOT, q->tess_job_va) == -ENOSPC);
      assert(context.vm.l1_count == tables && kicks == before);
      struct opengpu_buffer *l1 = vm_l1_find(&context.vm, code_va >> 22);
      assert(!l1 || !((u32 *)l1->cpu)[(code_va >> 12) & 1023]); }
    q->temporary_mapping_count = 0; RETIRE_CODE();
    /* A pre-existing code window is preserved; this job only revokes its own
     * four successfully acquired descriptor mappings on failure. */
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, code_va, 0x39000000, 4096, 2));
    assert(!SUBMIT_CODE(plans, 3)); q = last_job;
    { int before = kicks; assert(opengpu_sched_run_job(&q->base) == ERR_PTR(-EEXIST));
      assert(kicks == before && q->temporary_mapping_count == 4); }
    RETIRE_CODE();
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, code_va, 4096));
    /* Real alias verification runs under the transport's submit lock before
     * invalidation or doorbell publication. Remove the test-created alias. */
    assert(!SUBMIT_CODE(plans, 3)); q = last_job;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, 0x76000000, q->tess_fragment_code.dma, 4096, 2));
    { int before = kicks, inv = invalidations;
      assert(opengpu_sched_run_job(&q->base) == ERR_PTR(-EACCES));
      assert(kicks == before && invalidations == inv && q->temporary_mapping_count == 5); }
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, 0x76000000, 4096)); RETIRE_CODE();
    assert(!SUBMIT_CODE(plans, 3)); q = last_job; fail_submit = -EIO;
    assert(opengpu_sched_run_job(&q->base) == ERR_PTR(-EIO));
    fail_submit = 0; RETIRE_CODE();
    assert(!SUBMIT_CODE(plans, 3)); q = last_job;
    assert(opengpu_sched_run_job(&q->base) == &hardware_fence);
    assert(opengpu_sched_timedout_job(&q->base) == DRM_GPU_SCHED_STAT_RESET && !busy); RETIRE_CODE();
    /* Failed revocation retains code, descriptors, object and context. The
     * explicit test reset below is not automatic driver recovery. */
    assert(!SUBMIT_CODE(plans, 3)); q = last_job;
    assert(opengpu_sched_run_job(&q->base) == &hardware_fence);
    { int before = live_allocations; opengpu_sched_free_job(&q->base);
      assert(gpu.hw.wedged && busy && live_allocations == before && q->tess_fragment_code.cpu &&
          context.refs == 2 && object.refs == 1); }
    busy = 0; gpu.hw.wedged = false;
    for (u32 i = 0; i < q->temporary_mapping_count; ++i)
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, q->temporary_mappings[i].va, q->temporary_mappings[i].size));
    opengpu_tessellation_job_release(q); drm_gem_object_put(q->objects[0]);
    kfree(q->objects); opengpu_context_put(&context); kfree(q);
    assert(!SUBMIT_CODE(plans, 3)); q = last_job;
    assert(opengpu_sched_run_job(&q->base) == &hardware_fence); RETIRE_CODE();
    release_context_fence(&context); mutex_unlock(&file.lock);
    opengpu_mmu_vm_destroy(&gpu, &context.vm); opengpu_mmu_fini(&gpu);
    assert(!live_allocations && !live_kv && !object.refs && context.refs == 1);
    puts("owned fragment code: all domains/required plans/expected VA/RX/4MiB/lifetime/reject/drain/reuse PASS (C only)");
#undef RESET_CODE
#undef SUBMIT_CODE
#undef REJECT_CODE
#undef RETIRE_CODE
    return 0;
}
