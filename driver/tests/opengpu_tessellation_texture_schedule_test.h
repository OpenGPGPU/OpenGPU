/* Real scheduler/MMU tests for complete typed fragment texture ownership. */
static void texture_plans(struct opengpu_tessellation_texture_plan *plans,
    struct opengpu_tessellation_texture_binding *bindings,
    struct opengpu_tessellation_texture_level *levels)
{
    memset(bindings, 0, 32 * sizeof(*bindings));
    memset(levels, 0, 32 * sizeof(*levels));
    for (u32 i = 0; i < 32; ++i) {
        bindings[i].present = 1;
        bindings[i].data = (struct opengpu_tessellation_uniform_view) { 14, 0x100, 0x1000 };
        bindings[i].image = (struct opengpu_compute_texture_image_config) {
            .format = OPENGPU_TEXTURE_FORMAT_R32F, .width = 1, .height = 1, .depth = 1, .layers = 1 };
        bindings[i].levels = &levels[i]; bindings[i].level_count = 1;
        levels[i].offset = 4u * i;
        levels[i].view = (struct opengpu_compute_texture_level_config) {
            .width = 1, .height = 1, .depth = 1, .layers = 1, .block_bytes = 4, .row_pitch = 4 };
    }
    for (u32 i = 0; i < 3; ++i)
        plans[i] = (struct opengpu_tessellation_texture_plan) { i, 32, bindings };
}

static void texture_records(unsigned char *job, unsigned char *frame, unsigned char *draws,
    unsigned char *states, u32 domain, u32 frame_va)
{
    owned_uniform_records(job, frame, draws, states, domain, frame_va);
    for (u32 i = 0; i < 3; ++i) {
        put(states + 512 * i, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD, 1);
        put(states + 512 * i, OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD, 0xdead0000);
    }
}

static int test_owned_texture(void)
{
    unsigned char job[512], frame[2048], draws[6144], states[1536];
    struct opengpu_device gpu = { .hw = { .capabilities = GPU_CAP_UNIFIED_COMMANDS,
        .tessellation_transport = true, .tessellation_engines = 2, .tessellation_virtual_images = true } };
    struct opengpu_render_context context = { .gpu = &gpu, .id = 7, .refs = 1 };
    struct opengpu_file file = { .gpu = &gpu }; struct drm_file drm = {0};
    struct dma_resv fr = { .held = 1 }, ar = { .held = 1 }, br = { .held = 1 };
    struct drm_gem_object fo = { .resv = &fr }, ao = { .resv = &ar }, bo = { .resv = &br };
    struct opengpu_resource_binding fb = { .object = &fo, .type = OPENGPU_RESOURCE_COMPUTE_STORAGE,
        .va = OPENGPU_STORAGE_VA_BASE, .dma = 0x34000000, .size = 0x4000 };
    struct opengpu_resource_binding a = { .object = &ao, .type = OPENGPU_RESOURCE_COMPUTE_STORAGE,
        .va = OPENGPU_STORAGE_VA_BASE + OPENGPU_STORAGE_VA_STRIDE, .dma = 0x35000000, .size = 0x20000 };
    struct opengpu_resource_binding b = { .object = &bo, .type = OPENGPU_RESOURCE_COMPUTE_STORAGE,
        .va = OPENGPU_STORAGE_VA_BASE + 2 * OPENGPU_STORAGE_VA_STRIDE, .dma = 0x36000000, .size = 0x6000 };
    struct opengpu_sched_dep deps[] = { { &fo, DMA_RESV_USAGE_READ, DMA_RESV_USAGE_WRITE },
        { &ao, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ }, { &bo, DMA_RESV_USAGE_WRITE, DMA_RESV_USAGE_READ } };
    struct opengpu_tessellation_uniform_plan uniforms[3];
    struct opengpu_tessellation_texture_plan plans[3];
    struct opengpu_tessellation_texture_binding *bindings = calloc(32, sizeof(*bindings));
    struct opengpu_tessellation_texture_level *levels = calloc(32, sizeof(*levels));
    struct opengpu_tessellation_job_input input = { .job = job, .job_size = sizeof(job),
        .frame = frame, .frame_size = sizeof(frame), .draws = draws, .draws_size = sizeof(draws),
        .states = states, .states_size = sizeof(states) };
    struct opengpu_sched_job *q;
    assert(bindings && levels);
    mutex_init(&file.lock); mutex_lock(&file.lock);
    assert(!opengpu_mmu_init(&gpu) && !opengpu_mmu_vm_create(&gpu, &context.vm));
    context.bindings[12] = &fb; context.bindings[13] = &a; context.bindings[14] = &b;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, fb.va, fb.dma, fb.size, 0));
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, b.va, b.dma, b.size, OPENGPU_MMU_POLICY_UNCACHED));
#define RESET_TEXTURE() do { texture_records(job, frame, draws, states, 0, fb.va); \
    owned_uniform_plans(uniforms); texture_plans(plans, bindings, levels); } while (0)
