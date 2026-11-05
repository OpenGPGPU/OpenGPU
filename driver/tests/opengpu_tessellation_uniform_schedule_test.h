/* Exercise the production typed entry with real scheduler/MMU preparation. */
static void owned_uniform_plans(struct opengpu_tessellation_uniform_plan *plans)
{
    memset(plans, 0, 3 * sizeof(*plans));
    for (u32 i = 0; i < 3; ++i) {
        plans[i].state_handle = i;
        plans[i].active_mask = 3;
        plans[i].block_map[1] = 23;
        plans[i].bindings[0] = (struct opengpu_tessellation_uniform_view) { 14, 0x2000, 16384 };
        plans[i].bindings[23] = (struct opengpu_tessellation_uniform_view) { 14, 0x2100, 256 };
        plans[i].default_present = 1;
        plans[i].default_view = (struct opengpu_tessellation_uniform_view) { 15, 4, 4096 };
    }
}

static void owned_uniform_records(unsigned char *job, unsigned char *frame,
    unsigned char *draws, unsigned char *states, u32 domain, u32 frame_va)
{
    make_records(job, frame, draws, 7, domain, 3, 2048);
    make_bound_frame(frame, frame_va, frame_va, 2);
    put(frame, OPENGPU_TESS_FRAME_STATE_LIMIT, frame_va + 0x1000 + 1536);
    for (u32 i = 0; i < 3; ++i) {
        make_zero_input_draw(draws + 2048 * i, domain, 1);
        put(draws + 2048 * i, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, i);
        make_fragment_state(states + 512 * i, frame, 2, i);
        put(states + 512 * i, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD, 1);
        /* The new entry constructs roots; no caller root address is followed. */
        put(states + 512 * i, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD, 0xe8000000);
    }
}

static void reject_owned_uniform(struct opengpu_file *file, struct drm_file *drm,
    struct opengpu_render_context *context, const struct opengpu_tessellation_job_input *input,
    const struct opengpu_tessellation_uniform_plan *plans, u32 plan_count,
    const struct opengpu_sched_dep *deps, u32 dep_count, int expected)
{
    int refs[4], fences[4];
    int allocations = live_allocations, pushes = pushed, doorbells = kicks;
    int invalidates = invalidations, contexts = context->refs;
    struct dma_fence *fence = context->last_fence;
    assert(dep_count <= 4);
    for (u32 i = 0; i < dep_count; ++i) {
        refs[i] = deps[i].object->refs; fences[i] = deps[i].object->resv->fences;
    }
    assert(opengpu_tessellation_uniform_sched_submit(file, drm, context, input,
        plans, plan_count, 0, NULL, deps, dep_count, NULL) == expected);
    assert(live_allocations == allocations && pushed == pushes && kicks == doorbells &&
        invalidations == invalidates && context->refs == contexts && context->last_fence == fence);
    for (u32 i = 0; i < dep_count; ++i)
        assert(deps[i].object->refs == refs[i] && deps[i].object->resv->fences == fences[i]);
}

