/* SPDX-License-Identifier: MIT */
/* Actual Mesa -> Gallium/pipe/lib -> production ioctl/records/MMIO/retirement.
 * The instruction oracle and OS/device replies are CPU models, not a GPU. */
static unsigned indirect_source_handle, indirect_observations;
static uint64_t indirect_source_offset;
static uint32_t indirect_expected[3], indirect_limits[3];
static const struct opengpu_cs_program *indirect_program;

static void indirect_access(int readable)
{
    assert(indirect_source_handle && bos[indirect_source_handle].sparse);
    assert(!mprotect(bos[indirect_source_handle].data, bos[indirect_source_handle].size,
        readable ? PROT_READ | PROT_WRITE : PROT_NONE));
}

static void indirect_observe(const struct cs_buffer_kernel_input *in,
    const struct cs_buffer_kernel_result *r)
{
    const struct drm_opengpu_compute_indirect_resources *q = &in->indirect;
    assert(q->source.handle == indirect_source_handle && q->source.offset == indirect_source_offset &&
        !q->source.pad && !q->v2.reserved && q->v2.flags == OPENGPU_COMPUTE_INDIRECT_KERNARG_COUNTS);
    assert(!memcmp(q->max_groups, indirect_limits, sizeof(indirect_limits)) &&
        !memcmp(r->grid, indirect_expected, sizeof(indirect_expected)));
    assert(r->indirect && r->indirect_offset + 12 <= r->indirect_bytes &&
        r->kernarg == (uint64_t)r->indirect_base + r->indirect_offset);
    assert(!memcmp((const uint8_t *)r->indirect + r->indirect_offset, indirect_expected, 12));
    assert(indirect_program && r->code_bytes >= indirect_program->words * 4u &&
        !memcmp(r->code, indirect_program->code, indirect_program->words * 4u));
    for (unsigned i = 0; i < 3; i++) assert(!in->command.launch.grid[i]);
    if (!in->with_counter_source) {
        static const struct drm_opengpu_compute_counter_source empty;
        assert(!memcmp(&q->resources.source, &empty, sizeof(empty)) && !r->counter_bytes);
    }
    indirect_observations++;
}

static void indirect_counts(struct pipe_resource *source, unsigned offset, const uint32_t counts[3])
{
    indirect_source_handle = pipe_opengpu_resource_handle(opengpu_resource(source)->gpu);
    indirect_source_offset = offset;
    memcpy(indirect_expected, counts, 12);
    memcpy(bos[indirect_source_handle].data + offset, counts, 12);
}

static void indirect_launch(struct pipe_context *p, const struct pipe_grid_info *grid)
{
    for (unsigned i = 0; i < 3; i++)
        indirect_limits[i] = grid->work_dim && i >= grid->work_dim ? 1 : UINT32_MAX;
    indirect_snapshot_access = indirect_access;
    indirect_access(0); /* A Gallium/pipe peek would fault before the real ioctl. */
    p->launch_grid(p, grid);
    indirect_access(1);
    indirect_snapshot_access = NULL;
}

static void indirect_clear(struct pipe_resource *outputs[4])
{
    for (unsigned i = 0; i < 4; i++)
        memset(pipe_opengpu_resource_map(opengpu_resource(outputs[i])->gpu), 0xa5, 8192);
}

static void indirect_result(struct pipe_resource *outputs[4], const uint32_t counts[3], uint32_t seed)
{
    unsigned invocations = counts[0] * counts[1] * counts[2] * 8;
    assert(invocations <= 64);
    for (unsigned b = 0; b < 4; b++) {
        const uint32_t *data = pipe_opengpu_resource_map(opengpu_resource(outputs[b])->gpu);
        for (unsigned word = 0; word < 8192 / 4; word++) {
            uint32_t expected = UINT32_C(0xa5a5a5a5);
            if (b == 2 && word < invocations * 4)
                expected = word % 4 == 3 ? (word / 4) ^ seed : counts[word % 4];
            assert(data[word] == expected);
        }
    }
}