#define SUBMIT_TEXTURE() opengpu_tessellation_resources_sched_submit(&file, &drm, &context, &input, \
    uniforms, 3, plans, 3, 0, NULL, deps, 3, NULL)
#define REJECT_TEXTURE(error) do { int before_alloc = live_allocations, before_kv = live_kv, \
    before_push = pushed, before_kick = kicks, before_inv = invalidations; \
    int before_fences[] = { fr.fences, ar.fences, br.fences }; \
    struct dma_fence *held = context.last_fence; \
    assert(SUBMIT_TEXTURE() == (error)); \
    assert(live_allocations == before_alloc && live_kv == before_kv && pushed == before_push && \
        kicks == before_kick && invalidations == before_inv && context.last_fence == held && \
        context.refs == 1 && !fo.refs && !ao.refs && !bo.refs && \
        fr.fences == before_fences[0] && ar.fences == before_fences[1] && br.fences == before_fences[2]); } while (0)
#define RETIRE_TEXTURE() do { q = last_job; assert(opengpu_sched_run_job(&q->base) == &hardware_fence); \
    busy = 0; opengpu_sched_free_job(&q->base); assert(context.refs == 1 && !ao.refs && !bo.refs && !fo.refs && !live_kv); } while (0)
    for (u32 domain = 0; domain < 3; ++domain) {
        RESET_TEXTURE(); texture_records(job, frame, draws, states, domain, fb.va);
        assert(!SUBMIT_TEXTURE()); q = last_job;
        assert(q->tess_job.size == 512 + 3 * 1664 + 3 * 73856 && q->object_count == 3);
        memset(bindings, 0, 32 * sizeof(*bindings)); memset(levels, 0, 32 * sizeof(*levels));
        memset(plans, 0, sizeof(plans)); memset(uniforms, 0, sizeof(uniforms)); memset(states, 0, sizeof(states));
        for (u32 state = 0; state < 3; ++state) {
            u64 off = 512 + 3 * 1664 + state * 73856;
            unsigned char *root = (unsigned char *)q->tess_job.cpu + off;
            assert(tess_word((unsigned char *)q->tess_states.cpu + state * 512,
                OPENGPU_TESS_STATE_TEXTURE_ADDRESS_WORD) == q->tess_job_va + off);
            assert(tess_word(root, 0) == OPENGPU_NATIVE_TEXTURE_ROOT_MAGIC && tess_word(root, 1) == 128 &&
                tess_word(root, 6) == 1 && tess_word(root, 7) == 32);
            for (u32 unit = 0; unit < 32; ++unit) {
                unsigned char *binding = root + 128 + unit * 64;
                unsigned char *image = root + 128 + 32 * 64 + unit * 128;
                unsigned char *sampler = root + 128 + 32 * (64 + 128) + unit * 64;
                unsigned char *level = root + 128 + 32 * 256 + unit * 2048;
                assert(tess_word(binding, 0) == 0x47584201 && tess_word(binding, 6) == 1 && tess_word(binding, 7) == unit);
                assert(tess_word(image, 0) == 0x47584901 && tess_word(image, 6) == unit &&
                    tess_word(image, 19) == q->tess_job_va + off + 128 + 32 * 256 + unit * 2048);
                assert(tess_word(sampler, 0) == 0x47585301 && tess_word(sampler, 6) == unit);
                assert(tess_word(level, 0) == 0x47584c01 && tess_word(level, 12) == a.va + 0x100 + unit * 4);
                for (u32 word = 32; word < 512; ++word) assert(!tess_word(level, word));
            }
        }
        RETIRE_TEXTURE();
    }
    RESET_TEXTURE();
    put(states, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD, 2); REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    put(states, OPENGPU_TESS_STATE_TEXTURE_VERSION_WORD, 0); REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    plans[0].state_handle = 1; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    plans[0].binding_count = 33; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    plans[0].bindings = NULL; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    plans[1].state_handle = 0; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].present = 2; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].data.slot = 0; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].data.bytes = 0; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].data.offset = a.size; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].level_count = 17; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].levels = NULL; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    levels[31].view.width = 2; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    levels[31].view.block_bytes = 8; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    levels[31].offset = bindings[31].data.bytes; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].sampler.min_filter = 6; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    bindings[31].image.format = 64; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    deps[1].signal_usage = DMA_RESV_USAGE_BOOKKEEP; REJECT_TEXTURE(-EINVAL); deps[1].signal_usage = DMA_RESV_USAGE_READ;
    a.object = NULL; REJECT_TEXTURE(-EINVAL); a.object = &ao;
    ao.import_attach = &ao; REJECT_TEXTURE(-EINVAL); ao.import_attach = NULL;
    a.type = OPENGPU_RESOURCE_VERTEX_BUFFER; REJECT_TEXTURE(-EINVAL); a.type = OPENGPU_RESOURCE_COMPUTE_STORAGE;
    {
        u32 *pte = NULL;
        /* Locate the real table instead of assuming an L1 allocation index. */
        for (u32 i = 0; i < context.vm.l1_count; ++i)
            if (context.vm.l1_region[i] == a.va >> 22) pte = &((u32 *)context.vm.l1[i].cpu)[(a.va >> 12) & 1023u];
        assert(pte); u32 saved = *pte;
        *pte &= ~0x40u; REJECT_TEXTURE(-EINVAL); *pte = saved;
        *pte |= 0x10u; REJECT_TEXTURE(-EINVAL); *pte = saved;
        *pte ^= 1u << 10; REJECT_TEXTURE(-EINVAL); *pte = saved;
    }
    /* Texture-only admission isolates these aliases from the old uniform tests. */
    for (u32 i = 0; i < 3; ++i) { put(states + 512 * i, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD, 0); put(states + 512 * i, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD, 0); }
    bindings[31].data.slot = 13; bindings[31].data.offset = 0x1000; REJECT_TEXTURE(-EINVAL); RESET_TEXTURE();
    for (u32 i = 0; i < 3; ++i) { put(states + 512 * i, OPENGPU_TESS_STATE_UNIFORM_VERSION_WORD, 0); put(states + 512 * i, OPENGPU_TESS_STATE_UNIFORM_ADDRESS_WORD, 0); }
    {
        struct opengpu_resource_binding code = { .object = &ao, .type = OPENGPU_RESOURCE_SHADER,
            .offset = 0x100, .size = 128, .dma = 0x39000000 };
        context.bindings[2] = &code; REJECT_TEXTURE(-EINVAL);
        code.object = &bo; code.offset = 0; code.dma = a.dma + 0x100; REJECT_TEXTURE(-EINVAL);
        context.bindings[2] = NULL;
    }
    a.object = &fo; REJECT_TEXTURE(-EINVAL); a.object = &ao;
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size)); a.dma = fb.dma;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    REJECT_TEXTURE(-EINVAL);
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size)); a.dma = 0x35000000;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    {
        /* The four copied snapshots consume five pages before the expanded
         * metadata allocation. Point data into its second page, not GNQ. */
        u64 future_tail = next_dma + 6u * 4096u;
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size)); a.dma = future_tail;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
        assert(next_dma + 6u * 4096u == future_tail);
        REJECT_TEXTURE(-EINVAL); assert(next_dma > future_tail);
        assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, a.va, a.size)); a.dma = 0x35000000;
        assert(!opengpu_mmu_vm_map(&gpu, &context.vm, a.va, a.dma, a.size, OPENGPU_MMU_POLICY_UNCACHED));
    }
    RESET_TEXTURE();
    for (int allocation = 0; allocation < 5; ++allocation) {
        fail_alloc_after = allocation; REJECT_TEXTURE(-ENOMEM); fail_alloc_after = -1;
    }
    for (int allocation = 0; allocation < 99; ++allocation) {
        fail_kv_after = allocation; REJECT_TEXTURE(-ENOMEM); fail_kv_after = -1;
    }
    assert(!SUBMIT_TEXTURE()); q = last_job;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, 0x76000000, q->tess_job.dma + 8192, 4096, OPENGPU_MMU_POLICY_UNCACHED));
    { int before = kicks; assert(opengpu_sched_run_job(&q->base) == ERR_PTR(-EACCES)); assert(kicks == before); }
    opengpu_sched_free_job(&q->base);
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, 0x76000000, 4096));
    assert(!SUBMIT_TEXTURE()); RETIRE_TEXTURE();
    /* Sparse unit31 and the highest level slot15 remain representable. */
    for (u32 i = 0; i < 31; ++i) bindings[i].present = 0;
    bindings[31].image.base_level = bindings[31].image.max_level = 15; levels[31].view.level = 15;
    assert(!SUBMIT_TEXTURE()); q = last_job;
    { unsigned char *root = (unsigned char *)q->tess_job.cpu + 512 + 3 * 1664;
      unsigned char *last = root + 128 + 32 * 256 + 31 * 2048;
      for (u32 word = 0; word < 15 * 32; ++word) assert(!tess_word(last, word));
      assert(tess_word(last + 15 * 128, 0) == 0x47584c01); }
    RETIRE_TEXTURE();
    /* binding_count is an extent, never a compact count of present entries. */
    for (u32 i = 0; i < 3; ++i) plans[i].binding_count = 1;
    assert(!SUBMIT_TEXTURE()); q = last_job;
    { unsigned char *root = (unsigned char *)q->tess_job.cpu + 512 + 3 * 1664;
      assert(tess_word(root, 7) == 1 && tess_word(root, 9) - tess_word(root, 8) == 64);
      assert(tess_word(root + 128, 0) == 0); }
    RETIRE_TEXTURE();
    /* Complete mip spans include later pages and accept unordered plans. */
    RESET_TEXTURE();
    {
        struct opengpu_tessellation_texture_level chain[14] = {0}, swap;
        u64 offset = 0;
        for (u32 i = 0; i < 14; ++i) {
            chain[i].offset = offset;
            chain[i].view = (struct opengpu_compute_texture_level_config) {
                .level = i, .width = 8192u >> i, .height = 1, .depth = 1,
                .layers = 1, .row_pitch = (8192u >> i) * 4, .block_bytes = 4 };
            offset += chain[i].view.row_pitch;
        }
        swap = chain[0]; chain[0] = chain[13]; chain[13] = swap;
        for (u32 i = 1; i < 32; ++i) bindings[i].present = 0;
        bindings[0].data.offset = 0; bindings[0].data.bytes = 65536;
        bindings[0].image.width = 8192; bindings[0].image.max_level = 13;
        bindings[0].sampler.min_filter = 2; bindings[0].levels = chain; bindings[0].level_count = 14;
        assert(offset == 65532 && !SUBMIT_TEXTURE()); RETIRE_TEXTURE();
        bindings[0].level_count = 13; REJECT_TEXTURE(-EINVAL); bindings[0].level_count = 14;
        chain[1].view.level = 13; REJECT_TEXTURE(-EINVAL); chain[1].view.level = 1;
        chain[1].offset = 0; REJECT_TEXTURE(-EINVAL); chain[1].offset = 32768;
        bindings[0].data.bytes = 65531; REJECT_TEXTURE(-EINVAL); bindings[0].data.bytes = 65536;
        for (u32 i = 0; i < context.vm.l1_count; ++i)
            if (context.vm.l1_region[i] == a.va >> 22) {
                u32 *pte = &((u32 *)context.vm.l1[i].cpu)[((a.va + 0xf000) >> 12) & 1023u];
                u32 saved = *pte; *pte ^= 1u << 10; REJECT_TEXTURE(-EINVAL); *pte = saved;
            }
        assert(!SUBMIT_TEXTURE()); RETIRE_TEXTURE();
    }
    /* Plane/slice/block footprints use the same canonical image ABI rules. */
    for (u32 target = 1; target <= 3; ++target) {
        RESET_TEXTURE(); for (u32 i = 1; i < 32; ++i) bindings[i].present = 0;
        bindings[0].image.target = levels[0].view.target = target;
        bindings[0].image.width = levels[0].view.width = 2;
        bindings[0].image.height = levels[0].view.height = 2;
        levels[0].view.row_pitch = 8;
        if (target == 3) {
            bindings[0].image.depth = levels[0].view.depth = 2;
            levels[0].view.slice_pitch = 16;
        } else {
            levels[0].view.layer_pitch = 16;
            if (target == 1) bindings[0].image.layers = levels[0].view.layers = 3;
        }
        assert(!SUBMIT_TEXTURE()); RETIRE_TEXTURE();
        if (target == 3) levels[0].view.slice_pitch = 15;
        else levels[0].view.layer_pitch = 15;
        REJECT_TEXTURE(-EINVAL);
    }
    RESET_TEXTURE(); for (u32 i = 1; i < 32; ++i) bindings[i].present = 0;
    bindings[0].image.format = OPENGPU_TEXTURE_FORMAT_RGB8_ETC2;
    bindings[0].image.width = levels[0].view.width = 4;
    bindings[0].image.height = levels[0].view.height = 4;
    levels[0].view.block_4x4 = true; levels[0].view.block_bytes = 8; levels[0].view.row_pitch = 8;
    assert(!SUBMIT_TEXTURE()); RETIRE_TEXTURE();
    levels[0].view.block_4x4 = false; REJECT_TEXTURE(-EINVAL);
    /* A texture at the last 64-byte line keeps the exclusive 2^32 limit. */
    RESET_TEXTURE();
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, b.va, b.size)); b.va = 0xffffa000u;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, b.va, b.dma, b.size, OPENGPU_MMU_POLICY_UNCACHED));
    bindings[31].data = (struct opengpu_tessellation_uniform_view) { 15, 0x5fc0, 64 };
    levels[31].offset = 60;
    assert(!SUBMIT_TEXTURE()); q = last_job;
    { unsigned char *root = (unsigned char *)q->tess_job.cpu + 512 + 3 * 1664;
      unsigned char *image = root + 128 + 32 * 64 + 31 * 128;
      unsigned char *level = root + 128 + 32 * 256 + 31 * 2048;
      assert(tess_word(image, 16) == 0 && tess_word(image, 17) == 1 && tess_word(level, 12) == 0xfffffffcu); }
    RETIRE_TEXTURE();
    bindings[31].data.bytes = 65; REJECT_TEXTURE(-EINVAL);
    assert(!opengpu_mmu_vm_unmap(&gpu, &context.vm, b.va, b.size));
    b.va = OPENGPU_STORAGE_VA_BASE + 2 * OPENGPU_STORAGE_VA_STRIDE;
    assert(!opengpu_mmu_vm_map(&gpu, &context.vm, b.va, b.dma, b.size, OPENGPU_MMU_POLICY_UNCACHED));
    /* Unreferenced states and zero/discard jobs add no texture dependencies. */
    RESET_TEXTURE(); for (u32 i = 0; i < 3; ++i) put(draws + 2048 * i, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_TEMPLATE_SOURCE_STATE_WORD, 0);
    plans[1].bindings = plans[2].bindings = NULL; memset(states + 512, 0xff, 1024);
    assert(!SUBMIT_TEXTURE()); RETIRE_TEXTURE();
    for (u32 i = 0; i < 3; ++i) put(draws + 2048 * i, OPENGPU_NATIVE_TESSELLATION_SEQUENCE_COUNT_WORD, 0);
    memset(states, 0xff, sizeof(states));
    assert(!opengpu_tessellation_resources_sched_submit(&file, &drm, &context, &input, NULL, 0, NULL, 0, 0, NULL, deps, 1, NULL)); RETIRE_TEXTURE();
    RESET_TEXTURE(); input.scope.flags = GPU_UCMD_FLAG_RASTER_DISCARD; gpu.hw.tessellation_feedback_transport = true;
    assert(!opengpu_tessellation_resources_sched_submit(&file, &drm, &context, &input, NULL, 0, NULL, 0, 0, NULL, deps, 1, NULL)); RETIRE_TEXTURE();
    release_context_fence(&context); free(bindings); free(levels);
    for (u32 i = 0; i < 128; ++i) assert(!scheduler_fences[i].finished.refs);
    mutex_unlock(&file.lock); opengpu_mmu_vm_destroy(&gpu, &context.vm); opengpu_mmu_fini(&gpu);
    assert(!live_allocations && !live_kv && context.refs == 1 && !ao.refs && !bo.refs && !fo.refs);
    puts("owned fragment GXR/GXB/GXI/GXS/GXL: all domains/32 units/16 slots, backing/PTE/deps/tail lifetime and reuse PASS (C only)");
#undef RESET_TEXTURE
#undef SUBMIT_TEXTURE
#undef REJECT_TEXTURE
#undef RETIRE_TEXTURE
    return 0;
}