static int test_owned_uniform(void)
{
    unsigned char job[512], frame[2048], draws[6144], states[1536];
    struct opengpu_device gpu = { .hw = { .capabilities = GPU_CAP_UNIFIED_COMMANDS,
        .tessellation_transport = true, .tessellation_engines = 2,
        .tessellation_virtual_images = true } };
    struct opengpu_render_context context = { .gpu = &gpu, .id = 7, .refs = 1 };
    struct opengpu_file file = { .gpu = &gpu }; struct drm_file drm = {0};
    struct dma_resv frame_resv = { .held = 1 }, a_resv = { .held = 1 }, b_resv = { .held = 1 };
    struct drm_gem_object frame_object = { .resv = &frame_resv },
        a_object = { .resv = &a_resv }, b_object = { .resv = &b_resv };
    struct opengpu_resource_binding frame_binding = { .object = &frame_object,
        .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .va = OPENGPU_STORAGE_VA_BASE,
        .dma = 0x34000000, .size = 0x4000 };
    struct opengpu_resource_binding a = { .object = &a_object,
        .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .va = OPENGPU_STORAGE_VA_BASE + OPENGPU_STORAGE_VA_STRIDE,
        .dma = 0x35000000, .size = 0x6000 };
    struct opengpu_resource_binding b = { .object = &b_object,
        .type = OPENGPU_RESOURCE_COMPUTE_STORAGE, .va = OPENGPU_STORAGE_VA_BASE + 2 * OPENGPU_STORAGE_VA_STRIDE,
        .dma = 0x36000000, .size = 0x6000 };
    struct opengpu_sched_dep deps[] = {
        { &frame_object, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE },
        { &a_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
        { &b_object, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ },
    };
    struct opengpu_tessellation_uniform_plan plans[3];
    struct opengpu_tessellation_job_input input = { .job = job, .job_size = sizeof(job),
        .frame = frame, .frame_size = sizeof(frame), .draws = draws, .draws_size = sizeof(draws),
        .states = states, .states_size = sizeof(states) };
    struct opengpu_sched_job *queued;
    mutex_init(&file.lock); mutex_lock(&file.lock);
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu, &context.vm));
    context.bindings[12] = &frame_binding; context.bindings[13] = &a; context.bindings[14] = &b;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, frame_binding.va, frame_binding.dma, frame_binding.size, 0));
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, b.va, b.dma, b.size, OPENGPU_MMU_POLICY_UNCACHED));
    for (u32 domain = 0; domain < 3; ++domain) {
        owned_uniform_records(job, frame, draws, states, domain, frame_binding.va);
        owned_uniform_plans(plans);
        assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
            plans, 3, 0, NULL, deps, 3, NULL));
        queued = last_job;
        assert(queued->tess_job.size == 512 + 3 * 1664 && queued->object_count == 3);
        assert(a_object.refs == 1 && b_object.refs == 1 && frame_object.refs == 1 && context.refs == 2);
        assert(a_resv.wait_usage == DMA_RESV_USAGE_WRITE && a_resv.signal_usage == DMA_RESV_USAGE_READ &&
            b_resv.wait_usage == DMA_RESV_USAGE_WRITE && b_resv.signal_usage == DMA_RESV_USAGE_READ);
        memset(plans, 0, sizeof(plans)); memset(states, 0, sizeof(states));
        for (u32 i = 0; i < 3; ++i) {
            const unsigned char *root = (unsigned char *)queued->tess_job.cpu + 512 + i * 1664;
            const unsigned char *table = root + 64, *def = table + 1536;
            const unsigned char *state = (unsigned char *)queued->tess_states.cpu + i * 512;
            assert(tess_word(state, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD) == queued->tess_job_va + 512 + i * 1664);
            assert(tess_word(root, 0) == OPENGPU_UNIFORM_ROOT_MAGIC && tess_word(root, 1) == 64 &&
                tess_word(root, 2) == 1 && tess_word(root, 3) == 7 && tess_word(root, 4) == 8 && tess_word(root, 5) == 9);
            assert(tess_word(root, OPENGPU_UNIFORM_ROOT_STAGE_WORD) == 1 &&
                tess_word(root, OPENGPU_UNIFORM_ROOT_ACTIVEMASK_WORD) == 3 &&
                tess_word(root, OPENGPU_UNIFORM_ROOT_MAPLOW_WORD) == 23 * 32 &&
                !tess_word(root, OPENGPU_UNIFORM_ROOT_MAPHIGH_WORD));
            assert(tess_word(root, OPENGPU_UNIFORM_ROOT_BINDINGSBASE_WORD) == queued->tess_job_va + 576 + i * 1664 &&
                tess_word(root, OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD) == queued->tess_job_va + 2112 + i * 1664);
            assert(tess_word(root, OPENGPU_UNIFORM_ROOT_DEFAULTVIEW_WORD) == queued->tess_job_va + 2112 + i * 1664 &&
                tess_word(root, OPENGPU_UNIFORM_ROOT_DEFAULTPRESENT_WORD) == 1);
            for (u32 binding = 0; binding < 24; ++binding) {
                const unsigned char *view = table + binding * 64;
                if (binding == 0 || binding == 23) {
                    assert(tess_word(view, 0) == OPENGPU_UNIFORM_BINDING_MAGIC &&
                        tess_word(view, OPENGPU_UNIFORM_BINDING_BINDINGINDEX_WORD) == binding &&
                        tess_word(view, OPENGPU_UNIFORM_BINDING_STAGEMASK_WORD) == 2 &&
                        tess_word(view, OPENGPU_UNIFORM_BINDING_READONLY_WORD) == 1);
                } else {
                    for (u32 word = 0; word < 16; ++word) assert(!tess_word(view, word));
                }
            }
            assert(tess_word(table, OPENGPU_UNIFORM_BINDING_BASE_WORD) == a.va + 0x2000 &&
                tess_word(table, OPENGPU_UNIFORM_BINDING_LIMIT_WORD) == a.va + 0x6000);
            assert(tess_word(def, OPENGPU_UNIFORM_BINDING_BINDINGINDEX_WORD) == 31 &&
                tess_word(def, OPENGPU_UNIFORM_BINDING_BASE_WORD) == b.va + 4 &&
                tess_word(def, OPENGPU_UNIFORM_BINDING_LIMIT_WORD) == b.va + 4100 &&
                tess_word(def, OPENGPU_UNIFORM_BINDING_ALLOCATIONBASE_WORD) == b.va &&
                tess_word(def, OPENGPU_UNIFORM_BINDING_ALLOCATIONLIMIT_WORD) == b.va + 4160);
        }
        assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
        assert(!verify(&gpu, &context.vm, queued->tess_job_va, queued->tess_job.dma, queued->tess_job.size));
        busy = 0; opengpu_sched_free_job(&queued->base);
        assert(context.refs == 1 && !a_object.refs && !b_object.refs && !frame_object.refs);
    }
    owned_uniform_records(job, frame, draws, states, 2, frame_binding.va);
    owned_uniform_plans(plans);