static void indirect_reject_views(struct pipe_screen *s, struct pipe_resource *source,
    struct pipe_resource *output, const struct opengpu_cs_program *program)
{
    struct pipe_opengpu_context *ctx = pipe_opengpu_context_create(opengpu_screen_gpu(s)); assert(ctx);
    assert(!pipe_opengpu_bind_cs(ctx, program->code, program->words * 4u));
    struct pipe_opengpu_storage_view storage[4] = {{0}};
    storage[0] = (struct pipe_opengpu_storage_view) {.resource = opengpu_resource(output)->gpu,
        .bytes = 8192, .access = OPENGPU_COMPUTE_STORAGE_WRITE};
    struct pipe_opengpu_buffer_texture textures[32] = {{0}};
    struct pipe_opengpu_buffer_image images[4] = {{0}};
    const uint32_t local[3] = {4, 2, 1};
    struct pipe_opengpu_indirect_source original = {.resource = opengpu_resource(source)->gpu,
        .binding_bytes = 8192, .offset = 4092, .max_groups = {UINT32_MAX, UINT32_MAX, UINT32_MAX}};
    for (unsigned bad = 0; bad < 3; bad++) {
        struct pipe_opengpu_indirect_source view = original;
        struct pipe_opengpu_counter_view counter = {0};
        if (!bad) view.max_groups[1] = 0;
        if (bad == 1) counter.count = 1; /* Empty means every field is zero. */
        if (bad == 2) view.resource = storage[0].resource; /* Valid views, same allocation. */
        unsigned binds = binding_attempts, requests = resource_request_attempts;
        assert(pipe_opengpu_launch_grid_indirect_resources(ctx, local, storage, textures, images,
            NULL, counter, view, 1000) < 0 && errno == EINVAL);
        assert(binding_attempts == binds && resource_request_attempts == requests);
    }
    pipe_opengpu_context_destroy(ctx);
}