#define REJECT_OWNED() reject_owned_uniform(&file, &drm, &context, &input, plans, 3, deps, 3, -EINVAL)
    reject_owned_uniform(&file, &drm, &context, &input, NULL, 0, deps, 3, -EINVAL);
    reject_owned_uniform(&file, &drm, &context, &input, plans,
        (OPENGPU_COMMAND_VA_STRIDE - 512u) / 64u + 1u, deps, 3, -EINVAL);
    reject_owned_uniform(&file, &drm, &context, &input, plans, 3, deps, 2, -EINVAL);
    deps[2].signal_usage = DMA_RESV_USAGE_BOOKKEEP; REJECT_OWNED(); deps[2].signal_usage = DMA_RESV_USAGE_READ;
    plans[0].active_mask |= 1u << 12; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].block_map[0] = 24; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].block_map[2] = 1; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[1].state_handle = 0; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].bindings[0].slot = 0; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].bindings[0].bytes = 16385; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].bindings[0].offset = 0x2004; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].bindings[0].offset = ~(u64)0; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].default_view.bytes = 4097; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].default_view.offset = 5; REJECT_OWNED(); owned_uniform_plans(plans);
    plans[0].default_present = 2; REJECT_OWNED(); owned_uniform_plans(plans);
    a_object.import_attach = &gpu; REJECT_OWNED(); a_object.import_attach = NULL;
    a.type = OPENGPU_RESOURCE_VERTEX_BUFFER; REJECT_OWNED(); a.type = OPENGPU_RESOURCE_COMPUTE_STORAGE;
    a.size -= 1; REJECT_OWNED(); a.size += 1;
    {
        u64 tail_address = a.va + 5 * 4096u;
        const struct opengpu_buffer *table = vm_live_l1(&context.vm, tail_address >> 22);
        assert(table);
        u32 *leaf = (u32 *)table->cpu + ((tail_address >> 12) & 1023u);
        u32 saved = *leaf; assert(saved & 1u);
        *leaf = 0; REJECT_OWNED();
        *leaf = saved & ~2u; REJECT_OWNED();
        *leaf = saved & ~0x40u; REJECT_OWNED();
        *leaf = saved + (1u << 10); REJECT_OWNED();
        *leaf = saved & ~(3u << 8); REJECT_OWNED();
        *leaf = saved | 0x20u; REJECT_OWNED();
        *leaf = saved;
    }
    /* Same-GEM offset alias and a different-object DMA alias are independent
     * rejection paths. The mapped leaf still agrees with the selected data. */
    a.object = &frame_object; REJECT_OWNED(); a.object = &a_object;
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size));
    a.dma = frame_binding.dma;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    REJECT_OWNED();
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size));
    a.dma = 0x35000000;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    {
        u64 future_tail = next_dma + 5u * 4096u + 4096u;
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size));
        a.dma = future_tail;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
        assert(next_dma + 6u * 4096u == future_tail);
        plans[0].bindings[0].offset = 0; plans[0].bindings[0].bytes = 64;
        REJECT_OWNED();
        assert(next_dma == future_tail + 4096u);
        owned_uniform_plans(plans);
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size));
        a.dma = 0x35000000;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    }
    {
        struct opengpu_resource_binding code = { .object = &a_object,
            .type = OPENGPU_RESOURCE_SHADER, .offset = 0x2000, .size = 64, .dma = 0x39000000 };
        context.bindings[2] = &code; REJECT_OWNED();
        code.object = &b_object; code.offset = 0x5000; code.dma = a.dma + 0x2000; REJECT_OWNED();
        context.bindings[2] = NULL;
    }
    for (int allocation = 0; allocation < 5; ++allocation) {
        fail_alloc_after = allocation;
        reject_owned_uniform(&file, &drm, &context, &input, plans, 3, deps, 3, -ENOMEM);
        fail_alloc_after = -1;
    }
    /* A writable alias of a tail page after enqueue must be caught by the
     * whole-allocation verifier; the old 512-byte prefix check cannot see it. */
    assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
        plans, 3, 0, NULL, deps, 3, NULL));
    queued = last_job;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, 0x76000000,
        queued->tess_job.dma + 4096, 4096, OPENGPU_MMU_POLICY_UNCACHED));
    {
        int before_kicks = kicks, before_invalidations = invalidations;
        assert(opengpu_sched_run_job(&queued->base) == ERR_PTR(-EACCES));
        assert(kicks == before_kicks && invalidations == before_invalidations);
    }
    opengpu_sched_free_job(&queued->base);
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, 0x76000000, 4096));
    assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
        plans, 3, 0, NULL, deps, 3, NULL));
    queued = last_job;
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    busy = 0; opengpu_sched_free_job(&queued->base);
    /* Block and default data can share one object/dependency. Conversely a
     * default-only program must not demand dependencies for inactive blocks. */
    owned_uniform_plans(plans);
    for (u32 i = 0; i < 3; ++i) plans[i].default_view.slot = 14;
    assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
        plans, 3, 0, NULL, deps, 2, NULL));
    queued = last_job; assert(queued->object_count == 2 && !b_object.refs);
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    busy = 0; opengpu_sched_free_job(&queued->base);
    owned_uniform_plans(plans);
    for (u32 i = 0; i < 3; ++i) {
        plans[i].active_mask = 0; memset(plans[i].block_map, 0, sizeof(plans[i].block_map));
    }
    {
        struct opengpu_sched_dep defaults[] = { deps[0], deps[2] };
        assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
            plans, 3, 0, NULL, defaults, 2, NULL));
        queued = last_job; assert(queued->tess_job.size == 512 + 3 * 128 && !a_object.refs);
        for (u32 i = 0; i < 3; ++i) {
            const unsigned char *root = (unsigned char *)queued->tess_job.cpu + 512 + 128 * i;
            assert(!tess_word(root, OPENGPU_UNIFORM_ROOT_BINDINGSBASE_WORD) &&
                !tess_word(root, OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD) &&
                !tess_word(root, OPENGPU_UNIFORM_ROOT_BINDINGSLIMIT_WORD + 1));
        }
        assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
        busy = 0; opengpu_sched_free_job(&queued->base);
    }
    /* The last full block can end at exclusive 2^32; no address truncation. */
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size));
    a.va = 0xffffa000u;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    owned_uniform_plans(plans);
    assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
        plans, 3, 0, NULL, deps, 3, NULL));
    queued = last_job;
    assert(tess_word((unsigned char *)queued->tess_job.cpu + 576, OPENGPU_UNIFORM_BINDING_LIMIT_WORD) == 0 &&
        tess_word((unsigned char *)queued->tess_job.cpu + 576, OPENGPU_UNIFORM_BINDING_LIMIT_WORD + 1) == 1);
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    busy = 0; opengpu_sched_free_job(&queued->base);
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size));
    a.va = OPENGPU_STORAGE_VA_BASE + OPENGPU_STORAGE_VA_STRIDE;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    /* Only draw-referenced state zero requires a plan. */
    owned_uniform_records(job, frame, draws, states, 0, frame_binding.va);
    owned_uniform_plans(plans);
    for (u32 i = 0; i < 3; ++i)
        put(draws + 2048 * i, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, 0);
    memset(states + 512, 0xff, 1024);
    plans[1].active_mask = plans[2].active_mask = ~0u;
    assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
        plans, 3, 0, NULL, deps, 3, NULL));
    queued = last_job; assert(queued->tess_job.size == 512 + 1664);
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    busy = 0; opengpu_sched_free_job(&queued->base);
    for (u32 i = 0; i < 3; ++i)
        put(draws + 2048 * i, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 0);
    memset(states, 0xff, sizeof(states));
    assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
        NULL, 0, 0, NULL, deps, 1, NULL));
    queued = last_job; assert(queued->tess_job.size == 512);
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    busy = 0; opengpu_sched_free_job(&queued->base);
    owned_uniform_records(job, frame, draws, states, 0, frame_binding.va);
    input.scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD;
    gpu.hw.tessellation_feedback_transport = true;
    assert(!opengpu_tessellation_uniform_sched_submit(&file, &drm, &context, &input,
        NULL, 0, 0, NULL, deps, 1, NULL));
    queued = last_job; assert(queued->tess_job.size == 512);
    assert(opengpu_sched_run_job(&queued->base) == &hardware_fence);
    busy = 0; opengpu_sched_free_job(&queued->base);
#undef REJECT_OWNED
    release_context_fence(&context);
    assert(context.refs == 1 && !frame_object.refs && !a_object.refs && !b_object.refs);
    for (u32 i = 0; i < sizeof(scheduler_fences) / sizeof(scheduler_fences[0]); ++i)
        assert(!scheduler_fences[i].finished.refs);
    mutex_unlock(&file.lock);
    opengpu_mmu_vm_destroy(&gpu, &context.vm); opengpu_mmu_fini(&gpu);
    assert(!live_allocations);
    puts("owned tessellation GUR/GUB/default: all domains, dependencies, real PTEs, tail aliases and healthy reuse PASS (C only)");
    return 0;
}