static void indirect_compute_flow(const char *directory)
{
    const uint32_t two[3] = {2, 2, 2}, one[3] = {1, 1, 1}, zero[3] = {0};
    const char *names[] = {"indirect-consumer", "indirect-uniform", "indirect-counter"};
    indirect_observer = indirect_observe;
    for (unsigned test = 0; test < 3; test++) {
        /* Storage-only v2 must not require the unrelated legacy combined
         * resources, texture, image, uniform or atomic-counter features. */
        features = OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE |
            OPENGPU_DRIVER_FEATURE_COMPUTE_STORAGE_BYTE_VIEWS |
            OPENGPU_DRIVER_FEATURE_COMPUTE_INDIRECT_RESOURCES_V2;
        if (test == 1) features |= OPENGPU_DRIVER_FEATURE_COMPUTE_UNIFORMS;
        if (test == 2) features |= OPENGPU_DRIVER_FEATURE_COMPUTE_ATOMIC_COUNTERS |
            OPENGPU_DRIVER_FEATURE_COMPUTE_COUNTER_SOURCE;
        struct pipe_screen *s = opengpu_drm_screen_create(7, NULL); assert(s);
        assert(!s->get_param(s, PIPE_CAP_COMPUTE) &&
            pipe_opengpu_screen_compute_indirect(opengpu_screen_gpu(s)) &&
            !pipe_opengpu_screen_compute_indirect(NULL));
        uint64_t max_grid[3] = {0};
        assert(s->get_compute_param(s, PIPE_SHADER_IR_TGSI, PIPE_COMPUTE_CAP_MAX_GRID_SIZE, max_grid) == sizeof(max_grid));
        for (unsigned i = 0; i < 3; i++) assert(max_grid[i] == UINT32_MAX);
        struct pipe_context *p = s->context_create(s, NULL, 0); assert(p);
        struct pipe_resource *outputs[4], *source = new_buffer(s, 8192);
        for (unsigned i = 0; i < 4; i++) outputs[i] = new_buffer(s, 8192);
        struct pipe_resource *ubo = test == 1 ? new_buffer(s, 512) : NULL;
        struct pipe_resource *counter = test == 2 ? new_buffer(s, 8192) : NULL;
        char path[4096], dump[4096];
        assert(snprintf(path, sizeof(path), "%s/%s.comp", directory, names[test]) > 0);
        assert(snprintf(dump, sizeof(dump), "%s/%s", directory, names[test]) > 0);
        void *front = test == 2 ? cs_frontend_compile_atomic_counters(path, dump) :
            cs_frontend_compile_buffers(path, dump); assert(front);
        struct opengpu_cs_program program;
        assert(!opengpu_cs_emit(cs_frontend_tokens(front), &program));
        indirect_program = &program;
        cs_frontend_storage(front, p, outputs);
        uint32_t seed = 0;
        if (test == 1) {
            uint32_t defaults[4] = {0x9317d509u, 0, 0, 0};
            assert(cs_frontend_default_components(front) == 4);
            cs_frontend_defaults(front, p, defaults, 4);
            struct pipe_resource *blocks[36] = {0}; unsigned offsets[36] = {0}, sizes[36] = {0};
            blocks[35] = ubo; offsets[35] = 16; sizes[35] = 16;
            uint32_t *values = pipe_opengpu_resource_map(opengpu_resource(ubo)->gpu);
            memset(values, 0, 512); values[4] = 0x12345678;
            seed = defaults[0] ^ values[4]; cs_frontend_ubos(front, p, blocks, offsets, sizes);
        }
        if (counter) {
            memset(pipe_opengpu_resource_map(opengpu_resource(counter)->gpu), 0x5a, 8192);
            cs_frontend_counters(front, p, counter, 60, 32);
        }
        struct pipe_compute_state state = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = cs_frontend_tokens(front)};
        void *cs = p->create_compute_state(p, &state); assert(cs); p->bind_compute_state(p, cs);
        struct pipe_grid_info grid = {.work_dim = 3, .block = {4, 2, 1}, .grid = {13, 17, 19},
            .indirect = source, .indirect_offset = 4092};
        unsigned original_kernarg = 0;
        if (!test) {
            struct pipe_grid_info direct = {.work_dim = 3, .block = {4, 2, 1}, .grid = {1, 1, 1}};
            indirect_clear(outputs); p->launch_grid(p, &direct); indirect_result(outputs, one, 0);
            original_kernarg = contexts[last_compute_context].b[2].handle;
            assert(original_kernarg && !memcmp(bos[original_kernarg].data, one, 12));
            indirect_reject_views(s, source, outputs[2], &program);
        }
        const unsigned offsets[] = {0, 52, 4084, 4092, 8180};
        for (unsigned n = 0; n < (test ? 1u : 5u); n++) {
            grid.indirect_offset = test ? 4092 : offsets[n];
            indirect_counts(source, grid.indirect_offset, two); indirect_clear(outputs);
            unsigned before = indirect_submissions, groups = indirect_groups;
            indirect_launch(p, &grid);
            assert(indirect_submissions == before + 1 && indirect_groups == groups + 8);
            indirect_result(outputs, two, seed);
            if (original_kernarg) assert(!memcmp(bos[original_kernarg].data, one, 12));
        }
        if (counter) {
            const uint32_t *words = pipe_opengpu_resource_map(opengpu_resource(counter)->gpu);
            for (unsigned i = 0; i < 8192 / 4; i++)
                assert(words[i] == UINT32_C(0x5a5a5a5a) + (i == 15 ? 64 : 0));
        }
        if (!test) {
            /* Boundary grids exercise real driver admission and opcode14
             * staging, without traversing those enormous grids in the VM. */
            const uint32_t limits[][3] = {{UINT32_MAX, 1, 1}, {65535, 65535, 65535},
                {0, UINT32_MAX, UINT32_MAX}, {UINT32_MAX, 0, UINT32_MAX}, {UINT32_MAX, UINT32_MAX, 0}};
            semantic = 0;
            for (unsigned n = 0; n < sizeof(limits) / sizeof(limits[0]); n++) {
                indirect_counts(source, grid.indirect_offset, limits[n]); indirect_clear(outputs);
                unsigned before = indirect_submissions, groups = indirect_groups;
                indirect_launch(p, &grid);
                assert(indirect_submissions == before + 1 && indirect_groups == groups);
                indirect_result(outputs, zero, 0);
            }
            semantic = 1;
            const uint32_t overflow[3] = {UINT32_MAX, UINT32_MAX, UINT32_MAX};
            indirect_counts(source, grid.indirect_offset, overflow);
            unsigned attempts = resource_request_attempts, admitted = kernel_admissions;
            indirect_launch(p, &grid);
            assert(resource_request_attempts == attempts + 1 && kernel_admissions == admitted && errno == ERANGE);
            indirect_counts(source, grid.indirect_offset, two);
            grid.work_dim = 2; attempts = resource_request_attempts;
            indirect_launch(p, &grid);
            assert(resource_request_attempts == attempts + 1 && kernel_admissions == admitted);
            const uint32_t plane[3] = {2, 2, 1};
            indirect_counts(source, grid.indirect_offset, plane); indirect_clear(outputs);
            indirect_launch(p, &grid); indirect_result(outputs, plane, 0);
            grid.work_dim = 3; indirect_counts(source, grid.indirect_offset, two);
            indirect_clear(outputs); indirect_launch(p, &grid); indirect_result(outputs, two, 0);
            grid.indirect_offset = 1; reject_launch(p, &grid);
            grid.indirect_offset = 8184; reject_launch(p, &grid);
            page_pad_next = 1;
            struct pipe_resource *short_source = new_buffer(s, 64);
            assert(pipe_opengpu_resource_size(opengpu_resource(short_source)->gpu) == 4096);
            grid.indirect = short_source; grid.indirect_offset = 56; reject_launch(p, &grid);
            pipe_resource_reference(&short_source, NULL);
            struct pipe_screen *foreign = opengpu_drm_screen_create(7, NULL); assert(foreign);
            struct pipe_resource *foreign_source = new_buffer(foreign, 8192);
            grid.indirect = foreign_source; grid.indirect_offset = 0; reject_launch(p, &grid);
            pipe_resource_reference(&foreign_source, NULL); foreign->destroy(foreign);
            grid.indirect = source; grid.indirect_offset = 4092;
            indirect_counts(source, grid.indirect_offset, two); indirect_clear(outputs);
            fail_request = DRM_IOCTL_OPENGPU_COMPUTE_INDIRECT_RESOURCES; fail_nth = 1; fail_seen = 0;
            admitted = kernel_admissions; indirect_launch(p, &grid); fail_request = 0;
            assert(kernel_admissions == admitted && fail_seen == 1);
            indirect_launch(p, &grid); indirect_result(outputs, two, 0);
            /* A failed accepted wait retains the original source and private
             * counts. Mutating the source before retirement cannot change it. */
            indirect_clear(outputs); wait_error_kind = 4; wait_error = 1;
            indirect_launch(p, &grid);
            unsigned sync = next_sync - 1, handle = indirect_source_handle;
            assert(jobs[sync].live && bos[handle].jobs && buffer_jobs[sync].input->with_indirect);
            memcpy(bos[handle].data + grid.indirect_offset, one, 12);
            pipe_resource_reference(&source, NULL);
            assert(!bos[handle].handle && bos[handle].data && bos[handle].jobs);
            wait_error = 0; p->memory_barrier(p, PIPE_BARRIER_ALL); wait_error_kind = 0;
            assert(!jobs[sync].live && !bos[handle].jobs && !bos[handle].data);
            indirect_result(outputs, two, 0);
            source = new_buffer(s, 8192); grid.indirect = source;
            indirect_counts(source, grid.indirect_offset, two);
            handle = indirect_source_handle;
            indirect_clear(outputs); indirect_launch(p, &grid); indirect_result(outputs, two, 0);
            /* An original ESSL producer writes a count triplet across a page
             * boundary; this is CPU compiler/publication evidence only. */
            assert(snprintf(path, sizeof(path), "%s/indirect-producer.comp", directory) > 0);
            assert(snprintf(dump, sizeof(dump), "%s/indirect-producer", directory) > 0);
            void *producer = cs_frontend_compile_buffers(path, dump); assert(producer);
            struct pipe_compute_state prod_state = {.ir_type = PIPE_SHADER_IR_TGSI, .prog = cs_frontend_tokens(producer)};
            void *prod_cs = p->create_compute_state(p, &prod_state); assert(prod_cs);
            struct pipe_resource *targets[4] = {outputs[0], outputs[1], source, outputs[3]};
            p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, NULL, 0);
            cs_frontend_storage(producer, p, targets); p->bind_compute_state(p, prod_cs);
            memset(bos[handle].data, 0x6b, 8192);
            struct pipe_grid_info direct = {.work_dim = 1, .block = {4, 1, 1}, .grid = {1, 1, 1}};
            unsigned published = invalidates; p->launch_grid(p, &direct);
            p->set_shader_buffers(p, PIPE_SHADER_COMPUTE, 0, 4, NULL, 0);
            cs_frontend_storage(front, p, outputs); p->bind_compute_state(p, cs);
            indirect_clear(outputs); indirect_launch(p, &grid); indirect_result(outputs, two, 0);
            assert(invalidates > published);
            for (unsigned i = 0; i < 8192 / 4; i++)
                assert(((const uint32_t *)bos[handle].data)[i] == (i >= 1023 && i <= 1025 ? 2u : UINT32_C(0x6b6b6b6b)));
            p->delete_compute_state(p, prod_cs); cs_frontend_destroy(producer);
        }
        p->memory_barrier(p, PIPE_BARRIER_ALL); p->delete_compute_state(p, cs);
        cs_frontend_destroy(front); p->destroy(p);
        for (unsigned i = 0; i < 4; i++) pipe_resource_reference(&outputs[i], NULL);
        pipe_resource_reference(&source, NULL); pipe_resource_reference(&ubo, NULL);
        pipe_resource_reference(&counter, NULL); s->destroy(s); no_leaks();
    }
    assert(indirect_observations == indirect_submissions);
    indirect_observer = NULL; indirect_program = NULL;
    printf("INDIRECT_CPU_PASS programs=4 submissions=%u originalSource=true privateCounts=true "
        "threeDimensional=true protectedSourceMap=true unchangedKernarg=true logicalBounds=true "
        "zeroAndLargeGrids=true uniformAndCounter=true failureReuse=true noGPU=true\n", indirect_submissions);
}
